// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.Mux as WireMux

/** Closed set of network runtime failures. Algebras raise these through cats-mtl `Raise`; because
  * `Raise` is contravariant in its error, any `Raise[F, E]` with `NetworkError <: E` serves them.
  * `protocol` is a mini-protocol number, see [[Protocols]].
  */
enum NetworkError:
  case Connect(address: String, reason: String)
  case Timeout(protocol: Int, state: String)
  case SduTimeout(operation: NetworkError.SduOperation)
  case TxSubmission(violation: TxSubmissionPolicy.Violation)
  case HandshakeRefused(reason: String)
  case VersionMismatch(offered: Vector[Int], supported: Vector[Int])
  case IngressLimit(protocol: Int, bytes: Long)
  case Decode(protocol: Int, reason: String)
  case IllegalTransition(protocol: Int, detail: String)
  case QueryRefused(refusal: LocalStateQueryServer.Refusal)
  case BearerClosed(reason: String)

  /** An unexpected library failure or bug; never a recoverable failure of the peer. */
  case Defect(cause: Throwable)

  /** The peer's bytes are not a well-formed multiplexer frame (a header that cannot be read, a
    * payload past the limit): the multiplexer cannot tell which protocol was meant.
    */
  case Framing(cause: WireMux.Error)

  /** Local setup/encoding failures, distinct from malformed peer input. */
  case MuxSetup(cause: WireMux.Error)
  case MuxWrite(cause: WireMux.Error)

  def message: String = this match
    case Connect(a, r)       => s"connect $a: $r"
    case Timeout(p, s)       => s"${Protocols.name(p)} timed out in $s"
    case SduTimeout(op)      => s"multiplexer SDU ${op.toString.toLowerCase} timed out"
    case TxSubmission(v)     => s"tx-submission-2 protocol violation: ${v.message}"
    case HandshakeRefused(r) => s"handshake refused: $r"
    case VersionMismatch(o, s) =>
      s"no common version (offered ${o.mkString(",")}, peer supports ${s.mkString(",")})"
    case IngressLimit(p, b)      => s"${Protocols.name(p)} exceeded the ingress limit at $b bytes"
    case Decode(p, r)            => s"${Protocols.name(p)} decode: $r"
    case IllegalTransition(p, d) => s"${Protocols.name(p)} protocol violation: $d"
    case QueryRefused(r) =>
      s"${Protocols.name(Protocols.LocalStateQuery)} query not answered: ${r.message}"
    case BearerClosed(r) => s"connection closed: $r"
    case Defect(t)       => s"network defect: ${Option(t.getMessage).getOrElse(t.getClass.getName)}"
    case Framing(e)      => s"multiplexer framing: ${e.message}"
    case MuxSetup(e)     => s"multiplexer setup: ${e.message}"
    case MuxWrite(e)     => s"multiplexer write: ${e.message}"

object NetworkError:
  enum SduOperation:
    case Read, Write

  /** Only operating-system transport failures are connection failures. Preserve every unexpected
    * exception itself for the owning node's fatal boundary.
    */
  def fromThrowable(context: String, cause: Throwable): NetworkError = cause match
    case io: java.io.IOException =>
      BearerClosed(s"$context: ${Option(io.getMessage).getOrElse(io.getClass.getName)}")
    case other => Defect(other)

/** Mini-protocol numbers: the node-to-node ones and the node-to-client ones (5, 6, 7 and 9). */
object Protocols:
  val Handshake = 0
  val ChainSync = 2
  val BlockFetch = 3
  val TxSubmission2 = 4
  val KeepAlive = 8
  val PeerSharing = 10
  val LocalChainSync = 5
  val LocalTxSubmission = 6
  val LocalStateQuery = 7
  val LocalTxMonitor = 9
  val LeiosNotify = 18
  val LeiosFetch = 19

  def name(protocol: Int): String = protocol match
    case Handshake         => "handshake"
    case ChainSync         => "chain-sync"
    case BlockFetch        => "block-fetch"
    case TxSubmission2     => "tx-submission-2"
    case KeepAlive         => "keep-alive"
    case PeerSharing       => "peer-sharing"
    case LocalChainSync    => "local-chain-sync"
    case LocalTxSubmission => "local-tx-submission"
    case LocalStateQuery   => "local-state-query"
    case LocalTxMonitor    => "local-tx-monitor"
    case LeiosNotify       => "leios-notify"
    case LeiosFetch        => "leios-fetch"
    case other             => s"protocol-$other"
