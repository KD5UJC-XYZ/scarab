// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** What a ChainSync session keeps of its candidate from batch to batch besides the candidate itself
  * (issue 810): the candidate's index ([[FetchCandidates.Announced]]), which it hands to the fetch
  * decisions, and the block numbers of the points FindIntersect may offer again.
  *
  * The heights hold a point only while the candidate retains it (its base or a header) or it is the
  * origin. They are `restricted` once a batch has filtered them to the candidate of `announced`; a
  * FindIntersect installs the heights of the points it offered, which a candidate does not retain,
  * and the next batch filters them all.
  */
private[node] final case class CandidateTrack(
    announced: Option[FetchCandidates.Announced],
    heights: Map[Point, Option[BigInt]],
    restricted: Boolean
):
  /** `candidate` trimmed to `anchor`, but never past `published`
    * ([[FetchCandidates.Announced.trimmedTo]]), with the heights of the points it drops removed:
    * none unless the track is the one of `candidate` and the trim drops something. Heights filtered
    * to `candidate` stay filtered to the trimmed one.
    */
  def trimmed(
      candidate: ChainSelection.Candidate,
      anchor: Point,
      published: Point
  ): Option[CandidateTrack.Trimmed] =
    announced.filter(_.candidate eq candidate).flatMap { index =>
      index.trimmedTo(anchor, published) match
        case FetchCandidates.Announced.Step.Moved(next, left) if left.nonEmpty =>
          val gone = left.filter(point => !next.retains(point) && point != Point.Origin)
          Some(
            CandidateTrack.Trimmed(
              next.candidate,
              CandidateTrack(Some(next), heights -- gone, restricted)
            )
          )
        case _ => None
    }

  /** A candidate put in place from outside the batches (a Genesis graft), with its index: the next
    * batch carries that index, and filters every height, which were filtered to another candidate.
    */
  def replaced(index: FetchCandidates.Announced): CandidateTrack =
    copy(announced = Some(index), restricted = false)

  /** The heights of the points a FindIntersect offered. */
  def offered(heights: Map[Point, Option[BigInt]]): CandidateTrack =
    copy(heights = heights, restricted = false)

  /** The heights after a batch whose headers are `received`, which moved the candidate as `moved`
    * says: the heights of the received headers added, and those of points the candidate no longer
    * retains removed. When the heights were filtered to the candidate the batch started from and
    * the batch never started the candidate again, only the points the batch removed are looked at;
    * otherwise every height is.
    */
  def recorded(moved: CandidateTrack.Moved, received: Map[Point, Option[BigInt]]): CandidateTrack =
    val after = moved.announced
    def gone(point: Point): Boolean = !after.retains(point) && point != Point.Origin
    val heights = moved.left.filter(_ => carries(moved)) match
      case Some(left) => (this.heights ++ received) -- left.iterator.filter(gone)
      case None       => (this.heights ++ received).filterNot((point, _) => gone(point))
    CandidateTrack(Some(after), heights, true)

  /** Whether the heights are filtered to the candidate `moved` started from, so that only the
    * points it removed need looking at.
    */
  def carries(moved: CandidateTrack.Moved): Boolean =
    moved.left.isDefined && restricted && announced.exists(_.candidate eq moved.from)

private[node] object CandidateTrack:
  val initial: CandidateTrack = CandidateTrack(None, Map(Point.Origin -> None), false)

  /** A batch applied to a candidate: the candidate it started `from`, how the index of that
    * candidate was had (carried from the batch before, or scanned), the index the batch left, and
    * the points that stopped being retained on the way, unless a rollback started the candidate
    * again.
    */
  final case class Moved(
      from: ChainSelection.Candidate,
      start: FetchCandidates.Derivation,
      announced: FetchCandidates.Announced,
      left: Option[Vector[Point]]
  )

  /** A candidate trimmed to our chain's anchor, with the track that describes it. */
  final case class Trimmed(candidate: ChainSelection.Candidate, track: CandidateTrack)

  /** Apply `batch` to `before`, as a session's candidate takes it: each header rolled forward keeps
    * the newest `retained` headers, each rollback goes back to a point or starts the candidate
    * again there. The index is carried from `known` when it is the one of `before`, else found by
    * one scan of `before`.
    */
  def move(
      known: Option[FetchCandidates.Announced],
      before: ChainSelection.Candidate,
      batch: Vector[SyncEvent],
      retained: Int
  ): Moved =
    val (start, derivation) = known
      .filter(_.candidate eq before)
      .fold(FetchCandidates.Announced.of(before) -> FetchCandidates.Derivation.Scanned)(
        _ -> FetchCandidates.Derivation.Carried
      )
    batch.foldLeft(Moved(before, derivation, start, Some(Vector.empty))) { (moved, event) =>
      val step = event match
        case SyncEvent.RollForward(header, _) => moved.announced.forward(header, retained)
        case SyncEvent.RollBackward(point, _) => moved.announced.backward(point)
      step match
        case FetchCandidates.Announced.Step.Moved(next, left) =>
          moved.copy(announced = next, left = moved.left.map(_ ++ left))
        case FetchCandidates.Announced.Step.Reset(next) =>
          moved.copy(announced = next, left = None)
    }
