// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.Path
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value}

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
  * The header read alone ([[Block.decodeHeader]]) goes through the same envelope and header code,
  * so it gives the header [[Block.decode]] gives, and refuses what decode refuses before the body.
  *
  * The blocks are the first of every envelope tag of the era-walking capture (`fixtures/eras`, tags
  * 0 to 7), the first Dijkstra block of the Dijkstra devnet (`fixtures/dijkstra-devnet`, tag 8) and
  * the Conway blocks of `fixtures/preview/conway-governance.hex`.
  */
class BlockEnvelopeSuite extends ScalaCheckSuite:
  import EraBlocks.*

  private lazy val preview: Vector[Bytes] =
    lines(Path.of("fixtures", "preview", "conway-governance.hex")).map(hex)

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

  private def header(raw: Bytes): Either[ChainError, Header] =
    Block.decodeHeader(raw, ByronEpochSlots)

  test("the header read alone is the decoded block's header, for every tag") {
    (firstOfTag ++ preview.map(7 -> _)).foreach { case (tag, raw) =>
      assertEquals(header(raw), Right(decoded(raw).header), s"tag $tag")
    }
  }

  test(
    "the header read alone refuses an indefinite envelope, and a Byron block's, as decode does"
  ) {
    firstOfTag.foreach { case (tag, raw) =>
      assertEquals(header(indefiniteEnvelope(raw)), refused.map(_.header), s"tag $tag: envelope")
    }
    firstOfTag.filter(_._1 <= 1).foreach { case (tag, raw) =>
      assertEquals(header(indefiniteBlock(raw)), refused.map(_.header), s"tag $tag: Byron block")
    }
  }

  /** A block of every tag, written again in one of the ways the tests above write it, and then with
    * one byte changed or not; most changes are in the first bytes, where the envelope and the
    * header are.
    */
  private val rewritten: Gen[(Int, Bytes)] =
    for
      (tag, raw) <- Gen.oneOf(firstOfTag ++ preview.map(7 -> _))
      written <- Gen.oneOf(
        raw,
        indefiniteEnvelope(raw),
        indefiniteBlock(raw),
        withoutBody(raw)
      )
      changed <- Gen.frequency(
        1 -> Gen.const(written),
        4 -> (for
          at <- Gen.frequency(
            3 -> Gen.choose(0, (written.size - 1).min(1200)),
            1 -> Gen.choose(0, written.size - 1)
          )
          byte <- Gen.choose(0, 255)
        yield Bytes(written.value.updated(at, byte.toByte)))
      )
    yield (tag, changed)

  property("the header read alone is decode's header, or decode's own refusal of it") {
    forAll(rewritten) { (tag, raw) =>
      val alone = header(raw)
      Block.decode(raw, ByronEpochSlots) match
        case Right(block) => assertEquals(alone, Right(block.header), s"tag $tag: decoded")
        // decode refused the block: the header read alone is refused the same way, or the
        // refusal was of the body's contents, which it does not decode
        case Left(error) =>
          alone.left.foreach(refusal => assertEquals(refusal, error, s"tag $tag: refused"))
    }
  }
