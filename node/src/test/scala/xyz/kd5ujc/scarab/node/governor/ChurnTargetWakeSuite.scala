// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Stateful, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{
  ChurnTargets,
  GovernorConfig,
  Output,
  PeerGovernor,
  PeerStateActions,
  RefStateful,
  RootProvider
}
import xyz.kd5ujc.scarab.protocol.ConnectionState

/** Multiple writes precede governor consumption, as they can when churn's counters already satisfy
  * successive proposals. A target wake observes the TVar, not its old queued snapshot.
  */
class ChurnTargetWakeSuite extends FailsOnUncaught:
  private def observe(latest: Churn.Proposal, generation: BigInt, events0: List[Event[Int]]) =
    TestControl.executeEmbed(Supervisor[IO](await = false).use { supervisor =>
      for
        state <- Ref.of[IO, GovernorState[Int]](
          GovernorState.initial[Int](1L, LocalRoots.empty, Targets.zero, Sensitivity.initial(false))
        )
        initial <- state.get
        proposals <- ChurnTargets
          .create[IO](initial.view(Duration.Zero).counters, Churn.Proposal.from(one))
        events <- Queue.unbounded[IO, Event[Int]]
        complete <- Deferred[IO, Targets]
        _ <- events0.traverse_(events.offer)
        _ <- proposals.change(generation)(_ => latest)
        _ <- events.offer(Event.Wake(Duration.Zero))
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(out: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: ConnectionState.Provenance) =
            IO.pure(Option.empty[FailureClass])
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: ConnectionState.Provenance,
              generation: BigInt
          ) = establish(peer, mode, provenance)
          def activate(peer: Int, trust: xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust) =
            IO.pure(Option.empty[FailureClass])
          def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
          def close(peer: Int) = IO.pure(true)
          def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(Some(true))
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        run = Handle
          .allow[GovernorError] {
            PeerGovernor
              .run(
                actions,
                roots,
                events,
                supervisor,
                readRequestedTargets = Some(proposals.accepted),
                onEventApplied = Some {
                  case Event.Wake(_) =>
                    state.get.flatMap(st => complete.complete(st.requested).void)
                  case _ => IO.unit
                }
              )
              .void
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        result <- run.background.use(_ => complete.get)
      yield result
    })

  private val one = Targets.zero.revised(known = 1).toOption.getOrElse(fail("one known target"))
  private val two = Targets.zero.revised(known = 2).toOption.getOrElse(fail("two known targets"))

  test("an invalid latest proposal discards a previously queued valid wake") {
    val invalid = Churn.Proposal.from(one).copy(active = -1)
    observe(invalid, BigInt(0), List(Event.ChurnTargetsChanged(BigInt(0)))).map(result =>
      assertEquals(result, Targets.zero)
    )
  }

  test("superseded valid wakes all read the latest valid proposal") {
    observe(Churn.Proposal.from(two), BigInt(0), List.fill(2)(Event.ChurnTargetsChanged(BigInt(0))))
      .map(result => assertEquals(result, two))
  }

  test("an old generation wake cannot acquire a newer generation proposal") {
    observe(Churn.Proposal.from(two), BigInt(1), List(Event.ChurnTargetsChanged(BigInt(0)))).map(
      result => assertEquals(result, Targets.zero)
    )
  }

  test("direct target snapshots retain their existing generation semantics") {
    observe(Churn.Proposal.from(two), BigInt(0), List(Event.TargetsRequested(one, Some(BigInt(0)))))
      .map(result => assertEquals(result, one))
  }

  test("counter publication and proposal modification preserve the same atomic snapshot") {
    for
      state <- ChurnTargets.create[IO](
        GovernorState
          .initial[Int](1L, LocalRoots.empty, Targets.zero, Sensitivity.initial(false))
          .view(Duration.Zero)
          .counters,
        Churn.Proposal.from(Targets.zero)
      )
      initial <- state.counters
      result <- IO.both(
        state.publish(initial.copy(known = 100)),
        state.change(BigInt(0))(_.copy(known = 1))
      )
      counters <- state.counters
      requested <- state.requested
    yield
      assertEquals(counters.known, 100)
      assertEquals(requested.known, 1)
      assert(Set(0, 100)(result._2._1.known))
      assertEquals(result._2._2.known, 1)
  }
