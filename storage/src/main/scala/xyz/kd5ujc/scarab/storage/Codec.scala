// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import scala.annotation.unused

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.NativeScript
import xyz.kd5ujc.scarab.codec.Rational
import scala.compiletime.{constValue, constValueTuple, erasedValue, summonInline}
import scala.deriving.Mirror

/** Raised inside a [[Codec]] for malformed input; mapped to `StorageError.Corrupt` at the boundary.
  */
final class CodecException(message: String) extends RuntimeException(message, null, false, false)

/** Byte sink with the primitives of the snapshot format. Integers are LEB128 varints. */
final class Out(stream: OutputStream):
  private val buffer = new Array[Byte](1 << 16)
  private var used = 0

  private def room(n: Int): Unit =
    if used + n > buffer.length then flushBuffer()

  private def flushBuffer(): Unit =
    stream.write(buffer, 0, used)
    used = 0

  def byte(b: Int): Unit =
    room(1)
    buffer(used) = b.toByte
    used += 1

  def bytes(a: Array[Byte], from: Int, len: Int): Unit =
    if len > buffer.length then
      flushBuffer()
      stream.write(a, from, len)
    else
      room(len)
      System.arraycopy(a, from, buffer, used, len)
      used += len

  def varint(value: Long): Unit =
    var v = value
    while (v & ~0x7fL) != 0 do
      byte(((v & 0x7f) | 0x80).toInt)
      v >>>= 7
    byte(v.toInt)

  def fixed32(v: Int): Unit = (0 until 4).foreach(i => byte((v >>> (24 - 8 * i)) & 0xff))
  def fixed64(v: Long): Unit = (0 until 8).foreach(i => byte(((v >>> (56 - 8 * i)) & 0xff).toInt))

  def flush(): Unit =
    flushBuffer()
    stream.flush()

/** Byte source over `limit` readable bytes; any read past it is a [[CodecException]]. Collection
  * sizes are checked against what remains, so a corrupt length cannot trigger a huge allocation.
  */
final class In(stream: InputStream, limit: Long):
  private var consumed = 0L
  private val buffer = new Array[Byte](1 << 16)
  private var pos = 0
  private var end = 0

  def remaining: Long = limit - consumed
  def position: Long = consumed

  private def refill(): Unit =
    val want = math.min(buffer.length.toLong, remaining - (end - pos)).toInt
    if want <= 0 then throw CodecException("unexpected end of data")
    val n = stream.read(buffer, 0, want)
    if n < 0 then throw CodecException("unexpected end of data")
    pos = 0
    end = n

  def byte(): Int =
    if consumed >= limit then throw CodecException("unexpected end of data")
    if pos == end then refill()
    val b = buffer(pos) & 0xff
    pos += 1
    consumed += 1
    b

  def bytes(len: Int): Array[Byte] =
    if len < 0 || len > remaining then throw CodecException(s"length $len exceeds remaining data")
    val out = new Array[Byte](len)
    var done = 0
    while done < len do
      if pos == end then refill()
      val n = math.min(len - done, end - pos)
      System.arraycopy(buffer, pos, out, done, n)
      pos += n
      done += n
      // Count as we go: `refill` bounds its read by what is still unconsumed.
      consumed += n
    out

  def varint(): Long =
    var shift = 0
    var result = 0L
    var more = true
    while more do
      if shift > 63 then throw CodecException("varint too long")
      val b = byte()
      result |= (b & 0x7fL) << shift
      shift += 7
      more = (b & 0x80) != 0
    result

  def fixed32(): Int = (0 until 4).foldLeft(0)((acc, _) => (acc << 8) | byte())
  def fixed64(): Long = (0 until 8).foldLeft(0L)((acc, _) => (acc << 8) | byte())

  /** A collection size: non-negative and no larger than the bytes left (every element takes one).
    */
  def count(): Int =
    val n = varint()
    if n < 0 || n > remaining then throw CodecException(s"count $n exceeds remaining data")
    n.toInt

/** Binary codec for a persisted type. `shape` describes the encoded structure (field names and
  * types); it feeds the schema fingerprint stored with every snapshot, so a change to any persisted
  * type is detected as a version mismatch instead of being misread.
  *
  * Codecs are composed: products encode their fields in order, sums a variant index then the
  * variant, containers a count then the elements. Case classes and enums are derived from their
  * `Mirror` (see [[Codec.auto]]); only types with private constructors or recursion need an
  * explicit instance.
  */
trait Codec[A]:
  def write(out: Out, a: A): Unit
  def read(in: In): A
  def shape: String

