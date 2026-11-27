// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Id
import cats.data.NonEmptyChain
import cats.syntax.all.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Horizon,
  PlutusCase,
  PosixClock,
  TestLedger,
  Tx as FlatTx,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxOut}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayUtxo, UtxoContext, UtxoEnv}
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  Conway,
  ConwayLedgerState,
  ConwayNewEpochState,
  LedgerState,
  ShelleyBasedEra,
  UTxOState
}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ScarabEvaluator, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.{Era as ProtocolEra, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.node.ntc.FlatRecordings

/** Conway's UTXO on the era types whole (`era/rules/Utxo.scala`, `conwayUtxoTransition`,
  * cardano-ledger f649f975 `Conway/Rules/Utxo.hs:211-244`): Babbage's predicates, UTXOS as its
  * sub-rule and the change of the state, with UTXOW and LEDGER over it, held to the reference's
  * recorded verdicts and to the flat rules' recorded judgments.
  *
  * The cases are the 70 Conway transactions the reference node was given that `EraUtxosSuite`
  * judges UTXOS on (the Plutus V1 context submissions at protocol 9 and 10, the context-error and
  * the Plutus boundary submissions), each as submitted and with its flag the other way, under each
  * reference build and each validation policy. Before the flat UTXO and UTXOW were deleted, both
  * rules ran here in one judgment from the same state, and gave the same failures in the same
  * order, or the same state, with UTXOS's events saying what the flat counters counted; the era
  * rules' judgments were recorded after the two were found to agree
  * (`fixtures/conway-flat-refusals/utxo-judgments.jsonl`, under `era-utxo/`), a refusal by the
  * bytes of its failures, an admission by the state it leaves and its counters. LEDGER whole on the
  * same cases is held to the flat LEDGER's judgments, recorded before it was deleted
  * (`ledger-judgments.jsonl`).
  */
class EraUtxoSuite extends munit.FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val t = EraTx.conway

  private val files = Vector(
    "fixtures/reference/v1-context/conway-pv9.json",
    "fixtures/reference/v1-context/conway-pv10.json",
    "fixtures/reference/context-errors/submissions/conway-pv10.json",
    "fixtures/reference/plutus-boundaries/transactions/conway-pv10.json"
  )

  private final case class Case(
      name: String,
      flat: FlatTx,
      typed: ConwayTx,
      utxo: Map[TxIn, FlatTxOut],
      eraUtxo: UTxO[Conway],
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      pp: PParams[Conway],
      accepted: Boolean,
      clock: PosixClock
  )

  private val env: TxEnv = TestLedger.env

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def caseOf(name: String, c: PlutusCase, valid: Boolean, accepted: Boolean): Case =
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"$name: no witness bytes"))
    val parts = Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, valid)
    val utxo = c.spent ++ c.references ++ c.collateral
    Case(
      name,
      right(s"$name flat")(FlatTx.fromBlock(parts, ProtocolEra.Conway)),
      right(s"$name typed")(ConwayTx.decode(parts, StateLayout.Node11_1)),
      utxo,
      UTxO[Conway](
        utxo.map((in, out) => in -> right(s"$name output")(ConwayTxOut.fromStored(out)))
      ),
      c.params,
      PParams[Conway](right(s"$name parameters")(ConwayPParams.fromStored(c.params))),
      accepted,
      env.clock.getOrElse(fail("the environment has no clock"))
    )

  private val recorded: Vector[Case] = files.flatMap { file =>
    val path = Path.of(file)
    if !Files.isRegularFile(path) then fail(s"missing committed fixture $file")
    val rows = right(file)(parse(Files.readString(path))).asArray.getOrElse(fail(s"$file"))
    rows.map { j =>
      val c = PlutusCase.fromJson(j)
      val accepted =
        right(s"$file ${c.name}")(j.hcursor.downField("reference").get[Boolean]("accepted"))
      caseOf(s"$file/${c.name}", c, c.tx.isValid, accepted)
    }
  }

  private val flipped: Vector[Case] = recorded.map(c =>
    val witnesses = c.flat.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(c.flat.body.raw, witnesses, c.flat.auxiliary, !c.flat.isValid)
    c.copy(
      name = s"${c.name} (flag flipped)",
      flat = right(s"${c.name} flipped flat")(FlatTx.fromBlock(parts, ProtocolEra.Conway)),
      typed = right(s"${c.name} flipped typed")(ConwayTx.decode(parts, StateLayout.Node11_1))
    )
  )

  private def everyCase: Vector[Case] = recorded ++ flipped

  private def stAnnTx(c: Case): AlonzoStAnnTx[Conway] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](c.clock.posix, c.pp, c.eraUtxo, Map.empty, c.typed)

  /** The ledger state holding the case's outputs, with the instant stake they make. */
  private def ledger(c: Case): LedgerState[Conway] =
    ConwayNewEpochState.ls.get(Build.funded(Build.nes[Conway](5, c.flatPp), c.utxo))

  /** An era history that covers the slot and a stability window past it, so the forecast, which the
    * flat UTXO does not have, passes (`ConwayUtxoChecksSuite` compares it in each branch).
    */
  private def historyAt(tip: BigInt): Horizon =
    val params = Horizon.Params(100, 1000, 30, 30)
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(tip)))

  private val scarab: ScriptEvaluator[Id] = ScarabEvaluator.default

  private def never(name: String): ScriptEvaluator[Id] =
    request =>
      fail(s"$name: evaluated ${request.language} under a policy or judgment that skips it")

  // ---- the era rule ------------------------------------------------------------------------------

  private def eraRun(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Either[EraUtxoRun.Defect, Either[NonEmptyChain[EraUtxoRun.Rec], EraUtxoRun.S]] =
    val ls = ledger(c)
    EraUtxoRun.run(
      UtxoEnv(env.slot, c.pp, ConwayLedgerState.cert.get(ls)),
      stAnnTx(c),
      UtxoContext[Conway](
        Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        historyAt(env.slot),
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      ),
      LedgerState.utxo[Conway].get(ls),
      evaluator
    )

  // ---- what the rule made of a case -------------------------------------------------------------

  /** What a rule made of a case: no verdict, a refusal with its failures in order, or an admission
    * with the state it leaves and the counters of the scripts it ran.
    */
  private enum Outcome[+F]:
    case NoVerdict(reason: EvalFailure.Unsupported)
    case Refused(failures: Vector[F])
    case Admitted(
        state: UTxOState[Conway],
        before: UTxOState[Conway],
        succeeded: Long,
        failed: Long,
        scripts: Long
    )

  /** An outcome's cell: `N` for no verdict, a refusal's cell by `refused`, or an admission's
    * ([[FlatRecordings.utxoAdmitted]]).
    */
  private def cellOf[F](refused: Vector[F] => String)(o: Outcome[F]): String = o match
    case Outcome.NoVerdict(_) => "N"
    case Outcome.Refused(fs)  => refused(fs)
    case Outcome.Admitted(state, before, s, f, scripts) =>
      FlatRecordings.utxoAdmitted(state, before, (s, f, scripts))

  /** The era UTXO's outcome on `c`. */
  private def outcome(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Outcome[rules.ConwayUtxoPredFailure[Conway]] =
    val what = s"${c.name} under ${build.name}, $validation"
    val before = LedgerState.utxo[Conway].get(ledger(c))
    eraRun(c, build, validation, evaluator) match
      case Left(Left(ScriptVerdictUnavailable(_, reason))) => Outcome.NoVerdict(reason)
      case Left(Right(defect))                             => fail(s"$what: era defect $defect")
      case Right(Left(fs)) =>
        val failures = fs.toChain.toVector
        assert(EraUtxoRun.causesHeld(failures, evaluator), s"$what: a cause not the evaluator's")
        Outcome.Refused(failures)
      case Right(Right((state, events))) =>
        val (succeeded, failed, scripts) = EraUtxoRun
          .counters(t.isValid(c.typed) == IsPhase2Valid.Phase2Valid, events)
          .getOrElse(fail(s"$what: events $events"))
        Outcome.Admitted(state, before, succeeded, failed, scripts)

  /** The recorded judgment `id`, failing the test where none was recorded. */
  private def recorded(id: String): Vector[String] =
    FlatRecordings.utxoJudgments.getOrElse(id, fail(s"no recording of $id"))

  /** The judgments of `run` on every case, both flags, by id under `prefix`, with their outcomes
    * under each build, then each policy under it.
    */
  private def everyJudgment[F](prefix: String)(
      run: (Case, ReferenceBuild, TxEnv.Validation) => Outcome[F]
  ): Vector[(String, Vector[Outcome[F]])] =
    everyCase.map(c =>
      s"era-utxo/$prefix/${c.name}" -> (for
        build <- ReferenceBuild.values.toVector
        validation <- TxEnv.Validation.values.toVector
      yield run(c, build, validation))
    )

  // ---- the verdicts ------------------------------------------------------------------------------

  test("the corpus: the 70 submissions, accepted and refused") {
    assertEquals(recorded.size, 70)
    assert(recorded.exists(_.accepted) && recorded.exists(!_.accepted))
  }

  test("as submitted, the era UTXO gives the reference's verdict") {
    val outcomes =
      recorded.map(c => c -> outcome(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, scarab))
    outcomes.foreach { (c, o) =>
      o match
        case Outcome.Admitted(_, _, _, _, _) =>
          assert(c.accepted, s"${c.name}: the reference refused")
        case Outcome.Refused(_)        => assert(!c.accepted, s"${c.name}: the reference accepted")
        case Outcome.NoVerdict(reason) => fail(s"${c.name}: no verdict, $reason")
    }
    // what the corpus reaches through UTXO: UTXOS's collection errors and both mismatches
    def has(p: rules.ConwayUtxosPredFailure[Conway] => Boolean) = outcomes.exists {
      case (_, Outcome.Refused(fs)) =>
        fs.exists {
          case rules.ConwayUtxoPredFailure.UtxosFailure(f) => p(f)
          case _                                           => false
        }
      case _ => false
    }
    import rules.{ConwayUtxosPredFailure, TagMismatchDescription}
    assert(has { case ConwayUtxosPredFailure.CollectErrors(_) => true; case _ => false })
    assert(has {
      case ConwayUtxosPredFailure
            .ValidationTagMismatch(_, TagMismatchDescription.PassedUnexpectedly) =>
        true
      case _ => false
    })
    assert(has {
      case ConwayUtxosPredFailure
            .ValidationTagMismatch(_, TagMismatchDescription.FailedUnexpectedly(_)) =>
        true
      case _ => false
    })
  }

  private lazy val utxoJudged
      : Vector[(String, Vector[Outcome[rules.ConwayUtxoPredFailure[Conway]]])] =
    everyJudgment("utxo")((c, build, validation) => outcome(c, build, validation, scarab))

  test("every case, both flags, each build and each policy: the flat UTXO's recorded outcome") {
    utxoJudged.foreach((id, os) =>
      assertEquals(os.map(cellOf(FlatRecordings.utxoRefused)), recorded(id), id)
    )
    val outcomes = utxoJudged.flatMap(_._2)
    assert(outcomes.exists { case Outcome.Admitted(_, _, _, _, n) => n > 0; case _ => false })
    assert(outcomes.exists { case Outcome.Admitted(_, _, 0, 1, n) => n > 0; case _ => false })
  }

  test(
    "UTXOS's failures, Alonzo's and Conway's, are injected into UTXO's as the embedding wraps them"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.rules.{
      AlonzoUtxosPredFailure,
      ConwayUtxoPredFailure,
      ConwayUtxosPredFailure,
      EmbedUtxosInUtxo,
      InjectUtxoFailure,
      TagMismatchDescription
    }
    val alonzo = AlonzoUtxosPredFailure.ValidationTagMismatch[Conway](
      IsPhase2Valid.Phase2Invalid,
      TagMismatchDescription.PassedUnexpectedly
    )
    val conway = ConwayUtxosPredFailure.ValidationTagMismatch[Conway](
      IsPhase2Valid.Phase2Invalid,
      TagMismatchDescription.PassedUnexpectedly
    )
    val wrapped: ConwayUtxoPredFailure[Conway] = ConwayUtxoPredFailure.UtxosFailure(conway)
    assertEquals(InjectUtxoFailure.conwayAlonzoUtxos.injectFailure(alonzo), wrapped)
    assertEquals(InjectUtxoFailure.conwayUtxos.injectFailure(conway), wrapped)
    assertEquals(EmbedUtxosInUtxo.conway.wrapFailed(conway), wrapped)
  }

  /** Each case with scripts under a maximum transaction size of one byte, and its outcome. */
  private lazy val tooLarge: Vector[(String, Outcome[rules.ConwayUtxoPredFailure[Conway]])] =
    everyCase.filter(_.flat.witnesses.redeemers.nonEmpty).map { c0 =>
      val flatPp = c0.flatPp.copy(maxTxSize = 1)
      val c = c0.copy(
        flatPp = flatPp,
        pp = PParams[Conway](right(c0.name)(ConwayPParams.fromStored(flatPp)))
      )
      s"era-utxo/max-tx-size/${c.name}" ->
        outcome(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, never(c.name))
    }

  test("a failure of UTXO's predicates is recorded before UTXOS, which then evaluates nothing") {
    // a maximum transaction size of one byte fails `validateMaxTxSizeUTxO` on every case; UTXOS still
    // collects the scripts but evaluates none (`whenFailureFree`), and the evaluator is not asked
    assert(tooLarge.nonEmpty)
    tooLarge.foreach { (id, o) =>
      assertEquals(Vector(cellOf(FlatRecordings.utxoRefused)(o)), recorded(id), id)
      o match
        case Outcome.Refused(fs) =>
          assert(
            fs.exists {
              case rules.ConwayUtxoPredFailure.MaxTxSizeUTxO(m) => m.expected == 1
              case _                                            => false
            },
            s"$id: $fs"
          )
        case other => fail(s"$id: $other")
    }
  }

  // ---- UTXOW whole: its checks, then UTXO with UTXOS -------------------------------------------

  /** `c` with its body's script integrity hash (key 11) taken out, read again by both paths. */
  private def withoutIntegrity(c: Case): Case =
    val body = right(s"${c.name} body")(xyz.kd5ujc.scarab.codec.Cbor.decode(c.flat.body.raw))
    val edited = body.value match
      case xyz.kd5ujc.scarab.codec.Value.Map(fields) =>
        xyz.kd5ujc.scarab.codec.Value.Map(
          fields.filterNot((k, _) => k.value == xyz.kd5ujc.scarab.codec.Value.UInt(11))
        )
      case other => fail(s"${c.name}: a body that is not a map: $other")
    val raw = right(s"${c.name} re-encode")(
      xyz.kd5ujc.scarab.codec.Cbor.encode(edited, xyz.kd5ujc.scarab.codec.Cbor.Limits.Unbounded)
    )
    val witnesses = c.flat.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(raw, witnesses, c.flat.auxiliary, c.flat.isValid)
    c.copy(
      name = s"${c.name} (no integrity hash)",
      flat = right(s"${c.name} flat")(FlatTx.fromBlock(parts, ProtocolEra.Conway)),
      typed = right(s"${c.name} typed")(ConwayTx.decode(parts, StateLayout.Node11_1))
    )

  /** The era UTXOW's outcome on `c`; the counters are those UTXOS's events give, through UTXO's and
    * UTXOW's embeddings.
    */
  private def utxowOutcome(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Outcome[rules.ConwayUtxowPredFailure[Conway]] =
    val what = s"${c.name} under ${build.name}, $validation (UTXOW)"
    val ls = ledger(c)
    val before = LedgerState.utxo[Conway].get(ls)
    EraUtxoRun.Utxow.run(
      UtxoEnv(env.slot, c.pp, ConwayLedgerState.cert.get(ls)),
      stAnnTx(c),
      UtxoContext[Conway](
        Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        historyAt(env.slot),
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      ),
      LedgerState.utxo[Conway].get(ls),
      evaluator,
      env.verdicts,
      env.decoder
    ) match
      case Left(Left(Left(ScriptVerdictUnavailable(_, reason)))) => Outcome.NoVerdict(reason)
      case Left(defect) => fail(s"$what: era defect $defect")
      case Right(Left(fs)) =>
        val failures = fs.toChain.toVector
        assert(
          EraUtxoRun.utxowCausesHeld(failures, evaluator),
          s"$what: a cause not the evaluator's"
        )
        Outcome.Refused(failures)
      case Right(Right((state, events))) =>
        val (succeeded, failed, scripts) = EraUtxoRun.Utxow
          .counters(t.isValid(c.typed) == IsPhase2Valid.Phase2Valid, events)
          .getOrElse(fail(s"$what: events $events"))
        Outcome.Admitted(state, before, succeeded, failed, scripts)

  test("UTXOW whole, as submitted: the reference's verdict") {
    recorded.foreach { c =>
      utxowOutcome(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, scarab) match
        case Outcome.Admitted(_, _, _, _, _) =>
          assert(c.accepted, s"${c.name}: the reference refused")
        case Outcome.Refused(_)        => assert(!c.accepted, s"${c.name}: the reference accepted")
        case Outcome.NoVerdict(reason) => fail(s"${c.name}: no verdict, $reason")
    }
  }

  private lazy val utxowJudged
      : Vector[(String, Vector[Outcome[rules.ConwayUtxowPredFailure[Conway]]])] =
    everyJudgment("utxow")((c, build, validation) => utxowOutcome(c, build, validation, scarab))

  test(
    "UTXOW whole on every case, both flags, each build and each policy: the flat UTXOW's recorded outcome"
  ) {
    utxowJudged.foreach((id, os) =>
      assertEquals(os.map(cellOf(FlatRecordings.utxowRefused)), recorded(id), id)
    )
    val outcomes = utxowJudged.flatMap(_._2)
    assert(outcomes.exists { case Outcome.Admitted(_, _, _, _, n) => n > 0; case _ => false })
    assert(outcomes.exists { case Outcome.Refused(_) => true; case _ => false })
  }

  /** Each accepted case with scripts, its script integrity hash taken out, and its outcome. */
  private lazy val withoutHash: Vector[(String, Outcome[rules.ConwayUtxowPredFailure[Conway]])] =
    recorded.filter(c => c.accepted && c.flat.witnesses.redeemers.nonEmpty).map { c =>
      val edited = withoutIntegrity(c)
      s"era-utxo/utxow-no-integrity/${edited.name}" ->
        utxowOutcome(edited, ReferenceBuild.Release11_1, TxEnv.Validation.All, never(c.name))
    }

  test("a failure of UTXOW alone is recorded, and UTXOS then evaluates nothing") {
    // each accepted case with its script integrity hash taken out: UTXOW's checks of the integrity
    // hash and the signatures (its body changed) fail, UTXO's none, and UTXOS collects the scripts
    // but evaluates none (`whenFailureFree`): the evaluator fails the suite if asked
    assert(withoutHash.nonEmpty)
    withoutHash.foreach { (id, o) =>
      assertEquals(Vector(cellOf(FlatRecordings.utxowRefused)(o)), recorded(id), id)
      o match
        case Outcome.Refused(fs) =>
          assert(
            fs.exists {
              case rules.ConwayUtxowPredFailure.PPViewHashesDontMatch(_) => true
              case _                                                     => false
            } && fs.forall {
              case rules.ConwayUtxowPredFailure.UtxoFailure(_) => false
              case _                                           => true
            },
            s"$id: $fs"
          )
        case other => fail(s"$id: $other")
    }
  }

  test("UTXO's and UTXOS's failures are injected into UTXOW's as the embedding wraps them") {
    import xyz.kd5ujc.scarab.ledger.era.rules.{
      AlonzoUtxoPredFailure,
      ConwayUtxoPredFailure,
      ConwayUtxosPredFailure,
      ConwayUtxowPredFailure,
      EmbedUtxoInUtxow,
      InjectUtxowFailure,
      ShelleyUtxoPredFailure,
      TagMismatchDescription
    }
    val utxos = ConwayUtxosPredFailure.ValidationTagMismatch[Conway](
      IsPhase2Valid.Phase2Invalid,
      TagMismatchDescription.PassedUnexpectedly
    )
    val utxo: ConwayUtxoPredFailure[Conway] = ConwayUtxoPredFailure.UtxosFailure(utxos)
    val wrapped: ConwayUtxowPredFailure[Conway] = ConwayUtxowPredFailure.UtxoFailure(utxo)
    assertEquals(
      summon[InjectUtxowFailure[ConwayUtxosPredFailure[Conway], Conway]].injectFailure(utxos),
      wrapped
    )
    assertEquals(
      summon[InjectUtxowFailure[ConwayUtxoPredFailure[Conway], Conway]].injectFailure(utxo),
      wrapped
    )
    assertEquals(EmbedUtxoInUtxow.conway.wrapFailed(utxo), wrapped)
    val input = AlonzoUtxoPredFailure.InputSetEmptyUTxO[Conway]()
    assertEquals(
      summon[InjectUtxowFailure[AlonzoUtxoPredFailure[Conway], Conway]].injectFailure(input),
      ConwayUtxowPredFailure.UtxoFailure[Conway](ConwayUtxoPredFailure.InputSetEmptyUTxO[Conway]())
    )
    assertEquals(
      InjectUtxowFailure
        .conwayShelleyUtxo(ConwayUtxo.injectShelleyFailure11_2)
        .injectFailure(ShelleyUtxoPredFailure.InputSetEmptyUTxO[Conway]()),
      ConwayUtxowPredFailure.UtxoFailure[Conway](ConwayUtxoPredFailure.InputSetEmptyUTxO[Conway]())
    )
  }

  // ---- LEDGER whole: its checks, CERTS, GOV, then UTXOW ----------------------------------------

  /** What the era LEDGER made of a case, at the root: no verdict, its failures in the reference's
    * order, or the counters the events of an admission give, through UTXO's, UTXOW's and LEDGER's
    * embeddings ([[EraLedgerRun.counters]]), with its judgment's cell of the recording
    * (`ledger-judgments.jsonl`: the flat LEDGER's outcome, `FlatRecordings.refused` and
    * `admitted`).
    */
  private enum Whole:
    case NoVerdict(reason: EvalFailure.Unsupported)
    case Refused(failures: NonEmptyChain[rules.LedgerPredFailure[Conway]], cell: String)
    case Admitted(succeeded: Long, failed: Long, scripts: Long, cell: String)

  /** The era LEDGER on `c`, from the chain that holds its outputs, at the root. */
  private def wholeLedger(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Whole =
    val what = s"${c.name} under ${build.name}, $validation (LEDGER)"
    val nes = Build.funded(Build.nes[Conway](5, c.flatPp), c.utxo)
    val ls = ConwayNewEpochState.ls.get(nes)
    val globals =
      TestLedger.globals.copy(ledger = TestLedger.globals.ledger.copy(referenceBuild = build))
    EraLedgerRun.ledger(
      globals,
      rules.LedgerEnv[Conway](
        env.slot,
        Some(right("epoch")(xyz.kd5ujc.scarab.ledger.state.EpochNo.fromBigInt(env.epoch))),
        xyz.kd5ujc.scarab.ledger.state.TxIx.txIxFromIntegral(0).getOrElse(fail("index 0")),
        c.pp,
        nes.es.chainAccount
      ),
      stAnnTx(c),
      UtxoContext[Conway](
        Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        historyAt(env.slot),
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      ),
      ls,
      evaluator,
      env.verdicts,
      env.decoder
    ) match
      case Left(EraLedgerRun.Defect.Script(ScriptVerdictUnavailable(_, reason))) =>
        Whole.NoVerdict(reason)
      case Left(defect)    => fail(s"$what: era defect $defect")
      case Right(Left(fs)) => Whole.Refused(fs, FlatRecordings.refused(fs.toChain.toVector))
      case Right(Right((state, events))) =>
        val (succeeded, failed, scripts) = EraLedgerRun
          .counters(t.isValid(c.typed) == IsPhase2Valid.Phase2Valid, events)
          .getOrElse(fail(s"$what: events $events"))
        Whole.Admitted(
          succeeded,
          failed,
          scripts,
          FlatRecordings.admitted(state, succeeded, failed, scripts)
        )

  test("LEDGER whole, as submitted: the reference's verdict") {
    recorded.foreach { c =>
      wholeLedger(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, scarab) match
        case Whole.Admitted(_, _, _, _) => assert(c.accepted, s"${c.name}: the reference refused")
        case Whole.Refused(_, _)        => assert(!c.accepted, s"${c.name}: the reference accepted")
        case Whole.NoVerdict(reason)    => fail(s"${c.name}: no verdict, $reason")
    }
  }

  test("LEDGER whole on every case, both flags, each build and each policy: the flat LEDGER's") {
    // the flat LEDGER, before it was deleted, judged each case under each build and policy, and
    // the era LEDGER gave the same failures in the same order, or the same state and the same
    // counters; the era LEDGER's judgments were recorded (`fixtures/conway-flat-refusals`)
    val recording = FlatRecordings.judgments("era-utxo-ledger/")
    val judged = everyCase.map { c =>
      val wholes = for
        build <- ReferenceBuild.values.toVector
        validation <- TxEnv.Validation.values.toVector
      yield wholeLedger(c, build, validation, scarab)
      val cells = wholes.map {
        case Whole.NoVerdict(_)            => "N"
        case Whole.Refused(_, cell)        => cell
        case Whole.Admitted(_, _, _, cell) => cell
      }
      val id = s"era-utxo-ledger/${c.name}"
      assertEquals(cells, recording.getOrElse(id, fail(s"no recording of $id")), c.name)
      id -> wholes
    }
    assertEquals(judged.map(_._1).toSet, recording.keySet, "the cases recorded")
    assertEquals(judged.size, recording.size, "each case once")
    val outcomes = judged.flatMap(_._2)
    // a passing and a failing run of scripts among them
    assert(outcomes.exists { case Whole.Admitted(1, 0, n, _) => n > 0; case _ => false })
    assert(outcomes.exists { case Whole.Admitted(0, 1, n, _) => n > 0; case _ => false })
    assert(outcomes.exists { case Whole.Refused(_, _) => true; case _ => false })
    // a translation's failure among the collections'
    assert(outcomes.exists {
      case Whole.Refused(fs, _) =>
        fs.exists {
          case rules.ConwayLedgerPredFailure.ConwayUtxowFailure(
                rules.ConwayUtxowPredFailure.UtxoFailure(
                  rules.ConwayUtxoPredFailure.UtxosFailure(
                    rules.ConwayUtxosPredFailure.CollectErrors(es)
                  )
                )
              ) =>
            es.exists {
              case CollectError.BadTranslation(_) => true
              case _                              => false
            }
          case _ => false
        }
      case _ => false
    })
  }

  test(
    "the recording of the flat UTXO's and UTXOW's judgments holds this suite's alone, each once"
  ) {
    val ids = (utxoJudged ++ tooLarge).map(_._1) ++ (utxowJudged ++ withoutHash).map(_._1)
    assertEquals(ids.distinct.size, ids.size, "a judgment twice")
    assertEquals(ids.toSet, FlatRecordings.utxoJudgments.keySet.filter(_.startsWith("era-utxo/")))
  }

  test(
    "a refusal's cell holds a failing script's request, not always its cause, which the evaluator's on the request holds"
  ) {
    // the reference's text of a script that ended at an explicit `error` leaves its trace out, so a
    // failure whose cause has another trace has the same cell: the cause is held by the request
    // instead (`EraUtxoRun.causesHeld`), as the flat comparison held it, by its error and trace
    def explicit(f: rules.FailureDescription): Boolean = f match
      case rules.FailureDescription.PlutusFailure(
            EvalFailure.Script(
              xyz.kd5ujc.scarab.plutus.ScriptFailure.Uplc(
                xyz.kd5ujc.scarab.uplc.ledger.EvaluationError.Cek(
                  xyz.kd5ujc.scarab.uplc.cek
                    .CekFailure(xyz.kd5ujc.scarab.uplc.cek.CekError.ErrorTerm, _)
                ),
                _
              )
            ),
            _
          ) =>
        true
      case _ => false
    def traced(f: rules.ConwayUtxoPredFailure[Conway]): rules.ConwayUtxoPredFailure[Conway] =
      f match
        case rules.ConwayUtxoPredFailure.UtxosFailure(
              rules.ConwayUtxosPredFailure.ValidationTagMismatch(
                flag,
                rules.TagMismatchDescription.FailedUnexpectedly(fs)
              )
            ) =>
          rules.ConwayUtxoPredFailure.UtxosFailure(
            rules.ConwayUtxosPredFailure.ValidationTagMismatch(
              flag,
              rules.TagMismatchDescription.FailedUnexpectedly(fs.map {
                case rules.FailureDescription.PlutusFailure(
                      EvalFailure.Script(xyz.kd5ujc.scarab.plutus.ScriptFailure.Uplc(e, logs)),
                      pwc
                    ) =>
                  rules.FailureDescription.PlutusFailure(
                    EvalFailure.Script(
                      xyz.kd5ujc.scarab.plutus.ScriptFailure.Uplc(e, logs :+ "another trace")
                    ),
                    pwc
                  )
                case other => other
              })
            )
          )
        case other => other
    val failures = utxoJudged
      .flatMap(_._2)
      .collectFirst {
        case Outcome.Refused(fs) if fs.exists(f => EraUtxoRun.scriptFailures(f).exists(explicit)) =>
          fs
      }
      .getOrElse(fail("no refusal of a script that ended at an explicit error"))
    val mutant = failures.map(traced)
    assertNotEquals(mutant, failures)
    assertEquals(FlatRecordings.utxoRefused(mutant), FlatRecordings.utxoRefused(failures))
    assert(EraUtxoRun.causesHeld(failures, scarab))
    assert(!EraUtxoRun.causesHeld(mutant, scarab))
  }

  test("the recording holds each prefix's judgments, as many as were recorded") {
    def count(prefix: String) =
      FlatRecordings.utxoJudgments.keySet.count(_.startsWith(s"era-utxo/$prefix/"))
    assertEquals(
      Vector("utxo", "utxow", "max-tx-size", "utxow-no-integrity").map(count),
      Vector(140, 140, 140, 35)
    )
  }
