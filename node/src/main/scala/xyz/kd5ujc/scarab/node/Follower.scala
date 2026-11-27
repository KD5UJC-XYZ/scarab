// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import fs2.concurrent.{Signal, SignallingRef}
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
  BlockFailure,
  BlockPoolProblem,
  ChainError,
  Header,
  LedgerFailure,
  NodeError,
  PeerError,
  Points,
  StorageError,
  ValidationError
}
import cats.Monad
import cats.data.NonEmptyVector
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.assertions.{Assertions, Property}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.consensus.{BlockNoVerdict, ChainSelection, NoVerdictRetry, SelectView}
import xyz.kd5ujc.scarab.storage.ChainPersistence
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment

/** Select views of the blocks of the volatile chain, by block hash. Chain selection compares the
  * tip of a candidate with ours, and the volatile chain itself keeps only identities and ledger
  * states, so the views are recorded here as blocks are adopted.
  */
final class SelectViews[F[_]] private (ref: Ref[F, Map[Bytes, SelectView]]):
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
  * @param cpu
  *   Where the block rules run (the CPU pool, see [[Compute]]): for a peer's block on the selection
  *   worker, and for a block forged by this node.
  * @param window
  *   The window chain selection reads, kept in step with the chain by [[commit]].
  * @param publishing
  *   Held while one window is published, so that the GDD governor is handed the windows in the
  *   order they are published ([[publishWindow]]).
  * @param announced
  *   The tip a peer announced most recently, with the last ChainSync replies a session accepted
  *   ([[announce]]): the `peerTip` of the blocks adopted next (#924).
  * @param keeping
  *   Where a forged block is kept for the selection worker to consider ([[submitForged]]),
  *   installed with the worker ([[blockSelection]]).
  */
final class Follower[F[_]: Async, S] private (
    rules: BlockRules[F, S],
    events: FollowerEvents[F],
    epochOf: (S, BigInt) => BigInt,
    observe: (S, S, BlockRef) => List[FollowerEvent],
    persistence: Option[ChainPersistence[F, S]],
    tiebreaker: Era => ChainSelection.Tiebreaker,
    views: SelectViews[F],
    lock: Mutex[F],
    publishing: Mutex[F],
    cpu: Compute[F],
    window: SignallingRef[F, Follower.Committed[S]],
    genesis: Ref[F, Option[Follower.Genesis[F, S]]],
    selectionChanged: Ref[F, F[Unit]],
    invalidBlocks: Ref[F, Option[InvalidBlocks[F]]],
    announced: Ref[F, Option[ChainSync.Tip]],
    keeping: Ref[F, Option[Follower.Keeping[F]]]
)(using
    chain: Stateful[F, VolatileChain[S]],
    assertions: Assertions[F]
):
  private val F = Async[F]

  /** The revision and committed window share one cell. Subscribing after a publication observes its
    * new revision immediately, without depending on when a waiting fiber is scheduled.
    */
  private[node] def headerChanges: HeaderCheck.Changes[F] = new HeaderCheck.Changes[F]:
    def revision: F[Long] = window.get.map(_.revision)
    def changed(after: Long): F[Long] =
      window.discrete.map(_.revision).filter(_ != after).take(1).compile.lastOrError

  /** The GDD governor reads ChainDB's chain in the transaction that reads the candidates
    * (ouroboros-consensus 82ecba329, Genesis/Governor.hs:136-148). It is handed each window before
    * the window is published, so whatever a session checks against a window, the governor already
    * holds it with the candidates it reads.
    */
  private def publishWindow(current: VolatileChain[S], selected: ChainSelection.Local): F[Unit] =
    publishing.lock.surround(F.uncancelable { _ =>
      (window.get, genesis.get).flatMapN { (previous, installed) =>
        val next = Follower.Committed(current, selected, previous.revision + 1)
        installed.traverse_(_.select(next)) *> window.set(next)
      }
    })

  /** A session accepted ChainSync replies whose last announced `tip` (`Peer.Observation.Announced`,
    * before their headers are checked and their blocks fetched): the blocks adopted from now on are
    * logged with it.
    */
  private[node] def announce(tip: ChainSync.Tip): F[Unit] = announced.set(Some(tip))

  /** Installed once by the node before any peer sessions are started. From then on the governor is
    * handed every window before it is published ([[publishWindow]]), the current one first.
    */
  private[node] def installGenesis(
      governor: GenesisGovernor[F],
      windowAt: (S, BigInt, BigInt) => Option[BigInt]
  ): F[Unit] =
    val installed = Follower.Genesis[F, S](governor, windowAt)
    publishing.lock.surround(F.uncancelable { _ =>
      window.get.flatMap(installed.select) *> genesis.set(Some(installed))
    })

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
      def admits(header: Header) = chain.get.map(Follower.youngerThanImmutable(_, header))
      def governor = genesis.get.map(_.map(_.governor))
      def locked[A](body: F[A]): F[A] = lock.lock.surround(body)
      def project(
          candidates: Vector[ChainSelection.Candidate],
          phase: BlockSelection.Phase
      ): F[Vector[ChainSelection.Candidate]] =
        for
          current <- chain.get
          selected <- local
          limit <- limitFor(selected)
        yield
          if phase == BlockSelection.Phase.Startup then
            CandidateSelection.startup(current.k, selected, candidates, limit, tiebreaker)
          else CandidateSelection.project(current.k, selected, candidates, limit, tiebreaker)
      def apply(
          bodies: BlockSelection.Bodies[F],
          candidate: ChainSelection.Candidate,
          validation: Peer.Validation[F],
          phase: BlockSelection.Phase
      )(using
          Raise[F, NodeError]
      ): F[Unit] =
        local.flatMap { selected =>
          val decision =
            if phase != BlockSelection.Phase.Startup then
              // a candidate that announced nothing yet is not preferred
              candidate.headers.lastOption.fold(ChainSelection.Decision.Keep)(tip =>
                ChainSelection.decide(tiebreaker(tip.era), selected, candidate)
              )
            else if candidate.tip == selected.blocks.lastOption.fold(selected.anchor)(_.point) then
              ChainSelection.Decision.Keep
            else
              // Native initialChainSelection ranks from the immutable anchor. Reuse the already
              // validated selected prefix rather than persisting a temporary rollback to it.
              val common = candidate.headers.lastIndexWhere(h => selected.contains(h.point))
              val at = candidate.headers.lift(common).fold(candidate.base)(_.point)
              ChainSelection.Decision.Switch(
                at,
                selected.depthTo(at),
                candidate.headers.drop(common + 1)
              )
          decision match
            case ChainSelection.Decision.Switch(to, depth, take) =>
              val causal =
                if validation.addedPoint.exists(point => take.exists(_.point == point)) then
                  validation
                else Peer.Validation.none[F]
              announced.get.flatMap(
                switchTo(bodies, to, depth, take, _, pool.noVerdictBlocks, causal)
              )
            case ChainSelection.Decision.Keep if phase == BlockSelection.Phase.Startup => F.unit
            case _ => BlockPoolProblem.SelectionProjectionChanged(candidate.tip).raise[F, Unit]
        }
    // A forged block is admitted as the reference's ChainDB admits a block, written to the
    // received log and added to the processed forest, in the error scope submitForged gives it
    // (`retain`). A forged block whose body does not match its header (a bug of ours) is not kept:
    // the rules would refuse it when selection reached it.
    val before = summon[BlockSelection.Before[F]]
    def forged(selecting: BlockSelection.Selecting[F]) = new Follower.Keeping[F]:
      // the reference's order (ChainSel.hs:428-440, ouroboros-consensus 82ecba329): too old,
      // already stored, known invalid
      private def admitted(
          header: Header,
          raw: Bytes
      ): F[Either[Follower.Retention, BlockPool.Delivered]] =
        (chain.get, pool.processedSnapshot, pool.invalidBlocks.lookup(header.hash)).mapN {
          (current, forest, invalid) =>
            if !Follower.youngerThanImmutable(current, header) then Left(Follower.Retention.TooOld)
            else if forest.contains(header.hash) then Left(Follower.Retention.Present)
            else if invalid.isDefined then Left(Follower.Retention.KnownInvalid)
            else BlockPool.Delivered.checked(header, raw).leftMap(Follower.Retention.Unmatched(_))
        }
      private def stored(block: BlockPool.Delivered)(using Raise[F, NodeError]): F[Unit] =
        before.store(block.header, block.block) *> pool
          .processForged(block)
          .flatMap(_.fold(_.raise[F, Unit], _ => F.unit))
      def keep(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Follower.Retention] =
        admitted(header, raw).flatMap(
          _.fold(F.pure, block => stored(block).as(Follower.Retention.Kept))
        )
      def refused(header: Header, raw: Bytes, error: ValidationError)(using
          Raise[F, NodeError]
      ): F[Follower.Retention] =
        admitted(header, raw).flatMap(
          _.fold(
            F.pure,
            block =>
              stored(block) *> pool
                .invalidated(
                  BlockPool.Taken(raw, false, BlockPool.Source.Forged, Some(block.metadata)),
                  header,
                  error
                )
                .flatMap(_.fold(_.raise[F, Unit], F.pure))
                .as(Follower.Retention.Kept)
          )
        )
      def stored(header: Header): F[Follower.Stored] =
        (chain.get, pool.processedSnapshot, pool.invalidBlocks.lookup(header.hash)).mapN {
          (current, forest, invalid) =>
            if !Follower.youngerThanImmutable(current, header) then Follower.Stored.TooOld
            else if forest.contains(header.hash) then Follower.Stored.Present
            else invalid.fold[Follower.Stored](Follower.Stored.Fresh)(Follower.Stored.Invalid(_))
        }
      def select(header: Header, loss: Follower.AdoptionLoss)(using
          Raise[F, NodeError]
      ): F[Follower.Adoption] =
        selecting.forged(header.hash) *> outcome(header, loss)
      def outcome(header: Header, loss: Follower.AdoptionLoss): F[Follower.Adoption] =
        (
          chain.get,
          pool.invalidBlocks.lookup(header.hash),
          pool.noVerdictBlocks.lookup(header.hash)
        ).mapN { (current, invalid, withheld) =>
          if current.tip == header.point then
            Follower.Adoption.Adopted(
              BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
            )
          else
            invalid.fold(
              withheld.fold(Follower.Adoption.Lost(loss))(w => Follower.Adoption.NoVerdict(w.cause))
            )(Follower.Adoption.Invalid(_))
        }
    BlockSelection
      .resource(pool, work, fatal, application, originPredecessor)
      .flatMap(selecting =>
        Resource.make(keeping.set(Some(forged(selecting))))(_ => keeping.set(None))
      )

  /** The Limit on Eagerness on `selected`: the LoE fragment of the GSM state (`setGetLoEFragment`,
    * Node/Genesis.hs:252-278, ouroboros-consensus 82ecba329; the same at 808e40054 and 2416493bf),
    * none in Praos mode or once the node is caught up. Chain selection trims every candidate to it,
    * a peer's and a block this node forged alike (`constructPreferableCandidates`,
    * Storage/ChainDB/Impl/ChainSel.hs:767-777).
    */
  private def limitFor(selected: ChainSelection.Local): F[Option[Fragment]] =
    genesis.get.flatMap(_.flatTraverse(_.governor.limitAt(selected.anchor)))

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

  private[node] def genesisView(
      windowAt: (S, BigInt, BigInt) => Option[BigInt]
  ): F[GenesisGovernor.View] = window.get.map(Follower.genesisView(_, windowAt))

  /** Run one peer session until the peer fails (a raised [[PeerError]]) or forever. The session
    * follows the peer's chain and `track` is handed its validated header candidate; the node's
    * selection worker ([[blockSelection]]), installed before any session starts, adopts the blocks
    * the session delivers (issue 578). The two conversations are independent upstream: consensus
    * 82ecba329 ChainSync/Client.hs retains the validated candidate, while
    * BlockFetch/ClientInterface.hs:192–225 queues actual originals through addBlockAsync. Body
    * delivery never reconstructs that header candidate.
    */
  def session(
      peer: Peer[F],
      track: Ref[F, ChainSelection.Candidate] => F[Unit] = _ => Async[F].unit
  )(using Raise[F, NodeError]): F[Unit] =
    for
      registry <- registryFor(peer)
      selecting <- registry.selectionActive
      _ <- BlockPoolProblem.NoSelectionWorker.raise[F, Unit].unlessA(selecting)
      initial <- intersect(peer)
      candidate <- Ref.of[F, ChainSelection.Candidate](initial)
      _ <- track(peer.headerCandidate.getOrElse(candidate))
      _ <- Monad[F].foreverM(peer.next.flatMap(step(candidate, _)))
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
    candidate.headers.lastOption match
      case None => F.pure(Gsm.Candidate.Better(false))
      case Some(tip) =>
        lock.lock.surround(local).map { l =>
          ChainSelection.decide(tiebreaker(tip.era), l, candidate) match
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
        if !points.exists(snapshot.selection.contains) then Gsm.Candidate.DoesNotIntersect
        else
          Gsm.Candidate.Better(
            ChainSelection.prefersCandidate(tip.tiebreaker, snapshot.selection.tipView, tip.value)
          )
      }
    }

  /** ChainSync validates and retains the header fragment; ChainDB independently selects actual body
    * admissions (consensus 82ecba329 BlockFetch/ClientInterface.hs:192–225). The body journal may
    * lag that fragment and must not validate an older rollback again.
    */
  private def step(candidate: Ref[F, ChainSelection.Candidate], event: SyncEvent): F[Unit] =
    event match
      case SyncEvent.RollForward(header, _) =>
        candidate.set(ChainSelection.Candidate(header.point, Vector.empty))
      case SyncEvent.RollBackward(point, _) =>
        candidate.set(ChainSelection.Candidate(point, Vector.empty))

  /** Our chain as chain selection sees it. The window is kept between calls and updated by
    * [[commit]] (an append and a truncation per change, not the `k` blocks again for every header);
    * it is rebuilt when it is missing or no longer describes the chain (a restored chain, a state
    * set from outside).
    */
  /** A committed selection snapshot for fetch decisions. This does not acquire the application
    * lock: an applying session may be waiting for a scheduled block while holding that lock.
    */
  private[node] def fetchSelection: F[ChainSelection.Local] = window.get.map(_.selection)

  /** The committed chain as it changes: what the forger derives the inputs of its leader schedule
    * from (#1101). It moves only when a block is committed or rolled back, never while one is being
    * judged.
    */
  def committedChain: Signal[F, VolatileChain[S]] = window.map(_.chain)

  private def local: F[ChainSelection.Local] =
    for
      current <- chain.get
      cached <- window.get
      result <-
        if describes(cached.selection, current) then F.pure(cached.selection)
        else
          views.get.flatMap { known =>
            val rebuilt = ChainSelection.Local.of(current, viewOf(known))
            publishWindow(current, rebuilt).as(rebuilt)
          }
    yield result

  /** The select view of a block; a block without a recorded one gets its position only. */
  private def viewOf(known: Map[Bytes, SelectView])(ref: BlockRef): SelectView =
    known.getOrElse(ref.hash, SelectView(ref.blockNo, ref.slot, None, 0, None, ref.boundary))

  /** A cheap check that the kept window is the chain's: anchor, length and tip. */
  private def describes(l: ChainSelection.Local, current: VolatileChain[S]): Boolean =
    l.anchor == current.anchor.point && l.blocks.sizeIs == current.blocks.size &&
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

  /** Where applying a candidate stopped before its end: a refusal of a block, or no verdict on it.
    */
  private sealed trait Stopped

  private final case class Refused(
      header: Header,
      error: ValidationError,
      before: VolatileChain[S]
  ) extends Stopped

  /** The node has no verdict on `header`'s block (issue 567). */
  private final case class Withheld(header: Header, cause: BlockNoVerdict) extends Stopped

  /** What the judgement of one block says when it does not apply it. */
  private enum Judged:
    case Refusal(error: ValidationError)
    case NoVerdict(cause: BlockNoVerdict)

  /** The judgement of one block before anything of it is committed (R1, issue 567): valid, refused
    * by the protocol's rules, or no verdict of ours. `run` applies the rules in a scope of its own,
    * which never raises outward, so an exception it throws is the rules', not an outer scope's;
    * storage and every other failure of the node are raised to the caller. The scope is built
    * inside `defer`: `rescue` invokes its body at once (cats-mtl 1.7.0), so rules that throw while
    * they build their effect are caught too.
    */
  private def judge(run: Raise[F, NodeError] ?=> F[S])(using
      Raise[F, NodeError]
  ): F[Either[Judged, S]] =
    F.defer(
      Handle
        .allow[NodeError]((scope: Handle[F, NodeError]) ?=>
          run(using scope).map(state => Right(state): Either[NodeError, S])
        )
        .rescue(error => F.pure(Left(error)))
    ).attempt
      .flatMap {
        case Right(Right(state)) => F.pure(Right(state): Either[Judged, S])
        // No verdict is no refusal: the block is neither adopted nor recorded as invalid
        // and nobody is punished. Our evaluator or a crypto provider that fails on the
        // block, a quantity of the state outside its implementation type (#573) or an
        // exception of the rules no longer stop the node, where the reference's linked
        // addBlockRunner thread stops it (consensus 82ecba32
        // Storage/ChainDB/Impl/Background.hs:107-108,142): owner decision 2026-10-28,
        // issue 567.
        case Right(
              Left(
                ValidationError(
                  point,
                  BlockFailure.Tx(unavailable: LedgerFailure.Unavailable)
                )
              )
            ) =>
          F.pure(Left(Judged.NoVerdict(BlockNoVerdict.Evaluation(point, unavailable))))
        case Right(Left(refused: ValidationError)) =>
          F.pure(
            Left(
              refused.unverifiable.fold[Judged](Judged.Refusal(refused))(defect =>
                Judged.NoVerdict(BlockNoVerdict.Verification(defect))
              )
            )
          )
        case Right(
              Left(outOfRange: xyz.kd5ujc.scarab.ledger.ChainError.StateOutOfRange)
            ) =>
          F.pure(Left(Judged.NoVerdict(BlockNoVerdict.StateOutOfRange(outOfRange))))
        // a state with no Leios committee is held no better: the same state, the same failure
        case Right(Left(noCommittee: xyz.kd5ujc.scarab.ledger.ChainError.LeiosCommittee)) =>
          F.pure(Left(Judged.NoVerdict(BlockNoVerdict.StateOutOfRange(noCommittee))))
        // so is a governance record that does not hold Dijkstra's state
        case Right(Left(record: xyz.kd5ujc.scarab.ledger.ChainError.GovRecord)) =>
          F.pure(Left(Judged.NoVerdict(BlockNoVerdict.StateOutOfRange(record))))
        // a Dijkstra block that meets a limit of this build valid peer input can meet (a script
        // the evaluator cannot judge that its run could not name, the node's own readings that
        // disagree): no verdict, as the other eras'; any other defect stops the node. A script
        // named by its transaction is the evaluation's case above (#85)
        case Right(Left(stop: xyz.kd5ujc.scarab.ledger.ChainError.DijkstraBlock)) =>
          BlockNoVerdict
            .ofDijkstra(stop.cause)
            .fold(stop.raise[F, Either[Judged, S]])(cause => F.pure(Left(Judged.NoVerdict(cause))))
        // a Conway block whose era rules met a state, a store or a limit of this node's own: no
        // verdict, never a stop (issue 567, #85)
        case Right(Left(conway: xyz.kd5ujc.scarab.ledger.ChainError.ConwayBlock)) =>
          F.pure(Left(Judged.NoVerdict(BlockNoVerdict.ofConway(conway.cause))))
        // storage and every other failure of the node stay the node's
        case Right(Left(other)) => other.raise[F, Either[Judged, S]]
        case Left(thrown)       => F.pure(Left(Judged.NoVerdict(BlockNoVerdict.Defect(thrown))))
      }

  private def switchTo(
      bodies: BlockSelection.Bodies[F],
      to: ChainSync.Point,
      depth: Int,
      take: Vector[Header],
      peerTip: Option[ChainSync.Tip],
      withheldBlocks: NoVerdictBlocks[F],
      validation: Peer.Validation[F]
  )(using Raise[F, NodeError]): F[Unit] =
    for
      original <- chain.get
      rolled <- original.rollback(to).fold(_.raise[F, VolatileChain[S]], _.pure[F])
      outcome <- F
        .tailRecM[(VolatileChain[S], Vector[Applied], Int), Either[Stopped, Vector[Applied]]](
          (rolled, Vector.empty, 0)
        ) { (before, done, index) =>
          take.lift(index) match
            case None => F.pure(Right(Right(done)))
            case Some(header) =>
              for
                block <- bodies.fetch(header)
                // a withheld block selection reaches is one a reselection released (issue 693)
                retried <- withheldBlocks.lookup(header.hash)
                _ <- retried.traverse_(withheld =>
                  events.emit(FollowerEvent.NoVerdictRetried(header.point, withheld.attempts + 1))
                )
                // the block rules run on the CPU pool, as a forged block's do (D8, #596)
                verdict <- judge(cpu.within(rules(before.state, header, block)))
                // A block judged again and valid has its verdict, whether or not this candidate is
                // then adopted: withheld still, it would cut its chain, and a later block withheld
                // on the same fork could leave neither of them reachable (issue 693).
                _ <- withheldBlocks
                  .verdict(List(header.hash))
                  .whenA(retried.isDefined && verdict.isRight)
                result <- verdict match
                  case Left(Judged.Refusal(refused)) =>
                    F.pure(Right(Left(Refused(header, refused, before))))
                  case Left(Judged.NoVerdict(cause)) =>
                    F.pure(Right(Left(Withheld(header, cause))))
                  // A block the rules accepted whose header is not the next of this chain, by its
                  // number or its slot, is refused as any invalid block is, below; any other
                  // failure to extend the chain is the node's own (`Follower.misplaced`).
                  case Right(next) =>
                    before
                      .extend(header, next)
                      .fold(
                        unconnected =>
                          Follower
                            .misplaced(header, unconnected)
                            .fold(
                              unconnected.raise[F, Either[
                                (VolatileChain[S], Vector[Applied], Int),
                                Either[Stopped, Vector[Applied]]
                              ]]
                            )(refused => F.pure(Right(Left(Refused(header, refused, before))))),
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
      _ <- outcome match
        // nothing applied is nothing to commit
        case Right(steps) =>
          NonEmptyVector.fromVector(steps).traverse_(commit(original, to, depth, _, peerTip))
        // No verdict on a block: it is recorded so that selection does not judge it again before
        // its schedule says (issue 693), and reported; nothing is recorded as invalid and nobody
        // is punished (issue 567). The worker reconsiders the valid prefix before it, as for a
        // refusal.
        // A block this node forged, in any selection, is not judged again: our own block without
        // a verdict is handled as a refusal (R3, issue 567), as on the submission's direct path.
        case Left(withheld: Withheld) =>
          val record =
            if bodies.forged(withheld.header) then withheldBlocks.recordOwn
            else withheldBlocks.record
          F.uncancelable { _ =>
            record(withheld.header, withheld.cause).flatMap { (attempt, retry) =>
              events.emit(
                FollowerEvent.NoVerdict(withheld.header.point, withheld.cause, attempt, retry)
              ) *> unjudged(withheld.header.point, withheld.cause, attempt)
            }
          }
        case Left(refused: Refused) =>
          // Native validation records invalidity and punishes first (ChainSel.hs:1315–1344); the
          // worker then reconsiders a preferred valid prefix (1147–1186). The ChainDB worker
          // survives sender-thread cancellation, so this local boundary must survive it too.
          F.uncancelable { _ =>
            for
              _ <- bodies
                .invalidated(refused.header, refused.error)
                .flatMap(_.fold(_.raise[F, Unit], F.pure))
              // the adding session is punished through the pool, never by raising here: the
              // worker's validations come from admissions (`Peer.Validation.admission`) or none
              _ <- validation
                .rejected(refused.error, refused.before.tip)
                .flatMap(_.fold(_.raise[F, Peer.Rejection], F.pure))
              // a withheld block judged again and refused has its verdict (issue 693)
              _ <- withheldBlocks.verdict(List(refused.header.hash))
            yield ()
          }
    yield ()

  /** Everything applied: persist, replace the chain and announce it.
    *
    * The order is the point. The blocks are persisted before the new chain is published, as the
    * reference's ChainDB writes a block to its VolatileDB before chain selection moves the tip
    * (`Storage/ChainDB/Impl/ChainSel.hs` of ouroboros-consensus main, line 455
    * `VolatileDB.putBlock`, line 932 `writeTVar cdbChain`): a failure of the storage leaves the
    * chain that was published as it was, and what is on disk is a prefix of the new chain, which
    * the next start reads. A failure after the chain is published (announcing it) finds the chain
    * and the disk in step. In neither case does a published tip outrun what is stored; the node
    * stops on a storage failure (issue 154: `FailurePolicy.disposition`, as the reference's
    * `consensusRethrowPolicy` does) and recovers from disk.
    *
    * The mempool is not called here: its watcher ([[mempool.MempoolSync]]) reads the published
    * chain ([[committedChain]]) from a fiber of its own, as the reference's chain selection leaves
    * its mempool to the watcher that `openMempool` forks on the ledger tip (ouroboros-consensus
    * 82ecba32 `Mempool/Init.hs` 47-82; no module of `Storage/ChainDB/Impl` names the mempool). A
    * transaction being judged under the pool's lock therefore never holds back a commit, nor the
    * adoption of a forged block, the GSM's comparison or a state query's acquire, which take the
    * same chain lock.
    */
  private def commit(
      original: VolatileChain[S],
      to: ChainSync.Point,
      depth: Int,
      steps: NonEmptyVector[Applied],
      peerTip: Option[ChainSync.Tip]
  )(using Raise[F, NodeError]): F[Unit] =
    // An exception here is the commit's, whichever fiber asked for it, and stops the node; a hot
    // session would otherwise take it for an exception of its own connection (SessionEnd.scoped).
    // What the commit raises through the capabilities it is given keeps its typed cause.
    F.defer(
      Handle
        .allow[NodeError]((scope: Handle[F, NodeError]) ?=>
          committing(original, to, depth, steps, peerTip)(using scope)
            .map(_.asRight[Either[NodeError, Throwable]])
        )
        .rescue(error => F.pure(Left(Left(error))))
    ).handleError(cause => Left(Right(cause)))
      .flatMap {
        case Right(())          => F.unit
        case Left(Left(error))  => error.raise[F, Unit]
        case Left(Right(cause)) => (ChainError.CommitDefect(cause): NodeError).raise[F, Unit]
      }

  private def committing(
      original: VolatileChain[S],
      to: ChainSync.Point,
      depth: Int,
      steps: NonEmptyVector[Applied],
      peerTip: Option[ChainSync.Tip]
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
          ChainSelection.Local
            .of(finalChain, viewOf(known ++ steps.toVector.map(a => a.ref.hash -> a.view)))
        )
      )(F.pure)
      // once the chain is published, the blocks it aged past `k` are the copy runner's, as the
      // reference's copy runner waits on the chain its chain selection writes (`cdbChain`,
      // Storage/ChainDB/Impl/Background.hs:270-284, ouroboros-consensus 82ecba329): a reader under
      // the chain lock never sees the stores moved past the published chain's anchor
      _ <- F.uncancelable(_ =>
        chain.set(finalChain) *> publishWindow(finalChain, selected) *>
          persistence.traverse_(_.published)
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
      _ <- switched(original, to, depth)
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
    yield ()

  /** A commit, asserted: whether it switched to a fork (it rolled back), rolled back more than one
    * block, or more than half of the chain's `k` blocks (`2 * depth > k`).
    */
  private def switched(original: VolatileChain[S], to: ChainSync.Point, depth: Int): F[Unit] =
    def rollback = Follower.Rollback(original.tip, to, depth, original.k)
    assertions.sometimes(Property.Sometimes.ForkSwitched, depth > 0, rollback) *>
      assertions.sometimes(Property.Sometimes.RolledBackMoreThanOne, depth > 1, rollback) *>
      assertions.sometimes(
        Property.Sometimes.RolledBackMoreThanHalfK,
        Follower.pastHalfK(depth, original.k),
        rollback
      )

  /** A block's validation that ended without a verdict ([[FollowerEvent.NoVerdict]]), asserted
    * unreachable.
    */
  private def unjudged(block: ChainSync.Point, cause: BlockNoVerdict, attempt: Int): F[Unit] =
    assertions.unreachable(
      Property.Unreachable.BlockNoVerdict,
      Follower.Unjudged(block, cause, attempt)
    )

  /** Adopt a block this node has just forged. Chain selection takes it as it takes a peer's
    * candidate, as the reference hands it to `ChainDB.addBlockAsync` like any block
    * (`forkBlockForging`, NodeKernel.hs:756, ouroboros-consensus 82ecba329; Forge.hs:284 at
    * 808e40054): the Limit on Eagerness trims it ([[limitFor]]), and what is left replaces our
    * chain from the block's predecessor only when it is strictly preferred
    * ([[CandidateSelection.project]]). A block that extends our tip is preferred unless the LoE
    * leaves none of it: in Genesis mode, before the node is caught up, our selection may not reach
    * more than `k` blocks past the LoE fragment. A block of the same height as our tip (a slot
    * battle) replaces it only on the VRF tie-break. A block selection does not take is `Lost`,
    * which the forger reports as `TraceDidntAdoptBlock` (NodeKernel.hs:769). A block the rules
    * refuse is `Invalid`. The chain is untouched unless the block is `Adopted`.
    *
    * Whatever selection makes of it, the block is kept, as the reference's ChainDB puts it in its
    * VolatileDB before chain selection runs for it (`VolatileDB.putBlock`, ChainSel.hs:428-450;
    * 433-455 at 808e40054): in the processed forest of the selection worker ([[blockSelection]])
    * and the received log, unless the forest has it already, it is known invalid or it is no higher
    * than the immutable tip ([[Follower.youngerThanImmutable]]). An adopted block is kept before
    * the chain that holds it is published, so a later rollback leaves it in the forest; a refused
    * one is recorded invalid there, as the reference's selection records it. A kept block the
    * direct selection did not take goes to the worker's selection for it at once, as the reference
    * runs `chainSelectionForBlock` right after `putBlock` (ChainSel.hs:452): its candidates go
    * through the forest's blocks, so a block whose predecessor left our chain but is kept can be
    * adopted there, and the submission says what that selection decided
    * ([[Follower.Keeping.select]]). The worker's selections read the forest later too: when a block
    * delivered later makes the kept block's branch preferred, and when the LoE moves or the node is
    * caught up ([[GenesisGovernor.selectionChanged]]), as `ChainSelReprocessLoEBlocks` does
    * (ChainSel.hs 358-413, which `gddWatcher` triggers, Genesis/Governor.hs:172-184;
    * ChainSel.hs:363-418 at 808e40054). The block is adopted then if it is still valid, joins our
    * chain and is preferred; the forger's trace of it stays the one of its submission. Our own
    * block without a verdict is never judged again in this run (`NoVerdictRetry.Never`), as the
    * forger handles it as a refusal (R3, issue 567): when the direct selection finds so it is not
    * kept, and when any selection of the worker finds so of a kept one (the one its submission
    * runs, a reselection, an admission whose candidate goes through it), it stays in the forest
    * withheld for good, not released with a related block that comes due either
    * ([[NoVerdictBlocks.Schedule.Never]]), while every other block keeps the worker's retry
    * schedule. A restart reads it back as `Recovered`, and judges it again as it does every
    * withheld block. What the node holds of the block is checked first, in the reference's order
    * ([[Follower.Stored]]): a block stored already, withheld blocks included, is neither judged nor
    * selected again, and one known invalid is not judged again.
    *
    * What became of the block is the submission's [[Follower.Retention]]: a failure to keep it does
    * not change the adoption ([[retain]]).
    */
  def submitForged(header: Header, raw: Bytes)(using
      handle: Handle[F, NodeError]
  ): F[Follower.Submission] =
    lock.lock.surround {
      for
        before <- chain.get
        l <- local
        limit <- limitFor(l)
        known = before.blocks.map(_._1) ++ before.anchor.block
        // where the block would attach, and how many blocks of ours that replaces
        plan: Either[Follower.AdoptionLoss, (ChainSync.Point, Int)] =
          header.prevHash
            .fold(Option(ChainSync.Point.Origin: ChainSync.Point))(h =>
              known.find(_.hash == h).map(_.point)
            )
            .toRight(Follower.AdoptionLoss.DetachedPredecessor(before.tip, header.prevHash))
            .flatMap { base =>
              val offered = Vector(ChainSelection.Candidate(base, Vector(header)))
              def selected(under: Option[Fragment]) =
                CandidateSelection.project(before.k, l, offered, under, tiebreaker).headOption
              // a block selection would take but for the LoE is held back by it
              def lost =
                if selected(None).isDefined then Follower.AdoptionLoss.LimitOnEagerness(before.tip)
                else Follower.AdoptionLoss.SelectionPrefersTip(before.tip)
              selected(limit).map(chosen => (chosen.base, l.depthTo(chosen.base))).toRight(lost)
            }
        // what the node holds of the block already, checked first, in the reference's order
        // (ChainSel.hs:428-440, ouroboros-consensus 82ecba329)
        stored <- keeping.get.flatMap(
          _.fold(F.pure[Follower.Stored](Follower.Stored.Fresh))(_.stored(header))
        )
        result <- stored match
          // no younger than the immutable tip, as `IgnoreBlockOlderThanImmTip`: neither kept,
          // judged nor selected, and not adopted
          case Follower.Stored.TooOld =>
            F.pure(
              Follower.Submission(
                Follower.Adoption.Lost(Follower.AdoptionLoss.TooOld(before.tip)),
                Follower.Retention.TooOld
              )
            )
          // stored already, as `IgnoreBlockAlreadyInVolatileDB`: neither judged nor selected again,
          // and the forging loop reads the tip, the invalid set and the withheld set as they are
          case Follower.Stored.Present =>
            val loss = Follower.AdoptionLoss.AlreadyStored(before.tip)
            keeping.get
              .flatMap(_.fold(F.pure(Follower.Adoption.Lost(loss)))(_.outcome(header, loss)))
              .map(Follower.Submission(_, Follower.Retention.Present))
          // known invalid, as `IgnoreInvalidBlock`: not judged again, and the forging loop reads
          // the invalidity recorded
          case Follower.Stored.Invalid(error) =>
            F.pure(
              Follower
                .Submission(Follower.Adoption.Invalid(error), Follower.Retention.KnownInvalid)
            )
          case Follower.Stored.Fresh =>
            plan match
              // kept, the block goes to the worker's selection, which may take it through blocks the
              // forest has and our chain does not, as the reference's `chainSelectionForBlock` follows
              // the VolatileDB's predecessors (ChainSel.hs:452, 735-777, ouroboros-consensus 82ecba329)
              case Left(reason) =>
                retain(_.keep(header, raw)).flatMap(kept =>
                  keeping.get
                    .flatMap(
                      _.fold(F.pure(Follower.Adoption.Lost(reason)))(keeper =>
                        kept match
                          case Follower.Retention.Kept => keeper.select(header, reason)
                          // stored already, as the reference's `IgnoreBlockAlreadyInVolatileDB`: no
                          // selection, and the forging loop reads the tip as it is
                          case Follower.Retention.Present => keeper.outcome(header, reason)
                          case Follower.Retention.TooOld | Follower.Retention.KnownInvalid |
                              Follower.Retention.NoVerdict | Follower.Retention.NoWorker |
                              Follower.Retention.Unmatched(_) | Follower.Retention.Failed(_) |
                              Follower.Retention.Defect(_) =>
                            F.pure(Follower.Adoption.Lost(reason))
                      )
                    )
                    .map(Follower.Submission(_, kept))
                )
              case Right((at, depth)) =>
                for
                  rolled <- before.rollback(at).fold(_.raise[F, VolatileChain[S]], _.pure[F])
                  outcome <- judge(cpu.within(rules(rolled.state, header, raw))).flatMap {
                    case Left(Judged.Refusal(e)) =>
                      retain(_.refused(header, raw, e))
                        .map(Follower.Submission(Follower.Adoption.Invalid(e), _).asLeft[S])
                    // no verdict of ours on our own block: it is withheld as a peer's would be, and
                    // the forger handles it as a refusal (R3, issue 567, #584)
                    case Left(Judged.NoVerdict(cause)) =>
                      (events.emit(
                        FollowerEvent.NoVerdict(header.point, cause, 1, NoVerdictRetry.Never)
                      ) *> unjudged(header.point, cause, 1))
                        .as(
                          Follower
                            .Submission(
                              Follower.Adoption.NoVerdict(cause),
                              Follower.Retention.NoVerdict
                            )
                            .asLeft[S]
                        )
                    case Right(next) => F.pure(next.asRight[Follower.Submission])
                  }
                  adopted <- outcome match
                    case Left(refused) => F.pure(refused)
                    case Right(next) =>
                      val ref =
                        BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
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
                        // kept before the chain holding it is published, so a rollback leaves it
                        kept <- retain(_.keep(header, raw))
                        peerTip <- announced.get
                        _ <- commit(before, at, depth, NonEmptyVector.one(applied), peerTip)
                      yield Follower.Submission(Follower.Adoption.Adopted(ref), kept)
                yield adopted
      yield result
    }

  /** What chain selection made of a block this node has just forged ([[submitForged]]), for a
    * caller that does not report whether it was kept.
    */
  def adoptForged(header: Header, raw: Bytes)(using
      Handle[F, NodeError]
  ): F[Follower.Adoption] = submitForged(header, raw).map(_.adoption)

  /** Keep a forged block for the selection worker ([[blockSelection]]) with `keep`, and say what
    * came of it. Keeping is the node's work, not the forger's: a typed failure other than storage,
    * or an exception, leaves the block unkept and the adoption as it is, with the cause
    * ([[Follower.Retention]]). A storage failure is raised, and stops the node, as a failed write
    * of a peer's block to the received log stops it in the worker (`Fatal.of`). A block whose
    * predecessors would lead back to it is raised too (`BlockPoolProblem.AvailableCycle`), where
    * the selection for it raised it. The scope is built inside `defer`, as in [[judge]].
    */
  private def retain(
      keep: Follower.Keeping[F] => Raise[F, NodeError] ?=> F[Follower.Retention]
  )(using Raise[F, NodeError]): F[Follower.Retention] =
    keeping.get.flatMap(
      _.fold(F.pure(Follower.Retention.NoWorker)) { keeper =>
        F.defer(
          Handle
            .allow[NodeError]((scope: Handle[F, NodeError]) ?=>
              keep(keeper)(using scope).map(_.asRight[NodeError])
            )
            .rescue(error => F.pure(error.asLeft[Follower.Retention]))
        ).attempt
          .flatMap {
            case Right(Right(retention))            => F.pure(retention)
            case Right(Left(storage: StorageError)) => storage.raise[F, Follower.Retention]
            // the selection for the block raised it here before the forest refused it (#810)
            case Right(Left(cycle: BlockPoolProblem.AvailableCycle)) =>
              cycle.raise[F, Follower.Retention]
            case Right(Left(other)) => F.pure(Follower.Retention.Failed(other))
            case Left(thrown)       => F.pure(Follower.Retention.Defect(thrown))
          }
      }
    )

object Follower:
  /** Whether a rollback of `depth` blocks is of more than half of `k`: `2 * depth > k`, in `Long`,
    * which holds twice any `Int` (a configured k may be as large as `Int.MaxValue`).
    */
  private[node] def pastHalfK(depth: Int, k: Int): Boolean = 2L * depth > k

  /** What a commit's assertions report: the tip it left, the point it rolled back to, how many
    * blocks that was and the chain's `k`.
    */
  private final case class Rollback(from: ChainSync.Point, to: ChainSync.Point, depth: Int, k: Int)

  private object Rollback:
    given Encoder.AsObject[Rollback] = Encoder.AsObject.instance(r =>
      JsonObject(
        "from" -> Points.show(r.from).asJson,
        "to" -> Points.show(r.to).asJson,
        "depth" -> r.depth.asJson,
        "k" -> r.k.asJson
      )
    )

  /** What the assertion of a block without a verdict reports. */
  private final case class Unjudged(block: ChainSync.Point, cause: BlockNoVerdict, attempt: Int)

  private object Unjudged:
    given Encoder.AsObject[Unjudged] = Encoder.AsObject.instance(u =>
      JsonObject(
        "block" -> Points.show(u.block).asJson,
        "reason" -> u.cause.message.asJson,
        "attempt" -> u.attempt.asJson
      )
    )

  /** The GDD governor of a node in Genesis mode, and how a window becomes its view. */
  private final case class Genesis[F[_], S](
      governor: GenesisGovernor[F],
      windowAt: (S, BigInt, BigInt) => Option[BigInt]
  ):
    def select(window: Committed[S]): F[Unit] = governor.select(genesisView(window, windowAt))

  /** ChainDB's chain and immutable ledger as the GDD governor reads them: the selection fragment
    * and the Genesis window the immutable anchor's ledger gives.
    */
  private def genesisView[S](
      snapshot: Committed[S],
      windowAt: (S, BigInt, BigInt) => Option[BigInt]
  ): GenesisGovernor.View =
    val current = snapshot.chain
    val tipSlot = current.anchor.block.fold(BigInt(-1))(_.slot)
    GenesisGovernor.View(
      snapshot.fragment,
      current.k,
      slot => windowAt(current.anchor.state, tipSlot, slot)
    )

  /** Publish chain state and its selection window atomically for GDD readers. Capturing the
    * immutable ledger and fragment never waits for a block fetch or a running ledger check.
    */
  private final case class Committed[S](
      chain: VolatileChain[S],
      selection: ChainSelection.Local,
      revision: Long = 0L
  ):
    // the selection's fragment, carried with it ([[ChainSelection.Local.blockPoints]])
    val fragment: Fragment = Fragment(selection.anchor, selection.blockPoints)

  /** Preserve the full candidate outside selection: advancing or disabling LoE must reconsider
    * headers already downloaded, without requiring another peer announcement.
    *
    * The candidate is rebased on the newest of its points on our chain and trimmed to the limit
    * (`trimToLoE`, ouroboros-consensus 82ecba329 ChainSel.hs:803-845, 1144-1186 at 2416493b), each
    * searched from the candidate's tip as before, but through the window's carried index
    * ([[ChainSelection.Local.intersection]]) and by slot on the limit ([[GenesisDensity.trim]]):
    * nothing the size of the window or the limit is built for it (#1499).
    */
  private[node] def limitCandidate(
      k: Int,
      current: ChainSelection.Local,
      candidate: ChainSelection.Candidate,
      limit: Option[Fragment]
  ): Option[ChainSelection.Candidate] =
    current.intersection(candidate) match
      case None => Some(candidate)
      case Some((common, place)) =>
        val intersection =
          if common == 0 then candidate.base else candidate.headers(common - 1).point
        val selected = current.blockPoints
        // the blocks up to the intersection; a window block at Origin is not a fragment's block
        val before =
          if selected.sizeIs == current.blocks.size then selected.take(place)
          else
            current.blocks.take(place).map(_.point).collect { case block: ChainSync.Point.Block =>
              block
            }
        val extension = candidate.headers.drop(common)
        val proposed = Fragment(current.anchor, before ++ extension.map(_.blockPoint))
        GenesisDensity
          .trim(k, Fragment(current.anchor, selected), proposed, limit)
          .toOption
          .map { allowed =>
            ChainSelection.Candidate(
              intersection,
              extension.take((allowed.headers.size - before.size).max(0))
            )
          }

  /** Whether `header` 's block is younger than the immutable tip of `chain`, so that it may still
    * join a chain: a higher block number, or an epoch boundary block of the immutable tip's own
    * number, which follows it (`olderThanImmTip`, Storage/ChainDB/Impl/ChainSel.hs:574-581,
    * ouroboros-consensus 82ecba329; 579-586 at 808e40054, 870-877 at 2416493bf). Any block is,
    * while the immutable tip is Origin.
    */
  private[node] def youngerThanImmutable[S](chain: VolatileChain[S], header: Header): Boolean =
    chain.anchor.block.forall(anchor =>
      header.blockNo > anchor.blockNo || (header.isBoundary && header.blockNo == anchor.blockNo)
    )

  /** The refusal that a failure to extend the chain with `header` 's block is, when the failure is
    * the block's: the rules accepted the block, and its header is not the next of the chain it is
    * applied to by its block number or its slot ([[VolatileChain.extend]]). Header-first validation
    * refuses such a header before its block is fetched ([[NodeHeaderRules]]). Block application
    * meets one where no header was validated first (`--header-first off`), and in a block the
    * received log kept from such a run. The reference runs the envelope on every block it applies
    * (ouroboros-consensus 82ecba32 `validateEnvelope`,
    * `Ouroboros/Consensus/HeaderValidation.hs:364-376`, from `Ledger/Extended.hs:189-211`), so the
    * block is invalid, as one its rules refuse is.
    *
    * None for a failure that is the node's own, which stops it: a previous hash that is not the
    * tip's, since a candidate follows the forest's predecessor links from a point of our chain
    * ([[AvailableBlocks]]), and every failure that is not of a header's place.
    * [[VolatileChain.extend]] checks the number and the slot before the previous hash, so a header
    * that does not name the tip at all fails by its number or its slot first: it is refused only if
    * it names the tip, and is the node's own failure if it does not. A total match with no default,
    * so a new case must be placed here.
    */
  private[node] def misplaced(header: Header, failure: ChainError): Option[ValidationError] =
    import xyz.kd5ujc.scarab.ledger.HeaderStructureProblem as P
    failure match
      case ChainError.NotConnected(_, tip, cause) =>
        val namesTheTip = tip match
          case ChainSync.Point.Block(_, hash) => header.prevHash.contains(hash)
          case ChainSync.Point.Origin         => false
        cause match
          case P.BlockNumber(_, _) | P.SlotNumber(_, _) =>
            Option.when(namesTheTip)(
              ValidationError(header.point, BlockFailure.HeaderStructure(cause))
            )
          case P.PreviousHash(_, _) | P.UnexpectedBoundarySlot(_, _) | P.Transition(_) |
              P.MissingByronLedger | P.LedgerRead(_) | P.EpochPrecedes(_, _) |
              P.LeiosHeaderUnread(_) =>
            None
      case ChainError.StateAcquire(_) | ChainError.Decode(_, _) |
          ChainError.RollbackBeyondAnchor(_, _) | ChainError.NoIntersection |
          ChainError.MempoolTick(_, _) | ChainError.StateOutOfRange(_, _) |
          ChainError.CommitDefect(_) | ChainError.LeiosCommittee(_, _) |
          ChainError.GovRecord(_, _) | ChainError.DijkstraBlock(_, _) |
          ChainError.ConwayBlock(_, _) =>
        None

  /** Where a forged block is kept ([[Follower.submitForged]]): the processed forest of the
    * selection worker, whose selections read it, and the received log
    * ([[Follower.blockSelection]]). Each operation admits the block as the reference's ChainDB
    * admits a block it is given (Storage/ChainDB/Impl/ChainSel.hs:428-450, ouroboros-consensus
    * 82ecba329): not when it is no younger than the immutable tip, nor when the forest has it
    * already (`Present`, neither stored nor selected again), nor when it is known invalid.
    */
  private[node] trait Keeping[F[_]]:
    def keep(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Retention]

    /** Keep a block the rules refused, and record it invalid, as the reference's selection records
      * a block it finds invalid.
      */
    def refused(header: Header, raw: Bytes, error: ValidationError)(using
        Raise[F, NodeError]
    ): F[Retention]

    /** Run the worker's chain selection for a kept block chain selection did not take directly for
      * `loss`, as the reference runs it after `putBlock` (`chainSelectionForBlock`,
      * Storage/ChainDB/Impl/ChainSel.hs:452, 82ecba329; 457 at 808e40054), and say what it made of
      * the block as the reference's forging loop reads it (NodeKernel.hs:758-769): `Adopted` when
      * the block is the tip after it, `Invalid` when it found the block invalid, `NoVerdict` when
      * it withheld it, else `Lost(loss)`. The caller holds the application lock.
      */
    def select(header: Header, loss: AdoptionLoss)(using Raise[F, NodeError]): F[Adoption]

    /** What the node holds of the block already ([[Stored]]). */
    def stored(header: Header): F[Stored]

    /** What the reference's forging loop reads of a block it added (NodeKernel.hs:758-769,
      * ouroboros-consensus 82ecba329): as [[select]] says, without running a selection.
      */
    def outcome(header: Header, loss: AdoptionLoss): F[Adoption]

  /** What became of a forged block offered to [[Follower.submitForged]]. */
  enum Retention:
    /** In the selection worker's processed forest and the received log, for a later selection. */
    case Kept

    /** Kept already, by an earlier submission or delivery (`IgnoreBlockAlreadyInVolatileDB`). */
    case Present

    /** Not kept: it is no younger than the immutable tip (`IgnoreBlockOlderThanImmTip`). */
    case TooOld

    /** Not kept: it is known invalid (`IgnoreInvalidBlock`). */
    case KnownInvalid

    /** Not kept: our implementation has no verdict on it, and it is never judged again. */
    case NoVerdict

    /** No selection worker is running to keep it for: before the node's starts, or after it stops.
      */
    case NoWorker

    /** Its body does not match its header, a bug of ours: the rules would refuse it. */
    case Unmatched(problem: PeerError.ProtocolProblem)

    /** Keeping it failed with a typed error that is not a storage failure. */
    case Failed(error: NodeError)

    /** Keeping it threw. */
    case Defect(cause: Throwable)

    def message: String = this match
      case Kept         => "kept for a later selection"
      case Present      => "kept already"
      case TooOld       => "not kept: it is no younger than the immutable tip"
      case KnownInvalid => "not kept: it is known invalid"
      case NoVerdict    => "not kept: the node has no verdict on it"
      case NoWorker     => "not kept: no selection worker is running"
      case Unmatched(problem) =>
        s"not kept: its body does not match its header (${problem.message})"
      case Failed(error) => s"not kept: ${error.message}"
      case Defect(cause) =>
        s"not kept: defect: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"

  /** What the node holds of a forged block before chain selection runs for it, as the reference's
    * ChainDB checks a block it is given, in its order (Storage/ChainDB/Impl/ChainSel.hs:428-440,
    * ouroboros-consensus 82ecba329): a block no younger than the immutable tip
    * (`IgnoreBlockOlderThanImmTip`, [[Follower.youngerThanImmutable]]), the block itself
    * (`IgnoreBlockAlreadyInVolatileDB`, which withheld blocks are too), its recorded invalidity
    * (`IgnoreInvalidBlock`), or nothing yet.
    */
  private[node] enum Stored:
    case Fresh
    case TooOld
    case Present
    case Invalid(error: ValidationError)

  /** What chain selection made of a forged block, and whether it was kept. */
  final case class Submission(adoption: Adoption, retention: Retention)

  /** What happened to a forged block offered to [[Follower.adoptForged]]. */
  enum AdoptionLoss:
    case DetachedPredecessor(tip: ChainSync.Point, predecessor: Option[Bytes])
    case SelectionPrefersTip(tip: ChainSync.Point)

    /** The Limit on Eagerness leaves none of the block to chain selection, which would take it
      * otherwise (`trimToLoE`, Storage/ChainDB/Impl/ChainSel.hs:823-845, ouroboros-consensus
      * 82ecba329).
      */
    case LimitOnEagerness(tip: ChainSync.Point)

    /** The block is stored already, and chain selection does not run for it again
      * (`IgnoreBlockAlreadyInVolatileDB`, Storage/ChainDB/Impl/ChainSel.hs:431-433,
      * ouroboros-consensus 82ecba329).
      */
    case AlreadyStored(tip: ChainSync.Point)

    /** The block is no younger than the immutable tip, and chain selection does not run for it
      * (`IgnoreBlockOlderThanImmTip`, Storage/ChainDB/Impl/ChainSel.hs:428-430, ouroboros-consensus
      * 82ecba329).
      */
    case TooOld(tip: ChainSync.Point)
    def message: String = this match
      case DetachedPredecessor(tip, _) =>
        s"the tip moved to ${Points.show(tip)} and the block's predecessor is not on our chain"
      case SelectionPrefersTip(tip) =>
        s"the tip moved to ${Points.show(tip)}, which chain selection prefers to the block"
      case LimitOnEagerness(tip) =>
        s"the Limit on Eagerness keeps the block from chain selection, whose tip is ${Points.show(tip)}"
      case TooOld(tip) =>
        s"the block is no younger than the immutable tip; the tip is ${Points.show(tip)}"
      case AlreadyStored(tip) =>
        s"the block is stored already and not selected again; the tip is ${Points.show(tip)}"

  enum Adoption:
    case Adopted(block: BlockRef)
    case Lost(reason: Follower.AdoptionLoss)
    case Invalid(error: ValidationError)

    /** Our own implementation has no verdict on the block (R3, issue 567). */
    case NoVerdict(cause: BlockNoVerdict)

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
      tiebreaker: Era => ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor,
      epochOf: Option[(S, BigInt) => BigInt] = None,
      initialViews: Map[Bytes, SelectView] = Map.empty,
      compute: Option[Compute[F]] = None,
      chainGate: Option[Mutex[F]] = None
  )(using
      Stateful[F, VolatileChain[S]],
      Assertions[F]
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
        Mutex[F],
        SignallingRef[F, Committed[S]](Committed(initial, selected)),
        Ref.of[F, Option[Genesis[F, S]]](None),
        Ref.of[F, F[Unit]](Async[F].unit),
        Ref.of[F, Option[InvalidBlocks[F]]](None),
        Ref.of[F, Option[ChainSync.Tip]](None),
        Ref.of[F, Option[Keeping[F]]](None)
      ).mapN {
        (
            views,
            lock,
            publishing,
            window,
            genesis,
            selectionChanged,
            invalidBlocks,
            announced,
            keeping
        ) =>
          new Follower[F, S](
            rules,
            events,
            epochOf.getOrElse((_: S, slot: BigInt) => geometry.epochOf(slot)),
            observe,
            persistence,
            tiebreaker,
            views,
            lock,
            publishing,
            compute.getOrElse(Compute.inline[F]),
            window,
            genesis,
            selectionChanged,
            invalidBlocks,
            announced,
            keeping
          )
      }
    }
