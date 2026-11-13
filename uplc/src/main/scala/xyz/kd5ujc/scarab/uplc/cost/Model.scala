// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Applicative
import cats.data.Const
import cats.syntax.all.*

/** The costing functions of the reference's cost model (`PlutusCore/Evaluation/Machine/
  * CostingFun/Core.hs`, plutus 1.70.0.0 `507cab8c`), one enumeration per arity, each case a shape
  * of the model (its `"type"` in `builtinCostModel*.json`) with its coefficients. The leaf type `A`
  * is what a coefficient holds: `Unit` for a shape alone ([[BuiltinShapes]]), [[SatInt]] for a
  * model that costs. A coefficient is named as the reference's cost-model parameters name it: the
  * keys of the model's JSON object joined with `-` (`flattenObject "-"`,
  * `CostModelInterface.hs:290-317`); `traverse` visits every coefficient with that name, and `tags`
  * gives every `"type"` key with its value.
  *
  * The JSON encoding is the reference's: a model is `{"type": tag, "arguments": coefficients}`
  * (`modelArgumentOptions`, `CostingFun/JSON.hs:28-34`), a record's fields drop their type-name
  * prefix and are written in snake case (`modelOptions`, lines 20-22).
  */
trait Model[M[_]]:
  def traverse[G[_]: Applicative, A, B](model: M[A], path: String)(f: (String, A) => G[B]): G[M[B]]
  def tags[A](model: M[A], path: String): Vector[(String, String)]

object Model:
  def apply[M[_]](using m: Model[M]): Model[M] = m

  /** Every coefficient of `model` with its name under `path`, in the order of `traverse`. */
  def leaves[M[_]: Model, A](model: M[A], path: String): Vector[(String, A)] =
    Model[M]
      .traverse[[x] =>> Const[Vector[(String, A)], x], A, Unit](model, path)((name, a) =>
        Const(Vector(name -> a))
      )
      .getConst

