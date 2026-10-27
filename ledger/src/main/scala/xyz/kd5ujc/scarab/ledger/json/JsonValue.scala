// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.{Order, Show}
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.util.hashing.MurmurHash3

/** aeson 2.2.5.0 `Value` (`Data/Aeson/Types/Internal.hs:366-372`), the result of the reference's
  * JSON readers. Equality is the derived `Eq` and ordering the derived `Ord` (`494`), with numbers
  * compared as `Scientific` compares them; `toString` is its `Show` (`382-395`). Outside data nests
  * without a depth limit, so equality, ordering, hashing and `toString` walk an explicit stack
  * instead of the recursive case-class members (ADR 0011).
  */
enum JsonValue:
  case Object(fields: JsonObject)
  case Array(items: Vector[JsonValue])
  case String(text: JsonText)
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

  // Pending comparisons of the derived `Ord`, in order: the first that is not equal decides.
  private enum Compare:
    case Values(a: JsonValue, b: JsonValue)
    case Keys(a: JsonKey, b: JsonKey)
    case Sizes(a: Int, b: Int)

  /** The derived `Ord Value` (`Data/Aeson/Types/Internal.hs:494`): constructors in declaration
    * order, then their fields. An object compares as containers 0.7's `Ord (Map k v)`
    * (`Data/Map/Internal.hs:4232-4233`), the lists of its pairs in key order; an array as vector
    * 0.13.2.0's `Ord (Vector a)` (`Data/Vector.hs:294-296`); both are list orders, where a proper
    * prefix comes first. Strings and keys compare as `Text` and numbers as `Scientific`.
    */
  private def compare(a: JsonValue, b: JsonValue): Int =
    @tailrec def loop(todo: List[Compare]): Int = todo match
      case Nil => 0
      case Compare.Sizes(x, y) :: rest =>
        val c = Integer.compare(x, y)
        if c != 0 then c else loop(rest)
      case Compare.Keys(x, y) :: rest =>
        val c = JsonKey.order.compare(x, y)
        if c != 0 then c else loop(rest)
      case Compare.Values(x, y) :: _ if x.ordinal != y.ordinal =>
        Integer.compare(x.ordinal, y.ordinal)
      case Compare.Values(x, y) :: rest =>
        (x, y) match
          case (Object(p), Object(q)) =>
            val (xs, ys) = (p.toVector, q.toVector)
            val pairs = xs.lazyZip(ys).toList.flatMap { (l, r) =>
              List(Compare.Keys(l._1, r._1), Compare.Values(l._2, r._2))
            }
            loop(pairs ::: Compare.Sizes(xs.size, ys.size) :: rest)
          case (Array(p), Array(q)) =>
            val pairs = p.lazyZip(q).toList.map(Compare.Values.apply)
            loop(pairs ::: Compare.Sizes(p.size, q.size) :: rest)
          case (String(p), String(q)) =>
            val c = JsonText.order.compare(p, q)
            if c != 0 then c else loop(rest)
          case (Number(p), Number(q)) =>
            val c = Order[Scientific].compare(p, q)
            if c != 0 then c else loop(rest)
          case (Bool(p), Bool(q)) =>
            val c = java.lang.Boolean.compare(p, q)
            if c != 0 then c else loop(rest)
          case _ => loop(rest)
    loop(List(Compare.Values(a, b)))

  given Order[JsonValue] = Order.from(compare)
  given Ordering[JsonValue] = Order[JsonValue].toOrdering
  given Show[JsonValue] = Show.fromToString

  private enum Piece:
    case Value(value: JsonValue, precedence: Int)
    case Text(text: Predef.String)

  /** `instance Show Value` (`Data/Aeson/Types/Internal.hs:382-395`). An array shows as vector's
    * `showsPrec _ = shows . toList` (`Data/Vector/Generic.hs:2680-2682`) and an object's
    * `toAscList` as a list of pairs; list elements and pair components show at precedence 0
    * (`showList__` and `show_tuple`, `GHC/Internal/Show.hs:151-156,335-338`), so only a negative
    * number's own parentheses appear inside a value.
    */
  private def render(value: JsonValue): Predef.String =
    def listOf(items: Vector[List[Piece]]): List[Piece] =
      if items.isEmpty then List(Piece.Text("[]"))
      else
        val body =
          items.zipWithIndex.toList.flatMap((p, i) => if i == 0 then p else Piece.Text(",") :: p)
        Piece.Text("[") :: body ::: List(Piece.Text("]"))
    def applied(d: Int, name: Predef.String, argument: List[Piece]): List[Piece] =
      if d > 10 then Piece.Text("(" + name) :: argument ::: List(Piece.Text(")"))
      else Piece.Text(name) :: argument
    @tailrec def loop(todo: List[Piece], out: StringBuilder): Predef.String = todo match
      case Nil                      => out.result()
      case Piece.Text(text) :: rest => loop(rest, out.append(text))
      case Piece.Value(v, d) :: rest =>
        v match
          case Object(fields) =>
            val pairs = fields.toVector.map { (k, x) =>
              List(
                Piece.Text("(" + HaskellShow.text(k.text.asString) + ","),
                Piece.Value(x, 0),
                Piece.Text(")")
              )
            }
            loop(
              applied(d, "Object (fromList ", listOf(pairs) ::: List(Piece.Text(")"))) ::: rest,
              out
            )
          case Array(items) =>
            loop(
              applied(d, "Array ", listOf(items.map(x => List(Piece.Value(x, 0))))) ::: rest,
              out
            )
          case String(text) =>
            loop(
              applied(d, "String ", List(Piece.Text(HaskellShow.text(text.asString)))) ::: rest,
              out
            )
          case Number(n) =>
            val shown = new StringBuilder
            n.showsPrec(11, shown)
            loop(applied(d, "Number ", List(Piece.Text(shown.result()))) ::: rest, out)
          case Bool(b) =>
            loop(applied(d, "Bool ", List(Piece.Text(if b then "True" else "False"))) ::: rest, out)
          case Null => loop(rest, out.append("Null"))
    loop(List(Piece.Value(value, 0)), new StringBuilder)

