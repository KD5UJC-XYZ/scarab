// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.ledger.testByteStrings
import cats.effect.IO
import fs2.Stream
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.storage.haskell.state.SnapshotTables

class MemPackComparisonSuite extends CatsEffectSuite:
  import MemPackJoin.*
  private val raw =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/tables")))
  private def right[E, A](r: Either[E, A]): A = r.fold(e => fail(e.toString), identity)
  private val entries = right(SnapshotTables.decode(raw))._1
  private val outputs = entries.map(e => e.key -> right(TxOutMemPack.decode[Conway](e.value)))
  private def input(b: Bytes) = Stream.emits(b.value).covary[IO]
  private def local(rows: Vector[(Bytes, xyz.kd5ujc.scarab.ledger.TxOut)]) =
    Stream.emits(rows).covary[IO]
  private def encoded(rows: Vector[SnapshotTables.Entry]): Bytes =
    Bytes.wrap(Array(0x81.toByte, (0xa0 + rows.size).toByte)) ++
      rows.foldLeft(Bytes.empty)((acc, e) => acc ++ right(SnapshotTables.encodeEntry(e)))

  test(
    "native historical constructors are explicit; canonical profile rejects their byte changes"
  ) {
    for
      canonical <- scan(input(raw), Encoding.Canonical)
      historical <- scan(input(raw), Encoding.HistoricalConstructors, Some(entries.size.toLong))
      joined <- compareStreams(
        input(raw),
        local(outputs),
        Encoding.HistoricalConstructors,
        Some(entries.size.toLong)
      )
    yield
      assert(canonical.left.exists(_.isInstanceOf[Error.PackedBytes]), canonical.toString)
      assert(
        historical.exists(s => s.entries == entries.size && s.differentPackedBytes > 0),
        historical.toString
      )
      assertEquals(joined, historical)
  }

  test("original nonminimal CBOR is compared through canonical semantics and counted separately") {
    val (key, out) = outputs.head
    val head = out.raw.toArray(0)
    assert((head & 0xe0) == 0x80 && (head & 0x1f) < 24)
    val nonminimal = Bytes.wrap(Array(0x98.toByte, (head & 0x1f).toByte)) ++ out.raw.drop(1)
    val stored = right(xyz.kd5ujc.scarab.ledger.TxOut.fromBytes(nonminimal))
    compareStreams(
      input(raw),
      local(outputs.updated(0, key -> stored)),
      Encoding.HistoricalConstructors
    ).map { result =>
      assert(
        result.exists(s => s.entries == entries.size && s.differentOriginalCbor == 1),
        result.toString
      )
    }
  }

  test("empty and disjoint inputs cannot satisfy an output comparison") {
    for
      empty <- compareStreams(
        input(encoded(Vector.empty)),
        local(Vector.empty),
        Encoding.HistoricalConstructors
      )
      disjoint <- compareStreams(
        input(raw),
        local(outputs.map((key, out) => (key ++ Bytes.wrap(Array(0.toByte))) -> out)),
        Encoding.HistoricalConstructors
      )
    yield
      assertEquals(empty, Left(Error.Empty))
      assert(disjoint.left.exists(_.isInstanceOf[Error.KeySet]), disjoint.toString)
  }

  test("missing checkpoint records and wrong pinned counts fail") {
    for
      missing <- compareStreams(
        input(raw),
        local(outputs.dropRight(1)),
        Encoding.HistoricalConstructors
      )
      count <- scan(input(raw), Encoding.HistoricalConstructors, Some(entries.size.toLong + 1))
    yield
      assert(missing.left.exists(_.isInstanceOf[Error.KeySet]), missing.toString)
      assertEquals(count, Left(Error.Count(entries.size.toLong + 1, entries.size.toLong)))
  }

  test("all-undecodable native values fail instead of looking like zero semantic mismatches") {
    val broken = encoded(entries.map(_.copy(value = Bytes.empty)))
    compareStreams(input(broken), local(outputs), Encoding.HistoricalConstructors).map { result =>
      assert(result.left.exists(_.isInstanceOf[Error.Decode]), result.toString)
    }
  }

  test("tampered native value and duplicate keys fail") {
    val tampered = encoded(entries.updated(0, entries.head.copy(value = entries.last.value)))
    val duplicates = encoded(entries.take(1) ++ entries.take(1))
    for
      changed <- compareStreams(input(tampered), local(outputs), Encoding.HistoricalConstructors)
      repeated <- scan(input(duplicates), Encoding.HistoricalConstructors)
    yield
      assert(changed.left.exists(_.isInstanceOf[Error.Semantics]), changed.toString)
      assert(repeated.left.exists(_.isInstanceOf[Error.Order]), repeated.toString)
  }

  test("every proper prefix, unterminated indefinite map and trailing bytes fail framing") {
    val indefinite = Bytes.wrap(Array(0x81.toByte, 0xbf.toByte)) ++
      entries.foldLeft(Bytes.empty)((acc, e) => acc ++ right(SnapshotTables.encodeEntry(e)))
    val invalid = (0 until raw.size).toVector.map(raw.take) ++ Vector(
      indefinite,
      raw ++ Bytes.wrap(Array(0.toByte))
    )
    invalid.foldLeft(IO.unit) { (before, b) =>
      before *> scan(input(b), Encoding.HistoricalConstructors).map { result =>
        assert(result.left.exists(_.isInstanceOf[Error.Framing]), s"${b.size}: $result")
      }
    }
  }

  test("missing and tampered external files cannot satisfy the pinned native identity") {
    StorageTestKit.tempDir.use { dir =>
      val altered = dir / "tables"
      for
        missing <- scanTables(dir / "missing")
        _ <- IO.blocking(Files.write(altered.toNioPath, raw.toArray))
        tampered <- scanTables(altered)
      yield
        assert(missing.left.exists(_.isInstanceOf[Error.Read]), missing.toString)
        assert(tampered.left.exists(_.isInstanceOf[Error.Identity]), tampered.toString)
    }
  }

  test(
    "all 95 native sample rows exercise scripts, datum and sizing under the canonical contract"
  ) {
    val lines = Files.readAllLines(Path.of("fixtures/mempack/preprod-conway-sample.tsv"))
    import scala.jdk.CollectionConverters.*
    val samples = lines.asScala.toVector.map { line =>
      val fields = line.split('\t')
      SnapshotTables.Entry(right(Bytes.fromHex(fields(1))), right(Bytes.fromHex(fields(2))))
    }
    assertEquals(samples.size, 95)
    samples.foldLeft(IO.unit) { (previous, entry) =>
      val out = right(TxOutMemPack.decode[Conway](entry.value))
      previous *> compareStreams(
        input(encoded(Vector(entry))),
        local(Vector(entry.key -> out)),
        Encoding.Canonical,
        Some(1L)
      ).map { result =>
        assertEquals(result, Right(Summary(1L, 0L, 0L)), entry.key.hex)
      }
    }
  }
