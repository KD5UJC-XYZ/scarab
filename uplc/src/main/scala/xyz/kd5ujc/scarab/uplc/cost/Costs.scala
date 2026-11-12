// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Eval
import cats.syntax.all.*
import scala.annotation.tailrec

/** A lazy non-empty stream of costing integers, the reference's `CostStream`
  * (`PlutusCore/Evaluation/Machine/CostStream.hs:39-42`, plutus 1.70.0.0 `507cab8c`): the measures
  * of arguments, and the CPU or memory costs a costing function computes from them. The frozen
  * [[CostStream]] is the reference's `ExBudgetStream`, which [[Costs.zip]] makes from two of these.
  *
  * Its elements and their order are what the machine spends, one at a time, so every operation
  * keeps the reference's element structure, not only its sum. The rest of a stream is computed when
  * it is needed: a measurement or a costing function goes no further than the machine spends, and a
  * budget that runs out stops it (a `Data` value whose shared nodes unfold to an exponential size
  * is measured only as far as it is paid for). Every operation forces at most one element of its
  * inputs per element it gives, through `Eval`, so forcing a stream of any length takes constant
  * stack.
  */
enum Costs:
  case Last(cost: SatInt)
  case Cons(cost: SatInt, rest: Eval[Costs])

  /** The first cost. */
  def first: SatInt = this match
    case Last(cost)    => cost
    case Cons(cost, _) => cost

  /** `sumCostStream` (`CostStream.hs:71-79`): the costs added from the first, saturating. It forces
    * the whole stream.
    */
  def sum: SatInt =
    @tailrec def go(total: SatInt, costs: Costs): SatInt = costs match
      case Last(cost)       => total + cost
      case Cons(cost, rest) => go(total + cost, rest.value)
    this match
      case Last(cost)       => cost
      case Cons(cost, rest) => go(cost, rest.value)

  /** `mapCostStream` (`CostStream.hs:83-91`). */
  def map(f: SatInt => SatInt): Costs = this match
    case Last(cost)       => Last(f(cost))
    case Cons(cost, rest) => Cons(f(cost), rest.map(_.map(f)))

  /** The first `count` costs, or all of them if there are fewer, forcing no more of the stream. */
  def take(count: Int): Vector[SatInt] =
    @tailrec def go(costs: Costs, out: Vector[SatInt]): Vector[SatInt] =
      if out.sizeIs >= count then out
      else
        costs match
          case Last(cost) => out :+ cost
          case Cons(cost, rest) =>
            val next = out :+ cost
            if next.sizeIs >= count then next else go(rest.value, next)
    go(this, Vector.empty)

  /** Every cost, in order. It forces the whole stream. */
  def toVector: Vector[SatInt] = take(Int.MaxValue)

