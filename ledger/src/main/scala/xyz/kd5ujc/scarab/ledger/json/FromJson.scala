// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import io.circe.{Json, JsonObject as CirceObject}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError, Rational}
import xyz.kd5ujc.scarab.ledger.state.{NonZero, Word64}

/** The head constructor of a JSON value, as aeson's `typeOf` names it in a type mismatch
  * (`Data/Aeson/Types/FromJSON.hs:555-562`, aeson 2.2.5.1).
  */
enum JsonKind:
  case Object, Array, String, Number, Boolean, Null

object JsonKind:
  def of(json: Json): JsonKind =
    json.fold(Null, _ => Boolean, _ => Number, _ => String, _ => Array, _ => Object)

/** A key of the object form of a rational (`FromJSON (Ratio a)`). */
enum RatioKey:
  case Numerator, Denominator

  def key: String = this match
    case Numerator   => "numerator"
    case Denominator => "denominator"

/** Why a JSON value is not the number the reference's `FromJSON` instance reads. */
enum NumberError extends DomainError:
  /** `typeMismatch "Number"`. */
  case TypeMismatch(found: JsonKind)

  /** `parseBoundedIntegral "Word64"`: floating, or outside `0 .. 2^64 - 1`. */
  case NotWord64(value: Scientific)

  /** `nonZeroM` (`BaseTypes/NonZero.hs:126-129` at cardano-ledger f649f975). */
  case Zero

  /** `withBoundedScientific'` of `FromJSON Integer`: a decimal exponent beyond 1024 either way. */
  case ExponentOutOfBounds(value: Scientific)

  /** `parseIntegralFromScientific`: an `Integer` that is not whole. */
  case Floating(value: Scientific)

  /** `.:` of the object form: the key is absent. */
  case MissingKey(key: RatioKey)

  /** A component of the object form did not decode. */
  case InComponent(key: RatioKey, cause: NumberError)

  /** The object form with denominator 0. */
  case ZeroDenominator

  /** `fromScientificBoundedRatioWord64`: a negative coefficient. */
  case Negative(value: Scientific)

  /** `fromScientificBoundedRatioWord64`: more than 19 decimal places once normalized. */
  case TooPrecise(value: Scientific)

  /** `fromScientificBoundedRatioWord64`: a decimal exponent above 19 once normalized. */
  case TooBig(value: Scientific)

  /** `fromRationalEither`: outside the type's bounds, or a component outside `Word64`. */
  case OutsideOfBounds(value: Rational)

  /** The reference's text: aeson's messages (`Data/Aeson/Types/FromJSON.hs:194-212, 537-543,
    * 799-810, 918-921, 1708-1724`) and cardano-ledger's `failWith` (`BaseTypes.hs:464-465`).
    */
  def message: String = this match
    case TypeMismatch(found) => s"expected Number, but encountered $found"
    case NotWord64(value) =>
      s"parsing Word64 failed, value is either floating or will cause over or underflow ${value.shown}"
    case Zero => "Encountered zero while trying to construct a NonZero value"
    case ExponentOutOfBounds(value) =>
      val side = if value.base10Exponent > 0 then "greater than 1024" else "less than -1024"
      s"parsing Integer failed, found a number with exponent ${value.base10Exponent}, but it must not be $side"
    case Floating(value)     => s"parsing Integer failed, unexpected floating number ${value.shown}"
    case MissingKey(key)     => s"key \"${key.key}\" not found"
    case InComponent(key, e) => s"${key.key}: ${e.message}"
    case ZeroDenominator     => "Ratio denominator was 0"
    case Negative(value)     => s"Value is negative: ${value.shown}"
    case TooPrecise(value)   => s"Value is too precise: ${value.shown}"
    case TooBig(value)       => s"Value is too big: ${value.shown}"
    case OutsideOfBounds(r)  => s"Value is outside of bounds: ${FromJson.showRational(r)}"

/** Mirrors of the aeson `FromJSON` instances the genesis numbers are read with. A number is read
  * from its text by the aeson grammar ([[NativeJson]]), so its `Scientific` (coefficient and
  * exponent as written) is the one aeson builds.
  */
object FromJson:
  /** `FromJSON Scientific`: any JSON number. */
  def scientific(json: Json): Either[NumberError, Scientific] =
    json.asNumber.toRight(NumberError.TypeMismatch(JsonKind.of(json))).flatMap { n =>
      NativeJson.parse(Bytes.fromArray(n.toString.getBytes("US-ASCII"))) match
        case Right(JsonValue.Number(value)) => Right(value)
        case _                              => Left(NumberError.TypeMismatch(JsonKind.of(json)))
    }

  /** `FromJSON Word64` (`parseBoundedIntegral`, `toBoundedInteger`): a whole number in
    * `0 .. 2^64 - 1`, written in any notation (`5`, `5.0`, `0.5e1`).
    */
  def word64(json: Json): Either[NumberError, Word64] =
    scientific(json).flatMap(s =>
      s.toBoundedInteger(0, Word64.max.toBigInt)
        .toRight(NumberError.NotWord64(s))
        .map(n => Word64.fromUnsigned(n.toLong))
    )

  /** `FromJSON (NonZero Word64)` (`BaseTypes/NonZero.hs:108-109`). */
  def nonZeroWord64(json: Json): Either[NumberError, NonZero[Word64]] =
    word64(json).flatMap(w => NonZero.from(w).left.map(_ => NumberError.Zero))

  /** `FromJSON Integer` (`parseIntegral`): a whole number whose exponent, as written, is within
    * 1024 either way.
    */
  def integer(json: Json): Either[NumberError, BigInt] =
    scientific(json).flatMap { s =>
      if s.base10Exponent > 1024 || s.base10Exponent < -1024 then
        Left(NumberError.ExponentOutOfBounds(s))
      else s.wholeNumber.toRight(NumberError.Floating(s))
    }

  /** The object form of `FromJSON (Ratio Integer)` (`FromJSON.hs:1717-1724`):
    * `{"numerator": n, "denominator": d}`, reduced, with the sign on the numerator.
    */
  def rational(obj: CirceObject): Either[NumberError, Rational] =
    def component(key: RatioKey) =
      obj(key.key)
        .toRight(NumberError.MissingKey(key))
        .flatMap(integer(_).left.map(NumberError.InComponent(key, _)))
    for
      n <- component(RatioKey.Numerator)
      d <- component(RatioKey.Denominator)
      r <- Either.cond(d != 0, Rational(n, d), NumberError.ZeroDenominator)
    yield r

  /** `show` of a `Rational` (`GHC.Real`): `n % d`, a negative numerator in parentheses. */
  def showRational(r: Rational): String =
    val n = if r.numerator < 0 then s"(${r.numerator})" else r.numerator.toString
    s"$n % ${r.denominator}"
