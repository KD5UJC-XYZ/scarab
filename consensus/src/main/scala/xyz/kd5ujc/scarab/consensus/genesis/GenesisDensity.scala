// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Genesis Governor.hs at consensus 82ecba329: candidate agreement and density bounds. Fragment
  * headers are oldest first, and satisfy the same chain/slot laws as upstream AnchoredFragment.
  */
object GenesisDensity:
  final case class Fragment(anchor: Point, headers: Vector[Point.Block]):
    def tip: Point = headers.lastOption.getOrElse(anchor)
    def points: Vector[Point] = anchor +: headers

    /** How far `point` is from the anchor: 0 the anchor, `i + 1` the header at index `i`. Found by
      * its slot, as the reference's fragment finds a point (`splitAfterPoint` and `pointOnFragment`
      * measure by slot, ouroboros-network c45735a56c567fa977969173d18943bac6bb3821
      * `ouroboros-network/api/lib/Ouroboros/Network/AnchoredFragment.hs` 430-432 and 498-508): a
      * binary search for the first header of its slot, then the headers of that slot, which are
      * more than one only for a Byron EBB and its successor.
      */
    def offsetOf(point: Point): Option[Int] =
      if point == anchor then Some(0)
      else
        point match
          case Point.Origin => None
          case Point.Block(slot, _) =>
            @tailrec def first(low: Int, high: Int): Int =
              if low >= high then low
              else
                val mid = low + (high - low) / 2
                if headers(mid).slot.value < slot.value then first(mid + 1, high)
                else first(low, mid)
            Iterator
              .from(first(0, headers.size))
              .takeWhile(i => i < headers.size && headers(i).slot == slot)
              .find(i => headers(i) == point)
              .map(_ + 1)

    def splitAfter(point: Point): Option[(Fragment, Fragment)] =
      offsetOf(point).map(index =>
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
        val candidatePoints = candidate.points.toSet
        val index = acc.points.lastIndexWhere(candidatePoints.contains)
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
                suffix.headers.sizeIs > k,
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

  /** ChainSel.hs trimToLoE (ouroboros-consensus 82ecba329 ChainSel.hs:803-845, 1144-1186 at
    * 2416493b), represented as complete candidate fragments rather than ChainDiff. Count headers,
    * including Byron EBBs; this is deliberately independent of block numbers.
    *
    * The candidate is cut at its newest point on the limit, searched from its tip. A point is
    * looked up on the limit by its slot ([[Fragment.offsetOf]]), and one of a later slot than the
    * limit's tip is not on it, as the limit's slots never decrease (the header gate's envelope
    * check), so the search builds nothing of the limit's size (#1499).
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
        val tipSlot = slot(loe.tip)
        def pointAt(i: Int): Point = if i == 0 then candidate.anchor else candidate.headers(i - 1)
        Iterator
          .range(candidate.headers.size, -1, -1)
          .find(i => slot(pointAt(i)) <= tipSlot && loe.offsetOf(pointAt(i)).isDefined) match
          case None => Left(LoEError.CandidateDoesNotIntersectLimit)
          case Some(index) =>
            val containsLimitTip = pointAt(index) == loe.tip
            Right(
              Fragment(
                candidate.anchor,
                candidate.headers.take(index + (if containsLimitTip then k else 0))
              )
            )
