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
    // a verification key is read with `decodeBytesCanonical`, whose definite test names the bytes
    def key(raw: Bytes): Unit =
      assertEquals(
        verdict(raw, false),
        Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes)))
      )
    key(chunked(main, consensus(1), occurrence = Some(0)))
    key(chunked(main, psk(1), occurrence = Some(1)))
    key(chunked(main, psk(2)))
    for field <- Vector(h(1), psk(3), proxy(1)) do refused(chunked(main, field), boundary = false)
  }

  test(
    "a verification key in a longer length form than its shortest is refused; a signature is not"
  ) {
    // `decodeBytesCanonical` for a key (`Signing/VerificationKey.hs:96-97`), `decodeBytes` for a
    // signature (`Signing/Signature.hs:129-130`): 64 bytes as `59 0040` instead of `58 40`
    val h = items(node(main))
    val consensus = items(h(3))
    val proxy = items(items(consensus(3))(1))
    val psk = items(proxy(0))
    def widened(field: Node, occurrence: Int): Bytes =
      val all = Iterator
        .iterate(main.value.indexOfSlice(field.original.value))(i =>
          main.value.indexOfSlice(field.original.value, i + 1)
        )
        .takeWhile(_ >= 0)
        .toVector
      val at = all(occurrence)
      val payload = field.original.value.drop(2)
      assertEquals(field.original.value.take(2), Vector(0x58, 0x40).map(_.toByte))
      Bytes(
        main.value.take(at) ++ (Vector(0x59, 0x00, 0x40).map(_.toByte) ++ payload) ++
          main.value.drop(at + field.original.size)
      )
    val canonical = Left(
      Header.fail(
        LedgerDecodeError.Expected(
          DecodeField.Header / xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier.Canonical,
          LedgerDecodeError.Shape.Bytes,
          consensus(1).value
        )
      )
    )
    assert(verdict(widened(consensus(1), 0), false).isLeft, "the leader's key")
    assert(verdict(widened(psk(1), 1), false).isLeft, "the certificate's issuer")
    assert(verdict(widened(psk(2), 0), false).isLeft, "the delegate")
    assert(verdict(widened(psk(3), 0), false).isRight, "the certificate is a signature")
    assert(verdict(widened(proxy(1), 0), false).isRight, "the block signature")
    assertEquals(verdict(widened(consensus(1), 0), false), canonical)
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

  /** The offset in `root` 's bytes of the item at `path` (each step a child index). */
  private def offset(root: Node, path: List[Int]): Int = path match
    case Nil => 0
    case i :: rest =>
      val children = items(root)
      head(root) + children.take(i).map(_.original.size).sum + offset(children(i), rest)

  /** The length of an array's head: one byte below 24 items or for an indefinite one. */
  private def head(n: Node): Int = (n.original.value(0) & 0x1f) match
    case 24 => 2
    case 25 => 3
    case 26 => 5
    case 27 => 9
    case _  => 1

  private def at(root: Node, path: List[Int]): Node =
    path.foldLeft(root)((n, i) => items(n)(i))

  /** `raw` with the item at `path` replaced by `replacement`. */
  private def spliced(raw: Bytes, path: List[Int], replacement: Vector[Byte]): Bytes =
    val root = node(raw)
    val from = offset(root, path)
    Bytes(raw.value.patch(from, replacement, at(root, path).original.size))

  /** `raw` with the definite list at `path` written as an indefinite list of the same items. */
  private def indefinite(raw: Bytes, path: List[Int]): Bytes =
    val item = at(node(raw), path)
    spliced(raw, path, (0x9f.toByte +: item.original.value.drop(head(item))) :+ 0xff.toByte)

  private def uint(n: BigInt): Vector[Byte] =
    xyz.kd5ujc.scarab.ledger.byron.ByronCbor.uint(n).value

  private val Max64 = (BigInt(1) << 64) - 1
  private val Bignum64 = Vector(0xc2, 0x49, 0x01).map(_.toByte) ++ Vector.fill(8)(0.toByte)

  private def cause(raw: Bytes, boundary: Boolean): Option[LedgerDecodeError] =
    verdict(raw, boundary).left.toOption.map {
      case ChainError.Decode(_, reason) => reason
      case other                        => fail(s"not a decode failure: $other")
    }

  test("every list of a main header is definite (enforceSize, #634)") {
    for (path, site) <- Vector(
        Nil -> DecodeField.Header,
        List(3) -> DecodeField.Header,
        List(3, 0) -> DecodeField.EpochAndSlot,
        List(3, 2) -> DecodeField.ChainDifficulty,
        List(3, 3) -> DecodeField.ByronBlockSignature,
        List(3, 3, 1) -> DecodeField.ByronBlockSignature,
        List(3, 3, 1, 0) -> DecodeField.DelegationCertificate,
        List(4) -> DecodeField.BlockVersions,
        List(4, 0) -> DecodeField.ProtocolVersion,
        List(4, 1) -> DecodeField.SoftwareVersion,
        // the body proof's lists, read since #574
        List(2) -> DecodeField.BodyProof,
        List(2, 0) -> DecodeField.TxProof,
        List(2, 1) -> DecodeField.SSCProof
      )
    do
      assertEquals(
        cause(indefinite(main, path), false),
        Some(LedgerDecodeError.IndefiniteLength(site)),
        path
      )
  }

  test("every list of a boundary header is definite (#634)") {
    for (path, site) <- Vector(
        Nil -> DecodeField.Header,
        List(3) -> DecodeField.Header,
        List(3, 1) -> DecodeField.ChainDifficulty,
        // the extra data, read since #754
        List(4) -> DecodeField.BoundaryExtraData
      )
    do
      assertEquals(
        cause(indefinite(ebb, path), true),
        Some(LedgerDecodeError.IndefiniteLength(site)),
        path
      )
  }

  test("a main header's magic is a Word32; a boundary header's any Int32, and kept (#634)") {
    val word32 = (BigInt(1) << 32) - 1
    assertEquals(
      Header.byronFromRaw(spliced(main, List(0), uint(word32)), false, 21600).map(_.protocolMagic),
      Right(word32)
    )
    assertEquals(
      cause(spliced(main, List(0), uint(word32 + 1)), false),
      Some(LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkMagic, word32 + 1, 0, word32))
    )
    val minusOne = Vector(0x20.toByte)
    assert(cause(spliced(main, List(0), minusOne), false).isDefined, "a negative main magic")
    assertEquals(
      Header.byronFromRaw(spliced(ebb, List(0), minusOne), true, 21600).map(_.protocolMagic),
      Right(BigInt(-1))
    )
    val int32 = (BigInt(Int.MinValue), BigInt(Int.MaxValue))
    assertEquals(
      cause(spliced(ebb, List(0), uint(int32._2 + 1)), true),
      Some(
        LedgerDecodeError.NumberOutOfRange(
          DecodeField.NetworkMagic,
          int32._2 + 1,
          int32._1,
          int32._2
        )
      )
    )
  }

  test("epochs, slot counts and difficulties are Word64 (#634)") {
    for path <- Vector(List(3, 0, 0), List(3, 0, 1), List(3, 2, 0), List(3, 3, 1, 0, 0)) do
      assert(verdict(spliced(main, path, uint(Max64)), false).isRight, path)
      assert(verdict(spliced(main, path, Bignum64), false).isLeft, path)
    for path <- Vector(List(3, 0), List(3, 1, 0)) do
      assert(verdict(spliced(ebb, path, uint(Max64)), true).isRight, path)
      assert(verdict(spliced(ebb, path, Bignum64), true).isLeft, path)
    assertEquals(
      cause(spliced(main, List(3, 3, 0), uint(258)), false),
      Some(LedgerDecodeError.NumberOutOfRange(DecodeField.ByronBlockSignature, 258, 0, 255))
    )
  }

  test("a main header's extra data is read as decCBORBlockVersions reads it (#634)") {
    val h = items(node(main))
    val extra = items(h(4))
    assertEquals(
      cause(spliced(main, List(4), uint(0)), false),
      Some(
        LedgerDecodeError.Expected(
          DecodeField.BlockVersions,
          LedgerDecodeError.Shape.Array,
          Value.UInt(0)
        )
      )
    )
    // the empty attributes: a definite map of no entries (`dropEmptyAttributes`)
    assertEquals(
      cause(spliced(main, List(4, 2), Vector(0xbf, 0xff).map(_.toByte)), false),
      Some(LedgerDecodeError.IndefiniteLength(DecodeField.HeaderAttributes))
    )
    assertEquals(
      cause(spliced(main, List(4, 2), Vector(0xa1, 0x00, 0x40).map(_.toByte)), false),
      Some(LedgerDecodeError.NumberOutOfRange(DecodeField.HeaderAttributes, 1, 0, 0))
    )
    // four items, not three
    val three = (0x83.toByte +: extra.take(3).flatMap(_.original.value))
    assertEquals(
      cause(spliced(main, List(4), three), false),
      Some(LedgerDecodeError.ArrayLength(DecodeField.BlockVersions, 4, 3))
    )
    // the software version: definite text and a Word32
    assertEquals(
      cause(spliced(main, List(4, 1, 1), uint(BigInt(1) << 32)), false),
      Some(
        LedgerDecodeError.NumberOutOfRange(
          DecodeField.SoftwareNumber,
          BigInt(1) << 32,
          0,
          (BigInt(1) << 32) - 1
        )
      )
    )
    val chunkedName = (0x7f.toByte +: at(node(main), List(4, 1, 0)).original.value) :+ 0xff.toByte
    assertEquals(
      cause(spliced(main, List(4, 1, 0), chunkedName), false),
      Some(LedgerDecodeError.IndefiniteLength(DecodeField.ApplicationName))
    )
    // the extra body proof: a definite byte string of any length (`dropBytes`)
    assert(verdict(spliced(main, List(4, 3), Vector(0x43, 0, 0, 0).map(_.toByte)), false).isRight)
    assertEquals(
      cause(chunked(main, extra(3)), false),
      Some(LedgerDecodeError.IndefiniteLength(DecodeField.ByronBytes))
    )
  }
