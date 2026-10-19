// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{
  BlockRef,
  BlockRules,
  Compute,
  FollowerEvent,
  FollowerEvents,
  VolatileChain
}
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  Header,
  NodeError,
  PeerError,
  Points,
  ValidationError
}
import cats.Monad
import cats.effect.{Async, Ref}
import cats.effect.std.Mutex
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.storage.ChainPersistence
import xyz.kd5ujc.scarab.network.selection.Gsm

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
    onTip: Option[
      (VolatileChain[S], Vector[Bytes]) => Raise[F, xyz.kd5ujc.scarab.ledger.StorageError] ?=> F[
        Unit
      ]
    ],
    tiebreaker: Era => ChainSelection.Tiebreaker,
    views: SelectViews[F],
    lock: Mutex[F],
    /** Where a block forged by this node is applied (the CPU pool, see [[Compute]]). */
    cpu: Compute[F],
    /** The window chain selection reads, kept in step with the chain by [[commit]]. */
    window: Ref[F, Option[ChainSelection.Local]]
)(using
    chain: Stateful[F, VolatileChain[S]]
):
  private val F = Async[F]

  private def protocol[A](reason: PeerError.ProtocolFailure)(using Raise[F, PeerError]): F[A] =
    PeerError.Protocol(reason).raise[F, A]

  private def diverged[A](reason: PeerError.Divergence)(using Raise[F, PeerError]): F[A] =
    PeerError.Diverged(reason).raise[F, A]

  /** Run one peer session until the peer fails (a raised [[PeerError]]) or forever. `track` is
    * handed the session's candidate chain once it exists, for whoever compares it with the
    * selection ([[versus]]).
    */
  def session(
      peer: Peer[F],
      track: Ref[F, ChainSelection.Candidate] => F[Unit] = _ => Async[F].unit
  )(using Raise[F, NodeError]): F[Unit] =
    for
      current <- chain.get
      found <- peer.intersect(current.candidates)
      point <- found.fold(PeerError.NoIntersection.raise[F, ChainSync.Point])(_.pure[F])
      _ <- events.emit(FollowerEvent.Connected(point))
      candidate <- Ref.of[F, ChainSelection.Candidate](
        ChainSelection.Candidate(point, Vector.empty)
      )
      _ <- track(candidate)
      _ <- Monad[F].foreverM(peer.next.flatMap(step(peer, candidate, _)))
    yield ()

  /** How a peer's candidate chain compares with our selection (`CandidateVersusSelection`, GSM.hs
    * 88-98): whether chain selection would switch to it, or that it does not join ours. A candidate
    * that announced nothing yet is not preferred.
    */
  def versus(candidate: ChainSelection.Candidate): F[Gsm.Candidate] =
    if candidate.headers.isEmpty then F.pure(Gsm.Candidate.Better(false))
    else
      lock.lock.surround(local).map { l =>
        ChainSelection.decide(tiebreaker(candidate.headers.last.era), l, candidate) match
          case ChainSelection.Decision.Keep            => Gsm.Candidate.Better(false)
          case ChainSelection.Decision.Detached        => Gsm.Candidate.DoesNotIntersect
          case ChainSelection.Decision.Switch(_, _, _) => Gsm.Candidate.Better(true)
      }

  private def step(
      peer: Peer[F],
      candidate: Ref[F, ChainSelection.Candidate],
      event: SyncEvent
  )(using Raise[F, NodeError]): F[Unit] = event match
    case SyncEvent.RollBackward(point, _) =>
      candidate.get.flatMap { c =>
        c.rollback(point) match
          case Some(rolled) => candidate.set(rolled)
          case None         =>
            // Before the join point: the peer's chain below it is ours as long as it is retained.
            lock.lock.surround(local).flatMap { l =>
              if l.contains(point) then candidate.set(ChainSelection.Candidate(point, Vector.empty))
              else diverged(PeerError.Divergence.RollbackOutsideChain(point))
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
            protocol(PeerError.ProtocolProblem.HeaderDoesNotExtend(header.point, c.tip))
          case Some(grown) =>
            candidate.set(grown) *> adopt(peer, grown, tip).flatMap(candidate.set)
      }

  /** Our chain as chain selection sees it. The window is kept between calls and updated by
    * [[commit]] (an append and a truncation per change, not the `k` blocks again for every header);
    * it is rebuilt when it is missing or no longer describes the chain (a restored chain, a state
    * set from outside).
    */
  private def local: F[ChainSelection.Local] =
    for
      current <- chain.get
      cached <- window.get
      result <- cached.filter(describes(_, current)) match
        case Some(l) => F.pure(l)
        case None =>
          views.get.flatMap { known =>
            val rebuilt = ChainSelection.Local.of(current, viewOf(known))
            window.set(Some(rebuilt)).as(rebuilt)
          }
    yield result

  /** The select view of a block; a block without a recorded one gets its position only. */
  private def viewOf(known: Map[Bytes, SelectView])(ref: BlockRef): SelectView =
    known.getOrElse(ref.hash, SelectView(ref.blockNo, ref.slot, None, 0, None))

  /** A cheap check that the kept window is the chain's: anchor, length and tip. */
  private def describes(l: ChainSelection.Local, current: VolatileChain[S]): Boolean =
    l.anchor == current.anchor.point && l.blocks.size == current.blocks.size &&
      l.blocks.lastOption.forall(b => current.blocks.lastOption.exists(_._1.point == b.point))

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
  )(using Raise[F, NodeError]): F[ChainSelection.Candidate] =
    lock.lock.surround {
      for
        l <- local
        result <- ChainSelection.decide(tiebreaker(candidate.headers.last.era), l, candidate) match
          case ChainSelection.Decision.Keep => F.pure(candidate)
          case ChainSelection.Decision.Detached =>
            diverged(PeerError.Divergence.DetachedCandidate(candidate.base))
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
  )(using Raise[F, NodeError]): F[Unit] =
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

  /** Everything applied: persist, replace the chain, announce and tell the mempool.
    *
    * The order is the point. The blocks are persisted before the new chain is published, as the
    * reference's ChainDB writes a block to its VolatileDB before chain selection moves the tip
    * (`Storage/ChainDB/Impl/ChainSel.hs` of ouroboros-consensus main, line 455
    * `VolatileDB.putBlock`, line 932 `writeTVar cdbChain`): a failure of the storage leaves the
    * chain that was published as it was, and what is on disk is a prefix of the new chain, which
    * the next start reads. A failure after the chain is published (announcing it, telling the
    * mempool) finds the chain and the disk in step. In neither case does a published tip outrun
    * what is stored; the node stops on a storage failure (issue 154: `FailurePolicy.disposition`,
    * as the reference's `consensusRethrowPolicy` does) and recovers from disk.
    */
  private def commit(
      original: VolatileChain[S],
      to: ChainSync.Point,
      depth: Int,
      steps: Vector[Applied],
      peerTip: ChainSync.Tip
  )(using Raise[F, NodeError]): F[Unit] =
    for
      _ <- persistence.traverse_(_.rolledBack(to)).whenA(depth > 0)
      _ <- steps.traverse_(a =>
        persistence.traverse_(_.extended(a.ref, a.raw, a.immutable, a.after.anchor))
      )
      _ <- chain.set(steps.last.after)
      _ <- window.update(
        _.flatMap(l =>
          steps.foldLeft(if depth > 0 then l.rolledBackTo(to) else Some(l))((acc, a) =>
            acc.map(_.extended(ChainSelection.LocalBlock(a.ref.point, a.view), a.immutable.size))
          )
        )
      )
      _ <- events
        .emit(FollowerEvent.RolledBack(original.tip, to, depth))
        .whenA(depth > 0)
      _ <- steps.traverse_ { a =>
        for
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
        plan: Either[Follower.AdoptionLoss, (ChainSync.Point, Int)] =
          if before.tipRef.map(_.hash) == header.prevHash then Right((before.tip, 0))
          else
            header.prevHash.fold(Option(ChainSync.Point.Origin: ChainSync.Point))(h =>
              known.find(_.hash == h).map(_.point)
            ) match
              case None =>
                Left(
                  Follower.AdoptionLoss.DetachedPredecessor(before.tip, header.prevHash)
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
                      Follower.AdoptionLoss.SelectionPrefersTip(before.tip)
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
  enum AdoptionLoss:
    case DetachedPredecessor(tip: ChainSync.Point, predecessor: Option[Bytes])
    case SelectionPrefersTip(tip: ChainSync.Point)
    def message: String = this match
      case DetachedPredecessor(tip, _) =>
        s"the tip moved to ${Points.show(tip)} and the block's predecessor is not on our chain"
      case SelectionPrefersTip(tip) =>
        s"the tip moved to ${Points.show(tip)}, which chain selection prefers to the block"

  enum Adoption:
    case Adopted(block: BlockRef)
    case Lost(reason: Follower.AdoptionLoss)
    case Invalid(error: ValidationError)

  def create[F[_]: Async, S](
      rules: BlockRules[F, S],
      events: FollowerEvents[F],
      geometry: EpochGeometry,
      observe: (S, S, BlockRef) => List[FollowerEvent] = (_: S, _: S, _: BlockRef) => Nil,
      persistence: Option[ChainPersistence[F, S]] = None,
      onTip: Option[
        (VolatileChain[S], Vector[Bytes]) => Raise[F, xyz.kd5ujc.scarab.ledger.StorageError] ?=> F[
          Unit
        ]
      ] = None,
      tiebreaker: Era => ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor,
      /** The epoch of a slot as the state sees it (a chain whose Byron span is derived learns it).
        */
      epochOf: Option[(S, BigInt) => BigInt] = None,
      /** The select views of the blocks of a volatile chain restored from disk. */
      initialViews: Map[Bytes, SelectView] = Map.empty,
      /** The CPU pool the blocks are applied on; without one, on the calling fiber's pool. */
      compute: Option[Compute[F]] = None,
      /** Shared with the LSQ point/store capture; held only while a view is selected. */
      chainGate: Option[Mutex[F]] = None
  )(using
      Stateful[F, VolatileChain[S]]
  ): F[Follower[F, S]] =
    (
      SelectViews.create[F](initialViews),
      chainGate.fold(Mutex[F])(_.pure[F]),
      Ref.of[F, Option[ChainSelection.Local]](None)
    ).mapN { (views, lock, window) =>
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
        compute.getOrElse(Compute.inline[F]),
        window
      )
    }
