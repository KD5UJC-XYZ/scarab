// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import io.circe.Json
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.json.{BoundedRatio, NumberError, Scientific}

/** `PositiveUnitInterval` (cardano-ledger f649f975, `libs/cardano-ledger-core/src/Cardano/Ledger/
  * BaseTypes.hs:512-531`): a `BoundedRatio` of `Word64` numerator and denominator in `[10^-19, 1]`.
  * The lower bound is `positiveIntervalEpsilon` (`508-509`), the smallest decimal that survives a
  * JSON round trip, so the interval is the `(0, 1]` of the documentation as JSON can write it. Held
  * as the reduced rational; the only ways in check both bounds.
  */
opaque type PositiveUnitInterval = Rational

object PositiveUnitInterval:
  /** `boundRational` ([[BoundedRatio.bound]]). */
  def boundRational(r: Rational): Option[PositiveUnitInterval] = BoundedRatio.PositiveUnit.bound(r)

  /** `fromRationalEither` ([[BoundedRatio.fromRational]]). */
  def fromRational(r: Rational): Either[NumberError, PositiveUnitInterval] =
    BoundedRatio.PositiveUnit.fromRational(r)

  /** `fromScientificBoundedRatioWord64` ([[BoundedRatio.fromScientific]]). */
  def fromScientific(written: Scientific): Either[NumberError, PositiveUnitInterval] =
    BoundedRatio.PositiveUnit.fromScientific(written)

  /** `FromJSON (BoundedRatio b Word64)` ([[BoundedRatio.fromJson]]). */
  def fromJson(json: Json): Either[NumberError, PositiveUnitInterval] =
    BoundedRatio.PositiveUnit.fromJson(json)

  extension (p: PositiveUnitInterval)
    /** `unboundRational`. */
    def rational: Rational = p
    def numerator: BigInt = p.numerator
    def denominator: BigInt = p.denominator
