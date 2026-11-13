// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes

/** The value of a `value` constant, the reference's `Value` (`PlutusCore/Value.hs`, plutus 1.70.0.0
  * `507cab8c`): a map from currency symbol to a map from token name to quantity, both ordered by
  * key. Its invariants are those of `Value.hs:184-201`: no inner map is empty and no quantity is
  * zero; keys are at most [[Value.Key.MaxLength]] bytes and quantities are signed 128-bit, by their
  * types. Only the constructors below make one.
  *
  * The three cached figures of the reference's representation are kept with the value
  * (`Value.hs:184-201`): [[totalSize]] (the number of `(currency, token)` entries), the size of the
  * largest inner map [[maxInnerSize]], and [[negativeAmounts]].
  */
final case class Value private (
    entries: SortedMap[Value.Key, SortedMap[Value.Key, Value.Quantity]]
):
  val totalSize: Int = entries.valuesIterator.map(_.size).sum
  val maxInnerSize: Int = entries.valuesIterator.map(_.size).maxOption.getOrElse(0)
  val negativeAmounts: Int =
    entries.valuesIterator.map(_.valuesIterator.count(_.amount.signum < 0)).sum

  /** The entries in ascending order (`toList`, `Value.hs:295-297`). */
  def toList: Vector[(Value.Key, Vector[(Value.Key, Value.Quantity)])] =
    entries.toVector.map((currency, tokens) => currency -> tokens.toVector)

