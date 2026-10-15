// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Monad
import cats.effect.{Async, Ref, Resource, Temporal}
import cats.effect.std.Mutex
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.{ChainSync, Era}
import lab.node.consensus.{ChainSelection, SelectView}
import lab.node.storage.ChainPersistence
import scala.concurrent.duration.*

/** Select views of the blocks of the volatile chain, by block hash. Chain selection compares the
  * tip of a candidate with ours, and the volatile chain itself keeps only identities and ledger
  * states, so the views are recorded here as blocks are adopted.
  */
final class SelectViews[F[_]: cats.effect.Sync] private (ref: Ref[F, Map[Bytes, SelectView]]):
  def get: F[Map[Bytes, SelectView]] = ref.get
  def record(hash: Bytes, view: SelectView): F[Unit] = ref.update(_.updated(hash, view))
  def retain(keep: Set[Bytes]): F[Unit] = ref.update(_.filter((h, _) => keep(h)))

object SelectViews:
  def create[F[_]: cats.effect.Sync]: F[SelectViews[F]] = create(Map.empty)

  /** Starting from the views of a volatile chain that was restored from disk. */
  def create[F[_]: cats.effect.Sync](initial: Map[Bytes, SelectView]): F[SelectViews[F]] =
    Ref.of[F, Map[Bytes, SelectView]](initial).map(new SelectViews(_))

/** Follows upstream peers. Each peer session keeps a candidate chain (the headers the peer
  * announced after the point where it joined ours); when a candidate is preferred to our chain by
  * the Praos chain selection rule ([[ChainSelection]]) and the switch rolls back at most `k`
  * blocks, the blocks are fetched from that peer, validated through [[BlockRules]] on a copy of the
  * chain and, if all of them apply, adopted at once. A peer that rolls back or forks without being
  * preferred changes nothing. Any number of sessions may run concurrently: adoption is serialised.
  *
  * Chain state lives in `Stateful`; rule failures are raised as typed errors and are fatal (the
  * chain keeps the last valid block). Peer failures, including a peer whose chain does not connect,
  * are handled by [[Follower.supervise]].
  */
