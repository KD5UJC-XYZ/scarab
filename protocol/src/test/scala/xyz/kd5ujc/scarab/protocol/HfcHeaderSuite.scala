// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b

/** The hard-fork envelope of ChainSync headers, open for the eras after Conway, against headers
  * captured from the public Leios testnet (`fixtures/leios/testnet`, provenance in
  * `fixtures/leios/README.md`).
  */
class HfcHeaderSuite extends munit.FunSuite:
  private val dir = Path.of("fixtures", "leios", "testnet")

  /** `kind, block no, slot, hash, envelope` of each row; the notable file has a label first. */
  private final case class Row(label: String, kind: String, hash: String, envelope: Bytes)

  private def rows(file: String, labelled: Boolean): Vector[Row] =
    Files
      .readAllLines(dir.resolve(file))
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map { l =>
        val p = l.split('\t')
        val o = if labelled then 1 else 0
        Row(
          if labelled then p(0) else p(o + 1),
          p(o),
          p(o + 3),
          Bytes.fromHex(p(o + 4)).fold(e => fail(e), identity)
        )
      }

  private val notable = rows("headers-notable.tsv", labelled = true)
  private val fromOrigin = rows("headers-from-origin.tsv", labelled = false)

  private def row(label: String) = notable.find(_.label == label).getOrElse(fail(label))

  test("a Conway-tagged header of the testnet is a supported envelope, byte for byte") {
    val r = fromOrigin.head
    HfcHeader.decode(r.envelope) match
      case Right(HfcHeader.Supported(h)) =>
        assertEquals(h.era, Era.Conway)
        assertEquals(Blake2b.hash256.hash(h.raw).hex, r.hash)
        assertEquals(HfcHeader.encode(HfcHeader.Supported(h)), Right(r.envelope))
      case other => fail(other.toString)
  }

  test("a Dijkstra header (index 7) is named, its raw bytes hash to the block hash") {
    val r = row("first-dijkstra-tag")
    HfcHeader.decode(r.envelope) match
      case Right(d @ HfcHeader.Dijkstra(raw)) =>
        assertEquals(Blake2b.hash256.hash(raw).hex, r.hash)
        assertEquals(HfcHeader.encode(d), Right(r.envelope))
      case other => fail(other.toString)
  }

  test("every captured header decodes to the era its kind says") {
    (fromOrigin ++ notable).foreach { r =>
      HfcHeader.decode(r.envelope) match
        case Right(_: HfcHeader.Supported) => assertEquals(r.kind, "6")
        case Right(_: HfcHeader.Dijkstra)  => assertEquals(r.kind, "7")
        case other                         => fail(s"${r.label}: $other")
    }
  }

  test(
    "the closed decoder of the supported eras refuses index 7 (the baseline this one replaces)"
  ) {
    assert(NtNHeader.codec.decode(row("first-dijkstra-tag").envelope).isLeft)
  }

  test("an index no era has is kept with its payload, not refused") {
    // [9, h'abcd']
    val bytes = Bytes.fromHex("8209" + "42abcd").fold(e => fail(e), identity)
    assertEquals(
      HfcHeader.decode(bytes),
      Right(
        HfcHeader.Unrecognised(BigInt(9), Bytes.fromHex("42abcd").fold(e => fail(e), identity))
      )
    )
    assertEquals(HfcHeader.encode(HfcHeader.decode(bytes).toOption.get), Right(bytes))
  }

  test("malformed envelopes have their own typed reasons") {
    def hex(s: String) = Bytes.fromHex(s).fold(e => fail(e), identity)
    // not CBOR (a break outside an indefinite item)
    assertEquals(HfcHeader.decode(hex("ff")), Left(HfcHeaderError.Syntax))
    // not a pair
    assertEquals(HfcHeader.decode(hex("83010203")), Left(HfcHeaderError.NotAnEnvelope))
    assertEquals(HfcHeader.decode(hex("a0")), Left(HfcHeaderError.NotAnEnvelope))
    // the index is not an unsigned integer
    assertEquals(HfcHeader.decode(hex("82204100")), Left(HfcHeaderError.NotAnEnvelope))
    // a Dijkstra payload that is not tag 24 over a byte string
    assertEquals(HfcHeader.decode(hex("820741" + "00")), Left(HfcHeaderError.NotEmbeddedCbor))
    assertEquals(HfcHeader.decode(hex("8207d81801")), Left(HfcHeaderError.NotEmbeddedCbor))
    // trailing bytes after the envelope
    val good = row("first-dijkstra-tag").envelope
    assertEquals(HfcHeader.decode(good ++ hex("00")), Left(HfcHeaderError.Syntax))
    // a supported era whose payload is not an embedded header
    assert(HfcHeader.decode(hex("820641" + "00")).left.exists {
      case HfcHeaderError.Supported(Era.Conway, _) => true
      case _                                       => false
    })
  }

  test("every prefix of a real envelope is refused, never accepted") {
    val bytes = row("first-dijkstra-tag").envelope
    (0 until bytes.size by 7).foreach { n =>
      assert(HfcHeader.decode(bytes.take(n)).isLeft, s"prefix $n")
    }
  }

  test("the ChainSync payload codec accepts a header of any era and refuses a malformed envelope") {
    val good = row("first-dijkstra-tag").envelope
    assert(HfcHeader.codec.decode(good).isRight)
    assert(HfcHeader.codec.decode(good.take(10)).isLeft)
    assertEquals(HfcHeader.codec.encode(HfcHeader.codec.decode(good).toOption.get), Right(good))
  }
end HfcHeaderSuite
