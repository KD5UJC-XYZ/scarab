// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}
import java.util.zip.CRC32
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

/** One entry of the volatile log. */
enum VolatileRecord:
  /** A block was adopted (it extends the chain as the log stands, or starts a fork). */
  case Block(ref: BlockRef, raw: Bytes)

  /** The chain was rolled back to `to`: the blocks after it are no longer on the chain. */
  case Rollback(to: ChainSync.Point)

/** The blocks of the volatile part of the chain (the last `k`), kept on disk so a restart resumes
  * at the previous tip instead of fetching them again: the VolatileDB of ouroboros-consensus, as an
  * append-only log of the chain's own updates.
  *
  * The store is a hint, not a source of truth: whatever it returns is validated by applying it to
  * the immutable state, and anything it loses or cannot return is simply fetched from a peer. It
  * must never return a block that was not appended (checksums), and it must never lose a block that
  * the immutable store does not hold ([[gc]] is only given a slot the immutable store has made
  * durable).
  */
trait VolatileStore[F[_]]:
  /** Log an adopted block. */
  def append(ref: BlockRef, raw: Bytes): F[Unit]

  /** Log a rollback of the chain to `to` (`Origin` or a block). */
  def rollback(to: ChainSync.Point): F[Unit]

  /** Delete the log files all of whose blocks are at or below `durableSlot` (the slot of the newest
    * block the immutable store has forced to disk). The newest file is kept.
    */
  def gc(durableSlot: BigInt): F[Unit]

  /** Force the log to disk. */
  def sync: F[Unit]

