// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.time.Instant
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.network.selection.ConsensusMode
import xyz.kd5ujc.scarab.node.log.NodeEvent

/** The start-up advisory for a node that has a long way to sync in Praos mode without a trusted
  * source (ADR 0022).
  *
  * In Praos mode the node follows the longest chain its peers offer. From an empty chain, or far
  * behind, with nothing that marks a peer as trusted (a bootstrap peer, or a local root with
  * `trustable: true`), that chain is whatever the configured peers say. Genesis mode chooses among
  * the peers by density instead. The reference node has no equivalent warning (its start-up checks
  * of the topology, `TopologyP2P.readTopologyFile` in cardano-node 11.1.3, concern ledger peers,
  * the peer snapshot and bootstrap peers in Genesis mode only), so this is Scarab's own, and it
  * only informs: the mode is the operator's (`ConsensusMode` in the configuration file, default
  * `PraosMode` as the reference's `defaultConsensusMode`).
  */
object SyncAdvisory:

  /** How far the chain the node starts from is behind the wall clock. */
  enum Position:
    /** There is no block yet. */
    case FromOrigin

    /** The tip is more than an epoch of slots behind the clock. */
    case Behind(slots: BigInt)

    /** The tip is within an epoch of the clock. */
    case Near

  /** Whether the topology names a source the node may trust: bootstrap peers (addresses or SRV
    * names) or a local root marked `trustable`. No topology file names none.
    */
  def hasTrustedRoots(topology: Option[Topology]): Boolean =
    topology.exists(t =>
      t.bootstrapPeers.exists(_.nonEmpty) || t.bootstrapSrvPeers.nonEmpty ||
        t.localRoots.exists(_.trustable)
    )

  /** The position of `tipSlot` (the slot of the chain's last block, if any) at `now`. */
  def position(tipSlot: Option[BigInt], now: Instant, geometry: EpochGeometry): Position =
    tipSlot match
      case None => Position.FromOrigin
      case Some(tip) =>
        val lag = geometry.slotAt(now) - tip
        if lag > BigInt(geometry.epochLength) then Position.Behind(lag) else Position.Near

  /** The advisory, when the node is in Praos mode, has far to sync and has no trusted roots. */
  def assess(
      mode: ConsensusMode,
      topology: Option[Topology],
      position: Position
  ): Option[NodeEvent] =
    Option.when(
      mode == ConsensusMode.PraosMode && position != Position.Near && !hasTrustedRoots(topology)
    )(NodeEvent.PraosSyncUntrusted(position))
