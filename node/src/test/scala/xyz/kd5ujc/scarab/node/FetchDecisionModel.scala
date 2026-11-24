// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, Fetched}
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The from-scratch computations that issue 810 made incremental, kept as they were before it (at
  * 6c8c40269) so that the incremental ones can be checked against them: the candidate stages of a
  * decision, the fragments, the slot order of an announced candidate, and the candidate and heights
  * a ChainSync batch leaves.
  */
private[node] object FetchDecisionModel:

  private def slotOf(point: Point): Option[BigInt] = point match
    case Point.Block(slot, _) => Some(slot.value)
    case Point.Origin         => None

  /** `FetchCandidates.Announced.of` 's order check: every adjacent pair of headers is in slot
    * order.
    */
  def ordered(candidate: ChainSelection.Candidate): Boolean =
    candidate.headers
      .lazyZip(candidate.headers.drop(1))
      .forall((a, b) =>
        (slotOf(a.point), slotOf(b.point)) match
          case (Some(x), Some(y)) => x <= y
          case _                  => false
      )

  /** The selected chain's points and newest slot. */
  final case class Selected(local: ChainSelection.Local):
    val points: Set[Point] = local.blocks.map(_.point).toSet + local.anchor
    val newestSlot: Option[BigInt] = points.iterator.flatMap(slotOf).maxOption

  private def lastWithin(headers: Vector[Header], bound: BigInt): Int =
    @scala.annotation.tailrec
    def go(low: Int, high: Int): Int =
      if low >= high then low - 1
      else
        val middle = (low + high) >>> 1
        if slotOf(headers(middle).point).exists(_ <= bound) then go(middle + 1, high)
        else go(low, middle)
    go(0, headers.size)

  @scala.annotation.tailrec
  private def lastHeld(selected: Selected, headers: Vector[Header], from: Int): Int =
    if from < 0 then -1
    else if selected.points(headers(from).point) then from
    else lastHeld(selected, headers, from - 1)

  private def intersection(
      selected: Selected,
      candidate: ChainSelection.Candidate
  ): Option[(Point, Int)] =
    val headers = candidate.headers
    val start =
      if ordered(candidate) then selected.newestSlot.fold(-1)(lastWithin(headers, _))
      else headers.size - 1
    lastHeld(selected, headers, start) match
      case -1 => Option.when(selected.points(candidate.base))(candidate.base -> 0)
      case i  => Some(headers(i).point -> (i + 1))

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

  def suffix(
      selected: Selected,
      candidate: ChainSelection.Candidate
  ): Either[Refusal, (Point, Vector[Header])] =
    intersection(selected, candidate).toRight(Refusal.IntersectionTooDeep).map { (point, count) =>
      (point, candidate.headers.drop(count))
    }

  /** `FetchCandidates.select`: the missing runs of the plausible suffix. */
  def select(
      tiebreaker: ChainSelection.Tiebreaker,
      selected: Selected,
      candidate: ChainSelection.Candidate,
      alreadyFetched: Header => Boolean
  ): Either[Refusal, (Point, Vector[Header], Vector[Vector[Header]])] =
    if !plausible(tiebreaker, selected, candidate) then Left(Refusal.ChainNotPlausible)
    else
      suffix(selected, candidate).flatMap { (intersection, headers) =>
        val (completed, current) = headers.foldLeft(
          (Vector.empty[Vector[Header]], Vector.empty[Header])
        ) { case ((done, run), header) =>
          if alreadyFetched(header) || selected.points(header.point) then
            (if run.nonEmpty then done :+ run else done, Vector.empty)
          else (done, run :+ header)
        }
        val missing = if current.nonEmpty then completed :+ current else completed
        if missing.isEmpty then Left(Refusal.AlreadyFetched)
        else Right((intersection, headers, missing))
      }

  /** `FetchCoordinator.fragmentsOf`. */
  def fragmentsOf(headers: Vector[Header]): Vector[BlockFetchPolicy.Fragment] =
    val runs = headers.foldLeft(List.empty[NonEmptyVector[Header]]) { (groups, header) =>
      groups match
        case previous :: older if header.prevHash.contains(previous.last.hash) =>
          previous.append(header) :: older
        case _ => NonEmptyVector.one(header) :: groups
    }
    runs.reverse.toVector.map(run =>
      BlockFetchPolicy.Fragment(
        run.head.prevHash,
        run.toVector.map(h =>
          BlockFetchPolicy.Header(h.hash, h.slot, FetchCoordinator.estimatedBytes(h))
        )
      )
    )

  /** What `FetchCoordinator.act` chose for one peer and the fragments it offered the policy. */
  def chosen(
      selected: Option[ChainSelection.Local],
      useGenesis: Boolean,
      tiebreaker: ChainSelection.Tiebreaker,
      candidate: ChainSelection.Candidate,
      fetched: Fetched
  ): (Option[Refusal], Vector[BlockFetchPolicy.Fragment]) =
    val chosen = selected
      .map(Selected(_))
      .fold[Either[Refusal, Vector[Header]]](
        Right(candidate.headers.filterNot(h => fetched.contains(h.hash)))
      ) { local =>
        if useGenesis then
          if !plausible(tiebreaker, local, candidate) then Left(Refusal.ChainNotPlausible)
          else suffix(local, candidate).map(_._2)
        else select(tiebreaker, local, candidate, h => fetched.contains(h.hash)).map(_._3.flatten)
      }
    (chosen.left.toOption, fragmentsOf(chosen.getOrElse(Vector.empty)))

  /** One batch as `Peer.recordUpdates` applied it: the candidate it leaves. */
  def candidateAfter(
      before: ChainSelection.Candidate,
      batch: Vector[SyncEvent],
      retainedHeaders: Int
  ): ChainSelection.Candidate =
    batch.foldLeft(before) {
      case (known, SyncEvent.RollForward(h, _)) =>
        val grown = known.headers :+ h
        val dropped = grown.dropRight(retainedHeaders.max(1))
        ChainSelection.Candidate(
          dropped.lastOption.fold(known.base)(_.point),
          grown.takeRight(retainedHeaders.max(1))
        )
      case (known, SyncEvent.RollBackward(point, _)) =>
        known.rollback(point).getOrElse(ChainSelection.Candidate(point, Vector.empty))
    }

  /** The heights `Peer.recordUpdates` kept after a batch left `candidate`. */
  def heightsAfter(
      previous: Map[Point, Option[BigInt]],
      batch: Vector[SyncEvent],
      candidate: ChainSelection.Candidate
  ): Map[Point, Option[BigInt]] =
    val received = batch.collect { case SyncEvent.RollForward(h, _) =>
      h.point -> Some(h.blockNo)
    }.toMap
    val retained = candidate.headers.map(_.point).toSet + candidate.base
    (previous ++ received).filter((point, _) => retained(point) || point == ChainSync.Point.Origin)
