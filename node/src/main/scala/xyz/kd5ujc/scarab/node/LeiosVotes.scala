// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Concurrent, Ref, Resource, Unique}
import cats.effect.std.{Queue, Semaphore}
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.leios.{
  AddVoteResult,
  LeiosVoteState,
  TipCommittee,
  VoteAggregationDefect,
  VoteInvalid
}
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, Certificate, Committee, RbHash, SeatId, Vote, Weight}
import xyz.kd5ujc.scarab.ledger.LeiosCertificationDefect

/** Why the vote state reached no answer on a vote: a failure of this node, never a verdict on the
  * vote.
  */
enum LeiosVotesDefect:
  /** The native BLS library failed while the vote's signature was checked. */
  case Verification(cause: BlsError.Backend)

  /** The certificate of validated votes could not be assembled ([[VoteAggregationDefect]]). */
  case Aggregation(cause: VoteAggregationDefect)

  /** The committee of the selection's tip could not be read from its ledger
    * ([[LeiosCertificationDefect]]): a state the reference cannot hold.
    */
  case Committee(cause: LeiosCertificationDefect)

/** The node's Leios vote state, `LeiosVoteState m` (`LeiosVoteState.hs:42-55, 146-247`,
  * ouroboros-consensus 2416493b): the votes of this node and of its peers, tallied by announcing
  * block against the committee of the selection's tip, a certificate assembled once a tally reaches
  * the quorum, and a subscription to every vote counted. It is held in memory only, as the
  * reference's `TVar` s are.
  */
final class LeiosVotes[F[_]] private (
    held: Ref[F, LeiosVotes.Held[F]],
    committee: F[Either[LeiosCertificationDefect, Option[TipCommittee]]],
    votable: F[RbHash => Boolean],
    checks: Semaphore[F],
    val checksAtOnce: Int
)(using F: Concurrent[F]):
  import LeiosVotes.Verified

  /** `addVote` (`LeiosVoteState.hs:168-247`, ouroboros-consensus d91b3557): a vote whose block is
    * not `votable` is `NotVotable`, and one counted already `AlreadyKnown`, before any check, read
    * together as the reference reads them in one transaction; then the committee in force, read
    * once (`NoCommittee` without one); then the vote is validated against it outside the update,
    * the pairing being the costly part; then, in one update, counted unless a concurrent add
    * counted it first, and given to each subscriber registered at that update, once. The count and
    * the delivery are one step that a cancellation does not split, as the reference counts the vote
    * and writes it to its broadcast channel in one transaction: a vote is never counted while a
    * subscriber misses it, which a later copy, `AlreadyKnown`, would never make good.
    */
  def add(vote: Vote)(using Raise[F, LeiosVotesDefect]): F[AddVoteResult] =
    commit(Verified.unread(vote))

  /** [[add]] of a vote whose signature may have been checked ahead of its turn ([[verify]]): every
    * step of `addVote` is taken here, at the vote's turn, in its order: whether its block is one we
    * take votes for and whether it counted already, then the committee in force now, then the
    * check, which is the one made ahead only where it was made against this same committee, and
    * otherwise made here; then the update. So a vote checked ahead counts exactly as [[add]] would
    * count it now, whatever changed since its check.
    */
  def commit(verified: Verified)(using defect: Raise[F, LeiosVotesDefect]): F[AddVoteResult] =
    val vote = verified.vote
    (votable.map(_(vote.announcingRbHash)), held.get.map(_.state.seen(vote))).tupled.flatMap {
      case (false, _)   => F.pure(AddVoteResult.NotVotable)
      case (true, true) => F.pure(AddVoteResult.AlreadyKnown)
      case (true, false) =>
        committee.flatMap {
          case Left(failed) =>
            defect.raise[LeiosVotesDefect, AddVoteResult](LeiosVotesDefect.Committee(failed))
          case Right(None) => F.pure(AddVoteResult.NoCommittee)
          case Right(Some(tip @ TipCommittee(members, threshold))) =>
            verified.verdict(tip).fold(VoteInvalid.validate(members, vote))(Right(_)) match
              case Left(native) =>
                defect.raise[LeiosVotesDefect, AddVoteResult](LeiosVotesDefect.Verification(native))
              case Right(Left(reason)) => F.pure(AddVoteResult.Invalid(reason))
              case Right(Right(weight)) =>
                F.uncancelable(_ =>
                  held
                    .modify(h =>
                      h.state.add(vote, weight, members, threshold) match
                        case Left(failed) => (h, Left(failed))
                        case Right((next, result)) =>
                          (h.copy(state = next), Right((result, h.subscribers.values.toVector)))
                    )
                    .flatMap {
                      case Right((added @ AddVoteResult.Added(_, _), at)) =>
                        at.traverse_(_(vote)).as(Right(added))
                      case Right((other, _)) => F.pure(Right(other))
                      case Left(failed)      => F.pure(Left(failed))
                    }
                ).flatMap(
                  _.fold(
                    failed =>
                      defect.raise[LeiosVotesDefect, AddVoteResult](
                        LeiosVotesDefect.Aggregation(failed)
                      ),
                    F.pure
                  )
                )
        }
    }

  /** `vote` 's signature checked ahead of its turn against the committee in force now, at most as
    * many at once as the node allows, the committee read included, as its first read in an epoch
    * decodes every seat's key: the costly part of [[add]], made where it changes nothing a turn
    * relies on, since [[commit]] reads everything again at the vote's turn. A vote whose block is
    * not one we take votes for or that counted already, a committee that cannot be read or none, a
    * failure of the native library, or a failure of this reading leaves the vote unread, for its
    * turn to check.
    */
  def verify(vote: Vote): F[Verified] =
    (votable.map(_(vote.announcingRbHash)), held.get.map(_.state.seen(vote))).tupled
      .flatMap {
        case (true, false) =>
          checks.permit.surround(committee.map {
            case Right(Some(tip))      => Verified.against(tip, vote)
            case Left(_) | Right(None) => Verified.unread(vote)
          })
        case (false, _) | (true, true) => F.pure(Verified.unread(vote))
      }
      .handleError(_ => Verified.unread(vote))

  /** The votes of one `MsgLeiosVotes` from a peer, each added in turn (`NodeToNode.hs:717-766`,
    * ouroboros-consensus 2416493b); a vote that does not count is dropped, as there, and the
    * answers say which.
    */
  def received(votes: Vector[Vote])(using Raise[F, LeiosVotesDefect]): F[Vector[AddVoteResult]] =
    votes.traverse(add)

  /** `queryCert` (`LeiosVoteState.hs:241-243`, ouroboros-consensus 2416493b). */
  def certificate(rbHash: RbHash): F[Option[Certificate]] =
    held.get.map(_.state.certificate(rbHash))

  /** `queryVoters` (`LeiosVoteState.hs:244-246`, ouroboros-consensus 2416493b). */
  def voters(rbHash: RbHash): F[Map[SeatId, Weight]] = held.get.map(_.state.voters(rbHash))

  /** `subscribeVotes` (`LeiosVoteState.hs:235-240`, ouroboros-consensus 2416493b): each vote
    * counted from now on, once, for the life of the resource, on an unbounded queue as a duplicated
    * `TChan` is. Each vote is delivered in the update that counts it ([[add]]); two votes counted
    * at once by two adds may reach a subscriber in either order.
    */
  def subscribe: Resource[F, Queue[F, Vote]] =
    Resource.eval(Queue.unbounded[F, Vote]).flatTap(queue => subscribeWith(queue.offer))

  /** `deliver` given each vote counted from now on, once, for the life of the resource. It runs in
    * the step that counts the vote, which no cancellation splits, so it must not wait: an unbounded
    * queue's offer, as [[subscribe]] 's.
    */
  private[node] def subscribeWith(deliver: Vote => F[Unit]): Resource[F, Unit] =
    Resource
      .make(
        F.unique.flatTap(token =>
          held.update(h => h.copy(subscribers = h.subscribers.updated(token, deliver)))
        )
      )(token => held.update(h => h.copy(subscribers = h.subscribers - token)))
      .void

