// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.state.EraTag

/** The unspent outputs of era `E` (`newtype UTxO era = UTxO {unUTxO :: Map TxIn (TxOut era)}`,
  * cardano-ledger f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/State/UTxO.hs:92`): each
  * output at its era's type.
  */
final case class UTxO[E <: EraTag](unUTxO: Map[TxIn, TxOut[E]])
