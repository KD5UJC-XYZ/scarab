// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.Path
import java.time.Instant
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{PraosConfig, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}
import xyz.kd5ujc.scarab.ledger.{Header, NodeError, Nonce}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EpochNo, NewEpochState, PoolDistr, SnapShots}
import xyz.kd5ujc.scarab.node.mempool.TxKit

/** The pieces of the tests of the scheduled forger (#1101, §7): a pool forged with in two harnesses
  * at once, the scheduled forger on the test clock and the per-slot decision ([[Forger.step]]) slot
  * by slot, the same changes made to both, and what each published of every slot.
  */
trait ScheduleFixture extends ForgeFixture:

  /** f = 1: a pool with any stake leads every slot. */
  val everySlot: PraosConfig =
    config.copy(activeSlotsCoeff = Leader.Fraction.checked(1, 1).toOption.get)

  /** The same pool twice, each with a KES key of its own, since forging evolves the key in place.
    */
  def twins(kesStart: Int, seed: Int): (Pool, Pool) =
    val p = pool(kesStart, seed)
    (p, p.copy(creds = p.creds.copy(kes = kesOf(seed))))

  def kesOf(seed: Int): Sum6KesSigning.SigningKey =
    Sum6KesSigning.generate(hex(Array.fill(32)(seed.toByte))).toOption.get

  def vrfOf(seed: Int): Draft03Prover.SigningKey =
    Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)((seed + 50).toByte))).toOption.get

  /** A ledger in `epoch` whose distribution and snapshots give each pool its stake; `mark`, if
    * given, is the mark snapshot instead, which elects the next epoch's leaders.
    */
  def ledgerIn(
      epoch: BigInt,
      stakes: Vector[(Pool, BigInt)],
      mark: Option[Vector[(Pool, BigInt)]] = None,
      funds: Int = 1
  ): NewEpochState[Conway] =
    def snapshot(of: Vector[(Pool, BigInt)]) =
      ForgedChain.snapshotOf(of.map((p, amount) => (p.stake.credential, p.creds, amount)))
    val dist = snapshot(stakes)
    val nes = TxKit.native(funds)
    nes.copy(
      epoch = EpochNo.fromBigInt(epoch).toOption.get,
      es = nes.es.copy(snapshots =
        SnapShots(
          mark.fold(dist)(snapshot),
          dist,
          dist,
          nes.es.snapshots.fee,
          SnapShots.Layout.Node11_1
        )
      ),
      layout = NewEpochState.Layout.Node11_1(PoolDistr.of(dist))
    )

  /** Whether the VRF key of `seed` leads `slot` with all the stake under the genesis nonce. */
  def leads(seed: Int, slot: BigInt, coefficient: PraosConfig = config): Boolean =
    Leadership
      .check(slot, epochNonce, vrfOf(seed), 1, 1, coefficient.activeSlotsCoeff)
      .isRight

  /** The first pool seed whose VRF key leads every slot of `led` and none of `idle`, with all the
    * stake under the genesis nonce.
    */
  def seedLeading(led: Seq[BigInt], idle: Seq[BigInt] = Nil): Int =
    (1 to 400)
      .find(seed => led.forall(leads(seed, _)) && idle.forall(!leads(seed, _)))
      .getOrElse(throw new AssertionError(s"no seed leads $led and not $idle"))

  /** One side of a differential run: its harness, the reading its key source gives at a reload, and
    * its own copy of the pool.
    */
  final case class Side(h: Harness, reading: Ref[IO, HotKeySource.Reading[IO]], pool: Pool)

  /** A key source whose reading the test sets; the evolved key is not written anywhere. */
  def keysFrom(reading: Ref[IO, HotKeySource.Reading[IO]]): HotKeySource[IO] =
    new HotKeySource[IO]:
      def read = reading.get.map(Right(_))
      def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Unchanged)

  /** What a reload finds: the pool's own credentials, with a KES key of their own. */
  def found(p: Pool, seed: Int): HotKeySource.Reading[IO] =
    HotKeySource.Reading.Found(
      HotKeySource.Candidate(
        HotCredentials(kesOf(seed), p.creds.opCert, p.creds.coldVkey),
        p.creds.vrf,
        IO.unit
      )
    )

  /** What each change of a differential run does to a side. */
  type Change = Side => IO[Unit]

  def reloaded(reading: Side => HotKeySource.Reading[IO]): Change =
    side =>
      side.reading.set(reading(side)) *> side.h.forger.reload.flatMap(
        _.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure)
      )

  /** A SIGHUP that installs another VRF key for the pool. */
  def otherVrf(seed: Int, kesSeed: Int): Change =
    reloaded(side =>
      HotKeySource.Reading.Found(
        HotKeySource.Candidate(
          HotCredentials(kesOf(kesSeed), side.pool.creds.opCert, side.pool.creds.coldVkey),
          vrfOf(seed),
          IO.unit
        )
      )
    )

  /** A SIGHUP that installs another KES key, under a certificate issued for it (counter 1). */
  def otherKes(kesSeed: Int, start: Int): Change =
    reloaded(side => HotKeySource.Reading.Found(certified(side.pool, kesOf(kesSeed), start, 1)))

  /** A SIGHUP that installs the same KES key under a certificate issued again (counter 1). */
  def otherCertificate(kesSeed: Int, start: Int): Change =
    reloaded(side => HotKeySource.Reading.Found(certified(side.pool, kesOf(kesSeed), start, 1)))

  private def certified(
      p: Pool,
      kes: Sum6KesSigning.SigningKey,
      start: Int,
      sequence: Int
  ): HotKeySource.Candidate[IO] =
    def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)
    val signable =
      Bytes.fromArray(kes.verificationKey.toArray ++ be8(sequence.toLong) ++ be8(start.toLong))
    HotKeySource.Candidate(
      HotCredentials(
        kes,
        Header.OpCert(kes.verificationKey, sequence, start, p.cold.sign(signable)),
        p.creds.coldVkey
      ),
      p.creds.vrf,
      IO.unit
    )

  /** A SIGHUP that finds the KES key file absent: forging off. */
  def forgingOff: Change =
    reloaded(_ => HotKeySource.Reading.Absent(Path.of("kes.skey")))

  /** A SIGHUP that finds the files again: forging on with the pool's own credentials. */
  def forgingOn(kesSeed: Int): Change =
    reloaded(side => found(side.pool, kesSeed))

  /** The chain changed from elsewhere: a fork or a block that moves the tip. */
  def chained(
      f: VolatileChain[MirrorState] => IO[VolatileChain[MirrorState]]
  ): Change =
    side => side.h.ref.get.flatMap(f).flatMap(side.h.ref.set)

  /** Back to the last block before `slot`: the blocks from it on are gone. */
  def rolledBackBefore(slot: BigInt): Change =
    chained(c =>
      IO.fromEither(
        c.rollback(
          c.blocks.map(_._1).filter(_.slot < slot).lastOption.fold(c.anchor.point)(_.point)
        ).left
          .map(e => new AssertionError(e.toString))
      )
    )

  /** A fork to another chain from its origin: `state` instead of the one the chain grew from. */
  def forkedTo(state: MirrorState): Change =
    chained(_ => IO.pure(VolatileChain.genesis(geometry.k.toInt, state)))

  /** A scenario run on both paths: the slots `from` to `until`, the changes made just before a
    * slot's wake (`before`), and the ones made when the scheduled forger reaches a point of its own
    * (`at`), which the per-slot decision takes before the slot.
    */
  final case class Scenario(
      seed: Int,
      ledger: Pool => NewEpochState[Conway],
      from: BigInt,
      until: BigInt,
      kesStart: Int = 50,
      praos: PraosConfig = config,
      before: Vector[(BigInt, Change)] = Vector.empty,
      at: Vector[(Forger.Point, BigInt, Change)] = Vector.empty,
      scheduler: Boolean = true
  ):
    def changesAt(slot: BigInt): Vector[Change] =
      before.filter(_._1 == slot).map(_._2) ++ at.filter(_._2 == slot).map(_._3)

  /** The scheduled forger's run of `sc` on the test clock, and what it published. */
  def scheduledRun(sc: Scenario, p: Pool): IO[Vector[ForgeEvent]] =
    TestControl.executeEmbed(
      withHandle {
        for
          hooks <- Ref.of[IO, Map[Forger.Point, Change]](
            sc.at.map((point, _, change) => point -> change).toMap
          )
          reading <- Ref.of[IO, HotKeySource.Reading[IO]](found(p, p.creds.opCert.kesPeriod.toInt))
          side <- Deferred[IO, Side]
          stalled = (point: Forger.Point) =>
            point match
              case Forger.Point.Inputs(_) if !sc.scheduler => IO.never[Unit]
              case _                                       => IO.unit
          h <- harness(
            p,
            sc.ledger(p),
            live = true,
            offsetMillis = ((sc.from - 5000) * 1000).toLong,
            unbounded = true,
            source = Some(keysFrom(reading)),
            praos = sc.praos,
            pause = Some(point =>
              stalled(point) *> hooks
                .modify(m => (m - point, m.get(point)))
                .flatMap(_.traverse_(change => side.get.flatMap(change)))
            )
          )
          _ <- side.complete(Side(h, reading, p))
          fiber <- h.forger.run.start
          _ <- sc.before.traverse_((slot, change) =>
            h.settled(slot - 1) *> change(Side(h, reading, p))
          )
          ended <- IO.race(h.settled(sc.until), fiber.join)
          _ <- fiber.cancel
          _ <- ended.fold(
            _ => IO.unit,
            o => IO.raiseError(new AssertionError(s"the forger ended: $o"))
          )
          // every change made at a point of the forger's was made: the point was reached
          left <- hooks.get
          _ <- IO
            .raiseError(new AssertionError(s"points never reached: ${left.keys}"))
            .whenA(left.nonEmpty)
          events <- h.seen
        yield events
      }
    )

  /** The per-slot decision of every slot of `sc`, at the slot's onset, the changes made before it.
    */
  def perSlotRun(sc: Scenario, p: Pool)(using Handle[IO, NodeError]): IO[Vector[ForgeEvent]] =
    for
      at <- Ref.of[IO, Instant](geometry.slotStart(sc.from))
      reading <- Ref.of[IO, HotKeySource.Reading[IO]](found(p, p.creds.opCert.kesPeriod.toInt))
      h <- harness(
        p,
        sc.ledger(p),
        source = Some(keysFrom(reading)),
        clock = Some(at.get),
        praos = sc.praos
      )
      side = Side(h, reading, p)
      _ <- (sc.from to sc.until).toList.traverse_ { s =>
        at.set(geometry.slotStart(s)) *> sc.changesAt(s).traverse_(_(side)) *>
          h.forger
            .step(s)
            .flatMap(_.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure))
      }
      events <- h.seen
    yield events

  /** The line that ended each slot, by its name, in the order published. */
  def endings(events: Vector[ForgeEvent], from: BigInt, until: BigInt): Vector[(BigInt, String)] =
    events
      .collect {
        case ForgeEvent.Loop(
              t @ (_: ForgeTrace.NodeNotLeader | _: ForgeTrace.SlotIsImmutable |
              _: ForgeTrace.BlockFromFuture | _: ForgeTrace.NoLedgerView |
              _: ForgeTrace.ForgeStateUpdateError | _: ForgeTrace.NodeCannotForge |
              _: ForgeTrace.AdoptedBlock | _: ForgeTrace.DidntAdoptBlock |
              _: ForgeTrace.ForgedInvalidBlock)
            ) =>
          t.slot -> t.productPrefix
        case ForgeEvent.Failed(slot, e)    => slot -> s"forgeFailed ${e.productPrefix}"
        case ForgeEvent.Defect(slot, _, _) => slot -> "forgeDefect"
      }
      .filter((s, _) => from <= s && s <= until)

  /** Each block handed over, by its slot and hash. */
  def blocks(events: Vector[ForgeEvent], from: BigInt, until: BigInt): Vector[(BigInt, Bytes)] =
    events.collect {
      case f: ForgeEvent.Forged if from <= f.slot && f.slot <= until => f.slot -> f.hash
    }

  /** Both paths of `sc` and what each published, as the outcome of every slot and the blocks. */
  final case class Compared(
      scheduled: Vector[ForgeEvent],
      perSlot: Vector[ForgeEvent],
      from: BigInt,
      until: BigInt
  ):
    def scheduledEndings: Vector[(BigInt, String)] = endings(scheduled, from, until)
    def perSlotEndings: Vector[(BigInt, String)] = endings(perSlot, from, until)
    def scheduledBlocks: Vector[(BigInt, Bytes)] = blocks(scheduled, from, until)
    def perSlotBlocks: Vector[(BigInt, Bytes)] = blocks(perSlot, from, until)

  def compared(sc: Scenario)(using Handle[IO, NodeError]): IO[Compared] =
    val (p, twin) = twins(sc.kesStart, sc.seed)
    (scheduledRun(sc, p), perSlotRun(sc, twin)).mapN(Compared(_, _, sc.from, sc.until))

  /** The genesis state of `ledger` with `nonce` as the epoch nonce in force. */
  def stateWith(ledger: NewEpochState[Conway], nonce: Nonce): MirrorState =
    ForgedChain.genesisOf(mirrorEnv, ledger, nonce)
