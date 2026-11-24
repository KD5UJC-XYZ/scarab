// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.protocol.EbHash

/** The certifying view of the pool (`CertifyingView`, ouroboros-consensus d91b3557
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/CertifyingView.hs`):
  * the pool's held Dijkstra transactions revalidated on the state of the chain at `tip` with the
  * closure of the endorser block `eb` that tip announces applied, ticked to the slot after the tip
  * (`base`, the reference's `cvBase`), for the block that certifies `eb` to take its endorser
  * block's transactions from without revalidating them at its slot (`getCertifyingSnapshotFor`).
  *
  * @param judgedAt
  *   the slot after the tip, which the view's transactions are judged at (`isSlotNo`)
  * @param epoch
  *   the epoch of `base`, whose slots bound a later slot the view is read at (`ledgerSlotRange`)
  * @param revalidation
  *   the transactions held when the view was last brought up to date, revalidated on `base`
  * @param lastTicket
  *   the greatest ticket of the pool the view has judged (`isLastTicketNo`): a transaction with a
  *   greater one is not in it yet
  */
final case class CertifyingView private[node] (
    eb: EbHash,
    tip: Option[Bytes],
    judgedAt: BigInt,
    epoch: BigInt,
    base: DijkstraState,
    revalidation: DijkstraRevalidation,
    lastTicket: Long
):
  /** Whether a transaction the view kept has left the pool since (`isRemovalCounter` changed): the
    * view is then built again from its base, as the reference revalidates it from `cvBase` on a
    * removal (`removeFromCertifyingView`, `CertifyingView.hs:156-178`).
    */
  def removedFrom(pool: MempoolState): Boolean =
    val held = pool.entries.iterator.map(_.ticket).toSet
    revalidation.kept.exists((entry, _) => !held(entry.ticket))

  /** The pool's transactions the view has not judged yet, oldest first (`TxSeq.splitAfterTicketNo`
    * after `isLastTicketNo`, `CertifyingView.hs:99`).
    */
  def pending(pool: MempoolState): Vector[Entry] = pool.entries.filter(_.ticket > lastTicket)

  /** `catchUpCertifyingView` (`CertifyingView.hs:74-99`): `pending` revalidated on the state the
    * view's kept transactions leave, `state` (that state, with the stored outputs `pending` reads),
    * and appended.
    */
  def caughtUp(pending: Vector[Entry], state: DijkstraState, env: MempoolEnv): CertifyingView =
    if pending.isEmpty then this
    else
      copy(
        revalidation = MempoolState.dijkstraRevalidated(
          pending,
          revalidation.copy(state = state),
          DijkstraPoolEnv.of(env, judgedAt)
        ),
        lastTicket = pending.foldLeft(lastTicket)((last, entry) => last.max(entry.ticket))
      )

  /** `implGetCertifyingSnapshotFor` (`CertifyingView.hs:217-236`) of the block forged in `slot`
    * certifying `eb` on the chain whose tip's hash is `tip`, with `pool` as it is now: the view's
    * kept transactions and the withheld ones still held under the same ticket, when the view is of
    * `eb` on that tip, and `slot` is the one it was judged at, or a later one in the slots of its
    * epoch by `geometry` that every kept transaction's validity interval contains, where the view's
    * judgement holds (as [[MempoolState.dijkstraCached]] reads the pool's own). None also when a
    * kept transaction has left the pool and the view was not built again yet, which the reference's
    * view, revalidated under the pool's lock on a removal, never meets.
    */
  def snapshotFor(
      eb: EbHash,
      tip: Option[Bytes],
      slot: BigInt,
      pool: MempoolState,
      geometry: EpochGeometry
  ): Option[DijkstraRevalidation] =
    val kept = revalidation.kept
    lazy val inRange = slot > judgedAt &&
      (kept.foldMap((_, admitted) => SlotRange.dijkstraTx(admitted.read.tx)) |+|
        SlotRange.epoch(geometry, epoch)).contains(slot)
    Option.when(
      this.eb == eb && this.tip == tip && pool.tipHash == tip && !removedFrom(pool) &&
        (slot == judgedAt || inRange)
    ) {
      // by ticket: a transaction removed and held again is another admission, which the view
      // judges when it catches up, not by the verdict its removed one had
      val held = pool.entries.iterator.map(_.ticket).toSet
      revalidation.copy(withheld = revalidation.withheld.filter((entry, _) => held(entry.ticket)))
    }

object CertifyingView:
  /** `buildCertifyingView` (`CertifyingView.hs:49-72`): `pool` 's held transactions revalidated on
    * `base`, the state of the chain at `tip` with the closure of `eb` applied, ticked to the slot
    * after the tip, in `epoch`, with the stored outputs the pool's transactions read, when the pool
    * is at that tip and in Dijkstra (`syncCertifyingView` builds the view only for the pool's own
    * tip, `Mempool/Update.hs:647-667`); none otherwise.
    */
  def build(
      eb: EbHash,
      tip: Option[Bytes],
      epoch: BigInt,
      base: DijkstraState,
      pool: MempoolState,
      env: MempoolEnv
  ): Option[CertifyingView] =
    val judgedAt = pool.tipSlot + 1
    val inDijkstra = pool.base match
      case Right(_: DijkstraLedger) => true
      case _                        => false
    Option.when(pool.tipHash == tip && inDijkstra) {
      CertifyingView(
        eb,
        tip,
        judgedAt,
        epoch,
        base,
        MempoolState.dijkstraRevalidated(
          pool.entries,
          DijkstraRevalidation.from(base),
          DijkstraPoolEnv.of(env, judgedAt)
        ),
        pool.entries.map(_.ticket).maxOption.getOrElse(-1L)
      )
    }
