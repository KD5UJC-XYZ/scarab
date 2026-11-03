// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{ByteStrings, CborRead, DecodeSite, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.LedgerDecodeError.Shape

/** The readers of cardano-ledger-binary at decoder version 12, Dijkstra's `eraProtVerLow`, over a
  * decoded item, as the build of the public Leios testnet has them (cardano-ledger 9df9f98c,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs`, with cborg 0.2.10.0
  * underneath). Every list the reference reads at this version is read by `decodeRecordNamed`,
  * `decodeRecordSum` or `decodeList`: a definite list of its items or an indefinite one with its
  * items before the break, never a detached record. A byte string or a text may be chunked
  * (`decodeBytes`, `decodeString`: `ifDecoderVersionAtLeast (natVersion @12)`,
  * `Decoder.hs:359-364, 1564-1569`). A set may carry the tag 258 and need not (`allowTag setTag`,
  * `Decoder.hs:1058-1078`). cborg's integer readers take a head of any width.
  *
  * Only the verdict of a decoder is compared with the reference; the order in which two failures of
  * one input are found is not.
  */
private[ledger] object V12:
  type Result[A] = Either[LedgerDecodeError, A]

  /** Byte strings at version 12: definite or chunked. */
  given strings: ByteStrings = ByteStrings.Chunked

  val Word8Max: BigInt = BigInt(255)
  val Word16Max: BigInt = BigInt(65535)
  val Word32Max: BigInt = (BigInt(1) << 32) - 1
  val Word64Max: BigInt = (BigInt(1) << 64) - 1
  val Int64Max: BigInt = BigInt(Long.MaxValue)
  val Int64Min: BigInt = BigInt(Long.MinValue)

  /** The original bytes of an item of the block, as limits for decoding an item nested in it. */
  val nestedLimits: Cbor.Limits =
    Cbor.Limits(maxInputBytes = 2500000, maxItems = 1000000, maxStringBytes = 2500000)

  /** cborg's `decodeWord8`, `decodeWord16`, `decodeWord32`, `decodeWord64` (and `decodeWord`, 64
    * bits wide): an unsigned integer of any head width no larger than `max`.
    */
  def word(n: Node, what: DecodeSite, max: BigInt): Result[BigInt] =
    CborRead
      .uint(n, what)
      .flatMap(v => Either.cond(v <= max, v, LedgerDecodeError.NumberOutOfRange(what, v, 0, max)))

  def word8(n: Node, what: DecodeSite): Result[Int] = word(n, what, Word8Max).map(_.toInt)
  def word16(n: Node, what: DecodeSite): Result[Int] = word(n, what, Word16Max).map(_.toInt)
  def word32(n: Node, what: DecodeSite): Result[Long] = word(n, what, Word32Max).map(_.toLong)
  def word64(n: Node, what: DecodeSite): Result[BigInt] = word(n, what, Word64Max)

  /** cborg's `decodeInt` and `decodeInt64` on a 64-bit machine: an integer item, not a bignum, in
    * the range of `Int64`.
    */
  def int64(n: Node, what: DecodeSite): Result[Long] =
    CborRead
      .int(n, what)
      .flatMap(v =>
        Either.cond(
          v >= Int64Min && v <= Int64Max,
          v.toLong,
          LedgerDecodeError.NumberOutOfRange(what, v, Int64Min, Int64Max)
        )
      )

  def bool(n: Node, what: DecodeSite): Result[Boolean] = CborRead.bool(n, what)

  def bytes(n: Node, what: DecodeSite): Result[Bytes] = CborRead.bytes(n, what)

  /** `decodeFixedSized` of a hash or key: a byte string of exactly `size` bytes. */
  def bytesN(n: Node, size: Int, what: DecodeSite): Result[Bytes] =
    CborRead.bytesN(n, size, what)

  /** `decodeString` at version 12: a definite text or a chunked one, each chunk definite and UTF-8
    * on its own (`decodeStringIndefLen`, `Decoder.hs:1582-1601`), which the decode of the item has
    * already checked.
    */
  def text(n: Node, what: DecodeSite): Result[String] = n.value match
    case Value.Text(v) => Right(v)
    case other         => Left(LedgerDecodeError.Expected(what, Shape.Text, other))

  /** `textSizeN limit` (`BaseTypes.hs:644-661`): a text of at most `limit` bytes of UTF-8. */
  def textMax(n: Node, what: DecodeSite, limit: Int): Result[String] =
    text(n, what).flatMap { s =>
      val size = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      Either.cond(size <= limit, s, LedgerDecodeError.TextTooLong(what, limit, size))
    }

  /** `decodeNull`, `decodeNullStrictMaybe`: null, or the item read by `f`. */
  def nullable[A](n: Node)(f: Node => Result[A]): Result[Option[A]] = n.value match
    case Value.Null => Right(None)
    case _          => f(n).map(Some(_))

  /** `decodeList`, `decodeStrictSeq`, `decodeListLenOrIndef`: a definite or an indefinite list, its
    * items; no tag is read.
    */
  def list(n: Node, what: DecodeSite): Result[Vector[Node]] = CborRead.array(n, what)

  /** `decodeRecordNamed` with a constant size: a list of exactly `size` items, definite or
    * indefinite (`decodeListLikeT`, `Decoder.hs:682-700`).
    */
  def record(n: Node, size: Int, what: DecodeSite): Result[Vector[Node]] =
    CborRead.arrayN(n, size, what)

  /** `decodeRecordSum`: a list whose first item is the tag, read by `decodeWord`, and whose length
    * is the one the tag's decoder gives (`Decoder.hs:661-666`). `branch` gives, for a tag, the
    * length and the reader of the whole list; a tag it does not know is `invalidKey`.
    */
  def sum[A](n: Node, what: DecodeSite)(
      branch: PartialFunction[BigInt, (Int, Vector[Node] => Result[A])]
  ): Result[A] =
    for
      xs <- list(n, what)
      tag <- xs.headOption
        .toRight(LedgerDecodeError.Empty(what))
        .flatMap(word(_, what, Word64Max))
      chosen <- branch.lift(tag).toRight(LedgerDecodeError.UnknownTag(what, tag))
      (size, read) = chosen
      _ <- Either.cond(xs.size == size, (), LedgerDecodeError.TaggedArity(what, tag, xs.size))
      a <- read(xs)
    yield a

  /** `allowTag setTag` and the list after it (`decodeSetLikeEnforceNoDuplicates`): the tag 258, of
    * any head width, may precede the list; any other tag is refused.
    */
  def setItems(n: Node, what: DecodeSite): Result[Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 => list(inner, what)
    case Value.Tag(t, _)                 => Left(LedgerDecodeError.UnknownTag(what, t))
    case _                               => list(n, what)

  /** A set-like collection of the elements read by `elem` and judged equal by `key`: the reference
    * inserts each into its structure and fails when the structure is smaller than the count read
    * (`decodeListLikeEnforceNoDuplicates`, `Decoder.hs:1034-1056`).
    */
  def set[A, K](n: Node, what: DecodeSite)(elem: Node => Result[A])(
      key: A => K
  ): Result[Vector[A]] =
    setItems(n, what).flatMap(_.traverse(elem)).flatMap(unique(_, what)(key))

  /** As [[set]], and not empty (`decodeNonEmptySetLikeEnforceNoDuplicates`,
    * `Decoder.hs:1080-1098`).
    */
  def nonEmptySet[A, K](n: Node, what: DecodeSite)(elem: Node => Result[A])(
      key: A => K
  ): Result[Vector[A]] =
    set(n, what)(elem)(key).flatMap(nonEmpty(_, what))

  def nonEmpty[A](xs: Vector[A], what: DecodeSite): Result[Vector[A]] =
    CborRead.nonEmpty(xs, what, required = true)

  def unique[A, K](xs: Vector[A], what: DecodeSite)(key: A => K): Result[Vector[A]] =
    Either.cond(
      xs.map(key).distinct.size == xs.size,
      xs,
      LedgerDecodeError.DuplicateElement(what)
    )

  /** `decodeMapLenOrIndef` and its entries: a definite or an indefinite map; no tag is read. */
  def entries(n: Node, what: DecodeSite): Result[Vector[(Node, Node)]] = CborRead.map(n, what)

  /** `decodeMap` from version 9 (`decodeMapLikeEnforceNoDuplicates`, `Decoder.hs:801-832`): each
    * key and value read in turn, and a key that occurs twice, by the decoded key, refused.
    */
  def map[K, V](n: Node, what: DecodeSite)(k: Node => Result[K])(
      v: (K, Node) => Result[V]
  ): Result[Vector[(K, V)]] =
    entries(n, what)
      .flatMap(_.traverse((kn, vn) => k(kn).flatMap(key => v(key, vn).tupleLeft(key))))
      .flatMap(unique(_, what / DecodeSite.Qualifier.Key)(_._1))

  def nonEmptyMap[K, V](n: Node, what: DecodeSite)(k: Node => Result[K])(
      v: (K, Node) => Result[V]
  ): Result[Vector[(K, V)]] =
    map(n, what)(k)(v).flatMap(nonEmpty(_, what))

  /** The interval a `BoundedRatio b Word64` takes (`BaseTypes.hs:469-561`). */
  enum Interval:
    /** `UnitInterval`: 0 to 1. */
    case Unit

    /** `NonNegativeInterval`: 0 to the `Word64` maximum. */
    case NonNegative

    /** `PositiveInterval`: `1 % 10^19` (`positiveIntervalEpsilon`) to the `Word64` maximum. */
    case Positive

  private val Epsilon = Rational(1, BigInt(10).pow(19))

  /** `decCBOR` of `BoundedRatio b Word64` from version 12 (`BaseTypes.hs:407-428`):
    * `decodeIntegralRational @Word64`, the tag 30 (`assertTag`, any head width) over `decodeList`
    * of `Word64` that must hold exactly two, a zero denominator refused (`DecCBOR.hs:271-281`);
    * then `boundRational`, the reduced value in the type's interval.
    */
  def ratio(n: Node, what: DecodeSite, interval: Interval): Result[Rational] = n.value match
    case Value.Tag(t, inner) if t == 30 =>
      for
        xs <- list(inner, what)
        values <- xs.traverse(word64(_, what))
        pair <- values match
          case Vector(num, den) => Right((num, den))
          case _                => Left(LedgerDecodeError.ArrayLength(what, 2, values.size))
        (num, den) = pair
        _ <- Either.cond(den != 0, (), LedgerDecodeError.ZeroDenominator(what))
        r = Rational(num, den)
        _ <- Either.cond(
          bounded(r, interval),
          (),
          LedgerDecodeError.RationalOutOfBounds(what, r.numerator, r.denominator)
        )
      yield r
    case other => Left(LedgerDecodeError.ExpectedTag(what, 30, other))

  private def bounded(r: Rational, interval: Interval): Boolean = interval match
    case Interval.Unit        => r >= Rational(0) && r <= Rational(1)
    case Interval.NonNegative => r >= Rational(0) && r <= Rational(Word64Max)
    case Interval.Positive    => r >= Epsilon && r <= Rational(Word64Max)

  /** `decodeNestedCborBytes` and a full decode of what they hold (`Decoding.hs:213-240`;
    * `decodeCIC`, `Babbage/TxOut.hs:778-784`; `decodeBinaryData`, `Plutus/Data.hs:233-237`): the
    * tag 24, a byte string (chunked or not), and the bytes decoded as one item with nothing after
    * it. The item's own original bytes are those of the inner encoding.
    */
  def nested(n: Node, what: DecodeSite): Result[(Bytes, Node)] = n.value match
    case Value.Tag(t, inner) if t == 24 =>
      bytes(inner, what).flatMap(b => CborRead.decode(b, nestedLimits).tupleLeft(b))
    case other => Left(LedgerDecodeError.ExpectedTag(what, 24, other))

  /** Whether the item is a list (`TypeListLen`, `TypeListLen64` or `TypeListLenIndef` to
    * `peekTokenType`).
    */
  def isList(n: Node): Boolean = n.value match
    case Value.Arr(_) => true
    case _            => false
end V12
