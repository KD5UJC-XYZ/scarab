// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import xyz.kd5ujc.scarab.node.assertions.{Assertions, Collecting, Property}
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
      preceding: List[Event[Int]] = Nil,
      assertions: Assertions[IO] = Assertions.noop[IO]
  ) =
    given Assertions[IO] = assertions
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
        given Ask[IO, PeerGovernor.ChurnWritten] =
          Ask.const[IO, PeerGovernor.ChurnWritten](PeerGovernor.ChurnWritten.Initial)
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
              kind: IO[xyz.kd5ujc.scarab.network.OutboundLimit.Dial],
              generation: BigInt
          ) = establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Right(()))
          def deactivate(peer: Int): IO[PeerStateActions.Result[PeerStateActions.Deactivation]] =
            IO.pure(deactivationFailure.toLeft(PeerStateActions.Deactivation.Warm))
          def close(peer: Int) =
            IO.pure(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released)))
          def exempt(peer: Int) = IO.unit
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
                  false,
                  IO.pure(monitoring),
                  trust => actions.activate(peer, trust),
                  actions.deactivate(peer),
                  actions.close(peer),
                  IO.unit,
                  actions.exempt(peer)
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

  // #1032: the governor's commit asserts the demotion it traces, with its typed return reason
  test("a hot return whose chain no longer intersects is asserted where the governor commits it") {
    for
      collected <- Collecting.create
      (assertions, made) = collected
      observed <- observe(
        1,
        PeerStateActions.Monitoring(PeerStateActions.Status.Warm, Some(120.seconds)),
        Event.Observed(
          1,
          Observation.HotEnded(Some(120.seconds), Some(ReturnReason.NoMoreIntersection)),
          Some(BigInt(0)),
          Some(BigInt(1))
        ),
        assertions = assertions
      )
      asserted <- made
    yield
      assertEquals(observed._1, Some(Status.Warm(120.seconds)))
      // with targets of zero the governor may then close the warm peer, a commanded demotion
      assertEquals(
        asserted
          .filterNot(_.property == Property.Sometimes.PeerDemoted)
          .map(m => (m.property, m.condition)),
        Vector(
          Property.Sometimes.PeerDemotedAsynchronously -> true,
          Property.Sometimes.PeerNoLongerIntersects -> true
        )
      )
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

  // The connection a peer has when it becomes a local root is left out of the outbound limit. The
  // governor captures it when it decides, as it captures the connection of every other job: a
  // later connection to the peer, which the decision never saw, keeps its place however late the
  // exemption runs.
  test("a delayed exemption leaves out the connection it was decided for, not its replacement") {
    given Assertions[IO] = Assertions.noop[IO]
    def handle(identity: BigInt, exempted: IO[Unit]) =
      PeerStateActions.Connection[IO](
        identity,
        BigInt(0),
        false,
        IO.pure(PeerStateActions.Monitoring(PeerStateActions.Status.Warm, None)),
        _ => IO.pure(Right(())),
        IO.pure(Right(PeerStateActions.Deactivation.Warm)),
        IO.pure(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released))),
        IO.unit,
        exempted
      )
    Supervisor[IO](await = false).use { supervisor =>
      // a known target, so that the reload's local root is not clamped away
      val targets = Targets.validated(0, 10, 1, 0, 0, 0, 0).toOption.get
      val initial = GovernorState
        .initial[Int](1L, LocalRoots.empty, targets, Sensitivity.initial(false))
        .copy(
          targets = targets,
          peers =
            SortedMap(1 -> PeerRecord.cold(Origin.Other).copy(status = Status.Warm(Duration.Zero)))
        )
      val config = RootConfig(
        advertise = false,
        DiffusionMode.InitiatorAndResponder,
        ConnectionState.Provenance.Outbound,
        trustable = false
      )
      for
        state <- Ref.of[IO, GovernorState[Int]](initial)
        events <- Queue.unbounded[IO, Event[Int]]
        exempted <- Ref.of[IO, Vector[BigInt]](Vector.empty)
        late <- Deferred[IO, Unit]
        decided <- Deferred[IO, Unit]
        done <- Deferred[IO, Unit]
        // connection 1 now, connection 2 once the governor has decided
        current <- Ref.of[IO, BigInt](BigInt(1))
        connectionOf = (identity: BigInt) =>
          if identity == BigInt(1) then
            handle(identity, late.get *> exempted.update(_ :+ identity) *> done.complete(()).void)
          else handle(identity, exempted.update(_ :+ identity))
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Ask[IO, PeerGovernor.ChurnWritten] =
          Ask.const[IO, PeerGovernor.ChurnWritten](PeerGovernor.ChurnWritten.Initial)
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(out: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(ActionFixtures.established(this, peer))
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              kind: IO[xyz.kd5ujc.scarab.network.OutboundLimit.Dial],
              generation: BigInt
          ) = establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Right(()))
          def deactivate(peer: Int): IO[PeerStateActions.Result[PeerStateActions.Deactivation]] =
            IO.pure(Right(PeerStateActions.Deactivation.Warm))
          def close(peer: Int) =
            IO.pure(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released)))
          // by peer, looked up when the delayed job runs: what a governor that did not capture the
          // connection when it decided would do
          def exempt(peer: Int) =
            late.get *> current.get.flatMap(id =>
              exempted.update(_ :+ id) *> done.complete(()).void
            )
          def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(None)
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        connections = new PeerStateActions.Connections[IO, Int]:
          def monitorPeerConnection(peer: Int) = current.get.map(id => Some(connectionOf(id)))
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
                  // the decision is made: the peer's connection is replaced before the exemption
                  // runs
                  case Event.RootsConfigured(_, _, _, _) =>
                    current.set(BigInt(2)) *> decided.complete(()).void
                  case _ => IO.unit
                }
              )
              .void
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        outcome <- run.background.use { _ =>
          events.offer(
            Event.RootsConfigured(
              LocalRoots.fromGroups(List(RootGroupSpec(1, 1, List(1 -> config)))),
              false,
              Set.empty,
              Some(BigInt(1))
            )
          ) *> decided.get *> late.complete(()) *> done.get *> exempted.get
        }
        reclassified <- state.get
      yield
        assert(reclassified.localRoots.contains(1), "the peer is a local root")
        assertEquals(outcome, Vector(BigInt(1)), "only the connection decided for")
    }
  }
