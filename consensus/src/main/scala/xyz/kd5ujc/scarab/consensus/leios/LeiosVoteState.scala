// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.rbHashOrdering
import xyz.kd5ujc.scarab.crypto.bls.{
  BlsError,
  Certificate,
  Committee,
  RbHash,
  Seat,
  SeatId,
  Signature,
  Vote,
  Weight
}

/** `VoteInvalid` (ouroboros-consensus 2416493b `LeiosDemoTypes.hs:1421-1425`): why a vote does not
  * count against a committee.
  */
enum VoteInvalid derives CanEqual:
  /** The signature does not verify over the announcing block's hash under the seat's key. */
  case InvalidSignature

  /** The seat is past the committee's end. */
  case SignerNotInCommittee

  /** The seat holds no voting key. */
  case SignerHasNoKey

object VoteInvalid:
  /** `validateLeiosVote` (`LeiosDemoTypes.hs:1409-1419`, ouroboros-consensus 2416493b): the seat,
    * its key, then the signature over the announcing block's hash; a valid vote weighs its seat's
    * weight. Every failure of the signature check is `InvalidSignature` (`verifyDSIGN`'s `Left`),
    * but a failure of the native library, which is no verdict on the vote.
    */
  def validate(
      committee: Committee,
      vote: Vote
  ): Either[BlsError.Backend, Either[VoteInvalid, Weight]] =
    committee.seat(vote.voter) match
      case None                => Right(Left(SignerNotInCommittee))
      case Some(Seat(_, None)) => Right(Left(SignerHasNoKey))
      case Some(Seat(weight, Some(_))) =>
        Vote.verify(committee, vote) match
          case Right(_)                       => Right(Right(weight))
          case Left(native: BlsError.Backend) => Left(native)
          case Left(_)                        => Right(Left(InvalidSignature))

/** `VoteTally` (`LeiosVoteState.hs:75-85`, ouroboros-consensus 2416493b): what one accepted vote
  * did to its announcing block's tally, the vote's own weight, the running tally after it and the
  * quorum in force when it was counted.
  */
final case class VoteTally(weight: Weight, tally: Weight, threshold: Weight)

/** `AddVoteResult` (`LeiosVoteState.hs:57-68`, ouroboros-consensus 2416493b). */
enum AddVoteResult derives CanEqual:
  /** The tip's era has no Leios committee. */
  case NoCommittee

  /** The vote does not count against the committee in force. */
  case Invalid(reason: VoteInvalid)

  /** A vote of this seat for this announcing block was counted already. */
  case AlreadyKnown

  /** The vote's announcing block is not one we tally: not among the newest
    * [[LeiosVoteState.VoteWindow]] blocks of our chain. Dropped before validation, neither counted
    * nor relayed (`NotVotable`, `LeiosVoteState.hs` at ouroboros-consensus d91b3557).
    */
  case NotVotable

  /** Counted; the certificate is present whenever the tally is at or above the quorum. */
  case Added(tally: VoteTally, certificate: Option[Certificate])

/** `aggregateLeiosCert` failed on counted votes: the reference's `error` in `addVote`
  * (`LeiosVoteState.hs:213-223`, ouroboros-consensus 2416493b), "should not happen". It can when
  * the committee changed between two votes of one announcing block and a seat counted under the old
  * one is past the new one's end ([[Certificate.aggregateLeiosCert]]), or when the native library
  * fails. A seat keyless under the new committee is aggregated, as there.
  */
final case class VoteAggregationDefect(rbHash: RbHash, cause: BlsError)

/** `PointState` (`LeiosVoteState.hs:92-107`, ouroboros-consensus 2416493b): the votes counted for
  * one announcing block, by seat, their summed weight, and the certificate assembled once the sum
  * first reached the quorum. The reference's `psSeen` is a set of votes ordered by hash and seat
  * only (`Ord LeiosVote`, `LeiosDemoTypes.hs:1362-1365`), so within one block it is the set of
  * seats counted, which `voters` already holds.
  */
final case class PointVotes(
    voters: Map[SeatId, (Weight, Signature)],
    total: Weight,
    certificate: Option[Certificate]
)

object PointVotes:
  val empty: PointVotes = PointVotes(Map.empty, Weight.zero, None)

/** The pure state of `newLeiosVoteState` (`LeiosVoteState.hs:146-247`, ouroboros-consensus
  * 2416493b): the tallies by announcing block and the order in which their blocks were first seen.
  * Only the [[LeiosVoteState.Window]] most recently first seen are kept (`boundPoints`,
  * `LeiosVoteState.hs:128-145` at ouroboros-consensus d91b3557), a memory bound only: an evicted
  * block's votes would count as new again, which is safe because the node takes no vote for a block
  * outside the newest [[LeiosVoteState.VoteWindow]] of its chain, a window far smaller than the
  * bound (`NotVotable`, checked by the vote state's owner before validation).
  */
