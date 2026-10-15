// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.haskell.*

/** The persisted bytes of every fixed-layout format, pinned as hex computed independently of the
  * code (Python `struct` /`zlib`) from the layouts documented at each format. Databases written by
  * an earlier build must open, so these bytes may only change together with a format version.
  */
class FormatGoldenSuite extends FunSuite:
  private val hash = Bytes.fromArray(Array.tabulate[Byte](32)(_.toByte))

  private def hex(s: String): Array[Byte] = Bytes.fromHex(s).fold(e => fail(e), _.toArray)
  private def hex(a: Array[Byte]): String = Bytes.wrap(a).hex

  // ---- the file store's index entry: 68 bytes ----

  private val immutableEntry = FileImmutableStore.Entry(
    offset = 4096L,
    length = 1234,
    crc = 0xdeadbeef,
    ref = BlockRef(BigInt(123456789L), hash, BigInt(987654L), boundary = true)
  )
  private val immutableHex =
    "0000000000001000" + "000004d2" + "deadbeef" + "00000000075bcd15" + "00000000000f1206" +
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" + "01000000"

  test("immutable store index entry: layout, and the three padding bytes are ignored on read") {
    assertEquals(hex(FileImmutableStore.encode(immutableEntry)), immutableHex)
    val bytes = FileImmutableStore.IndexMagic ++ hex(immutableHex)
    assertEquals(FileImmutableStore.entries(bytes), Vector(immutableEntry))
    // The writer always pads with zeros; a reader must not be stricter than the old one was.
    val padded = bytes.clone()
    padded(bytes.length - 1) = 0x7f
    padded(bytes.length - 3) = 0x55
    assertEquals(FileImmutableStore.entries(padded), Vector(immutableEntry))
  }

  test("immutable store index entry: flags other than bit 0 read as not boundary") {
    val flags = hex(immutableHex)
    flags(64) = 0x02
    val entry = FileImmutableStore.entries(FileImmutableStore.IndexMagic ++ flags).head
    assertEquals(entry.ref.boundary, false)
    val both = hex(immutableHex)
    both(64) = 0xff.toByte
    assert(FileImmutableStore.entries(FileImmutableStore.IndexMagic ++ both).head.ref.boundary)
  }

  test("immutable store index entry: all-ones fields") {
    val raw = hex(
      "0000000000000000" + "00000001" + "00000000" + "0000000000000000" + "0000000000000000" +
        "ff" * 32 + "00000000"
    )
    val entry = FileImmutableStore.entries(FileImmutableStore.IndexMagic ++ raw).head
    assertEquals(entry.length, 1)
    assertEquals(entry.ref.hash, Bytes.fromArray(Array.fill(32)(0xff.toByte)))
    assertEquals(hex(FileImmutableStore.encode(entry)), hex(raw))
  }

  test("immutable store index: a partial last entry is not read") {
    val bytes = FileImmutableStore.IndexMagic ++ hex(immutableHex) ++ hex(immutableHex).take(67)
    assertEquals(FileImmutableStore.entries(bytes).size, 1)
    assertEquals(FileImmutableStore.entries(FileImmutableStore.IndexMagic.take(5)).size, 0)
  }

  // ---- the volatile log record: 58-byte header and payload, CRC-32 over all after the crc ----

  private val blockRecord = VolatileRecord.Block(
    BlockRef(BigInt(123456789L), hash, BigInt(987654L), boundary = true),
    Bytes.fromArray(Array(0xa1, 0x02, 0x03).map(_.toByte))
  )
  private val blockHex =
    "ef972cde" + "01" + "01" + "00000003" + "00000000075bcd15" + "00000000000f1206" +
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" + "a10203"
  private val rollbackRecord = VolatileRecord.Rollback(
    ChainSync.Point.Block(ChainSync.UInt64.saturating(BigInt(5000)), hash)
  )
  private val rollbackHex =
    "02aba1a4" + "02" + "00" + "00000000" + "0000000000001388" + "0000000000000000" +
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
  private val originHex =
    "cb595e13" + "02" + "02" + "00000000" + "0000000000000000" + "0000000000000000" + "00" * 32

  test("volatile record: block, rollback to a block and rollback to origin") {
    assertEquals(hex(VolatileStore.encode(blockRecord)), blockHex)
    assertEquals(hex(VolatileStore.encode(rollbackRecord)), rollbackHex)
    assertEquals(
      hex(VolatileStore.encode(VolatileRecord.Rollback(ChainSync.Point.Origin))),
      originHex
    )
    val log = hex(blockHex + rollbackHex + originHex)
    val decoded = VolatileStore.decodeAll(log)
    assertEquals(
      decoded.map(_._1),
      Vector(blockRecord, rollbackRecord, VolatileRecord.Rollback(ChainSync.Point.Origin))
    )
    assertEquals(decoded.map(_._2), Vector(61, 61 + 58, 61 + 58 + 58))
  }

  test("volatile log: decoding stops at a bad checksum, a torn tail or an unknown kind") {
    val good = hex(blockHex)
    val flipped = good.clone()
    flipped(60) = (flipped(60) ^ 1).toByte
    assertEquals(VolatileStore.decodeAll(good ++ flipped ++ good).size, 1)
    assertEquals(VolatileStore.decodeAll(good ++ good.take(57)).size, 1)
    assertEquals(VolatileStore.decodeAll(good ++ good.take(60)).size, 1)
    val unknown = hex(rollbackHex)
    unknown(4) = 0x09 // kind 9, checksum no longer matches
    assertEquals(VolatileStore.decodeAll(unknown).size, 0)
  }

  // ---- the Haskell ImmutableDB indices (Primary.hs, Secondary.hs of ouroboros-consensus) ----

  private val secondaryHex =
    "0102030405060708" + "fffe" + "0003" + "80000001" +
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" + "1122334455667788"

  test("haskell secondary entry: 56 bytes, big-endian, unsigned u16 fields") {
    val entry = SecondaryEntry(
      ByteOffset.of(0x0102030405060708L).get,
      0xfffe,
      3,
      0x80000001,
      hash,
      0x1122334455667788L
    )
    assertEquals(hex(SecondaryIndex.encode(List(entry)).require), secondaryHex)
    assertEquals(
      SecondaryIndex.parse(hex(secondaryHex), tolerateTornTail = false),
      Right(Vector(entry))
    )
  }

  test("haskell primary index: version byte, then big-endian u32 offsets") {
    val bytes = hex("01" + "00000000" + "00000038" + "00000038" + "00000070")
    val index = PrimaryIndex(Vector(0L, 56L, 56L, 112L))
    assertEquals(hex(PrimaryIndex.encode(index).require), "0100000000000000380000003800000070")
    assertEquals(
      PrimaryIndex.parse(bytes, ChunkSlots.of(20).get, tolerateTornTail = false),
      Right(index)
    )
    assertEquals(index.filled, Vector(0, 2))
  }
