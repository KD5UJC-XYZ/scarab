// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files, StandardOpenOption}
import java.nio.ByteBuffer
import java.util.zip.CRC32
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{Block, NetworkIdentity, StorageError}
import xyz.kd5ujc.scarab.protocol.Era

/** Read-only membership check for an offline import. Opening the ordinary ImmutableStore would
  * migrate and recover files; a refused snapshot must not perform either operation.
  */
private[storage] object SnapshotPoint:
  def check[F[_]: Sync](
      db: Path,
      identity: NetworkIdentity,
      anchor: BlockRef,
      era: Era,
      byronEpochSlots: BigInt
  )(using Raise[F, StorageError]): F[Unit] =
    val F = Sync[F]
    val root = db / "immutable"
    val slots = FileImmutableStore.DefaultChunkSlots
    def corrupt[E <: Product](detail: E): StorageError =
      StorageError.Corrupt("snapshot immutable point", detail)
    for
      recorded <- NetworkIdentityFile.verify[F](db, identity)
      _ <- F.unlessA(recorded)(
        StorageError
          .Missing("database network-identity; import immutable blocks first")
          .raise[F, Unit]
      )
      checked <- AtomicFile.guard[F, Either[StorageError, Unit]](root.toString) {
        val format = new String(Files.readAllBytes((root / "format").toNioPath), "UTF-8").trim
        val expected = s"${FileImmutableStore.FormatText} chunkSlots=$slots"
        if format != expected then
          Left(StorageError.VersionMismatch(root.toString, format, expected))
        else
          val chunk = anchor.slot / slots
          val stem = f"$chunk%05d"
          val index = (root / s"$stem.index").toNioPath
          val size = Files.size(index)
          val header = FileImmutableStore.HeaderSize
          val width = FileImmutableStore.EntrySize
          if size < header || size > header + (slots + 1) * width || (size - header) % width != 0
          then Left(corrupt(SnapshotReadError.IndexLength(size)))
          else
            val bytes = Files.readAllBytes(index)
            if !bytes.take(header).sameElements(FileImmutableStore.IndexMagic) then
              Left(corrupt(SnapshotReadError.IndexMagic))
            else
              FileImmutableStore
                .entries(bytes)
                .find(_.ref == anchor)
                .toRight(
                  StorageError.Missing(
                    s"snapshot point ${anchor.slot}/${anchor.hash.hex} in immutable chunks"
                  )
                )
                .flatMap { entry =>
                  val data = (root / s"$stem.chunk").toNioPath
                  // The block decoder itself bounds input to 2,500,000 bytes (Block.scala).
                  if entry.offset < 0 || entry.length <= 0 || entry.length > 2500000 || entry.end < entry.offset || entry.end > Files
                      .size(data)
                  then Left(corrupt(SnapshotReadError.PointOutsideData))
                  else
                    val channel = java.nio.channels.FileChannel.open(data, StandardOpenOption.READ)
                    val buffer = ByteBuffer.allocate(entry.length)
                    try
                      @annotation.tailrec
                      def read(at: Long): Boolean =
                        if !buffer.hasRemaining then true
                        else
                          val n = channel.read(buffer, at)
                          n > 0 && read(at + n)
                      if !read(entry.offset) then Left(corrupt(SnapshotReadError.TruncatedBlock))
                      else
                        val raw = buffer.array()
                        val crc = new CRC32()
                        crc.update(raw)
                        for
                          _ <- Either.cond(
                            crc.getValue.toInt == entry.crc,
                            (),
                            corrupt(SnapshotReadError.BlockChecksum)
                          )
                          block <- Block
                            .decode(Bytes.wrap(raw), byronEpochSlots)
                            .leftMap(e => corrupt(e))
                          h = block.header
                          _ <- Either.cond(
                            BlockRef(
                              h.slot,
                              h.hash,
                              h.blockNo,
                              h.isBoundary
                            ) == anchor && block.era == era,
                            (),
                            corrupt(SnapshotReadError.BlockAnchorMismatch)
                          )
                        yield ()
                    finally channel.close()
                }
      }
      _ <- checked.fold(_.raise[F, Unit], F.pure)
    yield ()
