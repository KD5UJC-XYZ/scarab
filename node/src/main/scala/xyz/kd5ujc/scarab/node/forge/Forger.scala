// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.ledger.{ForgeDefect, ForgeLedgerProblem, ForgeProtocolProblem}
import cats.{Applicative, Eq}
import cats.data.EitherT
import cats.effect.{Async, Clock, Deferred, Ref}
import cats.effect.std.Mutex
import fs2.concurrent.{Signal, SignallingRef}
import scala.annotation.unused
import scala.collection.immutable.SortedMap
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.ledger.{ForgeError, ValidationError}
import xyz.kd5ujc.scarab.ledger.{ClockProblem, NodeError}
import xyz.kd5ujc.scarab.ledger.VrfCert
import xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold as Leader
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash}
import xyz.kd5ujc.scarab.node.{Fatal, Follower, SessionEnd}
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig}
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals, PoolDistribution, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra, NewEpochState, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.node.read.{ChainRead, ConwayView, InEra, TxClock}
import xyz.kd5ujc.scarab.node.read.MirrorEraRead
import xyz.kd5ujc.scarab.node.mempool.{Capacity, ConwayPoolLedger, Mempool, MempoolEnv}
import xyz.kd5ujc.scarab.node.mempool.{EbCapacityScale, EbMeasure, Rejection}
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncingHeader,
  BytesSize,
  CertifyDecision,
  LeiosCertification
}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.LeiosStoreError
import xyz.kd5ujc.scarab.protocol.{ChainSync, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.UtxoStore
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.Fibers
import xyz.kd5ujc.scarab.node.assertions.{Assertions, Property}
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*

/** Whether the node forges, as the reference's `BlockForgingUpdate` trace says it: its type
  * `EnabledBlockForging` (cardano-node 11.1.3, `cardano-node/src/Cardano/Node/Startup.hs` 166-174),
  * whose constructors' names its trace prints.
  */
enum BlockForgingState:
  case EnabledBlockForging
  case DisabledBlockForging

  /** A SIGHUP arrived before consensus has started (the chain is still being opened and replayed):
    * the credentials are not read again (`Run.hs` 412-435, traced at 432).
    */
  case NotEffective

/** What the forger reports, for the JSON log and for tests. */
enum ForgeEvent:
  /** Forging was switched on or off: at start-up, and at every reload that finds the credentials
    * (on) or finds a credential file absent (off), as the reference traces `BlockForgingUpdate`
    * (cardano-node 11.1.3 `Run.hs` 248-252 and 633-647); `NotEffective` for a SIGHUP during
    * start-up (432).
    */
  case BlockForgingUpdate(state: BlockForgingState)

  /** Forging is on for this pool; `counter` is the operational certificate's issue counter and
    * `kesPeriod` the KES period now.
    */
  case Started(
      poolId: Bytes,
      vrfKeyHash: Bytes,
      kesStart: BigInt,
      kesEvolutions: BigInt,
      counter: BigInt,
      kesPeriod: BigInt
  )

  /** A trace of the forging loop, as the reference's `forkBlockForging` traces it. */
  case Loop(trace: ForgeTrace)

  /** A trace of the blockchain time the loop reads its slots from. */
  case Clock(trace: ClockTrace)

  /** A Leios line of the forge loop, which the reference traces to its Leios tracer
    * ([[LeiosForgeTrace]]).
    */
  case Leios(trace: LeiosForgeTrace)

  /** The KES key's state after the forge state was updated at a slot: what the reference traces
    * through its `forgeStateInfoTracer` (`Block/Forging.hs` 218-220), cardano-node 11.1.3
    * `Forge.StateInfo` (`Tracers/KESInfo.hs`), with the label of its block forging (`credentials`).
    */
  case StateInfo(info: KesInfo)

  /** Scarab's account of a block it forged (with [[ForgeTrace.ForgedBlock]]): its transactions,
    * size, KES periods and the stake it was elected with.
    */
  case Forged(
      slot: BigInt,
      epoch: BigInt,
      blockNo: BigInt,
      hash: Bytes,
      prev: Option[Bytes],
      txs: Vector[Bytes],
      bodySize: Long,
      kesPeriod: BigInt,
      relativeKesPeriod: Int,
      stake: BigInt,
      totalStake: BigInt,
      opCertCounter: BigInt
  )

  /** The forged block is now the tip of our chain (and is served to peers), with the time from the
    * start of its slot (with [[ForgeTrace.AdoptedBlock]]).
    */
  case Adopted(block: BlockRef, latencyMs: Option[Long] = None)

  /** The forged block did not become part of our chain (with [[ForgeTrace.DidntAdoptBlock]]), or
    * was replaced by another one later.
    */
  case Lost(slot: BigInt, hash: Bytes, reason: String)

  /** The forged block could not be kept for a later chain selection ([[Follower.submitForged]]): a
    * failure of the node's own that does not stop it. What selection made of the block is traced as
    * it would be otherwise.
    */
  case NotKept(slot: BigInt, hash: Bytes, retention: Follower.Retention)

  /** The KES key moved to a new period. */
  case KesEvolved(kesPeriod: BigInt, relativeKesPeriod: Int)

  /** The KES key and operational certificate were replaced (SIGHUP), or the same ones confirmed. */
  case KesReloaded(counter: BigInt, kesStart: BigInt, kesEvolutions: BigInt, kesPeriod: BigInt)

  /** A reload was refused: the credentials in use before it stay in force. */
  case KesReloadRefused(reason: ReloadRefusal)

  /** Key persistence failed; this observation is followed by a typed fatal outcome and node stop.
    */
  case KesPersistFailed(kesPeriod: BigInt, error: ForgeError)

  /** The evolved key was not written back because its file is not there (moved away, #918); no file
    * is made at `file`, and the key in memory signs on.
    */
  case KesFileAbsent(kesPeriod: BigInt, file: String)

  /** A write-back replaced the key file by the rename, because there is no atomic exchange here
    * (`reason`); said once. A key file moved away in the instant between the check that it is there
    * and the rename can then be made again (#918).
    */
  case KesWrittenByRename(reason: NoExchange)

  /** At start-up, the KES key read from its file is at period `keyPeriod`, later than the current
    * period `kesPeriod` by the wall clock: it cannot sign before then. The file holds a key evolved
    * ahead of time; restoring the unevolved key, or issuing a new KES key and operational
    * certificate, lets the pool forge again (#1101, the KES row of 2026-11-18).
    */
  case KesKeyAhead(keyPeriod: BigInt, kesPeriod: BigInt)

  /** The VRF key file gives its group some access (`mode`): allowed, as in the reference, which
    * warns (`GroupPermissionsExist`).
    */
  case KeyFileGroupAccess(file: String, mode: Int)

  /** A slot given up for a reason the reference does not have (see [[ForgeError]]). */
  case Failed(slot: BigInt, error: ForgeError)

  /** The block built in `slot` failed a check that only a bug of ours can fail ([[ForgeDefect]]):
    * no block, and the transactions it held (`evicted`) left the mempool.
    */
  case Defect(slot: BigInt, defect: ForgeDefect, evicted: Vector[Bytes])

  /** The forger's counters for an epoch that has just ended (see [[ForgeStats]]). */
  case EpochSummary(epoch: BigInt, stats: ForgeEpochStats)

  /** The leader schedule of `epoch` is complete, or a restart changed it (#1101, decision 7): the
    * slots the pool leads in it (`slots`), the number a pool of its stake leads on average
    * (`expected`, for comparison, computed in floating point and never used for a decision), and
    * whether the epoch's nonce is settled, a block at or after its freeze slot being immutable. The
    * reference does not publish its schedule.
    */
  case Scheduled(epoch: BigInt, slots: Vector[BigInt], expected: Double, settled: Boolean)

  /** An attempt at `slot` that does not count (#1101, Q4): a block built ahead of the slot and not
    * handed over (`hash`), an early refusal tried again, or an attempt that found the slot not led
    * under the inputs in force. Its lines are dropped, so that the log shows one attempt per slot.
    */
  case Discarded(slot: BigInt, reason: DiscardReason, hash: Option[Bytes])

/** Why an attempt at a slot was discarded ([[ForgeEvent.Discarded]]). */
enum DiscardReason:
  /** The block context at the slot's onset is not the one the block was built on. */
  case BlockContext

  /** The slot's inputs at its onset are not those the attempt was made from. */
  case Inputs

  /** A reload replaced the credentials, or disabled forging, after the attempt. */
  case Credentials

  /** The attempt was refused before the onset, and is made again. */
  case Refused

  /** The slot is not led under the inputs in force: it is published as any slot not led is. */
  case NotLed

  /** The block was built without a certificate the node holds at the onset: it is forged again, and
    * certifies, as the reference's forge at the slot does.
    */
  case Certificate

trait ForgeEvents[F[_]]:
  def emit(event: ForgeEvent): F[Unit]

  /** `event`, which happened at `at`: a line the forger held until its slot's outcome was final
    * keeps the time it was made (#1101). A sink that has no use for the time drops it.
    */
  def emitAt(@unused at: Instant, event: ForgeEvent): F[Unit] = emit(event)

/** Block production for one pool, in the Praos protocol, from a leader schedule computed ahead of
  * the slots (#1101, [the design](../../../../../../../../../docs/design/leader-schedule.md)). Four
  * fibers in one scope:
  *
  *   - the scheduler computes, from the committed chain and the credentials in force, which slots
  *     of the current and the next epoch the pool leads ([[LeaderSchedule]]);
  *   - the clock fiber reads the clock each slot, as the reference's blockchain time does
  *     (`hardForkBlockchainTime`, consensus 82ecba329 `BlockchainTime/WallClock/HardFork.hs`), and
  *     publishes the slot;
  *   - the forging fiber wakes [[Forger.WakeMargin]] before each led slot and forges it with the
  *     reference's steps ([[attempt]], `forkBlockForging` 's `go`, `NodeKernel.hs` 549-796), its
  *     lines held until the slot's outcome is final. Only an attempt admitted at its slot, begun or
  *     made again once the clock fiber had published it ([[Admitted]]), changes the KES state for
  *     it ([[HotKeys.update]], [[HotKeys.evolvedFor]]): one that is not and would ends, and is made
  *     again once admitted; a led slot of a KES period the clock has not reached at the wake is
  *     left to the slot driver's handover at its onset;
  *   - the slot driver takes each slot from the clock fiber as `knownSlotWatcher` hands it to the
  *     reference's forging thread, and is the only publisher of a slot's outcome: the lines and the
  *     counters of a led slot as the forging fiber made them, and for a slot not led the
  *     reference's line where a check before its leader check fails, else `nodeNotLeader`.
  *
  * Every led slot's decision and certificate, and every block, are those the reference's per-slot
  * check makes from the same chain and credentials: the schedule answers only under the inputs in
  * force, and a led slot is checked again against the chain at the slot.
  *
  * The slot clock is the wall clock over the era history (`EpochGeometry`); `F` is any `Async` so
  * tests drive it with a test clock.
  *
  * @param protocolVersion
  *   The protocol version the forged headers carry: the node's own under its build, with the
  *   producer signal as its minor ([[xyz.kd5ujc.scarab.node.NodeSetup.forgedProtocolVersion]]), not
  *   the ledger's, as `forgeShelleyBlock` takes `shelleyProtocolVersion` of the block config
  *   (ouroboros-consensus 82ecba32 `Shelley/Ledger/Forge.hs:83`, 2416493b
  *   `Shelley/Ledger/Forge.hs:144`).
  * @param pool
  *   What the forger keeps of the credentials it started with, none of it secret: the keys are
  *   those of `hot`, which owns them ([[Forger.Pool]]).
  * @param forecastWindow
  *   How far the ledger view can be forecast from the ledger state of the block's predecessor: the
  *   stability window (`SL.stabilityWindow`) of `ledgerViewForecastAt`.
  * @param now
  *   `now` for tests; the real clock by default.
  * @param unknownSlotBackoff
  *   How long the clock waits before it asks again while the current slot is unknown.
  * @param horizon
  *   Whether the era history at the tip of a chain translates a slot ([[Forger.withinHorizon]]).
  * @param viewed
  *   The leader views derived lately, by the tip they were derived from and their epoch.
  * @param stats
  *   Per-epoch counters (H5) and the reference's `ForgingStats`, folded over the traces.
  * @param chains
  *   The committed chain as it changes, from which the schedule's inputs are derived.
  * @param shared
  *   What the fibers share: the clock's slot, the claims on slots, the KES key's reservation and
  *   its update of each period.
  * @param pause
  *   Called at the points where a test suspends a fiber ([[Forger.Point]]); nothing by default.
  * @param conwaySnapshots
  *   The pool's snapshot of a slot a Conway block takes its transactions from, its held Conway
  *   transactions applied again on the ledger of the slot ([[ConwaySnapshots.ofPool]]): the node's
  *   pool, or another for a forger of tests ([[ConwaySnapshots.unadmitted]]).
  * @param dijkstraSnapshots
  *   The pool's snapshot of a slot a Dijkstra ranking block takes its transactions from, with the
  *   endorser block of those after them ([[BlockBuilder.partition]]): the node's pool
  *   ([[DijkstraSnapshots.ofPool]]), or none for a forger of tests
  *   ([[DijkstraSnapshots.unadmitted]]).
  * @param onForgedEb
  *   What is done with an endorser block forged with a ranking block, before the block goes to
  *   chain selection ([[OnForgedLeiosEb]]); none where the node has no endorser-block store, and
  *   then no endorser block is forged, for none could be served.
  * @param ebCapacityScale
  *   The share of an endorser block's capacity the forger forges with, read at each slot
  *   (`readEbCapacityScale`, [[EbCapacityScale]]).
  * @param certifying
  *   What a Dijkstra block's certification reads of the node: the store's closures, the vote
  *   state's certificates and the certified closure's application ([[LeiosCertifying]]); none: the
  *   forger never certifies ([[LeiosCertifying.none]]).
  */
final class Forger[F[_]: Async, S](
    config: PraosConfig,
    globals: LedgerGlobals,
    protocolVersion: (BigInt, BigInt),
    pool: Forger.Pool,
    val hot: HotKeys[F],
    follower: Follower[F, S],
    mempool: Mempool[F],
    events: ForgeEvents[F],
    forecastWindow: BigInt,
    now: F[Instant],
    sleep: FiniteDuration => F[Unit],
    unknownSlotBackoff: FiniteDuration,
    horizon: (VolatileChain[S], BigInt) => Boolean,
    cache: Ref[F, Option[Forger.Crossed]],
    viewed: Ref[F, Vector[((Option[Bytes], BigInt), Forger.LeaderView)]],
    pending: Ref[F, Vector[Forger.Forged]],
    val stats: ForgeStats[F],
    val schedule: LeaderSchedule[F],
    chains: Signal[F, VolatileChain[S]],
    shared: Forger.Shared[F],
    pause: Forger.Point => F[Unit],
    conwaySnapshots: ConwaySnapshots[F],
    dijkstraSnapshots: DijkstraSnapshots[F],
    onForgedEb: Option[OnForgedLeiosEb[F]],
    ebCapacityScale: F[EbCapacityScale],
    certifying: LeiosCertifying[F, S]
)(using
    chain: Stateful[F, VolatileChain[S]],
    read: ChainRead[S],
    clock: TxClock[S],
    assertions: Assertions[F]
):
  import Forger.{Attempted, Claim, Final, Held, Next, Prepared, Seen, Why}

  private val F = Async[F]
  private val geometry = config.geometry

  /** Run until cancelled. Whether forging is on is traced first, as the reference traces it at
    * start-up (`BlockForgingUpdate`, cardano-node 11.1.3 `Run.hs` 248-252); a node started as
    * non-producing runs all the same and forges once a reload enables it. A failure of any of the
    * four fibers ends the other three, and is the result.
    */
  def run: F[Either[Fatal, Unit]] = Forger.scoped {
    for
      instant <- now
      current <- hot.certificate
      _ <- events.emit(
        ForgeEvent.BlockForgingUpdate(
          current.fold(BlockForgingState.DisabledBlockForging)(_ =>
            BlockForgingState.EnabledBlockForging
          )
        )
      )
      _ <- current.traverse_(opCert =>
        events.emit(
          ForgeEvent.Started(
            pool.id,
            pool.vrfKeyHash,
            opCert.kesPeriod,
            config.maxKesEvolutions,
            opCert.sequence,
            kesPeriodOf(geometry.slotAt(instant))
          )
        )
      )
      // a key in its file ahead of the present cannot sign until the present reaches it
      _ <- Forger
        .keyAhead(pool, kesPeriodOf(geometry.slotAt(instant)))
        .traverse_(events.emit)
      _ <- F.both(
        F.both(scheduling, clocked(None)),
        F.both(F.both(forging(BigInt(-1), BigInt(-1)), driving(None)), warming)
      )
    yield ()
  }

  /** The certified closure applied off the forging path: on every tip the selection publishes, the
    * closure of the endorser block the tip announces, once the store holds it, applied to the tip's
    * unticked state as the forger applies it to certify it in the next slot
    * ([[LeiosCertifying.closed]], memoised by the node's [[LeiosCertifying.memoized]]). The
    * reference builds that state off the forging path too, in the mempool's certifying view
    * (`buildCertifyingView` over `applyAnnouncedClosure`, `Mempool/CertifyingView.hs:49-72`, and
    * `syncCertifyingView`, `Mempool/Update.hs:647-665` at ouroboros-consensus d91b3557), so that
    * the block that certifies it is not delayed by its application (#1380).
    *
    * Once applied, that state ticked to the slot after the tip is the base of the pool's certifying
    * view of the closure ([[DijkstraSnapshots.certifying]]): the pool revalidated on it, then kept
    * up to date with the pool's changes until the tip changes, which the block that certifies the
    * endorser block takes its endorser block's transactions from (`getCertifyingSnapshotFor`). The
    * reference builds and keeps the view in its mempool (`syncCertifyingView`,
    * `Mempool/Update.hs:647-667`, and the catch-up thread, `Mempool/Init.hs:59-60`), a pool sync
    * that a tip or an acquired closure starts.
    *
    * It is only a head start: the forger asks for the same application with the same arguments when
    * it certifies, and takes it from the memo or applies the closure itself, and revalidates the
    * pool itself when the view does not hold. So a warm or a view that fails, is refused or meets a
    * defect changes nothing the node does, and is let go of here, never raised: the forger meets
    * the same failure where it certifies, as it does without the warm.
    */
  private def warming: F[Unit] =
    chains.discrete
      .changesBy(_.tipRef.map(_.hash))(using Eq.fromUniversalEquals)
      // a tip whose closure is not complete yet is looked at again every slot until it is, the
      // look given up when the tip changes: the closure usually arrives after its announcing block
      .switchMap(current =>
        fs2.Stream.eval(
          warmed(current)
            .flatTap(pending => sleep(Forger.WarmRetry).whenA(pending))
            .iterateWhile(identity)
        )
      )
      .compile
      .drain

  /** Whether the tip `current` announces an endorser block whose closure the store does not hold
    * yet, after the closure's application is asked for where it does.
    */
  private def warmed(current: VolatileChain[S]): F[Boolean] =
    current.tipRef
      .fold(F.pure(false)) { tip =>
        val slot = tip.slot + 1
        Handle
          .allow[NodeError] {
            crossedTo(current, geometry.epochOf(slot), slot).flatMap { crossed =>
              (crossed.ledger, LeiosCertification.announcement(crossed.view.protocol.dep)) match
                case (Forger.TipLedger.InDijkstra(unticked), Some(point)) =>
                  Handle
                    .allow[StorageError](
                      certifying
                        .closureHeld(point.hash)
                        .ifM(
                          certifying
                            .closed(current.state, current.tipRef, unticked, point, slot)
                            .flatMap {
                              // the pool's certifying view of the closure, kept until the tip
                              // changes, on its state ticked to the slot after the tip
                              case Right(closed) =>
                                tickDijkstra(closed, slot).fold(
                                  _ => F.unit,
                                  base =>
                                    dijkstraSnapshots
                                      .certifying(point.hash, current.tipRef.map(_.hash), base)
                                )
                              case Left(_) => F.unit
                            }
                            .as(false),
                          F.pure(true)
                        )
                    )
                    .rescue(_ => F.pure(false))
                case (Forger.TipLedger.InDijkstra(_), None) | (Forger.TipLedger.InConway(_), _) =>
                  F.pure(false)
            }
          }
          .rescue(_ => F.pure(false))
      }
      .handleError(_ => false)

  private def kesPeriodOf(slot: BigInt): BigInt = slot / config.slotsPerKesPeriod

  /** The admission of `slot` now (§5.5), if the slot driver has taken it or a later slot, or the
    * clock fiber has published it or a later slot.
    */
  private def admittedNow(slot: BigInt): F[Option[Admitted]] =
    (shared.claims.get.map(_.takenFor(slot)), Admitted.now(slot, shared)).mapN(_.orElse(_))

  /** The admission of `slot`, once the clock fiber publishes it or the slot driver takes it,
    * whichever comes first, waited for holding nothing.
    */
  private def admission(slot: BigInt): F[Admitted] =
    Admitted
      .published(shared)
      .merge(shared.claims.discrete.map(_.takenFor(slot)).unNone)
      .find(_.admits(slot))
      .compile
      .lastOrError

  /** Whether the forge of `slot` has begun at `at` (§5.3): its onset has come, or the slot driver
    * has taken it, as the reference's forging thread begins a slot when `knownSlotWatcher` hands it
    * over.
    */
  private def begun(slot: BigInt, at: Instant): F[Boolean] =
    if !at.isBefore(geometry.slotStart(slot)) then F.pure(true)
    else shared.claims.get.map(_.takenFor(slot).nonEmpty)

  /** Until the slot driver has taken `slot`. */
  private def takenOf(slot: BigInt): F[Unit] =
    shared.claims.discrete.find(_.takenFor(slot).nonEmpty).compile.drain

  /** Switch to the KES key and operational certificate that are in the key files now, if they are
    * good for this pool at this time and on this chain; otherwise keep the old ones and report a
    * typed [[ForgeEvent.KesReloadRefused]]. A credential file that is absent disables forging
    * ([[HotKeys.reload]]). The Haskell node does this on SIGHUP: `Cardano.Node.Run`
    * `installSigHUPHandler` calls `updateBlockForging`, which reads the credentials again and hands
    * them to `setBlockForging` (cardano-node 11.1.3, `Run.hs` lines 596 and 626).
    */
  def reload: F[Either[Fatal, Unit]] = Forger.scoped {
    for
      instant <- now
      current <- chain.get
      counter = KeyHash
        .fromBytes[BlockIssuer](pool.id)
        .toOption
        .flatMap(current.state.protocol.counters.get)
        .map(_.toBigInt)
      _ <- hot.reload(kesPeriodOf(geometry.slotAt(instant)), counter)
    yield ()
  }

  /** The slot the clock fiber published last, none before the first. */
  private[forge] def clockSlot: Signal[F, Option[BigInt]] = shared.clock

  /** The claims on slots and the slot driver's mark. */
  private[forge] def claimed: Signal[F, Forger.Claims[F]] = shared.claims

  /** The slot driver's resolved-through mark: every slot up to it is settled. */
  private[forge] def settledThrough: Signal[F, BigInt] = shared.claims.map(_.mark)

  /** What the operator's gauges show of the schedule (#1101): the schedule of the epoch the clock
    * is in and of the next, and the first led slot from the clock's slot on.
    */
  def scheduleView: F[Forger.ScheduleView] =
    (shared.clock.get, now, schedule.schedules.get).mapN { (known, at, schedules) =>
      val slot = known.getOrElse(geometry.slotAt(at))
      val epoch = geometry.epochOf(slot)
      def gauge(e: BigInt) =
        schedules
          .epoch(e)
          .map(s => Forger.ScheduleGauge(s.led.size, Forger.expected(s.inputs), s.complete))
      Forger.ScheduleView(gauge(epoch), gauge(epoch + 1), schedules.nextLed(slot - 1))
    }

  // ---- the scheduler

  /** The scheduler: the inputs of the epochs the pool can still lead in, derived from the committed
    * chain and the credentials in force at every change of either, and the schedule computed from
    * them ([[LeaderSchedule.computed]]); the `leaderSchedule` line when an epoch's schedule is
    * complete and differs from the one last said.
    */
  private def scheduling: F[Unit] =
    (
      SignallingRef.of[F, LeaderSchedule.InForce](LeaderSchedule.InForce.none),
      Ref.of[F, Map[BigInt, Vector[BigInt]]](Map.empty)
    ).flatMapN { (inForce, said) =>
      val pumped = (chains, hot.inForce).tupled.discrete
        .evalMap((c, i) => known(c, i))
        .changes
        .evalMap(i => pause(Forger.Point.Inputs(i.epochs.map(_.epoch))) *> inForce.set(i))
        .compile
        .drain
      val computed = schedule.computed(inForce).evalMap(announce(said, _)).compile.drain
      F.both(pumped, computed).void
    }

  /** The first slot from which no block can change the nonce of `epoch` ([[Forger.freeze]]). */
  private def freeze(epoch: BigInt): BigInt = Forger.freeze(geometry, epoch)

  /** The inputs known on `current` with the VRF key of `installed`: those of the epochs
    * [[Forger.scheduledEpochs]] gives, read through the slot's own ledger view ([[viewOf]]), so the
    * schedule covers every era the slot's check forges in (§2.3). Inputs that cannot be derived are
    * left out: their slots are decided at the slot.
    */
  private def known(
      current: VolatileChain[S],
      installed: HotKeys.Installed
  ): F[LeaderSchedule.InForce] =
    installed.vrf.fold(F.pure(LeaderSchedule.InForce.none)) { vrf =>
      Forger.scheduleInputs(
        current.state,
        current.tipRef,
        globals.referenceBuild,
        geometry,
        pool.id,
        config.activeSlotsCoeff,
        vrf
      )(e => viewOf(current, e, geometry.firstSlot(e)))
    }

  private def announce(
      said: Ref[F, Map[BigInt, Vector[BigInt]]],
      added: LeaderSchedule.Added
  ): F[Unit] =
    val s = added.schedule
    val epoch = s.inputs.epoch
    val slots = s.led.keys.toVector
    said
      .modify(m =>
        if m.get(epoch).contains(slots) then (m, false)
        else (m.filter((e, _) => e >= epoch - 1).updated(epoch, slots), true)
      )
      .flatMap(changed =>
        chain.get
          .flatMap(c =>
            events.emit(
              ForgeEvent.Scheduled(
                epoch,
                slots,
                Forger.expected(s.inputs),
                c.anchor.block.exists(_.slot >= freeze(epoch))
              )
            )
          )
          .whenA(changed)
      )
      .whenA(s.complete)

  // ---- the clock fiber

  /** What the blockchain time reads at `instant` (`hardForkBlockchainTime`, consensus 82ecba329
    * `BlockchainTime/WallClock/HardFork.hs`): before the system start, how long to wait for it
    * (`systemTimeWait`); past the horizon of the era history at the tip of the chain, unknown
    * (`wallclockToSlot` failing); otherwise the slot.
    */
  private def read(instant: Instant): F[Forger.Reading] =
    chain.get.map(current => Forger.reading(geometry, instant, horizon(current, _)))

  private def clockTrace(t: ClockTrace): F[Unit] = events.emit(ForgeEvent.Clock(t))

  /** Until the next slot, as the reading at `instant` puts it (`timeLeft` of `getCurrentSlot'`),
    * waited on the monotonic clock as `threadDelay` waits: `elapsed` of it is already spent, and a
    * wall clock moved meanwhile does not lengthen it. `threadDelay` floors its delay to a
    * microsecond (io-classes 1.8 si-timers, `Control/Monad/Class/MonadTimer/SI.hs:72-101`, as
    * `AcceptedConnectionsLimit.threadDelay` has it), which a timer's overshoot covers. A floor to
    * the millisecond ended most waits before the next slot, which then read as the same slot and
    * was traced as a clock moved back (#629).
    */
  private def untilNext(slot: BigInt, instant: Instant, elapsed: FiniteDuration): F[Unit] =
    // A wait of a century or more is cut to a century, compared before it is converted: its slot
    // is one no clock reaches (a Byron slot duration of a `Natural` beyond it, #734), whose
    // `timeLeft` the reference holds as an unbounded `NominalDiffTime`
    val until = Duration.between(instant, geometry.slotStart(slot + 1))
    val bounded =
      if until.toDays >= Forger.LongestWait.toDays then Forger.LongestWait
      else until.toNanos.nanos
    val left = bounded - elapsed
    sleep(left.toMicros.micros.max(1.millis))

  /** The clock fiber: the slots, as the reference's blockchain time writes them for its readers
    * (`HardFork.hs` 103-151), in a fiber of their own that waits on no reader. Each reading is
    * checked against the previous one (`checkValidClockChange`): a later slot is published; the
    * same or an earlier slot within `MaxClockRewind` of the previous reading's time is traced
    * (`SystemClockMovedBack`) and the previous slot kept; further back, the node stops
    * ([[ClockProblem.SystemClockMovedBack]]). While the slot is unknown it is traced
    * (`CurrentSlotUnknown`) and the clock is asked again after `unknownSlotBackoff`.
    */
  private def clocked(
      previous: Option[(Option[BigInt], Instant)]
  )(using Raise[F, Fatal]): F[Unit] =
    now.flatMap { instant =>
      read(instant).flatMap {
        case Forger.Reading.BeforeStart(wait) =>
          clockTrace(ClockTrace.StartTimeInTheFuture(geometry.systemStart, wait)) *>
            sleep(wait) *> clocked(previous)
        case Forger.Reading.Unknown =>
          clockTrace(ClockTrace.CurrentSlotUnknown(instant)) *> sleep(unknownSlotBackoff) *>
            clocked(Some(None -> instant))
        case Forger.Reading.Known(slot) =>
          previous.flatMap((known, at) => known.map(_ -> at)) match
            case Some((last, at)) if slot <= last =>
              if !Duration.between(instant, at).minus(Forger.MaxClockRewind).isPositive then
                clockTrace(ClockTrace.SystemClockMovedBack(at, instant)) *>
                  Forger
                    .wokeEarly(geometry, at, instant)
                    .traverse_(awaited =>
                      assertions.unreachable(
                        Property.Unreachable.ClockWokeEarly,
                        Forger.EarlyWake(at, instant, awaited)
                      )
                    ) *>
                  untilNext(slot, instant, 0.millis) *>
                  clocked(Some(Some(last) -> instant))
              else
                (Fatal.Internal(ClockProblem.SystemClockMovedBack(last, slot)): Fatal)
                  .raise[F, Unit]
            case Some(_) | None =>
              for
                started <- F.monotonic
                _ <- shared.clock.set(Some(slot))
                finished <- F.monotonic
                _ <- untilNext(slot, instant, finished - started)
                _ <- clocked(Some(Some(slot) -> instant))
              yield ()
      }
    }

  // ---- the slot driver

  /** The slot driver: each slot the clock fiber publishes, as `knownSlotWatcher` takes them (C1): a
    * slot to its end, then the latest slot the clock has reached, the slots the forging fiber has
    * claimed in between first, in order. Any other slot that passes meanwhile gets no line and no
    * count, as the reference passes over a slot that passes during its forging action.
    */
  private def driving(
      last: Option[BigInt]
  )(using Handle[F, NodeError], Raise[F, Fatal]): F[Unit] =
    Admitted
      .published(shared)
      .collectFirst { case latest if last.forall(_ < latest.through) => latest }
      .compile
      .lastOrError
      .flatMap(latest => upTo(last, latest))
      .flatMap(handled => driving(Some(handled)))

  /** The slots up to `latest`, the slot the clock fiber had published when the driver took it,
    * which admits every one of them (§5.5) and which the claims keep from the take on: a forge of
    * one of them begins then, and is admitted.
    */
  private def upTo(last: Option[BigInt], latest: Admitted)(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[BigInt] =
    shared.claims.modify(_.take(latest)).flatMap {
      case Next.Claimed(slot, claim) =>
        settle(last, slot, Some(claim), latest) *> upTo(Some(slot), latest)
      case Next.Latest(claim) => settle(last, latest.through, claim, latest).as(latest.through)
    }

  /** One slot, to its end: the summary of the epoch that ended before it, the slot's outcome, its
    * settlement (the mark moves to it) and the forged blocks replaced since. `admitted` admits it.
    */
  private def settle(
      last: Option[BigInt],
      slot: BigInt,
      claim: Option[Claim[F]],
      admitted: Admitted
  )(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Unit] =
    for
      // the epoch the previous slot was in has ended: its counters are final
      _ <- last.traverse_ { l =>
        val ended = geometry.epochOf(l)
        if geometry.epochOf(slot) > ended then
          stats.epoch(ended).flatMap(s => events.emit(ForgeEvent.EpochSummary(ended, s)))
        else F.unit
      }
      on <- hot.enabled
      _ <- pause(Forger.Point.Disabled(slot)).unlessA(on)
      // the KES key at the slot's period, before anything of the slot (§5.5)
      kes <-
        if on then pause(Forger.Point.Updating(slot)) *> periodUpdate(slot, admitted)
        else F.pure(None)
      _ <- claim match
        case Some(Claim.Forging(result))   => awaited(slot, kes, result)
        case Some(Claim.Handed(result, _)) => awaited(slot, kes, result)
        case Some(Claim.Driver())          => F.unit
        case None => if on then own(slot, kes, admitted) else unowned(slot, kes)
      _ <- shared.claims.update(_.settled(slot))
      _ <- watch
    yield ()

  /** A slot the slot driver does not handle (forging is disabled): claimed by it, so that the
    * forging fiber cannot claim it any more, unless the forging fiber claimed it first, after a
    * reload enabled forging, whose result is then awaited and published before the mark passes it.
    */
  private def unowned(slot: BigInt, kes: Option[(Long, Forger.KesResult)]): F[Unit] =
    shared.claims.modify(_.claim(slot, Claim.Driver())).flatMap {
      case None                          => F.unit
      case Some(Claim.Forging(result))   => awaited(slot, kes, result)
      case Some(Claim.Handed(result, _)) => awaited(slot, kes, result)
      case Some(Claim.Driver())          => F.unit
    }

  /** A slot the forging fiber owns: its final result, published. */
  private def awaited(
      slot: BigInt,
      kes: Option[(Long, Forger.KesResult)],
      result: Deferred[F, Final[F]]
  ): F[Unit] =
    result.get.flatMap {
      case Final.Lines(held, generation) =>
        // the driver's update of the period stands for the forge's only if it was made with the
        // same credentials: the driver may have read forging disabled, or other credentials
        announced(slot, kes.filter(_._1 == generation), held)
          .map(_ ++ startedIn(slot, generation, held))
          .flatMap(publish)
      case Final.NotLed(discards, generation, info) =>
        publish(discards) *> notLed(slot, Some(generation -> info)).flatMap(publish)
      case Final.Skipped(discards) => publish(discards)
    }

  /** The lines of a slot not led, as the reference would end it: counted as a slot checked, with
    * `startLeadershipCheck` and the KES key's state at a period change, the state near its expiry,
    * then `nodeNotLeader`.
    */
  private def notLed(slot: BigInt, kes: Option[(Long, KesInfo)]): F[Vector[Held[F]]] =
    ended(slot, kes.map((generation, info) => generation -> Some(info)), reached = true)(
      Forger.traced(ForgeTrace.NodeNotLeader(slot))
    )

  /** The lines of a slot the slot driver ends itself, `kes` the generation of the period's update
    * and the key's state where it succeeded: the slot counted (the reference's
    * `StartLeadershipCheck`, whose line a slot not led logs only at a KES period change, once in
    * the period, whether its update succeeded or failed), the KES key's state if its period has not
    * been announced, or if the slot's checks reached the key near its expiry, then the lines that
    * end the slot (`last`).
    */
  private def ended(slot: BigInt, kes: Option[(Long, Option[KesInfo])], reached: Boolean)(
      last: Instant => Vector[Held[F]]
  ): F[Vector[Held[F]]] =
    (now, shared.published.get).mapN { (at, published) =>
      val period = kesPeriodOf(slot)
      val starting = kes.collect {
        case (generation, _) if !published.started.contains(generation -> period) => generation
      }
      val start = ForgeTrace.StartLeadershipCheck(slot)
      // at a KES period change the line too, first, as the reference orders a slot's lines (#1101,
      // Q2), once in the period for these credentials; counted once either way, the line's
      // publication counting it
      val counted: Vector[Held[F]] = starting.fold(
        Vector(Held.Effect(stats.record(geometry.epochOf(slot), start)))
      )(generation => Vector(Held.Line(ForgeEvent.Loop(start), at), startOf(generation, period)))
      val state = kes.collect {
        case (generation, Some(info))
            if !published.announced.contains(generation -> period) ||
              (reached && info.remaining <= Forger.KesWarning) =>
          Held.Kes[F](slot, generation, Right(info), at)
      }
      counted ++ state.toVector ++ last(at)
    }

  /** The mark that the `startLeadershipCheck` of `period` was published under the credentials of
    * `generation`, made where it is.
    */
  private def startOf(generation: Long, period: BigInt): Held[F] =
    Held.Effect(shared.published.update(_.started(generation, period)))

  /** The mark of the period's `startLeadershipCheck` for the lines of a slot the forging fiber
    * owned, where they hold one, whichever its attempt's end, with the credentials' generation of
    * that attempt (`generation`): a slot the slot driver ends later in the period logs none.
    */
  private def startedIn(slot: BigInt, generation: Long, held: Vector[Held[F]]): Vector[Held[F]] =
    val started = held
      .collect { case Held.Line(ForgeEvent.Loop(ForgeTrace.StartLeadershipCheck(s)), _) => s }
      .contains(slot)
    Option.when(started)(startOf(generation, kesPeriodOf(slot))).toVector

  /** `held`, the lines of a slot the forging fiber owned, with the KES key's state of the period
    * after them if the period has not been announced and they do not announce it.
    */
  private def announced(
      slot: BigInt,
      kes: Option[(Long, Forger.KesResult)],
      held: Vector[Held[F]]
  ): F[Vector[Held[F]]] =
    kes match
      case Some((generation, Right(Right(info)))) =>
        val period = kesPeriodOf(slot)
        val inHeld = held.exists {
          case Held.Kes(s, g, Right(_), _) => g == generation && kesPeriodOf(s) == period
          case Held.Kes(_, _, Left(_), _) | Held.Line(_, _) | Held.Effect(_) | Held.Withdrawal(_) =>
            false
        }
        shared.published.get.flatMap { published =>
          if inHeld || published.announced.contains(generation -> period) then F.pure(held)
          else now.map(at => held :+ Held.Kes(slot, generation, Right(info), at))
        }
      case Some((_, Right(Left(_)))) | Some((_, Left(_))) | None => F.pure(held)

  /** A slot no fiber has claimed: decided (§5.4), and either ended here or handed to the forging
    * fiber, which forges it at once.
    */
  private def own(slot: BigInt, kes: Option[(Long, Forger.KesResult)], admitted: Admitted)(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Unit] =
    decided(slot, kes).flatMap {
      case Forger.Decision.Disabled() => unowned(slot, kes)
      case Forger.Decision.Ends(lines) =>
        shared.claims.modify(_.claim(slot, Claim.Driver())).flatMap {
          case None                          => publish(lines)
          case Some(Claim.Forging(result))   => awaited(slot, kes, result)
          case Some(Claim.Handed(result, _)) => awaited(slot, kes, result)
          case Some(Claim.Driver())          => F.unit
        }
      case Forger.Decision.Led() =>
        // handed over with the driver's admission: the slot's forge is admitted (§5.5)
        pause(Forger.Point.Deciding(slot)) *> Deferred[F, Final[F]].flatMap { result =>
          shared.claims.modify(_.claim(slot, Claim.Handed(result, admitted))).flatMap {
            case None                          => awaited(slot, kes, result)
            case Some(Claim.Forging(theirs))   => awaited(slot, kes, theirs)
            case Some(Claim.Handed(theirs, _)) => awaited(slot, kes, theirs)
            case Some(Claim.Driver())          => F.unit
          }
        }
    }

  /** The decision of a slot no fiber has claimed, with the checks the reference makes before its
    * leader check, in the order [[attempt]] makes them and with no VRF and no ledger tick (#1101,
    * Q1): the tip, the forecast horizon, the era of the ledger the view is forecast from, and the
    * KES key's state from the period's update. A slot that passes them is led if the schedule says
    * so under the inputs in force, or, where it does not cover the slot under them, if
    * [[Leadership.check]] says so (Q3); a check that fails otherwise than by not leading is left to
    * the forging fiber, whose attempt ends the slot as the reference would.
    */
  private def decided(slot: BigInt, kes: Option[(Long, Forger.KesResult)])(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Forger.Decision[F]] =
    val epoch = geometry.epochOf(slot)
    val info = kes.map((g, result) =>
      g -> (result match
        case Right(Right(i))          => Some(i)
        case Right(Left(_)) | Left(_) => None)
    )
    def ends(trace: ForgeTrace, reached: Boolean = false) =
      ended(slot, info, reached)(Forger.traced(trace)).map(Forger.Decision.Ends(_))
    kes match
      case None => F.pure(Forger.Decision.Disabled())
      case Some((generation, result)) =>
        chain.get.flatMap { current =>
          blockContext(current, slot) match
            case Left(trace) => ends(trace)
            case Right(context) =>
              forecast(context.base, slot) match
                case Left(range) => ends(ForgeTrace.NoLedgerView(slot, range))
                case Right(forecastable) =>
                  viewOf(context.base, epoch, slot).flatMap {
                    case Left(e: ForgeError) => failedEnd(slot, e, info)
                    case Left(other)         => other.raise[F, Forger.Decision[F]]
                    case Right(view)         =>
                      // the period's update, made before the slot's checks, is written back only
                      // now that the slot has passed the forecast check (§5.5)
                      hot.confirm(forecastable) *> (result match
                        case Left(e) => failedEnd(slot, e, info)
                        case Right(Left(failure)) =>
                          ended(slot, info, reached = false)(at =>
                            Vector(Held.Kes(slot, generation, Left(failure), at))
                          ).map(Forger.Decision.Ends(_))
                        case Right(Right(_)) =>
                          hot.inForce.get.flatMap(_.vrf.fold(F.pure(Forger.Decision.Disabled())) {
                            vrf =>
                              val inputs = Forger.inputsOf(
                                geometry,
                                epoch,
                                view,
                                pool.id,
                                config.activeSlotsCoeff,
                                vrf
                              )
                              schedule.answer(slot, inputs).flatMap {
                                case LeaderSchedule.Answer.Led(_) => F.pure(Forger.Decision.Led())
                                case LeaderSchedule.Answer.NotLed =>
                                  ends(ForgeTrace.NodeNotLeader(slot), reached = true)
                                case LeaderSchedule.Answer.Uncovered =>
                                  hot.keys
                                    .evaluating(vrf)(Leadership.check(slot, inputs, _))
                                    .flatMap {
                                      case Some(Left(_: ForgeError.NotLeader)) =>
                                        ends(ForgeTrace.NodeNotLeader(slot), reached = true)
                                      // led, a check that fails otherwise, or one the credentials in
                                      // force no longer make (a reload replaced the VRF key since):
                                      // the forging fiber's attempt ends the slot as the reference
                                      // would
                                      case Some(Left(_) | Right(_)) | None =>
                                        F.pure(Forger.Decision.Led())
                                    }
                              }
                          }))
                  }
        }

  /** A slot the driver ends with Scarab's `forgeFailed`, or the node stops if the failure is one of
    * the infrastructure (as [[attempt]] treats it).
    */
  private def failedEnd(slot: BigInt, e: ForgeError, info: Option[(Long, Option[KesInfo])])(using
      Raise[F, Fatal]
  ): F[Forger.Decision[F]] =
    HotKeys
      .infrastructureFailure(e)
      .fold(
        ended(slot, info, reached = false)(at =>
          Vector(
            Held.Effect(stats.failed(geometry.epochOf(slot))),
            Held.Line(ForgeEvent.Failed(slot, e), at)
          )
        ).map(Forger.Decision.Ends(_))
      )(_.raise[F, Forger.Decision[F]])

  // ---- the forging fiber

  /** The forging fiber: the earliest led slot after `last`, from the schedule, or a slot the slot
    * driver handed over after `forged`, the last slot this fiber forged (a slot passed over is not
    * one, so that a slot handed over after its scheduled wake was passed over is still forged). A
    * scheduled slot is forged early ([[early]]) once its wake has come and every slot before it is
    * settled, unless its KES period is one the clock has not reached then: that slot is passed
    * over, and forged when the slot driver hands it over at its onset. One handed over is forged at
    * once. A change of the schedule or of the claims wakes it, and it takes the earliest again. It
    * publishes nothing: each slot's final result goes to the slot driver.
    */
  private def forging(last: BigInt, forged: BigInt)(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Unit] =
    (shared.claims.get, schedule.schedules.get, now)
      .flatMapN { (claims, schedules, at) =>
        val scheduled = schedules.nextLed(last)
        def passed(t: BigInt) = F.pure((t, forged))
        val waiting = F.pure((last, forged))
        claims.handedAfter(forged) match
          case Some((t, result, admitted)) =>
            forgeNow(t, Some(admitted)).flatMap(result.complete).as((t.max(last), t))
          case None =>
            scheduled match
              case None => changeOf(None, claims, schedules) *> waiting
              case Some(t) =>
                val wake = geometry.slotStart(t).minusNanos(Forger.WakeMargin.toNanos)
                if t <= claims.mark then passed(claims.mark)
                else if claims.owned.contains(t) then passed(t)
                // its time has passed and no one has claimed it: passed over, as the reference
                // passes over a slot that passes while its forging thread is busy (C1); should
                // the slot driver still hand it over, it is forged then
                else if !at.isBefore(geometry.slotStart(t + 1)) then passed(t)
                else if claims.mark < t - 1 || at.isBefore(wake) then
                  changeOf(
                    Option.when(at.isBefore(wake))(Forger.between(at, wake)),
                    claims,
                    schedules
                  ) *> waiting
                // a slot of a KES period the clock has not reached, in practice a period's first
                // slot, is not forged ahead: its attempt would need the key moved into that period,
                // which no attempt does before it is admitted at the slot (§5.5), and would end
                // with a discard line; this only saves that attempt, the safety is in `HotKeys`. At the onset the slot driver, after its update of the
                // period, hands it over, and it is forged then, as the per-slot loop forges it
                else if kesPeriodOf(t) > kesPeriodOf(geometry.slotAt(at)) then passed(t)
                else
                  // a slot is forged only while forging is enabled, and only in a slot the clock
                  // can know, as the reference's watcher hands it only known slots
                  (hot.enabled, chain.get.map(horizon(_, t))).flatMapN { (on, known) =>
                    if !on then passed(t)
                    else if !known then changeOf(None, claims, schedules) *> waiting
                    else
                      pause(Forger.Point.Waking(t)) *> Deferred[F, Final[F]].flatMap { result =>
                        shared.claims.modify(_.forging(t, result)).flatMap { claimed =>
                          if claimed then early(t, result).as((t, t)) else waiting
                        }
                      }
                  }
      }
      .flatMap(forging(_, _))

  /** Until `delay` has passed, or the claims or the schedule differ from `claims` and `schedules`.
    */
  private def changeOf(
      delay: Option[FiniteDuration],
      claims: Forger.Claims[F],
      schedules: LeaderSchedule.Schedules
  ): F[Unit] =
    val timer = delay.fold(F.never[Unit])(sleep)
    val claimed = shared.claims.discrete.dropWhile(_ == claims).take(1).compile.drain
    val scheduled = schedule.schedules.discrete.dropWhile(_ == schedules).take(1).compile.drain
    F.race(timer, F.race(claimed, scheduled)).void

  /** `slot`, claimed by the forging fiber, forged before its onset (§5.3): the attempt is
    * provisional until the slot's forge begins ([[begun]]: the onset, or the slot driver's take of
    * the slot, whichever comes first), made again when the chain or the credentials change, and
    * then its block is handed over only if it was built on the block context, the inputs and the
    * credentials in force then; otherwise the slot is forged again then. An attempt begun once the
    * slot's forge has begun is final.
    */
  private def early(slot: BigInt, result: Deferred[F, Final[F]])(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Unit] =
    (pause(Forger.Point.Woke(slot)) *> now.flatMap(begun(slot, _)).flatMap { started =>
      if started then admittedNow(slot).flatMap(forgeNow(slot, _))
      else
        for
          seen <- seenNow
          admitted <- admittedNow(slot)
          first <- attempt(slot, scheduled, admitted)
          _ <- pause(first match
            case _: Attempted.Built[F, S] => Forger.Point.Signed(slot)
            case _: Attempted.Ended[F, S] => Forger.Point.Refused(slot))
          last <- provisional(slot, first, seen, Vector.empty)
        yield last
    }).flatMap(result.complete).void

  private def seenNow: F[Seen] =
    (chain.get, hot.inForce.get).mapN((c, i) => Seen(c.tipRef.map(_.hash), i.generation))

  private def provisional(
      slot: BigInt,
      current: Attempted[F, S],
      seen: Seen,
      discards: Vector[Held[F]]
  )(using Handle[F, NodeError], Raise[F, Fatal]): F[Final[F]] =
    val onset = geometry.slotStart(slot)
    now.flatMap(at => begun(slot, at).tupleLeft(at)).flatMap { (at, started) =>
      if started then whenBegun(slot, current, discards)
      else
        val changed =
          (chains.map(_.tipRef.map(_.hash)), hot.inForce.map(_.generation)).tupled.discrete
            .dropWhile((tip, generation) => tip == seen.tip && generation == seen.generation)
            .take(1)
            .compile
            .lastOrError
            .map((tip, generation) => Seen(tip, generation))
        pause(Forger.Point.Waiting(slot)) *> F
          .race(F.race(sleep(Forger.between(at, onset)), takenOf(slot)), changed)
          .flatMap {
            // the monotonic timer and the slot driver's take are only wakes: the wall clock and the
            // claims, read again, say whether the slot's forge has begun (a clock moved back within
            // the rewind allowance has the onset later)
            case Left(_) => provisional(slot, current, seen, discards)
            case Right(next) =>
              current match
                case built: Attempted.Built[F, S] if next.generation == built.prepared.generation =>
                  provisional(slot, built, next, discards)
                case other =>
                  val why = other match
                    case _: Attempted.Built[F, S] => DiscardReason.Credentials
                    case e: Attempted.Ended[F, S] => Forger.reasonOf(e.why)
                  discarded(slot, why, other).flatMap { line =>
                    admittedNow(slot)
                      .flatMap(attempt(slot, scheduled, _))
                      .flatMap(again => provisional(slot, again, next, discards :+ line))
                  }
          }
    }

  /** Once the slot's forge has begun ([[begun]]): the early block handed over if it still holds,
    * else the slot forged again.
    */
  private def whenBegun(slot: BigInt, current: Attempted[F, S], discards: Vector[Held[F]])(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Final[F]] =
    current match
      case built: Attempted.Built[F, S] =>
        holds(slot, built.prepared).flatMap {
          case None =>
            handover(slot, built).map(lines =>
              Final.Lines(discards ++ lines, built.prepared.generation)
            )
          case Some(why) =>
            discarded(slot, why, built).flatMap(line =>
              admittedNow(slot).flatMap(forgeNow(slot, _)).map(_.after(discards :+ line))
            )
        }
      case e: Attempted.Ended[F, S] =>
        discarded(slot, Forger.reasonOf(e.why), e).flatMap(line =>
          admittedNow(slot).flatMap(forgeNow(slot, _)).map(_.after(discards :+ line))
        )

  /** Why a block built early cannot be handed over now, if it cannot: forging was disabled or the
    * credentials replaced, the block context is not the one it was built on, the slot's inputs are
    * not those it was made from, or it would certify now ([[certifiesNow]]).
    */
  private def holds(slot: BigInt, prepared: Prepared[S])(using
      Handle[F, NodeError]
  ): F[Option[DiscardReason]] =
    (chain.get, hot.inForce.get, hot.enabled).flatMapN { (current, installed, on) =>
      if !on || installed.generation != prepared.generation then
        F.pure(Some(DiscardReason.Credentials))
      else
        blockContext(current, slot) match
          case Right(context)
              if context.prev == prepared.context.prev &&
                context.blockNo == prepared.context.blockNo =>
            installed.vrf.fold(F.pure(Option(DiscardReason.Credentials))) { vrf =>
              viewOf(context.base, prepared.inputs.epoch, slot).flatMap {
                case Right(view)
                    if Forger.inputsOf(
                      geometry,
                      prepared.inputs.epoch,
                      view,
                      pool.id,
                      config.activeSlotsCoeff,
                      vrf
                    ) == prepared.inputs =>
                  certifiesNow(prepared).map(Option.when(_)(DiscardReason.Certificate))
                case Right(_) | Left(_) => F.pure(Some(DiscardReason.Inputs))
              }
            }
          case Right(_) | Left(_) => F.pure(Some(DiscardReason.BlockContext))
    }

  /** Whether a block built early without a certificate, only because the closure or the certificate
    * was missing then, would certify now: the store holds the closure and the vote state a
    * certificate for the parent. The reference decides at the slot (`decideLeiosCertify`,
    * `NodeKernel/Forge.hs:324-396` at ouroboros-consensus 2416493b), so such a block is forged
    * again.
    */
  private def certifiesNow(prepared: Prepared[S])(using Handle[F, NodeError]): F[Boolean] =
    prepared.certifiable.fold(F.pure(false)) { (point, tip) =>
      tip
        .flatMap(xyz.kd5ujc.scarab.crypto.bls.RbHash.fromBytes)
        .fold(F.pure(false))(rbHash =>
          (certifying.closureHeld(point.hash), certifying.certificate(rbHash))
            .mapN(_ && _.isDefined)
        )
    }

  /** The Debug line of an attempt that does not count. */
  private def discarded(slot: BigInt, why: DiscardReason, a: Attempted[F, S]): F[Held[F]] =
    val hash = a match
      case b: Attempted.Built[F, S] => Some(b.prepared.block.header.hash)
      case _: Attempted.Ended[F, S] => None
    now.map(at => Held.Line(ForgeEvent.Discarded(slot, why, hash), at))

  /** `slot` forged now, from the node as it is, and its final result: a block handed over, a
    * refusal, or not led; nothing while forging is disabled, as the reference runs no forging
    * thread then. `admitted`: its admission, if it has one (§5.5); one that would change the KES
    * state without it waits, holding nothing, until the clock fiber publishes the slot or the slot
    * driver takes it, and is made again, admitted.
    */
  private def forgeNow(slot: BigInt, admitted: Option[Admitted])(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Final[F]] =
    hot.enabled.flatMap { on =>
      if !on then F.pure(Final.Skipped(Vector.empty))
      else
        attempt(slot, scheduled, admitted).flatMap {
          case built: Attempted.Built[F, S] =>
            handover(slot, built).map(Final.Lines(_, built.prepared.generation))
          case Attempted.Ended(_, Why.NotLed(generation, info), _) =>
            now.map(at =>
              Final.NotLed(
                Vector(Held.Line(ForgeEvent.Discarded(slot, DiscardReason.NotLed, None), at)),
                generation,
                info
              )
            )
          case Attempted.Ended(held, Why.Refused | Why.Disabled, generation) =>
            F.pure(Final.Lines(held, generation))
          // made again once admitted, holding nothing meanwhile (§5.5)
          case e @ Attempted.Ended(_, Why.Unpublished, _) =>
            discarded(slot, DiscardReason.Refused, e).flatMap(line =>
              admission(slot).flatMap(a => forgeNow(slot, Some(a))).map(_.after(Vector(line)))
            )
        }
    }

  /** The leader check of a slot the forging fiber forges (decision 5): under inputs equal to the
    * schedule's, the slot's output evaluated again and the schedule's certificate; otherwise
    * [[Leadership.check]] afresh. Each evaluation borrows the VRF key the inputs name from the
    * credentials in force (none: they no longer hold it, [[Leadership.Keys]]).
    */
  private def scheduled(
      slot: BigInt,
      inputs: EpochInputs
  ): F[Option[Either[ForgeError, VrfCert]]] =
    schedule.answer(slot, inputs).flatMap {
      case LeaderSchedule.Answer.Led(cert) =>
        hot.keys.evaluating(inputs.vrf)(key =>
          Leadership.decide(
            slot,
            inputs.nonce,
            Leadership.Evaluator.again(key, cert),
            inputs.stake,
            inputs.total,
            inputs.activeSlotsCoeff
          )
        )
      case LeaderSchedule.Answer.NotLed    => F.pure(Some(Left(ForgeError.NotLeader(slot))))
      case LeaderSchedule.Answer.Uncovered => checked(slot, inputs)
    }

  /** The leader check of the reference's per-slot loop: [[Leadership.check]] of the slot. */
  private def checked(slot: BigInt, inputs: EpochInputs): F[Option[Either[ForgeError, VrfCert]]] =
    hot.keys.evaluating(inputs.vrf)(Leadership.check(slot, inputs, _))

  /** The per-slot decision of one slot, as the reference's forging thread makes it at the slot: the
    * attempt with [[Leadership.check]], its block handed over at once, and its lines published as
    * they are. It is the oracle of the differential test (#1101, §7); while forging is disabled the
    * slot is not attempted.
    */
  private[forge] def step(slot: BigInt): F[Either[Fatal, Unit]] =
    Forger.scoped(perSlot(slot))

  private def perSlot(slot: BigInt)(using Handle[F, NodeError], Raise[F, Fatal]): F[Unit] =
    hot.enabled.flatMap { on =>
      attempt(slot, checked, Some(Admitted.at(slot)))
        .flatMap {
          case built: Attempted.Built[F, S] =>
            handover(slot, built)
          case e: Attempted.Ended[F, S] => F.pure(e.held)
        }
        .flatMap(publish)
        .whenA(on)
    } *> watch

  // ---- the KES key (§5.5)

  /** The update of the KES key to the period of `slot`, a slot the slot driver handles and
    * `admitted` admits, with the credentials in force, under the reservation: the one recorded for
    * them and the period, or made now and recorded. None while forging is disabled.
    */
  private def periodUpdate(slot: BigInt, admitted: Admitted)(using
      Raise[F, Fatal]
  ): F[Option[(Long, Forger.KesResult)]] =
    shared.reservation.lock
      .surround(recordedOrMade(slot, Some(admitted)))
      // the driver's admission admits every slot it handles: `NotAdmitted` is not reached
      .map(_.flatMap((generation, made) => made.toOption.map(generation -> _)))

  /** The update of the KES key to the period of `slot` recorded for the credentials in force, read
    * as it is; or, where there is none, made now by [[HotKeys.updateIn]] and recorded, which it
    * does only for an attempt admitted at `slot` ([[NotAdmitted]] otherwise, and nothing is
    * recorded).
    */
  private def recordedOrMade(slot: BigInt, admitted: Option[Admitted])(using
      Raise[F, Fatal]
  ): F[Option[(Long, Either[NotAdmitted, Forger.KesResult])]] =
    val period = kesPeriodOf(slot)
    hot.inForce.get.flatMap { installed =>
      shared.records.get.map(_.get(installed.generation -> period)).flatMap {
        case Some(found) =>
          hot.enabled.map(on => Option.when(on)(installed.generation -> Right(found)))
        case None =>
          admitted.fold(
            hot.enabled.map(on => Option.when(on)(installed.generation -> Left(NotAdmitted(slot))))
          )(a =>
            hot
              .updateIn(slot, a)
              .flatTap(
                _.traverse_((generation, made) =>
                  made.traverse_(result =>
                    shared.records.update(
                      _.filter((key, _) => key._1 == generation && key._2 >= period - 1)
                        .updated(generation -> period, result)
                    )
                  )
                )
              )
          )
      }
    }

  // ---- publication

  /** The lines and counters of a slot, in order: the only place the forger's slot outcomes are
    * published. A held line keeps the time it was made; the KES key's failure is named here, in
    * slot order: `KESCouldNotEvolve` the first time for the credentials in force,
    * `KESKeyAlreadyPoisoned` after.
    */
  private def publish(held: Vector[Held[F]]): F[Unit] =
    held.traverse_ {
      case Held.Line(ForgeEvent.Loop(t), at) => trace(t, at)
      case Held.Line(e, at)                  => events.emitAt(at, e)
      case Held.Kes(slot, generation, Right(info), at) =>
        shared.published.update(_.announced(generation, kesPeriodOf(slot))) *>
          events.emitAt(at, ForgeEvent.StateInfo(info))
      case Held.Kes(slot, generation, Left(failure), at) =>
        shared.published.modify(_.named(generation, slot, failure)).flatMap(trace(_, at))
      case Held.Effect(run)     => run
      case Held.Withdrawal(run) => run
    }

  private def trace(t: ForgeTrace, at: Instant): F[Unit] =
    stats.record(geometry.epochOf(t.slot), t) *> events.emitAt(at, ForgeEvent.Loop(t))

  // ---- one attempt at a slot

  /** A step of an attempt that may end the slot early, as `withEarlyExit` does in the reference:
    * the left side is why the slot ended, after its trace.
    */
  private type Step[A] = EitherT[F, Why, A]

  /** Where an attempt's lines go: held, each with the time it was made, until the slot's outcome is
    * final.
    */
  private final class Holding(held: Ref[F, Vector[Held[F]]])(using Handle[F, NodeError]):
    def line(e: ForgeEvent): F[Unit] = now.flatMap(at => held.update(_ :+ Held.Line(e, at)))
    def kes(slot: BigInt, generation: Long, r: Either[KesEvolutionError, KesInfo]): F[Unit] =
      now.flatMap(at => held.update(_ :+ Held.Kes(slot, generation, r, at)))
    def effect(run: F[Unit]): F[Unit] = held.update(_ :+ Held.Effect(run))

    /** The transactions the selection had no verdict on, which leave the mempool once the attempt
      * counts: before its block is handed over, or when its refusal is published; never for an
      * attempt that is discarded.
      */
    def withdrawal(reasons: Map[Bytes, Rejection]): F[Unit] =
      held.update(_ :+ Held.Withdrawal(mempool.withdraw(reasons).void)).whenA(reasons.nonEmpty)
    def say(t: ForgeTrace): Step[Unit] = EitherT.liftF(line(ForgeEvent.Loop(t)))
    def giveUp[A](t: ForgeTrace): Step[A] = EitherT.left(line(ForgeEvent.Loop(t)).as(Why.Refused))
    def failed(slot: BigInt, error: ForgeError): F[Unit] =
      effect(stats.failed(geometry.epochOf(slot))) *> line(ForgeEvent.Failed(slot, error))

    /** The block built in `slot` failed a check of ours: the slot ends, its transactions (`ids`)
      * leave the mempool, valid or not, so that the next leader slot does not build the same block,
      * as the reference does with a forged block its own rules refuse (`NodeKernel.hs` 757-787),
      * and the node goes on (owner decision of 2026-11-14, #987).
      */
    def defect[A](slot: BigInt, epoch: BigInt, d: ForgeDefect, ids: Vector[Bytes]): Step[A] =
      EitherT.left(
        (effect(stats.defect(epoch)) *> line(ForgeEvent.Defect(slot, d, ids)) *>
          effect(
            assertions.unreachable(Property.Unreachable.ForgeDefect, Forger.Defective(slot, d)) *>
              mempool.removeTxsEvenIfValid(ids.toSet).void.whenA(ids.nonEmpty)
          )).as(Why.Refused)
      )

  private def perform[A](fa: F[A]): Step[A] = EitherT.liftF(fa)

  private def proceed[A](a: A): Step[A] = EitherT.rightT(a)

  /** A reload disabled forging while the slot was attempted: the slot ends with nothing more, as
    * the reference's forging thread ends when its block forging is removed.
    */
  private def disabled[A]: Step[A] = EitherT.leftT(Why.Disabled)

  /** The attempt, not admitted at its slot, would change the KES state (§5.5): it ends with nothing
    * changed, its reservation and the key's lock given back.
    */
  private def unpublished[A]: Step[A] = EitherT.leftT(Why.Unpublished)

  /** `section` with the KES key reserved: from a forge's update of the key to its signature (§5.5).
    */
  private def reserved[A](section: Step[A]): Step[A] =
    EitherT(shared.reservation.lock.surround(section.value))

  /** The slot, step by step as `forkBlockForging` 's `go` (`NodeKernel.hs` 573-796), up to the
    * block built and signed, with every line held: the block, or why the slot ended. `decide` is
    * the leader check: the per-slot check, or the schedule's with its re-check. `admitted` is the
    * attempt's admission at its slot, which it keeps throughout (§5.5): without it, the attempt
    * changes nothing, reads the period's update recorded and signs with a key already at the slot's
    * period, or ends, `Why.Unpublished`. A failure of the forger's own (`forgeFailed`) ends the
    * slot; one of the infrastructure stops the node.
    */
  private def attempt(
      slot: BigInt,
      decide: (BigInt, EpochInputs) => F[Option[Either[ForgeError, VrfCert]]],
      admitted: Option[Admitted]
  )(using handle: Handle[F, NodeError], terminal: Raise[F, Fatal]): F[Attempted[F, S]] =
    (Ref.of[F, Vector[Held[F]]](Vector.empty), hot.inForce.get.map(_.generation)).flatMapN {
      (held, generation) =>
        val hold = Holding(held)
        handle.attempt(F.defer(steps(slot, decide, hold, admitted).value)).flatMap {
          case Right(Right(prepared)) => held.get.map(Attempted.Built(prepared, _))
          case Right(Left(why))       => held.get.map(Attempted.Ended(_, why, generation))
          case Left(e: ForgeError) =>
            HotKeys
              .infrastructureFailure(e)
              .fold(
                hold.failed(slot, e) *>
                  held.get.map(Attempted.Ended[F, S](_, Why.Refused, generation))
              )(_.raise[F, Attempted[F, S]])
          case Left(other) => other.raise[F, Attempted[F, S]]
        }
    }

  private def steps(
      slot: BigInt,
      decide: (BigInt, EpochInputs) => F[Option[Either[ForgeError, VrfCert]]],
      hold: Holding,
      admitted: Option[Admitted]
  )(using Handle[F, NodeError], Raise[F, Fatal]): Step[Prepared[S]] =
    val epoch = geometry.epochOf(slot)
    val period = kesPeriodOf(slot)
    for
      _ <- hold.say(ForgeTrace.StartLeadershipCheck(slot))
      current <- perform(chain.get)
      context <- blockContext(current, slot).fold(hold.giveUp, proceed)
      _ <- hold.say(ForgeTrace.BlockContext(slot, context.blockNo, context.prev))
      _ <- hold.say(ForgeTrace.LedgerState(slot, context.prev))
      forecastable <- forecast(context.base, slot)
        .fold(r => hold.giveUp(ForgeTrace.NoLedgerView(slot, r)), proceed)
      crossed <- perform(crossedTo(context.base, epoch, slot))
      _ <- hold.say(ForgeTrace.LedgerView(slot))
      _ <- perform(pause(Forger.Point.Context(slot)))
      prepared <- reserved(
        signing(forecastable, epoch, period, context, crossed, decide, hold, admitted)
      )
    yield prepared

  /** From the forge state's update to the block signed, with the KES key reserved:
    * `checkShouldForge` (`Block/Forging.hs` 192-226), the forge state, then the leader check, then
    * whether a leader can forge; then the block built and signed. The slot, `forecastable`, has
    * passed the forecast check: the key the update leaves is written back for it.
    */
  private def signing(
      forecastable: Forecastable,
      epoch: BigInt,
      period: BigInt,
      context: Forger.Context[S],
      crossed: Forger.Crossed,
      decide: (BigInt, EpochInputs) => F[Option[Either[ForgeError, VrfCert]]],
      hold: Holding,
      admitted: Option[Admitted]
  )(using Handle[F, NodeError], Raise[F, Fatal]): Step[Prepared[S]] =
    val slot = forecastable.slot
    for
      _ <- perform(pause(Forger.Point.Reserved(slot)))
      record <- perform(recordedOrMade(slot, admitted)).flatMap(_.fold(disabled)(proceed))
      (generation, made) = record
      result <- made.fold(_ => unpublished, proceed)
      _ <- perform(hot.confirm(forecastable))
      key <- result match
        case Left(e) => perform(lift[KesInfo](Left(e)))
        case Right(Left(failure)) =>
          EitherT.left[KesInfo](hold.kes(slot, generation, Left(failure)).as(Why.Refused))
        case Right(Right(info)) => perform(hold.kes(slot, generation, Right(info)).as(info))
      // the VRF verification key of the credentials in force: a reload replaces it with the key
      // it read
      vrf <- perform(hot.locked(c => F.pure(c.vrf))).flatMap(_.fold(disabled)(proceed))
      inputs = Forger.inputsOf(
        geometry,
        epoch,
        crossed.view,
        pool.id,
        config.activeSlotsCoeff,
        vrf
      )
      // none: a reload replaced the VRF key or disabled forging since, and the slot ends as it
      // does when that happens before the block is signed ([[assemble]])
      cert <- perform(decide(slot, inputs)).flatMap {
        case None              => disabled
        case Some(Right(cert)) => proceed(cert)
        case Some(Left(_: ForgeError.NotLeader)) =>
          EitherT.left[VrfCert](
            hold
              .line(ForgeEvent.Loop(ForgeTrace.NodeNotLeader(slot)))
              .as(Why.NotLed(generation, key))
          )
        case Some(Left(e)) => perform(e.raise[F, VrfCert])
      }
      _ <- perform(pause(Forger.Point.Rechecked(slot)))
      // `praosCheckCanForge` (`Protocol/Praos.hs` 691-711)
      _ <-
        if key.start > period then
          hold.giveUp(
            ForgeTrace.NodeCannotForge(slot, CannotForge.KeyNotUsableYet(period, key.start))
          )
        else proceed(())
      _ <- hold.say(ForgeTrace.NodeIsLeader(slot))
      // the boundary is the expensive part of the tick (#131): off the compute pool, as the
      // epoch's leader view is
      ticked <- perform(
        F.blocking(
          Forger.blockLedger(
            Globals(globals, geometry),
            config.forks.transition(geometry.k),
            crossed,
            slot
          )
        ).flatMap(lift)
      )
      _ <- hold.say(ForgeTrace.ForgeTickedLedgerState(slot, context.prev))
      _ <- perform(
        lift(
          Either.cond(
            ticked.params.protocolMajor >= 9,
            (),
            ForgeError.UnsupportedProtocol(
              ForgeProtocolProblem.Version(ticked.params.protocolMajor)
            )
          )
        )
      )
      snapshot <- perform(mempool.snapshot)
      selected <- ticked match
        // the pool's snapshot of the slot, on the ticked ledger, and the longest prefix of it the
        // block's capacity takes (`getSnapshotFor` and `snapshotTake`, `NodeKernel.hs:697-707` at
        // ouroboros-consensus 82ecba32; `partitionMempool` with no endorser block for Conway,
        // `NodeKernel/Forge.hs:786-801` at 2416493b)
        case Forger.Ticked.InConway(ledger) =>
          perform(select(context.base, slot, ledger)).map((chosen, withheld) =>
            (
              BlockBuilder.Assembly.InConway(
                BlockBuilder.body(chosen.map(h => BlockBuilder.piece(h.admitted)))
              ),
              withheld,
              Option.empty[CertifyDecision]
            )
          )
        // whether the block certifies what its parent announced (`decideLeiosCertify`), then
        // the pool's snapshot of the slot, partitioned for the ranking block and the endorser
        // block it announces, on the parent with the certified closure applied when it
        // certifies (`partitionMempool`, `NodeKernel/Forge.hs:760-869` at ouroboros-consensus
        // 2416493b; [[DijkstraForge]])
        case Forger.Ticked.InDijkstra(nes, unticked) =>
          val parent = DijkstraParent(
            context.base.state,
            context.base.tipRef,
            unticked,
            nes,
            LeiosCertification.announcement(crossed.view.protocol.dep)
          )
          for
            ebLimit <- perform(endorserLimit(ticked.params))
            decision <- perform(
              DijkstraForge.decide(slot, BigInt(geometry.slotLengthMillis), parent, certifying)
            )
            _ <- DijkstraForge
              .decided(slot, decision)
              .traverse_(t => perform(hold.line(ForgeEvent.Leios(t))))
            split <- perform(
              DijkstraForge.partition(
                slot,
                parent,
                decision,
                Capacity.dijkstraBlockUnit(ticked.params),
                ebLimit,
                dijkstraSnapshots,
                certifying,
                tickDijkstra(_, slot),
                clock.at(context.base.state, Era.Dijkstra, slot)
              )
            )
          // the held transactions the rules have no verdict on leave the pool with their
          // reason, as the Conway path's do (issue 584)
          yield (split.body, split.withheld, Some(split.decision))
      (body, withheld, decision) = selected
      _ <- perform(hold.withdrawal(withheld))
      _ <- hold.say(
        ForgeTrace.ForgingMempoolSnapshot(
          slot,
          context.prev,
          snapshot.tipHash,
          snapshot.tipSlot + 1
        )
      )
      // the ranking block's transactions and its endorser block's: both leave the pool when the
      // block is refused (`addBlockToChainDB`, `NodeKernel/Forge.hs:543-592` at
      // ouroboros-consensus 2416493b)
      ids = body.ids ++ body.endorsedIds
      _ <- perform(pause(Forger.Point.Signing(slot)))
      signed <- perform(assemble(context, forecastable, ticked.params, cert, vrf, body, admitted))
        .flatMap(_.fold(disabled)(proceed))
        .flatMap(_.fold(_ => unpublished, proceed))
        .flatMap(_.fold(d => hold.defect(slot, epoch, d, ids), proceed))
      (block, signedWith) = signed
      // the endorser block's lines `forgeShelleyBlock` traces once the header is made
      // (`Shelley/Ledger/Forge.hs:124-138, 202-208` at ouroboros-consensus 2416493b)
      _ <- DijkstraForge
        .forged(slot, block.header.hash, block.body)
        .traverse_(t => perform(hold.line(ForgeEvent.Leios(t))))
      _ <- hold.say(
        ForgeTrace.ForgedBlock(
          slot,
          context.prev,
          block.header.hash,
          block.header.blockNo,
          block.header.prevHash
        )
      )
      _ <- perform(
        hold.line(
          ForgeEvent.Forged(
            slot,
            epoch,
            block.header.blockNo,
            block.header.hash,
            block.header.prevHash,
            block.body.ids,
            block.body.size,
            period,
            block.relativeKesPeriod,
            inputs.stake,
            inputs.total,
            block.counter
          )
        )
      )
      _ <- perform(hold.effect(forged(slot, block)))
    yield Prepared(context, block, ids, inputs, signedWith, decision)

  /** The block handed to chain selection (`Follower.adoptForged`) and what came of it, its lines
    * held.
    */
  private def handover(slot: BigInt, built: Attempted.Built[F, S])(using
      handle: Handle[F, NodeError],
      terminal: Raise[F, Fatal]
  ): F[Vector[Held[F]]] =
    val prepared = built.prepared
    val withdrawals = built.held.collect { case Held.Withdrawal(run) => run }
    val lines = built.held.filter {
      case Held.Withdrawal(_)                                      => false
      case Held.Line(_, _) | Held.Kes(_, _, _, _) | Held.Effect(_) => true
    }
    pause(Forger.Point.Handing(slot)) *> Ref.of[F, Vector[Held[F]]](Vector.empty).flatMap { held =>
      val hold = Holding(held)
      // the check of the credentials and the submission under the key's lock, so that no reload
      // lands between them: a block signed with credentials no longer in force is not handed over,
      // as the reference's forging thread of replaced credentials ends (C19); the failures of the
      // adoption come back as values, so that none crosses the lock's scope
      hot
        .lockedIn { (_, generation) =>
          if generation != prepared.generation then F.pure(Option.empty[Either[NodeError, Unit]])
          else
            handle
              .attempt(F.defer(withdrawals.sequence_ *> adopted(slot, prepared, hold)))
              .map(Option(_))
        }
        .map(_.flatten)
        .flatMap {
          case None           => discarded(slot, DiscardReason.Credentials, built).map(Vector(_))
          case Some(Right(_)) => held.get.map(lines ++ _)
          case Some(Left(e: ForgeError)) =>
            HotKeys
              .infrastructureFailure(e)
              .fold(hold.failed(slot, e) *> held.get.map(lines ++ _))(
                _.raise[F, Vector[Held[F]]]
              )
          case Some(Left(other)) => other.raise[F, Vector[Held[F]]]
        }
    }

  /** The block's endorser block kept ([[stored]]), then the block handed to chain selection, as the
    * forge runs `onForgedLeiosEb` before `addBlockToChainDB` (`NodeKernel/Forge.hs:283-305` at
    * ouroboros-consensus 2416493b).
    */
  private def adopted(slot: BigInt, prepared: Prepared[S], hold: Holding)(using
      Handle[F, NodeError]
  ): F[Unit] =
    val block = prepared.block
    val epoch = geometry.epochOf(slot)
    block.endorsed.traverse_(stored) *> follower.submitForged(block.header, block.raw).flatMap {
      submitted =>
        adoption(slot, epoch, prepared, hold, submitted.adoption) *>
          Forger
            .notKept(slot, block.header.hash, submitted.retention)
            .traverse_(event => hold.line(event))
    }

  /** `onForgedLeiosEb` of the endorser block forged with a block: its announcement, body and
    * closure through the handlers a peer's are taken by ([[OnForgedLeiosEb]]), all written before
    * the block is handed over, as the forge must not announce what it has not stored. The store's
    * failure is the node's storage error: it comes back from the handover as a value, outside the
    * key's lock, and stops the node ([[Forger.scoped]]), as a failed write of the reference's
    * single writer does.
    */
  private def stored(e: AnnouncedEb)(using Handle[F, NodeError]): F[Unit] =
    onForgedEb.traverse_(on =>
      Handle
        .allow[LeiosStoreError](on.forged(e.announcing, e.forged))
        .rescue(error => (StorageError.LeiosStore(error): NodeError).raise[F, Unit])
    )

  /** What the forger does with what chain selection made of its block. */
  private def adoption(
      slot: BigInt,
      epoch: BigInt,
      prepared: Prepared[S],
      hold: Holding,
      selected: Follower.Adoption
  )(using Handle[F, NodeError]): F[Unit] =
    val block = prepared.block
    selected match
      case Follower.Adoption.Adopted(ref) =>
        for
          at <- now
          latency = Duration.between(geometry.slotStart(slot), at).toMillis
          _ <- hold.effect(stats.latency(epoch, latency.max(0L)))
          _ <- hold.effect(pending.update(_ :+ Forger.Forged(ref, slot)))
          _ <- hold.line(
            ForgeEvent.Loop(
              ForgeTrace.AdoptedBlock(slot, ref.hash, block.estimatedSize, block.body.ids)
            )
          )
          _ <- hold.line(ForgeEvent.Adopted(ref, Some(latency)))
        yield ()
      case Follower.Adoption.Lost(reason) =>
        hold.line(ForgeEvent.Loop(ForgeTrace.DidntAdoptBlock(slot, block.header.hash))) *>
          hold.line(ForgeEvent.Lost(slot, block.header.hash, reason.message))
      // A refusal of our own block: the block's transactions leave the pool, valid or not, so
      // that the next leader slot does not repeat the failure (`NodeKernel.hs` 770-783), and the
      // slot ends.
      case Follower.Adoption.Invalid(e) =>
        val ids = prepared.ids.toSet
        hold.line(ForgeEvent.Loop(ForgeTrace.ForgedInvalidBlock(slot, block.header.hash, e))) *>
          hold.effect(
            assertions.unreachable(
              Property.Unreachable.ForgedInvalid,
              Forger.Refused(slot, block.header.hash, e)
            ) *> mempool.removeTxsEvenIfValid(ids).void.whenA(ids.nonEmpty)
          )
      // No verdict on it (the evaluator or a crypto provider failed on it, a quantity of the state
      // is out of range, or the rules threw; in the reference an exception in the linked forging
      // thread that stops the node, consensus 82ecba32 NodeKernel.hs forkBlockForging): handled
      // as a refusal, and the follower reports it (`Chain.BlockNoVerdict`). A failure of our own
      // implementation does not stop the node (owner decision 2026-10-28, issue 567, #584).
      case Follower.Adoption.NoVerdict(_) =>
        val ids = prepared.ids.toSet
        hold.effect(mempool.removeTxsEvenIfValid(ids).void.whenA(ids.nonEmpty))

  /** A block forged in `slot`, asserted: that Scarab forges, and whether with transactions. */
  private def forged(slot: BigInt, block: Forger.Built): F[Unit] =
    def made = Forger.Made(slot, block.header.hash, block.body.ids.size)
    assertions.sometimes(Property.Sometimes.Forged, true, made) *>
      assertions.sometimes(Property.Sometimes.ForgedWithTransactions, block.body.ids.nonEmpty, made)

  /** The chain to forge on in `slot`, as `forkBlockForging` picks it through
    * `mkCurrentBlockContext` (consensus 82ecba329 NodeKernel.hs:585 and 831–891), or the trace of
    * why there is none.
    *
    * Without a volatile block the block follows the anchor (the immutable tip) if the anchor is in
    * an earlier slot, and otherwise the slot is immutable (`TraceSlotIsImmutable`, 846–850). A tip
    * in an earlier slot is extended; a tip in a later one gives up (`TraceBlockFromFuture`, 880).
    * When the tip holds the slot (the `EQ` case, 883–891), a boundary block is extended in its own
    * slot (885–888): the block follows it with the next number, as after any tip
    * (`blockContextFromPrevHeader`, whose `succ` is right for a boundary block, 819–820). Any other
    * block gets an alternative, "same block no and same predecessor" (889–891): the chain without
    * its tip, which is the anchor when the tip is the only volatile block. Chain selection then
    * decides between the two as for any pair of blocks of one height ([[Follower.adoptForged]]).
    */
  private def blockContext(
      current: VolatileChain[S],
      slot: BigInt
  ): Either[ForgeTrace, Forger.Context[S]] =
    current.blocks.lastOption match
      case None =>
        current.anchor.block match
          case Some(anchor) if anchor.slot >= slot =>
            Left(ForgeTrace.SlotIsImmutable(slot, anchor.point, anchor.blockNo))
          case _ => Right(Forger.Context(current))
      case Some((tip, _)) =>
        if tip.slot < slot then Right(Forger.Context(current))
        else if tip.slot > slot then Left(ForgeTrace.BlockFromFuture(slot, tip.slot))
        else if tip.boundary then Right(Forger.Context(current))
        else Right(Forger.Context(current.copy(blocks = current.blocks.dropRight(1))))

  /** Whether the ledger view of `slot` can be forecast from the ledger state at the tip of `base`,
    * the predecessor of the block to forge (`NodeKernel.hs` 611-629): `ledgerViewForecastAt` of the
    * Praos eras (consensus 82ecba329 `Shelley/Ledger/SupportsProtocol.hs` 100-127) answers for the
    * tip's own slot and for every slot before `maxFor`, the stability window after the successor of
    * the tip's slot (`succWithOrigin`, zero at Origin), and for no other. A slot it answers for is
    * the evidence the KES write-back needs ([[Forecastable]]).
    */
  private def forecast(
      base: VolatileChain[S],
      slot: BigInt
  ): Either[OutsideForecastRange, Forecastable] =
    Forecastable.check(base.tipRef.map(_.slot), forecastWindow, slot)

  private def lift[A](e: Either[NodeError, A])(using Handle[F, NodeError]): F[A] =
    e.fold(_.raise[F, A], F.pure)

  /** The transactions of a Conway block in `slot` on `base`, and the held ones the rules have no
    * verdict on: the pool's snapshot of the slot on `ticked`, the parent's ledger ticked to it,
    * with the clock of a transaction in a block of the slot on `base` (`getSnapshotFor`,
    * [[ConwaySnapshots]]), cut to the block's capacity ([[BlockBuilder.take]]). Those with no
    * verdict leave the pool with their reason, and the block is built from the rest: the owner's
    * decision of 2026-10-28 (issue 584), where the reference's linked forging thread stops the node
    * (consensus 82ecba32 Node/NodeKernel.hs `forkBlockForging`); they leave it only once the
    * attempt counts (#1101).
    */
  private def select(
      base: VolatileChain[S],
      slot: BigInt,
      ticked: ConwayPoolLedger
  )(using Handle[F, NodeError]): F[(Vector[ConwayHeld], Map[Bytes, Rejection])] =
    conwaySnapshots
      .forSlot(ticked, base.tip, slot, clock.at(base.state, Era.Conway, slot))
      .map(snapshot =>
        BlockBuilder.take(snapshot, BlockBuilder.Limits.of(ticked.params).capacity) ->
          snapshot.withheld
      )

  /** The capacity of the endorser block forged in a slot: `leiosEndorserBlockCapacity` scaled by
    * the share read for the slot (`scaleEbCapacity` of `readEbCapacityScale`,
    * `NodeKernel/Forge.hs:784-789` at ouroboros-consensus 2416493b), or none where the node keeps
    * no endorser block ([[onForgedEb]]). Parameters whose endorser block the LeiosFetch codec
    * cannot carry stop the node, as `guardLeiosWireLimit` 's `error` stops the reference's
    * (`Shelley/Ledger/Mempool.hs:915-939`).
    */
  private def endorserLimit(params: ProtocolParams)(using Raise[F, Fatal]): F[EbMeasure] =
    Capacity
      .endorserBlockCapacity(params)
      .fold(
        over =>
          (Fatal.Internal(
            ForgeError.UnsupportedProtocol(
              ForgeProtocolProblem.EndorserBlockReferencesOverWire(over.parameter, over.wire)
            )
          ): Fatal).raise[F, EbMeasure],
        capacity =>
          onForgedEb.fold(F.pure(EbMeasure.zero))(_ => ebCapacityScale.map(_.apply(capacity)))
      )

  /** Build and sign the block (`forgeBlock`). The checks here are Scarab's own and never refuse a
    * block the steps before let through: the key's window, and the body size and the decoding of
    * the block just built, which only a bug of ours can fail ([[ForgeDefect]]; the body is checked
    * before the key evolves or signs). None: forging was disabled meanwhile, or a reload installed
    * another VRF key than `vrf`, which proved `cert`; nothing was signed, as the reference's
    * forging thread ends when its block forging is replaced.
    */
  private def assemble(
      context: Forger.Context[S],
      forecastable: Forecastable,
      params: ProtocolParams,
      cert: VrfCert,
      vrf: Bytes,
      body: BlockBuilder.Assembly,
      admitted: Option[Admitted]
  )(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Option[Either[NotAdmitted, Either[ForgeDefect, (Forger.Built, Long)]]]] =
    val slot = forecastable.slot
    val kesPeriod = kesPeriodOf(slot)
    def signedWith(c: HotKeys.Signer)(using
        Raise[F, Fatal]
    ): F[Either[NotAdmitted, Either[ForgeError, (Bytes, Bytes, Int, BigInt)]]] =
      val start = c.opCert.kesPeriod
      val header =
        for _ <- Either.cond(
            start <= kesPeriod && kesPeriod < start + config.maxKesEvolutions,
            (),
            ForgeError.KeyPeriodOutOfRange(kesPeriod, start, config.maxKesEvolutions)
          )
        yield body.headerBody(
          context.blockNo,
          slot,
          context.base.tipRef.map(_.hash),
          c.coldVkey,
          c.vrf,
          cert,
          c.opCert,
          protocolVersion
        )
      header match
        case Left(e)           => F.pure(Right(Left(e)))
        case Right(headerBody) =>
          // the key at the slot's period before it signs in it: as it is where it is there, else
          // moved there, only by an attempt admitted at the slot, and written back (§5.5)
          hot.evolvedFor(c, forecastable, admitted).flatMap {
            case Left(early)    => F.pure(Left(early))
            case Right(Left(e)) => F.pure(Right(Left(e)))
            case Right(Right(_)) =>
              F.blocking(c.kes.sign(headerBody))
                .map(signature =>
                  Right(Right((headerBody, signature, c.kes.period, c.opCert.sequence)))
                )
          }
    // the header, the key's evolution and the signature all use the credentials of one moment: a
    // reload (SIGHUP) waits until the block is signed; the generation of those credentials goes
    // with the block, so that it is handed over only while they are in force
    val assembled
        : F[Option[(Either[NotAdmitted, Either[ForgeError, (Bytes, Bytes, Int, BigInt)]], Long)]] =
      hot
        .lockedIn { (c, generation) =>
          if c.vrf != vrf then F.pure(None)
          else signedWith(c).map(signed => Some(signed -> generation))
        }
        .map(_.flatten)
    val oversized = Option
      .when(body.size > params.maxBlockBodySize)(
        ForgeDefect.BodySize(body.size, params.maxBlockBodySize)
      )
      .orElse(body.endorserBlockOverWire)
    oversized.fold(
      assembled.flatMap(
        _.traverse((signed, generation) =>
          signed.traverse(made =>
            lift(made).map { (headerBody, signature, relative, counter) =>
              val raw = body.block(headerBody, signature)
              Block
                .decode(raw, geometry.byron.epochSlots)
                .left
                .map(ForgeDefect.BlockDecode(_))
                .map(decoded =>
                  Forger.Built(
                    decoded.header,
                    raw,
                    body,
                    relative,
                    counter,
                    Forger.announced(decoded, body)
                  ) -> generation
                )
            }
          )
        )
      )
    )(defect => F.pure(Some(Right(Left(defect)))))

  /** A Dijkstra state at `slot`, in Dijkstra ([[Forger.tickedTo]]). */
  private def tickDijkstra(
      nes: NewEpochState[Dijkstra],
      slot: BigInt
  ): Either[NodeError, NewEpochState[Dijkstra]] =
    ticked(Forger.TipLedger.InDijkstra(nes), slot).flatMap {
      case Forger.TipLedger.InDijkstra(next) => Right(next)
      case Forger.TipLedger.InConway(_) =>
        Left(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftDijkstra))
    }

  private def ticked(ledger: Forger.TipLedger, slot: BigInt): Either[NodeError, Forger.TipLedger] =
    Forger.tickedTo(Globals(globals, geometry), config.forks.transition(geometry.k), ledger, slot)

  /** The tip's ledger state in the node's reference build ([[Forger.ledgerAt]]). */
  private def ledgerAt(
      current: VolatileChain[S],
      epoch: BigInt,
      slot: BigInt
  ): Either[NodeError, Forger.TipLedger] =
    Forger.ledgerAt(current.state, current.tipRef, epoch, slot, globals.referenceBuild)

  /** The tip's ledger state and the ledger view of `epoch` 's leader check ([[Forger.crossed]];
    * cached per tip and epoch, as every slot of the epoch needs the view; a chain at its origin,
    * whose tip names no state, is not cached). This is where the ledger view of the slot comes
    * from; the refusals here are Scarab's own (only Praos with Conway transactions is forged, and
    * in the Leios build Praos2 on Dijkstra, on a fully validated ledger: [[Forger.ledgerAt]]).
    */
  private def crossedTo(
      current: VolatileChain[S],
      epoch: BigInt,
      slot: BigInt
  )(using Handle[F, NodeError]): F[Forger.Crossed] =
    val key = (current.tipRef.map(_.hash), epoch)
    cache.get.flatMap {
      case Some(c) if c.key == key && key._1.nonEmpty => F.pure(c)
      case _ =>
        for
          ledger <- lift(ledgerAt(current, epoch, slot))
          c <- F.blocking(Forger.crossed(key, ledger, current.state.protocol, epoch))
          _ <- cache.set(Some(c))
        yield c
    }

  /** The inputs of the leader check of `slot` forecast from the tip of `base`, with the VRF key
    * whose verification key is `vrf`: those [[attempt]] checks the slot with when `base` is the
    * chain it builds on, by the same derivation ([[Forger.ledgerAt]], [[Forger.leaderView]],
    * [[Forger.inputsOf]]) without the cache and the tick of the block's own ledger.
    */
  private[forge] def inputsAt(
      base: VolatileChain[S],
      slot: BigInt,
      vrf: Bytes
  ): Either[NodeError, EpochInputs] =
    val epoch = geometry.epochOf(slot)
    ledgerAt(base, epoch, slot)
      .map(ledger =>
        Forger.inputsOf(
          geometry,
          epoch,
          Forger.leaderView(ledger, base.state.protocol, epoch),
          pool.id,
          config.activeSlotsCoeff,
          vrf
        )
      )

  /** The leader view of `epoch` forecast from the tip of `base`, by the derivation of [[inputsAt]],
    * kept for the last few tips and epochs asked about (by the tip's hash, which names the state; a
    * chain at its origin is not kept): what the slot driver decides a slot not claimed with, and
    * the scheduler derives the inputs from, with no ledger tick.
    */
  private def viewOf(
      base: VolatileChain[S],
      epoch: BigInt,
      slot: BigInt
  ): F[Either[NodeError, Forger.LeaderView]] =
    val key = (base.tipRef.map(_.hash), epoch)
    viewed.get.flatMap(
      _.collectFirst { case (k, v) if k == key && key._1.nonEmpty => v }.fold(
        F.delay(
          ledgerAt(base, epoch, slot)
            .map(ledger => Forger.leaderView(ledger, base.state.protocol, epoch))
        ).flatTap(
          _.traverse_(v =>
            viewed.update(kept => ((key -> v) +: kept.filterNot(_._1 == key)).take(Forger.Viewed))
          )
        )
      )(v => F.pure(Right(v)))
    )

  /** Forged blocks that are no longer on our chain have been replaced by a better one. */
  private def watch: F[Unit] =
    pending.get.flatMap { list =>
      if list.isEmpty then F.unit
      else
        chain.get.flatMap { current =>
          val onChain = current.blocks.map(_._1.hash).toSet ++ current.anchor.block.map(_.hash)
          val byNumber = current.blocks.map((r, _) => r.blockNo -> r.hash).toMap
          val tipNo = current.tipRef.map(_.blockNo)
          val (lost, keep) = list.partition { f =>
            !onChain(f.ref.hash) && byNumber.contains(f.ref.blockNo)
          }
          // forgotten once it is immutable (no longer in the volatile part but known as the anchor)
          val settled = keep.filter(f => tipNo.exists(_ >= f.ref.blockNo + current.k))
          pending.set(keep.filterNot(settled.contains)) *>
            lost.traverse_(f =>
              stats.replaced(geometry.epochOf(f.slot)) *>
                events.emit(
                  ForgeEvent.Lost(
                    f.slot,
                    f.ref.hash,
                    "replaced by another block at the same height"
                  )
                )
            )
        }
    }

object Forger:

  /** How long the warm waits before it looks again at a tip whose closure is not complete yet:
    * about a slot of the testnet (`Forger.warming`, #1380).
    */
  private[forge] val WarmRetry: FiniteDuration = 1.second

  /** The line a forged block that could not be kept gets: only for a failure of the node's own
    * ([[Follower.Retention]]), not for a block the reference's ChainDB would not keep either, nor
    * while no selection worker runs.
    */
  private[forge] def notKept(
      slot: BigInt,
      hash: Bytes,
      retention: Follower.Retention
  ): Option[ForgeEvent] = retention match
    case Follower.Retention.Unmatched(_) | Follower.Retention.Failed(_) |
        Follower.Retention.Defect(_) =>
      Some(ForgeEvent.NotKept(slot, hash, retention))
    case Follower.Retention.Kept | Follower.Retention.Present | Follower.Retention.TooOld |
        Follower.Retention.KnownInvalid | Follower.Retention.NoVerdict |
        Follower.Retention.NoWorker =>
      None

  /** The most [[Forger]] sleeps at once: a century, far inside what a `FiniteDuration` holds. */
  val LongestWait: FiniteDuration = 36500.days

  /** How long before a led slot's onset the forging fiber forges it (#1101, Q6): half the trial
    * devnet's 0.1 s slot, far inside the reference's 2 s clock skew, and Amaru's margin.
    */
  val WakeMargin: FiniteDuration = 50.millis

  /** The KES periods left at or below which `stateInfo` is a Warning or an Alert, and is logged in
    * every slot whose checks reach the key (cardano-node 11.1.3 `Tracers/KESInfo.hs` 129-150).
    */
  val KesWarning: BigInt = 7

  /** How many leader views [[Forger]] keeps: the base and its predecessor, each for two epochs. */
  private val Viewed: Int = 4

  /** The time from `from` to `to`, none if `to` is earlier. */
  private[forge] def between(from: Instant, to: Instant): FiniteDuration =
    val d = Duration.between(from, to)
    if d.isNegative then 0.nanos else d.toNanos.nanos

  /** `slots × (1 - (1 - f)^σ)`, σ the pool's share of the distribution: the expected number of
    * slots the pool leads in the epoch of `inputs`, for the operator to compare with (#1101, §5.6).
    * Display only: floating point, never used for a decision.
    */
  def expected(inputs: EpochInputs): Double =
    val f = inputs.activeSlots.numerator.toDouble / inputs.activeSlots.denominator.toDouble
    val sigma =
      if inputs.total <= 0 then 0.0
      else (BigDecimal(inputs.stake) / BigDecimal(inputs.total)).toDouble
    (inputs.end - inputs.first).toDouble * (1 - math.pow(1 - f, sigma))

  /** One epoch's schedule for the gauges: the led slots found so far, the expected number, and
    * whether it covers the epoch.
    */
  final case class ScheduleGauge(led: Int, expected: Double, complete: Boolean)

  /** What the gauges show of the schedule: the epoch the clock is in, the next, and the first led
    * slot from the clock's slot on.
    */
  final case class ScheduleView(
      current: Option[ScheduleGauge],
      next: Option[ScheduleGauge],
      nextLeader: Option[BigInt]
  )

  /** Where a test suspends a fiber, to drive an interleaving (#1101, §7). */
  enum Point:
    /** The scheduler has derived the inputs in force and is about to compute from them. */
    case Inputs(epochs: Vector[BigInt])

    /** The forging fiber's wake for a slot has come, and it is about to claim the slot. */
    case Waking(slot: BigInt)

    /** The forging fiber has claimed a slot and is about to make its early attempt. */
    case Woke(slot: BigInt)

    /** A forge has its block context and the slot's ledger view, and is about to update the KES key
      * and make the leader check again.
      */
    case Context(slot: BigInt)

    /** A forge has made the leader check again and is about to build its block. */
    case Rechecked(slot: BigInt)

    /** A forge has reserved the KES key and is about to update it to the slot's period. */
    case Reserved(slot: BigInt)

    /** A forge is about to sign, the KES key reserved. */
    case Signing(slot: BigInt)

    /** An early attempt has built and signed its block, before the slot's onset. */
    case Signed(slot: BigInt)

    /** An early attempt ended without a block before the slot's onset, and is pending. */
    case Refused(slot: BigInt)

    /** A pending early attempt has read the clock and is about to wait for the slot's forge to
      * begin: its onset, or the slot driver's take of it.
      */
    case Waiting(slot: BigInt)

    /** The slot driver found a slot led and is about to hand it to the forging fiber. */
    case Deciding(slot: BigInt)

    /** The slot driver found forging disabled at a slot, and is about to settle it. */
    case Disabled(slot: BigInt)

    /** The slot driver has taken a slot with forging enabled, and is about to make or read the KES
      * key's update of its period.
      */
    case Updating(slot: BigInt)

    /** A forge is about to hand its block to chain selection. */
    case Handing(slot: BigInt)

  /** The update of the KES key to a period: the key's state, its failure, or a failure of the
    * forger's own.
    */
  private[forge] type KesResult = Either[ForgeError, Either[KesEvolutionError, KesInfo]]

  /** What one slot's attempt holds until the slot's outcome is final (#1101, §5.3): its lines, each
    * with the time it was made; the KES key's state or failure, whose kind the publisher names; and
    * the counters and other effects of the outcome.
    */
  private[forge] enum Held[F[_]]:
    case Line(event: ForgeEvent, at: Instant)
    case Kes(
        slot: BigInt,
        generation: Long,
        result: Either[KesEvolutionError, KesInfo],
        at: Instant
    )
    case Effect(run: F[Unit])

    /** Transactions leaving the mempool, run before a block is handed over or with the lines. */
    case Withdrawal(run: F[Unit])

  /** Why an attempt ended without a block. */
  private[forge] enum Why:
    /** A check refused the slot, after its line. */
    case Refused

    /** The slot is not led under the inputs in force, with the KES key's state it was checked with.
      */
    case NotLed(generation: Long, info: KesInfo)

    /** Forging was disabled while the slot was attempted. */
    case Disabled

    /** The attempt, not admitted at its slot, would change the KES state (§5.5): it changed
      * nothing, and is made again once admitted.
      */
    case Unpublished

  /** `trace` as the one line that ends a slot, made at the time it is given. */
  private[forge] def traced[F[_]](trace: ForgeTrace): Instant => Vector[Held[F]] =
    at => Vector(Held.Line(ForgeEvent.Loop(trace), at))

  private[forge] def reasonOf(why: Why): DiscardReason = why match
    case Why.NotLed(_, _)                             => DiscardReason.NotLed
    case Why.Refused | Why.Disabled | Why.Unpublished => DiscardReason.Refused

  /** A block built and signed and what it was made from: its block context, the slot's inputs, the
    * transactions taken, and the generation of the credentials that signed it.
    */
  private[forge] final case class Prepared[S](
      context: Context[S],
      block: Built,
      ids: Vector[Bytes],
      inputs: EpochInputs,
      generation: Long,
      certify: Option[CertifyDecision] = None
  ):
    /** The endorser block and announcing block this block, built early, did not certify only
      * because the closure or the certificate was missing then: what a forge at the slot's onset
      * may find present ([[Forger.holds]]).
      */
    def certifiable: Option[(EbPoint, Option[Bytes])] = certify.flatMap {
      case CertifyDecision.ClosureNotDownloaded(point) =>
        Some(point -> context.base.tipRef.map(_.hash))
      case CertifyDecision.NoCertificate(point) => Some(point -> context.base.tipRef.map(_.hash))
      case CertifyDecision.NothingAnnounced | CertifyDecision.TooEarly(_, _) |
          CertifyDecision.Certify(_, _) =>
        None
    }

  /** What an attempt came to before its block is handed over; an attempt that ended carries the
    * generation of the credentials in force when it began.
    */
  private[forge] enum Attempted[F[_], S]:
    case Built(prepared: Prepared[S], held: Vector[Held[F]])
    case Ended(held: Vector[Held[F]], why: Why, generation: Long)

  /** The final result of a slot the forging fiber owns, which the slot driver publishes. */
  private[forge] enum Final[F[_]]:
    /** A block handed over, or a refusal that stands: published as they are, with the generation of
      * the credentials of the attempt they are of.
      */
    case Lines(held: Vector[Held[F]], generation: Long)

    /** Not led: published as any slot not led is, after the discarded attempts' lines. */
    case NotLed(discards: Vector[Held[F]], generation: Long, info: KesInfo)

    /** Forging was disabled at the slot: nothing but the discarded attempts' lines. */
    case Skipped(discards: Vector[Held[F]])

    /** The same, after the lines of the attempts discarded before it. */
    def after(prefix: Vector[Held[F]]): Final[F] = this match
      case Lines(held, g)            => Lines(prefix ++ held, g)
      case NotLed(discards, g, info) => NotLed(prefix ++ discards, g, info)
      case Skipped(discards)         => Skipped(prefix ++ discards)

  /** What the slot driver decided of a slot no fiber had claimed. */
  private[forge] enum Decision[F[_]]:
    /** Forging is disabled: nothing. */
    case Disabled()

    /** The slot ends here, with these lines. */
    case Ends(lines: Vector[Held[F]])

    /** The slot is led, or its check failed otherwise than by not leading it: the forging fiber
      * forges it.
      */
    case Led()

  /** Who owns a slot's outcome (§5.4): the slot driver, or the forging fiber, which hands the final
    * result over; `Handed` is a slot the driver gave the forging fiber to forge at once, with the
    * driver's admission (§5.5).
    */
  private[forge] enum Claim[F[_]]:
    case Driver()
    case Forging(result: Deferred[F, Final[F]])
    case Handed(result: Deferred[F, Final[F]], admitted: Admitted)

  /** The next slot the driver handles on its way to `latest`: a claimed slot before it, or it. */
  private[forge] enum Next[F[_]]:
    case Claimed(slot: BigInt, claim: Claim[F])
    case Latest(claim: Option[Claim[F]])

  /** One claim per slot, set once, the slot driver's resolved-through `mark` (the highest slot
    * whose outcome it has settled, the slots it passed over included), and `taken`, the clock
    * fiber's publication that the slot driver took last, which admits the slots up to it (§5.5):
    * their forges have begun. A slot at or below the mark cannot be claimed, and the forging fiber
    * claims a slot only once the mark is just before it.
    */
  private[forge] final case class Claims[F[_]](
      mark: BigInt,
      owned: SortedMap[BigInt, Claim[F]],
      taken: Option[Admitted]
  ):
    /** The next slot on the way to `latest`, the publication the slot driver takes, which is kept
      * as `taken`; the mark just before that slot: the slots passed over on the way are settled
      * with no outcome.
      */
    def take(latest: Admitted): (Claims[F], Next[F]) =
      val took = copy(taken = Some(latest))
      owned.iteratorFrom(mark + 1).nextOption().filter(_._1 < latest.through) match
        case Some((slot, claim)) =>
          (took.copy(mark = mark.max(slot - 1)), Next.Claimed(slot, claim))
        case None =>
          (took.copy(mark = mark.max(latest.through - 1)), Next.Latest(owned.get(latest.through)))

    /** The slot driver's admission of `slot`, if it has taken it or a later slot. */
    def takenFor(slot: BigInt): Option[Admitted] = taken.filter(_.admits(slot))

    /** `slot` settled: the mark at it, the claims up to it gone. */
    def settled(slot: BigInt): Claims[F] =
      copy(mark = mark.max(slot), owned = owned.rangeFrom(slot + 1))

    /** `claim` on `slot` unless one is there, and the one that is. */
    def claim(slot: BigInt, claim: Claim[F]): (Claims[F], Option[Claim[F]]) =
      owned.get(slot) match
        case Some(theirs) => (this, Some(theirs))
        case None         => (copy(owned = owned.updated(slot, claim)), None)

    /** The forging fiber's claim on `slot`, if no one has claimed it and every slot before it is
      * settled.
      */
    def forging(slot: BigInt, result: Deferred[F, Final[F]]): (Claims[F], Boolean) =
      if mark == slot - 1 && !owned.contains(slot) then
        (copy(owned = owned.updated(slot, Claim.Forging(result))), true)
      else (this, false)

    /** The first slot after `last` the driver handed to the forging fiber. */
    def handedAfter(last: BigInt): Option[(BigInt, Deferred[F, Final[F]], Admitted)] =
      owned.iteratorFrom(last + 1).collectFirst { case (slot, Claim.Handed(result, admitted)) =>
        (slot, result, admitted)
      }

  /** What the slot driver has published that names the next lines: the KES period whose state it
    * announced, the KES period whose `startLeadershipCheck` it published (a slot not led logs one
    * once per period, #1101 Q2), each with the credentials' generation, and the generation whose
    * poisoned key it has reported as `KESCouldNotEvolve`.
    */
  private[forge] final case class Published(
      announced: Option[(Long, BigInt)],
      started: Option[(Long, BigInt)],
      poisoned: Option[Long]
  ):
    def announced(generation: Long, period: BigInt): Published =
      copy(announced = Some(generation -> period))

    def started(generation: Long, period: BigInt): Published =
      copy(started = Some(generation -> period))

    /** The failed update's line at `slot`: `KESCouldNotEvolve` the first time for `generation`,
      * then `KESKeyAlreadyPoisoned`, as the reference's key answers every update after the one that
      * poisoned it (`HotKey.hs` 385-418).
      */
    def named(generation: Long, slot: BigInt, failure: KesEvolutionError): (Published, ForgeTrace) =
      if poisoned.contains(generation) then
        (
          this,
          ForgeTrace.ForgeStateUpdateError(
            slot,
            KesEvolutionError.KeyAlreadyPoisoned(failure.info, failure.target)
          )
        )
      else
        (
          copy(poisoned = Some(generation)),
          ForgeTrace.ForgeStateUpdateError(
            slot,
            KesEvolutionError.CouldNotEvolve(failure.info, failure.target)
          )
        )

  /** The tip of the committed chain and the credentials' generation an attempt was made with. */
  private[forge] final case class Seen(tip: Option[Bytes], generation: Long)

  /** What the four fibers share (§5.1): the slot the clock fiber published last, the claims on
    * slots, the KES key's reservation and the update of each period, and what the publisher has
    * said of the key.
    */
  private[forge] final class Shared[F[_]](
      val clock: SignallingRef[F, Option[BigInt]],
      val claims: SignallingRef[F, Claims[F]],
      val records: Ref[F, Map[(Long, BigInt), KesResult]],
      val reservation: Mutex[F],
      val published: Ref[F, Published]
  )

  private[forge] object Shared:
    def create[F[_]: Async]: F[Shared[F]] =
      (
        SignallingRef.of[F, Option[BigInt]](None),
        SignallingRef.of[F, Claims[F]](Claims(BigInt(-1), SortedMap.empty, None)),
        Ref.of[F, Map[(Long, BigInt), KesResult]](Map.empty),
        Mutex[F],
        Ref.of[F, Published](Published(None, None, None))
      ).mapN(Shared(_, _, _, _, _))

  /** What the blockchain time reads: the wait for the system start, an unknown slot, or the slot.
    */
  private[node] enum Reading:
    case BeforeStart(remaining: FiniteDuration)
    case Unknown
    case Known(slot: BigInt)

  /** What the blockchain time reads at `instant` (`hardForkBlockchainTime`, consensus 82ecba329
    * `BlockchainTime/WallClock/HardFork.hs`): before the system start, how long to wait for it
    * (`systemTimeWait`); a slot the era history at the chain's tip does not translate
    * (`translates`), unknown (`wallclockToSlot` failing); otherwise the slot.
    */
  private[node] def reading(
      geometry: EpochGeometry,
      instant: Instant,
      translates: BigInt => Boolean
  ): Reading =
    if instant.isBefore(geometry.systemStart) then
      Reading.BeforeStart(Duration.between(instant, geometry.systemStart).toMillis.max(1L).millis)
    else
      val slot = geometry.slotAt(instant)
      if translates(slot) then Reading.Known(slot) else Reading.Unknown

  /** How far back the wall clock may move without stopping the node (`hfbtMaxClockRewind`,
    * consensus 82ecba329 `Node.hs` 560).
    */
  private[node] val MaxClockRewind: Duration = Duration.ofSeconds(20)

  /** A reading traced as the clock moved back that is a wake before the slot the loop waited for,
    * not a clock that moved back (#629, #1032): later than the previous reading (the time moves
    * on), and more than 1 µs but less than 1 ms before the start of the slot after the previous
    * reading's, which is the one the loop waited for. A wait floored to the microsecond
    * ([[untilNext]]) can end less than 1 µs early; a reading earlier than the previous one is a
    * clock that really moved back. It is the typed form of the Antithesis trial driver's detector
    * of the trace, which read the log for the same readings. The slot start it woke before, when it
    * is such a wake; its details ([[EarlyWake]]) are built only where they are written.
    */
  private[forge] def wokeEarly(
      geometry: xyz.kd5ujc.scarab.ledger.EpochGeometry,
      previous: Instant,
      now: Instant
  ): Option[Instant] =
    val awaited = geometry.slotStart(geometry.slotAt(previous) + 1)
    val early = Duration.between(now, awaited)
    Option.when(
      now.isAfter(previous) && early.compareTo(EarlyWake.Least) > 0 &&
        early.compareTo(EarlyWake.Most) < 0
    )(awaited)

  /** A wake `awaited - now` before the slot start the loop waited for (see [[wokeEarly]]). */
  private[forge] final case class EarlyWake(previous: Instant, now: Instant, awaited: Instant)

  private[forge] object EarlyWake:
    /** A wait floored to the microsecond ends less than this early. */
    val Least: Duration = Duration.ofNanos(1000)

    /** #629's floor to the millisecond ended a wait less than this early. */
    val Most: Duration = Duration.ofMillis(1)

    given Encoder.AsObject[EarlyWake] = Encoder.AsObject.instance(w =>
      JsonObject(
        "prevTime" -> w.previous.toString.asJson,
        "newTime" -> w.now.toString.asJson,
        "slotStart" -> w.awaited.toString.asJson,
        "earlyNanos" -> Duration.between(w.now, w.awaited).toNanos.asJson
      )
    )

  /** What the assertions of a forged block report: its slot, hash and number of transactions. */
  private final case class Made(slot: BigInt, hash: Bytes, txs: Int)

  private object Made:
    given Encoder.AsObject[Made] = Encoder.AsObject.instance(m =>
      JsonObject("slot" -> m.slot.asJson, "hash" -> m.hash.hex.asJson, "txs" -> m.txs.asJson)
    )

  /** What the assertion of a forged block our own rules refuse reports. */
  private final case class Refused(slot: BigInt, hash: Bytes, reason: ValidationError)

  private object Refused:
    given Encoder.AsObject[Refused] = Encoder.AsObject.instance(r =>
      JsonObject(
        "slot" -> r.slot.asJson,
        "hash" -> r.hash.hex.asJson,
        "reason" -> r.reason.message.asJson
      )
    )

  /** The endorser block of `body` with the header of `decoded`, the block built of it, that
    * announces it (`mkForgedAnnouncingHeader`, [[AnnouncingHeader.forged]]): its hash and the
    * length of its body come from the block forged, not from the header. A body of Dijkstra is
    * built into a Dijkstra block, which decodes as one; none where no endorser block was forged.
    */
  private[forge] def announced(decoded: Block, body: BlockBuilder.Assembly): Option[AnnouncedEb] =
    (decoded, body) match
      case (Block.Dijkstra(block), BlockBuilder.Assembly.InDijkstra(_, Some(e))) =>
        Some(
          AnnouncedEb(
            AnnouncingHeader
              .forged(block.header, e.forged.point.hash, BytesSize.wrap(e.forged.body.encodedSize)),
            e.forged
          )
        )
      case _ => None

  /** What the assertion of a forge defect reports. */
  private final case class Defective(slot: BigInt, defect: ForgeDefect)

  private object Defective:
    given Encoder.AsObject[Defective] = Encoder.AsObject.instance { d =>
      val detail = d.defect match
        case ForgeDefect.BodySize(actual, maximum) =>
          List("bodySize" -> actual.asJson, "maxBodySize" -> maximum.asJson)
        case ForgeDefect.EndorserBlockSize(actual, maximum) =>
          List("endorserBlockSize" -> actual.asJson, "maxEndorserBlockSize" -> maximum.asJson)
        case ForgeDefect.BlockDecode(cause) => List("cause" -> cause.message.asJson)
      JsonObject.fromIterable(
        List("slot" -> d.slot.asJson, "kind" -> d.defect.productPrefix.asJson) ++ detail
      )
    }

  /** What a failed tick of the tip's state to `slot` is: the ledger view of this slot is
    * unavailable, and the slot ends without a block. A quantity of the state outside its vocabulary
    * type is no exception: the reference's `compactCoinOrError` throws in the forging thread, which
    * is linked (ouroboros-consensus 82ecba32 `NodeKernel.hs:378-380`), so the reference stops; the
    * owner decided the node does not (2026-10-28, issue 567, ADR 0017), and the failure keeps its
    * cause (`BlockFailure.stateOutOfRange` of the tick, #573).
    */
  private[forge] def tickFault(
      slot: BigInt,
      failure: xyz.kd5ujc.scarab.ledger.rules.TickFailure
  ): NodeError =
    ForgeError.LedgerViewUnavailable(slot, ForgeLedgerProblem.Tick(failure))

  /** Like runExceptT at the forging worker boundary: typed failures and defects finish as values.
    * The scope and the body are constructed inside defer (cats-mtl 1.7.0 Handle.rescue invokes its
    * body eagerly); cancellation passes through unchanged.
    */
  private[forge] def scoped[F[_]: Async, A](
      body: (Handle[F, NodeError], Raise[F, Fatal]) ?=> F[A]
  ): F[Either[Fatal, A]] =
    Fibers.scoped[F, Fatal, A](Fatal.Defect(_)) {
      val terminal = summon[Handle[F, Fatal]]
      Handle.allow[NodeError](body).rescue { error =>
        val fatal = SessionEnd.of(error) match
          case SessionEnd.Storage(e)     => Fatal.Storage(e)
          case SessionEnd.Unavailable(e) => Fatal.Unavailable(e)
          // a crypto provider that fails on our own block is raised by `build`, with nothing evicted
          case SessionEnd.Unverifiable(e) => Fatal.Unverifiable(e)
          // an exception while our block was committed (`ChainError.CommitDefect`)
          case SessionEnd.Defect(cause) => Fatal.Defect(cause)
          case _                        => Fatal.Internal(error)
        terminal.raise[Fatal, A](fatal)
      }
    }

  /** How long the blockchain time waits before it asks again for a slot it could not translate
    * (`BackoffDelay 60`, consensus 82ecba329 `Node.hs` 550).
    */
  val UnknownSlotBackoff: FiniteDuration = 60.seconds

  /** The warning that the KES key of `c`, as it was read from its file at start-up, is at a later
    * period than `kesPeriod`, the current one: a key evolved past the present, which cannot sign
    * until the present reaches it. A key at its certificate's first period is not evolved, and is
    * never said to be ahead.
    */
  private[forge] def keyAhead(c: Pool, kesPeriod: BigInt): Option[ForgeEvent.KesKeyAhead] =
    val at = c.kesStart + c.kesPeriod
    Option.when(c.kesPeriod > 0 && at > kesPeriod)(ForgeEvent.KesKeyAhead(at, kesPeriod))

  /** What the forger keeps of the credentials it starts with, none of it secret (#1418): the pool's
    * id, the hash of the VRF verification key a registration of it carries, and where the KES key
    * read at start-up stands, its certificate's first period and its own ([[keyAhead]]). The keys
    * themselves are the [[HotKeys]]' from the start, and are erased with their credentials.
    */
  final case class Pool(id: Bytes, vrfKeyHash: Bytes, kesStart: BigInt, kesPeriod: Int)

  object Pool:
    def of(c: PoolCredentials): Pool =
      Pool(c.poolId, c.vrfKeyHash, c.opCert.kesPeriod, c.kes.period)

  /** Whether the era history at the chain's tip translates `slot` (`wallclockToSlot` of the summary
    * of the current ledger, `hfbtGetLedgerState`): the horizon of [[ChainRead.clock]], the same one
    * the transactions' time translation has.
    */
  def withinHorizon[S](using clock: TxClock[S]): (VolatileChain[S], BigInt) => Boolean =
    (current, slot) => clock.translates(current.state, slot)

  /** What the forger keeps of the tip, the parent of the blocks it forges, for the slots of `epoch`
    * ([[Crossed]]): its ledger state `ledger` as it is, and the ledger view of the epoch's leader
    * check ([[leaderView]]).
    *
    * The state is not carried into `epoch` here: a led slot's block is ticked once, from its
    * parent's state to its slot (`getTickedLedgerState`'s `applyChainTick`, ouroboros-consensus
    * 2416493b `NodeKernel/Forge.hs:686-705`), as block application ticks it (`EraBlock.ticked`), so
    * the state the mempool's transactions are selected on (Conway), or the snapshot provider is
    * given (Dijkstra), is the one the block is validated against. A tick to the epoch's first slot
    * and then to the slot differs from it at and past the new epoch's slot of no return: TICK
    * solidifies the future parameters before NEWEPOCH (`validatingTickTransition`,
    * `Shelley/Rules/Tick.hs:160-181` at cardano-ledger f649f975, 147-185 at 9df9f98c), so the
    * single tick solidifies the parent's, which EPOCH replaces with the new epoch's prediction,
    * where the second of two ticks solidifies that prediction; a slot reaches it from a parent of
    * the old epoch where the epoch is shorter than three stability windows, as on the devnets
    * (`ConwayForgeTickSuite`, `DijkstraForgeSuite`).
    */
  private[forge] def crossed(
      key: (Option[Bytes], BigInt),
      ledger: TipLedger,
      protocol: ProtocolState,
      epoch: BigInt
  ): Crossed =
    Crossed(key, ledger, leaderView(ledger, protocol, epoch))

  /** The ledger state the block of the led slot `slot` is built on: the tip's state
    * `crossed.ledger`, the block's parent's, ticked once to the slot ([[tickedTo]], [[crossed]]), a
    * Conway state as the pool's ledger of Conway ([[ConwayPoolLedger]]), whose parameters in force
    * that do not read into Conway's give none ([[ForgeProtocolProblem.ConwayParams]]: the slot ends
    * without a block, as it does for a chain the forger has no ledger of), a Dijkstra state (the
    * Leios build only) as it is, with the parent's unticked beside it ([[Ticked.InDijkstra]]). The
    * only tick of an attempt's block.
    */
  private[forge] def blockLedger(
      globals: Globals,
      transition: Hfc.Transition,
      crossed: Crossed,
      slot: BigInt
  ): Either[NodeError, Ticked] =
    tickedTo(globals, transition, crossed.ledger, slot).flatMap { t =>
      (t, crossed.ledger) match
        case (TipLedger.InConway(nes), _) =>
          ConwayPoolLedger
            .of(nes)
            .bimap(
              e => ForgeError.UnsupportedProtocol(ForgeProtocolProblem.ConwayParams(e)),
              Ticked.InConway(_)
            )
        case (TipLedger.InDijkstra(nes), TipLedger.InDijkstra(parent)) =>
          Right(Ticked.InDijkstra(nes, parent))
        case (TipLedger.InDijkstra(_), TipLedger.InConway(_)) =>
          Left(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftConway))
    }

  /** The tip's ledger state, which a led slot's block is ticked from ([[crossed]]), and the ledger
    * view of the epoch's leader check.
    */
  final case class Crossed(
      key: (Option[Bytes], BigInt),
      ledger: TipLedger,
      view: LeaderView
  )

  /** The ledger state the forger builds on, by era: Conway's, or in the Leios build Dijkstra's,
    * whose blocks the testnet's build forges with Praos2 (`ShelleyProtocolHeader (Praos2 c) =
    * Leios.Header c`, ouroboros-consensus 2416493b `Ouroboros/Consensus/Protocol/Praos2.hs:57`).
    * The release builds forge Conway's only, as before.
    */
  enum TipLedger:
    case InConway(nes: NewEpochState[Conway])
    case InDijkstra(nes: NewEpochState[Dijkstra])

    def epoch: BigInt = this match
      case InConway(nes)   => nes.epoch.toBigInt
      case InDijkstra(nes) => nes.epoch.toBigInt

    def era: Era = this match
      case InConway(_)   => Era.Conway
      case InDijkstra(_) => Era.Dijkstra

    /** The state as the combinator's tick takes it. */
    def inEra: EraNes = this match
      case InConway(nes)   => EraNes.InEra(ShelleyBasedEra.SConway, nes)
      case InDijkstra(nes) => EraNes.InEra(ShelleyBasedEra.SDijkstra, nes)

    /** The pool distribution of the leader check ([[leaderView]]). */
    def leaderPools(nextEpoch: Boolean): PoolDistribution = this match
      case InConway(nes) =>
        MirrorEraRead(nes)(using ShelleyBasedEra.SConway).leaderPools(nextEpoch)
      case InDijkstra(nes) =>
        MirrorEraRead(nes)(using ShelleyBasedEra.SDijkstra).leaderPools(nextEpoch)

  /** The ledger state at a slot, ticked into it, by era: what the block's transactions are applied
    * on and its limits read from ([[Forger.tickedTo]]).
    */
  enum Ticked:
    case InConway(ledger: ConwayPoolLedger)

    /** The Dijkstra state of the slot, `nes`, and the parent's it was ticked from, unticked, which
      * a certified closure is applied to (`partitionMempool` 's `unticked`, ouroboros-consensus
      * 2416493b `NodeKernel/Forge.hs:826-835`).
      */
    case InDijkstra(nes: NewEpochState[Dijkstra], parent: NewEpochState[Dijkstra])

    def params: ProtocolParams = this match
      case InConway(ledger)   => ledger.params
      case InDijkstra(nes, _) => MirrorEraRead(nes)(using ShelleyBasedEra.SDijkstra).params

  /** Whether `build` forges Dijkstra blocks: the Leios build does, as the testnet's node does
    * (`forgeShelleyBlock` of the Dijkstra era under Praos2, ouroboros-consensus 2416493b
    * `Shelley/Ledger/Forge.hs:79-142`); the release builds, whose Dijkstra this node never enters,
    * do not, and keep the refusal of a Dijkstra tip.
    */
  private[forge] def forgesDijkstra(build: ReferenceBuild): Boolean = build match
    case ReferenceBuild.Leios                                    => true
    case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => false

  /** What the leader check of a slot reads from the tip's state: the pool distribution of its
    * forecast ledger view, and the chain-dependent state ticked into the slot's epoch.
    */
  final case class LeaderView(pools: PoolDistribution, protocol: ProtocolState)

  /** The ledger state at the tip `tip` of the chain whose state is `state`, from which the ledger
    * view of `slot`, in `epoch`, is forecast. The refusals are Scarab's own: only Praos with Conway
    * transactions is forged, and in the Leios build ([[forgesDijkstra]]) also Praos2 on a Dijkstra
    * state, on a fully validated ledger, and an epoch before the tip's has no forecast. A release
    * build refuses a Dijkstra state as of an era it does not forge (`Earlier(Dijkstra)`), as it
    * always has.
    */
  private[forge] def ledgerAt[S](
      state: S,
      tip: Option[BlockRef],
      epoch: BigInt,
      slot: BigInt,
      build: ReferenceBuild
  )(using ChainRead[S]): Either[NodeError, TipLedger] =
    tipLedger(state, tip, build).flatTap(ledger => inEpoch(ledger.epoch, epoch, slot))

  /** The ledger state at the tip `tip` of the chain whose state is `state`, in an era the build
    * forges ([[ledgerAt]] 's, before its epoch is checked): Conway's, and in the Leios build
    * ([[forgesDijkstra]]) also Dijkstra's. The leader check at the slot and the schedule
    * ([[scheduledEpochs]]) both read the tip through it, so they cover the same eras.
    */
  private[forge] def tipLedger[S](
      state: S,
      tip: Option[BlockRef],
      build: ReferenceBuild
  )(using ChainRead[S]): Either[NodeError, TipLedger] =
    ConwayView(state, tip) match
      case Right(nes) => Right(TipLedger.InConway(nes))
      case Left(ConwayView.Unavailable.InEarlier(Era.Dijkstra)) if forgesDijkstra(build) =>
        dijkstraAt(state, tip).map(TipLedger.InDijkstra(_))
      case Left(why) => Left(unsupported(why))

  /** The epochs whose leader schedule can be computed on the chain whose state is `state` and tip
    * `tip` (§2.3 of the leader-schedule design, #1101): that of the tip's ledger ([[tipLedger]]),
    * and the next once the tip is at or past that epoch's freeze slot ([[freeze]]). None where the
    * build forges no block on that ledger: those slots are decided at the slot, which refuses them.
    */
  private[forge] def scheduledEpochs[S](
      state: S,
      tip: Option[BlockRef],
      build: ReferenceBuild,
      geometry: EpochGeometry
  )(using ChainRead[S]): Vector[BigInt] =
    tipLedger(state, tip, build).toOption.fold(Vector.empty[BigInt]) { ledger =>
      val at = tip.fold(BigInt(-1))(_.slot)
      ledger.epoch +: Vector(ledger.epoch + 1).filter(next => at >= freeze(geometry, next))
    }

  /** The inputs of the leader schedule on the chain whose state is `state` and tip `tip`: those of
    * each epoch [[scheduledEpochs]] gives, from the ledger view `view` of its first slot, as the
    * slot's check derives them ([[inputsOf]]). The scheduler passes its cached view of the tip,
    * [[ledgerAt]] 's and [[leaderView]] 's; an epoch whose view cannot be derived is left out, and
    * its slots are decided at the slot.
    */
  private[forge] def scheduleInputs[F[_]: Applicative, S](
      state: S,
      tip: Option[BlockRef],
      build: ReferenceBuild,
      geometry: EpochGeometry,
      poolId: Bytes,
      activeSlotsCoeff: Leader.Fraction,
      vrf: Bytes
  )(view: BigInt => F[Either[NodeError, LeaderView]])(using
      ChainRead[S]
  ): F[LeaderSchedule.InForce] =
    scheduledEpochs(state, tip, build, geometry)
      .traverse(e =>
        view(e).map(_.toOption.map(v => inputsOf(geometry, e, v, poolId, activeSlotsCoeff, vrf)))
      )
      .map(found => LeaderSchedule.InForce.of(found.flatten*))

  /** The first slot from which no block can change the nonce of `epoch`: the randomness
    * stabilisation window (4k/f) before its first slot.
    */
  private[forge] def freeze(geometry: EpochGeometry, epoch: BigInt): BigInt =
    geometry.firstSlot(epoch) - geometry.randomnessStabilisationWindow.toBigInt

  /** The Dijkstra state of the chain at `tip`, which [[ConwayView]] found to be in Dijkstra. */
  private def dijkstraAt[S](state: S, tip: Option[BlockRef])(using
      read: ChainRead[S]
  ): Either[NodeError, NewEpochState[Dijkstra]] =
    state.ledger(tip) match
      case Left(error) => Left(unsupported(ConwayView.Unavailable.Refused(error)))
      case Right(None) => Left(unsupported(ConwayView.Unavailable.InByron))
      case Right(Some(InEra.Of(era, read))) =>
        era match
          case ShelleyBasedEra.SDijkstra =>
            read.nes.left.map(e => unsupported(ConwayView.Unavailable.Refused(e)))
          case other => Left(unsupported(ConwayView.Unavailable.InEarlier(EraNes.eraOf(other))))

  private def unsupported(why: ConwayView.Unavailable): NodeError =
    ForgeError.UnsupportedProtocol(why match
      case ConwayView.Unavailable.InByron        => ForgeProtocolProblem.InByron
      case ConwayView.Unavailable.InEarlier(era) => ForgeProtocolProblem.Earlier(era)
      case ConwayView.Unavailable.Refused(e)     => ForgeProtocolProblem.Mirror(e)
      case ConwayView.Unavailable.Params(e)      => ForgeProtocolProblem.ConwayParams(e))

  private def inEpoch(stateEpoch: BigInt, epoch: BigInt, slot: BigInt): Either[NodeError, Unit] =
    Either.cond(
      epoch >= stateEpoch,
      (),
      ForgeError.LedgerViewUnavailable(slot, ForgeLedgerProblem.EpochPrecedes(epoch, stateEpoch))
    )

  /** The state `ledger` at `slot`: the combinator's tick ([[Hfc.tick]]: the translation into the
    * slot's era, then the slot's own work), as the block rules would make it for a block in that
    * slot. The state stays in its era: a Conway state is not carried into Dijkstra, nor a Dijkstra
    * state out of it, so no transition reads the blocks after the voting deadline (zero here).
    */
  private[forge] def tickedTo(
      globals: Globals,
      transition: Hfc.Transition,
      ledger: TipLedger,
      slot: BigInt
  ): Either[NodeError, TipLedger] =
    for
      target <- SlotNo
        .fromBigInt(slot)
        .left
        .map(e => ForgeError.LedgerViewUnavailable(slot, ForgeLedgerProblem.Vocabulary(e)))
      state <- Hfc
        .tick(globals, transition)(ledger.inEra, 0L, target)
        .left
        .map(Forger.tickFault(slot, _))
      next <- (ledger, state) match
        case (TipLedger.InConway(_), _) =>
          ConwayView
            .of(state)
            .map(TipLedger.InConway(_))
            .toRight(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftConway))
        case (TipLedger.InDijkstra(_), i: EraNes.InEra[e]) =>
          i.era match
            case ShelleyBasedEra.SDijkstra => Right(TipLedger.InDijkstra(i.nes))
            case _ => Left(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftDijkstra))
    yield next

  /** The ledger view of the leader check of `epoch` from the tip's state `ledger` and its
    * chain-dependent state `protocol`. Praos2 checks the leader as Praos does (`checkIsLeader =
    * checkIsLeaderPolyPraos`, and its tick is `tickChainDepStatePolyPraos`, which also clears the
    * announcement at an epoch boundary: ouroboros-consensus 2416493b
    * `Ouroboros/Consensus/Protocol/Praos2.hs:127-129`; [[Praos.tick]] of a Praos2 state). The pool
    * distribution is the forecast of the tip's ledger (`futureForecast`, which ticks with TICKF:
    * cardano-ledger f649f975 `Shelley/Rules/Tick.hs` 201-242, `Conway/Rules/Tickf.hs` 42-66): into
    * the next epoch the mark distribution, in the tip's epoch and two or more epochs on the current
    * one, as the header rules take it (`ShelleyHeaderForecast`). The epoch nonce is that of the
    * state ticked into `epoch`.
    */
  private[forge] def leaderView(
      ledger: TipLedger,
      protocol: ProtocolState,
      epoch: BigInt
  ): LeaderView =
    LeaderView(
      ledger.leaderPools(nextEpoch = epoch == ledger.epoch + 1),
      Praos.tick(protocol, ledger.era, epoch)
    )

  /** The inputs of the leader check of `epoch` from its ledger view: the epoch nonce, the stake of
    * the pool `poolId` (none if the distribution does not have it) and the distribution's total,
    * with the VRF key whose verification key is `vrf`.
    */
  private[forge] def inputsOf(
      geometry: EpochGeometry,
      epoch: BigInt,
      view: LeaderView,
      poolId: Bytes,
      activeSlotsCoeff: Leader.Fraction,
      vrf: Bytes
  ): EpochInputs =
    EpochInputs.of(
      geometry,
      epoch,
      view.protocol.epochNonce,
      view.pools.stake.getOrElse(poolId, BigInt(0)),
      view.pools.total,
      activeSlotsCoeff,
      vrf
    )

  /** A block we forged and adopted, kept to notice if it is replaced later. */
  final case class Forged(ref: BlockRef, slot: BigInt)

  /** Where the block goes, `BlockContext` (`NodeKernel.hs` 809-818): the chain it is built on,
    * whose tip is its predecessor (`bcPrevPoint`), and so its number (`bcBlockNo`).
    */
  final case class Context[S](base: VolatileChain[S]):
    def prev: ChainSync.Point = base.tip

    /** The successor's number of the predecessor (`succ` of an Origin block number is the first,
      * zero), which is right after a boundary block too.
      */
    def blockNo: BigInt = base.tipRef.fold(BigInt(0))(_.blockNo + 1)

  /** A block built and signed, not yet adopted.
    *
    * @param endorsed
    *   The endorser block forged with the block and the header that announces it, which the node
    *   keeps before the block goes to chain selection ([[OnForgedLeiosEb]]).
    */
  final case class Built(
      header: Header,
      raw: Bytes,
      body: BlockBuilder.Assembly,
      relativeKesPeriod: Int,
      counter: BigInt,
      endorsed: Option[AnnouncedEb]
  ):
    /** `estimateBlockSize` of the header (see [[ForgeTrace.AdoptedBlock]]). */
    def estimatedSize: Long = header.raw.size.toLong + body.size + 10

  /** @param source
    *   Where the KES key is written back after evolving and read from again at a reload; none: the
    *   key is kept in memory only, as the Haskell node does.
    * @param enabled
    *   Forge from the start; false starts with forging disabled (`--start-as-non-producing-node`,
    *   see [[HotKeys.create]]).
    * @param conwaySnapshots
    *   Where a Conway block's transactions come from ([[ConwaySnapshots.ofPool]] in the node, with
    *   the node's environment); none: the pool's snapshot ([[ConwaySnapshots.ofPool]]) in the
    *   forger's environment, its constants and Plutus backend.
    * @param dijkstraSnapshots
    *   Where a Dijkstra ranking block's transactions come from ([[DijkstraSnapshots.ofPool]] in the
    *   node); none: empty snapshots, [[DijkstraSnapshots.unadmitted]].
    * @param onForgedEb
    *   What is done with a forged endorser block ([[OnForgedLeiosEb]]); none: no endorser block is
    *   forged.
    * @param ebCapacityScale
    *   The share of an endorser block's capacity to forge with; none: the whole, as a node without
    *   adaptive endorsements forges (`NodeKernel.hs:497-512` at ouroboros-consensus 2416493b).
    * @param certifying
    *   What a Dijkstra block's certification reads of the node
    *   ([[xyz.kd5ujc.scarab.node.LeiosCertifier]] in the node); none: it never certifies,
    *   [[LeiosCertifying.none]].
    */
  def create[F[_]: Async, S](
      config: PraosConfig,
      globals: LedgerGlobals,
      plutus: PlutusBackend,
      protocolVersion: (BigInt, BigInt),
      credentials: PoolCredentials,
      follower: Follower[F, S],
      mempool: Mempool[F],
      events: ForgeEvents[F],
      forecastWindow: Option[BigInt] = None,
      now: Option[F[Instant]] = None,
      sleep: Option[FiniteDuration => F[Unit]] = None,
      unknownSlotBackoff: FiniteDuration = Forger.UnknownSlotBackoff,
      horizon: Option[(VolatileChain[S], BigInt) => Boolean] = None,
      utxoStore: Option[UtxoStore[F]] = None,
      source: Option[HotKeySource[F]] = None,
      enabled: Boolean = true,
      chains: Option[Signal[F, VolatileChain[S]]] = None,
      pause: Option[Point => F[Unit]] = None,
      conwaySnapshots: Option[ConwaySnapshots[F]] = None,
      dijkstraSnapshots: Option[DijkstraSnapshots[F]] = None,
      onForgedEb: Option[OnForgedLeiosEb[F]] = None,
      ebCapacityScale: Option[F[EbCapacityScale]] = None,
      certifying: Option[LeiosCertifying[F, S]] = None
  )(using
      Stateful[F, VolatileChain[S]],
      ChainRead[S],
      TxClock[S],
      Assertions[F]
  ): F[Forger[F, S]] =
    val clockNow = now.getOrElse(Clock[F].realTimeInstant)
    val sleeping = sleep.getOrElse((d: FiniteDuration) => Async[F].sleep(d))
    // what is public of the credentials, taken before the hot keys own them
    (
      Async[F].delay(Pool.of(credentials)),
      HotKeys.create[F](credentials, source, config, events, enabled)
    ).flatMapN((pool, hot) =>
      (
        Ref.of[F, Option[Crossed]](None),
        Ref.of[F, Vector[((Option[Bytes], BigInt), LeaderView)]](Vector.empty),
        Ref.of[F, Vector[Forged]](Vector.empty),
        ForgeStats.create[F],
        LeaderSchedule.create[F](config.geometry, hot.keys, Some(clockNow), Some(sleeping)),
        Shared.create[F]
      ).mapN { (cache, viewed, pending, stats, schedule, shared) =>
        new Forger[F, S](
          config,
          globals,
          protocolVersion,
          pool,
          hot,
          follower,
          mempool,
          events,
          forecastWindow.getOrElse(config.geometry.stabilityWindow.toBigInt),
          clockNow,
          sleeping,
          unknownSlotBackoff,
          horizon.getOrElse(Forger.withinHorizon[S]),
          cache,
          viewed,
          pending,
          stats,
          schedule,
          chains.getOrElse(follower.committedChain),
          shared,
          pause.getOrElse(_ => Async[F].unit),
          conwaySnapshots.getOrElse(
            ConwaySnapshots.ofPool[F](
              mempool,
              utxoStore,
              Async[F].pure(MempoolEnv(globals, config.geometry, plutus.evaluator, plutus.decoder))
            )
          ),
          dijkstraSnapshots.getOrElse(DijkstraSnapshots.unadmitted[F]),
          onForgedEb,
          ebCapacityScale.getOrElse(Async[F].pure(EbCapacityScale.full)),
          certifying.getOrElse(LeiosCertifying.none[F, S])
        )
      }
    )
