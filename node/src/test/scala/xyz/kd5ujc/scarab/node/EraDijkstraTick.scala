// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState,
  GovStateBases,
  GovStateReadError,
  GovStateRecord,
  ProposalsWriteError
}
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraTick, NewEpochGovState}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, SlotNo}

/** Dijkstra's tick on the era's governance state carried from tick to tick beside the node's state
  * ([[DijkstraTick.sts]], the node's own rule), for the suites whose states hold actions the node's
  * record of the governance state cannot hold (an update of keys 34 to 56): the node's path
  * ([[DijkstraTick.onRecord]]) reads and writes that record at every tick, so it cannot carry them.
  * Each state is read and written by the era's reader of the node's record over the bases a suite
  * gives.
  */
object EraDijkstraTick:
  /** A Dijkstra state with its governance state as the era's record. */
  type State = NewEpochGovState[Dijkstra]

  object State:
    def apply(nes: NewEpochState[Dijkstra], gov: ConwayGovState[Dijkstra]): State =
      NewEpochGovState(nes, gov)

  /** The state as the node records it: the era's governance state written to the node's record over
    * `bases`, or the action the record cannot hold.
    */
  def stored(
      s: State,
      bases: GovStateBases
  ): Either[ProposalsWriteError, NewEpochState[Dijkstra]] =
    GovStateRecord
      .toStored[Dijkstra](s.gov, bases)
      .map(NewEpochGovState.record[Dijkstra].replace(_)(s.nes))

  /** Dijkstra's tick to `slot` ([[DijkstraTick.sts]]). */
  def tick(globals: Globals, before: State, slot: SlotNo): Either[TickFailure, State] =
    Sts(DijkstraTick.sts)(globals, (), before, slot)

  /** The node's record of the governance state as the era's, read by the era's reader. */
  def read(nes: NewEpochState[Dijkstra]): Either[GovStateReadError, State] =
    GovStateRecord
      .fromStored[Dijkstra](nes.es.ls.utxo.gov)
      .map(NewEpochGovState(nes, _))

  /** The flat record's parameter positions of `nes`, the bases its era record is written over. */
  def bases(nes: NewEpochState[Dijkstra]): GovStateBases = GovStateBases.of(nes.es.ls.utxo.gov)
