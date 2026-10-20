// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational

/** Conway-only parameters taken from the Conway genesis when the chain crosses into Conway.
  *
  * @param plutusV3CostModel
  *   The PlutusV3 cost model of the Conway genesis (`plutusV3CostModel`), if present.
  * @param committee
  *   The genesis committee (`None` only if the genesis has no committee section).
  */
final case class ConwayParams(
    drepDeposit: BigInt,
    drepActivity: BigInt,
    govActionDeposit: BigInt,
    govActionLifetime: BigInt,
    minFeeRefScriptCostPerByte: Rational,
    plutusV3CostModel: Vector[BigInt] = Vector.empty,
    poolVotingThresholds: PoolVotingThresholds = PoolVotingThresholds.zero,
    drepVotingThresholds: DRepVotingThresholds = DRepVotingThresholds.zero,
    committeeMinSize: BigInt = 0,
    committeeMaxTermLength: BigInt = 0,
    committee: Option[Committee] = None,
    constitution: Constitution = ConwayGenesis.noConstitution
)

/** Network constants the epoch rules depend on. `stabilityWindow` is 3k/f and `startRewards` the
  * number of slots into an epoch after which the reward calculation starts.
  * @param alonzoCoinsPerUTxOWord
  *   `lovelacePerUTxOWord` of the Alonzo genesis: the parameter the Mary to Alonzo translation
  *   introduces (`upgradePParams`, `Alonzo/PParams.hs`), which the era-indexed parameters of the
  *   eras before it do not carry.
  */
final case class LedgerGlobals(
    maxSupply: BigInt,
    epochLength: BigInt,
    activeSlotsCoeff: Rational,
    network: Int,
    updateQuorum: Int = 5,
    stabilityWindow: BigInt = 0,
    startRewards: BigInt = 0,
    firstEpoch: BigInt = 0,
    conway: Option[ConwayParams] = None,
    alonzoCoinsPerUTxOWord: BigInt = 0
)
