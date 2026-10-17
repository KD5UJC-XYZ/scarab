// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, StateMirror}
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era

/** The node's era-indexed block rules ([[MirrorRules]]) on the era-walking capture
  * (`fixtures/eras`, Byron to Conway, 731 blocks): every block is accepted, and the state after
  * each is the chain's at that block. This covers the hand-over from Byron and the first block of
  * every era, the steps that no snapshot or single-era fixture reaches. (Until J2.9 the suite ran
  * the old rules beside these and compared the state after each block; the comparison was made on
  * all 731 blocks, the header state, the consensus bookkeeping and the telescope bounds equal at
  * each, before the old rules were removed.)
  */
class MirrorRulesSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  private val headers: Vector[Header] = blocks.map(_.header)

  private def ref(h: Header) = BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)

  /** The state after each block. */
  private lazy val states: Vector[MirrorState] =
    blocks.indices
      .foldLeft(Vector(setup.mirrorStart)) { (acc, i) =>
        MirrorSteps.block(setup, acc.last, headers(i), raws(i)) match
          case Right(next) => acc :+ next
          case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }
      .tail

  test("every block of the capture, Byron to Conway, is accepted") {
    assertEquals(states.size, blocks.size)
  }

  test("the state after a block is in the era of the block, with the block as its tip") {
    blocks.indices.foreach { i =>
      val at = s"block $i (${blocks(i).era})"
      val m = states(i)
      assertEquals(m.era, blocks(i).era, s"the era after $at")
      assertEquals(m.ext.headerState.chainDep.era, blocks(i).era, s"the header state after $at")
      assertEquals(
        StateMirror.anchorOf(m.ext.headerState.tip),
        Some(ref(headers(i))),
        s"the tip after $at"
      )
    }
  }

  test("the telescopes hold one past era for each era the chain has been through") {
    blocks.indices.foreach { i =>
      val m = states(i)
      assertEquals(m.ext.ledgerState.past.size, blocks(i).era.ordinal, s"block $i")
      assertEquals(m.ext.headerState.chainDep.past.size, blocks(i).era.ordinal, s"block $i")
    }
    assertEquals(states.last.era, Era.Conway)
  }
