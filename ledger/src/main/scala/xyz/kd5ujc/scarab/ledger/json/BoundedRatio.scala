// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import io.circe.Json
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.Word64

/** The `BoundedRatio b Word64` intervals of cardano-ledger f649f975
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs`): a reduced rational whose numerator
  * and denominator are within `Word64`, between the interval's `minBound` and `maxBound`.
  */
enum BoundedRatio(val lower: Rational, val upper: Rational):
  /** `UnitInterval`: `[0, 1]`. */
  case Unit extends BoundedRatio(Rational.zero, Rational.one)

  /** `NonNegativeInterval`: `[0, maxBound :: Word64]`. */
  case NonNegative extends BoundedRatio(Rational.zero, Rational(Word64.max.toBigInt))

  /** `PositiveUnitInterval`: `[positiveIntervalEpsilon, 1]`, the epsilon being `10^-19`
    * (`BaseTypes.hs:508-509`), `10 ^ maxDecimalsWord64`.
    */
  case PositiveUnit extends BoundedRatio(Rational(1, BigInt(10).pow(19)), Rational.one)

  /** `boundRational` (`fromRationalBoundedRatio`, `BaseTypes.hs:348-378`): numerator and
    * denominator of the reduced rational within `Word64`, the value within the bounds.
    */
  /** `maxDecimalsWord64` (`BaseTypes.hs:208-209`). */
  private def MaxDecimals: Int = 19

  def bound(r: Rational): Option[Rational] =
    val inWord = (n: BigInt) => n >= 0 && n <= Word64.max.toBigInt
    Option.when(inWord(r.numerator) && inWord(r.denominator) && lower <= r && r <= upper)(r)

  /** `fromRationalEither` (`BaseTypes.hs:460-462`). */
  def fromRational(r: Rational): Either[NumberError, Rational] =
    bound(r).toRight(NumberError.OutsideOfBounds(r))

  /** `fromScientificBoundedRatioWord64` (`BaseTypes.hs:444-458`): the normalized decimal, refused
    * when negative, when it has more than 19 decimal places, or when its exponent is above 19, and
    * then bounded as [[fromRational]].
    */
  def fromScientific(written: Scientific): Either[NumberError, Rational] =
    val sci = written.normalize
    val (coeff, exp10) = (sci.coefficient, sci.base10Exponent)
    if coeff < 0 then Left(NumberError.Negative(sci))
    else if exp10 <= 0 then
      if exp10 < -MaxDecimals then Left(NumberError.TooPrecise(sci))
      else fromRational(Rational(coeff, BigInt(10).pow((-exp10).toInt)))
    else if MaxDecimals < exp10 then Left(NumberError.TooBig(sci))
    else fromRational(Rational(coeff * BigInt(10).pow(exp10.toInt)))

  /** `FromJSON (BoundedRatio b Word64)` (`BaseTypes.hs:437-442`): an object is the rational form
    * `{"numerator": n, "denominator": d}`, anything else a decimal number.
    */
  def fromJson(json: Json): Either[NumberError, Rational] =
    json.asObject match
      case Some(obj) => FromJson.rational(obj).flatMap(fromRational)
      case None      => FromJson.scientific(json).flatMap(fromScientific)
