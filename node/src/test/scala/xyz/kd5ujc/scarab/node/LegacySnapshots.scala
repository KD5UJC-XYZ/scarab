// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.{Codec, FileLedgerSnapshots, NodeCodecs, SnapshotV2, StateSnapshot}

/** The snapshot files of the builds before V3, read directly through the V2 reader (no migration,
  * no store): the reference the migration is compared with, and the way the tools that look at the
  * snapshots of a real sync (a Preview run is version 1, written before the packages were renamed)
  * get at them.
  */
object LegacySnapshots:
  private given Codec[StateSnapshot[NodeState]] = NodeCodecs.nodeSnapshot
  private val reader = SnapshotV2.format[Id, NodeState]
  private val schema = SnapshotV2.fingerprint(NodeCodecs.nodeSnapshot.shape)

  /** The snapshot in `file`, version 1 or 2. */
  def read(file: Path): Either[StorageError, StateSnapshot[NodeState]] =
    FileLedgerSnapshots.verified(Files.readAllBytes(file)).flatMap { content =>
      val atV2 =
        if SnapshotV2.numberOf(content).contains(1) then SnapshotV2.upgraded(content, schema)
        else content
      reader.decode(atV2)
    }
