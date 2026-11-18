// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.HCursor
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.testByteStrings
import xyz.kd5ujc.scarab.ledger.rules.RedeemerIndexCases
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Babbage, Conway, EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.mempool.MempoolState
import xyz.kd5ujc.scarab.protocol.Era

/** The redeemer index of a repeated certificate, and the missing redeemers of every purpose,
  * against what the reference node (11.1.3) answered.
  *
  * `fixtures/devnet/redeemer-index.jsonl` holds each transaction of the redeemer-index cases the
  * `e2e` scenario sent the node (`redeemer_index` in `scripts/devnet/erarefusal.py`) with the
  * node's verdict: accepted, or refused with its raw `ApplyTxErr` over LocalTxSubmission and the
  * rendering `cardano-cli` printed. Each line also keeps the outputs the transaction spends, the
  * Plutus V1 cost model in force and the provenance of the run (commit, command, image, protocol
  * version). `devnet.py refused-fixture --redeemer-index` assembles the file from the run
  * directory, line for line.
  *
  * Each transaction is put to the node's pool rules ([[RedeemerIndexCases.judged]], the node's
  * evaluator) in a chain that holds the outputs it spends, the script's stake credential registered
  * and the pools it delegates to, at the recorded protocol version and cost model. One the
  * reference accepted must be accepted; one it refused must be refused with the failures that
  * [[PredicateFailureCbor]] writes as exactly the recorded bytes.
  */
class RedeemerIndexReferenceSuite extends FunSuite:
  private final case class Record(
      label: String,
      era: Era,
      major: Int,
      raw: Bytes,
      accepted: Boolean,
      reason: Option[Bytes],
      cli: String,
      spent: Vector[(TxIn, TxOut)],
      costModel: Vector[BigInt]
  ):
    /** The case, without the era the label starts with. */
    def kind: String = label.stripPrefix(era.toString.toLowerCase + "-")

    def tx: Tx = MempoolState.parseIn(raw, era).fold(r => fail(s"$label: ${r.message}"), _.tx)

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)

  private def output(hex: String): TxOut =
    Cbor
      .decode(bytes(hex))
      .left
      .map(e => s"$e")
      .flatMap(node =>
        TxOut
          .decode(node, Address.Mode.Rigorous, TxOut.Forms.Stored, ScriptKind.newestKnown)
          .left
          .map(_.message)
      )
      .fold(e => fail(e), identity)

  private def input(key: String): TxIn = key.split('#') match
    case Array(id, ix) => TxIn(bytes(id), BigInt(ix))
    case _             => fail(s"input $key")

  private def record(json: HCursor): Record =
    def get[A: io.circe.Decoder](key: String): A =
      json.get[A](key).fold(e => fail(e.message), identity)
    val label = get[String]("label")
    val era = get[String]("era") match
      case "alonzo"  => Era.Alonzo
      case "babbage" => Era.Babbage
      case "conway"  => Era.Conway
      case other     => fail(s"$label: era $other")
    val accepted = get[String]("verdict") match
      case "accepted" => true
      case "refused"  => false
      case other      => fail(s"$label: verdict $other")
    Record(
      label,
      era,
      json
        .downField("protocol")
        .downField("protocolVersion")
        .get[Int]("major")
        .fold(e => fail(e.message), identity),
      bytes(get[String]("tx")),
      accepted,
      json.get[String]("reason").toOption.map(bytes),
      json.get[String]("cli").getOrElse(""),
      get[Map[String, String]]("spent").toVector.map((k, v) => input(k) -> output(v)),
      get[Vector[BigInt]]("costModelPlutusV1")
    )

  private lazy val records: Vector[Record] =
    Files
      .readAllLines(Path.of("fixtures/devnet/redeemer-index.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => record(parse(line).fold(e => fail(e.message), identity).hcursor))

  /** The node's verdict on the recorded transaction: the chain of [[RedeemerIndexCases.chain]] with
    * the outputs it spends, the script credential its certificates delegate registered, the pools
    * they delegate to, and the recorded cost model of Plutus V1 (language 0).
    */
  private def judge[E <: EraTag](r: Record)(using
      ShelleyBasedEra[E]
  ): Either[Vector[PredicateFailure], Long] =
    val tx = r.tx
    val delegations = tx.body.certificates.collect {
      case Certificate.Delegate(credential, Some(pool), _, _) => credential -> pool
    }
    val credential = delegations.map(_._1).distinct match
      case Vector(one) => one
      case other       => fail(s"${r.label}: the certificates delegate $other")
    val pp = TestLedger.params(r.major).copy(costModels = Map(0 -> r.costModel))
    val nes =
      RedeemerIndexCases.chain[E](pp, r.spent, credential, delegations.map(_._2).distinct)
    RedeemerIndexCases.judged[E](nes, TestLedger.env)(tx)

  private def verdict(r: Record): Either[Vector[PredicateFailure], Long] = r.era match
    case Era.Alonzo  => judge[Alonzo](r)
    case Era.Babbage => judge[Babbage](r)
    case Era.Conway  => judge[Conway](r)
    case other       => fail(s"${r.label}: era $other")

  test("the recording has the cases of Babbage and Conway, and the reference's verdicts on them") {
    val recorded = records.map(r => r.label -> r.accepted).toMap
    val expected = Map(
      "babbage-missing-redeemers-every-purpose" -> false,
      "babbage-repeated-certificate-redeemer-0" -> true,
      "babbage-repeated-certificate-redeemers-0-1" -> false,
      "conway-missing-redeemers-every-purpose" -> false,
      "conway-distinct-certificates-redeemer-0" -> false,
      "conway-distinct-certificates-redeemers-0-1" -> true
    )
    expected.foreach { (label, accepted) =>
      assertEquals(
        recorded.get(label),
        Some(accepted),
        s"$label: the reference's verdict (true: accepted)"
      )
    }
  }

  records.foreach { r =>
    test(s"${r.era} ${r.kind}: the node's verdict is the reference's") {
      (verdict(r), r.reason) match
        case (Right(_), None) => assert(r.accepted, s"${r.label}: no reason, yet refused")
        case (Right(scripts), Some(_)) =>
          fail(s"${r.label}: accepted ($scripts scripts), the reference refused it: ${r.cli}")
        case (Left(failures), None) =>
          fail(s"${r.label}: refused with $failures, the reference accepted it")
        case (Left(failures), Some(reason)) =>
          assertEquals(
            PredicateFailureCbor
              .applyTxError(r.era, failures)
              .fold(error => fail(error.message), identity)
              .map(_.hex),
            Some(reason.hex),
            s"${r.label}: $failures"
          )
    }
  }
