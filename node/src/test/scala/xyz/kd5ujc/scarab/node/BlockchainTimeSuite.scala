// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import cats.effect.std.Queue
import java.nio.file.Path
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.ClockProblem
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

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

  // `hardForkBlockchainTime` (`HardFork.hs` 76-90 at 2416493b): `systemTimeWait`, then the first
  // reading, published before the time is returned, and the loop forked with its delay
  test("acquisition waits for the system start and publishes the first reading before it returns") {
    val starting = geometry.copy(systemStart = Instant.EPOCH.plusSeconds(3))
    val slotLength = Duration.between(starting.slotStart(0), starting.slotStart(1)).toNanos.nanos
    val slotOf =
      (handle: Ref[IO, Option[BlockchainTime[IO]]]) => handle.get.flatMap(_.traverse(_.currentSlot))
    for
      handle <- Ref.of[IO, Option[BlockchainTime[IO]]](None)
      program = BlockchainTime
        .resource[IO, Int](
          SlotClock.system[IO](IO.pure(starting)),
          IO.pure(VolatileChain.genesis[Int](2160, 0)),
          (_, _) => true,
          _ => IO.unit,
          IO.raiseError
        )
        .use(time => handle.set(Some(time)) *> IO.never[Unit])
      control <- TestControl.execute(program)
      _ <- control.tick
      before <- slotOf(handle)
      wait <- control.nextInterval
      _ <- control.advanceAndTick(wait)
      acquired <- slotOf(handle)
      firstDelay <- control.nextInterval
      _ <- control.advanceAndTick(firstDelay)
      next <- slotOf(handle)
    yield
      // nothing is returned before the system start, which is the only wait
      assertEquals(before, None)
      assertEquals(wait, 3.seconds)
      // the first reading, the slot of the system start, is published as the time is returned
      assertEquals(acquired, Some(Some(BigInt(0))))
      // the loop's first wait is the first reading's delay, until the next slot
      assertEquals(firstDelay, slotLength)
      assertEquals(next, Some(Some(BigInt(1))))
  }

  test("a slot the era history at the tip does not translate is published as unknown") {
    for
      waits <- Queue.unbounded[IO, FiniteDuration]
      permits <- Queue.unbounded[IO, Unit]
      clock = new SlotClock[IO]:
        def now = IO.pure(start.plusMillis(5500))
        def geometry = IO.pure(BlockchainTimeSuite.this.geometry)
      seen <- BlockchainTime
        .resource[IO, Int](
          clock,
          IO.pure(VolatileChain.genesis[Int](2160, 0)),
          (_, _) => false,
          _ => IO.unit,
          IO.raiseError,
          sleep = Some(d => waits.offer(d) *> permits.take)
        )
        .use(time => (time.currentSlot, waits.take).tupled)
    yield
      val (slot, delay) = seen
      // unknown, asked again after the back-off
      assertEquals(slot, None)
      assertEquals(delay, xyz.kd5ujc.scarab.node.forge.Forger.UnknownSlotBackoff)
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

  private lazy val testnet = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(
      Path.of("fixtures", "leios", "testnet", "config", "config.json")
    )
    .fold(e => fail(e.message), identity)

  /** The public testnet's selected chain at its captured ledger state of slot 2,168,639
    * (`fixtures/leios/state/testnet-2168639`), in epoch 100 of Dijkstra, which the configuration
    * starts at epoch 0 with every other Shelley-based era.
    */
  private lazy val caughtUp: VolatileChain[MirrorState] =
    val raw = Bytes.wrap(
      java.nio.file.Files.readAllBytes(Path.of("fixtures/leios/state/testnet-2168639/state"))
    )
    val file = HaskellLedgerFile.decode(raw, StateLayout.Leios).fold(e => fail(e.render), identity)
    val state = testnet.mirrorStart.copy(ext = file.ext.getOrElse(fail("no Shelley-based ledger")))
    val tip = BlockRef(
      BigInt(2168639),
      Bytes
        .fromHex("aed73a1050263343a16d9a980b2f6dce1152c2e959b69987230a9e5f4844f619")
        .fold(e => fail(e.message), identity),
      BigInt(97273)
    )
    VolatileChain[MirrorState](testnet.geometry.k.toInt, Anchor(Some(tip), state), Vector.empty)

  // the node's wiring (`BlockchainTime.ofChain` with the setup's `txClock`) translates the wall
  // clock with the summary of the tip's ledger state, in its own era (`hardForkSummary`,
  // `HardFork.hs` 101-102 at 2416493b): a Dijkstra tip whose configuration starts every era at
  // epoch 0 is not read as a Conway state ending at the first epoch's safe zone
  test("the slot after a caught-up Dijkstra tip of the public testnet is published") {
    def published(slot: BigInt): IO[Option[BigInt]] =
      BlockchainTime
        .ofChain[IO, MirrorState](
          new SlotClock[IO]:
            def now = IO.pure(testnet.geometry.slotStart(slot).plusMillis(500))
            def geometry = IO.pure(testnet.geometry)
          ,
          IO.pure(caughtUp),
          testnet.txClock,
          problem => IO(fail(s"the clock moved back: $problem")),
          IO.raiseError
        )
        .use(_.currentSlot)
    (published(BigInt(2168640)), published(BigInt(2268639))).tupled.map { (next, far) =>
      assertEquals(next, Some(BigInt(2168640)))
      // far past the tip's safe zone, the slot is unknown
      assertEquals(far, None)
    }
  }