final class Follower[F[_]: Async, S] private (
    rules: BlockRules[F, S],
    events: FollowerEvents[F],
    epochOf: (S, BigInt) => BigInt,
    /** Extra events derived from a state transition (era changes, epoch summaries). */
    observe: (S, S, BlockRef) => List[FollowerEvent],
    /** Receives every applied block and rollback so the immutable part can be stored. */
    persistence: Option[ChainPersistence[F, S]],
    /** Called with the chain after every change of the tip and the raw blocks that were added
      * (mempool revalidation, for one).
      */
    onTip: Option[(VolatileChain[S], Vector[Bytes]) => F[Unit]],
    tiebreaker: Era => ChainSelection.Tiebreaker,
    views: SelectViews[F],
    lock: Mutex[F],
    /** Where a block forged by this node is applied (the CPU pool, see [[Compute]]). */
    cpu: Compute[F]
)(using
    chain: Stateful[F, VolatileChain[S]],
    raise: Raise[F, ChainError]
):
  private val F = Async[F]

  private def protocol[A](reason: String)(using Raise[F, PeerError]): F[A] =
    PeerError.Protocol(reason).raise[F, A]

  /** Run one peer session until the peer fails (a raised [[PeerError]]) or forever. */
  def session(peer: Peer[F])(using Raise[F, PeerError]): F[Unit] =
    for
      current <- chain.get
      found <- peer.intersect(current.candidates)
      point <- found.fold(PeerError.NoIntersection.raise[F, ChainSync.Point])(_.pure[F])
      _ <- events.emit(FollowerEvent.Connected(point))
      candidate <- Ref.of[F, ChainSelection.Candidate](
        ChainSelection.Candidate(point, Vector.empty)
      )
      _ <- Monad[F].foreverM(peer.next.flatMap(step(peer, candidate, _)))
    yield ()

  private def step(
      peer: Peer[F],
      candidate: Ref[F, ChainSelection.Candidate],
      event: SyncEvent
  )(using Raise[F, PeerError]): F[Unit] = event match
    case SyncEvent.RollBackward(point, _) =>
      candidate.get.flatMap { c =>
        c.rollback(point) match
          case Some(rolled) => candidate.set(rolled)
          case None         =>
            // Before the join point: the peer's chain below it is ours as long as it is retained.
            lock.lock.surround(local).flatMap { l =>
              if l.contains(point) then candidate.set(ChainSelection.Candidate(point, Vector.empty))
              else protocol(s"peer rolled back to ${Points.show(point)}, outside our chain")
            }
      }
    case SyncEvent.RollForward(header, tip) =>
      candidate.get.flatMap { c =>
        val extended =
          if c.headers.isEmpty && c.base == ChainSync.Point.Origin then
            Some(c.copy(headers = Vector(header)))
          else c.extend(header)
        extended match
          case None =>
            protocol(s"header ${Points.show(header.point)} does not extend the peer's chain")
          case Some(grown) =>
            candidate.set(grown) *> adopt(peer, grown, tip).flatMap(candidate.set)
      }

  /** Our chain as chain selection sees it. */
  private def local: F[ChainSelection.Local] =
    for
      current <- chain.get
      known <- views.get
    yield
      def view(ref: BlockRef): SelectView =
        known.getOrElse(ref.hash, SelectView(ref.blockNo, ref.slot, None, 0, None))
      ChainSelection.Local(
        current.anchor.point,
        current.anchor.block.fold(SelectView(-1, 0, None, 0, None))(view),
        current.blocks.map((r, _) => ChainSelection.LocalBlock(r.point, view(r)))
      )

  private final case class Applied(
      ref: BlockRef,
      raw: Bytes,
      immutable: Vector[BlockRef],
      after: VolatileChain[S],
      before: VolatileChain[S],
      state: S,
      view: SelectView,
      era: Era
  )

  /** Switch to the candidate if chain selection prefers it; returns the candidate to keep. */
  private def adopt(
      peer: Peer[F],
      candidate: ChainSelection.Candidate,
      peerTip: ChainSync.Tip
  )(using Raise[F, PeerError]): F[ChainSelection.Candidate] =
    lock.lock.surround {
      for
        l <- local
        result <- ChainSelection.decide(tiebreaker(candidate.headers.last.era), l, candidate) match
          case ChainSelection.Decision.Keep => F.pure(candidate)
          case ChainSelection.Decision.Detached =>
            protocol(s"the peer's chain no longer joins ours at ${Points.show(candidate.base)}")
          case ChainSelection.Decision.Switch(to, depth, take) =>
            switchTo(peer, to, depth, take, peerTip).as(candidate.rebase(take.last.point))
      yield result
    }

  private def switchTo(
      peer: Peer[F],
      to: ChainSync.Point,
      depth: Int,
      take: Vector[Header],
      peerTip: ChainSync.Tip
  ): F[Unit] =
    for
      original <- chain.get
      rolled <- original.rollback(to).fold(_.raise[F, VolatileChain[S]], _.pure[F])
      steps <- take
        .foldLeftM((rolled, Vector.empty[Applied])) { case ((before, done), header) =>
          for
            block <- peer.fetch(header)
            next <- rules(before.state, header, block)
            extended <- before
              .extend(header, next)
              .fold(_.raise[F, (VolatileChain[S], Vector[BlockRef])], _.pure[F])
            ref = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
          yield (
            extended._1,
            done :+ Applied(
              ref,
              block,
              extended._2,
              extended._1,
              before,
              next,
              SelectView.of(header),
              header.era
            )
          )
        }
        .map(_._2)
      _ <- commit(original, to, depth, steps, peerTip)
    yield ()

  /** Everything applied: replace the chain, persist, announce and tell the mempool. */
  private def commit(
      original: VolatileChain[S],
      to: ChainSync.Point,
      depth: Int,
      steps: Vector[Applied],
      peerTip: ChainSync.Tip
  ): F[Unit] =
    for
      _ <- chain.set(steps.last.after)
      _ <- persistence.traverse_(_.rolledBack(to)).whenA(depth > 0)
      _ <- events
        .emit(FollowerEvent.RolledBack(original.tip, to, depth))
        .whenA(depth > 0)
      _ <- steps.traverse_ { a =>
        for
          _ <- persistence.traverse_(_.extended(a.ref, a.raw, a.immutable, a.after.anchor))
          _ <- views.record(a.ref.hash, a.view)
          epoch = epochOf(a.state, a.ref.slot)
          _ <- events.emit(FollowerEvent.Extended(a.ref, epoch, peerTip, a.era))
          enteredEpoch = a.before.tipRef.forall(t => epochOf(a.state, t.slot) < epoch)
          _ <- events.emit(FollowerEvent.EpochEntered(epoch, a.ref)).whenA(enteredEpoch)
          _ <- observe(a.before.state, a.state, a.ref).traverse_(events.emit)
          _ <- a.immutable.traverse_(b => events.emit(FollowerEvent.Immutable(b)))
        yield ()
      }
      _ <- views.retain(
        steps.last.after.blocks.map(_._1.hash).toSet ++ steps.last.after.anchor.block.map(_.hash)
      )
      _ <- onTip.traverse_(_(steps.last.after, steps.map(_.raw)))
    yield ()

  /** Adopt a block this node has just forged. It goes through the node's block rules like any other
    * block. If it extends our tip it is adopted. If the tip has moved on (a block of the same
    * height arrived first: a slot battle) chain selection decides as it does for a peer's chain:
    * the block replaces the tip only when it is strictly preferred (the VRF tie-break), otherwise
    * it is `Lost`. A block the rules refuse is `Invalid`. The chain is untouched unless the block
    * is `Adopted`.
    */
  def adoptForged(header: Header, raw: Bytes)(using
      handle: Handle[F, NodeError]
  ): F[Follower.Adoption] =
    lock.lock.surround {
      for
        before <- chain.get
        l <- local
        known = before.blocks.map(_._1) ++ before.anchor.block
        // where the block would attach, and how many blocks of ours that replaces
        plan: Either[String, (ChainSync.Point, Int)] =
          if before.tipRef.map(_.hash) == header.prevHash then Right((before.tip, 0))
          else
            header.prevHash.fold(Option(ChainSync.Point.Origin: ChainSync.Point))(h =>
              known.find(_.hash == h).map(_.point)
            ) match
              case None =>
                Left(
                  s"the tip moved to ${Points.show(before.tip)} and the block's predecessor is not on our chain"
                )
              case Some(base) =>
                ChainSelection.decide(
                  tiebreaker(header.era),
                  l,
                  ChainSelection.Candidate(base, Vector(header))
                ) match
                  case ChainSelection.Decision.Switch(to, depth, _) => Right((to, depth))
                  case _ =>
                    Left(
                      s"the tip moved to ${Points.show(before.tip)}, which chain selection prefers to the block"
                    )
        result <- plan match
          case Left(reason) => F.pure(Follower.Adoption.Lost(reason))
          case Right((at, depth)) =>
            for
              rolled <- before.rollback(at).fold(_.raise[F, VolatileChain[S]], _.pure[F])
              outcome <- handle.attempt(cpu.within(rules(rolled.state, header, raw))).flatMap {
                case Left(e: ValidationError) => F.pure(Follower.Adoption.Invalid(e).asLeft[S])
                case Left(other)              => other.raise[F, Either[Follower.Adoption, S]]
                case Right(next)              => F.pure(next.asRight[Follower.Adoption])
              }
              adopted <- outcome match
                case Left(refused) => F.pure(refused)
                case Right(next) =>
                  val ref = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
                  for
                    extended <- rolled
                      .extend(header, next)
                      .fold(_.raise[F, (VolatileChain[S], Vector[BlockRef])], _.pure[F])
                    applied = Applied(
                      ref,
                      raw,
                      extended._2,
                      extended._1,
                      rolled,
                      next,
                      SelectView.of(header),
                      header.era
                    )
                    tip = ChainSync.Tip(
                      header.point,
                      ChainSync.UInt64.saturating(header.blockNo)
                    )
                    _ <- commit(before, at, depth, Vector(applied), tip)
                  yield Follower.Adoption.Adopted(ref)
            yield adopted
      yield result
    }

