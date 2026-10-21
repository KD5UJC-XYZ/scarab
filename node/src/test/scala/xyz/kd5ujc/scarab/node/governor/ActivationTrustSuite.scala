// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.{Ask, Handle, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{
  GovernorConfig,
  Output,
  PeerGovernor,
  PeerStateActions,
  RefStateful,
  RootProvider
}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

class ActivationTrustSuite extends FailsOnUncaught:
  test("a queued activation retains its selected local-root trust across a reload") {
    Supervisor[IO](await = false).use { supervisor =>
      val config = RootConfig(false, DiffusionMode.InitiatorOnly, Provenance.Outbound, true)
      val roots = LocalRoots.fromGroups(List(RootGroupSpec(1, 1, List(1 -> config))))
      val targets = Targets.validated(0, 1, 1, 1, 0, 0, 0).fold(e => fail(e.message), identity)
      val initial = GovernorState
        .initial[Int](1L, roots, targets, Sensitivity.initial(false))
        .copy(
          targets = targets,
          localRoots = roots,
          peers =
            SortedMap(1 -> PeerRecord.cold(Origin.Local).copy(status = Status.Warm(Duration.Zero)))
        )
      for
        state <- Ref.of[IO, GovernorState[Int]](initial)
        events <- Queue.unbounded[IO, Event[Int]]
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        reloaded <- Deferred[IO, Unit]
        observed <- Deferred[IO, PeerTrust]
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) = IO.unit
        actions = new PeerStateActions[IO, Int]:
          def establish(peer: Int, mode: DiffusionMode, provenance: Provenance) =
            IO.pure(Option.empty[FailureClass])
          def establishAt(
              peer: Int,
              mode: DiffusionMode,
              provenance: Provenance,
              generation: BigInt
          ) = establish(peer, mode, provenance)
          def activate(peer: Int, trust: PeerTrust) =
            entered.complete(()).void *> release.get *> observed
              .complete(trust)
              .as(Option.empty[FailureClass])
          def deactivate(peer: Int) = IO.pure(PeerStateActions.Deactivation.Warm)
          def close(peer: Int) = IO.pure(true)
          def closeIfGeneration(peer: Int, generation: BigInt) = IO.pure(Some(true))
        provider = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        runner = Handle
          .allow[GovernorError] {
            PeerGovernor
              .run(
                actions,
                provider,
                events,
                supervisor,
                onEventApplied = Some {
                  case Event.RootsConfigured(_, _, _, Some(generation))
                      if generation == BigInt(1) =>
                    reloaded.complete(()).void
                  case _ => IO.unit
                }
              )
              .void
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        trust <- runner.background.use { _ =>
          (for
            _ <- entered.get
            _ <- events.offer(
              Event.RootsConfigured(
                LocalRoots.fromGroups(
                  List(RootGroupSpec(1, 1, List(1 -> config.copy(trustable = false))))
                ),
                false,
                Set.empty,
                Some(BigInt(1))
              )
            )
            _ <- reloaded.get
            current <- state.get
            _ = assert(!current.localRoots.config(1).trustable)
            _ <- release.complete(())
            result <- observed.get
          yield result).guarantee(release.complete(()).void)
        }
      yield assertEquals(trust, PeerTrust.Trustable)
    }
  }
