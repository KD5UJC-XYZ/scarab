// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.{ForgeAssemblyProblem, ForgeLedgerProblem, ForgeProtocolProblem}
import scala.annotation.unused
import cats.effect.{Async, Clock, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.{BlockFailure, ChainError, EvaluationUnavailable, ForgeError}
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.ledger.VrfCert
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash}
import xyz.kd5ujc.scarab.node.{Fatal, Follower, SessionEnd}
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig}
import xyz.kd5ujc.scarab.consensus.state.{Placement, ProtocolState}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.LedgerGlobals
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.node.read.{ChainRead, ConwayView}
import xyz.kd5ujc.scarab.node.read.LeaderPools.given
import xyz.kd5ujc.scarab.node.mempool.{Mempool, MempoolLedger}
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

  /** The pool led `slot` and a block was assembled and signed (not yet adopted). */
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

  /** The forged block is now the tip of our chain (and is served to peers). */
  case Adopted(block: BlockRef, latencyMs: Option[Long] = None)

  /** The forged block did not become part of our chain, or was replaced by another one later. */
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

  /** A slot that was not checked for leadership, and why ([[ForgeSkip]]). */
  case Skipped(slot: BigInt, reason: ForgeSkip)

  /** A real problem (not simply losing the leader lottery). */
  case Failed(slot: BigInt, error: ForgeError)

  /** The forger's counters for an epoch that has just ended (see [[ForgeStats]]). */
  case EpochSummary(epoch: BigInt, stats: ForgeEpochStats)

trait ForgeEvents[F[_]]:
  def emit(event: ForgeEvent): F[Unit]

/** Block production for one pool, in the Praos protocol: once per slot, check leadership with the
  * stake of the ledger view the tip implies for that slot; if elected, build a block from the
  * mempool, KES-sign it, validate it with the node's own block rules and adopt it through the
  * [[Follower]], which tells the chain server (peers fetch it) and the mempool.
  *
  * The slot clock is the wall clock over the era history (`EpochGeometry`); `F` is any `Async` so
  * tests drive it with a test clock. At most one attempt is made per slot, and only the current
  * slot is attempted (a slot that has passed is not forged late).
  *
  * @param credentials
  *   The pool's keys as they were at the start; the VRF key and the cold key never change, the KES
  *   key and certificate are those of `hot`.
  * @param maxTipAge
  *   The ledger view of a slot is only taken from a tip at most this many slots older: the tip of
  *   the chain the block is built on.
  * @param ready
  *   True once the node has a peer to learn the chain from: a node that has not yet intersected
  *   with anyone would otherwise forge on its own empty chain.
  * @param now
  *   `now` for tests; the real clock by default.
  * @param utxoStore
  *   The backing store of the UTxO set: the read set of the candidates is fetched from it.
  * @param stats
  *   Per-epoch counters (H5): checked and led slots, blocks, skips by reason, forge latency.
  */
