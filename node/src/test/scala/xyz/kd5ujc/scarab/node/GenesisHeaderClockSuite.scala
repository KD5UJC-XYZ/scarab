// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience}
import java.nio.file.{Files, Path}
import java.time.Instant
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, ChainSelection, Compute, SelectView, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{
  Block,
  EpochGeometry,
  GenesisInternalProblem,
  HeaderError,
  PeerError
}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

class GenesisHeaderClockSuite extends FailsOnUncaught:
  private given Tell[IO, GenesisInternalProblem] with
    val functor = summon[cats.Functor[IO]]
    def tell(problem: GenesisInternalProblem): IO[Unit] =
      IO.raiseError(new AssertionError(problem.message))
  private final case class Capture(
      setup: NodeSetup,
      blocks: Vector[Block],
      states: Vector[MirrorState]
  )
  private def read(directory: String): Capture =
    val root = Path.of(directory)
    val setup =
      NodeSetup.fromDirectory(root.resolve("genesis")).fold(e => fail(e.message), identity)
    val raws = Files
      .readString(root.resolve("blocks.hex"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(hex => Bytes.fromHex(hex).fold(e => fail(e.message), identity))
      .toVector
    val blocks = raws.map(raw =>
      Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
    )
    val states =
      blocks.zip(raws).foldLeft(Vector(setup.mirrorStart)) { case (previous, (block, raw)) =>
        previous :+ MirrorSteps
          .block(setup, previous.last, block.header, raw)
          .fold(e => fail(e.toString), identity)
      }
    Capture(setup, blocks, states)
  private lazy val preprod = read("fixtures/preprod")
  private lazy val eras = read("fixtures/eras")
  private def check(
      c: Capture,
      index: Int,
      arrival: Instant
  ): IO[Either[Fault, HeaderGate.Acceptance]] =
    val headers = c.blocks.map(_.header)
    val applied = headers
      .take(index)
      .zip(c.states.drop(1))
      .foldLeft(
        VolatileChain.genesis[MirrorState](
          c.setup.geometry.k.toInt,
          c.setup.mirrorStart,
          c.setup.byronRules.map(_.ledger.genesis.hash)
        )
      ) { case (before, (header, state)) =>
        before
          .extend(header, state)
          .fold(e => fail(e.message), _._1)
      }
    def view(ref: BlockRef): SelectView =
      headers.find(_.point == ref.point).fold(fail("fixture header missing"))(SelectView.of)
    val selected = ChainSelection.Local(
      applied.anchor.point,
      applied.anchor.block.fold(SelectView(-1, 0, None, 0, None))(view),
      applied.blocks.map((ref, _) => ChainSelection.LocalBlock(ref.point, view(ref)))
    )
    for
      state <- Ref.of[IO, VolatileChain[MirrorState]](applied)
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(state)
      given Ask[IO, ChainSelection.Local] = Ask.const[IO, ChainSelection.Local](selected)
      inner = HeaderCheck.forGenesisChain[IO, MirrorState](
        NodeHeaderRules(c.setup.praos, c.setup.globals, c.setup.byronRules, c.setup.handOver),
        Compute.inline[IO],
        10000
      )
      clock = new SlotClock[IO]:
        def now: IO[Instant] = IO.pure(arrival)
        // Intentionally unusable: canonical timing must come from the intersection ledger.
        def geometry: IO[EpochGeometry] =
          IO.raiseError(new AssertionError("global geometry consulted"))
      gate <- HeaderClock.clocked(clock, inner).start(applied.tip)
      result <- Fault.fiber(
        gate.acceptOrRestart(Vector(SyncEvent.RollForward(headers(index), ChainSync.Tip.Origin)))
      )
    yield result

  test(
    "original signed Byron headers use Byron slot time while the Shelley boundary is unresolved"
  ) {
    val c = preprod
    val index = c.blocks.indexWhere(b => b.header.era == Era.Byron && b.header.slot > 0)
    assert(index >= 0)
    val h = c.blocks(index).header
    val onset = c.setup.geometry.systemStart
      .plusMillis((h.slot * c.setup.geometry.byron.slotLengthMillis).toLong)
    check(c, index, onset.minusSeconds(3)).map { result =>
      assertEquals(
        result,
        Left(
          Fault.Failed(PeerError.InvalidHeader(h.point, HeaderError.FarFuture(h.slot, 3000, 2000)))
        )
      )
    }
  }

  test(
    "the original first Shelley header is timed from the known handover before applying its body"
  ) {
    val c = eras
    val index = c.blocks.indexWhere(_.header.era == Era.Shelley)
    assert(index > 0)
    val h = c.blocks(index).header
    val first = c
      .states(index + 1)
      .consensus
      .byron
      .firstShelleyEpoch
      .fold(fail("handover epoch missing"))(identity)
    val geometry = c.setup.geometry.resolved(first)
    val onset = geometry.systemStart.plusMillis(geometry.slotOffsetMillis(h.slot).toLong)
    check(c, index, onset).map(result =>
      assertEquals(result, Right(HeaderGate.Acceptance.Accepted))
    )
  }

  test(
    "native checkTime pauses forecasts, delays once across a wall-clock step, then resumes before validation"
  ) {
    TestControl.executeEmbed {
      LimitOnPatience
        .resource[IO](LeakyBucket.Config.disabled, IO.unit, error => IO(fail(error.message)))
        .use { patience =>
          for
            offset <- Ref.of[IO, FiniteDuration](Duration.Zero)
            accepted <- Ref.of[IO, Option[FiniteDuration]](None)
            clock = new SlotClock[IO]:
              def now =
                (IO.realTime, offset.get).mapN((t, o) => Instant.EPOCH.plusNanos((t + o).toNanos))
              def geometry = IO.raiseError(new AssertionError("canonical global geometry"))
            checker = new HeaderCheck[IO]:
              def start(intersection: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
                override val canonicalTiming = true
                override def forecastTime(header: xyz.kd5ujc.scarab.ledger.Header)(using
                    cats.Applicative[IO]
                ) =
                  patience.snapshot.flatMap(s => IO(assert(s.paused))) *>
                    IO.sleep(100.millis).as(Some(HeaderGate.TimeForecast.Known(BigInt(10).pow(12))))
                override def prepareHeader(header: xyz.kd5ujc.scarab.ledger.Header)(using
                    cats.Applicative[IO],
                    Raise[IO, PeerError]
                ) =
                  patience.snapshot.flatMap(s => IO(assert(s.paused))) *>
                    IO.sleep(100.millis).as(HeaderGate.Acceptance.Accepted)
                def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
                  patience.snapshot.flatMap(s => IO(assert(!s.paused))) *>
                    IO.monotonic.flatMap(t => accepted.set(Some(t)))
                def counts = IO.pure(HeaderGate.Counts(0, 0, 0)))
            gate <- HeaderClock
              .clocked(clock, checker, patience = Some(patience))
              .start(ChainSync.Point.Origin)
            fiber <- Fault
              .fiber(
                gate.accept(
                  Vector(SyncEvent.RollForward(preprod.blocks(1).header, ChainSync.Tip.Origin))
                )
              )
              .start
            _ <- IO.sleep(200.millis) *> offset.set((-1).second)
            outcome <- fiber.joinWithNever
            reached <- accepted.get
            _ = assertEquals(outcome, Right(()))
            _ = assertEquals(reached, Some(1100.millis))
          yield ()
        }
    }
  }

  test("canceling canonical ledger forecast preparation resumes the patience bucket") {
    TestControl.executeEmbed {
      LimitOnPatience
        .resource[IO](LeakyBucket.Config.disabled, IO.unit, error => IO(fail(error.message)))
        .use { patience =>
          for
            waiting <- Deferred[IO, Unit]
            checker = new HeaderCheck[IO]:
              def start(intersection: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
                override val canonicalTiming = true
                override def forecastTime(header: xyz.kd5ujc.scarab.ledger.Header)(using
                    cats.Applicative[IO]
                ) =
                  IO.pure(Some(HeaderGate.TimeForecast.Known(0)))
                override def prepareHeader(header: xyz.kd5ujc.scarab.ledger.Header)(using
                    cats.Applicative[IO],
                    Raise[IO, PeerError]
                ) =
                  waiting.complete(()).void *> IO.never[HeaderGate.Acceptance]
                def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) = IO.unit
                def counts = IO.pure(HeaderGate.Counts(0, 0, 0)))
            clock = SlotClock.system[IO](
              IO.raiseError(new AssertionError("canonical global geometry"))
            )
            gate <- HeaderClock
              .clocked(clock, checker, patience = Some(patience))
              .start(ChainSync.Point.Origin)
            fiber <- Fault
              .fiber(
                gate.accept(
                  Vector(SyncEvent.RollForward(preprod.blocks(1).header, ChainSync.Tip.Origin))
                )
              )
              .start
            _ <- waiting.get *> fiber.cancel
            state <- patience.snapshot
            _ = assert(!state.paused)
          yield ()
        }
    }
  }

  test(
    "legacy live clock also rejects an original signed Byron header before its known Byron onset"
  ) {
    val c = preprod
    val h = c.blocks
      .find(b => b.header.era == Era.Byron && b.header.slot > 0)
      .fold(fail("Byron header missing"))(_.header)
    val onset = c.setup.geometry.systemStart
      .plusMillis((h.slot * c.setup.geometry.byron.slotLengthMillis).toLong)
    val clock = new SlotClock[IO]:
      def now = IO.pure(onset.minusSeconds(3))
      def geometry = IO.pure(c.setup.geometry)
    for
      gate <- HeaderClock.clocked(clock, HeaderCheck.none[IO]).start(ChainSync.Point.Origin)
      result <- Fault.fiber(gate.accept(Vector(SyncEvent.RollForward(h, ChainSync.Tip.Origin))))
    yield assertEquals(
      result,
      Left(
        Fault.Failed(PeerError.InvalidHeader(h.point, HeaderError.FarFuture(h.slot, 3000, 2000)))
      )
    )
  }
