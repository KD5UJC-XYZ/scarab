// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.consensus as live

/** The frozen node state brought to the live one: the only way the old state types reach the rest
  * of the node.
  */
object Upgrade:
  def node(old: NodeState): live.NodeState =
    live.NodeState(
      live.PraosState(
        old.praos.epoch,
        old.praos.evolving,
        old.praos.candidate,
        old.praos.epochNonce,
        old.praos.lab,
        old.praos.lastEpochBlock,
        old.praos.counters,
        old.praos.previousEpochNonce
      ),
      old.ledger,
      old.boot,
      live.ByronState(
        old.byron.tip,
        old.byron.stats,
        old.byron.completed,
        old.byron.ledger,
        live.PBftState(old.byron.pbft.signers),
        old.byron.firstShelleyEpoch
      )
    )
