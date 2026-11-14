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
      case LedgerRulesVerdict.Refused(fs, _, FailureContext(true, _)) => fs
      case other => fail(s"not refused: $other")
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

  private def refusalNames(id: String): Vector[ReportName] =
    val (state, tx) = Unregistered(id)
    LedgerRulesCase(id, "fixture.json", judge(fixture(state, tx))).names

  private val unregisteredNames = Set(
    ReportName.StakeKeyNotRegistered,
    ReportName.StakeCredentialInvalidPoolDelegation,
    ReportName.StakeCredentialInvalidVoteDelegation
  )

  test("an unregistered credential's refusal is named by the certificate that refused it") {
    val expected = Vector(
      "00290" -> ReportName.StakeKeyNotRegistered,
      "00291" -> ReportName.StakeKeyNotRegistered,
      "00286" -> ReportName.StakeCredentialInvalidPoolDelegation,
      "00287" -> ReportName.StakeCredentialInvalidVoteDelegation,
      "00288" -> ReportName.StakeCredentialInvalidVoteDelegation,
      "00100" -> ReportName.StakeCredentialInvalidPoolDelegation
    )
    expected.foreach { (id, name) =>
      val names = refusalNames(id)
      assertEquals(names.filter(unregisteredNames), Vector(name), s"$id: $names")
    }
  }

  test("a certificate's kind: unregistrations and delegations, not their registering forms") {
    val c = Credential.Key(Hash28)
    val pool = Some(Hash28)
    val drep = Some(DRep.AlwaysAbstain)
    assertEquals(
      CertificateKind.of(Certificate.Unregister(c, None)),
      Some(CertificateKind.Unregistration)
    )
    assertEquals(
      CertificateKind.of(Certificate.Unregister(c, Some(BigInt(2)))),
      Some(CertificateKind.Unregistration)
    )
    assertEquals(
      CertificateKind.of(Certificate.Delegate(c, pool, None, None)),
      Some(CertificateKind.PoolDelegation)
    )
    assertEquals(
      CertificateKind.of(Certificate.Delegate(c, None, drep, None)),
      Some(CertificateKind.VoteDelegation)
    )
    assertEquals(
      CertificateKind.of(Certificate.Delegate(c, pool, drep, None)),
      Some(CertificateKind.PoolAndVoteDelegation)
    )
    assertEquals(CertificateKind.of(Certificate.Delegate(c, pool, drep, Some(BigInt(2)))), None)
    assertEquals(CertificateKind.of(Certificate.Register(c, None)), None)
    assertEquals(
      CertificateKind.PoolAndVoteDelegation.name,
      ReportName.StakeCredentialInvalidVoteDelegation
    )
  }

  test("an unregistered credential's refusal with no attributed certificate has no name") {
    val failure = PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(Credential.Key(Hash28)))
    assertEquals(LedgerRulesNames.of(failure, NoContext), Vector.empty)
    assertEquals(
      LedgerRulesNames.of(
        failure,
        FailureContext(true, Map(Credential.Key(Hash28) -> CertificateKind.VoteDelegation))
      ),
      Vector(ReportName.StakeCredentialInvalidVoteDelegation)
    )
  }

  test("a vote the mempool alone refuses is judged by LEDGER, which runs the scripts") {
    val (state, tx) = UnelectedVote
    val admitted = judge(fixture(state, tx))
    admitted match
      case LedgerRulesVerdict.Refused(Vector(), Vector(dropped), _) =>
        assert(LedgerRulesNames.mempoolOnly(dropped), s"dropped: $dropped")
      case other => fail(s"not judged by LEDGER: $other")
    assertEquals(result(admitted), Some("pass"))
    // the same transaction claiming that its scripts fail: it has none, so they pass, and the
    // claim is refused (`ValidationTagMismatch`), which only the evaluation of phase two finds
    val claimedInvalid = judge(fixture(state, tx.stripSuffix("f5f6") + "f4f6"))
    assertEquals(result(claimedInvalid), Some("ValidationTagMismatch"))
  }

  test("the harness's renamings: inputs, collect errors, refunds and unelected voters") {
    val spent = PredicateFailure.Mempool(MempoolProblem.AllInputsSpent)
    assertEquals(
      LedgerRulesNames.of(spent, NoContext.copy(hasInputs = false)),
      Vector(ReportName.InputSetEmptyUTxO)
    )
    val noModel = PredicateFailure.utxo(
      UtxoFailure.Utxos(
        UtxosFailure.CollectErrors(NonEmptyVector.one(CollectError.NoCostModel(PlutusLanguage.V3)))
      )
    )
    assertEquals(
      LedgerRulesNames.of(noModel, NoContext),
      Vector(ReportName.MissingCostModel)
    )
    val refund =
      PredicateFailure.govCert(GovCertFailure.DRepIncorrectRefund(Mismatch(BigInt(1), BigInt(2))))
    assertEquals(
      LedgerRulesNames.of(refund, NoContext),
      Vector(ReportName.IncorrectDepositDRep)
    )
    val unelected = PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(Vector.empty))
    assertEquals(
      LedgerRulesNames.of(unelected, NoContext),
      Vector(ReportName.VotersDoNotExist)
    )
    val witnesses = PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(Set(Hash28)))
    assertEquals(
      LedgerRulesNames.of(witnesses, NoContext),
      Vector(ReportName.MissingVerificationKeyWitnessesUTXOW)
    )
  }

  test(
    "the result is the first failure the schema names, and LEDGER's acceptance after a refusal only the mempool makes is a pass"
  ) {
    val unnamed = PredicateFailure.utxow(UtxowFailure.InvalidMetadata)
    val fee = PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(BigInt(1), BigInt(2))))
    val value = PredicateFailure.utxo(UtxoFailure.InputSetEmpty)
    def refused(fs: PredicateFailure*) =
      result(LedgerRulesVerdict.Refused(fs.toVector, Vector.empty, NoContext))
    assertEquals(refused(unnamed, fee, value), Some("FeeTooSmallUTxO"))
    assertEquals(refused(unnamed), None)
    val unelected = PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(Vector.empty))
    assert(LedgerRulesNames.mempoolOnly(unelected))
    assertEquals(
      result(LedgerRulesVerdict.Refused(Vector.empty, Vector(unelected), NoContext)),
      Some("pass")
    )
  }

  private val fee = PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(BigInt(1), BigInt(2))))
  private val noInputs = PredicateFailure.utxo(UtxoFailure.InputSetEmpty)

  private def selected(
      verdict: LedgerRulesVerdict,
      expected: ReportName,
      selection: Selection = Selection.ExpectedInFailures
  ): LedgerRulesCase =
    LedgerRulesCase("00001", "a.json", verdict, Some(expected), selection)

  private val twoFailures =
    LedgerRulesVerdict.Refused(Vector(fee, noInputs), Vector.empty, NoContext)

  test("a refusal names the expected failure when Scarab raised it among others") {
    val c = selected(twoFailures, ReportName.InputSetEmptyUTxO)
    assertEquals(c.result, Some("InputSetEmptyUTxO"))
    assertEquals(c.chosen.map(_._2), Some(Selection.ExpectedInFailures))
    assertEquals(c.names, Vector(ReportName.FeeTooSmallUTxO, ReportName.InputSetEmptyUTxO))
    val first = selected(twoFailures, ReportName.InputSetEmptyUTxO, Selection.First)
    assertEquals(first.result, Some("FeeTooSmallUTxO"))
    assertEquals(first.chosen.map(_._2), Some(Selection.First))
  }

  test("a refusal that did not raise the expected name, or raised it first, names its first") {
    val notRaised = selected(twoFailures, ReportName.MaxTxSizeUTxO)
    assertEquals(notRaised.result, Some("FeeTooSmallUTxO"))
    assertEquals(notRaised.chosen.map(_._2), Some(Selection.First))
    val raisedFirst = selected(twoFailures, ReportName.FeeTooSmallUTxO)
    assertEquals(raisedFirst.result, Some("FeeTooSmallUTxO"))
    assertEquals(raisedFirst.chosen.map(_._2), Some(Selection.First))
  }

  test("a pass expected to fail is a pass") {
    assertEquals(
      selected(LedgerRulesVerdict.Accepted, ReportName.FeeTooSmallUTxO).result,
      Some("pass")
    )
    val unelected = PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(Vector.empty))
    val admitted = LedgerRulesVerdict.Refused(Vector.empty, Vector(unelected), NoContext)
    assertEquals(selected(admitted, ReportName.VotersDoNotExist).result, Some("pass"))
    assertEquals(selected(admitted, ReportName.VotersDoNotExist).chosen, None)
  }

  test("a fixture's expectation: a pass, a decoding failure or a predicate") {
    def expectation(expected: Json) =
      LedgerRulesFixture.expectation(
        "f.json",
        SimpleTransfer.mapObject(_.add("expected", expected)).noSpaces
      )
    assertEquals(expectation("Pass".asJson), Right(FixtureExpectation.Pass))
    assertEquals(
      expectation(Json.obj("decoding_failure" -> true.asJson)),
      Right(FixtureExpectation.DecodingFailure)
    )
    val predicate =
      Json.obj("predicate" -> "ValidationTagMismatch".asJson, "description" -> "x".asJson)
    assertEquals(
      expectation(predicate).map(_.refusal),
      Right(Some(ReportName.ValidationTagMismatch))
    )
    assertEquals(
      expectation(Json.obj("predicate" -> "PoolMetadataHashTooBig".asJson)).map(_.refusal),
      Right(None)
    )
    assert(expectation(Json.obj()).isLeft)
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
    assertEquals(
      LedgerRulesMain.config(paths).map(_.selection),
      Right(Selection.ExpectedInFailures)
    )
    assertEquals(
      LedgerRulesMain.config(paths ++ List("--select", "first")).map(_.selection),
      Right(Selection.First)
    )
    assertEquals(
      LedgerRulesMain.config(paths ++ List("--select", "last")),
      Left(LedgerRulesMain.Error.Select("last"))
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
  private val NoContext = FailureContext(hasInputs = true, unregistered = Map.empty)

  /** The key hash of Amaru's public test key `dev-42`, and of the cold credential of the committee
    * member of fixture `00154`, kept apart from the field that names them.
    */
  private val Dev42 = "93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8"
  private val ColdMember = "ecc3093b061a9ed426cd9956161fc48d4ed1ac58dc4aaed7e1ce1830"

  /** The simple transfer with another initial state and transaction. */
  private def fixture(state: String, tx: String): Json =
    SimpleTransfer.mapObject(o =>
      o.add("initial_state", io.circe.parser.parse(state).getOrElse(Json.Null))
        .add("transaction", tx.asJson)
    )

  private val Dev42Output =
    "a200581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a004c4b40"

  private def oneInput(input: String, more: String = ""): String =
    s"""{"utxo": [{"input": "$input", "output": "$Dev42Output"}]$more}"""

  /** Fixtures of the corpus (same provenance as [[SimpleTransfer]]) whose transactions refuse an
    * unregistered credential: `00290` and `00291` deregister it (Shelley's and Conway's
    * certificate), `00286` delegates it to a pool, `00287` its vote, `00288` both, and `00100`
    * deregisters a registered one and then delegates it to a pool. Their initial state and
    * transaction, by id.
    */
  private val Unregistered: Map[String, (String, String)] = Map(
    "00290" -> (
      oneInput("825820bbc20ff08205e269352834e5412708a2a452b287bd00f084aef8421760bc8d5200"),
      "84a400d9010281825820bbc20ff08205e269352834e5412708a2a452b287bd00f084aef8421760bc8d52000181a2" +
        "00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a00493e00021a00030d4004d9" +
        "01028182018200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8a100d9010281825820" +
        "2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db125840c2d76bb6c87295ba5be83e41" +
        "2d5a0216684b3d51c1c5f53d20e0cffd11304248a00a772edae9ad6200a6cef8c1278ca23a50ff204d983ff85b7e" +
        "bfbfe44b3008f5f6"
    ),
    "00291" -> (
      oneInput("8258208b68089fd4e5136a3a73b7983089899e55d855660f48710c035e7a569071f6aa00"),
      "84a400d90102818258208b68089fd4e5136a3a73b7983089899e55d855660f48710c035e7a569071f6aa000181a2" +
        "00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a00493e00021a00030d4004d9" +
        "01028183088200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd81a001e8480a100d901" +
        "02818258202152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db125840780f42851f2efc" +
        "7e74f5e41f9f363d134c193cf2d3c630189f631901cfa3068471db5cc692e47d72c9bbd24d0928c064a4bb378f5d" +
        "af6e552212d21fd095ff00f5f6"
    ),
    "00286" -> (
      oneInput(
        "825820d578dd54ab2d4821f55d789d2026cae282eeed91abe253e7444c46e6f567abd100",
        """, "pools": ["4bbe2e054fb148c33693a11ea5e21568a46c74a27d3d2ab92024ccd3"]"""
      ),
      "84a400d9010281825820d578dd54ab2d4821f55d789d2026cae282eeed91abe253e7444c46e6f567abd1000181a2" +
        "00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a00493e00021a00030d4004d9" +
        "01028183028200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8581c4bbe2e054fb148" +
        "c33693a11ea5e21568a46c74a27d3d2ab92024ccd3a100d90102818258202152f8d19b791d24453242e15f2eab6c" +
        "b7cffa7b6a5ed30097960e069881db125840f98e4dcc995c3d2eac40c80a97df323d1586bacb8a48e249469a0049" +
        "4a2980c6fb77af8c9c732d559968f18b532b2eda9b026dffc6b33c733670176ecb709709f5f6"
    ),
    "00287" -> (
      oneInput("825820efb5b716fc3e32e5b59cd2e5c23f9575195eb171da2348d86ebef6ec9c2a73dc00"),
      "84a400d9010281825820efb5b716fc3e32e5b59cd2e5c23f9575195eb171da2348d86ebef6ec9c2a73dc000181a2" +
        "00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a00493e00021a00030d4004d9" +
        "01028183098200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd88102a100d901028182" +
        "58202152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12584025fee24bb67b7078f2f7" +
        "64e4d76f9cc79a63eeeb6009bfcf76316ea26b6b3a5794dbe23f6371969d348841d95e1f38f8d9d6a23794b3aef9" +
        "3164c09813f18808f5f6"
    ),
    "00288" -> (
      oneInput(
        "82582092e106900d624b3f86b904a0731738b46ac696ccda4970f4fb7728b9cfe7d0f700",
        """, "pools": ["4bbe2e054fb148c33693a11ea5e21568a46c74a27d3d2ab92024ccd3"]"""
      ),
      "84a400d901028182582092e106900d624b3f86b904a0731738b46ac696ccda4970f4fb7728b9cfe7d0f7000181a2" +
        "00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a00493e00021a00030d4004d9" +
        "010281840a8200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8581c4bbe2e054fb148" +
        "c33693a11ea5e21568a46c74a27d3d2ab92024ccd38102a100d90102818258202152f8d19b791d24453242e15f2e" +
        "ab6cb7cffa7b6a5ed30097960e069881db1258409eeddfec522f5cce390302c732306d194cc3e0f2baae0e4b32da" +
        "d5146d63b02b895982885ba01ccfeb99dc79aa60b3763683f705cc768ac0d26605a1dd85180df5f6"
    ),
    "00100" -> (
      """{"utxo": [{"input": "825820827273a8722791f640b59ba20ba9340cb0398535b36e1957ed8e8dde693976d500",""" +
        """ "output": "bf00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a004c4b40ff"}],""" +
        """ "pools": ["deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"],""" +
        s""" "accounts": [{"credential": "8200581c$Dev42",""" +
        """ "deposit": 2000000, "rewards": 0}]}""",
      "84a500d9010281825820827273a8722791f640b59ba20ba9340cb0398535b36e1957ed8e8dde693976d5000181bf" +
        "00581d60a1c146c212acfe02048afbf8d2e5e9c806165934d9935c696f85c3dc011a0067c280ff021a00030d4004" +
        "d901028282018200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd883028200581c93c1" +
        "91b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8581cdeadbeefdeadbeefdeadbeefdeadbeefdead" +
        "beefdeadbeefdeadbeef0f00a100d90102818258202152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097" +
        "960e069881db125840e823f7e5d3af0e0e44b512f197c36a65f4b1e84dd0614eb7fc3baf07aa82b58358ef5549df" +
        "6e1597a55a0a04208b1cf66636ad12daf6c81d6f8ee40ed5bbbf0df5f6"
    )
  )

  /** Fixture `00154` of the corpus (same provenance): a vote by the hot key of a committee member
    * that holds no term, below protocol version 11, so that only the mempool refuses it.
    */
  private val UnelectedVote: (String, String) = (
    oneInput(
      "825820e4ab7bd9de7060f2f81fc907132ce2942081ac10b2b9454d53642eb304a64a3800",
      s""", "committee": [{"cold_credential": "8200581c$ColdMember",""" +
        s""" "status": "8200581c$Dev42"}],""" +
        """ "proposals": [[{"transaction_id": "7e2b9d5f1a3c7e5b9d1f3a5c7e9b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b",""" +
        """ "proposal_index": 0}, "Orphan", 6]]"""
    ),
    "84a500d9010281825820e4ab7bd9de7060f2f81fc907132ce2942081ac10b2b9454d53642eb304a64a38000181a2" +
      "00581d6093c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8011a00493e00021a00030d400f00" +
      "13a18200581c93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8a18258207e2b9d5f1a3c7e5b" +
      "9d1f3a5c7e9b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b008201f6a100d90102818258202152f8d19b791d2445" +
      "3242e15f2eab6cb7cffa7b6a5ed30097960e069881db125840f46d6100d906cb1424b6f9234fd187090bddf85d04" +
      "53f8f09700cbcdf7491556c325dac760dc35efd1dc234e532e357d413aa35a99c1ba2310ed73caa9306008f5f6"
  )
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
