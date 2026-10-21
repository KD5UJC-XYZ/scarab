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
  BlockPoolProblem,
  Header,
  NodeError,
  PeerError,
  Points,
  ValidationError
}
import cats.Monad
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.storage.ChainPersistence
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment

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
  * Chain state lives in `Stateful`; failures retain their typed causes and leave the published
  * chain at the last committed state. The session owner classifies the outcome through
  * [[SessionEnd]] and [[FailurePolicy.disposition]], distinguishing an invalid peer block from a
  * storage failure or an internal defect.
  *
  * @param observe
  *   Extra events derived from a state transition (era changes, epoch summaries).
  * @param persistence
  *   Receives every applied block and rollback so the immutable part can be stored.
  * @param onTip
  *   Called with the chain after every change of the tip and the raw blocks that were added
  *   (mempool revalidation, for one).
  * @param cpu
  *   Where a block forged by this node is applied (the CPU pool, see [[Compute]]).
  * @param window
  *   The window chain selection reads, kept in step with the chain by [[commit]].
  */
final class Follower[F[_]: Async, S] private (
    rules: BlockRules[F, S],
    events: FollowerEvents[F],
    epochOf: (S, BigInt) => BigInt,
    observe: (S, S, BlockRef) => List[FollowerEvent],
    persistence: Option[ChainPersistence[F, S]],
    onTip: Option[Follower.OnTip[F, S]],
    tiebreaker: Era => ChainSelection.Tiebreaker,
    views: SelectViews[F],
    lock: Mutex[F],
    cpu: Compute[F],
    window: Ref[F, Follower.Committed[S]],
    genesis: Ref[F, Option[GenesisGovernor[F]]],
    selectionChanged: Ref[F, F[Unit]],
    invalidBlocks: Ref[F, Option[InvalidBlocks[F]]]
)(using
    chain: Stateful[F, VolatileChain[S]]
):
  private val F = Async[F]

  /** Installed once by the node before any peer sessions are started. */
  private[node] def installGenesis(governor: GenesisGovernor[F]): F[Unit] =
    genesis.set(Some(governor))

  private[node] def installFetchCoordinator(coordinator: FetchCoordinator[F]): F[Unit] =
    selectionChanged.set(coordinator.refreshGenesis)

  /** Installed before sessions start: every body producer and chain selector shares this map. */
  private[node] def installInvalidBlocks(registry: InvalidBlocks[F]): F[Unit] =
    invalidBlocks.set(Some(registry))

  /** The node owns the bounded add-block runner, independently of every announcing session. */
  private[node] def blockSelection(
      pool: BlockPool[F],
      work: SelectionWork[F],
      fatal: Fatal => F[Unit],
      originPredecessor: Option[Bytes] = None
  )(using BlockSelection.Before[F], Raise[F, NodeError]): Resource[F, Unit] =
    val application = new BlockSelection.Application[F]:
      def local = fetchSelection
      def admits(header: Header) = chain.get.map(
        _.anchor.block.forall(anchor =>
          header.blockNo > anchor.blockNo || (header.isBoundary && header.blockNo == anchor.blockNo)
        )
      )
      def reset(using Raise[F, NodeError]): F[Unit] =
        for
          current <- chain.get
          _ <- (for
            _ <- persistence.traverse_(_.rolledBack(current.anchor.point))
            cached <- window.get
            anchored = current.copy(blocks = Vector.empty)
            _ <- F.uncancelable(_ =>
              chain.set(anchored) *>
                window.set(
                  Follower.Committed(anchored, cached.selection.copy(blocks = Vector.empty))
                )
            )
            _ <- selectionChanged.get.flatten
            _ <- onTip.traverse_(_(anchored, Vector.empty))
          yield ()).whenA(current.blocks.nonEmpty)
        yield ()
      def governor = genesis.get
      def locked[A](body: F[A]): F[A] = lock.lock.surround(body)
      def project(
          candidates: Vector[ChainSelection.Candidate]
      ): F[Vector[ChainSelection.Candidate]] =
        for
          current <- chain.get
          selected <- local
          control <- genesis.get
          fragment = Fragment(
            selected.anchor,
            selected.blocks.map(_.point).collect { case block: ChainSync.Point.Block =>
              block
            }
          )
          limit <- control.traverse(_.limitAt(fragment)).map(_.flatten)
        yield CandidateSelection.project(current.k, selected, candidates, limit, tiebreaker)
      def apply(peer: Peer[F], candidate: ChainSelection.Candidate, validation: Peer.Validation[F])(
          using Raise[F, NodeError]
      ): F[Unit] =
        local.flatMap { selected =>
          ChainSelection.decide(tiebreaker(candidate.headers.last.era), selected, candidate) match
            case ChainSelection.Decision.Switch(to, depth, take) =>
              val causal =
                if validation.addedPoint.exists(point => take.exists(_.point == point)) then
                  validation
                else Peer.Validation.none[F]
              switchTo(peer, to, depth, take, ChainSync.Tip.Origin, pool.invalidBlocks, causal).void
            case _ => BlockPoolProblem.SelectionProjectionChanged(candidate.tip).raise[F, Unit]
        }
    BlockSelection.resource(pool, work, fatal, application, originPredecessor)

  private def registryFor(peer: Peer[F])(using Raise[F, NodeError]): F[InvalidBlocks[F]] =
    for
      proposed <- peer.invalidBlocks.fold(InvalidBlocks.create[F])(F.pure)
      selected <- invalidBlocks.modify {
        case None                     => Some(proposed) -> proposed
        case current @ Some(existing) => current -> existing
      }
      _ <- BlockPoolProblem.DifferentInvalidRegistry
        .raise[F, Unit]
        .whenA(peer.invalidBlocks.exists(_ ne selected))
    yield selected

  private def protocol[A](reason: PeerError.ProtocolFailure)(using Raise[F, PeerError]): F[A] =
    PeerError.Protocol(reason).raise[F, A]

  private def diverged[A](reason: PeerError.Divergence)(using Raise[F, PeerError]): F[A] =
    PeerError.Diverged(reason).raise[F, A]

  private[node] def genesisView(
      windowAt: (S, BigInt, BigInt) => Option[BigInt]
  ): F[GenesisGovernor.View] = window.get.map { snapshot =>
    val current = snapshot.chain
    val tipSlot = current.anchor.block.fold(BigInt(-1))(_.slot)
    GenesisGovernor.View(
      snapshot.fragment,
      current.k,
      slot => windowAt(current.anchor.state, tipSlot, slot)
    )
  }

  /** Run one peer session until the peer fails (a raised [[PeerError]]) or forever. `track` is
    * handed the session's candidate chain once it exists, for whoever compares it with the
    * selection ([[versus]]).
    */
  def session(
      peer: Peer[F],
      track: Ref[F, ChainSelection.Candidate] => F[Unit] = _ => Async[F].unit
  )(using Raise[F, NodeError]): F[Unit] =
    for
      registry <- registryFor(peer)
      initial <- intersect(peer)
      candidate <- Ref.of[F, ChainSelection.Candidate](initial)
      _ <- track(candidate)
      control <- genesis.get
      _ <- control.fold(
        Monad[F].foreverM(
          peer.next.flatMap(event => step(peer, candidate, event, intersect(peer), registry))
        )
      ) { governor =>
        SessionInput.resource(peer).use { input =>
          for
            serial <- Mutex[F]
            lastTip <- Ref.of[F, ChainSync.Tip](ChainSync.Tip.Origin)
            consume = Monad[F].foreverM(input.next.flatMap { read =>
              serial.lock.surround {
                input.current(read).flatMap { current =>
                  if !current then F.unit
                  else
                    val tip = read.event match
                      case SyncEvent.RollForward(_, value)  => value
                      case SyncEvent.RollBackward(_, value) => value
                    lastTip.set(tip) *> step(
                      peer,
                      candidate,
                      read.event,
                      input.reintersect(intersect(peer)),
                      registry
                    )
                }
              }
            })
            reselect = registry.selectionActive.flatMap {
              case true => F.never[Unit]
              case false =>
                F.tailRecM[Long, Unit](-1L) { previous =>
                  governor.selectionChanged(previous).flatMap { revision =>
                    serial.lock
                      .surround {
                        (candidate.get, lastTip.get).tupled.flatMap { (known, tip) =>
                          if known.headers.isEmpty then F.unit
                          else
                            registry.selectionActive
                              .flatMap {
                                case true  => considered(peer, known).as(Some(known))
                                case false => adopt(peer, known, tip, registry)
                              }
                              .flatMap {
                                case Some(kept) => candidate.set(kept)
                                case None =>
                                  input.reintersect(intersect(peer)).flatMap(candidate.set)
                              }
                        }
                      }
                      .as(Left(revision))
                  }
                }
            }
            _ <- F.both(consume, reselect).void
          yield ()
        }
      }
    yield ()

  private def intersect(peer: Peer[F])(using Raise[F, NodeError]): F[ChainSelection.Candidate] =
    for
      current <- chain.get
      found <- peer.intersect(current.candidates)
      point <- found.fold(PeerError.NoIntersection.raise[F, ChainSync.Point])(_.pure[F])
      _ <- events.emit(FollowerEvent.Connected(point))
    yield ChainSelection.Candidate(point, Vector.empty)

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

  /** GSM compares the full captured candidate, including the validated local prefix before its
    * original intersection. It must not apply LoE to this preference comparison.
    */
  private[node] def versusHeaders(
      history: xyz.kd5ujc.scarab.consensus.HeaderChain[NodeHeaderState]
  ): F[Gsm.Candidate] =
    val candidateView = history.entries.lastOption
      .map(e =>
        xyz.kd5ujc.scarab.consensus.HeaderChain
          .TipView(SelectView.of(e.header), tiebreaker(e.header.era))
      )
      .orElse(history.baseView)
    candidateView.fold(F.pure[Gsm.Candidate](Gsm.Candidate.Better(false))) { tip =>
      window.get.map { snapshot =>
        val points = history.entries.reverseIterator.map(_.header.point) ++
          Iterator.single(history.base) ++ history.prefix.reverseIterator.map(_.point)
        if !points.exists(snapshot.points) then Gsm.Candidate.DoesNotIntersect
        else
          Gsm.Candidate.Better(
            ChainSelection.prefersCandidate(tip.tiebreaker, snapshot.selection.tipView, tip.value)
          )
      }
    }

  private def step(
      peer: Peer[F],
      candidate: Ref[F, ChainSelection.Candidate],
      event: SyncEvent,
      reintersect: F[ChainSelection.Candidate],
      registry: InvalidBlocks[F]
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
            registry.selectionActive.flatMap {
              case true => candidate.set(grown) *> considered(peer, grown)
              case false =>
                peer
                  .validation(header)
                  .flatMap(_.fold(_.raise[F, Peer.Validation[F]], F.pure))
                  .flatMap { validation =>
                    candidate.set(grown) *> adopt(peer, grown, tip, registry, validation).flatMap {
                      case Some(kept) => candidate.set(kept)
                      // NoLongerIntersects: drain the pipeline and find a new intersection on this
                      // connection (consensus 82ecba329, ChainSync/Client.hs:1499–1511). Another session
                      // may have advanced the retained window while this candidate was catching up.
                      case None => reintersect.flatMap(candidate.set)
                    }
                  }
            }
      }

  /** Our chain as chain selection sees it. The window is kept between calls and updated by
    * [[commit]] (an append and a truncation per change, not the `k` blocks again for every header);
    * it is rebuilt when it is missing or no longer describes the chain (a restored chain, a state
    * set from outside).
    */
  /** A committed selection snapshot for fetch decisions. This does not acquire the application
    * lock: an applying session may be waiting for a scheduled block while holding that lock.
    */
  private[node] def fetchSelection: F[ChainSelection.Local] = window.get.map(_.selection)

  private def local: F[ChainSelection.Local] =
    for
      current <- chain.get
      cached <- window.get
      result <-
        if describes(cached.selection, current) then F.pure(cached.selection)
        else
          views.get.flatMap { known =>
            val rebuilt = ChainSelection.Local.of(current, viewOf(known))
            window.set(Follower.Committed(current, rebuilt)).as(rebuilt)
          }
    yield result

  /** The select view of a block; a block without a recorded one gets its position only. */
  private def viewOf(known: Map[Bytes, SelectView])(ref: BlockRef): SelectView =
    known.getOrElse(ref.hash, SelectView(ref.blockNo, ref.slot, None, 0, None, ref.boundary))

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

  private final case class Refused(
      header: Header,
      error: ValidationError,
      before: VolatileChain[S],
      valid: Vector[Applied]
  )

  /** Switch if preferred; no candidate means its intersection left our retained window. */
  private def adopt(
      peer: Peer[F],
      candidate: ChainSelection.Candidate,
      peerTip: ChainSync.Tip,
      registry: InvalidBlocks[F],
      validation: Peer.Validation[F] = Peer.Validation.none[F]
  )(using Raise[F, NodeError]): F[Option[ChainSelection.Candidate]] =
    lock.lock.surround(adoptLocked(peer, candidate, peerTip, registry, validation))

  private def adoptLocked(
      peer: Peer[F],
      candidate: ChainSelection.Candidate,
      peerTip: ChainSync.Tip,
      registry: InvalidBlocks[F],
      validation: Peer.Validation[F]
  )(using Raise[F, NodeError]): F[Option[ChainSelection.Candidate]] = {
    for
      l <- local
      control <- genesis.get
      selected = Fragment(
        l.anchor,
        l.blocks.map(_.point).collect { case block: ChainSync.Point.Block =>
          block
        }
      )
      limit <- control.traverse(_.limitAt(selected)).map(_.flatten)
      current <- chain.get
      invalid <- registry.snapshot
      validPrefix = candidate.copy(headers =
        candidate.headers.takeWhile(h => !invalid.entries.contains(h.hash))
      )
      bounded = Follower.limitCandidate(current.k, l, validPrefix, limit)
      result <- bounded match
        case None => considered(peer, candidate).as(Some(candidate))
        case Some(eligible) =>
          ChainSelection.decide(
            tiebreaker(eligible.headers.lastOption.fold(candidate.headers.last.era)(_.era)),
            l,
            eligible
          ) match
            case ChainSelection.Decision.Keep =>
              considered(peer, candidate).as(Some(candidate.rebase(eligible.base)))
            case ChainSelection.Decision.Detached => considered(peer, candidate).as(None)
            case ChainSelection.Decision.Switch(to, depth, take) =>
              val causal =
                if validation.addedPoint.exists(point => take.exists(_.point == point)) then
                  validation
                else Peer.Validation.none[F]
              switchTo(peer, to, depth, take, peerTip, registry, causal).flatMap { committed =>
                considered(peer, candidate).as(Some(committed.fold(candidate)(candidate.rebase)))
              }
    yield result
  }

  private def considered(peer: Peer[F], candidate: ChainSelection.Candidate): F[Unit] =
    peer.selectionWork.traverse_(work =>
      candidate.headers.traverse_(h => work.processing(h.hash).use(_ => F.unit))
    )

  private def switchTo(
      peer: Peer[F],
      to: ChainSync.Point,
      depth: Int,
      take: Vector[Header],
      peerTip: ChainSync.Tip,
      registry: InvalidBlocks[F],
      validation: Peer.Validation[F]
  )(using Raise[F, NodeError]): F[Option[ChainSync.Point]] =
    for
      original <- chain.get
      rolled <- original.rollback(to).fold(_.raise[F, VolatileChain[S]], _.pure[F])
      causal <- Ref.of[F, Peer.Validation[F]](validation)
      outcome <- F
        .tailRecM[(VolatileChain[S], Vector[Applied], Int), Either[Refused, Vector[Applied]]](
          (rolled, Vector.empty, 0)
        ) { (before, done, index) =>
          take.lift(index) match
            case None => F.pure(Right(Right(done)))
            case Some(header) =>
              for
                block <- peer.fetch(header)
                previous <- causal.get
                _ <- peer
                  .validation(header)
                  .flatMap(_.fold(_.raise[F, Peer.Validation[F]], F.pure))
                  .flatMap(next =>
                    causal.update { current =>
                      if next.admissionOrder
                          .exists(order => current.admissionOrder.forall(_ < order))
                      then next
                      else current
                    }
                  )
                  .unlessA(previous.addedPoint.contains(header.point))
                checked = Handle
                  .allow[NodeError](
                    rules(before.state, header, block)
                      .map(state => Right(state): Either[ValidationError, S])
                  )
                  .rescue {
                    case refused: ValidationError => F.pure(Left(refused))
                    case other                    => other.raise[F, Either[ValidationError, S]]
                  }
                verdict <- peer.selectionWork.fold(checked)(work =>
                  work.processing(header.hash).surround(checked)
                )
                result <- verdict match
                  case Left(refused) => F.pure(Right(Left(Refused(header, refused, before, done))))
                  case Right(next) =>
                    before
                      .extend(header, next)
                      .fold(
                        _.raise[F, Either[
                          (VolatileChain[S], Vector[Applied], Int),
                          Either[Refused, Vector[Applied]]
                        ]],
                        extended =>
                          val ref =
                            BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
                          F.pure(
                            Left(
                              (
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
                                ),
                                index + 1
                              )
                            )
                          )
                      )
              yield result
        }
      committed <- outcome match
        case Right(steps)  => commit(original, to, depth, steps, peerTip).as(Some(take.last.point))
        case Left(refused) =>
          // Native validation records invalidity and punishes first (ChainSel.hs:1315–1344),
          // then reconsiders a preferred valid prefix (1147–1186). The ChainDB worker survives
          // sender-thread cancellation, so this local commit boundary must survive it too.
          F.uncancelable { _ =>
            for
              _ <- peer
                .invalidated(refused.header, refused.error)
                .flatMap(_.fold(_.raise[F, Unit], F.pure))
              _ <- registry.record(refused.header, refused.error).whenA(peer.invalidBlocks.isEmpty)
              adding <- causal.get
              decision <- adding
                .rejected(refused.error, refused.before.tip)
                .flatMap(_.fold(_.raise[F, Peer.Rejection], F.pure))
              localNow <- local
              prefix = take.take(refused.valid.size)
              preferred = prefix.lastOption.exists(h =>
                ChainSelection.prefersCandidate(
                  tiebreaker(h.era),
                  localNow.tipView,
                  SelectView.of(h)
                )
              )
              delegated <- registry.selectionActive
              _ <- commit(original, to, depth, refused.valid, peerTip).whenA(
                preferred && !delegated
              )
              _ <- refused.error.raise[F, Unit].whenA(decision == Peer.Rejection.PunishedCurrent)
            yield Option.when(preferred && !delegated)(refused.before.tip)
          }
    yield committed

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
      finalChain = steps.last.after
      cached <- window.get
      updated = steps.foldLeft(
        if depth > 0 then cached.selection.rolledBackTo(to) else Some(cached.selection)
      )((acc, a) =>
        acc.map(_.extended(ChainSelection.LocalBlock(a.ref.point, a.view), a.immutable.size))
      )
      selected <- updated.fold(
        views.get.map(known =>
          ChainSelection.Local.of(finalChain, viewOf(known ++ steps.map(a => a.ref.hash -> a.view)))
        )
      )(F.pure)
      _ <- F.uncancelable(_ =>
        chain.set(finalChain) *> window.set(Follower.Committed(finalChain, selected))
      )
      _ <- invalidBlocks.get.flatMap(
        _.traverse_(registry =>
          finalChain.anchor.block.traverse_(anchor => registry.pruneBefore(anchor.slot))
        )
      )
      _ <- selectionChanged.get.flatten
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
  trait OnTip[F[_], S]:
    def apply(chain: VolatileChain[S], raws: Vector[Bytes])(using Raise[F, NodeError]): F[Unit]

  object OnTip:
    def apply[F[_], S](
        run: (VolatileChain[S], Vector[Bytes]) => Raise[F, NodeError] ?=> F[Unit]
    ): OnTip[F, S] =
      new OnTip[F, S]:
        def apply(chain: VolatileChain[S], raws: Vector[Bytes])(using
            Raise[F, NodeError]
        ): F[Unit] =
          run(chain, raws)

  /** Publish chain state and its selection window atomically for GDD readers. Capturing the
    * immutable ledger and fragment never waits for a block fetch or a running ledger check.
    */
  private final case class Committed[S](chain: VolatileChain[S], selection: ChainSelection.Local):
    val points: Set[ChainSync.Point] =
      selection.blocks.iterator.map(_.point).toSet + selection.anchor
    val fragment: Fragment = Fragment(
      selection.anchor,
      selection.blocks.map(_.point).collect { case block: ChainSync.Point.Block => block }
    )

  /** Preserve the full candidate outside selection: advancing or disabling LoE must reconsider
    * headers already downloaded, without requiring another peer announcement.
    */
  private[node] def limitCandidate(
      k: Int,
      current: ChainSelection.Local,
      candidate: ChainSelection.Candidate,
      limit: Option[Fragment]
  ): Option[ChainSelection.Candidate] =
    val selected = Fragment(
      current.anchor,
      current.blocks.map(_.point).collect { case block: ChainSync.Point.Block =>
        block
      }
    )
    val localPoints = current.anchor +: current.blocks.map(_.point)
    val localPositions = localPoints.zipWithIndex.toMap
    val candidatePoints = candidate.base +: candidate.headers.map(_.point)
    val common = candidatePoints.lastIndexWhere(localPositions.contains)
    if common < 0 then Some(candidate)
    else
      val intersection = candidatePoints(common)
      val before =
        current.blocks.take(localPositions.getOrElse(intersection, 0)).map(_.point).collect {
          case block: ChainSync.Point.Block => block
        }
      val extension = candidate.headers.drop(common)
      val proposed = Fragment(
        current.anchor,
        before ++ extension.map(_.point).collect { case block: ChainSync.Point.Block =>
          block
        }
      )
      GenesisDensity.trim(k, selected, proposed, limit).toOption.map { allowed =>
        ChainSelection.Candidate(
          intersection,
          extension.take((allowed.headers.size - before.size).max(0))
        )
      }

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

  /** @param epochOf
    *   The epoch of a slot as the state sees it (a chain whose Byron span is derived learns it).
    * @param initialViews
    *   The select views of the blocks of a volatile chain restored from disk.
    * @param compute
    *   The CPU pool the blocks are applied on; without one, on the calling fiber's pool.
    * @param chainGate
    *   Shared with the LSQ point/store capture; held only while a view is selected.
    */
  def create[F[_]: Async, S](
      rules: BlockRules[F, S],
      events: FollowerEvents[F],
      geometry: EpochGeometry,
      observe: (S, S, BlockRef) => List[FollowerEvent] = (_: S, _: S, _: BlockRef) => Nil,
      persistence: Option[ChainPersistence[F, S]] = None,
      onTip: Option[OnTip[F, S]] = None,
      tiebreaker: Era => ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor,
      epochOf: Option[(S, BigInt) => BigInt] = None,
      initialViews: Map[Bytes, SelectView] = Map.empty,
      compute: Option[Compute[F]] = None,
      chainGate: Option[Mutex[F]] = None
  )(using
      Stateful[F, VolatileChain[S]]
  ): F[Follower[F, S]] =
    Stateful[F, VolatileChain[S]].get.flatMap { initial =>
      val selected = ChainSelection.Local.of(
        initial,
        ref =>
          initialViews.getOrElse(
            ref.hash,
            SelectView(ref.blockNo, ref.slot, None, 0, None, ref.boundary)
          )
      )
      (
        SelectViews.create[F](initialViews),
        chainGate.fold(Mutex[F])(_.pure[F]),
        Ref.of[F, Committed[S]](Committed(initial, selected)),
        Ref.of[F, Option[GenesisGovernor[F]]](None),
        Ref.of[F, F[Unit]](Async[F].unit),
        Ref.of[F, Option[InvalidBlocks[F]]](None)
      ).mapN { (views, lock, window, genesis, selectionChanged, invalidBlocks) =>
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
          window,
          genesis,
          selectionChanged,
          invalidBlocks
        )
      }
    }
