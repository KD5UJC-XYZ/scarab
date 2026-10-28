// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.{ForgeAssemblyProblem, ForgeLedgerProblem, ForgeProtocolProblem}
import cats.data.EitherT
import cats.effect.{Async, Clock, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.ledger.{
  EvaluationUnavailable,
  ForgeError,
  LedgerFailure,
  VerificationDefect
}
import xyz.kd5ujc.scarab.ledger.{ClockProblem, NodeError}
import xyz.kd5ujc.scarab.ledger.VrfCert
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash}
import xyz.kd5ujc.scarab.node.{Fatal, Follower, SessionEnd}
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig}
import xyz.kd5ujc.scarab.consensus.state.{Placement, ProtocolState}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{LedgerGlobals, PoolDistribution}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.node.read.{ChainRead, ConwayView}
import xyz.kd5ujc.scarab.node.read.MirrorEraRead
import xyz.kd5ujc.scarab.node.mempool.{Entry, Mempool, MempoolLedger, MempoolState}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.UtxoStore
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.Fibers

/** What the forger reports, for the JSON log and for tests. */
enum ForgeEvent:
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

  /** The KES key moved to a new period. */
  case KesEvolved(kesPeriod: BigInt, relativeKesPeriod: Int)

  /** The KES key and operational certificate were replaced (SIGHUP), or the same ones confirmed. */
  case KesReloaded(counter: BigInt, kesStart: BigInt, kesEvolutions: BigInt, kesPeriod: BigInt)

  /** A reload was refused: the credentials in use before it stay in force. */
  case KesReloadRefused(reason: ReloadRefusal)

  /** Key persistence failed; this observation is followed by a typed fatal outcome and node stop.
    */
  case KesPersistFailed(kesPeriod: BigInt, error: ForgeError)

  /** The VRF key file gives its group some access (`mode`): allowed, as in the reference, which
    * warns (`GroupPermissionsExist`).
    */
  case KeyFileGroupAccess(file: String, mode: Int)

  /** A slot given up for a reason the reference does not have (see [[ForgeError]]). */
  case Failed(slot: BigInt, error: ForgeError)

  /** The forger's counters for an epoch that has just ended (see [[ForgeStats]]). */
  case EpochSummary(epoch: BigInt, stats: ForgeEpochStats)

trait ForgeEvents[F[_]]:
  def emit(event: ForgeEvent): F[Unit]

/** Block production for one pool, in the Praos protocol, as the reference's `forkBlockForging` does
  * it (consensus 82ecba329 `NodeKernel.hs` 549-796): once per slot, find the block to build on, the
  * ledger view of the slot from its predecessor, update the KES key, check leadership with the
  * stake of that ledger view, and if elected build a block from the mempool, KES-sign it, validate
  * it with the node's own block rules and adopt it through the [[Follower]], which tells the chain
  * server (peers fetch it) and the mempool. Every step is traced as the reference traces it
  * ([[ForgeTrace]]), and the counters are folded over those traces.
  *
  * The slot clock is the wall clock over the era history (`EpochGeometry`); `F` is any `Async` so
  * tests drive it with a test clock. At most one attempt is made per slot, and only the current
  * slot is attempted: slots that pass while an attempt runs are not attempted, and nothing is said
  * of them, as the reference's `knownSlotWatcher` passes over them.
  *
  * @param credentials
  *   The pool's keys as they were at the start; the VRF key and the cold key never change, the KES
  *   key and certificate are those of `hot`.
  * @param forecastWindow
  *   How far the ledger view can be forecast from the ledger state of the block's predecessor: the
  *   stability window (`SL.stabilityWindow`) of `ledgerViewForecastAt`.
  * @param now
  *   `now` for tests; the real clock by default.
  * @param unknownSlotBackoff
  *   How long the loop waits before it asks the clock again while the current slot is unknown.
  * @param horizon
  *   Whether the era history at the tip of a chain translates a slot ([[Forger.withinHorizon]]).
  * @param utxoStore
  *   The backing store of the UTxO set: the read set of the candidates is fetched from it.
  * @param stats
  *   Per-epoch counters (H5) and the reference's `ForgingStats`, folded over the traces.
  */
