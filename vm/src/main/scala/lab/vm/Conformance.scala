// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.uplc.DeBruijn

final case class VectorInput(name: String, source: String, expected: String, budget: String)
final case class ConformanceCheck(name: String, matched: Boolean, detail: String)

object Conformance:
  private val budgetPattern = "(?s)\\(\\{cpu:\\s*(\\d+)\\s*\\|\\s*mem:\\s*(\\d+)\\}\\)".r
  def expectedBudget(text: String): Either[String, Option[Budget]] = text.trim match
    case "evaluation failure" => Right(None)
    case budgetPattern(cpu, mem) =>
      for
        c <- cpu.toLongOption.toRight("CPU outside Long range")
        m <- mem.toLongOption.toRight("memory outside Long range")
      yield Some(Budget(c, m))
    case _ => Left("invalid upstream budget expectation")

  def check(input: VectorInput, evaluator: Evaluator): Either[String, ConformanceCheck] =
    expectedBudget(input.budget).flatMap { expected =>
      if input.expected.trim == "evaluation failure" then
        if expected.nonEmpty then Left("failure expectation must have failure budget sentinel")
        else
          val outcome = evaluator.evaluate(input.source)
          Right(
            ConformanceCheck(
              input.name,
              (outcome match
                case Outcome.EvaluationFailure("divideInteger-zero", _) => true
                case _                                                  => false
              ),
              s"expected evaluator failure; actual=$outcome; upstream supplies no failure budget"
            )
          )
      else
        Evaluator.parse(input.expected).left.map(_.toString).flatMap { program =>
          expected.toRight("successful expectation requires numeric budget").map { budget =>
            val result = evaluator.evaluate(input.source)
            def resultMatches(outcome: Outcome): Boolean = outcome match
              case Outcome.Success(term, spent) =>
                (DeBruijn
                  .deBruijnTerm(term) α_== DeBruijn.deBruijnTerm(program.term)) && spent == budget
              case _ => false
            val exact = evaluator.evaluate(input.source, budget)
            val lowCpu = evaluator.evaluate(input.source, budget.copy(cpu = budget.cpu - 1))
            val lowMem = evaluator.evaluate(input.source, budget.copy(memory = budget.memory - 1))
            val matched = resultMatches(result) && resultMatches(exact) &&
              lowCpu.isInstanceOf[Outcome.BudgetExhausted] && lowMem
                .isInstanceOf[Outcome.BudgetExhausted]
            ConformanceCheck(
              input.name,
              matched,
              s"expected CPU=${budget.cpu} memory=${budget.memory}; actual=$result; exact/CPU-1/memory-1=$exact/$lowCpu/$lowMem"
            )
          }
        }
    }
