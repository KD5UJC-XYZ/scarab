// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import cats.effect.testkit.TestControl
import io.circe.Json
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.node.assertions.{Collecting, Made, Property}
import xyz.kd5ujc.scarab.node.mempool.Source

/** The forger's assertions (#1032), each checked with the collecting interpreter from the forger's
  * own typed outcome: a block forged, with or without transactions, one its own rules refuse, a
  * forge defect, and a wake before the slot the slot loop waited for.
  */
class ForgerAssertionsSuite
    extends xyz.kd5ujc.scarab.network.FailsOnUncaught
    with munit.ScalaCheckSuite
    with ForgeFixture:
  private def leaderSlot(p: Pool): BigInt =
    (5000 until 5040)
      .find(slot =>
        Leadership
          .check(slot, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
          .isRight
      )
      .map(BigInt(_))
      .getOrElse(fail("fixture pool leads no slot"))

  private def of(made: Vector[Made], properties: Property*): Vector[Made] =
    made.filter(m => properties.contains(m.property))

  test("a forged block is asserted, and whether it has transactions") {
    val p = pool(kesStart = 50)
    val (t1, _) = xyz.kd5ujc.scarab.node.mempool.TxKit.pay(1)
    val forged = Vector(Property.Sometimes.Forged, Property.Sometimes.ForgedWithTransactions)
    withHandle {
      for
        empty <- Collecting.create
        h1 <- harness(p, ledgerWith(p, 1), assertions = empty._1)
        _ <- h1.forger.step(leaderSlot(p))
        withoutTxs <- empty._2
        full <- Collecting.create
        h2 <- harness(p, ledgerWith(p, 1), assertions = full._1)
        _ <- h2.mempool.add(t1, Source.Local)
        _ <- h2.forger.step(leaderSlot(p))
        withTxs <- full._2
      yield
        assertEquals(
          of(withoutTxs, forged*).map(m => (m.property, m.condition, m.details("txs"))),
          Vector(
            (Property.Sometimes.Forged, true, Some(Json.fromInt(0))),
            (Property.Sometimes.ForgedWithTransactions, false, Some(Json.fromInt(0)))
          )
        )
        assertEquals(
          of(withTxs, forged*).map(m => (m.property, m.condition, m.details("txs"))),
          forged.map(p => (p, true, Some(Json.fromInt(1))))
        )
    }
  }

  test("a forged block its own rules refuse is asserted unreachable") {
    val p = pool(kesStart = 50)
    val cause = xyz.kd5ujc.scarab.ledger.BlockFailure.HeaderDiffers
    withHandle {
      for
        collected <- Collecting.create
        h <- harness(p, ledgerWith(p, 1), ruleFailure = Some(cause), assertions = collected._1)
        _ <- h.forger.step(leaderSlot(p))
        made <- collected._2
      yield assertEquals(
        of(made, Property.Unreachable.ForgedInvalid).map(m => (m.condition, m.details("slot"))),
        Vector((false, Some(Json.fromBigInt(leaderSlot(p)))))
      )
    }
  }

  test("a forge defect is asserted unreachable") {
    val p = pool(kesStart = 50)
    // an empty body is 4 bytes, so a maximum of 3 makes every body too large
    val ledger = xyz.kd5ujc.scarab.ledger.state.ConwayNewEpochState.gov
      .modify(g => g.copy(curPParams = g.curPParams.copy(maxBlockBodySize = BigInt(3))))(
        ledgerWith(p, 1)
      )
    withHandle {
      for
        collected <- Collecting.create
        h <- harness(p, ledger, assertions = collected._1)
        _ <- h.forger.step(leaderSlot(p))
        made <- collected._2
      yield assertEquals(
        of(made, Property.Unreachable.ForgeDefect).map(m =>
          (m.details("kind"), m.details("bodySize"))
        ),
        Vector((Some(Json.fromString("BodySize")), Some(Json.fromLong(4L))))
      )
    }
  }

  test("an early wake is a reading later than the previous one, 1 µs to 1 ms before its slot") {
    // slots of one second from the epoch: the previous reading in slot 5000, its next slot at 5001 s
    val previous = Instant.ofEpochSecond(5000, 250000000L)
    val awaited = Instant.ofEpochSecond(5001)
    def early(by: Duration) = Forger.earlyWake(geometry, previous, awaited.minus(by))
    assertEquals(
      early(Duration.ofNanos(500000)),
      Some(Forger.EarlyWake(previous, awaited.minusNanos(500000), awaited))
    )
    assertEquals(early(Duration.ofNanos(1001)).map(_.now), Some(awaited.minusNanos(1001)))
    assertEquals(early(Duration.ofNanos(999999)).map(_.now), Some(awaited.minusNanos(999999)))
    // the bounds themselves are out: a wait floored to the microsecond, and #629's millisecond
    assertEquals(early(Duration.ofNanos(1000)), None)
    assertEquals(early(Duration.ofNanos(999)), None)
    assertEquals(early(Duration.ofMillis(1)), None)
    assertEquals(early(Duration.ofMillis(500)), None)
    // a reading earlier than the previous one is a clock that moved back, not a wake
    assertEquals(Forger.earlyWake(geometry, previous, previous.minusNanos(500000)), None)
    assertEquals(Forger.earlyWake(geometry, previous, previous), None)
    // a reading in the slot after is no wake before it
    assertEquals(Forger.earlyWake(geometry, previous, awaited.plusNanos(500000)), None)
  }

  property("an early wake is a later reading, more than 1 µs and less than 1 ms before its slot") {
    import org.scalacheck.{Gen, Prop}
    Prop.forAll(
      Gen.choose(5000L, 6000L),
      Gen.choose(0L, 999999999L),
      Gen.choose(-2000000L, 2000000L)
    ) { (second, offset, earlyNanos) =>
      val previous = Instant.ofEpochSecond(second, offset)
      val now = Instant.ofEpochSecond(second + 1).minusNanos(earlyNanos)
      val expected = now.isAfter(previous) && earlyNanos > 1000L && earlyNanos < 1000000L
      assertEquals(Forger.earlyWake(geometry, previous, now).isDefined, expected)
    }
  }

  /** The forger's slot loop on the test clock from slot 5000, its wall clock moved back by `back`
    * from the reading after the first wait on: what it asserted within 1.5 s.
    */
  private def movedBack(back: Duration): IO[Vector[Made]] =
    val p = pool(kesStart = 50, seed = 9)
    val clock = IO.realTimeInstant.map { t =>
      val wall = t.plusSeconds(5000)
      if wall.isBefore(Instant.ofEpochSecond(5000, 500000000L)) then wall else wall.minus(back)
    }
    for
      collected <- Collecting.create
      control <- TestControl.execute(withHandle {
        harness(
          p,
          ledgerWith(p, 1),
          live = true,
          unbounded = true,
          clock = Some(clock),
          assertions = collected._1
        ).flatTap(_.forger.run.start)
      })
      _ <- control.tickFor(1500.millis)
      made <- collected._2
    yield of(made, Property.Unreachable.ClockWokeEarly)

  test("the slot loop asserts a wake before its slot, and not a clock that moved back") {
    for
      woke <- movedBack(Duration.ofNanos(500000))
      skipped <- movedBack(Duration.ofMillis(500))
      back <- movedBack(Duration.ofMillis(1500))
    yield
      assertEquals(
        woke.map(m => (m.details("prevTime"), m.details("newTime"), m.details("earlyNanos"))),
        Vector(
          (
            Some(Json.fromString("1970-01-01T01:23:20Z")),
            Some(Json.fromString("1970-01-01T01:23:20.999500Z")),
            Some(Json.fromLong(500000L))
          )
        )
      )
      // half a second before its slot is no wake of a floored wait: a clock skip
      assertEquals(skipped, Vector.empty[Made])
      // a reading before the previous one is a clock that moved back
      assertEquals(back, Vector.empty[Made])
  }