final case class LeiosVoteState private (
    points: SortedMap[RbHash, PointVotes],
    order: Vector[RbHash]
):

  /** `seenIn` (`LeiosVoteState.hs:113-115`, ouroboros-consensus 2416493b): this seat's vote for
    * this block was counted.
    */
  def seen(vote: Vote): Boolean =
    points.get(vote.announcingRbHash).exists(_.voters.contains(vote.voter))

  /** `queryCert` (`LeiosVoteState.hs:241-243`, ouroboros-consensus 2416493b). */
  def certificate(rbHash: RbHash): Option[Certificate] = points.get(rbHash).flatMap(_.certificate)

  /** `queryVoters` (`LeiosVoteState.hs:244-246`, ouroboros-consensus 2416493b): the weight of each
    * seat counted for the block.
    */
  def voters(rbHash: RbHash): Map[SeatId, Weight] =
    points.get(rbHash).fold(Map.empty[SeatId, Weight])(_.voters.view.mapValues(_._1).toMap)

  /** The transaction of `addVote` once the vote is validated (`LeiosVoteState.hs:172-234`,
    * ouroboros-consensus 2416493b): a vote counted already is `AlreadyKnown` and changes nothing;
    * otherwise room is made for its block first, its weight is counted, and the certificate is
    * assembled from every counted vote the first time the tally reaches `threshold`, and kept.
    */
  def add(
      vote: Vote,
      weight: Weight,
      committee: Committee,
      threshold: Weight
  ): Either[VoteAggregationDefect, (LeiosVoteState, AddVoteResult)] =
    if seen(vote) then Right((this, AddVoteResult.AlreadyKnown))
    else
      val rbHash = vote.announcingRbHash
      val (bounded, nextOrder) = LeiosVoteState.boundPoints(rbHash, points, order)
      val point = bounded.getOrElse(rbHash, PointVotes.empty)
      // `insertLookupWithKey` replaces the seat's earlier entry, so its weight leaves the total;
      // with `seen` above no seat is counted twice, as there
      val replaced = point.voters.get(vote.voter).fold(Weight.zero)(_._1)
      val voters = point.voters.updated(vote.voter, (weight, vote.signature))
      val total = Weight(point.total.value + weight.value - replaced.value)
      val assembled: Either[VoteAggregationDefect, Option[Certificate]] =
        point.certificate match
          case Some(cert) => Right(Some(cert))
          case None if Ordering[Weight].gteq(total, threshold) =>
            Certificate
              .aggregateLeiosCert(committee, voters.view.mapValues(_._2).toMap)
              .left
              .map(VoteAggregationDefect(rbHash, _))
              .map(Some(_))
          case None => Right(None)
      assembled.map { cert =>
        val next = LeiosVoteState(
          bounded.updated(rbHash, PointVotes(voters, total, cert)),
          nextOrder
        )
        (next, AddVoteResult.Added(VoteTally(weight, total, threshold), cert))
      }

object LeiosVoteState:
  /** `leiosVoteWindow` (`LeiosVoteState.hs:152-153`, ouroboros-consensus d91b3557): how many of the
    * newest blocks of our chain we take votes for.
    */
  val VoteWindow: Int = 8

  /** `withinVoteWindow` (`LeiosVoteState.hs:155-158`, ouroboros-consensus d91b3557): whether a
    * block is among the newest [[VoteWindow]] of our chain's, given newest first.
    */
  def withinVoteWindow(newestFirst: Vector[RbHash])(rbHash: RbHash): Boolean =
    newestFirst.take(VoteWindow).contains(rbHash)

  /** `maxAnnouncementCount` (`LeiosTxCache/API.hs:154-155`, ouroboros-consensus 2416493b). */
  val Window: Int = 128

  val empty: LeiosVoteState = LeiosVoteState(SortedMap.empty, Vector.empty)

  /** `boundPoints` (`LeiosVoteState.hs:131-143`, ouroboros-consensus 2416493b): a block already
    * tallied keeps its place; a new one goes last, and the first seen is dropped once more than
    * [[Window]] are held.
    */
  private def boundPoints(
      rbHash: RbHash,
      points: SortedMap[RbHash, PointVotes],
      order: Vector[RbHash]
  ): (SortedMap[RbHash, PointVotes], Vector[RbHash]) =
    if points.contains(rbHash) then (points, order)
    else
      val grown = order :+ rbHash
      grown.headOption match
        case Some(oldest) if grown.sizeIs > Window => (points - oldest, grown.drop(1))
        case _                                     => (points, grown)
