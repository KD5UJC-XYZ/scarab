// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{FiberIO, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ClockProblem, ForgeError, NodeError}
import xyz.kd5ujc.scarab.node.Fatal

/** The KES key is written back only at a period the node has confirmed (#1101, the KES row of
  * 2026-11-18 in the decisions register): the period written to the key file is never later than
  * that of a slot that passed the forecast check. A reading of the wall clock ahead, at a KES
  * period's first slot or at a reload (SIGHUP), evolves the key in memory and writes nothing ahead,
  * so a restart at the true time reads a key that can sign; normal operation writes the key back
  * once in each period. Each run is on the test clock, the wall clock a test-controlled distance
  * ahead of it.
  */
class KesWriteBackClockSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScheduleFixture:

  /** A key source that keeps a copy of each key it is asked to write back, with its period relative
    * to the certificate: the key file as the write-back leaves it. Each read reads `reading` again,
    * with a key of its own, as a reload reads the files again.
    */
  private def recording(
      written: Ref[IO, Vector[(Int, Sum6KesSigning.SigningKey)]],
      reading: => HotKeySource.Reading[IO]
  ): HotKeySource[IO] =
    new HotKeySource[IO]:
      def read = IO(Right(reading))
      def persist(key: Sum6KesSigning.SigningKey) =
        written.update(_ :+ (key.period -> key.copy)).as(Persisted.Written(WriteProtocol.Exchanged))

  /** The wall clock on the test clock: half a slot into slot `from` when it is made, and `ahead`
    * milliseconds ahead of the test clock while the test says so.
    */
  private def wallClock(from: BigInt, ahead: Ref[IO, Long]): IO[IO[Instant]] =
    IO.realTimeInstant.map(start =>
      (IO.realTimeInstant, ahead.get).mapN((now, by) =>
        geometry.slotStart(from).plusMillis(500 + by).plus(JDuration.between(start, now))
      )
    )

  /** A forger of `p` from slot `from`, as the node has it: the era history's horizon at the tip and
    * the stability window as the forecast window (3k/f, 30 slots; a KES period is 100), its ledger
    * in epoch 0, where `p` has all the stake and leads every slot.
    */
  private def node(
      p: Pool,
      from: BigInt,
      ahead: Ref[IO, Long],
      source: Option[HotKeySource[IO]]
  )(using Handle[IO, NodeError]): IO[Harness] =
    wallClock(from, ahead).flatMap(clock =>
      harness(
        p,
        ledgerIn(0, Vector(p -> BigInt(1000000))),
        forecastWindow = geometry.stabilityWindow.toBigInt,
        live = true,
        source = source,
        clock = Some(clock),
        praos = everySlot
      )
    )

  /** `body` with the forger of `h` running, cancelled when `body` ends, however it ends. */
  private def running[A](h: Harness)(body: FiberIO[Either[Fatal, Unit]] => IO[A]): IO[A] =
    Resource.make(h.forger.run.start)(_.cancel).use(body)

  /** What a node started again at the true time, from slot `from` with the key `onDisk` as the file
    * holds it, forges up to slot `until`: the slots forged and the slots it gave up.
    */
  private def restarted(p: Pool, onDisk: Sum6KesSigning.SigningKey, from: BigInt, until: BigInt)(
      using Handle[IO, NodeError]
  ): IO[(Vector[BigInt], Vector[(BigInt, ForgeError)])] =
    for
      still <- Ref.of[IO, Long](0L)
      h <- node(p.copy(creds = p.creds.copy(kes = onDisk.copy)), from, still, None)
      _ <- running(h)(fiber => IO.race(h.settled(until), fiber.joinWithNever).timeout(2.minutes))
      events <- h.seen
    yield (
      events.collect { case f: ForgeEvent.Forged => f.slot },
      events.collect { case ForgeEvent.Failed(slot, e) => slot -> e }
    )

  test(
    "a reading of the clock ahead past the forecast range, at a KES period's first slot, writes " +
      "nothing back, and a restart at the true time forges"
  ) {
    // the file holds the twin's copy of the key, at the certificate's first period
    val (p, file) = twins(0, 3)
    TestControl.executeEmbed(
      withHandle {
        for
          written <- Ref.of[IO, Vector[(Int, Sum6KesSigning.SigningKey)]](Vector.empty)
          ahead <- Ref.of[IO, Long](0L)
          h <- node(p, 10, ahead, Some(recording(written, found(p, 3))))
          ran <- running(h) { fiber =>
            for
              _ <- h.settled(12).timeout(2.minutes)
              // one reading 450 s ahead, KES period 4, inside the era history's horizon at the
              // tip but past the slots the tip's ledger view can be forecast for
              _ <- ahead.set(450000L)
              _ <- h.clocked(460).timeout(2.minutes)
              jumped <- h.forger.clockSlot.get
              _ <- jumped.traverse_(s => h.settled(s).timeout(2.minutes))
              _ <- ahead.set(0L)
              ended <- fiber.joinWithNever.timeout(2.minutes)
            yield (jumped, ended)
          }
          (jumped, ended) = ran
          events <- h.seen
          disk <- written.get
          // the node started again at the true time from what the file holds
          onDisk = disk.lastOption.fold(file.creds.kes)(_._2)
          again <- restarted(p, onDisk, 20, 25)
        yield
          val slot = jumped.getOrElse(BigInt(-1))
          assertEquals(slot / slotsPerKes, BigInt(4), s"the clock fiber published $jumped")
          assertEquals(disk.map(_._1), Vector.empty, "nothing is written back ahead")
          // the key evolves in memory at the period's first slot, as a timer would
          assertEquals(
            events.collect { case ForgeEvent.KesEvolved(k, r) => (k, r) },
            Vector((BigInt(4), 4))
          )
          assert(
            events.exists {
              case ForgeEvent.Loop(ForgeTrace.NoLedgerView(s, _)) => s == slot
              case _                                              => false
            },
            "the slot read ahead has no ledger view"
          )
          // the reference's blockchain time stops the node when the clock goes back that far
          ended match
            case Left(Fatal.Internal(ClockProblem.SystemClockMovedBack(from, _))) =>
              assertEquals(from, slot)
            case other => fail(s"the forger ended with $other")
          val (forged, failed) = again
          assertEquals(failed, Vector.empty, "the restarted node gives no led slot up")
          assertEquals(forged, (BigInt(20) to BigInt(25)).toVector, "and forges every led slot")
      }
    )
  }

  test(
    "a SIGHUP while the clock reads 60 KES periods ahead writes nothing back, and a restart at " +
      "the true time forges"
  ) {
    val (p, file) = twins(0, 3)
    TestControl.executeEmbed(
      withHandle {
        for
          written <- Ref.of[IO, Vector[(Int, Sum6KesSigning.SigningKey)]](Vector.empty)
          ahead <- Ref.of[IO, Long](0L)
          // the files hold the same key and certificate: a SIGHUP for the topology, say
          h <- node(p, 10, ahead, Some(recording(written, found(p, 3))))
          ran <- running(h) { fiber =>
            for
              _ <- h.settled(12).timeout(2.minutes)
              _ <- ahead.set(6000000L)
              reloaded <- h.forger.reload
              _ <- ahead.set(0L)
              byReload <- written.get
              // the slots after the reload, which pass the forecast check
              _ <- IO.race(h.settled(18), fiber.joinWithNever).timeout(2.minutes)
            yield (reloaded, byReload)
          }
          (reloaded, byReload) = ran
          events <- h.seen
          disk <- written.get
          onDisk = disk.lastOption.fold(file.creds.kes)(_._2)
          again <- restarted(p, onDisk, 20, 25)
        yield
          assertEquals(reloaded, Right(()))
          assertEquals(
            events.collect { case e: ForgeEvent.KesReloaded => e },
            Vector(ForgeEvent.KesReloaded(0, 0, 62, 60))
          )
          assertEquals(byReload.map(_._1), Vector.empty, "the reload writes nothing back")
          assertEquals(disk.map(_._1), Vector.empty, "nor does a slot after it, behind the key")
          // the key in memory, ahead, signs nothing more until the node is started again
          assertEquals(
            events.collect { case f: ForgeEvent.Forged if f.slot > 12 => f.slot },
            Vector.empty
          )
          val (forged, failed) = again
          assertEquals(failed, Vector.empty, "the restarted node gives no led slot up")
          assertEquals(forged, (BigInt(20) to BigInt(25)).toVector, "and forges every led slot")
      }
    )
  }

  test(
    "a SIGHUP at the true time writes nothing back; the next slot that passes the forecast " +
      "check writes the key in use over the file it was read again from"
  ) {
    val (p, _) = twins(50, 3)
    TestControl.executeEmbed(
      withHandle {
        for
          written <- Ref.of[IO, Vector[(Int, Sum6KesSigning.SigningKey)]](Vector.empty)
          // the files hold the key in use as it was installed, at the certificate's first period
          h <- harness(
            p,
            ledgerIn(5, Vector(p -> BigInt(1000000))),
            live = true,
            offsetMillis = 150000,
            unbounded = true,
            source = Some(recording(written, found(p, 3))),
            praos = everySlot
          )
          ran <- running(h) { _ =>
            for
              // slot 5150 is in KES period 51: the key moves there and is written back
              _ <- h.settled(5152).timeout(2.minutes)
              before <- written.get
              reloaded <- h.forger.reload
              byReload <- written.get
              _ <- h.settled(5155).timeout(2.minutes)
            yield (before, reloaded, byReload)
          }
          (before, reloaded, byReload) = ran
          after <- written.get
          events <- h.seen
        yield
          assertEquals(reloaded, Right(()))
          assertEquals(before.map(_._1), Vector(1))
          assertEquals(byReload.map(_._1), Vector(1), "the reload writes nothing back")
          assertEquals(after.map(_._1), Vector(1, 1), "the next slot writes the key in use back")
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
      }
    )
  }

  test("normal operation writes the key back once in each KES period, at its first slot") {
    val (p, _) = twins(50, 3)
    TestControl.executeEmbed(
      withHandle {
        for
          written <- Ref.of[IO, Vector[(Int, Sum6KesSigning.SigningKey)]](Vector.empty)
          h <- harness(
            p,
            ledgerIn(5, Vector(p -> BigInt(1000000))),
            live = true,
            offsetMillis = 98000,
            unbounded = true,
            source = Some(recording(written, found(p, 3))),
            praos = everySlot
          )
          ran <- running(h) { _ =>
            for
              _ <- h.settled(5099).timeout(2.minutes)
              inFirst <- written.get
              _ <- h.settled(5100).timeout(2.minutes)
              atSecond <- written.get
              _ <- h.settled(5202).timeout(5.minutes)
            yield (inFirst, atSecond)
          }
          (inFirst, atSecond) = ran
          disk <- written.get
          forged <- forgedIn(h)
          events <- h.seen
        yield
          assertEquals(inFirst.map(_._1), Vector.empty)
          assertEquals(atSecond.map(_._1), Vector(1), "written back at the period's first slot")
          assertEquals(disk.map(_._1), Vector(1, 2))
          assertEquals(forged.map(_.relativeKesPeriod).distinct, Vector(0, 1, 2))
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
      }
    )
  }

  /** What the files hold: the pool's own credentials, their KES key at `period` relative to the
    * certificate.
    */
  private def filed(p: Pool, seed: Int, period: Int): HotKeySource.Reading[IO] =
    val key = kesOf(seed)
    key.evolveTo(period).fold(e => fail(e.toString), identity)
    HotKeySource.Reading.Found(
      HotKeySource.Candidate(
        HotCredentials(key, p.creds.opCert, p.creds.coldVkey),
        p.creds.vrf,
        IO.unit
      )
    )

  /** A forger of `p` that a SIGHUP while the clock reads 60 KES periods ahead leaves with its key
    * past the present, a SIGHUP at the true time when the files hold `onFile`, and what followed:
    * the two reloads' results, the slot settled at the second, and the events.
    */
  private def reloadedTwice(p: Pool, onFile: => HotKeySource.Reading[IO])(using
      Handle[IO, NodeError]
  ): IO[(Either[Fatal, Unit], Either[Fatal, Unit], BigInt, Vector[ForgeEvent], Vector[Int])] =
    for
      written <- Ref.of[IO, Vector[(Int, Sum6KesSigning.SigningKey)]](Vector.empty)
      ahead <- Ref.of[IO, Long](0L)
      h <- node(p, 10, ahead, Some(recording(written, onFile)))
      ran <- running(h) { fiber =>
        for
          _ <- h.settled(12).timeout(2.minutes)
          _ <- ahead.set(6000000L)
          first <- h.forger.reload
          _ <- ahead.set(0L)
          // led slots the key in memory, past the present, cannot sign
          _ <- h.settled(15).timeout(2.minutes)
          second <- h.forger.reload
          at <- h.forger.settledThrough.get
          // the forger goes on: it is not started again
          _ <- IO.race(h.settled(at + 5), fiber.joinWithNever).timeout(2.minutes)
        yield (first, second, at)
      }
      (first, second, at) = ran
      events <- h.seen
      disk <- written.get
    yield (first, second, at, events, disk.map(_._1))

  test(
    "after a SIGHUP while the clock read ahead, a SIGHUP at the true time takes the key from its " +
      "file, as the reference's reload does, and the next led slot signs with no restart"
  ) {
    val (p, _) = twins(0, 3)
    TestControl.executeEmbed(
      withHandle(reloadedTwice(p, found(p, 3))).map { (first, second, at, events, disk) =>
        assertEquals(first, Right(()))
        assertEquals(second, Right(()))
        assertEquals(
          events.collect { case e: ForgeEvent.KesReloaded => e },
          Vector(ForgeEvent.KesReloaded(0, 0, 62, 60), ForgeEvent.KesReloaded(0, 0, 62, 0))
        )
        val failed = events.collect { case ForgeEvent.Failed(s, e) => s -> e }
        assert(failed.nonEmpty, "the key past the present gives the led slots up")
        assert(
          failed.forall((s, e) =>
            s <= at && e == ForgeError.KesEvolution(Sum6KesSigning.Failure.PeriodOutOfRange(0))
          ),
          failed.toString
        )
        val forged = events.collect { case f: ForgeEvent.Forged if f.slot > at => f }
        assertEquals(forged.map(_.slot), ((at + 1) to (at + 5)).toVector, "every led slot after")
        assert(forged.forall(_.relativeKesPeriod == 0), forged.toString)
        assertEquals(disk, Vector.empty, "nothing is written back ahead")
      }
    )
  }

  test(
    "a SIGHUP at the true time is refused, and led slots are given up as before, when the file's " +
      "key is past the present too"
  ) {
    val (p, _) = twins(0, 3)
    TestControl.executeEmbed(
      withHandle(reloadedTwice(p, filed(p, 3, 60))).map { (first, second, at, events, disk) =>
        assertEquals(first, Right(()))
        assertEquals(second, Right(()))
        assertEquals(
          events.collect { case ForgeEvent.KesReloadRefused(why) => why },
          Vector(ReloadRefusal.KeyPastPeriod(60, 0))
        )
        assertEquals(
          events.collect { case f: ForgeEvent.Forged if f.slot > 12 => f.slot },
          Vector.empty
        )
        assert(
          events.exists {
            case ForgeEvent.Failed(s, ForgeError.KesEvolution(_)) => s > at
            case _                                                => false
          },
          "led slots after the refused reload are given up"
        )
        assertEquals(disk, Vector.empty)
      }
    )
  }
