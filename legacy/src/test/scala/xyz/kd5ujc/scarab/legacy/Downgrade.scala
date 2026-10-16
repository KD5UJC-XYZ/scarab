// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.consensus as live

/** The live node state as the frozen types hold it: what the builds of V1 to V3 would have had, for
  * the tests that write old snapshot files. The inverse of [[Upgrade.node]] on the states it makes.
  */
object Downgrade:
  def node(s: live.NodeState): NodeState =
    NodeState(
      PraosState(
        s.praos.epoch,
        s.praos.evolving,
        s.praos.candidate,
        s.praos.epochNonce,
        s.praos.lab,
        s.praos.lastEpochBlock,
        s.praos.counters,
        s.praos.previousEpochNonce
      ),
      s.ledger,
      s.boot,
      ByronState(
        s.byron.tip,
        s.byron.stats,
        s.byron.completed,
        s.byron.ledger,
        PBftState(s.byron.pbft.signers),
        s.byron.firstShelleyEpoch
      )
    )
