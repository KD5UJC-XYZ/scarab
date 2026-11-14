// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyVector
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusBackend, PlutusLanguage, PlutusVm}

class LedgerRulesSuite extends FunSuite:
  import LedgerRulesSuite.*

  private val backend = PlutusBackend.of(PlutusVm.Default)

  private def judge(fixture: Json, documents: Map[String, String] = Documents): LedgerRulesVerdict =
    LedgerRulesRun.judge("fixture.json", fixture.noSpaces, documents.get, backend)

  private def result(verdict: LedgerRulesVerdict): Option[String] =
    LedgerRulesCase("00036", "fixture.json", verdict).result

  private def withState(state: Json): Json =
    SimpleTransfer.mapObject(_.add("initial_state", state))

  private def withParams(overrides: (String, Json)*): Json =
    SimpleTransfer.mapObject(
      _.add(
        "protocol_parameters",
        Json.obj("$ref" -> ParamsRef.asJson, "$override" -> Json.obj(overrides*))
      )
    )

  test("the corpus's simple transfer is accepted by the mempool's decoder and rules") {
    val verdict = judge(SimpleTransfer)
    assertEquals(verdict, LedgerRulesVerdict.Accepted)
    assertEquals(result(verdict), Some("pass"))
  }

  test("a transaction none of whose inputs is unspent is BadInputsUTxO, as the harness names it") {
    val verdict = judge(withState(Json.obj("utxo" -> Json.arr())))
    val failures = verdict match
      case LedgerRulesVerdict.Refused(fs, _, true) => fs
      case other                                   => fail(s"not refused: $other")
    assertEquals(failures, Vector(PredicateFailure.Mempool(MempoolProblem.AllInputsSpent)))
    assertEquals(result(verdict), Some("BadInputsUTxO"))
  }

  test("an $override replaces the referenced document's key") {
    val verdict = judge(withParams("max_transaction_size" -> 100.asJson))
    val names = LedgerRulesCase("00036", "fixture.json", verdict).names
    assert(names.contains(ReportName.MaxTxSizeUTxO), s"names: $names")
  }

  test("a fixture that sets a constant of the Conway ledger has no verdict") {
    judge(withParams("max_reference_scripts_size" -> 50.asJson)) match
      case LedgerRulesVerdict.Unjudged(NoVerdict.Fixture(FixtureError.Unsupported(_))) => ()
      case other => fail(s"judged: $other")
  }

  test("a $ref that no documents root holds leaves the fixture without a verdict or a result") {
    val verdict = judge(SimpleTransfer, Documents - ParamsRef)
    assertEquals(
      verdict,
      LedgerRulesVerdict.Unjudged(NoVerdict.Fixture(FixtureError.Reference(ParamsRef)))
    )
    assertEquals(result(verdict), None)
  }

  test("the clock of an open-ended era ends at the start of the epoch after the point's") {
    val context = for
      fixture <- LedgerRulesFixture.read("fixture.json", SimpleTransfer.noSpaces, Documents.get)
      built <- LedgerRulesState.build(fixture, backend)
    yield built
    val clock = context.map(_.env.clock) match
      case Right(Some(c)) => c
      case other          => fail(s"no clock: $other")
    assertEquals(clock.posix(431999), Right(BigInt(431999000)))
    assert(clock.posix(432000).isLeft, "slot 432000 is past the horizon")
  }

  test(
    "the reference's one unregistered-credential failure is first named as the reference names it"
  ) {
    val failure = PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(Credential.Key(Hash28)))
    assertEquals(
      LedgerRulesNames.of(failure, hasInputs = true),
      Vector(
        ReportName.StakeKeyNotRegistered,
        ReportName.StakeCredentialInvalidPoolDelegation,
        ReportName.StakeCredentialInvalidVoteDelegation
      )
    )
  }

  test("the harness's renamings: inputs, collect errors, refunds and unelected voters") {
    val spent = PredicateFailure.Mempool(MempoolProblem.AllInputsSpent)
    assertEquals(
      LedgerRulesNames.of(spent, hasInputs = false),
      Vector(ReportName.InputSetEmptyUTxO)
    )
    val noModel = PredicateFailure.utxo(
      UtxoFailure.Utxos(
        UtxosFailure.CollectErrors(NonEmptyVector.one(CollectError.NoCostModel(PlutusLanguage.V3)))
      )
    )
    assertEquals(
      LedgerRulesNames.of(noModel, hasInputs = true),
      Vector(ReportName.MissingCostModel)
    )
    val refund =
      PredicateFailure.govCert(GovCertFailure.DRepIncorrectRefund(Mismatch(BigInt(1), BigInt(2))))
    assertEquals(
      LedgerRulesNames.of(refund, hasInputs = true),
      Vector(ReportName.IncorrectDepositDRep)
    )
    val unelected = PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(Vector.empty))
    assertEquals(
      LedgerRulesNames.of(unelected, hasInputs = true),
      Vector(ReportName.VotersDoNotExist)
    )
    val witnesses = PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(Set(Hash28)))
    assertEquals(
      LedgerRulesNames.of(witnesses, hasInputs = true),
      Vector(ReportName.MissingVerificationKeyWitnessesUTXOW)
    )
  }

  test(
    "the result is the first failure the schema names, and a mempool-only refusal alone passes"
  ) {
    val unnamed = PredicateFailure.utxow(UtxowFailure.InvalidMetadata)
    val fee = PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(BigInt(1), BigInt(2))))
    val value = PredicateFailure.utxo(UtxoFailure.InputSetEmpty)
    def refused(fs: PredicateFailure*) =
      result(LedgerRulesVerdict.Refused(fs.toVector, Vector.empty, true))
    assertEquals(refused(unnamed, fee, value), Some("FeeTooSmallUTxO"))
    assertEquals(refused(unnamed), None)
    val unelected = PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(Vector.empty))
    assert(LedgerRulesNames.mempoolOnly(unelected))
    assertEquals(
      result(LedgerRulesVerdict.Refused(Vector.empty, Vector(unelected), true)),
      Some("pass")
    )
  }

  test("the report has the schema's fields and leaves out a fixture without a result") {
    val cases = Vector(
      LedgerRulesCase("00001", "a.json", LedgerRulesVerdict.Accepted),
      LedgerRulesCase(
        "00002",
        "b.json",
        LedgerRulesVerdict.Unjudged(NoVerdict.Fixture(FixtureError.Reference("x")))
      ),
      LedgerRulesCase(
        "00003",
        "c.json",
        LedgerRulesVerdict.Malformed(
          TransactionDecodeProblem.Ledger(
            LedgerDecodeError.ArrayLength(DecodeField.Transaction, 4, 2)
          )
        )
      )
    )
    val report = LedgerRulesMain.report(cases, "2026-11-14T00:00:00Z", Some(Commit))
    val c = report.hcursor
    assertEquals(c.get[String]("version"), Right("1"))
    assertEquals(c.get[String]("scenario"), Right("ledger-rules"))
    assertEquals(c.get[String]("dataset_commit"), Right(Commit))
    assertEquals(c.downField("implementation").get[String]("name"), Right("scarab"))
    assertEquals(
      c.get[Vector[Json]]("scenarios"),
      Right(
        Vector(
          Json.obj("id" -> "00001".asJson, "result" -> "pass".asJson),
          Json.obj("id" -> "00003".asJson, "result" -> "DecodingFailure".asJson)
        )
      )
    )
    assertEquals(
      LedgerRulesMain.report(cases, "t", None).hcursor.downField("dataset_commit").succeeded,
      false
    )
  }

  test("the arguments: three paths, known options once, a full commit id") {
    val paths = List("s", "d", "r.json")
    assert(LedgerRulesMain.config(paths).isRight)
    assertEquals(
      LedgerRulesMain.config(paths ++ List("--dataset-commit", "abc")),
      Left(LedgerRulesMain.Error.Commit("abc"))
    )
    assert(LedgerRulesMain.config(paths ++ List("--unknown", "x")).isLeft)
    assert(LedgerRulesMain.config(paths ++ List("--sidecar", "a", "--sidecar", "b")).isLeft)
    assert(LedgerRulesMain.config(List("s", "d")).isLeft)
    assertEquals(
      LedgerRulesMain.config(paths ++ List("--plutus-vm", "scarab")).map(_.vm),
      Right(PlutusVm.Scarab)
    )
  }

  test("the report names are the schema's 80, each once") {
    assertEquals(ReportName.values.length, 80)
    assertEquals(ReportName.values.map(_.toString).distinct.length, 80)
  }

  test("a fixture file's id is its first five digits") {
    assertEquals(LedgerRulesCase.id("00036-pass-simple-transfer.json"), Some("00036"))
    assertEquals(LedgerRulesCase.id("schema.json"), None)
    assertEquals(LedgerRulesCase.id("0003-x.json"), None)
  }

