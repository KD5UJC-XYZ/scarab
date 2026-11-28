// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** The Haskell-layout state of a native V10 snapshot file, for the suites outside `storage` that
  * read a recorded snapshot's state (`SnapshotV10.sections` is the store's own).
  */
object SnapshotV10Sections:
  def haskellState(file: Array[Byte], layout: ReferenceLayout): Either[StorageError, Bytes] =
    FileLedgerSnapshots
      .verified(file)
      .flatMap(content => SnapshotV10.sections(content, layout))
      .map(_._4)
