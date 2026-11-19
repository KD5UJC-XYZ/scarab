// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.node.Fatal

/** What confirms a KES period for the write-back (#1101, the KES row of 2026-11-18 in the decisions
  * register): a slot that passed the forecast check ([[Forecastable]]), whose period is not earlier
  * than the key's; and the warning at start-up when the key in its file is ahead of the present.
  * The forger's runs with the clock read ahead are `KesWriteBackClockSuite` 's.
  */
class KesConfirmedWriteBackSuite
    extends xyz.kd5ujc.scarab.network.FailsOnUncaught
    with ScheduleFixture:

  /** A key source that records the period, relative to the certificate, of each key it is asked to
    * write back.
    */
  private def writing(written: Ref[IO, Vector[Int]]): HotKeySource[IO] =
    new HotKeySource[IO]:
      def read = IO.pure(Left(xyz.kd5ujc.scarab.ledger.ForgeError.KesUpdateInterrupted))
      def persist(key: Sum6KesSigning.SigningKey) =
        written.update(_ :+ key.period).as(Persisted.Written(WriteProtocol.Exchanged))

  /** `slot`, which passes the forecast check from the origin with a window that covers it. */
  private def forecastable(slot: BigInt): Forecastable =
    Forecastable.check(None, BigInt(1000000), slot).fold(r => fail(r.toString), identity)

  /** `body` with the forger's terminal failures raised, so that a test sees one. */
  private def ok[A](body: Raise[IO, Fatal] ?=> IO[A]): IO[A] =
    Forger.scoped(body).flatMap(_.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure))

  test("the forecast check answers for the slots ledgerViewForecastAt answers for, and no other") {
    def answers(at: Option[BigInt], window: BigInt, slot: BigInt) =
      Forecastable.check(at, window, slot).map(_.slot)
    // from the origin, every slot before the window
    assertEquals(answers(None, 30, 29), Right(BigInt(29)))
    assertEquals(answers(None, 30, 30), Left(OutsideForecastRange(None, 30, 30)))
    // from a tip, its own slot and every slot before the window after its successor
    assertEquals(answers(Some(12), 30, 12), Right(BigInt(12)))
    assertEquals(answers(Some(12), 30, 42), Right(BigInt(42)))
    assertEquals(answers(Some(12), 30, 43), Left(OutsideForecastRange(Some(12), 43, 43)))
    assertEquals(answers(Some(50), 0, 50), Right(BigInt(50)))
    assertEquals(answers(Some(50), 0, 51), Left(OutsideForecastRange(Some(50), 51, 51)))
  }

  test(
    "a key evolved in memory is written back only once a slot of its period, or a later one, " +
      "has passed the forecast check, and once"
  ) {
    val p = pool(kesStart = 50, seed = 31)
    for
      written <- Ref.of[IO, Vector[Int]](Vector.empty)
      keys <- HotKeys.create[IO](p.creds, Some(writing(written)), config, _ => IO.unit)
      // the update of a period's first slot, on a reading of the clock three periods ahead
      _ <- ok(toPeriod(keys, 53))
      evolved <- written.get
      _ <- ok(keys.confirm(forecastable(5013)))
      _ <- ok(keys.confirm(forecastable(5299)))
      behind <- written.get
      _ <- ok(keys.confirm(forecastable(5300)))
      _ <- ok(keys.confirm(forecastable(5301)))
      disk <- written.get
    yield
      assertEquals(p.creds.kes.period, 3)
      assertEquals(evolved, Vector.empty, "the evolution itself writes nothing")
      assertEquals(behind, Vector.empty, "a slot of an earlier period writes nothing ahead of it")
      assertEquals(disk, Vector(3), "a slot of the key's period writes it back, once")
  }

  test("a key poisoned past its certificate's window is never written back") {
    val p = pool(kesStart = 0, seed = 32)
    for
      written <- Ref.of[IO, Vector[Int]](Vector.empty)
      keys <- HotKeys.create[IO](p.creds, Some(writing(written)), config, _ => IO.unit)
      // evolved in memory, never confirmed, then past the window [0, 62): poisoned and erased
      _ <- ok(toPeriod(keys, 5))
      poisoned <- ok(toPeriod(keys, 62))
      _ <- ok(keys.confirm(forecastable(6200)))
      disk <- written.get
    yield
      assertEquals(
        poisoned,
        Some(Right(Right(Left(KesEvolutionError.CouldNotEvolve(KesInfo(0, 62, 5), 62)))))
      )
      assert(p.creds.kes.isForgotten, "the key is erased from memory")
      assertEquals(disk, Vector.empty, "the erased key is not written over the file")
  }

  /** A key source whose files hold `p` 's own credentials, its KES key (of `seed`, at `period`
    * relative to the certificate) read again at each reload; nothing is written.
    */
  private def files(p: Pool, seed: Int, period: Int = 0): HotKeySource[IO] =
    new HotKeySource[IO]:
      def read = IO {
        val key = kesOf(seed)
        key.evolveTo(period).fold(e => fail(e.toString), identity)
        Right(
          HotKeySource.Reading.Found(
            HotKeySource.Candidate(
              HotCredentials(key, p.creds.opCert, p.creds.coldVkey),
              p.creds.vrf,
              IO.unit
            )
          )
        )
      }
      def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Unchanged)

  test(
    "a reload is refused when the file holds the key in use evolved past the period, even with " +
      "the key in use behind it"
  ) {
    // the certificate from period 50, the reload at 51: the key in use at relative period 1, the
    // file's copy of it at 3. The reference's reload would sign with the file's key, past the
    // period; it is refused, and the key in use stays in force
    val p = pool(kesStart = 50, seed = 37)
    for
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      keys <- HotKeys.create[IO](p.creds, Some(files(p, 37, 3)), config, e => seen.update(_ :+ e))
      _ <- ok(toPeriod(keys, 51))
      _ <- ok(keys.reload(51, Some(BigInt(0))))
      refused <- seen.get.map(_.collect { case ForgeEvent.KesReloadRefused(why) => why })
      reloaded <- seen.get.map(_.collect { case r: ForgeEvent.KesReloaded => r })
      current <- currentHot(keys)
    yield
      assertEquals(refused, Vector(ReloadRefusal.KeyPastPeriod(3, 1)))
      assertEquals(reloaded, Vector.empty)
      assertEquals(current.kes.period, 1, "the key in use stays in force")
      assert(!current.kes.isForgotten)
  }

  test(
    "a key poisoned past its window is not brought back: a reload past the window is refused and " +
      "the key stays poisoned; an erased key is never the one a reload keeps"
  ) {
    val p = pool(kesStart = 0, seed = 36)
    val erased = KesInfo(0, 62, 0)
    for
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      keys <- HotKeys.create[IO](p.creds, Some(files(p, 36)), config, e => seen.update(_ :+ e))
      poisoned <- ok(toPeriod(keys, 62))
      _ <- ok(keys.reload(62, Some(BigInt(0))))
      still <- ok(toPeriod(keys, 62))
      refused <- seen.get.map(_.collect { case ForgeEvent.KesReloadRefused(why) => why })
      // a reload with the clock back inside the window installs the file's key, never the erased
      // one, as the reference's reload makes a hot key afresh from the file
      _ <- ok(keys.reload(61, Some(BigInt(0))))
      current <- currentHot(keys)
    yield
      assertEquals(
        poisoned,
        Some(Right(Right(Left(KesEvolutionError.CouldNotEvolve(erased, 62)))))
      )
      assertEquals(refused, Vector(ReloadRefusal.Expired(62, 0, 62)))
      assertEquals(
        still,
        Some(Right(Right(Left(KesEvolutionError.KeyAlreadyPoisoned(erased, 62))))),
        "the refused reload leaves the key poisoned"
      )
      assert(p.creds.kes.isForgotten, "the erased key stays erased")
      assert(!current.kes.isForgotten, "the key installed is the file's, not the erased one")
      assertEquals(current.kes.period, 61)
  }

  test("the key read at start-up is ahead exactly when it has evolved past the current period") {
    def ahead(start: Int, relative: Int, now: BigInt): Option[ForgeEvent] =
      val p = pool(kesStart = start, seed = 33)
      p.creds.kes.evolveTo(relative).fold(e => fail(e.toString), identity)
      Forger.keyAhead(p.creds, now)
    assertEquals(ahead(50, 3, 50), Some(ForgeEvent.KesKeyAhead(53, 50)))
    assertEquals(ahead(50, 3, 52), Some(ForgeEvent.KesKeyAhead(53, 52)))
    assertEquals(ahead(50, 3, 53), None, "at the present")
    assertEquals(ahead(50, 3, 60), None, "behind it")
    assertEquals(ahead(50, 0, 50), None, "not evolved")
    // a certificate whose window starts later: its key not evolved is not ahead, one evolved is
    assertEquals(ahead(60, 0, 50), None)
    assertEquals(ahead(60, 1, 50), Some(ForgeEvent.KesKeyAhead(61, 50)))
  }

  /** The events of a node started at slot `from` with its key at `relative`, up to the clock
    * fiber's first slot; forging switched on or not.
    */
  private def startedWith(relative: Int, from: BigInt, enabled: Boolean = true) =
    val p = pool(kesStart = 50, seed = 34)
    TestControl.executeEmbed(
      withHandle {
        for
          _ <- IO(p.creds.kes.evolveTo(relative).fold(e => fail(e.toString), identity))
          h <- harness(
            p,
            ledgerIn(5, Vector(p -> BigInt(1000000))),
            live = true,
            offsetMillis = ((from - 5000) * 1000).toLong,
            unbounded = true,
            enabled = enabled
          )
          fiber <- h.forger.run.start
          _ <- h.clocked(from).timeout(1.minute).guarantee(fiber.cancel)
          events <- h.seen
        yield events.filter(_.isInstanceOf[ForgeEvent.KesKeyAhead])
      }
    )

  test("a node started with its key ahead of the present says so once, at start-up") {
    for
      ahead <- startedWith(3, 5020)
      disabled <- startedWith(3, 5020, enabled = false)
      present <- startedWith(3, 5320)
      fresh <- startedWith(0, 5020)
    yield
      assertEquals(ahead, Vector(ForgeEvent.KesKeyAhead(53, 50)))
      assertEquals(disabled, Vector(ForgeEvent.KesKeyAhead(53, 50)), "forging switched off too")
      assertEquals(present, Vector.empty, "a key at the present")
      assertEquals(fresh, Vector.empty, "a key not evolved")
  }
