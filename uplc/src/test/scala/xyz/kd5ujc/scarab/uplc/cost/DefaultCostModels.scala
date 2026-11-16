// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import io.circe.{Json, parser}
import java.nio.file.Files
import xyz.kd5ujc.scarab.uplc.{ParamNames, PlutusLanguage, ReferenceCorpus, SemanticsVariant}

/** The default cost models of a plutus release as the vendored reference data holds them
  * (`cost-model/builtinCostModel{A..E}.json` and `cekMachineCosts{A..E}.json` of the release's
  * view; variants F and G of 1.71.0.0 read D's and E's, `ExBudgetingDefaults.hs:158-159,185-186` at
  * `261766ad`), flattened as the reference's `applyParams` flattens a model (`flattenObject "-"`,
  * `CostModelInterface.hs:290-317`), and the default parameter list of a language in a variant: the
  * variant's values in the order of the release's parameter names for the language. The evaluator
  * carries the shapes only; tests take the values from here.
  *
  * @param tag
  *   the release, a tag of [[ReferenceCorpus.Releases]]
  */
class DefaultCostModelsOf(val tag: String):
  private lazy val view = ReferenceCorpus.view(tag)

  /** The text of a file of the release's view, by its path in the layout. */
  def read(path: String): String =
    Files.readString(
      view.getOrElse(path, throw new IllegalStateException(s"no $path in the $tag view"))
    )

  private def json(path: String): Json = parser.parse(read(path)).fold(throw _, identity)

  /** The variant whose files hold `variant` 's default models. */
  private def file(variant: SemanticsVariant): SemanticsVariant = variant match
    case SemanticsVariant.F => SemanticsVariant.D
    case SemanticsVariant.G => SemanticsVariant.E
    case other              => other

  private def files(variant: SemanticsVariant): Vector[String] =
    Vector(
      s"cost-model/cekMachineCosts${file(variant)}.json",
      s"cost-model/builtinCostModel${file(variant)}.json"
    )

  /** Every numeric leaf of the variant's two default models. */
  def parameters(variant: SemanticsVariant): Map[String, Long] =
    files(variant)
      .flatMap(file => DefaultCostModels.flatten(json(file)))
      .collect { case (name, value) if value.isNumber => name -> value.asNumber.flatMap(_.toLong) }
      .map((name, value) => name -> value.getOrElse(throw new IllegalStateException(name)))
      .toMap

  /** Every `"type"` leaf of the variant's builtin model. */
  def types(variant: SemanticsVariant): Map[String, String] =
    DefaultCostModels
      .flatten(json(s"cost-model/builtinCostModel${file(variant)}.json"))
      .collect {
        case (name, value) if value.isString => name -> value.asString.getOrElse("")
      }
      .toMap

  /** The names of the variant's builtin model (its top-level keys). */
  def builtins(variant: SemanticsVariant): Set[String] =
    json(s"cost-model/builtinCostModel${file(variant)}.json").asObject
      .fold(Set.empty[String])(_.keys.toSet)

  /** The parameter names of `language` in the release: 1.70.0.0's tables, or the evaluator's
    * (1.71.0.0's, [[EvaluationContext.parameterNames]]).
    */
  def names(language: PlutusLanguage): Vector[String] =
    if tag == DefaultCostModels.Plutus170.tag then
      language.evaluatedAs match
        case PlutusLanguage.V1                     => ParamNames.v1
        case PlutusLanguage.V2                     => ParamNames.v2
        case PlutusLanguage.V3 | PlutusLanguage.V4 => ParamNames.v3
    else EvaluationContext.parameterNames(language)

  /** The default list of `language` in `variant`. */
  def list(language: PlutusLanguage, variant: SemanticsVariant): Vector[Long] =
    val values = parameters(variant)
    names(language).map(name =>
      values.getOrElse(name, throw new IllegalStateException(s"$tag $variant has no $name"))
    )

  /** The costs of `variant` with its default parameters, through the ledger's path: the list, the
    * evaluator's names, the model.
    */
  def costs(variant: SemanticsVariant): VariantCosts =
    val language = DefaultCostModels.languageOf(variant)
    val (_, parameters) =
      EvaluationContext.tagWithParamNames(
        EvaluationContext.parameterNames(language),
        list(language, variant)
      )
    EvaluationContext
      .costsOf(variant, parameters)
      .fold(e => throw new IllegalStateException(e.toString), identity)

/** The defaults of the evaluator's release, plutus 1.71.0.0, and of 1.70.0.0. */
object DefaultCostModels extends DefaultCostModelsOf("1.71.0.0"):
  val Plutus170: DefaultCostModelsOf = new DefaultCostModelsOf("1.70.0.0")

  /** The leaves of a JSON object with their flattened names. */
  def flatten(json: Json, prefix: String = ""): Vector[(String, Json)] =
    json.asObject.fold(Vector(prefix -> json))(
      _.toVector.flatMap((key, value) =>
        flatten(value, if prefix.isEmpty then key else s"$prefix-$key")
      )
    )

  /** A language whose evaluation context holds `variant`. */
  def languageOf(variant: SemanticsVariant): PlutusLanguage =
    if SemanticsVariant.ofContext(PlutusLanguage.V1).contains(variant) then PlutusLanguage.V1
    else PlutusLanguage.V3

  /** Every variant of 1.71.0.0. */
  val Variants: Vector[SemanticsVariant] = SemanticsVariant.values.toVector

  /** The variants of 1.70.0.0, A to E. */
  val Variants170: Vector[SemanticsVariant] = Variants.take(5)
