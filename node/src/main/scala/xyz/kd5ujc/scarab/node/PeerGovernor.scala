// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.effect.{Clock, Deferred, Ref, Temporal}
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust
import xyz.kd5ujc.scarab.network.{PeerActionError, PeerOperation, PeerStatus}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** What the governor asks of the world to move a peer between temperatures (the reference's
  * `PeerStateActions`, `PeerSelection/PeerStateActions.hs` 590-: establish, activate, deactivate,
  * close). Establishment is named by address; subsequent production jobs capture the retained
  * [[PeerStateActions.Connection]] before supervision. Unrequested changes wake the governor with
  * [[Event.Observed]], which it rechecks against that connection's current status and identity.
  */
trait PeerStateActions[F[_], A]:
  /** Open a connection and retain its handle or its original acquisition failure. */
  def establish(
      peer: A,
      mode: DiffusionMode,
      provenance: Provenance
  ): F[Either[EstablishmentError, PeerStateActions.Connection[F]]]

  /** Open a connection for one roots generation; stale completion cleanup can target that link. */
  def establishAt(
      peer: A,
      mode: DiffusionMode,
      provenance: Provenance,
      generation: BigInt
  ): F[Either[EstablishmentError, PeerStateActions.Connection[F]]]

  /** Start the hot protocols on an established connection, preserving typed failures. */
  def activate(peer: A): F[Either[PeerActionError, Unit]] = activate(peer, PeerTrust.Untrustable)

  /** Activate with the trust flags captured when the governor selected this job. */
  def activate(peer: A, trust: PeerTrust): F[Either[PeerActionError, Unit]]

  /** End the hot protocols and keep the connection. */
  def deactivate(peer: A): F[PeerStateActions.Result[PeerStateActions.Deactivation]]

  /** Close the connection and retain its graceful-completion and manager-release outcomes. */
  def close(peer: A): F[PeerStateActions.Result[PeerStateActions.CloseResult]]

  /** Close a link only if it is still the attempt completed by `generation`. */
  def closeIfGeneration(
      peer: A,
      generation: BigInt
  ): F[Option[PeerStateActions.Result[PeerStateActions.CloseResult]]]

object PeerStateActions:
  type Status = PeerStatus
  val Status = PeerStatus

  enum Release:
    case Released, Lingering

  enum CloseCompletion:
    case Clean, TimedOut

  final case class CloseResult(
      release: Release,
      completion: CloseCompletion = CloseCompletion.Clean,
      status: Status = Status.Cooling
  )

  final case class Failure(cause: PeerActionError, release: Release)
  type Result[A] = Either[Failure, A]

  final case class Monitoring(
      status: Status,
      repromoteDelay: Option[FiniteDuration],
      failure: Option[xyz.kd5ujc.scarab.ledger.PeerError] = None
  )

  /** Reads and commands retain this connection after its bearer is gone or its address is reused.
    */
  final case class Connection[F[_]](
      identity: BigInt,
      rootsGeneration: BigInt,
      read: F[Monitoring],
      activate: PeerTrust => F[Either[PeerActionError, Unit]],
      deactivate: F[Result[Deactivation]],
      close: F[Result[CloseResult]],
      awaitCold: F[Unit]
  )

  trait Connections[F[_], A]:
    def monitorPeerConnection(peer: A): F[Option[Connection[F]]]
    def retainConnections(known: Set[A]): F[Unit]

  /** How a deactivation ended. */
  enum Deactivation:
    /** The hot protocols ended on the wire and the connection is warm. */
    case Warm

    /** They could not be ended cleanly, so the connection was closed and released. */
    case Closed

    /** The connection was closed but is still held by the connection manager. */
    case Lingering

/** Where public roots come from (`requestPublicRootPeers`, `PeerSelectionActions.hs`): the
  * configured public roots, or, in the sensitive state, the bootstrap peers. None when the request
  * failed.
  */
trait RootProvider[F[_], A]:
  def request(max: Int, bootstrap: Boolean): F[Option[(PublicRoots[A], FiniteDuration)]]

  /** Ask for up to `max` big ledger peers (`requestPublicRootPeers` for `SingBigLedgerPeers`). */
  def requestBigLedger(max: Int): F[Option[(BigLedgerRoots[A], FiniteDuration)]]

