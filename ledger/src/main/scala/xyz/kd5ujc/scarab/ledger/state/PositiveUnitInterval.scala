// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import io.circe.Json
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.json.{FromJson, NumberError, Scientific}

/** `PositiveUnitInterval` (cardano-ledger f649f975, `libs/cardano-ledger-core/src/Cardano/Ledger/
  * BaseTypes.hs:512-531`): a `BoundedRatio` of `Word64` numerator and denominator in `[10^-19, 1]`.
  * The lower bound is `positiveIntervalEpsilon` (`508-509`), the smallest decimal that survives a
  * JSON round trip, so the interval is the `(0, 1]` of the documentation as JSON can write it. Held
  * as the reduced rational; the only ways in check both bounds.
  */
opaque type PositiveUnitInterval = Rational

object PositiveUnitInterval:
  /** `maxDecimalsWord64` (`BaseTypes.hs:208-209`). */
  private val MaxDecimals: Int = 19

  private val minBound: Rational = Rational(1, BigInt(10).pow(MaxDecimals))
  private val maxBound: Rational = Rational.one
  private val maxWord: BigInt = Word64.max.toBigInt

  /** `boundRational` (`fromRationalBoundedRatio`, `BaseTypes.hs:348-378`): numerator and
    * denominator of the reduced rational within `Word64`, the value within the bounds.
    */
  def boundRational(r: Rational): Option[PositiveUnitInterval] =
    val inWord = (n: BigInt) => n >= 0 && n <= maxWord
    Option.when(
      inWord(r.numerator) && inWord(r.denominator) && minBound <= r && r <= maxBound
    )(r)

  /** `fromRationalEither` (`BaseTypes.hs:460-462`). */
  def fromRational(r: Rational): Either[NumberError, PositiveUnitInterval] =
    boundRational(r).toRight(NumberError.OutsideOfBounds(r))

  /** `fromScientificBoundedRatioWord64` (`BaseTypes.hs:444-458`): the normalized decimal, refused
    * when negative, when it has more than 19 decimal places, or when its exponent is above 19, and
    * then bounded as [[fromRational]].
    */
  def fromScientific(written: Scientific): Either[NumberError, PositiveUnitInterval] =
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
  def fromJson(json: Json): Either[NumberError, PositiveUnitInterval] =
    json.asObject match
      case Some(obj) => FromJson.rational(obj).flatMap(fromRational)
      case None      => FromJson.scientific(json).flatMap(fromScientific)

  extension (p: PositiveUnitInterval)
    /** `unboundRational`. */
    def rational: Rational = p
    def numerator: BigInt = p.numerator
    def denominator: BigInt = p.denominator
