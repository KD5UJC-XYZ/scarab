// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError

import cats.Show
import xyz.kd5ujc.scarab.consensus.state.StateMirrorError
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.haskell.state.StateNotWritable

/** What a snapshot write proves about the file before the store keeps it.
  *
  * The owner's call (#94, 2026-10-16): the read back to a full node state and its comparison with
  * the state written ([[Full]]) checked the Haskell-layout mirror while it was new; it costs a
  * second typed state at every write (0.84 GB on a preprod epoch-188 state), so the node keeps the
  * cheap check and the tests and the migrations keep the full one. A file that passes [[Structure]]
  * and that a restart still cannot restore is skipped by the recovery, which restores the newest
  * older snapshot that reads (or replays the immutable blocks): a slower restart, never another
  * state.
  */
enum SnapshotCheck:
  /** The sidecar decodes and the Haskell section is one well-formed CBOR item, read as the index of
    * its items (ADR 0013, two ints an item) and nothing built from it.
    */
  case Structure

  /** The file is read back to the node state and compared with the canonical form of the state
    * written: [[SnapshotRefusal.WouldNotRead]] and [[SnapshotRefusal.WouldDiffer]].
    */
  case Full

object SnapshotCheck:
  /** `structure` or `full`, as `--snapshot-check` takes it. */
  def parse(text: String): Option[SnapshotCheck] =
    values.find(_.toString.equalsIgnoreCase(text))

/** Why a state was not written as a snapshot, or a snapshot not brought to the current format. A
  * snapshot is an optimisation (a restart without one replays the immutable blocks), so a refusal
  * is a typed event and the snapshot is skipped, never an error of the node.
  */
enum SnapshotRefusal extends DomainError:
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
  case WouldDiffer(parts: List[SnapshotDifference])

/** The two invariants checked when a snapshot is written and read back in full. */
enum SnapshotDifference:
  case ReencodedBytes, Anchor

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
  * decides everything inside: [[SnapshotV4]] is the Haskell ledger state with a sidecar. The older
  * layouts (the one-codec V2 and the V3 sidecar) are read only by the migration, through the frozen
  * readers of the `legacy` module.
  *
  * The effect `F` is where a format reads what it needs (the V4 format asks for the mirror's
  * environment); a refusal or an unreadable file is a value, not a failure of `F`.
  */
trait SnapshotFormat[F[_], S]:
  def encode(snapshot: StateSnapshot[S]): F[Either[SnapshotRefusal, Array[Byte]]]

  def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[S]]]

object SnapshotFormat:
  extension [F[_], S](format: SnapshotFormat[F, S])
    /** The format that applies `restore` to the state it reads (not to the one it writes): what a
      * node does to the state of a snapshot an older build wrote, once, as it comes in.
      */
    def restoring(restore: S => S)(using F: cats.Functor[F]): SnapshotFormat[F, S] =
      new SnapshotFormat[F, S]:
        def encode(snapshot: StateSnapshot[S]): F[Either[SnapshotRefusal, Array[Byte]]] =
          format.encode(snapshot)

        def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[S]]] =
          F.map(format.decode(content))(_.map(s => s.copy(state = restore(s.state))))
