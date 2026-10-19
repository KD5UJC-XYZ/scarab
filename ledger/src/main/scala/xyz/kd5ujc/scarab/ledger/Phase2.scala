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
  /** Everything one evaluation depended on, handed to an optional observer (tooling that records
    * evaluation cases); observing never changes the result.
    */
  final case class Observation(
      tx: Tx,
      params: ProtocolParams,
      slot: BigInt,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      clock: Option[BigInt => BigInt],
      outcome: Collected[Outcome]
  )

  /** The first script that failed, or the number of scripts that all succeeded. */
  enum Outcome:
    case Passed(scripts: Int)
    case Failed(scripts: Int, redeemer: Redeemer, reason: EvalFailure)

    /** An implementation limit supplies no script verdict, whichever validity flag the tx carries.
      */
    case Unavailable(scripts: Int, redeemer: Redeemer, reason: EvalFailure.Unsupported)

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
    val outcome =
      try Right(job.requests.map(run(_, evaluator)))
      catch case scala.util.control.NonFatal(cause) => Left(Defect(cause))
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
      clock: Option[BigInt => BigInt],
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
  private[ledger] def capture[A](body: Preparation => A): (Either[Defect, A], Vector[Job]) =
    val jobs = Vector.newBuilder[Job]
    val recorder = new Preparation:
      def append(job: Job): Unit = { val _ = jobs.addOne(job); () }
    val result =
      try Right(body(recorder))
      catch case scala.util.control.NonFatal(cause) => Left(Defect(cause))
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

  /** Evaluate in reference order, retaining the first failure and its original typed cause. */
  def run(requests: Vector[(Redeemer, EvalRequest)], evaluator: ScriptEvaluator[Id]): Outcome =
    @scala.annotation.tailrec
    def loop(rest: List[(Redeemer, EvalRequest)], done: Int): Outcome = rest match
      case Nil => Outcome.Passed(done)
      case (redeemer, request) :: tail =>
        evaluator.evaluate(request) match
          case EvalResult.Success(_, _) => loop(tail, done + 1)
          case EvalResult.Failure(reason: EvalFailure.Unsupported, _, _) =>
            Outcome.Unavailable(done, redeemer, reason)
          case EvalResult.Failure(reason, _, _) => Outcome.Failed(done, redeemer, reason)
    loop(requests.toList, 0)

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