final class Forger[F[_]: Async, S](
    config: PraosConfig,
    globals: LedgerGlobals,
    credentials: PoolCredentials,
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
    pending: Ref[F, Vector[Forger.Forged]],
    utxoStore: Option[UtxoStore[F]],
    val stats: ForgeStats[F]
)(using
    chain: Stateful[F, VolatileChain[S]],
    read: ChainRead[S]
):
  private val F = Async[F]
  private val geometry = config.geometry

  /** Run until cancelled. */
  def run: F[Either[Fatal, Unit]] = Forger.scoped {
    for
      instant <- now
      current <- hot.current
      _ <- events.emit(
        ForgeEvent.Started(
          credentials.poolId,
          credentials.vrfKeyHash,
          current.opCert.kesPeriod,
          config.maxKesEvolutions,
          current.opCert.sequence,
          kesPeriodOf(geometry.slotAt(instant))
        )
      )
      _ <- loop(None)
    yield ()
  }

  private def kesPeriodOf(slot: BigInt): BigInt = slot / config.slotsPerKesPeriod

  /** Switch to the KES key and operational certificate that are in the key files now, if they are
    * good for this pool at this time and on this chain; otherwise keep the old ones and report a
    * typed [[ForgeEvent.KesReloadRefused]]. The Haskell node does this on SIGHUP:
    * `Cardano.Node.Run` `installSigHUPHandler` calls `updateBlockForging`, which reads the
    * credentials again and hands them to `setBlockForging` (cardano-node 11.1.3, `Run.hs` lines 596
    * and 626).
    */
  def reload: F[Either[Fatal, Unit]] = Forger.scoped {
    for
      instant <- now
      current <- chain.get
      counter = KeyHash
        .fromBytes[BlockIssuer](credentials.poolId)
        .toOption
        .flatMap(current.state.protocol.counters.get)
        .map(_.toBigInt)
      _ <- hot.reload(kesPeriodOf(geometry.slotAt(instant)), counter)
    yield ()
  }

  /** What the blockchain time reads at `instant` (`hardForkBlockchainTime`, consensus 82ecba329
    * `BlockchainTime/WallClock/HardFork.hs`): before the system start, how long to wait for it
    * (`systemTimeWait`); past the horizon of the era history at the tip of the chain, unknown
    * (`wallclockToSlot` failing); otherwise the slot.
    */
  private def read(instant: Instant): F[Forger.Reading] =
    if instant.isBefore(geometry.systemStart) then
      F.pure(
        Forger.Reading.BeforeStart(
          Duration.between(instant, geometry.systemStart).toMillis.max(1L).millis
        )
      )
    else
      chain.get.map { current =>
        val slot = geometry.slotAt(instant)
        if horizon(current, slot) then Forger.Reading.Known(slot) else Forger.Reading.Unknown
      }

  private def clockTrace(t: ClockTrace): F[Unit] = events.emit(ForgeEvent.Clock(t))

  /** Until the next slot, as the reading at `instant` puts it (`timeLeft` of `getCurrentSlot'`),
    * waited on the monotonic clock as `threadDelay` waits: `elapsed` of it is already spent, and a
    * wall clock moved meanwhile does not lengthen it.
    */
  private def untilNext(slot: BigInt, instant: Instant, elapsed: FiniteDuration): F[Unit] =
    val left = Duration.between(instant, geometry.slotStart(slot + 1)).toMillis.millis - elapsed
    sleep(left.max(1.millis))

  /** The slots, as the reference's blockchain time and `knownSlotWatcher` hand them to
    * `forkBlockForging` (`HardFork.hs` 103-151, `BlockchainTime/API.hs` 57-77). Each reading is
    * checked against the previous one (`checkValidClockChange`): a later slot is taken; the same or
    * an earlier slot within `MaxClockRewind` of the previous reading's time is traced
    * (`SystemClockMovedBack`) and the previous slot kept; further back, the node stops
    * ([[ClockProblem.SystemClockMovedBack]]). While the slot is unknown it is traced
    * (`CurrentSlotUnknown`), nothing else runs, and the clock is asked again after
    * `unknownSlotBackoff`. A slot is attempted when the known slot changes; a slot passed while an
    * attempt runs is not.
    */
  private def loop(
      previous: Option[(Option[BigInt], Instant)]
  )(using Handle[F, NodeError], Raise[F, Fatal]): F[Unit] =
    now.flatMap { instant =>
      read(instant).flatMap {
        case Forger.Reading.BeforeStart(wait) =>
          clockTrace(ClockTrace.StartTimeInTheFuture(geometry.systemStart, wait)) *>
            sleep(wait) *> loop(previous)
        case Forger.Reading.Unknown =>
          clockTrace(ClockTrace.CurrentSlotUnknown(instant)) *> sleep(unknownSlotBackoff) *>
            loop(Some(None -> instant))
        case Forger.Reading.Known(slot) =>
          previous.flatMap((known, at) => known.map(_ -> at)) match
            case Some((last, at)) if slot <= last =>
              if !Duration.between(instant, at).minus(Forger.MaxClockRewind).isPositive then
                clockTrace(ClockTrace.SystemClockMovedBack(at, instant)) *>
                  untilNext(slot, instant, 0.millis) *>
                  loop(Some(Some(last) -> instant))
              else
                (Fatal.Internal(ClockProblem.SystemClockMovedBack(last, slot)): Fatal)
                  .raise[F, Unit]
            case lastKnown =>
              for
                // the epoch the previous slot was in has ended: its counters are final
                _ <- lastKnown.traverse_ { (l, _) =>
                  val ended = geometry.epochOf(l)
                  if geometry.epochOf(slot) > ended then
                    stats.epoch(ended).flatMap(s => events.emit(ForgeEvent.EpochSummary(ended, s)))
                  else F.unit
                }
                started <- F.monotonic
                _ <- stepInScope(slot)
                finished <- F.monotonic
                _ <- untilNext(slot, instant, finished - started)
                _ <- loop(Some(Some(slot) -> instant))
              yield ()
      }
    }

  /** One slot in its own scope: a forging refusal permits another slot; a service failure or defect
    * is returned to the owner and never enters the recoverable forging channel.
    */
  private[forge] def step(slot: BigInt): F[Either[Fatal, Unit]] =
    Forger.scoped(stepInScope(slot))

  private def stepInScope(
      slot: BigInt
  )(using handle: Handle[F, NodeError], terminal: Raise[F, Fatal]): F[Unit] =
    handle.attempt(F.defer(attempt(slot))).flatMap {
      case Right(_) => F.unit
      case Left(e: ForgeError) =>
        HotKeys.infrastructureFailure(e).fold(failed(slot, e))(_.raise[F, Unit])
      case Left(other) => other.raise[F, Unit]
    } *> watch

  private def failed(slot: BigInt, error: ForgeError): F[Unit] =
    stats.failed(geometry.epochOf(slot)) *> events.emit(ForgeEvent.Failed(slot, error))

  /** A step of the loop that may end the slot early, as `withEarlyExit` does in the reference: the
    * left side is the slot given up, after its trace.
    */
  private type Step[A] = EitherT[F, Unit, A]

  private def trace(t: ForgeTrace): F[Unit] =
    stats.record(geometry.epochOf(t.slot), t) *> events.emit(ForgeEvent.Loop(t))

  private def say(t: ForgeTrace): Step[Unit] = EitherT.liftF(trace(t))

  private def giveUp[A](t: ForgeTrace): Step[A] = EitherT.left(trace(t))

  private def perform[A](fa: F[A]): Step[A] = EitherT.liftF(fa)

  private def proceed[A](a: A): Step[A] = EitherT.rightT(a)

  /** The slot, step by step as `forkBlockForging`'s `go` (`NodeKernel.hs` 573-796). */
  private def attempt(slot: BigInt)(using Handle[F, NodeError], Raise[F, Fatal]): F[Unit] =
    val epoch = geometry.epochOf(slot)
    val period = kesPeriodOf(slot)
    (for
      _ <- say(ForgeTrace.StartLeadershipCheck(slot))
      current <- perform(chain.get)
      context <- blockContext(current, slot).fold(giveUp, proceed)
      _ <- say(ForgeTrace.BlockContext(slot, context.blockNo, context.prev))
      _ <- say(ForgeTrace.LedgerState(slot, context.prev))
      _ <- forecast(context.base, slot).fold(r => giveUp(ForgeTrace.NoLedgerView(slot, r)), proceed)
      crossed <- perform(crossedTo(context.base, epoch, slot))
      _ <- say(ForgeTrace.LedgerView(slot))
      // `checkShouldForge` (`Block/Forging.hs` 192-226): the forge state, then the leader check,
      // then whether a leader can forge
      key <- perform(hot.update(period).flatMap(lift)).flatMap(
        _.fold(
          e => giveUp(ForgeTrace.ForgeStateUpdateError(slot, e)),
          info => perform(events.emit(ForgeEvent.StateInfo(info))).as(info)
        )
      )
      pools = crossed.pools
      stake = pools.stake.getOrElse(credentials.poolId, BigInt(0))
      cert <- perform(
        F.blocking(
          Leadership.check(
            slot,
            crossed.protocol.epochNonce,
            credentials.vrf,
            stake,
            pools.total,
            config.activeSlotsCoeff
          )
        )
      ).flatMap {
        case Right(cert)                   => proceed(cert)
        case Left(_: ForgeError.NotLeader) => giveUp(ForgeTrace.NodeNotLeader(slot))
        case Left(e)                       => perform(e.raise[F, VrfCert])
      }
      // `praosCheckCanForge` (`Protocol/Praos.hs` 691-711)
      _ <-
        if key.start > period then
          giveUp(ForgeTrace.NodeCannotForge(slot, CannotForge.KeyNotUsableYet(period, key.start)))
        else proceed(())
      _ <- say(ForgeTrace.NodeIsLeader(slot))
      ticked <- perform(lift(tick(crossed.nes, slot)))
      _ <- say(ForgeTrace.ForgeTickedLedgerState(slot, context.prev))
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
      held <- perform(mempool.snapshot)
      chosen <- perform(select(context.base, slot, epoch, ticked, held))
      _ <- say(
        ForgeTrace.ForgingMempoolSnapshot(slot, context.prev, held.tipHash, held.tipSlot + 1)
      )
      block <- perform(assemble(context, slot, ticked, cert, chosen.map(_._2)))
      _ <- say(
        ForgeTrace.ForgedBlock(
          slot,
          context.prev,
          block.header.hash,
          block.header.blockNo,
          block.header.prevHash
        )
      )
      _ <- perform(
        events.emit(
          ForgeEvent.Forged(
            slot,
            epoch,
            block.header.blockNo,
            block.header.hash,
            block.header.prevHash,
            block.body.ids,
            block.body.size,
            period,
            block.relativeKesPeriod.toInt,
            stake,
            pools.total,
            block.counter
          )
        )
      )
      adoption <- perform(follower.adoptForged(block.header, block.raw))
      _ <- adoption match
        case Follower.Adoption.Adopted(ref) =>
          for
            at <- perform(now)
            latency = Duration.between(geometry.slotStart(slot), at).toMillis
            _ <- perform(stats.latency(epoch, latency.max(0L)))
            _ <- perform(pending.update(_ :+ Forger.Forged(ref, slot)))
            _ <- say(
              ForgeTrace.AdoptedBlock(slot, ref.hash, block.estimatedSize, block.body.ids)
            )
            _ <- perform(events.emit(ForgeEvent.Adopted(ref, Some(latency))))
          yield ()
        case Follower.Adoption.Lost(reason) =>
          say(ForgeTrace.DidntAdoptBlock(slot, block.header.hash)) *>
            perform(events.emit(ForgeEvent.Lost(slot, block.header.hash, reason.message)))
        case Follower.Adoption.Invalid(e) =>
          SessionEnd.of(e) match
            case SessionEnd.Unavailable(unavailable) => perform(unavailable.raise[F, Unit])
            // a crypto provider failed on our own block: an exception in the forging thread, which
            // is linked to the node in the reference, so the forger stops with Fatal.Unverifiable
            // and nothing leaves the pool (consensus 82ecba32 NodeKernel.hs forkBlockForging,
            // Background.hs:107-108,142)
            case SessionEnd.Unverifiable(defect) => perform(defect.raise[F, Unit])
            // a refusal, or a defect a transaction of ours triggers (SessionEnd.Held): the block's
            // transactions leave the pool, valid or not, so that the next leader slot does not
            // repeat the failure (`NodeKernel.hs` 770-783), and the slot ends
            case _ =>
              val ids = chosen.map(_._1.id).toSet
              say(ForgeTrace.ForgedInvalidBlock(slot, block.header.hash, e)) *>
                perform(
                  mempool
                    .removeTxsEvenIfValid(ids)(using summon, Mempool.raisedAt[F](context.base.tip))
                    .void
                    .whenA(ids.nonEmpty)
                )
    yield ()).value.void

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
    * the tip's slot (`succWithOrigin`, zero at Origin), and for no other.
    */
  private def forecast(base: VolatileChain[S], slot: BigInt): Either[OutsideForecastRange, Unit] =
    val at = base.tipRef.map(_.slot)
    val maxFor = at.fold(BigInt(0))(_ + 1) + forecastWindow
    Either.cond(at.contains(slot) || slot < maxFor, (), OutsideForecastRange(at, maxFor, slot))

  private def lift[A](e: Either[ForgeError, A])(using Handle[F, NodeError]): F[A] =
    e.fold(_.raise[F, A], F.pure)

  /** The transactions of the block, from the mempool's held ones ([[BlockBuilder.select]]). */
  private def select(
      base: VolatileChain[S],
      slot: BigInt,
      epoch: BigInt,
      ticked: MempoolLedger,
      held: MempoolState
  )(using Handle[F, NodeError]): F[Vector[(Entry, BlockBuilder.Piece)]] =
    for
      readable <- UtxoStore.complete(
        utxoStore,
        ticked.utxo,
        UtxoStore.readSet(held.entries.map(_.tx))
      )
      selection <- F.blocking(
        BlockBuilder.select(
          ticked.withUtxo(readable),
          slot,
          epoch,
          geometry,
          globals,
          held.entries,
          BlockBuilder.Limits.of(ticked.params),
          Some(
            base.state.clock(
              Placement.of(base.state.geometry(geometry), config.forks),
              Era.Conway,
              slot
            )
          )
        )
      )
      chosen <- selection.fold(
        {
          case unavailable: LedgerFailure.Unavailable =>
            EvaluationUnavailable(base.tip, unavailable)
              .raise[F, Vector[(Entry, BlockBuilder.Piece)]]
          // the forging thread is linked to the node in the reference, which re-applies the pool's
          // transactions in it (consensus 82ecba32 Node/NodeKernel.hs `forkBlockForging`)
          case LedgerFailure.Unverifiable(tx, defect) =>
            VerificationDefect(base.tip, VerificationDefect.Site.Witness(tx), defect)
              .raise[F, Vector[(Entry, BlockBuilder.Piece)]]
        },
        F.pure
      )
    yield chosen

  /** Build and sign the block (`forgeBlock`). The checks here are Scarab's own and never refuse a
    * block the steps before let through: the key's window, the body size and the decoding of the
    * block just built.
    */
  private def assemble(
      context: Forger.Context[S],
      slot: BigInt,
      ticked: MempoolLedger,
      cert: VrfCert,
      pieces: Vector[BlockBuilder.Piece]
  )(using Handle[F, NodeError], Raise[F, Fatal]): F[Forger.Built] =
    val body = BlockBuilder.body(pieces)
    val kesPeriod = kesPeriodOf(slot)
    // the header, the key's evolution and the signature all use the credentials of one moment: a
    // reload (SIGHUP) waits until the block is signed
    val assembled: F[Either[ForgeError, (Bytes, Bytes, BigInt, BigInt)]] = hot.locked { c =>
      val start = c.opCert.kesPeriod
      val header = for
        _ <- Either.cond(
          start <= kesPeriod && kesPeriod < start + config.maxKesEvolutions,
          (),
          ForgeError.KeyPeriodOutOfRange(kesPeriod, start, config.maxKesEvolutions)
        )
        _ <- Either.cond(
          body.size <= ticked.params.maxBlockBodySize,
          (),
          ForgeError.Assembly(
            ForgeAssemblyProblem.BodySize(body.size, ticked.params.maxBlockBodySize)
          )
        )
      yield BlockBuilder.headerBody(
        context.blockNo,
        slot,
        context.base.tipRef.map(_.hash),
        c.coldVkey,
        c.vrf.publicKey,
        cert,
        body.size,
        body.hash,
        c.opCert,
        ticked.params.version
      )
      header match
        case Left(e)           => F.pure(Left(e))
        case Right(headerBody) =>
          // the key is at the slot's period, and written back there, before it signs in it
          hot.evolved(c, kesPeriod).flatMap {
            case Left(e) => F.pure(Left(e))
            case Right(_) =>
              F.blocking(c.kes.sign(headerBody))
                .map(signature =>
                  Right((headerBody, signature, kesPeriod - start, c.opCert.sequence))
                )
          }
    }
    assembled.flatMap(lift).flatMap { (headerBody, signature, relative, counter) =>
      val raw = BlockBuilder.block(headerBody, signature, body)
      lift(
        Block
          .decode(raw, geometry.byron.epochSlots)
          .left
          .map(e => ForgeError.Assembly(ForgeAssemblyProblem.BlockDecode(e)))
          .map(decoded => Forger.Built(decoded.header, raw, body, relative, counter))
      )
    }

  /** The Conway ledger state at `slot` from the state at the tip: the combinator's tick
    * ([[Hfc.tick]]: the epoch boundaries on the way, then the slot's own work), as the block rules
    * would make it for a block in that slot.
    */
  private def tick(
      nes: NewEpochState[Conway],
      slot: BigInt
  ): Either[ForgeError, MempoolLedger] =
    ticked(nes, slot).map(MempoolLedger.of)

  private def ticked(
      nes: NewEpochState[Conway],
      slot: BigInt
  ): Either[ForgeError, NewEpochState[Conway]] =
    for
      target <- SlotNo
        .fromBigInt(slot)
        .left
        .map(e => ForgeError.LedgerViewUnavailable(slot, ForgeLedgerProblem.Vocabulary(e)))
      state <- Hfc
        .tick(Globals(globals, geometry), config.forks.eraAt)(
          EraNes.InEra(ShelleyBasedEra.SConway, nes),
          target
        )
        .left
        .map(f => ForgeError.LedgerViewUnavailable(slot, ForgeLedgerProblem.Tick(f)))
      conway <- ConwayView
        .of(state)
        .toRight(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftConway))
    yield conway

  /** The ledger and Praos state of the tip carried to `epoch` (cached per tip and epoch: the epoch
    * boundary is the expensive part and every slot of a new epoch needs it). This is where the
    * ledger view of the slot comes from; the refusals here are Scarab's own (only Praos with Conway
    * transactions is forged, on a fully validated ledger).
    */
  private def crossedTo(
      current: VolatileChain[S],
      epoch: BigInt,
      slot: BigInt
  )(using Handle[F, NodeError]): F[Forger.Crossed] =
    val state = current.state
    val key = (current.tipRef.map(_.hash), epoch)
    cache.get.flatMap {
      case Some(c) if c.key == key => F.pure(c)
      case _ =>
        for
          _ <- lift(
            Either.cond(
              !state.consensus.boot.active,
              (),
              ForgeError.UnsupportedProtocol(ForgeProtocolProblem.Bootstrap)
            )
          )
          nes <- lift(
            ConwayView(state, current.tipRef).left.map(why =>
              ForgeError.UnsupportedProtocol(why match
                case ConwayView.Unavailable.InByron        => ForgeProtocolProblem.InByron
                case ConwayView.Unavailable.InEarlier(era) => ForgeProtocolProblem.Earlier(era)
                case ConwayView.Unavailable.Refused(e)     => ForgeProtocolProblem.Mirror(e))
            )
          )
          _ <- lift(
            Either.cond(
              epoch >= nes.epoch.toBigInt,
              (),
              ForgeError.LedgerViewUnavailable(
                slot,
                ForgeLedgerProblem.EpochPrecedes(epoch, nes.epoch.toBigInt)
              )
            )
          )
          c <- F
            .blocking {
              // the ledger view of the leader check is the forecast of the tip's ledger
              // (`futureForecast`, which ticks with TICKF: cardano-ledger f649f975
              // `Shelley/Rules/Tick.hs` 201-242, `Conway/Rules/Tickf.hs` 42-66): into the next
              // epoch the mark distribution, in the tip's epoch and two or more epochs on the
              // current one, as the header rules take it (`ShelleyHeaderForecast`)
              val pools = MirrorEraRead(nes)(using ShelleyBasedEra.SConway)
                .leaderPools(nextEpoch = epoch == nes.epoch.toBigInt + 1)
              // the boundaries between the tip's epoch and the slot's, for the block's own ledger
              // (`applyChainTick`); the slot's own work is done per attempt
              val start =
                if epoch > nes.epoch.toBigInt then ticked(nes, geometry.firstSlot(epoch))
                else Right(nes)
              start.map(
                Forger.Crossed(key, _, pools, Praos.tick(state.protocol, Era.Conway, epoch))
              )
            }
            .flatMap(lift)
          _ <- cache.set(Some(c))
        yield c
    }

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
  /** What the blockchain time reads: the wait for the system start, an unknown slot, or the slot.
    */
  private[forge] enum Reading:
    case BeforeStart(remaining: FiniteDuration)
    case Unknown
    case Known(slot: BigInt)

  /** How far back the wall clock may move without stopping the node (`hfbtMaxClockRewind`,
    * consensus 82ecba329 `Node.hs` 560).
    */
  private[forge] val MaxClockRewind: Duration = Duration.ofSeconds(20)

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
          // BlockBuilder.select skips a transaction that triggers a defect, and adoption makes it
          // a refusal of our own block, so none reaches here; were one to, there is no verdict
          case SessionEnd.Held(e) => Fatal.Unavailable(e)
          // a crypto provider that fails on our own block is raised by `build`, with nothing evicted
          case SessionEnd.Unverifiable(e) => Fatal.Unverifiable(e)
          case _                          => Fatal.Internal(error)
        terminal.raise[Fatal, A](fatal)
      }
    }

  /** How long the blockchain time waits before it asks again for a slot it could not translate
    * (`BackoffDelay 60`, consensus 82ecba329 `Node.hs` 550).
    */
  val UnknownSlotBackoff: FiniteDuration = 60.seconds

  /** Whether the era history at the chain's tip translates `slot` (`wallclockToSlot` of the summary
    * of the current ledger, `hfbtGetLedgerState`): the horizon of [[ChainRead.clock]], the same one
    * the transactions' time translation has.
    */
  def withinHorizon[S](config: PraosConfig)(using
      read: ChainRead[S]
  ): (VolatileChain[S], BigInt) => Boolean =
    (current, slot) =>
      val placement = Placement.of(current.state.geometry(config.geometry), config.forks)
      current.state.clock(placement, Era.Conway, slot).posix(slot).isRight

  /** The tip's ledger and chain-dependent state carried into an epoch, and the pool distribution of
    * its forecast ledger view.
    */
  final case class Crossed(
      key: (Option[Bytes], BigInt),
      nes: NewEpochState[Conway],
      pools: PoolDistribution,
      protocol: ProtocolState
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

  /** A block built and signed, not yet adopted. */
  final case class Built(
      header: Header,
      raw: Bytes,
      body: BlockBuilder.Body,
      relativeKesPeriod: BigInt,
      counter: BigInt
  ):
    /** `estimateBlockSize` of the header (see [[ForgeTrace.AdoptedBlock]]). */
    def estimatedSize: Long = header.raw.size.toLong + body.size + 10

  /** @param source
    *   Where the KES key is written back after evolving and read from again at a reload; none: the
    *   key is kept in memory only, as the Haskell node does.
    */
  def create[F[_]: Async, S](
      config: PraosConfig,
      globals: LedgerGlobals,
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
      source: Option[HotKeySource[F]] = None
  )(using
      Stateful[F, VolatileChain[S]],
      ChainRead[S]
  ): F[Forger[F, S]] =
    (
      Ref.of[F, Option[Crossed]](None),
      Ref.of[F, Vector[Forged]](Vector.empty),
      ForgeStats.create[F],
      HotKeys.create[F](credentials, source, config, events)
    ).mapN { (cache, pending, stats, hot) =>
      new Forger[F, S](
        config,
        globals,
        credentials,
        hot,
        follower,
        mempool,
        events,
        forecastWindow.getOrElse(config.geometry.stabilityWindow.toBigInt),
        now.getOrElse(Clock[F].realTimeInstant),
        sleep.getOrElse(d => Async[F].sleep(d)),
        unknownSlotBackoff,
        horizon.getOrElse(Forger.withinHorizon(config)),
        cache,
        pending,
        utxoStore,
        stats
      )
    }
