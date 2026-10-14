// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.bls.BlsError
import lab.cbor.{Bytes, Cbor, Node, Value}

/** What a field of a Leios message should have been. */
enum LeiosField:
  case Point, Slot, Hash, Word32, Word16, Word64, Header, Votes, EndorserBlock, Bitmaps,
    Transactions

/** Closed set of codec failures of the Leios mini-protocols (18 and 19). */
enum LeiosWireError:
  /** The bytes so far are a proper prefix of a message; read more. */
  case Incomplete

  /** Not decodable CBOR within the local limits. */
  case Syntax
  case NotAnArray
  case EmptyMessage
  case UnknownTag(tag: BigInt)

  /** A known tag that the protocol state does not admit (wrong direction or state). */
  case NotAdmitted(tag: Int)
  case WrongArity(tag: Int, expected: Int, actual: Int)
  case BadField(tag: Int, field: LeiosField)

  /** A vote inside `MsgLeiosVotes` failed strict decoding. */
  case BadVote(index: Int, error: BlsError)

  /** A message that cannot be encoded within the encoder's bounds. */
  case Unencodable

/** A point on the Leios protocols: `[slot, eb_hash]`. */
final case class EbPoint(slot: Long, hash: EbHash)

/** The window bitmaps of a transaction request or answer: 64-transaction window index (16 bits) to
  * a 64-bit presence bitmap, in wire order.
  */
final case class TxBitmaps(windows: Vector[(Int, BigInt)])

private[network] object LeiosWire:
  /** Local policy: the CIP bounds an endorser block by 600 kB, a transaction list by the same
    * order.
    */
  val limits: ProtocolWire.Limits =
    ProtocolWire.Limits(
      maxMessageBytes = 2500000,
      maxStringBytes = 2500000,
      maxHashBytes = 64,
      maxDepth = 12,
      maxItems = 65535
    )

  /** The extent of the first complete CBOR item of `bytes`, if any. */
  def extent(bytes: Bytes): Either[LeiosWireError, Int] =
    try
      val reader = new ProtocolWire.Reader(bytes, limits)
      reader.skip(0)
      Right(reader.position)
    catch
      case ProtocolWire.Failure(_, true)  => Left(LeiosWireError.Incomplete)
      case ProtocolWire.Failure(_, false) => Left(LeiosWireError.Syntax)

  /** The tag and the remaining fields (with their original bytes) of the first message in `bytes`.
    */
  def split(bytes: Bytes): Either[LeiosWireError, (Int, Vector[Node], Int)] =
    for
      n <- extent(bytes)
      node <- Cbor.decode(bytes.take(n)).left.map(_ => LeiosWireError.Syntax)
      parts <- node.value match
        case Value.Arr(xs) => Right(xs)
        case _             => Left(LeiosWireError.NotAnArray)
      head <- parts.headOption.toRight(LeiosWireError.EmptyMessage)
      tag <- head.value match
        case Value.UInt(t) if t <= 1000 => Right(t.toInt)
        case Value.UInt(t)              => Left(LeiosWireError.UnknownTag(t))
        case _                          => Left(LeiosWireError.BadField(-1, LeiosField.Word16))
    yield (tag, parts.tail, n)

  def arity(tag: Int, fields: Vector[Node], expected: Int): Either[LeiosWireError, Unit] =
    Either.cond(
      fields.size == expected,
      (),
      LeiosWireError.WrongArity(tag, expected, fields.size)
    )

  def hash(tag: Int, n: Node): Either[LeiosWireError, EbHash] = n.value match
    case Value.ByteString(b) =>
      EbHash.fromBytes(b).toRight(LeiosWireError.BadField(tag, LeiosField.Hash))
    case _ => Left(LeiosWireError.BadField(tag, LeiosField.Hash))

  def uint(tag: Int, n: Node, max: BigInt, field: LeiosField): Either[LeiosWireError, BigInt] =
    n.value match
      case Value.UInt(v) if v <= max => Right(v)
      case _                         => Left(LeiosWireError.BadField(tag, field))

  def point(tag: Int, n: Node): Either[LeiosWireError, EbPoint] = n.value match
    case Value.Arr(Vector(s, h)) =>
      for
        slot <- uint(tag, s, BigInt(Long.MaxValue), LeiosField.Slot)
        hash <- hash(tag, h)
      yield EbPoint(slot.toLong, hash)
    case _ => Left(LeiosWireError.BadField(tag, LeiosField.Point))

  def bitmaps(tag: Int, n: Node): Either[LeiosWireError, TxBitmaps] = n.value match
    case Value.Map(entries) =>
      entries
        .foldLeft[Either[LeiosWireError, Vector[(Int, BigInt)]]](Right(Vector.empty)) {
          case (acc, (k, v)) =>
            for
              xs <- acc
              window <- uint(tag, k, BigInt(0xffff), LeiosField.Word16)
              bits <- uint(tag, v, (BigInt(1) << 64) - 1, LeiosField.Word64)
            yield xs :+ (window.toInt -> bits)
        }
        .map(TxBitmaps(_))
    case _ => Left(LeiosWireError.BadField(tag, LeiosField.Bitmaps))

  // --- encoding (requests are small and bounded) ---
  def nodeOf(v: Value): Node = Node(v, Bytes.empty)

  def all[A, B](
      xs: Vector[A]
  )(f: A => Either[LeiosWireError, B]): Either[LeiosWireError, Vector[B]] =
    xs.foldLeft[Either[LeiosWireError, Vector[B]]](Right(Vector.empty))((acc, x) =>
      acc.flatMap(bs => f(x).map(bs :+ _))
    )

  /** A complete CBOR item, as a node to embed in a message. */
  def embed(item: Bytes): Either[LeiosWireError, Node] =
    Cbor.decode(item).left.map(_ => LeiosWireError.Syntax)
  def tagged(tag: Int, rest: Value*): Value =
    Value.Arr((Value.UInt(BigInt(tag)) +: rest.toVector).map(nodeOf))
  def pointValue(p: EbPoint): Value =
    Value.Arr(Vector(nodeOf(Value.UInt(BigInt(p.slot))), nodeOf(Value.ByteString(p.hash.bytes))))
  def bitmapsValue(b: TxBitmaps): Value =
    Value.Map(b.windows.map { case (w, bits) =>
      (nodeOf(Value.UInt(BigInt(w))), nodeOf(Value.UInt(bits)))
    })
  def encode(v: Value): Either[LeiosWireError, Bytes] =
    Cbor.encode(v).left.map(_ => LeiosWireError.Unencodable)
