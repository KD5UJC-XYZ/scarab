// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import cats.syntax.all.*

import DecodeError.{Arity, Kind}

/** A CBOR codec for one model type. Decoding works on the byte-preserving `Node` tree of
  * `xyz.kd5ujc.scarab.codec` and reports typed, located errors. Structured writers use shortest
  * forms, definite containers and schema map order; CDDL maps do not require key order. Opaque
  * nodes and declared-preserved values retain original bytes, and byte strings may require chunks.
  */
trait Codec[A]:
  def decode(node: Node): Decoded[A]
  def encode(a: A, out: Writer): Unit

  /** The constraint `a` violates, if it violates this codec's value constraint (used by smart
    * constructors, and by writers of formats that refuse a value they cannot write).
    */
  def check(a: A): Option[ConstraintViolation] = None

  /** Decode a complete CBOR item: syntax errors, trailing bytes and decoder exceptions are errors,
    * never thrown.
    */
  final def decodeBytes(bytes: Bytes, limits: Cbor.Limits = Codec.DefaultLimits): Decoded[A] =
    Cbor.decode(bytes, limits) match
      case Left(message) => Left(DecodeError(Kind.Syntax(message), Nil, 0, rooted = true))
      case Right(root) =>
        Either
          .catchNonFatal(decode(root).left.map(_.rebased(root.offset)))
          .leftMap(e => DecodeError(Kind.Internal(e), Nil, 0, rooted = true))
          .flatten

  /** The bytes of `a`, or the constraint it violates: a value that [[check]] refuses is never
    * written. This is the way to write a value of unknown origin.
    */
  final def write(a: A): Either[ConstraintViolation, Bytes] =
    check(a).toLeft(encodeUnchecked(a))

  /** The bytes of `a`, without [[check]]. Only for a value that is writable by construction (the
    * decoded, or generated, or a literal) and for tests of the writer itself; [[write]] is the
    * checked one.
    */
  final def encodeUnchecked(a: A): Bytes =
    val w = new Writer
    encode(a, w)
    w.result

