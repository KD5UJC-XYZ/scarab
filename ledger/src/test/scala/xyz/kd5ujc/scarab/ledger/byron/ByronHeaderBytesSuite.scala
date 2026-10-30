// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, ChainError, DecodeField, Header, LedgerDecodeError}

/** A Byron header is read at decoder version 1 (`byronProtVer`, `Version.hs:162-163`,
  * cardano-ledger f649f975), where `decodeBytes` takes a definite byte string only
  * (`Decoder.hs:350-373`), and its body proof is read as a `Proof` (main, `Block/Proof.hs:82-85`)
  * or dropped as bytes (boundary, `Block/Header.hs:620-621`). Real mainnet headers, each with one
  * field rewritten as one indefinite chunk of the same bytes, so a refusal is for the form alone.
  * Evidence for #574.
  */
class ByronHeaderBytesSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s.trim).fold(e => fail(e.toString), identity)

  private def node(raw: Bytes): Node = CborRead.decode(raw).fold(e => fail(e.toString), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"array expected: $other")

  /** The main header of the first real mainnet block of `byron-main-blocks.hex`. */
  private val main: Bytes =
    val block = hex(Files.readAllLines(Path.of("fixtures/mainnet/byron-main-blocks.hex")).get(0))
    items(items(node(block))(1))(0).original

  private val ebb: Bytes =
    hex(Files.readAllLines(Path.of("fixtures/mainnet/byron-ebb-header.hex")).get(0))

  /** `raw` with the `occurrence` -th copy of the byte string `field` written as one indefinite
    * chunk (the leader's key is also the delegation certificate's issuer, so it occurs twice).
    */
  private def chunked(raw: Bytes, field: Node, occurrence: Option[Int] = None): Bytes =
    val all = Iterator
      .iterate(raw.value.indexOfSlice(field.original.value))(i =>
        raw.value.indexOfSlice(field.original.value, i + 1)
      )
      .takeWhile(_ >= 0)
      .toVector
    // a field found more than once must name which copy it is
    assert(occurrence.isDefined || all.size == 1, s"the field occurs ${all.size} times")
    val at = all.lift(occurrence.getOrElse(0)).getOrElse(fail(s"no occurrence $occurrence"))
    Bytes(
      raw.value.take(at) ++ (0x5f.toByte +: field.original.value :+ 0xff.toByte) ++
        raw.value.drop(at + field.original.size)
    )

  /** The header's refusal, as the decode failure it wraps (`Header.fail`). */
  private def verdict(raw: Bytes, boundary: Boolean): Either[ChainError, Unit] =
    Header.byronFromRaw(raw, boundary, 21600).map(_ => ())

  private def refused(raw: Bytes, boundary: Boolean): Unit =
    assertEquals(
      verdict(raw, boundary),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header))),
      "a chunked byte string of the header"
    )

  test("the real headers decode") {
    assert(Header.byronFromRaw(main, false, 21600).isRight)
    assert(Header.byronFromRaw(ebb, true, 21600).isRight)
  }

  test("a chunked previous hash, leader key or delegation signature is refused (main header)") {
    val h = items(node(main))
    val consensus = items(h(3))
    val proxy = items(items(consensus(3))(1))
    val psk = items(proxy(0))
    // the leader's key is the certificate's issuer: its first copy is the leader, its second psk(1)
    assertEquals(consensus(1).original, psk(1).original)
    refused(chunked(main, consensus(1), occurrence = Some(0)), boundary = false)
    refused(chunked(main, psk(1), occurrence = Some(1)), boundary = false)
    for field <- Vector(h(1), psk(2), psk(3), proxy(1)) do
      refused(chunked(main, field), boundary = false)
  }

  test("a chunked previous hash or body proof is refused (boundary header)") {
    val h = items(node(ebb))
    refused(chunked(ebb, h(1)), boundary = true)
    assertEquals(
      verdict(chunked(ebb, h(2)), true),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
    )
  }

  test("the main body proof is a Proof: a chunked hash in it is refused, a malformed one too") {
    val h = items(node(main))
    val proof = items(h(2))
    val tx = items(proof(0))
    def cause(raw: Bytes) = verdict(raw, false)
    assertEquals(
      cause(chunked(main, tx(1))),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
    )
    // the fixture's delegation hash equals its witnesses hash: its first copy is the witnesses hash
    assertEquals(proof(2).original, tx(2).original)
    assertEquals(
      cause(chunked(main, tx(2), occurrence = Some(0))),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
    )
    assertEquals(
      cause(chunked(main, proof(2), occurrence = Some(1))),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
    )
    assertEquals(
      cause(chunked(main, proof(3))),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
    )
    // a byte string of the SSC proof (`dropBytes`, any length, definite)
    assertEquals(
      cause(chunked(main, items(proof(1))(1))),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
    )
    // the SSC proof's tag is 0 to 3 (`dropSscProof`, `Ssc.hs:169-188`)
    val ssc = items(proof(1))
    val retagged = Bytes(
      main.value.patch(main.value.indexOfSlice(proof(1).original.value) + 1, Vector(0x04.toByte), 1)
    )
    assertEquals(ssc.head.value, Value.UInt(BigInt(0)), "the fixture's SSC proof is tag 0")
    assertEquals(
      cause(retagged),
      Left(Header.fail(LedgerDecodeError.UnknownTag(DecodeField.SSCProof, 4)))
    )
  }
