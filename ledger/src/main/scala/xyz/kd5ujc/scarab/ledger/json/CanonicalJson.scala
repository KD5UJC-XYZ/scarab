// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import io.circe.Json
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, TreeFold}

/** A value of canonical JSON as canonical-json 0.6.0.1 holds it (`JSValue`,
  * `Text/JSON/Canonical/Types.hs`): a number is an `Int54`, a string the bytes between its quotes
  * (`JSString`, a `ShortByteString`, `:64`), and an object the pairs in the order of the text, a
  * key repeated as often as it is written. The reference reads the Byron genesis as one
  * (`readGenesisData`, `Cardano/Chain/Genesis/Data.hs:169-181`, cardano-ledger f649f975), #730.
  */
enum CanonicalJson:
  case Null
  case Bool(value: Boolean)
  case Num(value: Long)
  case Str(bytes: Bytes)
  case Arr(items: Vector[CanonicalJson])
  case Obj(fields: Vector[(Bytes, CanonicalJson)])

  /** Which kind of value this is, as a schema error names it (`expectedButGotValue`). */
  def kind: CanonicalJson.Kind = this match
    case Null    => CanonicalJson.Kind.Null
    case Bool(_) => CanonicalJson.Kind.Bool
    case Num(_)  => CanonicalJson.Kind.Number
    case Str(_)  => CanonicalJson.Kind.String
    case Arr(_)  => CanonicalJson.Kind.Array
    case Obj(_)  => CanonicalJson.Kind.Object

  /** The first value of `key`, as `fromJSField` finds it (`lookup`,
    * `Text/JSON/Canonical/Class.hs`).
    */
  def field(key: String): Option[CanonicalJson] = this match
    case Obj(fields) =>
      val k = CanonicalJson.bytesOf(key)
      fields.collectFirst { case (`k`, v) => v }
    case _ => None

