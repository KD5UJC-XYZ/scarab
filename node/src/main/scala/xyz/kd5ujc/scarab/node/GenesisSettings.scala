// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.state.SlotNo
import xyz.kd5ujc.scarab.network.genesis.LeakyBucket

/** Node/Genesis.hs:90-190 at consensus 82ecba329 and Cardano/Node/Orphans.hs:48-59. These are
  * production defaults, rather than the older high-level design's 5,000-token example. Durations
  * retain the reference's picosecond precision at the policy boundary.
  */
final case class GenesisSettings(
    enableCSJ: Boolean = true,
    enableLoEAndGDD: Boolean = true,
    enableLoP: Boolean = true,
    blockFetchGracePicos: BigInt = 10 * GenesisSettings.PicosPerSecond,
    bucketCapacity: BigInt = 100000,
    bucketRate: BigInt = 500,
    jumpSlots: BigInt = 4320,
    gddIntervalPicos: BigInt = GenesisSettings.PicosPerSecond
):
  def bucket: LeakyBucket.Config =
    if enableLoP then LeakyBucket.Config(Rational(bucketCapacity), Rational(bucketRate))
    else LeakyBucket.Config.disabled

object GenesisSettings:
  val PicosPerSecond: BigInt = BigInt(10).pow(12)
  val HistoricityCutoffPicos: BigInt = BigInt(3 * 2160 * 20 + 3600) * PicosPerSecond

  def parse(value: Option[Json]): Either[ConfigError, GenesisSettings] =
    value match
      case None => Right(GenesisSettings())
      case Some(json) =>
        val cursor = json.hcursor
        def optional[A: Decoder](key: String): Either[ConfigError, Option[A]] =
          cursor
            .get[Option[A]](key)
            .leftMap(e => ConfigError(ConfigProblem.JsonField("LowLevelGenesisOptions", key, e)))
        def picos(key: String, default: BigInt): Either[ConfigError, BigInt] =
          optional[BigDecimal](key).map(
            _.fold(default)(seconds =>
              (Rational.fromDecimal(seconds) * Rational(PicosPerSecond)).floor
            )
          )
        for
          _ <- Either.cond(
            json.isObject,
            (),
            ConfigError(ConfigProblem.ExpectedObject("LowLevelGenesisOptions", json))
          )
          csj <- optional[Boolean]("EnableCSJ")
          density <- optional[Boolean]("EnableLoEAndGDD")
          patience <- optional[Boolean]("EnableLoP")
          grace <- picos("BlockFetchGracePeriod", 10 * PicosPerSecond)
          capacity <- optional[BigInt]("BucketCapacity")
          rate <- optional[BigInt]("BucketRate")
          jump <- optional[BigInt]("CSJJumpSize")
          _ <- jump.traverse_(n =>
            SlotNo.fromBigInt(n).leftMap(e => ConfigError(ConfigProblem.Ancillary(e)))
          )
          interval <- picos("GDDRateLimit", PicosPerSecond)
        yield GenesisSettings(
          csj.getOrElse(true),
          density.getOrElse(true),
          patience.getOrElse(true),
          grace,
          capacity.getOrElse(BigInt(100000)),
          rate.getOrElse(BigInt(500)),
          jump.getOrElse(BigInt(4320)),
          interval
        )
