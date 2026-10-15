// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

/** Closed set of network runtime failures. Algebras raise these through cats-mtl `Raise`; because
  * `Raise` is contravariant in its error, any `Raise[F, E]` with `NetworkError <: E` serves them.
  * `protocol` is a mini-protocol number, see [[Protocols]].
  */
enum NetworkError:
  case Connect(address: String, reason: String)
  case Timeout(protocol: Int, state: String)
  case HandshakeRefused(reason: String)
  case VersionMismatch(offered: Vector[Int], supported: Vector[Int])
  case IngressLimit(protocol: Int, bytes: Int)
  case Decode(protocol: Int, reason: String)
  case IllegalTransition(protocol: Int, detail: String)
  case BearerClosed(reason: String)

  def message: String = this match
    case Connect(a, r)       => s"connect $a: $r"
    case Timeout(p, s)       => s"${Protocols.name(p)} timed out in $s"
    case HandshakeRefused(r) => s"handshake refused: $r"
    case VersionMismatch(o, s) =>
      s"no common version (offered ${o.mkString(",")}, peer supports ${s.mkString(",")})"
    case IngressLimit(p, b)      => s"${Protocols.name(p)} exceeded the ingress limit at $b bytes"
    case Decode(p, r)            => s"${Protocols.name(p)} decode: $r"
    case IllegalTransition(p, d) => s"${Protocols.name(p)} protocol violation: $d"
    case BearerClosed(r)         => s"connection closed: $r"

/** Mini-protocol numbers: the node-to-node ones and the node-to-client ones (5, 6, 7 and 9). */
object Protocols:
  val Handshake = 0
  val ChainSync = 2
  val BlockFetch = 3
  val TxSubmission2 = 4
  val KeepAlive = 8
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
    case LocalChainSync    => "local-chain-sync"
    case LocalTxSubmission => "local-tx-submission"
    case LocalStateQuery   => "local-state-query"
    case LocalTxMonitor    => "local-tx-monitor"
    case LeiosNotify       => "leios-notify"
    case LeiosFetch        => "leios-fetch"
    case other             => s"protocol-$other"
