// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import xyz.kd5ujc.scarab.node.assertions.Assertions
import cats.effect.{IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Stateful, Tell}
import fs2.concurrent.SignallingRef
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

/** The governor loop ranks a hot demotion by the churn mode the churn governor wrote, read with the
  * targets written with it, at each pass: `simpleChurnModePeerSelectionPolicy` reads `churnModeVar`
  * (`Cardano/Network/Diffusion/Policies.hs` 33-51 at cardano-diffusion
  * c5117705f6d5f8ea57ce3e772b6f9c35bfdb9167) in the governor's transaction, after its targets
  * monitor (`Governor.hs` 685-714 at ouroboros-network c45735a56c567fa977969173d18943bac6bb3821).
  * The metrics are [[PeerMetricsSuite.disagreeing]]: by blocks peer 2 goes before peer 1, by bytes
  * peer 1 before peer 2.
  */
class HotDemotionModeSuite extends FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private def active(n: Int): Targets =
    Targets.validated(0, 150, 10, n, 0, 0, 0).toOption.getOrElse(fail(s"targets active $n"))

  private val deadline = ChurnMode.Praos(FetchMode.Deadline)
  private val bulkSync = ChurnMode.Praos(FetchMode.BulkSync)

  /** The first ordinary hot demotion, or the first change of targets, whichever is traced first. */
  private enum Seen:
    case Demoted(peers: Set[Int], scores: Map[Int, Option[Long]])
    case TargetsChanged(targets: Targets)

  /** Three hot peers whose targets are `requested`, with `cell` as what the churn governor wrote;
    * `script` runs with the loop and gets the event queue and a wait for the first `n` demotions
    * and changes of targets traced. `applied` sees each event the loop applied.
    */
  private def running[R](
      requested: Targets,
      cell: Ref[IO, PeerGovernor.ChurnWritten],
      applied: Queue[IO, Event[Int]] => Event[Int] => IO[Unit] = _ => _ => IO.unit
  )(
      script: (Queue[IO, Event[Int]], Int => IO[Vector[Seen]]) => IO[R]
  ): IO[R] =
    TestControl.executeEmbed(Supervisor[IO](await = false).use { supervisor =>
      val initial = GovernorState
        .initial[Int](1L, LocalRoots.empty, requested, Sensitivity.initial(false))
        .copy(
          targets = requested,
          peers = SortedMap.from(
            (1 to 3).map(_ -> PeerRecord.cold(Origin.Public).copy(status = Status.Hot))
          )
        )
      for
        state <- Ref.of[IO, GovernorState[Int]](initial)
        events <- Queue.unbounded[IO, Event[Int]]
        seen <- SignallingRef[IO, Vector[Seen]](Vector.empty)
        given Stateful[IO, GovernorState[Int]] = RefStateful(state)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] =
          Ask.const[IO, PeerMetrics[Int]](PeerMetricsSuite.disagreeing)
        given Ask[IO, PeerGovernor.ChurnWritten] = RefStateful.reader(cell)
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(out: Output[Int]) =
            val noted = out match
              case Output.Traced(Trace.Demote(Temperature.Hot, peers, false, _, _, scores)) =>
                Some(Seen.Demoted(peers.toSet, scores))
              case Output.Traced(Trace.TargetsChanged(targets)) =>
                Some(Seen.TargetsChanged(targets))
              case _ => None
            noted.fold(IO.unit)(s => seen.update(_ :+ s))
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
          .allow[GovernorError](
            PeerGovernor
              .run(actions, roots, events, supervisor, onEventApplied = Some(applied(events)))
              .void
          )
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        traced = (n: Int) => seen.waitUntil(_.size >= n) *> seen.get
        result <- run.background.use(_ => script(events, traced))
      yield result
    })

  private def firstSeen(requested: Targets, written: PeerGovernor.ChurnWritten): IO[Seen] =
    Ref
      .of[IO, PeerGovernor.ChurnWritten](written)
      .flatMap(cell => running(requested, cell)((_, traced) => traced(1).map(_.head)))

  test("in deadline mode the loop demotes by upstreamyness plus fetchynessBlocks") {
    firstSeen(active(1), PeerGovernor.ChurnWritten(deadline, None)).map(
      assertEquals(_, Seen.Demoted(Set(2, 3), Map(1 -> Some(5L), 2 -> Some(1L), 3 -> Some(1L))))
    )
  }

  test("in bulk sync and in Genesis the loop demotes by fetchynessBytes") {
    val bytes = Seen.Demoted(Set(1, 3), Map(1 -> Some(300L), 2 -> Some(5000L), 3 -> Some(0L)))
    for
      bulk <- firstSeen(active(1), PeerGovernor.ChurnWritten(bulkSync, None))
      genesis <- firstSeen(active(1), PeerGovernor.ChurnWritten(ChurnMode.Genesis, None))
    yield
      assertEquals(bulk, bytes)
      assertEquals(genesis, bytes)
  }

  test("targets written with a mode are in force before a demotion ranks by that mode") {
    // the churn governor wrote bulk sync with an active target of three, and the event that
    // announces it has not arrived: the governor takes the targets first and demotes nobody, as
    // the targets monitor comes before the demotions, instead of demoting two by bytes against the
    // active target of one it had
    val written = PeerGovernor.ChurnWritten(
      bulkSync,
      Some(PeerGovernor.RequestedTargets(active(3), BigInt(0)))
    )
    firstSeen(active(1), written).map(assertEquals(_, Seen.TargetsChanged(active(3))))
  }

  test("each pass reads the mode again: a change between two demotions ranks the second") {
    val first = PeerGovernor.ChurnWritten(
      deadline,
      Some(PeerGovernor.RequestedTargets(active(2), BigInt(0)))
    )
    val second = PeerGovernor.ChurnWritten(
      bulkSync,
      Some(PeerGovernor.RequestedTargets(active(1), BigInt(0)))
    )
    for
      cell <- Ref.of[IO, PeerGovernor.ChurnWritten](first)
      seen <- running(active(2), cell) { (events, traced) =>
        // once the first demotion is traced the churn governor writes bulk sync with a lower
        // active target, and announces it
        traced(1) *> cell.set(second) *> events.offer(Event.ChurnTargetsChanged(BigInt(0))) *>
          traced(3)
      }
    // deadline: 3 scores 1 and joined before 2, which also scores 1; then bulk sync: by bytes 1
    // (300) goes before 2 (5000)
    yield assertEquals(
      seen.take(3),
      Vector(
        Seen.Demoted(Set(3), Map(1 -> Some(5L), 2 -> Some(1L), 3 -> Some(1L))),
        Seen.TargetsChanged(active(1)),
        Seen.Demoted(Set(1), Map(1 -> Some(300L), 2 -> Some(5000L)))
      )
    )
  }

  test("targets taken keep the mode written with them while a later write waits for its topology") {
    // the governor takes an active target of one written with bulk sync; its topology is then
    // reconfigured, and a write of the old generation (two active, bulk sync) arrives late: it is
    // not taken, and the demotion that the target of one asks for ranks by the bulk-sync bytes of
    // the targets in force, not by the deadline mode of the pass before
    val lower = active(1)
    val late = PeerGovernor.ChurnWritten(
      bulkSync,
      Some(PeerGovernor.RequestedTargets(active(2), BigInt(0)))
    )
    def applied(cell: Ref[IO, PeerGovernor.ChurnWritten])(
        events: Queue[IO, Event[Int]]
    ): Event[Int] => IO[Unit] = {
      case Event.TargetsRequested(`lower`, _) =>
        cell.set(late) *> events.offer(
          Event.RootsConfigured(LocalRoots.empty[Int], false, Set.empty[Int], Some(BigInt(1)))
        )
      case _ => IO.unit
    }
    for
      cell <- Ref.of[IO, PeerGovernor.ChurnWritten](
        PeerGovernor.ChurnWritten(
          deadline,
          Some(PeerGovernor.RequestedTargets(active(3), BigInt(0)))
        )
      )
      seen <- running(active(3), cell, applied(cell)) { (events, traced) =>
        cell.set(
          PeerGovernor.ChurnWritten(bulkSync, Some(PeerGovernor.RequestedTargets(lower, BigInt(0))))
        ) *> events.offer(Event.ChurnTargetsChanged(BigInt(0))) *> traced(2)
      }
    yield assertEquals(
      seen.take(2),
      Vector(
        Seen.TargetsChanged(lower),
        Seen.Demoted(Set(1, 3), Map(1 -> Some(300L), 2 -> Some(5000L), 3 -> Some(0L)))
      )
    )
  }