object VolatileStore:
  /** What opening found. */
  final case class OpenReport(
      /** Blocks on the logged chain (after applying the rollbacks). */
      blocks: Int,
      files: Int,
      /** Bytes of a torn or damaged tail that were cut off, and files after it that were removed.
        */
      truncatedBytes: Long,
      removedFiles: Int
  )

  /** The chain a log describes: its blocks in order, rollbacks applied. A rollback to a block the
    * log no longer holds (it became immutable and its file was collected) leaves nothing, which is
    * where the volatile chain starts.
    */
  def chainOf(records: Iterable[VolatileRecord]): Vector[(BlockRef, Bytes)] =
    records.foldLeft(Vector.empty[(BlockRef, Bytes)]) {
      case (chain, VolatileRecord.Block(ref, raw))              => chain :+ (ref -> raw)
      case (_, VolatileRecord.Rollback(ChainSync.Point.Origin)) => Vector.empty
      case (chain, VolatileRecord.Rollback(ChainSync.Point.Block(slot, hash))) =>
        val i = chain.indexWhere((r, _) => r.hash == hash && r.slot == slot.value)
        if i >= 0 then chain.take(i + 1) else Vector.empty
    }

  // Record layout (big-endian):
  //   crc u32 | kind u8 | flags u8 | length u32 | slot u64 | blockNo u64 | hash[32] | payload[length]
  // The CRC-32 covers every byte after the crc field.
  val HeaderSize = 58
  private val KindBlock = 1
  private val KindRollback = 2
  private val FlagBoundary = 1
  private val FlagOrigin = 2

  def encode(record: VolatileRecord): Array[Byte] =
    val (kind, flags, slot, blockNo, hash, payload) = record match
      case VolatileRecord.Block(ref, raw) =>
        (
          KindBlock,
          if ref.boundary then FlagBoundary else 0,
          ref.slot,
          ref.blockNo,
          ref.hash.toArray,
          raw.toArray
        )
      case VolatileRecord.Rollback(ChainSync.Point.Origin) =>
        (KindRollback, FlagOrigin, BigInt(0), BigInt(0), new Array[Byte](32), Array.empty[Byte])
      case VolatileRecord.Rollback(ChainSync.Point.Block(slot, hash)) =>
        (KindRollback, 0, slot.value, BigInt(0), hash.toArray, Array.empty[Byte])
    val b = ByteBuffer.allocate(HeaderSize + payload.length)
    b.putInt(0).put(kind.toByte).put(flags.toByte).putInt(payload.length)
    b.putLong(slot.toLong).putLong(blockNo.toLong).put(hash).put(payload)
    val a = b.array()
    val crc = new CRC32()
    crc.update(a, 4, a.length - 4)
    ByteBuffer.wrap(a).putInt(0, crc.getValue.toInt)
    a

  /** The valid prefix of a log file: its records, each with the offset where it ends. Parsing stops
    * at the first record that is incomplete, fails its checksum or is not understood.
    */
  def decodeAll(bytes: Array[Byte]): Vector[(VolatileRecord, Int)] =
    val out = Vector.newBuilder[(VolatileRecord, Int)]
    var at = 0
    var ok = true
    while ok && bytes.length - at >= HeaderSize do
      val b = ByteBuffer.wrap(bytes, at, bytes.length - at)
      val crc = b.getInt
      val kind = b.get() & 0xff
      val flags = b.get() & 0xff
      val length = b.getInt
      val slot = b.getLong
      val blockNo = b.getLong
      val hash = new Array[Byte](32)
      b.get(hash)
      if length < 0 || length > bytes.length - at - HeaderSize || slot < 0 || blockNo < 0 then
        ok = false
      else
        val end = at + HeaderSize + length
        val check = new CRC32()
        check.update(bytes, at + 4, end - at - 4)
        if check.getValue.toInt != crc then ok = false
        else
          val record = kind match
            case KindBlock =>
              val payload = java.util.Arrays.copyOfRange(bytes, at + HeaderSize, end)
              Some(
                VolatileRecord.Block(
                  BlockRef(slot, Bytes.fromArray(hash), blockNo, (flags & FlagBoundary) != 0),
                  Bytes.fromArray(payload)
                )
              )
            case KindRollback if length == 0 =>
              Some(
                VolatileRecord.Rollback(
                  if (flags & FlagOrigin) != 0 then ChainSync.Point.Origin
                  else
                    ChainSync.Point
                      .Block(ChainSync.UInt64.saturating(slot), Bytes.fromArray(hash))
                )
              )
            case _ => None
          record match
            case Some(r) =>
              out += (r -> end)
              at = end
            case None => ok = false
    out.result()

  /** What [[open]] hands back besides the store: the chain the log describes. */
  final case class Opened(chain: Vector[(BlockRef, Bytes)], report: OpenReport)

  private final case class FileInfo(seq: Int, blocks: Int, maxSlot: BigInt)

  private val Suffix = ".log"
  private def nameOf(seq: Int) = f"$seq%08d$Suffix"

  /** Open (or create) the log in `dir`. Files are `<seq>.log`; a new file starts after
    * `blocksPerFile` blocks. Opening validates every file in order, cuts the first damaged one at
    * its last good record and removes the files after it.
    */
  def open[F[_]: Async](dir: Path, blocksPerFile: Int = 512)(using
      Raise[F, StorageError]
  ): Resource[F, (VolatileStore[F], Opened)] =
    val F = Async[F]
    val root = dir.toNioPath

    def guard[A](what: String)(body: => A): F[A] =
      F.blocking(body).attempt.flatMap {
        case Right(a) => F.pure(a)
        case Left(e)  => StorageError.Io(what, e).raise[F, A]
      }

    def syncDir(): Unit =
      val ch = FileChannel.open(root, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()

    final class Current(val seq: Int, val channel: FileChannel)

    /** Read the existing files; returns the infos, the logged records, and what was repaired. */
    val scan: F[(Vector[FileInfo], Vector[VolatileRecord], Long, Int)] = guard(root.toString) {
      Files.createDirectories(root)
      val names = {
        val s = Files.list(root)
        try
          s.iterator.asScala
            .map(_.getFileName.toString)
            .filter(_.endsWith(Suffix))
            .flatMap(n => n.stripSuffix(Suffix).toIntOption.map(_ -> n))
            .toVector
            .sortBy(_._1)
        finally s.close()
      }
      var infos = Vector.empty[FileInfo]
      var records = Vector.empty[VolatileRecord]
      var cut = 0L
      var removed = 0
      var damaged = false
      names.foreach { (seq, name) =>
        val path = root.resolve(name)
        if damaged then
          Files.delete(path)
          removed += 1
        else
          val bytes = Files.readAllBytes(path)
          val parsed = decodeAll(bytes)
          val good = parsed.lastOption.fold(0)(_._2)
          if good < bytes.length then
            val ch = FileChannel.open(path, StandardOpenOption.WRITE)
            try
              ch.truncate(good.toLong)
              ch.force(true)
            finally ch.close()
            cut += bytes.length - good
            damaged = true
          val rs = parsed.map(_._1)
          records = records ++ rs
          infos = infos :+ FileInfo(
            seq,
            rs.count(_.isInstanceOf[VolatileRecord.Block]),
            rs.collect { case VolatileRecord.Block(r, _) => r.slot }.maxOption.getOrElse(BigInt(-1))
          )
      }
      if cut > 0 || removed > 0 then syncDir()
      (infos, records, cut, removed)
    }

    Resource
      .make(
        for
          scanned <- scan
          (infos, records, cut, removed) = scanned
          mutex <- Mutex[F]
          chain = chainOf(records)
          startNew = infos.lastOption.forall(_.blocks >= blocksPerFile)
          seq = if startNew then infos.lastOption.fold(0)(_.seq + 1) else infos.last.seq
          channel <- guard(root.toString) {
            val ch = FileChannel.open(
              root.resolve(nameOf(seq)),
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE
            )
            ch.position(ch.size())
            if startNew then syncDir()
            ch
          }
          files <- Ref.of[F, Vector[FileInfo]](
            if startNew then infos :+ FileInfo(seq, 0, BigInt(-1)) else infos
          )
          current <- Ref.of[F, Current](new Current(seq, channel))
        yield (
          mutex,
          files,
          current,
          Opened(chain, OpenReport(chain.size, infos.size, cut, removed))
        )
      ) { (_, _, current, _) =>
        current.get.flatMap(c =>
          guard(root.toString)(
            try c.channel.force(true)
            finally c.channel.close()
          )
        )
      }
      .map { (mutex, files, current, opened) =>
        val store = new VolatileStore[F]:
          private def write(channel: FileChannel, bytes: Array[Byte]): Unit =
            val buf = ByteBuffer.wrap(bytes)
            while buf.hasRemaining do channel.write(buf)

          /** Start a new file when the current one holds `blocksPerFile` blocks. */
          private def roll: F[Unit] =
            files.get.flatMap { fs =>
              if fs.last.blocks < blocksPerFile then F.unit
              else
                current.get.flatMap { c =>
                  val next = c.seq + 1
                  guard(root.toString) {
                    c.channel.force(true)
                    c.channel.close()
                    val ch = FileChannel.open(
                      root.resolve(nameOf(next)),
                      StandardOpenOption.CREATE,
                      StandardOpenOption.READ,
                      StandardOpenOption.WRITE
                    )
                    syncDir()
                    ch
                  }.flatMap(ch =>
                    current.set(new Current(next, ch)) *>
                      files.update(_ :+ FileInfo(next, 0, BigInt(-1)))
                  )
                }
            }

          private def log(record: VolatileRecord): F[Unit] =
            mutex.lock.surround {
              for
                _ <- roll
                c <- current.get
                _ <- guard(root.toString)(write(c.channel, encode(record)))
                _ <- record match
                  case VolatileRecord.Block(ref, _) =>
                    files.update(fs =>
                      fs.init :+ fs.last.copy(
                        blocks = fs.last.blocks + 1,
                        maxSlot = fs.last.maxSlot.max(ref.slot)
                      )
                    )
                  case _ => F.unit
              yield ()
            }

          def append(ref: BlockRef, raw: Bytes): F[Unit] = log(VolatileRecord.Block(ref, raw))

          def rollback(to: ChainSync.Point): F[Unit] = log(VolatileRecord.Rollback(to))

          def sync: F[Unit] =
            mutex.lock.surround(
              current.get.flatMap(c => guard(root.toString)(c.channel.force(true)))
            )

          def gc(durableSlot: BigInt): F[Unit] =
            mutex.lock.surround {
              files.get.flatMap { fs =>
                val doomed = fs.init.takeWhile(_.maxSlot <= durableSlot)
                if doomed.isEmpty then F.unit
                else
                  guard(root.toString) {
                    doomed.foreach(f => Files.deleteIfExists(root.resolve(nameOf(f.seq))))
                    syncDir()
                  } *> files.update(_.drop(doomed.size))
              }
            }
        (store: VolatileStore[F], opened)
      }
