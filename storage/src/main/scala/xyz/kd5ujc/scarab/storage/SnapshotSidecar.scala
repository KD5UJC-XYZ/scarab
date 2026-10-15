// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.consensus.state.ConsensusExtras
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.ledger.{ProtocolParams, Utxo}
import xyz.kd5ujc.scarab.ledger.state.Gap
import xyz.kd5ujc.scarab.protocol.Era

/** What a V3 snapshot holds that the Haskell ledger state beside it has no place for.
  *
  * @param anchor
  *   the last block applied (none: genesis). In a Shelley-based era the header state of the Haskell
  *   section says it too, and the reader checks that the two agree.
  * @param observed
  *   the epochs in which the node saw each era begin where the configuration does not say
  *   ([[xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv.observed]]): the telescopes of the
  *   Haskell section were built with them, so the reader rebuilds them under the same ones. They
  *   are empty after a restart, and then a later snapshot is built on the guesses of
  *   `EraHistory.summary` (roadmap F1).
  * @param gaps
  *   the gaps the state was written with (`Gap`): what the Haskell section holds but the node does
  *   not compute, kept so that a later migration never invents data
  * @param chain
  *   the state itself, by the family of era
  */
final case class SnapshotSidecar(
    anchor: Option[BlockRef],
    observed: Map[Era, BigInt],
    gaps: Set[Gap],
    chain: SnapshotSidecar.Chain
)

object SnapshotSidecar:
  /** The part by family of era. */
  enum Chain:
    /** The chain is still in Byron, whose ledger state the mirror does not model: the node state
      * whole, and no Haskell section.
      */
    case InByron(state: NodeState)

    /** A Shelley-based era.
      *
      * The `utxo` is the node's set as the snapshot left it: its bookkeeping (the stake by
      * credential and pointer, the count and total, the stamps, the diff) which the Haskell section
      * has no `tables` for, while the entries themselves are in the RocksDB store that the
      * snapshot's checkpoint holds.
      *
      * The node's protocol parameters have every field in every era (the later eras' come from
      * their genesis files at the start), while the `PParams` of an era in the Haskell section has
      * only its own: `params` and `prevParams` are the node's, put back after the state is read.
      */
    case InShelley(
        utxo: Utxo,
        params: ProtocolParams,
        prevParams: Option[ProtocolParams],
        extras: ConsensusExtras.InShelley
    )

  import NodeCodecs.given

  given codec: Codec[SnapshotSidecar] = Codec.auto

  /** The schema fingerprint stored in front of the sidecar. */
  def fingerprint: Array[Byte] = SnapshotV2.fingerprint(codec.shape)
