// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.*

class Phase2UnavailableSuite extends FunSuite:
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
  private val scripts: Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(script => script.hash -> script)
      .toMap ++ c.tx.witnesses.scriptsByHash
  private val limit = EvalFailure.Unsupported("test interpreter has no implementation")
  private val unavailable: ScriptEvaluator[Id] = _ =>
    EvalResult.Failure(limit, ExUnits.zero, Vector.empty)

  private def checked(tx: Tx, evaluator: ScriptEvaluator[Id]) =
    TxChecks.phase2(
      c.params,
      PlutusStats.zero,
      tx,
      TxEnv(
        c.slot,
        189,
        0,
        clock = Some(slot => c.posix(slot).fold(error => fail(error.message), identity)),
        evaluator = evaluator
      ),
      scripts,
      tx.body.inputs.flatMap(c.spent.get),
      tx.body.referenceInputs.flatMap(c.references.get),
      Set(0 -> BigInt(0))
    )

  test("implementation unavailability supplies no verdict for either transaction validity flag") {
    for valid <- Vector(true, false) do
      val tx = c.tx.copy(isValid = valid)
      val outcome = Phase2.evaluate(
        tx,
        c.params,
        scripts,
        c.spent,
        c.references,
        c.posix,
        unavailable
      )
      outcome match
        case Right(Phase2.Outcome.Unavailable(0, _, `limit`)) => ()
        case other => fail(s"unexpected phase-2 outcome: $other")
      checked(tx, unavailable) match
        case Left(LedgerFailure.Unavailable(id, _, `limit`)) => assertEquals(id, tx.body.id)
        case other => fail(s"unexpected transaction outcome: $other")
  }

  test("a proven script failure still supplies the invalid-tag collateral verdict") {
    val failing: ScriptEvaluator[Id] =
      _ => EvalResult.Failure(EvalFailure.OutOfBudget, ExUnits.zero, Vector.empty)
    assert(checked(c.tx.copy(isValid = false), failing).isRight)
    checked(c.tx.copy(isValid = true), failing) match
      case Left(LedgerFailure.Rejected(_, _)) => ()
      case other                              => fail(s"unexpected script-failure outcome: $other")
  }
