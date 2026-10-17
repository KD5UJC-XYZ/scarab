// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.FiniteDuration

/** A reading of the monotonic clock, as an offset from its origin. The governor takes `now` as an
  * argument and keeps every deadline in its state as a `Time`, so a test drives it with any clock.
  */
type Time = FiniteDuration

/** The temperature of a peer, with the transitions in progress.
  *
  * The reference keeps the temperature in nested sets (known, established, active) and, next to
  * them, the sets of the peers a job is working on (`inProgressPromoteCold`,
  * `inProgressPromoteWarm`, `inProgressDemoteWarm`, `inProgressDemoteHot`,
  * `inProgressDemoteToCold`; `Governor/Types.hs` 673-681), and its invariants say that each
  * in-progress set is a subset of some other set (`assertPeerSelectionState`, `Types.hs` 1328).
  * Here the status of a peer is one value, so a peer cannot be in progress and in no set, or in two
  * in-progress sets (ADR 0016). The sets and counters of the reference are derived from the
  * statuses ([[GovernorState.view]]).
  */
enum Status:
  /** Known, not connected. `retryAt` is the earliest time the governor may connect again; none when
    * the peer is available now (the reference's `availableToConnect` and `nextConnectTimes`).
    */
  case Cold(retryAt: Option[Time])

  /** A job is connecting (`inProgressPromoteCold`). */
  case Connecting

  /** Established with the warm protocols only; it may be activated from `readyAt` (the reference's
    * `nextActivateTimes`).
    */
  case Warm(readyAt: Time)

  /** A job is starting the hot protocols (`inProgressPromoteWarm`); the peer is established. */
  case Activating

  /** The hot protocols run. */
  case Hot

  /** A job is ending the hot protocols (`inProgressDemoteHot`); the peer is still active. */
  case Deactivating

  /** A job is closing the connection (`inProgressDemoteWarm`); the peer is still established. */
  case Closing

  /** The connection failed or was closed under us and lingers until the connection manager has
    * released it (`PeerCooling`, `inProgressDemoteToCold`); the peer is still established, and can
    * be neither promoted nor demoted.
    */
  case Cooling

  /** Established: a connection exists (warm, hot, or on its way to cold). */
  def established: Boolean = this match
    case Cold(_) | Connecting => false
    case _                    => true

  /** Active: counts towards the active target (hot, or being deactivated). */
  def active: Boolean = this match
    case Hot | Deactivating => true
    case _                  => false

  /** A job works on the peer, so the decisions leave it alone. */
  def inProgress: Boolean = this match
    case Connecting | Activating | Deactivating | Closing | Cooling => true
    case _                                                          => false

/** Where a known peer came from. A peer has one origin; a local root wins over a public one, as
  * `Monitor.localRoots` keeps the two sets disjoint (`Governor/Monitor.hs` 381-386).
  */
enum Origin:
  /** Named by a local root group of the topology (or `--peer`). */
  case Local

  /** A public root of the topology. */
  case Public

  /** A bootstrap peer: trusted, and the only source in the sensitive state. */
  case Bootstrap

  /** Known, and no longer a root: a former local root, or a peer learnt from another peer. */
  case Other

  def root: Boolean = this != Other

  /** Kept however often it fails: the operator's own peers and the trusted bootstrap peers
    * (`reportFailures`'s `unforgettable`).
    */
  def unforgettable: Boolean = this == Local || this == Bootstrap

/** Everything the governor knows of one peer (`KnownPeerInfo`, `State/KnownPeers.hs` 100-145, and
  * the entry in the established set).
  */
final case class PeerRecord(
    status: Status,
    origin: Origin,
    /** Failures since the last success or the last clearing; at the retry limit the peer is
      * forgotten unless it is a root.
      */
    failCount: Int,
    /** Was hot and was demoted: more likely to be picked for closing (`knownPeerTepid`). */
    tepid: Boolean,
    /** A connection to it has been established at some time (`knownSuccessfulConnection`). */
    everConnected: Boolean,
    /** When the fail count is reset (a peer that stayed hot for `policyClearFailCountDelay`). */
    clearFailAt: Option[Time]
)

object PeerRecord:
  def cold(origin: Origin): PeerRecord =
    PeerRecord(Status.Cold(None), origin, 0, false, false, None)
