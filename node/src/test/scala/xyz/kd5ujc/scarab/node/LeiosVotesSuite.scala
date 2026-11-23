// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.leios.{AddVoteResult, TipCommittee, VoteInvalid}
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, Committee, RbHash, Seat, SeatId, Vote, Weight}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.ledger.LeiosCertificationDefect
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

  // --- votes checked ahead of their turn ---

  /** The committee a scenario reads at a step: one of two layouts, the first again as a new value,
    * none, or one that cannot be read.
    */
  private enum Basis derives CanEqual:
    case First, FirstAgain, Rotated, Absent, Unreadable

  /** Which blocks a scenario takes votes for at a step: every block, none, or every block but the
    * first of the few.
    */
  private enum Votable derives CanEqual:
    case Every, NoBlock, AllButFirst

  private def admits(votable: Votable)(block: RbHash): Boolean = votable match
    case Votable.Every       => true
    case Votable.NoBlock     => false
    case Votable.AllButFirst => block != rbHash(60)

  /** A step of a scenario: a vote arrives, the committee in force changes, or the blocks votes are
    * taken for change.
    */
  private enum Step:
    case Arrives(vote: Vote)
    case Changes(to: Basis)
    case Admits(votable: Votable)

  /** Six seats of equal weight and a quorum of four; `Rotated` seats each key one seat later, so a
    * vote signed for one layout is invalid under the other.
    */
  private lazy val six = Vector.tabulate(6)(i => key(940 + i))
  private def layout(shift: Int): TipCommittee = TipCommittee(
    right(
      Committee.honoured(
        Vector.tabulate(6)(i => Seat(Weight(Rational(1, 6)), Some(six((i + shift) % 6)._2)))
      )
    ),
    Weight(Rational(2, 3))
  )
  private lazy val first = layout(0)
  private lazy val firstAgain = layout(0)
  private lazy val rotated = layout(1)

  private def read(basis: Basis): Either[LeiosCertificationDefect, Option[TipCommittee]] =
    basis match
      case Basis.First      => Right(Some(first))
      case Basis.FirstAgain => Right(Some(firstAgain))
      case Basis.Rotated    => Right(Some(rotated))
      case Basis.Absent     => Right(None)
      case Basis.Unreadable => Left(LeiosCertificationDefect.CommitteeOfNode11_1)

  /** Votes of each seat for `blocks`, signed for either layout. */
  private def signedFor(blocks: Vector[RbHash], seats: Int): Vector[Vote] =
    for
      block <- blocks
      seat <- (0 until seats).toVector
      shift <- Vector(0, 1)
    yield right(Vote.sign(six((seat + shift) % 6)._1, right(SeatId.fromInt(seat)), block))

  /** Three blocks, every seat, both layouts: duplicates, invalid votes and certificates. */
  private lazy val few = signedFor(Vector.tabulate(3)(i => rbHash(60 + i)), 6)

  /** 140 blocks, two seats, both layouts: more blocks than the vote state holds, so blocks leave it
    * between a vote's check and its turn, and their votes count again.
    */
  private lazy val many = signedFor(Vector.tabulate(140)(i => rbHash(1000 + i)), 2)

  private val basis: Gen[Basis] = Gen.oneOf(Basis.values.toSeq)

  private def steps(pool: Vector[Vote], length: Int): Gen[Vector[Step]] =
    Gen
      .listOfN(
        length,
        Gen.frequency(
          18 -> Gen.oneOf(pool).map(Step.Arrives(_)),
          2 -> basis.map(Step.Changes(_)),
          1 -> Gen.oneOf(Votable.values.toSeq).map(Step.Admits(_))
        )
      )
      .map(_.toVector)

  /** A scenario: the committee in force at the start, the one in force while every vote is checked
    * ahead, and the steps.
    */
  private def scenario(pool: Vector[Vote], length: Int): Gen[(Basis, Basis, Vector[Step])] =
    for
      start <- basis
      ahead <- basis
      taken <- steps(pool, length)
    yield (start, ahead, taken)

  /** A scenario past the window: both seats' votes for each of the 140 blocks in order, the
    * committee changing only between layouts under which they count, so more blocks than the window
    * holds are counted and the first ones leave it; then votes for the first blocks again, under
    * any committee.
    */
  private val wide: Gen[(Basis, Basis, Vector[Step])] =
    val counting = Gen.oneOf(Basis.First, Basis.FirstAgain)
    def slot(block: Int): Gen[Vector[Step]] =
      Gen
        .frequency(
          19 -> Gen.const(Vector.empty[Step]),
          1 -> counting.map(b => Vector(Step.Changes(b)))
        )
        .map(_ ++ Vector(Step.Arrives(many(4 * block)), Step.Arrives(many(4 * block + 2))))
    for
      ahead <- basis
      sweep <- Gen.sequence[List[Vector[Step]], Vector[Step]]((0 until 140).map(slot))
      again <- steps(many.take(4 * 20), 60)
    yield (Basis.First, ahead, sweep.toVector.flatten ++ again)

  /** What a run shows: each vote's answer or the vote state's defect, the votes each subscriber was
    * given in order, and each block's certificate and voters at the end.
    */
  private type Shown = (
      Vector[Either[LeiosVotesDefect, AddVoteResult]],
      Vector[Vote],
      Vector[(Option[Certificate], Map[SeatId, Weight])]
  )

  /** The steps run against a vote state whose committee starts as `start` 's: every vote checked
    * ahead under `ahead` 's committee and then committed in its turn, or, without `ahead`, each
    * taken in its turn with nothing checked ahead, as [[LeiosVotes.add]] takes it.
    */
  private def run(
      start: Basis,
      ahead: Option[Basis],
      steps: Vector[Step]
  ): IO[Shown] =
    for
      in <- Ref.of[IO, Basis](start)
      taking <- Ref.of[IO, Votable](Votable.Every)
      votes <- LeiosVotes.create[IO](in.get.map(read), taking.get.map(admits), checks = 2)
      handedOn <- Ref.of[IO, Vector[Vote]](Vector.empty)
      shown <- votes.subscribeWith(v => handedOn.update(_ :+ v)).use { _ =>
        val arriving = steps.collect { case Step.Arrives(v) => v }
        // every vote checked ahead under the `ahead` committee, before the first turn, the farthest
        // a check can be from its turn; or none checked ahead, which is `add`
        val checked: IO[Vector[LeiosVotes.Verified]] = ahead.fold(
          IO.pure(arriving.map(LeiosVotes.Verified.unread))
        )(b => in.set(b) *> arriving.traverse(votes.verify) <* in.set(start))
        checked.flatMap { verified =>
          steps
            .foldLeftM((verified, Vector.empty[Either[LeiosVotesDefect, AddVoteResult]])) {
              case ((next, answers), Step.Changes(to)) => in.set(to).as((next, answers))
              case ((next, answers), Step.Admits(v))   => taking.set(v).as((next, answers))
              case ((next, answers), Step.Arrives(_)) =>
                Handle
                  .allow[LeiosVotesDefect](
                    votes.commit(next.head).map(_.asRight[LeiosVotesDefect])
                  )
                  .rescue(d => IO.pure(d.asLeft[AddVoteResult]))
                  .map(answer => (next.tail, answers :+ answer))
            }
            .map(_._2)
        }
      }
      blocks = steps.collect { case Step.Arrives(v) => v.announcingRbHash }.distinct
      ends <- blocks.traverse(b => (votes.certificate(b), votes.voters(b)).tupled)
      delivered <- handedOn.get
    yield (shown, delivered, ends)

  test("a vote checked ahead of its turn counts as adding it in its turn counts it") {
    // the model is `add` (`addVote`, LeiosVoteState.hs:157-234 at ouroboros-consensus 2416493b):
    // checking every vote ahead, against any committee, changes no answer, no delivery and no tally,
    // whatever the committee, the window and the counted votes become before each vote's turn
    val cases =
      Gen
        .listOfN(60, scenario(few, 30))
        .apply(Gen.Parameters.default, Seed(1349L))
        .toList
        .flatten ++
        Gen
          .listOfN(3, wide)
          .apply(Gen.Parameters.default, Seed(1355L))
          .toList
          .flatten
    cases
      .traverse { (start, ahead, steps) =>
        (run(start, None, steps), run(start, Some(ahead), steps)).tupled
          .map((added, committed) => ((start, ahead, steps.size), added, committed))
      }
      .map { runs =>
        assertEquals(runs.size, 63)
        runs
          .foreach((label, added, committed) => assertEquals(committed, added, s"scenario $label"))
        // the scenarios reach every answer, and leave blocks certified
        val answers = runs.flatMap(_._2._1)
        assert(answers.exists(_.exists(_.isInstanceOf[AddVoteResult.Added])), "no vote counted")
        assert(answers.contains(Right(AddVoteResult.AlreadyKnown)), "no vote known")
        assert(answers.contains(Right(AddVoteResult.NotVotable)), "no vote for a block not taken")
        assert(answers.contains(Right(AddVoteResult.NoCommittee)), "no vote without a committee")
        assert(
          answers.contains(Right(AddVoteResult.Invalid(VoteInvalid.InvalidSignature))),
          "no invalid vote"
        )
        assert(answers.exists(_.isLeft), "no committee unread")
        assert(runs.exists(_._2._3.exists(_._1.isDefined)), "no block certified")
      }
  }

object LeiosVotesSuite:
  /** A vote subscription that never yields: a LeiosNotify server with no vote to relay. */
  val noVotes: cats.effect.Resource[IO, cats.effect.std.Queue[IO, Vote]] =
    cats.effect.Resource.eval(cats.effect.std.Queue.unbounded[IO, Vote])
