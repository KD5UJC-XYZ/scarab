// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{DeBruijn, Program, Term, TermF}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus.{FlatCase, FlatExpected}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.text.{Evaluation, UplcPrinter}

/** Why the reference's conformance runner reads no program from Flat bytes (`getInputProg` and
  * `decodeFlatProg`, `PlutusConformance/Common.hs:383-398,562-575`, plutus 1.70.0.0 `507cab8c`).
  * The runner shows each as `parse/decode error`.
  */
enum FlatRefusal:
  /** An empty file, which the runner does not decode (`Common.hs:386-387`). */
  case Empty

  /** `unflat` refuses the bytes as an `UnrestrictedProgram` (`Common.hs:571-575`): the decoder with
    * no check on builtins, constant types or `constr` fields (`UntypedPlutusCore/Core/
    * Instance/Flat.hs:288-305`), which still refuses `constr` and `case` before version 1.1.0
    * (`Flat.hs:167-185`).
    */
  case Undecodable(error: FlatError)

  /** A variable that no enclosing `lam` binds, anywhere in the term: `unDeBruijnProgram`
    * (`Common.hs:550-554,567-568`) converts the whole term, the fields of a `constr` and the whole
    * of a `case` included (`UntypedPlutusCore/DeBruijn.hs:100-125`).
    */
  case FreeVariable(index: DeBruijn)

/** How a case run on its Flat file departs from the runner's expectation. The runner checks the
  * result and the budget as two tests (`discoverTests`, `Common.hs:232-238`), so a case can have a
  * mismatch of each.
  */
enum FlatMismatch[+R]:
  /** The reference reads the program; this decoder does not. */
  case Refused(refusal: FlatRefusal)

  /** The reference refuses the program (`parse/decode error`); this decoder reads it. */
  case Accepted(program: Program)

  /** The expected result does not decode as a program, so it cannot be compared: the runner's
    * golden file then holds the decoder's error (`decodeFlatExpected`, `Common.hs:352-354`), which
    * no evaluation matches.
    */
  case UnreadableExpectation(refusal: FlatRefusal)

  /** The result differs from the decoded expectation (`compareAlphaEq`, `Common.hs:438-440`): a
    * different version or de Bruijn term.
    */
  case Result(expected: Program, actual: Program)

  case Budget(expected: ExBudget, actual: ExBudget)

  /** The reference evaluates the program; the evaluator fails. */
  case UnexpectedFailure

  /** The reference fails (`evaluation failure`); the evaluator returns a result. */
  case UnexpectedSuccess(result: Program, budget: ExBudget)

  case Unavailable(reason: R)

/** The verdicts of a run: every case by name with its mismatches, none when it conforms. */
final case class FlatReport[R](verdicts: Vector[(String, List[FlatMismatch[R]])]):
  def mismatches: Vector[(String, FlatMismatch[R])] =
    verdicts.flatMap((name, ms) => ms.map(name -> _))
  def conforming: Int = verdicts.count(_._2.isEmpty)

  /** Each mismatch on a line, with its case's name. */
  def render(show: R => String): String =
    def budget(b: ExBudget) = s"cpu ${b.cpu.toLong} mem ${b.memory.toLong}"
    mismatches
      .map { (name, m) =>
        val what = m match
          case FlatMismatch.Refused(r) => s"the reference decodes it; refused here: $r"
          case FlatMismatch.Accepted(p) =>
            s"the reference refuses it; decoded here: ${UplcPrinter.program(p)}"
          case FlatMismatch.UnreadableExpectation(r) => s"the expected result does not decode: $r"
          case FlatMismatch.Result(e, a) =>
            s"result: expected ${UplcPrinter.program(e)}, got ${UplcPrinter.program(a)}"
          case FlatMismatch.Budget(e, a)      => s"budget: expected ${budget(e)}, got ${budget(a)}"
          case FlatMismatch.UnexpectedFailure => "the reference evaluates it; the evaluator fails"
          case FlatMismatch.UnexpectedSuccess(r, b) =>
            s"the reference fails; the evaluator returns ${UplcPrinter.program(r)} (${budget(b)})"
          case FlatMismatch.Unavailable(r) => s"incomplete: ${show(r)}"
        s"$name: $what"
      }
      .mkString("\n")

/** The conformance runner of the reference run on the `.flat` files (`--format flat`,
  * `PlutusConformance/Common.hs:98-120,509-542`, plutus 1.70.0.0 `507cab8c`) over an evaluator:
  * each program is decoded as an `UnrestrictedProgram` and closed as `decodeFlatProg` does,
  * evaluated, and its result compared with the decoded `.flat.expected` program and its budget with
  * `.budget.expected`, exactly. No case is excluded and there is no tolerance.
  */
