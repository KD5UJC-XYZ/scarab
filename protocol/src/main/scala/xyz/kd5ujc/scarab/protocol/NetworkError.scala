// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.protocol.Mux as WireMux

/** Closed set of network runtime failures. Algebras raise these through cats-mtl `Raise`; because
  * `Raise` is contravariant in its error, any `Raise[F, E]` with `NetworkError <: E` serves them.
  * `protocol` is a mini-protocol number, see [[Protocols]].
  */
enum NetworkError extends DomainError:
  /** A connection to `address` could not be opened, for its transport's own typed `cause`. */
  case Connect(address: String, cause: TcpSocketError[?, ?])
  case Timeout(protocol: Int, state: String)
  case SduTimeout(operation: NetworkError.SduOperation)
  case TxSubmission(violation: TxSubmissionPolicy.Violation)
  case HandshakeRefused(reason: NetworkError.HandshakeFailure)
  case VersionMismatch(offered: Vector[Int], supported: Vector[Int])
  case IngressLimit(protocol: Int, bytes: Long)
  case Decode(protocol: Int, cause: ProtocolError)
  case IllegalTransition(protocol: Int, cause: NetworkError.Violation)

  /** The LeiosFetch server ends the connection rather than answer a request. */
  case LeiosFetchRefused(refusal: LeiosFetchRefusal)

  /** The LeiosFetch client ends the connection over the peer's answer. */
  case LeiosFetchInvalidReply(fault: LeiosFetchFault)
  case QueryRefused(refusal: QueryRefusal[?])

  /** The selected ledger view and its backing UTxO generation did not have a provable relation. */
  case StateAcquireFailure(
      point: ChainSync.Point,
      flushed: Long,
      pinned: Long,
      selected: BigInt
  )
  case BearerClosed(reason: NetworkError.Closure)

  /** A ChainSync client's next block, the one after `after`, is not kept: the node has no chain
    * database, and the block has left its volatile chain. No instruction can follow, so the server
    * ends the connection instead of leaving the client to wait. The reference always keeps its
    * immutable chain and has no such case.
    */
  case NotKept(protocol: Int, after: ChainSync.Point)

  /** Our BlockFetch server ended the connection over a range the peer asked for, for `refusal`, as
    * the reference's server throws for it (ouroboros-consensus 82ecba32,
    * `MiniProtocol/BlockFetch/Server.hs` 107-146): its mux stops on the exception and the
    * connection closes (network-mux `Mux.hs` 458-463, ouroboros-network c45735a5), with no
    * `NoBlocks` and no `BatchDone` for that range. The node goes on: the reference's rethrow policy
    * makes each of them `ShutdownPeer` (`Node/RethrowPolicy.hs` 72-100).
    */
  case RangeRefused(refusal: NetworkError.RangeRefusal)

  /** An accepted connection stayed silent after its handshake for the protocol idle timeout: no
    * remote-initiated mini-protocol sent a byte (ouroboros-network c45735a5,
    * `defaultProtocolIdleTimeout`).
    */
  case ProtocolIdleTimeout(after: scala.concurrent.duration.FiniteDuration)

  /** An unexpected library failure or bug of unknown origin; never a recoverable failure of the
    * peer. It stops the node at the owner's fatal boundary.
    */
  case Defect(cause: Throwable)

  /** The decoder of `protocol` threw on the peer's bytes instead of answering: still a defect with
    * its original cause, but one whose origin is this connection's input. The policy is the
    * reference's for an exception in a mini-protocol thread: the connection is closed and the peer
    * is charged, the node goes on (network `RethrowPolicy.hs`: an exception no policy names is
    * `ShutdownPeer`).
    */
  case DecoderDefect(protocol: Int, cause: Throwable)

  /** The node's own code threw while it answered the peer's message on `protocol` (the ledger rules
    * on a submitted transaction): no verdict, and nothing the peer is shown to have done wrong. As
    * in the reference, where the exception ends that mini-protocol's thread, the connection is
    * closed and the node goes on (consensus 82ecba329 `Mempool/Update.hs` `doAddTx`; network
    * `RethrowPolicy.hs`: `ShutdownPeer`). Unlike the reference, the peer is not charged: owner
    * decision of 2026-10-28, ADR 0017 amendment of that date (issue 564).
    */
  case ServingDefect(protocol: Int, cause: Throwable)

  /** The bearer's reader threw while it read the peer's bytes or routed them to their protocol:
    * still a defect with its original cause, but one met on this connection's input. As for an
    * exception in a bearer thread in the reference, the connection is closed and the peer is
    * charged, the node goes on (network `RethrowPolicy.hs`: an exception no policy names is
    * `ShutdownPeer`; `ConnectionHandler.hs` `classifyExceptions`). An `IOException` of the socket
    * stays a closed connection ([[fromThrowable]]).
    */
  case BearerDefect(cause: Throwable)

  /** The peer's bytes are not a well-formed multiplexer frame (a header that cannot be read, a
    * payload past the limit): the multiplexer cannot tell which protocol was meant.
    */
  case Framing(cause: WireMux.Error)

  /** Local setup/encoding failures, distinct from malformed peer input. */
  case MuxSetup(cause: WireMux.Error)
  case MuxWrite(cause: WireMux.Error)

  def message: String = this match
    case Connect(a, r)       => s"connect $a: ${r.show}"
    case Timeout(p, s)       => s"${Protocols.name(p)} timed out in $s"
    case SduTimeout(op)      => s"multiplexer SDU ${op.toString.toLowerCase} timed out"
    case TxSubmission(v)     => s"tx-submission-2 protocol violation: ${v.message}"
    case HandshakeRefused(r) => s"handshake refused: ${r.message}"
    case VersionMismatch(o, s) =>
      s"no common version (offered ${o.mkString(",")}, peer supports ${s.mkString(",")})"
    case IngressLimit(p, b) => s"${Protocols.name(p)} exceeded the ingress limit at $b bytes"
    case Decode(p, r)       => s"${Protocols.name(p)} decode: ${r.message}"
    case IllegalTransition(p, d) =>
      s"${Protocols.name(p)} protocol violation: ${NetworkError.violationMessage(d)}"
    case LeiosFetchRefused(r) =>
      s"${Protocols.name(Protocols.LeiosFetch)} request refused: ${r.message}"
    case LeiosFetchInvalidReply(f) =>
      s"${Protocols.name(Protocols.LeiosFetch)} answer refused: ${f.message}"
    case QueryRefused(r) =>
      s"${Protocols.name(Protocols.LocalStateQuery)} query not answered: ${r.message}"
    case StateAcquireFailure(point, flushed, pinned, selected) =>
      s"${Protocols.name(Protocols.LocalStateQuery)} acquired UTxO view at $point with stamp $pinned; expected $flushed..$selected"
    case BearerClosed(r) => s"connection closed: ${r.message}"
    case NotKept(p, after) =>
      s"${Protocols.name(p)}: the block after ${NetworkError.pointText(after)} is not kept: the node keeps no chain database"
    case RangeRefused(refusal) =>
      s"${Protocols.name(Protocols.BlockFetch)}: the range ends the connection: ${refusal.message}"
    case ProtocolIdleTimeout(after) =>
      s"no mini-protocol activity within ${after.toSeconds} s of the handshake"
    case Defect(t) => s"network defect: ${NetworkError.describe(t)}"
    case ServingDefect(p, t) =>
      s"${Protocols.name(p)}: the node's own code threw while answering: ${NetworkError.describe(t)}"
    case DecoderDefect(p, t) =>
      s"${Protocols.name(p)} decoder defect: ${NetworkError.describe(t)}"
    case BearerDefect(t) => s"bearer reader defect: ${NetworkError.describe(t)}"
    case Framing(e)      => s"multiplexer framing: ${e.message}"
    case MuxSetup(e)     => s"multiplexer setup: ${e.message}"
    case MuxWrite(e)     => s"multiplexer write: ${e.message}"

