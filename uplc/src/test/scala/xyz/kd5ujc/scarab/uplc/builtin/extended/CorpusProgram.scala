// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import cats.syntax.either.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, PlutusData, Text, Value}
import xyz.kd5ujc.scarab.uplc.bls.Bls12_381

/** The part of the textual UPLC of the conformance corpus (`plutus-reference/conformance`, plutus
  * 1.70.0.0) that a tree of builtin applications needs: programs, applications, `force`, builtins,
  * `error` and constants of every type. A lambda, `delay`, a variable, `constr` or `case` is read
  * as [[CorpusTerm.Unsupported]]: the machine evaluates those, not a test of the denotations. This
  * reader is the test's; the evaluator's parser is another work package's.
  */
enum CorpusTerm:
  case Builtin(function: DefaultFun)
  case Con(constant: Constant)
  case Force(term: CorpusTerm)
  case Apply(function: CorpusTerm, argument: CorpusTerm)
  case Error
  case Unsupported(form: String)

/** Why the text is not read. */
enum ParseFailure:
  case UnexpectedEnd
  case Unexpected(token: String, expected: String)
  case UnknownBuiltin(name: String)
  case BadInteger(text: String)
  case BadBytes(text: String)
  case BadString(text: String)
  case InvalidPoint(text: String)
  case InvalidValue(cause: Value.Invalid)
  case NoTextualForm(uni: DefaultUni)
  case ElementType(cause: Constant.ElementTypeMismatch)

/** What the corpus expects of a program. */
enum Expected:
  case Result(constant: Constant)
  case EvaluationFailure
  case ParseError
  case Other(text: String)

