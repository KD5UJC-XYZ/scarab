// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import fs2.Chunk
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** CRC32 of the original file bytes, as `System.FS.CRC`. The accumulator is immutable, so it can
  * travel with a streaming decoder without a shared mutable checksum object.
  */
opaque type SnapshotCrc = Int

object SnapshotCrc:
  val empty: SnapshotCrc = -1

  private val table: Vector[Int] = Vector.tabulate(256) { n =>
    (0 until 8).foldLeft(n)((c, _) => if (c & 1) == 0 then c >>> 1 else (c >>> 1) ^ 0xedb88320)
  }

  extension (crc: SnapshotCrc)
    def value: Long = (crc ^ -1).toLong & 0xffffffffL

    def update(bytes: Bytes): SnapshotCrc =
      bytes.foldLeft[Int](crc)((acc, b) => (acc >>> 8) ^ table((acc ^ b) & 0xff))

    def update(chunk: Chunk[Byte]): SnapshotCrc =
      @tailrec def go(i: Int, acc: Int): Int =
        if i == chunk.size then acc
        else go(i + 1, (acc >>> 8) ^ table((acc ^ chunk(i)) & 0xff))
      go(0, crc)

  def of(bytes: Bytes): SnapshotCrc = empty.update(bytes)

  /** `crcOfConcat`, ouroboros-consensus 82ecba3 `Util/CRC.hs:20-26`: decimal ASCII of the two
    * unsigned CRCs, with no delimiter, rather than the concatenated files or their binary CRCs.
    */
  def combined(state: SnapshotCrc, tables: SnapshotCrc): SnapshotCrc =
    of(
      Bytes.wrap(
        (state.value.toString + tables.value.toString)
          .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      )
    )
