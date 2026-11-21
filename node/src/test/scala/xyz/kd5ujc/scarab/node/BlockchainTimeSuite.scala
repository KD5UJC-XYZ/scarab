// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import java.nio.file.Path
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.ClockProblem
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The node's blockchain time ([[BlockchainTime]]), against `hardForkBlockchainTime` and
  * `checkValidClockChange` (`BlockchainTime/WallClock/HardFork.hs` 67-185 at ouroboros-consensus
  * 2416493b): the slot a fiber of its own publishes, which the Leios fetch decision reads.
  */
class BlockchainTimeSuite extends FailsOnUncaught:
  private val start = Instant.parse("2026-01-01T00:00:00Z")
  private val geometry = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(
      Path.of("fixtures", "dijkstra-devnet", "genesis", "config.json")
    )
    .fold(e => fail(e.message), identity)
    .geometry
    .copy(systemStart = start)
  private val t0 = start.plusSeconds(500)

  test("a later slot is published, and a known slot after an unknown one, or the other way") {
    assertEquals(
      BlockchainTime.checkValidClockChange((Some(5), t0), (Some(6), t0.plusSeconds(1))),
      Right(Some(BigInt(6)))
    )
    assertEquals(
      BlockchainTime.checkValidClockChange((None, t0), (Some(6), t0)),
      Right(Some(BigInt(6)))
    )
    assertEquals(BlockchainTime.checkValidClockChange((Some(5), t0), (None, t0)), Right(None))
  }

  test("a clock moved back within the rewind keeps the slot; further back, the node stops") {
    assertEquals(
      BlockchainTime.checkValidClockChange((Some(5), t0), (Some(5), t0.minusSeconds(1))),
      Right(Some(BigInt(5)))
    )
    assertEquals(
      BlockchainTime.checkValidClockChange((Some(5), t0), (Some(3), t0.minusSeconds(20))),
      Right(Some(BigInt(5)))
    )
    assertEquals(
      BlockchainTime.checkValidClockChange((Some(5), t0), (Some(3), t0.minusSeconds(21))),
      Left(ClockProblem.SystemClockMovedBack(5, 3))
    )
  }

  test("the fiber waits for the start, then publishes an untranslated slot as unknown") {
    for
      nowRef <- Ref.of[IO, Instant](start.minus(Duration.ofSeconds(3)))
      waits <- Queue.unbounded[IO, FiniteDuration]
      permits <- Queue.unbounded[IO, Unit]
      clock = new SlotClock[IO]:
        def now = nowRef.get
        def geometry = IO.pure(BlockchainTimeSuite.this.geometry)
      moved <- Ref.of[IO, Vector[ClockProblem]](Vector.empty)
      seen <- BlockchainTime
        .resource[IO, Int](
          clock,
          IO.pure(VolatileChain.genesis[Int](2160, 0)),
          (_, _) => false,
          p => moved.update(_ :+ p),
          IO.raiseError,
          sleep = Some(d => waits.offer(d) *> permits.take)
        )
        .use { time =>
          val at = start.plusMillis(5500)
          for
            beforeStart <- waits.take
            unknownBefore <- time.currentSlot
            _ <- nowRef.set(at) *> permits.offer(())
            known <- waits.take
            slot <- time.currentSlot
          yield (beforeStart, unknownBefore, known, slot, at)
        }
      problems <- moved.get
    yield
      val (beforeStart, unknownBefore, known, slot, at) = seen
      assertEquals(beforeStart, 3.seconds)
      assertEquals(unknownBefore, None)
      // the era history at this chain's tip translates no slot: unknown, asked again after the
      // back-off
      assertEquals(slot, None)
      assertEquals(known, xyz.kd5ujc.scarab.node.forge.Forger.UnknownSlotBackoff)
      assertEquals(problems, Vector.empty)
      assert(at.isAfter(start))
  }

  test("a known slot is published, and the wait is until the next slot's start") {
    for
      nowRef <- Ref.of[IO, Instant](start.plusMillis(5500))
      waits <- Queue.unbounded[IO, FiniteDuration]
      permits <- Queue.unbounded[IO, Unit]
      clock = new SlotClock[IO]:
        def now = nowRef.get
        def geometry = IO.pure(BlockchainTimeSuite.this.geometry)
      result <- BlockchainTime
        .resource[IO, Int](
          clock,
          IO.pure(VolatileChain.genesis[Int](2160, 0)),
          (_, _) => true,
          _ => IO.unit,
          IO.raiseError,
          sleep = Some(d => waits.offer(d) *> permits.take)
        )
        .use { time =>
          for
            first <- waits.take
            slot <- time.currentSlot
            // the clock moves back within the rewind: the slot is kept
            _ <- nowRef.set(start.plusMillis(4500)) *> permits.offer(())
            _ <- waits.take
            kept <- time.currentSlot
          yield (first, slot, kept)
        }
    yield
      val (first, slot, kept) = result
      val expected = geometry.slotAt(start.plusMillis(5500))
      assertEquals(slot, Some(expected))
      assertEquals(
        first,
        Duration.between(start.plusMillis(5500), geometry.slotStart(expected + 1)).toNanos.nanos
      )
      assertEquals(kept, Some(expected))
  }
