// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import cats.Monad
import cats.parse.{Numbers, Parser as P, Parser0 as P0}
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant as Con,
  DefaultFun,
  DefaultUni,
  PlutusData,
  Program,
  ProgramVersion,
  Text,
  Value,
  Word64
}

/** The textual syntax of untyped Plutus Core as the reference's parser reads it: plutus 1.70.0.0
  * (`507cab8c`), `UntypedPlutusCore/Parser.hs` with `PlutusCore/Parser/ParserCommon.hs`,
  * `Parser/Type.hs` and `Parser/Builtin.hs`. Each megaparsec combinator is transcribed with the
  * cats-parse one of the same commitment: an alternative is tried only when the previous failed
  * without consuming input, and `try` is `backtrack`. Keywords have no word boundary (`symbol`), so
  * `(con integer1)` is the integer 1, as it is upstream.
  *
  * Terms, types, constants and `data` nest as deep as the text makes them, so the parser is a
  * machine on the heap (ADR 0011): cats-parse's `tailRecM` runs one step at a time, and a step
  * reads the tokens that open or close one layer and pushes or pops a continuation. Names are
  * resolved to uniques as the reference's parser does; [[NamedProgram.deBruijn]] then resolves the
  * variables.
  */
object UplcParser:

  /** The program `text` with its names (`parseProgram`, `UntypedPlutusCore/Parser.hs:152-156`). */
  def parseProgram(text: String): Either[TextError, NamedProgram] =
    Machine(text).program.parse(text) match
      case Left(error)         => Left(TextError.Syntax(error.failedAtOffset, error.expected))
      case Right((_, outcome)) => outcome

  /** The closed program `text`, with de Bruijn indices: what the conformance runner evaluates
    * (`PlutusConformance/Common.hs:374-382`, `CekEvaluator.hs:54-57`).
    */
  def program(text: String): Either[ProgramError, Program] =
    parseProgram(text)
      .leftMap(ProgramError.Unparsable(_))
      .flatMap(_.deBruijn.leftMap(ProgramError.Open(_)))

  // Characters, as Haskell classifies them (`PlutusCore/Name/Unique.hs:75-87`, `Data.Char`).
  private def isAsciiAlpha(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isIdentifierStart(c: Char): Boolean = isAsciiAlpha(c) || c == '_'
  private def isIdentifierChar(c: Char): Boolean = isIdentifierStart(c) || isDigit(c) || c == '\''
  // ASCII letters, digits, punctuation and symbols are the printable ASCII characters but space.
  private def isQuotedIdentifierChar(c: Char): Boolean = c > ' ' && c < '\u007f' && c != '`'
  private def isHexDigit(c: Char): Boolean =
    isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  /** Haskell's `isSpace` (`GHC.Unicode`, base 4.18.2.1): up to U+0377 the space, tab to carriage
    * return and U+00A0, above it the space separators. The JDK's Unicode tables stand in for GHC's.
    */
  private def isSpace(c: Char): Boolean =
    c == ' ' || (c >= 0x9 && c <= 0xd) || c == 0xa0 ||
      (c > 0x377 && Character.getType(c) == Character.SPACE_SEPARATOR)

  // `whitespace` (`ParserCommon.hs:89-90`): spaces, `--` line comments and nested `{- -}` block
  // comments. A block comment is read by a loop over its depth (`skipBlockCommentNested`: the end
  // first, then a nested comment, then any character).
  private val blockComment: P[Unit] =
    val token: P[Int] =
      P.oneOf(List(P.string("-}").as(-1), P.string("{-").as(1), P.anyChar.as(0)))
    P.string("{-") *> Monad[P0].tailRecM(1) { depth =>
      if depth == 0 then P.pure(Right(())) else token.map(change => Left(depth + change))
    }

  private val ws: P0[Unit] =
    P.oneOf(
      List(
        P.charsWhile(isSpace).void,
        (P.string("--") *> P.charsWhile0(_ != '\n')).void,
        blockComment
      )
    ).rep0
      .void

  private def symbol(s: String): P[Unit] = P.string(s) <* ws
  private def symbolChar(c: Char): P[Unit] = P.char(c) <* ws

  // The punctuation, each with the spaces after it, built once: a step builds no character set.
  private val openParen: P[Unit] = symbolChar('(')
  private val closeParen: P[Unit] = symbolChar(')')
  private val openBracket: P[Unit] = symbolChar('[')
  private val closeBracket: P[Unit] = symbolChar(']')
  private val comma: P[Unit] = symbolChar(',')
  private val builtinName: P0[String] = P.charsWhile0(isIdentifierChar) <* ws

  // `Lex.decimal` into an unbounded number.
  private val decimal: P[BigInt] = Numbers.digits.map(BigInt(_))

  // `conInteger = Lex.signed whitespace (lexeme Lex.decimal)` (`Builtin.hs:66-67`).
  private val integer: P[BigInt] =
    val sign: P[BigInt => BigInt] =
      P.char('+').as((n: BigInt) => n) | P.char('-').as((n: BigInt) => -n)
    ((sign <* ws).?.with1 ~ (decimal <* ws)).map((s, n) => s.fold(n)(_(n)))

  // `many hexByte` (`Builtin.hs:70-78`): an odd digit fails after consuming.
  private val hexBytes: P0[Bytes] =
    (P.charWhere(isHexDigit) ~ P.charWhere(isHexDigit)).rep0
      .map(pairs => Bytes.wrap(pairs.map((h, l) => Integer.parseInt(s"$h$l", 16).toByte).toArray))

  // `conBS` (`Builtin.hs:77-78`) and `con0xBS` (`Builtin.hs:135-136`).
  private val bytestring: P[Bytes] = P.char('#') *> hexBytes <* ws
  private val bytes0x: P[Bytes] = P.string("0x") *> hexBytes <* ws

  // `version` (`ParserCommon.hs:206-212`).
  private val version: P[ProgramVersion] =
    ((decimal <* P.char('.')) ~ (decimal <* P.char('.')) ~ decimal <* ws).mapFilter {
      case ((major, minor), patch) => ProgramVersion.of(major, minor, patch)
    }

  // `name` (`ParserCommon.hs:215-236`): an identifier or a back-quoted one, then an explicit unique
  // (`-` and a decimal, read into a wrapping `Int`) when one follows; all under `try`.
  private val identifier: P[String] =
    (P.charWhere(isIdentifierStart) ~ P.charsWhile0(isIdentifierChar)).string |
      (P.char('`') *> P.charsWhile(isQuotedIdentifierChar) <* P.char('`'))
  private val explicitUnique: P0[Option[Long]] =
    (P.char('-') *> Numbers.digits.map(_.foldLeft(0L)((u, d) => u * 10 + (d - '0')))).backtrack.?
  private val name: P[(String, Option[Long])] = (identifier ~ explicitUnique).backtrack

  /** The uniques given so far to names written without one (`uniqueForName`,
    * `ParserCommon.hs:240-248`): one per text, fresh from 0 in the order of first use
    * (`freshUnique`, `PlutusCore/Quote.hs:51-52,108-112`).
    */
  private final case class Names(byText: Map[String, Long], fresh: Long):
    def resolve(text: String, explicit: Option[Long]): (Names, Name) =
      explicit match
        case Some(unique) => (this, Name(text, unique))
        case None =>
          byText.get(text) match
            case Some(unique) => (this, Name(text, unique))
            case None         => (Names(byText.updated(text, fresh), fresh + 1), Name(text, fresh))

  private type Outcome = Either[TextError, NamedProgram]

  /** The machine for one text. The text is at hand because a string literal is read by
    * [[HaskellChar.readLitChar]] on a window of the text.
    */
  private final class Machine(input: String):

    sealed trait State
    final case class Expect[A](goal: Goal[A], k: K[A], names: Names) extends State
    final case class Return[A](value: A, k: K[A], names: Names) extends State

    type Step = Either[State, Outcome]

    def expect[A](goal: Goal[A], k: K[A], names: Names): Step = Left(Expect(goal, k, names))
    def back[A](value: A, k: K[A], names: Names): Step = Left(Return(value, k, names))
    def stop(error: TextError): Step = Right(Left(error))

    /** The first alternative, or the second if it fails without consuming input. */
    def either(first: P0[Step], second: P0[Step]): P0[Step] = P.oneOf0(List(first, second))

    /** Something to read: its opening tokens, after which the step pushes its continuation. */
    sealed trait Goal[A]:
      def start(k: K[A], names: Names): P0[Step]

    /** What to do with a value read: its closing tokens, or the next thing to read. */
    sealed trait K[A]:
      def receive(value: A, names: Names): P0[Step]

    // `program` (`UntypedPlutusCore/Parser.hs:133-141`): the version is known to every term in it.
    // A `def`, so that the machine is complete before the parser that runs it is built.
    def program: P0[Outcome] =
      (ws.with1 *> openParen *> symbol("program") *> version).flatMap { v =>
        val run = Monad[P0].tailRecM[State, Outcome](
          Expect(TermGoal(v), Top(v), Names(Map.empty, 0))
        ) {
          case s: Expect[a] => s.goal.start(s.k, s.names)
          case s: Return[a] => s.k.receive(s.value, s.names)
        }
        run
      }

    // ---- terms (`UntypedPlutusCore/Parser.hs:43-130`) ----

    // `term`: an application, a keyword in parentheses, or a variable; `withSpan` reads the spaces
    // after each.
    final case class TermGoal(version: ProgramVersion) extends Goal[NamedTerm]:
      def start(k: K[NamedTerm], names: Names): P[Step] =
        P.oneOf(
          List(
            openBracket.as(expect(this, AppFunction(this, k), names)),
            openParen *> keyword(k, names),
            (name <* ws).map { (text, explicit) =>
              val (next, n) = names.resolve(text, explicit)
              back(NamedTerm.variable(n), k, next)
            }
          )
        )

      private def close(value: NamedTerm, k: K[NamedTerm], names: Names): P[Step] =
        closeParen.as(back(value, k, names))

      private def keyword(k: K[NamedTerm], names: Names): P[Step] =
        P.oneOf(
          List(
            symbol("builtin") *> builtinName.flatMap { text =>
              DefaultFun
                .fromName(text)
                .fold(P.pure(stop(TextError.UnknownBuiltin(text))))(f =>
                  close(NamedTerm.builtin(f), k, names)
                )
            },
            symbol("lam") *> (name <* ws).map { (text, explicit) =>
              val (next, binder) = names.resolve(text, explicit)
              expect(this, Lam(binder, k), next)
            },
            symbol("constr") *> constr(k, names),
            symbol("con") *> P.pure(expect(TypeGoal, ConType(k), names)),
            symbol("delay") *> P.pure(expect(this, Wrap(NamedTerm.delay, k), names)),
            symbol("force") *> P.pure(expect(this, Wrap(NamedTerm.force, k), names)),
            symbol("error") *> close(NamedTerm.error, k, names),
            symbol("case") *> P.pure(expect(this, Scrutinee(this, k), names))
          )
        )

      // `constrTerm`: the version is checked before the tag is read.
      private def constr(k: K[NamedTerm], names: Names): P0[Step] =
        if !version.allowsConstrAndCase then P.pure(stop(TextError.ConstrBeforeV110(version)))
        else
          (decimal <* ws).flatMap { tag =>
            Word64
              .fromBigInt(tag)
              .fold(P.pure(stop(TextError.ConstrTagTooLarge(tag))))(t =>
                fields(Fields(this, t, Vector.empty, k), names)
              )
          }

      /** `many term` and then the closing token of `k`. */
      def fields(k: Fields, names: Names): P0[Step] =
        either(
          start(k, names),
          closeParen.as(back(NamedTerm.constr(k.tag, k.done), k.next, names))
        )

      /** The branches of a `case`: the version is checked once they are read. */
      def branches(k: Branches, names: Names): P0[Step] =
        either(
          start(k, names),
          if !version.allowsConstrAndCase then P.pure(stop(TextError.CaseBeforeV110(version)))
          else closeParen.as(back(NamedTerm.caseOf(k.scrutinee, k.done), k.next, names))
        )

    final case class Top(version: ProgramVersion) extends K[NamedTerm]:
      def receive(body: NamedTerm, names: Names): P0[Step] =
        (P.char(')') *> ws *> P.end).as(Right(Right(NamedProgram(version, body))))

    final case class AppFunction(goal: TermGoal, next: K[NamedTerm]) extends K[NamedTerm]:
      def receive(function: NamedTerm, names: Names): P0[Step] =
        P.pure(expect(goal, AppArgument(goal, function, next), names))

    // `some term`, applied left to right (`mkIterApp`).
    final case class AppArgument(goal: TermGoal, function: NamedTerm, next: K[NamedTerm])
        extends K[NamedTerm]:
      def receive(argument: NamedTerm, names: Names): P0[Step] =
        val applied = NamedTerm.app(function, argument)
        either(
          goal.start(AppArgument(goal, applied, next), names),
          closeBracket.as(back(applied, next, names))
        )

    final case class Lam(binder: Name, next: K[NamedTerm]) extends K[NamedTerm]:
      def receive(body: NamedTerm, names: Names): P0[Step] =
        closeParen.as(back(NamedTerm.lam(binder, body), next, names))

    final case class Wrap(layer: NamedTerm => NamedTerm, next: K[NamedTerm]) extends K[NamedTerm]:
      def receive(term: NamedTerm, names: Names): P0[Step] =
        closeParen.as(back(layer(term), next, names))

    final case class Fields(
        goal: TermGoal,
        tag: Word64,
        done: Vector[NamedTerm],
        next: K[NamedTerm]
    ) extends K[NamedTerm]:
      def receive(field: NamedTerm, names: Names): P0[Step] =
        goal.fields(Fields(goal, tag, done :+ field, next), names)

    final case class Scrutinee(goal: TermGoal, next: K[NamedTerm]) extends K[NamedTerm]:
      def receive(scrutinee: NamedTerm, names: Names): P0[Step] =
        goal.branches(Branches(goal, scrutinee, Vector.empty, next), names)

    final case class Branches(
        goal: TermGoal,
        scrutinee: NamedTerm,
        done: Vector[NamedTerm],
        next: K[NamedTerm]
    ) extends K[NamedTerm]:
      def receive(branch: NamedTerm, names: Names): P0[Step] =
        goal.branches(Branches(goal, scrutinee, done :+ branch, next), names)

    // `(con T v)`: the type, which must be of kind `*`, then its value, then `)`.
    final case class ConType(next: K[NamedTerm]) extends K[Kinded]:
      def receive(kinded: Kinded, names: Names): P0[Step] = kinded match
        case Kinded.Star(t) => P.pure(expect(ConstantGoal(t, parens = true), ConBody(next), names))
        case other          => P.pure(stop(TextError.NotOfKindStar(other)))

    final case class ConBody(next: K[NamedTerm]) extends K[Con]:
      def receive(c: Con, names: Names): P0[Step] =
        closeParen.as(back(NamedTerm.constant(c), next, names))

    // ---- types (`PlutusCore/Parser/Type.hs:93-158`) ----

    // `defaultUni`: every alternative is under `try`, so a type that fails to read fails without
    // consuming, but no other alternative then succeeds, and the constant around it fails.
    case object TypeGoal extends Goal[Kinded]:
      private val keywords: List[(String, Kinded)] = List(
        "integer" -> Kinded.Star(DefaultUni.Integer),
        "bytestring" -> Kinded.Star(DefaultUni.ByteString),
        "string" -> Kinded.Star(DefaultUni.String),
        "unit" -> Kinded.Star(DefaultUni.Unit),
        "bool" -> Kinded.Star(DefaultUni.Bool),
        "list" -> Kinded.ListOf,
        "array" -> Kinded.ArrayOf,
        "pair" -> Kinded.PairOf,
        "data" -> Kinded.Star(DefaultUni.Data),
        "bls12_381_G1_element" -> Kinded.Star(DefaultUni.Bls12_381_G1_Element),
        "bls12_381_G2_element" -> Kinded.Star(DefaultUni.Bls12_381_G2_Element),
        "bls12_381_mlresult" -> Kinded.Star(DefaultUni.Bls12_381_MlResult),
        "value" -> Kinded.Star(DefaultUni.Value)
      )
      private val keyword: P[Kinded] = P.oneOf(keywords.map((s, k) => symbol(s).as(k)))

      def start(k: K[Kinded], ns: Names): P[Step] =
        P.oneOf(
          List(openParen.as(expect(this, TypeHead(k), ns)), keyword.map(back(_, k, ns)))
        )

      /** `many defaultUni` and then `)`. */
      def arguments(applied: Kinded, k: K[Kinded], ns: Names): P0[Step] =
        either(closeParen.as(back(applied, k, ns)), start(TypeArgument(applied, k), ns))

    final case class TypeHead(next: K[Kinded]) extends K[Kinded]:
      def receive(head: Kinded, names: Names): P0[Step] = TypeGoal.arguments(head, next, names)

    // `foldM tryUniApply`, an argument at a time.
    final case class TypeArgument(applied: Kinded, next: K[Kinded]) extends K[Kinded]:
      def receive(argument: Kinded, names: Names): P0[Step] =
        applied(argument).fold(P.pure(stop(TextError.KindMismatch(applied, argument))))(
          TypeGoal.arguments(_, next, names)
        )

    // ---- constants (`PlutusCore/Parser/Builtin.hs:101-170`) ----

    /** A constant of type `t`; `parens` is `ExpectParens`, which only `data` reads. */
    final case class ConstantGoal(t: DefaultUni, parens: Boolean) extends Goal[Con]:
      def start(k: K[Con], names: Names): P0[Step] = t match
        case DefaultUni.Integer    => integer.map(n => back(Con.Integer(n), k, names))
        case DefaultUni.ByteString => bytestring.map(b => back(Con.ByteString(b), k, names))
        case DefaultUni.String =>
          stringLiteral.map(_.fold(stop, s => back(Con.String(s), k, names)))
        case DefaultUni.Unit => (openParen *> closeParen).as(back(Con.Unit, k, names))
        case DefaultUni.Bool =>
          P.oneOf(List(symbol("True").as(true), symbol("False").as(false)))
            .map(b => back(Con.Bool(b), k, names))
        case DefaultUni.Data =>
          if parens then openParen.as(expect(DataGoal, AsConstant(parens, k), names))
          else DataGoal.start(AsConstant(parens, k), names)
        case DefaultUni.Bls12_381_G1_Element =>
          bytes0x.map(b =>
            BlsLiteral
              .g1(b)
              .fold(
                p => stop(TextError.InvalidG1(b, p)),
                e => back(Con.Bls12_381_G1_Element(e), k, names)
              )
          )
        case DefaultUni.Bls12_381_G2_Element =>
          bytes0x.map(b =>
            BlsLiteral
              .g2(b)
              .fold(
                p => stop(TextError.InvalidG2(b, p)),
                e => back(Con.Bls12_381_G2_Element(e), k, names)
              )
          )
        case DefaultUni.Bls12_381_MlResult => P.pure(stop(TextError.MlResultLiteral))
        case DefaultUni.Value =>
          valueLiteral.map(_.fold(stop, v => back(Con.Value(v), k, names)))
        case DefaultUni.List(e)  => items(e, array = false, k, names)
        case DefaultUni.Array(e) => items(e, array = true, k, names)
        case DefaultUni.Pair(a, b) =>
          openParen.as(expect(ConstantGoal(a, parens = false), PairFirst(b, k), names))

    // `conList` and `conArray`: `[`, elements separated by `,` (`sepBy`), `]`. An element never
    // starts with `]`, so `]` first is `sepBy`'s empty list.
    private def items(e: DefaultUni, array: Boolean, k: K[Con], names: Names): P[Step] =
      openBracket *> either(
        closeBracket.as(collection(e, array, Vector.empty, k, names)),
        P.pure(expect(ConstantGoal(e, parens = false), Items(e, array, Vector.empty, k), names))
      )

    private def collection(
        e: DefaultUni,
        array: Boolean,
        done: Vector[Con],
        k: K[Con],
        names: Names
    ): Step =
      (if array then Con.Array.of(e, done) else Con.List.of(e, done))
        .fold(m => stop(TextError.IllTyped(m)), c => back(c, k, names))

    final case class Items(e: DefaultUni, array: Boolean, done: Vector[Con], next: K[Con])
        extends K[Con]:
      def receive(item: Con, names: Names): P0[Step] =
        val all = done :+ item
        either(
          comma.as(
            expect(ConstantGoal(e, parens = false), Items(e, array, all, next), names)
          ),
          closeBracket.as(collection(e, array, all, next, names))
        )

    // `conPair`: `(`, the first, `,`, the second, `)`.
    final case class PairFirst(second: DefaultUni, next: K[Con]) extends K[Con]:
      def receive(first: Con, names: Names): P0[Step] =
        comma.as(
          expect(ConstantGoal(second, parens = false), PairSecond(first, next), names)
        )

    final case class PairSecond(first: Con, next: K[Con]) extends K[Con]:
      def receive(second: Con, names: Names): P0[Step] =
        closeParen.as(back(Con.Pair(first, second), next, names))

    final case class AsConstant(parens: Boolean, next: K[Con]) extends K[PlutusData]:
      def receive(d: PlutusData, names: Names): P0[Step] =
        if parens then closeParen.as(back(Con.Data(d), next, names))
        else P.pure(back(Con.Data(d), next, names))

    // ---- data (`conDataNoParens`, `Builtin.hs:119-131`) ----

    case object DataGoal extends Goal[PlutusData]:
      def start(k: K[PlutusData], names: Names): P[Step] =
        P.oneOf(
          List(
            (symbol("Constr") *> integer).flatMap(i =>
              list(DataItems(PlutusData.Constr(i, _), Vector.empty, k), names)
            ),
            symbol("Map") *> openBracket *>
              either(
                closeBracket.as(back(PlutusData.Map(Vector.empty), k, names)),
                entry(Vector.empty, k, names)
              ),
            symbol("List") *> list(DataItems(PlutusData.List(_), Vector.empty, k), names),
            (symbol("I") *> integer).map(i => back(PlutusData.I(i), k, names)),
            (symbol("B") *> bytestring).map(b => back(PlutusData.B(b), k, names))
          )
        )

      private def list(k: DataItems, names: Names): P[Step] =
        openBracket *> either(
          closeBracket.as(back(k.build(Vector.empty), k.next, names)),
          P.pure(expect(DataGoal, k, names))
        )

      /** A map entry: `(`, the key, `,`, the value, `)`. */
      def entry(done: Vector[(PlutusData, PlutusData)], k: K[PlutusData], names: Names): P[Step] =
        openParen.as(expect(DataGoal, MapKey(done, k), names))

    final case class DataItems(
        build: Vector[PlutusData] => PlutusData,
        done: Vector[PlutusData],
        next: K[PlutusData]
    ) extends K[PlutusData]:
      def receive(item: PlutusData, names: Names): P0[Step] =
        val all = done :+ item
        either(
          comma.as(expect(DataGoal, DataItems(build, all, next), names)),
          closeBracket.as(back(build(all), next, names))
        )

    final case class MapKey(done: Vector[(PlutusData, PlutusData)], next: K[PlutusData])
        extends K[PlutusData]:
      def receive(key: PlutusData, names: Names): P0[Step] =
        comma.as(expect(DataGoal, MapValue(done, key, next), names))

    final case class MapValue(
        done: Vector[(PlutusData, PlutusData)],
        key: PlutusData,
        next: K[PlutusData]
    ) extends K[PlutusData]:
      def receive(value: PlutusData, names: Names): P0[Step] =
        val all = done :+ (key -> value)
        closeParen *> either(
          comma *> DataGoal.entry(all, next, names),
          closeBracket.as(back(PlutusData.Map(all), next, names))
        )

    // ---- string literals (`conText`, `Builtin.hs:86-87`) ----

    // `manyTill charLiteral (char '"')`: the closing quote is tried first; each character is read
    // by `readLitChar` on the ten code points ahead.
    private val stringLiteral: P[Either[TextError, Text]] =
      (P.char('"') *> P.index).flatMap { from =>
        scan(from, Vector.empty) match
          case Left(error) => P.pure(Left(error))
          case Right((codePoints, to)) =>
            (P.length0(to - from) *> P.char('"') *> ws)
              .as(Text.fromCodePoints(codePoints).leftMap(TextError.InvalidText(_)))
      }

    @tailrec private def scan(at: Int, done: Vector[Int]): Either[TextError, (Vector[Int], Int)] =
      if at >= input.length then Left(TextError.CharacterLiteral(at))
      else if input.charAt(at) == '"' then Right((done, at))
      else
        val window = codePointsFrom(at, HaskellChar.Window)
        HaskellChar.readLitChar(window) match
          case None => Left(TextError.CharacterLiteral(at))
          case Some((c, used)) =>
            scan(input.offsetByCodePoints(at, used), done :+ c)

    private def codePointsFrom(at: Int, count: Int): Vector[Int] =
      Vector.unfold((at, count)) { (i, n) =>
        Option.when(n > 0 && i < input.length) {
          val c = input.codePointAt(i)
          (c, (i + Character.charCount(c), n - 1))
        }
      }

    // ---- value literals (`conValue`, `Builtin.hs:177-220`) ----

    // The entries in their written order; the bounds and emptiness checks of `parsePair`, then
    // `buildValueWith`'s order checks, are made once all are read.
    private val valueLiteral: P[Either[TextError, Value]] =
      def listOf[A](entry: P[A]): P[List[A]] =
        openBracket *> entry.repSep0(comma) <* closeBracket
      def pairOf[A](second: P[A]): P[(Bytes, A)] =
        openParen *> ((bytestring <* comma) ~ second) <* closeParen
      listOf(pairOf(listOf(pairOf(integer)))).map(checkValue)

    private def checkValue(outer: List[(Bytes, List[(Bytes, BigInt)])]): Either[TextError, Value] =
      def key(b: Bytes) = Value.Key(b).toRight(Value.Invalid.KeyTooLong(b))
      outer
        .traverse { (currency, tokens) =>
          for
            c <- key(currency)
            ts <- tokens.traverse { (token, amount) =>
              for
                t <- key(token)
                _ <- Either.cond(amount.signum != 0, (), Value.Invalid.ZeroQuantity(c, t))
                q <- Value.Quantity(amount).toRight(Value.Invalid.QuantityOutOfBounds(amount))
              yield t -> q
            }
            _ <- Either.cond(ts.nonEmpty, (), Value.Invalid.EmptyInnerMap(c))
          yield c -> ts
        }
        .flatMap(Value.build)
        .leftMap(TextError.InvalidValue(_))
