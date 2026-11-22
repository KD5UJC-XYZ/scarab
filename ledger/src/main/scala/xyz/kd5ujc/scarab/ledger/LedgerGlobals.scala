// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.{Network, Word64}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The KES constants of the network: `maxKESEvo` and `slotsPerKESPeriod` of `Globals`
  * (`BaseTypes.hs:761-789`, cardano-ledger f649f975), which `mkShelleyGlobals` takes from the
  * Shelley genesis (`sgMaxKESEvolutions`, `sgSlotsPerKESPeriod`, `Shelley/Genesis.hs:314-315`). The
  * Leios build derives the age a voting key is honoured for from them (`maxKeyAgeEpochs`,
  * cardano-ledger 9df9f98c `Dijkstra/Rules/Snap.hs:150-179`).
  */
final case class KesParameters(maxEvolutions: Word64, slotsPerPeriod: Word64)

object KesParameters:
  /** Mainnet's (its Shelley genesis: `maxKESEvolutions` 62, `slotsPerKESPeriod` 129600), for the
    * tests and tools whose state comes from no genesis file.
    */
  val Mainnet: KesParameters = KesParameters(Word64.fromUnsigned(62L), Word64.fromUnsigned(129600L))

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
  * @param network
  *   `networkId` of `Globals`: the Shelley genesis's `networkId` (`mkShelleyGlobals`,
  *   cardano-ledger 9df9f98c `Shelley/Genesis.hs:775`), never one decided from the network magic.
  * @param systemStart
  *   `systemStart` of `Globals`: the Shelley genesis's `systemStart` (`mkShelleyGlobals`,
  *   cardano-ledger 9df9f98c `Shelley/Genesis.hs:781,784`), from which the Plutus script contexts'
  *   times count; not the hard-fork combinator's ([[EpochGeometry.systemStart]]).
  * @param alonzoCoinsPerUTxOWord
  *   `lovelacePerUTxOWord` of the Alonzo genesis: the parameter the Mary to Alonzo translation
  *   introduces (`upgradePParams`, `Alonzo/PParams.hs`), which the era-indexed parameters of the
  *   eras before it do not carry.
  * @param dijkstra
  *   The Dijkstra genesis, the context of the translation from Conway (`TranslationContext
  *   DijkstraEra`, cardano-ledger 9df9f98c `Dijkstra/Translation.hs:51`), when the configuration
  *   names its file. The reference reads it only with `ExperimentalHardForksEnabled` and otherwise
  *   takes `emptyDijkstraGenesis` (cardano-node b004fe0c `Protocol/Cardano.hs:137-144`), which
  *   nothing reads, as the Dijkstra trigger is read only with the same setting (`Configuration/
  *   POM.hs:713-715`).
  * @param kes
  *   The network's KES constants ([[KesParameters]]), from the Shelley genesis.
  * @param referenceBuild
  *   The build of `cardano-node` whose rules the ledger follows (`--reference-build`, #826): where
  *   two builds' rules differ, a rule matches on it. `NodeSetup` sets it to the build its
  *   constructors are given (`NodeSetup.mirroring`), as it sets [[dijkstra]] from the
  *   configuration, so the node and every tool that reads a configuration follow the build they
  *   name; elsewhere it is [[ReferenceBuild.Default]].
  */
final case class LedgerGlobals(
    maxSupply: BigInt,
    epochLength: BigInt,
    activeSlotsCoeff: Rational,
    network: Network,
    systemStart: SystemStart,
    updateQuorum: Int = 5,
    stabilityWindow: BigInt = 0,
    startRewards: BigInt = 0,
    firstEpoch: BigInt = 0,
    conway: Option[ConwayParams] = None,
    alonzoCoinsPerUTxOWord: BigInt = 0,
    dijkstra: Option[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGenesis] = None,
    referenceBuild: ReferenceBuild = ReferenceBuild.Default,
    kes: KesParameters
)
