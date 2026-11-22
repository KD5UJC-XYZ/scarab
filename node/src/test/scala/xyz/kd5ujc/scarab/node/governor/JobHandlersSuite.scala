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
import xyz.kd5ujc.scarab.network.{
  FailsOnUncaught,
  NetworkError,
  PeerActionError,
  PeerOperation,
  PeerStatus
}
import xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.{
  EstablishmentError,
  FailurePolicy,
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

  /** The event the governor applied that a test waits for, when it applied it, its state after it,
    * and what it had told by then.
    */
  private final case class Applied(
      event: Event[Int],
      at: FiniteDuration,
      state: GovernorState[Int],
      outputs: Vector[Output[Int]]
  ):
    def record: Option[PeerRecord] = state.peers.get(Peer)
    def outcome: Outcome[Int] = event match
      case Event.Completed(outcome) => outcome
      case other                    => fail(s"not a completion: $other")

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

  private val released =
    IO.pure[PeerStateActions.Result[PeerStateActions.CloseResult]](
      Right(PeerStateActions.CloseResult(PeerStateActions.Release.Released))
    )

  /** The peer's retained connection, which reads `reads` and goes cold when `cold` completes. */
  private def connections(
      actions: PeerStateActions[IO, Int],
      cold: IO[Unit],
      reads: PeerStatus
  ): PeerStateActions.Connections[IO, Int] = new PeerStateActions.Connections[IO, Int]:
    def monitorPeerConnection(peer: Int) =
      IO.pure(
        Option.when(peer == Peer)(
          PeerStateActions.Connection(
            BigInt(1),
            BigInt(0),
            false,
            IO.pure(PeerStateActions.Monitoring(reads, None)),
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

  /** Run the governor from `initial` until it applies an event that `until` holds of, with its
    * state after it, and give what it did then. One that never comes fails at an hour of virtual
    * time.
    *
    * @param connected
    *   Whether the governor watches retained connections, as the node's does; without, its jobs
    *   take the actions' legacy path.
    * @param reads
    *   What the retained connection reads when the governor looks at it.
    * @param refused
    *   The outputs whose telling raises, as a log that cannot be written does.
    * @param alongside
    *   What the world does while the governor runs, given the governor's queue.
    */
  private def watch(
      initial: GovernorState[Int],
      actions: PeerStateActions[IO, Int],
      cold: IO[Unit],
      roots: RootProvider[IO, Int] = noRoots,
      sharing: Option[PeerSharingActions[IO, Int]],
      connected: Boolean = true,
      reads: PeerStatus,
      refused: Output[Int] => Boolean = _ => false,
      alongside: Queue[IO, Event[Int]] => IO[Unit] = _ => IO.unit,
      reacting: Event[Int] => Option[Event[Int]] = _ => None,
      onApplied: Event[Int] => IO[Unit] = _ => IO.unit,
      beforeApplied: Event[Int] => IO[Unit] = _ => IO.unit,
      embed: IO[Applied] => IO[Applied] = TestControl.executeEmbed(_)
  )(until: (Event[Int], GovernorState[Int]) => Boolean): IO[Applied] =
    embed(Supervisor[IO](await = false).use { supervisor =>
      for
        current <- Ref.of[IO, GovernorState[Int]](initial)
        events <- xyz.kd5ujc.scarab.node.JobPool.Unbounded[IO, Event[Int]]
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
                beforeEventApplied = Some(beforeApplied),
                onEventApplied = Some(event =>
                  onApplied(event) *> reacting(event).traverse_(events.offer) *> current.get
                    .flatMap(st =>
                      (IO.monotonic, told.get)
                        .flatMapN((at, outputs) =>
                          applied.complete(Applied(event, at, st, outputs)).void
                        )
                        .whenA(until(event, st))
                    )
                ),
                connections = Option.when(connected)(connections(actions, cold, reads))
              )
              .void
          }
          .rescue(error => IO.raiseError(new AssertionError(error.toString)))
        result <- (run, alongside(events)).parTupled.background.use(_ =>
          applied.get.timeout(1.hour)
        )
      yield result
    })

  /** As [[watch]], until the governor applies a completion that `applies`. */
  private def govern(
      initial: GovernorState[Int],
      actions: PeerStateActions[IO, Int],
      cold: IO[Unit] = IO.unit,
      roots: RootProvider[IO, Int] = noRoots,
      sharing: Option[PeerSharingActions[IO, Int]] = None,
      connected: Boolean = true,
      reads: PeerStatus = PeerStatus.Warm,
      refused: Output[Int] => Boolean = _ => false,
      reacting: Event[Int] => Option[Event[Int]] = _ => None
  )(applies: Outcome[Int] => Boolean): IO[Applied] =
    watch(
      initial,
      actions,
      cold,
      roots,
      sharing,
      connected,
      reads,
      refused,
      reacting = reacting
    ) {
      case (Event.Completed(outcome), _) => applies(outcome)
      case _                             => false
    }

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

  // the path of #1148: the report of a failed connect raises, before the job offers its completion;
  // the completion keeps the failure's own class
  test("a failed connect whose report raises still completes, with its failure's class") {
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
      val classified = ActionFixtures.unavailable match
        case EstablishmentError.Failed(cause) => FailurePolicy.classify(cause)
        case other                            => fail(s"the fixture is not a peer failure: $other")
      assertEquals(applied.outcome, Outcome.ConnectFailed(Peer, classified, BigInt(0)))
      assertEquals(classified, FailureClass.Transient)
      assertEquals(applied.record.map(_.failCount), Some(1))
      assert(retryIn(applied).nonEmpty, s"the peer should be cold, was ${applied.record}")
    }
  }

  test("a failed activation whose report raises still completes, with its failure's class") {
    val inactive = PeerActionError.Inactive(PeerOperation.Activate, PeerStatus.Cold)
    govern(
      state(targets(0, 1, 1, 1), Peer -> warm),
      actions(activating = IO.pure(Left(inactive))),
      refused = {
        case Output.Traced(Trace.PeerActionFailed(_, _, _)) => true
        case _                                              => false
      }
    ) {
      case Outcome.ActivateFailed(Peer, _) => true
      case _                               => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.ActivateFailed(Peer, FailureClass.Transient))
      assertEquals(applied.record.map(_.failCount), Some(1))
    }
  }

  // `promoteWarmPeer`'s handler (ActivePeers.hs 460-558 at c45735a5): the pause of one second,
  // then the peer leaves the established set, cold for the error delay of 10 s with a fuzz of up
  // to 2 s; the connection the activation raised on is closed, as an exception of the node's own
  // code ends its connection
  test("an activation that raises closes its connection and leaves the peer cold after the pause") {
    val raised = new Raised
    for
      closes <- Ref.of[IO, Int](0)
      applied <- govern(
        state(targets(0, 1, 1, 1), Peer -> warm),
        actions(activating = IO.raiseError(raised), closing = closes.update(_ + 1) *> released)
      ) {
        case Outcome.ActivateFailed(Peer, _) => true
        case _                               => false
      }
      closed <- closes.get
    yield
      assertEquals(applied.outcome, Outcome.ActivateFailed(Peer, FailureClass.NotThePeers))
      assertEquals(closed, 1, "the connection should be closed once")
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

  /** The peer's demotion raised and its connection was not seen cold, the wait for it timing out
    * (`cold` never ends) or raising. Gives what the governor did with the timeout's completion.
    */
  private def lingering(
      initial: GovernorState[Int],
      demotion: Deferred[IO, Unit] => PeerStateActions[IO, Int],
      cold: IO[Unit]
  ): IO[Applied] =
    Deferred[IO, Unit].flatMap(started =>
      govern(initial, demotion(started), cold = cold, reads = PeerStatus.Cooling) {
        case Outcome.CloseTimedOut(Peer) => true
        case _                           => false
      }
    )

  private def raising(started: Deferred[IO, Unit]) =
    started.complete(()) *> IO.raiseError(new Raised)

  private def timedOut(from: Temperature)(applied: Applied): Boolean =
    applied.outputs.contains(Output.Traced(Trace.DemoteFailed(from, Peer, timedOut = true)))

  // the same handler when the connection is not seen cold within the demotion timeout: the peer
  // keeps its place, and the monitor then demotes it by what its connection reads, cooling
  // (Monitor.hs 298-302 at c45735a5)
  test("a deactivation that raises leaves the peer cooling when its connection lingers") {
    lingering(
      state(targets(0, 1, 1, 0), Peer -> hot),
      started => actions(deactivating = raising(started)),
      IO.never
    ).map { applied =>
      assert(applied.at >= policy.demotionTimeout, s"came before the timeout, at ${applied.at}")
      assert(timedOut(Temperature.Hot)(applied), s"no timed-out demotion in ${applied.outputs}")
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  test("a deactivation whose wait for the release raises leaves the peer cooling at once") {
    lingering(
      state(targets(0, 1, 1, 0), Peer -> hot),
      started => actions(deactivating = raising(started)),
      IO.raiseError(new Raised)
    ).map { applied =>
      assert(applied.at < policy.demotionTimeout, s"waited for the timeout, at ${applied.at}")
      assert(timedOut(Temperature.Hot)(applied), s"no timed-out demotion in ${applied.outputs}")
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  // a cleanup that fails leaves the connection held: the peer is cooling until its release is seen
  // (Monitor.hs 292-296 at c45735a5), and the close's failure is reported
  test("an activation whose cleanup raises leaves the peer cooling, the close's failure reported") {
    val failedClose = new Raised
    govern(
      state(targets(0, 1, 1, 1), Peer -> warm),
      actions(activating = IO.raiseError(new Raised), closing = IO.raiseError(failedClose))
    ) {
      case Outcome.ActivateFailed(Peer, _) | Outcome.ActivateFailedLingering(Peer, _) => true
      case _                                                                          => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.ActivateFailedLingering(Peer, FailureClass.NotThePeers))
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
      assert(
        applied.outputs.contains(
          Output.Traced(
            Trace.PeerActionFailed(
              Peer,
              PeerOperation.Close,
              PeerActionError.ConnectionFailure(NetworkError.Defect(failedClose))
            )
          )
        ),
        "the close's exception should be reported"
      )
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
    lingering(
      state(targets(0, 1, 0, 0), Peer -> warm),
      started => actions(closing = raising(started)),
      IO.never
    ).map { applied =>
      assert(applied.at >= policy.demotionTimeout, s"came before the timeout, at ${applied.at}")
      assert(timedOut(Temperature.Warm)(applied), s"no timed-out demotion in ${applied.outputs}")
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  test("a close whose wait for the release raises leaves the peer cooling at once") {
    lingering(
      state(targets(0, 1, 0, 0), Peer -> warm),
      started => actions(closing = raising(started)),
      IO.raiseError(new Raised)
    ).map { applied =>
      assert(applied.at < policy.demotionTimeout, s"waited for the timeout, at ${applied.at}")
      assert(timedOut(Temperature.Warm)(applied), s"no timed-out demotion in ${applied.outputs}")
      assertEquals(applied.record.map(_.status), Some(Status.Cooling))
    }
  }

  // with no retained connection to watch, nothing reads it: the peer keeps its place, as the
  // handler's timeout leaves it
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

  // the cleanup's close that times out is reported as the close job reports it
  test("an activation whose cleanup's close times out reports the close's timeout") {
    govern(
      state(targets(0, 1, 1, 1), Peer -> warm),
      actions(
        activating = IO.raiseError(new Raised),
        closing = IO.pure(
          Right(
            PeerStateActions.CloseResult(
              PeerStateActions.Release.Released,
              PeerStateActions.CloseCompletion.TimedOut
            )
          )
        )
      )
    ) {
      case Outcome.ActivateFailed(Peer, _) | Outcome.ActivateFailedLingering(Peer, _) => true
      case _                                                                          => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.ActivateFailed(Peer, FailureClass.NotThePeers))
      assert(
        applied.outputs.contains(
          Output.Traced(
            Trace.PeerActionFailed(
              Peer,
              PeerOperation.Close,
              PeerActionError.TimedOut(PeerOperation.Close)
            )
          )
        ),
        s"the close's timeout should be reported: ${applied.outputs}"
      )
    }
  }

  private def sharingWith(answer: IO[PeerShare.Result[Int]]): PeerSharingActions[IO, Int] =
    new PeerSharingActions[IO, Int]:
      def inbound = IO.pure(Map.empty[Int, Boolean])
      def request(peer: Int, amount: PeerSharing.Amount) = answer
      def publish(state: GovernorState[Int]) = IO.unit

  private val sharingState = state(
    targets(0, 2, 1, 0),
    Peer -> warm.copy(shareAt = Some(Duration.Zero), sharing = Some(true))
  ).copy(sharing = PeerShare.Governor[Int](enabled = true))

  // the request's exception is its result, as `waitAllCatchOrTimeout` returns it (KnownPeers.hs
  // 298-304 at c45735a5): the batch reports it at once instead of waiting out the timeouts
  test("a peer-share request that raises is that peer's failed request, reported at once") {
    val raised = new Raised
    govern(sharingState, actions(), sharing = Some(sharingWith(IO.raiseError(raised)))) {
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

  // a cancelled request is observed when it ends (`waitCatchSTM`, KnownPeers.hs 597 at c45735a5),
  // not at the batch's deadline, as the reference's `AsyncCancelled`
  test("a peer-share request that is cancelled is reported at once, cancelled") {
    govern(
      sharingState,
      actions(),
      sharing = Some(sharingWith(IO.canceled *> IO.never[PeerShare.Result[Int]]))
    ) {
      case Outcome.Shared(_, _) => true
      case _                    => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.Shared(0L, List(Peer -> PeerShare.Result.Cancelled())))
      assertEquals(
        applied.record.flatMap(_.shareAt),
        Some(applied.at + policy.peerShareFailureRetryTime)
      )
      assert(
        applied.at < policy.peerShareBatchWaitTime,
        s"should come within the batch wait, came at ${applied.at}"
      )
    }
  }

  private val Other = 2

  /** Two peers to ask, both warm and due, so that one batch asks both. */
  private val twoSharing = state(
    targets(0, 3, 2, 0),
    Peer -> warm.copy(shareAt = Some(Duration.Zero), sharing = Some(true)),
    Other -> warm.copy(shareAt = Some(Duration.Zero), sharing = Some(true))
  ).copy(sharing = PeerShare.Governor[Int](enabled = true))

  private def phaseFailed(results: List[(Int, PeerShare.Result[Int])]): Boolean =
    results.nonEmpty && results.forall {
      case (_, PeerShare.Result.PhaseFailed(NetworkError.Defect(_))) => true
      case _                                                         => false
    }

  // a phase that raises reports its cause for its peers, which are then no longer in flight, and
  // leaves the time each is next due as the request set it (`handler`, KnownPeers.hs 280-289 at
  // c45735a5): here the first phase raises while it starts the requests
  test("a peer-share first phase that raises reports each of its peers, retry times as they were") {
    val sharing = new PeerSharingActions[IO, Int]:
      def inbound = IO.pure(Map.empty[Int, Boolean])
      def request(peer: Int, amount: PeerSharing.Amount) = throw new Raised
      def publish(state: GovernorState[Int]) = IO.unit
    govern(twoSharing, actions(), sharing = Some(sharing)) {
      case Outcome.Shared(_, _) => true
      case _                    => false
    }.map { applied =>
      applied.outcome match
        case Outcome.Shared(0L, results) =>
          assert(phaseFailed(results), s"each peer should be reported failed: $results")
          assertEquals(results.map(_._1).toSet, Set(Peer, Other))
        case other => fail(s"not the batch's completion: $other")
      assertEquals(applied.state.sharing.batches, Map.empty[Long, PeerShare.Batch[Int]])
      assertEquals(
        applied.state.peers.get(Peer).flatMap(_.shareAt),
        Some(policy.peerShareRetryTime)
      )
      assertEquals(
        applied.state.peers.get(Other).flatMap(_.shareAt),
        Some(policy.peerShareRetryTime)
      )
    }
  }

  // the second phase, started once the governor applied the first's answers, waits its whole time
  // from then and reports only the peers still in flight (`jobPhase2`, KnownPeers.hs 385- at
  // c45735a5)
  test("a peer-share second phase reports only the peers still in flight, after its whole wait") {
    val sharing = new PeerSharingActions[IO, Int]:
      def inbound = IO.pure(Map.empty[Int, Boolean])
      def request(peer: Int, amount: PeerSharing.Amount) =
        if peer == Peer then IO.pure(PeerShare.Result.Peers(Vector.empty[Int])) else IO.never
      def publish(state: GovernorState[Int]) = IO.unit
    govern(twoSharing, actions(), sharing = Some(sharing)) {
      case Outcome.Shared(_, results) => results.exists(_._1 == Other)
      case _                          => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.Shared(0L, List(Other -> PeerShare.Result.TimedOut())))
      assert(
        applied.at >= policy.peerShareOverallTimeout,
        s"the second phase should wait its whole time, ended at ${applied.at}"
      )
      assertEquals(applied.state.sharing.batches, Map.empty[Long, PeerShare.Batch[Int]])
    }
  }

  // On virtual time the test drives: the first phase's completion comes at the batch wait (3 s),
  // and its application is held until 8 s; the second phase starts then, as the governor forks a
  // completion's `decisionJobs` after applying it (Governor.hs 614-638 at c45735a5), so its whole
  // wait ends at 15 s, not at 10 s.
  test("a peer-share second phase starts only once the governor has applied the first") {
    val sharing = new PeerSharingActions[IO, Int]:
      def inbound = IO.pure(Map.empty[Int, Boolean])
      def request(peer: Int, amount: PeerSharing.Amount) =
        if peer == Peer then IO.pure(PeerShare.Result.Peers(Vector.empty[Int])) else IO.never
      def publish(state: GovernorState[Int]) = IO.unit
    Deferred[IO, Unit].flatMap { held =>
      TestControl
        .execute(
          watch(
            twoSharing,
            actions(),
            cold = IO.unit,
            sharing = Some(sharing),
            reads = PeerStatus.Warm,
            beforeApplied = {
              case Event.Completed(Outcome.Shared(_, results)) if results.exists(_._1 == Peer) =>
                held.get
              case _ => IO.unit
            },
            embed = identity
          ) {
            case (Event.Completed(Outcome.Shared(_, results)), _) => results.exists(_._1 == Other)
            case _                                                => false
          }
        )
        .flatMap { control =>
          for
            _ <- control.tickFor(8.seconds)
            _ <- held.complete(())
            _ <- control.tickAll
            result <- control.results
          yield result match
            case Some(cats.effect.Outcome.Succeeded(applied)) =>
              assert(
                applied.at >=
                  8.seconds + policy.peerShareOverallTimeout - policy.peerShareBatchWaitTime,
                s"the second phase should start once the first is applied, ended at ${applied.at}"
              )
            case other => fail(s"the run did not end with the second phase: $other")
        }
    }
  }

  // `jobReqPublicRootPeers`'s handler (RootPeers.hs 125-155 at c45735a5): a failed request, the
  // back-off one step in the failure direction and the next request 2 s on, its exception traced
  test("a request for public roots that raises is a failed request, one back-off step on") {
    val raised = new Raised
    val roots = new RootProvider[IO, Int]:
      def request(max: Int, bootstrap: Boolean) = IO.raiseError(raised)
      def requestBigLedger(max: Int) = IO.never
    govern(state(targets(1, 1, 0, 0)), actions(), roots = roots) {
      case Outcome.RootsFailed(_, _) => true
      case _                         => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.RootsFailed(BigInt(0), Some(raised)))
      assertEquals(applied.state.publicRootsInFlight, false)
      assertEquals(applied.state.publicRootBackoffs, -1)
      assertEquals(applied.state.publicRootRetryAt, applied.at + 2.seconds)
      assert(
        applied.outputs.contains(Output.Traced(Trace.RootsFailure(-1, 2.seconds, Some(raised)))),
        s"the failure should be traced with its exception: ${applied.outputs}"
      )
    }
  }

  // `jobReqBigLedgerPeers`'s handler (BigLedgerPeers.hs 134-162 at c45735a5): the same for big
  // ledger peers
  test("a request for big ledger peers that raises is a failed request, one back-off step on") {
    val raised = new Raised
    val roots = new RootProvider[IO, Int]:
      def request(max: Int, bootstrap: Boolean) = IO.never
      def requestBigLedger(max: Int) = IO.raiseError(raised)
    govern(state(targets(0, 0, 0, 0, knownBig = 1)), actions(), roots = roots) {
      case Outcome.BigLedgerFailed(_, _) => true
      case _                             => false
    }.map { applied =>
      assertEquals(applied.outcome, Outcome.BigLedgerFailed(BigInt(0), Some(raised)))
      assertEquals(applied.state.bigLedgerInFlight, false)
      assertEquals(applied.state.bigLedgerBackoffs, -1)
      assertEquals(applied.state.bigLedgerRetryAt, applied.at + 2.seconds)
      assert(
        applied.outputs.contains(
          Output.Traced(Trace.BigLedgerFailure(-1, 2.seconds, Some(raised)))
        ),
        s"the failure should be traced with its exception: ${applied.outputs}"
      )
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
        events <- xyz.kd5ujc.scarab.node.JobPool.Unbounded[IO, Event[Int]]
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
                  connections = Some(connections(connecting, IO.unit, PeerStatus.Warm))
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
