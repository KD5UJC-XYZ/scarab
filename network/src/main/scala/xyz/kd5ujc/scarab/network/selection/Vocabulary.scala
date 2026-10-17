// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** How a peer failed, as far as the governor cares (owner decision 8, docs/design/
  * peer-selection.md section 8a): a network failure is retried after a backoff; a peer that broke
  * the protocol, sent an invalid header or an oversize message is forgotten. The node maps each of
  * its typed peer errors to one of these in a single function (`PeerFailureClass`), which is the
  * one place to change if the policy does.
  */
enum PeerFailure:
  case Transient, Misbehaved

/** The temperatures a transition goes between. */
enum Temperature:
  case Cold, Warm, Hot

/** What the effect layer sees of an established peer's connection when it looks without blocking
  * (`monitorPeerConnection`, `PeerStateActions.hs` 908-950): a change the governor did not ask for
  * is an asynchronous demotion (`Monitor.connections`, `Governor/Monitor.hs` 111-320).
  */
enum Observation:
  /** A hot protocol ended and the connection is still up: the peer is warm. `delay` is how long the
    * exit policy asks to wait before it is activated again.
    */
  case HotEnded(delay: Option[FiniteDuration])

  /** The connection failed or was closed under us. `released` is false while the connection manager
    * still holds it (`PeerCooling`) and true once it is gone (`PeerCold`); `delay` is the exit
    * policy's wait before the next attempt.
    */
  case Lost(failure: PeerFailure, delay: FiniteDuration, released: Boolean)

/** The peers a request for public roots found: ordinary public roots, and bootstrap peers. */
final case class PublicRoots[A](ordinary: Set[A], bootstrap: Set[A]):
  def isEmpty: Boolean = ordinary.isEmpty && bootstrap.isEmpty

/** What a job reports when it ends: the governor applies it to the state *as it is then*, so a job
  * that finished after the world moved on (the peer was lost, the targets changed) is detected and
  * traced as aborted (the reference's `Completion`, `Types.hs` 1613-1644, as a closed value).
  */
enum Outcome[A]:
  case Connected(peer: A)
  case ConnectFailed(peer: A, failure: PeerFailure)
  case Activated(peer: A)
  case ActivateFailed(peer: A, failure: PeerFailure)
  case Deactivated(peer: A)

  /** The hot protocols could not be ended cleanly; the connection was closed. */
  case DeactivateFailed(peer: A)
  case Closed(peer: A)

  /** The connection was closed but the connection manager did not release it in time. */
  case CloseTimedOut(peer: A)

  /** The request for public roots answered, with the time its answer is good for. */
  case RootsFound(found: PublicRoots[A], ttl: FiniteDuration)
  case RootsFailed()

/** A piece of work for the effect layer (a job of the reference's `JobPool`). */
enum Job[A]:
  case Connect(peer: A, mode: DiffusionMode, provenance: Provenance)
  case Activate(peer: A)
  case Deactivate(peer: A)
  case Close(peer: A)

  /** Ask for up to `max` public roots; bootstrap peers when `bootstrap` (the sensitive state). */
  case RequestRoots(max: Int, bootstrap: Boolean)

/** What the governor tells the log: the decision it took or the outcome it applied (the cases of
  * `TracePeerSelection`, `Types.hs` 1646-1840, that G1.1 has).
  */
enum Trace[A]:
  case TargetsChanged(targets: Targets)
  case LocalRootsChanged(before: Int, after: Int)
  case JudgementChanged(judgement: LedgerStateJudgement)
  case OnlyBootstrapPeers()
  case RootsRequest(target: Int, current: Int)
  case RootsResults(found: Set[A], backoffs: Int, retryIn: FiniteDuration)
  case RootsFailure(backoffs: Int, retryIn: FiniteDuration)

  /** A decision to move peers up a temperature. `local` for the local root families. */
  case Promote(
      from: Temperature,
      peers: List[A],
      local: Boolean,
      target: Int,
      current: Int
  )
  case PromoteDone(to: Temperature, peer: A, target: Int, current: Int)
  case PromoteFailed(
      from: Temperature,
      peer: A,
      failure: PeerFailure,
      retryIn: Option[FiniteDuration],
      forgotten: Boolean
  )
  case PromoteAborted(from: Temperature, peer: A)

  /** A decision to move peers down a temperature. */
  case Demote(from: Temperature, peers: List[A], local: Boolean, target: Int, current: Int)
  case DemoteDone(from: Temperature, peer: A, current: Int)
  case DemoteFailed(from: Temperature, peer: A, timedOut: Boolean)

  /** A change the governor did not ask for: a hot protocol ended, or a connection was lost. */
  case DemoteAsynchronous(peer: A, to: Temperature, failure: Option[PeerFailure])
  case ForgetCold(target: Int, known: Int, peers: List[A])
  case Forgotten(peers: List[A])

/** The result of one step of the governor: the new state, the work to start, what to log. */
final case class Decision[A](
    state: GovernorState[A],
    jobs: List[Job[A]],
    traces: List[Trace[A]]
)

/** What the governor does next: act, or wait until `wakeAt` (or for an event, whichever is first).
  */
enum Step[A]:
  case Act(decision: Decision[A])

  /** Nothing to do now; `state` is the state with the time-driven bookkeeping applied. */
  case Idle(state: GovernorState[A], wakeAt: Option[Time])

/** What the effect loop feeds to the governor besides time. */
enum Event[A]:
  /** What a look at the connection of `peer` shows (an asynchronous demotion). */
  case Observed(peer: A, observation: Observation)

  /** A job ended. */
  case Completed(outcome: Outcome[A])

  /** The churn governor's variable changed (here the regime's targets). */
  case TargetsRequested(targets: Targets)

  /** The Genesis State Machine's judgement of the ledger changed. */
  case JudgementReported(judgement: LedgerStateJudgement)

  /** The configured local roots changed (a reload). */
  case RootsConfigured(roots: LocalRoots[A])

/** What ends the governor (the reference's `abortGovernor`, `Governor.hs` 596, and the invariant
  * check): closed, raised through `Raise` by the effect loop.
  */
enum GovernorError:
  /** The state broke an invariant: a bug. */
  case InvariantBroken(violations: List[GovernorState.Violation])

  /** The node did not reach the clean state of the sensitive state within 15 minutes
    * (`BootstrapPeersCriticalTimeoutError`, `Types.hs` 1863-1869): something is wrong with the
    * network or the node, and a restart starts it in the right state.
    */
  case BootstrapPeersCriticalTimeout

  def message: String = this match
    case InvariantBroken(vs) =>
      s"the peer selection state broke an invariant: ${vs.mkString(", ")}"
    case BootstrapPeersCriticalTimeout =>
      "the node did not reach a clean state with only bootstrap peers within 15 minutes"
