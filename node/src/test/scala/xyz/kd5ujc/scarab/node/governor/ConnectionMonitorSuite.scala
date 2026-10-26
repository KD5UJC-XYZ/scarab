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
  PeerStateActions,
  RefStateful,
  RootProvider
}
import xyz.kd5ujc.scarab.protocol.ConnectionState

/** Queued notifications are wakes: the retained connection read supplies the current status. */
class ConnectionMonitorSuite extends FailsOnUncaught:
  private def observe(
      identity: BigInt,
      monitoring: PeerStateActions.Monitoring,
      event: Event[Int],
      deactivationFailure: Option[PeerStateActions.Failure] = None,
      initialStatus: Status = Status.Hot,
      preceding: List[Event[Int]] = Nil
  ) =
    TestControl.executeEmbed(Supervisor[IO](await = false).use { supervisor =>
      val initial = GovernorState
        .initial[Int](1L, LocalRoots.empty, Targets.zero, Sensitivity.initial(false))
        .copy(peers =
          SortedMap(
            1 -> PeerRecord.cold(Origin.Other).copy(status = initialStatus, everConnected = true)
          )
        )
      for
        state <- Ref.of[IO, GovernorState[Int]](initial)
        events <- Queue.unbounded[IO, Event[Int]]
        complete <- Deferred[IO, GovernorState[Int]]
        traces <- Ref.of[IO, Vector[Trace[Int]]](Vector.empty)
        _ <- preceding.traverse_(events.offer) *> events.offer(event) *> events.offer(
          Event.Wake(Duration.Zero)
        )
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(
            policy = Policy(
              warmReconnectFuzz = (Duration.Zero, Duration.Zero),
              coldReconnectFuzz = (Duration.Zero, Duration.Zero)
            ),
            association = AssociationMode.Unrestricted
          )
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(out: Output[Int]) = out match
            case Output.Traced(trace) => traces.update(_ :+ trace)
            case _                    => IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(ActionFixtures.established(this, peer))
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              generation: BigInt
          ) = establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Right(()))
          def deactivate(peer: Int): IO[PeerStateActions.Result[PeerStateActions.Deactivation]] =
            IO.pure(deactivationFailure.toLeft(PeerStateActions.Deactivation.Warm))
          def close(peer: Int) =
            IO.pure(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released)))
          def closeIfGeneration(peer: Int, generation: BigInt) =
            IO.pure(Some(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released))))
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        connections = new PeerStateActions.Connections[IO, Int]:
          def monitorPeerConnection(peer: Int) =
            IO.pure(
              Some(
                PeerStateActions.Connection(
                  identity,
                  BigInt(0),
                  IO.pure(monitoring),
                  trust => actions.activate(peer, trust),
                  actions.deactivate(peer),
                  actions.close(peer),
                  IO.unit
                )
              )
            )
          def retainConnections(known: Set[Int]) = IO.unit
        run = Handle
          .allow[GovernorError] {
            PeerGovernor
              .run(
                actions,
                roots,
                events,
                supervisor,
                connections = Some(connections),
                onEventApplied = Some {
                  case Event.Wake(_) if deactivationFailure.isEmpty =>
                    state.get.flatMap(complete.complete).void
                  case Event.Completed(Outcome.DeactivateFailed(_))
                      if deactivationFailure.nonEmpty =>
                    state.get.flatMap(complete.complete).void
                  case _ => IO.unit
                }
              )
              .void
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        result <- run.background.use(_ => complete.get)
        observed <- traces.get
      yield (result.peers.get(1).map(_.status), observed)
    })

  test("a notification from an older connection cannot demote its replacement") {
    observe(
      2,
      PeerStateActions.Monitoring(PeerStateActions.Status.Warm, Some(120.seconds)),
      Event.Observed(1, Observation.HotEnded(Some(10.seconds)), Some(BigInt(0)), Some(BigInt(1)))
    )
      .map { case (result, _) => assertEquals(result, Some(Status.Hot)) }
  }

  test("a queued hot return cannot demote an already reactivated handle") {
    observe(
      1,
      PeerStateActions.Monitoring(PeerStateActions.Status.Hot, None),
      Event.Observed(1, Observation.HotEnded(Some(10.seconds)), Some(BigInt(0)), Some(BigInt(1)))
    )
      .map { case (result, _) => assertEquals(result, Some(Status.Hot)) }
  }

  test("warm demotion uses the retained result's delay") {
    observe(
      1,
      PeerStateActions.Monitoring(PeerStateActions.Status.Warm, Some(120.seconds)),
      Event.Observed(1, Observation.HotEnded(Some(10.seconds)), Some(BigInt(0)), Some(BigInt(1)))
    )
      .map { case (result, _) => assertEquals(result, Some(Status.Warm(120.seconds))) }
  }

  test("a retained Cold read completes release even if the queued notification saw Cooling") {
    observe(
      1,
      PeerStateActions.Monitoring(PeerStateActions.Status.Cold, Some(180.seconds)),
      Event.Observed(
        1,
        Observation.Lost(FailureClass.Transient, 10.seconds, false, "closed"),
        Some(BigInt(0)),
        Some(BigInt(1))
      )
    )
      .map { case (result, _) => assertEquals(result, Some(Status.Cold(Some(180.seconds)))) }
  }

  test("a failed demotion preserves every protocol error at the governor boundary") {
    import xyz.kd5ujc.scarab.network.{NetworkError, PeerActionError, PeerOperation, ProtocolResults}
    val errors = cats.data.NonEmptyVector.of(
      ProtocolResults.MiniProtocolFailure(2, NetworkError.Timeout(2, "idle")),
      ProtocolResults.MiniProtocolFailure(
        3,
        NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
      )
    )
    val failure = PeerStateActions.Failure(
      PeerActionError.ApplicationFailures(errors),
      PeerStateActions.Release.Released
    )
    observe(
      1,
      PeerStateActions.Monitoring(PeerStateActions.Status.Hot, None),
      Event.Wake(Duration.Zero),
      Some(failure)
    )
      .map { (status, traces) =>
        assertEquals(status, Some(Status.Cold(None)))
        assertEquals(
          traces.collect { case Trace.PeerActionFailed(1, PeerOperation.Deactivate, error) =>
            error
          },
          Vector(failure.cause)
        )
      }
  }

  test("a completed command on an old connection cannot change its replacement") {
    observe(
      2,
      PeerStateActions.Monitoring(PeerStateActions.Status.Hot, None),
      Event.ConnectionCompleted(1, BigInt(1), Outcome.DeactivateFailed(1))
    ).map { case (result, _) => assertEquals(result, Some(Status.Hot)) }
  }

  test("loss consumed while Connecting is reconciled before accepting its retained handle") {
    val lost: Observation.Lost =
      Observation.Lost(FailureClass.Transient, 10.seconds, false, "controlled bearer loss")
    observe(
      1,
      PeerStateActions.Monitoring(PeerStateActions.Status.Cold, Some(10.seconds)),
      Event.ConnectionCompleted(1, BigInt(1), Outcome.Connected(1, BigInt(0))),
      initialStatus = Status.Connecting,
      preceding = List(
        Event.Observed(1, lost, Some(BigInt(0)), Some(BigInt(1))),
        Event.Observed(1, lost.copy(released = true), Some(BigInt(0)), Some(BigInt(1)))
      )
    ).map { (status, traces) =>
      assertEquals(status, Some(Status.Cold(Some(10.seconds))))
      assertEquals(
        traces.count {
          case Trace.DemoteAsynchronous(_, Temperature.Cold, _, _, _, _) => true
          case _                                                         => false
        },
        1
      )
    }
  }

  test("a misbehaving connection lost while Connecting is still forgotten at completion") {
    val original = xyz.kd5ujc.scarab.ledger.PeerError.Protocol(
      xyz.kd5ujc.scarab.network.NetworkError
        .IngressLimit(xyz.kd5ujc.scarab.network.Protocols.ChainSync, 65537L)
    )
    val lost: Observation.Lost =
      Observation.Lost(FailureClass.Misbehaved, 10.seconds, false, original.message)
    observe(
      1,
      PeerStateActions.Monitoring(PeerStateActions.Status.Cold, Some(10.seconds), Some(original)),
      Event.ConnectionCompleted(1, BigInt(1), Outcome.Connected(1, BigInt(0))),
      initialStatus = Status.Connecting,
      preceding = List(
        Event.Observed(1, lost, Some(BigInt(0)), Some(BigInt(1))),
        Event.Observed(1, lost.copy(released = true), Some(BigInt(0)), Some(BigInt(1)))
      )
    ).map { (status, traces) =>
      assertEquals(status, None)
      assert(traces.exists {
        case Trace.DemoteAsynchronous(
              1,
              Temperature.Cold,
              Some(FailureClass.Misbehaved),
              Some(reason),
              _,
              _
            ) =>
          reason == original.message
        case _ => false
      })
    }
  }
