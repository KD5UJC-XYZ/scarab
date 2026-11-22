// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.show.*
import xyz.kd5ujc.scarab.crypto.bls.BlstProblem
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.{
  Draft03Prover,
  PraosLeaderThreshold as Leader,
  PraosVrfCertificate as Vrf
}
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  Mux as WireMux,
  NetworkError,
  ProtocolError,
  ReferenceBuild
}

/** Closed hierarchy of node failures. Algebras raise the narrowest subtype; because cats-mtl
  * `Raise` is contravariant in its error, one `Raise[F, NodeError]` serves them all.
  */
sealed trait NodeError extends DomainError:
  def message: String

/** Failures attributable to the remote peer or the connection. These are recoverable by
  * reconnecting and re-intersecting.
  */
enum PeerError extends NodeError:
  case Handshake(cause: NetworkError.HandshakeRefused | NetworkError.VersionMismatch)
  case Protocol(cause: PeerError.ProtocolFailure)
  case Framing(cause: WireMux.Error)
  case MuxSetup(cause: WireMux.Error)
  case MuxWrite(cause: WireMux.Error)
  case Unavailable(cause: PeerError.Unavailability)
  case Genesis(cause: PeerError.GenesisProblem)

  /** The peer's chain shares no point with ours (it is behind, or on another network). */
  case NoIntersection

  /** Native normal ChainSync termination after draining (`Client.hs` 1555–1585). */
  case RolledBackPastIntersection(point: ChainSync.Point)

  /** The peer's chain and ours part ways in a way that is nobody's fault: it rolled back beyond
    * what we keep, or our chain moved on so that its candidate no longer joins it. The session
    * starts again; the peer did nothing wrong.
    */
  case Diverged(cause: PeerError.Divergence)

  /** A header the peer announced breaks a header rule; its block was not fetched (G6). */
  case InvalidHeader(point: ChainSync.Point, error: xyz.kd5ujc.scarab.ledger.HeaderError)

  /** A block the peer sent is refused by our rules (the session ends and nothing of the block was
    * applied). The same fact as a [[ValidationError]] raised in the session, seen as the peer's.
    */
  case InvalidBlock(error: ValidationError)

  /** A decoder threw on this peer's input instead of answering. It stays a defect with its original
    * cause; only its policy is the connection's (see `FailurePolicy`).
    */
  case InputDefect(site: PeerError.InputSite, cause: Throwable)

  /** The node's own code threw while it answered this peer on `protocol` (the ledger rules on a
    * transaction it submitted): no verdict, and nothing shown against the peer. The connection
    * closes and the peer is not charged (`FailurePolicy`, issue 564).
    */
  case ServingDefect(protocol: Int, cause: Throwable)

  /** An exception of the node's own code on this connection whose origin is not known more
    * precisely (`NetworkError.Defect`: a fiber of the connection, its servers, clients or
    * establishment). The connection closes and the peer is not charged; the node goes on, as an
    * exception no rethrow policy names ends only that peer in the reference (issue 567).
    */
  case ConnectionDefect(cause: Throwable)

  /** An invariant of the node's own Genesis code that does not hold for this connection (its
    * identity, its jump state, a header's state, time or selection view). It says nothing about the
    * peer: the connection closes, the peer is not charged and the node goes on, as `ourBug` is
    * `ShutdownPeer` in the reference (issue 567, H7).
    */
  case GenesisInvariant(problem: GenesisInternalProblem)

  /** A crypto provider failed while a header rule verified a header this peer announced: an
    * exception from a library, not a verdict on the header. The peer is neither forgotten nor
    * blamed and nothing is recorded as invalid; the connection closes and the peer is tried again
    * after the error delay, as the reference's default `ShutdownPeer` does for an exception no
    * rethrow policy names ([[xyz.kd5ujc.scarab.node.FailurePolicy.classify]]).
    */
  case Unverifiable(error: VerificationDefect)

  /** A defect of the node's own state or configuration left a header this peer announced without a
    * verdict ([[HeaderDefect]]): nothing is recorded as invalid and the peer is not charged; the
    * connection closes, as the reference's exception in a ChainSync client ends that peer
    * ([[xyz.kd5ujc.scarab.node.FailurePolicy.classify]]).
    */
  case HeaderUndecided(point: ChainSync.Point, defect: HeaderDefect)

  /** Our ChainSync server could not give this peer its next block: the node keeps no chain database
    * and the block has left its volatile chain ([[NetworkError.NotKept]]). The connection closes
    * and the peer is not charged: the history is the node's to keep. The reference always keeps its
    * immutable chain and has no such case (#924).
    */
  case NotKept(cause: NetworkError.NotKept)

  /** Our BlockFetch server ended this connection over a range the peer asked for
    * ([[NetworkError.RangeRefused]]): an Origin end, bounds that are not a range, or a block of the
    * batch collected since it started. The connection closes and the node goes on, and the peer is
    * tried again after the error delay, as the reference's rethrow policy makes each of these
    * exceptions `ShutdownPeer` ([[xyz.kd5ujc.scarab.node.FailurePolicy.classify]]).
    */
  case RangeRefused(cause: NetworkError.RangeRefused)

  /** Our LeiosFetch server ended the connection rather than answer the peer's request
    * ([[NetworkError.LeiosFetchRefused]]): it named an endorser block or a transaction this node
    * does not hold, or the store could not answer.
    */
  case LeiosFetchRefused(cause: NetworkError.LeiosFetchRefused)

  /** Our LeiosFetch client ended the connection over the peer's answer
    * ([[NetworkError.LeiosFetchInvalidReply]]): a body or transactions that are not what was asked
    * for, or a body that hashes to the one asked for and is still refused.
    */
  case LeiosFetchInvalidReply(cause: NetworkError.LeiosFetchInvalidReply)

  /** Our LeiosNotify server ended the connection over a request beyond the pipelining bound
    * ([[NetworkError.LeiosNotifyExcessiveRequests]]).
    */
  case LeiosNotifyExcessiveRequests(cause: NetworkError.LeiosNotifyExcessiveRequests)

  /** The peer's ChainSync roll-forwards claimed two endorser blocks certified for one election, the
    * election's slot and pool, the one claimed first and the one claimed now
    * (`ExnLeiosTwoCertificationClaims`, ouroboros-consensus 2416493b `LeiosDemoLogic.hs`
    * 1696-1731): one certified announcement per election is all an honest peer ever selects.
    */
  case LeiosTwoCertificationClaims(
      slot: xyz.kd5ujc.scarab.protocol.LeiosWord.Word64,
      pool: xyz.kd5ujc.scarab.codec.Bytes,
      alreadyClaimed: xyz.kd5ujc.scarab.protocol.EbHash,
      claimed: xyz.kd5ujc.scarab.protocol.EbHash
  )

  /** Our LeiosNotify client ended the connection over the peer's reply ([[LeiosNotifyFault]]): an
    * announcement or an offer it refuses, or a cancellation before it quit (`hLeiosNotifyClient`,
    * ouroboros-consensus 2416493b `NodeToNode.hs` 615-768).
    */
  case LeiosNotify(fault: LeiosNotifyFault)

  /** The node could not check this peer's LeiosNotify announcement at `point` against its own
    * immutable tip: the tip's ledger does not read, or its chain-dependent state is of another
    * protocol ([[HeaderError]]). A failure of the node's own, never a verdict on the announcement:
    * the connection ends, as the reference's exception in its client ends it, and the peer is not
    * charged.
    */
  case LeiosAnnouncementUndecided(point: ChainSync.Point, cause: HeaderError)

  /** The node's vote state reached no answer on a vote of this peer's, for the announcing block
    * `rbHash`: the native BLS library failed, the certificate could not be assembled, or the
    * committee of the selection's tip could not be read. A defect of the node's own, never a
    * verdict on the vote: the connection ends as the reference's exception in its LeiosNotify
    * client ends it, and the peer is not charged.
    */
  case LeiosVoteUndecided(
      rbHash: xyz.kd5ujc.scarab.crypto.bls.RbHash,
      cause: xyz.kd5ujc.scarab.crypto.bls.BlsError | LeiosCertificationDefect
  )

  def message: String = this match
    case Handshake(r)   => s"handshake: ${r.message}"
    case Protocol(r)    => s"protocol: ${PeerError.protocolMessage(r)}"
    case Framing(e)     => s"protocol: multiplexer framing: ${e.message}"
    case MuxSetup(e)    => s"multiplexer setup: ${e.message}"
    case MuxWrite(e)    => s"multiplexer write: ${e.message}"
    case Unavailable(r) => s"unavailable: ${r.message}"
    case Genesis(cause) => s"Genesis: ${cause.message}"
    case NoIntersection => "the peer's chain shares no point with ours"
    case RolledBackPastIntersection(point) =>
      s"the peer rolled back beyond the captured intersection fragment to ${Points.show(point)}"
    case Diverged(r)         => s"diverged: ${r.message}"
    case InvalidHeader(p, e) => s"invalid header ${Points.show(p)}: ${e.message}"
    case InvalidBlock(e)     => s"invalid block ${e.message}"
    case InputDefect(site, cause) =>
      s"defect decoding ${site.message}: ${NetworkError.describe(cause)}"
    case ConnectionDefect(cause) =>
      s"the node's own code threw on this connection: ${NetworkError.describe(cause)}"
    case GenesisInvariant(problem) => s"the node's own Genesis state broke: ${problem.message}"
    case ServingDefect(protocol, cause) =>
      s"the node's own code threw answering ${xyz.kd5ujc.scarab.protocol.Protocols.name(protocol)}: ${NetworkError.describe(cause)}"
    case Unverifiable(e)                     => e.message
    case HeaderUndecided(p, d)               => s"${Points.show(p)} has no verdict: ${d.message}"
    case NotKept(cause)                      => cause.message
    case RangeRefused(cause)                 => cause.message
    case LeiosFetchRefused(cause)            => cause.message
    case LeiosFetchInvalidReply(cause)       => cause.message
    case LeiosNotifyExcessiveRequests(cause) => cause.message
    case LeiosTwoCertificationClaims(slot, pool, first, second) =>
      s"the peer claimed ${first.bytes.hex} and ${second.bytes.hex} certified for the election of pool ${pool.hex} at slot ${slot.toBigInt}"
    case LeiosNotify(fault) => s"LeiosNotify: ${fault.message}"
    case LeiosAnnouncementUndecided(p, cause) =>
      s"the announcement ${Points.show(p)} has no verdict: ${cause.message}"
    case LeiosVoteUndecided(rbHash, cause) =>
      val why = cause match
        case d: LeiosCertificationDefect              => d.message
        case e: xyz.kd5ujc.scarab.crypto.bls.BlsError => e.toString
      s"no answer on a vote for ${rbHash.bytes.hex}: $why"

