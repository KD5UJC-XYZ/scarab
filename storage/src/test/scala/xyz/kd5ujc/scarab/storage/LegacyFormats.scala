// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.ByteBuffer
import java.util.zip.CRC32
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.haskell.*

/** The `ByteBuffer` parsers the storage formats had before they moved to scodec, kept verbatim as
  * the oracle of the differential tests and the baseline of [[FormatBenchSuite]]: the scodec codecs
  * must accept and reject exactly the same bytes and produce the same values.
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

  object Immutable:
    val HeaderSize = 8
    val EntrySize = 68

    def encode(e: FileImmutableStore.Entry): Array[Byte] =
      val b = ByteBuffer.allocate(EntrySize)
      b.putLong(e.offset).putInt(e.length).putInt(e.crc)
      b.putLong(e.ref.slot.toLong).putLong(e.ref.blockNo.toLong)
      b.put(e.ref.hash.toArray)
      b.put(if e.ref.boundary then 1.toByte else 0.toByte)
      b.array()

    private def decode(b: ByteBuffer): FileImmutableStore.Entry =
      val offset = b.getLong
      val length = b.getInt
      val crc = b.getInt
      val slot = b.getLong
      val blockNo = b.getLong
      val hash = new Array[Byte](32)
      b.get(hash)
      val flags = b.get()
      b.position(b.position() + 3)
      FileImmutableStore.Entry(
        offset,
        length,
        crc,
        BlockRef(slot, Bytes.fromArray(hash), blockNo, (flags & 1) != 0)
      )

    def entries(index: Array[Byte]): Vector[FileImmutableStore.Entry] =
      if index.length < HeaderSize then Vector.empty
      else
        val count = (index.length - HeaderSize) / EntrySize
        val b = ByteBuffer.wrap(index, HeaderSize, count * EntrySize)
        Vector.fill(count)(decode(b))

  object Volatile:
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