object Costs:

  /** One cost. */
  def single(cost: Long): Costs = Last(SatInt(cost))

  /** The stream of `costs` in order, none when there are none. */
  def of(costs: Seq[SatInt]): Option[Costs] =
    costs.reverseIterator.foldLeft(Option.empty[Costs]) {
      case (None, cost)       => Some(Last(cost))
      case (Some(rest), cost) => Some(Cons(cost, Eval.now(rest)))
    }

  /** `unconsCost` (`CostStream.hs:46-48`): the first cost and the rest, if there is one. */
  private def uncons(costs: Costs): (SatInt, Option[Eval[Costs]]) = costs match
    case Last(cost)       => (cost, None)
    case Cons(cost, rest) => (cost, Some(rest))

  /** `reconsCost` (`CostStream.hs:53-55`). */
  private def recons(cost: SatInt, rest: Option[Eval[Costs]]): Costs =
    rest.fold(Last(cost))(Cons(cost, _))

  /** `addCostStream` (`CostStream.hs:94-106`): two single costs add up to one; otherwise the two
    * streams are interleaved, one element from each in turn starting with the left, and once the
    * side whose turn it is has given its last element the other follows whole (`addCostStreamGo`).
    */
  def add(left: Costs, right: => Costs): Costs = left match
    case Last(l) =>
      right match
        case Last(r) => Last(l + r)
        case other   => interleave(left, Eval.now(other))
    case _ => interleave(left, Eval.later(right))

  /** `addCostStreamGo`: the element of the side whose turn it is, then the rest with the turns
    * swapped. Like the reference, it forces the other side only when the rest is forced, and the
    * side that just gave an element only when its turn comes again (the left stream is matched
    * first in `addCostStream`, so the right one is not looked at while the left goes on).
    */
  private def interleave(turn: Costs, other: Eval[Costs]): Costs = turn match
    case Last(cost)       => Cons(cost, other)
    case Cons(cost, rest) => Cons(cost, other.map(interleave(_, rest)))

  /** `minCostStream` (`CostStream.hs:110-132`): two single costs give their minimum; otherwise the
    * first cost of each side is compared, the smaller emitted and the difference put back in front
    * of the side that had the larger, until either side runs out (`minCostStreamGo`). The result
    * may be longer than either side.
    */
  def min(left: Costs, right: Costs): Costs = (left, right) match
    case (Last(l), Last(r)) => Last(l.min(r))
    case _                  => minGo(left, right)

  private def minGo(left: Costs, right: Costs): Costs =
    val (costL, restL) = uncons(left)
    val (costR, restR) = uncons(right)
    val order = costL.compare(costR)
    val (costMin, nextL, nextR) =
      if order < 0 then (costL, restL, Some(Eval.now(recons(costR - costL, restR))))
      else if order == 0 then (costL, restL, restR)
      else (costR, Some(Eval.now(recons(costL - costR, restL))), restR)
    recons(
      costMin,
      for
        l <- nextL
        r <- nextR
      yield (l, r).mapN(minGo)
    )

  /** `scaleLinearly` (`CostingFun/Core.hs:318-321`): every cost times `slope`, with `intercept`
    * added in front ([[add]]: folded into a single cost, or before a longer stream).
    */
  def scaleLinearly(intercept: SatInt, slope: SatInt, costs: Costs): Costs =
    add(Last(intercept), costs.map(slope * _))

  /** `scaleLinearlyTwoVariables` (`CostingFun/Core.hs:617-625`). */
  def scaleLinearlyTwoVariables(
      intercept: SatInt,
      slope1: SatInt,
      costs1: Costs,
      slope2: SatInt,
      costs2: Costs
  ): Costs =
    add(Last(intercept), add(costs1.map(slope1 * _), costs2.map(slope2 * _)))

  /** `scaleQuadratically` (`CostingFun/Core.hs:332-347`): `c0 + c1 x + c2 x^2` over a stream whose
    * costs add up to `x`, each cost `x_i` becoming `2 c2 x_i r + c2 x_i x_i + c1 x_i` where `r` is
    * the sum of the costs before it, and `c0` added in front.
    */
  def scaleQuadratically(c0: SatInt, c1: SatInt, c2: SatInt, costs: Costs): Costs =
    def f(x: SatInt, r: SatInt): SatInt = SatInt(2) * c2 * x * r + c2 * x * x + c1 * x
    def go(r: SatInt, costs: Costs): Costs = costs match
      case Last(x)       => Last(f(x, r))
      case Cons(x, rest) => Cons(f(x, r), rest.map(go(r + x, _)))
    add(Last(c0), go(SatInt.zero, costs))

  /** `zipCostStream` (`ExBudgetStream.hs:190-208`): CPU and memory costs paired element by element
    * into the machine's budgets; the shorter side is taken as zeros after its last element.
    */
  def zip(cpu: Costs, memory: Costs): CostStream = (cpu, memory) match
    case (Last(c), Last(m)) => CostStream.Last(ExBudget(c, m))
    case _                  => zipGo(cpu, memory)

  private def zipGo(cpu: Costs, memory: Costs): CostStream = (cpu, memory) match
    case (Last(c), Last(m)) => CostStream.Last(ExBudget(c, m))
    case (Last(c), Cons(m, ms)) =>
      CostStream.Cons(ExBudget(c, m), ms.map(budgets(SatInt.zero -> _)))
    case (Cons(c, cs), Last(m)) =>
      CostStream.Cons(ExBudget(c, m), cs.map(budgets(_ -> SatInt.zero)))
    case (Cons(c, cs), Cons(m, ms)) => CostStream.Cons(ExBudget(c, m), (cs, ms).mapN(zipGo))

  /** `costToExBudgetStream` (`ExBudgetStream.hs:176-181`): each cost made a budget by `budget`. */
  private def budgets(budget: SatInt => (SatInt, SatInt))(costs: Costs): CostStream = costs match
    case Last(cost) =>
      val (c, m) = budget(cost)
      CostStream.Last(ExBudget(c, m))
    case Cons(cost, rest) =>
      val (c, m) = budget(cost)
      CostStream.Cons(ExBudget(c, m), rest.map(budgets(budget)))
