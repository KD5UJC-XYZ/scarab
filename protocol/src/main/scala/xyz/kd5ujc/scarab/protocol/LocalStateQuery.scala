// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Point, Role}

/** Node-to-client LocalStateQuery (mini-protocol 7): the client acquires the ledger state at a
  * point, asks queries of it and releases it. `query` and `result` are single CBOR items whose
  * contents (the hard-fork-combinator query envelope and its answer) belong to the caller.
  *
  * States and agency (ouroboros-network `Protocol.LocalStateQuery.Type`):
  *   - `Idle` (client): `Acquire` or `Done`;
  *   - `Acquiring` (server): `Acquired` or `Failure`;
  *   - `Acquired` (client): `Query`, `ReAcquire` or `Release`;
  *   - `Querying` (server): `Result`.
  */
object LocalStateQuery:
  val MiniProtocolId = 7

  /** The most a client message (`Acquire`, `Query`, ...) may take: the reference reads it with the
    * same codec as an answer, with no limit and bounded only by the mux ingress queue
    * (`codecLocalStateQuery` is `Stateful.mkCodecCborLazyBS`,
    * ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalStateQuery/Codec.hs:41-59;
    * `maximumMiniProtocolLimits`, cardano-diffusion/lib/Cardano/Network/NodeToClient.hs:192), so
    * here the largest byte array.
    */
  val MaxQueryBytes: Int = Int.MaxValue

  /** The deepest nesting of a client message. The reference has no bound; ours keeps the effective
    * depth the former 65,535-item bound gave a chain of single-item wrappers (two items each),
    * which `DeepQuerySuite` exercises. The walkers of a query are stack safe, so this bounds only
    * the heap one query takes.
    */
  val MaxQueryDepth = 32767

  /** The most a server message (`Result`, ...) may take: the reference reads it with no limit
    * (`maximumMiniProtocolLimits`), so here the largest byte array.
    */
  val MaxResultBytes: Int = Int.MaxValue
  enum State:
    case Idle, Acquiring, Acquired, Querying, Done

  type Target = LocalStateQueryEngine.Target[Point]
  val Target: LocalStateQueryEngine.Target.type = LocalStateQueryEngine.Target

  enum Failure:
    /** The point is older than the immutable tip (tag 0). */
    case PointTooOld

    /** The point is not on the node's chain (tag 1). */
    case PointNotOnChain

  type Message = LocalStateQueryEngine.Message[Point]
  val Message: LocalStateQueryEngine.Message.type = LocalStateQueryEngine.Message

  def transition(state: State, sender: Role, message: Message): Either[ProtocolError, State] =
    LocalStateQueryEngine.transition(state, sender, message)

  def encode(state: State, sender: Role, message: Message): Either[ProtocolError, Bytes] =
    LocalStateQueryEngine.encode(state, sender, message, ChainSync.enginePointCodec)

  /** Strict Cardano point validation retains the original bounded reader and error ordering. */
  def decodePrefix(state: State, sender: Role, bytes: Bytes): DecodeResult[Message] =
    LocalStateQueryEngine.decodePrefix(state, sender, bytes, ChainSync.enginePointCodec)