object FlatConformance:

  /** The program the runner reads from Flat bytes (`getInputProg Flat`, `Common.hs:383-398`). It
    * also reads a `.flat.expected` result this way (`decodeFlatExpected`, `Common.hs:352-354`).
    */
  def program(bytes: Bytes): Either[FlatRefusal, Program] =
    if bytes.isEmpty then Left(FlatRefusal.Empty)
    else
      FlatDecoder
        .program(bytes, Checks.unrestricted)
        .left
        .map(FlatRefusal.Undecodable(_))
        .flatMap(p => freeVariable(p.term).map(FlatRefusal.FreeVariable(_)).toLeft(p))

  /** The first variable of `term`, in pre-order, that no enclosing `lam` binds. A `lam` binds at
    * the level outside it (`declareBinder`, `PlutusCore/DeBruijn/Internal.hs:240-243`) and its body
    * is one level in (`withScope`, 247-248); index `i` names level `current - i` (`ixToLevel`,
    * 372-373), so it is bound when `0 < i <= current`, unsigned. The walk keeps its stack on the
    * heap, so it is as deep as the term.
    */
  def freeVariable(term: Term): Option[DeBruijn] =
    @tailrec def go(pending: List[(Term, Long)]): Option[DeBruijn] = pending match
      case Nil => None
      case (t, level) :: rest =>
        t.unfix match
          case TermF.Var(name) =>
            val i = name.index.bits
            if i != 0L && java.lang.Long.compareUnsigned(i, level) <= 0 then go(rest)
            else Some(name)
          case TermF.LamAbs(body)              => go((body, level + 1) :: rest)
          case TermF.Apply(function, argument) => go((function, level) :: (argument, level) :: rest)
          case TermF.Force(body)               => go((body, level) :: rest)
          case TermF.Delay(body)               => go((body, level) :: rest)
          case TermF.Constr(_, fields)         => go(fields.toList.map(_ -> level) ++ rest)
          case TermF.Case(scrutinee, branches) =>
            go((scrutinee, level) :: branches.toList.map(_ -> level) ++ rest)
          case TermF.Constant(_) | TermF.Builtin(_) | TermF.Error => go(rest)
    go((term, 0L) :: Nil)

  /** The mismatches of case `c` under `evaluate`; none when it conforms. A refused program is
    * `parse/decode error` and a failed evaluation `evaluation failure` (`getTestedValue`,
    * `Common.hs:403-417`); the result is compared as `compareAlphaEq` compares
    * (`Common.hs:423-473`) and the budget as the budget test does (`testForBudget`,
    * `Common.hs:257-267`).
    */
  def check[R](c: FlatCase, evaluate: Program => Evaluation[R]): List[FlatMismatch[R]] =
    (c.expected, program(c.program)) match
      case (FlatExpected.ParseError, Left(_))  => Nil
      case (FlatExpected.ParseError, Right(p)) => List(FlatMismatch.Accepted(p))
      case (_, Left(refusal))                  => List(FlatMismatch.Refused(refusal))
      case (FlatExpected.EvaluationFailure, Right(p)) =>
        evaluate(p) match
          case Evaluation.Failed => Nil
          case Evaluation.Evaluated(t, b) =>
            List(FlatMismatch.UnexpectedSuccess(Program(p.version, t), b))
          case Evaluation.Unavailable(reason) => List(FlatMismatch.Unavailable(reason))
      case (FlatExpected.Evaluates(result, budget), Right(p)) =>
        program(result) match
          case Left(refusal) => List(FlatMismatch.UnreadableExpectation(refusal))
          case Right(expected) =>
            evaluate(p) match
              case Evaluation.Failed              => List(FlatMismatch.UnexpectedFailure)
              case Evaluation.Unavailable(reason) => List(FlatMismatch.Unavailable(reason))
              case Evaluation.Evaluated(t, b)     =>
                // the result keeps the version of the program (`CekEvaluator.hs:59-61`)
                val actual = Program(p.version, t)
                Option.when(actual != expected)(FlatMismatch.Result(expected, actual)).toList ++
                  Option.when(b != budget)(FlatMismatch.Budget(budget, b)).toList

  /** Every case of `cases` under `evaluate`. */
  def run[R](cases: Vector[FlatCase], evaluate: Program => Evaluation[R]): FlatReport[R] =
    FlatReport(cases.map(c => c.name -> check(c, evaluate)))
