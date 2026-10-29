// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** A Shelley-based header reads its byte strings as definite-length strings. The 448-byte KES
  * signature and the VRF certificate's 64-byte output and 80-byte proof are fixed-size fields read
  * with `decodeFixedSized`, which is `rawDecodeFixedSized =<< decodeBytes` (`Decoder.hs:375-376`,
  * cardano-ledger f649f975); the issuer key, the VRF key, the previous hash and the body hash go
  * through their own `DecCBOR` instances. `decodeBytes` refuses a chunked string below decoder
  * version 12 (`Decoder.hs:350-373`). A Shelley header is encoded with `toEraCBOR @era` and decoded
  * with `eraDecoder @era` — the header's own codec
  * (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Block.hs:337` and
  * `:346` at ouroboros-consensus 82ecba32) — at the era's lowest protocol version, 2..9 from
  * Shelley to Conway. Evidence: #437.
  *
  * Every header byte string — the issuer and VRF keys, the previous hash, the body hash and the
  * 448-byte KES signature — goes through the same `bytes` helper, and `Header.fromRaw` validates no
  * certificate, so a complete body with nulls elsewhere reaches any of them (the cases below cover
  * the signature, the issuer key, the VRF key and the body hash). The accepted twin is the fixture
  * corpus: `EraDecodingSuite` decodes the committed real headers.
  */
class HeaderByteStringsSuite extends FunSuite:
  private def bytes(xs: Int*): Bytes = Bytes.fromArray(xs.map(_.toByte).toArray)

  private val key32 = Bytes.fromArray(Array.fill[Byte](32)(0xab.toByte))
  private val key32Definite = bytes(0x58, 0x20) ++ key32

  private def chunked(b: Bytes): Bytes =
    // one definite-length chunk of `b`, wrapped in an indefinite (chunked) string
    val n = b.size
    val head =
      if n < 24 then bytes(0x40 | n)
      else if n < 256 then bytes(0x58, n)
      else bytes(0x59, n >> 8, n & 0xff)
    bytes(0x5f) ++ head ++ b ++ bytes(0xff)

  private val kesBytes = Bytes.fromArray(Array.fill[Byte](448)(0))
  private val kes = bytes(0x59, 0x01, 0xc0) ++ kesBytes

  /** `[header_body, kes_signature]`, the shape every Shelley-based era shares. */
  private def header(kesSignature: Bytes, body: Bytes): Bytes =
    bytes(0x82) ++ body ++ kesSignature

  /** A 15-field TPraos body. `at` overrides one field index, so a case can put a chunked string
    * where it wants it; the block number and slot are unsigned and minimal, and the rest are null,
    * since `fromRaw` validates no certificate.
    */
  private def body(at: Map[Int, Bytes], count: Int = 15): Bytes =
    // field 0 is the block number and field 1 the slot, both read as unsigned; the rest may be null.
    // TPraos bodies are 15 fields, Praos (Babbage, Conway) are 10.
    val base = Map(0 -> bytes(0x01), 1 -> bytes(0x05)) ++ at
    val fields = (0 until count).map(i => base.getOrElse(i, bytes(0xf6)))
    bytes(0x80 | count) ++ fields.foldLeft(bytes())(_ ++ _)

  private def bytesOf(n: Int): Bytes =
    bytes(0x58, n) ++ Bytes.fromArray(Array.fill[Byte](n)(0x11.toByte))

  /** A VRF certificate: `[output (64), proof (80)]`, both definite. */
  private val vrfCertField = bytes(0x82) ++ bytesOf(0x40) ++ bytesOf(0x50)

  /** The fields `fromRaw` must read successfully before it reaches the body hash at index 8. */
  private val upToBodyHash = Map(
    2 -> (bytes(0x58, 0x20) ++ key32),
    3 -> key32Definite,
    4 -> bytesOf(0x20),
    5 -> vrfCertField,
    6 -> vrfCertField,
    7 -> bytes(0x00)
  )

  private val issuerField = Map(3 -> key32Definite)

  test("a chunked issuer key is refused: the header body reads key bytes as definite") {
    assertEquals(
      Header.fromRaw(Era.Shelley, header(kes, body(Map(3 -> chunked(key32))))).map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }

  test("a chunked previous hash is refused: optHash reads it as definite too") {
    assertEquals(
      Header.fromRaw(Era.Shelley, header(kes, body(Map(2 -> chunked(key32))))).map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }

  test("a chunked VRF key is refused") {
    assertEquals(
      Header
        .fromRaw(Era.Shelley, header(kes, body(issuerField.updated(4, chunked(key32)))))
        .map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }

  test("a chunked body hash is refused") {
    assertEquals(
      Header
        .fromRaw(Era.Shelley, header(kes, body(upToBodyHash.updated(8, chunked(key32)))))
        .map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }

  test("a chunked KES signature is refused: Scarab reads the signature before the body") {
    // Scarab's `fromRaw` reads the signature first (the reference reads the body first), so this is
    // the first byte string Scarab reaches.
    assertEquals(
      Header.fromRaw(Era.Shelley, header(chunked(kesBytes), body(issuerField))).map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }

  test("a chunked KES signature is refused for a Praos header too (Conway, 10-field body)") {
    // The body is a real 10-field Praos shape even though the signature is refused before it is read,
    // so the case does not depend on the shape being wrong.
    assertEquals(
      Header
        .fromRaw(Era.Conway, header(chunked(kesBytes), body(issuerField, count = 10)))
        .map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }

  test("a chunked body hash is refused in a Praos header too (Conway, 10 fields)") {
    // `praosTail` reads the hash at index 7; the fields before it must decode, since this case reaches
    // the tail rather than stopping at the signature. The VRF certificate is `[output, proof]`.
    val praos = body(
      Map(
        3 -> key32Definite,
        4 -> bytesOf(0x20),
        5 -> vrfCertField,
        6 -> bytes(0x00),
        7 -> chunked(key32)
      ),
      count = 10
    )
    assertEquals(
      Header.fromRaw(Era.Conway, header(kes, praos)).map(_ => ()),
      Left(Header.fail(LedgerDecodeError.IndefiniteLength(DecodeField.Header)))
    )
  }
