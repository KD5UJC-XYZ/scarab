// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import xyz.kd5ujc.scarab.ledger.{
  ConfigError,
  ConfigProblem,
  CostModelLanguage,
  GenesisCostModels,
  GenesisFieldError,
  GenesisFile,
  GenesisSchema,
  ProtocolParams
}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.json.{BoundedRatio, FromJson, JsonKind, NumberError}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The Dijkstra genesis (`DijkstraGenesis`, cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Genesis.hs:32-35`): a newtype of the parameters
  * the translation from Conway introduces (`UpgradeDijkstraPParams`, `Dijkstra/PParams.hs:410-435`,
  * whose fields these are, in its order), which is the translation's context (`TranslationContext
  * DijkstraEra`, `Dijkstra/Translation.hs:51`). They are the values of the fields Dijkstra adds to
  * the parameters ([[ProtocolParams]], from `maxRefScriptSizePerBlock`) and the PlutusV4 cost
  * model.
  *
  * @param maxPledgeLeverage
  *   `MaxPledgeLeverage`, a `NonNegativeInterval` or none (`Core/PParams.hs:369-373`).
  * @param perasBootstrapRound
  *   a `Word32` or none.
  */
final case class DijkstraGenesis(
    maxRefScriptSizePerBlock: BigInt,
    maxRefScriptSizePerTx: BigInt,
    refScriptCostStride: BigInt,
    refScriptCostMultiplier: Rational,
    maxPledgeLeverage: Option[Rational],
    minPoolMargin: Rational,
    plutusV4CostModel: Vector[Long],
    leiosAnnouncementPeriodLength: BigInt,
    leiosVotePeriodLength: BigInt,
    leiosDiffusionPeriodLength: BigInt,
    leiosCommitteeSize: BigInt,
    leiosQuorumStakeThreshold: Rational,
    maxEndorserBlockReferencesSize: BigInt,
    maxEndorserBlockTxsSize: BigInt,
    maxEndorserBlockExUnits: ExUnits,
    maxRefScriptSizePerEndorserBlock: BigInt,
    perasMinCandidateBlockAge: BigInt,
    perasHealingFactor: Rational,
    perasCertBoost: BigInt,
    perasTargetCommitteeSize: BigInt,
    perasBootstrapRound: Option[BigInt],
    perasQuorumThresholdSafetyMargin: Rational,
    refInputsCostPerMultiAssetPolicy: BigInt,
    refInputsCostPerDatumByte: BigInt
):

  /** `upgradeDijkstraPParams` (`PParams.hs:593-665`) of Conway's parameters: every Conway field
    * kept, the PlutusV4 model laid over the cost models (`hkdAlignWith` of both present,
    * `updateCostModels`), and each field Dijkstra adds the genesis value.
    */
  def upgrade(p: ProtocolParams): ProtocolParams =
    p.copy(
      costModels =
        p.costModels.updated(CostModelLanguage.PlutusV4.tag, plutusV4CostModel.map(BigInt(_))),
      maxRefScriptSizePerBlock = maxRefScriptSizePerBlock,
      maxRefScriptSizePerTx = maxRefScriptSizePerTx,
      refScriptCostStride = refScriptCostStride,
      refScriptCostMultiplier = refScriptCostMultiplier,
      maxPledgeLeverage = maxPledgeLeverage,
      minPoolMargin = minPoolMargin,
      leiosAnnouncementPeriodLength = leiosAnnouncementPeriodLength,
      leiosVotePeriodLength = leiosVotePeriodLength,
      leiosDiffusionPeriodLength = leiosDiffusionPeriodLength,
      leiosCommitteeSize = leiosCommitteeSize,
      leiosQuorumStakeThreshold = leiosQuorumStakeThreshold,
      maxEndorserBlockReferencesSize = maxEndorserBlockReferencesSize,
      maxEndorserBlockTxsSize = maxEndorserBlockTxsSize,
      maxEndorserBlockExUnits = maxEndorserBlockExUnits,
      maxRefScriptSizePerEndorserBlock = maxRefScriptSizePerEndorserBlock,
      perasMinCandidateBlockAge = perasMinCandidateBlockAge,
      perasHealingFactor = perasHealingFactor,
      perasCertBoost = perasCertBoost,
      perasTargetCommitteeSize = perasTargetCommitteeSize,
      perasBootstrapRound = perasBootstrapRound,
      perasQuorumThresholdSafetyMargin = perasQuorumThresholdSafetyMargin,
      refInputsCostPerMultiAssetPolicy = refInputsCostPerMultiAssetPolicy,
      refInputsCostPerDatumByte = refInputsCostPerDatumByte
    )

object DijkstraGenesis:

  /** `FromJSON (UpgradeDijkstraPParams Identity era)` (`PParams.hs:442-468`) over the whole file,
    * as `readGenesisAny` decodes it (cardano-node b004fe0c `Protocol/Shelley.hs:120-140`): an
    * object, its fields read in this order, a key it does not know ignored. Every field is `.:`
    * except the maximum pledge leverage, `.:? .!= MaxPledgeLeverage SNothing`, absent or null being
    * none; the Peras bootstrap round is required and may be null.
    */
  def read(json: Json): Either[ConfigError, DijkstraGenesis] =
    for
      o <- json.asObject.toRight(kind(Vector.empty, JsonKind.Object, json))
      blockScripts <- req(o, "maxRefScriptSizePerBlock")(FromJson.word(32))
      txScripts <- req(o, "maxRefScriptSizePerTx")(FromJson.word(32))
      // `NonZero Word32` (`BaseTypes/NonZero.hs:108-109`)
      stride <- req(o, "refScriptCostStride")(j =>
        FromJson.word(32)(j).flatMap(v => Either.cond(v != 0, v, NumberError.Zero))
      )
      multiplier <- req(o, "refScriptCostMultiplier")(BoundedRatio.Positive.fromJson)
      leverage <- o("maxPledgeLeverage")
        .filterNot(_.isNull)
        .traverse(number("maxPledgeLeverage", _)(BoundedRatio.NonNegative.fromJson))
      margin <- req(o, "minPoolMargin")(BoundedRatio.Unit.fromJson)
      v4 <- costModel(o, "plutusV4CostModel")
      // `Milliseconds32` is a `Word32` (`BaseTypes.hs:706-708`)
      announcement <- req(o, "leiosAnnouncementPeriodLength")(FromJson.word(32))
      vote <- req(o, "leiosVotePeriodLength")(FromJson.word(32))
      diffusion <- req(o, "leiosDiffusionPeriodLength")(FromJson.word(32))
      committee <- req(o, "leiosCommitteeSize")(FromJson.word(16))
      quorum <- req(o, "leiosQuorumStakeThreshold")(BoundedRatio.Unit.fromJson)
      references <- req(o, "maxEndorserBlockReferencesSize")(FromJson.word(32))
      ebTxs <- req(o, "maxEndorserBlockTxsSize")(FromJson.word(32))
      ebUnits <- exUnits(o, "maxEndorserBlockExecutionUnits")
      ebScripts <- req(o, "maxRefScriptSizePerEndorserBlock")(FromJson.word(32))
      // `SlotInterval` is a `Word32` (cardano-base c1e1ed8d `Cardano/Slotting/Slot.hs:56-61`)
      candidateAge <- req(o, "perasMinCandidateBlockAge")(FromJson.word(32))
      healing <- req(o, "perasHealingFactor")(BoundedRatio.Positive.fromJson)
      boost <- req(o, "perasCertBoost")(FromJson.word(16))
      perasCommittee <- req(o, "perasTargetCommitteeSize")(FromJson.word(16))
      // `StrictMaybe Word32` under `.:`: present, null being none
      bootstrap <- present(o, "perasBootstrapRound").flatMap(j =>
        Option.unless(j.isNull)(j).traverse(number("perasBootstrapRound", _)(FromJson.word(32)))
      )
      margin2 <- req(o, "perasQuorumThresholdSafetyMargin")(BoundedRatio.Unit.fromJson)
      // `Coin` is an `Integer` (`Coin.hs:93-103`), `CoinPerByte` a compact coin, a `Word64`
      // (`Coin.hs:147-150, 220-222`)
      perPolicy <- req(o, "refInputsCostPerMultiAssetPolicy")(FromJson.integer)
      perDatumByte <- req(o, "refInputsCostPerDatumByte")(j => FromJson.word64(j).map(_.toBigInt))
    yield DijkstraGenesis(
      blockScripts,
      txScripts,
      stride,
      multiplier,
      leverage,
      margin,
      v4,
      announcement,
      vote,
      diffusion,
      committee,
      quorum,
      references,
      ebTxs,
      ebUnits,
      ebScripts,
      candidateAge,
      healing,
      boost,
      perasCommittee,
      bootstrap,
      margin2,
      perPolicy,
      perDatumByte
    )

  private def fieldError(path: Vector[String], cause: GenesisFieldError): ConfigError =
    ConfigError(ConfigProblem.GenesisField(GenesisFile.Dijkstra, path, cause))

  private def kind(path: Vector[String], expected: JsonKind, json: Json): ConfigError =
    fieldError(path, GenesisFieldError.Kind(expected, JsonKind.of(json)))

  /** `.:`: the key's value, an absent key refused. */
  private def present(o: JsonObject, name: String): Either[ConfigError, Json] =
    o(name).toRight(fieldError(Vector(name), GenesisFieldError.Missing))

  private def number[A](name: String, json: Json)(
      read: Json => Either[NumberError, A]
  ): Either[ConfigError, A] =
    read(json).leftMap(e => fieldError(Vector(name), GenesisFieldError.Number(e)))

  private def req[A](o: JsonObject, name: String)(
      read: Json => Either[NumberError, A]
  ): Either[ConfigError, A] =
    present(o, name).flatMap(number(name, _)(read))

  /** `parseCostModelAsArray False PlutusV4 =<< o .: name` (`CostModels.hs:188-192`): an array of
    * `Int64` of exactly the language's initial number of parameters.
    */
  private def costModel(o: JsonObject, name: String): Either[ConfigError, Vector[Long]] =
    for
      json <- present(o, name)
      _ <- json.asArray.toRight(kind(Vector(name), JsonKind.Array, json))
      values <- GenesisCostModels
        .plutusV4(json)
        .leftMap(p => ConfigError(ConfigProblem.GenesisCostModel(p)))
    yield values

  /** `FromJSON OrdExUnits`, a newtype of `FromJSON ExUnits` (`Plutus/ExUnits.hs:120-130, 162-164`):
    * an object, each unit under either of its names, within `Word64`.
    */
  private def exUnits(o: JsonObject, name: String): Either[ConfigError, ExUnits] =
    for
      json <- present(o, name)
      _ <- json.asObject.toRight(kind(Vector(name), JsonKind.Object, json))
      units = Json.fromJsonObject(o).hcursor.downField(name)
      memory <- GenesisSchema.unit(GenesisFile.Dijkstra)(
        units,
        Vector(name),
        "memory",
        "exUnitsMem"
      )
      steps <- GenesisSchema.unit(GenesisFile.Dijkstra)(
        units,
        Vector(name),
        "steps",
        "exUnitsSteps"
      )
    yield ExUnits(memory, steps)
