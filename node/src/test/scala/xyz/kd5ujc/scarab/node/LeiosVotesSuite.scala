// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.leios.{AddVoteResult, TipCommittee, VoteInvalid}
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, Committee, Seat, SeatId, Vote, Weight}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** The node's vote state, `newLeiosVoteState` (ouroboros-consensus 2416493b
  * `LeiosVoteState.hs:146-247`): the committee read for each vote, the subscription to the votes
  * counted, and the votes of a peer's `MsgLeiosVotes` (`NodeToNode.hs:717-766`). Keys are throwaway
  * keys made here.
  */
class LeiosVotesSuite extends FailsOnUncaught:
  private lazy val keys = Vector.tabulate(4)(i => key(900 + i))
  private lazy val committee = TipCommittee(
    right(Committee.honoured(keys.map(k => Seat(Weight(Rational(1, 4)), Some(k._2))))),
    Weight(Rational(3, 4))
  )
  private val announcing = rbHash(21)
  private def vote(i: Int): Vote =
    right(Vote.sign(keys(i)._1, right(SeatId.fromInt(i)), announcing))

  private def allowed[A](body: cats.mtl.Raise[IO, LeiosVotesDefect] ?=> IO[A]): IO[A] =
    Handle.allow[LeiosVotesDefect](body).rescue(e => IO(fail(s"defect: $e")))

  test("a seat's vote counted already is AlreadyKnown before its signature is checked") {
    val forged = right(Vote.sign(keys(1)._1, right(SeatId.fromInt(0)), announcing))
    for
      votes <- LeiosVotes.create[IO](IO.pure(Some(committee)))
      answers <- allowed(votes.received(Vector(vote(0), forged)))
    yield assertEquals(answers.drop(1), Vector(AddVoteResult.AlreadyKnown))
  }

  test("without a committee no vote counts, and none is published") {
    for
      votes <- LeiosVotes.create[IO](IO.pure(None))
      result <- votes.subscribe.use(q => allowed(votes.add(vote(0))).product(q.tryTakeN(None)))
    yield assertEquals(result, (AddVoteResult.NoCommittee, List.empty))
  }

  test("a subscriber gets every vote counted once, not a duplicate nor an invalid vote") {
    // seat 1's vote signed with another key, before seat 1 has voted
    val forged = right(Vote.sign(keys(2)._1, right(SeatId.fromInt(1)), announcing))
    for
      votes <- LeiosVotes.create[IO](IO.pure(Some(committee)))
      late <- allowed(votes.add(vote(3)))
      seen <- votes.subscribe.use { q =>
        allowed(votes.received(Vector(vote(0), vote(0), forged, vote(1), vote(2))))
          .product(q.tryTakeN(None))
      }
      cert <- votes.certificate(announcing)
      voters <- votes.voters(announcing)
    yield
      val (answers, published) = seen
      assert(late.isInstanceOf[AddVoteResult.Added])
      assertEquals(
        answers.map {
          case AddVoteResult.Added(_, c) => s"added ${c.isDefined}"
          case other                     => other.toString
        },
        Vector(
          "added false",
          AddVoteResult.AlreadyKnown.toString,
          AddVoteResult.Invalid(VoteInvalid.InvalidSignature).toString,
          "added true",
          "added true"
        )
      )
      assertEquals(published, List(vote(0), vote(1), vote(2)))
      assertEquals(voters.keySet.map(_.index), Set(0, 1, 2, 3))
      assertEquals(
        cert.map(Certificate.verify(committee.committee, committee.threshold, announcing, _)),
        Some(Right(Weight(Rational(3, 4))))
      )
  }

  test("the committee is read for every vote, so a vote counts against the one in force") {
    for
      current <- Ref.of[IO, Option[TipCommittee]](None)
      votes <- LeiosVotes.create[IO](current.get)
      before <- allowed(votes.add(vote(0)))
      _ <- current.set(Some(committee))
      after <- allowed(votes.add(vote(0)))
    yield
      assertEquals(before, AddVoteResult.NoCommittee)
      assert(after.isInstanceOf[AddVoteResult.Added])
  }

  test("the same vote added concurrently is counted once") {
    for
      votes <- LeiosVotes.create[IO](IO.pure(Some(committee)))
      results <- votes.subscribe.use(q =>
        allowed(Vector.fill(8)(vote(2)).parTraverse(votes.add)).product(q.tryTakeN(None))
      )
    yield
      val (answers, published) = results
      assertEquals(answers.count(_.isInstanceOf[AddVoteResult.Added]), 1)
      assertEquals(answers.count(_ == AddVoteResult.AlreadyKnown), 7)
      assertEquals(published, List(vote(2)))
  }
