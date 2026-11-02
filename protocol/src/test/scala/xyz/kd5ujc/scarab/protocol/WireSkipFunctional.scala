// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

import ProtocolWire.Limits

/** The functional form of [[ProtocolWire.Reader]]'s `skip` and `item`, measured against the
  * imperative one (issue 657, `docs/performance.md` §40 for the method): the same checks in the
  * same order, as pure functions of the position and the item count, the open containers on a
  * `List` and the walk a `@tailrec` loop. `WireSkipEquivalenceSuite` compares the two;
  * `WireSkipBench` times them.
  */
object WireSkipFunctional:
  private type Read[A] = Either[ProtocolError, A]

  /** Where the walk is: the next byte and the items counted so far. */
  final case class At(pos: Int, items: Int)

  private enum Opened:
    case Scalar
    case Container(children: Int)

  private val IndefiniteArray = -1
  private val IndefiniteKey = -2
  private val IndefiniteValue = -3

  /** One complete item from the front of `input`: its bytes and where it ends. */
  def item(input: Bytes, limits: Limits): Read[(Bytes, At)] =
    if !limits.valid then Left(ProtocolError.InvalidLocalLimits)
    else skip(input, limits, At(0, 0), 0).map(end => (input.slice(0, end.pos).compact, end))

  private def need(input: Bytes, limits: Limits, pos: Int, n: Int): Read[Unit] =
    if n < 0 || n > limits.maxMessageBytes - pos then Left(ProtocolError.MessageByteLimitExceeded)
    else if n > input.size - pos then Left(ProtocolError.TruncatedCbor)
    else Right(())

  private def peek(input: Bytes, limits: Limits, pos: Int): Read[Int] =
    // `map`, not `as`: the byte is read only once `need` has admitted it
    need(input, limits, pos, 1).flatMap(_ =>
      input.lift(pos).toRight(ProtocolError.TruncatedCbor).map(_ & 255)
    )

  /** The argument of a head whose additional information is `ai`, read from `pos`, and the position
    * after it.
    */
  private def argument(input: Bytes, limits: Limits, ai: Int, pos: Int): Read[(BigInt, Int)] =
    if ai < 24 then Right((BigInt(ai), pos))
    else
      val width = ai match
        case 24 => Right(1)
        case 25 => Right(2)
        case 26 => Right(4)
        case 27 => Right(8)
        case _  => Left(ProtocolError.ReservedOrIndefiniteArgument)
      width.flatMap { n =>
        need(input, limits, pos, n).flatMap(_ =>
          input
            .bigEndian(pos, n)
            .toRight(ProtocolError.TruncatedCbor)
            .map(v => (BigInt(v) + (if v < 0 then BigInt(1) << 64 else 0), pos + n))
        )
      }

  private def bounded(value: BigInt, max: Int, what: ProtocolError): Read[Int] =
    if value > max then Left(what) else Right(value.toInt)

  private def count(limits: Limits, items: Int): Read[Int] =
    if items >= limits.maxItems then Left(ProtocolError.CBORItemLimitExceeded) else Right(items + 1)

  /** A definite string chunk's length, checked, and the position after it. */
  private def stringChunk(
      input: Bytes,
      limits: Limits,
      major: Int,
      ai: Int,
      remaining: Int,
      pos: Int
  ): Read[(Int, Int)] =
    for
      (value, at) <- argument(input, limits, ai, pos)
      n <- bounded(value, remaining, ProtocolError.StringLimitExceeded)
      _ <- need(input, limits, at, n)
      _ <-
        if major != 3 then Right(())
        else
          Either
            .catchNonFatal(
              StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.slice(at, at + n).toArray))
            )
            .leftMap(_ => ProtocolError.InvalidUTF8)
    yield (n, at + n)

  /** The chunks of an indefinite string, up to and including its break. */
  @tailrec
  private def chunks(input: Bytes, limits: Limits, major: Int, total: Int, at: At): Read[At] =
    peek(input, limits, at.pos) match
      case Left(error) => Left(error)
      case Right(255)  => Right(At(at.pos + 1, at.items))
      case Right(head) =>
        val chunk = for
          items <- count(limits, at.items)
          _ <-
            if head / 32 != major || (head & 31) == 31 then Left(ProtocolError.InvalidStringChunk)
            else Right(())
          (n, pos) <- stringChunk(
            input,
            limits,
            major,
            head & 31,
            limits.maxStringBytes - total,
            at.pos + 1
          )
        yield (n, At(pos, items))
        chunk match
          case Left(error)      => Left(error)
          case Right((n, next)) => chunks(input, limits, major, total + n, next)

  /** One item's head and its scalar payload; a container or tag is left open. */
  private def opened(input: Bytes, limits: Limits, at: At): Read[(Opened, At)] =
    for
      items <- count(limits, at.items)
      h <- peek(input, limits, at.pos)
      major = h / 32
      ai = h & 31
      pos = at.pos + 1
      result <- major match
        case 0 | 1 => argument(input, limits, ai, pos).map((_, p) => (Opened.Scalar, At(p, items)))
        case 2 | 3 =>
          if ai != 31 then
            stringChunk(input, limits, major, ai, limits.maxStringBytes, pos)
              .map((_, p) => (Opened.Scalar, At(p, items)))
          else chunks(input, limits, major, 0, At(pos, items)).map(next => (Opened.Scalar, next))
        case 4 | 5 =>
          val multiplier = if major == 5 then 2 else 1
          if ai == 31 then
            Right(
              (
                Opened.Container(if major == 5 then IndefiniteKey else IndefiniteArray),
                At(pos, items)
              )
            )
          else
            for
              (value, p) <- argument(input, limits, ai, pos)
              n <- bounded(
                value,
                (limits.maxItems - items) / multiplier,
                ProtocolError.ItemLimitExceeded
              )
            yield (Opened.Container(n * multiplier), At(p, items))
        case 6 =>
          argument(input, limits, ai, pos).map((_, p) => (Opened.Container(1), At(p, items)))
        case _ =>
          ai match
            case 31 | 28 | 29 | 30 => Left(ProtocolError.UnexpectedBreakOrReservedSimpleValue)
            case 24 =>
              peek(input, limits, pos).flatMap(b =>
                if b < 32 then Left(ProtocolError.InvalidExtendedSimpleValue)
                else Right((Opened.Scalar, At(pos + 1, items)))
              )
            case 25 | 26 | 27 =>
              argument(input, limits, ai, pos).map((_, p) => (Opened.Scalar, At(p, items)))
            case _ => Right((Opened.Scalar, At(pos, items)))
    yield result

  /** Skip one item from `at`. `pending` holds, for each open container, the children its parent
    * still waits for (`open` of them); `remaining` is that count for the innermost one: a number of
    * children, or an indefinite array, key or value.
    */
  def skip(input: Bytes, limits: Limits, at: At, depth: Int): Read[At] =
    @tailrec
    def walk(at: At, remaining: Int, pending: List[Int], open: Int): Read[At] =
      if remaining == 0 then
        pending match
          case Nil            => Right(at)
          case parent :: rest => walk(at, parent, rest, open - 1)
      else
        val indefinite = remaining == IndefiniteArray || remaining == IndefiniteKey
        val ends =
          if indefinite then peek(input, limits, at.pos).map(_ == 255) else Right(false)
        ends match
          case Left(error) => Left(error)
          case Right(true) => walk(At(at.pos + 1, at.items), 0, pending, open)
          case Right(false) =>
            val left =
              if remaining > 0 then remaining - 1
              else if remaining == IndefiniteKey then IndefiniteValue
              else if remaining == IndefiniteValue then IndefiniteKey
              else remaining
            if depth + open > limits.maxDepth then Left(ProtocolError.CBORDepthLimitExceeded)
            else
              opened(input, limits, at) match
                case Left(error)                        => Left(error)
                case Right((Opened.Scalar, next))       => walk(next, left, pending, open)
                case Right((Opened.Container(c), next)) => walk(next, c, left :: pending, open + 1)
    walk(at, 1, Nil, 0)
