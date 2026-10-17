// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.consensus.HardForkConfig
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.storage.{FileLedgerSnapshots, StateSnapshot}

/** The snapshot files of the builds before V3, read directly through the frozen V2 reader of the
  * `legacy` module (no migration, no store) and brought to the live node state: the reference the
  * migration is compared with, and the way the tools that look at the snapshots of a real sync (a
  * Preview run is version 1, written before the packages were renamed) get at them.
  */
object LegacySnapshots:
  /** The snapshot in `file`, version 1 or 2; `forks` places its state in an era. */
  def read(
      file: Path,
      forks: HardForkConfig
  ): Either[StorageError, StateSnapshot[legacy.FlatNodeState]] =
    FileLedgerSnapshots
      .verified(Files.readAllBytes(file))
      .flatMap(legacy.SnapshotV2.decodeNode)
      .flatMap(s =>
        legacy.Upgrade
          .node(s.state, forks, s.anchor)
          .map(StateSnapshot(s.anchor, _))
          .left
          .map(e => StorageError.Corrupt(file.toString, e.toString))
      )
