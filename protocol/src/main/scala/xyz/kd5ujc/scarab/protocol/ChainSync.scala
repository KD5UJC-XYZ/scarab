// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** Pure, non-pipelined ChainSync envelope. No transport or Cardano payload validation. */
object ChainSync:
  final class UInt64 private (val value: BigInt):
    override def equals(other: Any): Boolean = other match
      case n: UInt64 => value == n.value
      case _         => false
    override def hashCode: Int = value.hashCode
    override def toString: String = value.toString
  object UInt64:
    val Max: BigInt = (BigInt(1) << 64) - 1
    val Zero: UInt64 = new UInt64(0)
    def from(value: BigInt): Either[ProtocolError, UInt64] =
      if value < 0 || value > Max then Left(ProtocolError.OutsideUint64(value))
      else Right(new UInt64(value))

    /** Total conversion for values that are unsigned 64-bit by construction (decoded CBOR unsigned
      * integers, slot and block counters). Out-of-range input saturates; it cannot occur for such
      * values, so saturation exists only to keep the function total.
      */
    def saturating(value: BigInt): UInt64 =
      if value < 0 then Zero else new UInt64(value.min(Max))

  enum Point:
    case Origin
    case Block(slot: UInt64, hash: Bytes)
  final class Tip private (val point: Point, val blockNo: UInt64):
    override def equals(other: Any): Boolean = other match
      case t: Tip => point == t.point && blockNo == t.blockNo
      case _      => false
    override def hashCode: Int = (point, blockNo).hashCode
  object Tip:
    val Origin: Tip = new Tip(Point.Origin, UInt64.Zero)
    // Source decodeTip discards blockNo at Origin, including a nonzero wire value.
    def apply(point: Point, blockNo: UInt64): Tip = point match
      case Point.Origin => Origin
      case _            => new Tip(point, blockNo)

  enum State:
    case Idle, NextCanAwait, NextMustReply, Intersect, Done
  enum Role:
    case Client, Server
  enum Message[+P]:
    case RequestNext
    case AwaitReply
    case RollForward(payload: P, tip: Tip)
    case RollBackward(point: Point, tip: Tip)
    case FindIntersect(points: Vector[Point])
    case IntersectFound(point: Point, tip: Tip)
    case IntersectNotFound(tip: Tip)
    case Done

  /** The bytes of a point's hash: the reference reads it with `decodeRawHash`, which refuses any
    * other length than the block's `HashSize` (`Ouroboros/Consensus/Block/Abstract.hs:276-279,
    * 307-314`, ouroboros-consensus 82ecba32), 32 in every Cardano era (`HashSize ByronBlock = 32`,
    * `Byron/Ledger/Block.hs:84`, the same across eras by `CanHardFork`; #755).
    */
  val HashBytes: Int = 32

  enum DecodeResult[+A]:
    case NeedMore
    case Failed(error: ProtocolError)
    case Decoded(value: A, consumed: Int)

  /** Bounds are local policy. 65535 is the pinned network smallByteLimit per message on
    * node-to-node. `local` lifts the message and string bound to the 2.5 MB of a mux message, for
    * the node-to-client protocol, which carries whole blocks and has no byte limit upstream.
    *
    * @param maxItems
    *   the most CBOR items of a message: 65535, so that within the 65535 bytes of a node-to-node
    *   message, where every item takes a byte, it never binds; the reference counts none (#755).
    * @param maxCandidates
    *   the most points this node puts in a `MsgFindIntersect` it sends. One it receives is read
    *   with `decodeList` and bounded by its bytes alone (`ChainSync/Codec.hs:165-166,209-214`,
    *   ouroboros-network 2fb69829; #755).
    */
  final case class Limits(
      maxMessageBytes: Int = 65535,
      maxDepth: Int = 24,
      maxItems: Int = 65535,
      maxStringBytes: Int = 65535,
      maxCandidates: Int = 64,
      maxHashBytes: Int = 64,
      local: Boolean = false
  ):
    private[protocol] def valid: Boolean =
      val ceiling = if local then 2500000 else 65535
      maxMessageBytes > 0 && maxMessageBytes <= ceiling && maxDepth >= 0 && maxDepth <= 64 &&
      maxItems > 0 && maxItems <= 65535 && maxStringBytes >= 0 && maxStringBytes <= ceiling &&
      maxCandidates >= 0 && maxCandidates <= 1024 && maxHashBytes >= 0 && maxHashBytes <= 65535

  object Limits:
    /** The node-to-client profile: whole blocks (`RawNtNBlock`s up to 2499991 bytes) and the
      * longest intersection list a client sends.
      */
    val NodeToClient: Limits =
      Limits(
        maxMessageBytes = 2500000,
        maxStringBytes = 2500000,
        maxCandidates = 1024,
        local = true
      )

  /** A payload adapter receives exactly one bounded syntactically valid CBOR item. */
  trait PayloadCodec[P]:
    def encode(value: P): Either[ProtocolError, Bytes]
    def decode(bytes: Bytes): Either[ProtocolError, P]

  /** Strict Cardano point and tip adapters used by the generic network runtime. Their reader and
    * writer hooks retain the direct validation order of this facade.
    */
  private[scarab] val enginePointCodec: ChainSyncEngine.PointCodec[Point] =
    new ChainSyncEngine.PointCodec[Point]:
      def encode(value: Point): Either[ProtocolError, Bytes] = encodePoint(value)
      def decode(item: Bytes): Either[ProtocolError, Point] =
        decoded(decodePoint(item), item.size).flatMap(cardano)
      override private[protocol] def readFrom(reader: ProtocolWire.Reader)(using
          ProtocolWire.Scope
      ): Point = get(cardano(reader.point()))
      override private[protocol] def writeTo(
          writer: ProtocolWire.Writer,
          value: Point,
          bounds: ProtocolWire.Limits
      )(using ProtocolWire.Scope): Unit = writer.point(value)

  private[scarab] val engineTipCodec: ChainSyncEngine.TipCodec[Tip] =
    new ChainSyncEngine.TipCodec[Tip]:
      def encode(value: Tip): Either[ProtocolError, Bytes] = encodeTip(value)
      def decode(item: Bytes): Either[ProtocolError, Tip] =
        decoded(decodeTip(item), item.size).flatMap(t => cardano(t.point).map(_ => t))
      override private[protocol] def readFrom(reader: ProtocolWire.Reader)(using
          ProtocolWire.Scope
      ): Tip =
        val t = reader.tip()
        get(cardano(t.point))
        t
      override private[protocol] def writeTo(
          writer: ProtocolWire.Writer,
          value: Tip,
          bounds: ProtocolWire.Limits
      )(using ProtocolWire.Scope): Unit = writer.tip(value)

  private[scarab] def enginePayloadCodec[P](
      codec: PayloadCodec[P]
  ): ChainSyncEngine.PayloadCodec[P] =
    new ChainSyncEngine.PayloadCodec[P]:
      def encode(value: P): Either[ProtocolError, Bytes] = codec.encode(value)
      def decode(item: Bytes): Either[ProtocolError, P] = codec.decode(item)

  /** A point of the Cardano chain as the reference's codecs read it: its hash, if it has one, is of
    * [[HashBytes]] (`decodeRawHash`).
    */
  private def cardano(point: Point): Either[ProtocolError, Point] = point match
    case Point.Block(_, hash) if hash.size != HashBytes =>
      Left(ProtocolError.CardanoPointHashMustContain32Bytes(hash.size))
    case _ => Right(point)

  private def decoded[A](result: DecodeResult[A], consumed: Int): Either[ProtocolError, A] =
    result match
      case DecodeResult.Decoded(value, n) if n == consumed => Right(value)
      case DecodeResult.Decoded(_, _) => Left(ProtocolError.TrailingPayloadBytes)
      case DecodeResult.NeedMore      => Left(ProtocolError.TruncatedCbor)
      case DecodeResult.Failed(error) => Left(error)

  private def toEngine[P](message: Message[P]): ChainSyncEngine.Message[Point, Tip, P] =
    message match
      case Message.RequestNext                => ChainSyncEngine.Message.RequestNext
      case Message.AwaitReply                 => ChainSyncEngine.Message.AwaitReply
      case Message.RollForward(payload, tip)  => ChainSyncEngine.Message.RollForward(payload, tip)
      case Message.RollBackward(point, tip)   => ChainSyncEngine.Message.RollBackward(point, tip)
      case Message.FindIntersect(points)      => ChainSyncEngine.Message.FindIntersect(points)
      case Message.IntersectFound(point, tip) => ChainSyncEngine.Message.IntersectFound(point, tip)
      case Message.IntersectNotFound(tip)     => ChainSyncEngine.Message.IntersectNotFound(tip)
      case Message.Done                       => ChainSyncEngine.Message.Done

  private def fromEngine[P](message: ChainSyncEngine.Message[Point, Tip, P]): Message[P] =
    message match
      case ChainSyncEngine.Message.RequestNext                => Message.RequestNext
      case ChainSyncEngine.Message.AwaitReply                 => Message.AwaitReply
      case ChainSyncEngine.Message.RollForward(payload, tip)  => Message.RollForward(payload, tip)
      case ChainSyncEngine.Message.RollBackward(point, tip)   => Message.RollBackward(point, tip)
      case ChainSyncEngine.Message.FindIntersect(points)      => Message.FindIntersect(points)
      case ChainSyncEngine.Message.IntersectFound(point, tip) => Message.IntersectFound(point, tip)
      case ChainSyncEngine.Message.IntersectNotFound(tip)     => Message.IntersectNotFound(tip)
      case ChainSyncEngine.Message.Done                       => Message.Done

  def transition[P](state: State, sender: Role, message: Message[P]): Either[ProtocolError, State] =
    ChainSyncEngine.transition(state, sender, toEngine(message))

  private[protocol] def bad(error: ProtocolError)(using ProtocolWire.Scope): Nothing =
    ProtocolWire.bad(error)
  private[protocol] def get[A](value: Either[ProtocolError, A])(using ProtocolWire.Scope): A =
    ProtocolWire.get(value)
  private def attempt[A](body: ProtocolWire.Scope ?=> A): Either[ProtocolError, A] =
    ProtocolWire.attempt(body)

  def encodePoint(point: Point, limits: Limits = Limits()): Either[ProtocolError, Bytes] = attempt {
    val writer = new ChainSyncWire.Writer(limits)
    writer.point(point)
    val result = writer.result
    get(validateItem(result, limits))
    result
  }
  def encodeTip(tip: Tip, limits: Limits = Limits()): Either[ProtocolError, Bytes] = attempt {
    val writer = new ChainSyncWire.Writer(limits)
    writer.tip(tip)
    val result = writer.result
    get(validateItem(result, limits))
    result
  }
  def decodePoint(bytes: Bytes, limits: Limits = Limits()): DecodeResult[Point] =
    parse(bytes, limits)(_.point())
  def decodeTip(bytes: Bytes, limits: Limits = Limits()): DecodeResult[Tip] =
    parse(bytes, limits)(_.tip())

  private def parse[A](bytes: Bytes, limits: Limits)(
      body: ProtocolWire.Scope ?=> ChainSyncWire.Reader => A
  ): DecodeResult[A] =
    ProtocolWire.prefix {
      val reader = new ChainSyncWire.Reader(bytes, limits)
      val value = body(reader)
      get(validateItem(bytes.take(reader.position), limits))
      DecodeResult.Decoded(value, reader.position)
    }

  def encode[P](
      state: State,
      sender: Role,
      message: Message[P],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] =
    ChainSyncEngine.encode(
      state,
      sender,
      toEngine(message),
      enginePointCodec,
      engineTipCodec,
      enginePayloadCodec(payload),
      limits
    )

  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): DecodeResult[Message[P]] =
    ChainSyncEngine.decodePrefix(
      state,
      sender,
      bytes,
      enginePointCodec,
      engineTipCodec,
      enginePayloadCodec(payload),
      limits
    ) match
      case DecodeResult.NeedMore      => DecodeResult.NeedMore
      case DecodeResult.Failed(error) => DecodeResult.Failed(error)
      case DecodeResult.Decoded(message, consumed) =>
        DecodeResult.Decoded(fromEngine(message), consumed)

  def validateItem(bytes: Bytes, limits: Limits = Limits()): Either[ProtocolError, Unit] = attempt {
    val r = new ChainSyncWire.Reader(bytes, limits)
    r.skip(0)
    if r.position != bytes.size then bad(ProtocolError.TrailingPayloadBytes)
  }
