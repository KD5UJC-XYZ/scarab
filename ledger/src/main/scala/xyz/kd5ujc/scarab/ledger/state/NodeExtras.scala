// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node

/** What the node holds about a pool of a stake snapshot that `StakePoolSnapShot` has no place for:
  * the owners (the snapshot keeps the ones that delegate to the pool), the relays and the metadata.
  * The reward account's network is the node's (`MirrorEnv.network`).
  */
final case class PoolResidue(owners: Set[Bytes], relays: Bytes, metadata: Option[Bytes])

object PoolResidue:
  given Eq[PoolResidue] = Eq.fromUniversalEquals
  given Show[PoolResidue] = Show.fromToString

  def of(params: node.PoolParams): PoolResidue =
    PoolResidue(params.owners, params.relays, params.metadata)

/** The residue of the three snapshots, by pool, and the pools of `mark` the node counts as having a
  * delegator with no stake (the mirror derives the exact count from the accounts, which agrees on a
  * real state; the node's own set is kept so that a state comes back as it was).
  */
final case class SnapshotsResidue(
    mark: Map[Bytes, PoolResidue],
    set: Map[Bytes, PoolResidue],
    go: Map[Bytes, PoolResidue],
    markIdle: Set[Bytes]
)

object SnapshotsResidue:
  given Eq[SnapshotsResidue] = Eq.fromUniversalEquals
  given Show[SnapshotsResidue] = Show.fromToString

  val empty: SnapshotsResidue = SnapshotsResidue(Map.empty, Map.empty, Map.empty, Set.empty)

/** The fields of the node's ledger state that have no place in the state of the era the mirror is
  * of: a pre-Conway state holds no governance, DReps or committee, and a Conway state holds no
  * pointers and no parameter-update proposals. The node's state carries them in every era, empty on
  * a real state, and `LedgerMirror.from` puts back what is here, so a mirror of a state that does
  * have them is not lossy; the corpus tests assert that [[Residue.isEmpty]] on every real state.
  */
final case class Residue(
    ppup: node.Ppup,
    ptrs: Map[node.Ptr, node.Credential],
    gov: node.GovState,
    dreps: Map[node.Credential, node.DRepState],
    committeeState: Map[node.Credential, node.CommitteeAuth],
    dormantEpochs: BigInt,
    accountDreps: Map[node.Credential, node.DRep]
):
  def isEmpty: Boolean = this == Residue.empty

object Residue:
  val empty: Residue = Residue(
    node.Ppup.empty,
    Map.empty,
    node.GovState.empty,
    Map.empty,
    Map.empty,
    BigInt(0),
    Map.empty
  )

  given Eq[Residue] = Eq.fromUniversalEquals
  given Show[Residue] = Show.fromToString

/** The two parameters of the flat `ProtocolParams` that some eras' parameters lack: the mirror
  * zeroes the ones the era has no field for, and the originals are kept here.
  */
final case class ParamFields(minUTxOValue: BigInt, coinsPerUTxOWord: BigInt):
  def restore(p: node.ProtocolParams): node.ProtocolParams =
    p.copy(minUTxOValue = minUTxOValue, coinsPerUTxOWord = coinsPerUTxOWord)

object ParamFields:
  given Eq[ParamFields] = Eq.fromUniversalEquals
  given Show[ParamFields] = Show.fromToString

  def of(p: node.ProtocolParams): ParamFields = ParamFields(p.minUTxOValue, p.coinsPerUTxOWord)

/** The parameter fields of the current and the previous parameters. */
final case class DroppedParams(cur: ParamFields, prev: ParamFields)

object DroppedParams:
  given Eq[DroppedParams] = Eq.fromUniversalEquals
  given Show[DroppedParams] = Show.fromToString

/** The node-only bookkeeping that has no place in the Haskell state, kept beside a mirror so that
  * the node's state is recovered from it (`LedgerMirror.from`) and never exported. The consensus
  * side of the mirror adds its own (the Byron and bootstrap state, the previous epoch nonce of the
  * TPraos eras).
  *
  * The UTxO's own counters (`Utxo.count`, `coin`, `stamp`, `flushed`, the diff) are not here: the
  * mirror holds the node's `Utxo` whole behind its `UtxoTables` handle.
  *
  * @param plutus
  *   the phase-2 evaluation counters
  * @param rewardDraft
  *   the reward update computed at the epoch boundary and not yet made visible
  * @param rewardRegistered
  *   the registered credentials when the reward calculation started (before Babbage)
  * @param rewardAmounts
  *   what each credential is paid by the reward update: the mirror's `rs` is unattributed to pools
  *   (the node does not know the pools) and is empty, so the amounts are here
  * @param prevParamsExplicit
  *   `prevParams` was set (the mirror's previous parameters are always there)
  * @param pulserAbsent
  *   the state is from before the first epoch boundary of the era, so there is no completed DRep
  *   pulser (the mirror's is a placeholder)
  * @param snapshots
  *   the pool details of the three snapshots that `StakePoolSnapShot` has no place for
  * @param poolDistribution
  *   the node's `poolDistribution` when it is not the `set` snapshot (a state at the first epochs
  *   of a chain), where `nesPd` is not `PoolDistr.of(set)`
  * @param params
  *   the parameter fields that the era's parameters lack (see [[ParamFields]])
  * @param residue
  *   the fields that have no place in the era
  */
final case class NodeExtras(
    plutus: node.PlutusStats,
    rewardDraft: Option[node.RewardUpdate],
    rewardRegistered: Option[Set[node.Credential]],
    rewardAmounts: Map[node.Credential, BigInt],
    prevParamsExplicit: Boolean,
    pulserAbsent: Boolean,
    snapshots: SnapshotsResidue,
    poolDistribution: Option[node.Snapshot],
    params: DroppedParams,
    residue: Residue
)

object NodeExtras:
  // The node's own records, compared structurally and rendered by their `toString`.
  given Eq[NodeExtras] = Eq.fromUniversalEquals
  given Show[NodeExtras] = Show.fromToString
