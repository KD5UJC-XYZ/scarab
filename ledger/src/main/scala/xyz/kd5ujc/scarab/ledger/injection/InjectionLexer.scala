// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.injection.InjectionJson.Scalar
import xyz.kd5ujc.scarab.ledger.json.Scientific

/** The installed json-stream 0.4.6.1 lexer, including chunk-dependent number/string tokens.
  * `c_lib/lexer.c:21-260`, `Data/JsonStream/CLexer.hs:120-253`. Separators are lexer whitespace;
  * this is deliberately not a replacement with a conventional JSON grammar.
  *
  * The reference's `Element` stream (`Data/JsonStream/TokenParser.hs:12-22`) is split in two
  * phases. Outside a string continuation `pull` yields every element except `StringContent` after
  * the first and `StringEnd`; inside one `Text.pull` yields only those two. The lexer emits
  * `StringContent` followed only by `StringContent` or `StringEnd`, and `StringEnd` only after
  * `StringContent` (`CLexer.hs:219-225`), so a parser state that receives any other order is
  * unrepresentable.
  */
private[injection] object InjectionLexer:
  /** An element outside a string continuation. `ObjectEnd` /`ArrayEnd` carry the rest of the
    * current chunk in the reference; it is the lexer state's `remaining` right after the token.
    */
  enum Token:
    case ObjectStart, ObjectEnd, ArrayStart, ArrayEnd
    case Value(value: Scalar)
    case Integer(value: Long)
    case StringRaw(bytes: Bytes)

  private enum Mode:
    case Base
    case Quoted
    case Number(parts: List[Bytes], size: Int)
    case Identifier(word: String, offset: Int, value: Scalar)

  final case class State private[InjectionLexer] (
      private[InjectionLexer] val input: Bytes,
      private[InjectionLexer] val at: Int,
      private[InjectionLexer] val mode: Mode
  ):
    def feed(bytes: Bytes): State = copy(input = bytes, at = 0)
    def remaining: Bytes = input.drop(at)

  /** Inside a string whose first part has been emitted (`state_data = 1`, `lexer.c:180-226`).
    * `closed` is set after the final part, whose `StringEnd` follows it (`CLexer.hs:222-223`).
    */
  final case class Text private[InjectionLexer] (
      private[InjectionLexer] val input: Bytes,
      private[InjectionLexer] val at: Int,
      private[InjectionLexer] val escaped: Boolean,
      private[InjectionLexer] val closed: Boolean
  ):
    def feed(bytes: Bytes): Text = copy(input = bytes, at = 0)
    def remaining: Bytes = input.drop(at)
    def pull: TextStep = InjectionLexer.pullText(this)

  val initial: State = State(Bytes.empty, 0, Mode.Base)

  /** `Failed` is the reference's `TokFailed` (`CLexer.hs:193,214,218,234`), which carries no cause.
    */
  enum Step:
    case Emit(token: Token, next: State)
    case Content(part: Bytes, next: Text)
    case NeedData(next: State)
    case Failed

  enum TextStep:
    case Content(part: Bytes, next: Text)
    case End(next: State)
    case NeedData(next: Text)

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
  // number ending in its first chunk; continuations take parseNumber's different grammar. With
  // no fraction digits it is `JInteger`, otherwise `JValue (Number (scientific n (-digits)))`
  // (`lexer.c:110-176`, `CLexer.hs:204-208`).
  private def fastNumber(text: String): Option[Token] =
    final case class Acc(value: Long, digits: Int, decimals: Int, dot: Boolean, negative: Boolean)
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
      .map { a =>
        val signed = if a.negative then -a.value else a.value
        if a.decimals == 0 then Token.Integer(signed)
        else Token.Value(Scalar.Number(Scientific(signed, -a.decimals.toLong)))
      }

  private val scientific = "([+-]?)([0-9]+)(?:\\.([0-9]*))?(?:[eE]([+-]?)([0-9]*))?".r

  private val leafDigits = 10240

  // Integer concatenation is exact: left * 10^rightWidth + right. Midpoint splitting limits
  // the JVM decimal constructor's quadratic work without changing Scientific's representation.
  // An owner-approved implementation difference (recorded in #474, 2026-10-27) with results
  // identical to the reference's one-pass digit fold (`CLexer.hs:130-172`). The measured
  // leaf size balances CPU time against intermediate allocation; see
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

  /** `parseNumber` (`CLexer.hs:130-172`); `None` is its `Nothing`, a `TokFailed`. */
  private[injection] def generalNumber(text: String): Option[Scientific] = text match
    case scientific(sign, whole, decimal, exponentSign, power) =>
      val fraction = Option(decimal).getOrElse("")
      // Haskell's Scientific exponent is machine Int; the pinned 64-bit parser wraps arithmetic.
      val magnitude = Option(power).getOrElse("").foldLeft(0L)((n, c) => n * 10 + (c - '0'))
      val exponent = (if exponentSign == "-" then -magnitude else magnitude) - fraction.length
      val digits = whole + fraction
      val unsigned = decimalCoefficient(digits)
      val coefficient = if sign == "-" then -unsigned else unsigned
      Some(Scientific(coefficient, exponent))
    case _ => None

  private def ascii(bytes: Bytes): String =
    new String(bytes.toArray, java.nio.charset.StandardCharsets.US_ASCII)

  @tailrec def pull(s: State): Step =
    import Step.*
    import Token.*
    if s.at == s.input.size then NeedData(s)
    else
      s.mode match
        case Mode.Base =>
          val c = s.input(s.at) & 255
          def token(t: Token): Step = Emit(t, s.copy(at = s.at + 1))
          def word(text: String, value: Scalar): State =
            s.copy(at = s.at + 1, mode = Mode.Identifier(text, 1, value))
          if space(c) then pull(s.copy(at = s.at + 1))
          else
            c match
              case 123                => token(ObjectStart)
              case 125                => token(ObjectEnd)
              case 91                 => token(ArrayStart)
              case 93                 => token(ArrayEnd)
              case 34                 => pull(s.copy(at = s.at + 1, mode = Mode.Quoted))
              case 116                => pull(word("true", Scalar.Bool(true)))
              case 102                => pull(word("false", Scalar.Bool(false)))
              case 110                => pull(word("null", Scalar.Null))
              case _ if numberChar(c) => pull(s.copy(mode = Mode.Number(Nil, 0)))
              case _                  => Failed
        case Mode.Quoted =>
          val (end, escaped) = stringEnd(s.input, s.at, false)
          val bytes = s.input.slice(s.at, end)
          if end == s.input.size then Content(bytes, Text(s.input, end, escaped, false))
          else Emit(StringRaw(bytes), s.copy(at = end + 1, mode = Mode.Base))
        case Mode.Number(parts, size) =>
          val end = numberEnd(s.input, s.at)
          val bytes = s.input.slice(s.at, end)
          if end == s.input.size then
            if parts.nonEmpty && size > 200000 then Failed
            else pull(s.copy(at = end, mode = Mode.Number(bytes :: parts, size + bytes.size)))
          else
            val text = (bytes :: parts).reverseIterator.map(ascii).mkString
            val number =
              if parts.isEmpty then
                fastNumber(text).orElse(generalNumber(text).map(n => Value(Scalar.Number(n))))
              else generalNumber(text).map(n => Value(Scalar.Number(n)))
            number match
              case None        => Failed
              case Some(token) => Emit(token, s.copy(at = end, mode = Mode.Base))
        case Mode.Identifier(word, offset, value) =>
          val c = s.input(s.at) & 255
          if offset == word.length then
            if space(c) || c == ']' || c == '}' then Emit(Value(value), s.copy(mode = Mode.Base))
            else Failed
          else if c == word.charAt(offset) then
            pull(s.copy(at = s.at + 1, mode = Mode.Identifier(word, offset + 1, value)))
          else Failed

  private def pullText(t: Text): TextStep =
    if t.closed then TextStep.End(State(t.input, t.at, Mode.Base))
    else if t.at == t.input.size then TextStep.NeedData(t)
    else
      val (end, escaped) = stringEnd(t.input, t.at, t.escaped)
      val bytes = t.input.slice(t.at, end)
      if end == t.input.size then TextStep.Content(bytes, t.copy(at = end, escaped = escaped))
      else TextStep.Content(bytes, Text(t.input, end + 1, false, true))
