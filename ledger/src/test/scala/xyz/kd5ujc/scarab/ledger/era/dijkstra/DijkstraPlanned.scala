// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Id
import java.util.concurrent.{Callable, Executors}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import munit.Assertions
import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraBlockRun
import xyz.kd5ujc.scarab.plutus.{EvalRequest, EvalResult, ScriptEvaluator}

/** Dijkstra's era run on a block prepared for the node's bounded parallel evaluation of its scripts
  * (ADR 0018), against its serial run ([[DijkstraBlockRun.program]]): the plan's result is the
  * serial run's, whatever order its jobs run in, on as many threads as the node's workers, and with
  * none run at all. The node's own scheduler (`ParallelPlutus`) is `DijkstraParallelSuite` 's
  * (node).
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

  /** What a checked block came to: the serial run's result, the plan's jobs, and their dependency
    * batches by the place of each job's batch among the block's.
    */
  final case class Checked(
      serial: DijkstraBlockRun.Result,
      jobs: Int,
      batches: Vector[Vector[Int]]
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
    if n == 1 || !body.worthwhile then Right(body.serial)
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

  /** The block `programOf` makes with an evaluator, run serially with `base` (kept, [[Shared]]),
    * and prepared with the same results: resolved with its jobs run last batch first, each batch's
    * last first; with none run, each then evaluated where it lands; and on as many threads as each
    * of the node's worker counts ([[threaded]]); each to the serial run's result.
    */
  def check(name: String, base: ScriptEvaluator[Id], runner: Runner = threaded)(
      programOf: ScriptEvaluator[Id] => DijkstraBlockRun.Body
  ): Checked =
    val shared = Shared(base)
    val serial = programOf(shared).serial
    val plan = programOf(shared.replaying).prepare
    same(
      s"$name, reversed",
      serial,
      plan.resolve(plan.batches.reverse.flatMap(_.reverse).map(plan.execute))
    )
    same(s"$name, none run", serial, programOf(shared.replaying).prepare.resolve(Vector.empty))
    workers.foreach(n => same(s"$name, $n workers", serial, runner(programOf(shared.replaying), n)))
    Checked(serial, plan.jobs.size, plan.batches.map(_.map(_.index)))
