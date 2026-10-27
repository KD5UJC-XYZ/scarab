// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}

/** Strict byte-to-Value boundary of aeson 2.2.5.0: `bsToTokens` (`Decoding/ByteString.hs:32-126`)
  * read into a Value as `toResultValue` does (`Decoding/Conversion.hs:48-94`), then
  * `eitherDecodeStrict` 's check for trailing input (`Decoding.hs:48-53`). Every object value is
  * parsed before duplicate collapse; the first original key wins. This is independent of genesis
  * field decoding.
  */
object NativeJson:
  /** What a refused byte or end of input was expected to be, as the reference's messages name it.
    */
  enum Expected(val text: String):
    case Value extends Expected("JSON value")
    case ValueOrArrayEnd extends Expected("JSON value or ]")
    case ArraySeparator extends Expected(", or ]")
    case KeyOrObjectEnd extends Expected("record key literal or }")
    case ObjectSeparator extends Expected(", or }")
    case KeyLiteral extends Expected("key literal")
    case Colon extends Expected(":")

  /** The failures of `bsToTokens`; `message` is the reference's text. */
  enum Error extends DomainError:
    /** `tkErrEOF` (`Decoding/ByteString.hs:118-120`) and its spelled-out copies (36, 84). */
    case EndOfInput(expected: Expected)

    /** `tkErrBS` (`Decoding/ByteString.hs:123-125`) and its spelled-out copies (57, 90): the
      * `showBeginning` of at most 30 bytes (`110-111`). After a comma in an object, those bytes
      * start at the comma (`85-88`).
      */
    case Unexpected(beginning: Bytes, expected: Expected)

    /** `scanStringLiteral`'s `errEnd` (`Decoding/ByteString.hs:165`). */
    case StringEnd

    /** `scanStringLiteral` 's `errCC` (`Decoding/ByteString.hs:166`), spelled as the reference
      * does.
      */
    case StringControl

    /** `unescapeText` 's only failure (`Internal/Unescape.hs:36-39`), shown as text 2.1.3's
      * `UnicodeException` (`Data/Text/Encoding/Error.hs:83-84`).
      */
    case InvalidUtf8Stream

    /** `scanNumberLiteral`'s `errEnd` (`Decoding/ByteString.hs:272`). */
    case NumberEnd

    /** A byte that cannot start the integer or fraction part, shown as a `Word8`
      * (`Decoding/ByteString.hs:194,221`).
      */
    case NumberByte(byte: Int)

    /** A byte that cannot continue an exponent, shown as a `Char` (`errUnx`,
      * `Decoding/ByteString.hs:273`).
      */
    case NumberChar(byte: Int)

    /** `Decoding/ByteString.hs:200`. */
    case LeadingZero

    def message: String = this match
      case EndOfInput(expected) => s"Unexpected end-of-input, expecting ${expected.text}"
      case Unexpected(beginning, expected) =>
        s"Unexpected ${HaskellShow.bytes(beginning)}, expecting ${expected.text}"
      case StringEnd     => "Unexpected end-of-input while parsing string literal"
      case StringControl => "Unespected control character while parsing string literal"
      case InvalidUtf8Stream =>
        "Cannot decode input: Data.Text.Internal.Encoding.decodeUtf8: Invalid UTF-8 stream"
      case NumberEnd        => "Unexpected end-of-input while parsing number literal"
      case NumberByte(byte) => s"Unexpected $byte while parsing number literal"
      case NumberChar(byte) => s"Unexpected ${HaskellShow.char(byte)} while parsing number literal"
      case LeadingZero      => "Number literal with leading zero"

  /** The failures of `eitherDecodeStrict` at `Value` (`Decoding.hs:48-53`). */
  enum DecodeError extends DomainError:
    case Syntax(error: Error)

    /** Input other than JSON whitespace after the value (`Decoding.hs:52`). */
    case TrailingGarbage

    def message: String = this match
      case Syntax(error)   => error.message
      case TrailingGarbage => "Trailing garbage"

  /** A value and the offset of the first byte after it, the leftover that `toResultValue` hands to
    * its continuation.
    */
  final case class Parsed(value: JsonValue, end: Int)

  // A container whose next value is being read.
  private enum Frame:
    case ArrayItems(items: List[JsonValue])
    case ObjectField(fields: List[(JsonKey, JsonValue)], key: JsonKey)

  // The reference's continuations: `goT`, `goA`, `goA1`, `goR`, `goR1` and `goRK'`.
  private enum Mode:
    case Value
    case ArrayFirst
    case ArrayAfter(items: List[JsonValue])
    case ObjectFirst
    case ObjectAfter(fields: List[(JsonKey, JsonValue)])
    case Colon(fields: List[(JsonKey, JsonValue)], key: JsonKey)

  private final case class State(at: Int, mode: Mode, frames: List[Frame])
  private enum Step:
    case Continue(state: State)
    case Done(parsed: Parsed)

  private[json] def byte(input: Bytes, at: Int): Int = input(at) & 255

  // `skipSpace` (`Decoding/ByteString.hs:114-115`).
  @tailrec private def space(input: Bytes, at: Int): Int =
    if at < input.size && isSpace(byte(input, at)) then space(input, at + 1) else at

  private def isSpace(c: Int): Boolean = c == 0x20 || c == 0x0a || c == 0x0d || c == 0x09

  private def unexpected(input: Bytes, at: Int, what: Expected): Error =
    Error.Unexpected(input.slice(at, (at + 30).min(input.size)).compact, what)

  private def refuse(input: Bytes, at: Int, what: Expected): Either[Error, Step] =
    Left(if at == input.size then Error.EndOfInput(what) else unexpected(input, at, what))

  // The continuation of a finished value: the enclosing container's next mode, or the end.
  private def finish(at: Int, value: JsonValue, frames: List[Frame]): Either[Error, Step] =
    frames match
      case Nil => Right(Step.Done(Parsed(value, at)))
      case Frame.ArrayItems(items) :: rest =>
        Right(Step.Continue(State(at, Mode.ArrayAfter(value :: items), rest)))
      case Frame.ObjectField(fields, key) :: rest =>
        Right(Step.Continue(State(at, Mode.ObjectAfter((key -> value) :: fields), rest)))

  private def literal(
      input: Bytes,
      at: Int,
      text: String,
      value: JsonValue,
      frames: List[Frame]
  ): Either[Error, Step] =
    if input.size - at >= text.length && text.indices.forall(i =>
        byte(input, at + i) == text.charAt(i)
      )
    then finish(at + text.length, value, frames)
    else Left(unexpected(input, at, Expected.Value))

  // `tokenCase` (`Decoding/ByteString.hs:39-57`) at a byte that is present.
  private def token(input: Bytes, at: Int, frames: List[Frame]): Either[Error, Step] =
    byte(input, at) match
      case 123 => Right(Step.Continue(State(at + 1, Mode.ObjectFirst, frames)))
      case 91  => Right(Step.Continue(State(at + 1, Mode.ArrayFirst, frames)))
      case 34 =>
        NativeJsonString
          .read(input, at)
          .flatMap((text, end) => finish(end, JsonValue.String(text), frames))
      case 110 => literal(input, at, "null", JsonValue.Null, frames)
      case 116 => literal(input, at, "true", JsonValue.Bool(true), frames)
      case 102 => literal(input, at, "false", JsonValue.Bool(false), frames)
      case c if c == 45 || c >= 48 && c <= 57 =>
        NativeJsonNumber.read(input, at).flatMap((number, end) => finish(end, number, frames))
      case _ => Left(unexpected(input, at, Expected.Value))

  // `goRK`: a key from its opening quote. The reader refuses a lone surrogate (as an invalid UTF-8
  // stream), so `pack` keeps every accepted string as it is.
  private def key(input: Bytes, at: Int): Either[Error, (JsonKey, Int)] =
    NativeJsonString.read(input, at).map((text, end) => JsonKey.fromText(text) -> end)

  private def advance(input: Bytes, original: State): Either[Error, Step] =
    val s = original.copy(at = space(input, original.at))
    def is(c: Int): Boolean = s.at < input.size && byte(input, s.at) == c
    s.mode match
      case Mode.Value =>
        if s.at == input.size then Left(Error.EndOfInput(Expected.Value))
        else token(input, s.at, s.frames)
      case Mode.ArrayFirst =>
        if s.at == input.size then Left(Error.EndOfInput(Expected.ValueOrArrayEnd))
        else if is(93) then finish(s.at + 1, JsonValue.Array(Vector.empty), s.frames)
        else token(input, s.at, Frame.ArrayItems(Nil) :: s.frames)
      case Mode.ArrayAfter(items) =>
        if is(93) then finish(s.at + 1, JsonValue.Array(items.reverse.toVector), s.frames)
        else if is(44) then
          Right(Step.Continue(State(s.at + 1, Mode.Value, Frame.ArrayItems(items) :: s.frames)))
        else refuse(input, s.at, Expected.ArraySeparator)
      case Mode.ObjectFirst =>
        if is(34) then
          key(input, s.at).map((k, end) => Step.Continue(State(end, Mode.Colon(Nil, k), s.frames)))
        else if is(125) then finish(s.at + 1, JsonValue.Object(JsonObject.fromList(Nil)), s.frames)
        else refuse(input, s.at, Expected.KeyOrObjectEnd)
      case Mode.ObjectAfter(fields) =>
        if is(44) then
          val next = space(input, s.at + 1)
          if next == input.size then Left(Error.EndOfInput(Expected.KeyLiteral))
          else if byte(input, next) == 34 then
            key(input, next).map((k, end) =>
              Step.Continue(State(end, Mode.Colon(fields, k), s.frames))
            )
          else Left(unexpected(input, s.at, Expected.KeyLiteral))
        else if is(125) then
          // The pairs are reversed and `fromList` keeps a key's last pair, so the first original
          // wins (`Decoding/Conversion.hs:81-94`).
          finish(s.at + 1, JsonValue.Object(JsonObject.fromList(fields)), s.frames)
        else refuse(input, s.at, Expected.ObjectSeparator)
      case Mode.Colon(fields, k) =>
        if is(58) then
          Right(
            Step.Continue(State(s.at + 1, Mode.Value, Frame.ObjectField(fields, k) :: s.frames))
          )
        else refuse(input, s.at, Expected.Colon)

  /** One value from the start of `input`, and where it ends; what follows is not examined. */
  def parse(input: Bytes): Either[Error, Parsed] =
    @tailrec def loop(s: State): Either[Error, Parsed] = advance(input, s) match
      case Left(error)                => Left(error)
      case Right(Step.Done(parsed))   => Right(parsed)
      case Right(Step.Continue(next)) => loop(next)
    loop(State(0, Mode.Value, Nil))

  /** `bsSpace` (`Decoding/Conversion.hs:26-27`): only JSON whitespace follows `from`. */
  def onlySpace(input: Bytes, from: Int): Boolean = space(input, from) == input.size

  /** `eitherDecodeStrict` at `Value` (`Decoding.hs:48-53`). The reference runs `ifromJSON` between
    * the parse and the trailing-input check; at `Value` it always succeeds, so the check follows at
    * once. A decoder of a typed value runs between `parse` and `onlySpace`, as the reference's
    * does.
    */
  def decodeStrict(input: Bytes): Either[DecodeError, JsonValue] =
    parse(input) match
      case Left(error) => Left(DecodeError.Syntax(error))
      case Right(Parsed(value, end)) =>
        Either.cond(onlySpace(input, end), value, DecodeError.TrailingGarbage)
