// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.Assertions.fail
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** A block of every BlockFetch envelope tag, 0 to 8: the first of each tag of the era-walking
  * capture (`fixtures/eras`, tags 0 to 7) and the first Dijkstra block of the Dijkstra devnet
  * (`fixtures/dijkstra-devnet`, tag 8), with the helpers that take an envelope apart and write it
  * again.
  */
object EraBlocks:
  /** `10k` of the era-walking capture's Byron genesis (k = 5). */
  val ByronEpochSlots: BigInt = BigInt(50)

  def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  def lines(path: Path): Vector[String] =
    Files.readAllLines(path).asScala.toVector.map(_.trim).filter(_.nonEmpty)

  /** The first block of every envelope tag, by tag. */
  lazy val firstOfTag: Vector[(Int, Bytes)] =
    val eras = lines(Path.of("fixtures", "eras", "blocks.hex")).map(hex)
    val dijkstra = lines(Path.of("fixtures", "dijkstra-devnet", "blocks.tsv"))
      .drop(1)
      .map(_.split('\t'))
      .collectFirst { case row if row(0) == "8" => hex(row(4)) }
      .getOrElse(fail("no Dijkstra block in fixtures/dijkstra-devnet/blocks.tsv"))
    val byTag = (eras :+ dijkstra).groupBy(raw =>
      items(raw)._1.value match
        case Value.UInt(tag) => tag.toInt
        case other           => fail(s"not a tag: $other")
    )
    (0 to 8).toVector.map(tag => tag -> byTag.getOrElse(tag, fail(s"no block of tag $tag")).head)

  /** The tag and the block of a definite envelope. */
  def items(raw: Bytes): (Node, Node) =
    Cbor.decode(raw, Cbor.Limits.Unbounded).map(_.value) match
      case Right(Value.Arr(Vector(tag, block))) => (tag, block)
      case other                                => fail(s"not an envelope: $other")

  /** `parts` as a definite list of fewer than 24 items. */
  def definite(parts: Vector[Bytes]): Bytes =
    Bytes(Vector((0x80 + parts.size).toByte)) ++ parts.foldLeft(Bytes.empty)(_ ++ _)

  /** `parts` as an indefinite list. */
  def indefinite(parts: Vector[Bytes]): Bytes =
    Bytes(0x9f.toByte +: parts.flatMap(_.value) :+ 0xff.toByte)

  /** `raw` with every part of its block after the header written as the integer 0, which no era
    * reads as a body: the envelope and the header are the block's own.
    */
  def withoutBody(raw: Bytes): Bytes =
    val (tag, block) = items(raw)
    block.value match
      case Value.Arr(parts) =>
        val zero = Bytes(Vector(0.toByte))
        definite(
          Vector(
            tag.original,
            definite(parts.take(1).map(_.original) ++ parts.drop(1).map(_ => zero))
          )
        )
      case other => fail(s"the block is not a list: $other")
