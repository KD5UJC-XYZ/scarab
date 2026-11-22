// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.era.ConwayGovState
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraTick, NewEpochGovState}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, SlotNo}

/** Dijkstra's tick on the era's governance state carried from tick to tick beside the node's state
  * ([[DijkstraTick.sts]], the rule the node's path runs, [[DijkstraTick.onRecord]]), for the suites
  * that change the governance state between ticks.
  */
object EraDijkstraTick:
  /** A Dijkstra state with its governance state beside it. */
  type State = NewEpochGovState[Dijkstra]

  object State:
    /** `nes` with `gov` as its governance state, and beside it. */
    def apply(nes: NewEpochState[Dijkstra], gov: ConwayGovState[Dijkstra]): State =
      NewEpochGovState(NewEpochGovState.record.replace(gov)(nes), gov)

  /** The state as the node holds it: its governance state the one beside it. */
  def stored(s: State): NewEpochState[Dijkstra] = NewEpochGovState.record.replace(s.gov)(s.nes)

  /** Dijkstra's tick to `slot` ([[DijkstraTick.sts]]). */
  def tick(globals: Globals, before: State, slot: SlotNo): Either[TickFailure, State] =
    Sts(DijkstraTick.sts)(globals, (), before, slot)

  /** A node's Dijkstra state with its governance state beside it. */
  def read(nes: NewEpochState[Dijkstra]): State = NewEpochGovState(nes, nes.es.ls.utxo.gov)
