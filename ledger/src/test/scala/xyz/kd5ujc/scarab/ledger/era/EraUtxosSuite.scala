// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain, StateT}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  PlutusCase,
  PosixClock,
  TestLedger,
  Tx as FlatTx,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxOut}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxoEvent,
  BabbageUtxo,
  ConwayUtxo,
  ConwayUtxos,
  ConwayUtxosEvent,
  ConwayUtxosPredFailure,
  FailureDescription,
  TagMismatchDescription,
  UtxoStateDefect,
  UtxosEvent,
  UtxosPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Judge, Judgment, RuleT, ValidationPolicy}
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayCertState,
  ConwayLedgerState,
  ConwayNewEpochState,
  Credential,
  LedgerState,
  ShelleyBasedEra,
  Staking,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  ScarabEvaluator,
  ScriptEvaluator,
  ScriptFailure as UplcScriptFailure
}
import xyz.kd5ujc.scarab.uplc.cek.CekError
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Conway's UTXOS on the era types (`era/rules/Utxos.scala`, cardano-ledger f649f975
  * `Conway/Rules/Utxos.hs`, with Babbage's `expectScriptsToPass` and `babbageEvalScriptsTxInvalid`)
  * and the change of the UTxO state that follows it (Babbage's `updateUTxOState` after Conway's
  * `updateTreasuryDonation`), held to the reference's recorded verdicts and to the flat rules'
  * recorded judgments.
  *
  * The cases are every Conway transaction the reference node was given with what it spends,
  * references and puts up as collateral: the Plutus V1 context submissions at protocol 9 and 10,
  * the context-error submissions and the Plutus boundary submissions, each accepted or refused by
  * the reference node 11.1.3, whose refusals here are a context error or a validation-tag mismatch
  * (`V1ContextSuite`). Before the flat UTXOS and UTXO were deleted, the era rule was compared here
  * with them on each, under each flag, each validation policy and with a failure recorded before
  * UTXOS or not, and gave the flat rule's verdict: the same context errors in the same order (their
  * contents are `EraPlutusContextSuite` 's), the same tag mismatch with each script's failure and
  * request, or the same scripts evaluated; and where the scripts passed as the flag says, the same
  * state after. The era rule's judgments were recorded after the two were found to agree
  * (`fixtures/conway-flat-refusals/utxos-judgments.jsonl`, `UtxoRecordings`), and it is held to
  * them. On the reference's multi-script results, the scripts that passed and failed are the
  * reference's, in its order; and on the recorded blocks' transactions, with crafted donations and
  * deregistrations, the state after is the flat rule's recorded one.
  */
