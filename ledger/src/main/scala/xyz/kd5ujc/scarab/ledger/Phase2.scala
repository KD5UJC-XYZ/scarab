// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import cats.data.{EitherT, NonEmptyVector, State}
import cats.mtl.Stateful
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
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

  /** An immutable block body program whose scripts the node can evaluate in bounded parallel
    * workers (ADR 0018): the serial rules ([[serial]]), the executable reference, or an ordered
    * preparation ([[prepare]]) whose plan yields the same result. The flat rules' body
    * (`EraBlock.Body`) and an era block run's (`ConwayBlockRun.Body`, `DijkstraBlockRun.Body`) are
    * each one.
    */
  trait Program[A]:
    def serial: A
    def hasScripts: Boolean

    /** Whether the body may be prepared: more than one transaction carries redeemers, and their
      * execution units are within the block's limit (BBODY's `validateExUnits`, which it checks
      * only after LEDGERS), so that what the workers evaluate ahead stays within what an admissible
      * block declares.
      */
    def preparable: Boolean

    /** The execution units the body's redeemers declare, summed over its transactions
      * (`getTotalExUnits`, the sum BBODY's `validateExUnits` takes).
      */
    def declared: ExUnits

    /** Whether the node prepares the body for its workers: it may be prepared ([[preparable]]), and
      * its scripts declare enough work for the preparation to pay ([[pays]]).
      */
    def worthwhile: Boolean = preparable && pays(declared)
    def prepare: Plan[A]

  /** The CPU steps a body's redeemers must declare, summed, for the node to prepare it for its
    * workers (#85, ADR 0018's amendment of 2026-11-24): below them preparing costs more than it
    * saves. Measured on 2026-11-24, each body serially and prepared at four workers in one JVM,
    * alternated: on the recorded Leios testnet blocks (`PlutusThresholdBench`, tools) all eight
    * that declare less lost 0.1 to 0.3 ms (10 to 30%) prepared in each of three runs, and those
    * above were within about 0.3 ms either way up to 0.5 billion steps; on Dijkstra's and Conway's
    * blocks of 2 to 32 synthetic scripts, preparing cost about 0.2 ms a block plus about 20 us a
    * job and saved about three quarters of the scripts' evaluation, crossing near 0.35 billion
    * steps; on the recorded preprod window (`ConwayBodiesReplaySuite`), the flat rules' body did
    * best with this floor, and Conway's era run, whose jobs also translate their contexts, lost
    * about 0.2% by it. Declared, not spent, steps decide, as only they are known before the scripts
    * run, and a script spends at most what it declares: a block declaring more than its scripts
    * spend is prepared at a loss of at most that overhead.
    */
  val payingSteps: BigInt = BigInt(200000000)

  /** Whether a body whose redeemers declare `units` declares enough work for preparing it to pay
    * ([[payingSteps]]).
    */
  def pays(units: ExUnits): Boolean = units.steps >= payingSteps

  /** A prepared body: its jobs in dependency batches, each executed alone into a value, and the
    * only route to the body's result, which checks every reached verdict in transaction order.
    * Missing or foreign completed values are evaluated directly, never treated as a success. An
    * implementation's [[Completed]] is its own (a class of the plan), so that a value another plan
    * executed with its own evaluator does not type-check in this plan's [[resolve]].
    */
  trait Plan[A]:
    type Job
    type Completed
    def batches: Vector[Vector[Job]]
    def execute(job: Job): Completed
    def resolve(completed: Vector[Completed]): Either[Defect, A]

  final class Completed private[Phase2] (
      private[ledger] val job: Job,
      private[ledger] val outcome: Either[Defect, Collected[Outcome]]
  )

  /** Only values cross the worker boundary. Raw defects are landed in order by the owner. */
  private[ledger] def execute(job: Job, evaluator: ScriptEvaluator[Id]): Completed =
    val outcome = Either.catchNonFatal(job.requests.map(run(_, evaluator))).leftMap(Defect(_))
    new Completed(job, outcome)

  /** Only the ledger's preparation factory can install this capability: the recorder of one block's
    * jobs `J`, the flat rules' ([[Preparation]]) or an era block run's.
    */
  sealed abstract class Recorder[J]:
    private[ledger] def append(job: J): Unit

  type Preparation = Recorder[Job]

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
  private[ledger] def capture[A](body: Preparation => A): (Either[Defect, A], Vector[Job]) =
    captured[Job, A](body)

  /** [[capture]] of the jobs `J` of any body program: the jobs recorded before a raw defect are
    * kept beside it, so that the plan lands them in order before the defect.
    */
  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private[ledger] def captured[J, A](body: Recorder[J] => A): (Either[Defect, A], Vector[J]) =
    val jobs = Vector.newBuilder[J]
    val recorder = new Recorder[J]:
      def append(job: J): Unit = { val _ = jobs.addOne(job); () }
    val result = Either.catchNonFatal(body(recorder)).leftMap(Defect(_))
    (result, jobs.result())

  /** Every required, provided Plutus script in the reference's collection order, reversed on
    * success. Missing redeemers keep accumulating after a setup failure, but later contexts are not
    * built (`Alonzo/Plutus/Evaluate.hs:141-185`, cardano-ledger f649f975). This is deliberately not
    * an accumulating traversal of every context error. The scripts needed and their contexts are
    * those of the era `E` ([[TxChecks.scriptsNeeded]], [[ScriptContexts.Environment]]), whatever
    * the protocol version; the evaluator is given the version.
    */
  def collect[E <: EraTag](
      tx: Tx,
      pp: ProtocolParams,
      scripts: Map[Bytes, Script],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt]
  )(using era: ShelleyBasedEra[E]): Collected[Vector[(Redeemer, EvalRequest)]] =
    val env = ScriptContexts.Environment(era, pp.protocolMajor.toInt, spent, references, posix)
    lazy val referenceScripts: Map[Bytes, Bytes] =
      (spent.valuesIterator ++ references.valuesIterator)
        .flatMap(_.referenceScript)
        .flatMap(s => s.plutus.map(s.hash -> _))
        .toMap
    val redeemers = tx.witnesses.redeemers.map(r => (r.tag, r.index) -> r).toMap
    val needed = TxChecks
      .redeemersNeeded(TxChecks.scriptsNeeded[E](tx.body, spent), scripts)
      .flatMap((purpose, hash) => scripts.get(hash).map(purpose -> _))
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
  def evaluate[E <: EraTag](
      tx: Tx,
      pp: ProtocolParams,
      scripts: Map[Bytes, Script],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt],
      evaluator: ScriptEvaluator[Id]
  )(using ShelleyBasedEra[E]): Collected[Outcome] =
    collect[E](tx, pp, scripts, spent, references, posix).map(requests => run(requests, evaluator))

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
      case ScriptKind.PlutusV4 => Right(PlutusLanguage.V4)
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