/** What the governor tells the world besides its traces. */
enum Output[A]:
  case Traced(trace: Trace[A])

  /** The original connection acquisition failure, before its existing retry-policy projection. */
  case EstablishmentFailed(peer: A, error: EstablishmentError)

  /** The counters changed (`PeerSelectionCounters`). */
  case Counted(counters: GovernorState.Counters)

  /** The outbound connections state changed ([[OutboundConnectionsState]]). */
  case Connections(state: OutboundConnectionsState)

  /** The churn governor did something: a round began, a step ended or timed out, it sleeps. */
  case Churned(trace: Churn.Trace)

  /** The ledger-peers worker did something: loaded a snapshot, rebuilt its maps. */
  case LedgerPeers(trace: LedgerPeersTrace)

  /** The local roots' DNS met a lookup that threw. */
  case LocalRoots(trace: LocalRootsTrace)

/** Peer-sharing effects. A request owns its error scope and returns a closed discovery result;
  * implementation defects are reported to the node fatal sink before returning.
  */
trait PeerSharingActions[F[_], A]:
  def inbound: F[Map[A, Boolean]]
  def request(
      peer: A,
      amount: xyz.kd5ujc.scarab.protocol.PeerSharing.Amount
  ): F[PeerShare.Result[A]]
  def publish(state: GovernorState[A]): F[Unit]

/** The settings of the effect loop.
  *
  * @param activationPause
  *   The pause after a failed activation, so a failing promotion cannot spin (`threadDelay 1`,
  *   `ActivePeers.hs` 476).
  */
final case class GovernorConfig(
    policy: Policy = Policy(),
    association: AssociationMode,
    activationPause: FiniteDuration = 1.second
)

/** The governor loop: the effect that drives [[PeerSelection]]. It keeps the state in `Stateful`,
  * reads its settings from `Ask`, tells what it did through `Tell`, and ends only by raising a
  * [[GovernorError]]. Jobs run in fibers of the `Supervisor`, each of which ends by offering its
  * outcome to the queue, so a job that is cancelled at shutdown leaves no claim behind; events
  * arrive on the same queue from the connections, the Genesis State Machine and the regime of
  * targets.
  *
  * The peer metrics (`Ask`) are read once per pass and given to the decision function, which ranks
  * the hot peers for demotion by them. Each pass applies the events that are waiting, then asks the
  * decision function; a decision is committed (state, traces, jobs) and the pass starts again; when
  * nothing applies the loop waits for an event or for the time the decision function named,
  * whichever comes first.
  */