final class Forger[F[_]: Async, S](
    config: PraosConfig,
    globals: LedgerGlobals,
    credentials: PoolCredentials,
    val hot: HotKeys[F],
    follower: Follower[F, S],
    mempool: Mempool[F],
    events: ForgeEvents[F],
    maxTipAge: BigInt,
    ready: F[Boolean],
    now: F[Instant],
    sleep: FiniteDuration => F[Unit],
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

  private def loop(last: Option[BigInt])(using Handle[F, NodeError], Raise[F, Fatal]): F[Unit] =
    for
      instant <- now
      slot = geometry.slotAt(instant)
      // slots that passed while the previous step was still running are never attempted: say so
      _ <- last.fold(F.unit)(l =>
        (l + 1 until slot)
          .take(Forger.MaxReportedMissed)
          .toList
          .traverse_(missed => skipped(missed, ForgeSkip.Busy))
      )
      // the epoch the previous slot was in has ended: its counters are final
      _ <- last.traverse_ { l =>
        val ended = geometry.epochOf(l)
        if geometry.epochOf(slot) > ended then
          stats.epoch(ended).flatMap(s => events.emit(ForgeEvent.EpochSummary(ended, s)))
        else F.unit
      }
      _ <- if last.exists(_ >= slot) then F.unit else stepInScope(slot)
      after <- now
      untilNext = Duration.between(after, geometry.slotStart(slot + 1))
      _ <- sleep(untilNext.toMillis.max(1L).millis)
      _ <- loop(Some(slot))
    yield ()

  private def skipped(slot: BigInt, reason: ForgeSkip): F[Unit] =
    stats.skipped(geometry.epochOf(slot), reason.kind) *> events.emit(
      ForgeEvent.Skipped(slot, reason)
    )

  /** One slot in its own scope: a forging refusal permits another slot; a service failure or defect
    * is returned to the owner and never enters the recoverable forging channel.
    */
  private[forge] def step(slot: BigInt): F[Either[Fatal, Unit]] =
    Forger.scoped(stepInScope(slot))

  private def stepInScope(
      slot: BigInt
  )(using handle: Handle[F, NodeError], terminal: Raise[F, Fatal]): F[Unit] =
    handle.attempt(F.defer(hot.advance(kesPeriodOf(slot)) *> attempt(slot))).flatMap {
      case Right(_)                      => F.unit
      case Left(_: ForgeError.NotLeader) => F.unit
      case Left(e: ForgeError) =>
        HotKeys.infrastructureFailure(e).fold(failed(slot, e))(_.raise[F, Unit])
      case Left(other) => other.raise[F, Unit]
    } *> watch

  private def failed(slot: BigInt, error: ForgeError): F[Unit] =
    stats.failed(geometry.epochOf(slot)) *> events.emit(ForgeEvent.Failed(slot, error))

  private def attempt(slot: BigInt)(using Handle[F, NodeError], Raise[F, Fatal]): F[Unit] =
    for
      current <- chain.get
      connected <- ready
      _ <-
        if !connected then stats.skipped(geometry.epochOf(slot), ForgeSkip.Kind.NotConnected)
        else blockContext(current, slot).fold(skipped(slot, _), forgeOn(_, slot))
    yield ()

  /** The chain to forge on in `slot`, as `forkBlockForging` picks it through
    * `mkCurrentBlockContext` (consensus 82ecba329 NodeKernel.hs:585 and 831–891), or why there is
    * none.
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
  ): Either[ForgeSkip, VolatileChain[S]] =
    current.blocks.lastOption match
      case None =>
        current.anchor.block match
          case Some(anchor) if anchor.slot >= slot =>
            Left(ForgeSkip.SlotIsImmutable(slot, anchor.point, anchor.blockNo))
          case _ => Right(current)
      case Some((tip, _)) =>
        if tip.slot < slot then Right(current)
        else if tip.slot > slot then Left(ForgeSkip.BlockFromFuture(slot, tip.slot))
        else if tip.boundary then Right(current)
        else Right(current.copy(blocks = current.blocks.dropRight(1)))

  /** Forge on `base`, the chain the block is built on, unless its tip is too old to forge from. The
    * reference forecasts the ledger view from the ledger at `bcPrevPoint`, the predecessor of the
    * block to forge (consensus 82ecba329 NodeKernel.hs:602), and gives up when the slot is outside
    * the forecast range (`TraceNoLedgerView`, 613-629). The tip of `base` is that predecessor, also
    * when the block is an alternative to the one in the slot ([[blockContext]]).
    */
  private def forgeOn(base: VolatileChain[S], slot: BigInt)(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Unit] =
    val tipSlot = base.tipRef.map(_.slot)
    if slot - tipSlot.getOrElse(BigInt(0)) > maxTipAge then
      skipped(slot, ForgeSkip.TipTooOld(tipSlot))
    else forge(base, slot)

  private def lift[A](e: Either[NodeError, A])(using Handle[F, NodeError]): F[A] =
    e.fold(_.raise[F, A], F.pure)

  private def forge(current: VolatileChain[S], slot: BigInt)(using
      Handle[F, NodeError],
      Raise[F, Fatal]
  ): F[Unit] =
    val epoch = geometry.epochOf(slot)
    val tip = current.tipRef
    for
      crossed <- crossedTo(current, epoch, slot)
      _ <- stats.checked(epoch)
      pools = crossed.nes.leaderPools
      stake = pools.stake.getOrElse(credentials.poolId, BigInt(0))
      // a pool must be registered with this very VRF key to be elected
      _ <- lift(
        Either.cond(
          pools.vrf.get(credentials.poolId).contains(credentials.vrfKeyHash),
          (),
          ForgeError.NotLeader(slot)
        )
      )
      cert <- F
        .blocking(
          Leadership.check(
            slot,
            crossed.protocol.epochNonce,
            credentials.vrf,
            stake,
            pools.total,
            config.activeSlotsCoeff
          )
        )
        .flatMap(lift)
      _ <- stats.leader(epoch)
      ticked <- lift(tick(crossed.nes, slot))
      _ <- lift(
        Either.cond(
          ticked.params.protocolMajor >= 9,
          (),
          ForgeError.UnsupportedProtocol(
            ForgeProtocolProblem.Version(ticked.params.protocolMajor)
          )
        )
      )
      held <- mempool.snapshot
      limits = BlockBuilder.Limits(
        ticked.params.maxBlockBodySize.toLong,
        ticked.params.maxBlockExUnits
      )
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
          limits,
          Some(
            current.state.clock(
              Placement.of(current.state.geometry(geometry), config.forks),
              Era.Conway,
              slot
            )
          )
        )
      )
      chosen <- selection.fold(
        stop =>
          (stop match
            case unavailable: xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable =>
              EvaluationUnavailable(current.tip, unavailable)
            case outOfRange: xyz.kd5ujc.scarab.ledger.LedgerFailure.StateOutOfRange =>
              ChainError.StateOutOfRange(slot, outOfRange.error)
          ).raise[F, Vector[(xyz.kd5ujc.scarab.node.mempool.Entry, BlockBuilder.Piece)]],
        F.pure
      )
      outcome <- build(
        current,
        tip,
        slot,
        epoch,
        ticked,
        cert,
        chosen.map(_._2),
        stake,
        pools.total
      )
      _ <- outcome match
        case Left(e: ForgeError.SelfRejected) if chosen.nonEmpty =>
          // never let a transaction we mis-judged cost the block: try again without any
          // the reference removes the block's transactions from the pool, valid or not, so that the
          // next leader slot does not repeat the failure (`NodeKernel.hs`, `removeTxsEvenIfValid`)
          failed(slot, e) *>
            mempool.removeTxsEvenIfValid(chosen.map(_._1.id).toSet).void *>
            build(current, tip, slot, epoch, ticked, cert, Vector.empty, stake, pools.total)
              .flatMap(_.fold(e2 => failed(slot, e2), _ => F.unit))
        case Left(e)  => failed(slot, e)
        case Right(_) => F.unit
    yield ()

  private def build(
      @unused current: VolatileChain[S],
      tip: Option[BlockRef],
      slot: BigInt,
      epoch: BigInt,
      ticked: MempoolLedger,
      cert: VrfCert,
      pieces: Vector[BlockBuilder.Piece],
      stake: BigInt,
      totalStake: BigInt
  )(using Handle[F, NodeError], Raise[F, Fatal]): F[Either[ForgeError, Unit]] =
    val body = BlockBuilder.body(pieces)
    val kesPeriod = slot / config.slotsPerKesPeriod
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
        tip.fold(BigInt(0))(_.blockNo + 1),
        slot,
        tip.map(_.hash),
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
    assembled.flatMap {
      case Left(e) => F.pure(Left(e))
      case Right((headerBody, signature, relative, counter)) =>
        val raw = BlockBuilder.block(headerBody, signature, body)
        Block.decode(raw, geometry.byron.epochSlots) match
          case Left(e) =>
            F.pure(Left(ForgeError.Assembly(ForgeAssemblyProblem.BlockDecode(e))))
          case Right(decoded) =>
            val h = decoded.header
            val forged = ForgeEvent.Forged(
              slot,
              epoch,
              h.blockNo,
              h.hash,
              h.prevHash,
              body.ids,
              body.size,
              kesPeriod,
              relative.toInt,
              stake,
              totalStake,
              counter
            )
            stats.forged(epoch) *> events.emit(forged) *> follower.adoptForged(h, raw).flatMap {
              case Follower.Adoption.Adopted(ref) =>
                now.flatMap { at =>
                  val latency = Duration.between(geometry.slotStart(slot), at).toMillis
                  stats.adopted(epoch, latency.max(0L)) *>
                    pending.update(_ :+ Forger.Forged(ref, slot)) *>
                    events.emit(ForgeEvent.Adopted(ref, Some(latency))).as(Right(()))
                }
              case Follower.Adoption.Lost(reason) =>
                stats.lost(epoch) *>
                  events.emit(ForgeEvent.Lost(slot, h.hash, reason.message)).as(Right(()))
              case Follower.Adoption.Invalid(e) =>
                SessionEnd.of(e) match
                  case SessionEnd.Unavailable(unavailable) =>
                    unavailable.raise[F, Either[ForgeError, Unit]]
                  // a refusal, or a defect a transaction of ours triggers (SessionEnd.Held): the
                  // block is not adopted, its transactions leave the pool and an empty block is
                  // tried instead
                  case _ => F.pure(Left(ForgeError.SelfRejected(e)))
            }
    }

  /** The Conway ledger state at `slot` from the state at the tip: the combinator's tick
    * ([[Hfc.tick]]: the epoch boundaries on the way, then the slot's own work), as the block rules
    * would make it for a block in that slot.
    */
  private def tick(
      nes: NewEpochState[Conway],
      slot: BigInt
  ): Either[NodeError, MempoolLedger] =
    ticked(nes, slot).map(MempoolLedger.of)

  private def ticked(
      nes: NewEpochState[Conway],
      slot: BigInt
  ): Either[NodeError, NewEpochState[Conway]] =
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
        .map(Forger.tickFault(slot, _))
      conway <- ConwayView
        .of(state)
        .toRight(ForgeError.UnsupportedProtocol(ForgeProtocolProblem.LeftConway))
    yield conway

  /** The ledger and Praos state of the tip carried to `epoch` (cached per tip and epoch: the epoch
    * boundary is the expensive part and every slot of a new epoch needs it).
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
              // the boundaries between the tip's epoch and the slot's, so that the pool distribution
              // is the one in force in the slot's epoch; the slot's own work is done per attempt
              val start =
                if epoch > nes.epoch.toBigInt then ticked(nes, geometry.firstSlot(epoch))
                else Right(nes)
              start.map(Forger.Crossed(key, _, Praos.tick(state.protocol, Era.Conway, epoch)))
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
              stats.lost(geometry.epochOf(f.slot)) *>
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
  /** What a failed tick of the tip's state to `slot` is. A quantity of the state outside its
    * vocabulary type is no forging failure but the node's stop ([[ChainError.StateOutOfRange]]):
    * the reference's `compactCoinOrError` throws in the forging thread, which is linked
    * (ouroboros-consensus 82ecba32 `NodeKernel.hs:378-380`), so the node stops and no later slot is
    * tried. Any other failure leaves the ledger view of this slot unavailable.
    */
  private[forge] def tickFault(
      slot: BigInt,
      failure: xyz.kd5ujc.scarab.ledger.rules.TickFailure
  ): NodeError =
    BlockFailure
      .Tick(failure)
      .stateOutOfRange
      .fold[NodeError](ForgeError.LedgerViewUnavailable(slot, ForgeLedgerProblem.Tick(failure)))(
        ChainError.StateOutOfRange(slot, _)
      )

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
          case _                  => Fatal.Internal(error)
        terminal.raise[Fatal, A](fatal)
      }
    }

  /** At most this many missed slots are reported at once (a suspended machine would report
    * millions).
    */
  private val MaxReportedMissed = 1000

  /** The tip's ledger and chain-dependent state carried into an epoch. */
  final case class Crossed(
      key: (Option[Bytes], BigInt),
      nes: NewEpochState[Conway],
      protocol: ProtocolState
  )

  /** A block we forged and adopted, kept to notice if it is replaced later. */
  final case class Forged(ref: BlockRef, slot: BigInt)

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
      ready: F[Boolean],
      maxTipAge: Option[BigInt] = None,
      now: Option[F[Instant]] = None,
      sleep: Option[FiniteDuration => F[Unit]] = None,
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
        maxTipAge.getOrElse(config.geometry.stabilityWindow.toBigInt),
        ready,
        now.getOrElse(Clock[F].realTimeInstant),
        sleep.getOrElse(d => Async[F].sleep(d)),
        cache,
        pending,
        utxoStore,
        stats
      )
    }