object PeerError:
  enum GenesisProblem:
    case HistoricalAwait(
        point: ChainSync.Point,
        slotTimePicos: BigInt,
        arrivalTimePicos: BigInt,
        cutoffPicos: BigInt
    )
    case HistoricalRollback(
        point: ChainSync.Point,
        slotTimePicos: BigInt,
        arrivalTimePicos: BigInt,
        cutoffPicos: BigInt
    )
    case LoPExpired, DensityTooLow
    case CandidateTooSparse(
        intersection: ChainSync.Point,
        ourTip: ChainSync.Point,
        theirTip: ChainSync.Point
    )
    case InvalidJumpResponse(requested: ChainSync.Point, actual: Option[ChainSync.Point])
    def message: String = this match
      case HistoricalAwait(point, slot, arrival, cutoff) =>
        s"historical AwaitReply at ${Points.show(point)}: age ${arrival - slot} ps exceeds $cutoff ps"
      case HistoricalRollback(point, slot, arrival, cutoff) =>
        s"historical rollback rewinds ${Points.show(point)}: age ${arrival - slot} ps exceeds $cutoff ps"
      case CandidateTooSparse(intersection, ourTip, theirTip) =>
        s"candidate at ${Points.show(theirTip)} is not preferred to ${Points.show(ourTip)} beyond the forecast at ${Points.show(intersection)}"
      case LoPExpired    => "ChainSync patience bucket exhausted"
      case DensityTooLow => "candidate density cannot compete with another peer"
      case InvalidJumpResponse(requested, actual) =>
        s"intersection response ${actual.fold("no intersection")(Points.show)} does not match requested jump ${Points.show(requested)}"

  /** Where the peer's input was being decoded when a decoder threw. */
  enum InputSite:
    /** A mini-protocol message, in the multiplexer's channel (`MuxChannel.recv`). */
    case Wire(protocol: Int)

    /** The connection's bytes, read and routed by the multiplexer's bearer reader. */
    case Bearer

    /** The ledger header inside a ChainSync roll-forward. */
    case ChainSyncHeader

    /** A fetched block body, checked against its announced header. */
    case FetchedBlock(point: ChainSync.Point)
    def message: String = this match
      case Wire(protocol)  => s"a ${xyz.kd5ujc.scarab.protocol.Protocols.name(protocol)} message"
      case Bearer          => "the connection's bytes"
      case ChainSyncHeader => "a ChainSync header"
      case FetchedBlock(point) => s"the fetched block ${Points.show(point)}"

  type ProtocolFailure = ProtocolError | ProtocolProblem | NetworkError.IngressLimit |
    NetworkError.Decode | NetworkError.IllegalTransition | NetworkError.QueryRefused |
    NetworkError.TxSubmission
  enum ProtocolProblem:
    case HeaderDecode(cause: ChainError)
    case FetchedBlockIntegrity(cause: BlockTransportError)
    case InvalidPunishmentHeader(cause: TentativeHeaderError)
    case HeaderDoesNotExtend(point: ChainSync.Point, tip: ChainSync.Point)
    case HeaderValidationDidNotSettle
    case BlockCount(expected: Int, actual: Int)
    def message: String = this match
      case HeaderDecode(cause)          => cause.message
      case FetchedBlockIntegrity(cause) => cause.message
      case InvalidPunishmentHeader(cause) =>
        s"invalid tentative-header vocabulary: ${cause.message}"
      case HeaderDoesNotExtend(point, _) =>
        s"header ${Points.show(point)} does not extend the peer's chain"
      case HeaderValidationDidNotSettle => "header validation did not settle"
      case BlockCount(expected, actual) => s"expected $expected block, received $actual"
  enum Unavailability:
    case Network(
        cause: NetworkError.Connect | NetworkError.Timeout | NetworkError.SduTimeout |
          NetworkError.ProtocolIdleTimeout | NetworkError.BearerClosed
    )
    case Connection(cause: xyz.kd5ujc.scarab.protocol.ConnectionError)
    case Resolution(host: String, cause: java.io.IOException)
    case SessionEnded
    case MissingAnnouncedBlock(point: ChainSync.Point)
    def message: String = this match
      case Network(cause)           => cause.message
      case Connection(cause)        => cause.message
      case Resolution(host, cause)  => s"cannot resolve $host: ${cause.getMessage}"
      case SessionEnded             => "the session ended"
      case MissingAnnouncedBlock(_) => "peer does not have the announced block"
  enum Divergence:
    case RollbackOutsideChain(point: ChainSync.Point)
    case DetachedCandidate(base: ChainSync.Point)
    case ForeignBlock(cause: ValidationError)
    def message: String = this match
      case RollbackOutsideChain(point) =>
        s"peer rolled back to ${Points.show(point)}, outside our chain"
      case DetachedCandidate(base) =>
        s"the peer's chain no longer joins ours at ${Points.show(base)}"
      case ForeignBlock(cause) => s"a block fetched by another peer: ${cause.message}"
  def protocolMessage(cause: ProtocolFailure): String = cause match
    case error: ProtocolError   => error.message
    case error: ProtocolProblem => error.message
    case error: NetworkError    => error.message

