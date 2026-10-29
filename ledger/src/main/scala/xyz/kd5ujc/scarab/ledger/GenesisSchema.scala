// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Json, JsonObject}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.json.{BoundedRatio, FromJson, JsonKind, NumberError}

/** The Shelley, Alonzo and Conway genesis files as the reference's parsers read them: every field
  * they require is required here, of the type they read it with, and a field they read as optional
  * may be absent (cardano-ledger f649f975). The node checks each file at start-up, so a file the
  * reference node would refuse is refused before any reader of it runs.
  *
  *   - Shelley: `instance FromJSON ShelleyGenesis` (`Shelley/Genesis.hs:494-513`) and its
  *     `LegacyJSONPParams` (`403-437`).
  *   - Alonzo: `instance FromJSON AlonzoGenesis` (`Alonzo/Genesis.hs:222-242`), with `Prices` and
  *     `ExUnits` of `Plutus/ExUnits.hs`.
  *   - Conway: `instance FromJSON ConwayGenesis` (`Conway/Genesis.hs:156-165`) and
  *     `UpgradeConwayPParams` (`Conway/PParams.hs:1082-1095`).
  *
  * The cost models are read by [[GenesisCostModels]], whose refusal is kept as it is
  * ([[ConfigProblem.GenesisCostModel]]); the funds' addresses by [[GenesisState.address]] and the
  * committee's credentials by [[GenesisState.credential]], whose refusals are kept as
  * [[GenesisFieldError.Nested]].
  */
