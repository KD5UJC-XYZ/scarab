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
import xyz.kd5ujc.scarab.node.mempool.{MempoolLedger, TxKit}
import xyz.kd5ujc.scarab.protocol.ChainSync
import scala.concurrent.duration.*

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

  /** The way a side of a differential run forges: the scheduled forger, or the per-slot decision.
    */
  enum Way:
    case Scheduled
    case PerSlot

  /** One side of a differential run: its harness, the reading its key source gives at a reload, its
    * own copy of the pool, and the way it forges.
    */
  final case class Side(
      h: Harness,
      reading: Ref[IO, HotKeySource.Reading[IO]],
      pool: Pool,
      way: Way,
      rewind: FiniteDuration => IO[Unit]
  )

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

  /** A SIGHUP that installs another KES key, under a certificate issued for it (`counter`). */
  def otherKes(kesSeed: Int, start: Int, counter: Int = 1): Change =
    reloaded(side =>
      HotKeySource.Reading.Found(certified(side.pool, kesOf(kesSeed), start, counter))
    )

  /** A SIGHUP that installs the same KES key under a certificate issued again (counter 1). */
  def otherCertificate(kesSeed: Int, start: Int): Change =
    reloaded(side => HotKeySource.Reading.Found(certified(side.pool, kesOf(kesSeed), start, 1)))

  /** The pool's credentials with another KES key, under a certificate issued for it (counter 1). */
  def certifiedFor(p: Pool, kesSeed: Int, counter: Int = 1): HotKeySource.Candidate[IO] =
    certified(p, kesOf(kesSeed), p.creds.opCert.kesPeriod.toInt, counter)

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
    * slot's wake (`before`), the ones made between a slot's wake and its onset (`margin`, at
    * [[inMargin]], a slot's later ones each [[MarginStep]] after the one before), and the ones made
    * when the scheduled forger reaches a point of its own (`at`), which the per-slot decision takes
    * before the slot. `behind`: at the margin of its slot, before that margin's changes, the wall
    * clock moves back by its duration, within the rewind allowance, and stays behind. `within`: the
    * changes made on both paths when an attempt reaches a point (the per-slot decision's attempt
    * passes the same points). `known`: the slots the scheduled run's clock can know (its horizon).
    * `timed`: the changes made on the scheduled run when its test clock has run that long from the
    * run's start, and on the per-slot run when its attempt reaches the point; the scheduled run
    * ends only once they are made.
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
      scheduler: Boolean = true,
      committed: Boolean = false,
      setup: Change = _ => IO.unit,
      selection: Option[(BigInt, ChainSync.Point, MempoolLedger) => MempoolLedger] = None,
      margin: Vector[(BigInt, Change)] = Vector.empty,
      behind: Option[(BigInt, FiniteDuration)] = None,
      within: Vector[(Forger.Point, Change)] = Vector.empty,
      known: BigInt => Boolean = _ => true,
      timed: Vector[(FiniteDuration, Forger.Point, Change)] = Vector.empty
  ):
    /** The longest the scheduled run may take on the test clock, past which it fails. */
    def bound: FiniteDuration = ((until - from).toLong + 120).seconds

    /** Each change of a slot's margin, with its place among that slot's. */
    def margins: Vector[(BigInt, Int, Change)] =
      margin.zipWithIndex.map { case ((slot, change), i) =>
        (slot, margin.take(i).count(_._1 == slot), change)
      }

    /** How far behind the wall clock reads in the margin of `slot`. */
    def back(slot: BigInt): FiniteDuration =
      behind.collect { case (s, d) if s <= slot => d }.getOrElse(Duration.Zero)

  /** How far apart the changes of one slot's margin are made. */
  val MarginStep: FiniteDuration = 5.millis

  /** Half the wake margin before the onset of `slot`, and `nth` steps of [[MarginStep]] after:
    * after the forging fiber's wake for the slot, before the slot begins.
    */
  def inMargin(slot: BigInt, nth: Int = 0): Instant =
    geometry
      .slotStart(slot)
      .minusNanos(Forger.WakeMargin.toNanos / 2)
      .plusNanos((MarginStep * nth.toLong).toNanos)

  /** What a path published, and the transactions its mempool held at the end. */
  final case class Outcome(events: Vector[ForgeEvent], mempool: Vector[Bytes])

  /** The scheduled forger's run of `sc` on the test clock, and what it published, bounded by
    * `sc.bound` on that clock: a settlement that never comes fails the run, and the forger is
    * cancelled whatever happened. The test clock starts at the onset of `sc.from`, and is driven to
    * each change of a slot's margin and each timed change, which is made when the clock is there,
    * and to the moment the wall clock moves back (`sc.behind`), after which the wall clock reads
    * that far behind it.
    */
  def scheduledRun(sc: Scenario, p: Pool): IO[Outcome] =
    val start = geometry.slotStart(sc.from)
    def later(slot: BigInt) = sc.behind.collect { case (s, d) if s < slot => d }
    for
      back <- Ref.of[IO, Long](0L)
      due <- sc.margins.traverse((slot, nth, change) =>
        Deferred[IO, Unit].map(d => (slot, nth, change, d))
      )
      timed <- sc.timed.traverse((after, _, change) =>
        Deferred[IO, Unit].map(d => (after, change, d))
      )
      control <- TestControl.execute(
        scheduledOn(
          sc,
          p,
          back,
          due.map((_, _, change, d) => change -> d) ++ timed.map((_, change, d) => change -> d)
        )
      )
      // on the test clock, which the wall clock's moving back does not move: the rewind first at
      // its instant, then each change of a margin
      events = sc.behind.toVector.map((slot, d) =>
        (Forger.between(start, inMargin(slot)), 0, back.set(d.toMillis))
      ) ++ due.map((slot, nth, _, d) =>
        (
          Forger.between(start, inMargin(slot, nth)) + later(slot).getOrElse(Duration.Zero),
          1,
          d.complete(()).void
        )
      ) ++ timed.map((after, _, d) => (after, 1, d.complete(()).void))
      _ <- events.sortBy((at, order, _) => (at, order)).foldLeftM(Duration.Zero) {
        (reached, event) =>
          val (at, _, act) = event
          control.tickFor((at - reached).max(Duration.Zero)) *> act *> control.tick.as(at)
      }
      _ <- control.tickAll
      result <- control.results
      outcome <- result match
        case Some(cats.effect.kernel.Outcome.Succeeded(o)) => IO.pure(o)
        case Some(cats.effect.kernel.Outcome.Errored(e))   => IO.raiseError(e)
        case Some(cats.effect.kernel.Outcome.Canceled()) =>
          IO.raiseError(new AssertionError("the scheduled run was cancelled"))
        case None => IO.raiseError(new AssertionError("the scheduled run did not end"))
    yield outcome

  /** The program of [[scheduledRun]]: each change of a slot's margin, and each timed change, is
    * made once its `due` is completed.
    */
  private def scheduledOn(
      sc: Scenario,
      p: Pool,
      back: Ref[IO, Long],
      margins: Vector[(Change, Deferred[IO, Unit])]
  ): IO[Outcome] =
    val offset = ((sc.from - 5000) * 1000).toLong
    withHandle {
      for
        hooks <- Ref.of[IO, Map[Forger.Point, Change]](
          (sc.at.map((point, _, change) => point -> change) ++ sc.within).toMap
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
          offsetMillis = offset,
          unbounded = true,
          known = Some(sc.known),
          source = Some(keysFrom(reading)),
          clock = Some(
            (back.get, IO.realTimeInstant).mapN((b, t) =>
              t.plusSeconds(5000).plusMillis(offset - b)
            )
          ),
          praos = sc.praos,
          committedChain = sc.committed,
          selectionLedger = sc.selection,
          pause = Some(point =>
            stalled(point) *> hooks
              .modify(m => (m - point, m.get(point)))
              .flatMap(_.traverse_(change => side.get.flatMap(change)))
          )
        )
        here = Side(h, reading, p, Way.Scheduled, d => back.update(_ + d.toMillis))
        _ <- side.complete(here)
        _ <- sc.setup(here)
        timed <- margins.traverse((change, due) => (due.get *> change(here)).start)
        fiber <- h.forger.run.start
        ended <- (sc.before.traverse_((slot, change) => h.settled(slot - 1) *> change(here)) *>
          IO.race(h.settled(sc.until) *> timed.traverse_(_.join), fiber.join))
          .timeout(sc.bound)
          .guarantee(fiber.cancel *> timed.traverse_(_.cancel))
        _ <- ended.fold(
          _ => IO.unit,
          o => IO.raiseError(new AssertionError(s"the forger ended: $o"))
        )
        // every change made at a point of the forger's was made: the point was reached
        left <- hooks.get
        _ <- IO
          .raiseError(new AssertionError(s"points never reached: ${left.keys}"))
          .whenA(left.nonEmpty)
        // and every change of a slot's margin and every timed change
        made <- timed.traverse(_.join)
        _ <- made.traverse_ {
          case cats.effect.kernel.Outcome.Succeeded(_) => IO.unit
          case cats.effect.kernel.Outcome.Errored(e)   => IO.raiseError(e)
          case cats.effect.kernel.Outcome.Canceled() =>
            IO.raiseError(
              new AssertionError("a change of a slot's margin or a timed change was never made")
            )
        }
        events <- h.seen
        held <- h.mempool.snapshot
      yield Outcome(events, held.entries.map(_.id))
    }

  /** The per-slot decision of every slot of `sc`, at the slot's onset, the changes made before it
    * in the order of the scheduled run: before the slot's wake, in its margin (the clock then at
    * [[inMargin]], behind by what the wall clock has moved back), at the forger's points.
    */
  def perSlotRun(sc: Scenario, p: Pool)(using Handle[IO, NodeError]): IO[Outcome] =
    for
      at <- Ref.of[IO, Instant](geometry.slotStart(sc.from))
      reading <- Ref.of[IO, HotKeySource.Reading[IO]](found(p, p.creds.opCert.kesPeriod.toInt))
      hooks <- Ref.of[IO, Map[Forger.Point, Change]](
        (sc.within ++ sc.timed.map((_, point, change) => point -> change)).toMap
      )
      here <- Deferred[IO, Side]
      h <- harness(
        p,
        sc.ledger(p),
        source = Some(keysFrom(reading)),
        clock = Some(at.get),
        praos = sc.praos,
        selectionLedger = sc.selection,
        pause = Some(point =>
          hooks
            .modify(m => (m - point, m.get(point)))
            .flatMap(_.traverse_(change => here.get.flatMap(change)))
        )
      )
      side = Side(h, reading, p, Way.PerSlot, d => at.update(_.minusNanos(d.toNanos)))
      _ <- here.complete(side)
      _ <- sc.setup(side)
      _ <- (sc.from to sc.until).toList.traverse_ { s =>
        at.set(geometry.slotStart(s)) *> sc.before.filter(_._1 == s).traverse_(_._2(side)) *>
          sc.margins
            .filter(_._1 == s)
            .traverse_((_, nth, change) =>
              at.set(inMargin(s, nth).minusNanos(sc.back(s).toNanos)) *> change(side)
            ) *>
          at.set(geometry.slotStart(s)) *> sc.at.filter(_._2 == s).traverse_(_._3(side)) *>
          h.forger
            .step(s)
            .flatMap(_.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure))
      }
      events <- h.seen
      held <- h.mempool.snapshot
    yield Outcome(events, held.entries.map(_.id))

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
      until: BigInt,
      scheduledMempool: Vector[Bytes] = Vector.empty,
      perSlotMempool: Vector[Bytes] = Vector.empty
  ):
    def scheduledEndings: Vector[(BigInt, String)] = endings(scheduled, from, until)
    def perSlotEndings: Vector[(BigInt, String)] = endings(perSlot, from, until)
    def scheduledBlocks: Vector[(BigInt, Bytes)] = blocks(scheduled, from, until)
    def perSlotBlocks: Vector[(BigInt, Bytes)] = blocks(perSlot, from, until)

  def compared(sc: Scenario)(using Handle[IO, NodeError]): IO[Compared] =
    val (p, twin) = twins(sc.kesStart, sc.seed)
    (scheduledRun(sc, p), perSlotRun(sc, twin)).mapN((a, b) =>
      Compared(a.events, b.events, sc.from, sc.until, a.mempool, b.mempool)
    )

  /** The genesis state of `ledger` with `nonce` as the epoch nonce in force. */
  def stateWith(ledger: NewEpochState[Conway], nonce: Nonce): MirrorState =
    ForgedChain.genesisOf(mirrorEnv, ledger, nonce)