/** Violated local Genesis invariants; reconnecting or blaming the remote peer cannot repair them.
  */
enum BlockPoolProblem extends NodeError:
  case MissingDeliveredHeader(point: ChainSync.Point)
  case DifferentInvalidRegistry
  case NoSelectionWorker
  case AvailableHashMismatch(key: Bytes, actual: Bytes)
  case SelectionProjectionChanged(point: ChainSync.Point)
  case AvailableCycle(hash: Bytes)

  def message: String = this match
    case MissingDeliveredHeader(point) =>
      s"received block ${Points.show(point)} has no original adding-header context"
    case AvailableHashMismatch(key, actual) =>
      s"available block key ${key.hex} differs from header hash ${actual.hex}"
    case SelectionProjectionChanged(point) =>
      s"candidate projection changed under selection lock at $point"
    case AvailableCycle(hash) => s"available block ancestry contains a cycle at ${hash.hex}"
    case DifferentInvalidRegistry =>
      "follower sessions do not share the same invalid-block registry"
    case NoSelectionWorker =>
      "a follower session started without the node's selection worker, which adopts its blocks"

/** Missing local metadata in the rolling ChainSync interpreter; never a remote refusal. */
enum ChainSyncInternalProblem extends NodeError:
  case MissingHeight(point: ChainSync.Point)
  case MissingRequestContext
  case InvalidBlockNumber(value: BigInt)
  case InvalidPipelineMarks(low: Int, high: Int)
  case NegativeOutstanding(count: Int)
  def message: String = this match
    case MissingHeight(point) =>
      s"ChainSync lacks the captured block number of ${Points.show(point)}"
    case InvalidBlockNumber(value) => s"ChainSync local block number is outside Word64: $value"
    case MissingRequestContext     => "ChainSync reply has no submitted request context"
    case InvalidPipelineMarks(low, high) => s"invalid ChainSync pipeline marks $low/$high"
    case NegativeOutstanding(count)      => s"negative ChainSync outstanding count $count"

/** The blockchain time cannot go on (consensus 82ecba329 `BlockchainTime/WallClock/HardFork.hs`
  * 121-151): the wall clock went back further than the tolerated rewind, so the current slot would
  * go back from `previous` to `now`. The reference throws `SystemClockMovedBack` and the node
  * stops.
  */
enum ClockProblem extends NodeError:
  case SystemClockMovedBack(previous: BigInt, now: BigInt)
  def message: String = this match
    case SystemClockMovedBack(previous, now) =>
      s"the system clock moved back from slot $previous to slot $now"

