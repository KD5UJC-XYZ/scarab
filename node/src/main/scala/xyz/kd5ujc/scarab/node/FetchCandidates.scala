// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Praos candidate boundary of BlockFetch, without Peras weights or Genesis selection. Reference:
  * consensus 82ecba329 MiniProtocol/BlockFetch/ClientInterface.hs 256–284 and
  * Util/AnchoredFragment.hs 167–210; network c45735a5 BlockFetch/Decision.hs
  * filterPlausibleCandidates, selectForkSuffixes and filterNotAlreadyFetched. A candidate whose
  * point slots never decrease takes a bounded intersection search; any other is scanned from its
  * tip ([[SlotOrder]]). The owner supplies one coherent selected-chain snapshot and any additional
  * downloaded blocks retained outside that chain.
  */
private[node] object FetchCandidates:

  /** The selected chain of one decision with the set of its points, built once for every candidate
    * that decision weighs: the intersection, plausibility and fetched checks all ask it.
    */
  final class Selected private (
      val local: ChainSelection.Local,
      points: Set[Point],
      val newestSlot: Option[BigInt]
  ):
    def holds(point: Point): Boolean = points(point)

  object Selected:
    def of(local: ChainSelection.Local): Selected =
      val points = local.blocks.map(_.point).toSet + local.anchor
      new Selected(local, points, points.iterator.flatMap(slotOf).maxOption)

  private def slotOf(point: Point): Option[BigInt] = point match
    case Point.Block(slot, _) => Some(slot.value)
    case Point.Origin         => None

  /** Whether a candidate's header slots never decrease, as every candidate that passed the header
    * gate's slot check has them, or nothing is known of their order (a candidate the gate did not
    * check, `--header-first off`).
    */
  enum SlotOrder:
    case NonDecreasing, Unchecked

  /** A candidate with the order of its slots, found once when its session announces it rather than
    * at every decision that weighs it.
    */
  final class Announced private (val candidate: ChainSelection.Candidate, val order: SlotOrder)

  object Announced:
    def of(candidate: ChainSelection.Candidate): Announced =
      val ordered = candidate.headers
        .lazyZip(candidate.headers.drop(1))
        .forall((a, b) =>
          (slotOf(a.point), slotOf(b.point)) match
            case (Some(x), Some(y)) => x <= y
            case _                  => false
        )
      new Announced(candidate, if ordered then SlotOrder.NonDecreasing else SlotOrder.Unchecked)

  final case class Selection(
      intersection: Point,
      suffix: Vector[Header],
      missing: Vector[Vector[Header]]
  ):
    def headers: Vector[Header] = missing.flatten

  /** The combined native boundary declines a detached chain at the plausibility stage first. */
  /** @param alreadyFetched
    *   Whether a header's block is downloaded outside the selected chain; the selected chain's own
    *   blocks always count as fetched.
    */
  def select(
      tiebreaker: ChainSelection.Tiebreaker,
      selected: Selected,
      announced: Announced,
      alreadyFetched: Header => Boolean = _ => false
  ): Either[Refusal, Selection] =
    if !plausible(tiebreaker, selected, announced) then Left(Refusal.ChainNotPlausible)
    else
      suffix(selected, announced).flatMap { (intersection, headers) =>
        val (completed, current) = headers.foldLeft(
          (Vector.empty[Vector[Header]], Vector.empty[Header])
        ) { case ((done, run), header) =>
          if alreadyFetched(header) || selected.holds(header.point) then
            (if run.nonEmpty then done :+ run else done, Vector.empty)
          else (done, run :+ header)
        }
        val missing = if current.nonEmpty then completed :+ current else completed
        if missing.isEmpty then Left(Refusal.AlreadyFetched)
        else Right(Selection(intersection, headers, missing))
      }

  /** Empty current fragments are anchored at an immutable block. A strict extension is plausible
    * even when a Byron boundary header repeats that anchor's chain difficulty.
    */
  def plausible(
      tiebreaker: ChainSelection.Tiebreaker,
      selected: Selected,
      announced: Announced
  ): Boolean =
    val local = selected.local
    intersection(selected, announced).isDefined && announced.candidate.headers.lastOption.exists {
      tip =>
        if local.blocks.isEmpty then tip.point != local.anchor
        else ChainSelection.prefersCandidate(tiebreaker, local.tipView, SelectView.of(tip))
    }

  /** Latest common point, bounded by the selected immutable anchor. */
  def suffix(
      selected: Selected,
      announced: Announced
  ): Either[Refusal, (Point, Vector[Header])] =
    intersection(selected, announced).toRight(Refusal.IntersectionTooDeep).map { (point, count) =>
      (point, announced.candidate.headers.drop(count))
    }

  /** The newest header of the candidate on the selected chain. No point of the selected chain is
    * past its newest slot, so when the candidate's slots never decrease the newest header on the
    * selected chain is at or before the last header within that slot: found by halving, and the
    * scan back starts there instead of at the candidate's tip (issue 810: a dynamo far ahead of the
    * selection was scanned whole, for each peer, at every decision). A candidate of unchecked order
    * is scanned from its tip.
    */
  private def intersection(selected: Selected, announced: Announced): Option[(Point, Int)] =
    val candidate = announced.candidate
    val headers = candidate.headers
    val start = announced.order match
      case SlotOrder.NonDecreasing => selected.newestSlot.fold(-1)(lastWithin(headers, _))
      case SlotOrder.Unchecked     => headers.size - 1
    lastHeld(selected, headers, start) match
      case -1 => Option.when(selected.holds(candidate.base))(candidate.base -> 0)
      case i  => Some(headers(i).point -> (i + 1))

  /** The index of the last header at or before `from` that is on the selected chain, or -1. */
  @scala.annotation.tailrec
  private def lastHeld(selected: Selected, headers: Vector[Header], from: Int): Int =
    if from < 0 then -1
    else if selected.holds(headers(from).point) then from
    else lastHeld(selected, headers, from - 1)

  /** The index of the last header whose slot is at most `bound`, or -1: by halving, for headers
    * whose slots never decrease.
    */
  private def lastWithin(headers: Vector[Header], bound: BigInt): Int =
    @scala.annotation.tailrec
    def go(low: Int, high: Int): Int =
      // invariant: every index below `low` is within `bound`, every index from `high` on is past it
      if low >= high then low - 1
      else
        val middle = (low + high) >>> 1
        if slotOf(headers(middle).point).exists(_ <= bound) then go(middle + 1, high)
        else go(low, middle)
    go(0, headers.size)
