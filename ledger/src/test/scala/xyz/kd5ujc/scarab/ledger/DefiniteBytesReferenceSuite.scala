// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Kind, Refusal}

/** Below decoder version 12 the reference reads a byte string with `decodeBytes` or
  * `decodeByteArray` over a definite-length string only (`Decoder.hs` in cardano-ledger f649f975);
  * Plutus `Data` and metadata read chunked strings in every version.
  * `fixtures/reference/definite-bytes/cases.jsonl` holds original encodings, each place of each era
  * written with a definite string and with one chunk, each line with what the unchanged released
  * decoders did with it ([[ByteStrings]]).
  */
class DefiniteBytesReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.DefiniteBytes).fold(e => fail(e.toString), identity)

  /** The cases the tests below name, by the id the generator derives from their names. */
  private enum Known(val id: String):
    case BabbageChunkedAddress extends Known("babbage-body-map-output-address-indefinite")
    case ConwayChunkedDatumHash extends Known("conway-body-map-output-datum-hash-indefinite")
    case ConwayChunkedScriptRef extends Known("conway-body-map-output-reference-script-indefinite")
    case ConwayChunkedInputId extends Known("conway-body-input-id-indefinite")

  private def known(k: Known): Case =
    rows.find(_.id == k.id).getOrElse(fail(s"the corpus has no case $k"))

  /** Scarab's verdict and, for an accepted encoding, the original bytes it kept. */
  private def scarab(row: Case): Either[LedgerDecodeError, Bytes] = row.kind match
    case Kind.Body      => TxBody.decode(row.raw, row.era).map(_.raw)
    case Kind.Witnesses => Witnesses.decode(row.raw, row.era).map(_.raw.getOrElse(Bytes.empty))
    case Kind.Transaction =>
      Tx.fromEnvelope(row.raw, row.era).map(_ => row.raw)

  test("the corpus covers every era from Shelley to Conway, refused and accepted") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    for kind <- Kind.values do
      assert(rows.exists(r => r.kind == kind && r.accepted), s"no accepted $kind case")
      assert(rows.exists(r => r.kind == kind && !r.accepted), s"no refused $kind case")
    assertEquals(rows.map(_.name).distinct.size, rows.size)
  }

  test(
    "every case was recorded for the rule it breaks, and the metadata agrees with the reference"
  ) {
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  test("every chunked string the reference refuses has a definite twin it accepts") {
    val byName = rows.map(r => r.name -> r).toMap
    val chunked = rows.filter(_.name.endsWith(": indefinite"))
    assert(chunked.nonEmpty)
    for row <- chunked do
      val twin = byName.get(row.name.stripSuffix(": indefinite") + ": definite")
      assert(twin.exists(_.accepted), s"${row.name}: no accepted definite twin")
      assert(!row.accepted, row.name)
  }

  for row <- rows do
    test(s"${row.era} ${row.kind}: ${row.name}") {
      (row.expect, scarab(row)) match
        case (None, Right(retained)) =>
          assertEquals(retained, row.raw, "the original bytes are retained")
          if row.kind == Kind.Body then
            assertEquals(
              TxBody.decode(row.raw, row.era).map(_.id),
              Right(Blake2b.hash256.hash(row.raw)),
              "the id hashes the original bytes"
            )
          row.withdrawals.foreach { recorded =>
            val expected = recorded.traverse((a, n) => RewardAccount.decode(a).tupleRight(n))
            val decoded = TxBody.decode(row.raw, row.era).map(_.withdrawals)
            assertEquals(decoded.map(_.size), expected.map(_.size), "one entry per account")
            assertEquals(
              decoded.map(_.toMap),
              expected.map(_.toMap),
              "the withdrawals the reference decoded"
            )
          }
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }

  test("a repeated withdrawal account keeps its last amount before version 9 and fails from it") {
    val lastWins = rows.filter(_.withdrawals.isDefined)
    assertEquals(lastWins.map(_.era).toSet, Era.values.toSet -- Set(Era.Byron, Era.Conway))
    val refused = rows.filter(_.expect.contains(Refusal.DuplicateElement))
    assertEquals(refused.map(_.era).toSet, Set(Era.Conway))
    for row <- refused do
      assertEquals(
        TxBody.decode(row.raw, row.era).map(_ => ()),
        Left(LedgerDecodeError.DuplicateElement(DecodeField.Withdrawals)),
        row.name
      )
  }

  test("no era is read at decoder version 12 or above, so every era reads definite strings") {
    for era <- Era.values do
      assertEquals(ByteStrings.of(DecoderVersion.of(era)), ByteStrings.Definite, era.toString)
  }

  /** The output of the body of `row`, which has one output. */
  private def output(row: Case): Bytes =
    val outputs = for
      root <- Cbor.decode(row.raw).toOption
      body <- CborRead.fields(root, DecodeField.TransactionBody).toOption
      field <- body.get(1)
      xs <- CborRead.array(field, DecodeField.Outputs).toOption
      out <- xs.headOption
    yield out.original
    outputs.getOrElse(fail(s"${row.name}: no output"))

  test("an output stored with a chunked string still reads, and a block refuses the same output") {
    for k <- Vector(
        Known.BabbageChunkedAddress,
        Known.ConwayChunkedDatumHash,
        Known.ConwayChunkedScriptRef
      )
    do
      val row = known(k)
      assert(TxBody.decode(row.raw, row.era).isLeft, row.name)
      val raw = output(row)
      val stored = TxOut.fromBytes(raw)(using ByteStrings.Chunked)
      assert(stored.isRight, s"${row.name}: $stored")
      assertEquals(stored.map(_.raw), Right(raw), "the stored output keeps its original bytes")
      assertEquals(
        TxOut.fromBytes(raw, Address.Mode.of(row.era))(using
          ByteStrings.of(DecoderVersion.of(row.era))
        ),
        Left(LedgerDecodeError.IndefiniteLength(siteOf(k)))
      )
  }

  private def siteOf(k: Known): DecodeSite = k match
    case Known.BabbageChunkedAddress  => DecodeField.OutputAddress
    case Known.ConwayChunkedDatumHash => DecodeField.DatumHash
    case Known.ConwayChunkedScriptRef => DecodeField.ReferenceScript
    case Known.ConwayChunkedInputId   => DecodeField.InputId

  test("a refusal names the place of the chunked string") {
    assertEquals(
      TxBody.decode(known(Known.ConwayChunkedInputId).raw, Era.Conway),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.InputId))
    )
  }

  test("an update read before with a chunked extra entropy still applies, and a body refuses it") {
    val hash = Bytes.fromHex("5b" * 32).fold(e => fail(e.toString), identity)
    val chunked =
      Bytes.fromHex("8201" + "5f5820" + "5b" * 32 + "ff").fold(e => fail(e.toString), identity)
    val stored = ParamUpdate(Map(13 -> chunked))
    assertEquals(
      ProtocolParams.update(TestLedger.params(2), stored).map(_.extraEntropy),
      Right(Nonce.Hash(hash))
    )
    val lite = LiteParams((2, 0), Rational.one, Nonce.Neutral, 65536, 16384, 1100)
    assertEquals(LiteLedger.applyUpdate(lite, stored).map(_.extraEntropy), Right(Nonce.Hash(hash)))
    val body =
      rows.find(_.id == "shelley-body-update-extra-entropy-indefinite").getOrElse(fail("no case"))
    assertEquals(
      TxBody.decode(body.raw, Era.Shelley).left.toOption,
      Some(LedgerDecodeError.IndefiniteLength(DecodeField.Nonce))
    )
  }

  /** The definite Shelley body with the extra entropy `nonce` (hex) in place of its own. */
  private def shelleyBodyWith(nonce: String): Bytes =
    val definite =
      rows.find(_.id == "shelley-body-update-extra-entropy-definite").getOrElse(fail("no case"))
    val own = "82015820" + "5b" * 32
    assert(definite.raw.hex.contains(own))
    Bytes.fromHex(definite.raw.hex.replace(own, nonce)).fold(e => fail(e.toString), identity)

  private def ingress(nonce: String): Vector[Either[LedgerDecodeError, Unit]] =
    val body = shelleyBodyWith(nonce)
    Vector(
      TxBody.decode(body, Era.Shelley).map(_ => ()),
      LiteLedger.effects(body, Era.Shelley).map(_ => ())
    )

  test("a neutral nonce has exactly one field at both ingress paths, as Summands checks") {
    val refused = Left(LedgerDecodeError.TaggedArity(DecodeField.Nonce, BigInt(0), 2))
    for nonce <- Vector("820000", "9f0000ff") do
      assertEquals(ingress(nonce), Vector(refused, refused), nonce)
  }

  test("a neutral nonce of one field and a hash nonce of two are still accepted at ingress") {
    for nonce <- Vector(
        "8100",
        "9f00ff",
        "82015820" + "5b" * 32,
        "9f01" + "5820" + "5b" * 32 + "ff"
      )
    do assertEquals(ingress(nonce), Vector(Right(()), Right(())), nonce)
  }

  test("an update stored with a neutral nonce of two fields still applies (ADR 0006)") {
    val stored =
      ParamUpdate(Map(13 -> Bytes.fromHex("820000").fold(e => fail(e.toString), identity)))
    assertEquals(
      ProtocolParams.update(TestLedger.params(2), stored).map(_.extraEntropy),
      Right(Nonce.Neutral)
    )
    val lite = LiteParams(
      (2, 0),
      Rational.one,
      Nonce.Hash(Bytes(Vector.fill(32)(1.toByte))),
      65536,
      16384,
      1100
    )
    assertEquals(LiteLedger.applyUpdate(lite, stored).map(_.extraEntropy), Right(Nonce.Neutral))
  }