enum GenesisInternalProblem extends NodeError:
  case PatienceWatcherFailed(cause: NetworkError)
  case MissingConnectionIdentity
  case InvalidConnectionIdentity(cause: xyz.kd5ujc.scarab.protocol.ConnectionIdentityError)
  case MissingDynamoJumpInfo, UnexpectedIntersection
  case GraftMissingPoint(point: ChainSync.Point)
  case HeaderTimeUnavailable(point: ChainSync.Point)
  case HeaderSelectionUnavailable(point: ChainSync.Point)
  case HeaderStateUnavailable(point: ChainSync.Point)
  def message: String = this match
    case PatienceWatcherFailed(cause) => s"Genesis patience watcher failed: ${cause.message}"
    case MissingDynamoJumpInfo        => "Genesis dynamo has no validated jump snapshot"
    case UnexpectedIntersection =>
      "Genesis jumper with a settled intersection received another jump result"
    case MissingConnectionIdentity => "Genesis requires the established TCP connection identity"
    case InvalidConnectionIdentity(cause) => s"Genesis TCP connection identity: ${cause.message}"
    case GraftMissingPoint(point)         => s"Genesis jump snapshot lacks ${Points.show(point)}"
    case HeaderSelectionUnavailable(point) =>
      s"Genesis validated header lacks a selection view at ${Points.show(point)}"
    case HeaderStateUnavailable(point) =>
      s"Genesis validated header lacks state at ${Points.show(point)}"
    case HeaderTimeUnavailable(point) =>
      s"Genesis validated header lacks slot time at ${Points.show(point)}"

/** Failures of the local chain model. These are not recoverable by reconnecting. */
enum ChainError extends NodeError:
  /** The retained ledger point and pinned backing generation do not describe one state. */
  case StateAcquire(cause: NetworkError.StateAcquireFailure)

  case Decode(what: DecodeSite, cause: LedgerDecodeError)
  case NotConnected(point: ChainSync.Point, tip: ChainSync.Point)
  case RollbackBeyondAnchor(target: ChainSync.Point, anchor: ChainSync.Point)
  case NoIntersection
  case MempoolTick(slot: BigInt, cause: rules.TickFailure)

  /** The rules run for `slot` (a block's tick, its transactions, the Byron hand-over, a forging
    * attempt) met a quantity of the ledger state, its parameters included, outside the range of its
    * vocabulary type ([[BlockFailure.stateOutOfRange]]). The reference aborts with an exception
    * there (`compactCoinOrError`, cardano-ledger f649f975 `Coin.hs:184-188`) in a linked thread
    * (ouroboros-consensus 82ecba32 `Storage/ChainDB/Impl/Background.hs:107-109,142` for the block
    * runner, `NodeKernel.hs:378-380` for the forger), so the node stops: this is neither a verdict
    * on a block nor a fault of the peer that sent it.
    */
  case StateOutOfRange(slot: BigInt, cause: xyz.kd5ujc.scarab.ledger.state.VocabError)

  /** An exception while the node committed a chain it selected (persisting it, publishing it,
    * telling the mempool). The reference commits in the ChainDB's background thread, which is
    * linked to the node (ouroboros-consensus 82ecba32 `Storage/ChainDB/Impl/Background.hs:107-108,
    * 142`), so the node stops, whichever fiber asked for the commit.
    */
  case CommitDefect(cause: Throwable)

  /** SNAP met no Leios committee for the mark snapshot it rotates, because of a state the reference
    * cannot hold ([[rules.TickFailure.LeiosCommittee]]: a parameter outside its word, a seated pool
    * of the 11.1 layout or a seated key its decoder refuses). The node stops: this is neither a
    * verdict on a block nor a fault of the peer that sent it. A failure of the BLS library there is
    * a provider defect instead ([[ValidationError.unverifiable]]).
    */
  case LeiosCommittee(slot: BigInt, cause: xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError)

  /** The tick for `slot` that enters Dijkstra met a Conway governance record that does not read
    * into Dijkstra's ([[rules.TickFailure.GovRecord]]), which only a damaged record gives. The
    * reference translates its own record, so it does not meet it: this is neither a verdict on a
    * block nor a fault of the peer that sent it, as a state out of range is not
    * ([[StateOutOfRange]]).
    */
  case GovRecord(slot: BigInt, cause: xyz.kd5ujc.scarab.ledger.era.StoredGovError)

  /** The node cannot apply the Dijkstra block of `slot` ([[DijkstraBlockDefect]]): a missing or
    * undecodable closure, which the reference meets with `error` (ouroboros-consensus 2416493b
    * `Storage/LedgerDB/Forker.hs:1048-1052, 1084-1087`), or another state of its own it cannot
    * judge. This is neither a verdict on the block nor a fault of the peer that sent it: the
    * follower withholds the block for a limit of this build valid peer input can meet
    * (`BlockNoVerdict.ofDijkstra`), and for the rest the node stops.
    */
  case DijkstraBlock(slot: BigInt, cause: DijkstraBlockDefect)

  def message: String = this match
    case DijkstraBlock(slot, cause) => s"the Dijkstra block of slot $slot: ${cause.message}"
    case MempoolTick(slot, cause)   => s"cannot tick mempool ledger to slot $slot: $cause"
    case CommitDefect(cause) =>
      s"the node's own code threw while committing a chain: ${NetworkError.describe(cause)}"
    case LeiosCommittee(slot, cause) =>
      s"no Leios committee for the snapshots rotated at slot $slot: $cause"
    case GovRecord(slot, cause) =>
      s"the governance record does not hold Dijkstra's state at slot $slot: $cause"
    case StateOutOfRange(slot, cause) =>
      s"the ledger state in the rules for slot $slot is out of range: ${cats.Show[xyz.kd5ujc.scarab.ledger.state.VocabError].show(cause)}"
    case StateAcquire(cause) => cause.message
    case Decode(w, r)        => s"decode ${w.show}: ${r.show}"
    case NotConnected(p, t)  => s"${Points.show(p)} does not extend tip ${Points.show(t)}"
    case RollbackBeyondAnchor(t, a) =>
      s"rollback to ${Points.show(t)} beyond anchor ${Points.show(a)}"
    case NoIntersection => "peer shares no point with the local chain"

/** A block or header the local rules reject, with the closed [[BlockFailure]] that says why. */
final case class ValidationError(point: ChainSync.Point, failure: BlockFailure) extends NodeError:
  /** The rule family and the reason as the log renders them (the `rule` and `reason` fields). */
  def rule: String = failure.rule.name
  def reason: String = failure.reason
  def message: String = s"${Points.show(point)} rejected by $rule: $reason"

  /** The provider defect this "refusal" is in fact, if any: a crypto provider failed on the block,
    * so the rules have no verdict on it ([[ProviderDefect]]). It is never recorded as invalid and
    * its peer is not blamed: the node stops ([[Fatal.Unverifiable]]).
    */
  def unverifiable: Option[VerificationDefect] = failure match
    case BlockFailure.Unverifiable(_, defect) =>
      Some(VerificationDefect(point, VerificationDefect.Site.Header, defect))
    case BlockFailure.Tx(LedgerFailure.Unverifiable(txId, defect)) =>
      Some(VerificationDefect(point, VerificationDefect.Site.Witness(txId), defect))
    case BlockFailure.Tick(
          rules.TickFailure.LeiosCommittee(
            xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError.PossessionCheckFailed(pool, error)
          )
        ) =>
      Some(
        VerificationDefect(
          point,
          VerificationDefect.Site.LeiosCommittee(pool.bytes),
          ProviderDefect.Bls(error)
        )
      )
    case _ => None

object ValidationError:
  given cats.Show[ValidationError] = cats.Show.show(_.message)

/** The implementation cannot evaluate this block. It is no verdict on the block or its peer. */
final case class EvaluationUnavailable(point: ChainSync.Point, failure: LedgerFailure.Unavailable)
    extends NodeError:
  def message: String = s"${Points.show(point)} evaluation unavailable: ${failure.message}"

/** A crypto provider failed while the rules verified the block at `point` ([[ProviderDefect]]): an
  * exception from a library, not a verdict. The reference lets it end the thread that applies the
  * block, and that thread is linked to the node (ouroboros-consensus 82ecba32
  * `Storage/ChainDB/Impl/Background.hs:107-108,142`), so the node stops: `Fatal.Unverifiable`.
  */
final case class VerificationDefect(
    point: ChainSync.Point,
    site: VerificationDefect.Site,
    defect: ProviderDefect
) extends NodeError:
  def message: String = s"${Points.show(point)} has no verdict: ${site.message}: ${defect.message}"

object VerificationDefect:
  /** What was being verified: the header's VRF, KES or certificate, a transaction witness, or a
    * seated pool's proof of possession for the Leios committee.
    */
  enum Site:
    case Header
    case Witness(txId: Bytes)
    case LeiosCommittee(pool: Bytes)
    def message: String = this match
      case Header               => "header"
      case Witness(tx)          => s"witness of tx ${tx.hex.take(16)}"
      case LeiosCommittee(pool) => s"Leios committee key of pool ${pool.hex.take(16)}"

/** Why a UTxO store, or a pinned view or an enumeration of it, has no open database
  * ([[StorageError.UtxoStoreClosed]]).
  */
enum UtxoStoreClosure derives CanEqual:
  /** A restore or a reset closed the database and has not opened a new one: it failed after the
    * close. A later restore or reset that succeeds opens one.
    */
  case NoDatabase

  /** The store was released: it is never opened again. */
  case Released

  /** A pinned view or an enumeration read a database that a restore (or the reset after a failed
    * one) has since closed and replaced: it reads nothing more. The store itself never holds this
    * reason.
    */
  case Replaced

  def message: String = this match
    case NoDatabase => "a restore or reset closed it and failed before opening a new one"
    case Released   => "the store was released"
    case Replaced   => "the database this view or enumeration read was replaced by a restore"

/** Why a UTxO checkpoint of the RocksDB store cannot be restored by this build. */
enum CheckpointProblem:
  /** The keys are in two layouts (an older build, then this one, wrote to the same store): the
    * entries of the old layout may have been spent since, so none can be trusted.
    */
  case MixedKeyLayouts

  /** A key of the layout before the 34-byte key whose index does not fit the 16 bits of the current
    * one.
    */
  case IndexTooLarge(index: Long)

  /** A key of a length no layout of the store has. */
  case UnknownKeyLength(length: Int)

  /** The snapshot names a checkpoint directory that does not exist. */
  case Absent

  /** The snapshot records that its state keeps nothing outside the file, while this node keeps the
    * UTxO entries in a store: the entries of the snapshot are not known.
    */
  case Unbound

  /** The snapshot was written for checkpoints of another key layout. */
  case LayoutMismatch(found: UtxoKeyLayout, expected: UtxoKeyLayout)

  /** The checkpoint directory is not the one the snapshot was written with: its digest differs from
    * the one the snapshot recorded.
    */
  case DigestMismatch(found: Bytes, recorded: Bytes)

  def render: String = this match
    case MixedKeyLayouts =>
      "it holds keys of the 36-byte layout and of the 34-byte one together"
    case IndexTooLarge(i)    => s"it holds an output index of $i, which the 34-byte key cannot hold"
    case UnknownKeyLength(n) => s"it holds a key of $n bytes"
    case Absent              => "the snapshot's checkpoint directory does not exist"
    case Unbound => "the snapshot records no checkpoint, but the UTxO entries live in one"
    case LayoutMismatch(found, expected) =>
      s"the snapshot was written for the $found key layout, this build uses $expected"
    case DigestMismatch(found, recorded) =>
      s"its digest is ${found.hex}, the snapshot recorded ${recorded.hex}"

/** Failures of the on-disk chain database. Raised by the storage algebras; IO exceptions are mapped
  * to [[Io]] at the file boundary only.
  */
enum FirstImmutableProblem extends DomainError:
  case InvalidIndexEntry
  case ChecksumMismatch
  case MissingChunk
  case BlockDecode(error: ChainError)

  def message: String = this match
    case InvalidIndexEntry  => "invalid first immutable index entry"
    case ChecksumMismatch   => "first immutable block checksum mismatch"
    case MissingChunk       => "first immutable index references a missing chunk"
    case BlockDecode(error) => s"first immutable block cannot be decoded: ${error.message}"

/** Why the after-voting counter of a snapshot that does not know it (`Gap.ShelleyTransition`, a
  * snapshot converted by a pre-native Scarab build) cannot be recounted from the immutable store.
  */
enum TransitionCounterProblem extends DomainError:
  /** The store holds no block before the voting deadline (at slot `deadline`) of the tip's epoch,
    * so it cannot tell how many blocks after it it lacks; `firstStored` is the slot of its first
    * block, none when it is empty.
    */
  case StoreStartsAfterDeadline(deadline: BigInt, firstStored: Option[BigInt])

  /** The snapshot's anchor (`anchor`, a slot) is not the tip of its Shelley-based ledger state
    * (`tip`), so the blocks counted up to the anchor are not the state's.
    */
  case AnchorNotTip(anchor: Option[BigInt], tip: Option[BigInt])

  def message: String = this match
    case StoreStartsAfterDeadline(deadline, first) =>
      s"the immutable store's first block (${first.fold("none")(s => s"slot $s")}) is not before the voting deadline at slot $deadline"
    case AnchorNotTip(anchor, tip) =>
      s"the snapshot's anchor (${anchor.fold("origin")(s => s"slot $s")}) is not its ledger tip (${tip
          .fold("origin")(s => s"slot $s")})"

/** What makes a ledger state, read from a snapshot or an import, one that no chain of the
  * configured reference build reaches and that the build's rules would carry to another state than
  * the reference's ([[StorageError.UnreachableLedgerState]]).
  */
enum UnreachableStateProblem extends DomainError:
  /** The pools' VRF key hashes hold `entries` entries at protocol major version `major`, below 11.
    * The reference's map is empty there: POOL writes it only from 11 and HARDFORK first fills it on
    * entering 11 (cardano-ledger f649f975 `Shelley/Era.hs:259-262`,
    * `Conway/Rules/HardFork.hs:77-78`). The retired flat conversion derived the map from 10, and
    * the 11.1 HARDFORK adds to the map it has, so a pool of such a state would be counted twice on
    * entering 11.
    */
  case VRFKeyHashesBeforeVersion11(major: BigInt, entries: Int)

  def message: String = this match
    case VRFKeyHashesBeforeVersion11(major, entries) =>
      s"its pools' VRF key hashes hold $entries entries at protocol version $major, where the reference holds none before 11"

/** Where a ledger state that a check of its content refuses came from
  * ([[StorageError.UnreachableLedgerState]]).
  */
enum LedgerStateSource derives CanEqual:
  /** A snapshot of the node's database, as the node restores it at start: `file` is the name the
    * node writes the snapshot of its anchor under (none for a snapshot without an anchor, which the
    * node never writes).
    */
  case Snapshot(file: Option[java.nio.file.Path])

  /** The `state` file of a reference node's database, as `import-ledger-snapshot` reads it. */
  case ReferenceState(file: java.nio.file.Path)

  /** The state an SCLS import rebuilds on the snapshot `base` of the node's database. */
  case SclsImport(base: java.nio.file.Path)

  def describe: String = this match
    case Snapshot(Some(file)) => s"the snapshot $file"
    case Snapshot(None)       => "a snapshot without an anchor"
    case ReferenceState(file) => s"the ledger state $file"
    case SclsImport(base)     => s"the SCLS import onto the snapshot $base"

enum StorageError extends NodeError:
  /** Reconstruction failed independently of whether the stored bytes are well formed. */
  case Reconstruction[E <: DomainError](what: String, cause: E) extends StorageError
  case DecoderDefect(what: String, cause: Throwable)

  case DatabaseInUse(database: String)

  /** The stored bytes fail validation (bad checksum, truncation, impossible structure). */
  case Corrupt[E <: DomainError](what: String, cause: E) extends StorageError

  /** A read-only validation failure while checking the first block before legacy adoption. */
  case FirstImmutable(problem: FirstImmutableProblem)

  /** An operating-system failure reading or writing `what`. */
  case Io(what: String, cause: Throwable)

  /** A snapshot converted by a pre-native Scarab build does not know its after-voting counter
    * (`Gap.ShelleyTransition`) and the immutable store cannot recount it: it is not opened. The
    * reference serialises the counter and cannot open a state without it; a fresh sync and a
    * Haskell or Mithril import always carry it (owner-approved 2026-11-04, PR #772). Stale local
    * data, not a fault of any peer.
    */
  case UnrecoverableTransitionCounter(problem: TransitionCounterProblem)

  /** The data was written by an incompatible format or schema version. */
  case VersionMismatch(what: String, found: String, expected: String)

  /** A file names a format version of its kind that this build does not know (written by a newer
    * build, most likely): it is not opened, and nothing is rewritten.
    */
  case UnknownVersion(what: String, kind: FileKind, found: String)

  /** A known format whose migration reader has been retired. Refused before mutable stores open. */
  case RetiredVersion(
      what: String,
      kind: FileKind,
      found: FormatVersion,
      readableFrom: FormatVersion
  )

  /** A file of a supported previous version holds state that the current format keeps and that
    * version did not (`loss`): no migration recovers it, and filling it in would invent it, so the
    * file is refused before any store opens and the chain has to be synced again from genesis (ADR
    * 0006, amendment of 2026-11-03).
    */
  case ResyncRequired(what: String, kind: FileKind, found: FormatVersion, loss: FormatLoss)

  /** A ledger state from `source` that no chain of the configured reference build `build` reaches
    * and that its rules would carry to another state than the reference's (`problem`): it is not
    * used, and the chain has to be synced again, or a ledger state the reference wrote imported.
    * Stale local data of a retired Scarab conversion, not a fault of any peer. Refused where it is
    * restored or imported, rather than repaired by a migration step or at the hard fork: the
    * owner's decision (asked 2026-11-18T08:22:34Z and decided 2026-11-18T14:21:46Z).
    */
  case UnreachableLedgerState(
      source: LedgerStateSource,
      build: ReferenceBuild,
      problem: UnreachableStateProblem
  )

  /** A ledger snapshot whose Haskell state bytes were written under the reference build `recorded`,
    * whose layout is not that of the `configured` one. No conversion between builds exists, so the
    * snapshot is not read, as the reference does not read a snapshot it cannot decode.
    */
  case ReferenceBuildMismatch(what: String, recorded: ReferenceBuild, configured: ReferenceBuild)

  /** A ledger snapshot that records a reference build this build does not know (`recorded`, as
    * written): written by a newer build, most likely. It is not the `configured` build, so it is
    * treated as [[ReferenceBuildMismatch]] is.
    */
  case UnknownReferenceBuild(what: String, recorded: String, configured: ReferenceBuild)

  /** A requested block or point is not stored. */
  case Missing(what: String)

  /** An append that does not extend the stored chain. */
  case OutOfOrder(cause: StorageStructureError)

  /** The database was created for another network than the one this node is configured for. */
  case NetworkMismatch(found: NetworkIdentity, expected: NetworkIdentity)

  /** A v1/v2 identity proves these fields differ, but does not contain the newer genesis inputs. */
  case LegacyNetworkMismatch(found: LegacyNetworkIdentity, expected: NetworkIdentity)

  /** A matching but incomplete v1/v2 identity needs an explicit operator acknowledgement. */
  case LegacyIdentityNeedsConfirmation(version: FormatVersion)

  /** A V6 identity, which records no Dijkstra genesis binding, reached a migration without the
    * node's configuration to take the binding from: only an open with it binds the file (#824).
    */
  case IdentityNeedsDijkstraBinding(version: FormatVersion)

  /** Populated data has no identity; associating it with a network needs explicit confirmation. */
  case MissingIdentityNeedsConfirmation(database: String)

  /** An operator-confirmation backup already exists but contains different bytes. */
  case IdentityBackupConflict(path: String)

  /** A database without a recorded identity whose first block does not follow the configured
    * genesis (it belongs to another network, or to another genesis of the same one).
    */
  case ForeignChain(firstBlockPredecessor: String, expectedPredecessor: String)

  /** The UTxO checkpoint `dir` cannot be used. The snapshot it belongs to restores nothing safely:
    * the state has to be replayed from the immutable chunks.
    */
  case UtxoCheckpoint(dir: String, problem: CheckpointProblem)

  /** The UTxO store in `dir` has no open database, for the reason `why`. */
  case UtxoStoreClosed(dir: String, why: UtxoStoreClosure)

  /** The Leios endorser-block store failed with `cause`, the storage module's typed error
    * (`LeiosStoreError`): its open at start, or a call on it.
    */
  case LeiosStore[E <: DomainError](cause: E) extends StorageError

  def message: String = this match
    case Reconstruction(what, cause) => s"$what reconstruction failed: $cause"
    case DecoderDefect(what, cause)  => s"$what decoder defect: $cause"
    case DatabaseInUse(db) => s"database $db is already owned by a follower or offline command"
    case Corrupt(w, r)     => s"corrupt $w: $r"
    case FirstImmutable(p) => s"corrupt first immutable block: ${p.message}"
    case Io(w, r)          => s"io $w: $r"
    case VersionMismatch(w, f, e) => s"$w version $f, expected $e"
    case UnrecoverableTransitionCounter(problem) =>
      s"the ledger snapshot was converted by a pre-native Scarab build and does not know its blocks after the voting deadline, which cannot be recounted: ${problem.message}; resynchronise (remove <db>/snapshots and <db>/utxo and replay the immutable chunks, or sync a new database) or import a Haskell or Mithril ledger state"
    case UnknownVersion(w, k, f) =>
      s"$w: unknown ${k.label} format '$f' (this build reads from '${k.identifier(k.readableFrom)}' through '${k.identifier(k.current)}'); it was probably written by a newer build"
    case RetiredVersion(w, k, f, oldest) =>
      s"$w: retired ${k.label} format '${k.identifier(f)}'; use the archived migration build to convert a copy to '${k.identifier(oldest)}' before opening it"
    case ResyncRequired(w, k, f, loss) =>
      s"$w: the ${k.label} format '${k.identifier(f)}' did not keep ${loss.message}, which '${k.identifier(k.current)}' keeps and no migration can recover; sync the chain again from genesis: replay it from the immutable chunks (stop the node, remove <db>/snapshots and <db>/utxo, start it again) or sync a new database"
    case UnreachableLedgerState(source, build, problem) =>
      s"${source.describe} holds a ledger state that no chain of the reference build ${build.describe} reaches: ${problem.message}; sync the chain again from genesis: replay it from the immutable chunks (stop the node, remove <db>/snapshots and <db>/utxo, start it again) or sync a new database, or import a ledger state the reference node wrote"
    case ReferenceBuildMismatch(w, recorded, configured) =>
      s"$w was recorded under the reference build ${recorded.describe}, which is not the configured ${configured.describe}; no conversion between builds exists"
    case UnknownReferenceBuild(w, recorded, configured) =>
      s"$w was recorded under the reference build '$recorded', which this build does not know; the configured build is ${configured.describe}"
    case Missing(w)    => s"missing $w"
    case OutOfOrder(r) => s"out of order: $r"
    case NetworkMismatch(f, e) =>
      s"the database belongs to another network: it was created for ${f.describe}, this node is configured for ${e.describe}; use the matching --genesis-dir or another --db"
    case LegacyNetworkMismatch(f, e) =>
      s"the legacy database identity differs from this node: it records ${f.describe}, configured identity is ${e.describe}; use the matching genesis configuration or another --db"
    case LegacyIdentityNeedsConfirmation(version) =>
      s"the database identity format ${FileKind.IdentityFile.identifier(version)} lacks Alonzo and Conway genesis bindings; rerun with --adopt-legacy-network-identity after verifying the database's origin (this acknowledgement is not historical proof)"
    case IdentityNeedsDijkstraBinding(version) =>
      s"the database identity format ${FileKind.IdentityFile.identifier(version)} has no Dijkstra genesis binding, which its first open with the node's configuration records"
    case MissingIdentityNeedsConfirmation(database) =>
      s"database $database contains data but has no network identity; rerun with --adopt-legacy-network-identity only after verifying its origin (this acknowledgement is not historical proof)"
    case IdentityBackupConflict(path) =>
      s"cannot preserve the legacy network identity because $path already contains different bytes"
    case ForeignChain(f, e) =>
      s"the database holds a chain whose first block names $f as its predecessor, the configured genesis requires $e; use the matching --genesis-dir or another --db"
    case UtxoCheckpoint(d, p) =>
      s"the UTxO checkpoint $d cannot be restored: ${p.render}; replay from the immutable chunks (stop the node, remove <db>/snapshots and <db>/utxo, start it again)"
    case UtxoStoreClosed(d, why) => s"the UTxO store $d has no open database: ${why.message}"
    case LeiosStore(cause)       => s"the Leios store failed: $cause"

/** Why this node cannot (or did not) forge a block. [[NotLeader]] is the ordinary answer of most
  * slots; the others are real problems with the keys, the ledger view or the assembled block.
  */
enum ForgeError extends NodeError:
  /** A key or certificate file is missing, malformed or of the wrong type. */
  case KeyFile(file: String, cause: ForgeKeyProblem)

  /** A failure at one of the durability boundaries for an evolved KES key. */
  case KesFileFailure(file: String, operation: KesFileOperation, cause: KesFileFailureCause)

  /** A KES signing key was decoded, but its material is invalid. */
  case KesKey(file: String, cause: Sum6KesSigning.Failure)

  /** The certificate names another KES key. */
  case KesKeyNotCertified(
      expected: Bytes,
      actual: Bytes
  )

  case CertificateFailure(cause: OperationalCertificate.Failure)
  case CertificateSignatureRejected

  /** The KES period of the slot is outside the operational certificate's window. */
  case KeyPeriodOutOfRange(period: BigInt, start: BigInt, evolutions: BigInt)

  /** A cancelled key operation may have mutated key material without completing durability. */
  case KesUpdateInterrupted

  /** The exact target is before the key's current period or beyond its signing lifetime. */
  case KesEvolution(cause: Sum6KesSigning.Failure)

  case VrfInput(cause: Vrf.Failure)
  case VrfProof(cause: Draft03Prover.Failure)
  case LeaderThreshold(cause: Leader.Error)

  /** The VRF output of the slot is above the pool's threshold (or the pool has no stake). */
  case NotLeader(slot: BigInt)

  /** The slot's pool distribution cannot be derived from the tip (tip too old or ledger not ready).
    */
  case LedgerViewUnavailable(slot: BigInt, cause: ForgeLedgerProblem)

  /** Forging is only implemented for Praos (Conway) on a fully validated ledger. */
  case UnsupportedProtocol(cause: ForgeProtocolProblem)

  /** The block body or header could not be assembled within the protocol limits. */
  case Assembly(cause: ForgeAssemblyProblem)

  /** A certification the forger decided on cannot be carried out ([[ForgeCertifyProblem]]). */
  case Certify(cause: ForgeCertifyProblem)

  def message: String = this match
    case KeyFile(f, r) => s"key file $f: ${r.message}"
    case KesFileFailure(f, op, cause) =>
      s"KES key file $f during ${op.toString}: ${cause.message}"
    case KesKey(f, e) => s"key file $f: ${e.message}"
    case KesKeyNotCertified(_, _) =>
      "keys do not match: the operational certificate names another KES verification key"
    case CertificateFailure(e) => s"operational certificate: ${e.message}"
    case CertificateSignatureRejected =>
      "keys do not match: the operational certificate is not signed by its cold key"
    case KeyPeriodOutOfRange(p, s, e) =>
      s"KES period $p outside the operational certificate window [$s, ${s + e})"
    case KesUpdateInterrupted =>
      "KES key operation was interrupted; restart to recover durable key material"
    case KesEvolution(e)                => s"KES: ${e.message}"
    case VrfInput(e)                    => s"VRF: $e"
    case VrfProof(e)                    => s"VRF: $e"
    case LeaderThreshold(e)             => s"VRF: ${e.message}"
    case NotLeader(slot)                => s"not elected for slot $slot"
    case LedgerViewUnavailable(slot, r) => s"no ledger view for slot $slot: $r"
    case UnsupportedProtocol(r)         => s"cannot forge: $r"
    case Assembly(r)                    => s"block assembly: $r"
    case Certify(r)                     => s"cannot certify: $r"

enum KesFileOperation:
  case StageWrite, StageSync, StageReadback, DirectorySyncBeforeErase
  case ScrubWrite, ScrubSync, Rename, DirectorySyncAfterRename, Cleanup

enum KesFileFailureCause:
  case FileSystem(cause: Throwable)
  case NoWriteProgress
  case StageReadback(cause: ForgeError)
  case StagedKeyMismatch(mismatch: KesStageMismatch)
  case StageStillPresentAfterRecovery

  /** `renameat2` with `RENAME_EXCHANGE` failed with this errno (#918). */
  case Exchange(errno: Int)

  def message: String = this match
    case FileSystem(cause)              => Option(cause.getMessage).getOrElse(cause.toString)
    case NoWriteProgress                => "file channel made no write progress"
    case StageReadback(cause)           => cause.message
    case StagedKeyMismatch(value)       => value.message
    case StageStillPresentAfterRecovery => "staged key remains after successful recovery"
    case Exchange(errno)                => s"renameat2 RENAME_EXCHANGE failed with errno $errno"

enum KesStageMismatch:
  case Period(expected: Int, actual: Int)
  case VerificationKey(expected: Bytes, actual: Bytes)
  case SerializedKeyBytes

  def message: String = this match
    case Period(expected, actual) => s"staged KES period $actual, expected $expected"
    case VerificationKey(_, _)    => "staged KES verification key does not match the evolved key"
    case SerializedKeyBytes       => "staged KES serialized key bytes do not match the evolved key"

/** The peer governor stopped the node, as the reference's `abortGovernor` does: its state broke an
  * invariant (a bug), or the node did not reach a clean state with only trusted peers in time
  * (`BootstrapPeersCriticalTimeoutError`, ouroboros-network `Governor/Types.hs` 1863-1869).
  */
enum GovernorFault extends NodeError:
  case InvariantBroken[E <: DomainError](violations: List[E]) extends GovernorFault
  case BootstrapPeersCriticalTimeout
  case LocalDnsRefreshDefect(cause: Throwable)

  def message: String = this match
    case InvariantBroken(vs) => s"the peer selection state broke an invariant: ${vs.mkString(", ")}"
    case BootstrapPeersCriticalTimeout =>
      "the node did not reach a clean state with only bootstrap peers within 15 minutes"
    case LocalDnsRefreshDefect(cause) =>
      s"local root DNS refresh defect: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"

/** Which native library the node refuses to start with, and why. */
enum NativeLibraryProblem:
  /** The BLS12-381 library of Leios certificates cannot run on this machine, or computes a wrong
    * answer (ADR 0012). Without the check the JVM dies with SIGILL, without an event.
    */
  case Bls(problem: BlstProblem)

  def message: String = this match
    case Bls(problem) => problem.message

/** A native library failed its start-up check. */
final case class NativeLibraryError(problem: NativeLibraryProblem) extends NodeError:
  def message: String = s"native library: ${problem.message}"

/** A configuration failure. `problem` says which one for the callers that act on it (a genesis file
  * whose hash differs from the configured one, a topology the node cannot follow); `reason` is the
  * text.
  */
final case class ConfigError(problem: ConfigProblem) extends NodeError:
  def reason: String = problem.message
  def message: String = s"config: ${problem.message}"

object Points:
  def show(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"
