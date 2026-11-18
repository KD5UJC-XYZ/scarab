// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{DefaultFun, PlutusData, Value}
import xyz.kd5ujc.scarab.uplc.Value.{Key, Quantity}

/** The `Value` builtins of plutus 1.70.0.0 (`507cab8c`), `PlutusCore/Value.hs` (cited by line),
  * over [[Value]], with the policy filters of 1.71.0.0 (`261766ad`). 1.71.0.0 caches one more
  * figure with a value (the negative amounts of each currency, `Value.hs:184-208` there), which
  * changes no result. The reference keeps cached figures with a value and updates them as it goes;
  * [[Value]] does so in `insertCoin` and `deleteCoin` ([[Value.updated]], [[Value.removed]]), and
  * counts them for every other result, which is built through [[Value.pack]] or
  * [[Value.buildWith]].
  */
object Values:
  /** `valueDataMaxSize` (`Value.hs:540-541`, plutus 1.70.0.0). */
  val ValueDataMaxSize: Int = 40000

  private type Inner = SortedMap[Key, Quantity]
  private type Nested = SortedMap[Key, Inner]

  /** `insertCoin` (`Value.hs:345-384`, plutus 1.70.0.0): the quantity of `(currency, token)` set to
    * `amount`; an amount of 0 deletes the entry without checking the keys (`deleteCoin`, 387-411),
    * otherwise the keys and the amount are checked in that order.
    */
  def insertCoin(
      currency: Bytes,
      token: Bytes,
      amount: BigInt,
      value: Value
  ): Either[ValueFailure, Value] =
    if amount.signum == 0 then Right(deleteCoin(currency, token, value))
    else
      for
        c <- Key(currency).toRight(ValueFailure.InvalidCurrency(currency))
        t <- Key(token).toRight(ValueFailure.InvalidToken(token))
        q <- Quantity(amount).toRight(ValueFailure.QuantityOutOfBounds(amount))
      yield value.updated(c, t, q)

  /** `deleteCoin` (`Value.hs:387-411`, plutus 1.70.0.0): a key too long for a value is in none. */
  private def deleteCoin(currency: Bytes, token: Bytes, value: Value): Value =
    (Key(currency), Key(token)) match
      case (Some(c), Some(t)) => value.removed(c, t)
      case _                  => value

  /** `lookupCoin` (`Value.hs:413-418`, plutus 1.70.0.0): 0 for an entry the value does not have. */
  def lookupCoin(currency: Bytes, token: Bytes, value: Value): BigInt =
    (for
      c <- Key(currency)
      t <- Key(token)
      inner <- value.entries.get(c)
      q <- inner.get(t)
    yield q.amount).getOrElse(BigInt(0))

  /** `valueContains` (`Value.hs:487-498`, plutus 1.70.0.0): neither value has a negative amount,
    * and every entry of `b` is in `a` with at least its quantity (`isSubmapOfBy`).
    */
  def valueContains(a: Value, b: Value): Either[ValueFailure, Boolean] =
    if a.negativeAmounts > 0 then Left(ValueFailure.FirstValueNegative)
    else if b.negativeAmounts > 0 then Left(ValueFailure.SecondValueNegative)
    else if a.totalSize < b.totalSize then Right(false)
    else
      Right(b.entries.forall { (c, tokens) =>
        a.entries
          .get(c)
          .exists(inner =>
            tokens.forall((t, q) => inner.get(t).exists(have => q.amount <= have.amount))
          )
      })

  /** `unionValue` (`Value.hs:508-538`, plutus 1.70.0.0): the sum of the quantities, an entry whose
    * sum is 0 dropped, failing when a sum leaves the 128-bit bounds.
    */
  def unionValue(a: Value, b: Value): Either[ValueFailure, Value] =
    if a.totalSize == 0 then Right(b)
    else if b.totalSize == 0 then Right(a)
    else
      val currencies = (a.entries.keySet ++ b.entries.keySet).toVector
      val summed = currencies.foldLeft[Either[ValueFailure, Nested]](Right(SortedMap.empty)) {
        case (Left(e), _) => Left(e)
        case (Right(acc), c) =>
          val x = a.entries.getOrElse(c, SortedMap.empty[Key, Quantity])
          val y = b.entries.getOrElse(c, SortedMap.empty[Key, Quantity])
          (x.keySet ++ y.keySet).toVector
            .foldLeft[Either[ValueFailure, Inner]](Right(SortedMap.empty)) {
              case (Left(e), _) => Left(e)
              case (Right(inner), t) =>
                val sum = x.get(t).fold(BigInt(0))(_.amount) + y.get(t).fold(BigInt(0))(_.amount)
                Quantity(sum).toRight(ValueFailure.UnionOutOfBounds).map(inner.updated(t, _))
            }
            .map(acc.updated(c, _))
      }
      summed.map(Value.pack)

  /** `valueData` (`Value.hs:545-553`, plutus 1.70.0.0):
    * `Map [(B currency, Map [(B token, I quantity)])]` in ascending order, for a value of at most
    * [[ValueDataMaxSize]] entries.
    */
  def valueData(value: Value): Either[ValueFailure, PlutusData] =
    Either.cond(
      value.totalSize <= ValueDataMaxSize,
      PlutusData.Map(value.toList.map { (c, tokens) =>
        PlutusData.B(c.bytes) -> PlutusData.Map(
          tokens.map((t, q) => PlutusData.B(t.bytes) -> PlutusData.I(q.amount))
        )
      }),
      ValueFailure.ValueDataTooLarge
    )

  /** `unValueData` (`Value.hs:557-586`, plutus 1.70.0.0) through `buildValueWith` (637-694), each
    * entry converted before it is compared with the one before.
    */
  def unValueData(data: PlutusData): Either[ValueFailure, Value] =
    def failed(cause: UnValueDataCause): ValueFailure = ValueFailure.UnValueData(cause)
    def key(d: PlutusData): Either[ValueFailure, Key] = d match
      case PlutusData.B(b) => Key(b).toRight(failed(UnValueDataCause.InvalidKey(b)))
      case _               => Left(failed(UnValueDataCause.NonB))
    def map(d: PlutusData): Either[ValueFailure, Vector[(PlutusData, PlutusData)]] = d match
      case PlutusData.Map(entries) => Right(entries)
      case _                       => Left(failed(UnValueDataCause.NonMap))
    def quantity(d: PlutusData): Either[ValueFailure, Quantity] = d match
      case PlutusData.I(i) => Quantity(i).toRight(failed(UnValueDataCause.InvalidQuantity))
      case _               => Left(failed(UnValueDataCause.NonI))
    map(data).flatMap(outer =>
      Value.buildWith[ValueFailure, (PlutusData, PlutusData), (PlutusData, PlutusData)](outer)(
        (c, tokens) => key(c).flatMap(k => map(tokens).map(k -> _)),
        (t, q) => key(t).flatMap(k => quantity(q).map(k -> _))
      ) {
        case Value.Invalid.CurrencySymbolsNotAscending(_, _) =>
          failed(UnValueDataCause.CurrencySymbolsNotAscending)
        case Value.Invalid.TokenNamesNotAscending(_, _, _) =>
          failed(UnValueDataCause.TokenNamesNotAscending)
        case Value.Invalid.EmptyInnerMap(_)       => failed(UnValueDataCause.EmptyInnerMap)
        case Value.Invalid.ZeroQuantity(_, _)     => failed(UnValueDataCause.ZeroQuantity)
        case Value.Invalid.KeyTooLong(b)          => failed(UnValueDataCause.InvalidKey(b))
        case Value.Invalid.QuantityOutOfBounds(_) => failed(UnValueDataCause.InvalidQuantity)
      }
    )

  /** `policies` (`Value.hs:419-424`, plutus 1.70.0.0): the currency symbols in ascending order. */
  def policies(value: Value): Vector[Bytes] = value.entries.keysIterator.map(_.bytes).toVector

  /** The ids that can name a currency (`policySet`, `Value.hs:469-477`, plutus 1.70.0.0): longer
    * ones match none.
    */
  private def currencies(ids: Vector[Bytes]): Set[Key] = ids.flatMap(Key(_)).toSet

  /** `policyFilterMaxSize` (`Value.hs:466-469`, plutus 1.71.0.0 `261766ad`): the most currencies
    * `keepPolicies` and `dropPolicies` accept in their value.
    */
  val PolicyFilterMaxSize: Int = 8191

  /** The value, unless it has more than [[PolicyFilterMaxSize]] currencies, which `function`
    * refuses (`policyFilterOverflow`, `Value.hs:471-475`, plutus 1.71.0.0) before it reads `ids`.
    */
  private def filterable(function: DefaultFun, value: Value): Either[ValueFailure, Value] =
    Either.cond(
      value.entries.sizeIs <= PolicyFilterMaxSize,
      value,
      ValueFailure.TooManyPolicies(function)
    )

  /** `keepPolicies` (`Value.hs:477-493`, plutus 1.71.0.0): the value restricted to the currencies
    * `ids` name, refused for a value of more than [[PolicyFilterMaxSize]] currencies. 1.70.0.0
    * (`Value.hs:425-436` at `507cab8c`) has no such limit; it keeps the same currencies.
    */
  def keepPolicies(ids: Vector[Bytes], value: Value): Either[ValueFailure, Value] =
    filterable(DefaultFun.KeepPolicies, value).map { v =>
      val kept = currencies(ids)
      Value.pack(v.entries.filter((c, _) => kept.contains(c)))
    }

  /** `dropPolicies` (`Value.hs:495-503`, plutus 1.71.0.0): the value without the currencies `ids`
    * name, refused for a value of more than [[PolicyFilterMaxSize]] currencies, which 1.70.0.0
    * (`Value.hs:437-467` at `507cab8c`) does not refuse.
    */
  def dropPolicies(ids: Vector[Bytes], value: Value): Either[ValueFailure, Value] =
    filterable(DefaultFun.DropPolicies, value).map(v => Value.pack(v.entries -- currencies(ids)))

  /** `scaleValue` (`Value.hs:604-628`, plutus 1.70.0.0): every quantity times `factor`, the empty
    * value for 0, the first product out of bounds in key order failing.
    */
  def scaleValue(factor: BigInt, value: Value): Either[ValueFailure, Value] =
    if factor.signum == 0 then Right(Value.empty)
    else
      value.entries.toVector
        .foldLeft[Either[ValueFailure, Nested]](Right(SortedMap.empty)) {
          case (Left(e), _) => Left(e)
          case (Right(acc), (c, tokens)) =>
            tokens.toVector
              .foldLeft[Either[ValueFailure, Inner]](Right(SortedMap.empty)) {
                case (Left(e), _) => Left(e)
                case (Right(inner), (t, q)) =>
                  Quantity(factor * q.amount)
                    .toRight(ValueFailure.ScaleOutOfBounds(factor, q.amount))
                    .map(inner.updated(t, _))
              }
              .map(acc.updated(c, _))
        }
        .map(Value.pack)
