// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.NonEmptyList
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef

/** The heap the immutable store's cache of sealed chunks' indexes
  * ([[FileImmutableStore.IndexCache]]) holds per index entry. Run by hand in the JDK container,
  * with the node's documented heap settings:
  *
  * {{{
  * java -Xms3g -Xmx3g -XX:+UseG1GC -cp <tools classpath> xyz.kd5ujc.scarab.storage.IndexCacheHeapMain [repetitions]
  * }}}
  *
  * For each shape of chunk it writes each chunk's index as the store writes it (`encode`, after the
  * magic), reads it back as the store reads it (`entries`), and adds it to a cache with no bound,
  * as a miss adds it. It prints the heap in use after full collections with the cache reachable,
  * less the heap in use before the cache was made, divided by the entries held, for each
  * repetition, then the least, the mean and the most. The shapes: a Byron chunk of 21,601 entries
  * (a boundary block and a block in each of its 21,600 slots, about 650 bytes each), thirteen of
  * them (about the store's bound of 270,000 entries) and 250 of them (the reference's bound of 250
  * past chunks, at that size); and 250 Shelley-era chunks of 1,080 entries (a block every twenty
  * slots, 20 to 90 kB each).
  */
object IndexCacheHeapMain extends IOApp:
  private val heap = ManagementFactory.getMemoryMXBean

  /** The heap in use once three full collections have run. */
  private val settled: IO[Long] =
    IO.blocking(heap.gc()).replicateA_(3) *> IO(heap.getHeapMemoryUsage.getUsed)

  /** A chunk's entries: from its number and an entry's position, the entry's slot, block number,
    * size and whether it is a boundary block.
    */
  private final case class Shape(
      name: String,
      perChunk: Int,
      firstChunk: Int,
      chunks: Int,
      entry: (Int, Int) => (Long, Long, Int, Boolean)
  ):
    def entries: Long = chunks.toLong * perChunk

  private val ChunkSlots = FileImmutableStore.DefaultChunkSlots

  private def byron(chunks: Int) = Shape(
    s"Byron, $chunks chunks of 21,601",
    21_601,
    100,
    chunks,
    (c, i) =>
      if i == 0 then (c * ChunkSlots, c * ChunkSlots - 1, 1_000, true)
      else (c * ChunkSlots + i - 1, c * ChunkSlots + i - 1, 600 + (i * 37) % 100, false)
  )

  private val shelley = Shape(
    "Shelley era, 250 chunks of 1,080",
    1_080,
    5_000,
    250,
    (c, i) =>
      (c * ChunkSlots + 20L * i, 10_000_000L + c * 1_080L + i, 20_000 + (i * 7_919) % 70_000, false)
  )

  /** A distinct 32-byte hash for entry `i` of chunk `c`. */
  private def hash(c: Int, i: Int): Array[Byte] =
    val b = ByteBuffer.allocate(32)
    (0 until 8).foreach(k => b.putInt((c * 1_000_003 + i) * (2 * k + 1) ^ 0x9e3779b9))
    b.array()

  /** Chunk `c`'s index file, as the store writes it. */
  private def indexFile(shape: Shape, c: Int): Array[Byte] =
    val file = ByteBuffer.allocate(
      FileImmutableStore.HeaderSize + shape.perChunk * FileImmutableStore.EntrySize
    )
    file.put(FileImmutableStore.IndexMagic)
    (0 until shape.perChunk).foldLeft(0L) { (at, i) =>
      val (slot, number, size, boundary) = shape.entry(c, i)
      val h = hash(c, i)
      val ref = BlockRef(BigInt(slot), Bytes.fromArray(h), BigInt(number), boundary)
      file.put(
        FileImmutableStore.encode(
          FileImmutableStore.Entry(at, size, FileImmutableStore.crcOf(h, 0, h.length), ref)
        )
      )
      at + size
    }
    file.array()

  /** The cache holding every chunk of `shape`, each read from its index file. */
  private def filled(shape: Shape): FileImmutableStore.IndexCache =
    (shape.firstChunk until shape.firstChunk + shape.chunks).foldLeft(
      FileImmutableStore.IndexCache.empty
    )((cache, c) =>
      cache.added(c, FileImmutableStore.entries(indexFile(shape, c)), Long.MaxValue, 0.seconds)
    )

  /** The heap per entry held, in bytes, of one cache of `shape`. */
  private def perEntry(shape: Shape): IO[Double] =
    for
      before <- settled
      cache <- IO(filled(shape))
      after <- settled
      _ <- IO(java.lang.ref.Reference.reachabilityFence(cache))
      _ <- IO.raiseUnless(cache.total == shape.entries)(
        new IllegalStateException(s"held ${cache.total} entries of ${shape.entries}")
      )
    yield (after - before).toDouble / shape.entries

  /** Measures each shape `repetitions` times, after one unmeasured run, and prints the results. */
  private def measure(repetitions: Int): IO[Unit] =
    List(shelley, byron(13), byron(250)).traverse_ { shape =>
      for
        // once unmeasured, so that the classes are loaded and the code compiled
        _ <- perEntry(shape)
        measured <- (perEntry(shape), perEntry(shape).replicateA(repetitions - 1))
          .mapN(NonEmptyList(_, _))
        mean = measured.toList.sum / measured.size
        each = measured.map(b => f"$b%.1f").toList.mkString(" ")
        held = mean * shape.entries / (1 << 20)
        _ <- IO.println(
          f"${shape.name}: ${shape.entries}%,d entries; bytes per entry $each; " +
            f"least ${measured.minimum}%.1f, mean $mean%.1f, most ${measured.maximum}%.1f; " +
            f"held $held%.0f MiB"
        )
      yield ()
    }

  def run(args: List[String]): IO[ExitCode] =
    args.headOption.fold(Option(5))(_.toIntOption.filter(_ > 0)) match
      case Some(repetitions) => measure(repetitions).as(ExitCode.Success)
      case None =>
        IO.println("usage: IndexCacheHeapMain [repetitions, a positive number; 5 if none]")
          .as(ExitCode(2))
