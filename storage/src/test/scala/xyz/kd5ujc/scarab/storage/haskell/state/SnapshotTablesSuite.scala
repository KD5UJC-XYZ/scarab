// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.effect.SyncIO
import cats.mtl.Raise
import fs2.Stream
import java.nio.file.{Files, Path}
import java.util.zip.CRC32
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack

class SnapshotTablesSuite extends FunSuite:
  import SnapshotTables.*

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)
  private def right[A](e: Either[Error, A]): A = e.fold(e => fail(e.toString), identity)
  private def referenceCrc(bytes: Bytes): Long =
    val crc = new CRC32()
    bytes.withRange(crc.update)
    crc.getValue

  private val root = Path.of("fixtures/haskell-state")
  private val eras = Vector(
    "shelley-1000",
    "allegra-1800",
    "mary-2308",
    "alonzo-3013",
    "babbage-4000",
    "conway-4593"
  )
  private def file(era: String, name: String): Bytes =
    Bytes.wrap(Files.readAllBytes(root.resolve(era).resolve(name)))
  private val conway = file("conway-4593", "tables")

  test("CRC32 matches independent CRC32 including empty and standard check vector") {
    val samples = Vector(Bytes.empty, hex("313233343536373839"), conway)
    samples.foreach(b => assertEquals(SnapshotCrc.of(b).value, referenceCrc(b)))
    assertEquals(SnapshotCrc.of(samples(1)).value, 0xcbf43926L)
    val split = conway.size / 2
    assertEquals(
      SnapshotCrc.empty.update(conway.take(split)).update(conway.drop(split)).value,
      referenceCrc(conway)
    )
  }

  test("every real Shelley-based table preserves entries, framing, CRC and metadata") {
    eras.foreach { era =>
      val bytes = file(era, "tables")
      val (entries, summary) = right(decode(bytes))
      val framed = entries.foldLeft(right(prefix(summary.entries)))((acc, e) =>
        acc ++ right(encodeEntry(e))
      ) ++ suffix(summary.entries)
      assertEquals(framed, bytes, era)
      assertEquals(summary.crc.value, referenceCrc(bytes), era)
      val meta = SnapshotMeta
        .parse(new String(file(era, "meta").toArray, java.nio.charset.StandardCharsets.UTF_8))
        .fold(e => fail(e.toString), identity)
      assertEquals(
        SnapshotMeta.verify(meta, SnapshotCrc.of(file(era, "state")), summary.crc),
        Right(()),
        era
      )
      assertEquals(SnapshotMeta.encode(meta).flatMap(SnapshotMeta.parse), Right(meta), era)
    }
  }

  test("every two-chunk split and one-byte chunks give the same entries and original-byte CRC") {
    val (expected, summary) = right(decode(conway))
    (0 to conway.size).foreach { split =>
      val (first, a) = right(decoder.feed(conway.take(split)))
      val (last, b) = right(first.feed(conway.drop(split)))
      assertEquals(a ++ b, expected)
      assertEquals(right(last.finish).crc.value, summary.crc.value)
    }
    val (last, entries) = conway.value.foldLeft((decoder, Vector.empty[Entry])) {
      case ((d, out), byte) =>
        val (next, emitted) = right(d.feed(Bytes.wrap(Array(byte))))
        (next, out ++ emitted)
    }
    assertEquals(entries, expected)
    assertEquals(right(last.finish).entries, summary.entries)
  }

  test("definite and indefinite maps, empty maps and unsorted entries are framing-valid") {
    Vector("81a0", "81bfff", "9801b800").foreach(s =>
      assertEquals(right(decode(hex(s)))._1, Vector.empty)
    )
    val entries = right(decode(conway))._1.reverse
    val bytes = entries.foldLeft(hex("81bf"))((acc, e) => acc ++ right(encodeEntry(e))) ++ hex("ff")
    assertEquals(right(decode(bytes))._1, entries)
    val key = hex("00" * 34)
    val wideHeads = hex("9801b801590022") ++ key ++ hex("580100")
    assertEquals(right(decode(wideHeads))._1, Vector(Entry(key, hex("00"))))
    assertEquals(right(decode(wideHeads))._2.crc.value, referenceCrc(wideHeads))
  }

  test(
    "every truncation, wrong envelope, key width, indefinite string and trailing byte is typed"
  ) {
    (0 until conway.size).foreach(n => assert(decode(conway.take(n)).isLeft, s"prefix $n"))
    Vector("80a0", "82a0a0", "9fa0ff", "81a14040", "81a15fffff", "81a05800", "81a000").foreach(s =>
      assert(decode(hex(s)).isLeft, s)
    )
    assertEquals(decode(hex("81a0ff")), Left(Error.TrailingBytes(2L)))
    val hugeValue = hex("81a15822" + "00" * 34 + "5bffffffffffffffff")
    assertEquals(
      decode(hugeValue),
      Left(Error.LengthOutOfRange(Field.Value, BigInt("ffffffffffffffff", 16)))
    )
  }

  test("wide and one-byte-at-a-time tables stay stack safe on a 256 KiB worker") {
    onSmallStack {
      val entry = right(encodeEntry(Entry(hex("00" * 34), hex("00"))))
      val initial = right(decoder.feed(hex("81bf")))._1
      val current = (0 until 20000).foldLeft(initial) { (state, _) =>
        entry.value.foldLeft(state)((d, byte) => right(d.feed(Bytes.wrap(Array(byte))))._1)
      }
      val last = right(current.feed(hex("ff")))._1
      assertEquals(right(last.finish).entries, 20000L)
    }
  }

  test("metadata uses decimal CRC text and refuses corruption, other backends and versions") {
    val state = SnapshotCrc.of(file("conway-4593", "state"))
    val tables = SnapshotCrc.of(conway)
    val meta = SnapshotMeta.fromCrcs(state, tables)
    assertEquals(meta.checksum, 670422015L)
    assertEquals(
      SnapshotMeta.encode(meta),
      Right("""{"backend":"utxohd-mem","checksum":670422015,"tablesCodecVersion":1}""")
    )
    assert(
      SnapshotMeta
        .verify(meta.copy(checksum = 0), state, tables)
        .left
        .exists(_.isInstanceOf[MetaError.ChecksumMismatch])
    )
    assertEquals(
      SnapshotMeta.verify(meta.copy(backend = SnapshotBackend.Lsm), state, tables),
      Left(MetaError.UnsupportedBackend(SnapshotBackend.Lsm))
    )
    assertEquals(
      SnapshotMeta.encode(meta.copy(checksum = -1)),
      Left(MetaError.BadChecksum(BigInt(-1)))
    )
    assertEquals(
      SnapshotMeta.encode(meta.copy(tablesCodecVersion = 2)),
      Left(MetaError.UnknownTablesCodecVersion(BigInt(2)))
    )
    assert(SnapshotCrc.of(file("conway-4593", "state") ++ conway).value != meta.checksum)
  }

  test("the fs2 reader also handles a wide one-byte-chunk table on a 256 KiB worker") {
    given Raise[SyncIO, Error] with
      def functor = cats.Functor[SyncIO]
      def raise[E <: Error, A](error: E): SyncIO[A] = SyncIO.delay(fail(error.toString))
    onSmallStack {
      val entry = right(encodeEntry(Entry(hex("00" * 34), hex("00"))))
      val input =
        (Stream.emits(hex("81bf").value) ++ Stream.emits(entry.value).repeatN(20000) ++ Stream
          .emits(hex("ff").value))
          .covary[SyncIO]
          .chunkN(1)
          .unchunks
      val read = SnapshotTableStream.fold(input, 0L)((n, _) => SyncIO.pure(n + 1)).unsafeRunSync()
      assertEquals(read._1, 20000L)
      assertEquals(read._2.entries, 20000L)
    }
  }
