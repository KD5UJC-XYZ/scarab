// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.{Tx, TxIn}

/** A block decoded once for both the read-set fetch and the rules. */
final case class Prepared(block: Block.Shelley, txs: Vector[Either[LedgerDecodeError, Tx]]):
  /** Everything the transactions can read from the UTxO set (not what the block itself creates).
    */
  def inputs: Vector[TxIn] =
    val created = txs.collect { case Right(t) => t.body.id }.toSet
    txs.flatMap {
      case Right(t) =>
        (t.body.inputs ++ t.body.referenceInputs ++ t.body.collateral).filterNot(i =>
          created.contains(i.id)
        )
      case Left(_) => Vector.empty
    }
