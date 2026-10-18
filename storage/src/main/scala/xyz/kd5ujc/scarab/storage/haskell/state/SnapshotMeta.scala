// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import io.circe.Json

/** Where the UTxO of a snapshot is kept (`SnapshotBackend`, `LedgerDB/Snapshots.hs:245-262`); its
  * name is in the `meta` file.
  */
enum SnapshotBackend(val name: String) derives Eq, Show:
  case InMemory extends SnapshotBackend("utxohd-mem")
  case Lmdb extends SnapshotBackend("utxohd-lmdb")
  case Lsm extends SnapshotBackend("utxohd-lsm")

object SnapshotBackend:
  def named(name: String): Option[SnapshotBackend] = values.find(_.name == name)

/** Why a `meta` file is not read. */
enum MetaError derives Eq, Show:
  /** Not JSON, or not an object with the three fields. */
  case Malformed(message: String)

  /** A backend this reader does not know. */
  case UnknownBackend(name: String)

  /** A checksum outside a `Word32`. */
  case BadChecksum(value: BigInt)

  /** A tables codec version other than 1 (`enforceVersion`, `Snapshots.hs:215-221`). */
  case UnknownTablesCodecVersion(version: BigInt)

  case UnsupportedBackend(backend: SnapshotBackend)
  case ChecksumMismatch(expected: Long, actual: Long)

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
      json <- io.circe.parser.parse(text).left.map(e => MetaError.Malformed(e.message))
      obj <- json.asObject.toRight(MetaError.Malformed("not a JSON object"))
      name <- field(obj.apply("backend"), "backend")(_.asString)
      backend <- SnapshotBackend.named(name).toRight(MetaError.UnknownBackend(name))
      crc <- field(obj.apply("checksum"), "checksum")(_.asNumber.flatMap(_.toBigInt))
      _ <- Either.cond(crc.signum >= 0 && crc.bitLength <= 32, (), MetaError.BadChecksum(crc))
      version <- field(obj.apply("tablesCodecVersion"), "tablesCodecVersion")(
        _.asNumber.flatMap(_.toBigInt)
      )
      _ <- Either.cond(version == 1, (), MetaError.UnknownTablesCodecVersion(version))
    yield SnapshotMeta(backend, crc.toLong, version.toInt)

  private def field[A](value: Option[Json], name: String)(
      read: Json => Option[A]
  ): Either[MetaError, A] =
    value.flatMap(read).toRight(MetaError.Malformed(s"missing or mistyped field $name"))
