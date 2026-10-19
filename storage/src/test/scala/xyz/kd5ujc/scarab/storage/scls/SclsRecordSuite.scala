// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.Id
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack

class SclsRecordSuite extends FunSuite:
  import SclsRecord.*
  private def right[E, A](result: Either[E, A]): A = result.fold(e => fail(e.toString), identity)
  private def bits[A](result: scodec.Attempt[A]): A = result match
    case scodec.Attempt.Successful(value) => value
    case scodec.Attempt.Failure(cause)    => fail(cause.message)
  private def hex(value: String): Bytes = right(Bytes.fromHex(value))
  private val reference =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls")))
  private val limit = right(SclsFraming.Limit.fromInt(16 * 1024 * 1024 + 4096))
  private val frames = right(SclsFraming.decode(reference, limit))
  private val records = frames.map(frame => right(decode(frame)))
  private val chunks = records.collect { case chunk: Record.Chunk => chunk }
  private val manifest = records
    .collectFirst { case manifest: Record.Manifest => manifest }
    .getOrElse(fail("missing reference manifest"))

  test("typed records re-encode all actual Haskell bytes, including summary and manifest footer") {
    assertEquals(records.head, Record.Header)
    assertEquals(records.map(record => right(encode(record))), frames)
    assertEquals(manifest.slot, BigInt(4593))
    assertEquals(manifest.entries, BigInt(16))
    assertEquals(manifest.chunks, BigInt(8))
    assertEquals(manifest.previous, BigInt(0))
    assertEquals(manifest.summary, Summary("2026-10-18T00:00:00Z", "scls-tool:reference", None))
    assertEquals(manifest.namespaces.map(_.namespace), Namespace.values.toVector)
    assertEquals(manifest.namespaces.count(_.entries == 0), 4)
    assertEquals(
      manifest.root.bytes.hex,
      "fb12b75ed9b1abcbd341bd751b0c45606e060fd714e6bb97ce5dcb0a"
    )
    assertEquals(chunks.map(_.count).sum, 16L)
    val rows = chunks.flatMap(chunk =>
      right(entries(chunk)).map { entry =>
        s"${chunk.namespace.name}\t${entry.key.hex}\t${entry.value.hex}"
      }
    )
    assertEquals(
      rows.mkString("", "\n", "\n"),
      Files.readString(Path.of("fixtures/scls/conway-4593/entries.tsv"))
    )
  }

  test("record fields reject unknown versions, compression, namespaces and incorrect key widths") {
    val header = frames.head
    assertEquals(
      decode(header.copy(contents = hex("53434c5300000002"))),
      Left(Error.UnsupportedVersion(2))
    )
    assertEquals(
      decode(header.copy(contents = hex("4e4f504500000001"))),
      Left(Error.InvalidMagic(hex("4e4f5045")))
    )
    val chunkFrame = frames(1)
    val compression = chunkFrame.contents.take(8) ++ hex("01") ++ chunkFrame.contents.drop(9)
    assertEquals(
      decode(chunkFrame.copy(contents = compression)),
      Left(Error.UnsupportedCompression(1))
    )
    val namespaceStart = 13
    val unknown = chunkFrame.contents.take(namespaceStart) ++ hex("78") ++ chunkFrame.contents.drop(
      namespaceStart + 1
    )
    assertEquals(
      decode(chunkFrame.copy(contents = unknown)),
      Left(Error.UnsupportedNamespace("xlocks/v0"))
    )
    val keyOffset = namespaceStart + Namespace.Blocks.bytes.size
    val badKey = chunkFrame.contents.take(keyOffset) ++ hex("00000001") ++ chunkFrame.contents.drop(
      keyOffset + 4
    )
    assertEquals(
      decode(chunkFrame.copy(contents = badKey)),
      Left(Error.KeySize(Namespace.Blocks, 1))
    )
    assertEquals(
      decode(SclsFraming.Frame(SclsFraming.Kind.Metadata, Bytes.empty)),
      Left(Error.UnsupportedRecord(SclsFraming.Kind.Metadata))
    )
  }

  test("truncated header and manifest fields fail, and their trailing bytes are not ignored") {
    frames.filter(_.kind != SclsFraming.Kind.Chunk).foreach { frame =>
      (0 until frame.contents.size).foreach { end =>
        assert(
          decode(frame.copy(contents = frame.contents.take(end))).isLeft,
          s"${frame.kind} prefix $end"
        )
      }
      assert(decode(frame.copy(contents = frame.contents ++ hex("00"))).isLeft)
    }
    val last = frames.last
    val wrongFooter = last.contents.take(last.contents.size - 4) ++ hex("00000000")
    assertEquals(
      decode(last.copy(contents = wrongFooter)),
      Left(Error.FooterSize(last.contents.size.toLong + 1, 0))
    )
  }

  test("unsigned64 values remain exact and encoding rejects values outside the format range") {
    val maximum = (BigInt(1) << 64) - 1
    val max: Record.Manifest =
      manifest.copy(slot = maximum, entries = maximum, chunks = maximum, previous = maximum)
    assertEquals(right(decode(right(encode(max)))), max)
    assertEquals(encode(max.copy(slot = maximum + 1)), Left(Error.Word64Range(maximum + 1)))
    assertEquals(encode(max.copy(slot = BigInt(-1))), Left(Error.Word64Range(BigInt(-1))))
  }

  test("a short raw chunk footer reports its required size before taking entry data") {
    val headerSize = 8 + 1 + 4 + Namespace.Blocks.bytes.size + 4
    assertEquals(
      decode(frames(1).copy(contents = frames(1).contents.take(headerSize + 31))),
      Left(Error.Truncated(headerSize, 32, 31))
    )
  }

  test("manifest namespace order and uniqueness are checked before map construction") {
    val reversed = manifest.copy(namespaces = manifest.namespaces.reverse)
    assert(encode(reversed).left.exists {
      case Error.NamespaceOrder(_, _) => true
      case _                          => false
    })
    val duplicate = manifest.copy(namespaces = Vector.fill(2)(manifest.namespaces.head))
    assertEquals(encode(duplicate), Left(Error.NamespaceOrder(Namespace.Blocks, Namespace.Blocks)))
  }

  test("entry length, count, duplicate keys and ordering are rejected on a small stack") {
    onSmallStack {
      val original = chunks.head
      val decoded = right(entries(original))
      assertEquals(
        entries(original.copy(count = original.count + 1)),
        Left(Error.EntryCount(original.count + 1, decoded.size))
      )
      assertEquals(
        entries(original.copy(data = hex("00000000"))),
        Left(Error.EntrySize(original.namespace, 0))
      )
      val first = decoded.head
      val bodySize = first.key.size + first.value.size
      val length = bits(scodec.codecs.uint32.encode(bodySize.toLong))
      val entry = Bytes.wrap(length.toByteArray) ++ first.key ++ first.value
      assertEquals(
        entries(original.copy(data = entry ++ entry, count = 2)),
        Left(Error.EntryOrder(original.namespace, first.key, first.key))
      )
      val count = 100000
      val rows = Vector.tabulate(count) { index =>
        val suffix = bits(scodec.codecs.int64.encode(index.toLong))
        val key = Bytes.wrap(Array.fill[Byte](28)(0)) ++ Bytes.wrap(suffix.toByteArray)
        Bytes.wrap(bits(scodec.codecs.uint32.encode(37L)).toByteArray) ++ key ++ hex("00")
      }
      val data = Bytes.wrap(fs2.Chunk.concat(rows.map(row => fs2.Chunk.array(row.toArray))).toArray)
      assertEquals(right(entries(original.copy(data = data, count = count))).size, count)
    }
  }

  test("effectful entry folding is stack safe and retains only its accumulator and last key") {
    onSmallStack {
      val count = 100000
      val rows = Vector.tabulate(count) { index =>
        val suffix = bits(scodec.codecs.int64.encode(index.toLong))
        val key = Bytes.wrap(Array.fill[Byte](28)(0)) ++ Bytes.wrap(suffix.toByteArray)
        Bytes.wrap(bits(scodec.codecs.uint32.encode(37L)).toByteArray) ++ key ++ hex("00")
      }
      val data = Bytes.wrap(fs2.Chunk.concat(rows.map(row => fs2.Chunk.array(row.toArray))).toArray)
      val chunk = chunks.head.copy(data = data, count = count)
      val result = SclsRecord.foldEntriesM[Id, Long](chunk, 0L) { (total, _) => total + 1L }
      assertEquals(
        result,
        Right(
          (
            count.toLong,
            Some(
              Bytes.wrap(
                Array.fill[Byte](28)(0) ++ bits(
                  scodec.codecs.int64.encode((count - 1).toLong)
                ).toByteArray
              )
            )
          )
        )
      )
    }
  }
