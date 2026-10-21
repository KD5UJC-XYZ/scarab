// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Block, BlockTransport, BlockTransportError, Header}
import xyz.kd5ujc.scarab.ledger.byron.ByronError
import xyz.kd5ujc.scarab.protocol.Era

class FetchedBlockIntegritySuite extends FunSuite:
  private val epochSlots = BigInt(50)
  private lazy val originals = Files
    .readString(Path.of("fixtures/eras/blocks.hex"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => Bytes.fromHex(line).fold(e => fail(e.message), identity))
    .map(raw => Block.decode(raw, epochSlots).fold(e => fail(e.message), b => b -> raw))
    .toVector

  private def array(node: Node): Vector[Node] = node.value match
    case Value.Arr(items) => items
    case other           => fail(s"expected fixture array, got $other")

  private def parts(raw: Bytes): Vector[Node] =
    array(array(Cbor.decode(raw).fold(e => fail(e.message), identity))(1))

  private def replace(raw: Bytes, item: Node, value: Bytes): Bytes =
    raw.take(item.offset) ++ value ++ raw.drop(item.offset + item.original.size)

  private def encoded(value: Value): Bytes = Cbor.encode(value).fold(e => fail(e.message), identity)

  private def alternateEmpty(item: Node): Bytes =
    assertEquals(array(item), Vector.empty)
    if item.original == Bytes(Vector(0x80.toByte)) then Bytes(Vector(0x9f.toByte, 0xff.toByte))
    else Bytes(Vector(0x80.toByte))

  test("all 731 reference-adopted original bodies satisfy their exact announced headers") {
    assertEquals(originals.size, 731)
    assertEquals(originals.map(_._1.era).toSet, Era.values.toSet)
    assert(originals.exists(_._1.header.isBoundary))
    originals.foreach { (block, raw) =>
      assertEquals(BlockTransport.check(block.header, raw), Right(block))
      assertEquals(FetchedBlockIntegrity.check(block.header, raw), Right(()))
    }
  }

  for era <- Era.values.filterNot(_ == Era.Byron) do
    test(s"$era declared-size-only failure remains semantic, not a transport body-hash failure") {
      val (block, raw) = originals.find(_._1.era == era).getOrElse(fail(s"missing $era"))
      val header = block.header match
        case h: Header.Shelley => h
        case _                => fail(s"expected Shelley-based header for $era")
      val signed = array(array(parts(raw)(0))(0))
      val size = signed(if era.usesTPraos then 7 else 6)
      val changed = replace(raw, size, encoded(Value.UInt(header.bodySize + 1)))
      val decoded = Block.decode(changed, epochSlots).fold(e => fail(e.message), identity)
      assertEquals(decoded.header match
        case h: Header.Shelley => h.bodySize
        case _                => fail("changed fixture era"), header.bodySize + 1)
      assertEquals(BlockTransport.check(decoded.header, changed), Right(decoded))
      // The signed header was deliberately changed; this is a transport-boundary test, not
      // evidence that the altered signature or the declared size passes ledger admission.
      assertNotEquals(decoded.header.raw, header.raw)
    }

  test("different original witness-container bytes fail the hash before semantic forgiveness") {
    val (block, raw) = originals.find { (block, raw) =>
      block.era == Era.Shelley && array(parts(raw)(2)).isEmpty
    }.getOrElse(fail("missing empty Shelley witness container"))
    val witnesses = parts(raw)(2)
    val changed = replace(raw, witnesses, alternateEmpty(witnesses))
    val decoded = Block.decode(changed, epochSlots).fold(e => fail(e.message), identity)
    assertEquals(decoded.header.raw, block.header.raw)
    BlockTransport.check(block.header, changed) match
      case Left(BlockTransportError.WrongBodyHash(_, _)) => ()
      case other => fail(s"body commitment mismatch entered semantic admission: $other")
  }

  test("Byron main delegation proof is a transport check over original payload bytes") {
    val (block, raw) = originals.find { (block, raw) =>
      block.era == Era.Byron && !block.header.isBoundary &&
      array(array(parts(raw)(1))(2)).isEmpty
    }.getOrElse(fail("missing empty Byron delegation payload"))
    val delegation = array(parts(raw)(1))(2)
    val changed = replace(raw, delegation, alternateEmpty(delegation))
    assertEquals(Block.decode(changed, epochSlots).map(_.header.raw), Right(block.header.raw))
    assertEquals(
      BlockTransport.check(block.header, changed),
      Left(BlockTransportError.ByronBodyProof(ByronError.ProofMismatch(ByronError.Proof.Delegation)))
    )
  }

  test("a different announced point or era and malformed input cannot reach ChainDB") {
    val (shelley, raw) = originals.find(_._1.era == Era.Shelley).getOrElse(fail("no Shelley"))
    val allegra = originals.find(_._1.era == Era.Allegra).map(_._1).getOrElse(fail("no Allegra"))
    assertEquals(
      BlockTransport.check(allegra.header, raw),
      Left(BlockTransportError.WrongEra(Era.Allegra, Era.Shelley))
    )
    val next = originals.find(b => b._1.era == Era.Shelley && b._1.header.point != shelley.header.point)
      .map(_._1).getOrElse(fail("no distinct Shelley header"))
    assertEquals(
      BlockTransport.check(next.header, raw),
      Left(BlockTransportError.WrongHeader(next.header.point, shelley.header.point))
    )
    assert(BlockTransport.check(shelley.header, Bytes(Vector(0xff.toByte))).isLeft)
  }