/** text 2.1.3 `Text`: Unicode scalar values only, ordered by code point. */
opaque type JsonText = String

object JsonText:
  /** text 2.1.3 `pack` (`Data/Text/Internal.hs:245-272`), which writes `safe c`
    * (`Data/Text/Internal.hs:146-149`) for each `Char`: a surrogate code point becomes U+FFFD. The
    * `Char` s of a JVM string are its code points, a paired surrogate being one supplementary code
    * point, so each unpaired surrogate is replaced.
    */
  def pack(text: String): JsonText =
    def safe(c: Int): Int = if (c & 0x1ff800) != 0xd800 then c else 0xfffd
    if text.codePoints.allMatch(c => safe(c) == c) then text
    else
      val points = text.codePoints.map(safe).toArray
      new String(points, 0, points.length)

  /** text 2.1.3 `compareText` (`Data/Text.hs:496-498`) compares UTF-8 bytes, which is Unicode code
    * point order, not the JVM's UTF-16 code unit order.
    */
  private def textOrder(a: String, b: String): Int =
    @tailrec def loop(i: Int, j: Int): Int =
      if i == a.length then if j == b.length then 0 else -1
      else if j == b.length then 1
      else
        val (x, y) = (a.codePointAt(i), b.codePointAt(j))
        if x != y then Integer.compare(x, y)
        else loop(i + Character.charCount(x), j + Character.charCount(y))
    loop(0, 0)

  given order: Order[JsonText] = Order.from(textOrder)
  given Ordering[JsonText] = order.toOrdering

  extension (t: JsonText) def asString: String = t

/** aeson `Key` (`Data/Aeson/Key.hs:44`), a newtype of `Text`. */
opaque type JsonKey = JsonText

object JsonKey:
  /** `Key.fromText` (`Data/Aeson/Key.hs:53-54`). */
  def fromText(text: JsonText): JsonKey = text

  /** `Key.fromString` (`Data/Aeson/Key.hs:47-48`): `Key . T.pack`. */
  def fromString(text: String): JsonKey = JsonText.pack(text)

  /** `deriving newtype instance Ord Key` (`Data/Aeson/Key.hs:91`): `Text` order. */
  given order: Order[JsonKey] = JsonText.order
  given Ordering[JsonKey] = order.toOrdering

  extension (k: JsonKey) def text: JsonText = k

/** aeson `KeyMap Value` built with `ordered-keymap` (`Data/Aeson/KeyMap.hs:143-144`): a `Map` keyed
  * by `Key` in `Text` order.
  */
opaque type JsonObject = TreeMap[JsonKey, JsonValue]

object JsonObject:
  /** `KeyMap.fromList` (`Data/Aeson/KeyMap.hs:246-247`): when a key repeats, its last pair wins. */
  def fromList(pairs: IterableOnce[(JsonKey, JsonValue)]): JsonObject =
    TreeMap.from(pairs)(using JsonKey.order.toOrdering)

  extension (o: JsonObject)
    def get(key: JsonKey): Option[JsonValue] = o.get(key)
    def size: Int = o.size

    /** `KeyMap.toList`: the pairs in key order. */
    def toVector: Vector[(JsonKey, JsonValue)] = o.toVector
