// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.Show
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.network.ChainSyncTimeouts.PeerTrust
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** How a peer failed, as far as the governor cares (owner decision 8, docs/design/peer-selection/
  * decisions-and-risks.md section 8a): a network failure is retried after a backoff; a peer that
  * broke the protocol, sent an invalid header or an oversize message is forgotten. The node maps
  * each of its typed peer errors to one of these in a single function (`FailurePolicy`), which is
  * the one place to change if the policy does.
  */
/** How a peer's failure counts against it: [[Transient]] adds a failure and grows its backoff (the
  * reference's `ShutdownPeer` with the error delay), [[Misbehaved]] forgets it, and [[NotThePeers]]
  * closes the connection without counting a failure, for a failure of the node's own code that
  * shows nothing against the peer (owner decision 2026-10-28, issue 564).
  */
enum FailureClass:
  case Transient, Misbehaved, NotThePeers

/** The temperatures a transition goes between. */
enum Temperature:
  case Cold, Warm, Hot

/** A closed boundary packet retaining another service's typed refusal and its presentation
  * capability. The cause is rendered only by telemetry consumers.
  */
sealed abstract class SourceRefusal extends Product with Serializable:
  type Cause <: DomainError
  val cause: Cause
  def message: String

object SourceRefusal:
  final case class Typed[E <: DomainError](cause: E)(using show: Show[E]) extends SourceRefusal:
    type Cause = E
    def message: String = show.show(cause)

/** A successful application result, retained in typed form until the log renders its reason. */
sealed trait ReturnReason:
  type Result
  def result: Result
  def message: String

object ReturnReason:
  final case class Typed[A](result: A)(using show: Show[A]) extends ReturnReason:
    type Result = A
    def message: String = show.show(result)

/** What the effect layer sees of an established peer's connection when it looks without blocking
  * (`monitorPeerConnection`, `PeerStateActions.hs` 908-950): a change the governor did not ask for
  * is an asynchronous demotion (`Monitor.connections`, `Governor/Monitor.hs` 111-320).
  */
enum Observation:
  /** A typed action diagnostic; it preserves every application failure across the governor
    * boundary.
    */
  case ActionFailed(
      operation: xyz.kd5ujc.scarab.network.PeerOperation,
      error: xyz.kd5ujc.scarab.network.PeerActionError
  )

  /** A hot protocol ended and the connection is still up: the peer is warm. `delay` is how long the
    * exit policy asks to wait before it is activated again.
    */
  case HotEnded(delay: Option[FiniteDuration], reason: Option[ReturnReason] = None)

  /** The connection failed or was closed under us. `released` is false while the connection manager
    * still holds it (`PeerCooling`) and true once it is gone (`PeerCold`); `delay` is the exit
    * policy's wait before the next attempt.
    */
  case Lost(failure: FailureClass, delay: FiniteDuration, released: Boolean, reason: String)

  /** Attribution evidence only. The adding bearer is stopped by its exact live source key; this
    * observation must never demote or close a replacement connection at the same address.
    */
  case CachedBlockRejected(cause: SourceRefusal, sourceGeneration: BigInt, sourceLive: Boolean)

/** The peers a request for public roots found: ordinary public roots, bootstrap peers, and the
  * ledger peers the ledger answered with (when it answers, the configured public roots are not
  * offered: `requestPublicRootPeersImpl`, `PeerSelectionActions.hs` 132-184).
  */
final case class PublicRoots[A](
    ordinary: Set[A],
    bootstrap: Set[A],
    ledger: Set[A] = Set.empty[A],
    advertise: Map[A, Boolean] = Map.empty[A, Boolean]
):
  def isEmpty: Boolean = ordinary.isEmpty && bootstrap.isEmpty && ledger.isEmpty

/** A big-ledger request can fall back to configured public roots. The fallback is ordinary
  * extra-peer discovery, not evidence that the addresses belong to the big-ledger population
  * (`requestPublicRootPeersImpl`, PeerSelectionActions.hs; `jobReqBigLedgerPeers`).
  */
enum BigLedgerRoots[A]:
  case Ledger(peers: Set[A])
  case Fallback(roots: PublicRoots[A])

  def offered: Set[A] = this match
    case Ledger(peers)   => peers
    case Fallback(roots) => roots.ordinary ++ roots.bootstrap ++ roots.ledger

  def origin(peer: A): Origin = this match
    case Ledger(_) => Origin.BigLedger
    case Fallback(roots) =>
      if roots.bootstrap(peer) then Origin.Bootstrap
      else if roots.ledger(peer) then Origin.Ledger
      else Origin.Public

object BigLedgerRoots:
  /** Native `Nothing` falls back; `Just empty` remains an empty ledger response. */
  def fromLedger[A](ledger: Option[Set[A]], configured: PublicRoots[A]): BigLedgerRoots[A] =
    ledger.fold[BigLedgerRoots[A]](Fallback(configured))(Ledger(_))

/** What a job reports when it ends: the governor applies it to the state *as it is then*, so a job
  * that finished after the world moved on (the peer was lost, the targets changed) is detected and
  * traced as aborted (the reference's `Completion`, `Types.hs` 1613-1644, as a closed value).
  */
