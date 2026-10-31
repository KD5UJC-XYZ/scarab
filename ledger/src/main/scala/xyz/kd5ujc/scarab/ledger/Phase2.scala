// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import cats.data.{EitherT, NonEmptyVector, State}
import cats.mtl.Stateful
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.plutus.*

/** Phase-2 validation: every redeemer is evaluated against its script with the script context the
  * ledger builds. A transaction passes phase 2 when all of its scripts succeed within the budgets
  * the redeemers declare; the block's `is_valid` flag must equal that verdict.
  *
  * The evaluator is a pure function of the request (see [[ScriptEvaluator]]), so this stays a pure
  * function of the ledger state, the transaction and the era-history clock.
  */
object Phase2:
  /** Inputs and collected outcome of one transaction's phase-2 processing, handed to optional
    * tooling such as the case recorder. The observer supplies no evaluation verdict.
    */
  final case class Observation(
      tx: Tx,
      params: ProtocolParams,
      slot: BigInt,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      clock: Option[PosixClock],
      outcome: Collected[Outcome]
  )

  /** An immutable machine result, in collection order, including budgets and trace output. */
  final case class Evaluation(redeemer: Redeemer, request: EvalRequest, result: EvalResult)

  final case class Failure(redeemer: Redeemer, request: EvalRequest, reason: EvalFailure)

  final case class Report(evaluations: Vector[Evaluation]):
    def scripts: Int = evaluations.size
    def used: ExUnits = evaluations.foldLeft(ExUnits.zero)((total, e) => total + e.result.spent)
    def logs: Vector[String] = evaluations.flatMap(_.result match
      case EvalResult.Success(_, logs)    => logs
      case EvalResult.Failure(_, _, logs) => logs)

  /** The reference retains every passing script and failure, in collection order. */
  enum Outcome:
    case Passed(report: Report)
    case Failed(report: Report, failures: NonEmptyVector[Failure])

    /** An implementation limit supplies no script verdict, whichever validity flag the tx carries.
      * Its completed prefix is evidence, never a substitute for the missing result.
      */
    case Unavailable(report: Report, redeemer: Redeemer, reason: EvalFailure.Unsupported)

    def passed: Boolean = this match
      case Passed(_) => true
      case _         => false

  type Collected[A] = Either[NonEmptyVector[CollectError], A]

  /** A block-local request group. Identity is intentional: completed values cannot be reused for
    * another block, even when its transaction bytes happen to match.
    */
  final class Job private[Phase2] (
      val index: Int,
      val requests: Collected[Vector[(Redeemer, EvalRequest)]],
      private[ledger] val observation: Collected[Outcome] => Observation
  )

  final case class Defect(cause: Throwable) extends DomainError

  final class Completed private[Phase2] (
      private[ledger] val job: Job,
      private[ledger] val outcome: Either[Defect, Collected[Outcome]]
  )

  /** Only values cross the worker boundary. Raw defects are landed in order by the owner. */
  private[ledger] def execute(job: Job, evaluator: ScriptEvaluator[Id]): Completed =
    val outcome = Either.catchNonFatal(job.requests.map(run(_, evaluator))).leftMap(Defect(_))
    new Completed(job, outcome)

  /** Only the ledger's preparation factory can install this capability. */
  sealed abstract class Preparation:
    private[ledger] def append(job: Job): Unit

  private[ledger] def job(
      index: Int,
      tx: Tx,
      pp: ProtocolParams,
      slot: BigInt,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      clock: Option[PosixClock],
      requests: Collected[Vector[(Redeemer, EvalRequest)]]
  ): Job =
    new Job(
      index,
      requests,
      outcome => Observation(tx, pp, slot, spent, references, clock, outcome)
    )

  /** Instance-local builder behind a pure, block-local interface. It avoids a second ledger pass on
    * the measured hot path; neither the capability nor the tentative state escapes the plan.
    */
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private[ledger] def capture[A](body: Preparation => A): (Either[Defect, A], Vector[Job]) =
    val jobs = Vector.newBuilder[Job]
    val recorder = new Preparation:
      def append(job: Job): Unit = { val _ = jobs.addOne(job); () }
    val result = Either.catchNonFatal(body(recorder)).leftMap(Defect(_))
    (result, jobs.result())

  /** Every required, provided Plutus script in the reference's collection order, reversed on
    * success. Missing redeemers keep accumulating after a setup failure, but later contexts are not
    * built (`Alonzo/Plutus/Evaluate.hs:141-185`). This is deliberately not an accumulating
    * traversal of every context error.
    */
  def collect(
      tx: Tx,
      pp: ProtocolParams,
      scripts: Map[Bytes, Script],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt]
  ): Collected[Vector[(Redeemer, EvalRequest)]] =
    val env = ScriptContexts.Environment(pp.protocolMajor.toInt, spent, references, posix)
    lazy val referenceScripts: Map[Bytes, Bytes] =
      (spent.valuesIterator ++ references.valuesIterator)
        .flatMap(_.referenceScript)
        .flatMap(s => s.plutus.map(s.hash -> _))
        .toMap
    val redeemers = tx.witnesses.redeemers.map(r => (r.tag, r.index) -> r).toMap
    val needed = Purpose.collection(tx.body, spent, pp.protocolMajor >= 9).flatMap { purpose =>
      purpose.scriptHash.flatMap(scripts.get).filter(_.kind.isPlutus).map(purpose -> _)
    }
    type Infos = Map[PlutusLanguage, Either[TranslationError, PlutusData]]
    type Prepared[A] = State[Infos, A]
    type Setup[A] = EitherT[Prepared, CollectError, A]
    val cache = Stateful[Prepared, Infos]
    def lift[A](value: Either[CollectError, A]): Setup[A] = EitherT.fromEither[Prepared](value)
    def translated[A](value: Either[TranslationError, A]): Setup[A] =
      lift(value.leftMap(CollectError.BadTranslation(_)))
    def info(language: PlutusLanguage): Setup[PlutusData] =
      EitherT(
        cache.get
          .flatMap { infos =>
            infos
              .get(language)
              .fold {
                val result = ScriptContexts.txInfo(language, tx, env)
                cache.modify(_.updated(language, result)).as(result)
              }(_.pure[Prepared])
          }
          .map(_.leftMap(CollectError.BadTranslation(_)))
      )

    def prepare(purpose: Purpose, script: Script, r: Redeemer): Setup[(Redeemer, EvalRequest)] =
      for
        language <- translated(languageOf(script))
        code <- translated(
          script.plutus
            .orElse(purpose.scriptHash.flatMap(referenceScripts.get))
            .toRight(TranslationError.NoScriptBytes(script.hash))
        )
        model <- lift(costModel(pp, language))
        txInfo <- info(language)
        args <- translated(
          ScriptContexts.arguments(language, txInfo, tx, r, purpose, pp.protocolMajor.toInt)
        )
      yield r -> EvalRequest(language, pp.protocolMajor.toInt, code, args, model, r.exUnits)

    type Requests = List[(Redeemer, EvalRequest)]
    needed
      .foldLeftM[Prepared, Collected[Requests]](Right(Nil)) { case (collected, (purpose, script)) =>
        redeemers.get((purpose.tag, BigInt(purpose.index))) match
          case None =>
            val missing = CollectError.NoRedeemer(purpose)
            Left(
              collected.fold(
                errors => NonEmptyVector(missing, errors.toVector),
                _ => NonEmptyVector.one(missing)
              )
            )
              .pure[Prepared]
          case Some(redeemer) =>
            collected match
              case Left(errors) => Left(errors).pure[Prepared]
              case Right(requests) =>
                prepare(purpose, script, redeemer).value
                  .map(_.leftMap(NonEmptyVector.one).map(_ :: requests))
      }
      .runA(Map.empty)
      .value
      .map(_.toVector)

  /** `posix` converts a slot to POSIX milliseconds. All contexts are prepared before evaluation; a
    * script failure cannot hide a later setup failure, whichever validity flag the tx carries.
    */
  def evaluate(
      tx: Tx,
      pp: ProtocolParams,
      scripts: Map[Bytes, Script],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt],
      evaluator: ScriptEvaluator[Id]
  ): Collected[Outcome] =
    collect(tx, pp, scripts, spent, references, posix).map(requests => run(requests, evaluator))

  /** Evaluate every script in reference order. A proven failure does not suppress later results;
    * backend unavailability and raw defects still supply no complete transaction verdict.
    * `ScriptResult` 's semigroup accumulates successes and failures
    * (ledger-core/Plutus/Evaluate.hs).
    */
  def run(requests: Vector[(Redeemer, EvalRequest)], evaluator: ScriptEvaluator[Id]): Outcome =
    type Evaluated[A] = Either[Outcome, A]
    val evaluated = requests.foldLeftM[Evaluated, Vector[Evaluation]](Vector.empty) {
      case (done, (redeemer, request)) =>
        evaluator.evaluate(request) match
          case EvalResult.Failure(reason: EvalFailure.Unsupported, _, _) =>
            Left(Outcome.Unavailable(Report(done), redeemer, reason))
          case result => Right(done :+ Evaluation(redeemer, request, result))
    }
    evaluated.fold(
      identity,
      evaluations =>
        val report = Report(evaluations)
        NonEmptyVector
          .fromVector(evaluations.collect {
            case Evaluation(r, request, EvalResult.Failure(reason, _, _)) =>
              Failure(r, request, reason)
          })
          .fold[Outcome](Outcome.Passed(report))(Outcome.Failed(report, _))
    )

  private[ledger] def languageOf(script: Script): Either[TranslationError, PlutusLanguage] =
    script.kind match
      case ScriptKind.PlutusV1 => Right(PlutusLanguage.V1)
      case ScriptKind.PlutusV2 => Right(PlutusLanguage.V2)
      case ScriptKind.PlutusV3 => Right(PlutusLanguage.V3)
      case ScriptKind.Native   => Left(TranslationError.NativeScriptRedeemer)

  private def costModel(
      pp: ProtocolParams,
      language: PlutusLanguage
  ): Either[CollectError, Vector[Long]] =
    pp.costModels
      .get(language.id)
      .toRight(CollectError.NoCostModel(language))
      .flatMap(m =>
        Either.cond(
          m.forall(_.isValidLong),
          m.map(_.toLong),
          CollectError.BadTranslation(TranslationError.CostModelParameterTooLarge)
        )
      )