object Codec:
  // Generic CDDL `any` includes floating and simple values; wire decoders choose their own policy.
  val DefaultLimits: Cbor.Limits = Cbor.Limits(allowFloats = true, allowSimpleValues = true)

  /** The violation of `constraint` by `actual`, when `violated`. */
  def refuse(
      violated: Boolean,
      typeName: String,
      constraint: => String,
      actual: Any
  ): Option[ConstraintViolation] =
    Option.when(violated)(ConstraintViolation(typeName, constraint, String.valueOf(actual)))

  def describe(n: Node): String = n.value match
    case Value.UInt(_)       => "unsigned integer"
    case Value.NInt(_)       => "negative integer"
    case Value.ByteString(_) => "byte string"
    case Value.Text(_)       => "text string"
    case Value.Arr(_)        => "array"
    case Value.Map(_)        => "map"
    case Value.Tag(t, _)     => s"tag $t"
    case Value.Bool(_)       => "boolean"
    case Value.Null          => "null"
    case Value.Simple(n)     => s"simple value $n"
    case Value.Float(_, w)   => s"float ($w)"

  def mismatch(expected: String, n: Node): DecodeError =
    DecodeError.at(n, Kind.TypeMismatch(expected, describe(n)))

  // Leaves ----------------------------------------------------------------------------------

  /** An unsigned integer of any size. */
  val uint: Codec[BigInt] = new Codec[BigInt]:
    def decode(n: Node) = n.value match
      case Value.UInt(v) => Right(v)
      case _             => Left(mismatch("unsigned integer", n))
    def encode(a: BigInt, out: Writer) = out.uint(a)
    override def check(a: BigInt) = refuse(a.signum < 0, "unsigned integer", "0 <= x", a)

  /** An integer of either sign. */
  val int: Codec[BigInt] = new Codec[BigInt]:
    def decode(n: Node) = n.value match
      case Value.UInt(v) => Right(v)
      case Value.NInt(v) => Right(v)
      case _             => Left(mismatch("integer", n))
    def encode(a: BigInt, out: Writer) = out.int(a)

  /** An integer in `lo..hi`, as an `Int`. */
  def intRange(lo: Int, hi: Int): Codec[Int] = new Codec[Int]:
    private val what = s"$lo..$hi"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v <= hi && v >= lo => Right(v.toInt)
      case Value.NInt(v) if v >= lo && v <= hi => Right(v.toInt)
      case Value.UInt(v)                       => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case Value.NInt(v)                       => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                                   => Left(mismatch("integer", n))
    def encode(a: Int, out: Writer) = out.int(BigInt(a))
    override def check(a: Int) = refuse(a < lo || a > hi, "integer", what, a)

  /** An integer that is one of `values`, as an `Int`. */
  def intIn(values: Int*): Codec[Int] = new Codec[Int]:
    private val set = values.toSet
    private val what = values.mkString(" / ")
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v.isValidInt && set(v.toInt) => Right(v.toInt)
      case Value.UInt(v) => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _             => Left(mismatch("unsigned integer", n))
    def encode(a: Int, out: Writer) = out.uint(a.toLong)
    override def check(a: Int) = refuse(!set(a), "integer", what, a)

  /** An unsigned integer up to `max`, as an `Int`, `Long` or `BigInt` chosen by the generator. */
  def uintInt(max: Int): Codec[Int] = intRange(0, max)

  def uintLong(max: Long): Codec[Long] = new Codec[Long]:
    private val what = s"0..$max"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v <= max => Right(v.toLong)
      case Value.UInt(v)             => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                         => Left(mismatch("unsigned integer", n))
    def encode(a: Long, out: Writer) = out.uint(a)
    override def check(a: Long) = refuse(a < 0 || a > max, "unsigned integer", what, a)

  /** An integer in `lo..hi` as a `Long` (signed range; negative values are CBOR negative integers).
    */
  def longRange(lo: Long, hi: Long): Codec[Long] = new Codec[Long]:
    private val what = s"$lo..$hi"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v >= lo && v <= hi => Right(v.toLong)
      case Value.NInt(v) if v >= lo && v <= hi => Right(v.toLong)
      case Value.UInt(v)                       => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case Value.NInt(v)                       => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                                   => Left(mismatch("integer", n))
    def encode(a: Long, out: Writer) = out.int(BigInt(a))
    override def check(a: Long) = refuse(a < lo || a > hi, "integer", what, a)

  /** An integer in `lo..hi` as a `BigInt`. */
  def bigRange(lo: BigInt, hi: BigInt): Codec[BigInt] = new Codec[BigInt]:
    private val what = s"$lo..$hi"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v >= lo && v <= hi => Right(v)
      case Value.NInt(v) if v >= lo && v <= hi => Right(v)
      case Value.UInt(v)                       => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case Value.NInt(v)                       => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                                   => Left(mismatch("integer", n))
    def encode(a: BigInt, out: Writer) = out.int(a)
    override def check(a: BigInt) = refuse(a < lo || a > hi, "integer", what, a)

  def uintBig(max: BigInt): Codec[BigInt] = new Codec[BigInt]:
    private val what = s"0..$max"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v <= max => Right(v)
      case Value.UInt(v)             => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                         => Left(mismatch("unsigned integer", n))
    def encode(a: BigInt, out: Writer) = out.uint(a)
    override def check(a: BigInt) = refuse(a.signum < 0 || a > max, "unsigned integer", what, a)

  val bool: Codec[Boolean] = new Codec[Boolean]:
    def decode(n: Node) = n.value match
      case Value.Bool(b) => Right(b)
      case _             => Left(mismatch("boolean", n))
    def encode(a: Boolean, out: Writer) = out.bool(a)

  val text: Codec[String] = textSized(0, Int.MaxValue)

  def textSized(lo: Int, hi: Int): Codec[String] = new Codec[String]:
    private val what = s"$lo..$hi"
    def decode(n: Node) = n.value match
      case Value.Text(s) =>
        val len = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        if len >= lo && len <= hi then Right(s)
        else Left(DecodeError.at(n, Kind.BadSize(what, len.toLong)))
      case _ => Left(mismatch("text string", n))
    def encode(a: String, out: Writer) = out.text(a)
    override def check(a: String) =
      val len = a.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      refuse(len < lo || len > hi, "text string", s"size $what", a)

  val bytes: Codec[Bytes] = bytesSized(0, Int.MaxValue)

  /** A byte string whose definite chunks each fit `limit`, matching Plutus `decodeData`. The syntax
    * decoder has already checked framing; this check uses the original chunk heads.
    */
  def chunkedBytes(limit: Int): Codec[Bytes] = new Codec[Bytes]:
    def decode(n: Node): Decoded[Bytes] = n.value match
      case Value.ByteString(value) if xyz.kd5ujc.scarab.codec.ByteStringChunks.within(n, limit) =>
        Right(value)
      case Value.ByteString(value) =>
        Left(DecodeError.at(n, Kind.BadSize(s"chunks <= $limit", value.size.toLong)))
      case _ => Left(mismatch("byte string", n))

    def encode(value: Bytes, out: Writer): Unit =
      if value.size <= limit then out.bytes(value)
      else
        out.raw(xyz.kd5ujc.scarab.codec.Bytes(Vector(0x5f.toByte)))
        (0 until value.size by limit).foreach(at =>
          out.bytes(value.slice(at, (at + limit).min(value.size)))
        )
        out.break()

  def bytesSized(lo: Int, hi: Int): Codec[Bytes] = new Codec[Bytes]:
    private val what = if lo == hi then lo.toString else s"$lo..$hi"
    def decode(n: Node) = n.value match
      case Value.ByteString(b) =>
        if b.size >= lo && b.size <= hi then Right(b)
        else Left(DecodeError.at(n, Kind.BadSize(what, b.size.toLong)))
      case _ => Left(mismatch("byte string", n))
    def encode(a: Bytes, out: Writer) = out.bytes(a)
    override def check(a: Bytes) =
      refuse(a.size < lo || a.size > hi, "byte string", s"size $what", a)

  /** `any`: the item as it is on the wire, original bytes included. */
  val any: Codec[Node] = new Codec[Node]:
    def decode(n: Node) = Right(n)
    def encode(a: Node, out: Writer) = out.raw(a.original)

  /** `decode` of each of `items` with its index, in order; the first failure ends it. The results
    * are put in front of a list and turned around once: appending each to a vector copies the
    * vector's last block every time (`docs/performance.md` section 47).
    */
  private[runtime] def each[A, B](items: Vector[A])(
      decode: (A, Int) => Decoded[B]
  ): Decoded[Vector[B]] =
    @scala.annotation.tailrec
    def go(i: Int, out: List[B]): Decoded[Vector[B]] =
      if i == items.size then Right(out.reverseIterator.toVector)
      else
        decode(items(i), i) match
          case Right(b) => go(i + 1, b :: out)
          case Left(e)  => Left(e)
    go(0, Nil)

  // Combinators -----------------------------------------------------------------------------

  /** `[* x]`, `[+ x]`, `[n*m x]`. */
  def vec[A](inner: => Codec[A], min: Int = 0, max: Int = Int.MaxValue): Codec[Vector[A]] =
    new Codec[Vector[A]]:
      private lazy val c = inner
      def decode(n: Node) = n.value match
        case Value.Arr(items) =>
          if items.size < min || items.size > max then
            Left(
              DecodeError.at(
                n,
                Kind.BadArity(Arity.Range(min, Option.when(max != Int.MaxValue)(max)), items.size)
              )
            )
          else Codec.each(items)((item, i) => c.decode(item).left.map(_.at(s"[$i]")))
        case _ => Left(mismatch("array", n))
      def encode(a: Vector[A], out: Writer) =
        out.arrayHead(a.size)
        a.foreach(c.encode(_, out))

  /** `x / nil`: CBOR null is `None`. */
  def nullable[A](inner: => Codec[A]): Codec[Option[A]] = new Codec[Option[A]]:
    private lazy val c = inner
    def decode(n: Node) = n.value match
      case Value.Null => Right(None)
      case _          => c.decode(n).map(Some(_))
    def encode(a: Option[A], out: Writer) = a match
      case None    => out.nul()
      case Some(x) => c.encode(x, out)

  /** `#6.n(x)`. */
  def tagged[A](tag: BigInt, inner: => Codec[A]): Codec[A] = new Codec[A]:
    private lazy val c = inner
    def decode(n: Node) = n.value match
      case Value.Tag(t, v) =>
        if t == tag then c.decode(v) else Left(DecodeError.at(n, Kind.BadTag(tag, t)))
      case _ => Left(mismatch(s"tag $tag", n))
    def encode(a: A, out: Writer) =
      out.tag(tag)
      c.encode(a, out)
    override def check(a: A) = c.check(a)

  /** `#6.n(x) / x`: the tag is optional on input (Conway sets and non-empty lists), written on
    * output.
    */
  def maybeTagged[A](tag: BigInt, inner: => Codec[A]): Codec[A] = new Codec[A]:
    private lazy val c = inner
    def decode(n: Node) = n.value match
      case Value.Tag(t, v) if t == tag => c.decode(v)
      case _                           => c.decode(n)
    def encode(a: A, out: Writer) =
      out.tag(tag)
      c.encode(a, out)
    override def check(a: A) = c.check(a)

  /** `bytes .cbor x`: a byte string whose content is the encoding of `x`. */
  def embedded[A](inner: => Codec[A]): Codec[A] = new Codec[A]:
    private lazy val c = inner
    def decode(n: Node) = n.value match
      case Value.ByteString(b) =>
        Cbor.decode(b, DefaultLimits) match
          case Left(message) => Left(DecodeError.at(n, Kind.Syntax(message)).at("<cbor>"))
          case Right(root) =>
            c.decode(root) match
              case Left(e)  => Left(e.rebased(root.offset).at("<cbor>"))
              case Right(a) => Right(a)
      case _ => Left(mismatch("byte string (embedded CBOR)", n))
    def encode(a: A, out: Writer) =
      val w = new Writer
      c.encode(a, w)
      out.bytes(w.result)

  /** `{ * k => v }`: all pairs, in wire order (duplicates kept). */
  def pairs[K, V](
      key: => Codec[K],
      value: => Codec[V],
      min: Int = 0,
      max: Int = Int.MaxValue
  ): Codec[Vector[(K, V)]] =
    new Codec[Vector[(K, V)]]:
      private lazy val kc = key
      private lazy val vc = value
      def decode(n: Node) = Maps.pairs(n).flatMap { ps =>
        if ps.size < min || ps.size > max then
          Left(
            DecodeError.at(
              n,
              Kind.BadArity(Arity.Range(min, Option.when(max != Int.MaxValue)(max)), ps.size)
            )
          )
        else Maps.decodePairs(ps, kc, vc)
      }
      def encode(a: Vector[(K, V)], out: Writer) =
        out.mapHead(a.size)
        a.foreach { (k, v) =>
          kc.encode(k, out); vc.encode(v, out)
        }

