// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

/** UTXOW refuses a witness datum that is neither required by a spent Plutus input nor the hash of a
  * datum in an output or reference input: `NotAllowedSupplementalDatums` (`missingRequiredDatums`,
  * `Alonzo/Rules/Utxow.hs`; supplemental hashes in `Alonzo/UTxO.hs` and `Babbage/UTxO.hs`). The
  * base transaction is the actual reference submission of a Plutus V1 spend with its datum in the
  * witness set (`fixtures/reference/spending-datum/conway-pv10.json`, mode `supplied-witness`); the
  * reference's own refusals that carry the failure (`no-witness-spend-*`) are byte-equal in
  * `PlutusFailureReferenceSuite`.
  */
class SupplementalDatumSuite extends FunSuite:
  private val base: PlutusCase =
    parse(Files.readString(Path.of("fixtures/reference/spending-datum/conway-pv10.json")))
      .fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .find(_.hcursor.downField("datumCase").get[String]("mode") == Right("supplied-witness"))
      .map(PlutusCase.fromJson)
      .getOrElse(fail("no supplied-witness case"))

  private def datum(n: Int): Bytes = Bytes.fromArray(Array[Byte](n.toByte))
  private def hashOf(d: Bytes): Bytes = Blake2b.hash256.hash(d)

  private val spent = base.tx.body.inputs.map(base.spent)
  private val scripts = base.tx.witnesses.scriptsByHash
  private val needed = TxChecks.scriptsNeeded(base.tx.body, spent).values.flatten.toSet
  private val template = base.tx.body.outputs.headOption.getOrElse(fail("no output"))

  private def withWitnessDatums(extra: Bytes*): Tx =
    base.tx.copy(witnesses =
      base.tx.witnesses.copy(
        datums = base.tx.witnesses.datums ++ extra,
        datumsRaw = None
      )
    )

  private def check(pp: ProtocolParams, tx: Tx, referenced: Vector[TxOut] = Vector.empty) =
    TxChecks.datumChecks(pp, tx, spent, referenced, scripts)

  private def notAllowed(unallowed: Set[Bytes], acceptable: Set[Bytes]) =
    Left(
      LedgerFailure.Rejected(
        base.tx.body.id,
        PredicateFailure.utxow(UtxowFailure.NotAllowedSupplementalDatums(unallowed, acceptable))
      )
    )

  test("the reference transaction, whose witness datum is the one an input requires, passes") {
    assertEquals(check(base.params, base.tx), Right(()))
    assertEquals(
      TxChecks.alonzoChecks(base.params, base.tx, spent, Vector.empty, scripts, needed),
      Right(())
    )
  }

  test("a witness datum that no input requires and no output carries is refused") {
    val extra = datum(1)
    assertEquals(
      check(base.params, withWitnessDatums(extra)),
      notAllowed(Set(hashOf(extra)), Set.empty)
    )
  }

  test("a supplemental datum whose hash an output carries is allowed") {
    val extra = datum(2)
    val body = base.tx.body.copy(outputs =
      base.tx.body.outputs :+ template.copy(raw = Bytes.empty, datum = Datum.Hash(hashOf(extra)))
    )
    val tx = withWitnessDatums(extra).copy(body = body)
    assertEquals(check(base.params, tx), Right(()))
  }

  test("the payload partitions the supplemental datums into unallowed and acceptable") {
    val (ok, bad) = (datum(3), datum(4))
    val body = base.tx.body.copy(outputs =
      base.tx.body.outputs :+ template.copy(raw = Bytes.empty, datum = Datum.Hash(hashOf(ok)))
    )
    assertEquals(
      check(base.params, withWitnessDatums(ok, bad).copy(body = body)),
      notAllowed(Set(hashOf(bad)), Set(hashOf(ok)))
    )
  }

  test("an inline datum does not make its hash allowed") {
    val extra = datum(5)
    val body = base.tx.body.copy(outputs =
      base.tx.body.outputs :+ template.copy(raw = Bytes.empty, datum = Datum.Inline(extra))
    )
    assertEquals(
      check(base.params, withWitnessDatums(extra).copy(body = body)),
      notAllowed(Set(hashOf(extra)), Set.empty)
    )
  }

  test("reference inputs and the collateral return allow datums from Babbage, not in Alonzo") {
    val h = hashOf(datum(6))
    val ref = template.copy(raw = Bytes.empty, datum = Datum.Hash(h))
    val body = base.tx.body.copy(collateralReturn = Some(ref))
    val outputsOnly = base.tx.body.copy(collateralReturn = None)
    def supplemental(pv: Int, b: TxBody, refs: Vector[TxOut]) =
      TxChecks.supplementalDatumHashes(TestLedger.params(pv), b, refs)
    assertEquals(supplemental(5, outputsOnly, Vector(ref)), Set.empty[Bytes])
    assertEquals(supplemental(6, outputsOnly, Vector(ref)), Set.empty[Bytes])
    assertEquals(supplemental(7, outputsOnly, Vector(ref)), Set(h))
    assertEquals(supplemental(10, outputsOnly, Vector(ref)), Set(h))
    assertEquals(supplemental(5, body, Vector.empty), Set.empty[Bytes])
    assertEquals(supplemental(9, body, Vector.empty), Set(h))
    // through the rule: the reference input allows the datum in Conway only
    val tx = withWitnessDatums(datum(6))
    assertEquals(check(base.params, tx, Vector(ref)), Right(()))
    assertEquals(check(base.params, tx), notAllowed(Set(h), Set.empty))
  }

  // ---- the reference's own refusals

  private final case class Refused(label: String, era: Era, tx: Tx, reason: Node)

  private def hex(text: String): Bytes = Bytes.fromHex(text).fold(e => fail(e.message), identity)

  /** The `no-witness-spend-*` submissions the reference node 11.1.3 refused
    * (`fixtures/devnet/refused-raw-plutus.jsonl`, `scripts/devnet/erarefusal.py`): a script spent
    * with its datum in the witnesses and without its script. The datum is then neither required
    * (the script is not provided) nor the hash of an output.
    */
  private lazy val refused: Vector[Refused] =
    Files
      .readAllLines(Path.of("fixtures/devnet/refused-raw-plutus.jsonl"))
      .asScala
      .toVector
      .map(line => parse(line).fold(e => fail(e.message), identity).hcursor)
      .filter(_.get[String]("label").exists(_.contains("-no-witness-spend-")))
      .map { json =>
        def text(key: String) = json.get[String](key).fold(e => fail(e.message), identity)
        val era = text("era") match
          case "alonzo"  => Era.Alonzo
          case "babbage" => Era.Babbage
          case _         => Era.Conway
        Refused(
          text("label"),
          era,
          Tx.fromEnvelope(hex(text("tx")), era).fold(e => fail(e.message), identity),
          Cbor.decode(hex(text("reason"))).fold(e => fail(s"$e"), identity)
        )
      }

  private def arr(n: Node): Vector[Node] = n.value match
    case CborValue.Arr(xs) => xs
    case _                 => Vector.empty

  private def hashSet(n: Node): Option[Set[Bytes]] = n.value match
    case CborValue.Tag(_, inner) => hashSet(inner)
    case CborValue.Arr(xs) =>
      xs.foldLeft(Option(Set.empty[Bytes])) { (acc, x) =>
        (acc, x.value) match
          case (Some(set), CborValue.ByteString(b)) => Some(set + b)
          case _                                    => None
      }
    case _ => None

  /** The payload of the reference's `NotAllowedSupplementalDatums`: constructor 3 of Alonzo's
    * predicate failures (inside Babbage's wrapper), 12 of Conway's.
    */
  private def recordedPayload(r: Refused): (Set[Bytes], Set[Bytes]) =
    val tag = if r.era == Era.Conway then 12 else 3
    def search(n: Node): Vector[(Set[Bytes], Set[Bytes])] =
      val here = arr(n) match
        case Vector(t, unallowed, acceptable) if t.value == CborValue.UInt(tag) =>
          for u <- hashSet(unallowed); a <- hashSet(acceptable) yield (u, a)
        case _ => None
      here.toVector ++ arr(n).flatMap(search)
    search(r.reason) match
      case Vector(one) => one
      case other => fail(s"${r.label}: ${other.size} supplemental-datum failures in the reason")

  private val recordedScript =
    Blake2b.hash224.hash(Bytes(Vector(1.toByte) ++ hex("46010000222499").value))

  test("the reference refused six transactions with a supplemental datum, two per era") {
    assertEquals(refused.size, 6)
    assertEquals(
      refused.groupBy(_.era).view.mapValues(_.size).toMap,
      Map(Era.Alonzo -> 2, Era.Babbage -> 2, Era.Conway -> 2)
    )
  }

  refused.foreach { r =>
    val pv = r.era match
      case Era.Alonzo  => 5
      case Era.Babbage => 7
      case _           => 9
    test(s"${r.label}: the datum rules produce the reference's payload at protocol $pv") {
      val datums = r.tx.witnesses.datumHashes
      assertEquals(datums.size, 1)
      // The setup output at index 0 is locked by the script and carries the datum hash; the
      // script itself is not among the witnesses.
      val spent = r.tx.body.inputs.map { in =>
        val (header, hash) =
          if in.index == 0 then (0x70, recordedScript.value) else (0x60, Vector.fill(28)(1.toByte))
        val address =
          Address.decode(Bytes(header.toByte +: hash)).fold(e => fail(e.toString), identity)
        TxOut(address, Value.coin(10000000)).copy(
          datum = if in.index == 0 then Datum.Hash(datums.head) else Datum.NoDatum
        )
      }
      val expected = recordedPayload(r)
      assertEquals(expected, (datums, Set.empty[Bytes]))
      assertEquals(
        TxChecks.datumChecks(TestLedger.params(pv), r.tx, spent, Vector.empty, Map.empty),
        Left(
          LedgerFailure.Rejected(
            r.tx.body.id,
            PredicateFailure.utxow(
              UtxowFailure.NotAllowedSupplementalDatums(expected._1, expected._2)
            )
          )
        )
      )
    }
  }

  test("the datum hash of a key-locked spent input is supplemental, not required") {
    val extra = datum(7)
    val keyOut = TestLedger
      .out(TestLedger.key().address(), 5000000)
      .copy(datum = Datum.Hash(hashOf(extra)))
    val only = base.tx.copy(
      body = base.tx.body.copy(inputs = base.tx.body.inputs.take(1)),
      witnesses = base.tx.witnesses.copy(datums = Vector(extra), datumsRaw = None)
    )
    assertEquals(
      TxChecks.datumChecks(base.params, only, Vector(keyOut), Vector.empty, Map.empty),
      Left(
        LedgerFailure.Rejected(
          only.body.id,
          PredicateFailure.utxow(
            UtxowFailure.NotAllowedSupplementalDatums(Set(hashOf(extra)), Set.empty)
          )
        )
      )
    )
  }
