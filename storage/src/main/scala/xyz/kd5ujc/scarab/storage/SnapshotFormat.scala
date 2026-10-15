// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Show
import xyz.kd5ujc.scarab.consensus.state.StateMirrorError
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.haskell.state.StateNotWritable

/** Why a state was not written as a snapshot, or a snapshot not brought to the current format. A
  * snapshot is an optimisation (a restart without one replays the immutable blocks), so a refusal
  * is a typed event and the snapshot is skipped, never an error of the node.
  */
enum SnapshotRefusal:
  /** The ledger-state mirror refused the state (it does not convert, or not into a telescope). */
  case Mirror(error: StateMirrorError)

  /** The Haskell encoding refused a value outside the range of the Haskell type. */
  case NotWritable(error: StateNotWritable)

  /** An older snapshot that this build cannot read (corrupt, or of another schema). */
  case Unreadable(error: StorageError)

  /** What was written does not read back (a state the Haskell encoding cannot hold, as the state of
    * an era with a pending parameter update that names a parameter the era lacks): a snapshot that
    * could not be restored is not written.
    */
  case WouldNotRead(error: StorageError)

  /** What was written reads back as another state than the one written; `parts` name the fields (of
    * the node state, two levels deep) that differ, or none when only the anchor does.
    */
  case WouldDiffer(parts: List[String])

object SnapshotRefusal:
  given Show[SnapshotRefusal] = Show.show {
    case Mirror(e)       => s"the mirror refused the state: ${Show[StateMirrorError].show(e)}"
    case NotWritable(e)  => s"not representable in the Haskell encoding: ${e.violation}"
    case Unreadable(e)   => e.message
    case WouldNotRead(e) => s"the snapshot would not read back: ${e.message}"
    case WouldDiffer(parts) =>
      s"the snapshot would read back as another state, in ${parts.mkString(", ")}"
  }

/** The content of a snapshot file, without its checksum: what [[FileLedgerSnapshots]] stores around
  * (the SHA-256 of the content closes the file, and the file is written atomically). The format
  * decides everything inside: [[SnapshotV3]] is the Haskell ledger state with a sidecar, and
  * [[SnapshotV2]] the one-codec layout of the builds before it (kept to read, and for the stores'
  * own tests).
  *
  * The effect `F` is where a format reads what it needs (the V3 format asks for the mirror's
  * environment); a refusal or an unreadable file is a value, not a failure of `F`.
  */
trait SnapshotFormat[F[_], S]:
  def encode(snapshot: StateSnapshot[S]): F[Either[SnapshotRefusal, Array[Byte]]]

  def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[S]]]
