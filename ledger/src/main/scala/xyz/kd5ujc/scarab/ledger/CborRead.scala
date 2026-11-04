// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** Total readers over decoded CBOR. Every failure names what was being read. */
/** How the unsigned keys of a record map are read. */
enum FieldKeys:
  /** At full width, as the reference reads them (`invalidField` reads a `Word`, `Binary/Coders.hs`,
    * cardano-ledger f649f975): a key beyond `Int` names no field and is
    * [[LedgerDecodeError.UnknownKey]]. Every read of a block, a transaction or a query.
    */
  case FullWidth

  /** As the builds before #379 read them, as their low 32 bits: what such a build stored still
    * reads as it read then (ADR 0006). The stores' re-reads of their own outputs alone.
    */
  case Stored

private[ledger] object CborRead:
  def decode(bytes: Bytes, limits: Cbor.Limits = Cbor.Limits()): Result[Node] =
    Cbor.decode(bytes, limits).leftMap(LedgerDecodeError.Syntax(_))

  type Result[A] = Either[LedgerDecodeError, A]

  def uint(n: Node, what: DecodeSite): Result[BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Unsigned, n.value))

  def int(n: Node, what: DecodeSite): Result[BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case Value.NInt(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Integer, n.value))

  /** A byte string in a form `strings` reads ([[ByteStrings]]): an indefinite (chunked) string,
    * whose head is `0x5f`, is refused where only a definite one is read. The value is the joined
    * chunks, and the node keeps its original bytes either way. A node built by hand without
    * original bytes was not read from a chunked string.
    */
  def bytes(n: Node, what: DecodeSite)(using strings: ByteStrings): Result[Bytes] = n.value match
    case Value.ByteString(v) =>
      Either.cond(strings.reads(n), v, LedgerDecodeError.IndefiniteLength(what))
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Bytes, n.value))

  def bytesN(n: Node, size: Int, what: DecodeSite)(using ByteStrings): Result[Bytes] =
    bytes(n, what).flatMap(b =>
      Either.cond(b.size == size, b, LedgerDecodeError.ByteLength(what, size, b.size))
    )

  /** Whether the item is a map whose length is written in eight bytes (head `0xbb`): cborg's
    * `TypeMapLen64` (`Codec/CBOR/Decoding.hs`, cborg 0.2.10.0), where `0xa0` to `0xba` are
    * `TypeMapLen` on a 64-bit machine. A decoder that chooses between a map and a list by
    * `peekTokenType` and names `TypeMapLen` alone reads such a map as a list (#701). A node built
    * by hand is not one.
    */
  def mapLength64(n: Node): Boolean = n.original.byteOr(0)(false)(b => (b & 0xff) == 0xbb)

  /** The item's head is not of indefinite length (additional information 31): a string, array or
    * map read by a decoder that takes the definite form only (the Byron decoders).
    */
  def definite(n: Node, what: DecodeSite): Result[Unit] =
    Either.cond(!n.isIndefinite, (), LedgerDecodeError.IndefiniteLength(what))

  /** A text value and whether its head is indefinite (chunked), or `None` when the item is not
    * text: the text rule's head test in one place (see [[text]]). A reader whose refusals are its
    * own takes the flag and maps it to its own error (`PoolRelays.dns`,
    * `PoolRelayError.IndefiniteDnsName`) instead of writing the head test again (#435, #562).
    */
  def indefinite(n: Node): Option[(String, Boolean)] = n.value match
    case Value.Text(v) => Some((v, n.isIndefinite))
    case _             => None

  /** A Shelley-through-Conway text field: a definite-length string only.
    *
    * These fields are read with `decodeString` (cborg, through `instance DecCBOR
    * Text` in `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs:356-358`,
    * cardano-ledger f649f975), which takes a definite string at that reference pin: an
    * indefinite (chunked) one, whose head is `0x7f`, is not text there and cborg fails it with
    * "expected string". Dijkstra uses `dijkstra.V12.text`: the newer pinned ledger permits chunked
    * text at version 12 (cardano-ledger 9df9f98c `Decoding/Decoder.hs:1564-1569,1582-1598`). Other
    * forms that stay chunked have their own readers: transaction metadata
    * (`decodeMetadatum`, `Metadata.hs:153-222`) and Plutus `Data`. A node built by hand without
    * original bytes was not read from a chunked string.
    */
  def text(n: Node, what: DecodeSite): Result[String] = indefinite(n) match
    case Some((v, chunked)) =>
      Either.cond(
        !chunked,
        v,
        LedgerDecodeError.IndefiniteLength(what)
      )
    case None => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Text, n.value))

  /** A `Url` or a `DnsName`: definite text ([[text]]) of at most 64 bytes of UTF-8 below decoder
    * version 9 and 128 from it (`instance DecCBOR Url`, `instance DecCBOR DnsName`:
    * `ifDecoderVersionAtLeast (natVersion @9) (textDecCBOR 128) (textDecCBOR 64)`, `textSizeN`
    * measuring `lengthWord8`; `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:644-697`,
    * cardano-ledger f649f975).
    */
  def url(n: Node, what: DecodeSite, version: DecoderVersion): Result[String] =
    text(n, what).flatMap { s =>
      val limit = if version.atLeast(9) then 128 else 64
      val size = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
      Either.cond(size <= limit, s, LedgerDecodeError.TextTooLong(what, limit, size))
    }

  def bool(n: Node, what: DecodeSite): Result[Boolean] = n.value match
    case Value.Bool(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Boolean, n.value))

  /** The items of a list. A detached record ([[Node.isDetached]]) is not one: the reference reads
    * an empty indefinite list as a record's head only where it reads a `RecD` record ([[record]]),
    * and anywhere else as the empty list it is, the items after it being read by the enclosing
    * decoder.
    */
  def array(n: Node, what: DecodeSite): Result[Vector[Node]] = n.value match
    case Value.Arr(xs) =>
      Either.cond(!n.isDetached, xs, LedgerDecodeError.DetachedList(what))
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Array, n.value))

  def arrayN(n: Node, size: Int, what: DecodeSite): Result[Vector[Node]] =
    array(n, what).flatMap(xs =>
      Either.cond(xs.size == size, xs, LedgerDecodeError.ArrayLength(what, size, xs.size))
    )

  /** A record the reference reads with `RecD` below decoder version 12, where every era this node
    * decodes is: `decodeCount (RecD cn) n` reads the length with `decodeRecordNamed` around a
    * decoder that reads nothing (`Decoding/Coders.hs:482`, cardano-ledger f649f975), so a non-empty
    * indefinite list meets the check for its break before its first field and fails with "Excess
    * terms in array" (`decodeListLikeT`, `Decoding/Decoder.hs:690-708`). A definite list of `size`
    * items is the record. An empty indefinite list passes that check, and the reference then reads
    * the fields from the items after its break: read with them ([[DetachedRecords]]), it is the
    * detached record of those `size` fields ([[Node.isDetached]]) and is the record too (#687). Any
    * other item is not one.
    */
  def record(n: Node, size: Int, what: DecodeSite): Result[Vector[Node]] =
    n.value match
      case Value.Arr(xs) if n.isDetached =>
        Either.cond(xs.size == size, xs, LedgerDecodeError.ArrayLength(what, size, xs.size))
      case _ =>
        array(n, what).flatMap(xs =>
          definite(n, what) *>
            Either.cond(xs.size == size, xs, LedgerDecodeError.ArrayLength(what, size, xs.size))
        )

  /** The encoding a value read as `n` is kept by when it is written into another document: its
    * original bytes, except that a detached record ([[Node.isDetached]]) is kept with the head of a
    * definite list of its fields before their original bytes (`9f ff f1 f2` is kept as `82 f1 f2`),
    * so that the document holds it as one item, as the reference's own state holds the record. The
    * reference keeps the decoded value and writes it again; nothing hashes these bytes.
    */
  def kept(n: Node): Bytes =
    n.value match
      // the fields follow the `9f ff` and end where the record does
      case Value.Arr(xs) if n.isDetached => listHead(xs.size) ++ n.original.drop(2)
      case _                             => n.original

  /** The head of a definite list of `size` items, in its shortest form. */
  private def listHead(size: Int): Bytes =
    if size < 24 then Bytes.wrap(Array((0x80 | size).toByte))
    else if size < 256 then Bytes.wrap(Array(0x98.toByte, size.toByte))
    else if size < 65536 then Bytes.wrap(Array(0x99.toByte, (size >> 8).toByte, size.toByte))
    else
      Bytes.wrap(
        Array(
          0x9a.toByte,
          (size >> 24).toByte,
          (size >> 16).toByte,
          (size >> 8).toByte,
          size.toByte
        )
      )

  def map(n: Node, what: DecodeSite): Result[Vector[(Node, Node)]] = n.value match
    case Value.Map(kvs) => Right(kvs)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Map, n.value))

  /** The elements of a set-like list. From decoder version 9 the reference allows the tag 258 and
    * does not require it (`allowTag setTag`, `decodeSetLikeEnforceNoDuplicates`, Decoder.hs); below
    * it no tag is read (`decodeSet`, `decodeList`), so a tagged list is not an array there.
    */
  def list(n: Node, what: DecodeSite, version: DecoderVersion): Result[Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 && version.atLeast(9) => array(inner, what)
    case _                                                     => array(n, what)

  /** `decodeSet` / `decodeOSet`. Duplicates are judged on the decoded elements, so two encodings of
    * one element are one element: from version 9 they fail the decode (the decoded set is smaller
    * than the count read); below it the set keeps the first occurrence (`Set.fromList <$>
    * decodeCollection`, Decoder.hs). Only the decoded elements are collapsed: the caller keeps the
    * original bytes that are hashed or signed.
    */
  def set[A](n: Node, what: DecodeSite, version: DecoderVersion)(
      elem: Node => Result[A]
  ): Result[Vector[A]] =
    list(n, what, version).flatMap(
      _.traverse(elem).flatMap(as =>
        if version.atLeast(9) then unique(as, what) else Right(as.distinct)
      )
    )

  /** A sequence the reference decodes with `decodeStrictSeq` before version 9 (duplicates kept) and
    * as an `OSet` from it. From version 9 duplicates fail the decode (`decodeOSet`,
    * `Data/OSet/Strict.hs:109-116`, f649f975): the set is the elements under the reference's
    * derived `Ord`, so `identity` maps an element to the value that `Ord` compares, which for a
    * field Scarab keeps as its original encoding is the meaning of the encoding ([[Semantic]]).
    */
  def oset[A, K](n: Node, what: DecodeSite, version: DecoderVersion)(
      elem: Node => Result[A]
  )(identity: A => K): Result[Vector[A]] =
    if version.atLeast(9) then
      list(n, what, version)
        .flatMap(_.traverse(elem))
        .flatMap(xs => unique(xs.map(identity), what).as(xs))
    else list(n, what, version).flatMap(_.traverse(elem))

  /** The elements of a list the reference reads into a `Set` and silently collapses duplicates of
    * (`Set.fromList <$> decodeList`), judged on the decoded elements. From version 9 the list must
    * not be empty (`decodeNonEmptyList`, TxWits.hs `addrWitsSetDecoder`).
    */
  def tolerantSet[A](n: Node, what: DecodeSite, version: DecoderVersion)(
      elem: Node => Result[A]
  ): Result[Vector[A]] =
    list(n, what, version)
      .flatMap(xs => nonEmpty(xs, what, version.atLeast(9)))
      .flatMap(_.traverse(elem))
      .map(_.distinct)

  /** `xs` unless it is empty where the reference refuses an empty collection. */
  def nonEmpty[A](xs: Vector[A], what: DecodeSite, required: Boolean): Result[Vector[A]] =
    Either.cond(!required || xs.nonEmpty, xs, LedgerDecodeError.Empty(what))

  /** `xs` unless an element occurs twice (a `Map` or `Set` whose size is below the count read). */
  def unique[A](xs: Vector[A], what: DecodeSite): Result[Vector[A]] =
    Either.cond(xs.distinct.size == xs.size, xs, LedgerDecodeError.DuplicateElement(what))

  /** Map with unsigned-integer keys, rejecting duplicate keys, the keys read as `keys` says. */
  def fields(
      n: Node,
      what: DecodeSite,
      keys: FieldKeys = FieldKeys.FullWidth
  ): Result[Map[Int, Node]] =
    map(n, what).flatMap { kvs =>
      kvs
        .traverse { case (k, v) =>
          uint(k, what / DecodeSite.Qualifier.Key).flatMap(key =>
            keys match
              case FieldKeys.FullWidth =>
                Either.cond(key.isValidInt, key.toInt -> v, LedgerDecodeError.UnknownKey(what, key))
              case FieldKeys.Stored => Right(key.toInt -> v)
          )
        }
        .flatMap(pairs =>
          Either
            .cond(
              pairs.map(_._1).distinct.size == pairs.size,
              pairs.toMap,
              LedgerDecodeError.DuplicateKey(what)
            )
        )
    }

  /** A constructor tag as a decoder dispatches on it: `decodeRecordSum` and `Summands` read it with
    * `decodeWord`, at full width (`Binary/Decoding/Decoder.hs:670`, `Binary/Coders.hs`,
    * cardano-ledger-binary of cardano-ledger f649f975), so a tag beyond `Int` is none of the small
    * tags of any constructor, never its low 32 bits.
    */
  def small(tag: BigInt): Option[Int] = Option.when(tag.isValidInt)(tag.toInt)

  /** The interval a rational field is bounded to (`BoundedRatio`, `BaseTypes.hs`, f649f975). */
  enum Bound:
    /** `UnitInterval`: 0 to 1 (a pool margin, a committee quorum, a voting threshold). */
    case Unit

    /** `NonNegativeInterval`: 0 up to the `Word64` maximum (a price, `a0`). */
    case NonNegative

  /** The value of tag 30 as the reference decodes it below decoder version 12, which covers every
    * version this node reads (Conway is 9): `fromCBOR` of `BoundedRatio` reads
    * `decodeRationalWithTag` (`Binary/Plain.hs:155-163`), a tag 30 over a definite or indefinite
    * array of two `decodeInteger` s (any sign, bignum tags 2 and 3 included), refuses a zero
    * denominator, and builds `n % d`; `decCBOR` dispatches to it for versions below 12 and to
    * `decodeIntegralRational` from 12 (`BaseTypes.hs:416-430`), which this node does not read. The
    * value is then bounded by `boundRational` (`BaseTypes.hs:348-374`): the reduced numerator and
    * denominator must fit the underlying `Word64`, and the value lie in the type's interval. Equal
    * rationals are one value whatever the signs, factors or integer forms they were written with.
    */
  def rational(n: Node, what: DecodeSite, bound: Bound): Result[Rational] =
    ratio(n, what).flatMap(r =>
      Either.cond(
        fits(r, bound),
        r,
        LedgerDecodeError.RationalOutOfBounds(what, r.numerator, r.denominator)
      )
    )

  /** `decodeRationalWithTag`: the reduced rational, before any bound. */
  private[ledger] def ratio(n: Node, what: DecodeSite): Result[Rational] = n.value match
    case Value.Tag(t, inner) if t == 30 =>
      arrayN(inner, 2, what).flatMap(xs =>
        (integer(xs(0), what), integer(xs(1), what)).tupled.flatMap { case (num, den) =>
          Either.cond(den != 0, Rational(num, den), LedgerDecodeError.ZeroDenominator(what))
        }
      )
    case _ => Left(LedgerDecodeError.ExpectedTag(what, 30, n.value))

  private val Word64Max = (BigInt(1) << 64) - 1

  /** `fromRationalBoundedRatio` over `Word64`. */
  private[ledger] def fits(r: Rational, bound: Bound): Boolean =
    r.numerator >= 0 && r.numerator <= Word64Max && r.denominator <= Word64Max &&
      (bound match
        case Bound.Unit        => r <= Rational.one
        case Bound.NonNegative => true)

  /** `decodeInteger` (cborg `Read.hs`, `peekTokenType` and `readBigUInt`): an integer item of any
    * head width, or a bignum, which cborg recognizes only by the one-byte heads `0xc2` and `0xc3`
    * and reads with `tryConsumeBytes`, a definite byte string. A tag 2 or 3 with a widened head
    * (`0xd8 0x02`) or over an indefinite byte string is not an integer there. Neither distinction
    * is in the decoded tree, so they are read from the original bytes of the tag and its content; a
    * definite byte string of any length head or with leading zeros is still a bignum.
    */
  def integer(n: Node, what: DecodeSite): Result[BigInt] = n.value match
    case Value.UInt(v)                                                     => Right(v)
    case Value.NInt(v)                                                     => Right(v)
    case Value.Tag(t, inner) if (t == 2 || t == 3) && bignumHead(n, inner) =>
      // cborg's bignum is a definite string in every version; `bignumHead` has checked it.
      bytes(inner, what)(using ByteStrings.Definite).map { b =>
        val magnitude = BigInt(1, b.toArray)
        if t == 2 then magnitude else -1 - magnitude
      }
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Integer, n.value))

  /** The tag is the one-byte head `0xc2` / `0xc3` and its content a definite-length byte string. */
  private def bignumHead(tag: Node, content: Node): Boolean =
    tag.original.headOption.forall(head => head == 0xc2.toByte || head == 0xc3.toByte) &&
      content.original.headOption.forall(first =>
        ((first & 0xff) >> 5) == 2
      ) && !content.isIndefinite
