// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Point}

/** Strict Cardano BlockFetch facade over the shared typed engine. Point parsing retains the
  * original ProtocolWire reader/writer path, including malformed-point error ordering.
  */
object BlockFetch:
  val MiniProtocolId: Int = 3

  // Explicit enum exports keep generated forwarders in a fixed order for reproducible artifacts.
  type State = BlockFetchEngine.State
  object State:
    export BlockFetchEngine.State.{Idle, Busy, Streaming, Done}
    val values: Array[State] = BlockFetchEngine.State.values

  type Role = BlockFetchEngine.Role
  object Role:
    export BlockFetchEngine.Role.{Client, Server}
    val values: Array[Role] = BlockFetchEngine.Role.values

  type Message[+P] = BlockFetchEngine.Message[Point, P]
  object Message:
    object RequestRange:
      def apply(from: Point, to: Point): Message[Nothing] =
        BlockFetchEngine.Message.RequestRange(from, to)
      def unapply[P](message: Message[P]): Option[(Point, Point)] = message match
        case BlockFetchEngine.Message.RequestRange(from, to) => Some((from, to))
        case _                                               => None
    export BlockFetchEngine.Message.{ClientDone, StartBatch, NoBlocks, Block, BatchDone}

  /** Extends the existing pure adapter seam. Specialized adapters may check framing and declared
    * lengths before waiting for the body. Generic adapters receive one CBOR item.
    */
  type PayloadCodec[P] = BlockFetchEngine.PayloadCodec[P]

  type Limits = BlockFetchEngine.Limits
  val Limits: BlockFetchEngine.Limits.type = BlockFetchEngine.Limits

  type TimeLimits = BlockFetchEngine.TimeLimits
  val TimeLimits: BlockFetchEngine.TimeLimits.type = BlockFetchEngine.TimeLimits

  type RequestLimits = BlockFetchEngine.RequestLimits
  val RequestLimits: BlockFetchEngine.RequestLimits.type = BlockFetchEngine.RequestLimits

  type Received = BlockFetchEngine.Received
  object Received:
    val Empty: Received = BlockFetchEngine.Received.Empty
    def add(previous: Received, limits: RequestLimits): Either[ProtocolError, Received] =
      BlockFetchEngine.Received.add(previous, limits)

  private val cardanoPointCodec: BlockFetchEngine.PointCodec[Point] = CardanoBlockFetch.pointCodec

  def direction(sender: Role): Mux.Direction = BlockFetchEngine.direction(sender)

  def transition[P](state: State, sender: Role, message: Message[P]): Either[ProtocolError, State] =
    BlockFetchEngine.transition(state, sender, message)

  def encode[P](
      state: State,
      sender: Role,
      message: Message[P],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] =
    BlockFetchEngine.encode(state, sender, message, cardanoPointCodec, payload, limits)

  /** Decodes one message without consuming a suffix. Input is caller-owned and must itself be
    * ingress-bounded. This stateless prefix API does not buffer or accumulate chunks.
    */
  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): DecodeResult[Message[P]] =
    BlockFetchEngine.decodePrefix(state, sender, bytes, cardanoPointCodec, payload, limits)

  /** End-of-input/exact-message boundary: incomplete input is an explicit failure, never success.
    */
  def decode[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, Message[P]] =
    BlockFetchEngine.decode(state, sender, bytes, cardanoPointCodec, payload, limits)