class EraUtxosSuite extends munit.FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val t = EraTx.conway

  private val files = Vector(
    "fixtures/reference/v1-context/conway-pv9.json",
    "fixtures/reference/v1-context/conway-pv10.json",
    "fixtures/reference/context-errors/submissions/conway-pv10.json",
    "fixtures/reference/plutus-boundaries/transactions/conway-pv10.json"
  )

  /** One case: the flat and the typed transaction, what the UTxO set holds for it, the parameters
    * in both forms, and what the reference did with it (for the flag it was submitted with).
    */
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
      right(s"$name flat")(FlatTx.fromBlock(parts, Era.Conway)),
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

  /** Each recorded case as submitted. */
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

  /** Each recorded case with its flag the other way, which the reference was not given. */
  private val flipped: Vector[Case] = recorded.map(c =>
    val witnesses = c.flat.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(c.flat.body.raw, witnesses, c.flat.auxiliary, !c.flat.isValid)
    c.copy(
      name = s"${c.name} (flag flipped)",
      flat = right(s"${c.name} flipped flat")(FlatTx.fromBlock(parts, Era.Conway)),
      typed = right(s"${c.name} flipped typed")(ConwayTx.decode(parts, StateLayout.Node11_1))
    )
  )

  /** A row of the multi-script results (`fixtures/reference/script-results`): the reference's own
    * evaluation, in its collection order, of three scripts in each of the eight patterns of passes
    * and failures in Plutus V1, V2 and V3, and its admitted control. Each row is a case under each
    * flag, with the hashes of the scripts the reference's `evalPlutusScripts` passed and failed, in
    * order.
    */
  private final case class Result(
      c: Case,
      passing: List[Bytes],
      failing: List[Bytes],
      scripts: List[(Bytes, Boolean)]
  )

  private def scriptHash(text: String): Bytes =
    right(text)(Bytes.fromHex(text.stripPrefix("ScriptHash \"").stripSuffix("\"")))

  // a row that names no clock was measured on slot `s` at `100 s` milliseconds
  private val unnamedRowClock: PosixClock = PosixClock.Total(slot => slot * 100)

  private val results: Vector[Result] =
    val file = "fixtures/reference/script-results/aggregation.json"
    val rows = right(file)(parse(Files.readString(Path.of(file)))).asArray.getOrElse(fail(file))
    rows.flatMap { row =>
      val r = row.hcursor
      val name = right(file)(r.get[String]("name"))
      val parts = right(s"$name transaction")(
        Cbor.decode(right(name)(Bytes.fromHex(right(name)(r.get[String]("transaction")))))
      ).value match
        case CborValue.Arr(Vector(body, witnesses, flag, auxiliary)) =>
          flag.value match
            case CborValue.Bool(valid) =>
              Block.Transaction(
                body.original,
                witnesses.original,
                Option.unless(auxiliary.value == CborValue.Null)(auxiliary.original),
                valid
              )
            case other => fail(s"$name: not a validity flag: $other")
        case other => fail(s"$name: not a complete transaction: $other")
      val utxo = right(s"$name utxo")(r.get[Vector[io.circe.Json]]("utxo")).map { entry =>
        val e = entry.hcursor
        right(s"$name input")(
          TxIn.decode(
            right(name)(
              Cbor.decode(right(name)(Bytes.fromHex(right(name)(e.get[String]("input")))))
            )
          )
        ) -> right(s"$name output")(
          FlatTxOut.fromBytes(right(name)(Bytes.fromHex(right(name)(e.get[String]("output")))))
        )
      }.toMap
      val models = right(s"$name cost models")(r.get[Map[String, Vector[String]]]("costModels"))
        .map((language, values) => language.toInt -> values.map(BigInt(_)))
      val flatPp =
        TestLedger.params(right(name)(r.get[Int]("protocolMajor"))).copy(costModels = models)
      val oracle = r.downField("oracle")
      val passing =
        right(s"$name passing")(oracle.get[List[String]]("passingHashes")).map(scriptHash)
      val failing = right(s"$name failures")(oracle.get[List[io.circe.Json]]("failures"))
        .map(f => scriptHash(right(s"$name failure")(f.hcursor.get[String]("scriptHash"))))
      // every script the reference ran, in the order it ran them, and whether each passed
      val scripts = right(s"$name scripts")(oracle.get[List[io.circe.Json]]("scripts")).map { j =>
        val sc = j.hcursor
        scriptHash(right(s"$name script")(sc.get[String]("scriptHash"))) ->
          right(s"$name script")(sc.get[Boolean]("success"))
      }
      Vector(parts.isValid, !parts.isValid).map { valid =>
        val flagged = parts.copy(isValid = valid)
        Result(
          Case(
            s"$file/$name, valid: $valid",
            right(s"$name flat")(FlatTx.fromBlock(flagged, Era.Conway)),
            right(s"$name typed")(ConwayTx.decode(flagged, StateLayout.Node11_1)),
            utxo,
            UTxO[Conway](
              utxo.map((in, out) => in -> right(s"$name output")(ConwayTxOut.fromStored(out)))
            ),
            flatPp,
            PParams[Conway](right(s"$name parameters")(ConwayPParams.fromStored(flatPp))),
            accepted = valid == failing.isEmpty,
            unnamedRowClock
          ),
          passing,
          failing,
          scripts
        )
      }
    }

  /** Both paths read the case's clock: the flat rule from its environment (`TxChecks.phase2`). */
  private def stAnnTx(c: Case): AlonzoStAnnTx[Conway] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](c.clock.posix, c.pp, c.eraUtxo, Map.empty, c.typed)

  // ---- the evaluators ----------------------------------------------------------------------------

  private val scarab: ScriptEvaluator[Id] = ScarabEvaluator.default

  /** An evaluator that must not be asked: the rule is not to evaluate. */
  private def never(name: String): ScriptEvaluator[Id] =
    request =>
      fail(s"$name: evaluated ${request.language} under a policy or judgment that skips it")

  /** An evaluator with no verdict for any script. */
  private val unavailable: ScriptEvaluator[Id] =
    _ =>
      EvalResult.Failure(
        EvalFailure.Unsupported(BackendFailure.StackExhausted),
        ExUnits.zero,
        Vector.empty
      )

  // ---- the era rule ------------------------------------------------------------------------------

  private type Rec = UtxosPredFailure[Conway]
  private type Events = Chain[UtxosEvent[Conway]]
  private type F[A] = RuleT[Rec, ScriptVerdictUnavailable, Events, A]
  import RuleT.given

  /** The events, told into the judgment's state. */
  private given Tell[F, Events] = new Tell[F, Events]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Events): F[Unit] = summon[Stateful[F, Events]].modify(_ ++ events)

  private def lifted[M[_]: Monad](evaluator: ScriptEvaluator[Id]): ScriptEvaluator[M] =
    request => Monad[M].unit.map(_ => evaluator.evaluate(request))

  /** A failure recorded before UTXOS, as UTXO's or UTXOW's would be. */
  private val earlier: Rec = ConwayUtxosPredFailure.ValidationTagMismatch(
    IsPhase2Valid.Phase2Valid,
    TagMismatchDescription.PassedUnexpectedly
  )

  private def eraRun(
      c: Case,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id],
      failedBefore: Boolean
  ): Either[ScriptVerdictUnavailable, Either[NonEmptyChain[Rec], Events]] =
    given ScriptEvaluator[F] = lifted[F](evaluator)
    given Phase2Deferral[F, Conway] = Phase2Deferral.now
    val judge = summon[Judge[F, Rec]]
    val program =
      (if failedBefore then judge.fail(earlier) else Monad[F].unit) *>
        ConwayUtxos.utxosTransition[F, Conway](stAnnTx(c), validation)
    Judgment
      .judge(program, RuleT.Policy.Accumulate)(TestLedger.globals, Chain.empty)
      .map(_.map(_._1))

  // ---- both in one form --------------------------------------------------------------------------

  /** A collection's failure by its kind, and the language a missing cost model is of: their
    * contents are compared by `EraPlutusContextSuite`.
    */
  private enum Collect:
    case NoRedeemer, NoWitness, BadTranslation
    case NoCostModel(language: xyz.kd5ujc.scarab.plutus.PlutusLanguage)

  /** A script's failure, the machine's by its error and the script's trace: the cause the machine
    * keeps beside it holds terms, which are equal only as the same object.
    */
  private enum Cause:
    case Machine(error: CekError, logs: Vector[String])
    case Other(failure: EvalFailure)

  private def cause(failure: EvalFailure): Cause = failure match
    case EvalFailure.Script(UplcScriptFailure.Uplc(EvaluationError.Cek(cek), logs)) =>
      Cause.Machine(cek.error, logs)
    case other => Cause.Other(other)

  /** What UTXOS decided. */
  private enum Verdict:
    case NoVerdict(reason: EvalFailure.Unsupported)
    case CollectErrors(errors: List[Collect])
    case PassedUnexpectedly(flag: Boolean)
    case FailedUnexpectedly(failures: List[(Cause, EvalRequest)])

    /** The flat counters: a transaction whose scripts passed as its flag says, one whose scripts
      * failed as its flag says, and the scripts evaluated.
      */
    case Passed(succeeded: Long, failed: Long, scripts: Long)

  private def eraCollect(e: CollectError[Conway]): Collect = e match
    case CollectError.NoRedeemer(_)     => Collect.NoRedeemer
    case CollectError.NoWitness(_)      => Collect.NoWitness
    case CollectError.NoCostModel(l)    => Collect.NoCostModel(l)
    case CollectError.BadTranslation(_) => Collect.BadTranslation

  private def eraVerdict(
      c: Case,
      result: Either[ScriptVerdictUnavailable, Either[NonEmptyChain[Rec], Events]],
      failedBefore: Boolean
  ): Verdict =
    val valid = t.isValid(c.typed) == IsPhase2Valid.Phase2Valid
    def utxos(f: ConwayUtxosPredFailure[Conway]): Verdict = f match
      case ConwayUtxosPredFailure.CollectErrors(es) =>
        // UTXOS reports the annotated transaction's own collection failures
        assertEquals(stAnnTx(c).asatPlutusScriptsWithContext.left.toOption, Some(es), c.name)
        Verdict.CollectErrors(es.toList.map(eraCollect))
      case ConwayUtxosPredFailure.ValidationTagMismatch(flag, description) =>
        assertEquals(flag, t.isValid(c.typed), c.name)
        description match
          case TagMismatchDescription.PassedUnexpectedly =>
            Verdict.PassedUnexpectedly(flag == IsPhase2Valid.Phase2Valid)
          case TagMismatchDescription.FailedUnexpectedly(fs) =>
            Verdict.FailedUnexpectedly(fs.toList.map {
              case FailureDescription.PlutusFailure(failure, pwc) =>
                cause(failure) -> PlutusWithContext.evalRequest(pwc)
            })
    result match
      case Left(ScriptVerdictUnavailable(_, reason)) => Verdict.NoVerdict(reason)
      case Right(Left(failures)) =>
        val all = failures.toChain.toList
        val own = all.filterNot(_ == earlier)
        if failedBefore then assertEquals(all.size - own.size, 1, c.name)
        own match
          case Nil      => Verdict.Passed(0, 0, 0)
          case f :: Nil => utxos(f)
          case several  => fail(s"${c.name}: several failures $several")
      case Right(Right(events)) =>
        assert(!failedBefore, s"${c.name}: the earlier failure was lost")
        val passed = events.toList.collect {
          case ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(ps) =>
            ps.size
        }
        val failed = events.toList.collect { case ConwayUtxosEvent.FailedPlutusScriptsEvent(fs) =>
          fs.size
        }
        assert(passed.size <= 1 && failed.size <= 1, s"${c.name}: events $events")
        Verdict.Passed(
          if valid && passed.nonEmpty then 1 else 0,
          if failed.nonEmpty then 1 else 0,
          (passed ++ failed).sum.toLong
        )

  /** A verdict as it is recorded: `N` for none; `C:` and the kind of each collection failure, in
    * order (`r` no redeemer, `w` no witness, `t` a context's failure, `m` and a language's number
    * no cost model); `U:1` or `U:0` for scripts that passed unexpectedly under the flag; `F:` and
    * the digest of each failing script's request (`UtxoRecordings.request`), in order, whose cause
    * is the evaluator's on it ([[causesHeld]]); and `P:` with the counters of the scripts
    * evaluated.
    */
  private def verdictCell(v: Verdict): String = v match
    case Verdict.NoVerdict(_) => "N"
    case Verdict.CollectErrors(es) =>
      "C:" + es
        .map {
          case Collect.NoRedeemer     => "r"
          case Collect.NoWitness      => "w"
          case Collect.BadTranslation => "t"
          case Collect.NoCostModel(l) => s"m${l.id}"
        }
        .mkString(",")
    case Verdict.PassedUnexpectedly(flag) => if flag then "U:1" else "U:0"
    case Verdict.FailedUnexpectedly(fs) =>
      "F:" + fs.map((_, r) => UtxoRecordings.digest(UtxoRecordings.request(r))).mkString(",")
    case Verdict.Passed(succeeded, failed, scripts) => s"P:$succeeded/$failed/$scripts"

  /** Whether each failing script's cause is the one the evaluator gives its request: the cause is a
    * function of the request, which [[verdictCell]] records, so the two hold the failure whole.
    */
  private def causesHeld(v: Verdict): Boolean = v match
    case Verdict.FailedUnexpectedly(fs) =>
      fs.forall((c, r) =>
        scarab.evaluate(r) match
          case EvalResult.Failure(reason, _, _) => cause(reason) == c
          case EvalResult.Success(_, _)         => false
      )
    case _ => true

  /** The recorded judgment `id`, failing the test where none was recorded. */
  private def recordedCells(id: String): Vector[String] =
    UtxoRecordings.utxos.getOrElse(id, fail(s"no recording of $id"))

  /** The era rule on `c` under `validation`, `evaluator` and with a failure recorded before or not:
    * its verdict, each failing script's cause the evaluator's on its request.
    */
  private def judged(
      c: Case,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id],
      failedBefore: Boolean
  ): Verdict =
    val ours = eraVerdict(c, eraRun(c, validation, evaluator, failedBefore), failedBefore)
    assert(causesHeld(ours), s"${c.name}: a script's cause is not the evaluator's on its request")
    ours

  /** The era rule's verdicts on a case, as the flat rule's were recorded (`utxos/<case>`, a cell
    * each in this order): under `All`; applied again (`NonStatic`), with an evaluator that must not
    * be asked; after an earlier failure, with that evaluator; and with an evaluator that has no
    * verdict.
    */
  private final case class Verdicts(
      all: Verdict,
      nonStatic: Verdict,
      failedBefore: Verdict,
      unavailable: Verdict
  ):
    def cells: Vector[String] = Vector(all, nonStatic, failedBefore, unavailable).map(verdictCell)

  private def verdictsOf(c: Case): Verdicts =
    Verdicts(
      judged(c, TxEnv.Validation.All, scarab, false),
      judged(c, TxEnv.Validation.NonStatic, never(c.name), false),
      judged(c, TxEnv.Validation.All, never(c.name), true),
      judged(c, TxEnv.Validation.All, unavailable, false)
    )

  // ---- the verdicts ------------------------------------------------------------------------------

  test("the corpus: every case of each file, both flags, both verdicts of the reference") {
    assertEquals(recorded.size, 70)
    assert(recorded.exists(_.accepted) && recorded.exists(!_.accepted))
    assert(
      recorded.exists(c => !c.flat.isValid && c.accepted),
      "no invalid-flag transaction admitted"
    )
  }

  /** Every case of the submissions under both flags, and of the multi-script results. */
  private def everyCase: Vector[Case] = recorded ++ flipped ++ results.map(_.c)

  /** The era rule's verdicts on every case. */
  private lazy val verdicts: Vector[(Case, Verdicts)] = everyCase.map(c => c -> verdictsOf(c))

  test("the era rule gives the flat rule's recorded verdicts on every case, under each judgment") {
    verdicts.foreach((c, vs) => assertEquals(vs.cells, recordedCells(s"utxos/${c.name}"), c.name))
  }

  private def verdictOf(c: Case): Verdict =
    verdicts.find(_._1.name == c.name).getOrElse(fail(s"no judgment of ${c.name}"))._2.all

  test(
    "as submitted, the era rule gives the reference's verdict and the flat rule's recorded one"
  ) {
    val verdicts = recorded.map(c => c -> verdictOf(c))
    verdicts.foreach { (c, verdict) =>
      verdict match
        case Verdict.Passed(_, _, _) => assert(c.accepted, s"${c.name}: the reference refused")
        case other => assert(!c.accepted, s"${c.name}: the reference accepted, $other")
    }
    // what the corpus reaches: context errors, both mismatches and both flags evaluated
    assert(verdicts.exists { case (_, Verdict.CollectErrors(_)) => true; case _ => false })
    assert(verdicts.exists { case (_, Verdict.PassedUnexpectedly(_)) => true; case _ => false })
    assert(verdicts.exists { case (_, Verdict.FailedUnexpectedly(_)) => true; case _ => false })
    assert(
      verdicts.exists { case (_, Verdict.Passed(0, 1, n)) => n > 0; case _ => false },
      "no invalid-flag transaction whose script failed"
    )
    assert(verdicts.exists { case (_, Verdict.Passed(1, 0, n)) => n > 0; case _ => false })
  }

  test("the reference's script results: each script passed or failed, in its order, both flags") {
    assertEquals(results.size, 50)
    results.foreach { case Result(c, passing, failing, _) =>
      val valid = c.flat.isValid
      def hashes(pwcs: List[PlutusWithContext]) = pwcs.map(_.pwcScript.plutusRunnableScriptHash)
      eraRun(c, TxEnv.Validation.All, scarab, false) match
        case Right(Right(events)) =>
          val told = events.toList.map {
            case ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(ps) => (true, hashes(ps.toList))
            case ConwayUtxosEvent.FailedPlutusScriptsEvent(fs)     => (false, hashes(fs.toList))
          }
          val expected =
            if valid then Option.when(passing.nonEmpty)((true, passing)).toList
            else Option.when(passing.nonEmpty)((true, passing)).toList :+ ((false, failing))
          assert(valid == failing.isEmpty, s"${c.name}: passed though the reference refuses")
          assertEquals(told, expected, c.name)
        case Right(Left(failures)) =>
          failures.toList match
            case List(ConwayUtxosPredFailure.ValidationTagMismatch(_, description)) =>
              description match
                case TagMismatchDescription.PassedUnexpectedly =>
                  assert(!valid && failing.isEmpty, c.name)
                case TagMismatchDescription.FailedUnexpectedly(fs) =>
                  assert(valid, c.name)
                  assertEquals(
                    fs.toList.map { case FailureDescription.PlutusFailure(_, pwc) =>
                      pwc.pwcScript.plutusRunnableScriptHash
                    },
                    failing,
                    c.name
                  )
            case other => fail(s"${c.name}: $other")
        case Left(defect) => fail(s"${c.name}: $defect")
      verdictOf(c)
    }
    assert(results.exists(r => r.passing.nonEmpty && r.failing.nonEmpty))
  }

  // ---- the evaluator's calls ---------------------------------------------------------------------

  /** What a run of the era rule leaves, kept whatever it ends with: the failures it recorded, the
    * events it told and the requests the evaluator was given, in order.
    */
  private final case class Seen(
      failures: Chain[Rec],
      events: Events,
      calls: Vector[EvalRequest]
  )

  /** The era rule's monad for these runs: a defect keeps what was seen up to it. */
  private type R[A] = StateT[[X] =>> Either[(ScriptVerdictUnavailable, Seen), X], Seen, A]

  private given Judge[R, Rec] = new Judge[R, Rec]:
    val functor: Functor[R] = summon[Monad[R]]
    def tell(failures: Chain[Rec]): R[Unit] =
      StateT.modify(seen => seen.copy(failures = seen.failures ++ failures))
    def failing: R[Boolean] = StateT.inspect(_.failures.nonEmpty)
    def nest[A](fa: R[A]): R[A] = fa
    def policy: ValidationPolicy = ValidationPolicy.ValidateAll

  private given Tell[R, Events] = new Tell[R, Events]:
    val functor: Functor[R] = summon[Monad[R]]
    def tell(events: Events): R[Unit] =
      StateT.modify(seen => seen.copy(events = seen.events ++ events))

  private given Raise[R, ScriptVerdictUnavailable] = new Raise[R, ScriptVerdictUnavailable]:
    val functor: Functor[R] = summon[Monad[R]]
    def raise[E2 <: ScriptVerdictUnavailable, A](e: E2): R[A] = StateT(seen => Left((e, seen)))

  /** `answer` given the calls so far, each request recorded before it is answered. */
  private def recording(
      answer: (Vector[EvalRequest], EvalRequest) => EvalResult
  ): ScriptEvaluator[R] =
    request =>
      StateT(seen => Right((seen.copy(calls = seen.calls :+ request), answer(seen.calls, request))))

  private def recordedRun(
      c: Case,
      evaluator: ScriptEvaluator[R]
  ): Either[(ScriptVerdictUnavailable, Seen), Seen] =
    given ScriptEvaluator[R] = evaluator
    given Phase2Deferral[R, Conway] = Phase2Deferral.now
    ConwayUtxos
      .utxosTransition[R, Conway](stAnnTx(c), TxEnv.Validation.All)
      .runS(Seen(Chain.empty, Chain.empty, Vector.empty))

  /** The case's scripts with their contexts, as `mkAlonzoStAnnTx` collected them. */
  private def collected(c: Case): List[PlutusWithContext] =
    stAnnTx(c).asatPlutusScriptsWithContext.fold(es => fail(s"${c.name}: $es"), identity)

  /** The request of each script the reference ran, in the order it ran them. */
  private def inOrder(r: Result): List[(EvalRequest, Boolean)] =
    val byHash = collected(r.c).map(pwc => pwc.pwcScript.plutusRunnableScriptHash -> pwc).toMap
    r.scripts.map((hash, success) =>
      PlutusWithContext.evalRequest(
        byHash.getOrElse(hash, fail(s"${r.c.name}: ${hash.hex} was not collected"))
      ) -> success
    )

  test("the evaluator is asked for each script in the reference's order, with its verdict") {
    results.foreach { r =>
      val expected = inOrder(r)
      recordedRun(r.c, recording((_, request) => scarab.evaluate(request))) match
        case Right(seen) =>
          assertEquals(seen.calls.toList, expected.map(_._1), r.c.name)
          assertEquals(
            seen.calls.toList.map(request => scarab.evaluate(request).isSuccess),
            expected.map(_._2),
            r.c.name
          )
        case Left(defect) => fail(s"${r.c.name}: $defect")
    }
  }

  test(
    "an evaluator that stops answering after one call: that prefix, and the script it stopped at"
  ) {
    val stops = EvalFailure.Unsupported(BackendFailure.StackExhausted)
    val cases = results.filter(_.scripts.sizeIs >= 2)
    assert(cases.nonEmpty)
    cases.foreach { r =>
      val expected = inOrder(r).map(_._1)
      val evaluator = recording((before, request) =>
        if before.isEmpty then scarab.evaluate(request)
        else EvalResult.Failure(stops, ExUnits.zero, Vector.empty)
      )
      recordedRun(r.c, evaluator) match
        case Left((ScriptVerdictUnavailable(plutus, reason), seen)) =>
          assertEquals(seen.calls.toList, expected.take(2), r.c.name)
          assertEquals(PlutusWithContext.evalRequest(plutus), expected(1), r.c.name)
          assertEquals(reason, stops, r.c.name)
          assertEquals(seen.events, Chain.empty, r.c.name)
          assertEquals(seen.failures, Chain.empty, r.c.name)
        case Right(seen) => fail(s"${r.c.name}: a verdict after the evaluator stopped: $seen")
    }
  }

  test("with the flag flipped, the era rule gives the flat rule's recorded verdict") {
    val verdicts = flipped.map(verdictOf)
    assert(verdicts.exists { case Verdict.Passed(_, _, n) => n > 0; case _ => false })
    assert(verdicts.exists { case Verdict.PassedUnexpectedly(_) => true; case _ => false })
    assert(verdicts.exists { case Verdict.FailedUnexpectedly(_) => true; case _ => false })
  }

  test("a transaction applied again (NonStatic) collects its scripts and evaluates none") {
    verdicts.foreach { (c, vs) =>
      vs.nonStatic match
        case Verdict.Passed(_, _, scripts) => assertEquals(scripts, 0L, c.name)
        case Verdict.CollectErrors(_)      => ()
        case other                         => fail(s"${c.name}: $other")
    }
  }

  test(
    "after an earlier failure, the scripts are collected and none is evaluated (whenFailureFree)"
  ) {
    verdicts.foreach { (c, vs) =>
      vs.failedBefore match
        case Verdict.Passed(0, 0, 0) | Verdict.CollectErrors(_) => ()
        case other                                              => fail(s"${c.name}: $other")
    }
  }

  test("an evaluator with no verdict leaves the transaction with none, whatever its flag") {
    val reached = verdicts.map(_._2.unavailable)
    assert(
      reached.exists(_ == Verdict.NoVerdict(EvalFailure.Unsupported(BackendFailure.StackExhausted)))
    )
  }

  // ---- the state ---------------------------------------------------------------------------------

  /** The ledger state holding the case's outputs, with the instant stake they make. */
  private def ledger(c: Case): LedgerState[Conway] =
    ConwayNewEpochState.ls.get(Build.funded(Build.nes[Conway](5, c.flatPp), c.utxo))

  private type H[A] = RuleT[Nothing, UtxoStateDefect, Chain[AlonzoUtxoEvent[Conway]], A]

  private given Tell[H, Chain[AlonzoUtxoEvent[Conway]]] =
    new Tell[H, Chain[AlonzoUtxoEvent[Conway]]]:
      val functor: Functor[H] = summon[Monad[H]]
      def tell(events: Chain[AlonzoUtxoEvent[Conway]]): H[Unit] =
        summon[Stateful[H, Chain[AlonzoUtxoEvent[Conway]]]].modify(_ ++ events)

  /** The era's change of the state (`conwayUtxoTransition` after UTXOS), with its events. */
  private def eraUpdate(
      c: Case,
      cert: ConwayCertState,
      s: UTxOState[Conway]
  ): (UTxOState[Conway], Chain[AlonzoUtxoEvent[Conway]]) =
    Judgment.judge(
      BabbageUtxo.updateUTxOState[H, Conway](
        c.pp,
        cert,
        c.typed,
        ConwayUtxo.updateTreasuryDonation[Conway](c.typed, s)
      ),
      RuleT.Policy.Accumulate
    )(TestLedger.globals, Chain.empty) match
      case Right(Right((events, next))) => (next, events)
      case other                        => fail(s"${c.name}: $other")

  /** The era rule's state after `c` from `s`, held to the flat rule's recorded one (`id`, with
    * `more`, the rest of its cells): the governance state as it was, the rest by its form
    * (`UtxoRecordings.stateCell`).
    */
  private def stateHeld(
      id: String,
      s: UTxOState[Conway],
      after: UTxOState[Conway],
      more: Vector[String] = Vector.empty
  ): Unit =
    assert(
      summon[cats.Eq[UTxOState[Conway]]].eqv(after.copy(gov = s.gov), after),
      s"$id: the governance state changed"
    )
    assertEquals(UtxoRecordings.stateCell(after) +: more, recordedCells(id), id)

  /** The cases whose scripts pass as their flag says, as the era rule decides (as the flat rule
    * did, recorded): the state changes. A transaction flagged invalid with no redeemer is not one
    * (its scripts pass).
    */
  private def passing(cases: Vector[Case]): Vector[Case] =
    cases.filter(c =>
      verdictOf(c) match
        case Verdict.Passed(_, _, _) => true
        case _                       => false
    )

  /** The id of a state judgment, a case's under `prefix`. */
  private def stateId(prefix: String, c: Case): String = s"state/$prefix/${c.name}"

  test("where the scripts pass as the flag says, the state after is the flat rule's recorded one") {
    val changed = passing(everyCase)
    assert(changed.exists(_.flat.isValid) && changed.exists(!_.flat.isValid))
    // a transaction flagged invalid some of whose scripts passed
    assert(results.exists(r => !r.c.flat.isValid && r.passing.nonEmpty && changed.contains(r.c)))
    changed.foreach { c =>
      val ls = ledger(c)
      val cert = ConwayLedgerState.cert.get(ls)
      val s = LedgerState.utxo[Conway].get(ls)
      val (ours, events) = eraUpdate(c, cert, s)
      val body = t.body[TxLevel.Top](c.typed)(using t.bodyOf.top)
      // the deposits less the refunds, which the flat rule's were, recorded beside the state
      val deposited = events.toList.collect { case AlonzoUtxoEvent.TotalDeposits(_, coin) =>
        coin.value.toString
      }
      stateHeld(stateId("passing", c), s, ours, Vector(deposited.headOption.getOrElse("-")))
      if c.flat.isValid then
        val spent = c.eraUtxo.unUTxO.filter((in, _) => c.flat.body.inputs.contains(in))
        events.toList match
          case List(
                AlonzoUtxoEvent.TotalDeposits(id, _),
                AlonzoUtxoEvent.TxUTxODiff(diffSpent, created)
              ) =>
            assertEquals(id, c.flat.body.id, c.name)
            assertEquals(diffSpent, UTxO[Conway](spent), c.name)
            assertEquals(
              created,
              UTxOs.txouts[Conway, TxLevel.Top](body)(using t.bodyOf, t.bodyOf.top),
              c.name
            )
          case other => fail(s"${c.name}: $other")
      else assertEquals(events.toList, Nil, c.name)
    }
  }

  test(
    "the reference's recorded effects: an admitted invalid-flag transaction loses its collateral alone"
  ) {
    val admitted = recorded.filter(c => c.accepted && !c.flat.isValid)
    assert(admitted.nonEmpty)
    admitted.foreach { c =>
      val ls = ledger(c)
      val (after, _) =
        eraUpdate(c, ConwayLedgerState.cert.get(ls), LedgerState.utxo[Conway].get(ls))
      val held = after.utxo.utxo
      c.flat.body.inputs.foreach(in => assert(held.contains(in), s"${c.name}: input $in spent"))
      c.flat.body.collateral.foreach(in =>
        assert(!held.contains(in), s"${c.name}: collateral $in kept")
      )
      c.flat.body.collateralReturn.foreach(r =>
        assertEquals(held.get(TxIn(c.flat.body.id, c.flat.body.outputs.size)), Some(r), c.name)
      )
    }
  }

  /** Every Conway transaction of the recorded blocks, with the parameters of protocol 10, under
    * both flags, over a UTxO set that holds none of what it spends: its certificates, proposals,
    * donations and collateral returns, which the submissions lack.
    */
  private lazy val blockCases: Vector[Case] =
    val flatPp = TestLedger.params(10)
    val pp = PParams[Conway](right("parameters")(ConwayPParams.fromStored(flatPp)))
    EraCorpus
      .shelleyBlocks(
        Vector(
          "fixtures/eras/blocks.hex",
          "fixtures/preview/conway.hex",
          "fixtures/preview/conway-governance.hex"
        )
      )
      .filter(_.era == Era.Conway)
      .flatMap(_.transactions)
      .zipWithIndex
      .flatMap { (parts, i) =>
        Vector(parts.isValid, !parts.isValid).map { valid =>
          val flagged = parts.copy(isValid = valid)
          Case(
            s"block transaction $i, valid: $valid",
            right(s"block transaction $i flat")(FlatTx.fromBlock(flagged, Era.Conway)),
            right(s"block transaction $i typed")(ConwayTx.decode(flagged, StateLayout.Node11_1)),
            Map.empty,
            UTxO[Conway](Map.empty),
            flatPp,
            pp,
            accepted = true,
            env.clock.getOrElse(fail("the environment has no clock"))
          )
        }
      }

  /** The stake of the base addresses of `outs`, by credential, each output's lovelace times `sign`.
    */
  private def stakeOf(outs: Iterable[FlatTxOut], sign: Int): Map[Credential[Staking], BigInt] =
    outs.toVector
      .collect(out =>
        out.address.kind match
          case xyz.kd5ujc.scarab.ledger.Address.Kind.Shelley(_, _, Some(credential), _) =>
            right("stake credential")(
              Convert.credential[Staking](credential)
            ) -> out.value.coin * sign
      )
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** The admitted invalid-flag transaction with collateral that a collateral return is made of. */
  private lazy val returningBase: Case = recorded
    .find(c => c.accepted && !c.flat.isValid && c.flat.body.collateral.nonEmpty)
    .getOrElse(fail("no admitted invalid-flag transaction with collateral"))

  test(
    "a phase-2-invalid transaction holding its collateral and returning part of it: what it spends, creates and pays"
  ) {
    val base = returningBase
    val collateral = base.flat.body.collateral.distinct.map(in =>
      in -> base.utxo.getOrElse(in, fail(s"${base.name}: collateral $in not held"))
    )
    val held = collateral.map(_._2.value.coin).sum
    val (_, first) = collateral.head
    val returned = held - 1000000
    // field 16, the collateral return, to the first collateral output's address
    val entries = right(base.name)(Cbor.decode(base.flat.body.raw)).value match
      case CborValue.Map(kvs) =>
        kvs.filterNot((k, _) => k.value == CborValue.UInt(16)) :+
          (node(CborValue.UInt(16)) -> node(
            CborValue.Arr(
              Vector(
                node(CborValue.ByteString(first.address.raw)),
                node(CborValue.UInt(returned))
              )
            )
          ))
      case other => fail(s"${base.name}: not a body: $other")
    val parts = Block.Transaction(
      right(base.name)(Cbor.encode(CborValue.Map(entries))),
      base.flat.witnesses.raw.getOrElse(fail(s"${base.name}: no witness bytes")),
      base.flat.auxiliary,
      false
    )
    val c = base.copy(
      name = s"${base.name}, returning",
      flat = right(s"${base.name} returning")(FlatTx.fromBlock(parts, Era.Conway)),
      typed = right(s"${base.name} returning")(ConwayTx.decode(parts, StateLayout.Node11_1))
    )
    val body = t.body[TxLevel.Top](c.typed)(using t.bodyOf.top)
    val ls = ledger(c)
    val cert = ConwayLedgerState.cert.get(ls)
    val before = LedgerState.utxo[Conway].get(ls)
    val (after, events) = eraUpdate(c, cert, before)
    stateHeld(stateId("returning", c), before, after)
    assertEquals(events.toList, Nil, c.name)
    val at = BabbageUtxo.mkCollateralTxIn[Conway, TxLevel.Top](body)(using t.bodyOf, t.bodyOf.top)
    assertEquals(at, TxIn(c.flat.body.id, c.flat.body.outputs.size))
    val held2 = after.utxo.utxo
    collateral.foreach((in, _) => assert(!held2.contains(in), s"collateral $in kept"))
    c.flat.body.inputs.foreach(in => assert(held2.contains(in), s"input $in spent"))
    assertEquals(held2.get(at).map(_.value.coin), Some(returned))
    assertEquals(held2.get(at).map(_.address.raw), Some(first.address.raw))
    assertEquals(after.fees.value - before.fees.value, held - returned)
    assertEquals(after.deposited, before.deposited)
    assertEquals(after.donation, before.donation)
    // the instant stake: the return added, then the collateral taken out
    val stake = (s: UTxOState[Conway]) =>
      s.instantStake.credentialStake.view.mapValues(_.toBigInt).toMap.withDefaultValue(BigInt(0))
    val expected = (stakeOf(Vector(held2.get(at).getOrElse(fail("no return"))), 1).toVector ++
      stakeOf(collateral.map(_._2), -1).toVector).groupMapReduce(_._1)(_._2)(_ + _)
    assert(expected.nonEmpty, "the collateral moves no stake")
    val delta = (stake(after).keySet ++ stake(before).keySet)
      .map(k => k -> (stake(after)(k) - stake(before)(k)))
      .filter(_._2 != 0)
      .toMap
    assertEquals(delta, expected.filter(_._2 != 0))
  }

  /** The recorded blocks have no donation and no deregistration, so each block transaction with
    * redeemers donates 5 ada here (field 22, `treasuryDonation`) and deregisters a key's account
    * with its deposit (field 4, an `UnRegDepositCert` alone), under both flags: its signatures no
    * longer hold, which neither UTXOS nor the state's change reads.
    */
  private lazy val donated: Vector[Case] =
    blockCases.filter(_.flat.witnesses.redeemers.nonEmpty).map { c =>
      val entries = right(c.name)(Cbor.decode(c.flat.body.raw)).value match
        case CborValue.Map(kvs) =>
          kvs.filterNot((k, _) => k.value == CborValue.UInt(22) || k.value == CborValue.UInt(4)) ++
            Vector(
              node(CborValue.UInt(22)) -> node(CborValue.UInt(5000000)),
              node(CborValue.UInt(4)) -> node(
                CborValue.Arr(
                  Vector(
                    node(
                      CborValue.Arr(
                        Vector(
                          node(CborValue.UInt(8)),
                          node(
                            CborValue.Arr(
                              Vector(
                                node(CborValue.UInt(0)),
                                node(CborValue.ByteString(Bytes(Vector.fill(28)(7.toByte))))
                              )
                            )
                          ),
                          node(CborValue.UInt(2000000))
                        )
                      )
                    )
                  )
                )
              )
            )
        case other => fail(s"${c.name}: not a body: $other")
      val parts = Block.Transaction(
        right(c.name)(Cbor.encode(CborValue.Map(entries))),
        c.flat.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes")),
        c.flat.auxiliary,
        c.flat.isValid
      )
      val flat = right(s"${c.name} donating")(FlatTx.fromBlock(parts, Era.Conway))
      assertEquals(flat.body.donation, BigInt(5000000), c.name)
      assertEquals(unregistered(c.copy(flat = flat)).size, 1, c.name)
      c.copy(
        name = s"${c.name}, donating",
        flat = flat,
        typed = right(s"${c.name} donating")(ConwayTx.decode(parts, StateLayout.Node11_1))
      )
    }

  private def node(v: CborValue): Node = Node(v, Bytes.empty)

  /** The credentials whose deposits the body's certificates refund (`UnRegCert`,
    * `UnRegDepositCert`).
    */
  private def unregistered(c: Case): Vector[xyz.kd5ujc.scarab.ledger.Credential] =
    c.flat.body.certificates.collect {
      case xyz.kd5ujc.scarab.ledger.Certificate.Unregister(cred, _) =>
        cred
    }

  /** `cert` with an account holding a deposit of 2 ada for each credential the body refunds, so the
    * refund is the account's deposit (`lookupAccountDeposit`), not the absent account's zero.
    */
  private def held(c: Case, cert: ConwayCertState): ConwayCertState =
    val accounts = unregistered(c).map(credential =>
      right(s"${c.name} credential")(Convert.credential[Staking](credential)) -> ConwayAccountState(
        CompactCoin.fromUnsigned(0L),
        CompactCoin.fromUnsigned(2000000L),
        None,
        None
      )
    )
    ConwayCertState.dState.modify(d =>
      d.copy(accounts = ConwayAccounts.states.modify(_ ++ accounts)(d.accounts))
    )(cert)

  test(
    "on the recorded blocks' transactions, the state after is the flat rule's recorded one, under both flags"
  ) {
    // a transaction flagged invalid with no redeemer never reaches the change: its scripts pass,
    // and UTXOS refuses it (the flat rule's collateral is empty there, `TxChecks.collateral`)
    val cases = blockCases.filter(c => c.flat.isValid || c.flat.witnesses.redeemers.nonEmpty) ++
      donated
    assert(
      cases.exists(c => !c.flat.isValid && c.flat.body.collateralReturn.isDefined),
      "no return"
    )
    assert(cases.exists(c => c.flat.isValid && c.flat.body.certificates.nonEmpty), "no certificate")
    assert(cases.exists(c => c.flat.isValid && c.flat.body.proposals.nonEmpty), "no proposal")
    assert(cases.exists(c => c.flat.isValid && unregistered(c).nonEmpty), "no refund")
    cases.foreach { c =>
      val ls = ledger(c)
      val cert = held(c, ConwayLedgerState.cert.get(ls))
      val s = LedgerState.utxo[Conway].get(ls)
      val (ours, _) = eraUpdate(c, cert, s)
      stateHeld(stateId("blocks", c), s, ours)
      if !c.flat.isValid then
        c.flat.body.collateralReturn.foreach(r =>
          assertEquals(
            ours.utxo.utxo.get(TxIn(c.flat.body.id, c.flat.body.outputs.size)),
            Some(r),
            c.name
          )
        )
    }
  }

  test("a state's cell holds the UTxO set's diff and stamps, and not the governance state") {
    // the governance state, which UTXO leaves as it is, is not in the cell, and is checked to be as
    // it was instead (`stateHeld`); the UTxO set's stamps, which its map does not show, are
    val c = passing(everyCase).headOption.getOrElse(fail("no case whose scripts pass"))
    val ls = ledger(c)
    val s = LedgerState.utxo[Conway].get(ls)
    val (after, _) = eraUpdate(c, ConwayLedgerState.cert.get(ls), s)
    val otherPp = c.flatPp.copy(minFeeA = c.flatPp.minFeeA + 1)
    val otherGov =
      LedgerState.utxo[Conway].get(ConwayNewEpochState.ls.get(Build.nes[Conway](5, otherPp))).gov
    val govMutant = after.copy[Conway](gov = otherGov)
    assertEquals(UtxoRecordings.stateCell(govMutant), UtxoRecordings.stateCell(after))
    assert(!summon[cats.Eq[UTxOState[Conway]]].eqv(govMutant.copy(gov = s.gov), govMutant))
    val u = after.utxo.utxo
    val stampMutant = after.copy[Conway](utxo = UtxoTables(u.copy(stamp = u.stamp + 1)))
    assertEquals(stampMutant.utxo.utxo.toMap, after.utxo.utxo.toMap)
    assertNotEquals(UtxoRecordings.stateCell(stampMutant), UtxoRecordings.stateCell(after))
  }

  test(
    "the recording of the flat UTXOS's and UTXO's judgments holds this suite's alone, each once"
  ) {
    // the state judgments are those the tests above made, of the cases they name
    val stateIds =
      passing(everyCase).map(stateId("passing", _)) ++
        Vector(s"state/returning/${returningBase.name}, returning") ++
        (blockCases.filter(c => c.flat.isValid || c.flat.witnesses.redeemers.nonEmpty) ++ donated)
          .map(stateId("blocks", _))
    val ids = verdicts.map((c, _) => s"utxos/${c.name}") ++ stateIds
    assertEquals(ids.distinct.size, ids.size, "a judgment twice")
    assertEquals(
      ids.toSet,
      UtxoRecordings.utxos.keySet.filter(id => id.startsWith("utxos/") || id.startsWith("state/"))
    )
  }

  test("the recording holds each prefix's judgments, as many as were recorded") {
    def count(prefix: String) = UtxoRecordings.utxos.keySet.count(_.startsWith(prefix))
    assertEquals(
      Vector("utxos/", "state/passing/", "state/returning/", "state/blocks/").map(count),
      Vector(190, 68, 1, 128)
    )
  }
