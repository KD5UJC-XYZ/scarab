// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.cardano.ledger.{Language, MajorProtocolVersion}
import scalus.uplc.*
import scalus.uplc.builtin.Data
import scalus.uplc.eval.*

/** Test-only evaluation, downstream of complete translation parity checks. */
object TranslatedEvaluation:
  def evaluate(data: Data, model: String, source: String): Either[String, Budget] =
    for
      profile <- Pv9Profile.create(model, source)
      program <- Program.parseUplc(source).left.map(_.toString)
      result <-
        val vm = new PlutusVM(
          Language.PlutusV3,
          profile.parameters,
          BuiltinSemanticsVariant.C,
          Blake2bPlatform,
          MajorProtocolVersion.changPV
        )
        val spender = new RestrictingBudgetSpender(Pv9Profile.researchLimit.units)
        try
          vm.evaluateDeBruijnedTerm(
            DeBruijn.deBruijnTerm(Term.Apply(program.term, Term.Const(Constant.Data(data)))),
            spender
          ) match
            case Term.Const(Constant.Unit, _) => Right(Budget.from(spender.getSpentBudget))
            case _                            => Left("invalid-v3-return")
        catch case _: scalus.uplc.eval.EvaluationFailure => Left("explicit-error")
    yield result
