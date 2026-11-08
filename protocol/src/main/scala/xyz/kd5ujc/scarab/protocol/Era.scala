// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** Cardano eras with their hard-fork-combinator index (node-to-node ChainSync headers) and their
  * BlockFetch block tag. Byron has two block tags: 0 = epoch-boundary block, 1 = main block.
  */
enum Era(val index: Int, val blockTag: Int):
  case Byron extends Era(0, 1)
  case Shelley extends Era(1, 2)
  case Allegra extends Era(2, 3)
  case Mary extends Era(3, 4)
  case Alonzo extends Era(4, 5)
  case Babbage extends Era(5, 6)
  case Conway extends Era(6, 7)

  /** The era of the Leios testnet build (cardano-ledger-dijkstra 0.5.0.0, cardano-ledger 9df9f98c;
    * index 7 of `CardanoEras`, ouroboros-consensus 2416493b `Cardano/Block.hs`, and block tag 8,
    * `Cardano/Node.hs:173,197`). The node does not enter it yet (#54).
    */
  case Dijkstra extends Era(7, 8)

  /** Shelley..Alonzo headers use TPraos; Babbage and Conway use Praos, Dijkstra Praos2. */
  def usesTPraos: Boolean = ordinal >= Era.Shelley.ordinal && ordinal <= Era.Alonzo.ordinal

  /** Alonzo to Conway blocks carry the `invalid_transactions` list. A Dijkstra block body is
    * `[txs, leios_cert / nil, peras_cert / nil]` with no such list (cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/BlockBody/Internal.hs:185-203`).
    */
  def hasInvalidTransactions: Boolean =
    ordinal >= Era.Alonzo.ordinal && ordinal <= Era.Conway.ordinal

object Era:
  def fromIndex(index: BigInt): Option[Era] = values.find(e => index == e.index)

  /** Era of a BlockFetch/disk block tag (0 and 1 are both Byron). */
  def fromBlockTag(tag: BigInt): Option[Era] =
    if tag == 0 then Some(Byron) else values.find(e => tag == e.blockTag)
