// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.json.{JsonObject, JsonValue, Scientific}

/** Pull-based `objectItems value`, from json-stream 0.4.6.1 `Parser.hs:315-425,610-638,780-790`.
  * One completed entry is yielded before parsing the next entry. The consumer then decodes its
  * domain key, domain value and folds it (`Shelley/Genesis.hs:903-925`, cardano-ledger f649f975).
  * `NeedData` is preserved after an empty feed; this parser does not invent an EOF refusal.
  *
  * Each parser mode holds the place its result goes, so a completed value always has a destination,
  * and a string continuation is read only in a string mode (see `InjectionLexer`).
  */
object InjectionJson:
  /** The refusals `objectItems value` can reach with this lexer, one case per reference message;
    * `message` is the reference's text. The parser's other messages are unreachable here: `array'`
    * is entered only with `ArrayBegin` in hand (`Parser.hs:259`), and a string continuation never
    * meets another element (`Parser.hs:353,603,616,636`).
    */
  enum Error extends DomainError:
    /** `TokFailed` (`CLexer.hs:193,214,218,234`) where the parser received it
      * (`Parser.hs:321,361`).
      */
    case TokenFailed(site: TokenSite)

    /** `unescapeText` refused the string (`Parser.hs:331,349,408,486`). */
    case Undecodable(arrival: Arrival)

    /** `Object - unexpected item` (`Parser.hs:334`). */
    case UnexpectedItem(item: Item)

    /** `aeValue - unexpected token` (`Parser.hs:411`). */
    case UnexpectedToken(end: End)

    /** `ArrayEnd in ignoreval on 0 level` (`Parser.hs:622`). */
    case IgnoredArrayEnd

    /** `ObjectEnd in ignoreval on 0 level` (`Parser.hs:623`). */
    case IgnoredObjectEnd

    def message: String = this match
      case TokenFailed(TokenSite.Object)   => "Object - token failed"
      case TokenFailed(TokenSite.MoreData) => "More data - lexer failed."
      // `show (DecodeError desc Nothing)`: text-2.1.3 `Data/Text/Encoding/Error.hs:83-84`
      // (2.0.2 `:84-85` is the same) with the description at `Unescape.hs:104`.
      case Undecodable(Arrival.Whole) =>
        "Cannot decode input: Data.Text.Internal.Encoding.decodeUtf8: Invalid UTF-8 stream"
      case Undecodable(Arrival.Parts) => "Error decoding UTF8"
      case UnexpectedItem(item)       => "Object - unexpected item: " + InjectionShow.item(item)
      case UnexpectedToken(end)       => "aeValue - unexpected token: " + InjectionShow.end(end)
      case IgnoredArrayEnd            => "ArrayEnd in ignoreval on 0 level"
      case IgnoredObjectEnd           => "ObjectEnd in ignoreval on 0 level"

  /** The parser that received `TokFailed`: the outer `object'` before its first element
    * (`Parser.hs:321`) or any parser after it, through `moreData` (`Parser.hs:361`).
    */
  enum TokenSite:
    case Object, MoreData

  /** A string read as one `StringRaw` token (`Parser.hs:331,408`: `show` of the exception) or
    * concatenated from `StringContent` parts (`Parser.hs:349,486`).
    */
  enum Arrival:
    case Whole, Parts

  /** The `JValue` scalars the lexer yields (`CLexer.hs:195-218`). */
  enum Scalar:
    case Bool(value: Boolean)
    case Null
    case Number(value: Scientific)

    def json: JsonValue = this match
      case Bool(value)   => JsonValue.Bool(value)
      case Null          => JsonValue.Null
      case Number(value) => JsonValue.Number(value)

  /** The elements `object'` refuses in key position (`TokenParser.hs:12-22`). `ArrayEnd` holds the
    * rest of the chunk after it.
    */
  enum Item:
    case ArrayBegin, ObjectBegin
    case ArrayEnd(rest: Bytes)
    case JValue(value: Scalar)
    case JInteger(value: Long)

  /** The elements `aeValue` refuses, each with the rest of the chunk after it. */
  enum End:
    case ArrayEnd(rest: Bytes)
    case ObjectEnd(rest: Bytes)

  /** Where a completed value goes: a yielded outer entry, an array or a nested object's field. */
  private enum Parent:
    case Entry(key: String)
    case Item(values: List[JsonValue], up: Parent)
    case Field(fields: List[(String, JsonValue)], key: String, up: Parent)

  /** The object whose keys are read: the outer one, whose entries are yielded, or a nested one. */
  private enum KeySite:
    case Outer
    case Inner(fields: List[(String, JsonValue)], up: Parent)

  private enum Resume:
    case Key(site: KeySite)
    case Done

  private enum Target:
    case Key(site: KeySite)
    case Value(into: Parent)

  /** A parser waiting for an element outside a string continuation: `object'` before its first
    * element, its `nextitem`, `aeValue`, `array'` 's `nextitem` and `ignoreVal'` 's `handleTok`.
    */
  private enum Mode:
    case Root
    case Key(site: KeySite)
    case Value(into: Parent)
    case Items(values: List[JsonValue], up: Parent)
    case Ignore(level: Int, next: Resume)

  /** A parser inside a string continuation: `getLongKey` /`longString`, `ignoreStrRestThen
    * ignoreVal` and `handleLongString`.
    */
  private enum TextMode:
    case Long(parts: List[Bytes], size: Int, target: Target)
    case Skip(next: Resume)
    case Ignore(level: Int, next: Resume)

  private enum Phase:
    case Tokens(lexer: InjectionLexer.State, mode: Mode)
    case Text(lexer: InjectionLexer.Text, mode: TextMode)

  final class State private[InjectionJson] (private[InjectionJson] val phase: Phase):
    def pull: Step = run(phase)

  final class Input private[InjectionJson] (phase: Phase):
    def feed(bytes: Bytes): State = phase match
      case Phase.Tokens(lexer, mode) => new State(Phase.Tokens(lexer.feed(bytes), mode))
      case Phase.Text(lexer, mode)   => new State(Phase.Text(lexer.feed(bytes), mode))

  enum Step:
    case Entry(key: String, value: JsonValue, next: State)
    case NeedData(input: Input)
    case Done(remaining: Bytes)
    case Failed(error: Error)

  val initial: State = new State(Phase.Tokens(InjectionLexer.initial, Mode.Root))

  // `objectKeyStringLimit` (`Parser.hs:123-124`).
  private val objectKeyStringLimit = 65536

  private enum Action:
    case Continue(phase: Phase)
    case Return(step: Step)

  private def tokens(lexer: InjectionLexer.State, mode: Mode): Action =
    Action.Continue(Phase.Tokens(lexer, mode))

  private def text(lexer: InjectionLexer.Text, mode: TextMode): Action =
    Action.Continue(Phase.Text(lexer, mode))

  private def refuse(error: Error): Action = Action.Return(Step.Failed(error))

  private def resume(lexer: InjectionLexer.State, next: Resume): Action = next match
    case Resume.Key(site) => tokens(lexer, Mode.Key(site))
    case Resume.Done      => Action.Return(Step.Done(lexer.remaining))

  private def value(lexer: InjectionLexer.State, json: JsonValue, into: Parent): Action =
    into match
      case Parent.Entry(key) =>
        Action.Return(
          Step.Entry(key, json, new State(Phase.Tokens(lexer, Mode.Key(KeySite.Outer))))
        )
      case Parent.Item(values, up) => tokens(lexer, Mode.Items(json :: values, up))
      case Parent.Field(fields, key, up) =>
        tokens(lexer, Mode.Key(KeySite.Inner((key -> json) :: fields, up)))

  private def string(
      lexer: InjectionLexer.State,
      bytes: Bytes,
      arrival: Arrival,
      target: Target
  ): Action =
    InjectionString.read(bytes) match
      case None => refuse(Error.Undecodable(arrival))
      case Some(decoded) =>
        target match
          case Target.Value(into) => value(lexer, JsonValue.String(decoded), into)
          case Target.Key(KeySite.Outer) =>
            tokens(lexer, Mode.Value(Parent.Entry(decoded)))
          case Target.Key(KeySite.Inner(fields, up)) =>
            tokens(lexer, Mode.Value(Parent.Field(fields, decoded, up)))

  // `aeValue` (`Parser.hs:400-411`).
  private def readValue(
      lexer: InjectionLexer.State,
      token: InjectionLexer.Token,
      into: Parent
  ): Action =
    import InjectionLexer.Token.*
    token match
      case Value(scalar)    => value(lexer, scalar.json, into)
      case Integer(n)       => value(lexer, JsonValue.Number(Scientific(n, 0)), into)
      case StringRaw(bytes) => string(lexer, bytes, Arrival.Whole, Target.Value(into))
      case ArrayStart       => tokens(lexer, Mode.Items(Nil, into))
      case ObjectStart      => tokens(lexer, Mode.Key(KeySite.Inner(Nil, into)))
      case ArrayEnd         => refuse(Error.UnexpectedToken(End.ArrayEnd(lexer.remaining)))
      case ObjectEnd        => refuse(Error.UnexpectedToken(End.ObjectEnd(lexer.remaining)))

  // `ignoreVal'`'s `handleTok` (`Parser.hs:618-636`).
  private def ignore(
      lexer: InjectionLexer.State,
      level: Int,
      next: Resume,
      token: InjectionLexer.Token
  ): Action =
    import InjectionLexer.Token.*
    def again(n: Int): Action = tokens(lexer, Mode.Ignore(n, next))
    def close(error: Error): Action =
      if level == 0 then refuse(error)
      else if level == 1 then resume(lexer, next)
      else again(level - 1)
    token match
      case Value(_) | Integer(_) =>
        if level > 0 then again(level)
        else
          next match
            case Resume.Done      => Action.Return(Step.Done(Bytes.empty))
            case Resume.Key(site) => tokens(lexer, Mode.Key(site))
      case StringRaw(_)             => if level == 0 then resume(lexer, next) else again(level)
      case ArrayStart | ObjectStart => again(level + 1)
      case ArrayEnd                 => close(Error.IgnoredArrayEnd)
      case ObjectEnd                => close(Error.IgnoredObjectEnd)

  private def consume(
      lexer: InjectionLexer.State,
      mode: Mode,
      token: InjectionLexer.Token
  ): Action =
    import InjectionLexer.Token.*
    mode match
      case Mode.Root =>
        token match
          case ObjectStart => tokens(lexer, Mode.Key(KeySite.Outer))
          case _           => ignore(lexer, 0, Resume.Done, token)
      case Mode.Key(site) =>
        def unexpected(item: Item): Action = refuse(Error.UnexpectedItem(item))
        token match
          case ObjectEnd =>
            site match
              case KeySite.Outer => Action.Return(Step.Done(lexer.remaining))
              // The pairs are reversed and `fromList` keeps a key's last pair: the first original wins.
              case KeySite.Inner(fields, up) =>
                value(lexer, JsonValue.Object(JsonObject.fromList(fields)), up)
          case StringRaw(bytes) => string(lexer, bytes, Arrival.Whole, Target.Key(site))
          case ArrayStart       => unexpected(Item.ArrayBegin)
          case ObjectStart      => unexpected(Item.ObjectBegin)
          case ArrayEnd         => unexpected(Item.ArrayEnd(lexer.remaining))
          case Value(scalar)    => unexpected(Item.JValue(scalar))
          case Integer(n)       => unexpected(Item.JInteger(n))
      case Mode.Value(into) => readValue(lexer, token, into)
      case Mode.Items(values, up) =>
        token match
          case ArrayEnd => value(lexer, JsonValue.Array(values.reverse.toVector), up)
          case _        => readValue(lexer, token, Parent.Item(values, up))
      case Mode.Ignore(level, next) => ignore(lexer, level, next, token)

  // The first `StringContent` of a string, in the parser waiting for an element.
  private def open(lexer: InjectionLexer.Text, mode: Mode, part: Bytes): Action =
    def long(target: Target): Action = text(lexer, TextMode.Long(List(part), part.size, target))
    mode match
      case Mode.Root                => text(lexer, TextMode.Ignore(0, Resume.Done))
      case Mode.Key(site)           => long(Target.Key(site))
      case Mode.Value(into)         => long(Target.Value(into))
      case Mode.Items(values, up)   => long(Target.Value(Parent.Item(values, up)))
      case Mode.Ignore(level, next) => text(lexer, TextMode.Ignore(level, next))

  private def content(lexer: InjectionLexer.Text, mode: TextMode, part: Bytes): Action =
    mode match
      case TextMode.Long(_, size, Target.Key(site)) if size > objectKeyStringLimit =>
        text(lexer, TextMode.Skip(Resume.Key(site)))
      case TextMode.Long(parts, size, target) =>
        text(lexer, TextMode.Long(part :: parts, size + part.size, target))
      case TextMode.Skip(_) | TextMode.Ignore(_, _) => text(lexer, mode)

  private def end(lexer: InjectionLexer.State, mode: TextMode): Action = mode match
    case TextMode.Long(parts, _, target) =>
      string(lexer, Bytes(parts.reverseIterator.flatMap(_.value).toVector), Arrival.Parts, target)
    case TextMode.Skip(next)          => tokens(lexer, Mode.Ignore(0, next))
    case TextMode.Ignore(0, next)     => resume(lexer, next)
    case TextMode.Ignore(level, next) => tokens(lexer, Mode.Ignore(level, next))

  @tailrec private def run(phase: Phase): Step =
    val action = phase match
      case Phase.Tokens(lexer, mode) =>
        InjectionLexer.pull(lexer) match
          case InjectionLexer.Step.NeedData(next) =>
            Action.Return(Step.NeedData(new Input(Phase.Tokens(next, mode))))
          case InjectionLexer.Step.Failed =>
            val site = mode match
              case Mode.Root => TokenSite.Object
              case _         => TokenSite.MoreData
            refuse(Error.TokenFailed(site))
          case InjectionLexer.Step.Emit(token, next)   => consume(next, mode, token)
          case InjectionLexer.Step.Content(part, next) => open(next, mode, part)
      case Phase.Text(lexer, mode) =>
        lexer.pull match
          case InjectionLexer.TextStep.NeedData(next) =>
            Action.Return(Step.NeedData(new Input(Phase.Text(next, mode))))
          case InjectionLexer.TextStep.Content(part, next) => content(next, mode, part)
          case InjectionLexer.TextStep.End(next)           => end(next, mode)
    action match
      case Action.Return(step)   => step
      case Action.Continue(next) => run(next)
