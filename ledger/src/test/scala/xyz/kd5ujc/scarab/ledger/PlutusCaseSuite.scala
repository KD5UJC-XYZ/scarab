// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.plutus.ExUnits
import cats.Id
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.*
import munit.FunSuite

/** Recorded Preview transactions (`fixtures/preview/plutus`, written by `follow --plutus-cases`)
  * replayed through the node's phase 2: the script data hash must be the recomputed one, every
  * script must evaluate to the block's `is_valid` flag, and a script given no budget must fail.
  */
class PlutusCaseSuite extends FunSuite:
  private val cases = PlutusCase.all

  test("fixtures are present, cover both languages and both verdicts") {
    assert(cases.size >= 10, cases.size)
    val kinds = cases.flatMap(_.tx.witnesses.scripts.map(_.kind)).toSet ++
      cases.flatMap(c =>
        (c.spent.values ++ c.references.values).flatMap(_.referenceScript).map(_.kind)
      )
    assert(kinds.contains(ScriptKind.PlutusV1) && kinds.contains(ScriptKind.PlutusV2), kinds)
    assert(cases.exists(_.tx.isValid))
    assert(cases.exists(!_.tx.isValid), "no is_valid = false transaction among the fixtures")
  }

  private def scriptsOf(c: PlutusCase): Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++
      c.tx.witnesses.scriptsByHash

  private def neededKinds(c: PlutusCase): Set[ScriptKind] =
    val scripts = scriptsOf(c)
    Purpose
      .all(c.tx.body, c.spent)
      .flatMap(_.scriptHash)
      .flatMap(scripts.get)
      .map(_.kind)
      .toSet

  private def evaluate(c: PlutusCase, evaluator: ScriptEvaluator[Id]) =
    Phase2.evaluate(c.tx, c.params, scriptsOf(c), c.spent, c.references, c.posix, evaluator)

  cases.foreach { c =>
    test(s"${c.name} (${c.kind}): script data hash is the recomputed one") {
      assertEquals(ScriptIntegrity.hash(c.tx, c.params, neededKinds(c)), c.tx.body.scriptDataHash)
    }

    test(s"${c.name} (${c.kind}): phase 2 agrees with is_valid = ${c.tx.isValid}") {
      evaluate(c, ScalusEvaluator.default) match
        case Right(Phase2.Outcome.Passed(n)) =>
          assert(c.tx.isValid, s"all $n scripts passed but the block says is_valid = false")
        case Right(Phase2.Outcome.Failed(_, r, reason)) =>
          assert(!c.tx.isValid, s"redeemer ${r.tag}.${r.index} failed: ${reason.message}")
        case Left(e) => fail(e.reason)
    }
  }

  test("with no budget every valid transaction fails phase 2 with OutOfBudget") {
    val starved: ScriptEvaluator[Id] =
      r => ScalusEvaluator.default.evaluate(r.copy(budget = ExUnits(0, 0)))
    cases.filter(_.tx.isValid).foreach { c =>
      evaluate(c, starved) match
        case Right(Phase2.Outcome.Failed(_, _, EvalFailure.OutOfBudget)) => ()
        case other => fail(s"${c.name}: $other")
    }
  }

  test("the budget the redeemers declare covers what the machine spends") {
    cases.filter(_.tx.isValid).foreach { c =>
      val spent = collection.mutable.Buffer.empty[(ExUnits, ExUnits)]
      val recording: ScriptEvaluator[Id] = r =>
        val result = ScalusEvaluator.default.evaluate(r)
        spent += (result.spent -> r.budget)
        result
      evaluate(c, recording)
      spent.foreach { case (used, budget) =>
        assert(used.fits(budget), s"${c.name}: $used > $budget")
      }
    }
  }