object LedgerRulesSuite:
  private val Hash28 = Bytes(Vector.fill(28)(7.toByte))
  private val Commit = "40ba995081b2b066cab497eade9abfbc0e350970"
  private val ParamsRef = "common/protocol-parameters/preprod-conway-v10.json"
  private val EraRef = "common/era-history/preprod-conway.json"

  /** Fixture `00036-pass-simple-transfer.json` of the ledger-rules corpus (Amaru
    * `crates/amaru-ledger/tests/data/transaction/scenarios` at 40ba9950, as
    * node-diversity-conformance 9dc68a75 publishes it; Apache-2.0, PRAGMA), without its title and
    * description. Its witness is signed with Amaru's public test key `dev-42`.
    */
  private val SimpleTransfer: Json = Json.obj(
    "network" -> "preprod".asJson,
    "era_history" -> Json.obj("$ref" -> EraRef.asJson),
    "protocol_parameters" -> Json.obj("$ref" -> ParamsRef.asJson),
    "initial_state" -> Json.obj(
      "utxo" -> Json.arr(
        Json.obj(
          "input" -> "825820aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa00".asJson,
          "output" -> "a200581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a004c4b40".asJson
        )
      )
    ),
    "point" -> Json.obj("slot" -> 0.asJson, "transaction_index" -> 0.asJson),
    "transaction" -> ("84a400d9010281825820aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa00" +
      "0181a200581d60c3f9920656fdbfe2842e2c5671e0a2b0c1c84c497d42879de5376d7b011a00493e00021a00030d40" +
      "0f00a100d90102818258202152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12584008" +
      "07969bbf5d777f1ce93897e656ab9ddaa9866b42e59e942497d5ce923ce7e182060ab351a6cc7b3711c3065d7c28" +
      "15638144c92d8ec970dbf4580c54059a08f5f6").asJson,
    "expected" -> "Pass".asJson
  )

  private def ratio(n: Int, d: Int): Json =
    Json.obj("numerator" -> n.asJson, "denominator" -> d.asJson)

  /** The corpus's `preprod-conway-v10.json` without its cost models, which no script of the simple
    * transfer needs, and `preprod-conway.json` (same provenance).
    */
  private val Documents: Map[String, String] = Map(
    EraRef -> Json
      .obj(
        "stability_window" -> 129600.asJson,
        "eras" -> Json.arr(
          Json.obj(
            "start" -> Json.obj("time" -> 0.asJson, "slot" -> 0.asJson, "epoch" -> 0.asJson),
            "end" -> Json.Null,
            "params" -> Json.obj(
              "epoch_size_slots" -> 432000.asJson,
              "slot_length" -> 1000.asJson,
              "era_name" -> "Conway".asJson
            )
          )
        )
      )
      .noSpaces,
    ParamsRef -> Json
      .obj(
        "min_fee_coefficient" -> 44.asJson,
        "min_fee_constant" -> 155381.asJson,
        "min_fee_reference_scripts" -> Json.obj(
          "range" -> 25600.asJson,
          "base" -> ratio(15, 1),
          "multiplier" -> ratio(12, 10)
        ),
        "min_utxo_deposit_coefficient" -> 4310.asJson,
        "max_block_body_size" -> 90112.asJson,
        "max_block_header_size" -> 1100.asJson,
        "max_transaction_size" -> 16384.asJson,
        "max_value_size" -> 5000.asJson,
        "max_reference_scripts_size" -> 204800.asJson,
        "stake_credential_deposit" -> 2000000.asJson,
        "stake_pool_deposit" -> 500000000.asJson,
        "stake_pool_retirement_epoch_bound" -> 18.asJson,
        "stake_pool_pledge_influence" -> ratio(3, 10),
        "min_stake_pool_cost" -> 340000000.asJson,
        "desired_number_of_stake_pools" -> 500.asJson,
        "monetary_expansion" -> ratio(3, 1000),
        "treasury_expansion" -> ratio(2, 10),
        "collateral_percentage" -> 150.asJson,
        "max_collateral_inputs" -> 3.asJson,
        "plutus_cost_models" -> Json.obj(),
        "script_execution_prices" -> Json.obj(
          "mem_price" -> ratio(577, 10000),
          "step_price" -> ratio(721, 10000000)
        ),
        "max_execution_units_per_transaction" -> Json.obj(
          "mem" -> 14000000.asJson,
          "steps" -> BigInt("10000000000").asJson
        ),
        "max_execution_units_per_block" -> Json.obj(
          "mem" -> 62000000.asJson,
          "steps" -> BigInt("20000000000").asJson
        ),
        "stake_pool_voting_thresholds" -> Json.obj(
          "motion_no_confidence" -> ratio(51, 100),
          "committee_normal" -> ratio(51, 100),
          "committee_no_confidence" -> ratio(51, 100),
          "hard_fork_initiation" -> ratio(51, 100),
          "security_voting_threshold" -> ratio(51, 100)
        ),
        "delegate_representative_voting_thresholds" -> Json.obj(
          "motion_no_confidence" -> ratio(51, 100),
          "committee_normal" -> ratio(67, 100),
          "committee_no_confidence" -> ratio(67, 100),
          "update_constitution" -> ratio(6, 10),
          "hard_fork_initiation" -> ratio(75, 100),
          "pp_network_group" -> ratio(6, 10),
          "pp_economic_group" -> ratio(67, 100),
          "pp_technical_group" -> ratio(67, 100),
          "pp_governance_group" -> ratio(75, 100),
          "treasury_withdrawal" -> ratio(67, 100)
        ),
        "constitutional_committee_min_size" -> 7.asJson,
        "constitutional_committee_max_term_length" -> 146.asJson,
        "governance_action_lifetime" -> 6.asJson,
        "governance_action_deposit" -> BigInt("100000000000").asJson,
        "delegate_representative_deposit" -> 500000000.asJson,
        "delegate_representative_max_idle_time" -> 20.asJson,
        "version" -> Json.obj("major" -> 10.asJson, "minor" -> 0.asJson)
      )
      .noSpaces
  )
