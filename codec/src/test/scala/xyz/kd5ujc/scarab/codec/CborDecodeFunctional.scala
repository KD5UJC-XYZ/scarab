// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import scala.util.Try

import Cbor.{Error, Limits}

/** The functional form of [[Cbor]]'s decoder, measured against the imperative one (issue 650,
  * `docs/performance.md` §48; §40 for the method): the same checks in the same order, as pure
  * functions of the position and the item count, the open containers on a `List`, the index as two
  * persistent vectors (a container's end is set when it closes) and the walk a `@tailrec` loop.
  * `CborDecodeEquivalenceSuite` compares the two; `CborDecodeBench` times them.
  */
object CborDecodeFunctional:
  private type Read[A] = Either[Error, A]

  /** The index of a decode: where each item starts and ends, by item number, and where the first
    * item ends.
    */
  final case class Decoded(starts: Vector[Int], ends: Vector[Int], end: Int)

  /** An open container: its item number, its major type, the children still to come (-1 for an
    * indefinite container, which ends at a break) and the children so far.
    */
  private final case class Open(item: Int, major: Int, left: Long, count: Int)

  def decode(bytes: Bytes, limits: Limits = Limits(), prefix: Boolean = false): Read[Decoded] =
    if limits.maxInputBytes < 0 || limits.maxDepth < 0 || limits.maxItems < 0 || limits.maxStringBytes < 0
    then Left(Error.InvalidLimits)
    else if bytes.size > limits.maxInputBytes then Left(Error.InputLimitExceeded)
    else new Walk(bytes, limits).run(prefix)

  private final class Walk(input: Bytes, limits: Limits):
    private val data = input.unsafeArray
    private val base = input.unsafeOffset
    private val end = base + input.size

    private def byte(pos: Int): Read[Int] =
      if pos >= end then Left(Error.UnexpectedEnd) else Right(data(pos) & 0xff)

    private def isBreak(pos: Int): Boolean = pos < end && (data(pos) & 0xff) == 255

    /** `count` bytes from `pos` as a big-endian number, and the position after them. */
    private def bigEndian(pos: Int, count: Int): Read[(Long, Int)] =
      @tailrec def go(at: Int, n: Long): Read[(Long, Int)] =
        if at == pos + count then Right((n, at))
        else
          byte(at) match
            case Left(error) => Left(error)
            case Right(b)    => go(at + 1, (n << 8) | b.toLong)
      go(pos, 0L)

    /** The argument of a head with additional information `ai`, read from `pos`. */
    private def argument(ai: Int, pos: Int): Read[(Long, Int)] =
      if ai < 24 then Right((ai.toLong, pos))
      else
        ai match
          case 24 => bigEndian(pos, 1)
          case 25 => bigEndian(pos, 2)
          case 26 => bigEndian(pos, 4)
          case 27 => bigEndian(pos, 8)
          case _  => Left(Error.InvalidAdditionalInformation(ai))

    private def bounded(n: Long, bound: Int, what: Error): Read[Int] =
      if n < 0 || n > bound.toLong then Left(what) else Right(n.toInt)

    private def payload(size: Int, pos: Int): Read[Int] =
      if size > end - pos then Left(Error.TruncatedString) else Right(pos + size)

    private def utf8(from: Int, size: Int): Read[Unit] =
      val until = from + size
      if (from until until).forall(data(_) >= 0) then Right(())
      else
        Try(
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, from, size))
        ).toEither.left.map(_ => Error.InvalidUtf8).map(_ => ())

    private def count(items: Int): Read[Int] =
      if items >= limits.maxItems then Left(Error.ItemLimitExceeded) else Right(items + 1)

    /** A string item whose head, with additional information `ai`, ends at `pos`: where it ends and
      * the items counted.
      */
    private def string(major: Int, ai: Int, pos: Int, items: Int): Read[(Int, Int)] =
      if ai != 31 then
        for
          (n, at) <- argument(ai, pos)
          size <- bounded(n, limits.maxStringBytes, Error.StringLimitExceeded)
          next <- payload(size, at)
          _ <- if major == 3 then utf8(at, size) else Right(())
        yield (next, items)
      else
        @tailrec def chunks(pos: Int, items: Int, total: Int): Read[(Int, Int)] =
          if isBreak(pos) then Right((pos + 1, items))
          else
            val chunk = for
              counted <- count(items)
              h <- byte(pos)
              _ <-
                if h / 32 != major || (h & 31) == 31 then Left(Error.InvalidStringChunk)
                else Right(())
              (n, at) <- argument(h & 31, pos + 1)
              length <- bounded(n, limits.maxStringBytes - total, Error.StringLimitExceeded)
              next <- payload(length, at)
              _ <- if major == 3 then utf8(at, length) else Right(())
            yield (next, counted, length)
            chunk match
              case Left(error)                  => Left(error)
              case Right((next, counted, size)) => chunks(next, counted, total + size)
        chunks(pos, items, 0)

    /** A simple value or float with additional information `ai`; where it ends. */
    private def simple(ai: Int, pos: Int): Read[Int] = ai match
      case 20 | 21 | 22                                         => Right(pos)
      case n if limits.allowSimpleValues && (n < 20 || n == 23) => Right(pos)
      case 24 if limits.allowSimpleValues =>
        bigEndian(pos, 1).flatMap((value, next) =>
          if value < 32 then Left(Error.InvalidSimpleValue(value.toInt)) else Right(next)
        )
      case 25 if limits.allowFloats => bigEndian(pos, 2).map(_._2)
      case 26 if limits.allowFloats => bigEndian(pos, 4).map(_._2)
      case 27 if limits.allowFloats => bigEndian(pos, 8).map(_._2)
      case _                        => Left(Error.UnsupportedSimpleValue(ai))

    /** What one item's head opens: a complete leaf ending at the position, or a container. */
    private enum Step:
      case Leaf(next: Int, items: Int)
      case Container(open: Open, next: Int, items: Int)

    private def item(pos: Int, items: Int, number: Int): Read[Step] =
      byte(pos).flatMap { h =>
        val major = h / 32
        val ai = h & 31
        val at = pos + 1
        major match
          case 0 | 1 => argument(ai, at).map((_, next) => Step.Leaf(next, items))
          case 2 | 3 => string(major, ai, at, items).map((next, n) => Step.Leaf(next, n))
          case 4 =>
            if ai == 31 then Right(Step.Container(Open(number, 4, -1L, 0), at, items))
            else
              for
                (n, next) <- argument(ai, at)
                left <- bounded(n, limits.maxItems - items, Error.ItemLimitExceeded)
              yield Step.Container(Open(number, 4, left.toLong, 0), next, items)
          case 5 =>
            if ai == 31 then Right(Step.Container(Open(number, 5, -1L, 0), at, items))
            else
              for
                (n, next) <- argument(ai, at)
                pairs <- bounded(n, (limits.maxItems - items) / 2, Error.MapPairLimitExceeded)
              yield Step.Container(Open(number, 5, 2L * pairs, 0), next, items)
          case 6 =>
            argument(ai, at).map((_, next) => Step.Container(Open(number, 6, 1L, 0), next, items))
          case _ => simple(ai, at).map(next => Step.Leaf(next, items))
      }

    /** The parent of a completed item counts it as a child. */
    private def counted(stack: List[Open]): List[Open] = stack match
      case parent :: rest => parent.copy(count = parent.count + 1) :: rest
      case Nil            => Nil

    def run(prefix: Boolean): Read[Decoded] =
      // `stack`: the open containers, innermost first, `depth` of them
      @tailrec def walk(
          pos: Int,
          items: Int,
          stack: List[Open],
          depth: Int,
          starts: Vector[Int],
          ends: Vector[Int]
      ): Read[(Int, Vector[Int], Vector[Int])] =
        stack match
          case top :: below if top.left == 0 =>
            val ended = ends.updated(top.item, pos)
            if depth == 1 then Right((pos, starts, ended))
            else walk(pos, items, counted(below), depth - 1, starts, ended)
          case top :: below if top.left < 0 && isBreak(pos) =>
            if top.major == 5 && top.count % 2 == 1 then Left(Error.UnmatchedMapKey)
            else
              val ended = ends.updated(top.item, pos + 1)
              if depth == 1 then Right((pos + 1, starts, ended))
              else walk(pos + 1, items, counted(below), depth - 1, starts, ended)
          case _ =>
            val parent = stack match
              case top :: below if top.left > 0 => top.copy(left = top.left - 1) :: below
              case _                            => stack
            if depth > limits.maxDepth then Left(Error.DepthLimitExceeded)
            else
              count(items).flatMap(n => item(pos, n, starts.size).map((n, _))) match
                case Left(error) => Left(error)
                case Right((_, Step.Leaf(next, n))) =>
                  if depth == 0 then Right((next, starts :+ pos, ends :+ next))
                  else walk(next, n, counted(parent), depth, starts :+ pos, ends :+ next)
                case Right((_, Step.Container(open, next, n))) =>
                  walk(next, n, open :: parent, depth + 1, starts :+ pos, ends :+ 0)
      walk(base, 0, Nil, 0, Vector.empty, Vector.empty).flatMap { (pos, starts, ends) =>
        if !prefix && pos != end then Left(Error.TrailingBytes)
        else Right(Decoded(starts, ends, pos))
      }
