// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.state.{NonZero, Word64}

enum LedgerDbBackend:
  case V2InMemory
  case V2LSM(databasePath: Option[String], exportPath: Option[String])

enum LedgerSnapshotInterval:
  case Default
  case Requested(slots: NonZero[Word64])

enum LedgerQueryBatchSize:
  case Default
  case Requested(size: Word64)

/** Mithril defaults: LedgerDB/Snapshots.hs:672-690 at consensus 2416493b. A default interval is
  * resolved to 40*k slots by the reference; integer durations are seconds, of either sign.
  */
final case class LedgerSnapshotSettings(
    interval: LedgerSnapshotInterval = LedgerSnapshotInterval.Default,
    number: Word64 = Word64.fromUnsigned(2),
    slotOffset: Word64 = Word64.zero,
    rateLimitSeconds: BigInt = 600,
    minimumDelaySeconds: BigInt = 300,
    maximumDelaySeconds: BigInt = 21600
)

/** POM.hs:518-608 at cardano-node b004fe0c. These describe the reference backend, not Scarab's
  * store. Explicit settings are retained and reported as unused.
  */
final case class LedgerDbSettings(
    backend: LedgerDbBackend = LedgerDbBackend.V2InMemory,
    queryBatchSize: LedgerQueryBatchSize = LedgerQueryBatchSize.Default,
    snapshots: LedgerSnapshotSettings = LedgerSnapshotSettings(),
    deprecated: List[String] = Nil,
    configured: Boolean = false
):
  def unused: List[String] =
    Option.when(configured)("LedgerDB: recognised but unused").toList ++
      deprecated.map(key => s"$key: deprecated snapshot setting, recognised but unused")

object LedgerDbSettings:
  private val snapshotKeys =
    Set("SnapshotInterval", "NumOfDiskSnapshots", "SlotOffset", "RateLimit", "MinDelay", "MaxDelay")

  private[node] def parse(k: ConfigFields): Either[ConfigError, LedgerDbSettings] =
    for
      interval <- k.nonZeroWord64("SnapshotInterval")
      number <- k.word64("NumOfDiskSnapshots")
      db <- k.obj("LedgerDB")
      deprecated = List(
        Option.when(interval.nonEmpty)("SnapshotInterval"),
        Option.when(number.nonEmpty)("NumOfDiskSnapshots")
      ).flatten
      result <- db match
        case None =>
          Right(
            LedgerDbSettings(
              snapshots = LedgerSnapshotSettings(
                interval.fold[LedgerSnapshotInterval](LedgerSnapshotInterval.Default)(
                  LedgerSnapshotInterval.Requested(_)
                ),
                number.getOrElse(Word64.fromUnsigned(2))
              ),
              deprecated = deprecated
            )
          )
        case Some(fields) =>
          readDatabase(fields, interval, number).map(
            _.copy(deprecated = deprecated, configured = true)
          )
    yield result

  private def readDatabase(
      k: ConfigFields,
      topInterval: Option[NonZero[Word64]],
      topNumber: Option[Word64]
  ): Either[ConfigError, LedgerDbSettings] =
    for
      query <- k.word64("QueryBatchSize")
      name <- k.optional[String]("Backend")
      backend <- name.getOrElse("V2InMemory") match
        case "V2InMemory" => Right(LedgerDbBackend.V2InMemory)
        case "V2LSM" =>
          (k.optional[String]("LSMDatabasePath"), k.optional[String]("LSMExportPath"))
            .mapN(LedgerDbBackend.V2LSM.apply)
        case other =>
          Left(
            ConfigError(
              ConfigProblem.Selection("LedgerDB.Backend", other, Set("V2InMemory", "V2LSM"))
            )
          )
      snapshots <- k.value("Snapshots") match
        case None => readSnapshots(k, topInterval, topNumber)
        case Some(value) =>
          val shadowed = snapshotKeys.filter(k.value(_).nonEmpty)
          for
            _ <- Either.cond(
              shadowed.isEmpty,
              (),
              ConfigError(
                ConfigProblem.ConflictingFields(
                  shadowed.map("LedgerDB." + _) + "LedgerDB.Snapshots"
                )
              )
            )
            settings <- value.asString match
              case Some("Mithril") => Right(LedgerSnapshotSettings())
              case Some(other) =>
                Left(
                  ConfigError(ConfigProblem.Selection("LedgerDB.Snapshots", other, Set("Mithril")))
                )
              case None =>
                ConfigFields
                  .read("LedgerDB.Snapshots", value)
                  .flatMap(readSnapshots(_, topInterval, topNumber))
          yield settings
    yield LedgerDbSettings(
      backend,
      query.fold[LedgerQueryBatchSize](LedgerQueryBatchSize.Default)(
        LedgerQueryBatchSize.Requested(_)
      ),
      snapshots
    )

  private def readSnapshots(
      k: ConfigFields,
      topInterval: Option[NonZero[Word64]],
      topNumber: Option[Word64]
  ): Either[ConfigError, LedgerSnapshotSettings] =
    for
      interval <- k.nonZeroWord64("SnapshotInterval")
      number <- k.word64("NumOfDiskSnapshots")
      offset <- k.word64("SlotOffset")
      rate <- k.integer("RateLimit")
      minimum <- k.integer("MinDelay")
      maximum <- k.integer("MaxDelay")
      delay <- (minimum, maximum) match
        case (Some(lo), Some(hi)) =>
          Either.cond(
            lo <= hi,
            (lo, hi),
            ConfigFields.error(ConfigFieldError.SnapshotDelayRange(lo, hi))
          )
        case _ => Right((BigInt(300), BigInt(21600)))
    // POM uses Data.Monoid.Last: the present nested value wins; both values are parsed.
    yield LedgerSnapshotSettings(
      interval
        .orElse(topInterval)
        .fold[LedgerSnapshotInterval](LedgerSnapshotInterval.Default)(
          LedgerSnapshotInterval.Requested(_)
        ),
      number.orElse(topNumber).getOrElse(Word64.fromUnsigned(2)),
      offset.getOrElse(Word64.zero),
      rate.getOrElse(BigInt(600)),
      delay._1,
      delay._2
    )
