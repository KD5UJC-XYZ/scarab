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
  * of the era-indexed rules, which keep none of the old rules' bookkeeping (J2.9). New versions are
  * appended.
  */
enum FormatVersion derives CanEqual:
  case V1, V2, V3, V4, V5

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
          FormatVersion.V2 -> "scarab network identity v2"
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
    * state with a sidecar.
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
          FormatVersion.V5 -> "SCRBSNP5"
        )
      )

  /** The newest version at which the layout of this kind changed: what this build writes, and what
    * a migration brings the files of the kind to.
    */
  def current: FormatVersion = history.last._1

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
