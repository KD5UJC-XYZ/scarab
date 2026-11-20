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
  LedgerFailure,
  PlutusCase,
  PlutusStats,
  PosixClock,
  PredicateFailure,
  TestLedger,
  Tx as FlatTx,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  Utxo as FlatUtxo,
  UtxoFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxOut}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayUtxo, UtxoContext, UtxoEnv}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Judgment,
  RuleT,
  StAnnTx as FlatStAnnTx,
  Utxo as FlatUtxoRule,
  UtxoEnv as FlatUtxoEnv
}
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

/** Conway's UTXO on the era types whole (`era/rules/Utxo.scala`, `conwayUtxoTransition`,
  * cardano-ledger f649f975 `Conway/Rules/Utxo.hs:211-244`): Babbage's predicates, UTXOS as its
  * sub-rule and the change of the state, against the flat UTXO the node runs (`rules.Utxo`) and the
  * reference's recorded verdicts.
  *
  * The cases are the 70 Conway transactions the reference node was given that `EraUtxosSuite`
  * compares UTXOS on (the Plutus V1 context submissions at protocol 9 and 10, the context-error and
  * the Plutus boundary submissions), each as submitted and with its flag the other way, under each
  * reference build and each validation policy. Both rules run in one judgment from the same state:
  * the failures are the same in the same order, the era's put in the node's flat form
  * (`ConwayUtxoPredFailure.toStored`) where it has one; a script's failure is compared by its error
  * and trace with its request, and a collection's failure by its kind (their contents are
  * `EraPlutusContextSuite` 's), the two the flat form cannot hold. An admitted transaction leaves
  * the same state, and UTXOS's events embedded in UTXO's say what the flat counters count.
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
        0,
        historyAt(env.slot),
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      ),
      LedgerState.utxo[Conway].get(ls),
      evaluator
    )

  // ---- the flat rule -----------------------------------------------------------------------------

  private def flatRun(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Either[
    xyz.kd5ujc.scarab.ledger.LedgerDefect,
    Either[NonEmptyChain[PredicateFailure], Counted[UTxOState[Conway]]]
  ] =
    val ls = ledger(c)
    Judgment.run(FlatUtxoRule, RuleT.Policy.Accumulate)(
      TestLedger.globals,
      FlatUtxoEnv(
        env.copy(
          clock = Some(c.clock),
          evaluator = evaluator,
          validation = validation,
          referenceBuild = build
        ),
        c.flatPp,
        ConwayLedgerState.cert.get(ls)
      ),
      Counted(LedgerState.utxo[Conway].get(ls), PlutusStats.zero),
      FlatStAnnTx(c.flat, FlatUtxo.fromMap(c.utxo))
    )

  // ---- both in one form --------------------------------------------------------------------------

  import EraUtxoRun.Failure

  /** What a rule made of a case: no verdict, a refusal with its failures in order, or an admission
    * with the counters of the scripts it ran.
    */
  private enum Outcome:
    case NoVerdict(reason: EvalFailure.Unsupported)
    case Refused(failures: List[Failure])
    case Admitted(succeeded: Long, failed: Long, scripts: Long)

  /** Both rules on `c`: the same outcome, which is returned, and where the transaction is admitted,
    * the same state.
    */
  private def same(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Outcome =
    val what = s"${c.name} under ${build.name}, $validation"
    val flat = flatRun(c, build, validation, evaluator)
    val era = eraRun(c, build, validation, evaluator)
    val theirs = flat match
      case Left(LedgerFailure.Unavailable(_, _, reason)) => Outcome.NoVerdict(reason)
      case Left(other)                                   => fail(s"$what: flat defect $other")
      case Right(Left(fs)) =>
        Outcome.Refused(fs.toList.map(f => EraUtxoRun.form(f).getOrElse(fail(s"$what: $f"))))
      case Right(Right(counted)) =>
        Outcome.Admitted(counted.plutus.succeeded, counted.plutus.failed, counted.plutus.scripts)
    val ours = era match
      case Left(Left(ScriptVerdictUnavailable(_, reason))) => Outcome.NoVerdict(reason)
      case Left(Right(defect))                             => fail(s"$what: era defect $defect")
      case Right(Left(fs)) =>
        Outcome.Refused(
          fs.toList.map(f => EraUtxoRun.eraForm(f, purposes(c)).getOrElse(fail(s"$what: $f")))
        )
      case Right(Right((state, events))) =>
        val (succeeded, failed, scripts) = EraUtxoRun
          .counters(t.isValid(c.typed) == IsPhase2Valid.Phase2Valid, events)
          .getOrElse(fail(s"$what: events $events"))
        flat match
          case Right(Right(counted)) =>
            assert(
              summon[cats.Eq[UTxOState[Conway]]].eqv(state, counted.state),
              s"$what: $state\n${counted.state}"
            )
            assertEquals(state.utxo.utxo.toMap, counted.state.utxo.utxo.toMap, what)
          case _ => ()
        Outcome.Admitted(succeeded, failed, scripts)
    assertEquals(ours, theirs, what)
    ours

  // ---- the verdicts ------------------------------------------------------------------------------

  test("the corpus: the 70 submissions, accepted and refused") {
    assertEquals(recorded.size, 70)
    assert(recorded.exists(_.accepted) && recorded.exists(!_.accepted))
  }

  test("as submitted, the era UTXO gives the reference's verdict and the flat UTXO's") {
    val outcomes =
      recorded.map(c => c -> same(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, scarab))
    outcomes.foreach { (c, outcome) =>
      outcome match
        case Outcome.Admitted(_, _, _) => assert(c.accepted, s"${c.name}: the reference refused")
        case Outcome.Refused(_)        => assert(!c.accepted, s"${c.name}: the reference accepted")
        case Outcome.NoVerdict(reason) => fail(s"${c.name}: no verdict, $reason")
    }
    // what the corpus reaches through UTXO: UTXOS's collection errors and both mismatches
    def has(p: Failure => Boolean) = outcomes.exists {
      case (_, Outcome.Refused(fs)) => fs.exists(p)
      case _                        => false
    }
    assert(has { case Failure.CollectErrors(_) => true; case _ => false })
    assert(has { case Failure.PassedUnexpectedly(_) => true; case _ => false })
    assert(has { case Failure.FailedUnexpectedly(_, _) => true; case _ => false })
  }

  test("every case, both flags, each build and each policy: the flat UTXO's outcome") {
    val outcomes = for
      c <- everyCase
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
    yield same(c, build, validation, scarab)
    assert(outcomes.exists { case Outcome.Admitted(_, _, n) => n > 0; case _ => false })
    assert(outcomes.exists { case Outcome.Admitted(0, 1, n) => n > 0; case _ => false })
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

  test("a failure of UTXO's predicates is recorded before UTXOS, which then evaluates nothing") {
    // a maximum transaction size of one byte fails `validateMaxTxSizeUTxO` on every case; UTXOS still
    // collects the scripts but evaluates none (`whenFailureFree`), and the evaluator is not asked
    val withScripts = everyCase.filter(_.flat.witnesses.redeemers.nonEmpty)
    assert(withScripts.nonEmpty)
    withScripts.foreach { c0 =>
      val flatPp = c0.flatPp.copy(maxTxSize = 1)
      val c = c0.copy(
        flatPp = flatPp,
        pp = PParams[Conway](right(c0.name)(ConwayPParams.fromStored(flatPp)))
      )
      same(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, never(c.name)) match
        case Outcome.Refused(fs) =>
          assert(
            fs.exists {
              case Failure.Utxo(
                    PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.MaxTxSize(m)))
                  ) =>
                m.expected == 1
              case _ => false
            },
            s"${c.name}: $fs"
          )
        case other => fail(s"${c.name}: $other")
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

  /** The purposes of `c`'s scripts needed in their flat form, for a missing redeemer's failure. */
  private def purposes(c: Case) =
    val u = EraUTxO.conway
    rules.ConwayUtxowPredFailure.storedPurposes(
      u.getScriptsNeeded[TxLevel.Top](c.eraUtxo, t.body[TxLevel.Top](c.typed)(using t.bodyOf.top))(
        using u.txOf.bodyOf.top
      ),
      c.eraUtxo
    )

  /** Both UTXOWs on `c`: the same outcome, which is returned, and where admitted the same state;
    * the counters UTXOS's events give, through UTXO's and UTXOW's embeddings, are the flat rule's.
    */
  private def sameUtxow(
      c: Case,
      build: ReferenceBuild,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): Outcome =
    val what = s"${c.name} under ${build.name}, $validation (UTXOW)"
    val ls = ledger(c)
    val ctx = env.copy(
      clock = Some(c.clock),
      evaluator = evaluator,
      validation = validation,
      referenceBuild = build
    )
    val flat = Judgment.run(xyz.kd5ujc.scarab.ledger.rules.Utxow, RuleT.Policy.Accumulate)(
      TestLedger.globals,
      FlatUtxoEnv(ctx, c.flatPp, ConwayLedgerState.cert.get(ls)),
      Counted(LedgerState.utxo[Conway].get(ls), PlutusStats.zero),
      FlatStAnnTx(c.flat, FlatUtxo.fromMap(c.utxo))
    )
    val era = EraUtxoRun.Utxow.run(
      UtxoEnv(env.slot, c.pp, ConwayLedgerState.cert.get(ls)),
      stAnnTx(c),
      UtxoContext[Conway](
        Network.of(BigInt(env.network)).getOrElse(fail(s"network ${env.network}")),
        0,
        historyAt(env.slot),
        validation,
        ConwayUtxo.injectShelleyFailure(build)
      ),
      LedgerState.utxo[Conway].get(ls),
      evaluator,
      env.verdicts,
      env.decoder
    )
    val theirs = flat match
      case Left(LedgerFailure.Unavailable(_, _, reason)) => Outcome.NoVerdict(reason)
      case Left(other)                                   => fail(s"$what: flat defect $other")
      case Right(Left(fs)) =>
        Outcome.Refused(fs.toList.map(f => EraUtxoRun.form(f).getOrElse(fail(s"$what: $f"))))
      case Right(Right(counted)) =>
        Outcome.Admitted(counted.plutus.succeeded, counted.plutus.failed, counted.plutus.scripts)
    val ours = era match
      case Left(Left(Left(ScriptVerdictUnavailable(_, reason)))) => Outcome.NoVerdict(reason)
      case Left(defect) => fail(s"$what: era defect $defect")
      case Right(Left(fs)) =>
        Outcome.Refused(
          fs.toList.map(f => EraUtxoRun.Utxow.eraForm(f, purposes(c)).getOrElse(fail(s"$what: $f")))
        )
      case Right(Right((state, events))) =>
        val (succeeded, failed, scripts) = EraUtxoRun.Utxow
          .counters(t.isValid(c.typed) == IsPhase2Valid.Phase2Valid, events)
          .getOrElse(fail(s"$what: events $events"))
        flat match
          case Right(Right(counted)) =>
            assert(
              summon[cats.Eq[UTxOState[Conway]]].eqv(state, counted.state),
              s"$what: $state\n${counted.state}"
            )
          case _ => ()
        Outcome.Admitted(succeeded, failed, scripts)
    assertEquals(ours, theirs, what)
    ours

  test("UTXOW whole, as submitted: the reference's verdict and the flat UTXOW's") {
    recorded.foreach { c =>
      sameUtxow(c, ReferenceBuild.Release11_1, TxEnv.Validation.All, scarab) match
        case Outcome.Admitted(_, _, _) => assert(c.accepted, s"${c.name}: the reference refused")
        case Outcome.Refused(_)        => assert(!c.accepted, s"${c.name}: the reference accepted")
        case Outcome.NoVerdict(reason) => fail(s"${c.name}: no verdict, $reason")
    }
  }

  test("UTXOW whole on every case, both flags, each build and each policy: the flat UTXOW's") {
    val outcomes = for
      c <- everyCase
      build <- ReferenceBuild.values.toVector
      validation <- TxEnv.Validation.values.toVector
    yield sameUtxow(c, build, validation, scarab)
    assert(outcomes.exists { case Outcome.Admitted(_, _, n) => n > 0; case _ => false })
    assert(outcomes.exists { case Outcome.Refused(_) => true; case _ => false })
  }

  test("a failure of UTXOW alone is recorded, and UTXOS then evaluates nothing") {
    // each accepted case with its script integrity hash taken out: UTXOW's checks of the integrity
    // hash and the signatures (its body changed) fail, UTXO's none, and UTXOS collects the scripts
    // but evaluates none (`whenFailureFree`): the evaluator fails the suite if asked
    val withScripts = recorded.filter(c => c.accepted && c.flat.witnesses.redeemers.nonEmpty)
    assert(withScripts.nonEmpty)
    val refused = withScripts.map(c =>
      sameUtxow(
        withoutIntegrity(c),
        ReferenceBuild.Release11_1,
        TxEnv.Validation.All,
        never(c.name)
      )
    )
    assert(refused.forall {
      case Outcome.Refused(fs) =>
        fs.exists {
          case EraUtxoRun.Failure.Utxo(
                xyz.kd5ujc.scarab.ledger.PredicateFailure.Utxow(
                  xyz.kd5ujc.scarab.ledger.UtxowFailure.PPViewHashesDontMatch(_)
                )
              ) =>
            true
          case _ => false
        } && fs.forall {
          case EraUtxoRun.Failure.Utxo(
                xyz.kd5ujc.scarab.ledger.PredicateFailure.Utxow(
                  xyz.kd5ujc.scarab.ledger.UtxowFailure.Utxo(_)
                )
              ) =>
            false
          case EraUtxoRun.Failure.Utxo(_) => true
          case _                          => false
        }
      case _ => false
    })
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
