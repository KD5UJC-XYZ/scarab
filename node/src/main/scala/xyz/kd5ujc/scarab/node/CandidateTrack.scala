// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.protocol.ChainSync
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

/** A session's candidate as one state (issue 810): the candidate, its track, the revision its
  * announcements to the fetch decisions are ordered by, and, while the session streams, what it
  * publishes to its consumer. The reference keeps its candidate, `theirFrag`, in one TVar that its
  * ChainSync client writes as it rolls forward and as it trims it to our chain's anchor
  * (`intersectsWithCurrentChain`, ouroboros-consensus 82ecba329 MiniProtocol/ChainSync/Client.hs
  * 942-992). Here each change of the candidate is one transition of this state, so a batch, a trim,
  * the publisher's step and a restart never see the candidate apart from its index, the journal the
  * publisher reads or the cursor it published.
  */
private[node] final case class CandidateState(
    candidate: ChainSelection.Candidate,
    track: CandidateTrack,
    revision: Long,
    publication: Option[CandidateState.Publication]
):
  /** The state after `batch`, as [[CandidateTrack.move]] moves the candidate and
    * [[CandidateTrack.recorded]] its heights, with the batch's tip for the publisher, trimmed to
    * `anchor` as [[trimmed]] trims; and how the candidate moved. A rollback to a point the
    * candidate does not hold, one the header gate holds before the candidate's base, starts the
    * candidate again there and the publisher in a new epoch: it then publishes the rollback to that
    * point when what it published has no point in common with the candidate.
    */
  def recorded(
      batch: Vector[SyncEvent],
      retained: Int,
      received: Map[Point, Option[BigInt]],
      anchor: Option[Point]
  ): (CandidateState, CandidateTrack.Moved) =
    val moved = CandidateTrack.move(track.announced, candidate, batch, retained)
    val tip = batch.lastOption.map {
      case SyncEvent.RollForward(_, tip)  => tip
      case SyncEvent.RollBackward(_, tip) => tip
    }
    val next = CandidateState(
      moved.announced.candidate,
      track.recorded(moved, received),
      revision + 1,
      publication.map { p =>
        val restarted = if moved.left.isEmpty then p.copy(epoch = p.epoch + 1) else p
        tip.fold(restarted)(at => restarted.copy(tip = Some(at)))
      }
    )
    (anchor.fold(next)(next.trimmed), moved)

  /** The candidate trimmed to `anchor`, our chain's anchor, as the reference trims its candidate
    * whenever our chain changes, but never past the tip of the cursor, the newest point published
    * to the session's consumer ([[CandidateTrack.trimmed]]): the next publication then finds that
    * tip, or the point of a later rollback, which the header gate keeps at or after its base. Only
    * a streaming session is trimmed, as only it has a cursor; this same state when nothing is
    * dropped.
    */
  def trimmed(anchor: Point): CandidateState =
    publication
      .flatMap(p => track.trimmed(candidate, anchor, p.cursor.tip))
      .fold(this)(t => copy(candidate = t.candidate, track = t.track, revision = revision + 1))

  /** A candidate put in place from outside the batches, a new intersection or a Genesis graft, with
    * its index and the tip the peer gave with it: the publisher takes it in a new epoch.
    */
  def replaced(index: FetchCandidates.Announced, tip: ChainSync.Tip): CandidateState =
    CandidateState(
      index.candidate,
      track.replaced(index),
      revision + 1,
      publication.map(p => p.copy(tip = Some(tip), done = false, epoch = p.epoch + 1))
    )

  /** The candidate an external intersection starts from: nothing is published from it until the
    * session streams again.
    */
  def reset(next: ChainSelection.Candidate): CandidateState =
    CandidateState(next, track, revision + 1, None)

  /** The heights of the points a FindIntersect offered ([[CandidateTrack.offered]]). */
  def offered(heights: Map[Point, Option[BigInt]]): CandidateState =
    copy(track = track.offered(heights))

  /** The session streams from its candidate as it stands: nothing is published yet. */
  def streaming: CandidateState =
    copy(publication = Some(CandidateState.Publication(candidate, 0L, None, false, 0L)))

  /** A batch that ended or restarted the session: nothing more is published until a restart gives a
    * tip again.
    */
  def paused: CandidateState = copy(publication = publication.map(_.copy(tip = None)))

  /** ChainSync is done: the publisher ends once it has published the candidate. */
  def ended: CandidateState = copy(publication = publication.map(_.copy(done = true)))

  /** The publisher's step: what changed of the candidate since the cursor, at most `limit` headers
    * ([[Peer.publication]]); in a new epoch with no point in common, a rollback to the candidate's
    * base and its headers again. The cursor moves to what the events publish, in this same
    * transition, and the candidate is then trimmed to `anchor` ([[trimmed]]), which takes a trim
    * the old cursor held back. No events while the publisher has no tip.
    */
  def published(
      limit: Int,
      anchor: Option[Point]
  ): Either[PeerError.Divergence, (CandidateState, Vector[SyncEvent])] =
    publication.fold(Right(this -> Vector.empty[SyncEvent])) { p =>
      p.tip
        .fold(Right(p.cursor -> Vector.empty[SyncEvent])) { tip =>
          val normal = Peer.publication(p.cursor, candidate, tip, limit)
          normal.orElse {
            if p.found == p.epoch then normal
            else
              Peer
                .publication(
                  ChainSelection.Candidate(candidate.base, Vector.empty),
                  candidate,
                  tip,
                  limit
                )
                .map((after, events) =>
                  after -> (SyncEvent.RollBackward(candidate.base, tip) +: events)
                )
          }
        }
        .map { (after, events) =>
          val moved = copy(publication = Some(p.copy(cursor = after, found = p.epoch)))
          (anchor.fold(moved)(moved.trimmed), events)
        }
    }

private[node] object CandidateState:
  val initial: CandidateState =
    CandidateState(
      ChainSelection.Candidate(Point.Origin, Vector.empty),
      CandidateTrack.initial,
      0L,
      None
    )

  /** What a streaming session publishes: its `cursor`, the newest candidate published to the
    * consumer, found in epoch `found`; the peer's `tip` to publish with, none while a batch ended
    * or restarted the session; whether ChainSync is `done`; and the `epoch`, which each new
    * intersection or graft starts.
    */
  final case class Publication(
      cursor: ChainSelection.Candidate,
      found: Long,
      tip: Option[ChainSync.Tip],
      done: Boolean,
      epoch: Long
  )
