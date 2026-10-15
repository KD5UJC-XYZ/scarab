// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational, Value}
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Sorted, VocabError, Word64}
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}

import DecodeError.Kind

/** The encoding version the Haskell ledger's binary layer runs at (`Cardano.Ledger.Binary.Version`):
  * `ProtVer` of the mirror, whose major number is what the format depends on. Consensus writes a
  * state of an era at the lowest version of that era (`toEraCBOR`, `Core/Era.hs:233`), see
  * [[Versions.eraLow]]; a decoder can be run at any version, as the reference's can.
  */
extension (v: ProtVer)
  /** `ifEncodingVersionAtLeast (natVersion @major)`. */
  def atLeast(major: Int): Boolean = v.major.toBigInt >= major

/** The combinators the Haskell ledger-state codecs are built from: the shapes of the ledger's
  * `EncCBOR`/`DecCBOR` of numbers, containers and options, each with the decoder's checks. Instances
  * for the mirror's own types are in [[HaskellStateCodecs]].
  *
  * Sources: `cardano-ledger-binary` 1.9.1.0 (`Encoding/Encoder.hs`, `Decoding/Decoder.hs`), and the
  * ledger packages named in each combinator.
  */
object Wire:
  /** `DecodeError` for the item `n`. */
  def failure[A](n: Node, kind: Kind): Decoded[A] = Left(DecodeError.at(n, kind))

  // ---- numbers

  /** A `Word8`, `Word16`, `Word32` or `Word64` (`bits` wide) as a number. */
  def word(bits: Int): Codec[BigInt] = Codec.uintBig((BigInt(1) << bits) - 1)

  val word16: Codec[BigInt] = word(16)
  val word32: Codec[BigInt] = word(32)

  /** A `Word64` held as its bits (`encodeWord64`/`decodeWord64`). */
  val word64: Codec[Word64] = new Codec[Word64]:
    private val under = word(64)
    def decode(n: Node): Decoded[Word64] =
      under.decode(n).map(v => Word64.fromUnsigned(v.toLong))
    def encode(a: Word64, out: Writer): Unit = out.uint(a.toBigInt)

  /** An `Integer` (`encodeInteger`/`decodeInteger`): a plain integer, or a bignum (tags 2 and 3). */
  val integer: Codec[BigInt] = new Codec[BigInt]:
    def decode(n: Node): Decoded[BigInt] = n.value match
      case Value.UInt(v) => Right(v)
      case Value.NInt(v) => Right(v)
      case Value.Tag(t, inner) if t == 2 || t == 3 =>
        inner.value match
          case Value.ByteString(b) =>
            val magnitude = BigInt(1, b.toArray)
            Right(if t == 2 then magnitude else -1 - magnitude)
          case _ => Left(Codec.mismatch("byte string", inner))
      case _ => Left(Codec.mismatch("integer", n))
    def encode(a: BigInt, out: Writer): Unit = out.integer(a)

  /** A smart constructor's refusal, as the decode error that names it. */
  def violation(e: VocabError): Kind = e match
    case VocabError.Negative(what, v) =>
      Kind.Violation(ConstraintViolation(what.toString, "a non-negative number", v.toString))
    case VocabError.OutOfRange(what, v) =>
      Kind.Violation(ConstraintViolation(what.toString, "a number below 2^64", v.toString))
    case VocabError.WrongHashLength(what, expected, actual) =>
      Kind.Violation(ConstraintViolation(what.toString, s"$expected bytes", s"$actual bytes"))
    case VocabError.Zero(what) =>
      Kind.Violation(ConstraintViolation(what.toString, "a non-zero number", "0"))

  extension [R](c: Codec[R])
    /** The codec of `A`, written as `R` and read through a smart constructor whose refusal is a
      * located decode error. `check` is `R`'s.
      */
    def validated[A](make: R => Either[VocabError, A])(unmake: A => R): Codec[A] =
      new Codec[A]:
        def decode(n: Node): Decoded[A] =
          c.decode(n).flatMap(make(_).left.map(e => DecodeError.at(n, violation(e))))
        def encode(a: A, out: Writer): Unit = c.encode(unmake(a), out)
        override def check(a: A): Option[String] = c.check(unmake(a))

    /** The codec of `A`, an exact image of `R` (no refusal possible). */
    def imap[A](to: R => A)(from: A => R): Codec[A] =
      new Codec[A]:
        def decode(n: Node): Decoded[A] = c.decode(n).map(to)
        def encode(a: A, out: Writer): Unit = c.encode(from(a), out)
        override def check(a: A): Option[String] = c.check(from(a))

  /** A byte string of exactly `size` bytes. */
  def hash(size: Int): Codec[Bytes] = Codec.bytesSized(size, size)

  // ---- rationals

  private def ratioItems(n: Node): Decoded[(BigInt, BigInt)] = n.value match
    case Value.Arr(xs) if xs.size == 2 =>
      for
        num <- integer.decode(xs(0)).at("numerator")
        den <- integer.decode(xs(1)).at("denominator")
        _ <- Either.cond(
          den.signum != 0,
          (),
          DecodeError.at(xs(1), Kind.OutOfRange("a non-zero denominator", den)).at("denominator")
        )
      yield (num, den)
    case Value.Arr(xs) => Left(DecodeError.at(n, Kind.BadArity("2", xs.size)))
    case _             => Left(Codec.mismatch("array", n))

  /** A plain `Ratio Integer` (`encodeRatio`, `Encoder.hs:301-306`): tag 30 from version 9, absent
    * before. The decoder takes the tag when it is there from version 9 (`decodeRational`,
    * `Decoder.hs:474-483`).
    */
  def ratio(using v: ProtVer): Codec[Rational] = new Codec[Rational]:
    def decode(n: Node): Decoded[Rational] =
      val body = n.value match
        case Value.Tag(t, inner) if v.atLeast(9) && t == 30 => Right(inner)
        case Value.Tag(t, _)                               => Left(DecodeError.at(n, Kind.BadTag(30, t)))
        case _                                             => Right(n)
      body.flatMap(ratioItems(_).map((a, b) => Rational(a, b)))
    def encode(a: Rational, out: Writer): Unit =
      if v.atLeast(9) then out.tag(30)
      out.arrayHead(2)
      out.integer(a.numerator)
      out.integer(a.denominator)

  /** A bounded ratio (`UnitInterval`, `NonNegativeInterval`): always tag 30, whatever the version
    * (`Plain.encodeRatioWithTag`, `BaseTypes.hs:384`), both numbers `Word64`, and in `lo..hi`
    * (`hi` absent: unbounded above). The decoder requires the tag (`decodeRationalWithTag`).
    */
  private def bounded(what: String, hi: Option[Rational]): Codec[Rational] = new Codec[Rational]:
    private val max = (BigInt(1) << 64) - 1
    def decode(n: Node): Decoded[Rational] = n.value match
      case Value.Tag(t, inner) if t == 30 =>
        ratioItems(inner).flatMap { (num, den) =>
          val r = Rational(num, den)
          val fits = r.numerator.signum >= 0 && r.numerator <= max && r.denominator <= max
          if fits && hi.forall(r <= _) then Right(r)
          else Left(DecodeError.at(n, Kind.OutOfRange(what, num)))
        }
      case Value.Tag(t, _) => Left(DecodeError.at(n, Kind.BadTag(30, t)))
      case _               => Left(Codec.mismatch("tag 30", n))
    def encode(a: Rational, out: Writer): Unit =
      out.tag(30)
      out.arrayHead(2)
      out.uint(a.numerator)
      out.uint(a.denominator)
    override def check(a: Rational): Option[String] =
      Option.unless(a.numerator.signum >= 0 && a.numerator <= max && a.denominator <= max &&
        hi.forall(a <= _))(what)

  /** `UnitInterval`: a ratio in 0..1. */
  val unitInterval: Codec[Rational] = bounded("a ratio in 0..1", Some(Rational.one))

  /** `NonNegativeInterval`: a ratio of two `Word64`s. */
  val nonNegativeInterval: Codec[Rational] = bounded("a non-negative ratio", None)

  // ---- options

  /** `StrictMaybe a` and `Maybe a` (`encodeStrictMaybe`, `Encoder.hs:331`): `[]` or `[x]`. */
  def strictMaybe[A](inner: Codec[A]): Codec[Option[A]] = new Codec[Option[A]]:
    def decode(n: Node): Decoded[Option[A]] = n.value match
      case Value.Arr(xs) if xs.isEmpty    => Right(None)
      case Value.Arr(xs) if xs.size == 1 => inner.decode(xs(0)).map(Some(_))
      case Value.Arr(xs)                 => Left(DecodeError.at(n, Kind.BadArity("0 or 1", xs.size)))
      case _                             => Left(Codec.mismatch("array", n))
    def encode(a: Option[A], out: Writer): Unit = a match
      case None => out.arrayHead(0)
      case Some(x) =>
        out.arrayHead(1)
        inner.encode(x, out)
    override def check(a: Option[A]): Option[String] = a.flatMap(inner.check)

  /** `encodeNullStrictMaybe` (`Encoder.hs:340`): `null` or the value itself. */
  def nullable[A](inner: Codec[A]): Codec[Option[A]] = Codec.nullable(inner)

  // ---- nonces

  /** `Nonce` (`BaseTypes.hs:574-577`): `[0]` for the neutral nonce, `[1, hash]`. */
  val nonce: Codec[Nonce] = new Codec[Nonce]:
    private val h = hash(32)
    def decode(n: Node): Decoded[Nonce] =
      variant(n).flatMap {
        case (t, Vector()) if t == 0  => Right(Nonce.Neutral)
        case (t, Vector(x)) if t == 1 => h.decode(x).map(Nonce.Hash(_)).at("hash")
        case (t, xs)                  => unknownVariant(n, "Nonce", t, xs.size)
      }
    def encode(a: Nonce, out: Writer): Unit = a match
      case Nonce.Neutral =>
        out.arrayHead(1)
        out.uint(0L)
      case Nonce.Hash(x) =>
        out.arrayHead(2)
        out.uint(1L)
        h.encode(x, out)
    override def check(a: Nonce): Option[String] = a match
      case Nonce.Neutral => None
      case Nonce.Hash(x) => h.check(x)

  // ---- containers

  /** A set: tag 258 from version 9, and the variable-length framing (`encodeSet`,
    * `Encoder.hs:479-491`). The decoder takes elements in any order and either length form
    * (`decodeSet`, `Decoder.hs:952-962`): from version 9 the tag is optional and a repeated element
    * is an error; before it the tag is not permitted and repeats are ignored.
    */
  def setOf[A](elem: Codec[A])(using Order[A], ProtVer): Codec[SortedSet[A]] =
    val v = summon[ProtVer]
    new Codec[SortedSet[A]]:
      def decode(n: Node): Decoded[SortedSet[A]] =
        val body = n.value match
          case Value.Tag(t, inner) if v.atLeast(9) && t == 258 => Right(inner)
          case Value.Tag(t, _)                                 => Left(DecodeError.at(n, Kind.BadTag(258, t)))
          case _                                               => Right(n)
        body.flatMap(b =>
          b.value match
            case Value.Arr(items) =>
              Elements.decodeAll(items, elem).flatMap { decoded =>
                val set = Sorted.set(decoded)
                if v.atLeast(9) && set.size != decoded.size then
                  Left(DecodeError.at(b, Kind.DuplicateElement(s"${decoded.size - set.size} repeated")))
                else Right(set)
              }
            case _ => Left(Codec.mismatch("array", b))
        )
      def encode(a: SortedSet[A], out: Writer): Unit =
        if v.atLeast(9) then out.tag(258)
        out.variableArray(a.size)(a.foreach(elem.encode(_, out)))
      override def check(a: SortedSet[A]): Option[String] = a.iterator.flatMap(elem.check).nextOption()

  /** A map, in key order, with the variable-length framing (`encodeMap`, `Encoder.hs:397-408`). The
    * decoder takes entries in any order and either length form (`decodeMapByKey`,
    * `Decoder.hs:815-830`); from version 9 a repeated key is an error, before it the last wins.
    */
  def mapOf[K, V](key: Codec[K], value: Codec[V])(using Order[K], ProtVer): Codec[SortedMap[K, V]] =
    val v = summon[ProtVer]
    new Codec[SortedMap[K, V]]:
      def decode(n: Node): Decoded[SortedMap[K, V]] = n.value match
        case Value.Map(pairs) =>
          Elements.decodePairs(pairs, key, value).flatMap { decoded =>
            val map = Sorted.map(decoded)
            if v.atLeast(9) && map.size != decoded.size then
              Left(DecodeError.at(n, Kind.DuplicateKey(s"${decoded.size - map.size} repeated")))
            else Right(map)
          }
        case _ => Left(Codec.mismatch("map", n))
      def encode(a: SortedMap[K, V], out: Writer): Unit =
        out.variableMap(a.size)(a.foreach { (k, x) =>
          key.encode(k, out)
          value.encode(x, out)
        })
      override def check(a: SortedMap[K, V]): Option[String] =
        a.iterator.flatMap((k, x) => key.check(k).orElse(value.check(x))).nextOption()

  /** A sequence (`StrictSeq`, `encodeStrictSeq`, `Encoder.hs:516`): the variable-length framing
    * in order; the decoder reads either length form.
    */
  def seqOf[A](elem: Codec[A]): Codec[Vector[A]] = new Codec[Vector[A]]:
    def decode(n: Node): Decoded[Vector[A]] = n.value match
      case Value.Arr(items) => Elements.decodeAll(items, elem)
      case _                => Left(Codec.mismatch("array", n))
    def encode(a: Vector[A], out: Writer): Unit =
      out.variableArray(a.size)(a.foreach(elem.encode(_, out)))
    override def check(a: Vector[A]): Option[String] = a.iterator.flatMap(elem.check).nextOption()

  /** The items of an array of known size, or the arity error. */
  def items(n: Node, size: Int): Decoded[Vector[Node]] = xyz.kd5ujc.scarab.models.runtime.Arr.exact(n, size)

  /** A tagged alternative `[tag, field ...]`: the tag and the fields after it. */
  def variant(n: Node): Decoded[(BigInt, Vector[Node])] = n.value match
    case Value.Arr(xs) if xs.nonEmpty =>
      xs.head.value match
        case Value.UInt(tag) => Right((tag, xs.tail))
        case _               => Left(Codec.mismatch("unsigned integer", xs.head))
    case Value.Arr(_) => Left(DecodeError.at(n, Kind.BadArity("at least 1", 0)))
    case _            => Left(Codec.mismatch("array", n))

  /** An alternative with a tag or arity the sum does not have. */
  def unknownVariant[A](n: Node, sum: String, tag: BigInt, size: Int): Decoded[A] =
    Left(DecodeError.at(n, Kind.LiteralMismatch(s"a $sum alternative", s"tag $tag with $size fields")))

  /** Decode a list of items with one codec, reporting the index of the first failure. */
  private[state] object Elements:
    def decodeAll[A](items: Vector[Node], elem: Codec[A]): Decoded[Vector[A]] =
      val out = Vector.newBuilder[A]
      @scala.annotation.tailrec
      def go(i: Int): Decoded[Vector[A]] =
        if i == items.size then Right(out.result())
        else
          elem.decode(items(i)) match
            case Right(a) =>
              out += a
              go(i + 1)
            case Left(e) => Left(e.at(s"[$i]"))
      go(0)

    def decodePairs[K, V](
        pairs: Vector[(Node, Node)],
        key: Codec[K],
        value: Codec[V]
    ): Decoded[Vector[(K, V)]] =
      val out = Vector.newBuilder[(K, V)]
      @scala.annotation.tailrec
      def go(i: Int): Decoded[Vector[(K, V)]] =
        if i == pairs.size then Right(out.result())
        else
          val (k, x) = pairs(i)
          key.decode(k).left.map(_.at(s"key[$i]")) match
            case Left(e) => Left(e)
            case Right(kk) =>
              value.decode(x).left.map(_.at(s"value[$i]")) match
                case Left(e) => Left(e)
                case Right(vv) =>
                  out += ((kk, vv))
                  go(i + 1)
      go(0)
