// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.json.{JsonObject, JsonValue}

/** Pull-based `objectItems value`, from json-stream 0.4.6.1 `Parser.hs:315-425,610-638,780-790`.
  * One completed entry is yielded before parsing the next entry. The consumer then decodes its
  * domain key, domain value and folds it (`Shelley/Genesis.hs:903-925`, cardano-ledger f649f975).
  * `NeedData` is preserved after an empty feed; this parser does not invent an EOF refusal.
  */
object InjectionJson:
  enum Error extends DomainError:
    case LexicalByte(value: Int)
    case IdentifierBoundary(value: Int)
    case IdentifierByte(word: String, offset: Int, value: Int)
    case NumberSyntax, NumberLimit, StringEncoding, StringEscape, StringSurrogate
    case UnexpectedToken(context: Expected, token: TokenKind)
    case InvalidFrame

  enum Expected:
    case ObjectKey, Value, StringEnd, IgnoredValue

  enum TokenKind:
    case ObjectStart, ObjectEnd, ArrayStart, ArrayEnd, StringEnd, Scalar, StringRaw, StringPart

  private enum Frame:
    case Entry(key: String)
    case ArrayItems(values: List[JsonValue])
    case ObjectFields(values: List[(String, JsonValue)], key: Option[String])

  private enum Destination:
    case Key(outer: Boolean)
    case Value

  private enum Resume:
    case Key(outer: Boolean)
    case Done

  private enum Mode:
    case Root, Value, ArrayValues
    case Key(outer: Boolean)
    case LongString(parts: List[Bytes], size: Int, destination: Destination)
    case SkipString(next: Resume)
    case Ignore(level: Int, inString: Boolean, next: Resume)

  final class State private[InjectionJson] (
      private[InjectionJson] val lexer: InjectionLexer.State,
      private[InjectionJson] val mode: Mode,
      private[InjectionJson] val frames: List[Frame]
  ):
    def pull: Step = run(this)

  final class Input private[InjectionJson] (state: State):
    def feed(bytes: Bytes): State = new State(state.lexer.feed(bytes), state.mode, state.frames)

  enum Step:
    case Entry(key: String, value: JsonValue, next: State)
    case NeedData(input: Input)
    case Done(remaining: Bytes)
    case Failed(error: Error)

  val initial: State = new State(InjectionLexer.initial, Mode.Root, Nil)

  private enum Action:
    case Continue(state: State)
    case Return(step: Step)

  private def continue(s: State, mode: Mode, frames: List[Frame]): Action =
    Action.Continue(new State(s.lexer, mode, frames))

  private def resume(s: State, next: Resume): Action = next match
    case Resume.Key(outer) => continue(s, Mode.Key(outer), s.frames)
    case Resume.Done       => Action.Return(Step.Done(s.lexer.remaining))

  private def value(s: State, json: JsonValue): Action = s.frames match
    case Frame.Entry(key) :: rest =>
      Action.Return(Step.Entry(key, json, new State(s.lexer, Mode.Key(true), rest)))
    case Frame.ArrayItems(values) :: rest =>
      continue(s, Mode.ArrayValues, Frame.ArrayItems(json :: values) :: rest)
    case Frame.ObjectFields(values, Some(key)) :: rest =>
      continue(s, Mode.Key(false), Frame.ObjectFields((key -> json) :: values, None) :: rest)
    case _ => Action.Return(Step.Failed(Error.InvalidFrame))

  private def string(s: State, bytes: Bytes, destination: Destination): Action =
    InjectionString.read(bytes) match
      case Left(error) => Action.Return(Step.Failed(error))
      case Right(text) =>
        destination match
          case Destination.Value     => value(s, JsonValue.String(text))
          case Destination.Key(true) => continue(s, Mode.Value, Frame.Entry(text) :: s.frames)
          case Destination.Key(false) =>
            s.frames match
              case Frame.ObjectFields(values, None) :: rest =>
                continue(s, Mode.Value, Frame.ObjectFields(values, Some(text)) :: rest)
              case _ => Action.Return(Step.Failed(Error.InvalidFrame))

  private def unexpected(expected: Expected, token: InjectionLexer.Token): Action =
    val kind = token match
      case InjectionLexer.Token.ObjectStart   => TokenKind.ObjectStart
      case InjectionLexer.Token.ObjectEnd     => TokenKind.ObjectEnd
      case InjectionLexer.Token.ArrayStart    => TokenKind.ArrayStart
      case InjectionLexer.Token.ArrayEnd      => TokenKind.ArrayEnd
      case InjectionLexer.Token.StringEnd     => TokenKind.StringEnd
      case InjectionLexer.Token.Scalar(_)     => TokenKind.Scalar
      case InjectionLexer.Token.StringRaw(_)  => TokenKind.StringRaw
      case InjectionLexer.Token.StringPart(_) => TokenKind.StringPart
    Action.Return(Step.Failed(Error.UnexpectedToken(expected, kind)))

  private def readValue(s: State, token: InjectionLexer.Token): Action =
    import InjectionLexer.Token.*
    token match
      case Scalar(json)     => value(s, json)
      case StringRaw(bytes) => string(s, bytes, Destination.Value)
      case StringPart(bytes) =>
        continue(s, Mode.LongString(List(bytes), bytes.size, Destination.Value), s.frames)
      case ArrayStart  => continue(s, Mode.ArrayValues, Frame.ArrayItems(Nil) :: s.frames)
      case ObjectStart => continue(s, Mode.Key(false), Frame.ObjectFields(Nil, None) :: s.frames)
      case _           => unexpected(Expected.Value, token)

  private def ignore(
      s: State,
      level: Int,
      inString: Boolean,
      next: Resume,
      token: InjectionLexer.Token
  ): Action =
    import InjectionLexer.Token.*
    def again(n: Int, str: Boolean = false): Action =
      continue(s, Mode.Ignore(n, str, next), s.frames)
    if inString then
      token match
        case StringPart(_) => again(level, true)
        case StringEnd     => if level == 0 then resume(s, next) else again(level)
        case _             => unexpected(Expected.StringEnd, token)
    else
      token match
        case Scalar(_) if level == 0 && next == Resume.Done => Action.Return(Step.Done(Bytes.empty))
        case Scalar(_) | StringRaw(_) => if level == 0 then resume(s, next) else again(level)
        case StringPart(_)            => again(level, true)
        case ArrayStart | ObjectStart => again(level + 1)
        case ArrayEnd | ObjectEnd =>
          if level == 0 then unexpected(Expected.IgnoredValue, token)
          else if level == 1 then resume(s, next)
          else again(level - 1)
        case StringEnd => unexpected(Expected.IgnoredValue, token)

  private def consume(s: State, token: InjectionLexer.Token): Action =
    import InjectionLexer.Token.*
    s.mode match
      case Mode.Root =>
        token match
          case ObjectStart => continue(s, Mode.Key(true), Nil)
          case _           => ignore(s, 0, false, Resume.Done, token)
      case Mode.Key(outer) =>
        token match
          case ObjectEnd if outer => Action.Return(Step.Done(s.lexer.remaining))
          case ObjectEnd =>
            s.frames match
              case Frame.ObjectFields(fields, None) :: rest =>
                // The pairs are reversed and `fromList` keeps a key's last pair: the first original wins.
                value(
                  new State(s.lexer, Mode.Value, rest),
                  JsonValue.Object(JsonObject.fromList(fields))
                )
              case _ => Action.Return(Step.Failed(Error.InvalidFrame))
          case StringRaw(bytes) => string(s, bytes, Destination.Key(outer))
          case StringPart(bytes) =>
            continue(s, Mode.LongString(List(bytes), bytes.size, Destination.Key(outer)), s.frames)
          case _ => unexpected(Expected.ObjectKey, token)
      case Mode.Value => readValue(s, token)
      case Mode.ArrayValues =>
        token match
          case ArrayEnd =>
            s.frames match
              case Frame.ArrayItems(values) :: rest =>
                value(
                  new State(s.lexer, Mode.Value, rest),
                  JsonValue.Array(values.reverse.toVector)
                )
              case _ => Action.Return(Step.Failed(Error.InvalidFrame))
          case _ => readValue(s, token)
      case Mode.LongString(parts, size, destination) =>
        token match
          case StringEnd =>
            string(s, Bytes(parts.reverseIterator.flatMap(_.value).toVector), destination)
          case StringPart(bytes) =>
            destination match
              case Destination.Key(outer) if size > 65536 =>
                continue(s, Mode.SkipString(Resume.Key(outer)), s.frames)
              case _ =>
                continue(
                  s,
                  Mode.LongString(bytes :: parts, size + bytes.size, destination),
                  s.frames
                )
          case _ => unexpected(Expected.StringEnd, token)
      case Mode.SkipString(next) =>
        token match
          case StringPart(_) => continue(s, s.mode, s.frames)
          case StringEnd     => continue(s, Mode.Ignore(0, false, next), s.frames)
          case _             => unexpected(Expected.StringEnd, token)
      case Mode.Ignore(level, inString, next) => ignore(s, level, inString, next, token)

  @tailrec private def run(s: State): Step = InjectionLexer.pull(s.lexer) match
    case InjectionLexer.Step.NeedData(next) =>
      Step.NeedData(new Input(new State(next, s.mode, s.frames)))
    case InjectionLexer.Step.Failed(error) => Step.Failed(error)
    case InjectionLexer.Step.Emit(token, next) =>
      consume(new State(next, s.mode, s.frames), token) match
        case Action.Return(step)    => step
        case Action.Continue(state) => run(state)
