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
  private val limit = EvalFailure.Unsupported(BackendFailure.NoResult)
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
        clock = Some(
          PosixClock.Total(slot => c.posix(slot).fold(error => fail(error.message), identity))
        ),
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
        case Right(Phase2.Outcome.Unavailable(report, _, `limit`)) =>
          assertEquals(report.scripts, 0)
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

  test("a validity-tag mismatch retains the evaluator cause and its original exception") {
    val defect = new IllegalArgumentException("script machine refused")
    val reason = EvalFailure.Script(ScriptFailure(defect, Vector("before refusal")))
    val evaluator: ScriptEvaluator[Id] = _ => EvalResult.Failure(reason, ExUnits.zero, Vector.empty)
    checked(c.tx.copy(isValid = true), evaluator) match
      case Left(
            LedgerFailure.Rejected(
              _,
              PredicateFailure.Utxow(
                UtxowFailure.Utxo(
                  UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(true, Some(failures)))
                )
              )
            )
          ) =>
        assert(failures.forall(_.cause == reason))
        assert(failures.forall(_.request.isDefined))
        failures.headOption.map(_.cause) match
          case Some(EvalFailure.Script(ScriptFailure(cause: Throwable, logs))) =>
            assert(cause eq defect)
            assertEquals(logs, Vector("before refusal"))
          case other => fail(s"lost evaluator cause: $other")
      case other => fail(s"unexpected script-failure outcome: $other")
  }

  test("phase-1 backend unavailability never becomes malformed scripts, for either validity flag") {
    val script =
      scripts.values.find(_.kind.isPlutus).getOrElse(fail("fixture has no Plutus script"))
    val decoder: ScriptDecoder[Id] =
      (_, _, _) => Left(ScriptDecodeFailure.Unavailable(BackendFailure.NoResult))
    for valid <- Vector(true, false); witness <- Vector(true, false) do
      val base = c.tx.copy(isValid = valid, witnesses = c.tx.witnesses.copy(scripts = Vector.empty))
      val tx =
        if witness then base.copy(witnesses = base.witnesses.copy(scripts = Vector(script)))
        else
          val output = base.body.outputs.headOption.getOrElse(fail("fixture has no output"))
          base.copy(body = base.body.copy(outputs = Vector(output.copy(scriptRef = Some(script)))))
      TxChecks.scriptsWellFormed(c.params, tx, TxEnv(c.slot, 189, 0, decoder = decoder)) match
        case Left(LedgerFailure.Unavailable(id, ScriptLocation.Phase1(hash), _)) =>
          assertEquals(id, tx.body.id)
          assertEquals(hash, script.hash)
        case other => fail(s"unexpected phase-1 outcome: $other")
  }

  test("a proven decoder admission failure remains a malformed-script predicate failure") {
    val script =
      scripts.values.find(_.kind.isPlutus).getOrElse(fail("fixture has no Plutus script"))
    val decoder: ScriptDecoder[Id] =
      (_, _, _) => Left(ScriptDecodeFailure.Malformed(ScriptProblem.CborByteStringExpected))
    for valid <- Vector(true, false) do
      val tx = c.tx.copy(isValid = valid, witnesses = c.tx.witnesses.copy(scripts = Vector(script)))
      val expected = LedgerFailure.Rejected(
        tx.body.id,
        PredicateFailure.utxow(UtxowFailure.MalformedScriptWitnesses(Set(script.hash)))
      )
      assertEquals(
        TxChecks.scriptsWellFormed(c.params, tx, TxEnv(c.slot, 189, 0, decoder = decoder)),
        Left(expected)
      )
  }

  test("a defect a script triggers is the transaction's, a missing implementation is not") {
    import LedgerFailure.reach
    val defect = EvalFailure.Unsupported(BackendFailure.Defect(new NullPointerException("input")))
    val missing = EvalFailure.Unsupported(BackendFailure.Defect(new UnsatisfiedLinkError("native")))
    val script =
      scripts.values.find(_.kind.isPlutus).getOrElse(fail("fixture has no Plutus script"))
    for valid <- Vector(true, false) do
      val tx = c.tx.copy(isValid = valid)
      for (reason, expected) <- Vector(
          defect -> BackendFailure.Reach.Input,
          missing -> BackendFailure.Reach.Implementation,
          limit -> BackendFailure.Reach.Implementation
        )
      do
        val evaluator: ScriptEvaluator[Id] =
          _ => EvalResult.Failure(reason, ExUnits.zero, Vector.empty)
        checked(tx, evaluator) match
          case Left(failure: LedgerFailure.Unavailable) => assertEquals(failure.reach, expected)
          case other => fail(s"unexpected phase-2 outcome: $other")
      val witnessed = tx.copy(witnesses = tx.witnesses.copy(scripts = Vector(script)))
      for (reason, expected) <- Vector(
          BackendFailure.StackExhausted -> BackendFailure.Reach.Input,
          BackendFailure.BuiltinUnavailable(
            scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup
          ) -> BackendFailure.Reach.Implementation
        )
      do
        val decoder: ScriptDecoder[Id] = (_, _, _) => Left(ScriptDecodeFailure.Unavailable(reason))
        TxChecks.scriptsWellFormed(
          c.params,
          witnessed,
          TxEnv(c.slot, 189, 0, decoder = decoder)
        ) match
          case Left(failure: LedgerFailure.Unavailable) => assertEquals(failure.reach, expected)
          case other => fail(s"unexpected phase-1 outcome: $other")
  }
