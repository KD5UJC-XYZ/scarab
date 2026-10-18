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
    // the network failed, the peer is on another network or version, behind us, or has parted
    // from our chain through nobody's fault
    case _: PeerError.Unavailable | _: PeerError.Handshake | PeerError.NoIntersection |
        _: PeerError.Diverged | _: PeerError.MuxSetup | _: PeerError.MuxWrite =>
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
    case SessionEnd.Returned | SessionEnd.Cancelled | _: SessionEnd.Stopped => Disposition.Settled
