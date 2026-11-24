// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Id
import cats.syntax.all.*
import java.util.concurrent.{Callable, Executors}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import munit.Assertions
import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, Phase2, ScriptLocation}
import xyz.kd5ujc.scarab.ledger.era.CollectError
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraBlockRun, DijkstraPhase2}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalRequest, EvalResult, ScriptEvaluator}

/** Dijkstra's era run on a block prepared for the node's bounded parallel evaluation of its scripts
  * (ADR 0018), against its serial run ([[DijkstraBlockRun.program]]): the plan's result is the
  * serial run's, and so are the observations it tells the node's phase-2 observer
  * (`--plutus-cases`, [[DijkstraPhase2.Observation]]), whatever order its jobs run in, on as many
  * threads as the node's workers, and with none run at all. The node's own scheduler
  * (`ParallelPlutus`) is `DijkstraParallelSuite` 's (node).
  */
object DijkstraPlanned extends Assertions:
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
    * told, kept in order.
    */
  final class Observations:
    private val told = AtomicReference(Vector.empty[DijkstraPhase2.Observation])
    val observer: DijkstraPhase2.Observation => Unit = o =>
      val _ = told.updateAndGet(_ :+ o)
    def all: Vector[DijkstraPhase2.Observation] = told.get

  /** An observation's outcome as two runs that each evaluated their scripts compare it
    * ([[projected]]): each script by its location, its request and its verdict, a failure by its
    * reason's message, since a machine's failure holds terms, equal only as the same object.
    */
  enum Projection:
    case Ran(scripts: Vector[(ScriptLocation, EvalRequest, Option[String])])
    case Unavailable(
        scripts: Vector[(ScriptLocation, EvalRequest, Option[String])],
        location: ScriptLocation,
        request: EvalRequest,
        reason: EvalFailure.Unsupported
    )
    case Uncollected(errors: NonEmptyList[CollectError[Dijkstra]])

  /** Observations as two runs that each evaluated their scripts compare them: each batch by its id,
    * its bytes and its slot, and what its scripts came to ([[Projection]]).
    */
  def projected(
      observations: Vector[DijkstraPhase2.Observation]
  ): Vector[(Bytes, Block.Transaction, BigInt, Projection)] =
    def scripts(es: Vector[DijkstraPhase2.Evaluation]) =
      es.map(e =>
        (
          e.location,
          e.request,
          e.result match
            case EvalResult.Success(_, _)         => None
            case EvalResult.Failure(reason, _, _) => Some(reason.message)
        )
      )
    observations.map(o =>
      (
        o.id,
        o.transaction,
        o.slot,
        o.outcome match
          case DijkstraPhase2.Outcome.Ran(es) => Projection.Ran(scripts(es.toVector))
          case DijkstraPhase2.Outcome.Unavailable(es, location, request, reason) =>
            Projection.Unavailable(scripts(es), location, request, reason)
          case DijkstraPhase2.Outcome.Uncollected(errors) => Projection.Uncollected(errors)
      )
    )

  /** A block's run as a body program, made with an evaluator and, if any, the node's phase-2
    * observer.
    */
  type Program =
    (ScriptEvaluator[Id], Option[DijkstraPhase2.Observation => Unit]) => DijkstraBlockRun.Body

  /** What a checked block came to: the serial run's result, the plan's jobs, their dependency
    * batches by the place of each job's batch among the block's, and the observations the serial
    * run told.
    */
  final case class Checked(
      serial: DijkstraBlockRun.Result,
      jobs: Int,
      batches: Vector[Vector[Int]],
      observations: Vector[DijkstraPhase2.Observation]
  )

  /** How a check runs a body program with a number of workers: [[threaded]] here, the node's own
    * scheduler (`ParallelPlutus`) in the node's tests.
    */
  type Runner = (DijkstraBlockRun.Body, Int) => Either[Phase2.Defect, DijkstraBlockRun.Result]

  /** The node's worker counts checked. */
  val workers: Vector[Int] = Vector(1, 2, 4, 8)

  /** `body` run as the node's scheduler runs a body program with `n` workers: serially for one
    * worker or a block where preparing cannot pay ([[DijkstraBlockRun.Body.worthwhile]]), else
    * prepared, each dependency batch's jobs run on `n` threads at once, the batches in order, and
    * the plan resolved with every value.
    */
  def threaded(
      body: DijkstraBlockRun.Body,
      n: Int
  ): Either[Phase2.Defect, DijkstraBlockRun.Result] =
    if n == 1 || !body.worthwhile then Either.catchNonFatal(body.serial).leftMap(Phase2.Defect(_))
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

  /** The plan's result `planned` is the serial run's `serial`: the same BBODY state, proposals and
    * counters, or the same failures, or the same defect with the same naming.
    */
  def same(
      name: String,
      serial: DijkstraBlockRun.Result,
      planned: Either[Phase2.Defect, DijkstraBlockRun.Result]
  ): Unit =
    planned match
      case Left(defect)   => fail(s"$name: the plan's defect $defect")
      case Right(outcome) => assertEquals(outcome, serial, s"$name: the plan")

  /** The block `programOf` makes with an evaluator, run serially with `base` (kept, [[Shared]]) and
    * the node's phase-2 observer, and again without one, to the same result; and prepared with the
    * same results and the observer: resolved with its jobs run last batch first, each batch's last
    * first; with none run, each then evaluated where it lands; and with as many workers as each of
    * the node's worker counts (`runner`, [[threaded]] by default), with and without the observer;
    * each to the serial run's result, and, observed, to the serial run's observations, in its
    * order. An admitted block is run with the workers again, evaluating with `base` itself.
    */
  def check(name: String, base: ScriptEvaluator[Id], runner: Runner = threaded)(
      programOf: Program
  ): Checked =
    val shared = Shared(base)
    val told = Observations()
    val serial = programOf(shared, Some(told.observer)).serial
    assertEquals(programOf(shared.replaying, None).serial, serial, s"$name, not observed")
    // the plan's result and observations, the observer told only where the plan resolves
    def observed(variant: String)(
        run: Option[DijkstraPhase2.Observation => Unit] => Either[
          Phase2.Defect,
          DijkstraBlockRun.Result
        ]
    ): Unit =
      val again = Observations()
      same(variant, serial, run(Some(again.observer)))
      assertEquals(again.all, told.all, s"$variant: the observations")
    val plan = programOf(shared.replaying, None).prepare
    observed(s"$name, reversed") { o =>
      val observing = programOf(shared.replaying, o).prepare
      observing.resolve(observing.batches.reverse.flatMap(_.reverse).map(observing.execute))
    }
    observed(s"$name, none run")(o => programOf(shared.replaying, o).prepare.resolve(Vector.empty))
    workers.foreach { n =>
      observed(s"$name, $n workers")(o => runner(programOf(shared.replaying, o), n))
      same(s"$name, $n workers, not observed", serial, runner(programOf(shared.replaying, None), n))
    }
    // an admitted block holds no machine's term: its workers evaluate with `base` itself, the
    // overlapping evaluations real, to the same state, proposals and counters, and observations
    // that differ from the serial run's at most in a failure's terms ([[projected]])
    serial match
      case Right(Right(_)) =>
        workers.foreach { n =>
          val again = Observations()
          same(
            s"$name, $n workers evaluating",
            serial,
            runner(programOf(base, Some(again.observer)), n)
          )
          assertEquals(
            projected(again.all),
            projected(told.all),
            s"$name, $n workers evaluating: the observations"
          )
        }
      case Right(Left(_)) | Left(_) => ()
    Checked(serial, plan.jobs.size, plan.batches.map(_.map(_.index)), told.all)
