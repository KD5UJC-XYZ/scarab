// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ScalusEvaluator}

/** Complete reference submissions, including phase-1 refusals that never enter evaluation. Unlike
  * the context-only harness, decode refusals cannot supply an invalid-tag collateral verdict.
  */
class BoundaryTransactionsSuite extends FunSuite:
  private enum Stage(val wire: String):
    case Decode extends Stage("decode")
    case Scope extends Stage("scope")
    case Evaluation extends Stage("evaluation")
    case Success extends Stage("success")

  private def field[A: io.circe.Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(error => fail(error.message), identity)
  private def obj(json: Json, name: String): Json = field[Json](json, name)
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  private final case class Recorded(json: Json):
    val c: PlutusCase = PlutusCase.fromJson(json)
    val boundary: Json = obj(json, "boundary")
    val id: String = field[String](boundary, "id")
    val accepted: Boolean = field[Boolean](obj(json, "reference"), "accepted")
    val stage: Stage = Stage.values
      .find(_.wire == field[String](boundary, "stage"))
      .getOrElse(fail(s"unknown stage: $id"))
    val allInputs: Map[TxIn, TxOut] = c.spent ++ c.references ++ c.collateral

  private val directory = Path.of("fixtures/reference/plutus-boundary-transactions/transactions")
  private val scripts = Files
    .readAllLines(directory.getParent.resolve("scripts.jsonl"))
    .asScala
    .toVector
    .map { line =>
      val json = parse(line).fold(error => fail(error.message), identity)
      field[String](json, "id") -> json
    }
    .toMap
  private val recorded: Vector[Recorded] =
    // committed fixtures: a missing directory fails rather than leaving no cases to run
    if !Files.isDirectory(directory) then fail(s"missing committed fixture directory $directory")
    else
      val paths = Files.list(directory)
      try
        paths.iterator.asScala
          .filter(_.toString.endsWith(".json"))
          .toVector
          .sortBy(_.toString)
          .flatMap { path =>
            val json = parse(Files.readString(path)).fold(error => fail(error.message), identity)
            json.asArray.getOrElse(fail(s"not a case array: $path")).map(Recorded(_))
          }
      finally paths.close()

  test("each observed protocol covers every generated probe and both validity flags") {
    assert(recorded.nonEmpty, "no actual complete reference boundary submissions")
    assertEquals(recorded.map(_.c.protocolMajor).toSet, Set(10, 11))
    val ids = scripts.keySet
    recorded.groupBy(_.c.protocolMajor).foreach { (protocol, cases) =>
      assertEquals(
        cases.map(r => (r.id, r.c.tx.isValid)).toSet,
        (for id <- ids; valid <- Set(true, false) yield (id, valid))
      )
      assertEquals(cases.size, ids.size * 2, s"duplicate cases at protocol $protocol")
    }
  }

  private def malformed(c: PlutusCase): LedgerFailure = LedgerFailure.Rejected(
    c.tx.body.id,
    PredicateFailure.utxow(
      UtxowFailure.MalformedScriptWitnesses(c.tx.witnesses.scripts.map(_.hash).toSet)
    )
  )

  private def refusal(r: Recorded, failure: LedgerFailure): Unit =
    r.stage match
      case Stage.Decode => assertEquals(failure, malformed(r.c))
      case _ =>
        failure match
          case LedgerFailure.Rejected(
                _,
                PredicateFailure.Utxow(
                  UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(flag, _)))
                )
              ) =>
            assertEquals(flag, r.c.tx.isValid)
          case other => fail(s"not a validation-tag refusal: $other")

  private def disposition(r: Recorded, after: Utxo): Unit =
    val spent = obj(r.boundary, "spentAfter")
    r.c.spent.keys.foreach { in =>
      assertEquals(!after.contains(in), field[Boolean](spent, "funding"))
    }
    r.c.collateral.keys.foreach { in =>
      assertEquals(!after.contains(in), field[Boolean](spent, "collateral"))
    }

  private val issuer = KeyHash
    .fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte)))
    .fold(error => fail(error.toString), identity)
  private val commitment = BodyCommitment(100, Bytes.fromArray(Array.fill(32)(1.toByte)))

  recorded.foreach { r =>
    val c = r.c
    test(s"${c.name}: original witness bytes, admission and applied evaluation stage") {
      val original = scripts.getOrElse(r.id, fail(s"unknown boundary probe ${r.id}"))
      for name <- Vector("script", "sourceScript", "sourceIndices") do
        assertEquals(obj(r.boundary, name), obj(original, name), s"${r.id}/$name")
      assertEquals(field[String](obj(original, "stages"), c.protocolMajor.toString), r.stage.wire)
      for role <- Vector("funding", "collateral") do
        assert(field[Boolean](obj(r.boundary, "before"), role), s"missing $role before submission")
      assertEquals(field[Int](r.boundary, "observedProtocolMajor"), c.protocolMajor)
      assertEquals(field[Boolean](r.boundary, "included"), r.accepted)
      val confirmation = r.boundary.hcursor
        .get[Option[Json]]("setupConfirmation")
        .fold(error => fail(error.message), identity)
      assert(!r.id.startsWith("scope-depth-two-") || confirmation.nonEmpty)
      confirmation.foreach { proof =>
        assert(field[Int](proof, "securityParam") > 0)
        assert(
          field[Int](proof, "confirmedBlock") - field[Int](proof, "firstSharedBlock") >=
            field[Int](proof, "securityParam") + 1
        )
        assert(c.spent.keys.forall(_.id.hex == field[String](proof, "txId")))
      }
      val accepted = r.stage match
        case Stage.Decode                   => false
        case Stage.Success                  => c.tx.isValid
        case Stage.Scope | Stage.Evaluation => !c.tx.isValid
      assertEquals(r.accepted, accepted)
      assertEquals(
        c.tx.witnesses.scripts.flatMap(_.plutus),
        Vector(bytes(field[String](r.boundary, "script")))
      )
      val phase1 = TxChecks.scriptsWellFormed(c.params, c.tx, TestLedger.env)
      r.stage match
        case Stage.Decode => assertEquals(phase1, Left(malformed(c)))
        case stage =>
          assertEquals(phase1, Right(()))
          val outcome = Phase2.evaluate(
            c.tx,
            c.params,
            c.tx.witnesses.scriptsByHash,
            c.spent,
            c.references,
            c.posix,
            ScalusEvaluator.default
          )
          (stage, outcome) match
            case (Stage.Success, Right(Phase2.Outcome.Passed(_))) => ()
            case (Stage.Scope, Right(Phase2.Outcome.Failed(_, failures))) =>
              val index = failures.head.reason match
                case EvalFailure.OpenTerm(value) => value
                case other                       => fail(s"not an open term: $other")
              assert(
                field[Vector[String]](r.boundary, "sourceIndices").map(BigInt(_)).contains(index)
              )
            case (Stage.Evaluation, Right(Phase2.Outcome.Failed(_, failures))) =>
              assert(failures.toVector.forall(_.reason.isInstanceOf[EvalFailure.Script]))
            case other => fail(s"not the reference evaluation stage: $other")
      val spent = obj(r.boundary, "spentAfter")
      assertEquals(field[Boolean](spent, "funding"), r.accepted && c.tx.isValid)
      assertEquals(field[Boolean](spent, "collateral"), r.accepted && !c.tx.isValid)
    }

    test(s"${c.name}: LEDGER admission and both input roles match the reference") {
      val nes = Build.funded(Build.nes[Conway](5, c.params), r.allInputs)
      TestLedger.conwayLedger(nes, c.tx, TestLedger.env) match
        case Right(after) =>
          assert(r.accepted, "accepted a transaction the reference refused")
          disposition(r, after.state.utxo.utxo.utxo)
        case Left(failure) =>
          assert(!r.accepted, s"refused a reference-accepted transaction: $failure")
          refusal(r, failure)
    }

    test(s"${c.name}: BBODY admission and both input roles match the reference") {
      val nes = Build.funded(Build.nes[Conway](5, c.params), r.allInputs)
      val result = Sts(Bbody)(
        TestLedger.globals,
        BbodyEnv(TestLedger.env, c.params, Coin.zero),
        BbodyState(Build.counted(nes), BlocksMade.empty),
        BbodySignal(Vector(c.tx), issuer, commitment, commitment)
      )
      result match
        case Right(after) =>
          assert(r.accepted, "accepted a block transaction the reference refused")
          disposition(r, after.ledger.state.utxo.utxo.utxo)
        case Left(BlockFailure.Tx(failure)) =>
          assert(!r.accepted, s"refused a reference-accepted block transaction: $failure")
          refusal(r, failure)
        case Left(other) => fail(s"unexpected block refusal: $other")
    }
  }
