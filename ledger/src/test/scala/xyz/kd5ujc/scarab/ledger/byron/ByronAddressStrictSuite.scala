// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import munit.FunSuite
import scala.util.chaining.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, DecodeField, DecodeSite, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier
import xyz.kd5ujc.scarab.ledger.LedgerDecodeError.Shape

/** The rules of the reference's Byron `Address` decoder at `byronProtVer` (decoder version 1,
  * `decodeFullDecoder`; cardano-ledger f649f975, `Cardano.Chain.Common.Address`, `AddrAttributes`,
  * `AddrSpendingData`, `Hashing`), each with a case on either side of its boundary.
  * `fixtures/reference/conway-decoding` records the same encodings against the unchanged reference.
  */
class ByronAddressStrictSuite extends FunSuite:
  import ByronCbor.{bytes as bstr, concat, of, uint}

  private val root28 = bstr(Bytes.fromArray(Array.fill(28)(0x11.toByte)))
  private val emptyMap = of(0xa0)

  private def array3(root: Bytes, attributes: Bytes, kind: Bytes): Bytes =
    concat(of(0x83), root, attributes, kind)

  private def payload(
      root: Bytes = root28,
      attributes: Bytes = emptyMap,
      kind: Bytes = uint(0)
  ): Bytes = array3(root, attributes, kind)

  /** `[#6.24(bytes payload), crc32]`; every part may be replaced to write it differently. */
  private def envelope(
      body: Bytes,
      tagHead: Bytes = of(0xd8, 0x18),
      item: Option[Bytes] = None,
      crc: Option[Bytes] = None,
      outer: Bytes = of(0x82)
  ): Bytes =
    concat(
      outer,
      tagHead,
      item.getOrElse(bstr(body)),
      crc.getOrElse(uint(ByronCbor.crc32(body)))
    )

  private def address(
      root: Bytes = root28,
      attributes: Bytes = emptyMap,
      kind: Bytes = uint(0)
  ): Bytes = envelope(payload(root, attributes, kind))

  /** A byte string of one definite chunk written as an indefinite one. */
  private def indefinite(content: Bytes): Bytes = concat(of(0x5f), bstr(content), of(0xff))

  private def attributes(pairs: (Bytes, Bytes)*): Bytes =
    concat((of(0xa0 + pairs.size) +: pairs.flatMap((k, v) => Seq(k, v)))*)

  /** Attribute 1: the CBOR of the derivation path inside a byte string. */
  private def pathValue(cbor: Bytes): Bytes = bstr(cbor)
  private def path(content: Bytes): (Bytes, Bytes) = uint(1) -> pathValue(bstr(content))
  private def magic(cbor: Bytes): (Bytes, Bytes) = uint(2) -> bstr(cbor)

  /** An unsigned integer in the eight-byte head. */
  private def wide(n: Long): Bytes =
    Bytes.fromArray(0x1b.toByte +: (0 until 8).map(i => (n >> (56 - 8 * i)).toByte).toArray)

  private val onePathByte = Bytes.fromArray(Array(0x01.toByte))

  private def refusal(raw: Bytes): LedgerDecodeError =
    ByronAddress.decode(raw).fold(identity, a => fail(s"accepted: $a"))

  private def expectedAt(raw: Bytes, site: DecodeSite, shape: Shape): Unit =
    refusal(raw) match
      case LedgerDecodeError.Expected(s, sh, _) =>
        assertEquals(s, site)
        assertEquals(sh, shape)
      case other => fail(s"not an Expected at $site: $other")

  private def indefiniteAt(raw: Bytes, site: DecodeSite): Unit =
    assertEquals(refusal(raw), LedgerDecodeError.IndefiniteLength(site))

  private def accepts(raw: Bytes): ByronAddress =
    ByronAddress.decode(raw).fold(e => fail(e.toString), identity)

  test("the header byte of a bootstrap address is exactly 0x82") {
    val valid = address()
    assert(Address.decode(valid).isRight)
    for header <- 0x80 to 0x8f if header != 0x82 do
      val raw = Bytes(valid.value.updated(0, header.toByte))
      assertEquals(Address.decode(raw), Left(LedgerDecodeError.AddressHeader(header)))
  }

  test("the widths the reference keeps in the envelope are kept") {
    val body = payload()
    val tolerated = List(
      "tag 24 head" -> envelope(body, tagHead = of(0xd9, 0x00, 0x18)),
      "payload length" -> envelope(
        body,
        item = Some(concat(of(0x59, 0x00, body.size), body))
      ),
      "crc head" -> envelope(body, crc = Some(wide(ByronCbor.crc32(body)))),
      "body list head" -> envelope(concat(of(0x98, 0x03), root28, emptyMap, uint(0))),
      "root length" -> address(root = concat(of(0x59, 0x00, 0x1c), root28.drop(2))),
      "map head and key" -> address(
        attributes = concat(of(0xb8, 0x01, 0x18, 0x07), bstr(onePathByte))
      ),
      "unknown attribute" -> address(attributes = attributes(uint(5) -> bstr(onePathByte)))
    )
    tolerated.foreach((label, raw) => assert(ByronAddress.decode(raw).isRight, label))
  }

  test("an address keeps its root, type, magic, derivation path and unknown attributes") {
    val a = accepts(
      address(
        attributes = attributes(
          path(onePathByte),
          magic(uint(42)),
          uint(7) -> bstr(Bytes.fromArray(Array(1, 2, 3).map(_.toByte)))
        ),
        kind = uint(2)
      )
    )
    assertEquals(a.root.size, 28)
    assertEquals(a.addrType, ByronAddress.Redeem)
    assertEquals(a.networkMagic, Some(42L))
    assertEquals(a.derivationPathLength, 1)
    assertEquals(a.unknownAttributesLength, 3)
  }

  test("indefinite-length bytes, lists and maps are refused, their definite twins are not") {
    val body = payload()
    indefiniteAt(
      envelope(body, item = Some(indefinite(body))),
      DecodeField.ByronAddressPayload
    )
    indefiniteAt(
      envelope(concat(of(0x9f), root28, emptyMap, uint(0), of(0xff))),
      DecodeField.ByronAddressBody
    )
    indefiniteAt(
      address(root = indefinite(Bytes.fromArray(Array.fill(28)(0x11.toByte)))),
      DecodeField.ByronAddressRoot
    )
    indefiniteAt(
      address(attributes = concat(of(0xbf), path(onePathByte)._1, path(onePathByte)._2, of(0xff))),
      DecodeField.ByronAddressAttributes
    )
    indefiniteAt(
      address(attributes = attributes(uint(5) -> indefinite(onePathByte))),
      DecodeField.ByronBytes
    )
    indefiniteAt(
      address(attributes = attributes(uint(1) -> bstr(indefinite(onePathByte)))),
      DecodeField.ByronBytes
    )
    assert(ByronAddress.decode(address(attributes = attributes(path(onePathByte)))).isRight)
  }

  test("attribute keys are Word8 and strictly ascending") {
    val ascending = attributes(path(onePathByte), magic(uint(1)))
    assert(ByronAddress.decode(address(attributes = ascending)).isRight)
    val ascendingSite = DecodeField.ByronAddressAttributes / Qualifier.AscendingUniqueKeys
    expectedAt(
      address(attributes = attributes(magic(uint(1)), path(onePathByte))),
      ascendingSite,
      Shape.Map
    )
    expectedAt(
      address(attributes = attributes(path(onePathByte), path(onePathByte))),
      ascendingSite,
      Shape.Map
    )
    // 255 is the largest key and an unknown one keeps its bytes; 256 does not fit a Word8
    assert(
      ByronAddress.decode(address(attributes = attributes(uint(255) -> bstr(onePathByte)))).isRight
    )
    assertEquals(
      refusal(address(attributes = attributes(uint(256) -> bstr(onePathByte)))),
      LedgerDecodeError.NumberOutOfRange(DecodeField.ByronAddressAttributes, 256, 0, 255)
    )
    // key 2^32 + 1 must not wrap to the network magic or the derivation path
    val wide = BigInt(1) << 32
    assertEquals(
      refusal(address(attributes = attributes(uint(wide + 1) -> bstr(onePathByte)))),
      LedgerDecodeError.NumberOutOfRange(DecodeField.ByronAddressAttributes, wide + 1, 0, 255)
    )
  }

  test("the address type is a canonical Word8 that is 0 or 2") {
    assertEquals(accepts(address(kind = uint(0))).addrType, ByronAddress.VerKey)
    assertEquals(accepts(address(kind = uint(2))).addrType, ByronAddress.Redeem)
    List(1, 3, 23, 255).foreach(t =>
      assertEquals(
        refusal(address(kind = uint(t))),
        LedgerDecodeError.UnknownTag(DecodeField.ByronAddress, t)
      )
    )
    assertEquals(
      refusal(address(kind = uint(256))),
      LedgerDecodeError.NumberOutOfRange(DecodeField.ByronAddressType, 256, 0, 255)
    )
    expectedAt(
      address(kind = of(0x18, 0x00)),
      DecodeField.ByronAddressType / Qualifier.Canonical,
      Shape.Unsigned
    )
    expectedAt(
      address(kind = of(0x18, 0x02)),
      DecodeField.ByronAddressType / Qualifier.Canonical,
      Shape.Unsigned
    )
    expectedAt(address(kind = bstr(onePathByte)), DecodeField.ByronAddressType, Shape.Unsigned)
  }

  test("the derivation path is canonical bytes with nothing after it") {
    def derivation(inner: Bytes) = address(attributes = attributes(uint(1) -> bstr(inner)))
    def sized(n: Int) = Bytes.fromArray(Array.fill(n)(0x09.toByte))
    // the shortest head of each length is kept, a longer one is not
    List(0, 23, 24, 255, 256).foreach(n =>
      assertEquals(accepts(derivation(bstr(sized(n)))).derivationPathLength, n, s"length $n")
    )
    val canonicalSite = DecodeField.DerivationPath / Qualifier.Canonical
    expectedAt(derivation(concat(of(0x58, 0x01), onePathByte)), canonicalSite, Shape.Bytes)
    expectedAt(derivation(concat(of(0x59, 0x00, 0x18), sized(24))), canonicalSite, Shape.Bytes)
    expectedAt(derivation(concat(of(0x5a, 0, 0, 1, 0), sized(256))), canonicalSite, Shape.Bytes)
    expectedAt(derivation(uint(7)), DecodeField.ByronBytes, Shape.Bytes)
    indefiniteAt(derivation(indefinite(onePathByte)), DecodeField.ByronBytes)
    refusal(derivation(concat(bstr(onePathByte), of(0x00)))) match
      case LedgerDecodeError.Syntax(_) => ()
      case other                       => fail(s"not a syntax error: $other")
  }

  test("the network magic is a canonical Word32") {
    def withMagic(inner: Bytes) = address(attributes = attributes(uint(2) -> bstr(inner)))
    List(0L, 23L, 24L, 255L, 256L, 65535L, 65536L, (1L << 32) - 1).foreach(m =>
      assertEquals(accepts(withMagic(uint(m))).networkMagic, Some(m), s"magic $m")
    )
    val canonicalSite = DecodeField.NetworkMagic / Qualifier.Canonical
    expectedAt(withMagic(of(0x18, 0x05)), canonicalSite, Shape.Unsigned)
    expectedAt(withMagic(of(0x19, 0x00, 0x05)), canonicalSite, Shape.Unsigned)
    expectedAt(withMagic(of(0x1a, 0, 0, 0, 5)), canonicalSite, Shape.Unsigned)
    expectedAt(withMagic(of(0x1b, 0, 0, 0, 0, 0, 0, 0, 5)), canonicalSite, Shape.Unsigned)
    assertEquals(
      refusal(withMagic(uint(1L << 32))),
      LedgerDecodeError.NumberOutOfRange(
        DecodeField.NetworkMagic,
        BigInt(1) << 32,
        0,
        (BigInt(1) << 32) - 1
      )
    )
    expectedAt(withMagic(bstr(onePathByte)), DecodeField.NetworkMagic, Shape.Unsigned)
  }

  test("the root is exactly 28 bytes and nothing follows the address") {
    def rootOf(n: Int) = bstr(Bytes.fromArray(Array.fill(n)(0x11.toByte)))
    assert(ByronAddress.decode(address(root = rootOf(28))).isRight)
    List(0, 27, 29, 32).foreach(n =>
      assertEquals(
        refusal(address(root = rootOf(n))),
        LedgerDecodeError.ByteLength(DecodeField.ByronAddressRoot, 28, n)
      )
    )
    refusal(address() ++ of(0x00)) match
      case LedgerDecodeError.Syntax(_) => ()
      case other                       => fail(s"not a syntax error: $other")
  }

  test("the attributes of a transaction follow the same map rules") {
    import xyz.kd5ujc.scarab.codec.Cbor
    def length(raw: Bytes) =
      Cbor
        .decode(raw)
        .fold(e => fail(e.toString), identity)
        .pipe(ByronAddress.attributesLength(_, DecodeField.ByronTxAttributes))
    assertEquals(
      length(attributes(uint(1) -> bstr(onePathByte), uint(9) -> bstr(onePathByte))),
      Right(2)
    )
    assert(length(attributes(uint(9) -> bstr(onePathByte), uint(1) -> bstr(onePathByte))).isLeft)
    assert(length(attributes(uint(256) -> bstr(onePathByte))).isLeft)
  }