object Value:
  /** A currency symbol or token name: the reference's `K`, at most 32 bytes (`Value.hs:87-101`). */
  opaque type Key = Bytes

  object Key:
    val MaxLength: Int = 32

    /** `k` (`Value.hs:99-101`). */
    def apply(bytes: Bytes): Option[Key] = Option.when(bytes.size <= MaxLength)(bytes)

    extension (k: Key) def bytes: Bytes = k

    given Ordering[Key] = summon[Ordering[Bytes]]

  /** A quantity: the reference's `Quantity`, a signed 128-bit integer (`Value.hs:123-162`). */
  opaque type Quantity = BigInt

  object Quantity:
    val MinValue: Quantity = -(BigInt(1) << 127)
    val MaxValue: Quantity = (BigInt(1) << 127) - 1

    /** `quantity` (`Value.hs:157-161`). */
    def apply(amount: BigInt): Option[Quantity] =
      Option.when(amount >= MinValue && amount <= MaxValue)(amount)

    extension (q: Quantity) def amount: BigInt = q

    given Ordering[Quantity] = Ordering.BigInt

  /** Why a list of entries is not a value. The cases are the failures of `buildValueWith`
    * (`Value.hs:630-694`), with `k` and `quantity` for keys and amounts that are not yet typed.
    */
  enum Invalid:
    case KeyTooLong(bytes: Bytes)
    case QuantityOutOfBounds(amount: BigInt)
    case CurrencySymbolsNotAscending(previous: Key, next: Key)
    case TokenNamesNotAscending(currency: Key, previous: Key, next: Key)
    case EmptyInnerMap(currency: Key)
    case ZeroQuantity(currency: Key, token: Key)

  val empty: Value = new Value(SortedMap.empty)

  /** `buildValueWith` (`Value.hs:637-694`): the value of `outer`, whose entries `currency` and
    * `token` convert, in order, refused unless currency symbols and token names are strictly
    * ascending, every inner map is non-empty and no quantity is zero. A conversion's failure is
    * returned where the reference fails it, interleaved with those checks: an outer entry is
    * converted before it is compared with the previous one, and its inner entries one by one before
    * the emptiness check.
    *
    * @param invalid
    *   the error for a structural failure of the entries
    */
  def buildWith[E, A, B](
      outer: Seq[A]
  )(currency: A => Either[E, (Key, Seq[B])], token: B => Either[E, (Key, Quantity)])(
      invalid: Invalid => E
  ): Either[E, Value] =
    @tailrec def inner(
        c: Key,
        entries: List[B],
        previous: Option[Key],
        acc: Vector[(Key, Quantity)]
    ): Either[E, Vector[(Key, Quantity)]] =
      entries match
        case Nil => Right(acc)
        case b :: rest =>
          token(b) match
            case Left(e) => Left(e)
            case Right((t, q)) =>
              previous.filterNot(p => Ordering[Key].lt(p, t)) match
                case Some(p)               => Left(invalid(Invalid.TokenNamesNotAscending(c, p, t)))
                case None if q.signum == 0 => Left(invalid(Invalid.ZeroQuantity(c, t)))
                case None                  => inner(c, rest, Some(t), acc :+ (t -> q))
    @tailrec def go(
        entries: List[A],
        previous: Option[Key],
        acc: Vector[(Key, SortedMap[Key, Quantity])]
    ): Either[E, Value] =
      entries match
        case Nil => Right(new Value(SortedMap.from(acc)))
        case a :: rest =>
          currency(a) match
            case Left(e) => Left(e)
            case Right((c, tokens)) =>
              previous.filterNot(p => Ordering[Key].lt(p, c)) match
                case Some(p) => Left(invalid(Invalid.CurrencySymbolsNotAscending(p, c)))
                case None =>
                  inner(c, tokens.toList, None, Vector.empty) match
                    case Left(e)                 => Left(e)
                    case Right(ts) if ts.isEmpty => Left(invalid(Invalid.EmptyInnerMap(c)))
                    case Right(ts) => go(rest, Some(c), acc :+ (c -> SortedMap.from(ts)))
    go(outer.toList, None, Vector.empty)

  /** `buildValueWith` over typed entries, as the text parser calls it
    * (`Parser/Builtin.hs:177-179`).
    */
  def build(outer: Seq[(Key, Seq[(Key, Quantity)])]): Either[Invalid, Value] =
    buildWith[Invalid, (Key, Seq[(Key, Quantity)]), (Key, Quantity)](outer)(Right(_), Right(_))(
      identity
    )

  /** The Flat and CBOR decoders of `Value` (`Value.hs:210-233`): every key and amount is read, and
    * typed by `k` and `quantity`, before `buildValueWith` sees the list, so a key too long or an
    * amount out of bounds anywhere is refused before any order or emptiness check.
    */
  def decoded(outer: Seq[(Bytes, Seq[(Bytes, BigInt)])]): Either[Invalid, Value] =
    def key(b: Bytes): Either[Invalid, Key] = Key(b).toRight(Invalid.KeyTooLong(b))
    def quantity(i: BigInt): Either[Invalid, Quantity] =
      Quantity(i).toRight(Invalid.QuantityOutOfBounds(i))
    val typed = outer.toVector
      .foldLeft[Either[Invalid, Vector[(Key, Vector[(Key, Quantity)])]]](Right(Vector.empty)) {
        case (Left(e), _) => Left(e)
        case (Right(acc), (c, tokens)) =>
          for
            ck <- key(c)
            ts <- tokens.toVector
              .foldLeft[Either[Invalid, Vector[(Key, Quantity)]]](Right(Vector.empty)) {
                case (Left(e), _) => Left(e)
                case (Right(done), (t, amt)) =>
                  key(t).flatMap(tk => quantity(amt).map(q => done :+ (tk -> q)))
              }
          yield acc :+ (ck -> ts)
      }
    typed.flatMap(build)

  /** `pack` (`Value.hs:246-248` with `normalize`, 334-336): the value of a nested map with every
    * zero quantity and then every empty inner map dropped. The builtins that compute a value use
    * it.
    */
  def pack(nested: SortedMap[Key, SortedMap[Key, Quantity]]): Value =
    new Value(
      nested.view
        .mapValues(_.filter((_, q) => q.signum != 0))
        .filter((_, inner) => inner.nonEmpty)
        .to(SortedMap)
    )
