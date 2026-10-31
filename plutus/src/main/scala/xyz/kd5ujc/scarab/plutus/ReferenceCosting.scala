// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.data.NonEmptyVector
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scalus.cardano.ledger.ExUnits as ScalusExUnits
import scalus.uplc.{BuiltinSemanticsVariant, Constant, DefaultFun, DefaultUni}
import scalus.uplc.builtin.Data as UplcData
import scalus.uplc.eval.{
  BuiltinCostModel,
  BuiltinValueOps,
  CekValue,
  ConstCostingFun,
  CostModel,
  CostingFun,
  DefaultCostingFun,
  DropListCostingFun,
  ExpModCostingFunction,
  ExpModIntegerCostingFun,
  FiveArguments,
  FourArguments,
  IntegerToByteStringCostingFun,
  OneArgument,
  OneVariableLinearFunction,
  OneVariableQuadraticFunction,
  ReplicateByteCostingFun,
  ShiftOrRotateByteStringCostingFun,
  SixArguments,
  SubtractedSizesLinearFunction,
  ThreeArguments,
  TwoArguments,
  TwoVariableLinearFunction,
  TwoVariableQuadraticFunction,
  TwoVariableWithInteractionFunction,
  WriteBitsCostingFun
}

/** The cost of a saturated builtin application as plutus 1.70.0.0 (`507cab8c`) computes it, in
  * place of Scalus 1.4.0's. References are to `plutus-core/src/PlutusCore/` unless named.
  *
  * The reference measures each argument as a tree of costs (`ExMemoryUsage`,
  * `Evaluation/Machine/ExMemoryUsage.hs`), flattens it into a stream (`flattenCostRose`, lines
  * 132-159), runs the CPU and the memory model over the streams in [[SatInt]] arithmetic
  * (`Evaluation/Machine/CostingFun/Core.hs` lines 303-985, `Evaluation/Machine/CostStream.hs`) and
  * zips the two results into a stream of budgets (`Evaluation/Machine/ExBudgetStream.hs` lines
  * 58-77), which the machine spends one element at a time ([[ReferenceBudget]]). Scalus sums every
  * argument into one number, evaluates its models in another order of the same saturating
  * operations, divides with Java's `/` where the reference uses `dividedBy`, and returns one
  * budget. With cost-model parameters of mixed signs or at the bounds of `Int64` those differ
  * (issue 485); with the published parameters they agree.
  *
  * The model shapes (which costing function a builtin uses in a semantics variant) are taken from
  * Scalus's `BuiltinCostModel`, which `BuiltinCostingOracleSuite` checks against the reference for
  * every released builtin and variant; only their parameter values and the arithmetic are read
  * here.
  */
