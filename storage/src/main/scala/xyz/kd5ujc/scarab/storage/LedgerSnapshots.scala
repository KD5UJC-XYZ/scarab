// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.Async
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{CopyFlag, CopyFlags, Files, Flag, Flags, Path}
import java.io.{InputStream, OutputStream}
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.{DigestInputStream, DigestOutputStream, MessageDigest}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

/** The node state at an immutable point: `anchor` is the newest block applied (none: genesis). */
final case class StateSnapshot[S](anchor: Option[BlockRef], state: S)

/** Periodic snapshots of the full node state: the LedgerDB snapshots of ouroboros-consensus. */
trait LedgerSnapshots[F[_], S]:
  /** Write atomically (temp file, fsync, rename, fsync of the directory), then prune old ones. */
  def write(snapshot: StateSnapshot[S]): F[LedgerSnapshots.Written]

  /** The newest snapshot that decodes, passes its checksum and whose anchor `usable` accepts.
    * Snapshots that fail are reported and skipped, never raised.
    */
  def restore(usable: Option[BlockRef] => F[Boolean]): F[LedgerSnapshots.Restored[S]]

  /** File names, newest first. */
  def list: F[Vector[String]]

object LedgerSnapshots:
  final case class Written(name: String, bytes: Long, anchor: Option[BlockRef])
  final case class Skipped(name: String, error: StorageError)
  final case class Restored[S](
      snapshot: Option[(String, StateSnapshot[S])],
      skipped: Vector[Skipped]
  )

/** File layout: `snapshot-<slot, 20 digits>-<blockNo>.bin` holding
  *
  * {{{
  * "CSLSNAP1" | format version u32 | schema fingerprint[8] | payload | SHA-256 of all before
  * }}}
  *
  * The payload is the [[Codec]] of `StateSnapshot[S]`. The fingerprint is the SHA-256 prefix of the
  * codec's `shape`, so any change to a persisted type makes older files fail with `VersionMismatch`
  * instead of decoding garbage; recovery then falls back to an older snapshot or, with none usable,
  * to replaying the immutable blocks from genesis.
  */
