// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** The version of a persisted file format: a closed list, oldest first, so that a version this
  * build does not know cannot be represented (it is a [[StorageError.UnknownVersion]] at the point
  * the file is read). Every kind of file keeps its own identifier per version
  * ([[FileKind.identifier]]); the versions are shared so that one migration run moves a whole
  * database from one point of the format history to the next.
  *
  * History: `V1` was written under the project's former name (`cardano-scala-lab ...`, index magic
  * `CSLIDX01`); `V2` is the rename to Scarab and changes no layout. New versions are appended.
  */
enum FormatVersion derives CanEqual:
  case V1, V2

  /** The version a migration step from this one produces; none for the newest. */
  def next: Option[FormatVersion] = FormatVersion.values.drop(ordinal + 1).headOption

object FormatVersion:
  /** The version this build writes. */
  val Current: FormatVersion = V2

  given Ordering[FormatVersion] = Ordering.by(_.ordinal)

/** The kinds of file whose first bytes name their format version. `families` are the leading texts
  * every version of the kind has ever started with: an identifier of the family that no known
  * version spells is a newer (or foreign) version, not garbage.
  */
enum FileKind(val label: String, val families: List[String]):
  /** `<db>/network-identity`: the first line is the identifier. */
  case IdentityFile
      extends FileKind("network identity", List("cardano-scala-lab network", "scarab network"))

  /** `<db>/immutable/format`: the identifier, then ` chunkSlots=<n>`. */
  case FormatFile
      extends FileKind(
        "immutable store format",
        List("cardano-scala-lab immutable", "scarab immutable")
      )

  /** `<db>/immutable/<c>.index`: the identifier is the 8-byte magic. */
  case IndexFile extends FileKind("chunk index", List("CSLIDX", "SCRBIDX"))

  /** How a file of this kind at version `v` starts. The index magics are 8 ASCII characters:
    * `CSLIDX01` (the project's former name) and `SCRBIDX2` (Scarab, format 2; the last character is
    * the format number, so the next version is `SCRBIDX3`).
    */
  def identifier(v: FormatVersion): String = (this, v) match
    case (IdentityFile, FormatVersion.V1) => "cardano-scala-lab network identity v1"
    case (IdentityFile, FormatVersion.V2) => "scarab network identity v2"
    case (FormatFile, FormatVersion.V1)   => "cardano-scala-lab immutable store v1"
    case (FormatFile, FormatVersion.V2)   => "scarab immutable store v2"
    case (IndexFile, FormatVersion.V1)    => "CSLIDX01"
    case (IndexFile, FormatVersion.V2)    => "SCRBIDX2"

  /** The version whose identifier is `found`. A `found` of this kind's family that no version
    * spells is [[StorageError.UnknownVersion]]; anything else is [[StorageError.Corrupt]] (of
    * `what`, the file).
    */
  def versionOf(what: String, found: String): Either[StorageError, FormatVersion] =
    FormatVersion.values
      .find(identifier(_) == found)
      .toRight(
        if families.exists(found.startsWith) then StorageError.UnknownVersion(what, this, found)
        else StorageError.Corrupt(what, s"not a $label file")
      )
