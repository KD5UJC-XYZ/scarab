// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

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

/** Development tool: the throughput of the own CEK machine on synthetic loops, in machine steps per
  * second.
  *
  * `tools/runMain xyz.kd5ujc.scarab.uplc.CekThroughputMain [rounds]`
  *
  * The loops (`Terms.countdown`, a fixed-point countdown through `lessThanEqualsInteger`,
  * `ifThenElse` and `addInteger`; `Terms.constrSum`, a list of `constr` built and summed with
  * `case`) run with a counting budget and real costing: the core builtins of variant E
  * (`builtin.core`) and the default variant-E cost model (`CekConformance.machine`,
  * `DefaultCostModels`: the machine's step costs and every builtin's costing function). The step
  * count is the machine's, with every step costing one CPU unit and the builtins nothing. The
  * result is checked before timing; the rounds then run after a warm-up, and the median round is
  * reported.
  */
object CekThroughputMain:
  private final case class Workload(name: String, term: Term, expected: BigInt)

  def main(args: Array[String]): Unit =
    val rounds = args.headOption.flatMap(_.toIntOption).getOrElse(15)
    val workloads = Vector(
      Workload("countdown 200000", Terms.countdown(200000), 0),
      Workload("constr sum 50000", Terms.constrSum(50000), BigInt(50000) * 50001 / 2)
    )
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
      val steps = CekMachine.run(counter, BudgetMode.Counting, w.term).budget.spent.cpu.toLong

      def runOurs(): Unit =
        CekMachine.run(ours, BudgetMode.Counting, w.term).result match
          case CekResult.SuccessConstant(Constant.Integer(n)) if n == w.expected => ()
          case other => sys.error(s"${w.name}: the machine gave $other")

      def timed(body: () => Unit): Long =
        val start = System.nanoTime
        body()
        System.nanoTime - start

      (1 to 5).foreach(_ => runOurs())
      val samples = (1 to rounds).map(_ => timed(runOurs))
      def median(xs: Seq[Long]): Long = xs.sorted.apply(xs.size / 2)
      val oursNs = median(samples)
      def rate(ns: Long): Double = steps / (ns / 1e9)
      println(
        f"real costing  ${w.name}%-18s steps $steps%,d  ${oursNs / 1e6}%.1f ms (${rate(oursNs) / 1e6}%.2f M steps/s)"
      )
    }
