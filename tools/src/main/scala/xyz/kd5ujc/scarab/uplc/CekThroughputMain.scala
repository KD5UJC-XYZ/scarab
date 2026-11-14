// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import scalus.cardano.ledger.{MajorProtocolVersion, Word64 as SWord64}
import scalus.uplc.{Constant as SConstant, DefaultFun as SFun, NamedDeBruijn, Term as STerm}
import scalus.uplc.eval.{CountingBudgetSpender, NoLogger, PlutusVM}
import xyz.kd5ujc.scarab.uplc.builtin.{CostingFunction, Argument, MachineValue}
import xyz.kd5ujc.scarab.uplc.cek.{
  BudgetMode,
  CaserBuiltin,
  CekMachine,
  CekResult,
  MachineParameters,
  StubBuiltins,
  Terms
}
import xyz.kd5ujc.scarab.uplc.cost.{CekConformance, CostStream, DefaultCostModels, ExBudget}

/** Development tool: the throughput of the own CEK machine against Scalus's on synthetic loops, in
  * machine steps per second.
  *
  * `tools/runMain xyz.kd5ujc.scarab.uplc.CekThroughputMain [rounds]`
  *
  * The loops (`Terms.countdown`, a fixed-point countdown through `lessThanEqualsInteger`,
  * `ifThenElse` and `addInteger`; `Terms.constrSum`, a list of `constr` built and summed with
  * `case`) run on both machines with counting budgets and real costing on both sides: ours with the
  * core builtins of variant E (`builtin.core`) and the default variant-E cost model
  * (`CekConformance.machine`, `DefaultCostModels`: the machine's step costs and every builtin's
  * costing function), Scalus's as `PlutusVM.makePlutusV3VM` at protocol 11 builds it, with its own
  * default V3 cost model (not the repository's corrected machine). The step count is our machine's,
  * with every step costing one CPU unit and the builtins nothing. Both results are checked before
  * timing; the rounds then alternate between the machines after a warm-up, and the median round of
  * each is reported.
  */
object CekThroughputMain:
  private final case class Workload(name: String, term: Term, expected: BigInt)

  def main(args: Array[String]): Unit =
    val rounds = args.headOption.flatMap(_.toIntOption).getOrElse(15)
    val workloads = Vector(
      Workload("countdown 200000", Terms.countdown(200000), 0),
      Workload("constr sum 50000", Terms.constrSum(50000), BigInt(50000) * 50001 / 2)
    )
    val vm = PlutusVM.makePlutusV3VM(MajorProtocolVersion(11))
    val core = StubBuiltins.core(SemanticsVariant.E).fold(e => sys.error(e.toString), identity)
    val ours =
      CekConformance.machine(DefaultCostModels.costs(SemanticsVariant.E), CaserBuiltin.WithoutData)
    val free = new CostingFunction:
      def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
        CostStream.single(ExBudget.zero)
    val counter = MachineParameters(
      StubBuiltins.stepCounting,
      core.copy(costing = _ => free),
      CaserBuiltin.WithoutData
    )

    workloads.foreach { w =>
      val scalusTerm = toScalus(w.term)
      val steps = CekMachine.run(counter, BudgetMode.Counting, w.term).budget.spent.cpu.toLong

      def runOurs(): Unit =
        CekMachine.run(ours, BudgetMode.Counting, w.term).result match
          case CekResult.SuccessConstant(Constant.Integer(n)) if n == w.expected => ()
          case other => sys.error(s"${w.name}: our machine gave $other")
      def runScalus(): Unit =
        vm.evaluateDeBruijnedTerm(scalusTerm, CountingBudgetSpender(), NoLogger, false) match
          case STerm.Const(SConstant.Integer(n), _) if n == w.expected => ()
          case other => sys.error(s"${w.name}: Scalus gave $other")

      def timed(body: () => Unit): Long =
        val start = System.nanoTime
        body()
        System.nanoTime - start

      (1 to 5).foreach { _ =>
        runOurs(); runScalus()
      }
      val samples = (1 to rounds).map { i =>
        if i % 2 == 0 then (timed(runOurs), timed(runScalus))
        else
          val s = timed(runScalus)
          (timed(runOurs), s)
      }
      def median(xs: Seq[Long]): Long = xs.sorted.apply(xs.size / 2)
      val oursNs = median(samples.map(_._1))
      val scalusNs = median(samples.map(_._2))
      def rate(ns: Long): Double = steps / (ns / 1e9)
      println(
        f"real costing both sides  ${w.name}%-18s steps $steps%,d  ours ${oursNs / 1e6}%.1f ms (${rate(oursNs) / 1e6}%.2f M steps/s)  " +
          f"Scalus ${scalusNs / 1e6}%.1f ms (${rate(scalusNs) / 1e6}%.2f M steps/s)  " +
          f"ours/Scalus time ${oursNs.toDouble / scalusNs}%.2f"
      )
    }

  /** The same term for Scalus: de Bruijn indices kept, names invented. The loops are shallow. */
  private def toScalus(term: Term): STerm = term.unfix match
    case TermF.Var(i)          => STerm.Var(NamedDeBruijn("v", i.index.bits.toInt))
    case TermF.LamAbs(body)    => STerm.LamAbs("v", toScalus(body))
    case TermF.Apply(f, a)     => STerm.Apply(toScalus(f), toScalus(a))
    case TermF.Force(t)        => STerm.Force(toScalus(t))
    case TermF.Delay(t)        => STerm.Delay(toScalus(t))
    case TermF.Builtin(f)      => STerm.Builtin(SFun.valueOf(f.toString))
    case TermF.Error           => STerm.Error()
    case TermF.Constr(tag, fs) => STerm.Constr(SWord64(tag.bits), fs.map(toScalus).toList)
    case TermF.Case(s, bs)     => STerm.Case(toScalus(s), bs.map(toScalus).toList)
    case TermF.Constant(c) =>
      STerm.Const(c match
        case Constant.Integer(n) => SConstant.Integer(n)
        case Constant.Bool(b)    => SConstant.Bool(b)
        case Constant.Unit       => SConstant.Unit
        case other               => sys.error(s"no Scalus constant for $other"))
