// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import io.circe.parser
import java.time.Instant

/** Network configuration read from the Shelley genesis file. */
final case class ShelleyGenesis(
    networkMagic: Long,
    geometry: EpochGeometry,
    slotsPerKesPeriod: BigInt,
    maxKesEvolutions: BigInt,
    maxLovelaceSupply: BigInt,
    raw: Json
)

object Genesis:
  def shelley(text: String): Either[ConfigError, ShelleyGenesis] =
    for
      json <- parser
        .parse(text)
        .leftMap(e => ConfigError(s"shelley genesis is not JSON: ${e.message}"))
      c = json.hcursor
      magic <- field[Long](c, "networkMagic")
      start <- field[String](c, "systemStart").flatMap(s =>
        Either.catchNonFatal(Instant.parse(s)).leftMap(_ => ConfigError(s"invalid systemStart $s"))
      )
      slotLength <- field[BigDecimal](c, "slotLength")
      epochLength <- field[Long](c, "epochLength")
      k <- field[Int](c, "securityParam")
      f <- field[BigDecimal](c, "activeSlotsCoeff")
      millis <- Either.cond(
        (slotLength * 1000).isWhole,
        (slotLength * 1000).toLongExact,
        ConfigError(s"slot length $slotLength is not a whole number of milliseconds")
      )
      geometry <- EpochGeometry.checked(start, millis, epochLength, k, f)
      kesPeriod <- field[BigInt](c, "slotsPerKESPeriod")
      kesEvolutions <- field[BigInt](c, "maxKESEvolutions")
      supply <- field[BigInt](c, "maxLovelaceSupply")
    yield ShelleyGenesis(magic, geometry, kesPeriod, kesEvolutions, supply, json)

  private[node] def field[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(s"genesis field $name: ${e.message}"))
