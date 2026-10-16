// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.*
import munit.FunSuite

/** Preprod transaction c70243bb (block 2,216,025, slot 59,426,521, epoch 141, Babbage): 158
  * redeemers, one per script-locked input, all of one Plutus V1 script. Phase 2 once evaluated the
  * redeemers by a recursion that was not in tail position (a dozen frames per redeemer); a follower
  * restarted from a snapshot just before this block, with the code still interpreted, ran out of
  * stack on it and exited (`docs/findings.md`).
  *
  * The tests run phase 2 on a thread whose stack (256 KiB) is far too small for such a recursion.
  */
class PreprodManyRedeemersSuite extends FunSuite:
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-c70243bb.json"))

  private val scripts: Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++
      c.tx.witnesses.scriptsByHash

  private def evaluate(tx: Tx, evaluator: ScriptEvaluator[Id]) =
    Phase2.evaluate(tx, c.params, scripts, c.spent, c.references, c.posix, evaluator)

  test("the recorded transaction has 158 redeemers and is valid on chain") {
    assert(c.tx.isValid)
    assertEquals(c.tx.witnesses.redeemers.size, 158)
  }

  test("phase 2 passes all 158 scripts of the recorded transaction on a 256 KiB stack") {
    val outcome = PreprodDeepTx.onSmallStack(evaluate(c.tx, ScalusEvaluator.default))
    assertEquals(outcome, Right(Phase2.Outcome.Passed(158)))
  }

  test("the number of redeemers is not bounded by the stack: 20,000 on a 256 KiB stack") {
    val one = c.tx.witnesses.redeemers.head
    val many = c.tx.copy(witnesses = c.tx.witnesses.copy(redeemers = Vector.fill(20000)(one)))
    val succeeding: ScriptEvaluator[Id] = _ => EvalResult.Success(ExUnits(0, 0), Vector.empty)
    val outcome = PreprodDeepTx.onSmallStack(evaluate(many, succeeding))
    assertEquals(outcome, Right(Phase2.Outcome.Passed(20000)))
  }

  test(
    "the first failure stops the evaluation and names the redeemer, after any number before it"
  ) {
    val one = c.tx.witnesses.redeemers.head
    val many = c.tx.copy(witnesses = c.tx.witnesses.copy(redeemers = Vector.fill(20000)(one)))
    var calls = 0
    val failingAt: ScriptEvaluator[Id] = _ =>
      calls += 1
      if calls == 15000 then
        EvalResult.Failure(EvalFailure.OutOfBudget, ExUnits(0, 0), Vector.empty)
      else EvalResult.Success(ExUnits(0, 0), Vector.empty)
    val outcome = PreprodDeepTx.onSmallStack(evaluate(many, failingAt))
    assertEquals(outcome, Right(Phase2.Outcome.Failed(14999, one, EvalFailure.OutOfBudget)))
    assertEquals(calls, 15000)
  }
