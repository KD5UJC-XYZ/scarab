// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import xyz.kd5ujc.scarab.ledger.{Phase2, PreparedBlockFixture, TestLedger}
import xyz.kd5ujc.scarab.ledger.era.PlutusLoop
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayScriptedBlocks
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraRecordedBlocks, DijkstraScriptedBlocks}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  ScarabEvaluator,
  ScriptEvaluator
}

/** The declared work above which the node prepares a block's scripts for its workers
  * (`Phase2.pays`, #85, ADR 0018): a body that may be prepared but whose redeemers declare less is
  * not `worthwhile`, and the node's scheduler (`ParallelPlutus`) runs it serially at every worker
  * count; one that declares more is prepared. Either way its result (state, counters, refusal, the
  * naming of a script with no verdict) is the serial run's at one, two, four and eight workers. The
  * script-heavy blocks are Dijkstra's and Conway's blocks of [[PlutusLoop]]'s script
  * (`DijkstraScriptedBlocks`, `ConwayScriptedBlocks`), which keep the prepared path of the node's
  * own rule exercised: no recorded block of the Leios testnet declares much work.
  */
class PlutusThresholdSuite extends FailsOnUncaught:
  private val services: IO[Vector[(Int, ParallelPlutus[IO])]] =
    Vector(1, 2, 4, 8).traverse(n =>
      ParallelPlutus.Workers
        .from(n)
        .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
        .map(n -> _)
    )

  /** `p`, counting the preparations the scheduler takes of it. */
  private final class Counted[A](p: Phase2.Program[A]) extends Phase2.Program[A]:
    private val count = AtomicInteger(0)
    def serial: A = p.serial
    def hasScripts: Boolean = p.hasScripts
    def preparable: Boolean = p.preparable
    def declared: ExUnits = p.declared
    override def worthwhile: Boolean = p.worthwhile
    def prepare: Phase2.Plan[A] =
      val _ = count.incrementAndGet()
      p.prepare
    def prepared: Int = count.get

  /** The body `programOf` makes through the scheduler at every worker count: the serial run's
    * result each time, prepared exactly where there is more than one worker and the body is
    * `worthwhile`.
    */
  private def scheduled[A](name: String, programOf: () => Phase2.Program[A]): IO[Unit] =
    val serial = programOf().serial
    services.flatMap(_.traverse_ { (n, service) =>
      val counted = Counted(programOf())
      service(counted).map { result =>
        assertEquals(result, serial, s"$name, $n workers")
        assertEquals(
          counted.prepared,
          if n > 1 && counted.worthwhile then 1 else 0,
          s"$name, $n workers: prepared"
        )
      }
    })

  /** An evaluator giving no verdict for the `k` th request the serial run of `programOf` asks. */
  private def unjudgedAt[A](
      programOf: ScriptEvaluator[Id] => Phase2.Program[A],
      k: Int
  ): ScriptEvaluator[Id] =
    val asked = AtomicReference(Vector.empty[EvalRequest])
    val recording: ScriptEvaluator[Id] = request =>
      val _ = asked.updateAndGet(_ :+ request)
      ScarabEvaluator.default.evaluate(request)
    val _ = programOf(recording).serial
    val request = asked.get.lift(k).getOrElse(fail(s"no request $k"))
    r =>
      if r == request then
        EvalResult.Failure(
          EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(ExUnits(0, 0))),
          ExUnits(0, 0),
          Vector.empty
        )
      else ScarabEvaluator.default.evaluate(r)

  test("the threshold: a body pays from the steps it names, not one step below") {
    assert(Phase2.pays(ExUnits(0, Phase2.payingSteps)))
    assert(!Phase2.pays(ExUnits(BigInt(Long.MaxValue), Phase2.payingSteps - 1)))
  }

  test(
    "every recorded Leios testnet block that may be prepared: prepared exactly where its redeemers declare the steps, and the serial run's result at every worker count"
  ) {
    val preparable = DijkstraRecordedBlocks.samples.filter(s =>
      DijkstraRecordedBlocks.program(s, ScarabEvaluator.default).preparable
    )
    // both sides of the threshold among them
    val worthwhile =
      preparable.map(s => DijkstraRecordedBlocks.program(s, ScarabEvaluator.default).worthwhile)
    assert(worthwhile.contains(true) && worthwhile.contains(false), s"$worthwhile")
    preparable.traverse_ { s =>
      scheduled(
        s"block ${s.block}",
        () => DijkstraRecordedBlocks.program(s, ScarabEvaluator.default)
      )
    }
  }

  test("Dijkstra: a block of cheap scripts is not worthwhile, and the scheduler runs it serially") {
    val sample = DijkstraScriptedBlocks.honest(8, 0)
    val body = DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default)
    assert(body.preparable)
    assert(!body.worthwhile, s"${body.declared}")
    scheduled(
      "eight cheap batches",
      () => DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default)
    )
  }

  test(
    "Dijkstra: a block of heavy scripts is prepared, to the serial run's result, also with no verdict at each script"
  ) {
    val sample = DijkstraScriptedBlocks.honest(4, 300)
    val programOf = (e: ScriptEvaluator[Id]) => DijkstraRecordedBlocks.program(sample, e)
    val body = programOf(ScarabEvaluator.default)
    assert(body.worthwhile, s"${body.declared}")
    assertEquals(body.prepare.batches.map(_.size), Vector(4))
    assert(body.serial.exists(_.isRight), s"${body.serial}")
    scheduled("four heavy batches", () => programOf(ScarabEvaluator.default)) *>
      (0 until 4).toVector.traverse_ { k =>
        val unjudged = unjudgedAt(programOf, k)
        assert(programOf(unjudged).serial.isLeft, s"script $k: named")
        scheduled(s"four heavy batches, no verdict at script $k", () => programOf(unjudged))
      }
  }

  test(
    "Dijkstra: cheap scripts declaring heavy units are prepared, as only what they declare is known"
  ) {
    val sample = DijkstraScriptedBlocks.declaring(
      4,
      PlutusLoop.script(0),
      ExUnits(BigInt(1000000), Phase2.payingSteps / 4)
    )
    val body = DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default)
    assert(body.worthwhile, s"${body.declared}")
    scheduled(
      "four inflated batches",
      () => DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default)
    )
  }

  test("Conway: a block of cheap scripts runs serially, one of heavy scripts is prepared") {
    val cheap = ConwayScriptedBlocks.honest(8, 0)
    val heavy = ConwayScriptedBlocks.honest(4, 300)
    val cheapBody = cheap.program(ScarabEvaluator.default)
    val heavyBody = heavy.program(ScarabEvaluator.default)
    assert(cheapBody.preparable && !cheapBody.worthwhile, s"${cheapBody.declared}")
    assert(heavyBody.worthwhile, s"${heavyBody.declared}")
    assert(heavyBody.serial.exists(_.isRight), s"${heavyBody.serial}")
    scheduled("eight cheap transactions", () => cheap.program(ScarabEvaluator.default)) *>
      scheduled("four heavy transactions", () => heavy.program(ScarabEvaluator.default)) *>
      (0 until 4).toVector.traverse_ { k =>
        val unjudged = unjudgedAt(heavy.program, k)
        scheduled(
          s"four heavy transactions, no verdict at script $k",
          () => heavy.program(unjudged)
        )
      }
  }

  test("the flat rules' body: three cheap scripts may be prepared but are run serially") {
    val body = PreparedBlockFixture.body(TestLedger.scripted(true))
    assert(body.preparable)
    assertEquals(body.declared, ExUnits(30000, 30000000))
    assert(!body.worthwhile)
    scheduled("the flat body", () => PreparedBlockFixture.body(TestLedger.scripted(true)))
  }
