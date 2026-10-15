// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import xyz.kd5ujc.scarab.codec.{Cbor, Value}
import scala.util.control.NonFatal
import DecodeError.Kind

/** A CBOR codec for one model type. Decoding works on the byte-preserving `Node` tree of
  * `xyz.kd5ujc.scarab.codec` and reports typed, located errors; encoding is canonical (shortest
  * forms, definite lengths) except where a type declares that its original bytes are preserved.
  */
trait Codec[A]:
  def decode(node: Node): Decoded[A]
  def encode(a: A, out: Writer): Unit

  /** `Some(constraint)` when `a` violates this codec's value constraint (used by smart
    * constructors).
    */
  def check(a: A): Option[String] = None

  /** Decode a complete CBOR item: syntax errors, trailing bytes and decoder exceptions are errors,
    * never thrown.
    */
  final def decodeBytes(bytes: Bytes, limits: Cbor.Limits = Codec.DefaultLimits): Decoded[A] =
    Cbor.decode(bytes, limits) match
      case Left(message) => Left(DecodeError(Kind.Syntax(message), Nil, 0, rooted = true))
      case Right(root) =>
        try decode(root).left.map(_.rebased(root.original.unsafeOffset))
        catch
          case NonFatal(e) => Left(DecodeError(Kind.Internal(e.toString), Nil, 0, rooted = true))

  final def encodeBytes(a: A): Bytes =
    val w = new Writer
    encode(a, w)
    w.result

object Codec:
  val DefaultLimits: Cbor.Limits = Cbor.Limits()

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

  def mismatch(expected: String, n: Node): DecodeError =
    DecodeError.at(n, Kind.TypeMismatch(expected, describe(n)))

  // Leaves ----------------------------------------------------------------------------------

  /** An unsigned integer of any size. */
  val uint: Codec[BigInt] = new Codec[BigInt]:
    def decode(n: Node) = n.value match
      case Value.UInt(v) => Right(v)
      case _             => Left(mismatch("unsigned integer", n))
    def encode(a: BigInt, out: Writer) = out.uint(a)
    override def check(a: BigInt) = Option.when(a.signum < 0)("0 <= x")

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
    override def check(a: Int) = Option.when(a < lo || a > hi)(what)

  /** An integer that is one of `values`, as an `Int`. */
  def intIn(values: Int*): Codec[Int] = new Codec[Int]:
    private val set = values.toSet
    private val what = values.mkString(" / ")
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v.isValidInt && set(v.toInt) => Right(v.toInt)
      case Value.UInt(v) => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _             => Left(mismatch("unsigned integer", n))
    def encode(a: Int, out: Writer) = out.uint(a.toLong)
    override def check(a: Int) = Option.unless(set(a))(what)

  /** An unsigned integer up to `max`, as an `Int`, `Long` or `BigInt` chosen by the generator. */
  def uintInt(max: Int): Codec[Int] = intRange(0, max)

  def uintLong(max: Long): Codec[Long] = new Codec[Long]:
    private val what = s"0..$max"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v <= max => Right(v.toLong)
      case Value.UInt(v)             => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                         => Left(mismatch("unsigned integer", n))
    def encode(a: Long, out: Writer) = out.uint(a)
    override def check(a: Long) = Option.when(a < 0 || a > max)(what)

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
    override def check(a: Long) = Option.when(a < lo || a > hi)(what)

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
    override def check(a: BigInt) = Option.when(a < lo || a > hi)(what)

  def uintBig(max: BigInt): Codec[BigInt] = new Codec[BigInt]:
    private val what = s"0..$max"
    def decode(n: Node) = n.value match
      case Value.UInt(v) if v <= max => Right(v)
      case Value.UInt(v)             => Left(DecodeError.at(n, Kind.OutOfRange(what, v)))
      case _                         => Left(mismatch("unsigned integer", n))
    def encode(a: BigInt, out: Writer) = out.uint(a)
    override def check(a: BigInt) = Option.when(a.signum < 0 || a > max)(what)

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
      Option.when(len < lo || len > hi)(s"size $what")

  val bytes: Codec[Bytes] = bytesSized(0, Int.MaxValue)

  def bytesSized(lo: Int, hi: Int): Codec[Bytes] = new Codec[Bytes]:
    private val what = if lo == hi then lo.toString else s"$lo..$hi"
    def decode(n: Node) = n.value match
      case Value.ByteString(b) =>
        if b.size >= lo && b.size <= hi then Right(b)
        else Left(DecodeError.at(n, Kind.BadSize(what, b.size.toLong)))
      case _ => Left(mismatch("byte string", n))
    def encode(a: Bytes, out: Writer) = out.bytes(a)
    override def check(a: Bytes) = Option.when(a.size < lo || a.size > hi)(s"size $what")

  /** `any`: the item as it is on the wire, original bytes included. */
  val any: Codec[Node] = new Codec[Node]:
    def decode(n: Node) = Right(n)
    def encode(a: Node, out: Writer) = out.raw(a.original)

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
                Kind.BadArity(
                  s"$min..${if max == Int.MaxValue then "*" else max.toString}",
                  items.size
                )
              )
            )
          else
            val out = Vector.newBuilder[A]
            var i = 0
            var failure: DecodeError = null
            while failure == null && i < items.size do
              c.decode(items(i)) match
                case Right(a) => out += a
                case Left(e)  => failure = e.at(s"[$i]")
              i += 1
            if failure == null then Right(out.result()) else Left(failure)
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
              case Left(e)  => Left(e.rebased(root.original.unsafeOffset).at("<cbor>"))
              case Right(a) => Right(a)
      case _ => Left(mismatch("byte string (embedded CBOR)", n))
    def encode(a: A, out: Writer) =
      val w = new Writer
      c.encode(a, w)
      out.bytes(w.result)

  /** `{ * k => v }`: all pairs, in wire order (duplicates kept). */
  def pairs[K, V](key: => Codec[K], value: => Codec[V]): Codec[Vector[(K, V)]] =
    new Codec[Vector[(K, V)]]:
      private lazy val kc = key
      private lazy val vc = value
      def decode(n: Node) = Maps.pairs(n).flatMap(Maps.decodePairs(_, kc, vc))
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
  def exact(n: Node, size: Int): Decoded[Vector[Node]] = n.value match
    case Value.Arr(xs) if xs.size == size => Right(xs)
    case Value.Arr(xs) => Left(DecodeError.at(n, Kind.BadArity(size.toString, xs.size)))
    case _             => Left(Codec.mismatch("array", n))
  def between(n: Node, min: Int, max: Int): Decoded[Vector[Node]] = n.value match
    case Value.Arr(xs) if xs.size >= min && xs.size <= max => Right(xs)
    case Value.Arr(xs) => Left(DecodeError.at(n, Kind.BadArity(s"$min..$max", xs.size)))
    case _             => Left(Codec.mismatch("array", n))

