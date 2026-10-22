// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.crypto.bls.BlsError
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** What a field of a Leios message should have been. */
enum LeiosField:
  case Point, Slot, Hash, Word32, Word16, Word64, Header, Votes, EndorserBlock, Bitmaps,
    Transactions, Transaction

/** Closed set of codec failures of the Leios mini-protocols (18 and 19). */
enum LeiosWireError:
  /** The bytes so far are a proper prefix of a message; read more. */
  case Incomplete

  /** Not decodable CBOR within the local limits. */
  case Syntax(cause: ProtocolError)
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

  /** An endorser block that names no transaction: `{ + hash => word32 }` needs at least one. */
  case EmptyEndorserBlock

  /** A list or map of `actual` entries where the message admits at most `limit`. */
  case TooMany(tag: Int, field: LeiosField, limit: Int, actual: Int)

  /** Transaction `index` of `MsgLeiosBlockTxs` is not `#6.24(bytes)`. */
  case BadTransaction(index: Int)

  /** A bitmap of window `window` is zero: a window that names no transaction is not sent. */
  case ZeroBitmap(tag: Int, window: Int)

  /** Window `window` is at or above `limit`, the number of windows `maxTxsPerEb` transactions need.
    */
  case BitmapWindowOutOfRange(tag: Int, window: Int, limit: Int)

  /** Window `window` does not come after the window before it. */
  case BitmapWindowsNotAscending(tag: Int, window: Int)

  /** The answer carries `actual` transactions where the request's bitmaps name `expected`. */
  case TransactionCountMismatch(expected: Long, actual: Int)

/** A point on the Leios protocols: `[slot, eb_hash]`. */
final case class EbPoint(slot: Long, hash: EbHash)

/** The window bitmaps of a transaction request or answer: 64-transaction window index (16 bits) to
  * a 64-bit presence bitmap, in wire order.
  */
final case class TxBitmaps(windows: Vector[(Int, BigInt)]):

  /** The number of transactions the bitmaps name: the set bits of all windows. */
  def count: Long = windows.foldLeft(0L)((n, w) => n + w._2.bitCount)

private[protocol] object LeiosWire:
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
    ProtocolWire
      .attempt {
        val reader = new ProtocolWire.Reader(bytes, limits)
        reader.skip(0)
        reader.position
      }
      .left
      .map {
        case error if error.incomplete => LeiosWireError.Incomplete
        case cause                     => LeiosWireError.Syntax(cause)
      }

  /** The tag and the remaining fields (with their original bytes) of the first message in `bytes`.
    */
  def split(bytes: Bytes): Either[LeiosWireError, (Int, Vector[Node], Int)] =
    for
      n <- extent(bytes)
      node <- Cbor
        .decode(bytes.take(n))
        .left
        .map(e => LeiosWireError.Syntax(ProtocolError.Codec(e)))
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

  /** The checks of `decodeBitmaps` in ouroboros-consensus `MiniProtocol/LeiosFetch/Type.hs`
    * (revision `7b239a16`, lines 407 to 435), in its order: at most `MaxBitmapEntries` entries
    * (lines 410 to 413), then for each entry a 16-bit window and a 64-bit bitmap, a bitmap that is
    * not zero (427), a window below `MaxBitmapEntries` (430) and strictly above the window before
    * it (433).
    */
  def checkBitmaps(tag: Int, windows: Vector[(Int, BigInt)]): Either[LeiosWireError, Unit] =
    val limit = LeiosFetch.MaxBitmapEntries
    if windows.size > limit then
      Left(LeiosWireError.TooMany(tag, LeiosField.Bitmaps, limit, windows.size))
    else
      windows.zipWithIndex.foldLeft[Either[LeiosWireError, Unit]](Right(())) {
        case (acc, ((window, bits), i)) =>
          acc.flatMap { _ =>
            if bits.signum == 0 then Left(LeiosWireError.ZeroBitmap(tag, window))
            else if window >= limit then
              Left(LeiosWireError.BitmapWindowOutOfRange(tag, window, limit))
            else if i > 0 && window <= windows(i - 1)._1 then
              Left(LeiosWireError.BitmapWindowsNotAscending(tag, window))
            else Right(())
          }
      }

  def bitmaps(tag: Int, n: Node): Either[LeiosWireError, TxBitmaps] = n.value match
    case Value.Map(entries) =>
      for
        _ <- Either.cond(
          entries.size <= LeiosFetch.MaxBitmapEntries,
          (),
          LeiosWireError.TooMany(tag, LeiosField.Bitmaps, LeiosFetch.MaxBitmapEntries, entries.size)
        )
        windows <- all(entries) { case (k, v) =>
          for
            window <- uint(tag, k, BigInt(0xffff), LeiosField.Word16)
            bits <- uint(tag, v, (BigInt(1) << 64) - 1, LeiosField.Word64)
          yield window.toInt -> bits
        }
        _ <- checkBitmaps(tag, windows)
      yield TxBitmaps(windows)
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
    Cbor.decode(item).left.map(e => LeiosWireError.Syntax(ProtocolError.Codec(e)))

  /** The head of a definite-length array of `n` items. */
  private def arrayHead(n: Int): Bytes =
    def be(width: Int): Array[Byte] =
      Array.tabulate(width)(i => (n >>> (8 * (width - 1 - i))).toByte)
    if n < 24 then Bytes.fromArray(Array((0x80 | n).toByte))
    else if n < 256 then Bytes.fromArray(0x98.toByte +: be(1))
    else if n < 65536 then Bytes.fromArray(0x99.toByte +: be(2))
    else Bytes.fromArray(0x9a.toByte +: be(4))

  /** The array of the `head` items, encoded, followed by the complete CBOR items of `verbatim`,
    * written as they came: a transaction or a header that is hashed or signed is never re-encoded
    * (a non-shortest integer in it would change its bytes, and so its hash). Each of `verbatim`
    * must be exactly one item.
    */
  def arrayWithVerbatim(
      head: Vector[Value],
      verbatim: Vector[Bytes]
  ): Either[LeiosWireError, Bytes] =
    for
      heads <- all(head)(v => encode(v))
      _ <- all(verbatim)(embed)
    yield (heads ++ verbatim).foldLeft(arrayHead(head.size + verbatim.size))(_ ++ _)

  def tagged(tag: Int, rest: Value*): Value =
    Value.Arr((Value.UInt(BigInt(tag)) +: rest.toVector).map(nodeOf))
  def pointValue(p: EbPoint): Value =
    Value.Arr(Vector(nodeOf(Value.UInt(BigInt(p.slot))), nodeOf(Value.ByteString(p.hash.bytes))))

  /** The bitmaps as the reference writes them, an indefinite-length map (`encodeBitmaps`, `Type.hs`
    * of ouroboros-consensus `7b239a16`, lines 387 to 393; the Blueprint draws the same). Refused
    * unless they pass the checks the reference decoder applies.
    */
  def bitmapsBytes(b: TxBitmaps): Either[LeiosWireError, Bytes] =
    for
      _ <- checkBitmaps(-1, b.windows).left.map(_ => LeiosWireError.Unencodable)
      entries <- all(b.windows) { case (w, bits) =>
        for
          k <- encode(Value.UInt(BigInt(w)))
          v <- encode(Value.UInt(bits))
        yield k ++ v
      }
    yield entries.foldLeft(Bytes.fromArray(Array(0xbf.toByte)))(_ ++ _) ++ Bytes.fromArray(
      Array(0xff.toByte)
    )

  def encode(v: Value): Either[LeiosWireError, Bytes] =
    Cbor.encode(v).left.map(_ => LeiosWireError.Unencodable)