object Codec extends LowPriorityCodec:
  def apply[A](using c: Codec[A]): Codec[A] = c

  def make[A](name: String)(w: (Out, A) => Unit)(r: In => A): Codec[A] = new Codec[A]:
    def write(out: Out, a: A): Unit = w(out, a)
    def read(in: In): A = r(in)
    def shape: String = name

  /** Self-contained encoding of one value (tests, golden vectors). */
  def encode[A](a: A)(using c: Codec[A]): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out = Out(bytes)
    c.write(out, a)
    out.flush()
    bytes.toByteArray

  def decode[A](bytes: Array[Byte])(using c: Codec[A]): Either[String, A] =
    try
      val in = In(new ByteArrayInputStream(bytes), bytes.length.toLong)
      val a = c.read(in)
      Either.cond(in.remaining == 0, a, s"${in.remaining} trailing bytes")
    catch case e: CodecException => Left(e.getMessage)

  given Codec[Boolean] = make[Boolean]("Boolean")((o, b) => o.byte(if b then 1 else 0))(i =>
    i.byte() match
      case 0 => false
      case 1 => true
      case n => throw CodecException(s"invalid boolean $n")
  )
  given Codec[Int] = make[Int]("Int")((o, v) => o.varint(v.toLong))(i =>
    val v = i.varint()
    if v < Int.MinValue || v > Int.MaxValue then throw CodecException(s"int out of range: $v")
    v.toInt
  )
  given Codec[Long] = make[Long]("Long")((o, v) => o.varint(v))(_.varint())
  given Codec[String] = make[String]("String")((o, s) =>
    val b = s.getBytes(StandardCharsets.UTF_8)
    o.varint(b.length.toLong)
    o.bytes(b, 0, b.length)
  )(i => String(i.bytes(i.count()), StandardCharsets.UTF_8))

  /** Two's-complement big-endian magnitude, length-prefixed. */
  given Codec[BigInt] = make[BigInt]("BigInt")((o, v) =>
    val b = v.toByteArray
    o.varint(b.length.toLong)
    o.bytes(b, 0, b.length)
  )(i =>
    val n = i.count()
    if n == 0 then throw CodecException("empty BigInt")
    BigInt(i.bytes(n))
  )
  given Codec[Bytes] = make[Bytes]("Bytes")((o, v) =>
    val b = v.toArray
    o.varint(b.length.toLong)
    o.bytes(b, 0, b.length)
  )(i => Bytes.fromArray(i.bytes(i.count())))
  given Codec[Rational] with
    def write(out: Out, a: Rational): Unit =
      summon[Codec[BigInt]].write(out, a.numerator)
      summon[Codec[BigInt]].write(out, a.denominator)
    def read(in: In): Rational =
      val n = summon[Codec[BigInt]].read(in)
      val d = summon[Codec[BigInt]].read(in)
      if d.signum <= 0 then throw CodecException("non-positive denominator")
      Rational(n, d)
    def shape: String = "Rational(BigInt,BigInt)"

  given [A](using c: Codec[A]): Codec[Option[A]] with
    def write(out: Out, a: Option[A]): Unit = a match
      case None    => out.byte(0)
      case Some(v) => out.byte(1); c.write(out, v)
    def read(in: In): Option[A] = in.byte() match
      case 0 => None
      case 1 => Some(c.read(in))
      case n => throw CodecException(s"invalid option tag $n")
    def shape: String = s"Option[${c.shape}]"

  given [A](using c: Codec[A]): Codec[Vector[A]] with
    def write(out: Out, a: Vector[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Vector[A] =
      val n = in.count()
      val b = Vector.newBuilder[A]
      var i = 0
      while i < n do
        b += c.read(in)
        i += 1
      b.result()
    def shape: String = s"Vector[${c.shape}]"

  given [A](using c: Codec[A]): Codec[List[A]] with
    def write(out: Out, a: List[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): List[A] =
      val n = in.count()
      val b = List.newBuilder[A]
      var i = 0
      while i < n do
        b += c.read(in)
        i += 1
      b.result()
    def shape: String = s"List[${c.shape}]"

  given [A](using c: Codec[A]): Codec[Set[A]] with
    def write(out: Out, a: Set[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Set[A] =
      val n = in.count()
      val b = Set.newBuilder[A]
      var i = 0
      while i < n do
        b += c.read(in)
        i += 1
      b.result()
    def shape: String = s"Set[${c.shape}]"

  given [K, V](using k: Codec[K], v: Codec[V]): Codec[Map[K, V]] with
    def write(out: Out, a: Map[K, V]): Unit =
      out.varint(a.size.toLong)
      a.foreach { (key, value) =>
        k.write(out, key)
        v.write(out, value)
      }
    def read(in: In): Map[K, V] =
      val n = in.count()
      val b = Map.newBuilder[K, V]
      var i = 0
      while i < n do
        val key = k.read(in)
        b += key -> v.read(in)
        i += 1
      b.result()
    def shape: String = s"Map[${k.shape},${v.shape}]"

  /** Recursive: the one persisted type that contains itself, so it cannot be derived inline. A
    * script is as deep as the transaction that carried it allows (thousands of levels on preprod),
    * so both directions keep their stack on the heap.
    */
  given nativeScript: Codec[NativeScript] = new Codec[NativeScript]:
    private val bigInt = summon[Codec[BigInt]]
    private val bytes = summon[Codec[Bytes]]
    def write(out: Out, a: NativeScript): Unit =
      val stack = new java.util.ArrayDeque[Iterator[NativeScript]]
      def head(s: NativeScript): Unit =
        out.byte(s.ordinal)
        s match
          case NativeScript.Sig(h)              => bytes.write(out, h)
          case NativeScript.AllOf(ss)           => list(ss)
          case NativeScript.AnyOf(ss)           => list(ss)
          case NativeScript.NOfK(n, ss)         => bigInt.write(out, n); list(ss)
          case NativeScript.InvalidBefore(s)    => bigInt.write(out, s)
          case NativeScript.InvalidHereafter(s) => bigInt.write(out, s)
      def list(ss: Vector[NativeScript]): Unit =
        out.varint(ss.size.toLong)
        stack.push(ss.iterator)
      head(a)
      while !stack.isEmpty do
        val top = stack.peek()
        if top.hasNext then head(top.next()) else stack.pop()
    private final class Open(val tag: Int, val required: BigInt, var left: Int):
      val kids = Vector.newBuilder[NativeScript]
      def build: NativeScript =
        if tag == 1 then NativeScript.AllOf(kids.result())
        else if tag == 2 then NativeScript.AnyOf(kids.result())
        else NativeScript.NOfK(required, kids.result())
    def read(in: In): NativeScript =
      val stack = new java.util.ArrayDeque[Open]
      var result: NativeScript = null
      while result == null do
        var done: NativeScript = null
        val top = stack.peek()
        if top != null && top.left == 0 then
          stack.pop()
          done = top.build
        else
          if top != null then top.left -= 1
          in.byte() match
            case 0 => done = NativeScript.Sig(bytes.read(in))
            case 1 => stack.push(Open(1, 0, in.count()))
            case 2 => stack.push(Open(2, 0, in.count()))
            case 3 =>
              val n = bigInt.read(in)
              stack.push(Open(3, n, in.count()))
            case 4 => done = NativeScript.InvalidBefore(bigInt.read(in))
            case 5 => done = NativeScript.InvalidHereafter(bigInt.read(in))
            case n => throw CodecException(s"invalid native script tag $n")
        if done != null then if stack.isEmpty then result = done else stack.peek().kids += done
      result
    def shape: String =
      "NativeScript{Sig(Bytes)|AllOf(Vector[NativeScript])|AnyOf(Vector[NativeScript])|" +
        "NOfK(BigInt,Vector[NativeScript])|InvalidBefore(BigInt)|InvalidHereafter(BigInt)}"

/** Derivation by `Mirror`, at lower priority than the explicit instances above. */
trait LowPriorityCodec:
  inline given auto[A](using m: Mirror.Of[A]): Codec[A] =
    val name: String = constValue[m.MirroredLabel]
    val labels = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val codecs = LowPriorityCodec.summonAll[m.MirroredElemTypes]
    inline m match
      case s: Mirror.SumOf[A]     => LowPriorityCodec.sum[A](name, s, labels, codecs)
      case p: Mirror.ProductOf[A] => LowPriorityCodec.product[A](name, p, labels, codecs)

object LowPriorityCodec:
  inline def summonAll[T <: Tuple]: List[Codec[?]] = inline erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts)  => summonInline[Codec[t]] :: summonAll[ts]

  def product[A](
      name: String,
      m: Mirror.ProductOf[A],
      labels: List[String],
      codecs: List[Codec[?]]
  ): Codec[A] = new Codec[A]:
    private val fields = codecs.toArray.asInstanceOf[Array[Codec[Any]]]
    def write(out: Out, a: A): Unit =
      val p = a.asInstanceOf[Product]
      var i = 0
      while i < fields.length do
        fields(i).write(out, p.productElement(i))
        i += 1
    def read(in: In): A =
      val values = new Array[Any](fields.length)
      var i = 0
      while i < fields.length do
        values(i) = fields(i).read(in)
        i += 1
      m.fromProduct(new Product:
        def canEqual(that: Any): Boolean = false
        def productArity: Int = values.length
        def productElement(n: Int): Any = values(n))
    def shape: String =
      if labels.isEmpty then name
      else s"$name(${labels.zip(codecs).map((l, c) => s"$l:${c.shape}").mkString(",")})"

  def sum[A](
      name: String,
      m: Mirror.SumOf[A],
      @unused labels: List[String],
      codecs: List[Codec[?]]
  ): Codec[A] = new Codec[A]:
    private val variants = codecs.toArray.asInstanceOf[Array[Codec[Any]]]
    def write(out: Out, a: A): Unit =
      val i = m.ordinal(a)
      out.varint(i.toLong)
      variants(i).write(out, a)
    def read(in: In): A =
      val i = in.varint()
      if i < 0 || i >= variants.length then throw CodecException(s"$name: invalid variant $i")
      variants(i.toInt).read(in).asInstanceOf[A]
    def shape: String = s"$name{${codecs.map(_.shape).mkString("|")}}"
