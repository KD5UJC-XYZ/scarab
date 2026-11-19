// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import java.util.concurrent.atomic.AtomicLong
import xyz.kd5ujc.scarab.uplc.{Program, ProtocolMajor, ReferenceCorpus, SemanticsVariant, Term}
import xyz.kd5ujc.scarab.uplc.cost.{CekConformance, DefaultCostModels, ExBudget, StepKind}
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** A machine that may stop ([[CekMachine.runUntil]]) is the reference's until its stop gives a
  * reason: on every program of the conformance cases of plutus 1.71.0.0 (945 of the 1,009; the
  * other 64 are the reference's parse refusals, which have no program), in both budget modes, a
  * stop that is asked and never answers leaves the result, the budget and the logs as
  * [[CekMachine.run]] gives them; one that answers ends the evaluation with its reason, also one
  * that would never end.
  */
class CekStopSuite extends munit.FunSuite:
  private lazy val costs = DefaultCostModels.costs(SemanticsVariant.G)

  private def machine(program: Program): MachineParameters =
    CekConformance.machine(costs, CaserBuiltin.forProtocol(ProtocolMajor.Dijkstra, program.version))

  /** A stop that counts how often it is asked and never answers. */
  private final class Asked extends Stop[Nothing]:
    val times = AtomicLong(0)
    def apply(): Option[Nothing] =
      times.incrementAndGet()
      None

  /** A stop that answers `reason` the `n`th time it is asked. */
  private final class AfterAsked(n: Long, reason: String) extends Stop[String]:
    val times = AtomicLong(0)
    def apply(): Option[String] = Option.when(times.incrementAndGet() >= n)(reason)

  /** What a report shows: the discharged result, or the failure and the term it is about, the
    * budget and the logs.
    */
  private def shown(
      report: CekReport
  ): (Either[(CekError, Option[Term]), Term], BudgetState, Vector[String]) =
    val result = report.result match
      case success: CekResult.Success => Right(success.term)
      case CekResult.Failure(failure) => Left((failure.error, failure.cause.map(_.term)))
    (result, report.budget, report.logs)

  private lazy val cases =
    ReferenceCorpus.conformance(ReferenceCorpus.Plutus171).fold(p => fail(p.toString), identity)

  /** How a case reads: a program the reference runs, the parse refusal the reference records, or a
    * case this suite would drop, with why.
    */
  private enum Reading:
    case Runs(name: String, program: Program)
    case Refused(name: String)
    case Wrong(name: String, why: String)

  /** Each case read as its expectation says: a program for every case the reference runs, and no
    * program for the cases whose expectation is the reference's parse refusal; anything else is a
    * case this suite would drop, which it reports by name.
    */
  private lazy val read: (Vector[String], Vector[(String, Program)], Vector[(String, String)]) =
    val readings = cases.map { c =>
      (c.expected, UplcParser.program(c.program)) match
        case (ReferenceCorpus.Expected.ParseError, Left(_)) => Reading.Refused(c.name)
        case (ReferenceCorpus.Expected.ParseError, Right(_)) =>
          Reading.Wrong(c.name, "parsed, though the reference refuses it")
        case (_, Left(error))    => Reading.Wrong(c.name, error.toString)
        case (_, Right(program)) => Reading.Runs(c.name, program)
    }
    (
      readings.collect { case Reading.Refused(name) => name },
      readings.collect { case Reading.Runs(name, program) => name -> program },
      readings.collect { case Reading.Wrong(name, why) => name -> why }
    )

  test("the corpus is read whole: 945 programs to run, and the 64 the reference refuses to parse") {
    val (refused, programs, wrong) = read
    assertEquals(wrong, Vector.empty)
    assertEquals(cases.size, 1009)
    assertEquals(refused.size, 64)
    assertEquals(programs.size, 945)
  }

  test("on every program of the corpus, a stop that never answers changes nothing, in both modes") {
    val (_, programs, _) = read
    assertEquals(programs.size, 945)
    val asked = Asked()
    val modes = Vector(
      BudgetMode.Counting,
      BudgetMode.Restricting(ExBudget.of(1000000000L, 1000000L)),
      BudgetMode.Restricting(ExBudget.of(300000L, 2000L))
    )
    val differing = for
      (name, program) <- programs
      mode <- modes
      parameters = machine(program)
      plain = CekMachine.run(parameters, mode, program.term)
      stoppable <- CekMachine.runUntil(parameters, mode, program.term, asked) match
        case report: CekReport => Option.when(shown(report) != shown(plain))(name -> mode)
    yield stoppable
    assertEquals(differing, Vector.empty)
    assert(asked.times.get > 0, "the stop was never asked")
  }

  /** `(\x. x x) (\x. x x)`, which never ends. */
  private val endless: Program =
    UplcParser
      .program("(program 1.1.0 [(lam x [x x]) (lam x [x x])])")
      .fold(e => fail(e.toString), identity)

  test("a stop that answers ends an evaluation that would never end, with its reason") {
    val stop = AfterAsked(3, "stopped")
    CekMachine.runUntil(machine(endless), BudgetMode.Counting, endless.term, stop) match
      case reason: String    => assertEquals(reason, "stopped")
      case report: CekReport => fail(s"an endless evaluation ended: $report")
    assertEquals(stop.times.get, 3L)
  }

  test("a stop is asked once each time the counted steps are spent, not at every step") {
    // `[(lam x x) [(lam x x) ... (con unit ())]]`: no builtin, so the budget counts the steps
    val depth = 700
    val text = "[(lam x x) " * depth + "(con unit ())" + "]" * depth
    val program =
      UplcParser.program(s"(program 1.1.0 $text)").fold(e => fail(e.toString), identity)
    val asked = Asked()
    val report =
      CekMachine.runUntil(machine(program), BudgetMode.Counting, program.term, asked) match
        case report: CekReport => report
    val parameters = machine(program)
    val spent = report.budget.spent.cpu.toBigInt - parameters.costs.startup.cpu.toBigInt
    val steps = spent / parameters.costs.step(StepKind.Apply).cpu.toBigInt
    assertEquals(steps, BigInt(3 * depth + 1))
    assertEquals(BigInt(asked.times.get), steps / CekMachine.Slippage)
  }
