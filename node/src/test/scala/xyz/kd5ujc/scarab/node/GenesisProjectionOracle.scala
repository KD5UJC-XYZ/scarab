// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.{Bytes, HaskellSort}
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** The Genesis projection as it was before #1499, kept as the oracle of [[GenesisProjectionSuite]]:
  * each step over the whole window and the whole limit (a map of the window's points, a set of the
  * limit's points, the candidate's points searched from its tip), and the selection window's
  * lookups as plain scans of its blocks.
  */
private[node] object GenesisProjectionOracle:
  def splitAfter(fragment: Fragment, point: ChainSync.Point): Option[(Fragment, Fragment)] =
    val index = fragment.points.indexOf(point)
    Option.when(index >= 0)(
      (
        Fragment(fragment.anchor, fragment.headers.take(index)),
        Fragment(point, fragment.headers.drop(index))
      )
    )

  def sanitize(current: Fragment, limit: Fragment): Fragment =
    splitAfter(limit, current.anchor).fold(Fragment(current.anchor, Vector.empty))(_._2)

  def trim(
      k: Int,
      current: Fragment,
      candidate: Fragment,
      limit: Option[Fragment]
  ): Either[GenesisDensity.LoEError, Fragment] =
    limit match
      case None => Right(candidate)
      case Some(raw) =>
        val loe = sanitize(current, raw)
        val limitPoints = loe.points.toSet
        val index = candidate.points.lastIndexWhere(limitPoints.contains)
        if index < 0 then Left(GenesisDensity.LoEError.CandidateDoesNotIntersectLimit)
        else
          val intersection = candidate.points(index)
          val containsLimitTip = intersection == loe.tip
          Right(
            Fragment(
              candidate.anchor,
              candidate.headers.take(index + (if containsLimitTip then k else 0))
            )
          )

  def limitCandidate(
      k: Int,
      current: ChainSelection.Local,
      candidate: ChainSelection.Candidate,
      limit: Option[Fragment]
  ): Option[ChainSelection.Candidate] =
    val selected = Fragment(
      current.anchor,
      current.blocks.map(_.point).collect { case block: ChainSync.Point.Block =>
        block
      }
    )
    val localPoints = current.anchor +: current.blocks.map(_.point)
    val localPositions = localPoints.zipWithIndex.toMap
    val candidatePoints = candidate.base +: candidate.headers.map(_.point)
    val common = candidatePoints.lastIndexWhere(localPositions.contains)
    if common < 0 then Some(candidate)
    else
      val intersection = candidatePoints(common)
      val before =
        current.blocks.take(localPositions.getOrElse(intersection, 0)).map(_.point).collect {
          case block: ChainSync.Point.Block => block
        }
      val extension = candidate.headers.drop(common)
      val proposed = Fragment(
        current.anchor,
        before ++ extension.map(_.point).collect { case block: ChainSync.Point.Block =>
          block
        }
      )
      trim(k, selected, proposed, limit).toOption.map { allowed =>
        ChainSelection.Candidate(
          intersection,
          extension.take((allowed.headers.size - before.size).max(0))
        )
      }

  def project(
      k: Int,
      local: ChainSelection.Local,
      candidates: Vector[ChainSelection.Candidate],
      limit: Option[Fragment],
      tiebreaker: Era => ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor
  ): Vector[ChainSelection.Candidate] =
    val eligible = candidates.flatMap { candidate =>
      limitCandidate(k, local, candidate, limit).toVector.flatMap { projected =>
        val era = projected.headers.lastOption.map(_.era)
        era.toVector.flatMap { value =>
          ChainSelection.decide(tiebreaker(value), local, projected) match
            case ChainSelection.Decision.Switch(at, _, take) =>
              take.lastOption.map(tip => (ChainSelection.Candidate(at, take), tip)).toVector
            case ChainSelection.Decision.Keep | ChainSelection.Decision.Detached => Vector.empty
        }
      }
    }
    HaskellSort.sortBy(eligible)((a, b) => CandidateSelection.compareTips(a._2, b._2)).map(_._1)

  /** The window's lookups, each a scan of its blocks. */
  def contains(local: ChainSelection.Local, point: ChainSync.Point): Boolean =
    point == local.anchor || local.blocks.exists(_.point == point)

  def placesOf(local: ChainSelection.Local, hash: Option[Bytes]): Vector[Int] =
    local.blocks.indices
      .filter(i =>
        local.blocks(i).point match
          case ChainSync.Point.Block(_, h) => hash.contains(h)
          case ChainSync.Point.Origin      => hash.isEmpty
      )
      .toVector

  def depthTo(local: ChainSelection.Local, point: ChainSync.Point): Int =
    if point == local.anchor then local.blocks.size
    else local.blocks.size - 1 - local.blocks.indexWhere(_.point == point)

  def rolledBackTo(
      local: ChainSelection.Local,
      point: ChainSync.Point
  ): Option[ChainSelection.Local] =
    if point == local.anchor then Some(local.copy(blocks = Vector.empty))
    else
      local.blocks.indexWhere(_.point == point) match
        case -1 => None
        case i  => Some(local.copy(blocks = local.blocks.take(i + 1)))

  def extended(
      local: ChainSelection.Local,
      block: ChainSelection.LocalBlock,
      immutable: Int
  ): ChainSelection.Local =
    val (gone, kept) = (local.blocks :+ block).splitAt(immutable)
    gone.lastOption.fold(local.copy(blocks = kept))(anchor =>
      ChainSelection.Local(anchor.point, anchor.view, kept)
    )
