// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.plutus.ParamNames

/** A Plutus language of the ledger (`Language`, `Cardano/Ledger/Plutus/Language.hs`), with the
  * number of parameters its cost model had when it was added (`costModelInitParamCount`,
  * `Cardano/Ledger/Plutus/CostModels.hs` lines 233-241 at cardano-ledger `f649f975`).
  */
enum CostModelLanguage(val tag: Int, val key: String, val initialCount: Int):
  case PlutusV1 extends CostModelLanguage(0, "PlutusV1", 166)
  case PlutusV2 extends CostModelLanguage(1, "PlutusV2", 175)
  case PlutusV3 extends CostModelLanguage(2, "PlutusV3", 251)
  case PlutusV4 extends CostModelLanguage(3, "PlutusV4", 251)

  /** `costModelParamNames` (`CostModels.hs` lines 221-223): the names of the parameters in the
    * order of the on-chain list, those of the genesis files for PlutusV1.
    */
  def parameterNames: Vector[String] = this match
    case PlutusV1            => GenesisCostModels.genesisNamesV1
    case PlutusV2            => ParamNames.v2
    case PlutusV3 | PlutusV4 => ParamNames.v3

  /** `costModelInitParamNames` (`CostModels.hs` lines 227-228). */
  def initialNames: Vector[String] = parameterNames.take(initialCount)

/** Why a genesis file's cost models are refused. Each case is a failure of the reference's JSON
  * parsers (`parseCostModels` and what calls it), in the order the reference checks them.
  */
enum CostModelProblem extends DomainError:
  /** A value that must be an object: `CostModels` (`withObject`), `extraConfig`, the genesis file.
    */
  case NotObject(actual: Json)

  /** The cost model of a language is neither an array nor an object (`parseCostModel`). */
  case NotObjectOrArray(language: CostModelLanguage, actual: Json)

  /** The Conway genesis' PlutusV3 cost model is not an array (`parseCostModelAsArray`). */
  case NotArray(actual: Json)

  /** A parameter that is not an `Int64`. */
  case NotInt64(language: Option[CostModelLanguage], actual: Json)

  /** A cost model given by name lacks parameters (`costModelFromMap`), listed in order. */
  case MissingParameters(language: CostModelLanguage, names: Vector[String])

  /** The non-lenient parsers need exactly the initial number of parameters
    * (`guardNumberOfParameters`).
    */
  case ParameterCount(language: CostModelLanguage, supplied: Int, expected: Int)

  /** The Alonzo genesis has no PlutusV1 cost model (`AlonzoGenesis`). */
  case MissingLanguage(language: CostModelLanguage)

  /** A key of `Unknown` is not a `Word8` literal. */
  case UnknownKey(key: String)

  /** A required key is absent. */
  case MissingField(field: String)

  def message: String = this match
    case NotObject(actual)      => s"expected an object, got ${actual.noSpaces.take(80)}"
    case NotObjectOrArray(l, _) => s"${l.key} cost model is neither an array nor an object"
    case NotArray(actual)       => s"expected an array, got ${actual.noSpaces.take(80)}"
    case NotInt64(l, actual) =>
      s"${l.fold("cost model")(_.key)} parameter ${actual.noSpaces.take(80)} is not an Int64"
    case MissingParameters(l, n) => s"${l.key} cost model lacks parameters: ${n.mkString(", ")}"
    case ParameterCount(l, supplied, expected) =>
      s"${l.key} cost model has $supplied parameters, expected $expected"
    case MissingLanguage(l) => s"the genesis has no ${l.key} cost model"
    case UnknownKey(key)    => s"unknown cost model key `$key` is not a Word8"
    case MissingField(f)    => s"missing required field $f"

/** The cost models of the genesis files as the reference reads them.
  *
  *   - The Alonzo genesis' `costModels` go through `parseCostModels False [PlutusV1]`, its
  *     `extraConfig.costModels` through `parseCostModels True []`, and the PlutusV1 model is
  *     required (`Alonzo/Genesis.hs` lines 110-114 and 222-242).
  *   - The Conway genesis' `plutusV3CostModel` goes through `parseCostModelAsArray False PlutusV3`
  *     (`Conway/PParams.hs` line 1095).
  *   - `parseCostModels`, `parseCostModel`, `parseCostModelAsMap`, `parseCostModelAsArray`,
  *     `guardNumberOfParameters`, `costModelFromMap`, `mkCostModelsLenient` and `updateCostModels`
  *     are in `Cardano/Ledger/Plutus/CostModels.hs` lines 138-200, 205-215, 400-415 and 421-436.
  *
  * What is not mirrored: `mkCostModel` (lines 278-291) hands the values to Plutus
  * `mkEvaluationContext`, whose failure `CostModelApplyError` cannot be told from the parameter
  * names. `fixtures/reference/genesis-cost-models/sweep.json` records that the reference accepted
  * every list of the sweep (each parameter of each language at `Int64` 's minimum, `-1`, `0` and
  * maximum, 4,188 lists), so no refusal is produced here; the list is not a proof for every list.
  *
  * All the files in `fixtures/reference/genesis-cost-models` come from the unchanged reference.
  */
