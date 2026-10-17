// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption as Open}
import java.nio.file.attribute.PosixFilePermissions
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.ForgeError

/** What became of an evolved key handed to a [[HotKeySource]] to write back. */
enum Persisted:
  /** The file now holds the key and the key it replaced was overwritten. */
  case Written

  /** The file already holds this key at this period or a later one. */
  case Unchanged

  /** The file holds another KES key (the operator installed a rotation that is waiting for the
    * reload): it is left alone.
    */
  case Superseded

  case Failed(error: ForgeError)

/** The KES key file of a pool and how an evolved key replaces its predecessor on it (H2a, #116).
  *
  * The Haskell node never writes its KES key: it re-reads the period-0 file and evolves in memory
  * at every start, so the period-0 key stays on disk for ever (ouroboros-consensus
  * `HotKey.evolveKey` works on the in-memory key only). That is the improvement made here, for
  * forward security: after the key moves to a new period it is written back, and the key it
  * replaces is overwritten, so neither a restart nor a stolen disk yields the key of an earlier
  * period. The file stays the cardano-cli envelope: the period is read off the key
  * ([[Sum6KesSigning.fromEvolved]]), so there is no new format.
  *
  * The replacement is crash-safe in three steps, each of which can be interrupted:
  *   1. [[stage]] writes the new key to `<file>.next` and forces it to disk;
  *   1. [[scrub]] overwrites the old key in `<file>` with zeros and forces that;
  *   1. [[commit]] renames `<file>.next` over `<file>` (atomic) and forces the directory.
  * [[recover]] runs when the key is read: a `.next` that holds the same key at a later period (or
  * any good key where `<file>` is damaged) finishes the replacement; a torn `.next` is destroyed.
  * At every point at least one complete key is on disk, and never one of an earlier period than the
  * one last written once the old one has been overwritten.
  */
object KesFiles:
  private def nextOf(file: Path): Path =
    file.resolveSibling(file.getFileName.toString + ".next")

  private def io[A](file: Path)(body: => A): Either[ForgeError, A] =
    Either
      .catchNonFatal(body)
      .leftMap(e => ForgeError.KeyFile(file.toString, Option(e.getMessage).getOrElse(e.toString)))

  /** The file a path leads to, through symbolic links (a rotation switches generations by
    * re-pointing a link): the node writes back where it read from, so a rotation that happens
    * meanwhile is not overwritten by the key it replaces.
    */
  def resolved(file: Path): Either[ForgeError, Path] =
    io(file) {
      if Files.exists(file) then file.toRealPath()
      else file.toAbsolutePath.getParent.toRealPath().resolve(file.getFileName)
    }

  private def parse(file: Path): Either[ForgeError, Sum6KesSigning.SigningKey] =
    KeyFiles.read(file).flatMap(KeyFiles.kesSigningKey(file.toString, _))

  private def overwrite(file: Path): Unit =
    val length = Files.size(file).toInt
    val channel = FileChannel.open(file, Open.WRITE)
    try
      channel.write(ByteBuffer.wrap(new Array[Byte](length)), 0L)
      channel.force(true)
    finally channel.close()

  /** Overwrite a file with zeros and remove it. */
  private def destroy(file: Path): Unit =
    if Files.exists(file) then
      Either.catchNonFatal(overwrite(file)).void
      Files.deleteIfExists(file)
      ()

  private def syncDirectory(dir: Path): Unit =
    // not every file system lets a directory be opened and forced; the rename is atomic anyway
    Either.catchNonFatal {
      val channel = FileChannel.open(dir, Open.READ)
      try channel.force(true)
      finally channel.close()
    }.void

  /** Step 1: the new key, complete and on disk, beside the old one. */
  private[forge] def stage(file: Path, key: Sum6KesSigning.SigningKey): Either[ForgeError, Unit] =
    val next = nextOf(file)
    KeyFiles.kesEnvelope(file.toString, key).flatMap { text =>
      io(file) {
        Files.deleteIfExists(next)
        val channel =
          try
            FileChannel.open(
              next,
              java.util.Set.of[java.nio.file.OpenOption](Open.CREATE_NEW, Open.WRITE),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            )
          catch
            case _: UnsupportedOperationException =>
              FileChannel.open(next, Open.CREATE_NEW, Open.WRITE)
        try
          channel.write(ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          channel.force(true)
        finally channel.close()
      }
    }

  /** Step 2: the old key is overwritten (best effort: a copy-on-write or journalling file system
    * may keep the old blocks, which is what an encrypted volume is for).
    */
  private[forge] def scrub(file: Path): Either[ForgeError, Unit] =
    io(file)(if Files.exists(file) then overwrite(file))

  /** Step 3: the new key takes the name of the old one. */
  private[forge] def commit(file: Path): Either[ForgeError, Unit] =
    io(file) {
      Files.move(
        nextOf(file),
        file,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
      syncDirectory(file.toAbsolutePath.getParent)
    }

  /** Write `key` as the content of `file`, replacing the key it holds when that is the same KES key
    * at an earlier period (or is damaged or missing).
    */
  def persist(file: Path, key: Sum6KesSigning.SigningKey): Persisted =
    val onDisk = if Files.exists(file) then parse(file).toOption else None
    onDisk match
      case Some(d) if d.verificationKey != key.verificationKey => Persisted.Superseded
      case Some(d) if d.period >= key.period                   => Persisted.Unchanged
      case _ =>
        val steps = stage(file, key) *> scrub(file) *> commit(file)
        steps.fold(Persisted.Failed(_), _ => Persisted.Written)

  /** The key of `file` after any replacement that a crash interrupted is put right. */
  def recover(file: Path): Either[ForgeError, Sum6KesSigning.SigningKey] =
    val next = nextOf(file)
    val main = parse(file)
    if !Files.exists(next) then main
    else
      parse(next) match
        case Right(n) if main.fold(_ => true, m => same(m, n) && m.period <= n.period) =>
          (scrub(file) *> commit(file)).as(n)
        case _ =>
          destroy(next)
          main

  private def same(a: Sum6KesSigning.SigningKey, b: Sum6KesSigning.SigningKey): Boolean =
    a.verificationKey == b.verificationKey

  /** The KES key (recovered), the certificate and the cold key of a pool, from where the operator
    * installed them.
    */
  def readHot(kesFile: Path, opCertFile: Path): Either[ForgeError, HotCredentials] =
    for
      real <- resolved(kesFile)
      kes <- recover(real)
      ocText <- KeyFiles.read(opCertFile)
      oc <- KeyFiles.opCert(opCertFile.toString, ocText)
    yield HotCredentials(kes, oc._1, oc._2)
