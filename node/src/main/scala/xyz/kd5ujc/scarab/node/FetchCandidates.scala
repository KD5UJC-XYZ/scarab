// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Praos candidate boundary of BlockFetch, without Peras weights or Genesis selection. Reference:
  * consensus 82ecba329 MiniProtocol/BlockFetch/ClientInterface.hs 256–284 and
  * Util/AnchoredFragment.hs 167–210; network c45735a5 BlockFetch/Decision.hs
  * filterPlausibleCandidates, selectForkSuffixes and filterNotAlreadyFetched. Headers have already
  * passed the ChainSync header gate. The owner supplies one coherent selected-chain snapshot and
  * any additional downloaded blocks retained outside that chain.
  */
private[node] object FetchCandidates:

  /** The selected chain of one decision with the set of its points, built once for every candidate
    * that decision weighs: the intersection, plausibility and fetched checks all ask it.
    */
  final class Selected private (val local: ChainSelection.Local, points: Set[Point]):
    def holds(point: Point): Boolean = points(point)

  object Selected:
    def of(local: ChainSelection.Local): Selected =
      new Selected(local, local.blocks.map(_.point).toSet + local.anchor)

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
      candidate: ChainSelection.Candidate,
      alreadyFetched: Header => Boolean = _ => false
  ): Either[Refusal, Selection] =
    if !plausible(tiebreaker, selected, candidate) then Left(Refusal.ChainNotPlausible)
    else
      suffix(selected, candidate).flatMap { (intersection, headers) =>
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
      candidate: ChainSelection.Candidate
  ): Boolean =
    val local = selected.local
    intersection(selected, candidate).isDefined && candidate.headers.lastOption.exists { tip =>
      if local.blocks.isEmpty then tip.point != local.anchor
      else ChainSelection.prefersCandidate(tiebreaker, local.tipView, SelectView.of(tip))
    }

  /** Latest common point, bounded by the selected immutable anchor. */
  def suffix(
      selected: Selected,
      candidate: ChainSelection.Candidate
  ): Either[Refusal, (Point, Vector[Header])] =
    intersection(selected, candidate).toRight(Refusal.IntersectionTooDeep).map { (point, count) =>
      (point, candidate.headers.drop(count))
    }

  private def intersection(
      selected: Selected,
      candidate: ChainSelection.Candidate
  ): Option[(Point, Int)] =
    candidate.headers.lastIndexWhere(h => selected.holds(h.point)) match
      case -1 => Option.when(selected.holds(candidate.base))(candidate.base -> 0)
      case i  => Some(candidate.headers(i).point -> (i + 1))