object CanonicalJson:
  /** The kinds of value (`JSValue`'s constructors). */
  enum Kind:
    case Null, Bool, Number, String, Array, Object

  /** Where `parseCanonicalJSON` stops: the offset of the byte it could not take, or the input's
    * length where the input ended too soon.
    */
  final case class ParseError(offset: Int)

  private val Quote = '"'.toInt
  private val Backslash = '\\'.toInt
  private val Comma = ','.toInt
  private val Colon = ':'.toInt
  private val OpenBrace = '{'.toInt
  private val CloseBrace = '}'.toInt
  private val OpenBracket = '['.toInt
  private val CloseBracket = ']'.toInt
  private val Minus = '-'.toInt
  private val Zero = '0'.toInt

  private def concat(parts: Vector[Bytes]): Bytes =
    Bytes.wrap(parts.iterator.flatMap(_.toArray).toArray)

  private[json] def bytesOf(text: String): Bytes =
    Bytes.fromArray(text.getBytes(StandardCharsets.UTF_8))

  /** `fromJSString`: one `Char` per byte (`Types.hs:90-91`). */
  def string(bytes: Bytes): String = String(bytes.toArray, StandardCharsets.ISO_8859_1)

  /** An object or array not yet closed, with what it has so far. */
  private enum Frame:
    case InArray(items: Vector[CanonicalJson])
    case InObject(fields: Vector[(Bytes, CanonicalJson)], key: Bytes)

  /** Where the parse is: a value starts at `i`; a value ended and the next token is at `i`; a key
    * of an object with `fields` so far starts at `i`.
    */
  private enum At:
    case Value(i: Int)
    case Done(value: CanonicalJson, i: Int)
    case Key(i: Int, fields: Vector[(Bytes, CanonicalJson)])

  /** `parseCanonicalJSON` (`Text/JSON/Canonical/Parse.hs`): Parsec over the bytes, one `Char` each.
    * Whitespace (`spaces`, `isSpace` of a Latin-1 `Char`: space, `\t` to `\r`, `0xa0`) is taken
    * before the value and after every token. A string is any bytes but `"` and `\` between quotes,
    * the escapes `\"` and `\\` only; a number is `0`, or an optional `-` and up to 15 digits not
    * starting with `0`; `true`, `false`, `null`; arrays and objects with no trailing comma. What
    * follows the value is not read. Nesting is walked on the heap.
    */
  def parse(input: Bytes): Either[ParseError, CanonicalJson] =
    val size = input.size
    def at(i: Int): Int = if i < size then input.byteOr(i)(-1)(_ & 0xff) else -1
    def isSpace(b: Int): Boolean = b == 0x20 || (b >= 0x09 && b <= 0x0d) || b == 0xa0
    @tailrec def spaces(i: Int): Int = if isSpace(at(i)) then spaces(i + 1) else i
    def isDigit(b: Int): Boolean = b >= Zero && b <= Zero + 9

    // the bytes of the string whose opening quote is at `i`, and the offset after its closing one
    @tailrec def chars(i: Int, from: Int, parts: Vector[Bytes]): Either[ParseError, (Bytes, Int)] =
      at(i) match
        case -1    => Left(ParseError(size))
        case Quote => Right((concat(parts :+ input.slice(from, i)), i + 1))
        case Backslash =>
          at(i + 1) match
            case Quote | Backslash => chars(i + 2, i + 1, parts :+ input.slice(from, i))
            case _                 => Left(ParseError(i + 1))
        case _ => chars(i + 1, from, parts)

    @tailrec def digits(i: Int, left: Int, acc: Long): (Long, Int) =
      if left > 0 && isDigit(at(i)) then digits(i + 1, left - 1, acc * 10 + (at(i) - Zero))
      else (acc, i)

    def number(i: Int): Either[ParseError, (Long, Int)] =
      val negative = at(i) == Minus
      val start = if negative then i + 1 else i
      at(start) match
        case Zero if !negative => Right((0L, start + 1))
        case d if isDigit(d) && d != Zero =>
          val (n, end) = digits(start + 1, 14, d - Zero)
          Right((if negative then -n else n, end))
        case _ => Left(ParseError(start))

    def literal(i: Int, word: String, value: CanonicalJson): Either[ParseError, At] =
      val matched = word.indices.forall(k => at(i + k) == word(k).toInt)
      Either.cond(matched, At.Done(value, spaces(i + word.length)), ParseError(i))

    @tailrec def go(position: At, stack: List[Frame]): Either[ParseError, CanonicalJson] =
      position match
        case At.Value(i) =>
          val next: Either[ParseError, (At, List[Frame])] = at(i) match
            case OpenBrace =>
              val j = spaces(i + 1)
              if at(j) == CloseBrace then Right((At.Done(Obj(Vector.empty), spaces(j + 1)), stack))
              else Right((At.Key(j, Vector.empty), stack))
            case OpenBracket =>
              val j = spaces(i + 1)
              if at(j) == CloseBracket then
                Right((At.Done(Arr(Vector.empty), spaces(j + 1)), stack))
              else Right((At.Value(j), Frame.InArray(Vector.empty) :: stack))
            case Quote =>
              chars(i + 1, i + 1, Vector.empty).map((s, j) => (At.Done(Str(s), spaces(j)), stack))
            case b if b == 't'.toInt => literal(i, "true", Bool(true)).map((_, stack))
            case b if b == 'f'.toInt => literal(i, "false", Bool(false)).map((_, stack))
            case b if b == 'n'.toInt => literal(i, "null", Null).map((_, stack))
            case b if b == Minus || isDigit(b) =>
              number(i).map((n, j) => (At.Done(Num(n), spaces(j)), stack))
            case _ => Left(ParseError(i.min(size)))
          next match
            case Left(e)              => Left(e)
            case Right((p, newStack)) => go(p, newStack)
        case At.Done(value, i) =>
          stack match
            case Nil => Right(value)
            case Frame.InArray(items) :: below =>
              at(i) match
                case Comma => go(At.Value(spaces(i + 1)), Frame.InArray(items :+ value) :: below)
                case CloseBracket => go(At.Done(Arr(items :+ value), spaces(i + 1)), below)
                case _            => Left(ParseError(i))
            case Frame.InObject(fields, key) :: below =>
              val all = fields :+ (key -> value)
              at(i) match
                case Comma      => go(At.Key(spaces(i + 1), all), below)
                case CloseBrace => go(At.Done(Obj(all), spaces(i + 1)), below)
                case _          => Left(ParseError(i))
        case At.Key(i, fields) =>
          if at(i) != Quote then Left(ParseError(i))
          else
            chars(i + 1, i + 1, Vector.empty) match
              case Left(e) => Left(e)
              case Right((key, j)) =>
                val colon = spaces(j)
                if at(colon) != Colon then Left(ParseError(colon))
                else go(At.Value(spaces(colon + 1)), Frame.InObject(fields, key) :: stack)

    go(At.Value(spaces(0)), Nil)

  /** `renderCanonicalJSON` (`Parse.hs`): no whitespace, a string's bytes with `"` and `\` escaped,
    * an object's pairs sorted by key (`sortBy`, stable: a repeated key keeps its order and is
    * written each time). The Byron genesis hash is the hash of this (`Data.hs:179`).
    */
  def render(value: CanonicalJson): Bytes =
    val quote = Bytes.fromArray(Array(Quote.toByte))
    def text(t: String): Bytes = bytesOf(t)
    def str(b: Bytes): Bytes =
      val escaped = b.toArray.iterator.flatMap { byte =>
        if byte == Quote || byte == Backslash then Iterator(Backslash.toByte, byte)
        else Iterator(byte)
      }
      concat(Vector(quote, Bytes.wrap(escaped.toArray), quote))
    // the parts of a container, its separators between them, ahead of what was still to do
    def around(open: String, parts: Vector[Vector[Work]], close: String, rest: List[Work]) =
      (Work.Emit(text(open)) :: parts.zipWithIndex.toList.flatMap { (p, k) =>
        if k == 0 then p.toList else Work.Emit(text(",")) :: p.toList
      }) ::: (Work.Emit(text(close)) :: rest)
    // the bytes are emitted in order on an explicit work list, so the walk is linear in the output
    @tailrec def go(work: List[Work], out: List[Bytes]): List[Bytes] = work match
      case Nil                      => out
      case Work.Emit(bytes) :: rest => go(rest, bytes :: out)
      case Work.Visit(v) :: rest =>
        v match
          case Null       => go(rest, text("null") :: out)
          case Bool(b)    => go(rest, text(b.toString) :: out)
          case Num(n)     => go(rest, text(n.toString) :: out)
          case Str(b)     => go(rest, str(b) :: out)
          case Arr(items) => go(around("[", items.map(i => Vector(Work.Visit(i))), "]", rest), out)
          case Obj(fields) =>
            val sorted = fields.sortBy(_._1)
            val parts = sorted.map((k, v) => Vector(Work.Emit(str(k) ++ text(":")), Work.Visit(v)))
            go(around("{", parts, "}", rest), out)
    concat(go(List(Work.Visit(value)), Nil).reverse.toVector)

  /** What [[render]] has still to do: bytes to write, or a value to render. */
  private enum Work:
    case Emit(bytes: Bytes)
    case Visit(value: CanonicalJson)

  /** The value as circe holds it, for the readers that look fields up: a string as `fromJSString`
    * gives it, and of a repeated key the first value, as `fromJSField` finds it.
    */
  def toCirce(value: CanonicalJson): Json =
    TreeFold.fold[CanonicalJson, Json](value) {
      case Null       => TreeFold.Step.Leaf(Json.Null)
      case Bool(b)    => TreeFold.Step.Leaf(Json.fromBoolean(b))
      case Num(n)     => TreeFold.Step.Leaf(Json.fromLong(n))
      case Str(b)     => TreeFold.Step.Leaf(Json.fromString(string(b)))
      case Arr(items) => TreeFold.Step.Branch(items, vs => Right(Json.fromValues(vs)))
      case Obj(fields) =>
        val keys = fields.map((k, _) => string(k))
        TreeFold.Step.Branch(
          fields.map(_._2),
          vs =>
            Right(
              Json.fromFields(
                keys
                  .zip(vs)
                  .foldLeft((Set.empty[String], Vector.empty[(String, Json)])) {
                    case ((seen, kept), (k, v)) =>
                      if seen.contains(k) then (seen, kept) else (seen + k, kept :+ (k -> v))
                  }
                  ._2
              )
            )
        )
    }
