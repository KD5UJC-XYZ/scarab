// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.data.WriterT
import cats.mtl.Handle.handleForApplicativeError
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage, ProtocolMajor, SemanticsVariant}

/** From the ledger's cost-model list to the evaluation context, as plutus 1.70.0.0 builds it: a
  * list of any length is used, padded with `maxBound` or cut, with a warning only
  * (`tagWithParamNames`, `PlutusLedgerApi/Common/ParamName.hs:86-112`); no list of `Int64` values
  * is refused; the variant comes from the protocol version.
  */
final class EvaluationContextSuite extends munit.FunSuite:
  private val languages = Vector(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3)

  private def built(
      language: PlutusLanguage,
      values: Vector[Long]
  ): (Vector[CostModelApplyWarn], EvaluationContext) =
    val (warnings, context) = EvaluationContext.build(language, values)
    (warnings, context.fold(e => fail(s"$language, ${values.size} values: $e"), identity))

  private def coefficients(costs: VariantCosts): Map[String, Long] =
    (costs.model.machine.leaves ++ costs.model.builtins.leaves).map((n, v) => n -> v.toLong).toMap

  test("a list of every length is used: the missing names get maxBound, extra values are dropped") {
    // The lengths of every list mainnet and the test networks have had and some beside them.
    val lengths = Vector(0, 1, 100, 166, 175, 185, 233, 251, 297, 300, 302, 342, 343, 344, 360, 361,
      362, 400, 411)
    languages.foreach { language =>
      val names = EvaluationContext.parameterNames(language)
      lengths.foreach { length =>
        val values = Vector.tabulate(length)(i => i + 1L)
        val (warnings, context) = built(language, values)
        val expectedWarnings =
          if length < names.size then
            Vector(CostModelApplyWarn.TooFewParameters(names.size, length))
          else if length > names.size then
            Vector(CostModelApplyWarn.TooManyParameters(names.size, length))
          else Vector.empty
        assertEquals(warnings, expectedWarnings, s"$language, $length values")
        val expected = names.zipWithIndex
          .map((name, i) => name -> (if i < length then i + 1L else Long.MaxValue))
          .toMap
        assertEquals(
          context.variants.map(_.variant),
          SemanticsVariant.ofContext(language),
          s"$language"
        )
        context.variants.foreach(costs =>
          assertEquals(
            coefficients(costs),
            expected,
            s"$language, $length values, ${costs.variant}"
          )
        )
      }
    }
  }

  test("the variant of a protocol version is the context's (toSemVar of each language)") {
    val expected = Map(
      PlutusLanguage.V1 -> Vector(
        5 -> "A",
        6 -> "A",
        7 -> "A",
        8 -> "A",
        9 -> "B",
        10 -> "B",
        11 -> "D"
      ),
      PlutusLanguage.V2 -> Vector(7 -> "A", 8 -> "A", 9 -> "B", 10 -> "B", 11 -> "D"),
      PlutusLanguage.V3 -> Vector(9 -> "C", 10 -> "C", 11 -> "E")
    )
    expected.foreach { (language, rows) =>
      val (_, context) =
        built(language, DefaultCostModels.list(language, SemanticsVariant.ofContext(language).last))
      rows.foreach { (protocol, variant) =>
        assertEquals(
          context.at(ProtocolMajor(protocol)).map(_.variant.toString),
          Some(variant),
          s"$language at $protocol"
        )
      }
    }
    // A PlutusV4 list is PlutusV3's: the same names, a PlutusV3 context.
    val (_, v4) =
      built(PlutusLanguage.V4, DefaultCostModels.list(PlutusLanguage.V3, SemanticsVariant.E))
    assertEquals(v4.language, PlutusLanguage.V3)
    assertEquals(v4.at(ProtocolMajor(11)).map(_.variant), Some(SemanticsVariant.E))
  }

  private type Built[A] =
    WriterT[[x] =>> Either[CostModelApplyError, x], Vector[CostModelApplyWarn], A]

  test("with the reference's capabilities, the warnings are told and nothing is raised") {
    val result = EvaluationContext.make[Built](PlutusLanguage.V1, Vector.empty).run
    result match
      case Right((warnings, context)) =>
        assertEquals(warnings, Vector(CostModelApplyWarn.TooFewParameters(343, 0)))
        assertEquals(context.language, PlutusLanguage.V1)
      case Left(error) => fail(error.toString)
  }

  test(
    "a name a model lacks fails, the machine's first and the smallest first; so does a lacking coefficient"
  ) {
    val names = EvaluationContext.parameterNames(PlutusLanguage.V1)
    val (_, parameters) = EvaluationContext.tagWithParamNames(
      names,
      DefaultCostModels.list(PlutusLanguage.V1, SemanticsVariant.A)
    )
    assert(EvaluationContext.costsOf(SemanticsVariant.A, parameters).isRight)
    assertEquals(
      EvaluationContext.costsOf(SemanticsVariant.A, parameters + ("zzz" -> 1L) + ("aab" -> 1L)),
      Left(CostModelApplyError.UnknownParameter("aab"))
    )
    // The machine's parameters are applied first (`applySplitCostModelParams`), so "cekBogus" wins
    // over "aab" although it sorts after it.
    assertEquals(
      EvaluationContext
        .costsOf(SemanticsVariant.A, parameters + ("aab" -> 1L) + ("cekBogus" -> 1L)),
      Left(CostModelApplyError.UnknownParameter("cekBogus"))
    )
    assertEquals(
      EvaluationContext.costsOf(SemanticsVariant.A, parameters - "addInteger-cpu-arguments-slope"),
      Left(CostModelApplyError.UnsuppliedParameter("addInteger-cpu-arguments-slope"))
    )
    assertEquals(
      EvaluationContext.costsOf(SemanticsVariant.F, parameters),
      Left(CostModelApplyError.NoModel(SemanticsVariant.F))
    )
  }

  private def rows(path: Path): Vector[Json] =
    Files.readString(path).linesIterator.map(parser.parse(_).fold(throw _, identity)).toVector

  private def languageOf(number: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == number).getOrElse(fail(s"language $number"))

  private def defaults(number: Int, protocol: Int): Vector[Long] =
    val language = languageOf(number)
    DefaultCostModels.list(language, SemanticsVariant.of(language, ProtocolMajor(protocol)))

  test(
    "every list of the genesis cost-model fixture makes a context, as the reference's mkCostModel did"
  ) {
    val models = rows(Path.of("fixtures/reference/genesis-cost-models/evaluation-models.jsonl"))
    assertEquals(models.size, 51)
    models.foreach { model =>
      val c = model.hcursor
      val number = c.get[Int]("language").getOrElse(fail("language"))
      val protocol = c.get[Int]("protocol").getOrElse(fail("protocol"))
      // Its values, one value repeated, or the profile's defaults cut or extended with sevens
      // (the rule of CostModelLengthSuite).
      val values = (
        c.get[Option[Vector[Long]]]("values").toOption.flatten,
        c.get[Option[Long]]("fill").toOption.flatten
      ) match
        case (Some(values), _)  => values
        case (None, Some(fill)) => Vector.fill(c.get[Int]("length").getOrElse(fail("length")))(fill)
        case (None, None) =>
          (defaults(number, protocol) ++ Vector.fill(60)(7L))
            .take(c.get[Int]("length").getOrElse(fail("length")))
      val (_, context) = built(languageOf(number), values)
      assert(context.at(ProtocolMajor(protocol)).isDefined, model.noSpaces.take(80))
    }
  }

  test(
    "every list of the one-parameter sweep makes a context, as the reference's did (sweep.json)"
  ) {
    val sweep = parser
      .parse(Files.readString(Path.of("fixtures/reference/genesis-cost-models/sweep.json")))
      .fold(throw _, identity)
    val extremes = Vector(Long.MinValue, -1L, 0L, Long.MaxValue)
    Vector((1, 5, "PlutusV1"), (2, 7, "PlutusV2"), (3, 11, "PlutusV3")).foreach {
      (number, protocol, name) =>
        val base = defaults(number, protocol)
        assertEquals(
          sweep.hcursor.downField(name).get[Int]("observations").getOrElse(fail(name)),
          base.size * extremes.size
        )
        for
          index <- base.indices
          value <- extremes
        do
          val (_, context) = EvaluationContext.build(languageOf(number), base.updated(index, value))
          assert(context.isRight, s"$name[$index] = $value")
    }
  }
