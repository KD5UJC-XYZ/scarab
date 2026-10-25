// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.ledger.testByteStrings
import cats.data.NonEmptyVector
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.protocol.Era
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The failures of the Plutus and witness rules in Alonzo, Babbage and Conway against what the
  * reference node (11.1.3) answered.
  *
  * `fixtures/devnet/refused-raw-plutus.jsonl` holds, for each transaction that the `e2e` scenario
  * (`scripts/devnet/erarefusal.py`) sent the node and the node refused, the raw `ApplyTxErr` over
  * LocalTxSubmission, the transaction, the rendering of the reason that `cardano-cli` printed and
  * the provenance of the run (commit, command, image, protocol version). The file is assembled from
  * the run directory by `devnet.py refused-fixture --raw-plutus`, line for line.
  *
  * Each expected failure list is built from the recorded transaction (the certificate, the inputs,
  * the script integrity hash it carries) and from the `cardano-cli` rendering for what only the
  * ledger computes (the hash it expected, the minimum of a small output, the key that did not
  * sign); never from the recorded reason bytes. [[PreConwayFailureCbor]] and
  * [[PredicateFailureCbor]] must write exactly the recorded bytes, in the recorded order.
  */
class PlutusFailureReferenceSuite extends FunSuite:
  private final case class Record(label: String, era: Era, tx: Node, reason: Bytes, cli: String):
    /** The case, without the era the label starts with. */
    def kind: String = label.stripPrefix(era.toString.toLowerCase + "-")

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)
  private def decode(b: Bytes): Node = Cbor.decode(b).fold(e => fail(s"$e"), identity)

  private lazy val records: Vector[Record] =
    Files
      .readAllLines(Path.of("fixtures/devnet/refused-raw-plutus.jsonl"))
      .asScala
      .toVector
      .map { line =>
        val json = parse(line).fold(e => fail(e.message), identity).hcursor
        def text(key: String) = json.get[String](key).fold(e => fail(e.message), identity)
        val era = text("era") match
          case "alonzo"  => Era.Alonzo
          case "babbage" => Era.Babbage
          case "conway"  => Era.Conway
          case other     => fail(s"era $other")
        Record(text("label"), era, decode(bytes(text("tx"))), bytes(text("reason")), text("cli"))
      }

  // ---- reading the recorded transaction

  private def items(n: Node): Vector[Node] = n.value match
    case CborValue.Arr(xs) => xs
    case other             => fail(s"not an array: $other")

  private def field(map: Node, key: Int): Option[Node] = map.value match
    case CborValue.Map(entries) =>
      entries.collectFirst { case (k, v) if k.value == CborValue.UInt(key) => v }
    case other => fail(s"not a map: $other")

  private def byteString(n: Node): Bytes = n.value match
    case CborValue.ByteString(b) => b
    case other                   => fail(s"not bytes: $other")

  private def uint(n: Node): BigInt = n.value match
    case CborValue.UInt(v) => v
    case other             => fail(s"not an unsigned integer: $other")

  private def body(r: Record): Node = items(r.tx)(0)

  private def txIn(n: Node): TxIn = TxIn(byteString(items(n)(0)), uint(items(n)(1)))

  private def inputs(r: Record): Vector[TxIn] =
    items(field(body(r), 0).getOrElse(fail("no inputs"))).map(txIn)

  /** The first output of the transaction: the one the cases make too small. */
  private def firstOutput(r: Record): TxOut =
    TxOut
      .decode(items(field(body(r), 1).getOrElse(fail("no outputs")))(0))
      .fold(e => fail(e.message), identity)

  /** The script integrity hash the transaction carries. */
  private def suppliedHash(r: Record): Option[Bytes] = field(body(r), 11).map(byteString)

  private def credential(n: Node): Credential = items(n) match
    case Vector(kind, hash) if uint(kind) == 1 => Credential.Script(byteString(hash))
    case other                                 => fail(s"not a script credential: $other")

  /** The one certificate of the transaction, as the ledger's domain value. */
  private def certificate(r: Record): Certificate =
    items(field(body(r), 4).getOrElse(fail("no certificates"))) match
      case Vector(cert) =>
        items(cert) match
          case Vector(tag, c, pool) if uint(tag) == 2 =>
            Certificate.Delegate(credential(c), Some(byteString(pool)), None, None)
          case Vector(tag, c) if uint(tag) == 1 => Certificate.Unregister(credential(c), None)
          case Vector(tag, c, refund) if uint(tag) == 8 =>
            Certificate.Unregister(credential(c), Some(uint(refund)))
          case other => fail(s"${r.label}: certificate $other")
      case other => fail(s"${r.label}: certificates $other")

  private def certified(r: Record): Credential = certificate(r) match
    case Certificate.Delegate(c, _, _, _) => c
    case Certificate.Unregister(c, _)     => c
    case other                            => fail(s"certificate $other")

  private def scriptHashOf(c: Credential): Bytes = c match
    case Credential.Script(hash) => hash
    case other                   => fail(s"not a script: $other")

  // ---- reading what only the ledger computes, from the cardano-cli rendering

  private def captured(r: Record, pattern: String): String =
    pattern.r
      .findFirstMatchIn(r.cli)
      .map(_.group(1))
      .getOrElse(fail(s"${r.label}: no $pattern in ${r.cli}"))

  private def expectedHash(r: Record): Bytes =
    bytes(captured(r, """expected: SJust \(SafeHash "([0-9a-f]{64})"\)"""))

  private def minimum(r: Record): BigInt = BigInt(captured(r, """,Coin (\d+)\) :\|"""))

  private def missingKey(r: Record): Bytes = bytes(captured(r, "unKeyHash = \"([0-9a-f]{56})\""))

  // ---- the failures

  private val anyAddress = TestLedger.key().address()
  private def dummy = TestLedger.out(anyAddress, 0)

  private def collect(error: CollectError*): PredicateFailure =
    PredicateFailure.utxo(
      UtxoFailure.Utxos(UtxosFailure.CollectErrors(NonEmptyVector.fromVectorUnsafe(error.toVector)))
    )

  private def integrity(r: Record): Vector[PredicateFailure] =
    Vector(
      PredicateFailure.utxow(
        UtxowFailure.PPViewHashesDontMatch(Mismatch(suppliedHash(r), Some(expectedHash(r))))
      )
    )

  /** The order of the failures of the lists: the reference collects them in the order its rules
    * run, which differs between Conway (UTXO before the witness checks) and the earlier eras.
    */
  private def inOrder(
      r: Record
  )(earlier: Vector[PredicateFailure], conway: Vector[PredicateFailure]) =
    if r.era == Era.Conway then conway else earlier

  /** A purpose with no redeemer, and the script that needs it: `MissingRedeemers` and the
    * collection error of UTXOS, with the integrity mismatch when the transaction's hash is not the
    * ledger's.
    */
  private def noRedeemer(r: Record, purpose: Purpose, script: Bytes, withHash: Boolean) =
    val missing = PredicateFailure.utxow(UtxowFailure.MissingRedeemers(Vector(purpose -> script)))
    val collected = collect(CollectError.NoRedeemer(purpose))
    val mismatch = if withHash then integrity(r) else Vector.empty
    inOrder(r)(Vector(missing) ++ mismatch :+ collected, Vector(collected) ++ mismatch :+ missing)

  /** The input of the setup transaction that holds the script: output 0. */
  private def scriptInput(r: Record): TxIn =
    inputs(r).find(_.index == 0).getOrElse(fail(s"${r.label}: no script input"))

  /** The input of the setup transaction at output `index`. */
  private def inputAt(r: Record, index: Int): TxIn =
    inputs(r).find(_.index == index).getOrElse(fail(s"${r.label}: no input $index"))

  /** The script of the cases. */
  private val scriptHash =
    Blake2b.hash224.hash(Bytes(Vector(1.toByte) ++ bytes("46010000222499").value))

  private val datumHash = Blake2b.hash256.hash(bytes("182a"))

  private def expected(r: Record): Vector[PredicateFailure] =
    val variant = r.kind.substring(r.kind.lastIndexOf('-') + 1)
    r.kind match
      case k if k.startsWith("missing-redeemer-") =>
        val cert = certificate(r)
        assertEquals(
          scriptHashOf(certified(r)),
          scriptHash,
          s"${r.label}: the certificate names the script"
        )
        noRedeemer(r, Purpose.Certifying(0, cert), scriptHashOf(certified(r)), variant != "hash")
      case k if k.startsWith("no-redeemer-spend-") =>
        noRedeemer(r, Purpose.Spending(0, scriptInput(r), dummy), scriptHash, variant != "hash")
      case "no-witness-spend-language" | "no-witness-spend-plain" =>
        val missing = PredicateFailure.utxow(UtxowFailure.MissingScriptWitnesses(Set(scriptHash)))
        val datums =
          PredicateFailure.utxow(
            UtxowFailure.NotAllowedSupplementalDatums(Set(datumHash), Set.empty)
          )
        val extra = PredicateFailure.utxow(UtxowFailure.ExtraRedeemers(Vector((0, BigInt(0)))))
        val mismatch = if variant == "language" then integrity(r) else Vector.empty
        inOrder(r)(
          Vector(missing, datums, extra) ++ mismatch,
          mismatch ++ Vector(extra, datums, missing)
        )
      case "bad-translation-missing-input" =>
        val fake =
          inputs(r).find(_.id == bytes("ab" * 32)).getOrElse(fail(s"${r.label}: no fake input"))
        val badInputs = PredicateFailure.utxo(UtxoFailure.BadInputs(Set(fake)))
        val unresolved = collect(
          CollectError.BadTranslation(TranslationError.UnresolvedInput(fake))
        )
        r.era match
          case Era.Alonzo => Vector(badInputs) // the translation is not reached
          case Era.Conway => Vector(badInputs, unresolved)
          case _          => Vector(unresolved, badInputs)
      case "bad-translation-inline-datum" =>
        Vector(
          collect(
            CollectError.BadTranslation(
              TranslationError.InlineDatumsNotSupported(TxOutSource.TxOutFromInput(inputAt(r, 4)))
            )
          )
        )
      case "bad-translation-reference-script" =>
        Vector(
          collect(
            CollectError.BadTranslation(
              TranslationError.ReferenceScriptsNotSupported(
                TxOutSource.TxOutFromInput(inputAt(r, 5))
              )
            )
          )
        )
      case "bad-translation-reference-input" =>
        val refs = items(field(body(r), 18).getOrElse(fail("no reference inputs"))).map(txIn)
        Vector(
          collect(
            CollectError.BadTranslation(TranslationError.ReferenceInputsNotSupported(refs.toSet))
          )
        )
      case "missing-vkey-witness" =>
        Vector(PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(Set(missingKey(r)))))
      case "output-too-small" if r.era == Era.Alonzo =>
        Vector(PredicateFailure.utxo(UtxoFailure.OutputTooSmall(Vector(firstOutput(r)))))
      case "output-too-small" =>
        Vector(
          PredicateFailure.utxo(
            UtxoFailure.BabbageOutputTooSmall(Vector(firstOutput(r) -> minimum(r)))
          )
        )
      case other => fail(s"${r.label}: no failures built for $other")

  test("the recording has the cases of every era") {
    val kinds = records.groupMap(_.era)(_.kind).view.mapValues(_.toSet).toMap
    val common = Set(
      "missing-redeemer-delegation-hash",
      "missing-redeemer-deregistration-hash",
      "no-redeemer-spend-hash",
      "no-witness-spend-plain",
      "missing-vkey-witness",
      "output-too-small"
    )
    Vector(Era.Alonzo, Era.Babbage, Era.Conway).foreach { era =>
      val missing = common.diff(kinds.getOrElse(era, Set.empty))
      assert(missing.isEmpty, s"$era: not recorded: $missing")
    }
  }

  records.foreach { r =>
    test(s"${r.era} ${r.kind}: the bytes are the reference's") {
      val failures = expected(r)
      assertEquals(
        PredicateFailureCbor.applyTxError(r.era, failures).map(_.hex),
        Some(r.reason.hex)
      )
    }
  }
