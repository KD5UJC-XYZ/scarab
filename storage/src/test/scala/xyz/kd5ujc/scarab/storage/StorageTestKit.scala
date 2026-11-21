// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.util.Locale
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{StorageError, UtxoKeyLayout}

/** Helpers shared by the storage suites. */
object StorageTestKit:
  val tempDir: Resource[IO, Path] = Files.forAsync[IO].tempDirectory

  /** The binding a snapshot written now records of the checkpoint in `dir`. */
  def boundTo(dir: Path): CheckpointBinding =
    CheckpointBinding.Bound(UtxoKeyLayout.current, CheckpointDigest.of(dir.toNioPath))

  /** Run a program that may raise a [[StorageError]]. */
  def attempt[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[Either[StorageError, A]] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  /** Run a program that must not raise. */
  def ok[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[A] =
    attempt(body).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  /** The JVM's default locale, and its display and format locales, set to `locale` while the
    * resource is in use and given back on release. The build runs one suite at a time (its
    * `parallelExecution` setting and test tag limit in `build.sbt`), so no other suite runs under
    * it.
    */
  def underLocale(locale: Locale): Resource[IO, Unit] =
    Resource
      .make(IO {
        val saved = (
          Locale.getDefault,
          Locale.getDefault(Locale.Category.DISPLAY),
          Locale.getDefault(Locale.Category.FORMAT)
        )
        Locale.setDefault(locale)
        saved
      }) { (default, display, format) =>
        IO {
          Locale.setDefault(default)
          Locale.setDefault(Locale.Category.DISPLAY, display)
          Locale.setDefault(Locale.Category.FORMAT, format)
        }
      }
      .void

  def hash(i: Int): Bytes = Blake2b.hash256.hash(Bytes(s"block-$i".getBytes.toVector))

  /** Why a synthetic block's body does not decode. */
  enum Undecodable extends DomainError:
    case Body

  /** The body of a synthetic block that does not decode: [[decodes]] refuses it. */
  val undecodable: Bytes = Bytes("undecodable".getBytes.toVector)

  /** The body of a synthetic block that decodes and is corrupt: [[decodes]] refuses it. */
  val corrupt: Bytes = Bytes("corrupt".getBytes.toVector)

  /** The start-up check of a log of synthetic blocks ([[ReceivedBlocks.Check]]): every body decodes
    * but [[undecodable]], as the block its record names, and every body that decodes is sound but
    * [[corrupt]].
    */
  val decodes: ReceivedBlocks.Check = (ref, raw) =>
    if raw == undecodable then
      Left(
        VolatileStore.Refusal.Undecodable(
          StorageError.Corrupt("synthetic block", Undecodable.Body)
        )
      )
    else if raw == corrupt then
      Left(
        VolatileStore.Refusal.Corrupt(
          ref.hash,
          StorageError.Corrupt("synthetic block", Undecodable.Body)
        )
      )
    else Right(())

  /** A synthetic block `i`: slot `i * gap`, number `i`, payload of varying size. */
  def block(i: Int, gap: Int = 3): (BlockRef, Bytes) =
    val payload = Bytes(Vector.tabulate(40 + (i * 37) % 200)(j => (i + j).toByte))
    (BlockRef(BigInt(i) * gap, hash(i), BigInt(i)), payload)
