// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import io.circe.{Json, ParsingFailure}

/** Where the UTxO of a snapshot is kept (`SnapshotBackend`, `LedgerDB/Snapshots.hs:245-262`); its
  * name is in the `meta` file.
  */
enum SnapshotBackend(val name: String) derives Eq, Show:
  case InMemory extends SnapshotBackend("utxohd-mem")
  case Lmdb extends SnapshotBackend("utxohd-lmdb")
  case Lsm extends SnapshotBackend("utxohd-lsm")

object SnapshotBackend:
  def named(name: String): Option[SnapshotBackend] = values.find(_.name == name)

/** The three fields of the reference's snapshot metadata. */
enum MetaField(val name: String) derives Eq, Show:
  case Backend extends MetaField("backend")
  case Checksum extends MetaField("checksum")
  case TablesCodecVersion extends MetaField("tablesCodecVersion")

/** Why a `meta` file is not read. */
enum MetaError derives Eq, Show:
  case JsonSyntax(cause: ParsingFailure)
  case ExpectedObject(actual: Json)
  case MissingField(field: MetaField)
  case FieldTypeMismatch(field: MetaField, actual: Json)

  /** A backend this reader does not know. */
  case UnknownBackend(name: String)

  /** A checksum outside a `Word32`. */
  case BadChecksum(value: BigInt)

  /** A tables codec version other than 1 (`enforceVersion`, `Snapshots.hs:215-221`). */
  case UnknownTablesCodecVersion(version: BigInt)

  case UnsupportedBackend(backend: SnapshotBackend)
  case ChecksumMismatch(expected: Long, actual: Long)

object MetaError:
  private given Eq[ParsingFailure] = Eq.fromUniversalEquals
  private given Show[ParsingFailure] = Show.fromToString

/** The `meta` file of a ledger snapshot (`SnapshotMetadata`, `LedgerDB/Snapshots.hs:223-243`): a
  * JSON object `{"backend": ..., "checksum": <word32>, "tablesCodecVersion": 1}`. The checksum is
  * the CRC32 of the decimal text of the CRC32 of `state` followed by that of `tables`
  * (`crcOfConcat`, `Util/CRC.hs:20-26`; `V2/InMemory.hs:223`), not the CRC of the two files
  * together. [[SnapshotMeta.verify]] checks that rule for the Mem backend.
  */
final case class SnapshotMeta(backend: SnapshotBackend, checksum: Long, tablesCodecVersion: Int)
    derives Eq,
      Show

object SnapshotMeta:
  def fromCrcs(state: SnapshotCrc, tables: SnapshotCrc): SnapshotMeta =
    SnapshotMeta(SnapshotBackend.InMemory, SnapshotCrc.combined(state, tables).value, 1)

  /** The compact three-field JSON in the reference writer's order (`Snapshots.hs:223-243`). */
  def encode(meta: SnapshotMeta): Either[MetaError, String] =
    valid(meta).as(
      Json
        .obj(
          "backend" -> Json.fromString(meta.backend.name),
          "checksum" -> Json.fromLong(meta.checksum),
          "tablesCodecVersion" -> Json.fromInt(meta.tablesCodecVersion)
        )
        .noSpaces
    )

  def verify(meta: SnapshotMeta, state: SnapshotCrc, tables: SnapshotCrc): Either[MetaError, Unit] =
    for
      _ <- valid(meta)
      _ <- Either.cond(
        meta.backend == SnapshotBackend.InMemory,
        (),
        MetaError.UnsupportedBackend(meta.backend)
      )
      actual = SnapshotCrc.combined(state, tables).value
      _ <- Either.cond(
        meta.checksum == actual,
        (),
        MetaError.ChecksumMismatch(meta.checksum, actual)
      )
    yield ()

  private def valid(meta: SnapshotMeta): Either[MetaError, Unit] =
    for
      _ <- Either.cond(
        meta.checksum >= 0 && meta.checksum <= 0xffffffffL,
        (),
        MetaError.BadChecksum(BigInt(meta.checksum))
      )
      _ <- Either.cond(
        meta.tablesCodecVersion == 1,
        (),
        MetaError.UnknownTablesCodecVersion(BigInt(meta.tablesCodecVersion))
      )
    yield ()

  /** The fields of the file, or what is wrong with it. */
  def parse(text: String): Either[MetaError, SnapshotMeta] =
    for
      json <- io.circe.parser.parse(text).left.map(e => MetaError.JsonSyntax(e))
      obj <- json.asObject.toRight(MetaError.ExpectedObject(json))
      name <- field(obj.apply(MetaField.Backend.name), MetaField.Backend)(_.asString)
      backend <- SnapshotBackend.named(name).toRight(MetaError.UnknownBackend(name))
      crc <- field(obj.apply(MetaField.Checksum.name), MetaField.Checksum)(
        _.asNumber.flatMap(_.toBigInt)
      )
      _ <- Either.cond(crc.signum >= 0 && crc.bitLength <= 32, (), MetaError.BadChecksum(crc))
      version <- field(obj.apply(MetaField.TablesCodecVersion.name), MetaField.TablesCodecVersion)(
        _.asNumber.flatMap(_.toBigInt)
      )
      _ <- Either.cond(version == 1, (), MetaError.UnknownTablesCodecVersion(version))
    yield SnapshotMeta(backend, crc.toLong, version.toInt)

  private def field[A](value: Option[Json], field: MetaField)(
      read: Json => Option[A]
  ): Either[MetaError, A] =
    for
      json <- value.toRight(MetaError.MissingField(field))
      result <- read(json).toRight(MetaError.FieldTypeMismatch(field, json))
    yield result
