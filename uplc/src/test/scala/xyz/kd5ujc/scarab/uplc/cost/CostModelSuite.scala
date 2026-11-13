// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  PlutusLanguage,
  ProtocolMajor,
  SemanticsVariant,
  Signature,
  Value
}
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element, MlResult}
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, MachineValue}

/** The shapes of the cost models against the vendored `builtinCostModel{A..E}.json` and
  * `cekMachineCosts{A..E}.json` of plutus 1.70.0.0 and the shape inventory of the costing oracle,
  * the parameter names of each language against the coefficients of its variants, and a costing
  * function for every builtin in every variant.
  */
final class CostModelSuite extends munit.FunSuite:
  import DefaultCostModels.Variants

  private def shapes(variant: SemanticsVariant): BuiltinCostModel[Unit] =
    BuiltinShapes.of(variant).getOrElse(fail(s"no shapes for $variant"))

  private def coefficients(variant: SemanticsVariant): Set[String] =
    (CekMachineCosts.shape.leaves ++ shapes(variant).leaves).map(_._1).toSet

  test("every variant's shapes are the types of its vendored model, nested models included") {
    Variants.foreach { variant =>
      assertEquals(
        shapes(variant).tags.toMap,
        DefaultCostModels.types(variant),
        s"variant $variant"
      )
      assertEquals(
        shapes(variant).tags.size,
        DefaultCostModels.types(variant).size,
        s"$variant: no type twice"
      )
    }
  }

  test(
    "every variant's coefficients are the numbers of its vendored models, machine and builtins"
  ) {
    Variants.foreach { variant =>
      assertEquals(
        coefficients(variant),
        DefaultCostModels.parameters(variant).keySet,
        s"variant $variant"
      )
    }
  }

  test("every builtin but keepPolicies and dropPolicies has an entry in every variant") {
    val expected = DefaultFun.values.map(_.name).toSet - "keepPolicies" - "dropPolicies"
    Variants.foreach { variant =>
      assertEquals(DefaultCostModels.builtins(variant), expected, s"variant $variant")
    }
  }

  test("F and G have no model at 1.70.0.0") {
    assertEquals(BuiltinShapes.of(SemanticsVariant.F), None)
    assertEquals(BuiltinShapes.of(SemanticsVariant.G), None)
  }

  test("the shapes agree with the costing oracle's inventory (cost-shapes.json)") {
    val inventory = parser
      .parse(Files.readString(Path.of("fixtures/reference/plutus-costing/cost-shapes.json")))
      .fold(throw _, identity)
    Variants.foreach { variant =>
      val tags = shapes(variant).tags
      DefaultFun.values.map(_.name).filterNot(Set("keepPolicies", "dropPolicies")).foreach { name =>
        Vector("cpu", "memory").foreach { part =>
          val expected = inventory.hcursor
            .downField(variant.toString)
            .downField(name)
            .get[Vector[String]](part)
            .fold(e => fail(s"$variant $name $part: ${e.message}"), identity)
          val actual = tags.filter((key, _) => key.startsWith(s"$name-$part-")).map(_._2)
          assertEquals(actual, expected, s"$variant $name $part")
        }
      }
    }
  }

  test("each language's parameter names are exactly the coefficients of each variant it uses") {
    Vector(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3, PlutusLanguage.V4).foreach {
      language =>
        val names = EvaluationContext.parameterNames(language)
        assertEquals(names.distinct.size, names.size, s"$language: no name twice")
        SemanticsVariant.ofContext(language).foreach { variant =>
          assertEquals(names.toSet, coefficients(variant), s"$language in $variant")
        }
    }
  }

  test("the default lists made from the vendored models are the costing oracle's (models.jsonl)") {
    val rows = Files
      .readString(Path.of("fixtures/reference/plutus-costing/models.jsonl"))
      .linesIterator
      .map(parser.parse(_).fold(throw _, identity))
      .toVector
    val defaults = rows.filter(_.hcursor.get[Int]("scale").contains(1))
    assertEquals(defaults.size, 15, "one default list per profile")
    defaults.foreach { row =>
      val c = row.hcursor
      val language = PlutusLanguage.values
        .find(l => c.get[Int]("language").contains(l.id + 1))
        .getOrElse(fail(s"language of ${row.noSpaces.take(60)}"))
      val protocol = ProtocolMajor(c.get[Int]("protocol").getOrElse(fail("protocol")))
      val variant = SemanticsVariant.of(language, protocol)
      assertEquals(
        c.get[Vector[Long]]("costModel").getOrElse(fail("costModel")),
        DefaultCostModels.list(language, variant),
        s"$language at $protocol ($variant)"
      )
    }
  }

  test("the defaults go through the ledger's path unchanged: list, names, model, coefficients") {
    Variants.foreach { variant =>
      val costs = DefaultCostModels.costs(variant)
      val values =
        (costs.model.machine.leaves ++ costs.model.builtins.leaves).map((n, v) => n -> v.toLong)
      assertEquals(values.toMap, DefaultCostModels.parameters(variant), s"variant $variant")
      assertEquals(costs.variant, variant)
    }
  }

  test("the machine costs are the cekMachineCosts parameters, each step kind its own") {
    val costs = DefaultCostModels.costs(SemanticsVariant.E).machineCosts
    // cekMachineCostsE.json: start-up 100/100, every step 16000/100.
    assertEquals(costs.startup, ExBudget.of(100, 100))
    StepKind.values.foreach(kind =>
      assertEquals(costs.step(kind), ExBudget.of(16000, 100), kind.toString)
    )
    val names = Vector(
      StepKind.Constant -> "cekConstCost",
      StepKind.Variable -> "cekVarCost",
      StepKind.LamAbs -> "cekLamCost",
      StepKind.Apply -> "cekApplyCost",
      StepKind.Delay -> "cekDelayCost",
      StepKind.Force -> "cekForceCost",
      StepKind.Builtin -> "cekBuiltinCost",
      StepKind.Constr -> "cekConstrCost",
      StepKind.Case -> "cekCaseCost"
    )
    // A list whose every machine parameter is distinct shows which parameter each step charges.
    val language = PlutusLanguage.V3
    val distinct = EvaluationContext.parameterNames(language).zipWithIndex.map((_, i) => 1000L + i)
    val (_, parameters) =
      EvaluationContext.tagWithParamNames(EvaluationContext.parameterNames(language), distinct)
    val machine = EvaluationContext
      .costsOf(SemanticsVariant.E, parameters)
      .fold(e => fail(e.toString), identity)
      .machineCosts
    names.foreach { (kind, name) =>
      assertEquals(
        machine.step(kind),
        ExBudget.of(parameters(s"$name-exBudgetCPU"), parameters(s"$name-exBudgetMemory")),
        kind.toString
      )
    }
    assertEquals(
      machine.startup,
      ExBudget.of(
        parameters("cekStartupCost-exBudgetCPU"),
        parameters("cekStartupCost-exBudgetMemory")
      )
    )
  }

  private given MachineValue[Constant] with
    def constantOf(value: Constant): Option[Constant] = Some(value)
    def ofConstant(constant: Constant): Constant = constant

  private def sample(t: Signature.Type): Constant = t match
    case Signature.Type.Variable(_) => Constant.Integer(7)
    case Signature.Type.Integer     => Constant.Integer(7)
    case Signature.Type.ByteString  => Constant.ByteString(Bytes.empty)
    case Signature.Type.String => Constant.String(xyz.kd5ujc.scarab.uplc.Text.fromString("abc"))
    case Signature.Type.Unit   => Constant.Unit
    case Signature.Type.Bool   => Constant.Bool(true)
    case Signature.Type.Data   => Constant.Data(PlutusData.I(0))
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

  private def arguments(function: DefaultFun): Vector[Argument[Constant]] =
    function.signature.parameters.map {
      case Signature.Parameter.Opaque(_)   => Argument.Opaque(Constant.Integer(7))
      case Signature.Parameter.Constant(t) => Argument.Con(sample(t))
    }

  test(
    "every builtin has a costing function in every variant, and the batch-7 Value two cost 10^11"
  ) {
    Variants.foreach { variant =>
      val costing = DefaultCostModels.costs(variant).costing
      DefaultFun.values.foreach { function =>
        val cost = costing(function)(arguments(function))
        assert(cost.prefix(1).nonEmpty, s"$variant $function")
      }
      Vector(DefaultFun.KeepPolicies, DefaultFun.DropPolicies).foreach { function =>
        assertEquals(
          costing(function)(arguments(function)),
          CostStream.single(ExBudget.of(100000000000L, 100000000000L)),
          s"$variant $function: unimplementedCostingFun"
        )
      }
    }
  }

  test("a pair is measured as maxBound, for the constant costs of fstPair and sndPair") {
    val pair = Constant.Pair(Constant.Integer(1), Constant.Unit)
    assertEquals(Measure.constant(pair), Costs.Last(SatInt.maxValue))
    assertEquals(DefaultUni.Pair(DefaultUni.Integer, DefaultUni.Unit), pair.typeOf)
  }
