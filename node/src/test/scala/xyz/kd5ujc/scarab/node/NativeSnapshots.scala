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
  StateSnapshot
}

/** Recorded-data tools consume checksummed native copies: current V7 files, and V5 files of the
  * archived recorded corpora, which are brought to V7 in memory by the registered conversion
  * ([[SnapshotV7.fromV5]]) exactly as opening their database would. Older historical captures
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
        val current =
          if content.startsWith(SnapshotV5.Magic) then SnapshotV7.fromV5(content)
          else Right(content)
        current.fold(
          error => Sync[F].pure(Left(error)),
          SnapshotV7.format[F](SnapshotCheck.Full).decode(_)
        )
