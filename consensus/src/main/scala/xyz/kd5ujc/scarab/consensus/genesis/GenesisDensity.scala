// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Genesis Governor.hs at consensus 82ecba329: candidate agreement and density bounds. Fragment
  * headers are oldest first, and satisfy the same chain/slot laws as upstream AnchoredFragment.
  */
object GenesisDensity:
  final case class Fragment(anchor: Point, headers: Vector[Point.Block]):
    def tip: Point = headers.lastOption.getOrElse(anchor)
    def points: Vector[Point] = anchor +: headers
    def splitAfter(point: Point): Option[(Fragment, Fragment)] =
      val index = points.indexOf(point)
      Option.when(index >= 0)(
        (Fragment(anchor, headers.take(index)), Fragment(point, headers.drop(index)))
      )

  enum LatestSlot:
    case Unknown, Origin
    case At(slot: BigInt)

  final case class PeerState(candidate: Fragment, latestSlot: LatestSlot, idling: Boolean)
  final case class Shared[P](prefix: Fragment, suffixes: Vector[(P, Fragment)])
  final case class Bounds(
      clippedFragment: Fragment,
      offersMoreThanK: Boolean,
      lowerBound: BigInt,
      upperBound: BigInt,
      hasBlockAfter: Boolean,
      latestSlot: LatestSlot,
      idling: Boolean
  )
  final case class Result[P](losingPeers: Vector[P], bounds: Vector[(P, Bounds)])

  private def slot(point: Point): BigInt = point match
    case Point.Origin          => -1
    case Point.Block(value, _) => value.value

  /** Current selection contributes its immutable anchor, not its tip. A candidate that does not
    * contain that anchor contributes an empty fragment, including during ChainSync jumping.
    */
  def sharedCandidatePrefix[P](current: Fragment, candidates: Vector[(P, Fragment)]): Shared[P] =
    val empty = Fragment(current.anchor, Vector.empty)
    val rooted = candidates.map { case (peer, candidate) =>
      peer -> candidate.splitAfter(current.anchor).fold(empty)(_._2)
    }
    val prefix = rooted.headOption.fold(empty) { case (_, first) =>
      val common = rooted.drop(1).foldLeft(first) { case (acc, (_, candidate)) =>
        val index = acc.points.lastIndexWhere(candidate.points.contains)
        Fragment(acc.anchor, acc.headers.take(index.max(0)))
      }
      common
    }
    Shared(
      prefix,
      rooted.map { case (peer, candidate) =>
        peer -> candidate.splitAfter(prefix.tip).fold(empty)(_._2)
      }
    )

  /** The caller queries the genesis window at the first slot after the shared prefix, using the
    * immutable ledger's hard-fork history. Unknown queries postpone GDD, never substitute a window.
    */
  def firstSlotAfter(prefix: Fragment): BigInt = slot(prefix.tip) + 1

  def densityDisconnect[P](
      genesisWindow: BigInt,
      k: Int,
      states: Map[P, PeerState],
      suffixes: Vector[(P, Fragment)],
      loe: Fragment
  ): Result[P] =
    val end = firstSlotAfter(loe) + genesisWindow
    val bounds = suffixes.flatMap { case (peer, suffix) =>
      states.get(peer).toVector.flatMap { state =>
        state.latestSlot match
          case LatestSlot.Unknown => Vector.empty
          case latest =>
            val latestValue = latest match
              case LatestSlot.At(value) => value
              case _                    => BigInt(-1)
            // AF.splitAtSlot uses a strict lower half: the first slot outside the window is excluded.
            val clipped = Fragment(suffix.anchor, suffix.headers.takeWhile(h => slot(h) < end))
            val after = slot(suffix.tip).max(latestValue) >= end
            val lower = BigInt(clipped.headers.size)
            val potential = if after then BigInt(0) else end - (slot(clipped.tip) + 1)
            Vector(
              peer -> Bounds(
                clipped,
                suffix.headers.size > k,
                lower,
                lower + potential,
                after,
                latest,
                state.idling
              )
            )
      }
    }
    val losers = bounds.flatMap { case (peer, ours) =>
      Option.when(bounds.exists { case (_, theirs) =>
        (ours.idling || ours.clippedFragment.headers.nonEmpty || ours.hasBlockAfter) &&
        // AF.lastPoint is the oldest block, or the anchor for an empty fragment.
        ours.clippedFragment.headers.headOption.getOrElse(ours.clippedFragment.anchor) !=
          theirs.clippedFragment.headers.headOption.getOrElse(theirs.clippedFragment.anchor) &&
          (theirs.offersMoreThanK || ours.lowerBound == ours.upperBound) &&
          theirs.lowerBound >= (if ours.idling then ours.lowerBound else ours.upperBound)
      })(peer)
    }.distinct
    Result(losers, bounds)

  enum LoEError:
    case CandidateDoesNotIntersectLimit

  /** ChainSel.hs sanitizeLoEFrag: temporarily disconnected limits become the immutable anchor. */
  def sanitize(current: Fragment, limit: Fragment): Fragment =
    limit.splitAfter(current.anchor).fold(Fragment(current.anchor, Vector.empty))(_._2)

  /** ChainSel.hs trimToLoE, represented as complete candidate fragments rather than ChainDiff.
    * Count headers, including Byron EBBs; this is deliberately independent of block numbers.
    */
  def trim(
      k: Int,
      current: Fragment,
      candidate: Fragment,
      limit: Option[Fragment]
  ): Either[LoEError, Fragment] =
    limit match
      case None => Right(candidate)
      case Some(raw) =>
        val loe = sanitize(current, raw)
        val index = candidate.points.lastIndexWhere(loe.points.contains)
        if index < 0 then Left(LoEError.CandidateDoesNotIntersectLimit)
        else
          val intersection = candidate.points(index)
          val containsLimitTip = intersection == loe.tip
          Right(
            Fragment(
              candidate.anchor,
              candidate.headers.take(index + (if containsLimitTip then k else 0))
            )
          )
