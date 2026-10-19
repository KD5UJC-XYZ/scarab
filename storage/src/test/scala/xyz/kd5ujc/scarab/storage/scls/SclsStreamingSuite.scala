// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace, Record}

class SclsStreamingSuite extends CatsEffectSuite:
  import SclsStreaming.*

  private def right[E, A](result: Either[E, A]): A = result.fold(e => fail(e.toString), identity)
  private val recordLimit = right(SclsFraming.Limit.fromInt(1024 * 1024))
  private val reference =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls")))
  private val frames = right(SclsFraming.decode(reference, recordLimit))
  private val records = frames.map(frame => right(SclsRecord.decode(frame)))
  private val manifest = records
    .collectFirst { case value: Record.Manifest => value }
    .getOrElse(fail("reference manifest"))
  private val entries = records.collect { case chunk: Record.Chunk =>
    right(SclsRecord.entries(chunk)).map(chunk.namespace -> _)
  }.flatten
  private val outputSummary = manifest.summary

  private def config(feedBytes: Int) =
    right(Config.fromInts(1024 * 1024, 1000, feedBytes))

  private def attempt[A](body: Handle[IO, Error] ?=> IO[A]): IO[Either[Error, A]] =
    Handle.allow[Error](body.map(_.asRight[Error])).rescue(e => IO.pure(e.asLeft[A]))

  private def concat(values: Vector[Bytes]): Bytes = values.foldLeft(Bytes.empty)(_ ++ _)

  private def encoded(values: Vector[(Namespace, Entry)]): Bytes =
    val config = right(SclsOutput.Config.fromInts(1024 * 1024, 1000))
    val (start, header) = right(SclsOutput.begin(config))
    val (builder, records) = right(
      values.foldLeftM((start, Vector(header))) { case ((current, output), (namespace, entry)) =>
        current.push(namespace, entry).map((next, emitted) => next -> (output ++ emitted))
      }
    )
    concat(records ++ right(builder.finish(manifest.slot, outputSummary)))

  private def changedManifest(change: Record.Manifest => Record.Manifest): Bytes =
    val end = frames.last
    val value = right(SclsRecord.decode(end)) match
      case value: Record.Manifest => value
      case _                      => fail("reference terminal manifest")
    val changed = right(SclsRecord.encode(change(value)))
    concat(frames.dropRight(1).map(frame => right(SclsFraming.encode(frame, recordLimit)))) ++
      right(SclsFraming.encode(changed, recordLimit))

  private def input(bytes: Bytes): Stream[IO, Byte] = Stream.emits(bytes.value).covary[IO]

  test("the sorted entry stream exports the real 6060-byte reference exactly") {
    for
      output <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      result <- attempt {
        write[IO](Stream.emits(entries).covary[IO], manifest.slot, outputSummary, config(127))(
          bytes => output.update(_ :+ bytes)
        )
      }
      written <- output.get.map(concat)
    yield
      assertEquals(result, Right(()))
      assertEquals(reference.size, 6060)
      assertEquals(written, reference)
  }

  test("import handles byte-at-a-time and coalesced sources with bounded feed fragments") {
    Vector(1, 113, 8192).traverse_ { feedBytes =>
      for
        staged <- Ref.of[IO, Vector[(Namespace, Entry)]](Vector.empty)
        result <- attempt {
          read[IO](input(reference), config(feedBytes)) { (namespace, entry) =>
            staged.update(_ :+ (namespace -> entry))
          }
        }
        values <- staged.get
      yield
        assertEquals(result.map(_.manifest), Right(manifest))
        assertEquals(values, entries)
    }
  }

  test("truncation and trailing data never yield a verified container") {
    Vector(reference.take(reference.size - 1), reference ++ Bytes(Vector(0.toByte))).traverse_ {
      bytes =>
        for
          staged <- Ref.of[IO, Vector[(Namespace, Entry)]](Vector.empty)
          published <- Ref.of[IO, Boolean](false)
          result <- attempt {
            read[IO](input(bytes), config(256)) { (namespace, entry) =>
              staged.update(_ :+ (namespace -> entry))
            }.flatTap(_ => published.set(true))
          }
          wasPublished <- published.get
        yield
          assert(result.isLeft, result.toString)
          assert(!wasPublished, "unverified input must not yield a publishable result")
    }
  }

  test("manifest commitment failure leaves staged entries unverified and unpublished") {
    val invalid = changedManifest(_.copy(entries = manifest.entries + 1))
    for
      staged <- Ref.of[IO, Vector[(Namespace, Entry)]](Vector.empty)
      published <- Ref.of[IO, Boolean](false)
      result <- attempt {
        read[IO](input(invalid), config(2048)) { (namespace, entry) =>
          staged.update(_ :+ (namespace -> entry))
        }.flatTap(_ => published.set(true))
      }
      values <- staged.get
      wasPublished <- published.get
    yield
      assert(result.left.exists(_.isInstanceOf[Error.Validation]), result.toString)
      assertEquals(values, entries)
      assert(!wasPublished)
  }

  test("namespace value schema is checked after the containing chunk commitment") {
    val invalid = encoded(
      entries.updated(
        0,
        entries.head._1 -> entries.head._2.copy(value = Bytes(Vector(0xff.toByte)))
      )
    )
    for
      staged <- Ref.of[IO, Vector[(Namespace, Entry)]](Vector.empty)
      published <- Ref.of[IO, Boolean](false)
      result <- attempt {
        read[IO](input(invalid), config(2048)) { (namespace, entry) =>
          staged.update(_ :+ (namespace -> entry))
        }.flatTap(_ => published.set(true))
      }
      wasPublished <- published.get
    yield
      assert(result.left.exists(_.isInstanceOf[Error.Value]), result.toString)
      assert(!wasPublished)
  }

  test("typed stage failure and cancellation release the caller's unpublished staging scope") {
    val stageError = StorageError.Missing("SCLS test stage")
    for
      released <- Ref.of[IO, Boolean](false)
      failure <- attempt {
        Resource.make(IO.unit)(_ => released.set(true)).use { _ =>
          read[IO](input(reference), config(128))((_, _) => Error.Stage(stageError).raise[IO, Unit])
        }
      }
      failedRelease <- released.get
    yield
      assertEquals(failure, Left(Error.Stage(stageError)))
      assert(failedRelease)
  }

  test("canceling a blocked staging callback releases its scope") {
    TestControl.executeEmbed {
      for
        started <- Deferred[IO, Unit]
        released <- Ref.of[IO, Boolean](false)
        fiber <- attempt {
          Resource.make(IO.unit)(_ => released.set(true)).use { _ =>
            read[IO](input(reference), config(128))((_, _) => started.complete(()).void *> IO.never)
          }
        }.start
        _ <- started.get
        _ <- fiber.cancel
        result <- fiber.join
        wasReleased <- released.get
      yield
        assert(result match
          case Outcome.Canceled() => true
          case _                  => false)
        assert(wasReleased)
    }
  }