object Follower:
  /** What happened to a forged block offered to [[Follower.adoptForged]]. */
  enum Adoption:
    case Adopted(block: BlockRef)
    case Lost(reason: String)
    case Invalid(error: ValidationError)

  final case class Retry(maxAttempts: Int, delay: FiniteDuration)

  def create[F[_]: Async, S](
      rules: BlockRules[F, S],
      events: FollowerEvents[F],
      geometry: EpochGeometry,
      observe: (S, S, BlockRef) => List[FollowerEvent] = (_: S, _: S, _: BlockRef) => Nil,
      persistence: Option[ChainPersistence[F, S]] = None,
      onTip: Option[(VolatileChain[S], Vector[Bytes]) => F[Unit]] = None,
      tiebreaker: Era => ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor,
      /** The epoch of a slot as the state sees it (a chain whose Byron span is derived learns it).
        */
      epochOf: Option[(S, BigInt) => BigInt] = None,
      /** The select views of the blocks of a volatile chain restored from disk. */
      initialViews: Map[Bytes, SelectView] = Map.empty,
      /** The CPU pool the blocks are applied on; without one, on the calling fiber's pool. */
      compute: Option[Compute[F]] = None
  )(using
      Stateful[F, VolatileChain[S]],
      Raise[F, ChainError]
  ): F[Follower[F, S]] =
    (SelectViews.create[F](initialViews), Mutex[F]).mapN { (views, lock) =>
      new Follower[F, S](
        rules,
        events,
        epochOf.getOrElse((_: S, slot: BigInt) => geometry.epochOf(slot)),
        observe,
        persistence,
        onTip,
        tiebreaker,
        views,
        lock,
        compute.getOrElse(Compute.inline[F])
      )
    }

  /** Run sessions from `connect`, reconnecting after each [[PeerError]] up to `retry.maxAttempts`
    * consecutive failures. A session that applied at least one block resets the count.
    */
  def supervise[F[_]: Temporal, S](
      follower: Follower[F, S],
      connect: Resource[F, Peer[F]],
      events: FollowerEvents[F],
      retry: Retry,
      /** Runs the session (header handling, chain selection, applying and persisting blocks) on the
        * CPU pool, while the connection itself (multiplexer, KeepAlive, the pipelined fetch) stays
        * on the pool it was opened on, so a long step cannot hold up their timers.
        */
      compute: Option[Compute[F]] = None
  )(using chain: Stateful[F, VolatileChain[S]], handle: Handle[F, PeerError]): F[Unit] =
    def loop(attempt: Int): F[Unit] =
      chain.get.map(_.tip).flatMap { before =>
        connect
          .use(peer => compute.fold(follower.session(peer))(_.within(follower.session(peer))))
          .handleWith[PeerError] { error =>
            chain.get.map(_.tip).flatMap { after =>
              val next = if after != before then 1 else attempt + 1
              if next > retry.maxAttempts then error.raise[F, Unit]
              else
                events.emit(FollowerEvent.Reconnecting(error, next)) *> Temporal[F].sleep(
                  retry.delay
                ) *> loop(next)
            }
          }
      }
    loop(1)
