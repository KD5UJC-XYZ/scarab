// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import scala.annotation.tailrec
import scala.util.Using
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError, UtxoKeyLayout}

/** What a ledger snapshot records of the store that holds the rest of its state (the UTxO entries,
  * in a checkpoint taken at the snapshot's anchor). The reference keeps the same facts in the
  * metadata file of its snapshot: the backend, a checksum and the tables codec version
  * (`SnapshotMetadata`, Ouroboros/Consensus/Storage/LedgerDB/Snapshots.hs:223-227 at
  * `release-ouroboros-consensus-4.2.1.0`), and checks them before the snapshot is used.
  */
enum CheckpointBinding derives CanEqual:
  /** Written before [[xyz.kd5ujc.scarab.ledger.FormatVersion.V7]], which named the checkpoint only
    * by the directory that shares the snapshot's file stem. The migration from V5 records this
    * rather than a digest of whatever directory has that name now: it would certify a pairing it
    * never saw written (ADR 0006: a migration never invents data). Such a snapshot is restored as
    * the V5 build restored it, with a [[StorageEvent.SnapshotUnbound]] warning, and leaves the
    * store once newer snapshots replace it.
    */
  case Legacy

  /** The state keeps nothing outside the snapshot file ([[StateBacking.none]]). */
  case Unbacked

  /** The checkpoint of the given key layout whose directory has `digest` ([[CheckpointDigest]]). */
  case Bound(layout: UtxoKeyLayout, digest: Bytes)

object CheckpointBinding:
  given layoutCodec: Codec[UtxoKeyLayout] = Codec.auto
  given codec: Codec[CheckpointBinding] = Codec.auto

  /** Bytes of the fingerprint. */
  val FingerprintSize = 8

  /** The schema fingerprint stored in front of the binding. */
  def fingerprint: Array[Byte] =
    MessageDigest
      .getInstance("SHA-256")
      .digest(codec.shape.getBytes(StandardCharsets.UTF_8))
      .take(FingerprintSize)

/** The digest that binds a checkpoint directory to its snapshot: SHA-256 over every data file of
  * the directory, in name order, of its name, its size and, except for table files, its content.
  *
  * A RocksDB checkpoint is the `CURRENT` file, the `MANIFEST` it names (every live table file by
  * number, with its size and key range), the `OPTIONS` file, the write-ahead log and the table
  * files (`*.sst`, `*.blob`), which RocksDB never rewrites under the same name; their content is
  * covered by the manifest, so the digest reads only the small files and stays cheap for a set of
  * any size. The memory store's checkpoint is one file, read whole. `LOG`, `LOG.old.*` and `LOCK`
  * are left out: they are diagnostics and a lock that opening the checkpoint read-only may create
  * ([[RocksUtxoStore.hasCurrentKeys]]), not data.
  */
object CheckpointDigest:
  private def diagnostic(name: String): Boolean =
    name == "LOG" || name.startsWith("LOG.old") || name == "LOCK"

  private def table(name: String): Boolean = name.endsWith(".sst") || name.endsWith(".blob")

  /** The digest of `dir` (blocking; throws what the file system throws). */
  def of(dir: JPath): Bytes =
    val digest = MessageDigest.getInstance("SHA-256")
    val files = Using.resource(Files.list(dir)) { listing =>
      listing.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(_.getFileName.toString)
        .filterNot(diagnostic)
        .toVector
        .sorted
    }
    files.foreach { name =>
      val file = dir.resolve(name)
      val label = name.getBytes(StandardCharsets.UTF_8)
      digest.update(ByteBuffer.allocate(4).putInt(label.length).array())
      digest.update(label)
      digest.update(ByteBuffer.allocate(8).putLong(Files.size(file)).array())
      if !table(name) then
        Using.resource(Files.newInputStream(file)) { in =>
          val buffer = new Array[Byte](64 * 1024)
          @tailrec def rest(): Unit =
            val read = in.read(buffer)
            if read >= 0 then
              digest.update(buffer, 0, read)
              rest()
          rest()
        }
    }
    Bytes.fromArray(digest.digest())

  /** Why the checkpoint directory `dir` is not the one a snapshot recorded as `binding`, none when
    * it is (blocking; throws what the file system throws). A [[CheckpointBinding.Legacy]] binding
    * admits any existing directory, as the V5 build did; [[CheckpointBinding.Unbacked]] admits
    * none, since the caller keeps the entries in a checkpoint the snapshot does not know.
    */
  def problem(dir: JPath, binding: CheckpointBinding): Option[CheckpointProblem] =
    if !Files.isDirectory(dir) then Some(CheckpointProblem.Absent)
    else
      binding match
        case CheckpointBinding.Legacy   => None
        case CheckpointBinding.Unbacked => Some(CheckpointProblem.Unbound)
        case CheckpointBinding.Bound(layout, _) if layout != UtxoKeyLayout.current =>
          Some(CheckpointProblem.LayoutMismatch(layout, UtxoKeyLayout.current))
        case CheckpointBinding.Bound(_, recorded) =>
          val found = of(dir)
          Option.when(found != recorded)(CheckpointProblem.DigestMismatch(found, recorded))

  /** [[problem]] as the storage error that refuses the snapshot. */
  def admit(dir: JPath, binding: CheckpointBinding): Either[StorageError, Unit] =
    problem(dir, binding).toLeft(()).leftMap(StorageError.UtxoCheckpoint(dir.toString, _))
