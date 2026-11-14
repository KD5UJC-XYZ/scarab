// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.{BinaryError, Rational}
import xyz.kd5ujc.scarab.ledger.{
  DRepVotingThresholds,
  Nonce,
  PoolVotingThresholds,
  ProtocolParams as LiveProtocolParams
}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The node's protocol parameters as the sidecars of the snapshot versions before
  * [[xyz.kd5ujc.scarab.ledger.FormatVersion.V9]] hold them, frozen: Shelley's to Conway's fields.
  * The live [[LiveProtocolParams]] grew the fields Dijkstra adds at V9, which changes the shape of
  * its derived codec and with it the schema fingerprint of every sidecar that holds parameters; the
  * readers of the previous versions take this codec instead, whose shape and bytes are those the
  * files were written with (the `CodecSuite` goldens of the previous sidecar schemas guard it).
  */
private[storage] object FrozenProtocolParams:
  // Named as the live type was, so the derived shape is `ProtocolParams(minFeeA:...)`.
  final case class ProtocolParams(
      minFeeA: BigInt,
      minFeeB: BigInt,
      maxTxSize: BigInt,
      maxBlockBodySize: BigInt,
      maxValueSize: BigInt,
      coinsPerUTxOByte: BigInt,
      keyDeposit: BigInt,
      poolDeposit: BigInt,
      eMax: BigInt,
      nOpt: BigInt,
      a0: Rational,
      rho: Rational,
      tau: Rational,
      minPoolCost: BigInt,
      protocolMajor: BigInt,
      drepDeposit: BigInt,
      drepActivity: BigInt,
      govActionDeposit: BigInt,
      govActionLifetime: BigInt,
      minFeeRefScriptCostPerByte: Rational,
      protocolMinor: BigInt,
      maxBlockHeaderSize: BigInt,
      d: Rational,
      extraEntropy: Nonce,
      minUTxOValue: BigInt,
      coinsPerUTxOWord: BigInt,
      priceMem: Rational,
      priceSteps: Rational,
      maxTxExUnits: ExUnits,
      maxBlockExUnits: ExUnits,
      collateralPercentage: BigInt,
      maxCollateralInputs: BigInt,
      costModels: Map[Int, Vector[BigInt]],
      poolVotingThresholds: PoolVotingThresholds,
      drepVotingThresholds: DRepVotingThresholds,
      committeeMinSize: BigInt,
      committeeMaxTermLength: BigInt
  )

  import NodeCodecs.given

  private val frozen: Codec[ProtocolParams] = Codec.auto

  /** The parameters of a previous-version file, the fields Dijkstra adds at their pre-Dijkstra
    * defaults: the values the node's parameters have in every era before Dijkstra.
    */
  def live(p: ProtocolParams): LiveProtocolParams =
    LiveProtocolParams(
      p.minFeeA,
      p.minFeeB,
      p.maxTxSize,
      p.maxBlockBodySize,
      p.maxValueSize,
      p.coinsPerUTxOByte,
      p.keyDeposit,
      p.poolDeposit,
      p.eMax,
      p.nOpt,
      p.a0,
      p.rho,
      p.tau,
      p.minPoolCost,
      p.protocolMajor,
      p.drepDeposit,
      p.drepActivity,
      p.govActionDeposit,
      p.govActionLifetime,
      p.minFeeRefScriptCostPerByte,
      protocolMinor = p.protocolMinor,
      maxBlockHeaderSize = p.maxBlockHeaderSize,
      d = p.d,
      extraEntropy = p.extraEntropy,
      minUTxOValue = p.minUTxOValue,
      coinsPerUTxOWord = p.coinsPerUTxOWord,
      priceMem = p.priceMem,
      priceSteps = p.priceSteps,
      maxTxExUnits = p.maxTxExUnits,
      maxBlockExUnits = p.maxBlockExUnits,
      collateralPercentage = p.collateralPercentage,
      maxCollateralInputs = p.maxCollateralInputs,
      costModels = p.costModels,
      poolVotingThresholds = p.poolVotingThresholds,
      drepVotingThresholds = p.drepVotingThresholds,
      committeeMinSize = p.committeeMinSize,
      committeeMaxTermLength = p.committeeMaxTermLength
    )

  /** The live parameters without the fields Dijkstra adds. */
  def previous(p: LiveProtocolParams): ProtocolParams =
    ProtocolParams(
      p.minFeeA,
      p.minFeeB,
      p.maxTxSize,
      p.maxBlockBodySize,
      p.maxValueSize,
      p.coinsPerUTxOByte,
      p.keyDeposit,
      p.poolDeposit,
      p.eMax,
      p.nOpt,
      p.a0,
      p.rho,
      p.tau,
      p.minPoolCost,
      p.protocolMajor,
      p.drepDeposit,
      p.drepActivity,
      p.govActionDeposit,
      p.govActionLifetime,
      p.minFeeRefScriptCostPerByte,
      p.protocolMinor,
      p.maxBlockHeaderSize,
      p.d,
      p.extraEntropy,
      p.minUTxOValue,
      p.coinsPerUTxOWord,
      p.priceMem,
      p.priceSteps,
      p.maxTxExUnits,
      p.maxBlockExUnits,
      p.collateralPercentage,
      p.maxCollateralInputs,
      p.costModels,
      p.poolVotingThresholds,
      p.drepVotingThresholds,
      p.committeeMinSize,
      p.committeeMaxTermLength
    )

  /** The live parameters in the frozen schema, read totally into the live type. The writer is the
    * previous-version tests' (the node writes only the current version): it drops the fields
    * Dijkstra adds, which a node before Dijkstra holds at their defaults, so what such a node held
    * reads back unchanged.
    */
  val codec: Codec[LiveProtocolParams] = new Codec[LiveProtocolParams]:
    def write(out: Out, a: LiveProtocolParams): Unit = frozen.write(out, previous(a))
    def read(in: In): Either[BinaryError, LiveProtocolParams] = frozen.read(in).map(live)
    def shape: String = frozen.shape
