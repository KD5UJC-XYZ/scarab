// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node

// FROZEN with the flat state (J2.9): the bookkeeping the old rules kept beside the mirror, as the sidecars
// of V3 and V4 held it. The live `NodeExtras` keeps the phase-2 counters only.

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
  * a real state. Test support in `LedgerMirrorBack.from` restores this residue, so conversion of a
  * state that does have it is not lossy; the corpus tests assert [[Residue.isEmpty]] on real
  * states.
  */
final case class Residue(
    ppup: Ppup,
    ptrs: Map[node.Ptr, node.Credential],
    gov: GovState,
    dreps: Map[node.Credential, node.DRepState],
    committeeState: Map[node.Credential, node.CommitteeAuth],
    dormantEpochs: BigInt,
    accountDreps: Map[node.Credential, node.DRep]
):
  def isEmpty: Boolean = this == Residue.empty

object Residue:
  val empty: Residue = Residue(
    Ppup.empty,
    Map.empty,
    GovState.empty,
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
  * test support in `LedgerMirrorBack.from` recovers the flat state; it is never exported. The
  * consensus side of the mirror adds its own (the Byron and bootstrap state, the previous epoch
  * nonce of the TPraos eras).
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
  *   pulser (the mirror's is `ConwayEpoch.initialRatify`). Not derivable from the mirror: a state
  *   with no pulser and one whose pulser is the initial one differ in `FuturePParams` (the second
  *   is `Potential` early in the epoch), but not in a late slot, and the laws generate both
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
    rewardDraft: Option[RewardUpdate],
    rewardRegistered: Option[Set[node.Credential]],
    rewardAmounts: Map[node.Credential, BigInt],
    prevParamsExplicit: Boolean,
    pulserAbsent: Boolean,
    snapshots: SnapshotsResidue,
    poolDistribution: Option[Snapshot],
    params: DroppedParams,
    residue: Residue
)

object NodeExtras:
  // The node's own records, compared structurally and rendered by their `toString`.
  given Eq[NodeExtras] = Eq.fromUniversalEquals
  given Show[NodeExtras] = Show.fromToString

  /** The bookkeeping of a state the era-indexed rules made: nothing of the old rules', which they
    * do not keep (the reward draft, the residues, the parameter fields of other eras), and the
    * phase-2 counters at zero.
    */
  val empty: NodeExtras = NodeExtras(
    node.PlutusStats.zero,
    None,
    None,
    Map.empty,
    prevParamsExplicit = false,
    pulserAbsent = false,
    SnapshotsResidue.empty,
    None,
    DroppedParams(ParamFields(0, 0), ParamFields(0, 0)),
    Residue.empty
  )
