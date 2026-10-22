// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Fixtures, Header, PeerError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ChainSync

class HeaderClockCanonicalSuite extends FailsOnUncaught:
  private val geometry = EpochGeometry(Instant.EPOCH, 500, 432000, 2160, BigDecimal("0.05"))

  private def typed[A](body: Handle[IO, PeerError] ?=> IO[A]): IO[Either[PeerError, A]] =
    Handle
      .allow[PeerError](body.map(_.asRight[PeerError]))
      .rescue(error => IO.pure(error.asLeft[A]))

  private def exercise(
      slots: Vector[Int],
      forecastDelay: FiniteDuration = Duration.Zero,
      prepareDelay: FiniteDuration = Duration.Zero
  ): IO[(Either[PeerError, Unit], FiniteDuration, Vector[BigInt])] =
    TestControl.executeEmbed {
      for
        prepared <- Ref.of[IO, Vector[BigInt]](Vector.empty)
        check = new HeaderCheck[IO]:
          def start(intersection: ChainSync.Point): IO[HeaderGate[IO]] = IO.pure(new HeaderGate[IO]:
            override val canonicalTiming = true
            def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
            def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) = IO.unit
            override def forecastTime(header: Header)(using cats.Applicative[IO]) =
              IO.sleep(forecastDelay)
                .as(
                  Some(
                    HeaderGate.TimeForecast.Known(
                      header.slot * BigInt(500000000000L)
                    )
                  )
                )
            override def prepareHeader(header: Header)(using
                cats.Applicative[IO],
                Raise[IO, PeerError]
            ) =
              IO.sleep(prepareDelay) *> prepared
                .update(_ :+ header.slot)
                .as(HeaderGate.Acceptance.Accepted))
        gate <- HeaderClock
          .clocked(SlotClock.system[IO](IO.pure(geometry)), check)
          .start(ChainSync.Point.Origin)
        batch = slots.zipWithIndex.map { (slot, index) =>
          SyncEvent.RollForward(
            Fixtures.header(index, slot, None, salt = index),
            ChainSync.Tip.Origin
          )
        }
        result <- typed(gate.accept(batch))
        elapsed <- IO.monotonic
        seen <- prepared.get
      yield (result, elapsed, seen)
    }

  test("a prior header's delay cannot make the next header falsely far-future") {
    exercise(Vector(3, 7)).map { (result, elapsed, seen) =>
      assertEquals(result, Right(()))
      assertEquals(elapsed, 3500.millis)
      assertEquals(seen, Vector(BigInt(3), BigInt(7)))
    }
  }

  test("prepare time for an earlier header is excluded from the next arrival sample") {
    exercise(Vector(0, 6), prepareDelay = 1.second).map { (result, elapsed, seen) =>
      assertEquals(result, Right(()))
      assertEquals(elapsed, 4.seconds)
      assertEquals(seen, Vector(BigInt(0), BigInt(6)))
    }
  }

  test("forecast waiting preserves this header's own recorded arrival") {
    exercise(Vector(7), forecastDelay = 4.seconds).map { (result, elapsed, seen) =>
      assert(result.swap.exists {
        case PeerError.InvalidHeader(_, _: xyz.kd5ujc.scarab.ledger.HeaderError.FarFuture) => true
        case _                                                                             => false
      })
      assertEquals(elapsed, 4.seconds)
      assertEquals(seen, Vector.empty)
    }
  }
