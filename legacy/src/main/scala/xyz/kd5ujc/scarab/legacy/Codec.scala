// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import scala.annotation.unused
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.BinaryError
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.NativeScript
import xyz.kd5ujc.scarab.codec.Rational
import scala.compiletime.{constValue, constValueTuple, erasedValue, summonInline}
import scala.deriving.Mirror

// Frozen V2/V3/V4 binary layout. Only failure representation and reader composition change;
// shape strings, writer order and encodings remain byte-for-byte compatible (ADR 0006).

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

/** Buffered input interpreter. Malformed fields return values; stream I/O failures are handled by
  * the storage boundary. Collection sizes are bounded by the bytes that remain.
  */
final class In(source: InputStream, limit: Long):
  private val stream = new java.io.BufferedInputStream(
    new InputStream:
      private var left = limit
      override def read(): Int =
        if left <= 0 then -1
        else
          val value = source.read()
          if value >= 0 then left -= 1
          value
      override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
        if length == 0 then 0
        else if left <= 0 then -1
        else
          val count = source.read(bytes, offset, math.min(length.toLong, left).toInt)
          if count > 0 then left -= count
          count
    ,
    1 << 16
  )
  private var consumed = 0L
  def remaining: Long = limit - consumed
  def position: Long = consumed
  def byte(): Either[BinaryError, Int] =
    if consumed >= limit then Left(BinaryError.UnexpectedEnd(consumed))
    else
      val b = stream.read()
      if b < 0 then Left(BinaryError.UnexpectedEnd(consumed))
      else
        consumed += 1
        Right(b)
  def bytes(len: Int): Either[BinaryError, Array[Byte]] =
    if len < 0 || len > remaining then
      Left(BinaryError.LengthExceedsRemaining(len.toLong, remaining))
    else
      val out = new Array[Byte](len)
      cats.Monad[[A] =>> Either[BinaryError, A]].tailRecM(0) { done =>
        if done == len then Right(Right(out))
        else
          val n = stream.read(out, done, len - done)
          if n < 0 then Left(BinaryError.UnexpectedEnd(consumed))
          else if n == 0 then
            byte().map { value =>
              out(done) = value.toByte; Left(done + 1)
            }
          else
            consumed += n
            Right(Left(done + n))
      }
  def varint(): Either[BinaryError, Long] =
    cats.Monad[[A] =>> Either[BinaryError, A]].tailRecM((0, 0L)) { case (shift, result) =>
      if shift > 63 then Left(BinaryError.VarintTooLong(consumed))
      else
        byte().map { b =>
          val value = result | ((b & 0x7fL) << shift)
          if (b & 0x80) == 0 then Right(value) else Left((shift + 7, value))
        }
    }
  def fixed32(): Either[BinaryError, Int] =
    (0 until 4).toVector.foldLeftM(0)((acc, _) => byte().map(b => (acc << 8) | b))
  def fixed64(): Either[BinaryError, Long] =
    (0 until 8).toVector.foldLeftM(0L)((acc, _) => byte().map(b => (acc << 8) | b))
  def count(): Either[BinaryError, Int] = varint().flatMap { n =>
    Either.cond(n >= 0 && n <= remaining, n.toInt, BinaryError.CountExceedsRemaining(n, remaining))
  }

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
  def read(in: In): Either[BinaryError, A]
  def shape: String

