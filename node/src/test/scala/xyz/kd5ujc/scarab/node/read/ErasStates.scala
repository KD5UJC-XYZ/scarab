// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.node.{MirrorRun, NodeRules, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era

/** The states of the era-walking capture (`fixtures/eras`, Byron to Conway), each with the block
  * that made it, its anchor and its mirror: the corpus the read paths are compared on.
  */
object ErasStates:
  private val fixtures = Path.of("fixtures/eras")

  val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => sys.error(e.message), identity)

  val env: ConsensusMirrorEnv = setup.mirrorEnv()

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => sys.error(e), identity))

  val blocks: Vector[Block] =
    raws.map(
      Block.decode(_, setup.geometry.byron.epochSlots).fold(e => sys.error(e.message), identity)
    )

  /** A state of the capture. */
  final case class Sample(
      era: Era,
      anchor: BlockRef,
      state: NodeState,
      block: Block
  ):
    lazy val mirror: MirrorState =
      MirrorRun.run(env)(_.toMirror(Some(anchor), state)).fold(e => sys.error(e.toString), _.value)

  /** The state after every block. */
  lazy val samples: Vector[Sample] =
    blocks.indices
      .foldLeft(Vector.empty[Sample]) { (acc, i) =>
        val before = acc.lastOption.fold(setup.start)(_.state)
        val h = blocks(i).header
        NodeRules.applyBlock(before, h, raws(i), setup.praos, setup.globals, setup.byronRules) match
          case Right(next) =>
            acc :+ Sample(
              blocks(i).era,
              BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary),
              next,
              blocks(i)
            )
          case Left(f) => sys.error(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }
