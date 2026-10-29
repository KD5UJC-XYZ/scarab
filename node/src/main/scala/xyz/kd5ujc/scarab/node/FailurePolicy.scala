// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.selection.FailureClass

/** How the governor treats each way a peer can fail (owner decision 8, docs/design/peer-selection/
  * decisions-and-risks.md section 8a): a peer that broke the protocol, sent an invalid header or an
  * oversize message is forgotten; a network failure is retried after a backoff.
  *
  * This is the one place the policy lives. The reference classifies exceptions in the caller's
  * `RethrowPolicy` and `ExitPolicy` (ouroboros-consensus, not part of the governor); changing what
  * happens to a kind of failure is changing one line here.
  */
object FailurePolicy:
  def classify(error: PeerError): FailureClass = error match
    // the peer did something it must not: a malformed or oversize message, a message the protocol
    // does not allow in the state, a header that breaks a rule
    case _: PeerError.Protocol | _: PeerError.Framing | _: PeerError.InvalidHeader |
        _: PeerError.InvalidBlock =>
      FailureClass.Misbehaved
    // A decoder that threw on the peer's input: the connection closes and the peer is charged a
    // failure with the error delay, as the reference does for an exception that no rethrow policy
    // names (network 2fb69829 RethrowPolicy.hs:61-70 and 99-103, the unit `ShutdownPeer`;
    // consensus 82ecba329 Node/RethrowPolicy.hs:131-139, `theyBuggyOrEvil` and `ourBug` are both
    // `ShutdownPeer`). It is not `Misbehaved`: a decoder that throws instead of refusing may be
    // our bug on valid data, and forgetting would then drop every honest peer that sends it.
    case _: PeerError.InputDefect => FailureClass.Transient
    // A crypto provider failed on the peer's header: an exception in that peer's ChainSync thread,
    // which no rethrow policy names, so the same policy as a decoder defect: the connection closes
    // in both directions and the peer is charged a failure with the error delay, never forgotten
    // and never recorded as invalid (consensus 82ecba329 Node/RethrowPolicy.hs:40-45 and 131-139).
    // The reconnect delay is the one of any lost connection: `OutboundPeers.Config.repromoteDelay`
    // (10 s) plus the governor's `coldReconnectFuzz` (`selection.Events.observedConnection`).
    case _: PeerError.Unverifiable => FailureClass.Transient
    // The node's own code threw while it answered the peer (the ledger rules on a transaction the
    // peer submitted): the connection closes, as the exception ends that mini-protocol in the
    // reference (consensus 82ecba329 Mempool/Update.hs `doAddTx`, in the TxSubmission2 thread;
    // network 2fb69829 RethrowPolicy.hs: `ShutdownPeer`). The reference also charges the error
    // delay; here nothing is counted against the peer, since nothing it did is shown wrong
    // (owner decision 2026-10-28, ADR 0017 amendment, issue 564).
    case _: PeerError.ServingDefect => FailureClass.NotThePeers
    // An exception of the node's own code on the connection, of no more precise origin: the
    // reference ends the peer (`ShutdownPeer`, its default); the node goes on and the peer is not
    // charged (owner decision 2026-10-28, issue 567)
    case _: PeerError.ConnectionDefect => FailureClass.NotThePeers
    // the same for an invariant of our own Genesis code on the connection (issue 567, H7)
    case _: PeerError.GenesisInvariant => FailureClass.NotThePeers
    // Consensus 82ecba329 Node/RethrowPolicy.hs maps every ChainSyncClientException,
    // including EmptyBucket, DensityTooLow and HistoricityError, to ShutdownPeer.
    // Network 2fb69829 PeerStateActions.hs uses epErrorDelay (10s default), preserving
    // eligibility for re-promotion; Genesis refusals therefore back off rather than forget.
    // the network failed, the peer is on another network or version, behind us, or has parted
    // from our chain through nobody's fault
    case _: PeerError.Genesis | _: PeerError.Unavailable | _: PeerError.Handshake |
        PeerError.NoIntersection | _: PeerError.RolledBackPastIntersection | _: PeerError.Diverged |
        _: PeerError.MuxSetup | _: PeerError.MuxWrite =>
      FailureClass.Transient

  /** What the node does about the end of a hot session. Every outcome has an answer here; a failure
    * that is not the peer's is never a reason to try the peer again (issue 154).
    */
  def disposition(end: SessionEnd): Disposition = end match
    case SessionEnd.Failed(error) =>
      classify(error) match
        case FailureClass.Misbehaved => Disposition.Forget
        case FailureClass.Transient  => Disposition.Backoff
        // the session ends and the connection closes as for a backoff; the governor reads the
        // class from [[classify]] and counts nothing against the peer
        case FailureClass.NotThePeers => Disposition.Backoff
    case _: SessionEnd.Rejected     => Disposition.Forget
    case SessionEnd.Unavailable(e)  => Disposition.StopNode(Fatal.Unavailable(e))
    case SessionEnd.Unverifiable(e) => Disposition.StopNode(Fatal.Unverifiable(e))
    case SessionEnd.Storage(e)      => Disposition.StopNode(Fatal.Storage(e))
    case SessionEnd.Internal(e)     => Disposition.StopNode(Fatal.Internal(e))
    case SessionEnd.Defect(t)       => Disposition.StopNode(Fatal.Defect(t))
    case SessionEnd.Returned | SessionEnd.NoMoreIntersection |
        _: SessionEnd.RolledBackPastIntersection | SessionEnd.Cancelled | _: SessionEnd.Stopped =>
      Disposition.Settled
