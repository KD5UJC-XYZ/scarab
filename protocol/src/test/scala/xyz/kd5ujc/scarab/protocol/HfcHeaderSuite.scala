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
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

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
          Bytes.fromHex(p(o + 4)).fold(e => fail(e.toString), identity)
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
    val bytes = Bytes.fromHex("8209" + "42abcd").fold(e => fail(e.toString), identity)
    assertEquals(
      HfcHeader.decode(bytes),
      Right(
        HfcHeader.Unrecognised(BigInt(9), Bytes.fromHex("42abcd").fold(e => fail(e.toString), identity))
      )
    )
    assertEquals(HfcHeader.encode(HfcHeader.decode(bytes).toOption.get), Right(bytes))
  }

  test("malformed envelopes have their own typed reasons") {
    // not CBOR (a break outside an indefinite item)
    assertEquals(HfcHeader.decode(hex("ff")), Left(HfcHeaderError.Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.UnsupportedSimpleValue(31))))
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
    assertEquals(HfcHeader.decode(good ++ hex("00")), Left(HfcHeaderError.Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.TrailingBytes)))
    // a supported era whose payload is not an embedded header
    assert(HfcHeader.decode(hex("820641" + "00")).left.exists {
      case HfcHeaderError.Supported(Era.Conway, _) => true
      case _                                       => false
    })
  }

  // Fuzz seed 20743303 of the nightly run 39012761287 (target leios-header-envelope): `[7, tag24(..)]`
  // written as an indefinite-length array. The reference reads the envelope with `decodeListLen`
  // (ouroboros-consensus 4.2.0.1 `HardFork/Combinator/Serialisation/Common.hs` line 422, `enforceSize`;
  // cborg `Codec/CBOR/Read.hs` line 1925, `tryConsumeListLen`, takes 0x80 to 0x9b only), so it is
  // no envelope there either; it used to decode here and write back a definite array.
  private val indefiniteEnvelope: String =
    "9f07d818590380828c1943f51a0005ab9e582084d03b638ac00fc818113414d42e7ab1069f067bb852fedda1054faec7" +
      "b56fcc5820988b7453b0c3dfa59d8af72c24cbcc89120e261d709794d6c3b671ba616e03ee5820912f7d2d1bd61a2324" +
      "89232930a9c186b29ff5e660a86a8fbcfdd65d937ae269825840ceeb8acb12102b361b9fb67f8525fd50f0274d636183" +
      "5cd62107725235d5fc6f439e0760a8c170a2ac7193a1fa0008cd680eceba5008798c8ba25f27dbf65e445850e7cef572" +
      "278526739110ce7b11d68199fa05265e2217f3d9190aa69df4bd92fbcaa00ac6d0887b4d14d34b6ef1dc7f118c2ec384" +
      "b31a11234214a658edcf7307800f53eeddbe1759b1701faa8795cd021a000156795820373f96a5b606752ebe47bfb662" +
      "fc7fe955f4f2b4e378298212a79cdfda0c345c845820b110bd8bfd6db7b205c9ec0d3d9100987b4faeb1c1b708f120bd" +
      "de0429e2e97e00005840cfdbb0931f03a12a5bd87f84b33d764e0eb45a32c2240bd722d2a53a4aa200677c4d4282ec5d" +
      "791417aac29c4777abee732a48a881c4c21ecb98c0e8421f9702820c00f4825820e0de3c950714d0298fe9eda0350cae" +
      "f6cadedb2d4a27ff84ea01bfc87db6b952191f3a5901c09c6547327fb3e6615c989f1c0fed53a8d86a805468be72d30f" +
      "f47038aacd49bba9d036e3a111c6720a3e5529439aa093a88723211fc378d5a09e69db11ec060b0d991d9a461e5b17fc" +
      "cf0460a981cbfe923bb98297c50a5c7f9da2131f2f037300864adaebb6242d4f913c7dd1ad38e0ca66508aeb49e9e5bd" +
      "6a45cfef0a45a80ed8f9a594c1ffaecefcffbf7317c8688e30083d46558427f0266e2c5f837dd3ec26ef84c743d6553c" +
      "00ee30ffe7566d975e2a2c75d8f6f7fbde9ce43f92bfe8e7c9e41d83da2f262f567bf1803fca87c37d15c6f2b3dad13a" +
      "da8d18365a8bb93fb6c309f736b088d3117f86e330d6e199263ed99b0c003d5d25f8ed6efd3089e46c689c516258630b" +
      "7df3c5339e5a56275d511fec37190f25c19544b08a4bd6dc9ce63643b451e2dbb0c7b90e707e9da18a48e2ff97aef4fa" +
      "595f35da08a8d31fcffca84037c7019cf7e5289cde5a0907bba2fc1567c1746269876888db38debfc078b54924bb7ca5" +
      "dac1bc9340a414c66e9061a131298be2605401bab3db6116fccc672e2b668a52adca41866961e51ce043711767d3465b" +
      "cd69da68143fc6418a222aad3e30d0b21c3c21e817d10c9b930928ca7b8ef8554744ad6a407dc1ff"

  test("an envelope in an indefinite-length array is refused (fuzz seed 20743303)") {
    assertEquals(HfcHeader.decode(hex(indefiniteEnvelope)), Left(HfcHeaderError.NotAnEnvelope))
    // the same input as a definite array, which is the header it was meant to be
    val definite = "82" + indefiniteEnvelope.drop(2).dropRight(2)
    assert(HfcHeader.decode(hex(definite)).exists(_.isInstanceOf[HfcHeader.Dijkstra]))
    assertEquals(HfcHeader.decode(hex("9f07d8184100ff")), Left(HfcHeaderError.NotAnEnvelope))
    assertEquals(HfcHeader.decode(hex("9f0941abff")), Left(HfcHeaderError.NotAnEnvelope))
  }

  test("an indefinite-length embedded header is refused, as `decodeBytes` refuses it") {
    assertEquals(HfcHeader.decode(hex("8207d8185f4100ff")), Left(HfcHeaderError.NotEmbeddedCbor))
  }

  test("longer-than-shortest heads read as the reference reads them and are written canonically") {
    // 98 02 18 07 d8 18 58 01 00: the array length, the era and the byte string length padded
    val padded = hex("9802" + "1807" + "d818" + "580100")
    val canonical = hex("8207d8184100")
    val h = HfcHeader.decode(padded).fold(e => fail(e.toString), identity)
    assertEquals(h, HfcHeader.Dijkstra(hex("00")))
    assertEquals(HfcHeader.encode(h), Right(canonical))
    assertEquals(HfcHeader.decode(canonical), Right(h))
    val u = HfcHeader.decode(hex("9802" + "1809" + "4100")).fold(e => fail(e.toString), identity)
    assertEquals(u, HfcHeader.Unrecognised(BigInt(9), hex("4100")))
    assertEquals(HfcHeader.encode(u), Right(hex("82094100")))
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
