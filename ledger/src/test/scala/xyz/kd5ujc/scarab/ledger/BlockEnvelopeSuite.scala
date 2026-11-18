// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** The lists of a BlockFetch block the reference reads with `enforceSize`: the hard-fork envelope
  * `[tag, block]` (`enforceSize "CardanoBlock" 2`, ouroboros-consensus 82ecba329
  * `Ouroboros/Consensus/Cardano/Node.hs:184`) and a Byron block's `[header, body, extra]`
  * (`enforceSize "Block" 3` and `enforceSize "BoundaryBlock" 3`, cardano-ledger f649f975
  * `eras/byron/ledger/impl/src/Cardano/Chain/Block/Block.hs:329,486`). `enforceSize` reads the
  * length with `decodeListLen`, which takes a definite list only, so the same items written as an
  * indefinite list `9f .. ff` are no block there, though the header, and with it the block's hash,
  * is unchanged. The block inside the envelope of the later eras is a `decodeRecordNamed` record
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/Block.hs:106`), which takes either form.
  *
  * The blocks are the first of every envelope tag of the era-walking capture (`fixtures/eras`, tags
  * 0 to 7), the first Dijkstra block of the Dijkstra devnet (`fixtures/dijkstra-devnet`, tag 8) and
  * the Conway blocks of `fixtures/preview/conway-governance.hex`.
  */
class BlockEnvelopeSuite extends FunSuite:
  /** `10k` of the era-walking capture's Byron genesis (k = 5). */
  private val ByronEpochSlots = BigInt(50)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def lines(path: Path): Vector[String] =
    Files.readAllLines(path).asScala.toVector.map(_.trim).filter(_.nonEmpty)

  /** The first block of every envelope tag, by tag. */
  private lazy val firstOfTag: Vector[(Int, Bytes)] =
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

  private lazy val preview: Vector[Bytes] =
    lines(Path.of("fixtures", "preview", "conway-governance.hex")).map(hex)

  /** The tag and the block of a definite envelope. */
  private def items(raw: Bytes): (Node, Node) =
    Cbor.decode(raw, Cbor.Limits.Unbounded).map(_.value) match
      case Right(Value.Arr(Vector(tag, block))) => (tag, block)
      case other                                => fail(s"not an envelope: $other")

  private def indefinite(parts: Vector[Bytes]): Bytes =
    Bytes(0x9f.toByte +: parts.flatMap(_.value) :+ 0xff.toByte)

  /** `raw` with its envelope written as an indefinite list of the same two items. */
  private def indefiniteEnvelope(raw: Bytes): Bytes =
    val (tag, block) = items(raw)
    indefinite(Vector(tag.original, block.original))

  /** `raw` with the block inside its envelope written as an indefinite list of the same items. */
  private def indefiniteBlock(raw: Bytes): Bytes =
    val (tag, block) = items(raw)
    block.value match
      case Value.Arr(parts) =>
        Bytes(Vector(0x82.toByte)) ++ tag.original ++ indefinite(parts.map(_.original))
      case other => fail(s"the block is not a list: $other")

  private def decode(raw: Bytes): Either[ChainError, Block] = Block.decode(raw, ByronEpochSlots)

  private def decoded(raw: Bytes): Block = decode(raw).fold(e => fail(e.message), identity)

  private val refused: Either[ChainError, Block] =
    Left(
      ChainError.Decode(DecodeField.Block, LedgerDecodeError.IndefiniteLength(DecodeField.Block))
    )

  test("the definite blocks of every envelope tag decode and pass the transport check") {
    firstOfTag.foreach { case (tag, raw) =>
      val block = decoded(raw)
      assertEquals(
        BlockTransport.check(block.header, raw).map(_.header.point),
        Right(block.header.point),
        s"tag $tag"
      )
    }
  }

  test("an envelope written as an indefinite list is refused for every tag, its header unchanged") {
    firstOfTag.foreach { case (tag, raw) =>
      val announced = decoded(raw).header
      val rewritten = indefiniteEnvelope(raw)
      assertEquals(decode(rewritten), refused, s"tag $tag: Block.decode")
      if tag >= 2 then
        assertEquals(Block.decodeShelley(rewritten), refused, s"tag $tag: decodeShelley")
      assertEquals(
        BlockTransport.check(announced, rewritten).map(_.header.point),
        refused.left.map(BlockTransportError.Decode(_)).map(_.header.point),
        s"tag $tag: BlockTransport.check"
      )
    }
  }

  test("a Byron block written as an indefinite list is refused, main and boundary alike") {
    firstOfTag.filter(_._1 <= 1).foreach { case (tag, raw) =>
      val announced = decoded(raw).header
      val rewritten = indefiniteBlock(raw)
      assertEquals(decode(rewritten), refused, s"tag $tag: Block.decode")
      assertEquals(
        BlockTransport.check(announced, rewritten).map(_.header.point),
        refused.left.map(BlockTransportError.Decode(_)).map(_.header.point),
        s"tag $tag: BlockTransport.check"
      )
    }
  }

  test(
    "a Shelley-based block inside a definite envelope is read in either form, as the record it is"
  ) {
    firstOfTag.filter((tag, _) => tag >= 2 && tag <= 7).foreach { case (tag, raw) =>
      val block = decoded(raw)
      val rewritten = indefiniteBlock(raw)
      assertEquals(decode(rewritten).map(_.header.point), Right(block.header.point), s"tag $tag")
      assertEquals(
        BlockTransport.check(block.header, rewritten).map(_.header.point),
        Right(block.header.point),
        s"tag $tag: BlockTransport.check"
      )
    }
  }

  test(
    "a Preview Conway block whose envelope is 9f 07 <block> ff is refused; 82 07 <block> is read"
  ) {
    assertEquals(preview.size, 23)
    preview.foreach { raw =>
      assertEquals(raw.take(2).hex, "8207")
      val block = Block.decodeShelley(raw).fold(e => fail(e.message), identity)
      val rewritten =
        Bytes(Vector(0x9f.toByte, 0x07.toByte)) ++ raw.drop(2) ++ Bytes(Vector(0xff.toByte))
      assertEquals(Block.decodeShelley(rewritten), refused, block.header.point.toString)
      assertEquals(
        BlockTransport.check(block.header, rewritten).map(_.header.point),
        refused.left.map(BlockTransportError.Decode(_)).map(_.header.point),
        block.header.point.toString
      )
      assertEquals(
        BlockTransport.check(block.header, raw).map(_.header.point),
        Right(block.header.point)
      )
    }
  }
