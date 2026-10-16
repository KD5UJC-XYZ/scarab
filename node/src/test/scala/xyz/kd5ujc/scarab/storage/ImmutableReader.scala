// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.io.RandomAccessFile
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes

/** The blocks of a Scala ImmutableDB directory, read without opening it: [[FileImmutableStore]]
  * opens its newest chunk for writing and may rewrite what it finds, which a tool that looks at a
  * recorded sync (the K1 Preview run, mounted read-only) must not do.
  */
object ImmutableReader:
  private val ChunkSlots = """chunkSlots=(\d+)""".r.unanchored

  /** The slots per chunk of the database in `dir`, from its `format` file. */
  def chunkSlots(dir: Path): Option[Long] =
    val text = new String(Files.readAllBytes(dir.resolve("format")), "UTF-8")
    text match
      case ChunkSlots(n) => n.toLongOption
      case _             => None

  /** The blocks with `after < slot <= upTo`, in order. Reads a chunk's index, then each block. */
  def blocks(dir: Path, after: BigInt, upTo: BigInt): Iterator[ImmutableStore.Stored] =
    val size = chunkSlots(dir).getOrElse(FileImmutableStore.DefaultChunkSlots)
    val chunks = (after / size).toLong to (upTo / size).toLong
    chunks.iterator.flatMap { c =>
      val index = dir.resolve(f"$c%05d.index")
      if !Files.exists(index) then Iterator.empty
      else
        val wanted = FileImmutableStore
          .entries(Files.readAllBytes(index))
          .filter(e => e.ref.slot > after && e.ref.slot <= upTo)
        val file = new RandomAccessFile(dir.resolve(f"$c%05d.chunk").toFile, "r")
        try
          wanted.map { e =>
            val raw = new Array[Byte](e.length)
            file.seek(e.offset)
            file.readFully(raw)
            ImmutableStore.Stored(e.ref, Bytes.fromArray(raw))
          }.iterator
        finally file.close()
    }
