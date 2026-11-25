// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage, ScarabEvaluator}

/** Actual reference submissions bind phase-1 datum admission to the independently usable context
  * translation: getSpendingDatum returns Maybe (Alonzo/UTxO.hs:229-240, cardano-ledger f649f975),
  * and Nothing selects two legacy arguments (Alonzo/Plutus/TxInfo.hs:184-194) or V3 SpendingScript
  * ... Nothing.
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
    val spent = c.tx.body.inputs.map(in => in -> c.spent(in)).toMap
    val scripts = c.tx.witnesses.scriptsByHash
    val needed =
      TxChecks.scriptsNeeded(c.tx.body, spent)(using
        TestLedger.usualEra(c.params.protocolMajor.toInt)
      )
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
      (for
        l <- PlutusLanguage.values.filterNot(_ == PlutusLanguage.V4)
        m <- Mode.values
        flag <- Vector(true, false)
      yield (l, m, flag)).toSet
    )
  }

  /** The refusal of a case, as Conway's era LEDGER gives it (alone, or in a block's LEDGERS): its
    * failures in the reference's order, the recorded phase-1 failure or else the validation tag's.
    */
  private def eraRefusal(r: Recorded, failures: Vector[era.rules.LedgerPredFailure[Conway]]): Unit =
    import era.rules.{
      ConwayLedgerPredFailure as L,
      ConwayUtxoPredFailure as U,
      ConwayUtxosPredFailure as S,
      ConwayUtxowPredFailure as W,
      TagMismatchDescription
    }
    (r.phase1, failures) match
      case (
            Some(UtxowFailure.MissingRequiredDatums(hashes, received)),
            Vector(L.ConwayUtxowFailure(W.MissingRequiredDatums(missing, supplied)))
          ) =>
        assertEquals((missing.toSortedSet.toSet, supplied), (hashes, received))
      case (
            Some(UtxowFailure.UnspendableUTxONoDatumHash(inputs)),
            Vector(L.ConwayUtxowFailure(W.UnspendableUTxONoDatumHash(unspendable)))
          ) =>
        assertEquals(unspendable.toSortedSet.toSet, inputs)
      case (
            None,
            Vector(
              L.ConwayUtxowFailure(
                W.UtxoFailure(
                  U.UtxosFailure(
                    S.ValidationTagMismatch(
                      era.IsPhase2Valid.Phase2Invalid,
                      TagMismatchDescription.PassedUnexpectedly
                    )
                  )
                )
              )
            )
          ) =>
        ()
      case (expected, other) => fail(s"not the reference's refusal ($expected): $other")

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
      val env =
        ScriptContexts.Environment(TestLedger.usualEra(10), 10, c.spent, c.references, c.posix)
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
          .evaluate[xyz.kd5ujc.scarab.ledger.state.Conway](
            c.tx,
            c.params,
            r.scripts,
            c.spent,
            c.references,
            c.posix,
            ScarabEvaluator.default
          )
          .map {
            case Phase2.Outcome.Passed(report) => report.scripts;
            case other                         => fail(s"not passed: $other")
          },
        Right(1)
      )
      assertEquals(
        TestLedger.verdict(
          c.tx.body.id,
          rules.Test.all(
            TxChecks.datumChecks(c.tx, r.spent, c.references.values.toVector, r.scripts)(using
              TestLedger.usualEra((c.params).protocolMajor.toInt)
            ),
            TxChecks.redeemerChecks(c.tx, r.scripts, r.needed)
          )
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
          case PlutusLanguage.V4 => fail("no PlutusV4 case before Dijkstra")
        protocols.foreach { protocol =>
          assertEquals(
            TestLedger.verdict(
              c.tx.body.id,
              rules.Test.all(
                TxChecks.datumChecks(
                  c.tx,
                  r.spent,
                  c.references.values.toVector,
                  r.scripts
                )(using
                  TestLedger.usualEra((c.params.copy(protocolMajor = protocol)).protocolMajor.toInt)
                ),
                TxChecks.redeemerChecks(c.tx, r.scripts, r.needed)
              )
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
      era.conway.ConwayTestKit.eraVerdict(
        nes,
        era.conway.ConwayTestKit.typed(c.tx),
        TestLedger.env
      ) match
        case Right(after)   => assert(r.accepted); disposition(r, after.utxo.utxo.utxo)
        case Left(failures) => assert(!r.accepted); eraRefusal(r, failures)
      // a block of the transaction on Conway's era run, as the node applies Conway blocks
      era.conway.ConwayTestKit.eraBbody(nes, c.tx, TestLedger.env) match
        case Right(after) => assert(r.accepted); disposition(r, after.utxo.utxo.utxo)
        case Left(failures) =>
          assert(!r.accepted); eraRefusal(r, era.conway.ConwayTestKit.ledgersFailures(failures))
    }
  }
