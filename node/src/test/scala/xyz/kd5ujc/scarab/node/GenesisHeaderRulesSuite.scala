// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, HeaderRules}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, Header, HeaderError, HeaderStructureProblem}
import xyz.kd5ujc.scarab.node.read.ChainRead.given

/** Original signed blocks captured from cardano-node, using the exact full-block path as an
  * independent state progression and comparing the complete chain-dependent state after each
  * header.
  */
class GenesisHeaderRulesSuite extends FunSuite:
  private final case class Capture(
      setup: NodeSetup,
      blocks: Vector[Block],
      states: Vector[MirrorState]
  )
  private def capture(directory: String): Capture =
    val root = Path.of(directory)
    val setup =
      NodeSetup.fromDirectory(root.resolve("genesis")).fold(e => fail(e.message), identity)
    val raws = Files
      .readString(root.resolve("blocks.hex"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(line => Bytes.fromHex(line).fold(e => fail(e.message), identity))
      .toVector
    val blocks = raws.map(raw =>
      Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
    )
    val states =
      blocks.zip(raws).foldLeft(Vector(setup.mirrorStart)) { case (known, (block, raw)) =>
        val next = MirrorSteps
          .block(setup, known.last, block.header, raw)
          .fold(e => fail(e.toString), identity)
        known :+ next
      }
    Capture(setup, blocks, states)

  private lazy val eras = capture("fixtures/eras")
  private lazy val preprod = capture("fixtures/preprod")
  private def rules(c: Capture) =
    given HeaderTimeRead[MirrorState] = HeaderTimeRead.mirror(c.setup.byronHistory)
    new NodeHeaderRules[MirrorState](
      c.setup.praos,
      c.setup.globals,
      c.setup.byronRules,
      c.setup.handOver
    )
  private def ref(h: Header): BlockRef = BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)

  for (name, readCapture) <- Vector("every-era" -> (() => eras), "preprod" -> (() => preprod)) do
    test(s"$name original signed headers all validate and reproduce full-block protocol states") {
      val c = readCapture()
      val check = rules(c)
      c.blocks.indices.foreach { i =>
        val anchor = c.states(i)
        val tip = c.blocks.take(i).lastOption.map(b => ref(b.header))
        val outcome = check.validate(anchor, tip, check.at(anchor), c.blocks(i).header)
        outcome match
          case HeaderRules.Outcome.Valid(state: NodeHeaderState.Byron) =>
            assertEquals(state.pbft, c.states(i + 1).consensus.byron.pbft, s"header $i")
          case HeaderRules.Outcome.Valid(state: NodeHeaderState.Shelley) =>
            assertEquals(state.state, c.states(i + 1).protocol, s"header $i")
          case other =>
            fail(s"header $i (${c.blocks(i).era}, slot ${c.blocks(i).header.slot}): $other")
      }
    }

  test("GDD windows and production header-time forecasts share every captured era horizon") {
    Vector(eras, preprod).foreach { c =>
      val history = HeaderTimeRead.mirror(c.setup.byronHistory)
      c.states.zipWithIndex.foreach { (state, index) =>
        val tip = c.blocks.take(index).lastOption.map(b => ref(b.header))
        val tipSlot = tip.fold(BigInt(-1))(_.slot)
        val start = state.ext.ledgerState.current.start.slot
        val slots = Vector(
          BigInt(-1),
          BigInt(0),
          start,
          tipSlot,
          tipSlot + 1,
          tipSlot + 2 * c.setup.byronHistory.k,
          tipSlot + c.setup.geometry.stabilityWindow,
          tipSlot + c.setup.geometry.epochLength
        )
        slots.distinct.foreach { slot =>
          val available = GenesisHistory.window(state, tipSlot, slot, c.setup).nonEmpty
          val forecast = history.forecast(state, tip, slot, c.setup.praos, c.setup.byronRules)
          assertEquals(
            forecast.isInstanceOf[HeaderRules.TimeForecast.Known],
            available,
            s"era ${state.ext.ledgerState.era}, tip $tipSlot, query $slot: $forecast"
          )
        }
      }
    }
  }

  test("Byron forecast headers preserve the full PBFT state without applying their bodies") {
    val c = preprod
    val check = rules(c)
    val anchor = c.states(1)
    val tip = Some(ref(c.blocks.head.header))
    val horizon = c.blocks.head.header.slot + 2 * c.setup.geometry.securityParam
    val headers = c.blocks
      .drop(1)
      .takeWhile(b =>
        b.header.era == xyz.kd5ujc.scarab.protocol.Era.Byron && b.header.slot <= horizon
      )
    assert(headers.nonEmpty)
    headers.zipWithIndex.foldLeft(check.at(anchor)) { case (previous, (block, index)) =>
      check.validate(anchor, tip, previous, block.header) match
        case HeaderRules.Outcome.Valid(next: NodeHeaderState.Byron) =>
          assertEquals(next.pbft, c.states(index + 2).consensus.byron.pbft)
          next
        case other => fail(s"forecast header ${index + 1}: $other")
    }
  }

  test("block-number and slot envelope checks run before signature validation") {
    val c = eras
    val index = c.blocks.indexWhere(b => !b.header.isBoundary)
    val header = c.blocks(index).header match
      case h: Header.Byron => h
      case _               => fail("expected Byron main header")
    val anchor = c.states(index)
    val tip = c.blocks.take(index).lastOption.map(b => ref(b.header))
    val check = rules(c)
    val bad = header.copy(blockNo = header.blockNo + 1)
    assertEquals(
      check.validate(anchor, tip, check.at(anchor), bad),
      HeaderRules.Outcome.Invalid(
        HeaderError.Structure(HeaderStructureProblem.BlockNumber(bad.blockNo, header.blockNo))
      )
    )
  }

  test("Byron forecast excludes NOW+1+2k exactly") {
    val c = preprod
    val check = rules(c)
    val anchor = c.states(1)
    val tip = Some(ref(c.blocks.head.header))
    val cfg = c.setup.byronRules.getOrElse(fail("missing Byron config"))
    val last = anchor.consensus.byron.ledger.getOrElse(fail("missing Byron ledger")).lastSlot
    val limit = last + 1 + 2 * cfg.ledger.genesis.securityParam
    val header = c.blocks(1).header match
      case h: Header.Byron =>
        h.copy(epoch = limit / h.epochSlots, slotInEpoch = limit % h.epochSlots)
      case _ => fail("expected main header")
    assertEquals(
      check.validate(anchor, tip, check.at(anchor), header),
      HeaderRules.Outcome.Later(HeaderRules.ForecastReason.Horizon(limit, last))
    )
  }

  test("the first Shelley forecast starts at the era boundary rather than the last Byron slot") {
    val c = preprod
    val index = c.blocks.indexWhere(_.era == xyz.kd5ujc.scarab.protocol.Era.Shelley)
    val anchor = c.states(index)
    val tip = Some(ref(c.blocks(index - 1).header))
    val h = c.blocks(index).header match
      case h: Header.Shelley => h
      case _                 => fail("expected Shelley")
    val check = rules(c)
    val first = xyz.kd5ujc.scarab.node.NodeRules
      .entry(anchor.consensus.byron, h, c.setup.praos, c.setup.globals, c.setup.byronRules)
      .fold(e => fail(e.toString), identity) match
      case NodeRules.Entry.Entering(first, _, _, _) => first
      case _                                        => fail("expected entry")
    val geometry = c.setup.geometry.resolved(first)
    val limit = geometry.firstSlot(first) + geometry.stabilityWindow
    val tooLate = h.copy(slot = limit)
    assertEquals(
      check.validate(anchor, tip, check.at(anchor), tooLate),
      HeaderRules.Outcome.Later(
        HeaderRules.ForecastReason.Horizon(limit, tip.fold(BigInt(0))(_.slot))
      )
    )
  }

  test("Byron header-only validation rejects a tampered original signature before body fetch") {
    val c = eras
    val index = c.blocks.indexWhere(b => !b.header.isBoundary)
    val header = c.blocks(index).header match
      case h: Header.Byron => h
      case _               => fail("expected main header")
    val main = header.main.getOrElse(fail("missing consensus data"))
    val delegated = main.signature match
      case d: Header.ByronSignature.Delegated => d
    val bytes = delegated.signature.toArray
    bytes(0) = (bytes(0) ^ 1).toByte
    val bad = header.copy(main =
      Some(main.copy(signature = delegated.copy(signature = Bytes.fromArray(bytes))))
    )
    val check = rules(c)
    val anchor = c.states(index)
    val tip = c.blocks.take(index).lastOption.map(b => ref(b.header))
    assertEquals(
      check.validate(anchor, tip, check.at(anchor), bad),
      HeaderRules.Outcome.Invalid(
        HeaderError.Byron(xyz.kd5ujc.scarab.ledger.ByronChainError.SignatureRejected)
      )
    )
  }

  test("configured checkpoint hashes apply to every original header family before crypto") {
    val c = eras
    c.blocks.indices.foreach { i =>
      val header = c.blocks(i).header
      val anchor = c.states(i)
      val tip = c.blocks.take(i).lastOption.map(b => ref(b.header))
      val wrong = Bytes(Vector.fill(32)(42.toByte))
      val configured = new NodeHeaderRules[MirrorState](
        c.setup.praos.copy(checkpoints =
          xyz.kd5ujc.scarab.consensus.HeaderCheckpoints(Map(header.blockNo -> wrong))
        ),
        c.setup.globals,
        c.setup.byronRules,
        c.setup.handOver
      )
      assertEquals(
        configured.validate(anchor, tip, configured.at(anchor), header),
        HeaderRules.Outcome.Invalid(
          HeaderError.Envelope(
            xyz.kd5ujc.scarab.ledger.EnvelopeError
              .CheckpointMismatch(header.blockNo, wrong, header.hash)
          )
        ),
        s"header $i (${header.era})"
      )
    }
  }