object LeiosVotes:
  /** `newLeiosVoteState` (`LeiosVoteState.hs:146-156`, ouroboros-consensus 2416493b) of the
    * committee in force, read for each vote: `leiosCommitteeOfTip` of the selection's ledger state
    * (`NodeKernel.hs:988-991`), or the defect that left it unread.
    */
  /** @param votable
    *   Whether a block is one we take votes for: among the newest [[LeiosVoteState.VoteWindow]] of
    *   the selection (`withinVoteWindow`, `NodeKernel.hs:986-998` at ouroboros-consensus d91b3557).
    * @param checks
    *   How many votes' signatures may be checked ahead of their turn at once, node-wide; at least
    *   one ([[LeiosVotes.verify]]).
    */
  def create[F[_]: Concurrent](
      committee: F[Either[LeiosCertificationDefect, Option[TipCommittee]]],
      votable: F[RbHash => Boolean],
      checks: Int = 1
  ): F[LeiosVotes[F]] =
    val atOnce = math.max(1, checks)
    (
      Ref.of[F, Held[F]](Held(LeiosVoteState.empty, Map.empty)),
      Semaphore[F](atOnce.toLong)
    ).mapN(new LeiosVotes(_, committee, votable, _, atOnce))

  /** A vote with its signature checked ahead of its turn against a committee, or with nothing
    * checked ([[Verified.unread]]). The check is `validateLeiosVote` ([[VoteInvalid.validate]])
    * itself, made where the value is made, so a verdict is always that committee's on the vote; a
    * vote's turn uses it only against that same committee ([[verdict]]).
    */
  final class Verified private (
      val vote: Vote,
      checked: Option[(Committee, Either[VoteInvalid, Weight])]
  ):
    /** The verdict of the check where it was made against `tip` 's own committee, the value whose
      * seats the check read, which `validate` would give again.
      */
    def verdict(tip: TipCommittee): Option[Either[VoteInvalid, Weight]] =
      checked.collect { case (members, verdict) if members eq tip.committee => verdict }

  object Verified:
    /** A vote with nothing checked: its turn checks it, as [[LeiosVotes.add]] does. */
    def unread(vote: Vote): Verified = new Verified(vote, None)

    /** `vote` checked against `tip` 's committee; a failure of the native library is no verdict on
      * the vote, and leaves it unread.
      */
    def against(tip: TipCommittee, vote: Vote): Verified =
      VoteInvalid
        .validate(tip.committee, vote)
        .fold(_ => unread(vote), verdict => new Verified(vote, Some((tip.committee, verdict))))

  /** The vote state and the subscribers, in one cell, so that a vote's count and the subscribers it
    * goes to are read in one update.
    */
  private final case class Held[F[_]](
      state: LeiosVoteState,
      subscribers: Map[Unique.Token, Vote => F[Unit]]
  )
