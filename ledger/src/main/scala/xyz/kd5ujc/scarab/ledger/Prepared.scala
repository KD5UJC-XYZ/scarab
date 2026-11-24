// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayBlock
import xyz.kd5ujc.scarab.protocol.StateLayout

/** A block decoded once for both the read-set fetch and the rules, as the rules of its era read it:
  * a Conway block in the era's types, which Conway's era rules apply (#85); a block of another era
  * with its transactions read by the node's flat decoder, which the flat rules apply.
  */
enum Prepared:
  /** A block of an era before or after Conway, its transactions decoded by the flat decoder. */
  case Flat(block: Block.Shelley, txs: Vector[Either[LedgerDecodeError, Tx]])

  /** A Conway block, read into the era's types ([[ConwayBlock.Read]]). */
  case Conway(read: ConwayBlock.Read)

  /** The block as the block decoder read it. */
  def envelope: Block.Shelley = this match
    case Flat(block, _) => block
    case Conway(read)   => read.envelope

  /** Everything the transactions can read from the UTxO set (not what the block itself creates). */
  def inputs: Set[TxIn] = this match
    case Flat(_, txs) =>
      val created = txs.collect { case Right(t) => t.body.id }.toSet
      txs.iterator.flatMap {
        case Right(t) =>
          (t.body.inputs ++ t.body.referenceInputs ++ t.body.collateral).filterNot(i =>
            created.contains(i.id)
          )
        case Left(_) => Vector.empty
      }.toSet
    case Conway(read) => read.inputs

object Prepared:
  /** The block encoded as `raw`, decoded as the rules of its era read it, a Conway block's
    * transactions in the pool parameters' `layout`; none where the bytes are not a block of a
    * Shelley-based era the flat decoder reads (the rules then decode them and give their verdict).
    */
  def decode(raw: Bytes, layout: StateLayout): Option[Prepared] =
    ConwayBlock.read(raw, layout).toOption.flatMap {
      case Right(read)            => Some(Conway(read))
      case Left(b: Block.Shelley) => Some(Flat(b, b.transactions.map(t => Tx.fromBlock(t, b.era))))
      case Left(_)                => None
    }
