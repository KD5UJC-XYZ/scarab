// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Raise
import fs2.io.file.Path
import xyz.kd5ujc.scarab.ledger.StorageError

/** A [[FileImmutableStore]] whose reads of its files are recorded in order: which file, and the
  * bytes read from it, `from` up to `until`. The reads the open makes are not recorded.
  */
object CountedReads:
  final case class Read(path: Path, from: Long, until: Long)

  def open(dir: Path, chunkSlots: Long, cacheEntries: Int)(using
      Raise[IO, StorageError]
  ): Resource[IO, (ImmutableStore[IO], Ref[IO, Vector[Read]])] =
    val files = ImmutableReads.files[IO]
    for
      log <- Resource.eval(Ref.of[IO, Vector[Read]](Vector.empty))
      counted = new ImmutableReads[IO]:
        private def recorded(path: Path, from: Long)(bytes: Array[Byte]): IO[Unit] =
          log.update(_ :+ Read(path, from, from + bytes.length))

        def all(path: Path): IO[Array[Byte]] = files.all(path).flatTap(recorded(path, 0L))

        def range(path: Path, from: Long, until: Long): IO[Array[Byte]] =
          files.range(path, from, until).flatTap(recorded(path, from))
      opened <- FileImmutableStore.open[IO](dir, chunkSlots, cacheEntries, counted)
      _ <- Resource.eval(log.set(Vector.empty))
    yield (opened._1, log)