object FileLedgerSnapshots:
  val Magic: Array[Byte] = "CSLSNAP1".getBytes("US-ASCII")
  val FormatVersion = 2
  val DigestSize = 32
  private val Prefix = "snapshot-"
  private val Suffix = ".bin"

  private final class Reject(val error: StorageError) extends RuntimeException(error.message)

  def fingerprint(shape: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(shape.getBytes("UTF-8")).take(8)

  def open[F[_]: Async, S](dir: Path, keep: Int = 3)(using
      codec: Codec[StateSnapshot[S]],
      raise: Raise[F, StorageError]
  ): F[LedgerSnapshots[F, S]] =
    val F = Async[F]
    val files = Files.forAsync[F]
    val schema = fingerprint(codec.shape)

    def fail[A](e: StorageError): F[A] = e.raise[F, A]
    def guard[A](what: String)(fa: F[A]): F[A] =
      fa.attempt.flatMap(_.fold(e => fail(StorageError.Io(what, String.valueOf(e))), F.pure))
    def syncDir: F[Unit] = guard(dir.toString)(F.blocking {
      val ch = FileChannel.open(dir.toNioPath, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()
    })

    def names: F[Vector[String]] =
      guard(dir.toString)(
        files
          .list(dir)
          .map(_.fileName.toString)
          .filter(n => n.startsWith(Prefix) && n.endsWith(Suffix))
          .compile
          .toVector
          .map(_.sorted.reverse)
      )

    def nameOf(a: BlockRef): String = f"$Prefix${a.slot}%020d-${a.blockNo}$Suffix"

    def writePayload(snapshot: StateSnapshot[S])(os: OutputStream): Unit =
      val md = MessageDigest.getInstance("SHA-256")
      val out = Out(new DigestOutputStream(os, md))
      out.bytes(Magic, 0, Magic.length)
      out.fixed32(FormatVersion)
      out.bytes(schema, 0, schema.length)
      codec.write(out, snapshot)
      out.flush()
      os.write(md.digest())
      os.flush()

    def decodeStream(is: InputStream, size: Long): StateSnapshot[S] =
      if size < Magic.length + 4 + schema.length + DigestSize then
        throw Reject(StorageError.Corrupt("snapshot", s"file of $size bytes is too short"))
      val md = MessageDigest.getInstance("SHA-256")
      val in = In(new DigestInputStream(is, md), size - DigestSize)
      if !java.util.Arrays.equals(in.bytes(Magic.length), Magic) then
        throw Reject(StorageError.Corrupt("snapshot", "bad magic"))
      val version = in.fixed32()
      if version != FormatVersion then
        throw Reject(
          StorageError.VersionMismatch("snapshot format", version.toString, FormatVersion.toString)
        )
      val found = in.bytes(schema.length)
      if !java.util.Arrays.equals(found, schema) then
        throw Reject(
          StorageError.VersionMismatch(
            "snapshot schema",
            found.map(b => f"${b & 0xff}%02x").mkString,
            schema.map(b => f"${b & 0xff}%02x").mkString
          )
        )
      val snapshot = codec.read(in)
      if in.remaining != 0 then
        throw Reject(StorageError.Corrupt("snapshot", "trailing payload bytes"))
      val computed = md.digest()
      val stored = is.readNBytes(DigestSize)
      if !java.util.Arrays.equals(computed, stored) then
        throw Reject(StorageError.Corrupt("snapshot", "checksum mismatch"))
      snapshot

    def readFile(name: String): F[Either[StorageError, StateSnapshot[S]]] =
      val path = dir / name
      files.size(path).attempt.flatMap {
        case Left(e) => F.pure(Left(StorageError.Io(name, String.valueOf(e))))
        case Right(size) =>
          files
            .readAll(path)
            .through(fs2.io.toInputStream[F])
            .evalMap(is => F.blocking(decodeStream(is, size)).attempt)
            .compile
            .lastOrError
            .attempt
            .map(_.flatten.leftMap {
              case r: Reject         => r.error
              case c: CodecException => StorageError.Corrupt(name, c.getMessage)
              case e                 => StorageError.Io(name, String.valueOf(e))
            })
      }

    val tidy: F[Unit] =
      guard(dir.toString)(
        files.createDirectories(dir) *>
          files
            .list(dir)
            .filter(_.fileName.toString.endsWith(".tmp"))
            .evalMap(files.deleteIfExists)
            .compile
            .drain
      )

    tidy.as(new LedgerSnapshots[F, S]:
      def list: F[Vector[String]] = names

      def write(snapshot: StateSnapshot[S]): F[LedgerSnapshots.Written] =
        snapshot.anchor match
          case None => fail(StorageError.OutOfOrder("a genesis snapshot is never written"))
          case Some(anchor) =>
            val name = nameOf(anchor)
            val tmp = dir / (name + ".tmp")
            for
              _ <- guard(tmp.toString)(
                fs2.io
                  .readOutputStream[F](1 << 16)(os => F.blocking(writePayload(snapshot)(os)))
                  .through(files.writeAll(tmp))
                  .compile
                  .drain
              )
              size <- guard(tmp.toString)(files.size(tmp))
              _ <- guard(tmp.toString)(files.open(tmp, Flags(Flag.Write)).use(_.force(true)))
              _ <- guard(name)(
                files.move(
                  tmp,
                  dir / name,
                  CopyFlags(CopyFlag.AtomicMove, CopyFlag.ReplaceExisting)
                )
              )
              _ <- syncDir
              all <- names
              _ <- guard(dir.toString)(all.drop(keep).traverse_(n => files.deleteIfExists(dir / n)))
            yield LedgerSnapshots.Written(name, size, snapshot.anchor)

      def restore(usable: Option[BlockRef] => F[Boolean]): F[LedgerSnapshots.Restored[S]] =
        def go(
            rest: List[String],
            skipped: Vector[LedgerSnapshots.Skipped]
        ): F[LedgerSnapshots.Restored[S]] = rest match
          case Nil => F.pure(LedgerSnapshots.Restored(None, skipped))
          case name :: tail =>
            readFile(name).flatMap {
              case Left(error) => go(tail, skipped :+ LedgerSnapshots.Skipped(name, error))
              case Right(snapshot) =>
                usable(snapshot.anchor).flatMap {
                  case true => F.pure(LedgerSnapshots.Restored(Some(name -> snapshot), skipped))
                  case false =>
                    go(
                      tail,
                      skipped :+ LedgerSnapshots.Skipped(
                        name,
                        StorageError.Missing("snapshot anchor in the immutable store")
                      )
                    )
                }
            }
        names.flatMap(ns => go(ns.toList, Vector.empty)))
