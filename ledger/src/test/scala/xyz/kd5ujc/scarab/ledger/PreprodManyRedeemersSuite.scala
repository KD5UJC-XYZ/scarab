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

  private def evaluate(tx: Tx, evaluator: ScriptEvaluator[Id], spent: Map[TxIn, TxOut] = c.spent) =
    Phase2.evaluate(tx, c.params, scripts, spent, c.references, c.posix, evaluator)

  /** A reference redeemer map has unique pointers; widen the required input set with it. */
  private def manySpending(count: Int): (Tx, Map[TxIn, TxOut]) =
    val input = c.tx.body.inputs.headOption.getOrElse(fail("no original input"))
    val output = c.spent(input)
    val redeemer = c.tx.witnesses.redeemers.headOption.getOrElse(fail("no original redeemer"))
    val inputs = Vector.tabulate(count)(i => TxIn(input.id, BigInt(i)))
    val redeemers = Vector.tabulate(count)(i => redeemer.copy(index = BigInt(i)))
    (
      c.tx.copy(
        body = c.tx.body.copy(inputs = inputs),
        witnesses = c.tx.witnesses.copy(redeemers = redeemers)
      ),
      inputs.map(_ -> output).toMap
    )

  test("the recorded transaction has 158 redeemers and is valid on chain") {
    assert(c.tx.isValid)
    assertEquals(c.tx.witnesses.redeemers.size, 158)
  }

  test("phase 2 passes all 158 scripts of the recorded transaction on a 256 KiB stack") {
    val outcome = PreprodDeepTx.onSmallStack(evaluate(c.tx, ScalusEvaluator.default))
    assertEquals(outcome, Right(Phase2.Outcome.Passed(158)))
  }

  test("the number of redeemers is not bounded by the stack: 20,000 on a 256 KiB stack") {
    val (many, spent) = manySpending(20000)
    val succeeding: ScriptEvaluator[Id] = _ => EvalResult.Success(ExUnits(0, 0), Vector.empty)
    val outcome = PreprodDeepTx.onSmallStack(evaluate(many, succeeding, spent))
    assertEquals(outcome, Right(Phase2.Outcome.Passed(20000)))
  }

  test(
    "the first failure stops the evaluation and names the redeemer, after any number before it"
  ) {
    val (many, spent) = manySpending(20000)
    var calls = 0
    val failingAt: ScriptEvaluator[Id] = _ =>
      calls += 1
      if calls == 15000 then
        EvalResult.Failure(EvalFailure.OutOfBudget, ExUnits(0, 0), Vector.empty)
      else EvalResult.Success(ExUnits(0, 0), Vector.empty)
    val outcome = PreprodDeepTx.onSmallStack(evaluate(many, failingAt, spent))
    assertEquals(
      outcome,
      Right(Phase2.Outcome.Failed(14999, many.witnesses.redeemers(5000), EvalFailure.OutOfBudget))
    )
    assertEquals(calls, 15000)
  }