object GenesisSchema:
  def shelley(json: Json): Either[ConfigError, Unit] = run(GenesisFile.Shelley, shelleyFile)(json)

  def alonzo(json: Json): Either[ConfigError, Unit] =
    run(GenesisFile.Alonzo, alonzoFile)(json) *> costModels(GenesisCostModels.alonzo(json))

  def conway(json: Json): Either[ConfigError, Unit] =
    run(GenesisFile.Conway, conwayFile)(json) *> costModels(GenesisCostModels.conwayPlutusV3(json))

  /** A bounded ratio at `path` under `cursor` ([[BoundedRatio.fromJson]]), for the readers of the
    * files: the same instance the check reads it with, so a file the check accepts is read.
    */
  def ratio(file: GenesisFile, interval: BoundedRatio)(
      cursor: ACursor,
      path: Vector[String]
  ): Either[ConfigError, Rational] =
    at(file, cursor, path).flatMap(json =>
      interval.fromJson(json).leftMap(e => fieldError(file, path, GenesisFieldError.Number(e)))
    )

  /** A unit of `FromJSON ExUnits` (`Plutus/ExUnits.hs:118-128`): `o .: first <|> o .: second` as a
    * `Natural`, then within `Word64` (so a first name out of bounds does not fall back).
    */
  def unit(file: GenesisFile)(
      cursor: ACursor,
      path: Vector[String],
      first: String,
      second: String
  ): Either[ConfigError, BigInt] =
    def natural(name: String) =
      at(file, cursor.downField(name), path :+ name).flatMap(json =>
        FromJson
          .natural(json)
          .leftMap(e => fieldError(file, path :+ name, GenesisFieldError.Number(e)))
      )
    natural(first)
      .orElse(natural(second))
      .flatMap(n =>
        Either.cond(
          n <= state.Word64.max.toBigInt,
          n,
          fieldError(
            file,
            path,
            GenesisFieldError.Number(NumberError.NotWord(64, json.Scientific(n, 0)))
          )
        )
      )

  /** `updateQuorum`, a `Word64` (`Shelley/Genesis.hs:507`). A quorum above the number of genesis
    * delegates is never reached, and that number is within `Int`, so a larger quorum is read as
    * `Int.MaxValue`: no vote count reaches either.
    */
  def updateQuorum(shelley: Json): Either[ConfigError, Int] =
    val path = Vector("updateQuorum")
    at(GenesisFile.Shelley, shelley.hcursor.downField("updateQuorum"), path).flatMap(json =>
      FromJson
        .word64(json)
        .bimap(
          e => fieldError(GenesisFile.Shelley, path, GenesisFieldError.Number(e)),
          w => w.toBigInt.min(BigInt(Int.MaxValue)).toInt
        )
    )

  private def at(file: GenesisFile, cursor: ACursor, path: Vector[String]) =
    cursor.focus.toRight(fieldError(file, path, GenesisFieldError.Missing))

  /** A refusal at `path` below the value checked. */
  private final case class Failure(path: Vector[String], cause: GenesisFieldError)

  private type Check = Json => Either[Failure, Unit]

  private enum Presence:
    /** `.:`: absent is a refusal. */
    case Required

    /** `.:?` (with `.!=` or `maybeToStrictMaybe`): absent or `null` is the default. */
    case Optional

  /** A field under one of `names` read by `check`; `after` reads the value that was taken. */
  private final case class Field(
      names: Vector[String],
      presence: Presence,
      check: Check,
      after: Check = _ => Right(())
  )

  private def req(name: String, check: Check): Field = Field(Vector(name), Presence.Required, check)
  private def opt(name: String, check: Check): Field = Field(Vector(name), Presence.Optional, check)

  /** `o .: first <|> o .: second`: the first name that is present and reads. */
  private def either(first: String, second: String, check: Check, after: Check = _ => Right(())) =
    Field(Vector(first, second), Presence.Required, check, after)

  private def run(file: GenesisFile, check: Check)(json: Json): Either[ConfigError, Unit] =
    check(json).leftMap(f => fieldError(file, f.path, f.cause))

  private def fieldError(file: GenesisFile, path: Vector[String], cause: GenesisFieldError) =
    ConfigError(ConfigProblem.GenesisField(file, path, cause))

  /** The cost models' refusal as [[GenesisCostModels]] gives it: it names the field and language.
    */
  private def costModels[A](read: Either[CostModelProblem, A]): Either[ConfigError, Unit] =
    read.bimap(e => ConfigError(ConfigProblem.GenesisCostModel(e)), _ => ())

  private def leaf[A](read: Json => Either[GenesisFieldError, A]): Check =
    json => read(json).bimap(Failure(Vector.empty, _), _ => ())

  private def number[A](read: Json => Either[NumberError, A]): Check =
    leaf(read(_).leftMap(GenesisFieldError.Number(_)))

  private def kind(expected: JsonKind)(json: Json): Either[GenesisFieldError, Json] =
    val found = JsonKind.of(json)
    Either.cond(found == expected, json, GenesisFieldError.Kind(expected, found))

  private def anObjectOf(json: Json): Either[GenesisFieldError, JsonObject] =
    json.asObject.toRight(GenesisFieldError.Kind(JsonKind.Object, JsonKind.of(json)))

  private val anything: Check = _ => Right(())
  private val text: Check = leaf(kind(JsonKind.String))
  private val anObject: Check = leaf(kind(JsonKind.Object))
  private val integer: Check = number(FromJson.integer)
  private val natural: Check = number(FromJson.natural)
  private val word64: Check = number(FromJson.word64)
  private def word(bits: Int): Check = number(FromJson.word(bits))
  private def bounded(interval: BoundedRatio): Check = number(interval.fromJson)

  private def string(json: Json): Either[GenesisFieldError, String] =
    json.asString.toRight(GenesisFieldError.Kind(JsonKind.String, JsonKind.of(json)))

  private def choice(allowed: String*): Check =
    leaf(json =>
      string(json).flatMap(v =>
        Either.cond(allowed.contains(v), v, GenesisFieldError.Choice(v, allowed.toVector))
      )
    )

  /** A hash: hexadecimal text of `bytes` bytes (`FromJSON (Hash h a)`). */
  private def hexHash(bytes: Int)(value: String): Either[GenesisFieldError, Unit] =
    Bytes
      .fromHex(value)
      .toOption
      .filter(_.size == bytes)
      .toRight(GenesisFieldError.Hash(bytes, value))
      .void

  private def hash(bytes: Int): Check = leaf(string(_).flatMap(hexHash(bytes)))

  /** An object read field by field, in the order the instance reads them. */
  private def obj(fields: Field*): Check = json =>
    anObjectOf(json).leftMap(Failure(Vector.empty, _)).flatMap { o =>
      fields.toVector.traverse_ { field =>
        // `<|>`: the first name that is present and reads; otherwise the last one's refusal
        def attempt(name: String) =
          o(name)
            .toRight(Failure(Vector(name), GenesisFieldError.Missing))
            .flatMap(v => field.check(v).as(v).leftMap(f => f.copy(path = name +: f.path)))
            .flatMap(v => field.after(v).leftMap(f => f.copy(path = name +: f.path)))
            .map(_ => ())
        val absent = field.names.forall(n => o(n).forall(_.isNull))
        if field.presence == Presence.Optional && absent then Right(())
        else
          // `after` reads the value `<|>` took, so it does not make `<|>` try the next name
          val taken = field.names.find(n => o(n).exists(v => field.check(v).isRight))
          taken.fold(attempt(field.names.last))(attempt)
      }
    }

  /** A JSON object read as a map: each key by `key`, each value by `value`. */
  private def mapOf(key: String => Either[GenesisFieldError, Unit], value: Check): Check = json =>
    anObjectOf(json).leftMap(Failure(Vector.empty, _)).flatMap { o =>
      o.toVector.traverse_ { (k, v) =>
        key(k).leftMap(Failure(Vector(k), _)) *> value(v).leftMap(f => f.copy(path = k +: f.path))
      }
    }

  /** `FromJSONKey Credential`: `keyHash-` or `scriptHash-` and 28 bytes of hexadecimal. */
  private def credential(key: String): Either[GenesisFieldError, Unit] =
    GenesisState.credential(key).bimap(e => GenesisFieldError.Nested(e.problem), _ => ())

  /** `FromJSON Version` (`Binary/Version.hs:86-87`): a `Word32` within `MinVersion .. MaxVersion`
    * (0 to 13).
    */
  private val version: Check = number(json =>
    FromJson
      .word(32)(json)
      .flatMap(v =>
        Either.cond(v <= 13, v, NumberError.OutsideOfBounds(xyz.kd5ujc.scarab.codec.Rational(v)))
      )
  )

  /** `parseNonce` of `LegacyJSONPParams` (`Shelley/Genesis.hs:427-437`). */
  private val nonce: Check = json =>
    obj(req("tag", choice("Nonce", "NeutralNonce")))(json) *>
      json.hcursor
        .downField("tag")
        .focus
        .filter(_ == Json.fromString("Nonce"))
        .traverse_(_ => obj(req("contents", hash(32)))(json))

  private val shelleyParams: Check = obj(
    req("minFeeA", integer),
    req("minFeeB", integer),
    req("maxBlockBodySize", word(32)),
    req("maxTxSize", word(32)),
    req("maxBlockHeaderSize", word(16)),
    req("keyDeposit", integer),
    req("poolDeposit", integer),
    req("eMax", word(32)),
    req("nOpt", word(16)),
    req("a0", bounded(BoundedRatio.NonNegative)),
    req("rho", bounded(BoundedRatio.Unit)),
    req("tau", bounded(BoundedRatio.Unit)),
    req("decentralisationParam", bounded(BoundedRatio.Unit)),
    req("extraEntropy", nonce),
    req("protocolVersion", obj(req("major", version), req("minor", natural))),
    opt("minUTxOValue", integer),
    opt("minPoolCost", integer)
  )

  private def address(hex: String): Either[GenesisFieldError, Unit] =
    GenesisState.address(hex).bimap(e => GenesisFieldError.Nested(e.problem), _ => ())

  private val shelleyFile: Check = obj(
    req("systemStart", text),
    req("networkMagic", word(32)),
    req("networkId", choice("Mainnet", "Testnet")),
    req("activeSlotsCoeff", bounded(BoundedRatio.PositiveUnit)),
    req("securityParam", number(FromJson.nonZeroWord64)),
    req("epochLength", word64),
    req("slotsPerKESPeriod", word64),
    req("maxKESEvolutions", word64),
    req("slotLength", number(FromJson.scientific)),
    req("updateQuorum", word64),
    req("maxLovelaceSupply", word64),
    req("protocolParams", shelleyParams),
    req("genDelegs", mapOf(hexHash(28), obj(req("delegate", hash(28)), req("vrf", hash(32))))),
    req("initialFunds", mapOf(address, integer)),
    // `FromJSON ShelleyGenesisStaking` (`Shelley/Genesis.hs:526-531`); its entries are read by
    // [[rules.GenesisInjections]]
    opt("staking", obj(req("pools", anObject), req("stake", anObject))),
    // `FromJSON ShelleyExtraConfig` (`Shelley/Genesis.hs:255-260`): an object of optional
    // injections, read by [[rules.GenesisInjections]]
    opt("extraConfig", anObject)
  )

  /** `FromJSON ExUnits` (`Plutus/ExUnits.hs:118-128`): each unit `o .: "memory" <|> o .:
    * "exUnitsMem"` (and the steps alike) as a `Natural`, then within `Word64`.
    */
  private val exUnits: Check = obj(
    either("memory", "exUnitsMem", natural, word64),
    either("steps", "exUnitsSteps", natural, word64)
  )

  private val alonzoFile: Check = obj(
    req("lovelacePerUTxOWord", integer),
    req("costModels", anything),
    // `FromJSON Prices` (`Plutus/ExUnits.hs:176-181`)
    req(
      "executionPrices",
      obj(
        either("priceSteps", "prSteps", bounded(BoundedRatio.NonNegative)),
        either("priceMemory", "prMem", bounded(BoundedRatio.NonNegative))
      )
    ),
    req("maxTxExUnits", exUnits),
    req("maxBlockExUnits", exUnits),
    req("maxValueSize", word(32)),
    req("collateralPercentage", word(16)),
    req("maxCollateralInputs", word(16)),
    // `FromJSON AlonzoExtraConfig` (`Alonzo/Genesis.hs:110-114`): an object whose optional cost
    // models [[GenesisCostModels]] reads
    opt("extraConfig", anObject)
  )

  private def thresholds(names: String*): Check =
    obj(names.map(req(_, bounded(BoundedRatio.Unit)))*)

  private val conwayFile: Check = obj(
    req(
      "poolVotingThresholds",
      thresholds(
        "motionNoConfidence",
        "committeeNormal",
        "committeeNoConfidence",
        "hardForkInitiation",
        "ppSecurityGroup"
      )
    ),
    req(
      "dRepVotingThresholds",
      thresholds(
        "motionNoConfidence",
        "committeeNormal",
        "committeeNoConfidence",
        "updateToConstitution",
        "hardForkInitiation",
        "ppNetworkGroup",
        "ppEconomicGroup",
        "ppTechnicalGroup",
        "ppGovGroup",
        "treasuryWithdrawal"
      )
    ),
    req("committeeMinSize", word(16)),
    req("committeeMaxTermLength", word(32)),
    req("govActionLifetime", word(32)),
    req("govActionDeposit", integer),
    req("dRepDeposit", integer),
    req("dRepActivity", word(32)),
    req("minFeeRefScriptCostPerByte", bounded(BoundedRatio.NonNegative)),
    req("plutusV3CostModel", anything),
    req(
      "constitution",
      obj(req("anchor", obj(req("url", text), req("dataHash", hash(32)))), opt("script", hash(28)))
    ),
    req(
      "committee",
      obj(req("members", mapOf(credential, word64)), req("threshold", bounded(BoundedRatio.Unit)))
    ),
    opt("delegs", anObject),
    opt("initialDReps", anObject),
    // `FromJSON ConwayExtraConfig` (`Conway/Genesis.hs:99-103`): an object of optional injections
    opt("extraConfig", anObject)
  )