object Codec extends LowPriorityCodec:
  def apply[A](using c: Codec[A]): Codec[A] = c

  def make[A](name: String)(w: (Out, A) => Unit)(r: In => Either[BinaryError, A]): Codec[A] =
    new Codec[A]:
      def write(out: Out, a: A): Unit = w(out, a)
      def read(in: In): Either[BinaryError, A] = r(in)
      def shape: String = name

  /** Self-contained encoding of one value (tests, golden vectors). */
  def encode[A](a: A)(using c: Codec[A]): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out = Out(bytes)
    c.write(out, a)
    out.flush()
    bytes.toByteArray

  def decode[A](bytes: Array[Byte])(using c: Codec[A]): Either[BinaryError, A] =
    val in = In(new ByteArrayInputStream(bytes), bytes.length.toLong)
    c.read(in)
      .flatMap(a => Either.cond(in.remaining == 0, a, BinaryError.TrailingBytes(in.remaining)))

  given Codec[Boolean] = make[Boolean]("Boolean")((o, b) => o.byte(if b then 1 else 0))(i =>
    i.byte().flatMap {
      case 0 => Right(false)
      case 1 => Right(true)
      case n => Left(BinaryError.InvalidBoolean(n))
    }
  )
  given Codec[Int] = make[Int]("Int")((o, v) => o.varint(v.toLong))(i =>
    i.varint()
      .flatMap(v =>
        Either.cond(v >= Int.MinValue && v <= Int.MaxValue, v.toInt, BinaryError.IntOutOfRange(v))
      )
  )
  given Codec[Long] = make[Long]("Long")((o, v) => o.varint(v))(_.varint())
  given Codec[String] = make[String]("String")((o, s) =>
    val b = s.getBytes(StandardCharsets.UTF_8)
    o.varint(b.length.toLong)
    o.bytes(b, 0, b.length)
  )(i => i.count().flatMap(i.bytes).map(b => String(b, StandardCharsets.UTF_8)))
  given Codec[BigInt] = make[BigInt]("BigInt")((o, v) =>
    val b = v.toByteArray
    o.varint(b.length.toLong)
    o.bytes(b, 0, b.length)
  )(i =>
    i.count().flatMap { n =>
      if n == 0 then Left(BinaryError.EmptyBigInt) else i.bytes(n).map(BigInt(_))
    }
  )
  given Codec[Bytes] = make[Bytes]("Bytes")((o, v) =>
    val b = v.toArray
    o.varint(b.length.toLong)
    o.bytes(b, 0, b.length)
  )(i => i.count().flatMap(i.bytes).map(Bytes.fromArray))
  given Codec[Rational] with
    def write(out: Out, a: Rational): Unit =
      summon[Codec[BigInt]].write(out, a.numerator)
      summon[Codec[BigInt]].write(out, a.denominator)
    def read(in: In): Either[BinaryError, Rational] = for
      n <- summon[Codec[BigInt]].read(in)
      d <- summon[Codec[BigInt]].read(in)
      value <-
        if d.signum > 0 then Right(Rational(n, d)) else Left(BinaryError.NonPositiveDenominator(d))
    yield value
    def shape: String = "Rational(BigInt,BigInt)"
  given [A](using c: Codec[A]): Codec[Option[A]] with
    def write(out: Out, a: Option[A]): Unit = a match
      case None    => out.byte(0)
      case Some(v) => out.byte(1); c.write(out, v)
    def read(in: In): Either[BinaryError, Option[A]] = in.byte().flatMap {
      case 0 => Right(None)
      case 1 => c.read(in).map(Some(_))
      case n => Left(BinaryError.InvalidOptionTag(n))
    }
    def shape: String = s"Option[${c.shape}]"
  given [A](using c: Codec[A]): Codec[Vector[A]] with
    def write(out: Out, a: Vector[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Either[BinaryError, Vector[A]] =
      in.count().flatMap(n => Vector.fill(n)(()).traverse(_ => c.read(in)))
    def shape: String = s"Vector[${c.shape}]"
  given [A](using c: Codec[A]): Codec[List[A]] with
    def write(out: Out, a: List[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Either[BinaryError, List[A]] =
      in.count().flatMap(n => List.fill(n)(()).traverse(_ => c.read(in)))
    def shape: String = s"List[${c.shape}]"
  given [A](using c: Codec[A]): Codec[Set[A]] with
    def write(out: Out, a: Set[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Either[BinaryError, Set[A]] =
      summon[Codec[Vector[A]]].read(in).map(_.toSet)
    def shape: String = s"Set[${c.shape}]"
  given [K, V](using k: Codec[K], v: Codec[V]): Codec[Map[K, V]] with
    def write(out: Out, a: Map[K, V]): Unit =
      out.varint(a.size.toLong)
      a.foreach { (key, value) =>
        k.write(out, key); v.write(out, value)
      }
    def read(in: In): Either[BinaryError, Map[K, V]] =
      in.count()
        .flatMap(n =>
          Vector.fill(n)(()).traverse { _ =>
            for
              key <- k.read(in)
              value <- v.read(in)
            yield key -> value
          }
        )
        .map(_.toMap)
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
    private final case class Open(
        tag: Int,
        required: BigInt,
        left: Int,
        kids: Vector[NativeScript]
    ):
      def build: NativeScript = tag match
        case 1 => NativeScript.AllOf(kids)
        case 2 => NativeScript.AnyOf(kids)
        case _ => NativeScript.NOfK(required, kids)
    def read(in: In): Either[BinaryError, NativeScript] =
      type Pending = (List[Open], Option[NativeScript])
      cats.Monad[[A] =>> Either[BinaryError, A]].tailRecM[Pending, NativeScript]((Nil, None)) {
        case (Nil, Some(done)) => Right(Right(done))
        case (top :: rest, Some(done)) =>
          Right(Left((top.copy(kids = top.kids :+ done) :: rest, None)))
        case (top :: rest, None) if top.left == 0 => Right(Left((rest, Some(top.build))))
        case (stack, None) =>
          val parents = stack match
            case top :: rest => top.copy(left = top.left - 1) :: rest
            case Nil         => Nil
          in.byte().flatMap {
            case 0 => bytes.read(in).map(h => Left((parents, Some(NativeScript.Sig(h)))))
            case tag @ (1 | 2) =>
              in.count().map(n => Left((Open(tag, 0, n, Vector.empty) :: parents, None)))
            case 3 =>
              bigInt
                .read(in)
                .flatMap(n =>
                  in.count().map(count => Left((Open(3, n, count, Vector.empty) :: parents, None)))
                )
            case 4 => bigInt.read(in).map(n => Left((parents, Some(NativeScript.InvalidBefore(n)))))
            case 5 =>
              bigInt.read(in).map(n => Left((parents, Some(NativeScript.InvalidHereafter(n)))))
            case tag => Left(BinaryError.InvalidNativeScriptTag(tag))
          }
      }
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
    def read(in: In): Either[BinaryError, A] =
      fields.toVector.traverse(_.read(in)).map { values =>
        m.fromProduct(new Product:
          def canEqual(that: Any): Boolean = false
          def productArity: Int = values.length
          def productElement(n: Int): Any = values(n))
      }
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
    def read(in: In): Either[BinaryError, A] = in.varint().flatMap { i =>
      if i < 0 || i >= variants.length then
        Left(BinaryError.InvalidVariant(name, i, variants.length))
      else variants(i.toInt).read(in).map(_.asInstanceOf[A])
    }
    def shape: String = s"$name{${codecs.map(_.shape).mkString("|")}}"
