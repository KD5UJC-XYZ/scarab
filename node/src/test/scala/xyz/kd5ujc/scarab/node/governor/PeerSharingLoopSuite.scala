// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{
  GovernorConfig,
  Output,
  PeerGovernor,
  PeerNetwork,
  PeerSharingActions,
  PeerStateActions,
  RefStateful,
  RootProvider
}
import xyz.kd5ujc.scarab.protocol.{ConnectionState, PeerSharing}

/** The real effect loop on deterministic virtual time, including both asynchronous phases. */
class PeerSharingLoopSuite extends FailsOnUncaught:
  private final case class Recording(
      state: GovernorState[Int],
      requests: Vector[(Time, Int)],
      results: Vector[(Time, List[Int])],
      failures: Vector[GovernorError],
      maxInFlight: Int
  )

  private def run(horizon: FiniteDuration, knownTarget: Int = 6)(
      answer: (Int, PeerSharing.Amount) => IO[PeerShare.Result[Int]]
  ): IO[Recording] = Supervisor[IO](await = false).use { supervisor =>
    val targets =
      Targets.validated(0, knownTarget, 2, 2, 0, 0, 0).toOption.getOrElse(fail("targets"))
    val initial = GovernorState
      .initial[Int](1L, LocalRoots.empty, targets, Sensitivity.initial(false))
      .copy(
        targets = targets,
        peers = SortedMap.from(
          (1 to 2).map(peer =>
            peer -> PeerRecord
              .cold(Origin.Other)
              .copy(
                status = Status.Hot,
                everConnected = true,
                sharing = Some(true),
                shareAt = Some(300.seconds)
              )
          )
        ),
        sharing = PeerShare.Governor(enabled = true)
      )
    for
      events <- Queue.unbounded[IO, Event[Int]]
      state <- Ref.of[IO, GovernorState[Int]](initial)
      requests <- Ref.of[IO, Vector[(Time, Int)]](Vector.empty)
      results <- Ref.of[IO, Vector[(Time, List[Int])]](Vector.empty)
      failures <- Ref.of[IO, Vector[GovernorError]](Vector.empty)
      max <- Ref.of[IO, Int](0)
      given Stateful[IO, GovernorState[Int]] = RefStateful(state)
      given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
        GovernorConfig(association = AssociationMode.Unrestricted)
      )
      given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
      given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
        def functor = cats.Functor[IO]
        def tell(out: Output[Int]) = out match
          case Output.Traced(Trace.ShareResults(_, added)) =>
            IO.monotonic.flatMap(now => results.update(_ :+ (now -> added)))
          case _ => IO.unit
      actions = new PeerStateActions[IO, Int]:
        def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
          events.offer(Event.SharingNegotiated(peer, true)).as(Option.empty[FailureClass])
        def establishAt(
            peer: Int,
            mode: DiffusionMode,
            provenance: ConnectionState.Provenance,
            generation: BigInt
        ) =
          generation match
            case _ => establish(peer, mode, provenance)
        def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
          IO.pure(Option.empty[FailureClass])
        def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
        def close(peer: Int) = IO.pure(true)
        def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(Some(true))
      roots = new RootProvider[IO, Int]:
        def request(max: Int, bootstrap: Boolean) = IO.pure(None)
        def requestBigLedger(max: Int) = IO.pure(None)
      sharing = new PeerSharingActions[IO, Int]:
        def inbound = IO.pure(Map.empty[Int, Boolean])
        def publish(st: GovernorState[Int]) = max.update(_.max(st.sharing.inFlight))
        def request(peer: Int, amount: PeerSharing.Amount) =
          IO.monotonic.flatMap(now => requests.update(_ :+ (now -> peer))) *> answer(peer, amount)
      runner = Handle
        .allow[GovernorError] {
          PeerGovernor.run(actions, roots, events, supervisor, Some(sharing)).void
        }
        .rescue(error => failures.update(_ :+ error))
      recording <- runner.background.use { _ =>
        IO.sleep(horizon) *> (state.get, requests.get, results.get, failures.get, max.get).mapN(
          Recording.apply
        )
      }
    yield recording
  }

  test("the roots installation callback runs after the owner state reaches its generation") {
    Supervisor[IO](await = false).use { supervisor =>
      val initial =
        GovernorState.initial[Int](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))
      for
        events <- Queue.unbounded[IO, Event[Int]]
        state <- Ref.of[IO, GovernorState[Int]](initial)
        applied <- Deferred[IO, BigInt]
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(Option.empty[FailureClass])
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              generation: BigInt
          ) =
            generation match
              case _ => establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Option.empty[FailureClass])
          def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
          def close(peer: Int) = IO.pure(true)
          def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(Some(true))
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        onEventApplied = (
            (event: Event[Int]) =>
              event match {
                case Event.RootsConfigured(_, _, _, Some(generation)) =>
                  state.get.flatMap { current =>
                    if current.rootsGeneration >= generation then
                      applied.complete(current.rootsGeneration).void
                    else
                      IO.raiseError(
                        new AssertionError("roots callback preceded state installation")
                      )
                  }
                case _ => IO.unit
              }
        )
        runner = Handle
          .allow[GovernorError] {
            PeerGovernor.run(
              actions,
              roots,
              events,
              supervisor,
              onEventApplied = Some(onEventApplied)
            )
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        fiber <- runner.start
        _ <- events.offer(
          Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(3)))
        )
        generation <- applied.get.timeout(1.second)
        current <- state.get
        _ <- fiber.cancel
      yield
        assertEquals(generation, BigInt(3))
        assertEquals(current.rootsGeneration, BigInt(3))
    }
  }

  test("a delayed install applies only the newest queued roots generation") {
    Supervisor[IO](await = false).use { supervisor =>
      val initial =
        GovernorState.initial[Int](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))
      for
        events <- Queue.unbounded[IO, Event[Int]]
        state <- Ref.of[IO, GovernorState[Int]](initial)
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        installed <- Deferred[IO, BigInt]
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(Option.empty[FailureClass])
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              generation: BigInt
          ) =
            generation match
              case _ => establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Option.empty[FailureClass])
          def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
          def close(peer: Int) = IO.pure(true)
          def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(Some(true))
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        before = (
            (event: Event[Int]) =>
              event match
                case Event.RootsConfigured(_, _, _, Some(generation)) if generation == 1 =>
                  entered.complete(()).void *> release.get
                case _ => IO.unit
        )
        after = (
            (event: Event[Int]) =>
              event match
                case Event.RootsConfigured(_, _, _, Some(generation)) if generation == 2 =>
                  installed.complete(generation).void
                case _ => IO.unit
        )
        runner = Handle
          .allow[GovernorError] {
            PeerGovernor.run(
              actions,
              roots,
              events,
              supervisor,
              beforeEventApplied = Some(before),
              onEventApplied = Some(after)
            )
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        fiber <- runner.start
        _ <- events.offer(
          Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(1)))
        )
        _ <- entered.get.timeout(1.second)
        _ <- events.offer(
          Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(2)))
        )
        _ <- release.complete(())
        generation <- installed.get.timeout(1.second)
        current <- state.get
        _ <- fiber.cancel
      yield
        assertEquals(generation, BigInt(2))
        assertEquals(current.rootsGeneration, BigInt(2))
    }
  }

  test("cancelling a blocked roots install leaves owner state unchanged") {
    Supervisor[IO](await = false).use { supervisor =>
      val initial =
        GovernorState.initial[Int](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))
      for
        events <- Queue.unbounded[IO, Event[Int]]
        state <- Ref.of[IO, GovernorState[Int]](initial)
        entered <- Deferred[IO, Unit]
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(Option.empty[FailureClass])
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              generation: BigInt
          ) =
            generation match
              case _ => establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Option.empty[FailureClass])
          def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
          def close(peer: Int) = IO.pure(true)
          def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(Some(true))
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        before = (
            (event: Event[Int]) =>
              event match
                case Event.RootsConfigured(_, _, _, Some(_)) =>
                  entered.complete(()).void *> IO.never
                case _ => IO.unit
        )
        runner = Handle
          .allow[GovernorError] {
            PeerGovernor.run(actions, roots, events, supervisor, beforeEventApplied = Some(before))
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        fiber <- runner.start
        _ <- events.offer(
          Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(1)))
        )
        _ <- entered.get.timeout(1.second)
        _ <- fiber.cancel
        current <- state.get
      yield assertEquals(current.rootsGeneration, BigInt(0))
    }
  }

  test("a superseding generation cancels its snapshot wait and only the new check publishes") {
    for
      oldResult <- Deferred[IO, Boolean]
      oldCanceled <- Deferred[IO, Unit]
      currentGeneration <- Ref.of[IO, BigInt](1)
      traces <- Ref.of[IO, Vector[(BigInt, Boolean)]](Vector.empty)
      events <- Queue.unbounded[IO, Event[Int]]
      oldCheck <- PeerNetwork
        .runSnapshotCheck(
          oldResult.get,
          oldCanceled.get,
          BigInt(1),
          events
        )
        .start
      _ <- oldCanceled.complete(()).void
      _ <- oldCheck.joinWithNever
      _ <- oldResult.complete(true)
      _ <- events.offer(
        Event.RootsConfigured(LocalRoots.empty, false, Set.empty, Some(BigInt(2)))
      )
      newResult <- Deferred[IO, Boolean]
      newCanceled <- Deferred[IO, Unit]
      newCheck <- PeerNetwork
        .runSnapshotCheck(newResult.get, newCanceled.get, BigInt(1), events)
        .start
      _ <- newResult.complete(true)
      _ <- newCheck.joinWithNever
      installed <- events.take
      _ <- IO {
        installed match
          case Event.RootsConfigured(_, _, _, Some(generation)) =>
            assertEquals(generation, BigInt(2))
          case _ => fail("expected the queued roots reload before the delayed snapshot verdict")
      }
      _ <- currentGeneration.set(2)
      staleVerdict <- events.take
      _ <- PeerNetwork.publishSnapshotVerification(
        staleVerdict,
        currentGeneration.get,
        matched => traces.update(_ :+ (BigInt(1) -> matched))
      )
      currentResult <- Deferred[IO, Boolean]
      currentCanceled <- Deferred[IO, Unit]
      currentCheck <- PeerNetwork
        .runSnapshotCheck(currentResult.get, currentCanceled.get, BigInt(2), events)
        .start
      _ <- currentResult.complete(true)
      _ <- currentCheck.joinWithNever
      currentVerdict <- events.take
      _ <- PeerNetwork.publishSnapshotVerification(
        currentVerdict,
        currentGeneration.get,
        matched => traces.update(_ :+ (BigInt(2) -> matched))
      )
      actual <- traces.get
    yield assertEquals(actual, Vector(BigInt(2) -> true))
  }

  test("a stale close for an address leaves its newer-generation link open") {
    Supervisor[IO](await = false).use { supervisor =>
      val initial = GovernorState
        .initial[Int](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))
        .copy(rootsGeneration = BigInt(1))
      for
        events <- Queue.unbounded[IO, Event[Int]]
        state <- Ref.of[IO, GovernorState[Int]](initial)
        liveLinkGeneration <- Ref.of[IO, Option[BigInt]](Some(BigInt(1)))
        closeCalls <- Ref.of[IO, Vector[Int]](Vector.empty)
        checked <- Deferred[IO, BigInt]
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(Option.empty[FailureClass])
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              generation: BigInt
          ) =
            liveLinkGeneration.set(Some(generation)) *> establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Option.empty[FailureClass])
          def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
          def close(peer: Int) = closeCalls.update(_ :+ peer).as(true)
          def closeIfGeneration(peer: Int, generation: BigInt) =
            checked.complete(generation).void *>
              liveLinkGeneration.modify { current =>
                if current.contains(generation) then (None, Some(true)) else (current, None)
              }
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        runner = Handle
          .allow[GovernorError] {
            PeerGovernor.run(actions, roots, events, supervisor)
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        fiber <- runner.start
        _ <- events.offer(Event.Completed(Outcome.Connected(7, BigInt(0))))
        generation <- checked.get.timeout(1.second)
        calls <- closeCalls.get
        currentLink <- liveLinkGeneration.get
        _ <- fiber.cancel
      yield
        assertEquals(generation, BigInt(0))
        assertEquals(calls, Vector.empty)
        assertEquals(currentLink, Some(BigInt(1)))
    }
  }

  test("phase1 publishes the fast reply at3s, phase2 at4s uses only the remaining objective") {
    TestControl
      .executeEmbed(run(305.seconds) { (peer, _) =>
        if peer == 1 then IO.sleep(100.millis).as(PeerShare.Result.Peers(Vector(10, 11, 12)))
        else IO.sleep(4.seconds).as(PeerShare.Result.Peers(Vector(13, 14)))
      })
      .map { result =>
        assertEquals(result.requests.map(_._1).toSet, Set(300.seconds))
        assertEquals(result.results.map(_._1), Vector(303.seconds, 304.seconds))
        assertEquals(result.results.map(_._2.size), Vector(3, 1))
        assertEquals(result.state.peers.size, 6)
        assertEquals(result.state.sharing.inFlight, 0)
        assertEquals(result.maxInFlight, 2)
        assertEquals(result.failures, Vector.empty)
      }
  }

  test(
    "10s overall timeout releases the logical request and a bounded late wire reply cannot add peers"
  ) {
    TestControl
      .executeEmbed(run(320.seconds) { (peer, _) =>
        if peer == 1 then IO.pure(PeerShare.Result.Peers(Vector(10)))
        else IO.sleep(15.seconds).as(PeerShare.Result.Peers(Vector(13, 14)))
      })
      .map { result =>
        assertEquals(result.results.map(_._1), Vector(303.seconds, 310.seconds))
        assertEquals(result.state.peers.keySet, Set(1, 2, 10))
        assertEquals(result.state.peers(2).shareAt, Some(3910.seconds))
        assertEquals(result.state.sharing.inFlight, 0)
        assertEquals(result.failures, Vector.empty)
      }
  }

  test(
    "one-hour deterministic governor run uses results and is never chatty:900s per peer,2inflight"
  ) {
    def simulation = TestControl.executeEmbed(
      run(1.hour, knownTarget = 100) { (_, _) =>
        IO.sleep(100.millis).as(PeerShare.Result.Peers(Vector(10, 11)))
      },
      seed = Some("MTM4")
    )
    (simulation, simulation).tupled.map { (a, b) =>
      assertEquals(a, b)
      assertEquals(a.state.peers.keySet, Set(1, 2, 10, 11))
      assertEquals(a.maxInFlight, 2)
      assertEquals(a.failures, Vector.empty)
      assertEquals(a.requests.size, 8)
      a.requests.groupMap(_._2)(_._1).values.foreach { times =>
        assertEquals(times, Vector(300.seconds, 1200.seconds, 2100.seconds, 3000.seconds))
      }
      assertEquals(GovernorState.invariant(a.state), Nil)
    }
  }