private val ok: Decoded[Unit] = Right(())

/** Checks of the literal elements of arrays (`[2, header, tip]`). */
object Lit:
  def uint(n: Node, expected: Long): Decoded[Unit] = n.value match
    case Value.UInt(v) if v == expected => ok
    case Value.UInt(v) =>
      Left(DecodeError.at(n, Kind.LiteralMismatch(expected.toString, v.toString)))
    case _ => Left(Codec.mismatch("unsigned integer", n))
  def text(n: Node, expected: String): Decoded[Unit] = n.value match
    case Value.Text(s) if s == expected => ok
    case Value.Text(s) => Left(DecodeError.at(n, Kind.LiteralMismatch(expected, s)))
    case _             => Left(Codec.mismatch("text string", n))
  def bool(n: Node, expected: Boolean): Decoded[Unit] = n.value match
    case Value.Bool(b) if b == expected => ok
    case _                              => Left(Codec.mismatch(expected.toString, n))
  def nul(n: Node): Decoded[Unit] = n.value match
    case Value.Null => ok
    case _          => Left(Codec.mismatch("null", n))

object Arr:
  def at[A](xs: Vector[Node], position: Int)(decode: Node => Decoded[A]): Decoded[A] =
    xs.lift(position)
      .toRight(
        DecodeError(
          Kind.BadArity(Arity.AtLeast(position + 1), xs.size),
          Nil,
          xs.lastOption.fold(0)(_.offset)
        )
      )
      .flatMap(decode)

  def end(xs: Vector[Node], position: Int): Decoded[Unit] =
    Either.cond(
      position == xs.size,
      (),
      DecodeError(
        Kind.BadArity(Arity.Exactly(position), xs.size),
        Nil,
        xs.lastOption.fold(0)(_.offset)
      )
    )

  /** CDDL occurrences are greedy (RFC 8610, Appendix A): a matching member is consumed even when
    * the enclosing suffix subsequently fails.
    */
  def optional[A, B](xs: Vector[Node], position: Int, codec: Codec[A], path: String)(
      next: (Option[A], Int) => Decoded[B]
  ): Decoded[B] =
    if position >= xs.size then next(None, position)
    else
      at(xs, position)(codec.decode).at(path) match
        case Right(value) => next(Some(value), position + 1)
        case Left(error) =>
          error.terminal.fold(next(None, position))(terminal => Left(terminal.error))

  def exact(n: Node, size: Int): Decoded[Vector[Node]] = n.value match
    case Value.Arr(xs) if xs.size == size => Right(xs)
    case Value.Arr(xs) => Left(DecodeError.at(n, Kind.BadArity(Arity.Exactly(size), xs.size)))
    case _             => Left(Codec.mismatch("array", n))
  def between(n: Node, min: Int, max: Int): Decoded[Vector[Node]] = n.value match
    case Value.Arr(xs) if xs.size >= min && xs.size <= max => Right(xs)
    case Value.Arr(xs) =>
      Left(DecodeError.at(n, Kind.BadArity(Arity.Range(min, Some(max)), xs.size)))
    case _ => Left(Codec.mismatch("array", n))

  /** [[between]] from one item to `max`, as the non-empty vector that carries the minimum. */
  def nonEmpty(n: Node, max: Int): Decoded[NonEmptyVector[Node]] = n.value match
    case Value.Arr(xs) if xs.size <= max =>
      NonEmptyVector
        .fromVector(xs)
        .toRight(DecodeError.at(n, Kind.BadArity(Arity.Range(1, Some(max)), xs.size)))
    case Value.Arr(xs) =>
      Left(DecodeError.at(n, Kind.BadArity(Arity.Range(1, Some(max)), xs.size)))
    case _ => Left(Codec.mismatch("array", n))

