// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** A part of the Dijkstra era ([[Era.Dijkstra]]) that this build names but does not implement yet,
  * and so refuses (#54). The node does not enter Dijkstra: these are refusals of data of that era
  * from a peer, a client or a file, never a verdict on it. Each part is removed by the stage of #54
  * that implements it.
  */
enum DijkstraNotYet extends DomainError:
  /** A Dijkstra ledger state in the Haskell `state` file layout of the cardano-node 11.1 line,
    * whose Dijkstra parameters (35, cardano-ledger f649f975) and state this build does not read or
    * write; the Leios build's layout reads and writes Dijkstra's state. Persisted formats.
    */
  case LedgerState

  /** A ledger rule of Dijkstra whose reference differs from Conway's: EPOCH, SNAP, POOL and
    * POOLREAP seat the Leios committee, and BBODY, LEDGER and UTXOW read Dijkstra's block body and
    * transactions (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules`).
    * A decoded Dijkstra block or transaction reaches no rule. Ledger rules.
    */
  case Rules

  /** A pool's BLS key in a `StakePoolParams` of a Dijkstra state in the Leios build's layout, the
    * third of its ten items from version 12 (cardano-ledger 9df9f98c `State/StakePool.hs:744-771`):
    * the pool parameters this build holds have no place for it yet; ten items whose third is `null`
    * are no key, and read. Persisted formats.
    */
  case PoolParamsBlsKey

  /** A parameter update of a governance proposal in a Dijkstra state that sets a parameter Dijkstra
    * adds, update keys 34 to 56 (cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:798-1123`): a proposal's update
    * holds Conway's parameters only. Persisted formats.
    */
  case ParameterUpdate

  /** A Peras epoch context in the Peras state of the Leios build's layout that holds Dijkstra's
    * voting committee (`BoundedPerasEpochContext`, ouroboros-consensus 2416493b
    * `Peras/Context.hs:170-203`; Dijkstra's committee scheme `V1.PerasVotingCommitteeScheme`,
    * `Shelley/Node/Peras.hs:226-234`, which is `WFALS`, `Peras/Voting/V1.hs:39`): Peras is off in
    * the build, whose states hold no epoch context. Persisted formats.
    */
  case PerasEpochContext

  def message: String = this match
    case LedgerState =>
      "a Dijkstra ledger state of the 11.1 layout is not read or written by this build"
    case Rules   => "the ledger rules of Dijkstra are not implemented"
    case PoolParamsBlsKey =>
      "a pool's BLS key in a Dijkstra state's pool parameters is not read by this build"
    case ParameterUpdate =>
      "a Dijkstra parameter update in a governance proposal of the state is not read by this build"
    case PerasEpochContext =>
      "a Peras epoch context with Dijkstra's voting committee is not read by this build"
