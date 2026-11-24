// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.Monoid
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraTxBody, Tx, TxLevel, ValidityInterval}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

/** `SlotRange` (ouroboros-consensus d91b3557
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Ledger/SupportsMempool.hs:280-307`):
  * the half-open range of slots from `from` up to `until`, none being unbounded. Combining two
  * intersects them, so the range of a pool is where every transaction it holds and its ledger agree
  * that ticking changes nothing.
  */
final case class SlotRange(from: BigInt, until: Option[BigInt]):
  /** `inSlotRange` (`SupportsMempool.hs:306-307`). */
  def contains(slot: BigInt): Boolean = slot >= from && until.forall(slot < _)

object SlotRange:
  /** `anySlot`: every slot, the range of a pool that holds nothing. */
  val any: SlotRange = SlotRange(0, None)

  given Monoid[SlotRange] with
    def empty: SlotRange = any
    def combine(a: SlotRange, b: SlotRange): SlotRange =
      SlotRange(
        a.from.max(b.from),
        (a.until, b.until) match
          case (Some(x), Some(y)) => Some(x.min(y))
          case (x, y)             => x.orElse(y)
      )

  /** `validityIntervalSlotRange` (ouroboros-consensus-cardano d91b3557
    * `src/shelley/Ouroboros/Consensus/Shelley/Eras.hs:198-200`): from `invalidBefore`, or the first
    * slot, up to `invalidHereafter`, which the interval excludes.
    */
  def validity(interval: ValidityInterval): SlotRange =
    SlotRange(interval.invalidBefore.getOrElse(BigInt(0)), interval.invalidHereafter)

  /** `shelleyBasedTxSlotRange` of a Dijkstra transaction (`Shelley/Eras.hs:287-289`): its own
    * validity interval and each of its sub-transactions', intersected.
    */
  def dijkstraTx(tx: Tx[TxLevel.Top, Dijkstra]): SlotRange =
    val bodies = EraTxBody.dijkstra
    given BothLevels[TxLevel.Top] = BothLevels.Top
    given BothLevels[TxLevel.Sub] = BothLevels.Sub
    validity(bodies.vldt(tx.body)) |+|
      bodies.subTransactions(tx.body).toVector.foldMap(sub => validity(bodies.vldt(sub.body)))

  /** `ledgerSlotRange` of a Shelley-based ledger in `epoch` (ouroboros-consensus-cardano d91b3557
    * `src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:241-250`): the slots of that epoch,
    * as the parameters and the era change only at an epoch boundary.
    */
  def epoch(geometry: EpochGeometry, epoch: BigInt): SlotRange =
    SlotRange(geometry.firstSlot(epoch), Some(geometry.firstSlot(epoch + 1)))
