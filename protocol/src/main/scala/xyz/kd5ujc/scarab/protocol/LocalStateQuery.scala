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
  /** The most a client message (`Acquire`, `Query`, ...) may take. */
  val MaxMessageBytes = 2500000

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
