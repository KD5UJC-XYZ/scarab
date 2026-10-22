// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** Total readers over decoded CBOR. Every failure names what was being read. */
private[ledger] object CborRead:
  def decode(bytes: Bytes, limits: Cbor.Limits = Cbor.Limits()): Result[Node] =
    Cbor.decode(bytes, limits).leftMap(LedgerDecodeError.Syntax(_))

  type Result[A] = Either[LedgerDecodeError, A]

  def uint(n: Node, what: String): Result[BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Unsigned, n.value))

  def int(n: Node, what: String): Result[BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case Value.NInt(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Integer, n.value))

  def bytes(n: Node, what: String): Result[Bytes] = n.value match
    case Value.ByteString(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Bytes, n.value))

  def bytesN(n: Node, size: Int, what: String): Result[Bytes] =
    bytes(n, what).flatMap(b =>
      Either.cond(b.size == size, b, LedgerDecodeError.ByteLength(what, size, b.size))
    )

  def text(n: Node, what: String): Result[String] = n.value match
    case Value.Text(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Text, n.value))

  def bool(n: Node, what: String): Result[Boolean] = n.value match
    case Value.Bool(v) => Right(v)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Boolean, n.value))

  def array(n: Node, what: String): Result[Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Array, n.value))

  def arrayN(n: Node, size: Int, what: String): Result[Vector[Node]] =
    array(n, what).flatMap(xs =>
      Either.cond(xs.size == size, xs, LedgerDecodeError.ArrayLength(what, size, xs.size))
    )

  def map(n: Node, what: String): Result[Vector[(Node, Node)]] = n.value match
    case Value.Map(kvs) => Right(kvs)
    case _ => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Map, n.value))

  /** The elements of a set-like list. From decoder version 9 the reference allows the tag 258 and
    * does not require it (`allowTag setTag`, `decodeSetLikeEnforceNoDuplicates`, Decoder.hs); below
    * it no tag is read (`decodeSet`, `decodeList`), so a tagged list is not an array there.
    */
  def list(n: Node, what: String, version: DecoderVersion): Result[Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 && version.atLeast(9) => array(inner, what)
    case _                                                     => array(n, what)

  /** `decodeSet` / `decodeOSet`. From version 9 duplicates fail the decode (the decoded set is
    * smaller than the count read), judged on the decoded elements, so two encodings of one element
    * are duplicates; below it the set keeps the first occurrence (`Set.fromList`).
    */
  def set[A](n: Node, what: String, version: DecoderVersion)(
      elem: Node => Result[A]
  ): Result[Vector[A]] =
    list(n, what, version).flatMap { xs =>
      if version.atLeast(9) then xs.traverse(elem).flatMap(unique(_, what))
      else xs.distinctBy(_.original).traverse(elem)
    }

  /** A sequence the reference decodes with `decodeStrictSeq` before version 9 (duplicates kept) and
    * as an `OSet` from it. From version 9 duplicates fail the decode (`decodeOSet`,
    * `Data/OSet/Strict.hs:109-116`, f649f975): the set is the elements under the reference's
    * derived `Ord`, so `identity` maps an element to the value that `Ord` compares, which for a
    * field Scarab keeps as its original encoding is the meaning of the encoding ([[Semantic]]).
    */
  def oset[A, K](n: Node, what: String, version: DecoderVersion)(
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
  def tolerantSet[A](n: Node, what: String, version: DecoderVersion)(
      elem: Node => Result[A]
  ): Result[Vector[A]] =
    list(n, what, version)
      .flatMap(xs => nonEmpty(xs, what, version.atLeast(9)))
      .flatMap(_.traverse(elem))
      .map(_.distinct)

  /** `xs` unless it is empty where the reference refuses an empty collection. */
  def nonEmpty[A](xs: Vector[A], what: String, required: Boolean): Result[Vector[A]] =
    Either.cond(!required || xs.nonEmpty, xs, LedgerDecodeError.Empty(what))

  /** `xs` unless an element occurs twice (a `Map` or `Set` whose size is below the count read). */
  def unique[A](xs: Vector[A], what: String): Result[Vector[A]] =
    Either.cond(xs.distinct.size == xs.size, xs, LedgerDecodeError.DuplicateElement(what))

  /** Map with unsigned-integer keys, rejecting duplicate keys. */
  def fields(n: Node, what: String): Result[Map[Int, Node]] =
    map(n, what).flatMap { kvs =>
      kvs
        .traverse { case (k, v) => uint(k, s"$what key").map(_.toInt -> v) }
        .flatMap(pairs =>
          Either
            .cond(
              pairs.map(_._1).distinct.size == pairs.size,
              pairs.toMap,
              LedgerDecodeError.DuplicateKey(what)
            )
        )
    }

  def rational(n: Node, what: String): Result[Rational] = n.value match
    case Value.Tag(t, inner) if t == 30 =>
      arrayN(inner, 2, what).flatMap(xs =>
        (uint(xs(0), what), uint(xs(1), what)).tupled.flatMap { case (num, den) =>
          Either.cond(den > 0, Rational(num, den), LedgerDecodeError.ZeroDenominator(what))
        }
      )
    case _ => Left(LedgerDecodeError.ExpectedTag(what, 30, n.value))
