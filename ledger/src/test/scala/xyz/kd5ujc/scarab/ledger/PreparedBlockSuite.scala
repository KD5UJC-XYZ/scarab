// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.rules.EraBlock
import xyz.kd5ujc.scarab.plutus.*

class PreparedBlockSuite extends FunSuite:
  private val passing = TestLedger.scripted(true)
  private val failure: ScriptEvaluator[Id] = _ =>
    EvalResult.Failure(EvalFailure.OutOfBudget, ExUnits.zero, Vector.empty)

  private def check(evaluator: ScriptEvaluator[Id], txs: Vector[Tx] = PreparedBlockFixture.txs) =
    val serialSeen = Vector.newBuilder[Phase2.Observation]
    val parallelSeen = Vector.newBuilder[Phase2.Observation]
    val serial =
      PreparedBlockFixture.body(evaluator, txs, Some(o => { serialSeen += o; () })).serial
    val plan =
      PreparedBlockFixture.body(evaluator, txs, Some(o => { parallelSeen += o; () })).prepare
    val landed = plan.resolve(plan.batches.flatten.reverse.map(plan.execute))
    assertEquals(landed, Right(serial))
    assertEquals(
      parallelSeen.result().map(_.copy(clock = None)),
      serialSeen.result().map(_.copy(clock = None))
    )
    (serial, plan)

  test("a prepared full BBODY yields identical state, counters and ordered observations") {
    val (result, plan) = check(passing)
    result match
      case EraBlock.Outcome.Applied(_, stats) =>
        assertEquals(stats.succeeded, 3L)
        assertEquals(stats.integrityChecked, 3L)
      case other => fail(s"fixture did not reach phase 2: $other")
    assertEquals(plan.jobs.size, 3)
    assertEquals(plan.batches.map(_.size), Vector(3))
  }

  test("earlier script refusal precedes a later ordinary-rule refusal") {
    val txs = PreparedBlockFixture.txs
    val bad = txs(1).copy(body = txs(1).body.copy(inputs = Vector(TestLedger.txIn(100))))
    val (result, _) = check(failure, txs.updated(1, bad))
    assert(result.isInstanceOf[EraBlock.Outcome.Refused])
  }

  test("a later ordinary refusal is retained when earlier scripts succeed") {
    val txs = PreparedBlockFixture.txs
    check(passing, txs.updated(1, txs(1).copy(body = txs(1).body.copy(fee = -1))))
  }

  test("typed evaluator unavailability stays distinct for both validity flags") {
    val unsupported: ScriptEvaluator[Id] = _ =>
      EvalResult.Failure(
        EvalFailure.Unsupported(BackendFailure.NoResult),
        ExUnits.zero,
        Vector.empty
      )
    check(unsupported)
    check(unsupported, PreparedBlockFixture.txs.map(_.copy(isValid = false)))
  }

  test("collateral effects of validly invalid transactions and their counters match") {
    val (result, _) = check(failure, PreparedBlockFixture.txs.map(_.copy(isValid = false)))
    result match
      case EraBlock.Outcome.Applied(_, stats) => assertEquals(stats.failed, 3L)
      case other                              => fail(s"invalid-tag fixture failed: $other")
  }

  test("missing and foreign completions are never accepted as successful verdicts") {
    val body = PreparedBlockFixture.body(passing)
    val own = body.prepare
    val foreign = body.prepare
    assertEquals(own.resolve(Vector.empty), Right(body.serial))
    assertEquals(own.resolve(foreign.jobs.map(foreign.execute)), Right(body.serial))
  }

  test("raw evaluator defects land as the original defect") {
    val defect = new IllegalStateException("test backend defect")
    val evaluator: ScriptEvaluator[Id] = _ => throw defect
    val plan = PreparedBlockFixture.body(evaluator).prepare
    val results = plan.jobs.reverse.map(plan.execute)
    assert(plan.resolve(results).left.exists(_.cause eq defect))

  }

  test("block execution-unit limits reject before any machine starts") {
    val txs = PreparedBlockFixture.txs.map(t =>
      t.copy(witnesses =
        t.witnesses.copy(
          redeemers =
            t.witnesses.redeemers.map(_.copy(exUnits = PreparedBlockFixture.params.maxBlockExUnits))
        )
      )
    )
    val plan = PreparedBlockFixture.body(passing, txs).prepare
    assertEquals(plan.jobs.size, 0)
    assertEquals(plan.resolve(Vector.empty), Right(PreparedBlockFixture.body(passing, txs).serial))
  }

  test("a dependent spend sees its producer's tentative output and lands the same state") {
    val (result, plan) = check(passing, PreparedBlockFixture.dependent)
    assert(result.isInstanceOf[EraBlock.Outcome.Applied])
    assertEquals(plan.batches.map(_.map(_.index)), Vector(Vector(0, 2), Vector(1)))
  }

  test("later worker defects cannot overtake an earlier actual script refusal") {
    val first = PreparedBlockFixture
      .body(passing)
      .prepare
      .jobs
      .headOption
      .getOrElse(fail("no first job"))
      .requests
      .toOption
      .toVector
      .flatten
      .map(_._2)
      .toSet
    val defect = new IllegalArgumentException("later worker defect")
    val evaluator: ScriptEvaluator[Id] = request =>
      if first(request) then EvalResult.Failure(EvalFailure.OutOfBudget, ExUnits.zero, Vector.empty)
      else throw defect
    val body = PreparedBlockFixture.body(evaluator)
    val plan = body.prepare
    assertEquals(plan.resolve(plan.jobs.reverse.map(plan.execute)), Right(body.serial))
  }

  test("collection errors keep observer and refusal order behind earlier script verdicts") {
    val (result, plan) = check(passing, PreparedBlockFixture.collectionFailure)
    assert(result.isInstanceOf[EraBlock.Outcome.Refused])
    assertEquals(plan.jobs.size, 2)
    assert(plan.jobs(1).requests.isLeft)
    check(failure, PreparedBlockFixture.collectionFailure)
  }

  test("the pre-Conway BBODY path also retains full state and counters") {
    val body = PreparedBlockFixture.babbage(passing)
    val plan = body.prepare
    assertEquals(plan.jobs.size, 3)
    assertEquals(plan.resolve(plan.jobs.reverse.map(plan.execute)), Right(body.serial))
    assert(body.serial.isInstanceOf[EraBlock.Outcome.Applied])
  }
