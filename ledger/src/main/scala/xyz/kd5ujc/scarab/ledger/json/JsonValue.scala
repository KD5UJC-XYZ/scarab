// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.util.hashing.MurmurHash3

/** aeson 2.2.5.0 `Value` (`Data/Aeson/Types/Internal.hs:366-372`), the result of the reference's
  * JSON readers. Equality is the derived `Eq`, with numbers compared as `Scientific` compares them.
  * Outside data nests without a depth limit, so equality, hashing and `toString` walk an explicit
  * stack instead of the recursive case-class members (ADR 0011).
  */
enum JsonValue:
  case Object(fields: JsonObject)
  case Array(items: Vector[JsonValue])
  case String(text: Predef.String)
  case Number(value: Scientific)
  case Bool(value: Boolean)
  case Null

  override def equals(other: Any): Boolean = other match
    case that: JsonValue => JsonValue.same(List(this -> that))
    case _               => false

  override def hashCode: Int = JsonValue.hash(List(this), MurmurHash3.seqSeed, 0)

  override def toString: Predef.String = JsonValue.render(this)

object JsonValue:
  @tailrec private def same(pairs: List[(JsonValue, JsonValue)]): Boolean = pairs match
    case Nil => true
    // A `Null` pattern would call `equals` again, so constructors are compared by ordinal and the
    // remaining case is a pair of `Null`s.
    case pair :: _ if pair._1.ordinal != pair._2.ordinal => false
    case pair :: rest =>
      pair match
        case (Object(a), Object(b)) =>
          val (x, y) = (a.toVector, b.toVector)
          x.size == y.size && x.lazyZip(y).forall((p, q) => p._1 == q._1) &&
          same(x.lazyZip(y).map((p, q) => p._2 -> q._2).toList ::: rest)
        case (Array(a), Array(b))   => a.size == b.size && same(a.lazyZip(b).toList ::: rest)
        case (String(a), String(b)) => a == b && same(rest)
        case (Number(a), Number(b)) => a == b && same(rest)
        case (Bool(a), Bool(b))     => a == b && same(rest)
        case _                      => same(rest)

  @tailrec private def hash(todo: List[JsonValue], h: Int, n: Int): Int = todo match
    case Nil => MurmurHash3.finalizeHash(h, n)
    case value :: rest =>
      def mix(tag: Int, part: Int) = MurmurHash3.mix(MurmurHash3.mix(h, tag), part)
      value match
        case Object(fields) =>
          val pairs = fields.toVector
          hash(pairs.map(_._2).toList ::: rest, mix(0, pairs.map(_._1).hashCode), n + 1)
        case Array(items) => hash(items.toList ::: rest, mix(1, items.size), n + 1)
        case String(text) => hash(rest, mix(2, text.hashCode), n + 1)
        case Number(v)    => hash(rest, mix(3, v.hashCode), n + 1)
        case Bool(v)      => hash(rest, mix(4, v.hashCode), n + 1)
        case Null         => hash(rest, mix(5, 0), n + 1)

  private enum Piece:
    case Value(value: JsonValue)
    case Text(text: Predef.String)

  // Compact JSON text for diagnostics; a number is written as coefficient and exponent.
  private def render(value: JsonValue): Predef.String =
    def quoted(text: Predef.String) =
      "\"" + text.flatMap {
        case '"'          => "\\\""
        case '\\'         => "\\\\"
        case c if c < ' ' => f"\\u${c.toInt}%04x"
        case c            => c.toString
      } + "\""
    def separated(parts: Vector[List[Piece]]): List[Piece] =
      parts.zipWithIndex.toList.flatMap((p, i) => if i == 0 then p else Piece.Text(",") :: p)
    @tailrec def loop(todo: List[Piece], out: StringBuilder): Predef.String = todo match
      case Nil                      => out.result()
      case Piece.Text(text) :: rest => loop(rest, out.append(text))
      case Piece.Value(v) :: rest =>
        v match
          case Object(fields) =>
            val body = separated(
              fields.toVector.map((k, x) => List(Piece.Text(quoted(k) + ":"), Piece.Value(x)))
            )
            loop(Piece.Text("{") :: body ::: Piece.Text("}") :: rest, out)
          case Array(items) =>
            loop(
              Piece.Text("[") :: separated(items.map(x => List(Piece.Value(x)))) ::: Piece.Text(
                "]"
              ) :: rest,
              out
            )
          case String(text) => loop(rest, out.append(quoted(text)))
          case Number(n) =>
            loop(rest, out.append(n.coefficient).append('e').append(n.base10Exponent))
          case Bool(b) => loop(rest, out.append(b))
          case Null    => loop(rest, out.append("null"))
    loop(List(Piece.Value(value)), new StringBuilder)

/** aeson `KeyMap Value` built with `ordered-keymap` (`Data/Aeson/KeyMap.hs:136-143`): a `Map` keyed
  * by `Key`, ordered as `Text` (`Data/Aeson/Key.hs:91`). text 2 compares UTF-8 bytes, which is
  * Unicode code point order, not the JVM's UTF-16 code unit order.
  */
opaque type JsonObject = TreeMap[String, JsonValue]

object JsonObject:
  private object TextOrder extends Ordering[String]:
    def compare(a: String, b: String): Int =
      @tailrec def loop(i: Int, j: Int): Int =
        if i == a.length then if j == b.length then 0 else -1
        else if j == b.length then 1
        else
          val (x, y) = (a.codePointAt(i), b.codePointAt(j))
          if x != y then Integer.compare(x, y)
          else loop(i + Character.charCount(x), j + Character.charCount(y))
      loop(0, 0)

  val empty: JsonObject = TreeMap.empty(using TextOrder)

  /** `KeyMap.fromList`: when a key repeats, its last pair wins. */
  def fromList(pairs: IterableOnce[(String, JsonValue)]): JsonObject =
    TreeMap.from(pairs)(using TextOrder)

  extension (o: JsonObject)
    def get(key: String): Option[JsonValue] = o.get(key)
    def size: Int = o.size

    /** `KeyMap.toList`: the pairs in key order. */
    def toVector: Vector[(String, JsonValue)] = o.toVector
