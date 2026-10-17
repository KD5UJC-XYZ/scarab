// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.effect.{Clock, Ref, Temporal}
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

/** What the governor tells the world besides its traces. */
enum Output[A]:
  case Traced(trace: Trace[A])

  /** The counters changed (`PeerSelectionCounters`). */
  case Counted(counters: GovernorState.Counters)

  /** The outbound connections state changed ([[OutboundConnectionsState]]). */
  case Connections(state: OutboundConnectionsState)

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
  * Each pass applies the events that are waiting, then asks the decision function; a decision is
  * committed (state, traces, jobs) and the pass starts again; when nothing applies the loop waits
  * for an event or for the time the decision function named, whichever comes first.
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
      supervisor: Supervisor[F]
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]]
  ): F[Nothing] =
    Ref
      .of[F, Set[Time]](Set.empty)
      .flatMap(scheduled => loop(actions, roots, events, supervisor, scheduled))

  private def loop[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      supervisor: Supervisor[F],
      /** The times a wake-up is already asked for. */
      scheduled: Ref[F, Set[Time]]
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]]
  ): F[Nothing] =
    val F = Temporal[F]
    given Monad[F] = F

    def now: F[Time] = Clock[F].monotonic

    /** The work of one job, as the outcome the decision function applies. */
    def work(job: Job[A]): F[Outcome[A]] = job match
      case Job.Connect(peer, mode, provenance) =>
        actions
          .establish(peer, mode, provenance)
          .map(_.fold[Outcome[A]](Outcome.Connected(peer))(Outcome.ConnectFailed(peer, _)))
      case Job.Activate(peer) =>
        actions.activate(peer).flatMap {
          case None => F.pure[Outcome[A]](Outcome.Activated(peer))
          case Some(failure) =>
            config.reader(_.activationPause).flatMap(F.sleep) *>
              F.pure[Outcome[A]](Outcome.ActivateFailed(peer, failure))
        }
      case Job.Deactivate(peer) =>
        actions.deactivate(peer).map {
          case PeerStateActions.Deactivation.Warm      => Outcome.Deactivated(peer)
          case PeerStateActions.Deactivation.Closed    => Outcome.DeactivateFailed(peer)
          case PeerStateActions.Deactivation.Lingering => Outcome.CloseTimedOut(peer)
        }
      case Job.Close(peer) =>
        actions
          .close(peer)
          .map(released => if released then Outcome.Closed(peer) else Outcome.CloseTimedOut(peer))
      case Job.RequestRoots(max, bootstrap) =>
        roots
          .request(max, bootstrap)
          .map(
            _.fold[Outcome[A]](Outcome.RootsFailed())((found, ttl) =>
              Outcome.RootsFound(found, ttl)
            )
          )

    /** Start a job in a fiber of the supervisor; it ends by offering its outcome. */
    def start(job: Job[A]): F[Unit] =
      supervisor
        .supervise(work(job).flatMap(outcome => events.offer(Event.Completed(outcome))))
        .void

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
        loop <- PeerSelection.decide(st, at) match
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