/** `s * x + I` (`OneVariableLinearFunction`, `CostingFun/Core.hs:378-383`). */
final case class Linear[A](intercept: A, slope: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[Linear[B]] =
    (f(s"$path-intercept", intercept), f(s"$path-slope", slope)).mapN(Linear(_, _))

/** `s1 * x + s2 * y + I` (`TwoVariableLinearFunction`, `CostingFun/Core.hs:386-392`). */
final case class Linear2[A](intercept: A, slope1: A, slope2: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[Linear2[B]] =
    (f(s"$path-intercept", intercept), f(s"$path-slope1", slope1), f(s"$path-slope2", slope2))
      .mapN(Linear2(_, _, _))

/** `c0 + c1*x + c2*x^2` (`OneVariableQuadraticFunction`, `CostingFun/Core.hs:395-401`). */
final case class Quadratic[A](c0: A, c1: A, c2: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[Quadratic[B]] =
    (f(s"$path-c0", c0), f(s"$path-c1", c1), f(s"$path-c2", c2)).mapN(Quadratic(_, _, _))

/** `max minimum (c00 + c10*x + c01*y + c20*x^2 + c11*x*y + c02*y^2)`
  * (`TwoVariableQuadraticFunction`, `CostingFun/Core.hs:412-443`).
  */
final case class Quadratic2[A](minimum: A, c00: A, c10: A, c01: A, c20: A, c11: A, c02: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[Quadratic2[B]] =
    (
      f(s"$path-minimum", minimum),
      f(s"$path-c00", c00),
      f(s"$path-c10", c10),
      f(s"$path-c01", c01),
      f(s"$path-c20", c20),
      f(s"$path-c11", c11),
      f(s"$path-c02", c02)
    ).mapN(Quadratic2(_, _, _, _, _, _, _))

/** The cost of `expModInteger` (`ExpModCostingFunction`, `CostingFun/Core.hs:447-480`). */
final case class ExpMod[A](coefficient00: A, coefficient11: A, coefficient12: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[ExpMod[B]] =
    (
      f(s"$path-coefficient00", coefficient00),
      f(s"$path-coefficient11", coefficient11),
      f(s"$path-coefficient12", coefficient12)
    ).mapN(ExpMod(_, _, _))

/** `s * (x - y) + I`, at least `minimum` (`ModelSubtractedSizes`, `CostingFun/Core.hs:490-496`). */
final case class Subtracted[A](intercept: A, slope: A, minimum: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[Subtracted[B]] =
    (f(s"$path-intercept", intercept), f(s"$path-slope", slope), f(s"$path-minimum", minimum))
      .mapN(Subtracted(_, _, _))

/** A constant off the diagonal, linear on it (`ModelConstantOrLinear`,
  * `CostingFun/Core.hs:502-508`).
  */
final case class ConstantOrLinear[A](constant: A, intercept: A, slope: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[ConstantOrLinear[B]] =
    (f(s"$path-constant", constant), f(s"$path-intercept", intercept), f(s"$path-slope", slope))
      .mapN(ConstantOrLinear(_, _, _))

/** `c10*x + c01*y + c11*(x*y) + c00` (`TwoVariableWithInteractionFunction`,
  * `CostingFun/Core.hs:527-550`).
  */
final case class Interaction[A](c00: A, c10: A, c01: A, c11: A):
  def traverse[G[_]: Applicative, B](path: String)(f: (String, A) => G[B]): G[Interaction[B]] =
    (f(s"$path-c00", c00), f(s"$path-c10", c10), f(s"$path-c01", c01), f(s"$path-c11", c11))
      .mapN(Interaction(_, _, _, _))

private object Run:
  /** `evaluateTwoVariableQuadraticFunction` (`CostingFun/Core.hs:424-443`), in its order of
    * operations: saturating addition is not associative.
    */
  def quadratic2(q: Quadratic2[SatInt], x: SatInt, y: SatInt): SatInt =
    q.minimum.max(q.c00 + q.c10 * x + q.c01 * y + q.c20 * x * x + q.c11 * x * y + q.c02 * y * y)

  /** `evaluateExpModCostingFunction` (`CostingFun/Core.hs:460-480`): 50% more when `a` is larger
    * than the modulus.
    */
  def expMod(e: ExpMod[SatInt], aa: SatInt, ee: SatInt, mm: SatInt): SatInt =
    val cost0 = e.coefficient00 + e.coefficient11 * ee * mm + e.coefficient12 * ee * mm * mm
    if aa <= mm then cost0 else cost0 + cost0.dividedBy(2L)

  /** `evaluateTwoVariableWithInteractionFunction` (`CostingFun/Core.hs:536-550`). */
  def interaction(f: Interaction[SatInt], x: SatInt, y: SatInt): SatInt =
    f.c10 * x + f.c01 * y + f.c11 * (x * y) + f.c00

/** `ModelOneArgument` (`CostingFun/Core.hs:232-237`), run by `runOneArgumentModel` (349-359). */
enum OneArgument[A]:
  case ConstantCost(cost: A)
  case LinearInX(f: Linear[A])
  case QuadraticInX(f: Quadratic[A])

  def tag: String = this match
    case ConstantCost(_) => "constant_cost"
    case LinearInX(_)    => "linear_in_x"
    case QuadraticInX(_) => "quadratic_in_x"

object OneArgument:
  given Model[OneArgument] with
    def traverse[G[_]: Applicative, A, B](model: OneArgument[A], path: String)(
        f: (String, A) => G[B]
    ): G[OneArgument[B]] =
      val arguments = s"$path-arguments"
      model match
        case ConstantCost(c) => f(arguments, c).map(ConstantCost(_))
        case LinearInX(l)    => l.traverse(arguments)(f).map(LinearInX(_))
        case QuadraticInX(q) => q.traverse(arguments)(f).map(QuadraticInX(_))
    def tags[A](model: OneArgument[A], path: String): Vector[(String, String)] =
      Vector(s"$path-type" -> model.tag)

  extension (model: OneArgument[SatInt])
    def run(x: => Costs): Costs = model match
      case ConstantCost(c)                     => Costs.Last(c)
      case LinearInX(Linear(i, s))             => Costs.scaleLinearly(i, s, x)
      case QuadraticInX(Quadratic(c0, c1, c2)) => Costs.scaleQuadratically(c0, c1, c2, x)

/** `ModelTwoArguments` (`CostingFun/Core.hs:568-588`), run by `runTwoArgumentModel` (627-737). */
enum TwoArguments[A]:
  case ConstantCost(cost: A)
  case LinearInX(f: Linear[A])
  case LinearInY(f: Linear[A])
  case LinearInY2(f: Subtracted[A])
  case LinearInXAndY(f: Linear2[A])
  case AddedSizes(f: Linear[A])
  case SubtractedSizes(f: Subtracted[A])
  case MultipliedSizes(f: Linear[A])
  case MinSize(f: Linear[A])
  case MaxSize(f: Linear[A])
  case LinearOnDiagonal(f: ConstantOrLinear[A])
  case ConstOffDiagonal(constant: A, model: OneArgument[A])
  case ConstAboveDiagonal(constant: A, model: TwoArguments[A])
  case ConstBelowDiagonal(constant: A, model: TwoArguments[A])
  case QuadraticInY(f: Quadratic[A])
  case QuadraticInXAndY(f: Quadratic2[A])
  case WithInteractionInXAndY(f: Interaction[A])
  case AboveAndBelowDiagonal(constant: A, model: TwoArguments[A])

  def tag: String = this match
    case ConstantCost(_)             => "constant_cost"
    case LinearInX(_)                => "linear_in_x"
    case LinearInY(_)                => "linear_in_y"
    case LinearInY2(_)               => "linear_in_y2"
    case LinearInXAndY(_)            => "linear_in_x_and_y"
    case AddedSizes(_)               => "added_sizes"
    case SubtractedSizes(_)          => "subtracted_sizes"
    case MultipliedSizes(_)          => "multiplied_sizes"
    case MinSize(_)                  => "min_size"
    case MaxSize(_)                  => "max_size"
    case LinearOnDiagonal(_)         => "linear_on_diagonal"
    case ConstOffDiagonal(_, _)      => "const_off_diagonal"
    case ConstAboveDiagonal(_, _)    => "const_above_diagonal"
    case ConstBelowDiagonal(_, _)    => "const_below_diagonal"
    case QuadraticInY(_)             => "quadratic_in_y"
    case QuadraticInXAndY(_)         => "quadratic_in_x_and_y"
    case WithInteractionInXAndY(_)   => "with_interaction_in_x_and_y"
    case AboveAndBelowDiagonal(_, _) => "above_and_below_diagonal"

object TwoArguments:
  given Model[TwoArguments] with
    def traverse[G[_]: Applicative, A, B](model: TwoArguments[A], path: String)(
        f: (String, A) => G[B]
    ): G[TwoArguments[B]] =
      val arguments = s"$path-arguments"
      model match
        case ConstantCost(c)           => f(arguments, c).map(ConstantCost(_))
        case LinearInX(l)              => l.traverse(arguments)(f).map(LinearInX(_))
        case LinearInY(l)              => l.traverse(arguments)(f).map(LinearInY(_))
        case LinearInY2(s)             => s.traverse(arguments)(f).map(LinearInY2(_))
        case LinearInXAndY(l)          => l.traverse(arguments)(f).map(LinearInXAndY(_))
        case AddedSizes(l)             => l.traverse(arguments)(f).map(AddedSizes(_))
        case SubtractedSizes(s)        => s.traverse(arguments)(f).map(SubtractedSizes(_))
        case MultipliedSizes(l)        => l.traverse(arguments)(f).map(MultipliedSizes(_))
        case MinSize(l)                => l.traverse(arguments)(f).map(MinSize(_))
        case MaxSize(l)                => l.traverse(arguments)(f).map(MaxSize(_))
        case LinearOnDiagonal(c)       => c.traverse(arguments)(f).map(LinearOnDiagonal(_))
        case QuadraticInY(q)           => q.traverse(arguments)(f).map(QuadraticInY(_))
        case QuadraticInXAndY(q)       => q.traverse(arguments)(f).map(QuadraticInXAndY(_))
        case WithInteractionInXAndY(i) => i.traverse(arguments)(f).map(WithInteractionInXAndY(_))
        case ConstOffDiagonal(c, m) =>
          (f(s"$arguments-constant", c), Model[OneArgument].traverse(m, s"$arguments-model")(f))
            .mapN(ConstOffDiagonal(_, _))
        case ConstAboveDiagonal(c, m) =>
          (f(s"$arguments-constant", c), traverse(m, s"$arguments-model")(f))
            .mapN(ConstAboveDiagonal(_, _))
        case ConstBelowDiagonal(c, m) =>
          (f(s"$arguments-constant", c), traverse(m, s"$arguments-model")(f))
            .mapN(ConstBelowDiagonal(_, _))
        case AboveAndBelowDiagonal(c, m) =>
          (f(s"$arguments-constant", c), traverse(m, s"$arguments-model")(f))
            .mapN(AboveAndBelowDiagonal(_, _))

    def tags[A](model: TwoArguments[A], path: String): Vector[(String, String)] =
      val nested = s"$path-arguments-model"
      (s"$path-type" -> model.tag) +: (model match
        case ConstOffDiagonal(_, m)      => Model[OneArgument].tags(m, nested)
        case ConstAboveDiagonal(_, m)    => tags(m, nested)
        case ConstBelowDiagonal(_, m)    => tags(m, nested)
        case AboveAndBelowDiagonal(_, m) => tags(m, nested)
        case _                           => Vector.empty)

  extension (model: TwoArguments[SatInt])
    def run(x: => Costs, y: => Costs): Costs = model match
      case ConstantCost(c)                   => Costs.Last(c)
      case LinearInX(Linear(i, s))           => Costs.scaleLinearly(i, s, x)
      case LinearInY(Linear(i, s))           => Costs.scaleLinearly(i, s, y)
      case LinearInY2(Subtracted(i, s, _))   => Costs.scaleLinearly(i, s, y)
      case LinearInXAndY(Linear2(i, s1, s2)) => Costs.scaleLinearlyTwoVariables(i, s1, x, s2, y)
      case AddedSizes(Linear(i, s))          => Costs.scaleLinearly(i, s, Costs.add(x, y))
      case SubtractedSizes(Subtracted(i, s, m)) =>
        Costs.scaleLinearly(i, s, Costs.Last(m.max(x.sum - y.sum)))
      case MultipliedSizes(Linear(i, s)) => Costs.scaleLinearly(i, s, Costs.Last(x.sum * y.sum))
      case MinSize(Linear(i, s))         => Costs.scaleLinearly(i, s, Costs.min(x, y))
      case MaxSize(Linear(i, s))         => Costs.scaleLinearly(i, s, Costs.Last(x.sum.max(y.sum)))
      case LinearOnDiagonal(ConstantOrLinear(c, i, s)) =>
        val size1 = x.sum
        if size1 == y.sum then Costs.scaleLinearly(i, s, Costs.Last(size1)) else Costs.Last(c)
      case ConstOffDiagonal(c, m) =>
        val size1 = x.sum
        if size1 != y.sum then Costs.Last(c) else m.run(Costs.Last(size1))
      case ConstBelowDiagonal(c, m) =>
        val (size1, size2) = (x.sum, y.sum)
        if size1 > size2 then Costs.Last(c) else m.run(Costs.Last(size1), Costs.Last(size2))
      case ConstAboveDiagonal(c, m) =>
        val (size1, size2) = (x.sum, y.sum)
        if size1 < size2 then Costs.Last(c) else m.run(Costs.Last(size1), Costs.Last(size2))
      case AboveAndBelowDiagonal(_, m) =>
        val (size1, size2) = (x.sum, y.sum)
        m.run(Costs.Last(size1.max(size2)), Costs.Last(size1.min(size2)))
      case QuadraticInY(Quadratic(c0, c1, c2)) => Costs.scaleQuadratically(c0, c1, c2, y)
      case QuadraticInXAndY(q)                 => Costs.Last(Run.quadratic2(q, x.sum, y.sum))
      case WithInteractionInXAndY(f)           => Costs.Last(Run.interaction(f, x.sum, y.sum))

/** `ModelThreeArguments` (`CostingFun/Core.hs:741-753`), run by `runThreeArgumentModel` (761-821).
  */
enum ThreeArguments[A]:
  case ConstantCost(cost: A)
  case LinearInX(f: Linear[A])
  case LinearInY(f: Linear[A])
  case LinearInZ(f: Linear[A])
  case QuadraticInZ(f: Quadratic[A])
  case LiteralInYOrLinearInZ(f: Linear[A])
  case LinearInMaxYZ(f: Linear[A])
  case LinearInYAndZ(f: Linear2[A])
  case QuadraticInYAndZ(f: Quadratic2[A])
  case ExpModCost(f: ExpMod[A])

  def tag: String = this match
    case ConstantCost(_)          => "constant_cost"
    case LinearInX(_)             => "linear_in_x"
    case LinearInY(_)             => "linear_in_y"
    case LinearInZ(_)             => "linear_in_z"
    case QuadraticInZ(_)          => "quadratic_in_z"
    case LiteralInYOrLinearInZ(_) => "literal_in_y_or_linear_in_z"
    case LinearInMaxYZ(_)         => "linear_in_max_yz"
    case LinearInYAndZ(_)         => "linear_in_y_and_z"
    case QuadraticInYAndZ(_)      => "quadratic_in_y_and_z"
    case ExpModCost(_)            => "exp_mod_cost"

object ThreeArguments:
  given Model[ThreeArguments] with
    def traverse[G[_]: Applicative, A, B](model: ThreeArguments[A], path: String)(
        f: (String, A) => G[B]
    ): G[ThreeArguments[B]] =
      val arguments = s"$path-arguments"
      model match
        case ConstantCost(c)          => f(arguments, c).map(ConstantCost(_))
        case LinearInX(l)             => l.traverse(arguments)(f).map(LinearInX(_))
        case LinearInY(l)             => l.traverse(arguments)(f).map(LinearInY(_))
        case LinearInZ(l)             => l.traverse(arguments)(f).map(LinearInZ(_))
        case QuadraticInZ(q)          => q.traverse(arguments)(f).map(QuadraticInZ(_))
        case LiteralInYOrLinearInZ(l) => l.traverse(arguments)(f).map(LiteralInYOrLinearInZ(_))
        case LinearInMaxYZ(l)         => l.traverse(arguments)(f).map(LinearInMaxYZ(_))
        case LinearInYAndZ(l)         => l.traverse(arguments)(f).map(LinearInYAndZ(_))
        case QuadraticInYAndZ(q)      => q.traverse(arguments)(f).map(QuadraticInYAndZ(_))
        case ExpModCost(e)            => e.traverse(arguments)(f).map(ExpModCost(_))
    def tags[A](model: ThreeArguments[A], path: String): Vector[(String, String)] =
      Vector(s"$path-type" -> model.tag)

  extension (model: ThreeArguments[SatInt])
    def run(x: => Costs, y: => Costs, z: => Costs): Costs = model match
      case ConstantCost(c)                     => Costs.Last(c)
      case LinearInX(Linear(i, s))             => Costs.scaleLinearly(i, s, x)
      case LinearInY(Linear(i, s))             => Costs.scaleLinearly(i, s, y)
      case LinearInZ(Linear(i, s))             => Costs.scaleLinearly(i, s, z)
      case QuadraticInZ(Quadratic(c0, c1, c2)) => Costs.scaleQuadratically(c0, c1, c2, z)
      // The width `y` as it is when it is not zero, else linear in `z` (`integerToByteString`).
      case LiteralInYOrLinearInZ(Linear(i, s)) =>
        if y.sum == SatInt.zero then Costs.scaleLinearly(i, s, z) else y
      case LinearInMaxYZ(Linear(i, s)) => Costs.scaleLinearly(i, s, Costs.Last(y.sum.max(z.sum)))
      case LinearInYAndZ(Linear2(i, s1, s2)) => Costs.scaleLinearlyTwoVariables(i, s1, y, s2, z)
      case QuadraticInYAndZ(q)               => Costs.Last(Run.quadratic2(q, y.sum, z.sum))
      case ExpModCost(e)                     => Costs.Last(Run.expMod(e, x.sum, y.sum, z.sum))

/** `ModelFourArguments` (`CostingFun/Core.hs:844-848`), run by `runFourArgumentModel` (856-868). */
enum FourArguments[A]:
  case ConstantCost(cost: A)
  case LinearInU(f: Linear[A])

  def tag: String = this match
    case ConstantCost(_) => "constant_cost"
    case LinearInU(_)    => "linear_in_u"

object FourArguments:
  given Model[FourArguments] with
    def traverse[G[_]: Applicative, A, B](model: FourArguments[A], path: String)(
        f: (String, A) => G[B]
    ): G[FourArguments[B]] =
      val arguments = s"$path-arguments"
      model match
        case ConstantCost(c) => f(arguments, c).map(ConstantCost(_))
        case LinearInU(l)    => l.traverse(arguments)(f).map(LinearInU(_))
    def tags[A](model: FourArguments[A], path: String): Vector[(String, String)] =
      Vector(s"$path-type" -> model.tag)

  extension (model: FourArguments[SatInt])
    def run(u: => Costs): Costs = model match
      case ConstantCost(c)         => Costs.Last(c)
      case LinearInU(Linear(i, s)) => Costs.scaleLinearly(i, s, u)

/** `ModelFiveArguments` (`CostingFun/Core.hs:893-896`): a constant only (904-913). */
enum FiveArguments[A]:
  case ConstantCost(cost: A)

  def tag: String = "constant_cost"

object FiveArguments:
  given Model[FiveArguments] with
    def traverse[G[_]: Applicative, A, B](model: FiveArguments[A], path: String)(
        f: (String, A) => G[B]
    ): G[FiveArguments[B]] = model match
      case ConstantCost(c) => f(s"$path-arguments", c).map(ConstantCost(_))
    def tags[A](model: FiveArguments[A], path: String): Vector[(String, String)] =
      Vector(s"$path-type" -> model.tag)

  extension (model: FiveArguments[SatInt])
    def run: Costs = model match
      case ConstantCost(c) => Costs.Last(c)

/** `ModelSixArguments` (`CostingFun/Core.hs:940-943`): a constant only (951-961). */
enum SixArguments[A]:
  case ConstantCost(cost: A)

  def tag: String = "constant_cost"

object SixArguments:
  given Model[SixArguments] with
    def traverse[G[_]: Applicative, A, B](model: SixArguments[A], path: String)(
        f: (String, A) => G[B]
    ): G[SixArguments[B]] = model match
      case ConstantCost(c) => f(s"$path-arguments", c).map(ConstantCost(_))
    def tags[A](model: SixArguments[A], path: String): Vector[(String, String)] =
      Vector(s"$path-type" -> model.tag)

  extension (model: SixArguments[SatInt])
    def run: Costs = model match
      case ConstantCost(c) => Costs.Last(c)

/** The CPU and memory costing functions of one builtin (`CostingFun`,
  * `CostingFun/Core.hs:100-105`), written in the cost model under the builtin's name as `cpu` and
  * `memory`.
  */
final case class CostingFun[M[_], A](cpu: M[A], memory: M[A]):
  def traverse[G[_]: Applicative, B](name: String)(f: (String, A) => G[B])(using
      model: Model[M]
  ): G[CostingFun[M, B]] =
    (model.traverse(cpu, s"$name-cpu")(f), model.traverse(memory, s"$name-memory")(f))
      .mapN(CostingFun(_, _))

  def leaves(name: String)(using Model[M]): Vector[(String, A)] =
    Model.leaves(cpu, s"$name-cpu") ++ Model.leaves(memory, s"$name-memory")

  def tags(name: String)(using model: Model[M]): Vector[(String, String)] =
    model.tags(cpu, s"$name-cpu") ++ model.tags(memory, s"$name-memory")
