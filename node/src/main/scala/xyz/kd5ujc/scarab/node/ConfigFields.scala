// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{Decoder, Json, JsonObject}
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.json.{FromJson, JsonKind, NumberError, Scientific}
import xyz.kd5ujc.scarab.ledger.state.{NonZero, Word64}

/** Primitive instances used by the Leios configuration reader (aeson 2.3.1.0,
  * Data/Aeson/Types/FromJSON.hs:188-213,1697-1698,1752-1753,1810-1835).
  */
private[node] final class ConfigFields private (val name: String, val fields: JsonObject):
  def value(key: String): Option[Json] = fields(key).filterNot(_.isNull)

  def optional[A: Decoder](key: String): Either[ConfigError, Option[A]] =
    value(key).traverse(_.as[A].leftMap(e => ConfigError(ConfigProblem.JsonField(name, key, e))))

  def required[A: Decoder](key: String): Either[ConfigError, A] =
    optional[A](key).flatMap(_.toRight(ConfigError(ConfigProblem.MissingField(name, key))))

  def obj(key: String): Either[ConfigError, Option[ConfigFields]] =
    value(key).traverse(ConfigFields.read(s"$name.$key", _))

  private def number[A](key: String)(read: Json => Either[NumberError, A]) =
    value(key).traverse(j =>
      read(j).leftMap(e => ConfigFields.error(ConfigFieldError.Number(name, key, e)))
    )

  def word64(key: String): Either[ConfigError, Option[Word64]] = number(key)(FromJson.word64)

  def nonZeroWord64(key: String): Either[ConfigError, Option[NonZero[Word64]]] =
    number(key)(FromJson.nonZeroWord64)

  def integer(key: String): Either[ConfigError, Option[BigInt]] = number(key)(FromJson.integer)

  /** The reference build's Int is signed 64-bit, independently of Scala's Int. */
  def int64(key: String): Either[ConfigError, Option[Long]] =
    number(key)(FromJson.scientific).flatMap(
      _.traverse(s =>
        s.toBoundedInteger(BigInt(Long.MinValue), BigInt(Long.MaxValue))
          .map(_.toLong)
          .toRight(ConfigFields.error(ConfigFieldError.NotInt64(name, key, s)))
      )
    )

  /** Optional null is absent before parseRealFloat sees it; numeric strings are not numbers. */
  def realFloat(key: String): Either[ConfigError, Option[Double]] =
    value(key).traverse {
      case j if j.asString.contains("+inf") => Right(Double.PositiveInfinity)
      case j if j.asString.contains("-inf") => Right(Double.NegativeInfinity)
      case j if j.isNumber =>
        FromJson
          .scientific(j)
          .leftMap(e => ConfigFields.error(ConfigFieldError.Number(name, key, e)))
          .map(s => s"${s.coefficient}e${s.base10Exponent}".toDouble)
      case j => Left(ConfigFields.error(ConfigFieldError.NotDouble(name, key, JsonKind.of(j))))
    }

private[node] object ConfigFields:
  def read(name: String, json: Json): Either[ConfigError, ConfigFields] =
    json.asObject
      .map(new ConfigFields(name, _))
      .toRight(ConfigError(ConfigProblem.ExpectedObject(name, json)))

  def error(cause: ConfigFieldError): ConfigError = ConfigError(ConfigProblem.Ancillary(cause))

private[node] enum ConfigFieldError extends DomainError:
  case Number(context: String, field: String, cause: NumberError)
  case NotInt64(context: String, field: String, value: Scientific)
  case NotDouble(context: String, field: String, found: JsonKind)
  case SnapshotDelayRange(minimum: BigInt, maximum: BigInt)

  def message: String = this match
    case Number(context, field, cause) => s"$context.$field: ${cause.message}"
    case NotInt64(context, field, value) =>
      s"$context.$field: not a signed 64-bit integer: ${value.show}"
    case NotDouble(context, field, found) =>
      s"$context.$field: expected a number or +/-inf, found $found"
    case SnapshotDelayRange(minimum, maximum) =>
      s"LedgerDB: MinDelay $minimum exceeds MaxDelay $maximum"
