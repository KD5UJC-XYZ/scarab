// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.{Id, Monad}
import cats.data.NonEmptyList
import cats.syntax.all.*
import java.util.concurrent.{Callable, Executors}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import munit.Assertions
import xyz.kd5ujc.scarab.ledger.{Phase2, PosixClock, ProtocolParams, Redeemer, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.era.{CollectError, Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayBlockRun, ConwayPhase2}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState}
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  ScriptEvaluator,
  ScriptFailure
}
import xyz.kd5ujc.scarab.uplc.cek.{CekError, CekFailure}
import xyz.kd5ujc.scarab.uplc.Term
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** Conway's era run on a block prepared for the node's bounded parallel evaluation of its scripts
  * (ADR 0018), against its serial run ([[ConwayBlockRun.program]]): the plan's result is the serial
  * run's, and so are the observations it tells the node's phase-2 observer (`--plutus-cases`,
  * [[ConwayPhase2.Observation]]), whatever order its jobs run in, on as many threads as the node's
  * workers, and with none run at all; as `DijkstraPlanned` checks Dijkstra's run. The node's own
  * scheduler (`ParallelPlutus`) runs them in `ConwayBbodyImpWorkersSuite` (node).
  */
object ConwayPlanned extends Assertions:
  /** The evaluator `base`, each result it gives kept by its request and the place of the request
    * among the equal ones asked before; and the same results given again ([[replaying]]), each
    * request answered by the result of its place, so that a prepared plan sees the serial run's
    * results (a machine failure's term compares by identity), whatever order its jobs run in. A
    * request the serial run never asked is `base` 's.
    */
  final class Shared(base: ScriptEvaluator[Id]) extends ScriptEvaluator[Id]:
    private val results = AtomicReference(Map.empty[(EvalRequest, Int), EvalResult])
    private val asked = AtomicReference(Map.empty[EvalRequest, Int])
    private def placed(counts: AtomicReference[Map[EvalRequest, Int]], request: EvalRequest): Int =
      counts
        .getAndUpdate(m => m.updated(request, m.getOrElse(request, 0) + 1))
        .getOrElse(request, 0)
    def evaluate(request: EvalRequest): EvalResult =
      val place = placed(asked, request)
      val result = base.evaluate(request)
      val _ = results.updateAndGet(_.updated((request, place), result))
      result
    def replaying: ScriptEvaluator[Id] =
      val again = AtomicReference(Map.empty[EvalRequest, Int])
      request => results.get.getOrElse((request, placed(again, request)), base.evaluate(request))

  /** The node's phase-2 observer of a run, as `--plutus-cases` gives it: each observation it is
    * told, kept in order, with the one clock every run of a check shares.
    */
  final class Observations(clock: PosixClock):
    private val told = AtomicReference(Vector.empty[ConwayPhase2.Observation])
    val observing: ConwayBlockRun.Observing = ConwayBlockRun.Observing(
      o =>
        val _ = told.updateAndGet(_ :+ o)
      ,
      Some(clock)
    )
    def all: Vector[ConwayPhase2.Observation] = told.get

  /** A script's failure as two runs compare it: a machine's failure by its error, the term the
    * reference attaches to it (`ErrorWithCause`), discharged and compared as a tree, and its trace,
    * since the failure holds the term's discharge, equal only as the same object; every other
    * failure as it is, whose fields compare by value.
    */
  enum FailureForm derives CanEqual:
    case Machine(error: CekError, cause: Option[Term], logs: Vector[String])
    case Other(failure: EvalFailure)

  object FailureForm:
    def of(failure: EvalFailure): FailureForm = failure match
      case EvalFailure.Script(
            ScriptFailure.Uplc(EvaluationError.Cek(CekFailure(error, cause)), logs)
          ) =>
        Machine(error, cause.map(_.term), logs)
      case EvalFailure.Script(_) | EvalFailure.Decode(_) | EvalFailure.Unsupported(_) |
          EvalFailure.OutOfBudget | EvalFailure.OpenTerm(_) =>
        Other(failure)

  /** A script's result with its failure in its comparable form ([[FailureForm]]). */
  enum ResultForm derives CanEqual:
    case Passed(used: ExUnits, logs: Vector[String])
    case Failed(failure: FailureForm, used: ExUnits, logs: Vector[String])

  /** What an observation says of its scripts, each failure in its comparable form. */
  enum OutcomeForm derives CanEqual:
    case Passed(evaluations: Vector[(Redeemer, EvalRequest, ResultForm)])
    case Failed(
        evaluations: Vector[(Redeemer, EvalRequest, ResultForm)],
        failures: Vector[(Redeemer, EvalRequest, FailureForm)]
    )
    case Unavailable(
        evaluations: Vector[(Redeemer, EvalRequest, ResultForm)],
        redeemer: Redeemer,
        reason: EvalFailure.Unsupported
    )

  /** An observation as two runs compare it: every field as it is, its outcome in its comparable
    * form ([[OutcomeForm]]).
    */
  final case class Projection(
      tx: Tx[TxLevel.Top, Conway],
      params: ProtocolParams,
      slot: BigInt,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      clock: Option[PosixClock],
      outcome: Either[NonEmptyList[CollectError[Conway]], OutcomeForm]
  )

  def projected(observations: Vector[ConwayPhase2.Observation]): Vector[Projection] =
    def result(r: EvalResult): ResultForm = r match
      case EvalResult.Success(used, logs) => ResultForm.Passed(used, logs)
      case EvalResult.Failure(reason, used, logs) =>
        ResultForm.Failed(FailureForm.of(reason), used, logs)
    def evaluations(report: Phase2.Report) =
      report.evaluations.map(e => (e.redeemer, e.request, result(e.result)))
    observations.map { o =>
      val outcome = o.outcome.map {
        case Phase2.Outcome.Passed(report) => OutcomeForm.Passed(evaluations(report))
        case Phase2.Outcome.Failed(report, failures) =>
          OutcomeForm.Failed(
            evaluations(report),
            failures.toVector.map(f => (f.redeemer, f.request, FailureForm.of(f.reason)))
          )
        case Phase2.Outcome.Unavailable(report, redeemer, reason) =>
          OutcomeForm.Unavailable(evaluations(report), redeemer, reason)
      }
      Projection(o.tx, o.params, o.slot, o.spent, o.references, o.clock, outcome)
    }

  /** A block's run as a body program, made with an evaluator and, if any, the node's phase-2
    * observer.
    */
  type Program = (ScriptEvaluator[Id], Option[ConwayBlockRun.Observing]) => ConwayBlockRun.Body

  /** What a checked block came to: the serial run's result, the plan's jobs, their dependency
    * batches by the place of each job's transaction in the block, whether the body is worth
    * preparing, and the observations the serial run told.
    */
  final case class Checked(
      serial: ConwayBlockRun.Result,
      jobs: Int,
      batches: Vector[Vector[Int]],
      worthwhile: Boolean,
      observations: Vector[ConwayPhase2.Observation]
  )

  /** How a check runs a body program with a number of workers, in the effect `F`: [[threaded]]
    * here, the node's own scheduler (`ParallelPlutus`) in `IO` in the node's tests.
    */
  type Runner[F[_]] =
    (ConwayBlockRun.Body, Int) => F[Either[Phase2.Defect, ConwayBlockRun.Result]]

  /** The node's worker counts checked. */
  val workers: Vector[Int] = Vector(1, 2, 4, 8)

  /** `body` run as the node's scheduler runs a body program with `n` workers, prepared wherever it
    * may be: serially for one worker or a block that may not be prepared
    * ([[ConwayBlockRun.Body.preparable]]), else prepared, below the declared work the node prepares
    * above ([[ConwayBlockRun.Body.worthwhile]], #85) too, so that the prepared path is checked on
    * every block of cheap scripts; each dependency batch's jobs run on `n` threads at once, the
    * batches in order, and the plan resolved with every value.
    */
  val threaded: Runner[Id] = (body, n) =>
    if n == 1 || !body.preparable then Either.catchNonFatal(body.serial).leftMap(Phase2.Defect(_))
    else
      val plan = body.prepare
      val pool = Executors.newFixedThreadPool(n)
      val completed =
        try
          plan.batches.flatMap { batch =>
            pool
              .invokeAll(
                batch.map(job => (() => plan.execute(job)): Callable[plan.Completed]).asJava
              )
              .asScala
              .toVector
              .map(_.get())
          }
        finally pool.shutdown()
      plan.resolve(completed)

  /** The plan's result `planned` is the serial run's `serial`: the same BBODY state (the ledger
    * state by its order, `cats.Eq`), blocks made and counters, or the same failures, or the same
    * defect.
    */
  def same(
      name: String,
      serial: ConwayBlockRun.Result,
      planned: Either[Phase2.Defect, ConwayBlockRun.Result]
  ): Unit =
    planned match
      case Left(defect) => fail(s"$name: the plan's defect $defect")
      case Right(Right(Right(p))) =>
        serial match
          case Right(Right(s)) =>
            assert(cats.Eq[LedgerState[Conway]].eqv(p.state.ls, s.state.ls), s"$name: the state")
            assertEquals(p.state.blocksMade, s.state.blocksMade, s"$name: blocks made")
            assertEquals(p.plutus, s.plutus, s"$name: the counters")
          case other => fail(s"$name: the plan admits the block the serial run judges $other")
      case Right(other) => assertEquals(other, serial, s"$name: the plan")

  /** The block `programOf` makes with an evaluator, run serially with `base` (kept, [[Shared]]) and
    * the node's phase-2 observer, and again without one, to the same result; and prepared with the
    * same results and the observer: resolved with its jobs run last batch first, each batch's last
    * first; with none run, each then evaluated where it lands; and with as many workers as each of
    * the node's worker counts (`runner`), with and without the observer; each to the serial run's
    * result, and, observed, to the serial run's observations, in its order. An admitted block is
    * run with the workers again, evaluating with `base` itself.
    */
  def check[F[_]: Monad](
      name: String,
      base: ScriptEvaluator[Id],
      clock: PosixClock,
      runner: Runner[F]
  )(programOf: Program): F[Checked] =
    val shared = Shared(base)
    val told = Observations(clock)
    val serial = programOf(shared, Some(told.observing)).serial
    same(s"$name, not observed", serial, Right(programOf(shared.replaying, None).serial))
    // the plan's result and observations, the observer told only where the plan resolves
    def observed(variant: String)(
        run: Observations => Either[Phase2.Defect, ConwayBlockRun.Result]
    ): Unit =
      val again = Observations(clock)
      same(variant, serial, run(again))
      assertEquals(projected(again.all), projected(told.all), s"$variant: the observations")
    // prepared, a body tells nothing before its plan resolves, but for one whose transactions
    // declare more units than the block may hold, which is never prepared: its plan, without a job,
    // is the serial run, made as it is prepared, which tells as it runs (`ConwayBlockRun.Body`)
    def prepared(variant: String, again: Observations): ConwayBlockRun.Plan =
      val observing = programOf(shared.replaying, Some(again.observing)).prepare
      if again.all.nonEmpty then
        assertEquals(observing.jobs, Vector.empty, s"$variant: told before the plan resolves")
        assertEquals(projected(again.all), projected(told.all), s"$variant: told when prepared")
      observing
    val body = programOf(shared.replaying, None)
    val plan = body.prepare
    observed(s"$name, reversed") { again =>
      val observing = prepared(s"$name, reversed", again)
      observing.resolve(observing.batches.reverse.flatMap(_.reverse).map(observing.execute))
    }
    observed(s"$name, none run")(again => prepared(s"$name, none run", again).resolve(Vector.empty))
    val withWorkers = workers.traverse_ { n =>
      val again = Observations(clock)
      for
        observedRun <- runner(programOf(shared.replaying, Some(again.observing)), n)
        _ = same(s"$name, $n workers", serial, observedRun)
        _ = assertEquals(
          projected(again.all),
          projected(told.all),
          s"$name, $n workers: the observations"
        )
        quiet <- runner(programOf(shared.replaying, None), n)
      yield same(s"$name, $n workers, not observed", serial, quiet)
    }
    // an admitted block holds no machine's term: its workers evaluate with `base` itself, the
    // overlapping evaluations real, to the same state, blocks made and counters, and observations
    // that differ from the serial run's at most in a failure's terms ([[projected]])
    val evaluating = serial match
      case Right(Right(_)) =>
        workers.traverse_ { n =>
          val again = Observations(clock)
          runner(programOf(base, Some(again.observing)), n).map { result =>
            same(s"$name, $n workers evaluating", serial, result)
            assertEquals(
              projected(again.all),
              projected(told.all),
              s"$name, $n workers evaluating: the observations"
            )
          }
        }
      case Right(Left(_)) | Left(_) => Monad[F].unit
    (withWorkers *> evaluating).as(
      Checked(serial, plan.jobs.size, plan.batches.map(_.map(_.index)), body.worthwhile, told.all)
    )
