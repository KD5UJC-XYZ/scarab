// SPDX-License-Identifier: Apache-2.0
package lab.network

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

  /** Shelley..Alonzo headers use TPraos; Babbage and Conway use Praos. */
  def usesTPraos: Boolean = ordinal >= Era.Shelley.ordinal && ordinal <= Era.Alonzo.ordinal

  /** Alonzo and later blocks carry the `invalid_transactions` list. */
  def hasInvalidTransactions: Boolean = ordinal >= Era.Alonzo.ordinal

object Era:
  def fromIndex(index: BigInt): Option[Era] = values.find(e => index == e.index)

  /** Era of a BlockFetch/disk block tag (0 and 1 are both Byron). */
  def fromBlockTag(tag: BigInt): Option[Era] =
    if tag == 0 then Some(Byron) else values.find(e => tag == e.blockTag)