object PeerGovernor:

  /** What the loop remembers between two passes: what was last published, so that only changes are
    * told.
    */
  private final case class Loop(
      counters: Option[GovernorState.Counters],
      connections: Option[OutboundConnectionsState]
  )

  /** A validated observation of the churn target TVar, including its topology generation. */
  final case class RequestedTargets(targets: Targets, generation: BigInt)

  def run[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      supervisor: Supervisor[F],
      sharing: Option[PeerSharingActions[F, A]] = None,
      beforeEventApplied: Option[Event[A] => F[Unit]] = None,
      onEventApplied: Option[Event[A] => F[Unit]] = None,
      readRequestedTargets: Option[F[Option[RequestedTargets]]] = None,
      connections: Option[PeerStateActions.Connections[F, A]] = None
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]],
      metrics: Ask[F, PeerMetrics[A]]
  ): F[Nothing] =
    Ref
      .of[F, Set[Time]](Set.empty)
      .flatMap(scheduled =>
        loop(
          actions,
          roots,
          events,
          supervisor,
          scheduled,
          sharing,
          beforeEventApplied,
          onEventApplied,
          readRequestedTargets,
          connections
        )
      )

  /** @param scheduled
    *   The times a wake-up is already asked for.
    */
  private def loop[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      supervisor: Supervisor[F],
      scheduled: Ref[F, Set[Time]],
      sharing: Option[PeerSharingActions[F, A]],
      beforeEventApplied: Option[Event[A] => F[Unit]],
      onEventApplied: Option[Event[A] => F[Unit]],
      readRequestedTargets: Option[F[Option[RequestedTargets]]],
      connections: Option[PeerStateActions.Connections[F, A]]
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]],
      metrics: Ask[F, PeerMetrics[A]]
  ): F[Nothing] =
    val F = Temporal[F]
    given Monad[F] = F

    def now: F[Time] = Clock[F].monotonic

    /** Start an ordinary job and report its outcome to its owner. */
    def complete(result: F[Outcome[A]]): F[Unit] =
      supervisor.supervise(result.flatMap(outcome => events.offer(Event.Completed(outcome)))).void

    // Capture the retained connection in the governor fiber, before supervising the action.
    // ActivePeers.hs (2fb69829):167–181,450–461; EstablishedPeers.hs:971–978.
    def connectionJob(peer: A, generation: Option[BigInt] = None)(
        run: PeerStateActions.Connection[F] => F[Outcome[A]],
        legacy: F[Option[Outcome[A]]]
    ): F[Unit] = connections match
      case None =>
        supervisor
          .supervise(legacy.flatMap(_.traverse_(value => events.offer(Event.Completed(value)))))
          .void
      case Some(source) =>
        source.monitorPeerConnection(peer).flatMap {
          case Some(handle) if generation.forall(_ == handle.rootsGeneration) =>
            supervisor
              .supervise(
                run(handle).flatMap(value =>
                  events.offer(Event.ConnectionCompleted(peer, handle.identity, value))
                )
              )
              .void
          case _ => F.unit
        }

    // Governor demotion jobs, not PeerStateActions, own the manager-release deadline.
    // ActivePeers.hs (2fb69829):989–1000; EstablishedPeers.hs:971–992.
    def awaitRelease(handle: PeerStateActions.Connection[F]): F[PeerStateActions.Release] =
      config.reader(_.policy.demotionTimeout).flatMap { timeout =>
        F.timeoutTo(
          handle.awaitCold.as(PeerStateActions.Release.Released),
          timeout,
          F.pure(PeerStateActions.Release.Lingering)
        )
      }

    /** Start a job in a fiber of the supervisor; it ends by offering its outcome. */
    def start(job: Job[A]): F[Unit] = job match
      case Job.Share(batch, amount, peers) =>
        config.reader(_.policy).flatMap { policy =>
          // Requests belong to the supervisor, not to the governor's short wait. A logical
          // timeout stops waiting for discovery; the bounded wire request still drains its reply
          // before this client can be reused (as the reference's protocol controller does).
          peers
            .traverse { peer =>
              Deferred[F, PeerShare.Result[A]]
                .flatTap { result =>
                  val request = sharing.fold(
                    F.pure[PeerShare.Result[A]](PeerShare.Result.NotRegistered())
                  )(_.request(peer, amount))
                  supervisor.supervise(request.flatMap(result.complete).void).void
                }
                .map(peer -> _)
            }
            .flatMap { pending =>
              def collect(
                  wait: FiniteDuration,
                  remaining: List[(A, Deferred[F, PeerShare.Result[A]])]
              ) =
                F.race(F.sleep(wait), remaining.traverse_(_._2.get)).flatMap { _ =>
                  remaining.traverse { (peer, result) =>
                    result.tryGet.map(value => (peer, result, value))
                  }
                }
              val phases = for
                first <- collect(policy.peerShareBatchWaitTime, pending)
                done = first.collect { case (peer, _, Some(result)) => peer -> result }
                rest = first.collect { case (peer, result, None) => peer -> result }
                _ <- events.offer(Event.Completed(Outcome.Shared(batch, done))).whenA(done.nonEmpty)
                _ <-
                  if rest.isEmpty then F.unit
                  else
                    collect(
                      (policy.peerShareOverallTimeout - policy.peerShareBatchWaitTime)
                        .max(Duration.Zero),
                      rest
                    )
                      .flatMap(results =>
                        events.offer(
                          Event.Completed(
                            Outcome.Shared(
                              batch,
                              results.map((peer, _, value) =>
                                peer -> value.getOrElse(PeerShare.Result.TimedOut())
                              )
                            )
                          )
                        )
                      )
              yield ()
              supervisor.supervise(phases).void
            }
        }
      case Job.Connect(peer, mode, provenance, generation) =>
        supervisor
          .supervise(actions.establishAt(peer, mode, provenance, generation).flatMap {
            case Right(handle) =>
              events.offer(
                Event
                  .ConnectionCompleted(peer, handle.identity, Outcome.Connected(peer, generation))
              )
            case Left(error) =>
              val failure = error match
                case EstablishmentError.Failed(cause) => FailurePolicy.classify(cause)
                case _                                => FailureClass.Transient
              out.tell(Output.EstablishmentFailed(peer, error)) *>
                events.offer(Event.Completed(Outcome.ConnectFailed(peer, failure, generation)))
          })
          .void
      case Job.Activate(peer, trust) =>
        def outcome(result: Either[PeerActionError, Unit]): F[Outcome[A]] = result match
          case Right(_)    => F.pure[Outcome[A]](Outcome.Activated(peer))
          case Left(error) =>
            // ActivePeers.hs:462–479: preserve the failure, pause one second, then use the
            // existing promotion-failure policy. This does not introduce another backoff.
            val cause = error match
              case PeerActionError.ConnectionFailure(cause)    => Some(cause)
              case PeerActionError.ApplicationFailures(errors) => Some(errors.head.error)
              case _                                           => None
            val failure = cause
              .flatMap(c =>
                Peer.networkFault(c) match
                  case Fault.Failed(problem) => Some(FailurePolicy.classify(problem))
                  case _                     => None
              )
              .getOrElse(FailureClass.Transient)
            out.tell(Output.Traced(Trace.PeerActionFailed(peer, PeerOperation.Activate, error))) *>
              config.reader(_.activationPause).flatMap(F.sleep) *>
              F.pure[Outcome[A]](Outcome.ActivateFailed(peer, failure))
        connectionJob(peer)(
          _.activate(trust).flatMap(outcome),
          actions.activate(peer, trust).flatMap(outcome).map(Some(_))
        )
      case Job.Deactivate(peer) =>
        def outcome(result: PeerStateActions.Result[PeerStateActions.Deactivation]): F[Outcome[A]] =
          result match
            case Right(PeerStateActions.Deactivation.Warm) => F.pure(Outcome.Deactivated(peer))
            case Right(PeerStateActions.Deactivation.Closed) =>
              F.pure(Outcome.DeactivateFailed(peer))
            case Right(PeerStateActions.Deactivation.Lingering) =>
              F.pure(Outcome.CloseTimedOut(peer))
            case Left(failure) =>
              out
                .tell(
                  Output.Traced(
                    Trace.PeerActionFailed(peer, PeerOperation.Deactivate, failure.cause)
                  )
                )
                .as(
                  if failure.release == PeerStateActions.Release.Released then
                    Outcome.DeactivateFailed(peer)
                  else Outcome.CloseTimedOut(peer)
                )
        connectionJob(peer)(
          handle =>
            handle.deactivate.flatMap {
              case result @ Right(PeerStateActions.Deactivation.Warm) => outcome(result)
              case Left(failure) =>
                awaitRelease(handle).flatMap(release =>
                  outcome(Left(failure.copy(release = release)))
                )
              case Right(_) =>
                awaitRelease(handle).flatMap {
                  case PeerStateActions.Release.Released =>
                    outcome(Right(PeerStateActions.Deactivation.Closed))
                  case PeerStateActions.Release.Lingering =>
                    outcome(Right(PeerStateActions.Deactivation.Lingering))
                }
            },
          actions.deactivate(peer).flatMap(outcome).map(Some(_))
        )
      case Job.Close(peer, generation) =>
        def outcome(result: PeerStateActions.Result[PeerStateActions.CloseResult]): F[Outcome[A]] =
          result match
            case Right(result) =>
              out
                .tell(
                  Output.Traced(
                    Trace.PeerActionFailed(
                      peer,
                      PeerOperation.Close,
                      PeerActionError.TimedOut(PeerOperation.Close)
                    )
                  )
                )
                .whenA(result.completion == PeerStateActions.CloseCompletion.TimedOut) *>
                F.pure(
                  if result.release == PeerStateActions.Release.Released then Outcome.Closed(peer)
                  else Outcome.CloseTimedOut(peer)
                )
            case Left(failure) =>
              out
                .tell(
                  Output.Traced(Trace.PeerActionFailed(peer, PeerOperation.Close, failure.cause))
                )
                .as(
                  if failure.release == PeerStateActions.Release.Released then Outcome.Closed(peer)
                  else Outcome.CloseTimedOut(peer)
                )
        val legacy = generation match
          case None => actions.close(peer).flatMap(outcome).map(Some(_))
          case Some(expected) =>
            actions.closeIfGeneration(peer, expected).flatMap(_.traverse(outcome))
        connectionJob(peer, generation)(
          handle =>
            handle.close.flatMap {
              case Left(failure) =>
                awaitRelease(handle).flatMap(release =>
                  outcome(Left(failure.copy(release = release)))
                )
              case Right(result) =>
                awaitRelease(handle).flatMap(release =>
                  outcome(Right(result.copy(release = release)))
                )
            },
          legacy
        )
      case Job.RequestRoots(max, bootstrap, generation) =>
        complete(
          roots
            .request(max, bootstrap)
            .map(
              _.fold[Outcome[A]](Outcome.RootsFailed(generation))((found, ttl) =>
                Outcome.RootsFound(found, ttl, generation)
              )
            )
        )
      case Job.RequestBigLedger(max, generation) =>
        complete(
          roots
            .requestBigLedger(max)
            .map(
              _.fold[Outcome[A]](Outcome.BigLedgerFailed(generation))((found, ttl) =>
                Outcome.BigLedgerFound(found, ttl, generation)
              )
            )
        )

    /** Make a decision the state: the new state, the jobs started, the traces told. */
    def commit(decision: Decision[A]): F[Unit] =
      for
        _ <- state.set(decision.state)
        _ <- decision.traces.traverse_(trace => out.tell(Output.Traced(trace)))
        _ <- decision.jobs.traverse_(start)
        violations = GovernorState.invariant(decision.state)
        _ <- raise
          .raise[GovernorError, Unit](GovernorError.InvariantBroken(violations))
          .whenA(violations.nonEmpty)
      yield ()

    def applySnapshot(event: Event[A]): F[Unit] =
      for
        at <- now
        _ <- beforeEventApplied.traverse_(_(event))
        st <- state.get
        policy <- config.reader(_.policy)
        _ <- commit(PeerSelection.apply(st, event, at, policy))
        _ <- onEventApplied.traverse_(_(event))
      yield ()

    def apply(event: Event[A]): F[Unit] = event match
      case Event.ConnectionCompleted(peer, identity, outcome) =>
        connections.fold(applySnapshot(Event.Completed(outcome))) { source =>
          source.monitorPeerConnection(peer).flatMap {
            case Some(handle) if handle.identity == identity =>
              outcome match
                case connected: Outcome.Connected[A] =>
                  // A monitor can finish before this completion is accepted. Its earlier wakes may
                  // have arrived while Connecting, so reconcile the captured handle now.
                  handle.read.flatMap { current =>
                    applySnapshot(Event.Completed(connected)) *>
                      (current.status match
                        case PeerStatus.Cold | PeerStatus.Cooling =>
                          applySnapshot(
                            Event.Observed(
                              peer,
                              Observation.Lost(
                                current.failure
                                  .fold(FailureClass.Transient)(FailurePolicy.classify),
                                current.repromoteDelay.getOrElse(Duration.Zero),
                                current.status == PeerStatus.Cold,
                                current.failure.fold(
                                  "connection ended before establishment was accepted"
                                )(_.message)
                              ),
                              Some(handle.rootsGeneration),
                              Some(identity)
                            )
                          )
                        case _ => F.unit)
                  }
                case _ => applySnapshot(Event.Completed(outcome))
            case _ => F.unit
          }
        }
      case event @ Event.Observed(peer, observation, generation, Some(identity)) =>
        connections.fold(applySnapshot(event)) { source =>
          source.monitorPeerConnection(peer).flatMap {
            case Some(handle)
                if handle.identity == identity && generation.forall(_ == handle.rootsGeneration) =>
              handle.read.flatMap { current =>
                val observed = (current.status, observation) match
                  case (PeerStateActions.Status.Warm, ended: Observation.HotEnded) =>
                    Some(ended.copy(delay = current.repromoteDelay))
                  case (PeerStateActions.Status.Cooling, loss: Observation.Lost) =>
                    Some(loss.copy(released = false))
                  case (PeerStateActions.Status.Cold, loss: Observation.Lost) =>
                    Some(
                      loss
                        .copy(released = true, delay = current.repromoteDelay.getOrElse(loss.delay))
                    )
                  case (_, action: Observation.ActionFailed)        => Some(action)
                  case (_, cached: Observation.CachedBlockRejected) => Some(cached)
                  case _                                            => None
                observed.traverse_(value =>
                  applySnapshot(Event.Observed(peer, value, generation, Some(identity)))
                )
              }
            case _ => F.unit
          }
        }
      case Event.ChurnTargetsChanged(generation) =>
        readRequestedTargets.traverse_(
          _.flatMap(
            _.filter(_.generation == generation).traverse_(current =>
              applySnapshot(Event.TargetsRequested(current.targets, Some(current.generation)))
            )
          )
        )
      case _ => applySnapshot(event)

    /** Apply the events that are waiting. */
    val drain: F[Unit] =
      F.tailRecM(()) { _ =>
        events.tryTake.flatMap {
          case Some(event) => apply(event).as(Left(()))
          case None        => F.pure(Right(()))
        }
      }

    def publish(loop: Loop, at: Time): F[Loop] =
      for
        st <- state.get
        _ <- connections.traverse_(_.retainConnections(st.peers.keySet.toSet))
        association <- config.reader(_.association)
        _ <- sharing.traverse_(_.publish(st))
        view = st.view(at)
        counters = view.counters
        connections = GovernorState.outboundConnectionsState(association, view)
        _ <- out.tell(Output.Counted(counters)).whenA(!loop.counters.contains(counters))
        _ <- out
          .tell(Output.Connections(connections))
          .whenA(!loop.connections.contains(connections))
      yield loop.copy(counters = Some(counters), connections = Some(connections))

    /** Ask to be woken at `at`: a fiber that offers the wake-up to the queue, so that the loop has
      * one place to wait (the queue) and no race between a timer and a take. A time that is already
      * asked for is not asked for again.
      */
    def wake(at: Time): F[Unit] =
      scheduled.modify(times => if times(at) then (times, false) else (times + at, true)).flatMap {
        case false => F.unit
        case true =>
          now.flatMap { current =>
            supervisor
              .supervise(
                F.sleep((at - current).max(Duration.Zero)) *>
                  scheduled.update(_ - at) *> events.offer(Event.Wake(at))
              )
              .void
          }
      }

    /** One pass: the events, then the decision; the next pass follows at once after a decision, and
      * after the next event (or the wake-up) when there is nothing to do.
      */
    def pass(before: Loop): F[Loop] =
      for
        _ <- drain
        at <- now
        st <- state.get
        _ <- PeerSelection.abort(st, at).traverse_(raise.raise[GovernorError, Unit](_))
        scores <- metrics.ask[PeerMetrics[A]]
        policy <- config.reader(_.policy)
        inbound <- sharing.fold(F.pure(Map.empty[A, Boolean]))(_.inbound)
        loop <- PeerSelection.decide(st, at, scores, policy, inbound) match
          case Step.Act(decision) => commit(decision) *> publish(before, at)
          case Step.Idle(settled, wakeAt) =>
            for
              _ <- state.set(settled)
              published <- publish(before, at)
              _ <- wakeAt.traverse_(wake)
              event <- events.take
              _ <- apply(event)
            yield published
      yield loop

    F.tailRecM(Loop(None, None))(p => pass(p).map(Left(_)))