object CorpusProgram:
  private enum Token:
    case Open, Close, LBracket, RBracket, Comma
    case Atom(text: String)
    case Str(text: String)

  private def tokens(text: String): Either[ParseFailure, Vector[Token]] =
    @tailrec def go(i: Int, acc: Vector[Token]): Either[ParseFailure, Vector[Token]] =
      if i >= text.length then Right(acc)
      else
        text.charAt(i) match
          case c if c.isWhitespace => go(i + 1, acc)
          case '-' if text.startsWith("--", i) =>
            val end = text.indexOf('\n', i)
            go(if end < 0 then text.length else end, acc)
          case '(' => go(i + 1, acc :+ Token.Open)
          case ')' => go(i + 1, acc :+ Token.Close)
          case '[' => go(i + 1, acc :+ Token.LBracket)
          case ']' => go(i + 1, acc :+ Token.RBracket)
          case ',' => go(i + 1, acc :+ Token.Comma)
          case '"' =>
            string(text, i + 1) match
              case Left(e)           => Left(e)
              case Right((s, after)) => go(after, acc :+ Token.Str(s))
          case _ =>
            val end = (i until text.length)
              .find(j => text.charAt(j).isWhitespace || "()[],\"".contains(text.charAt(j)))
              .getOrElse(text.length)
            go(end, acc :+ Token.Atom(text.substring(i, end)))
    go(0, Vector.empty)

  /** A string literal from `start` (after its quote): Haskell escapes `\\`, `\"`, `\n`, `\t` and
    * decimal code points; any other escape is refused.
    */
  private def string(text: String, start: Int): Either[ParseFailure, (String, Int)] =
    @tailrec def go(i: Int, acc: StringBuilder): Either[ParseFailure, (String, Int)] =
      if i >= text.length then Left(ParseFailure.UnexpectedEnd)
      else
        text.charAt(i) match
          case '"' => Right((acc.result(), i + 1))
          case '\\' if i + 1 < text.length =>
            text.charAt(i + 1) match
              case '\\' => go(i + 2, acc.append('\\'))
              case '"'  => go(i + 2, acc.append('"'))
              case 'n'  => go(i + 2, acc.append('\n'))
              case 't'  => go(i + 2, acc.append('\t'))
              case d if d.isDigit =>
                val end = (i + 1 until text.length).find(j => !text.charAt(j).isDigit).getOrElse(text.length)
                go(end, acc.appendAll(Character.toChars(text.substring(i + 1, end).toInt)))
              case other => Left(ParseFailure.BadString(s"\\$other"))
          case c => go(i + 1, acc.append(c))
    go(start, new StringBuilder)

  private final case class Cursor(tokens: Vector[Token], at: Int):
    def peek: Option[Token] = tokens.lift(at)
    def next: Either[ParseFailure, (Token, Cursor)] =
      peek.toRight(ParseFailure.UnexpectedEnd).map(t => (t, copy(at = at + 1)))
    def expect(t: Token): Either[ParseFailure, Cursor] = next.flatMap {
      case (`t`, c) => Right(c)
      case (other, _) => Left(ParseFailure.Unexpected(other.toString, t.toString))
    }
    def atom: Either[ParseFailure, (String, Cursor)] = next.flatMap {
      case (Token.Atom(a), c) => Right((a, c))
      case (other, _)         => Left(ParseFailure.Unexpected(other.toString, "an atom"))
    }

    /** The cursor after the balanced group that starts here. */
    def skipGroup: Either[ParseFailure, Cursor] =
      @tailrec def go(i: Int, depth: Int): Either[ParseFailure, Cursor] =
        tokens.lift(i) match
          case None                                    => Left(ParseFailure.UnexpectedEnd)
          case Some(Token.Open | Token.LBracket)       => go(i + 1, depth + 1)
          case Some(Token.Close | Token.RBracket) if depth == 1 => Right(copy(at = i + 1))
          case Some(Token.Close | Token.RBracket)      => go(i + 1, depth - 1)
          case Some(_)                                 => go(i + 1, depth)
      go(at, 0)

  private type P[A] = Either[ParseFailure, (A, Cursor)]

  private def term(c: Cursor): P[CorpusTerm] = c.next.flatMap {
    case (Token.LBracket, c1) =>
      term(c1).flatMap((f, c2) => arguments(f, c2))
    case (Token.Open, c1) =>
      c1.atom.flatMap {
        case ("builtin", c2) =>
          c2.atom.flatMap((name, c3) =>
            DefaultFun
              .fromName(name)
              .toRight(ParseFailure.UnknownBuiltin(name))
              .flatMap(f => c3.expect(Token.Close).map(CorpusTerm.Builtin(f) -> _))
          )
        case ("con", c2) =>
          tpe(c2).flatMap((u, c3) => constant(u, c3)).flatMap((k, c4) =>
            c4.expect(Token.Close).map(CorpusTerm.Con(k) -> _)
          )
        case ("force", c2) =>
          term(c2).flatMap((t, c3) => c3.expect(Token.Close).map(CorpusTerm.Force(t) -> _))
        case ("error", c2) => c2.expect(Token.Close).map(CorpusTerm.Error -> _)
        case (form, _)     => c.skipGroup.map(CorpusTerm.Unsupported(form) -> _)
      }
    case (Token.Atom(name), c1) => Right(CorpusTerm.Unsupported(s"variable $name") -> c1)
    case (other, _)             => Left(ParseFailure.Unexpected(other.toString, "a term"))
  }

  @tailrec private def arguments(f: CorpusTerm, c: Cursor): P[CorpusTerm] = c.peek match
    case Some(Token.RBracket) => Right(f -> c.copy(at = c.at + 1))
    case _ =>
      term(c) match
        case Left(e)        => Left(e)
        case Right((a, c1)) => arguments(CorpusTerm.Apply(f, a), c1)

  private def tpe(c: Cursor): P[DefaultUni] = c.next.flatMap {
    case (Token.Atom(name), c1) =>
      (name match
        case "integer"              => Some(DefaultUni.Integer)
        case "bytestring"           => Some(DefaultUni.ByteString)
        case "string"               => Some(DefaultUni.String)
        case "unit"                 => Some(DefaultUni.Unit)
        case "bool"                 => Some(DefaultUni.Bool)
        case "data"                 => Some(DefaultUni.Data)
        case "bls12_381_G1_element" => Some(DefaultUni.Bls12_381_G1_Element)
        case "bls12_381_G2_element" => Some(DefaultUni.Bls12_381_G2_Element)
        case "bls12_381_mlresult"   => Some(DefaultUni.Bls12_381_MlResult)
        case "value"                => Some(DefaultUni.Value)
        case _                      => None
      ).toRight(ParseFailure.Unexpected(name, "a type")).map(_ -> c1)
    case (Token.Open, c1) =>
      c1.atom.flatMap {
        case ("list", c2) =>
          tpe(c2).flatMap((e, c3) => c3.expect(Token.Close).map(DefaultUni.List(e) -> _))
        case ("array", c2) =>
          tpe(c2).flatMap((e, c3) => c3.expect(Token.Close).map(DefaultUni.Array(e) -> _))
        case ("pair", c2) =>
          for
            (a, c3) <- tpe(c2)
            (b, c4) <- tpe(c3)
            c5 <- c4.expect(Token.Close)
          yield DefaultUni.Pair(a, b) -> c5
        case (other, _) => Left(ParseFailure.Unexpected(other, "a type operator"))
      }
    case (other, _) => Left(ParseFailure.Unexpected(other.toString, "a type"))
  }

  private def integer(text: String): Either[ParseFailure, BigInt] =
    Either
      .catchOnly[NumberFormatException](BigInt(text.stripPrefix("+")))
      .left
      .map(_ => ParseFailure.BadInteger(text))

  private def hex(text: String, prefix: String): Either[ParseFailure, Bytes] =
    if !text.startsWith(prefix) then Left(ParseFailure.BadBytes(text))
    else Bytes.fromHex(text.drop(prefix.length)).left.map(_ => ParseFailure.BadBytes(text))

  /** Items separated by commas up to `close`. */
  private def items[A](c: Cursor, close: Token)(item: Cursor => P[A]): P[Vector[A]] =
    @tailrec def go(c: Cursor, acc: Vector[A]): P[Vector[A]] =
      item(c) match
        case Left(e) => Left(e)
        case Right((a, c1)) =>
          c1.next match
            case Right((Token.Comma, c2))            => go(c2, acc :+ a)
            case Right((t, c2)) if t == close        => Right((acc :+ a) -> c2)
            case Right((other, _))                   => Left(ParseFailure.Unexpected(other.toString, "',' or a close"))
            case Left(e)                             => Left(e)
    if c.peek.contains(close) then Right(Vector.empty -> c.copy(at = c.at + 1)) else go(c, Vector.empty)

  private def constant(u: DefaultUni, c: Cursor): P[Constant] = u match
    case DefaultUni.Integer =>
      c.atom.flatMap((a, c1) => integer(a).map(Constant.Integer(_) -> c1))
    case DefaultUni.ByteString =>
      c.atom.flatMap((a, c1) => hex(a, "#").map(Constant.ByteString(_) -> c1))
    case DefaultUni.String =>
      c.next.flatMap {
        case (Token.Str(s), c1) => Right(Constant.String(Text.fromString(s)) -> c1)
        case (other, _)         => Left(ParseFailure.Unexpected(other.toString, "a string"))
      }
    case DefaultUni.Unit =>
      c.expect(Token.Open).flatMap(_.expect(Token.Close)).map(Constant.Unit -> _)
    case DefaultUni.Bool =>
      c.atom.flatMap {
        case ("True", c1)  => Right(Constant.Bool(true) -> c1)
        case ("False", c1) => Right(Constant.Bool(false) -> c1)
        case (other, _)    => Left(ParseFailure.Unexpected(other, "True or False"))
      }
    case DefaultUni.Data => data(c).map((d, c1) => Constant.Data(d) -> c1)
    // The reference reads a point through `uncompress` (`Parser/Builtin.hs`).
    case DefaultUni.Bls12_381_G1_Element =>
      c.atom.flatMap((a, c1) =>
        hex(a, "0x").flatMap(b =>
          Bls12_381.G1
            .uncompress(b)
            .left
            .map(_ => ParseFailure.InvalidPoint(a))
            .map(Constant.Bls12_381_G1_Element(_) -> c1)
        )
      )
    case DefaultUni.Bls12_381_G2_Element =>
      c.atom.flatMap((a, c1) =>
        hex(a, "0x").flatMap(b =>
          Bls12_381.G2
            .uncompress(b)
            .left
            .map(_ => ParseFailure.InvalidPoint(a))
            .map(Constant.Bls12_381_G2_Element(_) -> c1)
        )
      )
    case DefaultUni.Bls12_381_MlResult => Left(ParseFailure.NoTextualForm(u))
    case DefaultUni.Value =>
      c.expect(Token.LBracket).flatMap(c1 =>
        items(c1, Token.RBracket) { c2 =>
          for
            c3 <- c2.expect(Token.Open)
            (currency, c4) <- c3.atom
            cs <- hex(currency, "#")
            c5 <- c4.expect(Token.Comma)
            c6 <- c5.expect(Token.LBracket)
            (tokens, c7) <- items(c6, Token.RBracket) { d =>
              for
                d1 <- d.expect(Token.Open)
                (token, d2) <- d1.atom
                ts <- hex(token, "#")
                d3 <- d2.expect(Token.Comma)
                (amount, d4) <- d3.atom
                q <- integer(amount)
                d5 <- d4.expect(Token.Close)
              yield (ts, q) -> d5
            }
            c8 <- c7.expect(Token.Close)
          yield (cs, tokens) -> c8
        }
      ).flatMap((entries, c9) =>
        Value.decoded(entries).left.map(ParseFailure.InvalidValue(_)).map(Constant.Value(_) -> c9)
      )
    case DefaultUni.List(e) =>
      c.expect(Token.LBracket)
        .flatMap(items(_, Token.RBracket)(constant(e, _)))
        .flatMap((xs, c1) =>
          Constant.List.of(e, xs).left.map(ParseFailure.ElementType(_)).map(_ -> c1)
        )
    case DefaultUni.Array(e) =>
      c.expect(Token.LBracket)
        .flatMap(items(_, Token.RBracket)(constant(e, _)))
        .flatMap((xs, c1) =>
          Constant.Array.of(e, xs).left.map(ParseFailure.ElementType(_)).map(_ -> c1)
        )
    case DefaultUni.Pair(a, b) =>
      for
        c1 <- c.expect(Token.Open)
        (x, c2) <- constant(a, c1)
        c3 <- c2.expect(Token.Comma)
        (y, c4) <- constant(b, c3)
        c5 <- c4.expect(Token.Close)
      yield Constant.Pair(x, y) -> c5

  private def data(c: Cursor): P[PlutusData] = c.next.flatMap {
    case (Token.Open, c1) => data(c1).flatMap((d, c2) => c2.expect(Token.Close).map(d -> _))
    case (Token.Atom("I"), c1) =>
      c1.atom.flatMap((a, c2) => integer(a).map(PlutusData.I(_) -> c2))
    case (Token.Atom("B"), c1) =>
      c1.atom.flatMap((a, c2) => hex(a, "#").map(PlutusData.B(_) -> c2))
    case (Token.Atom("List"), c1) =>
      c1.expect(Token.LBracket)
        .flatMap(items(_, Token.RBracket)(data))
        .map((ds, c2) => PlutusData.List(ds) -> c2)
    case (Token.Atom("Map"), c1) =>
      c1.expect(Token.LBracket)
        .flatMap(items(_, Token.RBracket) { d =>
          for
            d1 <- d.expect(Token.Open)
            (k, d2) <- data(d1)
            d3 <- d2.expect(Token.Comma)
            (v, d4) <- data(d3)
            d5 <- d4.expect(Token.Close)
          yield (k, v) -> d5
        })
        .map((kvs, c2) => PlutusData.Map(kvs) -> c2)
    case (Token.Atom("Constr"), c1) =>
      for
        (tag, c2) <- c1.atom
        i <- integer(tag)
        c3 <- c2.expect(Token.LBracket)
        (ds, c4) <- items(c3, Token.RBracket)(data)
      yield PlutusData.Constr(i, ds) -> c4
    case (other, _) => Left(ParseFailure.Unexpected(other.toString, "data"))
  }

  /** The term of the program in `text`. */
  def program(text: String): Either[ParseFailure, CorpusTerm] =
    tokens(text).flatMap { ts =>
      val c = Cursor(ts, 0)
      for
        c1 <- c.expect(Token.Open)
        (keyword, c2) <- c1.atom
        _ <- Either.cond(keyword == "program", (), ParseFailure.Unexpected(keyword, "program"))
        (_, c3) <- c2.atom
        (t, c4) <- term(c3)
        _ <- c4.expect(Token.Close)
      yield t
    }

  /** The expectation in an `.uplc.expected` file. */
  def expected(text: String): Either[ParseFailure, Expected] =
    text.trim match
      case "evaluation failure" => Right(Expected.EvaluationFailure)
      case "parse error"        => Right(Expected.ParseError)
      case other =>
        program(other).map {
          case CorpusTerm.Con(c) => Expected.Result(c)
          case _                 => Expected.Other(other)
        }
