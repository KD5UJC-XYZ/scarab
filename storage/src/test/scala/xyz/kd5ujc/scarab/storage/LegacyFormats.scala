// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.haskell.*

/** The `ByteBuffer` parsers the Haskell ImmutableDB indices had before they moved to scodec, kept
  * verbatim as the oracle of [[HaskellFormatsDiffSuite]] and the baseline of [[FormatBenchSuite]]:
  * the scodec codecs must accept and reject exactly the same bytes and produce the same values.
  */
object LegacyFormats:

  object HaskellPrimary:
    def parse(
        bytes: Array[Byte],
        chunkSlots: ChunkSlots,
        tolerateTornTail: Boolean
    ): Either[ChunkFault, PrimaryIndex] =
      if bytes.isEmpty then Left(ChunkFault.PrimaryEmpty)
      else if bytes(0) != PrimaryIndex.Version.toByte then
        Left(ChunkFault.PrimaryVersion(bytes(0) & 0xff))
      else if (bytes.length - 1) % 4 != 0 && !tolerateTornTail then
        Left(ChunkFault.PrimaryLength(bytes.length))
      else
        val count = (bytes.length - 1) / 4
        val max = chunkSlots.value + 2
        if count.toLong > max then Left(ChunkFault.PrimaryTooLong(count, max))
        else
          val b = ByteBuffer.wrap(bytes, 1, count * 4)
          val offsets = Vector.fill(count)(b.getInt.toLong & 0xffffffffL)
          if offsets.headOption.exists(_ != 0L) then
            Left(ChunkFault.PrimaryFirstOffset(offsets.head))
          else
            offsets
              .zip(offsets.drop(1))
              .zipWithIndex
              .collectFirst {
                case ((f, t), r) if t - f != 0L && t - f != PrimaryIndex.EntrySize.toLong =>
                  ChunkFault.PrimaryStep(r, f, t)
              }
              .toLeft(PrimaryIndex(offsets))

    def encode(index: PrimaryIndex): Array[Byte] =
      val b = ByteBuffer.allocate(1 + 4 * index.offsets.size)
      b.put(PrimaryIndex.Version.toByte)
      index.offsets.foreach(o => b.putInt(o.toInt))
      b.array()

  object HaskellSecondary:
    val EntrySize = 56

    def encode(entries: Seq[SecondaryEntry]): Array[Byte] =
      val b = ByteBuffer.allocate(EntrySize * entries.size)
      entries.foreach { e =>
        b.putLong(e.blockOffset.value)
        b.putShort(e.headerOffset.toShort)
        b.putShort(e.headerSize.toShort)
        b.putInt(e.crc)
        b.put(e.headerHash.toArray)
        b.putLong(e.slotOrEpoch)
      }
      b.array()

    def parse(
        bytes: Array[Byte],
        tolerateTornTail: Boolean
    ): Either[ChunkFault, Vector[SecondaryEntry]] =
      if bytes.length % EntrySize != 0 && !tolerateTornTail then
        Left(ChunkFault.SecondaryLength(bytes.length))
      else
        val count = bytes.length / EntrySize
        val b = ByteBuffer.wrap(bytes)
        val out = Vector.newBuilder[SecondaryEntry]
        var fault: Option[ChunkFault] = None
        var i = 0
        while fault.isEmpty && i < count do
          val offset = b.getLong
          val headerOffset = b.getShort & 0xffff
          val headerSize = b.getShort & 0xffff
          val crc = b.getInt
          val hash = new Array[Byte](32)
          b.get(hash)
          val slot = b.getLong
          ByteOffset.of(offset) match
            case None                => fault = Some(ChunkFault.BlockOffsetRange(i))
            case Some(_) if slot < 0 => fault = Some(ChunkFault.SlotRange(i))
            case Some(off) =>
              out += SecondaryEntry(off, headerOffset, headerSize, crc, Bytes.wrap(hash), slot)
          i += 1
        fault.toLeft(out.result())