enum Outcome[A]:
  case Connected(peer: A, generation: BigInt = BigInt(0))
  case ConnectFailed(peer: A, failure: FailureClass, generation: BigInt = BigInt(0))
  case Activated(peer: A)
  case ActivateFailed(peer: A, failure: FailureClass)
  case Deactivated(peer: A)

  /** The hot protocols could not be ended cleanly; the connection was closed. */
  case DeactivateFailed(peer: A)
  case Closed(peer: A)

  /** The connection was closed but the connection manager did not release it in time. */
  case CloseTimedOut(peer: A)

  /** The request for public roots answered, with the time its answer is good for. */
  case RootsFound(found: PublicRoots[A], ttl: FiniteDuration, generation: BigInt = BigInt(0))
  case RootsFailed(generation: BigInt = BigInt(0))

  /** The request for big ledger peers answered, with the time its answer is good for. */
  case BigLedgerFound(found: BigLedgerRoots[A], ttl: FiniteDuration, generation: BigInt = BigInt(0))
  case BigLedgerFailed(generation: BigInt = BigInt(0))

  /** One completed phase of a peer-sharing batch. */
  case Shared(batch: Long, results: List[(A, PeerShare.Result[A])])

/** A piece of work for the effect layer (a job of the reference's `JobPool`). */
enum Job[A]:
  case Connect(peer: A, mode: DiffusionMode, provenance: Provenance, generation: BigInt = BigInt(0))
  case Activate(peer: A, trust: PeerTrust = PeerTrust.Untrustable)
  case Deactivate(peer: A)
  case Close(peer: A, generation: Option[BigInt] = None)

  /** Ask for up to `max` public roots; bootstrap peers when `bootstrap` (the sensitive state). */
  case RequestRoots(max: Int, bootstrap: Boolean, generation: BigInt = BigInt(0))

  /** Ask for up to `max` big ledger peers (`jobReqBigLedgerPeers`). */
  case RequestBigLedger(max: Int, generation: BigInt = BigInt(0))

  /** Parallel requests, reported in a short batch followed by the remaining timeout. */
  case Share(batch: Long, amount: xyz.kd5ujc.scarab.protocol.PeerSharing.Amount, peers: List[A])

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
      failure: FailureClass,
      retryIn: Option[FiniteDuration],
      forgotten: Boolean
  )
  case PromoteAborted(from: Temperature, peer: A)

  /** A decision to move peers down a temperature. Ordinary hot demotion retains scores for all
    * available peers (`TraceDemoteHotPeers`); absence of a metric differs from a measured zero.
    * Local and warm demotions have no native score payload.
    */
  case Demote(
      from: Temperature,
      peers: List[A],
      local: Boolean,
      target: Int,
      current: Int,
      scores: Map[A, Option[Int]] = Map.empty[A, Option[Int]]
  )
  case DemoteDone(from: Temperature, peer: A, current: Int)
  case DemoteFailed(from: Temperature, peer: A, timedOut: Boolean)

  /** A change the governor did not ask for: a hot protocol ended, or a connection was lost. */
  case DemoteAsynchronous(
      peer: A,
      to: Temperature,
      failure: Option[FailureClass],
      reason: Option[String],
      returned: Option[ReturnReason] = None,
      repromoteDelay: Option[FiniteDuration] = None
  )
  case PeerActionFailed(
      peer: A,
      operation: xyz.kd5ujc.scarab.network.PeerOperation,
      error: xyz.kd5ujc.scarab.network.PeerActionError
  )
  case CachedBlockRejected(
      peer: A,
      cause: SourceRefusal,
      sourceGeneration: BigInt,
      sourceLive: Boolean
  )
  case ForgetCold(target: Int, known: Int, peers: List[A])
  case Forgotten(peers: List[A])

  // the big ledger families (`TraceBigLedgerPeersRequest` and the other `*BigLedger*` cases of
  // `TracePeerSelection`, `Types.hs`)
  case BigLedgerRequest(target: Int, current: Int)
  case BigLedgerResults(found: Set[A], backoffs: Int, retryIn: FiniteDuration)
  case BigLedgerFailure(backoffs: Int, retryIn: FiniteDuration)
  case ForgetBigLedger(target: Int, known: Int, peers: List[A])
  case PromoteBigLedger(from: Temperature, peers: List[A], target: Int, current: Int)
  case DemoteBigLedger(
      from: Temperature,
      peers: List[A],
      target: Int,
      current: Int,
      scores: Map[A, Option[Int]] = Map.empty[A, Option[Int]]
  )

  case PickInbound(peers: List[A])
  case ShareRequests(amount: Int, peers: List[A])
  case ShareResults(results: List[(A, PeerShare.Result[A])], added: List[A])

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
  case Observed(
      peer: A,
      observation: Observation,
      generation: Option[BigInt] = None,
      connection: Option[BigInt] = None
  )

  /** A job ended. */
  case Completed(outcome: Outcome[A])

  /** The action ran against this retained connection, even if its address was reused. */
  case ConnectionCompleted(peer: A, connection: BigInt, outcome: Outcome[A])

  /** The churn governor's variable changed (here the regime's targets). */
  case TargetsRequested(targets: Targets, generation: Option[BigInt] = None)
  case ChurnTargetsChanged(generation: BigInt)

  /** The Genesis State Machine's judgement of the ledger changed. */
  case JudgementReported(judgement: LedgerStateJudgement)

  /** A supervised immutable peer-snapshot check completed for one roots generation. */
  case SnapshotVerificationCompleted(generation: BigInt, matches: Boolean)

  /** The configured local roots changed (a reload). */
  case RootsConfigured(
      roots: LocalRoots[A],
      useBootstrapPeers: Boolean,
      configuredPublicPeers: Set[A],
      generation: Option[BigInt] = None
  )

  /** The time the governor asked to be woken at has come (it changes nothing by itself). */
  case Wake(at: Time)

  /** Negotiated willingness on a newly established connection. */
  case SharingNegotiated(peer: A, enabled: Boolean, generation: Option[BigInt] = None)

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
