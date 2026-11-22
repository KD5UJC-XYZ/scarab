// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.data.{EitherT, NonEmptyVector, State}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, StateGen}
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, Committee, RbHash, Seat, SeatId, Vote, Weight}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.ledger.Praos2Defect
import xyz.kd5ujc.scarab.ledger.state.{SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** The decisions of Leios voting and certifying (ouroboros-consensus 2416493b): `goVote`
  * (`LeiosVoting.hs:309-419`) in the order of its checks, `tipAnnouncerFor`, the seats of the
  * node's keys, the vote timing and `decideLeiosCertify` (`NodeKernel/Forge.hs:324-396`) at its
  * boundaries.
  */
class LeiosVotingSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(10)

  /** What the program did and what is left of the clock's readings. */
  final case class World(clock: List[FiniteDuration], validated: Int, added: Vector[Vote])
  type S[A] = State[World, A]
  type F[A] = EitherT[S, LeiosVotingDefect, A]

  private lazy val keys = Vector.tabulate(4)(i => key(700 + i))
  // our keys: the first three; the fourth is a stranger's, seated, and the third holds no seat
  private lazy val voting = keys.take(3).map(k => right(VotingKey.of(k._1)))

  /** Four seats: the first two keys, a stranger's, and the first key again. */
  private lazy val tip = TipCommittee(
    right(
      Committee.honoured(
        Vector(
          Seat(Weight(Rational(1, 4)), Some(keys(0)._2)),
          Seat(Weight(Rational(1, 4)), Some(keys(1)._2)),
          Seat(Weight(Rational(1, 4)), Some(keys(3)._2)),
          Seat(Weight(Rational(1, 4)), Some(keys(0)._2))
        )
      )
    ),
    Weight(Rational(3, 4))
  )

  private def seat(i: Int): SeatId = right(SeatId.fromInt(i))
  private val announcing: RbHash = rbHash(3)
  private val ebHash: EbHash = EbHash.fromBytes(rbHash(4).bytes)
  private val slot = 100L
  private val point = EbPoint(Word64.fromUnsigned(slot), ebHash)

  /** A Praos2 state whose last header, at `slot`, announced `ebHash`, or announced nothing. */
  private def chainDep(announces: Boolean): ChainDepState =
    val fixed = org.scalacheck.rng.Seed(54L)
    val praos = StateGen.praosState
      .pureApply(Gen.Parameters.default, fixed)
      .copy(lastSlot = WithOrigin.At(SlotNo.fromUnsigned(slot)))
    val announced = StateGen.announcedBy.pureApply(Gen.Parameters.default, fixed)
    ChainDepState.Praos2(
      Praos2.State(
        praos,
        Option.when(announces)(
          announced.copy(announcement = announced.announcement.copy(hash = ebHash))
        )
      )
    )

  private def env(
      view: Option[SelectionView[Unit]],
      verdict: EbClosureVerdict[String, Int],
      answer: Vote => AddVoteResult
  ): LeiosVotingEnv[F, Unit, String, Int] = new LeiosVotingEnv[F, Unit, String, Int]:
    def now: F[FiniteDuration] = EitherT.liftF(State[World, FiniteDuration] { w =>
      (w.copy(clock = w.clock.drop(1)), w.clock.headOption.getOrElse(Duration.Zero))
    })
    def selection: F[Option[SelectionView[Unit]]] = EitherT.pure[S, LeiosVotingDefect](view)
    def validateClosure(p: EbPoint, ledger: Unit): F[EbClosureVerdict[String, Int]] =
      EitherT.liftF(
        State[World, EbClosureVerdict[String, Int]](w =>
          (w.copy(validated = w.validated + 1), verdict)
        )
      )
    def addVote(vote: Vote): F[AddVoteResult] =
      EitherT.liftF(
        State[World, AddVoteResult](w => (w.copy(added = w.added :+ vote), answer(vote)))
      )

  private val added: Vote => AddVoteResult = _ =>
    AddVoteResult.Added(VoteTally(Weight.zero, Weight.zero, Weight.zero), None)

  private def view(announces: Boolean = true, tipHash: Option[RbHash] = Some(announcing)) =
    Some(SelectionView(chainDep(announces), tipHash, Some(tip), ()))

  private def run(
      e: LeiosVotingEnv[F, Unit, String, Int],
      clock: List[FiniteDuration],
      keys: Vector[VotingKey] = voting
  ) =
    LeiosVoting
      .vote[F, Unit, String, Int](e, keys, point, 10.seconds)
      .value
      .run(World(clock, 0, Vector.empty))
      .value

  test("each seat a key holds gets one vote, the key's smallest seat, a stranger's seat none") {
    val (world, result) =
      run(env(view(), EbClosureVerdict.Valid(0, 0), added), List(1.second, 2.seconds))
    val votes = result.map(_.map(_.map(_._1).toVector))
    assertEquals(
      votes.map(_.map(_.map(v => (v.voter, v.announcingRbHash)))),
      Right(
        Right(
          Vector((seat(0), announcing), (seat(1), announcing))
        )
      )
    )
    assertEquals(world.validated, 1)
    assertEquals(world.added.map(v => Vote.verify(tip.committee, v).isRight), Vector(true, true))
  }

  test("the deadline is checked first and is strict: at it a vote is cast, after it none") {
    val (atWorld, at) =
      run(env(view(), EbClosureVerdict.Valid(0, 0), added), List(10.seconds, 10.seconds))
    assert(at.exists(_.isRight))
    assertEquals(atWorld.added.size, 2)
    val (late, after) =
      run(env(view(), EbClosureVerdict.Valid(0, 0), added), List(10.seconds + 1.milli))
    assertEquals(after, Right(Left(LeiosNotVotedReason.TooLate)))
    assertEquals(late.validated, 0)
  }

  test("a validation that ends after the deadline casts no vote") {
    val (world, result) =
      run(env(view(), EbClosureVerdict.Valid(0, 0), added), List(9.seconds, 10.seconds + 1.milli))
    assertEquals(result, Right(Left(LeiosNotVotedReason.TooLate)))
    assertEquals((world.validated, world.added), (1, Vector.empty))
  }

  test("a selection whose tip does not announce the block, or none, casts no vote, unvalidated") {
    Vector(view(announces = false), view(tipHash = None), None).foreach { v =>
      val (world, result) = run(env(v, EbClosureVerdict.Valid(0, 0), added), List(1.second))
      assertEquals(result, Right(Left(LeiosNotVotedReason.ChainTipDoesNotAnnounce)))
      assertEquals(world.validated, 0)
    }
  }

  test("no committee, or no seat of our keys, casts no vote, unvalidated") {
    val noCommittee = view().map(_.copy(committee = None))
    Vector(
      run(env(noCommittee, EbClosureVerdict.Valid(0, 0), added), List(1.second)),
      run(env(view(), EbClosureVerdict.Valid(0, 0), added), List(1.second), Vector(voting(2))),
      run(env(view(), EbClosureVerdict.Valid(0, 0), added), List(1.second), Vector.empty)
    ).foreach { (world, result) =>
      assertEquals(result, Right(Left(LeiosNotVotedReason.NotOnCommittee)))
      assertEquals(world.validated, 0)
    }
  }

  test("an invalid or unreadable closure casts no vote") {
    assertEquals(
      run(env(view(), EbClosureVerdict.Invalid("spent"), added), List(1.second))._2,
      Right(Left(LeiosNotVotedReason.EbTxsInvalid("spent")))
    )
    assertEquals(
      run(env(view(), EbClosureVerdict.Unreadable(7), added), List(1.second))._2,
      Right(Left(LeiosNotVotedReason.ClosureUnavailable(7)))
    )
  }

  test("votes all refused are VoteRejected; one accepted is a vote") {
    val refused: Vote => AddVoteResult = _ => AddVoteResult.AlreadyKnown
    run(env(view(), EbClosureVerdict.Valid(0, 0), refused), List(1.second, 2.seconds))._2 match
      case Right(Left(LeiosNotVotedReason.VoteRejected(answers))) =>
        assertEquals(answers.map(_._2).toVector, Vector.fill(2)(AddVoteResult.AlreadyKnown))
      case other => fail(s"not rejected: $other")
    val oneOf: Vote => AddVoteResult = v => if v.voter == seat(1) then added(v) else refused(v)
    assert(
      run(env(view(), EbClosureVerdict.Valid(0, 0), oneOf), List(1.second, 2.seconds))._2
        .exists(_.isRight)
    )
  }

  test("the vote timing of the parameters and the default of eras without them") {
    val timing = LeiosVoteTiming.of(1000, 4000)
    assertEquals(timing, LeiosVoteTiming(3.seconds, 4.seconds))
    assertEquals(timing, LeiosVoteTiming.default)
    assertEquals(LeiosVoteTiming.of(100, 400).windowFrom(5.seconds), (5300.millis, 5700.millis))
  }

  test("the certification gap: the testnet's 14 slots, rounded up, and no zero slot length") {
    assertEquals(LeiosVoting.minCertificationGap(1000, 1000, 4000, 7000), Right(BigInt(14)))
    assertEquals(LeiosVoting.minCertificationGap(100, 100, 400, 700), Right(BigInt(14)))
    assertEquals(LeiosVoting.minCertificationGap(3000, 1000, 4000, 7000), Right(BigInt(5)))
    assertEquals(LeiosVoting.minCertificationGap(0, 1000, 4000, 7000).isLeft, true)
    assertEquals(
      LeiosVoting.minCertificationGap(0, 1, 1, 1),
      Left(Praos2Defect.NonPositiveSlotLength(0))
    )
  }

  // decideLeiosCertify

  private val cert: Certificate =
    right(
      Certificate.aggregate(
        tip.committee,
        Map(seat(0) -> right(Vote.sign(keys(0)._1, seat(0), announcing)).signature)
      )
    )
  type G[A] = Either[CertifyDefect, A]

  private def decide(
      at: Long,
      announced: Option[EbPoint] = Some(point),
      gap: Option[BigInt] = Some(14),
      tipHash: Option[RbHash] = Some(announcing),
      held: Boolean = true,
      certs: Map[RbHash, Certificate] = Map(announcing -> cert)
  ): G[CertifyDecision] =
    LeiosVoting.decideLeiosCertify[G](
      Word64.fromUnsigned(at),
      announced,
      gap,
      tipHash,
      _ => Right(held),
      h => Right(certs.get(h))
    )

  test("decideLeiosCertify: one slot short of the gap is too early, at the gap it certifies") {
    assertEquals(decide(slot + 13), Right(CertifyDecision.TooEarly(point, 14)))
    assertEquals(decide(slot + 14), Right(CertifyDecision.Certify(cert, point)))
    assertEquals(decide(slot + 15), Right(CertifyDecision.Certify(cert, point)))
  }

  test("decideLeiosCertify: nothing announced, or no Leios gap, is nothing to certify") {
    assertEquals(decide(slot + 20, announced = None), Right(CertifyDecision.NothingAnnounced))
    assertEquals(decide(slot + 20, gap = None), Right(CertifyDecision.NothingAnnounced))
  }

  test("decideLeiosCertify: no closure, then no certificate for the parent, certify nothing") {
    assertEquals(
      decide(slot + 20, held = false),
      Right(CertifyDecision.ClosureNotDownloaded(point))
    )
    assertEquals(decide(slot + 20, certs = Map.empty), Right(CertifyDecision.NoCertificate(point)))
    assertEquals(
      decide(slot + 20, certs = Map(rbHash(77) -> cert)),
      Right(CertifyDecision.NoCertificate(point))
    )
    assertEquals(decide(slot + 20, tipHash = None), Left(CertifyDefect.OnGenesis))
  }

  test("decideLeiosCertify: the slots are subtracted as Word64s, so a slot before wraps") {
    assertEquals(decide(slot - 1), Right(CertifyDecision.Certify(cert, point)))
  }

  property("tipAnnouncerFor: the tip's hash exactly when the last header announced the point") {
    forAll(Gen.oneOf(true, false), Gen.option(Gen.const(announcing))) { (announces, tipHash) =>
      assertEquals(
        LeiosVoting.tipAnnouncerFor(chainDep(announces), tipHash, point),
        Option.when(announces)(tipHash).flatten
      )
      assertEquals(
        LeiosVoting.tipAnnouncerFor(
          chainDep(announces),
          tipHash,
          point.copy(slot = Word64.fromUnsigned(slot + 1))
        ),
        None
      )
    }
  }

  test("seats: every key its smallest seat, in the keys' order") {
    assertEquals(
      LeiosVoting.seats(Vector(voting(1), voting(2), voting(0)), tip).map(_._2),
      Vector(seat(1), seat(0))
    )
    assertEquals(NonEmptyVector.fromVector(LeiosVoting.seats(Vector(voting(2)), tip)), None)
  }
