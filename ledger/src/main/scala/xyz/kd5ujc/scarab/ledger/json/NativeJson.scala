// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import io.circe.Json
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}

/** Strict byte-to-Value boundary of aeson 2.2.5.0 (`Decoding/ByteString.hs:33-119`,
  * `Decoding/Conversion.hs:56-94`, `Decoding.hs:40-60`). Every object value is parsed before
  * duplicate collapse; the first original key wins. This is independent of genesis field decoding.
  */
object NativeJson:
  enum Expected:
    case Value, ObjectKey, Colon, ArraySeparator, ObjectSeparator, Digit, StringEnd

  enum Error extends DomainError:
    case End(offset: Int, expected: Expected)
    case Unexpected(offset: Int, expected: Expected, byte: Int)
    case TrailingInput(offset: Int)
    case LeadingZero(offset: Int)
    case StringControl(offset: Int, byte: Int)
    case StringEncoding(offset: Int)
    case StringEscape(offset: Int)
    case StringSurrogate(offset: Int)
    case NumberEncoding(offset: Int)
    case InvalidFrame

  private enum Frame:
    case ArrayItems(values: List[Json])
    case ObjectFields(values: List[(String, Json)], key: Option[String])

  private enum Mode:
    case Value, ArrayAfter, ObjectAfter, Colon
    case ArrayFirst
    case ObjectKey(mayEnd: Boolean)

  private final case class State(at: Int, mode: Mode, frames: List[Frame])
  private enum Step:
    case Continue(state: State)
    case Done(value: Json)

  private[json] def byte(input: Bytes, at: Int): Int = input(at) & 255

  @tailrec private def space(input: Bytes, at: Int): Int =
    if at == input.size then at
    else
      val c = byte(input, at)
      if c == 0x20 || c == 0x0a || c == 0x0d || c == 0x09 then space(input, at + 1)
      else at

  private def expected(input: Bytes, at: Int, what: Expected): Error =
    if at == input.size then Error.End(at, what)
    else Error.Unexpected(at, what, byte(input, at))

  private def finish(input: Bytes, at: Int, value: Json, frames: List[Frame]): Either[Error, Step] =
    frames match
      case Nil =>
        val end = space(input, at)
        Either.cond(end == input.size, Step.Done(value), Error.TrailingInput(end))
      case Frame.ArrayItems(items) :: rest =>
        Right(Step.Continue(State(at, Mode.ArrayAfter, Frame.ArrayItems(value :: items) :: rest)))
      case Frame.ObjectFields(fields, Some(key)) :: rest =>
        Right(
          Step.Continue(
            State(at, Mode.ObjectAfter, Frame.ObjectFields((key -> value) :: fields, None) :: rest)
          )
        )
      case _ => Left(Error.InvalidFrame)

  private def literal(
      input: Bytes,
      at: Int,
      text: String,
      value: Json,
      frames: List[Frame]
  ): Either[Error, Step] =
    if input.size - at >= text.length && text.indices.forall(i =>
        byte(input, at + i) == text.charAt(i)
      )
    then finish(input, at + text.length, value, frames)
    else Left(expected(input, at, Expected.Value))

  private def value(input: Bytes, s: State): Either[Error, Step] =
    if s.at == input.size then Left(Error.End(s.at, Expected.Value))
    else
      byte(input, s.at) match
        case 123 =>
          Right(
            Step.Continue(
              State(s.at + 1, Mode.ObjectKey(true), Frame.ObjectFields(Nil, None) :: s.frames)
            )
          )
        case 91 =>
          Right(Step.Continue(State(s.at + 1, Mode.ArrayFirst, Frame.ArrayItems(Nil) :: s.frames)))
        case 34 =>
          NativeJsonString.read(input, s.at).flatMap { (text, end) =>
            finish(input, end, Json.fromString(text), s.frames)
          }
        case 110 => literal(input, s.at, "null", Json.Null, s.frames)
        case 116 => literal(input, s.at, "true", Json.True, s.frames)
        case 102 => literal(input, s.at, "false", Json.False, s.frames)
        case c if c == 45 || c >= 48 && c <= 57 =>
          NativeJsonNumber.read(input, s.at).flatMap { (number, end) =>
            finish(input, end, number, s.frames)
          }
        case _ => Left(expected(input, s.at, Expected.Value))

  private def closeArray(input: Bytes, s: State): Either[Error, Step] = s.frames match
    case Frame.ArrayItems(items) :: rest =>
      finish(input, s.at + 1, Json.fromValues(items.reverse), rest)
    case _ => Left(Error.InvalidFrame)

  private def closeObject(input: Bytes, s: State): Either[Error, Step] = s.frames match
    case Frame.ObjectFields(fields, None) :: rest =>
      // Aeson's ordered KeyMap takes the last supplied pair, then traverses Text keys in UTF-8 order.
      // These pairs are reversed, so the first original key wins. JVM UTF-16 order is different.
      val ordered = fields.toMap.toVector
        .map { (key, value) =>
          (Bytes.wrap(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)), key, value)
        }
        .sortBy(_._1)
        .map { (_, key, value) => (key, value) }
      finish(input, s.at + 1, Json.fromFields(ordered), rest)
    case _ => Left(Error.InvalidFrame)

  private def advance(input: Bytes, original: State): Either[Error, Step] =
    val s = original.copy(at = space(input, original.at))
    def is(c: Int): Boolean = s.at < input.size && byte(input, s.at) == c
    def next(mode: Mode): Either[Error, Step] = Right(
      Step.Continue(s.copy(at = s.at + 1, mode = mode))
    )
    s.mode match
      case Mode.Value      => value(input, s)
      case Mode.ArrayFirst => if is(93) then closeArray(input, s) else value(input, s)
      case Mode.ArrayAfter =>
        if is(93) then closeArray(input, s)
        else if is(44) then next(Mode.Value)
        else Left(expected(input, s.at, Expected.ArraySeparator))
      case Mode.ObjectKey(mayEnd) =>
        if is(125) && mayEnd then closeObject(input, s)
        else if is(34) then
          NativeJsonString.read(input, s.at).flatMap { (key, end) =>
            s.frames match
              case Frame.ObjectFields(fields, None) :: rest =>
                Right(
                  Step
                    .Continue(State(end, Mode.Colon, Frame.ObjectFields(fields, Some(key)) :: rest))
                )
              case _ => Left(Error.InvalidFrame)
          }
        else Left(expected(input, s.at, Expected.ObjectKey))
      case Mode.Colon =>
        if is(58) then next(Mode.Value) else Left(expected(input, s.at, Expected.Colon))
      case Mode.ObjectAfter =>
        if is(125) then closeObject(input, s)
        else if is(44) then next(Mode.ObjectKey(false))
        else Left(expected(input, s.at, Expected.ObjectSeparator))

  def parse(input: Bytes): Either[Error, Json] =
    @tailrec def loop(s: State): Either[Error, Json] = advance(input, s) match
      case Left(error)                => Left(error)
      case Right(Step.Done(value))    => Right(value)
      case Right(Step.Continue(next)) => loop(next)
    loop(State(0, Mode.Value, Nil))
