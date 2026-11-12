// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import xyz.kd5ujc.scarab.uplc.{Program, Term}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus.{ConformanceCase, Expected}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget

/** What an evaluator says of a closed program: the reference runner's `EvaluationResult`
  * (`PlutusConformance/Common.hs:125`, plutus 1.70.0.0 `507cab8c`), less its decode error, which
  * the harness decides before it evaluates (`CekEvaluator.hs:54-57`).
  *
  * @tparam R
  *   why the evaluator cannot evaluate a program yet (a builtin not implemented, say), its own type
  */
enum Outcome[+R]:
  /** The machine returns `result` having spent `budget` (counting, `CekEvaluator.hs:59-61`). */
  case Evaluated(result: Term, budget: ExBudget)

  /** The machine fails, whatever the cause (`EvalFailure`). */
  case Failed

  /** The evaluator cannot evaluate this program: an incomplete gate, never a pass. */
  case Unavailable(reason: R)

/** How a case departs from the reference's expectation. The runner checks the result and the budget
  * as two tests (`discoverTests`, `Common.hs:232-238`), so a case can have a mismatch of each.
  */
enum Mismatch[+R]:
  /** The reference reads and closes the program; this parser does not. */
  case Refused(error: ProgramError)

  /** The reference refuses the program (`parse/decode error`); this parser reads and closes it. */
  case Accepted

  /** The expected result does not read as a closed program, so it cannot be compared. */
  case UnreadableExpectation(error: ProgramError)

  /** The result differs from the expected one up to the names of variables (`compareAlphaEq`,
    * `Common.hs:438-440`; `Eq (Term Name)`, `UntypedPlutusCore/Core/Instance/Eq.hs:24-28`): equal
    * versions and equal de Bruijn terms.
    */
  case Result(expected: Program, actual: Program)

  case Budget(expected: ExBudget, actual: ExBudget)

  /** The reference evaluates the program; the evaluator fails. */
  case UnexpectedFailure

  /** The reference fails (`evaluation failure`); the evaluator returns a result. */
  case UnexpectedSuccess(result: Program, budget: ExBudget)

  case Unavailable(reason: R)

/** The verdicts of a run: every case by name with its mismatches, none when it conforms. */
final case class Report[R](verdicts: Vector[(String, List[Mismatch[R]])]):
  def mismatches: Vector[(String, Mismatch[R])] =
    verdicts.flatMap((name, ms) => ms.map(name -> _))
  def conforming: Int = verdicts.count(_._2.isEmpty)

  /** Each mismatch on a line, with its case's name. */
  def render(show: R => String): String =
    mismatches
      .map { (name, m) =>
        val what = m match
          case Mismatch.Refused(e)               => s"the reference reads it; refused here: $e"
          case Mismatch.Accepted                 => "the reference refuses it; read here"
          case Mismatch.UnreadableExpectation(e) => s"the expected result does not read: $e"
          case Mismatch.Result(e, a) =>
            s"result: expected ${UplcPrinter.program(e)}, got ${UplcPrinter.program(a)}"
          case Mismatch.Budget(e, a) =>
            s"budget: expected cpu ${e.cpu.toLong} mem ${e.memory.toLong}, " +
              s"got cpu ${a.cpu.toLong} mem ${a.memory.toLong}"
          case Mismatch.UnexpectedFailure => "the reference evaluates it; the evaluator fails"
          case Mismatch.UnexpectedSuccess(r, b) =>
            s"the reference fails; the evaluator returns ${UplcPrinter.program(r)} " +
              s"(cpu ${b.cpu.toLong} mem ${b.memory.toLong})"
          case Mismatch.Unavailable(r) => s"incomplete: ${show(r)}"
        s"$name: $what"
      }
      .mkString("\n")

/** The conformance runner of the reference (`PlutusConformance/Common.hs`, `CekEvaluator.hs`) over
  * an evaluator: each program is read and closed as the reference's parser and `deBruijnTerm` do,
  * evaluated, and its result and budget compared exactly with the recorded ones. No case is
  * excluded and there is no tolerance.
  */
object Conformance:
  /** An evaluator: a closed program to its outcome. */
  type Evaluator[R] = Program => Outcome[R]

  /** The mismatches of case `c` under `evaluate`; none when it conforms. */
  def check[R](c: ConformanceCase, evaluate: Evaluator[R]): List[Mismatch[R]] =
    (c.expected, UplcParser.program(c.program)) match
      case (Expected.ParseError, Left(_))  => Nil
      case (Expected.ParseError, Right(_)) => List(Mismatch.Accepted)
      case (_, Left(e))                    => List(Mismatch.Refused(e))
      case (Expected.EvaluationFailure, Right(p)) =>
        evaluate(p) match
          case Outcome.Failed          => Nil
          case Outcome.Evaluated(t, b) => List(Mismatch.UnexpectedSuccess(Program(p.version, t), b))
          case Outcome.Unavailable(reason) => List(Mismatch.Unavailable(reason))
      case (Expected.Evaluates(text, budget), Right(p)) =>
        UplcParser.program(text) match
          case Left(e) => List(Mismatch.UnreadableExpectation(e))
          case Right(expected) =>
            evaluate(p) match
              case Outcome.Failed              => List(Mismatch.UnexpectedFailure)
              case Outcome.Unavailable(reason) => List(Mismatch.Unavailable(reason))
              case Outcome.Evaluated(t, b)     =>
                // the result keeps the version of the program (`CekEvaluator.hs:61`)
                val actual = Program(p.version, t)
                Option.when(actual != expected)(Mismatch.Result(expected, actual)).toList ++
                  Option.when(b != budget)(Mismatch.Budget(budget, b)).toList

  /** Every case of `cases` under `evaluate`. */
  def run[R](cases: Vector[ConformanceCase], evaluate: Evaluator[R]): Report[R] =
    Report(cases.map(c => c.name -> check(c, evaluate)))
