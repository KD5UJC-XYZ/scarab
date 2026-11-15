// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Stateful, Tell}
import scala.collection.immutable.SortedMap
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

/** The governor loop ranks a hot demotion by the churn mode in its cell when it decides, as
  * `simpleChurnModePeerSelectionPolicy` reads `churnModeVar` (`Cardano/Network/Diffusion/
  * Policies.hs` 33-51 at cardano-diffusion c5117705f6d5f8ea57ce3e772b6f9c35bfdb9167). The cell is
  * read as `PeerNetwork` reads the one the churn governor writes.
  */
class HotDemotionModeSuite extends FailsOnUncaught:
  private val targets =
    Targets.validated(0, 150, 10, 1, 0, 0, 0).toOption.getOrElse(fail("targets"))

  /** Three hot peers above an active target of one: the peers of the first hot demotion and the
    * scores it traced, with `mode` in the cell.
    */
  private def firstDemotion(mode: ChurnMode): IO[(Set[Int], Map[Int, Option[Long]])] =
    TestControl.executeEmbed(Supervisor[IO](await = false).use { supervisor =>
      val initial = GovernorState
        .initial[Int](1L, LocalRoots.empty, targets, Sensitivity.initial(false))
        .copy(
          targets = targets,
          peers = SortedMap.from(
            (1 to 3).map(_ -> PeerRecord.cold(Origin.Public).copy(status = Status.Hot))
          )
        )
      for
        state <- Ref.of[IO, GovernorState[Int]](initial)
        cell <- Ref.of[IO, ChurnMode](mode)
        events <- Queue.unbounded[IO, Event[Int]]
        demoted <- Deferred[IO, (Set[Int], Map[Int, Option[Long]])]
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] =
          Ask.const[IO, PeerMetrics[Int]](PeerMetricsSuite.disagreeing)
        given Ask[IO, ChurnMode] = RefStateful.reader(cell)
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(out: Output[Int]) = out match
            case Output.Traced(Trace.Demote(Temperature.Hot, peers, false, _, _, scores)) =>
              demoted.complete(peers.toSet -> scores).void
            case _ => IO.unit
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
          def deactivate(peer: Int) = IO.pure(Right(PeerStateActions.Deactivation.Warm))
          def close(peer: Int) =
            IO.pure(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released)))
          def closeIfGeneration(peer: Int, generation: BigInt) =
            IO.pure(Some(Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released))))
        roots = new RootProvider[IO, Int]:
          def request(max: Int, bootstrap: Boolean) = IO.pure(None)
          def requestBigLedger(max: Int) = IO.pure(None)
        run = Handle
          .allow[GovernorError](PeerGovernor.run(actions, roots, events, supervisor).void)
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        result <- run.background.use(_ => demoted.get)
      yield result
    })

  test("in deadline mode the loop demotes by upstreamyness plus fetchynessBlocks") {
    firstDemotion(ChurnMode.Praos(FetchMode.Deadline)).map(
      assertEquals(_, (Set(2, 3), Map(1 -> Some(5L), 2 -> Some(1L), 3 -> Some(1L))))
    )
  }

  test("in bulk sync and in Genesis the loop demotes by fetchynessBytes") {
    val bytes = (Set(1, 3), Map(1 -> Some(300L), 2 -> Some(5000L), 3 -> Some(0L)))
    for
      bulk <- firstDemotion(ChurnMode.Praos(FetchMode.BulkSync))
      genesis <- firstDemotion(ChurnMode.Genesis)
    yield
      assertEquals(bulk, bytes)
      assertEquals(genesis, bytes)
  }
