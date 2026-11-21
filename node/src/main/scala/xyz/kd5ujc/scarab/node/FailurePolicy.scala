// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.selection.FailureClass
import xyz.kd5ujc.scarab.protocol.LeiosFetchRefusal

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
    // names (network c45735a5 RethrowPolicy.hs:61-70 and 99-103, the unit `ShutdownPeer`;
    // consensus 82ecba329 Node/RethrowPolicy.hs:131-139, `theyBuggyOrEvil` and `ourBug` are both
    // `ShutdownPeer`). It is not `Misbehaved`: a decoder that throws instead of refusing may be
    // our bug on valid data, and forgetting would then drop every honest peer that sends it.
    case _: PeerError.InputDefect => FailureClass.Transient
    // A crypto provider failed on the peer's header: an exception in that peer's ChainSync thread,
    // which no rethrow policy names, so the connection closes in both directions, never forgotten
    // and never recorded as invalid (consensus 82ecba329 Node/RethrowPolicy.hs:40-45 and 131-139).
    // The reference charges the error delay; the failure is our implementation's, so nothing is
    // counted against the peer (owner decision 2026-10-28, issue 567; #584).
    case _: PeerError.Unverifiable => FailureClass.NotThePeers
    // A defect of the node's own state or configuration left the peer's header without a verdict
    // (a Dijkstra ledger view that cannot be made, a Praos2 parameter that cannot hold): the
    // reference's exception in the ChainSync client ends the peer (`ourBug`, `ShutdownPeer`); as
    // for the other defects of our own, nothing is counted against the peer
    case _: PeerError.HeaderUndecided => FailureClass.NotThePeers
    // The node's own code threw while it answered the peer (the ledger rules on a transaction the
    // peer submitted): the connection closes, as the exception ends that mini-protocol in the
    // reference (consensus 82ecba329 Mempool/Update.hs `doAddTx`, in the TxSubmission2 thread;
    // network c45735a5 RethrowPolicy.hs: `ShutdownPeer`). The reference also charges the error
    // delay; here nothing is counted against the peer, since nothing it did is shown wrong
    // (owner decision 2026-10-28, ADR 0017 amendment, issue 564).
    case _: PeerError.ServingDefect => FailureClass.NotThePeers
    // An exception of the node's own code on the connection, of no more precise origin: the
    // reference ends the peer (`ShutdownPeer`, its default); the node goes on and the peer is not
    // charged (owner decision 2026-10-28, issue 567)
    case _: PeerError.ConnectionDefect => FailureClass.NotThePeers
    // the same for an invariant of our own Genesis code on the connection (issue 567, H7)
    case _: PeerError.GenesisInvariant => FailureClass.NotThePeers
    // Our ChainSync server no longer keeps the block the peer asked for next (a node without a
    // chain database): the connection closes, and the peer, which asked for nothing wrong, is not
    // charged (#924). The reference always keeps its immutable chain and has no such case.
    case _: PeerError.NotKept => FailureClass.NotThePeers
    // Our BlockFetch server ended the connection over the peer's range: `NoGenesisBlock` and
    // `BlockGCed` (`distantPeer`), the ChainDB's `InvalidIteratorRange` (`theyBuggyOrEvil`) and the
    // ImmutableDB's `InvalidIteratorRangeError` (`ourBug`) are each `ShutdownPeer` (consensus
    // 82ecba329 Node/RethrowPolicy.hs:72-100), the peer tried again after the error delay
    case _: PeerError.RangeRefused => FailureClass.Transient
    // Our LeiosFetch server ended the connection rather than answer the peer's request: the
    // reference's handler throws `ExnLeiosUnknownBlockRequested`, `ExnLeiosUnknownTxsRequested` or a
    // `LeiosDbException` (ouroboros-consensus 2416493b LeiosDemoLogic.hs:263-307), which no rethrow
    // policy names, so each is `ShutdownPeer`, the default (Node/RethrowPolicy.hs)
    case PeerError.LeiosFetchRefused(cause) => leiosFetchRefused(cause.refusal)
    // Our LeiosFetch client ended the connection over the peer's answer: the reference's handler of
    // the answer throws with `error` or `ExnLeiosWellHashedBodyRejected` (ouroboros-consensus
    // 2416493b LeiosDemoLogic.hs:849-1172, 1395-1588 and 1972-1986), which no rethrow policy names
    // (Node/RethrowPolicy.hs), so each is `ShutdownPeer`, the default
    case _: PeerError.LeiosFetchInvalidReply => FailureClass.Transient
    // Our LeiosNotify server ended the connection over a request beyond `leiosNotifyPipelineDepth`
    // held: the reference's server throws `ExnLeiosNotifyExcessiveRequests`
    // (ouroboros-consensus 2416493b LeiosDemoOnlyTestNotify.hs:675-678 and 700-703), which no rethrow policy
    // names (Node/RethrowPolicy.hs), so it is `ShutdownPeer`, the default
    case _: PeerError.LeiosNotifyExcessiveRequests => FailureClass.Transient
    // The peer's roll-forwards claimed two endorser blocks certified for one election: the
    // reference's ChainSync client throws `ExnLeiosTwoCertificationClaims` (ouroboros-consensus
    // 2416493b LeiosDemoLogic.hs:1696-1731), which no rethrow policy names
    // (Node/RethrowPolicy.hs), so it is `ShutdownPeer`, the default
    case _: PeerError.LeiosTwoCertificationClaims => FailureClass.Transient
    // Consensus 82ecba329 Node/RethrowPolicy.hs maps every ChainSyncClientException,
    // including EmptyBucket, DensityTooLow and HistoricityError, to ShutdownPeer.
    // Network c45735a5 PeerStateActions.hs uses epErrorDelay (10s default), preserving
    // eligibility for re-promotion; Genesis refusals therefore back off rather than forget.
    // the network failed, the peer is on another network or version, behind us, or has parted
    // from our chain through nobody's fault
    case _: PeerError.Genesis | _: PeerError.Unavailable | _: PeerError.Handshake |
        PeerError.NoIntersection | _: PeerError.RolledBackPastIntersection | _: PeerError.Diverged |
        _: PeerError.MuxSetup | _: PeerError.MuxWrite =>
      FailureClass.Transient

  /** A request for a body or transactions this node does not hold is the peer's, and the peer is
    * tried again after the error delay, as `ShutdownPeer` has it. A store that failed, or a body it
    * holds that the server cannot answer with, is the node's own: the connection ends as the
    * reference's does, and nothing is counted against the peer, which asked for nothing wrong
    * (owner decision 2026-10-28, issue 567, `docs/decisions.md`).
    */
  private def leiosFetchRefused(refusal: LeiosFetchRefusal): FailureClass = refusal match
    case _: LeiosFetchRefusal.UnknownBlockRequested | _: LeiosFetchRefusal.UnknownTxsRequested =>
      FailureClass.Transient
    case _: LeiosFetchRefusal.StoredBodyUndecodable | _: LeiosFetchRefusal.StoredBodyTooLarge |
        _: LeiosFetchRefusal.Store =>
      FailureClass.NotThePeers

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
