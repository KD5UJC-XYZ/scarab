// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, StateGen}
import xyz.kd5ujc.scarab.crypto.bls.{
  Certificate,
  Committee,
  RbHash,
  Seat,
  SeatId,
  SecretKey,
  Vote,
  Weight
}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}

/** The pure vote state of `newLeiosVoteState` (ouroboros-consensus 2416493b
  * `LeiosVoteState.hs:146-247`), checked as the reference's own properties check it
  * (`test/consensus-test/Test/LeiosVoteState.hs:70-90`): duplicates, invalid votes, the threshold,
  * the reported tally and the window of announcing blocks; and the certificate it assembles passes
  * the checks of a certifying block.
  */
class LeiosVoteStateSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(10)

  private val seats = 6
  private lazy val keys = Vector.tabulate(seats)(i => key(500 + i))

  /** Six seats weighing 1/4, 1/4, 1/4, 1/8, 1/16, 1/16 of the stake, the last keyless. */
  private val weights = Vector(
    Rational(1, 4),
    Rational(1, 4),
    Rational(1, 4),
    Rational(1, 8),
    Rational(1, 16),
    Rational(1, 16)
  )
  private lazy val committee: Committee = right(
    Committee.honoured(keys.zip(weights).zipWithIndex.map { case (((_, vk), w), i) =>
      Seat(Weight(w), Option.when(i != seats - 1)(vk))
    })
  )
  private val quorum = Weight(Rational(3, 4))

  private def seat(i: Int): SeatId = right(SeatId.fromInt(i))
  private def vote(i: Int, h: RbHash): Vote = voteWith(keys(i)._1, i, h)
  private def voteWith(sk: SecretKey, i: Int, h: RbHash): Vote = right(Vote.sign(sk, seat(i), h))

  /** Validate, then add, as `addVote` does. */
  private def add(s: LeiosVoteState, v: Vote): (LeiosVoteState, AddVoteResult) =
    if s.seen(v) then (s, AddVoteResult.AlreadyKnown)
    else
      right(VoteInvalid.validate(committee, v)) match
        case Left(reason)  => (s, AddVoteResult.Invalid(reason))
        case Right(weight) => right(s.add(v, weight, committee, quorum))

  private def addAll(votes: Seq[Vote]): (LeiosVoteState, Vector[AddVoteResult]) =
    votes.foldLeft((LeiosVoteState.empty, Vector.empty[AddVoteResult])) { case ((s, rs), v) =>
      val (next, r) = add(s, v)
      (next, rs :+ r)
    }

  private val announcing = rbHash(9)

  test("a vote counted already is AlreadyKnown and changes nothing") {
    val (once, _) = addAll(Seq(vote(0, announcing)))
    assertEquals(add(once, vote(0, announcing)), (once, AddVoteResult.AlreadyKnown))
  }

  test("a vote of a keyless seat, past the committee or of the wrong key does not count") {
    val (_, results) = addAll(
      Seq(
        vote(5, announcing),
        right(Vote.sign(keys(0)._1, seat(seats), announcing)),
        voteWith(keys(2)._1, 1, announcing)
      )
    )
    assertEquals(
      results,
      Vector(
        AddVoteResult.Invalid(VoteInvalid.SignerHasNoKey),
        AddVoteResult.Invalid(VoteInvalid.SignerNotInCommittee),
        AddVoteResult.Invalid(VoteInvalid.InvalidSignature)
      )
    )
  }

  test("the quorum is reached at exactly 3/4 of the weight, and not one vote before") {
    // 1/4 + 1/8 + 1/16 < 3/4; with 1/4 more, 1/4 + 1/4 + 1/8 + 1/16 = 11/16 < 3/4; then exactly
    // 1/4 + 1/4 + 1/4 = 3/4 by three seats in another order
    val (below, belowResults) = addAll(Seq(0, 3, 4, 1).map(vote(_, announcing)))
    assert(belowResults.forall {
      case AddVoteResult.Added(_, cert) => cert.isEmpty
      case _                            => false
    })
    assertEquals(below.certificate(announcing), None)
    val (exact, results) = addAll(Seq(0, 1, 2).map(vote(_, announcing)))
    assertEquals(
      results.map {
        case AddVoteResult.Added(tally, cert) => (tally, cert.isDefined)
        case other                            => fail(s"not added: $other")
      },
      Vector(
        (VoteTally(Weight(Rational(1, 4)), Weight(Rational(1, 4)), quorum), false),
        (VoteTally(Weight(Rational(1, 4)), Weight(Rational(1, 2)), quorum), false),
        (VoteTally(Weight(Rational(1, 4)), Weight(Rational(3, 4)), quorum), true)
      )
    )
    val cert = exact.certificate(announcing).get
    assertEquals(
      Certificate.verify(committee, quorum, announcing, cert),
      Right(Weight(Rational(3, 4)))
    )
    assertEquals(exact.voters(announcing).keySet, Set(seat(0), seat(1), seat(2)))
  }

  test("the certificate assembled at the quorum is kept as later votes arrive") {
    val (reached, _) = addAll(Seq(0, 1, 2).map(vote(_, announcing)))
    val first = reached.certificate(announcing)
    val (later, result) = add(reached, vote(3, announcing))
    assertEquals(later.certificate(announcing), first)
    result match
      case AddVoteResult.Added(tally, cert) =>
        assertEquals(tally.tally, Weight(Rational(7, 8)))
        assertEquals(cert, first)
      case other => fail(s"not added: $other")
  }

  property("the reported tally is the sum of the weights reported, per announcing block") {
    forAll(Gen.someOf(0 until seats - 1), Gen.someOf(0 until seats - 1)) { (a, b) =>
      val other = rbHash(10)
      val votes = a.map(vote(_, announcing)) ++ b.map(vote(_, other))
      val (s, results) = addAll(votes.toSeq)
      val added = results.collect { case AddVoteResult.Added(t, _) => t }
      assertEquals(added.size, votes.size)
      def total(h: RbHash) = s.voters(h).values.foldLeft(Weight.zero)(_ + _)
      assertEquals(total(announcing), a.foldLeft(Weight.zero)((w, i) => w + Weight(weights(i))))
      assertEquals(total(other), b.foldLeft(Weight.zero)((w, i) => w + Weight(weights(i))))
    }
  }

  test("past the window of announcing blocks the first seen is forgotten, the latest kept") {
    val hashes = (0 to LeiosVoteState.Window).map(i => rbHash(1000 + i))
    val votes = hashes.map(vote(3, _))
    val (s, _) = addAll(votes)
    assert(add(s, votes.head)._2.isInstanceOf[AddVoteResult.Added])
    assertEquals(add(s, votes.last)._2, AddVoteResult.AlreadyKnown)
    assertEquals(s.voters(hashes.head), Map.empty)
  }

  property("the certificate of the parent's votes certifies the parent's announcement") {
    forAll(StateGen.praosState, StateGen.announcedBy) { (praos, announced) =>
      val (s, _) = addAll(Seq(0, 1, 2, 3).map(vote(_, announcing)))
      val cert = s.certificate(announcing).get
      val tip = TipCommittee(committee, quorum)
      val parent = ChainDepState.Praos2(Praos2.State(praos, Some(announced)))
      LeiosCertification.check(parent, Some(announcing.bytes), Some(tip), cert) match
        case Right(Right(certified)) =>
          assertEquals(certified.rbHash, announcing)
          // assembled when the tally first reached the quorum, by the first three votes
          assertEquals(certified.weight, Weight(Rational(3, 4)))
          assertEquals(Certificate.decode(cert.encode), Right(cert))
        case other => fail(s"not certified: $other")
    }
  }

  test("a seat counted under one committee and keyless under the next is aggregated, as there") {
    // seats 0 and 1 counted under the committee; seat 2 then arrives under a committee in which
    // seat 0 has lost its key: aggregateLeiosCert range-checks the seats only
    val (two, _) = addAll(Seq(0, 1).map(vote(_, announcing)))
    val changed = right(
      Committee.honoured(committee.seats.updated(0, committee.seats(0).copy(key = None)))
    )
    val v = vote(2, announcing)
    val weight = right(right(VoteInvalid.validate(changed, v)))
    two.add(v, weight, changed, quorum) match
      case Right((after, AddVoteResult.Added(tally, Some(cert)))) =>
        assertEquals(tally.tally, quorum)
        assertEquals(cert.signers.members(seats).map(_.map(_.index)), Some(Vector(0, 1, 2)))
        assertEquals(after.certificate(announcing), Some(cert))
      case other => fail(s"not certified: $other")
  }

  test("a seat counted under one committee and past the end of the next is a defect") {
    val (two, _) = addAll(Seq(0, 4).map(vote(_, announcing)))
    val smaller = right(Committee.honoured(committee.seats.take(4)))
    val v = vote(1, announcing)
    assert(two.add(v, Weight(Rational(1, 2)), smaller, quorum).isLeft)
  }
