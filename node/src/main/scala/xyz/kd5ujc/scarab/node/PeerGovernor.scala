// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Applicative, Monad}
import cats.effect.{Clock, Deferred, Poll, Ref, Temporal}
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust
import xyz.kd5ujc.scarab.network.{
  NetworkError,
  OutboundLimit,
  PeerActionError,
  PeerOperation,
  PeerStatus
}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.node.assertions.{Assertions, Property}
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*

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

  /** Open a connection for one roots generation; stale completion cleanup can target that link.
    * `kind` is what the opt-in outbound limit makes of it, as the governor classifies the peer: a
    * read-only view of the governor's state, which the dial reads when it reserves the connection.
    */
  def establishAt(
      peer: A,
      mode: DiffusionMode,
      provenance: Provenance,
      kind: F[OutboundLimit.Dial],
      generation: BigInt
  ): F[Either[EstablishmentError, PeerStateActions.Connection[F]]]

  /** Start the hot protocols on an established connection, preserving typed failures. */
  def activate(peer: A): F[Either[PeerActionError, Unit]] = activate(peer, PeerTrust.Untrustable)

  /** Activate with the trust flags captured when the governor selected this job. */
  def activate(peer: A, trust: PeerTrust): F[Either[PeerActionError, Unit]]

  /** End the hot protocols and keep the connection. */
  def deactivate(peer: A): F[PeerStateActions.Result[PeerStateActions.Deactivation]]

  /** `peer` is now a local root: the opt-in outbound limit leaves its connection out, so the place
    * the connection holds, if any, is given back (`Connection.exempt` of the connection manager).
    * Nothing for a peer with no connection.
    */
  def exempt(peer: A): F[Unit]

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
  /** @param peerSharing
    *   Whether the connection's handshake negotiated peer sharing.
    * @param exempt
    *   Leave this connection out of the opt-in outbound limit: the place it holds, if any, is given
    *   back (`Connection.exempt` of the connection manager), and no other connection's.
    */
  final case class Connection[F[_]](
      identity: BigInt,
      rootsGeneration: BigInt,
      peerSharing: Boolean,
      read: F[Monitoring],
      activate: PeerTrust => F[Either[PeerActionError, Unit]],
      deactivate: F[Result[Deactivation]],
      close: F[Result[CloseResult]],
      awaitCold: F[Unit],
      exempt: F[Unit]
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
  * [[GovernorError]]. Jobs run in fibers of the `Supervisor`, forked through a [[JobPool]] with the
  * handler that turns an exception of the job into its completion, so each job ends by offering its
  * outcome to the queue, the handler's if it raised; a job that is cancelled at shutdown leaves no
  * claim behind. Events arrive on the same queue from the connections, the Genesis State Machine
  * and the regime of targets.
  *
  * The peer metrics and what the churn governor last wrote ([[PeerGovernor.ChurnWritten]]) are read
  * through `Ask` once per pass: targets written but not yet announced are taken, and the decision
  * function ranks the hot peers for demotion by the metrics and the churn mode. Each pass applies
  * the events that are waiting, then asks the decision function; a decision is committed (state,
  * traces, jobs) and the pass starts again; when nothing applies the loop waits for an event or for
  * the time the decision function named, whichever comes first.
  */
object PeerGovernor:
  /** A demotion, asserted from its trace: one the governor commanded and that took effect
    * ([[Trace.DemoteDone]]), or one it did not ask for ([[Trace.DemoteAsynchronous]]), whose typed
    * return reason says whether the peer's chain no longer intersects ours. Every other trace, a
    * decision to demote and a demotion that failed among them, asserts nothing.
    */
  private[node] def demoted[F[_]: Applicative, A](trace: Trace[A])(using
      assertions: Assertions[F]
  ): F[Unit] = trace match
    case Trace.DemoteDone(from, peer, _) =>
      assertions.sometimes(Property.Sometimes.PeerDemoted, true, Demotion(peer.toString, from))
    case Trace.DemoteAsynchronous(peer, to, _, _, returned, _) =>
      def demotion = Demotion(peer.toString, to, returned)
      assertions.sometimes(Property.Sometimes.PeerDemotedAsynchronously, true, demotion) *>
        assertions.sometimes(
          Property.Sometimes.PeerNoLongerIntersects,
          returned.contains(ReturnReason.NoMoreIntersection),
          demotion
        )
    case Trace.TargetsChanged(_) | Trace.LocalRootsChanged(_, _) | Trace.JudgementChanged(_) |
        Trace.OnlyBootstrapPeers() | Trace.RootsRequest(_, _) | Trace.RootsResults(_, _, _) |
        Trace.RootsFailure(_, _, _) | Trace.Promote(_, _, _, _, _) | Trace.PromoteDone(_, _, _, _) |
        Trace.PromoteFailed(_, _, _, _, _) | Trace.PromoteAborted(_, _) |
        Trace.Demote(_, _, _, _, _, _) | Trace.DemoteFailed(_, _, _) |
        Trace.PeerActionFailed(_, _, _) | Trace.CachedBlockRejected(_, _, _, _) |
        Trace.ForgetCold(_, _, _) | Trace.Forgotten(_) | Trace.BigLedgerRequest(_, _) |
        Trace.BigLedgerResults(_, _, _) | Trace.BigLedgerFailure(_, _, _) |
        Trace.ForgetBigLedger(_, _, _) | Trace.PromoteBigLedger(_, _, _, _) |
        Trace.DemoteBigLedger(_, _, _, _, _) | Trace.PickInbound(_) | Trace.ShareRequests(_, _) |
        Trace.ShareResults(_, _) =>
      Applicative[F].unit

  /** What the assertion of a demotion reports: the peer, the temperature it left (commanded) or
    * went to (asynchronous), and the return reason of an asynchronous one.
    */
  private final case class Demotion(
      peer: String,
      temperature: Temperature,
      returned: Option[ReturnReason] = None
  )

  private object Demotion:
    given Encoder.AsObject[Demotion] = Encoder.AsObject.instance(d =>
      JsonObject(
        "peer" -> d.peer.asJson,
        "temperature" -> d.temperature.toString.asJson,
        "returned" -> d.returned.map(_.productPrefix).asJson
      )
    )

  /** What the loop remembers between two passes: what was last published, so that only changes are
    * told.
    */
  private final case class Loop(
      counters: Option[GovernorState.Counters],
      connections: Option[OutboundConnectionsState]
  )

  /** A validated observation of the churn target TVar, including its topology generation. */
  final case class RequestedTargets(targets: Targets, generation: BigInt)

  /** What the churn governor last wrote, read at once: the churn mode the hot-demotion pick ranks
    * by and the targets it asked for, if they are sane. The churn governor writes both in one
    * transaction (`updateTargets`, `Cardano/Network/PeerSelection/Churn.hs` 241-251 at
    * cardano-diffusion c5117705f6d5f8ea57ce3e772b6f9c35bfdb9167), and the governor reads both in
    * one, its targets monitor before its demotions (`Governor.hs` 666-714 at ouroboros-network
    * c45735a56c567fa977969173d18943bac6bb3821): targets the monitor takes are in force before a
    * demotion ranks by the mode written with them. Targets it does not take (none, insane, or while
    * the node cannot make progress: `Cardano/Network/PeerSelection/Governor/Monitor.hs` 117-149)
    * leave the targets in force, and the demotion ranks by the new mode against them.
    */
  final case class ChurnWritten(mode: ChurnMode, targets: Option[RequestedTargets])

  object ChurnWritten:
    /** Before the churn governor wrote anything (`churnModeVar`'s first value). */
    val Initial: ChurnWritten = ChurnWritten(ChurnMode.Initial, None)

  def run[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      supervisor: Supervisor[F],
      sharing: Option[PeerSharingActions[F, A]] = None,
      beforeEventApplied: Option[Event[A] => F[Unit]] = None,
      onEventApplied: Option[Event[A] => F[Unit]] = None,
      connections: Option[PeerStateActions.Connections[F, A]] = None
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]],
      metrics: Ask[F, PeerMetrics[A]],
      churn: Ask[F, ChurnWritten],
      assertions: Assertions[F]
  ): F[Nothing] =
    (
      Ref.of[F, Set[Time]](Set.empty),
      Ref.of[F, ChurnMode](ChurnMode.Initial),
      Ref.of[F, Map[A, (Observation, Option[BigInt], Option[BigInt])]](Map.empty),
      Ref.of[F, Map[Long, F[Unit]]](Map.empty)
    ).tupled
      .flatMap((scheduled, paired, held, phases) =>
        loop(
          actions,
          roots,
          events,
          JobPool[F, Event[A]](supervisor, events.offer),
          scheduled,
          paired,
          held,
          phases,
          sharing,
          beforeEventApplied,
          onEventApplied,
          connections
        )
      )

  /** @param jobs
    *   The only way the loop starts a fiber: each with its handler, completing to the queue.
    * @param scheduled
    *   The times a wake-up is already asked for.
    * @param paired
    *   The churn mode written with the targets the governor took last.
    * @param held
    *   For each peer being demoted, the last report of its connection that came meanwhile (a loss,
    *   or the end of its hot protocols), which the demotion's job leaves to its completion.
    * @param phases
    *   For each peer-sharing batch whose first phase is done, the start of its second phase, which
    *   the governor starts once it has applied the first phase's completion.
    */
  private def loop[F[_]: Temporal, A](
      actions: PeerStateActions[F, A],
      roots: RootProvider[F, A],
      events: Queue[F, Event[A]],
      jobs: JobPool[F, Event[A]],
      scheduled: Ref[F, Set[Time]],
      paired: Ref[F, ChurnMode],
      held: Ref[F, Map[A, (Observation, Option[BigInt], Option[BigInt])]],
      phases: Ref[F, Map[Long, F[Unit]]],
      sharing: Option[PeerSharingActions[F, A]],
      beforeEventApplied: Option[Event[A] => F[Unit]],
      onEventApplied: Option[Event[A] => F[Unit]],
      connections: Option[PeerStateActions.Connections[F, A]]
  )(using
      state: Stateful[F, GovernorState[A]],
      config: Ask[F, GovernorConfig],
      raise: Raise[F, GovernorError],
      out: Tell[F, Output[A]],
      metrics: Ask[F, PeerMetrics[A]],
      churn: Ask[F, ChurnWritten],
      assertions: Assertions[F]
  ): F[Nothing] =
    val F = Temporal[F]
    given Monad[F] = F

    def now: F[Time] = Clock[F].monotonic

    /** Start an ordinary job and report its outcome to its owner, or its handler's. */
    def complete(job: JobPool.Job[F, Outcome[A]]): F[Unit] =
      jobs.forkJob(job.map(Event.Completed(_))).void

    // Capture the retained connection in the governor fiber, before supervising the action.
    // ActivePeers.hs (c45735a5):167–181,450–461; EstablishedPeers.hs:971–978.
    def connectionJob(peer: A, generation: Option[BigInt] = None)(
        run: PeerStateActions.Connection[F] => JobPool.Job[F, Outcome[A]],
        legacy: JobPool.Job[F, Option[Outcome[A]]]
    ): F[Unit] = connections match
      case None =>
        jobs
          .completingTo[Option[Outcome[A]]](
            _.traverse_(value => events.offer(Event.Completed(value)))
          )
          .forkJob(legacy)
          .void
      case Some(source) =>
        source.monitorPeerConnection(peer).flatMap {
          case Some(handle) if generation.forall(_ == handle.rootsGeneration) =>
            jobs
              .forkJob(run(handle).map(Event.ConnectionCompleted(peer, handle.identity, _)))
              .void
          case _ => F.unit
        }

    /** What a handler tells of the exception it met, where telling cannot keep the completion from
      * the governor: an output that cannot be written is the log's failure (#1147), not a reason to
      * leave the peer in its job's state.
      */
    def report(output: Output[A]): F[Unit] = out.tell(output).attempt.void

    /** How an exception of the node's own code in a job counts against its peer: not at all, as for
      * an exception of ours while connecting (`EstablishmentError.Defect`, issue 567), where the
      * reference's handlers count it (`reportFailures`, `EstablishedPeers.hs` 532-540 and
      * `ActivePeers.hs` 500-509 at c45735a5).
      */
    def defect(cause: Throwable): FailureClass =
      FailurePolicy.classify(PeerError.ConnectionDefect(cause))

    /** The trace of an action whose job raised, its exception kept as the defect it is. */
    def raised(peer: A, operation: PeerOperation, cause: Throwable): Output[A] =
      Output.Traced(
        Trace.PeerActionFailed(
          peer,
          operation,
          PeerActionError.ConnectionFailure(NetworkError.Defect(cause))
        )
      )

    // Governor demotion jobs, not PeerStateActions, own the manager-release deadline.
    // ActivePeers.hs (c45735a5):987–998; EstablishedPeers.hs:971–992.
    def awaitRelease(handle: PeerStateActions.Connection[F]): F[PeerStateActions.Release] =
      config.reader(_.policy.demotionTimeout).flatMap { timeout =>
        F.timeoutTo(
          handle.awaitCold.as(PeerStateActions.Release.Released),
          timeout,
          F.pure(PeerStateActions.Release.Lingering)
        )
      }

    /** Start a job in a fiber of the supervisor, with its handler; it ends by offering its outcome,
      * or its handler's.
      */
    def start(job: Job[A]): F[Unit] = job match
      case Job.Share(batch, amount, peers) =>
        config.reader(_.policy).flatMap { policy =>
          type Pending = List[(A, Deferred[F, PeerShare.Result[A]])]
          def shared(results: List[(A, PeerShare.Result[A])]): Event[A] =
            Event.Completed(Outcome.Shared(batch, results))
          // A phase that raises reports its cause for each of its peers, which are then no longer in
          // flight, and leaves the time each is next due as it was (`handler`, `KnownPeers.hs`
          // 280-289 at c45735a5).
          def raised(of: List[A])(cause: Throwable): Event[A] =
            shared(of.map(_ -> PeerShare.Result.PhaseFailed(NetworkError.Defect(cause))))
          // Requests belong to the supervisor, not to the governor's short wait. A logical
          // timeout stops waiting for discovery; the bounded wire request still drains its reply
          // before this client can be reused (as the reference's protocol controller does). Each
          // is the reference's `async` of its request, whose exception or cancellation the phase
          // takes as its result when it ends (`waitAllCatchOrTimeout`, `KnownPeers.hs` 589-600 at
          // c45735a5, by `waitCatchSTM`).
          val requested: F[Pending] = peers.traverse { peer =>
            Deferred[F, PeerShare.Result[A]]
              .flatTap { result =>
                val request = sharing.fold(
                  F.pure[PeerShare.Result[A]](PeerShare.Result.NotRegistered())
                )(_.request(peer, amount))
                jobs
                  .completingTo[PeerShare.Result[A]](result.complete(_).void)
                  .forkJob(
                    JobPool.Job.pure(
                      F.onCancel(request, result.complete(PeerShare.Result.Cancelled()).void),
                      cause => PeerShare.Result.Failed(NetworkError.Defect(cause))
                    )
                  )
                  .void
              }
              .map(peer -> _)
          }
          def collect(wait: FiniteDuration, remaining: Pending) =
            F.race(F.sleep(wait), remaining.traverse_(_._2.get)).flatMap { _ =>
              remaining.traverse { (peer, result) =>
                result.tryGet.map(value => (peer, result, value))
              }
            }
          // The second phase waits for the rest up to the overall timeout from when it starts
          // (`jobPhase2`, `KnownPeers.hs` 385-), which is once the governor has applied the first
          // phase's completion, as the reference's governor forks the completion's `decisionJobs`
          // (378-382) after applying it (`Governor.hs` 614-638).
          def second(rest: Pending) =
            JobPool.Job.pure(
              collect(
                (policy.peerShareOverallTimeout - policy.peerShareBatchWaitTime)
                  .max(Duration.Zero),
                rest
              ).map(results =>
                shared(
                  results
                    .map((peer, _, value) => peer -> value.getOrElse(PeerShare.Result.TimedOut()))
                )
              ),
              raised(rest.map(_._1))
            )
          // The first phase starts the requests and gives the answers of the batch wait, and the
          // start of the second phase for the rest (`jobPhase1`, `KnownPeers.hs` 268 and 291-383);
          // what it raises is its handler's, for all of its peers, and has no second phase.
          val first = JobPool.Job.pure[F, (Event[A], Option[F[Unit]])](
            for
              pending <- requested
              results <- collect(policy.peerShareBatchWaitTime, pending)
              done = results.collect { case (peer, _, Some(result)) => peer -> result }
              rest = results.collect { case (peer, result, None) => peer -> result }
            yield (shared(done), Option.when(rest.nonEmpty)(jobs.forkJob(second(rest)).void)),
            cause => (raised(peers)(cause), None)
          )
          // the second phase is registered before the completion that starts it is offered, and
          // withdrawn if the offer raises, so that a fallback cannot be followed by it
          jobs
            .completingTo[(Event[A], Option[F[Unit]])]((completion, next) =>
              next.traverse_(start => phases.update(_.updated(batch, start))) *>
                events.offer(completion).onError(_ => phases.update(_ - batch))
            )
            .forkJob(first)
            .void
        }
      case Job.Connect(peer, mode, provenance, generation) =>
        // the governor's classification of the peer for the outbound limit, read by the dial when
        // it reserves the connection: the state then, not the state of this decision
        val classification = state.get.map(_.dialOf(peer))
        def failed(failure: FailureClass): Event[A] =
          Event.Completed(Outcome.ConnectFailed(peer, failure, generation))
        // `promoteColdPeer`'s handler (`EstablishedPeers.hs` 505-568 at c45735a5): the failure is
        // recorded with its back-off, and the peer is no longer connecting. `establishAt` gives the
        // exceptions of the connection as values, so what reaches here is the job's own
        def handler(cause: Throwable): F[Event[A]] =
          report(Output.EstablishmentFailed(peer, EstablishmentError.Defect(cause)))
            .as(failed(defect(cause)))
        jobs
          .forkJob(
            JobPool.Job(
              actions.establishAt(peer, mode, provenance, classification, generation).flatMap {
                case Right(handle) =>
                  F.pure(
                    Event.ConnectionCompleted(
                      peer,
                      handle.identity,
                      Outcome.Connected(peer, generation, Some(handle.peerSharing))
                    )
                  )
                case Left(error) =>
                  val failure = error match
                    case EstablishmentError.Failed(cause) => FailurePolicy.classify(cause)
                    // an exception of our own while connecting: nothing against the peer
                    // (issue 567)
                    case EstablishmentError.Defect(cause) => defect(cause)
                    case EstablishmentError.Superseded    => FailureClass.Transient
                  report(Output.EstablishmentFailed(peer, error)).as(failed(failure))
              },
              (cause, _) => handler(cause),
              cause => failed(defect(cause))
            )
          )
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
            report(Output.Traced(Trace.PeerActionFailed(peer, PeerOperation.Activate, error))) *>
              config.reader(_.activationPause).flatMap(F.sleep) *>
              F.pure[Outcome[A]](Outcome.ActivateFailed(peer, failure))
        def last(cause: Throwable): Outcome[A] =
          Outcome.ActivateFailedLingering(peer, defect(cause))
        // What a close of the connection released, its failure reported; one that raised leaves the
        // connection held as far as the governor knows.
        def closing(close: F[PeerStateActions.Result[PeerStateActions.CloseResult]])(
            poll: Poll[F]
        ): F[PeerStateActions.Release] =
          poll(close)
            .flatMap {
              case Right(result) => F.pure(result.release)
              case Left(failure) =>
                report(
                  Output.Traced(Trace.PeerActionFailed(peer, PeerOperation.Close, failure.cause))
                ).as(failure.release)
            }
            .handleErrorWith(problem =>
              report(raised(peer, PeerOperation.Close, problem))
                .as(PeerStateActions.Release.Lingering)
            )
        // `promoteWarmPeer`'s handler (`ActivePeers.hs` 460-558 at c45735a5): the pause, then the
        // failed promotion, which the governor applies while the peer is still activating and
        // traces as aborted otherwise. The exception is of the node's own code on the connection,
        // so the connection is closed first, as such an exception ends its connection (ADR 0017,
        // amendment of 2026-10-28). Released, the peer leaves the established set with it; still
        // held, it is cooling until the release is seen (`Governor/Monitor.hs` 292-296).
        def failed(close: Poll[F] => F[PeerStateActions.Release])(
            cause: Throwable,
            poll: Poll[F]
        ): F[Outcome[A]] =
          report(raised(peer, PeerOperation.Activate, cause)) *> close(poll).flatMap(release =>
            poll(config.reader(_.activationPause).flatMap(F.sleep)).as(release match
              case PeerStateActions.Release.Released  => Outcome.ActivateFailed(peer, defect(cause))
              case PeerStateActions.Release.Lingering => last(cause))
          )
        connectionJob(peer)(
          handle =>
            JobPool.Job(
              handle.activate(trust).flatMap(outcome),
              failed(closing(handle.close)),
              last
            ),
          JobPool
            .Job(
              actions.activate(peer, trust).flatMap(outcome),
              failed(closing(actions.close(peer))),
              last
            )
            .map(Some(_))
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
              report(
                Output.Traced(Trace.PeerActionFailed(peer, PeerOperation.Deactivate, failure.cause))
              ).as(
                if failure.release == PeerStateActions.Release.Released then
                  Outcome.DeactivateFailed(peer)
                else Outcome.CloseTimedOut(peer)
              )
        def released(release: PeerStateActions.Release): Outcome[A] = release match
          case PeerStateActions.Release.Released  => Outcome.DeactivateFailed(peer)
          case PeerStateActions.Release.Lingering => Outcome.CloseTimedOut(peer)
        def reported(cause: Throwable): F[Unit] =
          report(raised(peer, PeerOperation.Deactivate, cause))
        // `demoteActivePeer`'s handler (`ActivePeers.hs` 980-1039 at c45735a5): wait for the
        // connection to go cold, up to the demotion timeout; cold, the peer is neither hot nor
        // established, and tepid; still held, it stays, cooling
        def handler(handle: PeerStateActions.Connection[F])(
            cause: Throwable,
            poll: Poll[F]
        ): F[Outcome[A]] =
          reported(cause) *> poll(awaitRelease(handle)).map(released)
        // with no connection to watch, it is not seen cold: the handler's timeout
        def unwatched(cause: Throwable): F[Outcome[A]] =
          reported(cause).as[Outcome[A]](Outcome.CloseTimedOut(peer))
        connectionJob(peer)(
          handle =>
            JobPool.Job(
              handle.deactivate.flatMap {
                case result @ Right(PeerStateActions.Deactivation.Warm) => outcome(result)
                case Left(failure) =>
                  awaitRelease(handle).flatMap(release =>
                    outcome(Left(failure.copy(release = release)))
                  )
                case Right(_) => awaitRelease(handle).map(released)
              },
              handler(handle),
              _ => Outcome.CloseTimedOut(peer)
            ),
          JobPool
            .Job(
              actions.deactivate(peer).flatMap(outcome),
              (cause, _) => unwatched(cause),
              _ => Outcome.CloseTimedOut(peer)
            )
            .map(Some(_))
        )
      case Job.Close(peer, generation) =>
        def outcome(result: PeerStateActions.Result[PeerStateActions.CloseResult]): F[Outcome[A]] =
          result match
            case Right(result) =>
              report(
                Output.Traced(
                  Trace.PeerActionFailed(
                    peer,
                    PeerOperation.Close,
                    PeerActionError.TimedOut(PeerOperation.Close)
                  )
                )
              ).whenA(result.completion == PeerStateActions.CloseCompletion.TimedOut) *>
                F.pure(
                  if result.release == PeerStateActions.Release.Released then Outcome.Closed(peer)
                  else Outcome.CloseTimedOut(peer)
                )
            case Left(failure) =>
              report(
                Output.Traced(Trace.PeerActionFailed(peer, PeerOperation.Close, failure.cause))
              )
                .as(
                  if failure.release == PeerStateActions.Release.Released then Outcome.Closed(peer)
                  else Outcome.CloseTimedOut(peer)
                )
        def released(release: PeerStateActions.Release): Outcome[A] = release match
          case PeerStateActions.Release.Released  => Outcome.Closed(peer)
          case PeerStateActions.Release.Lingering => Outcome.CloseTimedOut(peer)
        def reported(cause: Throwable): F[Unit] = report(raised(peer, PeerOperation.Close, cause))
        // `demoteEstablishedPeer`'s handler (`EstablishedPeers.hs` 909-967 at c45735a5): wait for
        // the connection to go cold, up to the demotion timeout; cold, the peer is no longer
        // established; still held, it stays, cooling
        def handler(handle: PeerStateActions.Connection[F])(
            cause: Throwable,
            poll: Poll[F]
        ): F[Outcome[A]] =
          reported(cause) *> poll(awaitRelease(handle)).map(released)
        val legacy = generation match
          // with no connection to watch, it is not seen cold: the handler's timeout
          case None =>
            JobPool
              .Job(
                actions.close(peer).flatMap(outcome),
                (cause, _) => reported(cause).as[Outcome[A]](Outcome.CloseTimedOut(peer)),
                _ => Outcome.CloseTimedOut(peer)
              )
              .map(Some(_))
          // nothing says the link that raised was the generation's, and no close of it is in
          // progress: the peer is cold
          case Some(expected) =>
            JobPool.Job(
              actions.closeIfGeneration(peer, expected).flatMap(_.traverse(outcome)),
              (cause, _) => reported(cause).as(Option.empty[Outcome[A]]),
              _ => None
            )
        connectionJob(peer, generation)(
          handle =>
            JobPool.Job(
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
              handler(handle),
              _ => Outcome.CloseTimedOut(peer)
            ),
          legacy
        )
      // The connection to leave out is the one the peer has when the governor decides it, captured
      // here in the governor fiber, as `connectionJob` captures the others: a later connection to
      // the peer, which the decision did not see, keeps its place. No outcome: the governor's state
      // already says the peer is a local root. Scarab's own, so its handler is too: an exception
      // of the limit's bookkeeping leaves that place held until the connection ends, and ends the
      // fiber with nothing to report.
      case Job.Exempt(peer) =>
        def exempt(action: F[Unit]): F[Unit] =
          jobs.completingTo[Unit](_ => F.unit).forkJob(JobPool.Job.pure(action, _ => ())).void
        connections match
          case None => exempt(actions.exempt(peer))
          case Some(source) =>
            source.monitorPeerConnection(peer).flatMap(_.traverse_(handle => exempt(handle.exempt)))
      case Job.RequestRoots(max, bootstrap, generation) =>
        // `jobReqPublicRootPeers`'s handler (`RootPeers.hs` 125-155 at c45735a5): a failed
        // request, one back-off step further, with its exception
        def handler(cause: Throwable): Outcome[A] = Outcome.RootsFailed(generation, Some(cause))
        complete(
          JobPool.Job.pure(
            roots
              .request(max, bootstrap)
              .map(
                _.fold[Outcome[A]](Outcome.RootsFailed(generation))((found, ttl) =>
                  Outcome.RootsFound(found, ttl, generation)
                )
              ),
            handler
          )
        )
      case Job.RequestBigLedger(max, generation) =>
        // `jobReqBigLedgerPeers`'s handler (`BigLedgerPeers.hs` 134-162 at c45735a5): the same
        // for big ledger peers
        def handler(cause: Throwable): Outcome[A] = Outcome.BigLedgerFailed(generation, Some(cause))
        complete(
          JobPool.Job.pure(
            roots
              .requestBigLedger(max)
              .map(
                _.fold[Outcome[A]](Outcome.BigLedgerFailed(generation))((found, ttl) =>
                  Outcome.BigLedgerFound(found, ttl, generation)
                )
              ),
            handler
          )
        )

    /** Make a decision the state: the new state, the jobs started, the traces told. */
    def commit(decision: Decision[A]): F[Unit] =
      for
        _ <- state.set(decision.state)
        _ <- decision.traces.traverse_(trace =>
          out.tell(Output.Traced(trace)) *> demoted[F, A](trace)
        )
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

    /** What the monitor reads on a connection that is cooling or gone: its loss, released or not
      * (`Governor/Monitor.hs` 292-308 at c45735a5).
      */
    def lost(current: PeerStateActions.Monitoring, reason: String): Observation =
      Observation.Lost(
        current.failure.fold(FailureClass.Transient)(FailurePolicy.classify),
        current.repromoteDelay.getOrElse(Duration.Zero),
        current.status == PeerStatus.Cold,
        current.failure.fold(reason)(_.message)
      )

    /** Apply what was read on the connection `handle` names, as an observation of it. */
    def observed(
        peer: A,
        handle: PeerStateActions.Connection[F],
        observation: Observation
    ): F[Unit] =
      applySnapshot(
        Event.Observed(peer, observation, Some(handle.rootsGeneration), Some(handle.identity))
      )

    /** A report of a peer's connection that comes while the peer is being demoted, which the
      * decisions leave to the demotion's job, is held, the last one only. A demotion that times out
      * keeps the peer's place, and the monitor then demotes it by what its connection last reported
      * (`Governor/Monitor.hs` 292-308 at c45735a5), so its completion applies the report held; any
      * other end of the demotion drops it.
      */
    def observe(
        peer: A,
        observation: Observation,
        generation: Option[BigInt],
        identity: Option[BigInt]
    ): F[Unit] =
      state.get.flatMap { st =>
        val demoting = st.peers
          .get(peer)
          .exists(_.status match
            case Status.Closing(_) | Status.Deactivating => true
            case Status.Cold(_) | Status.Connecting | Status.Warm(_) | Status.Activating |
                Status.Hot | Status.Cooling =>
              false)
        val ofConnection = observation match
          case Observation.Lost(_, _, _, _) | Observation.HotEnded(_, _)                 => true
          case Observation.ActionFailed(_, _) | Observation.CachedBlockRejected(_, _, _) => false
        held
          .update(_.updated(peer, (observation, generation, identity)))
          .whenA(demoting && ofConnection)
      } *> applySnapshot(Event.Observed(peer, observation, generation, identity))

    /** Apply a job's completion, and what follows from it in the governor: the report held while a
      * demotion timed out, or the second phase of a peer-sharing batch.
      */
    def completed(outcome: Outcome[A], identity: Option[BigInt]): F[Unit] =
      applySnapshot(Event.Completed(outcome)) *> (outcome match
        case Outcome.CloseTimedOut(peer) =>
          held
            .modify(m => (m - peer, m.get(peer)))
            .flatMap(_.traverse_ { (observation, generation, observed) =>
              applySnapshot(Event.Observed(peer, observation, generation, observed))
                .whenA(identity.forall(id => observed.forall(_ == id)))
            })
        case Outcome.Deactivated(peer)      => held.update(_ - peer)
        case Outcome.DeactivateFailed(peer) => held.update(_ - peer)
        case Outcome.Closed(peer)           => held.update(_ - peer)
        case Outcome.Shared(batch, _) =>
          phases.modify(m => (m - batch, m.get(batch))).flatMap(_.sequence_)
        case Outcome.Connected(_, _, _) | Outcome.ConnectFailed(_, _, _) | Outcome.Activated(_) |
            Outcome.ActivateFailed(_, _) | Outcome.ActivateFailedLingering(_, _) |
            Outcome.RootsFound(_, _, _) | Outcome.RootsFailed(_, _) |
            Outcome.BigLedgerFound(_, _, _) | Outcome.BigLedgerFailed(_, _) =>
          F.unit)

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
                          observed(
                            peer,
                            handle,
                            lost(current, "connection ended before establishment was accepted")
                          )
                        case _ => F.unit)
                  }
                case _ => completed(outcome, Some(identity))
            case _ => F.unit
          }
        }
      case Event.Observed(peer, observation, generation, Some(identity)) =>
        connections.fold(observe(peer, observation, generation, Some(identity))) { source =>
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
                observed.traverse_(value => observe(peer, value, generation, Some(identity)))
              }
            case _ => F.unit
          }
        }
      case Event.ChurnTargetsChanged(generation) =>
        churn
          .ask[ChurnWritten]
          .flatMap(written =>
            written.targets.filter(_.generation == generation).traverse_(take(written, _))
          )
      case Event.Observed(peer, observation, generation, None) =>
        observe(peer, observation, generation, None)
      case Event.Completed(outcome) => completed(outcome, None)
      case _                        => applySnapshot(event)

    /** Take targets the churn governor wrote, and remember the mode written with them if they were
      * taken (a topology generation not yet in force does not take them).
      */
    def take(written: ChurnWritten, requested: RequestedTargets): F[Unit] =
      applySnapshot(Event.TargetsRequested(requested.targets, Some(requested.generation))) *>
        state.get.flatMap(st => paired.set(written.mode).whenA(st.requested == requested.targets))

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
        _ <- connections.traverse_(_.retainConnections(Set.from(st.peers.keySet)))
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
            // due however its timer ends, so that the time can be asked for again
            val due = scheduled.update(_ - at).as[Event[A]](Event.Wake(at))
            jobs
              .forkJob(
                JobPool.Job(
                  F.sleep((at - current).max(Duration.Zero)) *> due,
                  (_, _) => due,
                  _ => Event.Wake(at)
                )
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
        written <- churn.ask[ChurnWritten]
        // targets written with the churn mode are taken before a demotion ranks by it, as the
        // targets monitor comes before the demotions (`Governor.hs` 685-714 at c45735a5); the
        // event that announces them finds them taken
        _ <- state.get.flatMap(st =>
          written.targets
            .filter(t => t.generation == st.rootsGeneration && t.targets != st.requested)
            .traverse_(take(written, _))
        )
        at <- now
        st <- state.get
        _ <- PeerSelection.abort(st, at).traverse_(raise.raise[GovernorError, Unit](_))
        scores <- metrics.ask[PeerMetrics[A]]
        // the hot-demotion pick reads the churn mode where it reads the metrics (`Policies.hs`
        // 33-36 at cardano-diffusion c5117705f6d5f8ea57ce3e772b6f9c35bfdb9167); while the targets
        // written with it are of a topology generation not yet in force, the mode written with
        // the targets in force stays
        mode <-
          if written.targets.forall(_.targets == st.requested) then
            paired.set(written.mode).as(written.mode)
          else paired.get
        policy <- config.reader(_.policy)
        inbound <- sharing.fold(F.pure(Map.empty[A, Boolean]))(_.inbound)
        loop <- PeerSelection.decide(st, at, scores, mode, policy, inbound) match
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
