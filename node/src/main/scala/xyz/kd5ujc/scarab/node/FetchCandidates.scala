// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Praos candidate boundary of BlockFetch, without Peras weights or Genesis selection. Reference:
  * consensus 82ecba329 MiniProtocol/BlockFetch/ClientInterface.hs 256–284 and
  * Util/AnchoredFragment.hs 167–210; network 2fb69829 BlockFetch/Decision.hs
  * filterPlausibleCandidates, selectForkSuffixes and filterNotAlreadyFetched. Headers have already
  * passed the ChainSync header gate. The owner supplies one coherent selected-chain snapshot and
  * any additional downloaded blocks retained outside that chain.
  */
private[node] object FetchCandidates:
  final case class Selection(
      intersection: Point,
      suffix: Vector[Header],
      missing: Vector[Vector[Header]]
  ):
    def headers: Vector[Header] = missing.flatten

  /** The combined native boundary declines a detached chain at the plausibility stage first. */
  def select(
      tiebreaker: ChainSelection.Tiebreaker,
      local: ChainSelection.Local,
      candidate: ChainSelection.Candidate,
      alreadyFetched: Set[Point] = Set.empty
  ): Either[Refusal, Selection] =
    if !plausible(tiebreaker, local, candidate) then Left(Refusal.ChainNotPlausible)
    else
      suffix(local, candidate).flatMap { (intersection, headers) =>
        val fetched = alreadyFetched ++ local.blocks.map(_.point) + local.anchor
        val (completed, current) = headers.foldLeft(
          (Vector.empty[Vector[Header]], Vector.empty[Header])
        ) { case ((done, run), header) =>
          if fetched(header.point) then (if run.nonEmpty then done :+ run else done, Vector.empty)
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
      local: ChainSelection.Local,
      candidate: ChainSelection.Candidate
  ): Boolean =
    intersection(local, candidate).isDefined && candidate.headers.lastOption.exists { tip =>
      if local.blocks.isEmpty then tip.point != local.anchor
      else ChainSelection.prefers(tiebreaker, local.tipView, SelectView.of(tip))
    }

  /** Latest common point, bounded by the selected immutable anchor. */
  def suffix(
      local: ChainSelection.Local,
      candidate: ChainSelection.Candidate
  ): Either[Refusal, (Point, Vector[Header])] =
    intersection(local, candidate).toRight(Refusal.IntersectionTooDeep).map { (point, count) =>
      (point, candidate.headers.drop(count))
    }

  private def intersection(
      local: ChainSelection.Local,
      candidate: ChainSelection.Candidate
  ): Option[(Point, Int)] =
    val selected = local.blocks.map(_.point).toSet + local.anchor
    candidate.headers.lastIndexWhere(h => selected(h.point)) match
      case -1 => Option.when(selected(candidate.base))(candidate.base -> 0)
      case i  => Some(candidate.headers(i).point -> (i + 1))
