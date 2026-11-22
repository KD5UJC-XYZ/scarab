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

/** Why the vote state reached no answer on a vote: a failure of this node, never a verdict on the
  * vote.
  */
enum LeiosVotesDefect:
  /** The native BLS library failed while the vote's signature was checked. */
  case Verification(cause: BlsError.Backend)

  /** The certificate of validated votes could not be assembled ([[VoteAggregationDefect]]). */
  case Aggregation(cause: VoteAggregationDefect)

/** The node's Leios vote state, `LeiosVoteState m` (`LeiosVoteState.hs:42-55, 146-247`,
  * ouroboros-consensus 2416493b): the votes of this node and of its peers, tallied by announcing
  * block against the committee of the selection's tip, a certificate assembled once a tally reaches
  * the quorum, and a subscription to every vote counted. It is held in memory only, as the
  * reference's `TVar` s are.
  */
final class LeiosVotes[F[_]] private (
    state: Ref[F, LeiosVoteState],
    subscribers: Ref[F, Map[Unique.Token, Queue[F, Vote]]],
    committee: F[Option[TipCommittee]]
)(using F: Concurrent[F]):

  /** `addVote` (`LeiosVoteState.hs:157-234`, ouroboros-consensus 2416493b): a vote counted already
    * is `AlreadyKnown` before any check; then the committee in force, read once (`NoCommittee`
    * without one); then the vote is validated against it outside the update, the pairing being the
    * costly part; then, in one update, counted unless a concurrent add counted it first. Every vote
    * counted goes to each subscriber, once.
    */
  def add(vote: Vote)(using defect: Raise[F, LeiosVotesDefect]): F[AddVoteResult] =
    state.get.map(_.seen(vote)).flatMap {
      case true => F.pure(AddVoteResult.AlreadyKnown)
      case false =>
        committee.flatMap {
          case None => F.pure(AddVoteResult.NoCommittee)
          case Some(TipCommittee(members, threshold)) =>
            VoteInvalid.validate(members, vote) match
              case Left(native) =>
                defect.raise[LeiosVotesDefect, AddVoteResult](LeiosVotesDefect.Verification(native))
              case Right(Left(reason)) => F.pure(AddVoteResult.Invalid(reason))
              case Right(Right(weight)) =>
                state
                  .modify(s =>
                    s.add(vote, weight, members, threshold) match
                      case Left(failed)          => (s, Left(failed))
                      case Right((next, result)) => (next, Right(result))
                  )
                  .flatMap {
                    case Left(failed) =>
                      defect.raise[LeiosVotesDefect, AddVoteResult](
                        LeiosVotesDefect.Aggregation(failed)
                      )
                    case Right(added @ AddVoteResult.Added(_, _)) => publish(vote).as(added)
                    case Right(other)                             => F.pure(other)
                  }
        }
    }

  /** The votes of one `MsgLeiosVotes` from a peer, each added in turn (`NodeToNode.hs:717-766`,
    * ouroboros-consensus 2416493b); a vote that does not count is dropped, as there, and the
    * answers say which.
    */
  def received(votes: Vector[Vote])(using Raise[F, LeiosVotesDefect]): F[Vector[AddVoteResult]] =
    votes.traverse(add)

  /** `queryCert` (`LeiosVoteState.hs:241-243`, ouroboros-consensus 2416493b). */
  def certificate(rbHash: RbHash): F[Option[Certificate]] = state.get.map(_.certificate(rbHash))

  /** `queryVoters` (`LeiosVoteState.hs:244-246`, ouroboros-consensus 2416493b). */
  def voters(rbHash: RbHash): F[Map[SeatId, Weight]] = state.get.map(_.voters(rbHash))

  /** `subscribeVotes` (`LeiosVoteState.hs:235-240`, ouroboros-consensus 2416493b): each vote
    * counted from now on, once, for the life of the resource, on an unbounded queue as a duplicated
    * `TChan` is. The reference writes the vote to its channel in the transaction that counts it;
    * here it is offered just after, so two votes counted at once by two adds may reach a subscriber
    * in either order.
    */
  def subscribe: Resource[F, Queue[F, Vote]] =
    Resource
      .make(
        (F.unique, Queue.unbounded[F, Vote]).flatMapN((token, queue) =>
          subscribers.update(_.updated(token, queue)).as(token -> queue)
        )
      )((token, _) => subscribers.update(_ - token))
      .map(_._2)

  private def publish(vote: Vote): F[Unit] =
    subscribers.get.flatMap(_.values.toVector.traverse_(_.offer(vote)))

object LeiosVotes:
  /** `newLeiosVoteState` (`LeiosVoteState.hs:146-156`, ouroboros-consensus 2416493b) of the
    * committee in force, read for each vote: `leiosCommitteeOfTip` of the selection's ledger state
    * (`NodeKernel.hs:988-991`).
    */
  def create[F[_]: Concurrent](committee: F[Option[TipCommittee]]): F[LeiosVotes[F]] =
    (
      Ref.of[F, LeiosVoteState](LeiosVoteState.empty),
      Ref.of[F, Map[Unique.Token, Queue[F, Vote]]](Map.empty)
    ).mapN(new LeiosVotes(_, _, committee))
