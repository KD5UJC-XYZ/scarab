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
    case _: PeerError.Protocol | _: PeerError.InvalidHeader => FailureClass.Misbehaved
    // the network failed, the peer is on another network or version, behind us, or has parted
    // from our chain through nobody's fault
    case _: PeerError.Unavailable | _: PeerError.Handshake | PeerError.NoIntersection |
        _: PeerError.Diverged =>
      FailureClass.Transient
