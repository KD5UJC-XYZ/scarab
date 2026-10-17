// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.ledger as node

/** The node-only bookkeeping that has no place in the Haskell state, kept beside a mirror. The
  * consensus side of the mirror adds its own (the Byron and bootstrap state, the previous epoch
  * nonce of the TPraos eras).
  *
  * The UTxO's own counters (`Utxo.count`, `coin`, `stamp`, `flushed`, the diff) are not here: the
  * mirror holds the node's `Utxo` whole behind its `UtxoTables` handle.
  *
  * Until J2.9 this held, besides the counters, the old rules' bookkeeping (the reward draft, the
  * attributed amounts, the pool details of the snapshots, the parameter fields of other eras); the
  * era-indexed rules keep none of it and the fields went with them (snapshot format 5, which
  * `storage` migrates from).
  *
  * @param plutus
  *   the phase-2 evaluation counters
  */
final case class NodeExtras(plutus: node.PlutusStats)

object NodeExtras:
  // The node's own records, compared structurally and rendered by their `toString`.
  given Eq[NodeExtras] = Eq.fromUniversalEquals
  given Show[NodeExtras] = Show.fromToString

  /** The bookkeeping of a state at the start: the phase-2 counters at zero. */
  val empty: NodeExtras = NodeExtras(node.PlutusStats.zero)
