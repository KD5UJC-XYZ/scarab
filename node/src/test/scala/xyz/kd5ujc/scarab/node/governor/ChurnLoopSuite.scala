// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import fs2.concurrent.SignallingRef
import cats.mtl.{Ask, Tell}
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{ChurnLoop, ChurnWorld}

/** The churn loop on virtual time against a world that answers like a governor that meets every
  * target at once (or like one that never meets the increases): the order of the steps, the targets
  * they ask for, the head start, the timeouts, the sleep between rounds and the reaction to a
  * change of regime.
  */
class ChurnLoopSuite extends CatsEffectSuite:

  private val empty: GovernorState.Counters = GovernorState
    .initial[Int](1L, LocalRoots.empty[Int], Targets.zero, Sensitivity.initial(false))
    .view(Duration.Zero)
    .counters

  /** What the loop did, with the virtual time it did it at. */
  private final case class Log(
      requests: Vector[(FiniteDuration, Targets)],
      traces: Vector[(FiniteDuration, Churn.Trace)]
  )

  /** The counters follow the targets at once, except that the increases stay short when `stuck`. */
  private def run(
      horizon: FiniteDuration,
      base: Targets = Targets.relay,
      mode: Ref[IO, FetchMode],
      stuck: Boolean = false,
      regime: FetchMode => Regime.Kind = Regime.kind(_, false),
      consensusMode: ConsensusMode = ConsensusMode.PraosMode,
      ledgerJudgement: LedgerStateJudgement = LedgerStateJudgement.YoungEnough,
      syncTargets: Targets = Targets.sync,
      intervals: Churn.Intervals = Churn.Intervals(),
      responseDelay: FiniteDuration = Duration.Zero,
      changingJudgement: Option[Ref[IO, LedgerStateJudgement]] = None
  ): IO[Log] =
    for
      requestedCell <- Ref.of[IO, Churn.Proposal](Churn.Proposal.from(Targets.zero))
      counterCell <- SignallingRef[IO, GovernorState.Counters](
        empty.copy(
          root = 60,
          known = 150,
          established = 30,
          active = 20,
          knownBigLedger = 15,
          establishedBigLedger = 10,
          activeBigLedger = 5
        )
      )
      responses <- Queue.unbounded[IO, Churn.Proposal]
      requests <- Ref.of[IO, Vector[(FiniteDuration, Targets)]](Vector.empty)
      traces <- Ref.of[IO, Vector[(FiniteDuration, Churn.Trace)]](Vector.empty)
      world = new ChurnWorld[IO]:
        def counters: IO[GovernorState.Counters] = counterCell.get
        def awaitCounters(
            predicate: GovernorState.Counters => Boolean
        ): IO[GovernorState.Counters] =
          counterCell.discrete
            .filter(predicate)
            .take(1)
            .compile
            .last
            .flatMap(_.fold(IO.never[GovernorState.Counters])(IO.pure))
        def localHot: IO[Int] = IO.pure(2)
        def fetchMode: IO[FetchMode] = mode.get
        def judgement: IO[LedgerStateJudgement] =
          changingJudgement.fold(IO.pure(ledgerJudgement))(_.get)
        def requested: IO[Churn.Proposal] = requestedCell.get
        def request(t: Churn.Proposal): IO[Unit] =
          for
            now <- IO.monotonic
            _ <- t.validated.traverse_(valid => requests.update(_ :+ (now -> valid)))
            _ <- requestedCell.set(t)
            _ <- responses.offer(t)
          yield ()
      given Ask[IO, ChurnLoop.Config] = Ask.const[IO, ChurnLoop.Config](
        ChurnLoop.Config(
          base,
          regime,
          consensusMode = consensusMode,
          syncTargets = syncTargets,
          intervals = intervals,
          seed = 5L
        )
      )
      given Tell[IO, Churn.Trace] = new Tell[IO, Churn.Trace]:
        def functor = cats.Functor[IO]
        def tell(l: Churn.Trace): IO[Unit] =
          IO.monotonic.flatMap(now => traces.update(_ :+ (now -> l)))
      responder = responses.take
        .flatMap(t =>
          IO.sleep(responseDelay) *> (
            // a governor that meets what it can at once: it follows every decrease, and the
            // increases too unless it is `stuck`
            counterCell.update { c =>
              def follow(now: Int, target: Int) = if target > now && stuck then now else target
              c.copy(
                root = follow(c.root, t.root),
                known = follow(c.known, t.known),
                established = follow(c.established, t.established),
                active = follow(c.active, t.active),
                knownBigLedger = follow(c.knownBigLedger, t.knownBig),
                establishedBigLedger = follow(c.establishedBigLedger, t.establishedBig),
                activeBigLedger = follow(c.activeBigLedger, t.activeBig)
              )
            }
          )
        )
        .foreverM
      _ <- IO.race(IO.race(ChurnLoop.run[IO](world), responder), IO.sleep(horizon))
      r <- requests.get
      t <- traces.get
    yield Log(r, t)

  private def embed[A](io: IO[A]): IO[A] = TestControl.executeEmbed(io)

  test("the head start: no root peers for three seconds, then the base targets") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      log <- run(10.seconds, mode = mode)
    yield
      assertEquals(log.requests.take(2).map(_._2.root), Vector(0, 60))
      assertEquals(log.requests.take(2).map(_._1), Vector(Duration.Zero, 3.seconds))
      assertEquals(
        log.requests.head._2,
        Churn.head(Churn.Context(Regime.Kind.Default, 2, Targets.relay))
      ))
  }

  test("Genesis churn uses sync targets while the ledger judgement is TooOld") {
    embed(
      for
        mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
        log <- run(
          4.seconds,
          mode = mode,
          consensusMode = ConsensusMode.GenesisMode,
          ledgerJudgement = LedgerStateJudgement.TooOld
        )
      yield assertEquals(
        log.requests.take(2).map(_._2),
        Vector(
          Churn.head(
            Churn.Context(Regime.Kind.Default, 2, Targets.sync, ConsensusMode.GenesisMode)
          ),
          Targets.sync
        )
      )
    )
  }

  test(
    "the first round comes after the interval, with the fuzz, and has the twelve steps in order"
  ) {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      log <- run(5000.seconds, mode = mode)
    yield
      val round = log.traces.map(_._2)
      assertEquals(
        round.collect { case Churn.Trace.Action(step, _, _) => step }.take(12).toList,
        Churn.round
      )
      val (waitedAt, waited) = log.traces.collectFirst { case (at, Churn.Trace.Wait(d)) =>
        at -> d
      }.get
      assertEquals(waitedAt, 3.seconds)
      // the interval less the three seconds of the head start, plus the fuzz (up to 600 s)
      assert(waited >= 3297.seconds && waited <= 3897.seconds, waited.toString)
      val began = log.traces.collectFirst { case (at, Churn.Trace.Mode(_)) => at }.get
      assertEquals(began, 3.seconds + waited))
  }

  test(
    "with big ledger peers a round has the twelve steps in the reference's order, each reached"
  ) {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      log <- run(5000.seconds, mode = mode)
    yield
      val steps = log.traces.map(_._2).collect { case Churn.Trace.Action(step, _, _) => step }
      assertEquals(steps.take(12).toList, Churn.round)
      assertEquals(steps.take(12).map(_.number).toList, (1 to 12).toList)
      assert(!log.traces.exists { case (_, Churn.Trace.Timeout(_, _, _)) => true; case _ => false })
      // the big ledger targets move: down by a fifth and back to the base
      val big = log.requests.map(_._2).drop(2).take(12)
      assertEquals(big.map(_.activeBig).take(2), Vector(4, 5))
      assertEquals(big.last.knownBig, 15))
  }

  test("the targets of each step are the ones of the arithmetic, one set at a time") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      // a short interval so that the round happens within the horizon
      log <- run(
        200.seconds,
        mode = mode,
        intervals = Churn.Intervals(10.seconds, Duration.Zero, 10.seconds, Duration.Zero)
      )
    yield
      // requests: head, steady, then one per step of the first round
      val asked = log.requests.drop(2).drop(6).take(6).map(_._2)
      val ctx = Churn.Context(Regime.Kind.Default, 2, Targets.relay)
      val expected = Churn.round
        .filterNot(_.bigLedger)
        .foldLeft((Vector.empty[Targets], Churn.steady(ctx))) { case ((out, current), step) =>
          // the ideal world follows every target at once: the active decrease achieves 4 (20 to
          // 16) and the established decrease 4 (30 to 26), which the next decreases take as minimum
          val achieved: Churn.Step => Int = {
            case Churn.Step.DecreaseActive      => 4
            case Churn.Step.DecreaseEstablished => 4
            case _                              => 0
          }
          val next = Churn.apply(step, ctx, current, achieved)
          (out :+ next, next)
        }
        ._1
      assertEquals(asked.map(_.active), expected.map(_.active))
      assertEquals(asked.map(_.established), expected.map(_.established))
      assertEquals(asked.map(_.known), expected.map(_.known))
      assertEquals(asked.head.active, Churn.decrease(20))
      assertEquals(asked(1).active, 20))
  }

  test("a step the node cannot meet times out after its timeout, and the round goes on") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      log <- run(
        400.seconds,
        mode = mode,
        stuck = true,
        intervals = Churn.Intervals(10.seconds, Duration.Zero, 10.seconds, Duration.Zero)
      )
    yield
      val timeouts = log.traces.collect { case (_, Churn.Trace.Timeout(step, took, _)) =>
        step -> took
      }
      // the increases never reach their targets: active 60 s, known 80 s, established 60 s
      assertEquals(
        timeouts.take(3).toList,
        List(
          Churn.Step.IncreaseActiveBig -> 60.seconds,
          Churn.Step.IncreaseKnownBig -> 80.seconds,
          Churn.Step.IncreaseEstablishedBig -> 60.seconds
        )
      )
      // and the decreases did not
      assert(log.traces.exists {
        case (_, Churn.Trace.Action(Churn.Step.DecreaseActiveBig, _, _)) => true; case _ => false
      }))
  }

  test("in bulk sync the sleep is the short interval") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
      log <- run(1000.seconds, mode = mode)
    yield
      val waits = log.traces.collect { case (_, Churn.Trace.Wait(d)) => d }
      assert(waits.head >= 897.seconds && waits.head <= 957.seconds, waits.head.toString))
  }

  test(
    "a regime change does not interrupt the native churn sleep"
  ) {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
      flip = IO.sleep(100.seconds) *> mode.set(FetchMode.Deadline)
      both <- IO.both(run(200.seconds, mode = mode, regime = Regime.kind(_, true)), flip)
    yield
      val log = both._1
      // in the bootstrap regime the active target is cut ...
      assertEquals(log.requests(1)._2.active, 3) // min(max 1 2 + 1, 20)
      assertEquals(log.requests.size, 2)
      assertEquals(log.requests(1)._2.active, 3))
  }

  test("counter notifications resume a step before the former polling interval") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      log <- run(
        20.seconds,
        mode = mode,
        intervals = Churn.Intervals(10.seconds, Duration.Zero, 10.seconds, Duration.Zero),
        responseDelay = 150.millis
      )
    yield
      val elapsed = log.traces.collectFirst {
        case (_, Churn.Trace.Action(Churn.Step.DecreaseActiveBig, took, _)) => took
      }
      assertEquals(elapsed, Some(150.millis)))
  }

  test("entering bootstrap sync during the head start retains the actual initial targets") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.Deadline)
      both <- IO.both(
        run(4.seconds, mode = mode, regime = Regime.kind(_, true)),
        IO.sleep(1.second) *> mode.set(FetchMode.BulkSync)
      )
    yield
      val requests = both._1.requests
      assertEquals(requests.map(_._2.active), Vector(20, 20))
      assertEquals(requests.map(_._2.root), Vector(0, 0)))
  }

  test("Genesis refreshes selected targets after startup judgement changes without Praos cuts") {
    embed(for
      mode <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
      judgement <- Ref.of[IO, LedgerStateJudgement](LedgerStateJudgement.TooOld)
      both <- IO.both(
        run(
          4.seconds,
          mode = mode,
          consensusMode = ConsensusMode.GenesisMode,
          changingJudgement = Some(judgement)
        ),
        IO.sleep(1.second) *> judgement.set(LedgerStateJudgement.YoungEnough)
      )
    yield assertEquals(both._1.requests.map(_._2), Vector(Targets.sync, Targets.relay)))
  }
