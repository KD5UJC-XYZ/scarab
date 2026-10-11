// SPDX-License-Identifier: Apache-2.0
package lab.node.forge

import cats.effect.{Async, Clock, Ref}
import cats.mtl.{Handle, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import lab.cbor.Bytes
import lab.node.*
import lab.node.consensus.{Praos, PraosConfig, PraosState}
import lab.node.ledger.{Epoch, LedgerGlobals, LedgerState}
import lab.node.mempool.Mempool
import scala.concurrent.duration.*

/** What the forger reports, for the JSON log and for tests. */
enum ForgeEvent:
  /** Forging is on for this pool. */
  case Started(poolId: Bytes, vrfKeyHash: Bytes, kesStart: BigInt, kesEvolutions: BigInt)

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
      totalStake: BigInt
  )

  /** The forged block is now the tip of our chain (and is served to peers). */
  case Adopted(block: BlockRef)

  /** The forged block did not become part of our chain, or was replaced by another one later. */
  case Lost(slot: BigInt, hash: Bytes, reason: String)

  /** The KES key moved to a new period. */
  case KesEvolved(kesPeriod: BigInt, relativeKesPeriod: Int)

  /** A slot we cannot forge on (the tip is too old to forge from). */
  case Skipped(slot: BigInt, reason: String)

  /** A real problem (not simply losing the leader lottery). */
  case Failed(slot: BigInt, error: ForgeError)

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
    credentials: PoolCredentials,
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
    pending: Ref[F, Vector[Forger.Forged]]
)(using chain: Stateful[F, VolatileChain[NodeState]], handle: Handle[F, NodeError]):
  private val F = Async[F]
  private val geometry = config.geometry

  /** Run until cancelled. */
  def run: F[Unit] =
    events.emit(
      ForgeEvent.Started(
        credentials.poolId,
        credentials.vrfKeyHash,
        credentials.opCert.kesPeriod,
        config.maxKesEvolutions
      )
    ) *> loop(None)

  private def loop(last: Option[BigInt]): F[Unit] =
    for
      instant <- now
      slot = geometry.slotAt(instant)
      _ <- if last.exists(_ >= slot) then F.unit else step(slot)
      after <- now
      untilNext = Duration.between(after, geometry.slotStart(slot + 1))
      _ <- sleep(untilNext.toMillis.max(1L).millis)
      _ <- loop(Some(slot))
    yield ()

  /** Everything for one slot; nothing escapes (a failure is reported and the next slot goes on). */
  private[forge] def step(slot: BigInt): F[Unit] =
    handle
      .attempt(attempt(slot))
      .flatMap {
        case Right(_)                      => F.unit
        case Left(e: ForgeError.NotLeader) => F.unit
        case Left(e: ForgeError)           => events.emit(ForgeEvent.Failed(slot, e))
        case Left(other) =>
          events.emit(ForgeEvent.Failed(slot, ForgeError.Assembly(other.message)))
      }
      .handleErrorWith(t =>
        events.emit(
          ForgeEvent.Failed(slot, ForgeError.Assembly(s"${t.getClass.getName}: ${t.getMessage}"))
        )
      ) *> watch

  private def attempt(slot: BigInt)(using Handle[F, NodeError]): F[Unit] =
    for
      current <- chain.get
      connected <- ready
      tipSlot = current.tipRef.map(_.slot)
      _ <-
        if !connected then F.unit
        else if tipSlot.exists(_ >= slot) then
          F.unit // a block already occupies this slot or a later one
        else if slot - tipSlot.getOrElse(BigInt(0)) > maxTipAge then
          events.emit(
            ForgeEvent.Skipped(
              slot,
              s"the tip (${tipSlot.fold("genesis")(t => s"slot $t")}) is too old to forge from"
            )
          )
        else forge(current, slot)
    yield ()

  private def lift[A](e: Either[ForgeError, A]): F[A] = e.fold(_.raise[F, A], F.pure)

  private def forge(current: VolatileChain[NodeState], slot: BigInt): F[Unit] =
    val epoch = geometry.epochOf(slot)
    val tip = current.tipRef
    for
      crossed <- crossedTo(current, epoch, slot)
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
      cert <- lift(
        Leadership.check(
          slot,
          crossed.praos.epochNonce,
          credentials.vrf,
          stake,
          pools.total,
          config.activeSlotsCoeff
        )
      )
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
      chosen <- F.blocking(
        BlockBuilder.select(ticked, slot, epoch, geometry, globals, held.entries, limits)
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
          events.emit(ForgeEvent.Failed(slot, e)) *>
            build(current, tip, slot, epoch, ticked, cert, Vector.empty, stake, pools.total)
              .flatMap(_.fold(e2 => events.emit(ForgeEvent.Failed(slot, e2)), _ => F.unit))
        case Left(e)  => events.emit(ForgeEvent.Failed(slot, e))
        case Right(_) => F.unit
    yield ()

  private def build(
      current: VolatileChain[NodeState],
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
    val start = credentials.opCert.kesPeriod
    val relative = kesPeriod - start
    val header = for
      _ <- Either.cond(
        start <= kesPeriod && kesPeriod < start + config.maxKesEvolutions,
        (),
        ForgeError.KeyPeriodOutOfRange(kesPeriod, start, config.maxKesEvolutions)
      )
      _ <- Either.cond(
        body.size <= ticked.params.maxBlockBodySize,
        (),
        ForgeError.Assembly(s"body of ${body.size} bytes exceeds ${ticked.params.maxBlockBodySize}")
      )
    yield BlockBuilder.headerBody(
      tip.fold(BigInt(0))(_.blockNo + 1),
      slot,
      tip.map(_.hash),
      credentials.coldVkey,
      credentials.vrf.publicKey,
      cert,
      body.size,
      body.hash,
      credentials.opCert,
      ticked.params.version
    )
    header match
      case Left(e) => F.pure(Left(e))
      case Right(headerBody) =>
        for
          before <- F.delay(credentials.kes.period)
          signed <- F.delay(
            credentials.kes
              .evolveTo(relative.toInt)
              .leftMap(e => ForgeError.KesFailure(e.toString))
              .map(_ => credentials.kes.sign(headerBody))
          )
          _ <- events
            .emit(ForgeEvent.KesEvolved(kesPeriod, relative.toInt))
            .whenA(before != relative.toInt)
          result <- signed match
            case Left(e) => F.pure(Left(e))
            case Right(signature) =>
              val raw = BlockBuilder.block(headerBody, signature, body)
              Block.decode(raw, geometry.byron.epochSlots) match
                case Left(e) =>
                  F.pure(
                    Left(ForgeError.Assembly(s"the block we built does not decode: ${e.message}"))
                  )
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
                    totalStake
                  )
                  events.emit(forged) *> follower.adoptForged(h, raw).flatMap {
                    case Follower.Adoption.Adopted(ref) =>
                      pending.update(_ :+ Forger.Forged(ref, slot)) *>
                        events.emit(ForgeEvent.Adopted(ref)).as(Right(()))
                    case Follower.Adoption.Lost(reason) =>
                      events.emit(ForgeEvent.Lost(slot, h.hash, reason)).as(Right(()))
                    case Follower.Adoption.Invalid(e) =>
                      F.pure(Left(ForgeError.SelfRejected(e.rule, e.reason)))
                  }
        yield result

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
            Forger.Crossed(key, ledger, Praos.tick(state.praos, epoch))
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
              events.emit(
                ForgeEvent.Lost(f.slot, f.ref.hash, "replaced by another block at the same height")
              )
            )
        }
    }

object Forger:
  /** The tip's ledger and Praos state carried into an epoch. */
  final case class Crossed(
      key: (Option[Bytes], BigInt),
      ledger: LedgerState,
      praos: PraosState
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
      sleep: Option[FiniteDuration => F[Unit]] = None
  )(using Stateful[F, VolatileChain[NodeState]], Handle[F, NodeError]): F[Forger[F]] =
    (Ref.of[F, Option[Crossed]](None), Ref.of[F, Vector[Forged]](Vector.empty)).mapN {
      (cache, pending) =>
        new Forger[F](
          config,
          globals,
          credentials,
          follower,
          mempool,
          events,
          maxTipAge.getOrElse(config.geometry.stabilityWindow),
          ready,
          now.getOrElse(Clock[F].realTimeInstant),
          sleep.getOrElse(d => Async[F].sleep(d)),
          cache,
          pending
        )
    }

/** The leader schedule's view of a ledger: stake and VRF key hash per pool. */
object PoolDistributionOf:
  def apply(ledger: LedgerState): lab.node.consensus.PoolDistribution =
    lab.node.consensus.PoolDistribution(
      ledger.poolDistribution.poolStake,
      ledger.poolDistribution.pools.map { case (id, p) => id -> p.vrfKeyHash }
    )