private[plutus] object ReferenceCosting:

  /** A `CostStream` (`CostStream.hs` lines 34-45): a non-empty sequence of costs whose meaning is
    * their sum, kept in the order the reference produces them because the machine spends them in
    * that order. `Last` is the reference's single-element `CostLast`; `Many` holds two or more.
    */
  enum CostStream:
    case Last(cost: SatInt)
    case Many(elements: NonEmptyVector[SatInt])

    def costs: NonEmptyVector[SatInt] = this match
      case Last(cost)     => NonEmptyVector.one(cost)
      case Many(elements) => elements

    /** `sumCostStream` (lines 71-79): a left fold. */
    def sum: SatInt = this match
      case Last(cost)     => cost
      case Many(elements) => elements.reduceLeft(_ + _)

    /** `mapCostStream` (lines 83-91). */
    def map(f: SatInt => SatInt): CostStream = this match
      case Last(cost)     => Last(f(cost))
      case Many(elements) => Many(elements.map(f))

    /** `addCostStream` (lines 94-106): two single costs add, otherwise the elements interleave. */
    def add(other: CostStream): CostStream =
      @tailrec
      def interleave(
          a: NonEmptyVector[SatInt],
          b: NonEmptyVector[SatInt],
          out: Vector[SatInt]
      ): NonEmptyVector[SatInt] =
        NonEmptyVector.fromVector(a.tail) match
          case Some(rest) => interleave(b, rest, out :+ a.head)
          case None       => b.prependVector(out :+ a.head)
      (this, other) match
        case (Last(l), Last(r)) => Last(l + r)
        case _                  => CostStream.of(interleave(costs, other.costs, Vector.empty))

    /** `minCostStream` (lines 110-132): the smaller head is emitted and the larger keeps the
      * difference; it ends when either stream does.
      */
    def min(other: CostStream): CostStream =
      @tailrec
      def go(
          l: NonEmptyVector[SatInt],
          r: NonEmptyVector[SatInt],
          out: Vector[SatInt]
      ): NonEmptyVector[SatInt] =
        val (costL, restL) = (l.head, l.tail)
        val (costR, restR) = (r.head, r.tail)
        val (smaller, nextL, nextR) =
          if costL < costR then (costL, restL, (costR - costL) +: restR)
          else if costL > costR then (costR, (costL - costR) +: restL, restR)
          else (costL, restL, restR)
        (NonEmptyVector.fromVector(nextL), NonEmptyVector.fromVector(nextR)) match
          case (Some(left), Some(right)) => go(left, right, out :+ smaller)
          case _                         => NonEmptyVector.one(smaller).prependVector(out)
      (this, other) match
        case (Last(l), Last(r)) => Last(l.min(r))
        case _                  => CostStream.of(go(costs, other.costs, Vector.empty))

  object CostStream:
    /** `CostLast`. */
    def last(cost: SatInt): CostStream = Last(cost)

    /** The stream of `costs`: a single cost is `CostLast`, as the reference builds it. */
    private[ReferenceCosting] def of(costs: NonEmptyVector[SatInt]): CostStream =
      if costs.tail.isEmpty then Last(costs.head) else Many(costs)

  /** `scaleLinearly` (`Core.hs` lines 318-321). */
  private def scaleLinearly(intercept: SatInt, slope: SatInt, xs: CostStream): CostStream =
    CostStream.last(intercept).add(xs.map(slope * _))

  /** `scaleQuadratically` (`Core.hs` lines 332-347): `c + f(x)` element by element, where `f` sees
    * the running sum `r` of the elements before it.
    */
  private def scaleQuadratically(c: SatInt, b: SatInt, a: SatInt, xs: CostStream): CostStream =
    val two = SatInt(2)
    def term(x: SatInt, r: SatInt): SatInt = two * a * x * r + a * x * x + b * x
    val costs = xs.costs
    // the running sum before the first element is zero, and zero added to a cost is that cost
    val (scaled, _) =
      costs.tail.foldLeft((NonEmptyVector.one(term(costs.head, SatInt.Zero)), costs.head)) {
        case ((out, r), x) => (out :+ term(x, r), r + x)
      }
    CostStream.last(c).add(CostStream.of(scaled))

  /** A costing function of the reference (`ModelOneArgument` to `ModelSixArguments`, `Core.hs`
    * lines 232-243, 568-591, 741-759, 844-852, 893-900, 940-947), its arguments named by position.
    */
  enum Model:
    case ConstantCost(cost: SatInt)
    case Linear(at: Int, intercept: SatInt, slope: SatInt)
    case Quadratic(at: Int, c0: SatInt, c1: SatInt, c2: SatInt)
    case LinearInTwo(x: Int, y: Int, intercept: SatInt, slope1: SatInt, slope2: SatInt)
    case AddedSizes(intercept: SatInt, slope: SatInt)
    case SubtractedSizes(intercept: SatInt, slope: SatInt, minimum: SatInt)
    case MultipliedSizes(intercept: SatInt, slope: SatInt)
    case MinSize(intercept: SatInt, slope: SatInt)
    case MaxSize(intercept: SatInt, slope: SatInt)
    case LinearOnDiagonal(constant: SatInt, intercept: SatInt, slope: SatInt)
    case ConstOffDiagonal(constant: SatInt, model: Model)
    case ConstBelowDiagonal(constant: SatInt, model: Model)
    case ConstAboveDiagonal(constant: SatInt, model: Model)
    case AboveAndBelowDiagonal(model: Model)
    case QuadraticInTwo(
        x: Int,
        y: Int,
        minimum: SatInt,
        c00: SatInt,
        c10: SatInt,
        c01: SatInt,
        c20: SatInt,
        c11: SatInt,
        c02: SatInt
    )
    case WithInteraction(c00: SatInt, c10: SatInt, c01: SatInt, c11: SatInt)
    case LiteralInYOrLinearInZ(intercept: SatInt, slope: SatInt)
    case LinearInMaxYZ(intercept: SatInt, slope: SatInt)
    case ExpMod(c00: SatInt, c11: SatInt, c12: SatInt)

  /** `run<N>ArgumentModel` (`Core.hs` lines 349-359, 627-737, 761-822, 856-868, 904-913, 951-961).
    */
  def run(model: Model, xs: Vector[CostStream]): CostStream =
    import Model.*
    def sums = (xs(0).sum, xs(1).sum)
    model match
      case ConstantCost(cost)                   => CostStream.last(cost)
      case Linear(at, intercept, slope)         => scaleLinearly(intercept, slope, xs(at))
      case Quadratic(at, c0, c1, c2)            => scaleQuadratically(c0, c1, c2, xs(at))
      case LinearInTwo(x, y, intercept, s1, s2) =>
        // `scaleLinearlyTwoVariables` (lines 617-625)
        CostStream.last(intercept).add(xs(x).map(s1 * _).add(xs(y).map(s2 * _)))
      case AddedSizes(intercept, slope) => scaleLinearly(intercept, slope, xs(0).add(xs(1)))
      case SubtractedSizes(intercept, slope, minimum) =>
        val (x, y) = sums
        scaleLinearly(intercept, slope, CostStream.last(minimum.max(x - y)))
      case MultipliedSizes(intercept, slope) =>
        val (x, y) = sums
        scaleLinearly(intercept, slope, CostStream.last(x * y))
      case MinSize(intercept, slope) => scaleLinearly(intercept, slope, xs(0).min(xs(1)))
      case MaxSize(intercept, slope) =>
        val (x, y) = sums
        scaleLinearly(intercept, slope, CostStream.last(x.max(y)))
      case LinearOnDiagonal(constant, intercept, slope) =>
        val (x, y) = sums
        if x == y then scaleLinearly(intercept, slope, CostStream.last(x))
        else CostStream.last(constant)
      case ConstOffDiagonal(constant, inner) =>
        val (x, y) = sums
        if x != y then CostStream.last(constant) else run(inner, Vector(CostStream.last(x)))
      case ConstBelowDiagonal(constant, inner) =>
        val (x, y) = sums
        if x > y then CostStream.last(constant)
        else run(inner, Vector(CostStream.last(x), CostStream.last(y)))
      case ConstAboveDiagonal(constant, inner) =>
        val (x, y) = sums
        if x < y then CostStream.last(constant)
        else run(inner, Vector(CostStream.last(x), CostStream.last(y)))
      case AboveAndBelowDiagonal(inner) =>
        val (x, y) = sums
        run(inner, Vector(CostStream.last(x.max(y)), CostStream.last(x.min(y))))
      case QuadraticInTwo(at1, at2, minimum, c00, c10, c01, c20, c11, c02) =>
        // `evaluateTwoVariableQuadraticFunction` (lines 424-443)
        val (x, y) = (xs(at1).sum, xs(at2).sum)
        CostStream.last(
          minimum.max(c00 + c10 * x + c01 * y + c20 * x * x + c11 * x * y + c02 * y * y)
        )
      case WithInteraction(c00, c10, c01, c11) =>
        // `evaluateTwoVariableWithInteractionFunction` (lines 536-550)
        val (x, y) = sums
        CostStream.last(c10 * x + c01 * y + c11 * (x * y) + c00)
      case LiteralInYOrLinearInZ(intercept, slope) =>
        if xs(1).sum == SatInt.Zero then scaleLinearly(intercept, slope, xs(2)) else xs(1)
      case LinearInMaxYZ(intercept, slope) =>
        scaleLinearly(intercept, slope, CostStream.last(xs(1).sum.max(xs(2).sum)))
      case ExpMod(c00, c11, c12) =>
        // `evaluateExpModCostingFunction` (lines 460-479)
        val (aa, ee, mm) = (xs(0).sum, xs(1).sum, xs(2).sum)
        val cost0 = c00 + c11 * ee * mm + c12 * ee * mm * mm
        CostStream.last(if aa <= mm then cost0 else cost0 + cost0.dividedBy(2))

  /** An argument of a builtin's denotation as the reference unlifts and measures it: the Haskell
    * type in the denotation's signature (`Default/Builtins.hs` lines 1099-2491).
    */
  enum Arg:
    /** `Opaque val a`: any value; a non-constant measures 1 (`ExMemoryUsage (CekValue …)`,
      * `UntypedPlutusCore/Evaluation/Machine/Cek/Internal.hs` lines 751-759).
      */
    case Opaque

    /** `SomeConstant uni a`, `SomeConstant uni [a]`, `(a, b)` and `Vector a`: a constant of the
      * shape, measured by its type.
      */
    case AnyConstant, ListOf, PairOf, ArrayOf

    /** Monomorphic types. `CInteger` and `CByteString` of the `ensurable` variants measure as
      * `Integer` and `ByteString` (`Default/Universe/Cardano.hs` lines 21-27); their bounds, which
      * Scalus's builtins do not check either, are issue 498.
      */
    case Integer, ByteString, Text, Unit, Bool, Data, DataList, DataPairList, IntegerList
    case G1, G2, MlResult, G1List, G2List, Value

    /** `Int`, `Word8` and `Word64`: an integer within the type's bounds (`readKnownAsInteger`,
      * `Default/Universe.hs` lines 585-606), measured as 1 (`ExMemoryUsage.hs` lines 267-273,
      * 343-346).
      */
    case Int, Word8, Word64

    /** The size measures of `ExMemoryUsage.hs`: `NumBytesCostedAsNumWords` (lines 287-291),
      * `IntegerCostedLiterally` (304-308), `TextCostedByByteLength` (336-341), `DataNodeCount`
      * (399-412) and `ValueMaxDepth` (452-463).
      */
    case NumBytes, Literally, TextBytes, DataNodes, ValueMaxDepth

  private val Word8Max = BigInt(255)
  private val Word64Max = (BigInt(1) << 64) - 1
  private val dataList = DefaultUni.List(DefaultUni.Data)
  private val dataPairList = DefaultUni.List(DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data))
  private val integerList = DefaultUni.List(DefaultUni.Integer)
  private val g1List = DefaultUni.List(DefaultUni.BLS12_381_G1_Element)
  private val g2List = DefaultUni.List(DefaultUni.BLS12_381_G2_Element)

  /** Whether the reference unlifts the value as the argument: if any argument of a saturated
    * application does not, the application costs nothing and fails (`toMonoF`, `Builtin/Meaning.hs`
    * lines 245-262: `builtinRuntimeFailure` with the cost `mempty`).
    */
  private def accepts(arg: Arg, value: CekValue): Boolean =
    import Constant as C
    def within(lower: BigInt, upper: BigInt) = value match
      case CekValue.VCon(C.Integer(i)) => lower <= i && i <= upper
      case _                           => false
    def listOf(uni: DefaultUni) = value match
      case CekValue.VCon(list: C.List) => list.tpe == uni
      case _                           => false
    arg match
      case Arg.Opaque => true
      case Arg.AnyConstant =>
        value match
          case _: CekValue.VCon => true
          case _                => false
      case Arg.ListOf =>
        value match
          case CekValue.VCon(_: C.List) | _: CekValue.VList => true
          case _                                            => false
      case Arg.PairOf  => value match { case CekValue.VCon(_: C.Pair) => true; case _ => false }
      case Arg.ArrayOf => value match { case CekValue.VCon(_: C.Array) => true; case _ => false }
      case Arg.Integer | Arg.NumBytes | Arg.Literally =>
        value match { case CekValue.VCon(_: C.Integer) => true; case _ => false }
      case Arg.ByteString =>
        value match { case CekValue.VCon(_: C.ByteString) => true; case _ => false }
      case Arg.Text | Arg.TextBytes =>
        value match { case CekValue.VCon(_: C.String) => true; case _ => false }
      case Arg.Unit => value match { case CekValue.VCon(C.Unit) => true; case _ => false }
      case Arg.Bool => value match { case CekValue.VCon(_: C.Bool) => true; case _ => false }
      case Arg.Data | Arg.DataNodes =>
        value match { case CekValue.VCon(_: C.Data) => true; case _ => false }
      case Arg.DataList     => listOf(dataList)
      case Arg.DataPairList => listOf(dataPairList)
      case Arg.IntegerList  => listOf(integerList)
      case Arg.G1List       => listOf(g1List)
      case Arg.G2List       => listOf(g2List)
      case Arg.G1 =>
        value match { case CekValue.VCon(_: C.BLS12_381_G1_Element) => true; case _ => false }
      case Arg.G2 =>
        value match { case CekValue.VCon(_: C.BLS12_381_G2_Element) => true; case _ => false }
      case Arg.MlResult =>
        value match { case CekValue.VCon(_: C.BLS12_381_MlResult) => true; case _ => false }
      case Arg.Value | Arg.ValueMaxDepth =>
        value match { case CekValue.VCon(_: C.BuiltinValue) => true; case _ => false }
      case Arg.Int    => within(BigInt(Long.MinValue), BigInt(Long.MaxValue))
      case Arg.Word8  => within(BigInt(0), Word8Max)
      case Arg.Word64 => within(BigInt(0), Word64Max)

  private def one(cost: SatInt): CostStream = CostStream.last(cost)

  /** `memoryUsageInteger` (`ExMemoryUsage.hs` lines 247-256): 64-bit words. */
  private def integerSize(i: BigInt): SatInt =
    if i.signum == 0 then SatInt(1) else SatInt((i.abs.bitLength - 1).toLong / 64 + 1)

  /** `ExMemoryUsage BS.ByteString` (lines 319-323): `((n - 1) quot 8) + 1`. */
  private def byteStringSize(length: scala.Int): SatInt = SatInt(((length - 1) / 8 + 1).toLong)

  /** A chain of 100s ending in the rest: the list and text instances (lines 221-231, 326-334). A
    * list's last element is `n mod 100` (`dropN @100` succeeds on exactly 100 elements, leaving an
    * empty list); a text's last chunk is the final 1 to 100 characters.
    */
  private def batches(full: Long, rest: Long): CostStream =
    CostStream.of(
      NonEmptyVector.one(SatInt(rest)).prependVector(Vector.fill(full.toInt)(SatInt(100)))
    )

  private def listUsage(length: scala.Int): CostStream = batches(length / 100, length % 100)

  /** `T.length` counts code points. */
  private def textUsage(text: String): CostStream =
    val length = text.codePointCount(0, text.length).toLong
    if length == 0 then one(SatInt.Zero)
    else batches((length - 1) / 100, length - (length - 1) / 100 * 100)

  /** A pre-order walk of a `Data` value (`flattenCostRose` over the rose that `node` and the
    * children build), on the heap.
    */
  private def dataUsage(root: UplcData, node: UplcData => SatInt): CostStream =
    def children(d: UplcData): List[UplcData] = d match
      case UplcData.Constr(_, fields)    => fields.toScalaList
      case UplcData.Map(entries)         => entries.toScalaList.flatMap((k, v) => List(k, v))
      case UplcData.List(items)          => items.toScalaList
      case UplcData.I(_) | UplcData.B(_) => Nil
    @tailrec
    def go(todo: List[UplcData], out: NonEmptyVector[SatInt]): NonEmptyVector[SatInt] = todo match
      case Nil       => out
      case d :: rest => go(children(d) ++ rest, out :+ node(d))
    CostStream.of(go(children(root), NonEmptyVector.one(node(root))))

  /** `ExMemoryUsage Data` (lines 382-397): four per node plus the size of an integer or a byte
    * string.
    */
  private def dataNode(d: UplcData): SatInt = d match
    case UplcData.I(i) => SatInt(4) + integerSize(i)
    case UplcData.B(b) => SatInt(4) + byteStringSize(b.size)
    case _             => SatInt(4)

  /** `ExMemoryUsage` of a constant by its type (lines 203-497). */
  private def constantUsage(constant: Constant): CostStream =
    import Constant as C
    constant match
      case C.Integer(i)              => one(integerSize(i))
      case C.ByteString(b)           => one(byteStringSize(b.size))
      case C.String(s)               => textUsage(s)
      case C.Unit | (_: C.Bool)      => one(SatInt(1))
      case C.Data(d)                 => dataUsage(d, dataNode)
      case C.List(_, items)          => listUsage(items.size)
      case C.Array(_, items)         => one(SatInt(items.size.toLong))
      case _: C.Pair                 => one(SatInt.MaxBound)
      case _: C.BLS12_381_G1_Element => one(SatInt(18))
      case _: C.BLS12_381_G2_Element => one(SatInt(36))
      case _: C.BLS12_381_MlResult   => one(SatInt(72))
      case C.BuiltinValue(v)         => one(SatInt(BuiltinValueOps.totalSize(v).toLong))

  /** How the reference measures an argument it has unlifted ([[accepts]]). */
  private def usage(arg: Arg, value: CekValue): CostStream =
    (arg, value) match
      case (Arg.Int | Arg.Word8 | Arg.Word64, _) => one(SatInt(1))
      case (Arg.NumBytes, CekValue.VCon(Constant.Integer(n))) =>
        val m = n.abs - 1
        one(SatInt.fromBigInt((if m.signum < 0 then BigInt(-1) else m / 8) + 1))
      case (Arg.Literally, CekValue.VCon(Constant.Integer(n))) => one(SatInt.fromBigInt(n.abs))
      case (Arg.TextBytes, CekValue.VCon(Constant.String(s))) =>
        one(SatInt((s.getBytes(StandardCharsets.UTF_8).length / 4).toLong))
      case (Arg.DataNodes, CekValue.VCon(Constant.Data(d))) => dataUsage(d, _ => SatInt(1))
      case (Arg.ValueMaxDepth, CekValue.VCon(Constant.BuiltinValue(v))) =>
        one(SatInt(BuiltinValueOps.valueMaxDepth(v).toLong))
      case (_, CekValue.VCon(constant)) => constantUsage(constant)
      case (_, CekValue.VList(items))   => listUsage(items.size)
      case _                            => one(SatInt(1))

  /** A budget of more than one element: the machine spends `elements` in order
    * ([[ReferenceBudget.Steps]]); the fields hold their saturated sums for any other spender.
    */
  final class BudgetStream(val elements: NonEmptyVector[(SatInt, SatInt)])
      extends ScalusExUnits(
        elements.reduceLeftTo(_._2)(_ + _._2).toLong,
        elements.reduceLeftTo(_._1)(_ + _._1).toLong
      )

  /** `zipCostStream` (`ExBudgetStream.hs` lines 58-77): CPU and memory element by element, the
    * shorter one continued with zeros.
    */
  private def zip(cpu: CostStream, memory: CostStream): ScalusExUnits =
    (cpu, memory) match
      case (CostStream.Last(c), CostStream.Last(m)) => ScalusExUnits(m.toLong, c.toLong)
      case _                                        =>
        // the shorter stream is continued with zeros
        BudgetStream(
          cpu.costs.alignWith(memory.costs)(
            _.fold(c => (c, SatInt.Zero), m => (SatInt.Zero, m), (c, m) => (c, m))
          )
        )

  /** The reference's costing of one builtin: its arguments and its CPU and memory models. */
  final case class BuiltinCosting(args: Vector[Arg], cpu: Model, memory: Model) extends CostingFun:
    def calculateCost(values: CekValue*): ScalusExUnits =
      if values.sizeIs != args.size || !args.lazyZip(values).forall(accepts) then
        ScalusExUnits(0L, 0L)
      else
        val streams = args.lazyZip(values).map(usage).toVector
        zip(run(cpu, streams), run(memory, streams))

  /** A costing function of Scalus's cost model that has no counterpart here. */
  final case class Unmirrored(builtin: DefaultFun)

  /** The reference's costing of every builtin under a cost model and semantics variant. */
  def table(
      model: BuiltinCostModel,
      variant: BuiltinSemanticsVariant
  ): Either[Unmirrored, Map[DefaultFun, BuiltinCosting]] =
    DefaultFun.values.toVector
      .foldLeft[Either[Unmirrored, Map[DefaultFun, BuiltinCosting]]](Right(Map.empty)) {
        (acc, fun) =>
          for
            done <- acc
            models <- shapes(costingFunction(fun, model)).toRight(Unmirrored(fun))
          yield done.updated(fun, BuiltinCosting(signature(fun, variant), models._1, models._2))
      }

  private def shapes(costing: CostingFun): Option[(Model, Model)] =
    def both(cpu: CostModel, memory: CostModel) =
      for c <- shape(cpu); m <- shape(memory) yield (c, m)
    costing match
      case DefaultCostingFun(cpu, memory) => both(cpu, memory)
      case ConstCostingFun(cpu, memory) =>
        Some((Model.ConstantCost(SatInt(cpu.toLong)), Model.ConstantCost(SatInt(memory.toLong))))
      case IntegerToByteStringCostingFun(cpu, memory)     => both(cpu, memory)
      case ReplicateByteCostingFun(cpu, memory)           => both(cpu, memory)
      case ShiftOrRotateByteStringCostingFun(cpu, memory) => both(cpu, memory)
      case WriteBitsCostingFun(cpu, memory)               => both(cpu, memory)
      case DropListCostingFun(cpu, memory)                => both(cpu, memory)
      case ExpModIntegerCostingFun(cpu, memory)           => both(cpu, memory)
      case _                                              => None

  /** Scalus's model as the reference's (`Core.hs` constructors of the same names). */
  private def shape(model: CostModel): Option[Model] =
    import Model.*
    def s(c: scalus.uplc.eval.CostingInteger): SatInt = SatInt(c.toLong)
    def linear(at: scala.Int, f: OneVariableLinearFunction) = Linear(at, s(f.intercept), s(f.slope))
    def quadratic(at: scala.Int, f: OneVariableQuadraticFunction) =
      Quadratic(at, s(f.c0), s(f.c1), s(f.c2))
    def two(f: TwoVariableLinearFunction, x: scala.Int, y: scala.Int) =
      LinearInTwo(x, y, s(f.intercept), s(f.slope1), s(f.slope2))
    def quadraticTwo(f: TwoVariableQuadraticFunction, x: scala.Int, y: scala.Int) =
      QuadraticInTwo(x, y, s(f.minimum), s(f.c00), s(f.c10), s(f.c01), s(f.c20), s(f.c11), s(f.c02))
    model match
      case OneArgument.ConstantCost(c)   => Some(ConstantCost(s(c)))
      case OneArgument.LinearInX(f)      => Some(linear(0, f))
      case OneArgument.QuadraticInX(f)   => Some(quadratic(0, f))
      case TwoArguments.ConstantCost(c)  => Some(ConstantCost(s(c)))
      case TwoArguments.LinearInX(f)     => Some(linear(0, f))
      case TwoArguments.LinearInY(f)     => Some(linear(1, f))
      case TwoArguments.LinearInY2(f)    => Some(Linear(1, s(f.intercept), s(f.slope)))
      case TwoArguments.LinearInXAndY(f) => Some(two(f, 0, 1))
      case TwoArguments.AddedSizes(f)    => Some(AddedSizes(s(f.intercept), s(f.slope)))
      case TwoArguments.SubtractedSizes(f: SubtractedSizesLinearFunction) =>
        Some(SubtractedSizes(s(f.intercept), s(f.slope), s(f.minimum)))
      case TwoArguments.MultipliedSizes(f) => Some(MultipliedSizes(s(f.intercept), s(f.slope)))
      case TwoArguments.MinSize(f)         => Some(MinSize(s(f.intercept), s(f.slope)))
      case TwoArguments.MaxSize(f)         => Some(MaxSize(s(f.intercept), s(f.slope)))
      case TwoArguments.LinearOnDiagonal(f) =>
        Some(LinearOnDiagonal(s(f.constant), s(f.intercept), s(f.slope)))
      case TwoArguments.ConstOffDiagonal(f) =>
        shape(f.model).map(ConstOffDiagonal(s(f.constant), _))
      case TwoArguments.ConstBelowDiagonal(f) =>
        shape(f.model).map(ConstBelowDiagonal(s(f.constant), _))
      case TwoArguments.ConstAboveDiagonal(f) =>
        shape(f.model).map(ConstAboveDiagonal(s(f.constant), _))
      case TwoArguments.AboveAndBelowDiagonal(f) => shape(f.model).map(AboveAndBelowDiagonal(_))
      case TwoArguments.QuadraticInY(f)          => Some(quadratic(1, f))
      case TwoArguments.QuadraticInXAndY(f)      => Some(quadraticTwo(f, 0, 1))
      case TwoArguments.WithInteractionInXAndY(f: TwoVariableWithInteractionFunction) =>
        Some(WithInteraction(s(f.c00), s(f.c10), s(f.c01), s(f.c11)))
      case ThreeArguments.ConstantCost(c)  => Some(ConstantCost(s(c)))
      case ThreeArguments.LinearInX(f)     => Some(linear(0, f))
      case ThreeArguments.LinearInY(f)     => Some(linear(1, f))
      case ThreeArguments.LinearInZ(f)     => Some(linear(2, f))
      case ThreeArguments.QuadraticInZ(f)  => Some(quadratic(2, f))
      case ThreeArguments.LinearInYAndZ(f) => Some(two(f, 1, 2))
      case ThreeArguments.LiteralInYOrLinearInZ(f) =>
        Some(LiteralInYOrLinearInZ(s(f.intercept), s(f.slope)))
      case ThreeArguments.LinearInMaxYZ(f) => Some(LinearInMaxYZ(s(f.intercept), s(f.slope)))
      case ThreeArguments.ExpModCost(f: ExpModCostingFunction) =>
        Some(ExpMod(s(f.coefficient00), s(f.coefficient11), s(f.coefficient12)))
      case FourArguments.ConstantCost(c) => Some(ConstantCost(s(c)))
      case FourArguments.LinearInU(f)    => Some(linear(3, f))
      case FiveArguments.ConstantCost(c) => Some(ConstantCost(s(c)))
      case SixArguments.ConstantCost(c)  => Some(ConstantCost(s(c)))
      case _                             => None

  /** The reference's argument types of every builtin (`Default/Builtins.hs` lines 1099-2491). In
    * the `ensurable` variants D and E (lines 2788-2793) the shift and rotation amounts are `Int`
    * (lines 2237-2266), `constrData` 's index is a `Word64` (lines 1745-1760) and the string
    * builtins measure bytes (lines 1507-1590); `consByteString` takes a `Word8` in C and E (lines
    * 1258-1310).
    */
  def signature(fun: DefaultFun, variant: BuiltinSemanticsVariant): Vector[Arg] =
    import Arg.*
    import DefaultFun as F
    val ensurable =
      variant == BuiltinSemanticsVariant.D || variant == BuiltinSemanticsVariant.E
    val text = if ensurable then TextBytes else Text
    fun match
      case F.AddInteger | F.SubtractInteger | F.MultiplyInteger | F.DivideInteger |
          F.QuotientInteger | F.RemainderInteger | F.ModInteger | F.EqualsInteger |
          F.LessThanInteger | F.LessThanEqualsInteger =>
        Vector(Integer, Integer)
      case F.AppendByteString | F.EqualsByteString | F.LessThanByteString |
          F.LessThanEqualsByteString =>
        Vector(ByteString, ByteString)
      case F.ConsByteString =>
        val byte =
          if variant == BuiltinSemanticsVariant.C || variant == BuiltinSemanticsVariant.E then Word8
          else Integer
        Vector(byte, ByteString)
      case F.SliceByteString => Vector(Int, Int, ByteString)
      case F.IndexByteString => Vector(ByteString, Int)
      case F.LengthOfByteString | F.Sha2_256 | F.Sha3_256 | F.Blake2b_256 | F.Keccak_256 |
          F.Blake2b_224 | F.Ripemd_160 | F.DecodeUtf8 | F.ComplementByteString | F.CountSetBits |
          F.FindFirstSetBit | F.Bls12_381_G1_uncompress | F.Bls12_381_G2_uncompress =>
        Vector(ByteString)
      case F.VerifyEd25519Signature | F.VerifyEcdsaSecp256k1Signature |
          F.VerifySchnorrSecp256k1Signature =>
        Vector(ByteString, ByteString, ByteString)
      case F.AppendString | F.EqualsString      => Vector(text, text)
      case F.EncodeUtf8                         => Vector(text)
      case F.IfThenElse                         => Vector(Bool, Opaque, Opaque)
      case F.ChooseUnit                         => Vector(Unit, Opaque)
      case F.Trace                              => Vector(Text, Opaque)
      case F.FstPair | F.SndPair                => Vector(PairOf)
      case F.ChooseList                         => Vector(ListOf, Opaque, Opaque)
      case F.MkCons                             => Vector(AnyConstant, ListOf)
      case F.HeadList | F.TailList | F.NullList => Vector(ListOf)
      case F.ChooseData => Vector(Data, Opaque, Opaque, Opaque, Opaque, Opaque)
      case F.ConstrData => Vector(if ensurable then Word64 else Integer, DataList)
      case F.MapData    => Vector(DataPairList)
      case F.ListData   => Vector(DataList)
      case F.IData      => Vector(Integer)
      case F.BData      => Vector(ByteString)
      case F.UnConstrData | F.UnMapData | F.UnListData | F.UnIData | F.UnBData | F.SerialiseData =>
        Vector(Data)
      case F.EqualsData | F.MkPairData                             => Vector(Data, Data)
      case F.MkNilData | F.MkNilPairData                           => Vector(Unit)
      case F.Bls12_381_G1_add | F.Bls12_381_G1_equal               => Vector(G1, G1)
      case F.Bls12_381_G1_neg | F.Bls12_381_G1_compress            => Vector(G1)
      case F.Bls12_381_G1_scalarMul                                => Vector(Integer, G1)
      case F.Bls12_381_G2_add | F.Bls12_381_G2_equal               => Vector(G2, G2)
      case F.Bls12_381_G2_neg | F.Bls12_381_G2_compress            => Vector(G2)
      case F.Bls12_381_G2_scalarMul                                => Vector(Integer, G2)
      case F.Bls12_381_G1_hashToGroup | F.Bls12_381_G2_hashToGroup => Vector(ByteString, ByteString)
      case F.Bls12_381_millerLoop                                  => Vector(G1, G2)
      case F.Bls12_381_mulMlResult | F.Bls12_381_finalVerify       => Vector(MlResult, MlResult)
      case F.Bls12_381_G1_multiScalarMul                           => Vector(IntegerList, G1List)
      case F.Bls12_381_G2_multiScalarMul                           => Vector(IntegerList, G2List)
      case F.IntegerToByteString => Vector(Bool, NumBytes, Integer)
      case F.ByteStringToInteger => Vector(Bool, ByteString)
      case F.AndByteString | F.OrByteString | F.XorByteString =>
        Vector(Bool, ByteString, ByteString)
      case F.ReadBit       => Vector(ByteString, Int)
      case F.WriteBits     => Vector(ByteString, IntegerList, Bool)
      case F.ReplicateByte => Vector(NumBytes, Word8)
      case F.ShiftByteString | F.RotateByteString =>
        Vector(ByteString, if ensurable then Int else Literally)
      case F.ExpModInteger                => Vector(Integer, Integer, Integer)
      case F.DropList                     => Vector(Literally, ListOf)
      case F.LengthOfArray                => Vector(ArrayOf)
      case F.ListToArray                  => Vector(ListOf)
      case F.IndexArray                   => Vector(ArrayOf, Int)
      case F.InsertCoin                   => Vector(ByteString, ByteString, Integer, ValueMaxDepth)
      case F.LookupCoin                   => Vector(ByteString, ByteString, ValueMaxDepth)
      case F.UnionValue | F.ValueContains => Vector(Value, Value)
      case F.ValueData                    => Vector(Value)
      case F.UnValueData                  => Vector(DataNodes)
      case F.ScaleValue                   => Vector(Integer, Value)

  /** The costing function of a builtin in Scalus's cost model (field by field). */
  private def costingFunction(fun: DefaultFun, m: BuiltinCostModel): CostingFun =
    import DefaultFun as F
    fun match
      case F.AddInteger                      => m.addInteger
      case F.SubtractInteger                 => m.subtractInteger
      case F.MultiplyInteger                 => m.multiplyInteger
      case F.DivideInteger                   => m.divideInteger
      case F.QuotientInteger                 => m.quotientInteger
      case F.RemainderInteger                => m.remainderInteger
      case F.ModInteger                      => m.modInteger
      case F.EqualsInteger                   => m.equalsInteger
      case F.LessThanInteger                 => m.lessThanInteger
      case F.LessThanEqualsInteger           => m.lessThanEqualsInteger
      case F.AppendByteString                => m.appendByteString
      case F.ConsByteString                  => m.consByteString
      case F.SliceByteString                 => m.sliceByteString
      case F.LengthOfByteString              => m.lengthOfByteString
      case F.IndexByteString                 => m.indexByteString
      case F.EqualsByteString                => m.equalsByteString
      case F.LessThanByteString              => m.lessThanByteString
      case F.LessThanEqualsByteString        => m.lessThanEqualsByteString
      case F.Sha2_256                        => m.sha2_256
      case F.Sha3_256                        => m.sha3_256
      case F.Blake2b_256                     => m.blake2b_256
      case F.VerifyEd25519Signature          => m.verifyEd25519Signature
      case F.VerifyEcdsaSecp256k1Signature   => m.verifyEcdsaSecp256k1Signature
      case F.VerifySchnorrSecp256k1Signature => m.verifySchnorrSecp256k1Signature
      case F.AppendString                    => m.appendString
      case F.EqualsString                    => m.equalsString
      case F.EncodeUtf8                      => m.encodeUtf8
      case F.DecodeUtf8                      => m.decodeUtf8
      case F.IfThenElse                      => m.ifThenElse
      case F.ChooseUnit                      => m.chooseUnit
      case F.Trace                           => m.trace
      case F.FstPair                         => m.fstPair
      case F.SndPair                         => m.sndPair
      case F.ChooseList                      => m.chooseList
      case F.MkCons                          => m.mkCons
      case F.HeadList                        => m.headList
      case F.TailList                        => m.tailList
      case F.NullList                        => m.nullList
      case F.ChooseData                      => m.chooseData
      case F.ConstrData                      => m.constrData
      case F.MapData                         => m.mapData
      case F.ListData                        => m.listData
      case F.IData                           => m.iData
      case F.BData                           => m.bData
      case F.UnConstrData                    => m.unConstrData
      case F.UnMapData                       => m.unMapData
      case F.UnListData                      => m.unListData
      case F.UnIData                         => m.unIData
      case F.UnBData                         => m.unBData
      case F.EqualsData                      => m.equalsData
      case F.SerialiseData                   => m.serialiseData
      case F.MkPairData                      => m.mkPairData
      case F.MkNilData                       => m.mkNilData
      case F.MkNilPairData                   => m.mkNilPairData
      case F.Bls12_381_G1_add                => m.bls12_381_G1_add
      case F.Bls12_381_G1_neg                => m.bls12_381_G1_neg
      case F.Bls12_381_G1_scalarMul          => m.bls12_381_G1_scalarMul
      case F.Bls12_381_G1_equal              => m.bls12_381_G1_equal
      case F.Bls12_381_G1_hashToGroup        => m.bls12_381_G1_hashToGroup
      case F.Bls12_381_G1_compress           => m.bls12_381_G1_compress
      case F.Bls12_381_G1_uncompress         => m.bls12_381_G1_uncompress
      case F.Bls12_381_G2_add                => m.bls12_381_G2_add
      case F.Bls12_381_G2_neg                => m.bls12_381_G2_neg
      case F.Bls12_381_G2_scalarMul          => m.bls12_381_G2_scalarMul
      case F.Bls12_381_G2_equal              => m.bls12_381_G2_equal
      case F.Bls12_381_G2_hashToGroup        => m.bls12_381_G2_hashToGroup
      case F.Bls12_381_G2_compress           => m.bls12_381_G2_compress
      case F.Bls12_381_G2_uncompress         => m.bls12_381_G2_uncompress
      case F.Bls12_381_millerLoop            => m.bls12_381_millerLoop
      case F.Bls12_381_mulMlResult           => m.bls12_381_mulMlResult
      case F.Bls12_381_finalVerify           => m.bls12_381_finalVerify
      case F.Bls12_381_G1_multiScalarMul     => m.bls12_381_G1_multiScalarMul
      case F.Bls12_381_G2_multiScalarMul     => m.bls12_381_G2_multiScalarMul
      case F.Keccak_256                      => m.keccak_256
      case F.Blake2b_224                     => m.blake2b_224
      case F.IntegerToByteString             => m.integerToByteString
      case F.ByteStringToInteger             => m.byteStringToInteger
      case F.AndByteString                   => m.andByteString
      case F.OrByteString                    => m.orByteString
      case F.XorByteString                   => m.xorByteString
      case F.ComplementByteString            => m.complementByteString
      case F.ReadBit                         => m.readBit
      case F.WriteBits                       => m.writeBits
      case F.ReplicateByte                   => m.replicateByte
      case F.ShiftByteString                 => m.shiftByteString
      case F.RotateByteString                => m.rotateByteString
      case F.CountSetBits                    => m.countSetBits
      case F.FindFirstSetBit                 => m.findFirstSetBit
      case F.Ripemd_160                      => m.ripemd_160
      case F.ExpModInteger                   => m.expModInteger
      case F.DropList                        => m.dropList
      case F.LengthOfArray                   => m.lengthOfArray
      case F.ListToArray                     => m.listToArray
      case F.IndexArray                      => m.indexArray
      case F.InsertCoin                      => m.insertCoin
      case F.LookupCoin                      => m.lookupCoin
      case F.UnionValue                      => m.unionValue
      case F.ValueContains                   => m.valueContains
      case F.ValueData                       => m.valueData
      case F.UnValueData                     => m.unValueData
      case F.ScaleValue                      => m.scaleValue
