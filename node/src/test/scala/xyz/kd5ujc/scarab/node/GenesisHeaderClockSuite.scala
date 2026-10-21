// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.{Ask, Stateful, Tell}
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
          c.setup.geometry.securityParam,
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
        GenesisHeaderRules(c.setup.praos, c.setup.globals, c.setup.byronRules, c.setup.handOver),
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
