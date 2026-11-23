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
      votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))), IO.pure(_ => true))
      answers <- allowed(votes.received(Vector(vote(0), forged)))
    yield assertEquals(answers.drop(1), Vector(AddVoteResult.AlreadyKnown))
  }

  test("without a committee no vote counts, and none is published") {
    for
      votes <- LeiosVotes.create[IO](IO.pure(Right(None)), IO.pure(_ => true))
      result <- votes.subscribe.use(q => allowed(votes.add(vote(0))).product(q.tryTakeN(None)))
    yield assertEquals(result, (AddVoteResult.NoCommittee, List.empty))
  }

  test("a subscriber gets every vote counted once, not a duplicate nor an invalid vote") {
    // seat 1's vote signed with another key, before seat 1 has voted
    val forged = right(Vote.sign(keys(2)._1, right(SeatId.fromInt(1)), announcing))
    for
      votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))), IO.pure(_ => true))
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
      votes <- LeiosVotes.create[IO](current.get.map(Right(_)), IO.pure(_ => true))
      before <- allowed(votes.add(vote(0)))
      _ <- current.set(Some(committee))
      after <- allowed(votes.add(vote(0)))
    yield
      assertEquals(before, AddVoteResult.NoCommittee)
      assert(after.isInstanceOf[AddVoteResult.Added])
  }

  test("the same vote added concurrently is counted once") {
    for
      votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))), IO.pure(_ => true))
      results <- votes.subscribe.use(q =>
        allowed(Vector.fill(8)(vote(2)).parTraverse(votes.add)).product(q.tryTakeN(None))
      )
    yield
      val (answers, published) = results
      assertEquals(answers.count(_.isInstanceOf[AddVoteResult.Added]), 1)
      assertEquals(answers.count(_ == AddVoteResult.AlreadyKnown), 7)
      assertEquals(published, List(vote(2)))
  }

  test("a receiver cancelled while it delivers a vote still delivers it to every subscriber") {
    // the first subscriber's delivery waits for the test, so the add is cancelled mid fan-out;
    // the vote is then counted and every subscriber registered at the count has it, or neither
    for
      votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))), IO.pure(_ => true))
      delivering <- cats.effect.Deferred[IO, Unit]
      release <- cats.effect.Deferred[IO, Unit]
      first <- Ref.of[IO, Vector[Vote]](Vector.empty)
      second <- Ref.of[IO, Vector[Vote]](Vector.empty)
      // the first registered is the first delivered to only by chance of the map's order, so each
      // delivery waits once, whichever comes first
      gated = (into: Ref[IO, Vector[Vote]]) =>
        (v: Vote) =>
          delivering
            .complete(())
            .flatMap(firstHere => release.get.whenA(firstHere) *> into.update(_ :+ v))
      outcome <- (votes.subscribeWith(gated(first)), votes.subscribeWith(gated(second))).tupled
        .use { _ =>
          for
            adding <- allowed(votes.add(vote(3))).start
            _ <- delivering.get
            // the cancel waits for the delivery, which the release lets finish
            cancelled <- adding.cancel.start
            _ <- release.complete(())
            _ <- cancelled.joinWithNever
            again <- allowed(votes.add(vote(3)))
            got <- (first.get, second.get).tupled
          yield (again, got)
        }
    yield
      val (again, (got1, got2)) = outcome
      assertEquals(again, AddVoteResult.AlreadyKnown)
      assertEquals((got1, got2), (Vector(vote(3)), Vector(vote(3))))
  }

  test(
    "a vote for a block outside the window is NotVotable before anything else, and not published"
  ) {
    val forged = right(Vote.sign(keys(1)._1, right(SeatId.fromInt(0)), announcing))
    for
      votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))), IO.pure(_ != announcing))
      result <- votes.subscribe.use(q =>
        allowed(votes.received(Vector(vote(0), forged, vote(0)))).product(q.tryTakeN(None))
      )
      voters <- votes.voters(announcing)
    yield
      assertEquals(result, (Vector.fill(3)(AddVoteResult.NotVotable), List.empty))
      assertEquals(voters, Map.empty)
  }

  test("the window is the newest blocks of the chain, newest first") {
    val chain = Vector.tabulate(12)(i => rbHash(100 + i)) // newest first
    val within = xyz.kd5ujc.scarab.consensus.leios.LeiosVoteState.withinVoteWindow(chain)
    assertEquals(chain.map(within), Vector.fill(8)(true) ++ Vector.fill(4)(false))
    assert(!within(rbHash(99)))
  }

  /** The points of the replay, distinct hashes beyond BlsFixtures' 256, one vote of seat 0 each. */
  private def point(i: Int): xyz.kd5ujc.scarab.crypto.bls.RbHash =
    xyz.kd5ujc.scarab.crypto.bls.RbHash
      .fromBytes(
        xyz.kd5ujc.scarab.codec.Bytes
          .wrap(Array.tabulate[Byte](32)(b => (i >>> (8 * (b % 4))).toByte))
      )
      .getOrElse(fail("point"))
  private val replayPoints =
    2 * xyz.kd5ujc.scarab.consensus.leios.LeiosVoteState.Window + xyz.kd5ujc.scarab.consensus.leios.LeiosVoteState.VoteWindow
  private lazy val goodVotes = Vector.tabulate(replayPoints)(i =>
    right(Vote.sign(keys(0)._1, right(SeatId.fromInt(0)), point(i)))
  )
  private lazy val badVotes = Vector.tabulate(replayPoints)(i =>
    right(Vote.sign(keys(1)._1, right(SeatId.fromInt(0)), point(i)))
  )

  test("a replayed vote is taken once, and only for a votable block (ouroboros-consensus #2534)") {
    // votes cycling through more blocks than the vote state keeps, in several rounds and any order,
    // as a fixed set of them did when every node kept relaying votes for long-gone blocks
    (1 to 12).toVector.traverse_ { seed =>
      val rnd = new scala.util.Random(seed)
      val n = 1 + rnd.nextInt(replayPoints)
      val votable =
        rnd
          .shuffle((0 until n).toVector)
          .take(xyz.kd5ujc.scarab.consensus.leios.LeiosVoteState.VoteWindow)
          .toSet
      val bad = (0 until n).filter(_ => rnd.nextBoolean()).toSet
      val arrivals = Vector.fill(1 + rnd.nextInt(3))(rnd.shuffle((0 until n).toVector)).flatten
      val expected = arrivals
        .foldLeft((Set.empty[Int], Vector.empty[String])) { case ((taken, out), i) =>
          if !votable(i) then (taken, out :+ "notVotable")
          else if bad(i) then (taken, out :+ "invalid")
          else if taken(i) then (taken, out :+ "known")
          else (taken + i, out :+ "added")
        }
        ._2
      val votablePoints = votable.map(point)
      // the votes taken, in arrival order: each one's first arrival, when votable and well signed
      val published = arrivals.zip(expected).collect { case (i, "added") => goodVotes(i) }
      for
        votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))), IO.pure(votablePoints))
        outcome <- votes.subscribe.use(q =>
          allowed(arrivals.traverse(i => votes.add(if bad(i) then badVotes(i) else goodVotes(i))))
            .product(q.tryTakeN(None))
        )
      yield
        val (answers, relayed) = outcome
        assertEquals(
          answers.map {
            case AddVoteResult.NotVotable                            => "notVotable"
            case AddVoteResult.Invalid(VoteInvalid.InvalidSignature) => "invalid"
            case AddVoteResult.Invalid(other)                        => s"invalid $other"
            case AddVoteResult.AlreadyKnown                          => "known"
            case AddVoteResult.Added(_, _)                           => "added"
            case AddVoteResult.NoCommittee                           => "noCommittee"
          },
          expected,
          s"seed $seed"
        )
        // what reaches the subscribers that relay votes: exactly the votes taken, once each
        assertEquals(relayed.toVector, published, s"seed $seed: relayed")
    }
  }

object LeiosVotesSuite:
  /** A vote subscription that never yields: a LeiosNotify server with no vote to relay. */
  val noVotes: cats.effect.Resource[IO, cats.effect.std.Queue[IO, Vote]] =
    cats.effect.Resource.eval(cats.effect.std.Queue.unbounded[IO, Vote])
