// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, UTxO}
import xyz.kd5ujc.scarab.ledger.state.Conway

/** Conway's unspent outputs read from the outputs the node keeps. */
object ConwayUTxO:
  /** Each stored output read as a Conway output ([[ConwayTxOut.fromStored]]), the first one it
    * refuses refusing the whole: only a damaged store holds such an output.
    */
  def fromStored(
      stored: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
  ): Either[EraTxError, UTxO[Conway]] =
    stored.toVector
      .traverse((in, out) => ConwayTxOut.fromStored(out).map(in -> _))
      .map(outs => UTxO[Conway](outs.toMap))
