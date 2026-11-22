// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import org.scalacheck.Gen
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.leios.{
  AddVoteResult,
  EbClosureVerdict,
  LeiosNotVotedReason,
  LeiosVoteTiming,
  LeiosVotingDefect,
  LeiosVotingEnv,
  SelectionView,
  TipCommittee,
  VotingKey
}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, StateGen}
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, Committee, RbHash, Seat, SeatId, Vote, Weight}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.ledger.state.{SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.LeiosEbNotification

/** The voting thread (`runLeiosVoting` and `newVoteTimers`, ouroboros-consensus 2416493b
  * `LeiosVoting.hs:143-421`) on the virtual clock: a timer armed per acquired closure, fired when
  * the window opens and not before, a vote per seat our keys hold when the selection's tip
  * announces the block, none without keys, after the window or when the tip does not announce it; a
  * point armed twice fires once, and a second vote of a seat is refused as known; a vote cast
  * reaches the node's vote state, validated against the committee, and with a peer's vote assembles
  * the certificate. Keys are throwaway keys made here.
  */
class LeiosVotingRunSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private lazy val keys = Vector.tabulate(4)(i => key(800 + i))

  /** Four equal seats, a quorum of three of them. */
  private lazy val committee = TipCommittee(
    right(Committee.honoured(keys.map(k => Seat(Weight(Rational(1, 4)), Some(k._2))))),
    Weight(Rational(3, 4))
  )

  /** Our keys: the first two seats'. */
  private lazy val ours = keys.take(2).map(k => right(VotingKey.of(k._1)))

  private val announcing: RbHash = rbHash(31)
  private val ebHash: EbHash = EbHash.fromBytes(rbHash(32).bytes)
  private val slot = 100L
  private val point = EbPoint(Word64.fromUnsigned(slot), ebHash)

  /** One-second slots from the system start at the epoch; the window opens 3 s after the onset and
    * shuts 4 s later.
    */
  private val schedule = LeiosVoteSchedule[IO](
    s => IO.pure(Some(s.toLong.seconds)),
    IO.pure(LeiosVoteTiming(3.seconds, 4.seconds))
  )
  private val opens = 103.seconds

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

  private type Event = LeiosKernelEvent[String, Int]

  /** What a run leaves to look at: its traces, the closures it validated, and the vote state. */
  private final case class Run(
      traces: Ref[IO, Vector[Event]],
      validated: Ref[IO, Int],
      votes: LeiosVotes[IO],
      acquired: Queue[IO, LeiosEbNotification]
  ):
    def voted: IO[Vector[Vote]] =
      traces.get.map(_.collect { case LeiosKernelEvent.Voted(v, _) => v })

  private def created: IO[Run] =
    (
      Ref.of[IO, Vector[Event]](Vector.empty),
      Ref.of[IO, Int](0),
      LeiosVotes.create[IO](IO.pure(Right(Some(committee)))),
      Queue.unbounded[IO, LeiosEbNotification]
    ).mapN(Run.apply)

  private def env(run: Run, announces: Boolean): LeiosVotingEnv[IO, Unit, String, Int] =
    new LeiosVotingEnv[IO, Unit, String, Int]:
      def now: IO[FiniteDuration] = IO.realTime
      def selection: IO[Option[SelectionView[Unit]]] =
        IO.pure(Some(SelectionView(chainDep(announces), Some(announcing), Some(committee), ())))
      def validateClosure(p: EbPoint, ledger: Unit): IO[EbClosureVerdict[String, Int]] =
        run.validated.update(_ + 1).as(EbClosureVerdict.Valid)
      def addVote(vote: Vote): IO[AddVoteResult] =
        Handle
          .allow[LeiosVotesDefect](run.votes.add(vote))
          .rescue(e => IO.raiseError(new AssertionError(e.toString)))

  /** The thread over `run` with `keys`, the tip announcing the block or not. */
  private def thread(run: Run, keys: Vector[VotingKey], announces: Boolean = true): IO[Unit] =
    Handle
      .allow[LeiosVotingDefect](
        LeiosVotingRun.run[IO, Unit, String, Int](
          env(run, announces),
          schedule,
          keys,
          Resource.pure(run.acquired),
          e => run.traces.update(_ :+ e)
        )
      )
      .rescue(e => IO.raiseError(new AssertionError(e.toString)))

  private val closure = LeiosEbNotification.AcquiredEbTxs(point)

  test("a vote per seat when the window opens, not before, and the votes reach the vote state") {
    for
      run <- created
      _ <- run.acquired.offer(LeiosEbNotification.AcquiredEb(point, Word32.wrap(10)))
      _ <- run.acquired.offer(closure)
      control <- TestControl.execute(thread(run, ours))
      _ <- control.tick
      scheduled <- run.traces.get
      _ <- control.advanceAndTick(opens - 1.nano)
      early <- run.voted
      _ <- control.advanceAndTick(1.nano)
      voted <- run.voted
      validated <- run.validated.get
      voters <- run.votes.voters(announcing)
    yield
      // the body's notification arms nothing; the closure's arms one timer, at the window
      assertEquals(
        scheduled,
        Vector(LeiosKernelEvent.VoteScheduled(point, opens, opens + 4.seconds))
      )
      assertEquals(early, Vector.empty)
      assertEquals(
        voted.map(v => (v.voter.index, v.announcingRbHash)),
        Vector(0 -> announcing, 1 -> announcing)
      )
      assertEquals(validated, 1)
      assertEquals(voters.keySet.map(_.index), Set(0, 1))
  }

  test("a point armed twice fires once; armed again after its vote, each seat is known already") {
    for
      run <- created
      _ <- run.acquired.offer(closure) *> run.acquired.offer(closure)
      control <- TestControl.execute(thread(run, ours))
      _ <- control.tick *> control.advanceAndTick(opens)
      once <- run.voted
      _ <- run.acquired.offer(closure) *> control.tick
      traces <- run.traces.get
    yield
      assertEquals(once.size, 2)
      val last = traces.drop(traces.lastIndexWhere {
        case LeiosKernelEvent.VoteScheduled(_, _, _) => true
        case _                                       => false
      })
      last match
        case Vector(
              LeiosKernelEvent.VoteScheduled(_, _, _),
              LeiosKernelEvent.SeatNotVoted(_, _, AddVoteResult.AlreadyKnown),
              LeiosKernelEvent.SeatNotVoted(_, _, AddVoteResult.AlreadyKnown),
              LeiosKernelEvent.NotVoted(p, LeiosNotVotedReason.VoteRejected(answers))
            ) =>
          assertEquals((p, answers.length), (point, 2))
        case other => fail(s"not the refusals of known votes: $other")
  }

  test("a closure acquired after its window shut is not voted for: too late, never validated") {
    for
      run <- created
      control <- TestControl.execute(thread(run, ours))
      _ <- control.tick *> control.advanceAndTick(200.seconds)
      _ <- run.acquired.offer(closure) *> control.tick
      traces <- run.traces.get
      validated <- run.validated.get
    yield
      assertEquals(traces.last, LeiosKernelEvent.NotVoted(point, LeiosNotVotedReason.TooLate))
      assertEquals(validated, 0)
  }

  test("no vote when the selection's tip does not announce the block") {
    for
      run <- created
      _ <- run.acquired.offer(closure)
      control <- TestControl.execute(thread(run, ours, announces = false))
      _ <- control.tick *> control.advanceAndTick(opens)
      traces <- run.traces.get
      validated <- run.validated.get
    yield
      assertEquals(
        traces.last,
        LeiosKernelEvent.NotVoted(point, LeiosNotVotedReason.ChainTipDoesNotAnnounce)
      )
      assertEquals(validated, 0)
  }

  test("without voting keys the thread says it is disabled and ends, and nothing is voted") {
    for
      run <- created
      _ <- run.acquired.offer(closure)
      control <- TestControl.execute(thread(run, Vector.empty))
      _ <- control.tick *> control.advanceAndTick(opens)
      traces <- run.traces.get
      ended <- control.results
      voters <- run.votes.voters(announcing)
    yield
      assertEquals(traces, Vector(LeiosKernelEvent.VotingDisabled))
      assert(ended.exists(_.isSuccess), s"$ended")
      assertEquals(voters, Map.empty)
  }

  test("our votes and a peer's reach the quorum: the certificate verifies against the committee") {
    val peer = right(Vote.sign(keys(3)._1, right(SeatId.fromInt(3)), announcing))
    for
      run <- created
      _ <- Handle
        .allow[LeiosVotesDefect](run.votes.add(peer))
        .rescue(e => IO.raiseError(new AssertionError(e.toString)))
      _ <- run.acquired.offer(closure)
      control <- TestControl.execute(thread(run, ours))
      _ <- control.tick *> control.advanceAndTick(opens)
      traces <- run.traces.get
      cert <- run.votes.certificate(announcing)
    yield
      assertEquals(traces.last, LeiosKernelEvent.Certified(announcing))
      assertEquals(
        cert.map(Certificate.verify(committee.committee, committee.threshold, announcing, _)),
        Some(Right(Weight(Rational(3, 4))))
      )
  }