object NetworkError:
  /** Internal invariant failure crossing from the network's peer-error scope to the node owner. */
  final class InternalFailure(val error: NetworkError) extends RuntimeException(error.message)

  enum ConnectionRole:
    case Initiator, Responder

  type Violation = ProtocolError | RuntimeViolation
  enum Closure:
    case PeerClosed
    case Aborted
    case AbortedBy[E <: DomainError](cause: E)
    case Transport(context: String, cause: java.io.IOException)
    def message: String = this match
      case PeerClosed                => "closed by peer"
      case Aborted                   => "aborted"
      case AbortedBy(cause)          => s"aborted: $cause"
      case Transport(context, cause) => s"$context: ${cause.getMessage}"
  enum HandshakeFailure:
    case Local(cause: ProtocolError)
    case Remote(reason: Handshake.Reason)
    case VersionMismatch
    case QueryAnswered
    case UnexpectedQueryReply
    def message: String = this match
      case Local(cause)                     => cause.message
      case Remote(reason)                   => reason.toString
      case HandshakeFailure.VersionMismatch => "version mismatch"
      case QueryAnswered                    => "version query answered"
      case UnexpectedQueryReply             => "unexpected query reply"
  enum RuntimeViolation:
    case ReplyNotExpected(state: ChainSync.State | BlockFetch.State)
    case NotRegistered(role: NetworkError.ConnectionRole)
    case UnofferedLocalVersion(version: Int)
    case UnexpectedProtocol(protocol: Int)
    case WrongModeBit
    case ApplicationAlreadyInstalled
    case OutgoingMessageSize(actual: Int, maximum: Int)
    case IntersectionNotOffered
    case PipelinedRequest(state: ChainSync.State)
    case PipelineCapacity(maximum: Int)
    case InvalidPipelineCapacity(value: Int)
    case PeerSharingUnexpected(message: xyz.kd5ujc.scarab.protocol.PeerSharing.Message)
    case PeerSharingPolicy(cause: xyz.kd5ujc.scarab.protocol.PeerSharing.Violation)
    case TransactionNotRequested
    case KeepAliveUnexpected(message: KeepAlive.Message)
    case ChainSyncUnexpected(message: ChainSync.Message[?])
    case ChainSyncEngineUnexpected(message: ChainSyncEngine.Message[?, ?, ?])
    case BlockFetchUnexpected(message: BlockFetch.Message[?])
    case BlockFetchEngineUnexpected(message: BlockFetchEngine.Message[?, ?])
    case BlockFetchTooFewBlocks(expected: Int, actual: Int)
    case BlockFetchTooManyBlocks(expected: Int, actual: Int)
    case TxSubmissionUnexpected(message: TxSubmission2.Message)
    case LocalTxSubmissionUnexpected(message: LocalTxSubmission.Message)
    case LocalTxMonitorUnexpected(message: LocalTxMonitor.Message)
    case LocalStateQueryUnexpected(message: LocalStateQuery.Message)
    case LocalStateQueryEngineUnexpected(message: LocalStateQueryEngine.Message[?])

  def violationMessage(cause: Violation): String = cause match
    case error: ProtocolError    => error.message
    case error: RuntimeViolation => error.toString

  enum SduOperation:
    case Read, Write

  /** Why our BlockFetch server ends the connection over a range ([[RangeRefused]]): one case for
    * each exception the reference raises for a range, named as it is (ouroboros-consensus
    * 82ecba32).
    */
  enum RangeRefusal:
    /** An end of the range is Origin: the server's `NoGenesisBlock` (`BlockFetchServerException`,
      * `MiniProtocol/BlockFetch/Server.hs` 42-67, raised at 107-112), before anything is looked up.
      */
    case NoGenesisBlock

    /** The bounds `from` to `to` are not a range of the ChainDB: a lower bound in a later slot than
      * the upper, or a walk back from the upper bound that reaches the start of the chain without
      * meeting the lower (`ChainDbError`'s `InvalidIteratorRange`, `Storage/ChainDB/API.hs`
      * 981-999, raised at `Storage/ChainDB/Impl/Iterator.hs` 254-257 and 494-504).
      */
    case InvalidIteratorRange(from: ChainSync.Point, to: ChainSync.Point)

    /** The bounds `from` to `to` the ImmutableDB is asked to stream between are not a range: a
      * lower bound in a later slot, or a main block before the boundary block of its slot
      * (`ApiMisuse`'s `InvalidIteratorRangeError`, `Storage/ImmutableDB/API.hs` 370-379, raised at
      * `Storage/ImmutableDB/Impl/Iterator.hs` 134-159).
      */
    case InvalidIteratorRangeError(from: ChainSync.Point, to: ChainSync.Point)

    /** The block at `point`, part of a batch already started, was collected since and is not in the
      * immutable chain: the iterator's `IteratorBlockGCed`, which the server raises as `BlockGCed`
      * (`Storage/ChainDB/Impl/Iterator.hs` 654-695 and 774-784; `Server.hs` 42-67 and 144-146).
      */
    case BlockGCed(point: ChainSync.Point)

    def message: String = this match
      case NoGenesisBlock => "an end of the range is Origin"
      case InvalidIteratorRange(from, to) =>
        s"${NetworkError.pointText(from)} to ${NetworkError.pointText(to)} is not a range of the chain database"
      case InvalidIteratorRangeError(from, to) =>
        s"${NetworkError.pointText(from)} to ${NetworkError.pointText(to)} is not a range of the immutable chain"
      case BlockGCed(point) => s"${NetworkError.pointText(point)} was collected during the batch"

  /** A point as the messages write it. */
  private def pointText(point: ChainSync.Point): String = point match
    case ChainSync.Point.Origin            => "origin"
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}@${hash.hex.take(16)}"

  /** The text of an unexpected exception, for presentation only. */
  def describe(cause: Throwable): String =
    Option(cause.getMessage).getOrElse(cause.getClass.getName)

  /** Only operating-system transport failures are connection failures. Preserve every other
    * exception itself for the owning node's fatal boundary; an exception of a decoder on the peer's
    * bytes is [[DecoderDefect]], made where the decoder runs (`MuxChannel.recv`), not here.
    */
  def fromThrowable(context: String, cause: Throwable): NetworkError = cause match
    case io: java.io.IOException =>
      BearerClosed(Closure.Transport(context, io))
    case other => Defect(other)

  /** An exception of the bearer's reader: a transport failure is a closed connection, any other a
    * [[BearerDefect]] of this connection.
    */
  def ofReader(cause: Throwable): NetworkError = cause match
    case io: java.io.IOException => BearerClosed(Closure.Transport("bearer failed", io))
    case other                   => BearerDefect(other)

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
