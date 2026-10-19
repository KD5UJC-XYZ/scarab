// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, Record}

class SclsValidationSuite extends FunSuite:
  import SclsValidation.*
  private def right[E, A](result: Either[E, A]): A = result.fold(e => fail(e.toString), identity)
  private def bits[A](result: scodec.Attempt[A]): A = result match
    case scodec.Attempt.Successful(value) => value
    case scodec.Attempt.Failure(cause)    => fail(cause.message)
  private val limit = right(SclsFraming.Limit.fromInt(16 * 1024 * 1024 + 4096))
  private val reference =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls")))
  private val frames = right(SclsFraming.decode(reference, limit))
  private val records = frames.map(frame => right(SclsRecord.decode(frame)))
  private val chunks = records.collect { case chunk: Record.Chunk => chunk }
  private val manifest = records
    .collectFirst { case manifest: Record.Manifest => manifest }
    .getOrElse(fail("missing reference manifest"))
  private val zeroHash = SclsCommitment.Digest
    .fromBytes(Bytes.wrap(Array.fill[Byte](28)(0)))
    .getOrElse(fail("digest size"))
  private def encoded(record: Record): SclsFraming.Frame = right(SclsRecord.encode(record))
  private def ending(value: Record.Manifest): Vector[SclsFraming.Frame] =
    frames.init :+ encoded(value)

  private def chunk(entries: Vector[Entry], sequence: BigInt): Record.Chunk =
    val data = entries.map { entry =>
      val size = bits(scodec.codecs.uint32.encode((entry.key.size + entry.value.size).toLong))
      Bytes.wrap(size.toByteArray) ++ entry.key ++ entry.value
    }
    val bytes =
      Bytes.wrap(fs2.Chunk.concat(data.map(part => fs2.Chunk.array(part.toArray))).toArray)
    val hash = entries
      .foldLeft(SclsCommitment.emptyChunk)((state, entry) =>
        state.addEntry(Namespace.Blocks.bytes, entry.key, entry.value)
      )
      .digest
    Record.Chunk(sequence, Namespace.Blocks, bytes, entries.size.toLong, hash)

  test(
    "the actual Haskell container verifies counts, chunk digests, all namespace roots and global root"
  ) {
    assertEquals(right(validate(frames)).manifest, manifest)
    val end = right(
      frames.foldLeft[Either[Error, Validator]](Right(validator))((state, frame) =>
        state.flatMap(_.feed(frame))
      )
    )
    assertEquals(right(end.finish).manifest.slot, BigInt(4593))
  }

  test("only a header through a terminal manifest is a complete container") {
    assertEquals(validate(Vector.empty), Left(Error.MissingHeader))
    (1 until frames.size).foreach { count =>
      assertEquals(validate(frames.take(count)), Left(Error.MissingManifest))
    }
    assertEquals(
      validate(frames.tail),
      Left(Error.UnexpectedRecord(Expected.Header, SclsFraming.Kind.Chunk))
    )
    assertEquals(
      validate(Vector(frames.head, frames.head)),
      Left(Error.UnexpectedRecord(Expected.ChunkOrManifest, SclsFraming.Kind.Header))
    )
    assertEquals(
      validate(frames :+ frames.head),
      Left(Error.UnexpectedRecord(Expected.End, SclsFraming.Kind.Header))
    )
    assertEquals(
      validate(frames :+ frames.last),
      Left(Error.UnexpectedRecord(Expected.End, SclsFraming.Kind.Manifest))
    )
  }

  test(
    "chunk sequence, cross-namespace ordering, checksums and cross-chunk key order are enforced"
  ) {
    val first = chunks.head
    assertEquals(
      validate(frames.updated(1, encoded(first.copy(sequence = 2)))),
      Left(Error.Sequence(first.namespace, 1, 2))
    )
    val badHash = first.copy(hash = zeroHash)
    assert(validate(frames.updated(1, encoded(badHash))).left.exists {
      case Error.ChunkHash(Namespace.Blocks, _, found) => found == zeroHash
      case _                                           => false
    })
    assertEquals(
      validate(Vector(frames.head, encoded(chunks.last), encoded(first))),
      Left(Error.NamespaceOrder(Namespace.Utxo, Namespace.Blocks))
    )
    val second = first.copy(sequence = 2)
    assert(validate(Vector(frames.head, encoded(first), encoded(second))).left.exists {
      case Error.Record(SclsRecord.Error.EntryOrder(Namespace.Blocks, _, _)) => true
      case _                                                                 => false
    })
  }

  test("manifest declarations cannot hide missing data, roots or extra namespaces") {
    assertEquals(
      validate(ending(manifest.copy(entries = 17))),
      Left(Error.Count(CountField.Entries, 16, 17))
    )
    assertEquals(
      validate(ending(manifest.copy(chunks = 9))),
      Left(Error.Count(CountField.Chunks, 8, 9))
    )
    assertEquals(
      validate(ending(manifest.copy(previous = 123))),
      Left(Error.PreviousManifestOffset(123))
    )
    val first = manifest.namespaces.head
    val wrongCount =
      manifest.copy(namespaces = manifest.namespaces.updated(0, first.copy(entries = 3)))
    assertEquals(
      validate(ending(wrongCount)),
      Left(Error.Count(CountField.NamespaceEntries(Namespace.Blocks), 2, 3))
    )
    val wrongRoot =
      manifest.copy(namespaces = manifest.namespaces.updated(0, first.copy(root = zeroHash)))
    assert(validate(ending(wrongRoot)).left.exists {
      case Error.NamespaceRoot(Namespace.Blocks, _, found) => found == zeroHash
      case _                                               => false
    })
    assert(validate(ending(manifest.copy(root = zeroHash))).left.exists {
      case Error.GlobalRoot(_, found) => found == zeroHash
      case _                          => false
    })
    val missingEmpty =
      manifest.copy(namespaces = manifest.namespaces.filterNot(_.namespace == Namespace.DReps))
    assertEquals(
      validate(ending(missingEmpty)),
      Left(Error.NamespaceSet(Namespace.values.toVector, missingEmpty.namespaces.map(_.namespace)))
    )
  }

  test(
    "splitting a namespace across chunks preserves its Merkle root and verifies sequence and totals"
  ) {
    val entries = right(SclsRecord.entries(chunks.head))
    assertEquals(entries.size, 2)
    val split = Vector(chunk(entries.take(1), 1), chunk(entries.drop(1), 2))
    val info = manifest.namespaces.head.copy(chunks = 2)
    val next = manifest.copy(chunks = 9, namespaces = manifest.namespaces.updated(0, info))
    val frames = Vector(this.frames.head) ++ split.map(encoded) ++ this.frames
      .slice(2, this.frames.size - 1) :+ encoded(next)
    assertEquals(right(validate(frames)).manifest.root, manifest.root)
  }

  test("a rejected branch cannot mutate previously captured hash or validator state") {
    val prefix = right(validator.feed(frames.head))
    val after = right(prefix.feed(frames(1)))
    assert(prefix.feed(encoded(chunks.head.copy(hash = zeroHash))).isLeft)
    val complete = right(
      frames.tail.foldLeft[Either[Error, Validator]](Right(prefix))((state, frame) =>
        state.flatMap(_.feed(frame))
      )
    )
    assertEquals(right(complete.finish).manifest, manifest)
    assertEquals(after.finish, Left(Error.MissingManifest))
    val hash = SclsCommitment.emptyChunk
    val before = hash.digest
    val extended = hash.addEntry(Namespace.Blocks.bytes, Bytes.empty, Bytes.empty)
    assertEquals(hash.digest, before)
    assertNotEquals(extended.digest, before)
  }
