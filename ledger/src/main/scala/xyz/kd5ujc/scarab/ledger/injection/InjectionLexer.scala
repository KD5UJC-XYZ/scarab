// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.json.{JsonValue, Scientific}

/** The installed json-stream 0.4.6.1 lexer, including chunk-dependent number/string tokens.
  * `c_lib/lexer.c:21-260`, `Data/JsonStream/CLexer.hs:120-253`. Separators are lexer whitespace;
  * this is deliberately not a replacement with a conventional JSON grammar.
  */
private[injection] object InjectionLexer:
  enum Token:
    case ObjectStart, ObjectEnd, ArrayStart, ArrayEnd, StringEnd
    case Scalar(value: JsonValue)
    case StringRaw(bytes: Bytes)
    case StringPart(bytes: Bytes)

  private enum Mode:
    case Base
    case Quoted(continued: Boolean, escaped: Boolean)
    case Number(parts: List[Bytes], size: Int)
    case Identifier(word: String, offset: Int, value: JsonValue)

  final case class State private[InjectionLexer] (
      private[InjectionLexer] val input: Bytes,
      private[InjectionLexer] val at: Int,
      private[InjectionLexer] val mode: Mode,
      private[InjectionLexer] val pendingStringEnd: Boolean
  ):
    def feed(bytes: Bytes): State = copy(input = bytes, at = 0)
    def remaining: Bytes = input.drop(at)

  val initial: State = State(Bytes.empty, 0, Mode.Base, false)

  enum Step:
    case Emit(token: Token, next: State)
    case NeedData(next: State)
    case Failed(error: InjectionJson.Error)

  private def space(c: Int): Boolean =
    c == ':' || c == ',' || c == ' ' || (c >= 9 && c <= 13)

  private def numberChar(c: Int): Boolean =
    (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

  @tailrec private def numberEnd(input: Bytes, at: Int): Int =
    if at < input.size && numberChar(input(at) & 255) then numberEnd(input, at + 1) else at

  @tailrec private def stringEnd(input: Bytes, at: Int, escaped: Boolean): (Int, Boolean) =
    if at == input.size then (at, escaped)
    else if escaped then stringEnd(input, at + 1, false)
    else if input(at) == '\\' then stringEnd(input, at + 1, true)
    else if input(at) == '"' then (at, false)
    else stringEnd(input, at + 1, false)

  // The C fast path accepts a lone minus/dot and permits zero digits. It is used only for a
  // number ending in its first chunk; continuations take parseNumber's different grammar.
  private def fastNumber(text: String): Option[(BigInt, Long)] =
    final case class Acc(value: BigInt, digits: Int, decimals: Int, dot: Boolean, negative: Boolean)
    text.zipWithIndex
      .foldLeft(Option(Acc(0, 0, 0, false, false))) { case (acc, (c, i)) =>
        acc.flatMap { a =>
          if i == 0 && c == '-' then Some(a.copy(negative = true))
          else if c >= '0' && c <= '9' && a.digits < 18 then
            Some(
              a.copy(
                value = a.value * 10 + (c - '0'),
                digits = a.digits + 1,
                decimals = a.decimals + (if a.dot then 1 else 0)
              )
            )
          else if c == '.' && !a.dot then Some(a.copy(dot = true))
          else None
        }
      }
      .map(a => ((if a.negative then -a.value else a.value), -a.decimals.toLong))

  private val scientific = "([+-]?)([0-9]+)(?:\\.([0-9]*))?(?:[eE]([+-]?)([0-9]*))?".r

  private val leafDigits = 10240

  // Integer concatenation is exact: left * 10^rightWidth + right. Midpoint splitting limits
  // the JVM decimal constructor's quadratic work without changing Scientific's representation.
  // The measured leaf size balances CPU time against intermediate allocation; see
  // docs/performance/injection-coefficients.md.
  private def decimalCoefficient(digits: String): BigInt =
    enum Work:
      case Read(text: String)
      case Ready(value: BigInt)
    enum Frame:
      case Pending(right: String)
      case Join(left: BigInt, width: Int)
    @tailrec def loop(work: Work, stack: List[Frame], powers: Map[Int, BigInt]): BigInt =
      work match
        case Work.Read(text) =>
          if text.length <= leafDigits then loop(Work.Ready(BigInt(text)), stack, powers)
          else
            val (left, right) = text.splitAt(text.length / 2)
            loop(Work.Read(left), Frame.Pending(right) :: stack, powers)
        case Work.Ready(value) =>
          stack match
            case Nil => value
            case Frame.Pending(right) :: tail =>
              loop(Work.Read(right), Frame.Join(value, right.length) :: tail, powers)
            case Frame.Join(left, width) :: tail =>
              val power = powers.getOrElse(width, BigInt(10).pow(width))
              loop(Work.Ready(left * power + value), tail, powers.updated(width, power))
    if digits.length <= leafDigits then BigInt(digits)
    else loop(Work.Read(digits), Nil, Map.empty)

  private def generalNumber(text: String): Either[InjectionJson.Error, Scientific] = text match
    case scientific(sign, whole, decimal, exponentSign, power) =>
      val fraction = Option(decimal).getOrElse("")
      // Haskell's Scientific exponent is machine Int; the pinned 64-bit parser wraps arithmetic.
      val magnitude = Option(power).getOrElse("").foldLeft(0L)((n, c) => n * 10 + (c - '0'))
      val exponent = (if exponentSign == "-" then -magnitude else magnitude) - fraction.length
      val digits = whole + fraction
      val unsigned = decimalCoefficient(digits)
      val coefficient = if sign == "-" then -unsigned else unsigned
      Right(Scientific(coefficient, exponent))
    case _ => Left(InjectionJson.Error.NumberSyntax)

  private def ascii(bytes: Bytes): String =
    new String(bytes.toArray, java.nio.charset.StandardCharsets.US_ASCII)

  @tailrec def pull(s: State): Step =
    import Step.*
    import Token.*
    if s.pendingStringEnd then Emit(StringEnd, s.copy(pendingStringEnd = false))
    else if s.at == s.input.size then NeedData(s)
    else
      s.mode match
        case Mode.Base =>
          val c = s.input(s.at) & 255
          def token(t: Token): Step = Emit(t, s.copy(at = s.at + 1))
          if space(c) then pull(s.copy(at = s.at + 1))
          else
            c match
              case 123 => token(ObjectStart)
              case 125 => token(ObjectEnd)
              case 91  => token(ArrayStart)
              case 93  => token(ArrayEnd)
              case 34  => pull(s.copy(at = s.at + 1, mode = Mode.Quoted(false, false)))
              case 116 =>
                pull(s.copy(at = s.at + 1, mode = Mode.Identifier("true", 1, JsonValue.Bool(true))))
              case 102 =>
                pull(
                  s.copy(at = s.at + 1, mode = Mode.Identifier("false", 1, JsonValue.Bool(false)))
                )
              case 110 =>
                pull(s.copy(at = s.at + 1, mode = Mode.Identifier("null", 1, JsonValue.Null)))
              case _ if numberChar(c) => pull(s.copy(mode = Mode.Number(Nil, 0)))
              case _                  => Failed(InjectionJson.Error.LexicalByte(c))
        case Mode.Quoted(continued, escaped) =>
          val (end, nextEscape) = stringEnd(s.input, s.at, escaped)
          val bytes = s.input.slice(s.at, end)
          if end == s.input.size then
            Emit(StringPart(bytes), s.copy(at = end, mode = Mode.Quoted(true, nextEscape)))
          else
            val next = s.copy(at = end + 1, mode = Mode.Base, pendingStringEnd = continued)
            Emit(if continued then StringPart(bytes) else StringRaw(bytes), next)
        case Mode.Number(parts, size) =>
          val end = numberEnd(s.input, s.at)
          val bytes = s.input.slice(s.at, end)
          if end == s.input.size then
            if parts.nonEmpty && size > 200000 then Failed(InjectionJson.Error.NumberLimit)
            else pull(s.copy(at = end, mode = Mode.Number(bytes :: parts, size + bytes.size)))
          else
            val text = (bytes :: parts).reverseIterator.map(ascii).mkString
            val number =
              if parts.isEmpty then
                fastNumber(text).fold(generalNumber(text))((n, e) => Right(Scientific(n, e)))
              else generalNumber(text)
            number match
              case Left(error) => Failed(error)
              case Right(value) =>
                Emit(Scalar(JsonValue.Number(value)), s.copy(at = end, mode = Mode.Base))
        case Mode.Identifier(word, offset, value) =>
          val c = s.input(s.at) & 255
          if offset == word.length then
            if space(c) || c == ']' || c == '}' then Emit(Scalar(value), s.copy(mode = Mode.Base))
            else Failed(InjectionJson.Error.IdentifierBoundary(c))
          else if c == word.charAt(offset) then
            pull(s.copy(at = s.at + 1, mode = Mode.Identifier(word, offset + 1, value)))
          else Failed(InjectionJson.Error.IdentifierByte(word, offset, c))
