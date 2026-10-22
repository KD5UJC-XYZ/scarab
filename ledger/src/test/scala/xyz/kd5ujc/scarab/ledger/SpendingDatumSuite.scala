// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage, ScalusEvaluator}

/** Actual reference submissions bind phase-1 datum admission to the independently usable context
  * translation: getSpendingDatum returns Maybe (Alonzo/UTxO.hs:229-240), and Nothing selects two
  * legacy arguments (Alonzo/Plutus/TxInfo.hs:184-194) or V3 SpendingScript ... Nothing.
  */
class SpendingDatumSuite extends FunSuite:
  private enum Mode(val wire: String):
    case Missing extends Mode("missing-witness")
    case Supplied extends Mode("supplied-witness")
    case None extends Mode("no-datum")

  private def field[A: Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(error => fail(error.message), identity)

  private final case class Recorded(json: Json):
    val c = PlutusCase.fromJson(json)
    val datum = field[Json](json, "datumCase")
    val mode =
      Mode.values.find(_.wire == field[String](datum, "mode")).getOrElse(fail("datum mode"))
    val language = PlutusLanguage.values
      .find(_.id + 1 == field[Int](datum, "language"))
      .getOrElse(fail("datum language"))
    val accepted = field[Boolean](field[Json](json, "reference"), "accepted")
    val spent = c.tx.body.inputs.map(c.spent)
    val scripts = c.tx.witnesses.scriptsByHash
    val needed = TxChecks.scriptsNeeded(c.tx.body, spent).values.flatten.toSet
    val redeemer = c.tx.witnesses.redeemers.headOption.getOrElse(fail("no spending redeemer"))
    val purpose = Purpose
      .resolve(c.tx.body, c.spent, redeemer.tag, redeemer.index)
      .getOrElse(fail("unresolved spending purpose"))
    val allInputs = c.spent ++ c.collateral

    val phase1: Option[UtxowFailure] = mode match
      case Mode.Missing =>
        val hashes = c.spent.values
          .flatMap(_.datum match
            case Datum.Hash(hash) => Some(hash)
            case _                => Option.empty[Bytes])
          .toSet
        Some(UtxowFailure.MissingRequiredDatums(hashes, Set.empty))
      case Mode.None if language != PlutusLanguage.V3 =>
        purpose match
          case Purpose.Spending(_, input, _) =>
            Some(UtxowFailure.UnspendableUTxONoDatumHash(Set(input)))
          case _ => fail("not spending")
      case _ => None

  private val cases =
    parse(Files.readString(Path.of("fixtures/reference/spending-datum/conway-pv10.json")))
      .fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .map(Recorded(_))

  test("actual reference coverage includes every language, datum mode and validity flag") {
    assertEquals(cases.size, 18)
    assertEquals(
      cases.map(r => (r.language, r.mode, r.c.tx.isValid)).toSet,
      (for l <- PlutusLanguage.values; m <- Mode.values; flag <- Vector(true, false)
      yield (l, m, flag)).toSet
    )
  }

  private val issuer = KeyHash
    .fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte)))
    .fold(error => fail(error.toString), identity)
  private val commitment = BodyCommitment(100, Bytes.fromArray(Array.fill(32)(1.toByte)))

  private def refusal(r: Recorded, actual: LedgerFailure): Unit = r.phase1 match
    case Some(expected) =>
      assertEquals(actual, LedgerFailure.Rejected(r.c.tx.body.id, PredicateFailure.utxow(expected)))
    case None =>
      actual match
        case LedgerFailure.Rejected(
              _,
              PredicateFailure.Utxow(
                UtxowFailure.Utxo(
                  UtxoFailure.Utxos(
                    UtxosFailure.ValidationTagMismatch(false, None)
                  )
                )
              )
            ) =>
          ()
        case other => fail(s"not the reference validity-tag refusal: $other")

  private def disposition(r: Recorded, after: Utxo): Unit =
    r.c.spent.keys.foreach(in => assertEquals(!after.contains(in), r.accepted))
    r.c.collateral.keys.foreach(in => assert(after.contains(in)))

  cases.foreach { r =>
    val c = r.c
    test(s"${c.name}: original signed bytes, optional datum and phase-1 admission") {
      val original = "84" + field[String](r.json, "body") + field[String](r.json, "witnesses") +
        (if c.tx.isValid then "f5" else "f4") + "f6"
      assertEquals(field[String](r.datum, "transaction"), original)
      assertEquals(field[String](r.json, "txId"), c.tx.body.id.hex)
      assertEquals(
        c.tx.witnesses.scripts.flatMap(_.plutus).map(_.hex),
        Vector(field[String](r.datum, "script"))
      )
      val env = ScriptContexts.Environment(10, c.spent, c.references, c.posix)
      val info = ScriptContexts.txInfo(r.language, c.tx, env).fold(e => fail(e.message), identity)
      val args = ScriptContexts
        .arguments(r.language, info, c.tx, r.redeemer, r.purpose, 10)
        .fold(e => fail(e.message), identity)
      val datum = Option.when(r.mode == Mode.Supplied)(PlutusData.I(42))
      if r.language == PlutusLanguage.V3 then
        args match
          case Vector(
                PlutusData.Constr(_, Vector(_, _, PlutusData.Constr(tag, Vector(_, found))))
              ) =>
            assertEquals(tag, BigInt(1))
            assertEquals(found, PlutusData.maybe(datum))
          case other => fail(s"not a V3 spending script info: $other")
      else
        assertEquals(args.size, if datum.isDefined then 3 else 2)
        datum.foreach(d => assertEquals(args.headOption, Some(d)))
      assertEquals(
        Phase2
          .evaluate(
            c.tx,
            c.params,
            r.scripts,
            c.spent,
            c.references,
            c.posix,
            ScalusEvaluator.default
          )
          .map {
            case Phase2.Outcome.Passed(report) => report.scripts;
            case other                         => fail(s"not passed: $other")
          },
        Right(1)
      )
      assertEquals(
        TxChecks.alonzoChecks(
          c.params,
          c.tx,
          r.spent,
          c.references.values.toVector,
          r.scripts,
          r.needed
        ),
        r.phase1
          .toLeft(())
          .left
          .map(e => LedgerFailure.Rejected(c.tx.body.id, PredicateFailure.utxow(e)))
      )
      // The source's required-hash check is language- and flag-independent across supported eras.
      if r.mode == Mode.Missing then
        val protocols = r.language match
          case PlutusLanguage.V1 => 5 to 11
          case PlutusLanguage.V2 => 7 to 11
          case PlutusLanguage.V3 => 9 to 11
        protocols.foreach { protocol =>
          assertEquals(
            TxChecks.alonzoChecks(
              c.params.copy(protocolMajor = protocol),
              c.tx,
              r.spent,
              c.references.values.toVector,
              r.scripts,
              r.needed
            ),
            r.phase1
              .toLeft(())
              .left
              .map(e => LedgerFailure.Rejected(c.tx.body.id, PredicateFailure.utxow(e)))
          )
        }
    }

    test(s"${c.name}: complete LEDGER and BBODY verdicts preserve funding and collateral") {
      def nes = Build.funded(Build.nes[Conway](5, c.params), r.allInputs)
      TestLedger.conwayLedger(nes, c.tx, TestLedger.env) match
        case Right(after) => assert(r.accepted); disposition(r, after.state.utxo.utxo.utxo)
        case Left(error)  => assert(!r.accepted); refusal(r, error)
      Sts(Bbody)(
        TestLedger.globals,
        BbodyEnv(TestLedger.env, c.params, Coin.zero),
        BbodyState(Build.counted(nes), BlocksMade.empty),
        BbodySignal(Vector(c.tx), issuer, commitment, commitment)
      ) match
        case Right(after) => assert(r.accepted); disposition(r, after.ledger.state.utxo.utxo.utxo)
        case Left(BlockFailure.Tx(error)) => assert(!r.accepted); refusal(r, error)
        case Left(other)                  => fail(s"unexpected block refusal: $other")
    }
  }
