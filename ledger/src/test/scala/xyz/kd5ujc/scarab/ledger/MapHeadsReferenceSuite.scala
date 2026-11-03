// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Kind}

/** An output from Babbage (`decodeBabbageTxOut`, `Babbage/TxOut.hs:549-553`) and the redeemers from
  * decoder version 9 (`RedeemersRaw`, `Alonzo/TxWits.hs:548-559`, cardano-ledger f649f975) take the
  * map form for cborg's `TypeMapLen` and `TypeMapLenIndef` alone, so a map whose length is written
  * in eight bytes (`TypeMapLen64`, head `0xbb`) is read as a list and refused.
  *
  * `fixtures/reference/map-heads/cases.jsonl` holds each place with each width of map head, and as
  * controls the decoders that take the eight-byte tokens too (a multi-asset map, metadata and a
  * metadatum, the list forms), with what the unchanged released decoders did with each. Evidence
  * for #701.
  */
class MapHeadsReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.MapHeads).fold(e => fail(e.toString), identity)

  /** Scarab's verdict and, for an accepted encoding, the original bytes it kept: of a whole
    * transaction, its body's and its auxiliary data's.
    */
  private def scarab(row: Case): Either[LedgerDecodeError, Bytes] = row.kind match
    case Kind.Body      => TxBody.decode(row.raw, row.era).map(_.raw)
    case Kind.Witnesses => Witnesses.decode(row.raw, row.era).map(_.raw.getOrElse(Bytes.empty))
    case Kind.Transaction =>
      Tx.fromEnvelope(row.raw, row.era)
        .map(tx => tx.body.raw ++ tx.auxiliary.getOrElse(Bytes.empty))

  /** What an accepted encoding keeps as it came: the whole item, or of a whole transaction the
    * original bytes of its first and last items.
    */
  private def kept(row: Case): Bytes = row.kind match
    case Kind.Transaction =>
      CborRead
        .decode(row.raw)
        .flatMap(CborRead.array(_, DecodeField.Transaction))
        .map(xs => xs.head.original ++ xs.last.original)
        .fold(e => fail(e.toString), identity)
    case _ => row.raw

  test("the corpus holds every width of head in each place, refused and accepted") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      assertEquals(row.accepted, row.rule.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
    for era <- List(Era.Babbage, Era.Conway) do
      val places = List(s"$era body output map", s"$era body collateral return map")
      for place <- places.map(_.toLowerCase) do
        val widths = rows.filter(_.name.startsWith(place))
        assertEquals(widths.size, 5, place)
        assertEquals(widths.filterNot(_.accepted).map(_.name), Vector(s"$place, eight-byte length"))
    val conway = rows.filter(_.name.startsWith("conway witnesses redeemer map"))
    assertEquals(conway.size, 5)
    assertEquals(
      conway.filterNot(_.accepted).map(_.name).map(_.split(", ").last),
      Vector("eight-byte length")
    )
  }

  for row <- rows do
    test(s"${row.era}: ${row.name}") {
      (row.expect, scarab(row)) match
        case (None, Right(raw))  => assertEquals(raw, kept(row), "the original bytes are retained")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }

  /** The stores read an output in every form an earlier build accepted ([[TxOut.Forms.Stored]]),
    * and an earlier build accepted a map output with an eight-byte head.
    */
  test("an output stored as a map with an eight-byte head still reads, and keeps its bytes") {
    val wide = rows.filter(r =>
      r.name.endsWith("body output map, eight-byte length") && r.era.ordinal >= Era.Babbage.ordinal
    )
    assertEquals(
      wide.map(r => (r.era, r.accepted)).toSet,
      Set(Era.Babbage -> false, Era.Conway -> false)
    )
    for row <- wide do
      val raw = outputOf(row)
      assertEquals(raw.byteOr(0)(0)(_ & 0xff), 0xbb, row.name)
      assertEquals(TxOut.fromBytes(raw).map(_.raw), Right(raw), row.name)
  }

  private def outputOf(row: Case): Bytes =
    val outputs = for
      root <- Cbor.decode(row.raw).toOption
      body <- CborRead.fields(root, DecodeField.TransactionBody).toOption
      field <- body.get(1)
      xs <- CborRead.array(field, DecodeField.Outputs).toOption
      out <- xs.headOption
    yield out.original
    outputs.getOrElse(fail(s"${row.name}: no output"))
