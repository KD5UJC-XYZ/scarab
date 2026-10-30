// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Refusal}

/** A `Url` is read with `textDecCBOR`, which bounds it by its length in bytes of UTF-8: 128 from
  * decoder version 9 and 64 below it (`instance DecCBOR Url`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:644-697`, cardano-ledger f649f975).
  *
  * `fixtures/reference/url-length/cases.jsonl` holds original bodies of each era with a pool
  * metadata url, and of a Conway body with a DRep registration, proposal or vote anchor url, at the
  * era's bound and one byte over it, with what the unchanged released decoders did with each.
  * Evidence for #550.
  */
class UrlLengthReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.UrlLength).fold(e => fail(e.toString), identity)

  test("the corpus covers every era from Shelley to Conway, at the bound and over it") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      row.expect.foreach(e => assertEquals(e, Refusal.TextTooLong, row.name))
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  for row <- rows do
    test(s"${row.era}: ${row.name}") {
      (row.expect, TxBody.decode(row.raw, row.era)) match
        case (None, Right(body)) =>
          assertEquals(body.raw, row.raw, "the original bytes are retained")
          assertEquals(body.id, Blake2b.hash256.hash(row.raw), "the id hashes the original bytes")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }

  private def node(text: String): Node =
    val raw = text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val head =
      if raw.length < 24 then Array((0x60 | raw.length).toByte)
      else Array(0x78.toByte, raw.length.toByte)
    CborRead.decode(Bytes.fromArray(head ++ raw)).fold(e => fail(e.toString), identity)

  test("CborRead.url: 64 bytes of UTF-8 below decoder version 9, 128 from it") {
    val below = DecoderVersion.of(Era.Babbage)
    val from = DecoderVersion.of(Era.Conway)
    def url(n: Node, v: DecoderVersion) = CborRead.url(n, DecodeField.AnchorUrl, v)
    assertEquals(url(node("a" * 64), below), Right("a" * 64))
    assertEquals(
      url(node("a" * 65), below),
      Left(LedgerDecodeError.TextTooLong(DecodeField.AnchorUrl, 64, 65))
    )
    assertEquals(url(node("a" * 128), from), Right("a" * 128))
    assertEquals(
      url(node("a" * 129), from),
      Left(LedgerDecodeError.TextTooLong(DecodeField.AnchorUrl, 128, 129))
    )
    // bytes, not characters: 33 two-byte characters are 66 bytes
    assertEquals(
      url(node("é" * 33), below),
      Left(LedgerDecodeError.TextTooLong(DecodeField.AnchorUrl, 64, 66))
    )
  }
