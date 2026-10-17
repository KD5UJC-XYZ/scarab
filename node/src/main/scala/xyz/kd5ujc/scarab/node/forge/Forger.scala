// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import scala.annotation.unused
import cats.effect.{Async, Clock, Ref}
import cats.mtl.{Handle, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.ForgeError
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.ledger.VrfCert
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash}
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.consensus.{Praos, PraosConfig}
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{Epoch, LedgerGlobals, LedgerState}
import xyz.kd5ujc.scarab.node.mempool.Mempool
import xyz.kd5ujc.scarab.storage.UtxoStore
import scala.concurrent.duration.*

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

  /** The evolved KES key could not be written back; it is tried again at the next slot. */
  case KesPersistFailed(kesPeriod: BigInt, error: ForgeError)

  /** A slot we cannot forge on (the tip is too old to forge from). */
  case Skipped(slot: BigInt, reason: String)

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
  */
final class Forger[F[_]: Async](
    config: PraosConfig,
    globals: LedgerGlobals,
    /** The pool's keys as they were at the start; the VRF key and the cold key never change, the
      * KES key and certificate are those of `hot`.
      */
    credentials: PoolCredentials,
    val hot: HotKeys[F],
    follower: Follower[F, NodeState],
    mempool: Mempool[F],
    events: ForgeEvents[F],
    /** The ledger view of a slot is only taken from a tip at most this many slots older. */
    maxTipAge: BigInt,
    /** True once the node has a peer to learn the chain from: a node that has not yet intersected
      * with anyone would otherwise forge on its own empty chain.
      */
    ready: F[Boolean],
    /** `now` for tests; the real clock by default. */
    now: F[Instant],
    sleep: FiniteDuration => F[Unit],
    cache: Ref[F, Option[Forger.Crossed]],
    pending: Ref[F, Vector[Forger.Forged]],
    /** The backing store of the UTxO set: the read set of the candidates is fetched from it. */
    utxoStore: Option[UtxoStore[F]],
    /** Per-epoch counters (H5): checked and led slots, blocks, skips by reason, forge latency. */
    val stats: ForgeStats[F]
)(using chain: Stateful[F, VolatileChain[NodeState]], handle: Handle[F, NodeError]):
  private val F = Async[F]
  private val geometry = config.geometry

  /** Run until cancelled. */
  def run: F[Unit] =
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

  private def kesPeriodOf(slot: BigInt): BigInt = slot / config.slotsPerKesPeriod

  /** Switch to the KES key and operational certificate that are in the key files now, if they are
    * good for this pool at this time and on this chain; otherwise keep the old ones and report a
    * typed [[ForgeEvent.KesReloadRefused]]. The Haskell node does this on SIGHUP:
    * `Cardano.Node.Run` `installSigHUPHandler` calls `updateBlockForging`, which reads the
    * credentials again and hands them to `setBlockForging` (cardano-node 11.1.3, `Run.hs` lines 596
    * and 626).
    */
  def reload: F[Unit] =
    val attempted =
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
    attempted.handleErrorWith(t =>
      events.emit(
        ForgeEvent.KesReloadRefused(
          ReloadRefusal.Unreadable(ForgeError.Assembly(s"${t.getClass.getName}: ${t.getMessage}"))
        )
      )
    )

  private def loop(last: Option[BigInt]): F[Unit] =
    for
      instant <- now
      slot = geometry.slotAt(instant)
      // slots that passed while the previous step was still running are never attempted: say so
      _ <- last.fold(F.unit)(l =>
        (l + 1 until slot)
          .take(Forger.MaxReportedMissed)
          .toList
          .traverse_(missed =>
            skipped(missed, ForgeEpochStats.Busy, "the forger was busy with an earlier slot")
          )
      )
      // the epoch the previous slot was in has ended: its counters are final
      _ <- last.traverse_ { l =>
        val ended = geometry.epochOf(l)
        if geometry.epochOf(slot) > ended then
          stats.epoch(ended).flatMap(s => events.emit(ForgeEvent.EpochSummary(ended, s)))
        else F.unit
      }
      _ <- if last.exists(_ >= slot) then F.unit else step(slot)
      after <- now
      untilNext = Duration.between(after, geometry.slotStart(slot + 1))
      _ <- sleep(untilNext.toMillis.max(1L).millis)
      _ <- loop(Some(slot))
    yield ()

  private def skipped(slot: BigInt, key: String, reason: String): F[Unit] =
    stats.skipped(geometry.epochOf(slot), key) *> events.emit(ForgeEvent.Skipped(slot, reason))

  /** Everything for one slot; nothing escapes (a failure is reported and the next slot goes on). */
  private[forge] def step(slot: BigInt): F[Unit] =
    hot
      .advance(kesPeriodOf(slot))
      .handleErrorWith(t =>
        failed(slot, ForgeError.KesFailure(s"${t.getClass.getName}: ${t.getMessage}"))
      ) *> handle
      .attempt(attempt(slot))
      .flatMap {
        case Right(_)                      => F.unit
        case Left(_: ForgeError.NotLeader) => F.unit
        case Left(e: ForgeError)           => failed(slot, e)
        case Left(other)                   => failed(slot, ForgeError.Assembly(other.message))
      }
      .handleErrorWith(t =>
        failed(slot, ForgeError.Assembly(s"${t.getClass.getName}: ${t.getMessage}"))
      ) *> watch

  private def failed(slot: BigInt, error: ForgeError): F[Unit] =
    stats.failed(geometry.epochOf(slot)) *> events.emit(ForgeEvent.Failed(slot, error))

  private def attempt(slot: BigInt)(using Handle[F, NodeError]): F[Unit] =
    for
      current <- chain.get
      connected <- ready
      tipSlot = current.tipRef.map(_.slot)
      _ <-
        if !connected then stats.skipped(geometry.epochOf(slot), ForgeEpochStats.NotConnected)
        else if tipSlot.exists(_ >= slot) then
          // a block already occupies this slot or a later one
          stats.skipped(geometry.epochOf(slot), ForgeEpochStats.SlotOccupied)
        else if slot - tipSlot.getOrElse(BigInt(0)) > maxTipAge then
          skipped(
            slot,
            ForgeEpochStats.TipTooOld,
            s"the tip (${tipSlot.fold("genesis")(t => s"slot $t")}) is too old to forge from"
          )
        else forge(current, slot)
    yield ()

  private def lift[A](e: Either[ForgeError, A]): F[A] = e.fold(_.raise[F, A], F.pure)

  private def forge(current: VolatileChain[NodeState], slot: BigInt): F[Unit] =
    val epoch = geometry.epochOf(slot)
    val tip = current.tipRef
    for
      crossed <- crossedTo(current, epoch, slot)
      _ <- stats.checked(epoch)
      pools = PoolDistributionOf(crossed.ledger)
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
      ticked = Epoch.tick(crossed.ledger, slot, geometry.firstSlot(epoch), globals)
      _ <- lift(
        Either.cond(
          ticked.params.protocolMajor >= 9,
          (),
          ForgeError.UnsupportedProtocol(
            s"protocol version ${ticked.params.protocolMajor} is not Praos with Conway transactions"
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
      chosen <- F.blocking(
        BlockBuilder.select(
          ticked.copy(utxo = readable),
          slot,
          epoch,
          geometry,
          globals,
          held.entries,
          limits
        )
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
        case Left(e @ ForgeError.SelfRejected(rule, reason)) if chosen.nonEmpty =>
          // never let a transaction we mis-judged cost the block: try again without any
          failed(slot, e) *>
            build(current, tip, slot, epoch, ticked, cert, Vector.empty, stake, pools.total)
              .flatMap(_.fold(e2 => failed(slot, e2), _ => F.unit))
        case Left(e)  => failed(slot, e)
        case Right(_) => F.unit
    yield ()

  private def build(
      @unused current: VolatileChain[NodeState],
      tip: Option[BlockRef],
      slot: BigInt,
      epoch: BigInt,
      ticked: LedgerState,
      cert: VrfCert,
      pieces: Vector[BlockBuilder.Piece],
      stake: BigInt,
      totalStake: BigInt
  ): F[Either[ForgeError, Unit]] =
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
            s"body of ${body.size} bytes exceeds ${ticked.params.maxBlockBodySize}"
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
            F.pure(Left(ForgeError.Assembly(s"the block we built does not decode: ${e.message}")))
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
                  events.emit(ForgeEvent.Lost(slot, h.hash, reason)).as(Right(()))
              case Follower.Adoption.Invalid(e) =>
                F.pure(Left(ForgeError.SelfRejected(e.rule, e.reason)))
            }
    }

  /** The ledger and Praos state of the tip carried to `epoch` (cached per tip and epoch: the epoch
    * boundary is the expensive part and every slot of a new epoch needs it).
    */
  private def crossedTo(
      current: VolatileChain[NodeState],
      epoch: BigInt,
      slot: BigInt
  ): F[Forger.Crossed] =
    val state = current.state
    val key = (current.tipRef.map(_.hash), epoch)
    cache.get.flatMap {
      case Some(c) if c.key == key => F.pure(c)
      case _ =>
        for
          _ <- lift(
            Either.cond(
              !state.boot.active,
              (),
              ForgeError.UnsupportedProtocol("the ledger is in bootstrap mode")
            )
          )
          _ <- lift(
            Either.cond(
              epoch >= state.ledger.epoch,
              (),
              ForgeError.LedgerViewUnavailable(
                slot,
                s"epoch $epoch precedes the ledger's ${state.ledger.epoch}"
              )
            )
          )
          c <- F.blocking {
            val ledger = (state.ledger.epoch + 1 to epoch).foldLeft(state.ledger)((s, e) =>
              Epoch.boundary(s, e, globals)
            )
            Forger.Crossed(key, ledger, Praos.tick(state.protocol, Era.Conway, epoch))
          }
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
  /** At most this many missed slots are reported at once (a suspended machine would report
    * millions).
    */
  private val MaxReportedMissed = 1000

  /** The tip's ledger and chain-dependent state carried into an epoch. */
  final case class Crossed(
      key: (Option[Bytes], BigInt),
      ledger: LedgerState,
      protocol: ProtocolState
  )

  /** A block we forged and adopted, kept to notice if it is replaced later. */
  final case class Forged(ref: BlockRef, slot: BigInt)

  def create[F[_]: Async](
      config: PraosConfig,
      globals: LedgerGlobals,
      credentials: PoolCredentials,
      follower: Follower[F, NodeState],
      mempool: Mempool[F],
      events: ForgeEvents[F],
      ready: F[Boolean],
      maxTipAge: Option[BigInt] = None,
      now: Option[F[Instant]] = None,
      sleep: Option[FiniteDuration => F[Unit]] = None,
      utxoStore: Option[UtxoStore[F]] = None,
      /** Where the KES key is written back after evolving and read from again at a reload; none:
        * the key is kept in memory only, as the Haskell node does.
        */
      source: Option[HotKeySource[F]] = None
  )(using Stateful[F, VolatileChain[NodeState]], Handle[F, NodeError]): F[Forger[F]] =
    (
      Ref.of[F, Option[Crossed]](None),
      Ref.of[F, Vector[Forged]](Vector.empty),
      ForgeStats.create[F],
      HotKeys.create[F](credentials, source, config, events)
    ).mapN { (cache, pending, stats, hot) =>
      new Forger[F](
        config,
        globals,
        credentials,
        hot,
        follower,
        mempool,
        events,
        maxTipAge.getOrElse(config.geometry.stabilityWindow),
        ready,
        now.getOrElse(Clock[F].realTimeInstant),
        sleep.getOrElse(d => Async[F].sleep(d)),
        cache,
        pending,
        utxoStore,
        stats
      )
    }

/** The leader schedule's view of a ledger: stake and VRF key hash per pool. */
object PoolDistributionOf:
  def apply(ledger: LedgerState): xyz.kd5ujc.scarab.ledger.PoolDistribution =
    xyz.kd5ujc.scarab.ledger.PoolDistribution(
      ledger.poolDistribution.poolStake,
      ledger.poolDistribution.pools.map { case (id, p) => id -> p.vrfKeyHash }
    )
