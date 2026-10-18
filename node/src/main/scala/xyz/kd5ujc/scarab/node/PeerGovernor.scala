// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.effect.{Clock, Deferred, Ref, Temporal}
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** What the governor asks of the world to move a peer between temperatures (the reference's
  * `PeerStateActions`, `PeerSelection/PeerStateActions.hs` 590-: establish, activate, deactivate,
  * close). A peer is named by its address; whoever implements this keeps the connection. The
  * changes the governor did not ask for (a hot protocol ended, the connection was lost) are not
  * returned here: the implementation reports them as [[Event.Observed]] on the governor's queue.
  */
trait PeerStateActions[F[_], A]:
  /** Open a connection and start the warm protocols; none when it worked. */
  def establish(peer: A, mode: DiffusionMode, provenance: Provenance): F[Option[FailureClass]]

  /** Start the hot protocols on an established connection; none when it worked. */
  def activate(peer: A): F[Option[FailureClass]]

  /** End the hot protocols and keep the connection. */
  def deactivate(peer: A): F[PeerStateActions.Deactivation]

  /** Close the connection and wait for the connection manager to release it; false when it did not
    * within the demotion timeout.
    */
  def close(peer: A): F[Boolean]

object PeerStateActions:
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
  def requestBigLedger(max: Int): F[Option[(Set[A], FiniteDuration)]]

/** What the governor tells the world besides its traces. */
enum Output[A]:
  case Traced(trace: Trace[A])

  /** The counters changed (`PeerSelectionCounters`). */
  case Counted(counters: GovernorState.Counters)

  /** The outbound connections state changed ([[OutboundConnectionsState]]). */
  case Connections(state: OutboundConnectionsState)

  /** The churn governor did something: a round began, a step ended or timed out, it sleeps. */
  case Churned(trace: Churn.Trace)

  /** The ledger-peers worker did something: loaded a snapshot, rebuilt its maps. */
  case LedgerPeers(trace: LedgerPeersTrace)

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

/** The settings of the effect loop. */
final case class GovernorConfig(
    policy: Policy = Policy(),
    association: AssociationMode,
    /** The pause after a failed activation, so a failing promotion cannot spin (`threadDelay 1`,
      * `ActivePeers.hs` 476).
      */
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

  def run[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      supervisor: Supervisor[F],
      sharing: Option[PeerSharingActions[F, A]] = None
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]],
      metrics: Ask[F, PeerMetrics[A]]
  ): F[Nothing] =
    Ref
      .of[F, Set[Time]](Set.empty)
      .flatMap(scheduled => loop(actions, roots, events, supervisor, scheduled, sharing))

  private def loop[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      supervisor: Supervisor[F],
      /** The times a wake-up is already asked for. */
      scheduled: Ref[F, Set[Time]],
      sharing: Option[PeerSharingActions[F, A]]
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
      case Job.Connect(peer, mode, provenance) =>
        complete(
          actions
            .establish(peer, mode, provenance)
            .map(_.fold[Outcome[A]](Outcome.Connected(peer))(Outcome.ConnectFailed(peer, _)))
        )
      case Job.Activate(peer) =>
        complete(
          actions.activate(peer).flatMap {
            case None => F.pure[Outcome[A]](Outcome.Activated(peer))
            case Some(failure) =>
              config.reader(_.activationPause).flatMap(F.sleep) *>
                F.pure[Outcome[A]](Outcome.ActivateFailed(peer, failure))
          }
        )
      case Job.Deactivate(peer) =>
        complete(
          actions.deactivate(peer).map {
            case PeerStateActions.Deactivation.Warm      => Outcome.Deactivated(peer)
            case PeerStateActions.Deactivation.Closed    => Outcome.DeactivateFailed(peer)
            case PeerStateActions.Deactivation.Lingering => Outcome.CloseTimedOut(peer)
          }
        )
      case Job.Close(peer) =>
        complete(
          actions
            .close(peer)
            .map(released => if released then Outcome.Closed(peer) else Outcome.CloseTimedOut(peer))
        )
      case Job.RequestRoots(max, bootstrap) =>
        complete(
          roots
            .request(max, bootstrap)
            .map(
              _.fold[Outcome[A]](Outcome.RootsFailed())((found, ttl) =>
                Outcome.RootsFound(found, ttl)
              )
            )
        )
      case Job.RequestBigLedger(max) =>
        complete(
          roots
            .requestBigLedger(max)
            .map(
              _.fold[Outcome[A]](Outcome.BigLedgerFailed())((found, ttl) =>
                Outcome.BigLedgerFound(found, ttl)
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

    def apply(event: Event[A]): F[Unit] =
      for
        at <- now
        st <- state.get
        policy <- config.reader(_.policy)
        _ <- commit(PeerSelection.apply(st, event, at, policy))
      yield ()

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