sealed trait MapKey
object MapKey:
  final case class IntKey(value: Long) extends MapKey
  final case class TextKey(value: String) extends MapKey

/** The entries of a map struct: literal keys get a slot each, `* k => v` entries collect by key
  * type.
  */
final class MapSpec(val literals: Array[MapKey], val typed: Array[Node => Boolean])

final class MapParts(
    val owner: Node,
    val slots: Array[Node],
    val typed: Array[Vector[(Node, Node)]],
    spec: MapSpec
):
  private def name(i: Int): String = spec.literals(i) match
    case MapKey.IntKey(v)  => v.toString
    case MapKey.TextKey(s) => s"\"$s\""

  def required[A](i: Int, codec: Codec[A]): Decoded[A] =
    val n = slots(i)
    if n == null then Left(DecodeError.at(owner, Kind.MissingKey(name(i))))
    else codec.decode(n).at(name(i))

  def optional[A](i: Int, codec: Codec[A]): Decoded[Option[A]] =
    val n = slots(i)
    if n == null then Right(None) else codec.decode(n).map(Some(_)).at(name(i))

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
    val out = Vector.newBuilder[(K, V)]
    var i = 0
    var failure: DecodeError = null
    while failure == null && i < ps.size do
      val (k, v) = ps(i)
      kc.decode(k) match
        case Left(e) => failure = e.at(s"key[$i]")
        case Right(kk) =>
          vc.decode(v) match
            case Left(e)   => failure = e.at(s"value[$i]")
            case Right(vv) => out += ((kk, vv))
      i += 1
    if failure == null then Right(out.result()) else Left(failure)

  private def matches(key: MapKey, n: Node): Boolean = (key, n.value) match
    case (MapKey.IntKey(k), Value.UInt(v))  => v == k
    case (MapKey.TextKey(k), Value.Text(s)) => s == k
    case _                                  => false

  /** Distribute the pairs of the map over the spec: literal keys first, then typed entries in
    * order.
    */
  def split(n: Node, spec: MapSpec): Decoded[MapParts] = pairs(n).flatMap { ps =>
    val slots = new Array[Node](spec.literals.length)
    val typed = Array.fill(spec.typed.length)(Vector.newBuilder[(Node, Node)])
    var i = 0
    var failure: DecodeError = null
    while failure == null && i < ps.size do
      val (k, v) = ps(i)
      var j = 0
      var placed = false
      while !placed && j < spec.literals.length do
        if matches(spec.literals(j), k) then
          if slots(j) != null then
            failure = DecodeError.at(k, Kind.DuplicateKey(k.original.toString))
          else slots(j) = v
          placed = true
        j += 1
      j = 0
      while !placed && j < spec.typed.length do
        if spec.typed(j)(k) then { typed(j) += ((k, v)); placed = true }
        j += 1
      if !placed && failure == null then
        failure = DecodeError.at(k, Kind.UnexpectedKey(Codec.describe(k)))
      i += 1
    if failure == null then Right(new MapParts(n, slots, typed.map(_.result()), spec))
    else Left(failure)
  }

/** Ordered choice: the first alternative that decodes wins; if none does, all causes are reported.
  */
final class Alternatives[A](alts: Array[Node => Decoded[A]]):
  def apply(n: Node): Decoded[A] =
    var i = 0
    var causes: Vector[DecodeError] = Vector.empty
    var result: Decoded[A] = null
    while result == null && i < alts.length do
      alts(i)(n) match
        case r @ Right(_) => result = r
        case Left(e)      => causes = causes :+ e
      i += 1
    if result != null then result else Left(DecodeError.at(n, Kind.NoAlternative(causes)))
