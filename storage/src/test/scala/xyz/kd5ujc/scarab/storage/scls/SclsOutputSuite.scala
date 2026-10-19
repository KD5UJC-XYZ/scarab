// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, Record, Summary}

class SclsOutputSuite extends FunSuite:
  import SclsOutput.*

  private def right[E, A](result: Either[E, A]): A = result.fold(e => fail(e.toString), identity)
  private val limit = right(SclsFraming.Limit.fromInt(1024 * 1024))
  private val reference =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls")))
  private val frames = right(SclsFraming.decode(reference, limit))
  private val records = frames.map(frame => right(SclsRecord.decode(frame)))
  private val manifest = records
    .collectFirst { case value: Record.Manifest => value }
    .getOrElse(fail("reference manifest"))
  private val entries = records.collect { case chunk: Record.Chunk => chunk }.flatMap { chunk =>
    right(SclsRecord.entries(chunk)).map(chunk.namespace -> _)
  }
  private val summary = Summary("2026-10-18T00:00:00Z", "Scarab test", None)

  private def key(index: Int): Bytes =
    Bytes.wrap(Array.fill[Byte](28)(0) ++ Array.tabulate[Byte](8) { place =>
      ((BigInt(index) >> ((7 - place) * 8)) & 255).toByte
    })

  private def written(
      config: Config,
      values: Vector[(Namespace, Entry)],
      slot: BigInt = manifest.slot,
      detail: Summary = manifest.summary
  ): Vector[Bytes] =
    val (builder, header) = right(begin(config))
    val (end, chunks) = right(
      values.foldLeftM((builder, Vector(header))) { case ((current, out), (namespace, entry)) =>
        current.push(namespace, entry).map((next, emitted) => next -> (out ++ emitted))
      }
    )
    chunks ++ right(end.finish(slot, detail))

  private def decoded(records: Vector[Bytes]): Vector[SclsFraming.Frame] =
    records.flatMap(bytes => right(SclsFraming.decode(bytes, limit)))

  test("the builder reproduces the complete actual Haskell artifact byte for byte") {
    val output = written(right(Config.fromInts(1024 * 1024, 1000)), entries)
    assertEquals(decoded(output), frames)
    assertEquals(
      Bytes.wrap(fs2.Chunk.concat(output.map(b => fs2.Chunk.array(b.toArray))).toArray),
      reference
    )
    assertEquals(right(SclsValidation.validate(decoded(output))).manifest, manifest)
  }

  test("entry-count chunk boundaries preserve actual reference commitments and namespace totals") {
    val output = written(right(Config.fromInts(8192, 1)), entries)
    val verified = right(SclsValidation.validate(decoded(output))).manifest
    assertEquals(verified.root, manifest.root)
    assertEquals(verified.entries, manifest.entries)
    assertEquals(verified.chunks, manifest.entries)
    assertEquals(verified.namespaces.map(_.root), manifest.namespaces.map(_.root))
    assert(output.forall(_.size <= 8192 + 4))
  }

  test("the byte budget flushes before overflow and includes chunk overhead and frame tag") {
    val config = right(Config.fromInts(4096, 100))
    val values = Vector(1900, 2100, 1900).zipWithIndex.map { (size, index) =>
      Namespace.Blocks -> Entry(key(index), Bytes.wrap(Array.fill[Byte](size)(42)))
    }
    val output = written(config, values, 0, summary)
    assert(output.forall(_.size <= 4100))
    val verified = right(SclsValidation.validate(decoded(output))).manifest
    assertEquals(verified.entries, BigInt(3))
    assertEquals(verified.chunks, BigInt(3))
    val (initial, _) = right(begin(config))
    val exact = Entry(key(0), Bytes.wrap(Array.fill[Byte](3997)(0)))
    val (one, _) = right(initial.push(Namespace.Blocks, exact))
    val complete = right(one.finish(0, summary))
    assertEquals(complete.head.size, 4100)
    val excessive = exact.copy(value = exact.value ++ Bytes.wrap(Array[Byte](0)))
    assertEquals(
      initial.push(Namespace.Blocks, excessive),
      Left(Error.EntryTooLarge(Namespace.Blocks, 4038L, 4037L))
    )
  }

  test("rejected entries cannot mutate a reusable builder or bypass key and namespace order") {
    val config = right(Config.fromInts(8192, 1))
    val (initial, header) = right(begin(config))
    val (firstNs, first) = entries.head
    val (one, _) = right(initial.push(firstNs, first))
    assertEquals(one.push(firstNs, first), Left(Error.EntryOrder(firstNs, first.key, first.key)))
    assert(initial.push(firstNs, first.copy(key = Bytes.empty)).left.exists {
      case Error.Key(SclsKey.Error.Length(`firstNs`, 0)) => true
      case _                                             => false
    })
    val (lastNs, last) = entries.last
    val (later, _) = right(initial.push(lastNs, last))
    assertEquals(later.push(firstNs, first), Left(Error.NamespaceOrder(lastNs, firstNs)))
    val (end, out) = right(entries.tail.foldLeftM((one, Vector(header))) {
      case ((builder, emitted), (namespace, entry)) =>
        builder.push(namespace, entry).map((next, chunk) => next -> (emitted ++ chunk))
    })
    val verified = right(
      SclsValidation.validate(decoded(out ++ right(end.finish(manifest.slot, manifest.summary))))
    )
    assertEquals(verified.manifest.root, manifest.root)
  }

  test("empty exports declare all twelve namespace roots without manufacturing empty chunks") {
    val output = written(right(Config.fromInts(8192, 1)), Vector.empty, 0, summary)
    val result = right(SclsValidation.validate(decoded(output))).manifest
    assertEquals(output.size, 2)
    assertEquals(result.entries, BigInt(0))
    assertEquals(result.chunks, BigInt(0))
    assertEquals(result.namespaces.map(_.namespace), Namespace.values.toVector)
    assert(result.namespaces.forall(info => info.root == SclsCommitment.empty.root))
    assert(Config.fromInts(0, 1).isLeft)
    assertEquals(Config.fromInts(8192, 0), Left(Error.InvalidChunkEntries(0)))
    assert(begin(right(Config.fromInts(8, 1))).isLeft)
    val (tiny, _) = right(begin(right(Config.fromInts(9, 1))))
    assert(tiny.finish(0, summary).isLeft)
    val (valid, _) = right(begin(right(Config.fromInts(8192, 1))))
    assertEquals(valid.finish(-1, summary), Left(Error.Record(SclsRecord.Error.Word64Range(-1))))
  }

  test("many bounded chunks are produced and consumed incrementally on a small-stack thread") {
    onSmallStack {
      val config = right(Config.fromInts(4096, 17))
      val (initial, header) = right(begin(config))
      val start = right(SclsValidation.validator.feed(decoded(Vector(header)).head))
      val (builder, validator) = (0 until 10000).foldLeft((initial, start)) {
        case ((current, verifier), index) =>
          val (next, emitted) =
            right(current.push(Namespace.Blocks, Entry(key(index), Bytes.wrap(Array[Byte](1)))))
          val verified = right(decoded(emitted).foldLeftM(verifier)((v, frame) => v.feed(frame)))
          next -> verified
      }
      val end = right(decoded(right(builder.finish(0, summary))).foldLeftM(validator)(_.feed(_)))
      val result = right(end.finish).manifest
      assertEquals(result.entries, BigInt(10000))
      assertEquals(result.chunks, BigInt(589))
    }
  }
