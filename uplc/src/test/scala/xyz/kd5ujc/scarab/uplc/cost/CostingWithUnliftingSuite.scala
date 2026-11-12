// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  PlutusData,
  SemanticsVariant,
  Signature,
  Text,
  Value
}
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element, MlResult}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  Builtins,
  Denotation,
  Denotations,
  MachineValue,
  Outcome,
  UnliftingBound
}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreUnlifting, CoreVariant}

/** The costing beside the unlifting of the core builtins, two transcriptions of the same Haskell
  * types of `toBuiltinMeaning` (`PlutusCore/Default/Builtins.hs`, plutus 1.70.0.0): an argument the
  * unlifting bounds as `Int`, `Word8` or `Word64` is the one the costing measures as 1, the costing
  * measures as many arguments as the builtin takes, and every builtin's unlifted arguments are
  * costed in the reference's order.
  */
final class CostingWithUnliftingSuite extends munit.FunSuite:
  private given MachineValue[Constant] with
    def constantOf(value: Constant): Option[Constant] = Some(value)
    def ofConstant(constant: Constant): Constant = constant

  private def costing(variant: SemanticsVariant): BuiltinCosting =
    val language = DefaultCostModels.languageOf(variant)
    val (_, parameters) = EvaluationContext.tagWithParamNames(
      EvaluationContext.parameterNames(language),
      DefaultCostModels.list(language, variant)
    )
    EvaluationContext
      .costsOf(variant, parameters)
      .toOption
      .flatMap(costs => BuiltinCosting.of(variant, costs.model.builtins))
      .getOrElse(fail(s"no costing for $variant"))

  private def core(variant: SemanticsVariant): CoreVariant =
    CoreVariant.of(variant).fold(e => fail(e.toString), identity)

  private val oneLike: Set[UnliftingBound] =
    Set(UnliftingBound.Int, UnliftingBound.Word8, UnliftingBound.Word64)

  test(
    "an argument is measured as 1 exactly where the unlifting reads it as Int, Word8 or Word64"
  ) {
    DefaultCostModels.Variants.foreach { variant =>
      val costs = costing(variant)
      DefaultFun.values.foreach { function =>
        val bounds = CoreUnlifting.bounds(core(variant), function)
        costs.measures(function).zipWithIndex.foreach { (measure, position) =>
          assertEquals(
            measure eq Measure.one,
            bounds.get(position).exists(oneLike),
            s"$variant $function argument $position: measure $measure, bound ${bounds.get(position)}"
          )
        }
      }
    }
  }

  test("every builtin's costing measures as many arguments as it takes") {
    DefaultCostModels.Variants.foreach { variant =>
      val costs = costing(variant)
      DefaultFun.values.foreach(function =>
        assertEquals(costs.measures(function).size, function.arity, s"$variant $function")
      )
    }
  }

  private def sample(t: Signature.Type): Constant = t match
    case Signature.Type.Variable(_) => Constant.Integer(7)
    case Signature.Type.Integer     => Constant.Integer(7)
    case Signature.Type.ByteString  => Constant.ByteString(Bytes.empty)
    case Signature.Type.String      => Constant.String(Text.fromString("abc"))
    case Signature.Type.Unit        => Constant.Unit
    case Signature.Type.Bool        => Constant.Bool(true)
    case Signature.Type.Data        => Constant.Data(PlutusData.I(0))
    case Signature.Type.Bls12_381_G1_Element =>
      Constant.Bls12_381_G1_Element(G1Element.fromCompressed(Bytes.empty))
    case Signature.Type.Bls12_381_G2_Element =>
      Constant.Bls12_381_G2_Element(G2Element.fromCompressed(Bytes.empty))
    case Signature.Type.Bls12_381_MlResult =>
      Constant.Bls12_381_MlResult(MlResult.fromRaw(Bytes.empty))
    case Signature.Type.Value      => Constant.Value(Value.empty)
    case Signature.Type.List(e)    => Constant.List.empty(sample(e).typeOf)
    case Signature.Type.Pair(a, b) => Constant.Pair(sample(a), sample(b))
    case Signature.Type.Array(e) =>
      Constant.Array.of(sample(e).typeOf, Vector.empty).fold(m => fail(m.toString), identity)

  private val unit: Denotations = _ =>
    new Denotation:
      def apply[V](arguments: Vector[Argument[V]])(using value: MachineValue[V]): Outcome[V] =
        Outcome.Success(value.ofConstant(Constant.Unit), Vector.empty)

  test(
    "every builtin's unlifted arguments are costed, in every variant, as Builtins.prepare orders them"
  ) {
    DefaultCostModels.Variants.foreach { variant =>
      val unlifting = CoreUnlifting(core(variant))
      val builtins = Builtins(variant, unlifting, unit, costing(variant))
      DefaultFun.values.foreach { function =>
        val arguments = function.signature.parameters.map {
          case Signature.Parameter.Opaque(_)   => Constant.Integer(7)
          case Signature.Parameter.Constant(t) => sample(t)
        }
        assert(unlifting(function, arguments).isRight, s"$variant $function unlifts")
        assertNotEquals(
          builtins.prepare(function, arguments).cost,
          CostStream.free,
          s"$variant $function"
        )
      }
    }
  }

  test(
    "a polymorphic argument of another shape is costed as the constant it is (fstPair of an integer)"
  ) {
    val e = SemanticsVariant.E
    val builtins = Builtins(e, CoreUnlifting(core(e)), unit, costing(e))
    // fstPair has a constant cost (constant_cost in builtinCostModelE.json), whatever its argument.
    assertEquals(
      builtins
        .prepare(DefaultFun.FstPair, Vector[Constant](Constant.Integer(BigInt(1) << 200)))
        .cost,
      builtins
        .prepare(DefaultFun.FstPair, Vector[Constant](Constant.Pair(Constant.Unit, Constant.Unit)))
        .cost
    )
    // An argument that does not unlift costs nothing.
    assertEquals(
      builtins
        .prepare(DefaultFun.AddInteger, Vector[Constant](Constant.Unit, Constant.Integer(1)))
        .cost,
      CostStream.free
    )
  }