object GenesisCostModels:

  /** `CostModels`: the models of the languages the ledger knows, with the values they were built
    * from, and the values of the languages it does not (`Word8` to parameters).
    */
  final case class Models(
      valid: Map[CostModelLanguage, Vector[Long]],
      unknown: Map[Int, Vector[Long]]
  ):
    /** `updateCostModels` (lines 400-415): the languages of `update` replace those here, and a
      * language that becomes valid no longer counts as unknown.
      */
    def updated(update: Models): Models =
      val newValid = valid ++ update.valid
      Models(newValid, (unknown ++ update.unknown) -- newValid.keys.map(_.tag))

    /** `flattenCostModels` (lines 446-451): the language tag to the parameters. */
    def flatten: Map[Int, Vector[BigInt]] =
      (unknown ++ valid.map((language, values) => language.tag -> values)).map((tag, values) =>
        tag -> values.map(BigInt(_))
      )

  object Models:
    val empty: Models = Models(Map.empty, Map.empty)

  /** The PlutusV1 parameter names of the genesis files: the names of the `ParamName` enum, but six
    * of them under the older names (`plutusV1ParamNames`, `CostModels.hs` lines 247-260).
    */
  private[ledger] val genesisNamesV1: Vector[String] =
    val older = Map(
      "blake2b_256-cpu-arguments-intercept" -> "blake2b-cpu-arguments-intercept",
      "blake2b_256-cpu-arguments-slope" -> "blake2b-cpu-arguments-slope",
      "blake2b_256-memory-arguments" -> "blake2b-memory-arguments",
      "verifyEd25519Signature-cpu-arguments-intercept" -> "verifySignature-cpu-arguments-intercept",
      "verifyEd25519Signature-cpu-arguments-slope" -> "verifySignature-cpu-arguments-slope",
      "verifyEd25519Signature-memory-arguments" -> "verifySignature-memory-arguments"
    )
    ParamNames.v1.map(name => older.getOrElse(name, name))

  /** The cost models of an Alonzo genesis file: its PlutusV1 model, and the models of
    * `extraConfig.costModels` when it has them.
    */
  final case class Alonzo(plutusV1: Vector[Long], extra: Option[Models]):
    /** The initial cost models: the PlutusV1 model, with the extra ones laid over it. */
    def models: Models =
      Models(Map(CostModelLanguage.PlutusV1 -> plutusV1), Map.empty)
        .updated(extra.getOrElse(Models.empty))

  /** `FromJSON AlonzoGenesis` for the cost models (`Alonzo/Genesis.hs` lines 222-242). */
  def alonzo(genesis: Json): Either[CostModelProblem, Alonzo] =
    for
      o <- genesis.asObject.toRight(CostModelProblem.NotObject(genesis))
      raw <- o("costModels").toRight(CostModelProblem.MissingField("costModels"))
      base <- parse(lenient = false, Vector(CostModelLanguage.PlutusV1), raw)
      extra <- extraConfig(o)
      v1 <- base.valid
        .get(CostModelLanguage.PlutusV1)
        .toRight(CostModelProblem.MissingLanguage(CostModelLanguage.PlutusV1))
    yield Alonzo(v1, extra)

  /** `FromJSON AlonzoExtraConfig` (`Alonzo/Genesis.hs` lines 110-114): an absent or null
    * `extraConfig` has no models, and `costModels` is optional in it.
    */
  private def extraConfig(genesis: JsonObject): Either[CostModelProblem, Option[Models]] =
    present(genesis, "extraConfig")
      .traverse { config =>
        for
          o <- config.asObject.toRight(CostModelProblem.NotObject(config))
          models <- present(o, "costModels").traverse(parse(lenient = true, Vector.empty, _))
        yield models
      }
      .map(_.flatten)

  /** `.:?`: a key that is absent or null has no value. */
  private def present(o: JsonObject, key: String): Option[Json] = o(key).filterNot(_.isNull)

  /** The PlutusV3 cost model of a Conway genesis file (`Conway/PParams.hs` line 1095). */
  def conwayPlutusV3(genesis: Json): Either[CostModelProblem, Vector[Long]] =
    for
      o <- genesis.asObject.toRight(CostModelProblem.NotObject(genesis))
      raw <- o("plutusV3CostModel").toRight(CostModelProblem.MissingField("plutusV3CostModel"))
      array <- raw.asArray.toRight(CostModelProblem.NotArray(raw))
      values <- parseAsArray(lenient = false, CostModelLanguage.PlutusV3, array)
    yield values

  /** `parseCostModels` (`CostModels.hs` lines 141-165).
    *
    * @param lenient
    *   do not restrict the number of parameters to the initial count, and read the `Unknown` models
    * @param languages
    *   the languages to read; all of them when empty
    */
  def parse(
      lenient: Boolean,
      languages: Vector[CostModelLanguage],
      value: Json
  ): Either[CostModelProblem, Models] =
    for
      o <- value.asObject.toRight(CostModelProblem.NotObject(value))
      chosen = if languages.isEmpty then CostModelLanguage.values.toVector else languages
      parsed <- chosen.traverse(language =>
        parseCostModel(lenient, o, language).map(_.tupleLeft(language))
      )
      unknown <- if lenient then unknownModels(o) else Right(Models.empty)
    yield Models(parsed.flatMap(_.map((language, values) => language -> values)).toMap, Map.empty)
      .updated(unknown)

  /** `parseCostModel` (`CostModels.hs` lines 173-180). */
  private def parseCostModel(
      lenient: Boolean,
      o: JsonObject,
      language: CostModelLanguage
  ): Either[CostModelProblem, Option[Vector[Long]]] =
    present(o, language.key).traverse { value =>
      value.fold(
        jsonNull = Left(CostModelProblem.NotObjectOrArray(language, value)),
        jsonBoolean = _ => Left(CostModelProblem.NotObjectOrArray(language, value)),
        jsonNumber = _ => Left(CostModelProblem.NotObjectOrArray(language, value)),
        jsonString = _ => Left(CostModelProblem.NotObjectOrArray(language, value)),
        jsonArray = parseAsArray(lenient, language, _),
        jsonObject = parseAsMap(lenient, language, _)
      )
    }

  /** `parseCostModelAsMap` (`CostModels.hs` lines 182-186): the parameters by name, in the order of
    * the names, and the object must have exactly the initial number of keys unless lenient.
    */
  private def parseAsMap(
      lenient: Boolean,
      language: CostModelLanguage,
      m: JsonObject
  ): Either[CostModelProblem, Vector[Long]] =
    for
      named <- m.toVector.traverse((name, value) => int64(Some(language), value).tupleLeft(name))
      byName = named.toMap
      missing = language.initialNames.filterNot(byName.contains)
      _ <- Either.cond(missing.isEmpty, (), CostModelProblem.MissingParameters(language, missing))
      _ <- guardNumberOfParameters(lenient, language, m.size)
    yield language.initialNames.map(byName)

  /** `parseCostModelAsArray` (`CostModels.hs` lines 188-192). */
  private def parseAsArray(
      lenient: Boolean,
      language: CostModelLanguage,
      a: Vector[Json]
  ): Either[CostModelProblem, Vector[Long]] =
    for
      values <- a.traverse(int64(Some(language), _))
      _ <- guardNumberOfParameters(lenient, language, values.size)
    yield values

  /** `guardNumberOfParameters` (`CostModels.hs` lines 194-203). */
  private def guardNumberOfParameters(
      lenient: Boolean,
      language: CostModelLanguage,
      supplied: Int
  ): Either[CostModelProblem, Unit] =
    Either.cond(
      lenient || supplied == language.initialCount,
      (),
      CostModelProblem.ParameterCount(language, supplied, language.initialCount)
    )

  /** The `Unknown` models of a lenient parse and `mkCostModelsLenient` (`CostModels.hs` lines
    * 421-436): the models of a language the ledger knows count as valid, the others as unknown.
    */
  private def unknownModels(o: JsonObject): Either[CostModelProblem, Models] =
    present(o, "Unknown").fold(Right(Models.empty)) { raw =>
      for
        entries <- raw.asObject.toRight(CostModelProblem.NotObject(raw))
        parsed <- entries.toVector.traverse { (key, value) =>
          for
            tag <- word8(key).toRight(CostModelProblem.UnknownKey(key))
            array <- value.asArray.toRight(CostModelProblem.NotArray(value))
            values <- array.traverse(int64(None, _))
          yield tag -> values
        }
      yield
        val (unknown, known) = parsed.toMap.toVector.partitionMap { (tag, values) =>
          CostModelLanguage.values.find(_.tag == tag).map(_ -> values).toRight(tag -> values)
        }
        Models(known.toMap, unknown.toMap)
    }

  private def int64(
      language: Option[CostModelLanguage],
      value: Json
  ): Either[CostModelProblem, Long] =
    value.asNumber.flatMap(_.toLong).toRight(CostModelProblem.NotInt64(language, value))

  /** A key of a `Word8` map: a number literal (`scientific` of attoparsec) that is a whole number
    * from 0 to 255, also written `04`, `+4`, `4.0` or `4e0`.
    */
  private def word8(key: String): Option[Int] =
    Option
      .when(literal.matches(key))(scala.util.Try(BigDecimal(key)).toOption)
      .flatten
      .filter(n => n.signum == 0 || (n.precision - n.scale <= 3 && n.isWhole && n >= 0 && n <= 255))
      .map(_.toInt)

  private val literal = raw"[+-]?[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?".r
