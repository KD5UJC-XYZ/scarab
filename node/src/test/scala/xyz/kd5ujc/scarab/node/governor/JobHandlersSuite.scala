// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import xyz.kd5ujc.scarab.node.assertions.Assertions
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Supervisor}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, NetworkError, PeerActionError, PeerOperation}
import xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{
  EstablishmentError,
  GovernorConfig,
  Output,
  PeerGovernor,
  PeerSharingActions,
  PeerStateActions,
  RefStateful,
  RootProvider
}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance
import xyz.kd5ujc.scarab.protocol.PeerSharing

/** A job whose action raises ends with the completion its handler makes, as the reference's jobs do
  * (`Job job handler`, network-mux c45735a5 `Control/Concurrent/JobPool.hs` 45-49 and 111-113), so
  * the governor applies it and the peer leaves the state the job was started for (#1148). Each test
  * runs the governor on `TestControl`, starts one job of each kind from the state it is given, and
  * waits for the governor to apply that job's completion.
  */
class JobHandlersSuite extends FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]

  private final class Raised extends RuntimeException("the job raised")

  private val Peer = 1
  private val policy = Policy()

  /** The completion the governor applied, when it applied it, its state after it, and what it had
    * told by then.
    */
  private final case class Applied(
      outcome: Outcome[Int],
      at: FiniteDuration,
      state: GovernorState[Int],
      outputs: Vector[Output[Int]]
  ):
    def record: Option[PeerRecord] = state.peers.get(Peer)

  private def targets(
      root: Int,
      known: Int,
      established: Int,
      active: Int,
      knownBig: Int = 0
  ): Targets =
    Targets
      .validated(root, known, established, active, knownBig, 0, 0)
      .fold(problem => fail(problem.message), identity)

  private def state(targets: Targets, peers: (Int, PeerRecord)*): GovernorState[Int] =
    GovernorState
      .initial[Int](1L, LocalRoots.empty, targets, Sensitivity.initial(false))
      .copy(targets = targets, peers = SortedMap.from(peers))

  private val warm =
    PeerRecord.cold(Origin.Other).copy(status = Status.Warm(Duration.Zero), everConnected = true)
  private val hot = warm.copy(status = Status.Hot)

  /** Actions that block for ever, but for those a test gives. */
  private def actions(
      establishing: IO[Either[EstablishmentError, PeerStateActions.Connection[IO]]] = IO.never,
      activating: IO[Either[PeerActionError, Unit]] = IO.never,
      deactivating: IO[PeerStateActions.Result[PeerStateActions.Deactivation]] = IO.never,
      closing: IO[PeerStateActions.Result[PeerStateActions.CloseResult]] = IO.never
  ): PeerStateActions[IO, Int] = new PeerStateActions[IO, Int]:
    def establish(peer: Int, mode: DiffusionMode, provenance: Provenance) = establishing
    def establishAt(
        peer: Int,
        mode: DiffusionMode,
        provenance: Provenance,
        kind: IO[xyz.kd5ujc.scarab.network.OutboundLimit.Dial],
        generation: BigInt
    ) = establishing
    def activate(peer: Int, trust: PeerTrust) = activating
    def deactivate(peer: Int) = deactivating
    def exempt(peer: Int) = IO.unit
    def close(peer: Int) = closing
    def closeIfGeneration(peer: Int, generation: BigInt) = closing.map(Some(_))

  /** The peer's retained connection, which goes cold when `cold` completes. */
  private def connections(
      actions: PeerStateActions[IO, Int],
      cold: IO[Unit]
  ): PeerStateActions.Connections[IO, Int] = new PeerStateActions.Connections[IO, Int]:
    def monitorPeerConnection(peer: Int) =
      IO.pure(
        Option.when(peer == Peer)(
          PeerStateActions.Connection(
            BigInt(1),
            BigInt(0),
            false,
            IO.pure(PeerStateActions.Monitoring(PeerStateActions.Status.Warm, None)),
            trust => actions.activate(peer, trust),
            actions.deactivate(peer),
            actions.close(peer),
            cold,
            actions.exempt(peer)
          )
        )
      )
    def retainConnections(known: Set[Int]) = IO.unit

  private val noRoots = new RootProvider[IO, Int]:
    def request(max: Int, bootstrap: Boolean) = IO.never
    def requestBigLedger(max: Int) = IO.never

  /** Run the governor from `initial` until it applies a completion that `applies`, and give what it
    * did then. A completion that never comes fails at an hour of virtual time.
    *
    * @param connected
    *   Whether the governor watches retained connections, as the node's does; without, its jobs
    *   take the actions' legacy path.
    * @param refused
    *   The outputs whose telling raises, as a log that cannot be written does.
    */
  private def govern(
      initial: GovernorState[Int],
      actions: PeerStateActions[IO, Int],
      cold: IO[Unit] = IO.unit,
      roots: RootProvider[IO, Int] = noRoots,
      sharing: Option[PeerSharingActions[IO, Int]] = None,
      connected: Boolean = true,
      refused: Output[Int] => Boolean = _ => false
  )(applies: Outcome[Int] => Boolean): IO[Applied] =
    TestControl.executeEmbed(Supervisor[IO](await = false).use { supervisor =>
      for
        current <- Ref.of[IO, GovernorState[Int]](initial)
        events <- Queue.unbounded[IO, Event[Int]]
        told <- Ref.of[IO, Vector[Output[Int]]](Vector.empty)
        applied <- Deferred[IO, Applied]
        given Stateful[IO, GovernorState[Int]] = RefStateful(current)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(policy = policy, association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Ask[IO, PeerGovernor.ChurnWritten] =
          Ask.const[IO, PeerGovernor.ChurnWritten](PeerGovernor.ChurnWritten.Initial)
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) =
            told.update(_ :+ output) *> IO.raiseError(new Raised).whenA(refused(output))
        run = Handle
          .allow[GovernorError] {
            PeerGovernor
              .run(
                actions,
                roots,
                events,
                supervisor,
                sharing,
                onEventApplied = Some {
                  case Event.Completed(outcome) if applies(outcome) =>
                    (IO.monotonic, current.get, told.get).flatMapN((at, st, outputs) =>
                      applied.complete(Applied(outcome, at, st, outputs)).void
                    )
                  case _ => IO.unit
                },
                connections = Option.when(connected)(connections(actions, cold))
              )
              .void
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        result <- run.background.use(_ => applied.get.timeout(1.hour))
      yield result
    })

  private def retryIn(applied: Applied): Option[FiniteDuration] = applied.record.map(_.status) match
    case Some(Status.Cold(Some(retryAt))) => Some(retryAt - applied.at)
    case _                                => None

  // `promoteColdPeer`'s handler (EstablishedPeers.hs 505-568 at c45735a5): the failure is
  // recorded with the cold back-off, 5 s with a fuzz of up to 2 s either way at the first failure.
  // The exception is the node's own, so it counts nothing against the peer (issue 567)
  test("a connect that raises leaves the peer cold with its back-off, not connecting") {
    val raised = new Raised
    govern(
      state(targets(0, 1, 1, 0), Peer -> PeerRecord.cold(Origin.Other)),
      actions(establishing = IO.raiseError(raised))
    ) {
      case Outcome.ConnectFailed(Peer, _, _) => true
      case _                                 => false
    }.map { applied =>
      assertEquals(
        applied.outcome,
        Outcome.ConnectFailed(Peer, FailureClass.NotThePeers, BigInt(0))
      )
      val wait = retryIn(applied)
      assert(
        wait.exists(w => w >= 3.seconds && w <= 7.seconds),
        s"the peer should be cold for the base back-off, was ${applied.record.map(_.status)}"
      )
      assertEquals(applied.record.map(_.failCount), Some(0))
      assert(
        applied.outputs.contains(
          Output.EstablishmentFailed(Peer, EstablishmentError.Defect(raised))
        ),
        "the exception should be reported as an establishment defect"
      )
    }
  }

  // the path of #1148: the report of a failed connect raises, before the job offers its completion
  test("a failed connect whose report raises still completes, leaving the peer cold") {
    govern(
      state(targets(0, 1, 1, 0), Peer -> PeerRecord.cold(Origin.Other)),
      actions(establishing = IO.pure(Left(ActionFixtures.unavailable))),
      refused = {
        case Output.EstablishmentFailed(_, _) => true
        case _                                => false
      }
    ) {
      case Outcome.ConnectFailed(Peer, _, _) => true
      case _                                 => false
    }.map { applied =>
      assertEquals(
        applied.outcome,
        Outcome.ConnectFailed(Peer, FailureClass.NotThePeers, BigInt(0))
      )
      assert(retryIn(applied).nonEmpty, s"the peer should be cold, was ${applied.record}")
    }
  }

  // `promoteWarmPeer`'s handler (ActivePeers.hs 460-558 at c45735a5): the pause of one second,
  // then the peer leaves the established set, cold for the error delay of 10 s with a fuzz of up
  // to 2 s
  test("an activation that raises leaves the peer cold after the pause, not activating") {
    val raised = new Raised
    govern(state(targets(0, 1, 1, 1), Peer -> warm), actions(activating = IO.raiseError(raised))) {
      case Outcome.ActivateFailed(Peer, _) => true
      case _                               => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.ActivateFailed(Peer, FailureClass.NotThePeers))
      assert(applied.at >= 1.second, s"the failure should follow the pause, came at ${applied.at}")
      val wait = retryIn(applied)
      assert(
        wait.exists(w => w >= 8.seconds && w <= 12.seconds),
        s"the peer should be cold for the error delay, was ${applied.record.map(_.status)}"
      )
      assertEquals(applied.record.map(_.failCount), Some(0))
      assert(
        applied.outputs.contains(
          Output.Traced(
            Trace.PeerActionFailed(
              Peer,
              PeerOperation.Activate,
              PeerActionError.ConnectionFailure(NetworkError.Defect(raised))
            )
          )
        ),
        "the exception should be reported as the activation's defect"
      )
    }
  }

  // `demoteActivePeer`'s handler (ActivePeers.hs 980-1039 at c45735a5): the connection seen cold,
  // the peer is neither hot nor established, and tepid
  test("a deactivation that raises leaves the peer cold once its connection is released") {
    val raised = new Raised
    govern(state(targets(0, 1, 1, 0), Peer -> hot), actions(deactivating = IO.raiseError(raised))) {
      case Outcome.DeactivateFailed(Peer) | Outcome.CloseTimedOut(Peer) => true
      case _                                                            => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.DeactivateFailed(Peer))
      assertEquals(applied.record.map(_.status), Some(Status.Cold(None)))
      assertEquals(applied.record.map(_.tepid), Some(true))
      assert(
        applied.outputs.contains(
          Output.Traced(
            Trace.PeerActionFailed(
              Peer,
              PeerOperation.Deactivate,
              PeerActionError.ConnectionFailure(NetworkError.Defect(raised))
            )
          )
        ),
        "the exception should be reported as the deactivation's defect"
      )
    }
  }

  // the same handler when the connection is not seen cold within the demotion timeout: the peer
  // stays, cooling
  test("a deactivation that raises leaves the peer cooling when its connection lingers") {
    govern(
      state(targets(0, 1, 1, 0), Peer -> hot),
      actions(deactivating = IO.raiseError(new Raised)),
      cold = IO.never
    ) {
      case Outcome.DeactivateFailed(Peer) | Outcome.CloseTimedOut(Peer) => true
      case _                                                            => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.CloseTimedOut(Peer))
      assert(applied.at >= policy.demotionTimeout, s"came before the timeout, at ${applied.at}")
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  // `demoteEstablishedPeer`'s handler (EstablishedPeers.hs 909-967 at c45735a5): the connection
  // seen cold, the peer is no longer established
  test("a close that raises leaves the peer cold once its connection is released") {
    val raised = new Raised
    govern(state(targets(0, 1, 0, 0), Peer -> warm), actions(closing = IO.raiseError(raised))) {
      case Outcome.Closed(Peer) | Outcome.CloseTimedOut(Peer) => true
      case _                                                  => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.Closed(Peer))
      assertEquals(applied.record.map(_.status), Some(Status.Cold(None)))
      assert(
        applied.outputs.contains(
          Output.Traced(
            Trace.PeerActionFailed(
              Peer,
              PeerOperation.Close,
              PeerActionError.ConnectionFailure(NetworkError.Defect(raised))
            )
          )
        ),
        "the exception should be reported as the close's defect"
      )
    }
  }

  test("a close that raises leaves the peer cooling when its connection lingers") {
    govern(
      state(targets(0, 1, 0, 0), Peer -> warm),
      actions(closing = IO.raiseError(new Raised)),
      cold = IO.never
    ) {
      case Outcome.Closed(Peer) | Outcome.CloseTimedOut(Peer) => true
      case _                                                  => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.CloseTimedOut(Peer))
      assert(applied.at >= policy.demotionTimeout, s"came before the timeout, at ${applied.at}")
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  // with no retained connection to watch, the connection is not seen cold: the handler's timeout
  test("without a retained connection, a close that raises leaves the peer cooling") {
    govern(
      state(targets(0, 1, 0, 0), Peer -> warm),
      actions(closing = IO.raiseError(new Raised)),
      connected = false
    ) {
      case Outcome.Closed(Peer) | Outcome.CloseTimedOut(Peer) => true
      case _                                                  => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.CloseTimedOut(Peer))
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  // the request's exception is its result, as `waitAllCatchOrTimeout` returns it (KnownPeers.hs
  // 298-304 at c45735a5): the batch reports it at once instead of waiting out the timeouts
  test("a peer-share request that raises is that peer's failed request, reported at once") {
    val raised = new Raised
    val sharing = new PeerSharingActions[IO, Int]:
      def inbound = IO.pure(Map.empty[Int, Boolean])
      def request(peer: Int, amount: PeerSharing.Amount) = IO.raiseError(raised)
      def publish(state: GovernorState[Int]) = IO.unit
    val initial = state(
      targets(0, 2, 1, 0),
      Peer -> warm.copy(shareAt = Some(Duration.Zero), sharing = Some(true))
    ).copy(sharing = PeerShare.Governor[Int](enabled = true))
    govern(initial, actions(), sharing = Some(sharing)) {
      case Outcome.Shared(_, _) => true
      case _                    => false
    }.map { applied =>
      assertEquals(
        applied.outcome,
        Outcome.Shared(0L, List(Peer -> PeerShare.Result.Failed(NetworkError.Defect(raised))))
      )
      assert(
        applied.at < policy.peerShareBatchWaitTime,
        s"should come within the batch wait, came at ${applied.at}"
      )
      assertEquals(applied.state.sharing.batches, Map.empty[Long, PeerShare.Batch[Int]])
      assertEquals(
        applied.record.flatMap(_.shareAt),
        Some(applied.at + policy.peerShareFailureRetryTime)
      )
    }
  }

  // `jobReqPublicRootPeers`'s handler (RootPeers.hs 125-155 at c45735a5): a failed request, the
  // back-off one step in the failure direction and the next request 2 s on
  test("a request for public roots that raises is a failed request, one back-off step on") {
    val roots = new RootProvider[IO, Int]:
      def request(max: Int, bootstrap: Boolean) = IO.raiseError(new Raised)
      def requestBigLedger(max: Int) = IO.never
    govern(state(targets(1, 1, 0, 0)), actions(), roots = roots) {
      case Outcome.RootsFailed(_) => true
      case _                      => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.RootsFailed(BigInt(0)))
      assertEquals(applied.state.publicRootsInFlight, false)
      assertEquals(applied.state.publicRootBackoffs, -1)
      assertEquals(applied.state.publicRootRetryAt, applied.at + 2.seconds)
    }
  }

  // `jobReqBigLedgerPeers`'s handler (BigLedgerPeers.hs 134-162 at c45735a5): the same for big
  // ledger peers
  test("a request for big ledger peers that raises is a failed request, one back-off step on") {
    val roots = new RootProvider[IO, Int]:
      def request(max: Int, bootstrap: Boolean) = IO.never
      def requestBigLedger(max: Int) = IO.raiseError(new Raised)
    govern(state(targets(0, 0, 0, 0, knownBig = 1)), actions(), roots = roots) {
      case Outcome.BigLedgerFailed(_) => true
      case _                          => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.BigLedgerFailed(BigInt(0)))
      assertEquals(applied.state.bigLedgerInFlight, false)
      assertEquals(applied.state.bigLedgerBackoffs, -1)
      assertEquals(applied.state.bigLedgerRetryAt, applied.at + 2.seconds)
    }
  }

  // `notAsyncExceptions` (JobPool.hs 98-102 at c45735a5): cancellation is not given to the
  // handler, so a job cancelled when the governor stops offers nothing
  test("a job cancelled when the governor stops leaves no completion behind") {
    TestControl.executeEmbed(
      for
        current <- Ref.of[IO, GovernorState[Int]](
          state(targets(0, 1, 1, 0), Peer -> PeerRecord.cold(Origin.Other))
        )
        events <- Queue.unbounded[IO, Event[Int]]
        told <- Ref.of[IO, Vector[Output[Int]]](Vector.empty)
        entered <- Deferred[IO, Unit]
        given Stateful[IO, GovernorState[Int]] = RefStateful(current)
        given Ask[IO, GovernorConfig] = Ask.const[IO, GovernorConfig](
          GovernorConfig(policy = policy, association = AssociationMode.Unrestricted)
        )
        given Ask[IO, PeerMetrics[Int]] = Ask.const[IO, PeerMetrics[Int]](PeerMetrics.empty())
        given Ask[IO, PeerGovernor.ChurnWritten] =
          Ask.const[IO, PeerGovernor.ChurnWritten](PeerGovernor.ChurnWritten.Initial)
        given Tell[IO, Output[Int]] = new Tell[IO, Output[Int]]:
          def functor = cats.Functor[IO]
          def tell(output: Output[Int]) = told.update(_ :+ output)
        connecting = actions(establishing = entered.complete(()) *> IO.never)
        _ <- Supervisor[IO](await = false).use { supervisor =>
          Handle
            .allow[GovernorError] {
              PeerGovernor
                .run(
                  connecting,
                  noRoots,
                  events,
                  supervisor,
                  connections = Some(connections(connecting, IO.unit))
                )
                .void
            }
            .rescue(error => IO.raiseError(new AssertionError(error.toString)))
            .background
            .use(_ => entered.get)
        }
        left <- events.tryTakeN(None)
        outputs <- told.get
      yield
        assertEquals(
          left.collect { case event @ (Event.Completed(_) | Event.ConnectionCompleted(_, _, _)) =>
            event
          },
          Nil
        )
        assertEquals(
          outputs.collect { case failed @ Output.EstablishmentFailed(_, _) => failed },
          Vector.empty
        )
    )
  }
