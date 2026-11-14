// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import io.circe.{Json, parser}
import java.nio.file.Files
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage, ReferenceCorpus, SemanticsVariant}

/** The default cost models of plutus 1.70.0.0 as the vendored reference data holds them
  * (`cost-model/builtinCostModel{A..E}.json` and `cekMachineCosts{A..E}.json` of the release's
  * view), flattened as the reference's `applyParams` flattens a model (`flattenObject "-"`,
  * `CostModelInterface.hs:290-317`), and the default parameter list of a language in a variant: the
  * variant's values in the order of the language's parameter names. The evaluator carries the
  * shapes only; tests take the values from here.
  */
object DefaultCostModels:
  private lazy val view = ReferenceCorpus.view("1.70.0.0")

  /** The text of a file of the 1.70.0.0 view, by its path in the layout. */
  def read(path: String): String =
    Files.readString(
      view.getOrElse(path, throw new IllegalStateException(s"no $path in the 1.70.0.0 view"))
    )

  private def json(path: String): Json = parser.parse(read(path)).fold(throw _, identity)

  /** The leaves of a JSON object with their flattened names. */
  def flatten(json: Json, prefix: String = ""): Vector[(String, Json)] =
    json.asObject.fold(Vector(prefix -> json))(
      _.toVector.flatMap((key, value) =>
        flatten(value, if prefix.isEmpty then key else s"$prefix-$key")
      )
    )

  private def files(variant: SemanticsVariant): Vector[String] =
    Vector(s"cost-model/cekMachineCosts$variant.json", s"cost-model/builtinCostModel$variant.json")

  /** Every numeric leaf of the variant's two default models. */
  def parameters(variant: SemanticsVariant): Map[String, Long] =
    files(variant)
      .flatMap(file => flatten(json(file)))
      .collect { case (name, value) if value.isNumber => name -> value.asNumber.flatMap(_.toLong) }
      .map((name, value) => name -> value.getOrElse(throw new IllegalStateException(name)))
      .toMap

  /** Every `"type"` leaf of the variant's builtin model. */
  def types(variant: SemanticsVariant): Map[String, String] =
    flatten(json(s"cost-model/builtinCostModel$variant.json")).collect {
      case (name, value) if value.isString => name -> value.asString.getOrElse("")
    }.toMap

  /** The names of the variant's builtin model (its top-level keys). */
  def builtins(variant: SemanticsVariant): Set[String] =
    json(s"cost-model/builtinCostModel$variant.json").asObject.fold(Set.empty[String])(_.keys.toSet)

  /** A language whose evaluation context holds `variant`. */
  def languageOf(variant: SemanticsVariant): PlutusLanguage =
    if SemanticsVariant.ofContext(PlutusLanguage.V1).contains(variant) then PlutusLanguage.V1
    else PlutusLanguage.V3

  /** The default list of `language` in `variant`. */
  def list(language: PlutusLanguage, variant: SemanticsVariant): Vector[Long] =
    val values = parameters(variant)
    EvaluationContext
      .parameterNames(language)
      .map(name =>
        values.getOrElse(name, throw new IllegalStateException(s"$variant has no $name"))
      )

  /** The costs of `variant` with its default parameters, through the ledger's path: the list, the
    * names, the model.
    */
  def costs(variant: SemanticsVariant): VariantCosts =
    val language = languageOf(variant)
    val (_, parameters) =
      EvaluationContext.tagWithParamNames(
        EvaluationContext.parameterNames(language),
        list(language, variant)
      )
    EvaluationContext
      .costsOf(variant, parameters)
      .fold(e => throw new IllegalStateException(e.toString), identity)

  val Variants: Vector[SemanticsVariant] = Vector(
    SemanticsVariant.A,
    SemanticsVariant.B,
    SemanticsVariant.C,
    SemanticsVariant.D,
    SemanticsVariant.E
  )