sealed trait MapKey
object MapKey:
  final case class IntKey(value: Long) extends MapKey
  final case class TextKey(value: String) extends MapKey

/** The entries of a map struct: literal keys get a slot each, `* k => v` entries collect by key
  * type.
  */
final class MapSpec(val literals: Array[MapKey], val typed: Array[Node => Boolean])

/** A map split by a [[MapSpec]]: the value of each literal key (none when the map does not have
  * it), and the entries of each typed key class in wire order.
  */
final class MapParts(
    val owner: Node,
    val slots: Vector[Option[Node]],
    val typed: Vector[Vector[(Node, Node)]],
    spec: MapSpec
):
  private def name(i: Int): String = spec.literals(i) match
    case MapKey.IntKey(v)  => v.toString
    case MapKey.TextKey(s) => s"\"$s\""

  def required[A](i: Int, codec: Codec[A]): Decoded[A] = slots(i) match
    case None    => Left(DecodeError.at(owner, Kind.MissingKey(name(i))))
    case Some(n) => codec.decode(n).at(name(i))

  def optional[A](i: Int, codec: Codec[A]): Decoded[Option[A]] = slots(i) match
    case None    => Right(None)
    case Some(n) => codec.decode(n).map(Some(_)).at(name(i))

  def entries[K, V](i: Int, key: Codec[K], value: Codec[V]): Decoded[Vector[(K, V)]] =
    Maps.decodePairs(typed(i), key, value)

