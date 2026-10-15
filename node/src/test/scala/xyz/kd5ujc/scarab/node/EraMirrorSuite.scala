// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.{MirrorEnv, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.MirrorCorpus.{Report, SomeEra}

/** The ledger-side mirror on the era-walking capture (`fixtures/eras`): the state of the node
  * before the first block and after every block, from Byron to the third Conway block, is turned
  * into the mirror of the era of the block and back. Every state must come back, have no residue (a
  * field with no place in its era) and refuse each tampered derived piece.
  */
class EraMirrorSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val env: MirrorEnv =
    MirrorEnv(setup.globals.network, setup.geometry, setup.globals.updateQuorum)

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The state after each block, with the block. */
  private lazy val states: Vector[(Block, NodeState)] =
    blocks.indices
      .foldLeft(Vector.empty[(Block, NodeState)]) { (acc, i) =>
        val before = acc.lastOption.fold(setup.start)(_._2)
        val h = blocks(i).header
        NodeRules.applyBlock(before, h, raws(i), setup.praos, setup.globals, setup.byronRules) match
          case Right(next)          => acc :+ (blocks(i) -> next)
          case Left((rule, reason)) => fail(s"block $i rejected by $rule: $reason")
      }

  private lazy val reports: Vector[(String, Report)] =
    states.flatMap { (block, state) =>
      SomeEra.ofBlock(block.era).map { era =>
        val slot = WithOrigin.At(SlotNo.fromUnsigned(block.header.slot.toLong))
        s"${block.era} block ${block.header.blockNo}" ->
          MirrorCorpus.check(env, era, slot, state.ledger)
      }
    }

  test("every state of the capture, in the era of its block, comes back from its mirror") {
    println(MirrorCorpus.summary("eras fixture", reports))
    assert(reports.nonEmpty)
    val bad = reports.filterNot(_._2.ok)
    assert(bad.isEmpty, bad.take(3).map((n, r) => s"$n: ${r.error} ${r.tampers}").mkString("; "))
  }

  test("the capture reaches Shelley-based eras of every kind") {
    assertEquals(
      reports.map(_._1.takeWhile(_ != ' ')).distinct,
      Vector("Shelley", "Allegra", "Mary", "Alonzo", "Babbage", "Conway")
    )
  }
