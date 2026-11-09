// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.mtl.Ask
import cats.syntax.all.*
import java.io.IOException
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.{
  FileLedgerSnapshots,
  SnapshotCheck,
  SnapshotV5,
  SnapshotV7,
  SnapshotV8,
  SnapshotV9,
  SnapshotV10,
  StateSnapshot
}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** Recorded-data tools consume checksummed native copies: current V10 files, and V5, V7, V8 and V9
  * files of the archived recorded corpora, which are brought to V10 in memory by the registered
  * conversions ([[SnapshotV7.fromV5]], [[SnapshotV8.fromV7]], [[SnapshotV9.fromV8]],
  * [[SnapshotV10.fromV9]]) exactly as opening their database would. Older historical captures
  * remain archived; their conversion belongs to the pinned evidence recipe, rather than the test
  * loader.
  */
object NativeSnapshots:
  def read[F[_]: Sync](
      file: Path,
      env: ConsensusMirrorEnv
  ): F[Either[StorageError, StateSnapshot[MirrorState]]] =
    Sync[F]
      .blocking(Files.readAllBytes(file))
      .attemptNarrow[IOException]
      .map(_.leftMap(error => StorageError.Io(s"read snapshot $file", error)))
      .flatMap {
        case Left(error)  => Sync[F].pure(Left(error))
        case Right(bytes) => decode(bytes, env)
      }

  /** Decode the exact bytes a replay has authenticated, without reopening the source file. */
  def decode[F[_]: Sync](
      bytes: Array[Byte],
      env: ConsensusMirrorEnv
  ): F[Either[StorageError, StateSnapshot[MirrorState]]] =
    given Ask[F, ConsensusMirrorEnv] = Ask.const(env)
    FileLedgerSnapshots.verified(bytes) match
      case Left(error) => Sync[F].pure(Left(error))
      case Right(content) =>
        current(content).fold(
          error => Sync[F].pure(Left(error)),
          SnapshotV10.format[F](SnapshotCheck.Full, ReferenceLayout.Release11_1).decode(_)
        )

  /** Current-format content of a verified file's content: a V5, V7, V8 or V9 content through the
    * registered conversions, anything else unchanged (the V10 reader then judges it).
    */
  def current(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    if content.startsWith(SnapshotV5.Magic) then
      SnapshotV7
        .fromV5(content)
        .flatMap(SnapshotV8.fromV7)
        .flatMap(SnapshotV9.fromV8)
        .flatMap(SnapshotV10.fromV9)
    else if content.startsWith(SnapshotV7.Magic) then
      SnapshotV8.fromV7(content).flatMap(SnapshotV9.fromV8).flatMap(SnapshotV10.fromV9)
    else if content.startsWith(SnapshotV8.Magic) then
      SnapshotV9.fromV8(content).flatMap(SnapshotV10.fromV9)
    else if content.startsWith(SnapshotV9.Magic) then SnapshotV10.fromV9(content)
    else Right(content)
