// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Header, StorageError, Utxo}
import xyz.kd5ujc.scarab.ledger.state.MirrorError
import xyz.kd5ujc.scarab.node.{Projection, StateDump}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.storage.{AtomicFile, UtxoStore}

/** The follower's K1 digest for a point reached by the offline replay's full block rules. Snapshot
  * conversion alone does not establish current-rule boundary acceptance. The caller owns target
  * authentication, state progression and the fresh output directory; this writer includes the
  * backing UTxO and the state's unflushed changes, using the public follower's digest contract.
  */
object K1Projection:
  def write[F[_]: Sync](
      header: Header.Shelley,
      state: MirrorState,
      store: UtxoStore[F],
      out: Path
  )(using Raise[F, StorageError]): F[Either[MirrorError, Path]] =
    Sync[F].delay(Projection.node(header.point, state, Utxo.empty)).flatMap {
      case Left(error) => Sync[F].pure(Left(error))
      case Right(projection) =>
        for
          utxo <- StateDump.utxoDigest(Some(store), state.utxo)
          body = StateDump
            .reduce(projection)
            .deepMerge(
              Json.obj("utxo" -> utxo, "format" -> Json.fromString(StateDump.Format))
            )
          file = out.resolve(f"${header.slot}%012d-${header.hash.hex}.json")
          _ <- AtomicFile.guard[F, Path](out.toString)(Files.createDirectories(out))
          _ <- AtomicFile.replace[F](
            FsPath.fromNioPath(file),
            body.noSpaces.getBytes(StandardCharsets.UTF_8)
          )
        yield file.asRight[MirrorError]
    }
