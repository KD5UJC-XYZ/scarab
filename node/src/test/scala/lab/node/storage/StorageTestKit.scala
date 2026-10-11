// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.{BlockRef, StorageError}

/** Helpers shared by the storage suites. */
object StorageTestKit:
  val tempDir: Resource[IO, Path] = Files.forAsync[IO].tempDirectory

  /** Run a program that may raise a [[StorageError]]. */
  def attempt[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[Either[StorageError, A]] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  /** Run a program that must not raise. */
  def ok[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[A] =
    attempt(body).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  def hash(i: Int): Bytes = Blake2b.hash256.hash(Bytes(s"block-$i".getBytes.toVector))

  /** A synthetic block `i`: slot `i * gap`, number `i`, payload of varying size. */
  def block(i: Int, gap: Int = 3): (BlockRef, Bytes) =
    val payload = Bytes(Vector.tabulate(40 + (i * 37) % 200)(j => (i + j).toByte))
    (BlockRef(BigInt(i) * gap, hash(i), BigInt(i)), payload)
