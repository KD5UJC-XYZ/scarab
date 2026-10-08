// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes

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
    def from(value: BigInt): Either[String, UInt64] =
      if value < 0 || value > Max then Left("outside uint64") else Right(new UInt64(value))

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
  enum DecodeResult[+A]:
    case NeedMore
    case Failed(error: String)
    case Decoded(value: A, consumed: Int)

  /** Bounds are local policy. 65535 is the pinned network smallByteLimit per message. */
  final case class Limits(
      maxMessageBytes: Int = 65535,
      maxDepth: Int = 24,
      maxItems: Int = 8192,
      maxStringBytes: Int = 65535,
      maxCandidates: Int = 64,
      maxHashBytes: Int = 64
  ):
    private[network] def valid: Boolean =
      maxMessageBytes > 0 && maxMessageBytes <= 65535 && maxDepth >= 0 && maxDepth <= 64 &&
        maxItems > 0 && maxItems <= 65535 && maxStringBytes >= 0 && maxStringBytes <= 65535 &&
        maxCandidates >= 0 && maxCandidates <= 1024 && maxHashBytes >= 0 && maxHashBytes <= 65535

  /** A payload adapter receives exactly one bounded syntactically valid CBOR item. */
  trait PayloadCodec[P]:
    def encode(value: P): Either[String, Bytes]
    def decode(bytes: Bytes): Either[String, P]

  def transition[P](state: State, sender: Role, message: Message[P]): Either[String, State] =
    import State.*
    import Role.*
    (state, sender, message) match
      case (Idle, Client, Message.RequestNext)        => Right(NextCanAwait)
      case (Idle, Client, Message.FindIntersect(_))   => Right(Intersect)
      case (Idle, Client, Message.Done)               => Right(Done)
      case (NextCanAwait, Server, Message.AwaitReply) => Right(NextMustReply)
      case (
            NextCanAwait | NextMustReply,
            Server,
            Message.RollForward(_, _) | Message.RollBackward(_, _)
          ) =>
        Right(Idle)
      case (Intersect, Server, Message.IntersectFound(_, _) | Message.IntersectNotFound(_)) =>
        Right(Idle)
      case _ => Left(s"illegal ChainSync message/agency in $state from $sender")

  private[network] type Failure = ProtocolWire.Failure
  private[network] val Failure = ProtocolWire.Failure
  private[network] def bad(message: String): Nothing = throw Failure(message)
  private[network] def get[A](value: Either[String, A]): A = value.fold(bad, identity)
  private def attempt[A](body: => A): Either[String, A] =
    try Right(body)
    catch case Failure(message, _) => Left(message)

  def encodePoint(point: Point, limits: Limits = Limits()): Either[String, Bytes] = attempt {
    val writer = new ChainSyncWire.Writer(limits)
    writer.point(point)
    val result = writer.result
    get(validateItem(result, limits))
    result
  }
  def encodeTip(tip: Tip, limits: Limits = Limits()): Either[String, Bytes] = attempt {
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
      body: ChainSyncWire.Reader => A
  ): DecodeResult[A] =
    try
      val reader = new ChainSyncWire.Reader(bytes, limits)
      val value = body(reader)
      get(validateItem(Bytes(bytes.value.take(reader.position)), limits))
      DecodeResult.Decoded(value, reader.position)
    catch
      case Failure(_, true)        => DecodeResult.NeedMore
      case Failure(message, false) => DecodeResult.Failed(message)

  def encode[P](
      state: State,
      sender: Role,
      message: Message[P],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[String, Bytes] = attempt {
    get(transition(state, sender, message))
    val w = new ChainSyncWire.Writer(limits)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    message match
      case Message.RequestNext => outer(1, 0)
      case Message.AwaitReply  => outer(1, 1)
      case Message.RollForward(p, tip) =>
        outer(3, 2)
        val raw = get(payload.encode(p))
        get(validateItem(raw, limits))
        w.raw(raw); w.tip(tip)
      case Message.RollBackward(point, tip) => outer(3, 3); w.point(point); w.tip(tip)
      case Message.FindIntersect(points) =>
        if points.size > limits.maxCandidates then bad("candidate limit exceeded")
        outer(2, 4)
        if points.isEmpty then w.head(4, 0)
        else { w.byte(159); points.foreach(w.point); w.byte(255) }
      case Message.IntersectFound(point, tip) => outer(3, 5); w.point(point); w.tip(tip)
      case Message.IntersectNotFound(tip)     => outer(2, 6); w.tip(tip)
      case Message.Done                       => outer(1, 7)
    val result = w.result
    // Enforce depth/item bounds on encoder output as well as decoder input.
    get(validateItem(result, limits))
    result
  }

  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): DecodeResult[Message[P]] = parse(bytes, limits) { r =>
    val arity = r.arrayLength()
    val tag = r.uint().value
    val skeleton: Message[Nothing] = tag.toInt match
      case 0 if tag == 0 && arity == 1 => Message.RequestNext
      case 1 if tag == 1 && arity == 1 => Message.AwaitReply
      case 2 if tag == 2 && arity == 3 => Message.RollBackward(Point.Origin, Tip.Origin)
      case 3 if tag == 3 && arity == 3 => Message.RollBackward(Point.Origin, Tip.Origin)
      case 4 if tag == 4 && arity == 2 => Message.FindIntersect(Vector.empty)
      case 5 if tag == 5 && arity == 3 => Message.IntersectFound(Point.Origin, Tip.Origin)
      case 6 if tag == 6 && arity == 2 => Message.IntersectNotFound(Tip.Origin)
      case 7 if tag == 7 && arity == 1 => Message.Done
      case _                           => bad("unknown tag or wrong exact outer arity")
    get(transition(state, sender, skeleton))
    val message: Message[P] = tag.toInt match
      case 2 => Message.RollForward(get(payload.decode(r.item())), r.tip())
      case 3 => Message.RollBackward(r.point(), r.tip())
      case 4 => Message.FindIntersect(r.points())
      case 5 => Message.IntersectFound(r.point(), r.tip())
      case 6 => Message.IntersectNotFound(r.tip())
      case _ => skeleton
    message
  }

  def validateItem(bytes: Bytes, limits: Limits = Limits()): Either[String, Unit] = attempt {
    val r = new ChainSyncWire.Reader(bytes, limits)
    r.skip(0)
    if r.position != bytes.size then bad("trailing payload bytes")
  }
