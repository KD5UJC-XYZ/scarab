// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyList

/** The version of a persisted file format: a closed list, oldest first, so that a version this
  * build does not know cannot be represented (it is a [[StorageError.UnknownVersion]] at the point
  * the file is read). The versions are shared by all kinds of file, so that one point of the format
  * history names a whole database; a kind that did not change at a version is simply unchanged by
  * it (see [[FileKind.current]]), and a migration never rewrites it.
  *
  * History: `V1` was written under the project's former name (`cardano-scala-lab ...`, index magic
  * `CSLIDX01`); `V2` is the rename to Scarab and changes no layout of the index, format and
  * identity files; `V3` is the ledger snapshot as the Haskell ledger state plus a sidecar (the
  * chain index is unchanged by it); `V4` is the same layout with the chain-dependent state of the
  * node in the Haskell shape (the sidecar changed, J1.5); `V5` is the same layout with the sidecar
  * of the era-indexed rules, which keep none of the old rules' bookkeeping (J2.9); `V6` adds the
  * complete network identity including Alonzo and Conway genesis bindings; `V7` is the ledger
  * snapshot with a checkpoint binding in front of the V5 body (the UTxO key layout and the digest
  * of the RocksDB checkpoint the snapshot was written with, issue #395). New versions are appended.
  */
enum FormatVersion derives CanEqual:
  case V1, V2, V3, V4, V5, V6, V7

  /** The version a migration step from this one produces; none for the newest. */
  def next: Option[FormatVersion] = FormatVersion.values.drop(ordinal + 1).headOption

  def <=(other: FormatVersion): Boolean = ordinal <= other.ordinal

object FormatVersion:
  given Ordering[FormatVersion] = Ordering.by(_.ordinal)

/** The kinds of file whose first bytes name their format version. `families` are the leading texts
  * every version of the kind has ever started with: an identifier of the family that no known
  * version spells is a newer (or foreign) version, not garbage.
  *
  * `history` lists, oldest first, the versions at which the layout of the kind changed and the
  * identifier each introduced; the first is [[FormatVersion.V1]]. At any other version the kind is
  * as at the newest entry not after it, so `V3` of an index file is `V2` 's.
  */
enum FileKind(
    val label: String,
    val families: List[String],
    val history: NonEmptyList[(FormatVersion, String)]
):
  /** `<db>/network-identity`: the first line is the identifier. */
  case IdentityFile
      extends FileKind(
        "network identity",
        List("cardano-scala-lab network", "scarab network"),
        NonEmptyList.of(
          FormatVersion.V1 -> "cardano-scala-lab network identity v1",
          FormatVersion.V2 -> "scarab network identity v2",
          FormatVersion.V6 -> "scarab network identity v6"
        )
      )

  /** `<db>/immutable/format`: the identifier, then ` chunkSlots=<n>`. */
  case FormatFile
      extends FileKind(
        "immutable store format",
        List("cardano-scala-lab immutable", "scarab immutable"),
        NonEmptyList.of(
          FormatVersion.V1 -> "cardano-scala-lab immutable store v1",
          FormatVersion.V2 -> "scarab immutable store v2"
        )
      )

  /** `<db>/immutable/<c>.index`: the identifier is the 8-byte magic. `CSLIDX01` is the project's
    * former name and `SCRBIDX2` Scarab's, format 2 (the last character is the format number).
    */
  case IndexFile
      extends FileKind(
        "chunk index",
        List("CSLIDX", "SCRBIDX"),
        NonEmptyList.of(FormatVersion.V1 -> "CSLIDX01", FormatVersion.V2 -> "SCRBIDX2")
      )

  /** `<db>/snapshots/snapshot-<slot>-<blockNo>.bin`. The identifier is the 8-byte magic, and for
    * the two versions that share one (`CSLSNAP1`, which holds its format number as a 32-bit integer
    * next) the text `CSLSNAP1 v<n>`: `SCRBSNP3`, `SCRBSNP4` and `SCRBSNP5` are the Haskell ledger
    * state with a sidecar, and `SCRBSNP7` is the V5 body behind a checkpoint binding.
    */
  case SnapshotFile
      extends FileKind(
        "ledger snapshot",
        List("CSLSNAP", "SCRBSNP"),
        NonEmptyList.of(
          FormatVersion.V1 -> "CSLSNAP1 v1",
          FormatVersion.V2 -> "CSLSNAP1 v2",
          FormatVersion.V3 -> "SCRBSNP3",
          FormatVersion.V4 -> "SCRBSNP4",
          FormatVersion.V5 -> "SCRBSNP5",
          FormatVersion.V7 -> "SCRBSNP7"
        )
      )

  /** The newest version at which the layout of this kind changed: what this build writes, and what
    * a migration brings the files of the kind to.
    */
  def current: FormatVersion = history.last._1

  /** The oldest version this build can read or migrate. Older identifiers remain registered so
    * retirement is distinguished from a format this build has never known.
    */
  def readableFrom: FormatVersion = this match
    case SnapshotFile => FormatVersion.V5
    case _            => history.head._1

  def readable(what: String, version: FormatVersion): Either[StorageError, Unit] =
    Either.cond(
      readableFrom <= version,
      (),
      StorageError.RetiredVersion(what, this, version, readableFrom)
    )

  /** The next version that changes this kind after `from`; shared database versions which did not
    * alter this file kind are skipped without rewriting it.
    */
  def nextChangedVersion(from: FormatVersion): Option[FormatVersion] =
    history.toList.collectFirst { case (version, _) if from.ordinal < version.ordinal => version }

  /** How a file of this kind at version `v` starts. */
  def identifier(v: FormatVersion): String =
    history.toList.takeWhile(_._1 <= v).lastOption.getOrElse(history.head)._2

  /** The version whose identifier is `found`. A `found` of this kind's family that no version
    * spells is [[StorageError.UnknownVersion]]; anything else is [[StorageError.Corrupt]] (of
    * `what`, the file).
    */
  def versionOf(what: String, found: String): Either[StorageError, FormatVersion] =
    history
      .find(_._2 == found)
      .map(_._1)
      .toRight(
        if families.exists(found.startsWith) then StorageError.UnknownVersion(what, this, found)
        else StorageError.Corrupt(what, StorageStructureError.WrongFileKind(this))
      )

/** The layout of the keys and values of a UTxO store and its checkpoints. RocksDB has no format
  * version of its own, so a checkpoint is admitted through the snapshot that records its layout
  * ([[FileKind.SnapshotFile]] from [[FormatVersion.V7]]); a new layout is a new case here, a new
  * snapshot version and a conversion of the checkpoints (ADR 0006, ADR 0021). The reference records
  * the same fact as the tables codec version of its snapshot metadata (`TablesCodecVersion`,
  * Ouroboros/Consensus/Storage/LedgerDB/Snapshots.hs:206-227 at
  * `release-ouroboros-consensus-4.2.1.0`).
  */
enum UtxoKeyLayout derives CanEqual:
  /** `txid[32] | index u16` keys, the stamp at the one-byte key `0`, original output CBOR values,
    * all in the default column family.
    */
  case TxIdIndex16

object UtxoKeyLayout:
  /** The layout this build writes. */
  val current: UtxoKeyLayout = TxIdIndex16
