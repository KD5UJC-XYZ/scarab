// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.BinaryError
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.NativeScript
import xyz.kd5ujc.scarab.codec.Rational
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror
import scala.reflect.TypeTest

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
    if n < 0 || n > remaining then Left(BinaryError.CountExceedsRemaining(n, remaining))
    else if n > Int.MaxValue then Left(BinaryError.IntOutOfRange(n))
    else Right(n.toInt)
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

  /** Read incrementally: a corrupt first element must not allocate from the claimed count. */
  private[storage] def readCounted[A, B](in: In, initial: B)(
      read: => Either[BinaryError, A]
  )(append: (B, A) => B): Either[BinaryError, B] =
    in.count().flatMap { count =>
      cats.Monad[[C] =>> Either[BinaryError, C]].tailRecM((0, initial)) { case (index, result) =>
        if index == count then Right(Right(result))
        else read.map(value => Left((index + 1, append(result, value))))
      }
    }

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
      readCounted(in, Vector.empty[A])(c.read(in))(_ :+ _)
    def shape: String = s"Vector[${c.shape}]"
  given [A](using c: Codec[A]): Codec[List[A]] with
    def write(out: Out, a: List[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Either[BinaryError, List[A]] =
      readCounted(in, List.empty[A])(c.read(in))((values, value) => value :: values).map(_.reverse)
    def shape: String = s"List[${c.shape}]"
  given [A](using c: Codec[A]): Codec[Set[A]] with
    def write(out: Out, a: Set[A]): Unit =
      out.varint(a.size.toLong)
      a.foreach(c.write(out, _))
    def read(in: In): Either[BinaryError, Set[A]] =
      readCounted(in, Set.empty[A])(c.read(in))(_ + _)
    def shape: String = s"Set[${c.shape}]"
  given [K, V](using k: Codec[K], v: Codec[V]): Codec[Map[K, V]] with
    def write(out: Out, a: Map[K, V]): Unit =
      out.varint(a.size.toLong)
      a.foreach { (key, value) =>
        k.write(out, key); v.write(out, value)
      }
    def read(in: In): Either[BinaryError, Map[K, V]] =
      readCounted(in, Map.empty[K, V]) {
        for
          key <- k.read(in)
          value <- v.read(in)
        yield key -> value
      }(_ + _)
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

/** Derivation by the compiler's product/sum mirrors. Each field's generated accessor returns
  * exactly the type consumed by its codec. Sum cases carry a subtype relationship to their root and
  * a relative TypeTest; unlike ClassTag, that evidence checks the case in the root's type context,
  * including its parameters. No field value is recovered from productElement/Any. Field order,
  * variant ordinals and shape strings retain the persisted format.
  */
trait LowPriorityCodec:
  inline given auto[A <: Product](using m: Mirror.Of[A]): Codec[A] =
    inline m match
      case s: Mirror.SumOf[A]     => LowPriorityCodec.sum[A](using s)
      case p: Mirror.ProductOf[A] => LowPriorityCodec.product[A](using p)

object LowPriorityCodec:
  /** The labels of a mirror's elements, in declaration order: string literals kept at their own
    * type and widened by `toString` (the identity on a string, no cast).
    */
  private inline def labels[L <: Tuple]: List[String] = inline erasedValue[L] match
    case _: EmptyTuple => Nil
    case _: (l *: ls)  => constValue[l].toString :: labels[ls]

  /** Fields are indexed by their owner, value types and compiler-provided labels. A getter and its
    * codec share T; decoded heads remain T in the result tuple consumed by the mirror.
    */
  private[scarab] sealed trait Fields[A, Ts <: Tuple, Ls <: Tuple]:
    def write(out: Out, value: A): Unit
    def read(in: In): Either[BinaryError, Ts]
    def shapes: List[String]

  private[scarab] final class NoFields[A] extends Fields[A, EmptyTuple.type, EmptyTuple.type]:
    def write(out: Out, value: A): Unit = ()
    def read(in: In): Either[BinaryError, EmptyTuple.type] = Right(EmptyTuple)
    def shapes: List[String] = Nil

  private[scarab] final class OneField[A, T, Ts <: Tuple, L <: String, Ls <: Tuple](
      get: A => T,
      tail: Fields[A, Ts, Ls]
  )(using
      codec: Codec[T]
  ) extends Fields[A, T *: Ts, L *: Ls]:
    def write(out: Out, value: A): Unit =
      codec.write(out, get(value))
      tail.write(out, value)

    def read(in: In): Either[BinaryError, T *: Ts] =
      codec.read(in).flatMap(value => tail.read(in).map(value *: _))

    def shapes: List[String] = codec.shape :: tail.shapes

  private[scarab] given noFields[A]: Fields[A, EmptyTuple.type, EmptyTuple.type] = new NoFields[A]

  private[scarab] inline given oneField[A <: Product, T, Ts <: Tuple, L <: String, Ls <: Tuple](
      using
      codec: Codec[T],
      tail: Fields[A, Ts, Ls]
  ): Fields[A, T *: Ts, L *: Ls] =
    new OneField[A, T, Ts, L, Ls](ProductFields.named[A, T](constValue[L]), tail)

  /** The codecs of a sum's variants, one per case, in declaration order: the tag is the case's
    * ordinal, as before, and the case's codec is applied at the case's own type.
    */
  private[scarab] sealed trait Variants[A, Ts <: Tuple]:
    def write(out: Out, a: A, index: Int): Unit
    def read(in: In, index: Int): Either[BinaryError, A]
    def shapes: List[String]

  private[scarab] final class NoVariants[A] extends Variants[A, EmptyTuple.type]:
    def write(out: Out, a: A, index: Int): Unit = ()
    def read(in: In, index: Int): Either[BinaryError, A] =
      throw new IllegalStateException(s"no variant at ordinal $index")
    def shapes: List[String] = Nil

  private[scarab] final class OneVariant[A, T <: A, Ts <: Tuple](tail: Variants[A, Ts])(using
      codec: Codec[T],
      test: TypeTest[A, T]
  ) extends Variants[A, T *: Ts]:
    def write(out: Out, a: A, index: Int): Unit =
      if index == 0 then
        a match
          case test(value) => codec.write(out, value)
          case _ =>
            throw new IllegalStateException(s"the value at ordinal 0 is not of its variant's type")
      else tail.write(out, a, index - 1)

    def read(in: In, index: Int): Either[BinaryError, A] =
      if index == 0 then codec.read(in).map(value => value: A)
      else tail.read(in, index - 1)

    def shapes: List[String] = codec.shape :: tail.shapes

  private[scarab] given noVariants[A]: Variants[A, EmptyTuple.type] = new NoVariants[A]

  private[scarab] given oneVariant[A, T <: A, Ts <: Tuple](using
      codec: Codec[T],
      test: TypeTest[A, T],
      tail: Variants[A, Ts]
  ): Variants[A, T *: Ts] = new OneVariant[A, T, Ts](tail)

  /** A product's codec from its field chain: the instance is made here, not at the inline site, so
    * a derivation does not duplicate an anonymous class.
    */
  private def productCodec[A <: Product](m: Mirror.ProductOf[A])(
      shapeOf: String,
      fields: Fields[A, m.MirroredElemTypes, m.MirroredElemLabels]
  ): Codec[A] = new Codec[A]:
    def write(out: Out, a: A): Unit = fields.write(out, a)
    def read(in: In): Either[BinaryError, A] = fields.read(in).map(m.fromProduct)
    def shape: String = shapeOf

  /** The codec of a case class: its fields in declaration order, in the same order and with the
    * same shape as before.
    */
  inline def product[A <: Product](using m: Mirror.ProductOf[A]): Codec[A] =
    val name: String = constValue[m.MirroredLabel]
    val fieldLabels = labels[m.MirroredElemLabels]
    val fields = summonInline[Fields[A, m.MirroredElemTypes, m.MirroredElemLabels]]
    val shape =
      if fieldLabels.isEmpty then name
      else s"$name(${fieldLabels.zip(fields.shapes).map((l, s) => s"$l:$s").mkString(",")})"
    productCodec[A](m)(shape, fields)

  /** A sum's codec from its variant chain. */
  private def sumCodec[A](
      name: String,
      variants: Variants[A, ?],
      m: Mirror.SumOf[A]
  ): Codec[A] = new Codec[A]:
    private val arity = variants.shapes.length
    def write(out: Out, a: A): Unit =
      val index = m.ordinal(a)
      out.varint(index.toLong)
      variants.write(out, a, index)
    def read(in: In): Either[BinaryError, A] = in.varint().flatMap { index =>
      if index < 0 || index >= arity then Left(BinaryError.InvalidVariant(name, index, arity))
      else variants.read(in, index.toInt)
    }
    def shape: String = s"$name{${variants.shapes.mkString("|")}}"

  /** The codec of an enum: the variant index as before, then the variant's own codec. */
  inline def sum[A](using m: Mirror.SumOf[A]): Codec[A] =
    val name: String = constValue[m.MirroredLabel]
    sumCodec[A](name, summonInline[Variants[A, m.MirroredElemTypes]], m)
