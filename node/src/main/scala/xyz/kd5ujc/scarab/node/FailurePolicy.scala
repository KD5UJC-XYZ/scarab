// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.selection.FailureClass

/** How the governor treats each way a peer can fail (owner decision 8, docs/design/
  * peer-selection.md section 8a): a peer that broke the protocol, sent an invalid header or an
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
    case _: SessionEnd.Rejected    => Disposition.Forget
    case SessionEnd.Unavailable(e) => Disposition.StopNode(Fatal.Unavailable(e))
    case SessionEnd.Storage(e)     => Disposition.StopNode(Fatal.Storage(e))
    case SessionEnd.Internal(e)    => Disposition.StopNode(Fatal.Internal(e))
    case SessionEnd.Defect(t)      => Disposition.StopNode(Fatal.Defect(t))
    case SessionEnd.Returned | SessionEnd.NoMoreIntersection |
        _: SessionEnd.RolledBackPastIntersection | SessionEnd.Cancelled | _: SessionEnd.Stopped =>
      Disposition.Settled
