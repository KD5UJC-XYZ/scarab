// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Concurrent, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*

/** The opens of Leios stores in one process, one permit per lock file (by its real path).
  *
  * The operating system's record locks belong to the process: they cannot keep two opens of one
  * process apart, and closing one open's channel drops the lock another open of the process holds.
  * So an open of [[RocksLeiosStore]] takes its lock file's permit here before it opens the lock
  * file and gives it back after it closes it, and the operating-system lock only ever excludes
  * other processes. A process makes one instance, at its start, and gives it to every open of a
  * Leios store it makes (EFF-4: an instance made in a `Resource`, not process-wide state).
  */
final class LeiosStoreLocks[F[_]] private (
    permits: Ref[F, Map[java.nio.file.Path, Semaphore[F]]]
)(using F: Concurrent[F]):
  /** The permit of the lock file at `path`, held for the resource's life. */
  def held(path: java.nio.file.Path): Resource[F, Unit] =
    Resource
      .eval(Semaphore[F](1).flatMap { fresh =>
        permits.modify(held =>
          held.get(path).fold((held.updated(path, fresh), fresh))(existing => (held, existing))
        )
      })
      .flatMap(_.permit)

object LeiosStoreLocks:
  /** A process's instance, holding no permit yet. */
  def make[F[_]: Concurrent]: F[LeiosStoreLocks[F]] =
    Ref.of[F, Map[java.nio.file.Path, Semaphore[F]]](Map.empty).map(new LeiosStoreLocks(_))

  /** [[make]] as a resource, for a process that builds its services as resources. */
  def resource[F[_]: Concurrent]: Resource[F, LeiosStoreLocks[F]] = Resource.eval(make[F])
