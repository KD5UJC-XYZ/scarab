// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.node.MirrorCorpus.Report
import xyz.kd5ujc.scarab.protocol.Era

/** The ledger-side mirror on the era-walking capture (`fixtures/eras`): the state of the node
  * before the first block and after every block, from Byron to the third Conway block, is turned
  * into the mirror of the era of the block and back. Every state must come back, have no residue (a
  * field with no place in its era) and refuse each tampered derived piece.
  */
class EraMirrorSuite extends FunSuite:
  private val fixtures = Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val env = setup.mirrorEnv()

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
    val initial = "before the first block" ->
      MirrorCorpus.check(env, None, setup.start, Some(Era.Byron))
    initial +: states.map { (block, state) =>
      val h = block.header
      s"${block.era} block ${h.blockNo}" ->
        MirrorCorpus.check(
          env,
          Some(BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)),
          state,
          Some(block.era)
        )
    }

  test("every state of the capture, in the era of its block, comes back from its mirror") {
    println(MirrorCorpus.summary("eras fixture", reports))
    assert(reports.nonEmpty)
    val bad = reports.filterNot(_._2.ok)
    assert(bad.isEmpty, bad.take(3).map((n, r) => s"$n: ${r.error} ${r.tampers}").mkString("; "))
  }

  test("the capture reaches every era, Byron first, and every state is in the era of its block") {
    assertEquals(
      reports.map(_._2.era).distinct,
      Vector(Era.Byron, Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    assert(reports.forall(_._2.eraAgrees))
  }

  test("the boundaries between protocols are in the capture: Byron to Shelley, TPraos to Praos") {
    val eras = reports.map(_._2.era)
    assert(eras.sliding(2).exists {
      case Vector(a, b) => a == Era.Byron && b == Era.Shelley; case _ => false
    })
    assert(eras.sliding(2).exists {
      case Vector(a, b) => a == Era.Alonzo && b == Era.Babbage; case _ => false
    })
  }

  test("no tamper is left unexercised on the capture") {
    val kinds = reports.flatMap(_._2.tampers.map(_._1)).toSet
    assert(kinds.contains("the PBFT count of a genesis key"), kinds.toString)
    assert(kinds.contains("the last slot of the chain-dependent state"), kinds.toString)
    assert(kinds.contains("the telescope bounds, later by a slot"), kinds.toString)
    assert(kinds.exists(_.startsWith("the ledger side:")), kinds.toString)
  }