object Maps:
  def pairs(n: Node): Decoded[Vector[(Node, Node)]] = n.value match
    case Value.Map(ps) => Right(ps)
    case _             => Left(Codec.mismatch("map", n))

  def decodePairs[K, V](
      ps: Vector[(Node, Node)],
      kc: Codec[K],
      vc: Codec[V]
  ): Decoded[Vector[(K, V)]] =
    Codec.each(ps) { case ((k, v), i) =>
      kc.decode(k) match
        case Left(e) => Left(e.at(s"key[$i]"))
        case Right(kk) =>
          vc.decode(v) match
            case Left(e)   => Left(e.at(s"value[$i]"))
            case Right(vv) => Right((kk, vv))
    }

  private def matches(key: MapKey, n: Node): Boolean = (key, n.value) match
    case (MapKey.IntKey(k), Value.UInt(v))  => v == k
    case (MapKey.TextKey(k), Value.Text(s)) => s == k
    case _                                  => false

  /** Distribute the pairs of the map over the spec: literal keys first, then typed entries in
    * order.
    */
  def split(n: Node, spec: MapSpec): Decoded[MapParts] = pairs(n).flatMap { ps =>
    // each pair goes to the first literal key it matches, else to the first typed class that takes
    // it; the typed entries are put in front of their lists and turned around at the end
    @scala.annotation.tailrec
    def go(
        i: Int,
        slots: Vector[Option[Node]],
        typed: Vector[List[(Node, Node)]]
    ): Decoded[MapParts] =
      if i == ps.size then Right(new MapParts(n, slots, typed.map(_.reverse.toVector), spec))
      else
        val (k, v) = ps(i)
        val literal = spec.literals.indexWhere(matches(_, k))
        if literal >= 0 then
          if slots(literal).isDefined then
            Left(DecodeError.at(k, Kind.DuplicateKey(k.original.toString)))
          else go(i + 1, slots.updated(literal, Some(v)), typed)
        else
          val kind = spec.typed.indexWhere(_(k))
          if kind >= 0 then go(i + 1, slots, typed.updated(kind, (k, v) :: typed(kind)))
          else Left(DecodeError.at(k, Kind.UnexpectedKey(Codec.describe(k))))
    go(0, Vector.fill(spec.literals.length)(None), Vector.fill(spec.typed.length)(Nil))
  }

/** Ordered choice: the first alternative that decodes wins; if none does, all causes are reported.
  */
final class Alternatives[A](alts: Array[Node => Decoded[A]]):
  def apply(n: Node): Decoded[A] =
    @scala.annotation.tailrec
    def go(i: Int, causes: Vector[DecodeError]): Decoded[A] =
      if i == alts.length then Left(DecodeError.at(n, Kind.NoAlternative(causes)))
      else
        alts(i)(n) match
          case r @ Right(_) => r
          case Left(e) =>
            e.terminal match
              case Some(terminal) => Left(terminal.error)
              case None           => go(i + 1, causes :+ e)
    go(0, Vector.empty)
