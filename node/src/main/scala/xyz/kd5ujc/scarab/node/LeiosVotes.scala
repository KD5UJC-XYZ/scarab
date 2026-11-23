// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Concurrent, Ref, Resource, Unique}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.leios.{
  AddVoteResult,
  LeiosVoteState,
  TipCommittee,
  VoteAggregationDefect,
  VoteInvalid
}
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, Certificate, RbHash, SeatId, Vote, Weight}
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
    votable: F[RbHash => Boolean]
)(using F: Concurrent[F]):

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
  def add(vote: Vote)(using defect: Raise[F, LeiosVotesDefect]): F[AddVoteResult] =
    (votable.map(_(vote.announcingRbHash)), held.get.map(_.state.seen(vote))).tupled.flatMap {
      case (false, _)   => F.pure(AddVoteResult.NotVotable)
      case (true, true) => F.pure(AddVoteResult.AlreadyKnown)
      case (true, false) =>
        committee.flatMap {
          case Left(failed) =>
            defect.raise[LeiosVotesDefect, AddVoteResult](LeiosVotesDefect.Committee(failed))
          case Right(None) => F.pure(AddVoteResult.NoCommittee)
          case Right(Some(TipCommittee(members, threshold))) =>
            VoteInvalid.validate(members, vote) match
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
    */
  def create[F[_]: Concurrent](
      committee: F[Either[LeiosCertificationDefect, Option[TipCommittee]]],
      votable: F[RbHash => Boolean]
  ): F[LeiosVotes[F]] =
    Ref
      .of[F, Held[F]](Held(LeiosVoteState.empty, Map.empty))
      .map(new LeiosVotes(_, committee, votable))

  /** The vote state and the subscribers, in one cell, so that a vote's count and the subscribers it
    * goes to are read in one update.
    */
  private final case class Held[F[_]](
      state: LeiosVoteState,
      subscribers: Map[Unique.Token, Vote => F[Unit]]
  )
