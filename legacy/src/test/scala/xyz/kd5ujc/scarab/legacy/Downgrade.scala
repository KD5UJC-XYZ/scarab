// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.ledger.state.KeyHash.bytes
import xyz.kd5ujc.scarab.ledger.state.SlotNo.toBigInt

/** The live node state as the frozen types hold it: what the builds of V1 to V3 would have had, for
  * the tests that write old snapshot files. The inverse of [[Upgrade.node]] on the states it makes.
  */
object Downgrade:
  def node(s: FlatNodeState): NodeState =
    val p = s.protocol.view
    NodeState(
      PraosState(
        s.protocol.epoch,
        p.evolvingNonce,
        p.candidateNonce,
        p.epochNonce,
        p.labNonce,
        p.lastEpochBlockNonce,
        p.ocertCounters.iterator.map((k, v) => k.bytes -> v.toBigInt).toMap,
        p.previousEpochNonce
      ),
      s.ledger,
      s.boot,
      ByronState(
        s.byron.tip,
        s.byron.stats,
        s.byron.completed,
        s.byron.ledger,
        PBftState(s.byron.pbft.inWindow.map(w => (w.slot.toBigInt, w.genesisKey.bytes))),
        s.byron.firstShelleyEpoch
      )
    )
