// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, ReferenceCorpus, SemanticsVariant, Term, TermF}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus.Expected
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, MachineValue}
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** The costs of variant E against every budget the Haskell evaluator wrote for an upstream
  * `builtin/semantics` program of plutus 1.70.0.0 that is one saturated application of a builtin to
  * constants, whatever the builtin, its denotation implemented or not: the program's budget is the
  * machine's start-up, one step per node (an apply per argument, a force per type argument, the
  * builtin, a constant per argument) and the builtin's cost, added with saturation. The machine
  * checks every program whose builtins have denotations ([[CekConformanceSuite]]); this suite
  * checks the costing of the others too.
  */
final class ConformanceCostingSuite extends munit.FunSuite:
  private given MachineValue[Constant] with
    def constantOf(value: Constant): Option[Constant] = Some(value)
    def ofConstant(constant: Constant): Constant = constant

  private final case class Application(
      function: DefaultFun,
      forces: Int,
      arguments: Vector[Constant]
  )

  /** The builtin, its forces and its constant arguments, if `term` is nothing else. */
  private def application(term: Term): Option[Application] =
    @tailrec def go(t: Term, forces: Int, arguments: List[Constant]): Option[Application] =
      t.unfix match
        case TermF.Builtin(f)   => Some(Application(f, forces, arguments.toVector))
        case TermF.Force(inner) => go(inner, forces + 1, arguments)
        case TermF.Apply(function, argument) =>
          argument.unfix match
            case TermF.Constant(c) => go(function, forces, c :: arguments)
            case _                 => None
        case _ => None
    go(term, 0, Nil).filter(a =>
      a.arguments.size == a.function.arity && a.forces == a.function.forces
    )

  test(
    "every single application of the upstream semantics programs costs what the Haskell budget says"
  ) {
    val costs = DefaultCostModels.costs(SemanticsVariant.E)
    val machine = costs.machineCosts
    val cases = ReferenceCorpus
      .conformance(ReferenceCorpus.Pinned)
      .fold(p => fail(p.toString), identity)
      .filter(_.name.startsWith("builtin/semantics/"))
    val checked = cases.flatMap { c =>
      c.expected match
        case Expected.Evaluates(_, total) =>
          UplcParser.program(c.program).toOption.flatMap(p => application(p.term)).map { a =>
            val n = a.arguments.size.toLong
            val steps = machine.startup + machine.step(StepKind.Apply).times(n) +
              machine.step(StepKind.Force).times(a.forces.toLong) +
              machine.step(StepKind.Builtin) + machine.step(StepKind.Constant).times(n)
            val cost = costs.costing(a.function)(a.arguments.map(Argument.Con[Constant](_))).total
            // The machine adds every charge with saturation; all are non-negative here, so their
            // order does not change the sum (a builtin costing maxBound saturates the budget).
            (c.name, a.function, steps + cost, total)
          }
        case _ => None
    }
    val disagreements = checked.collect {
      case (name, _, actual, expected) if actual != expected =>
        s"$name: start-up, steps and cost $actual, the reference's budget $expected"
    }
    assertEquals(disagreements, Vector.empty[String])
    val builtins = checked.map(_._2).distinct
    println(
      s"ConformanceCostingSuite: ${checked.size} single applications of ${builtins.size} builtins " +
        s"costed as the reference's budgets, of ${cases.size} builtin/semantics cases"
    )
    // The coverage this suite claims, bounded below so that a narrower selection shows.
    assert(checked.size >= 423, s"only ${checked.size} programs checked")
    assert(builtins.size >= 72, s"only ${builtins.size} builtins checked")
  }
