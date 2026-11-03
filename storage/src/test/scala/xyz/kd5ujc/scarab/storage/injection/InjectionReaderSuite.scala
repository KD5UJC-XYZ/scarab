// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.injection

import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.GenesisState.InjectionData
import xyz.kd5ujc.scarab.ledger.state.Hash32

class InjectionReaderSuite extends CatsEffectSuite:
  private enum FsError extends DomainError:
    case Open, Read, Close
  private enum Control extends DomainError:
    case Key, Value, Fold
  private enum Event:
    case Open(segments: Vector[String])
    case Read(requested: Int, returned: Int)
    case Close
  private type Content = InjectionReader.Content[Control, Control, Control]
  private type Error = InjectionFiles.Failure[FsError, Content]
  private type Pairs = Vector[(String, Json)]
  private val path = Vector("input.json")
  private def bytes(text: String): Bytes =
    Bytes.fromArray(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private def hash(data: Bytes): Hash32 =
    Hash32.fromBytes(Blake2b.hash256.hash(data)).fold(e => fail(e.toString), identity)
  private val wrongHash = hash(bytes("wrong"))

  private final case class Memory(
      files: InjectionFiles[IO, FsError, Unit],
      events: Ref[IO, Vector[Event]]
  )
  private def memory(chunks: List[Bytes], failAt: Option[FsError] = None): IO[Memory] = for
    remaining <- Ref.of[IO, List[Bytes]](chunks)
    events <- Ref.of[IO, Vector[Event]](Vector.empty)
  yield
    val operations = new InjectionFiles.Operations[IO, FsError, Unit]:
      def open(segments: Vector[String])(using errors: Raise[IO, FsError]): IO[Unit] =
        events.update(
          _ :+ Event.Open(segments)
        ) *> (if failAt.contains(FsError.Open) then errors.raise(FsError.Open) else IO.unit)
      def readSome(handle: Unit, requested: Int)(using errors: Raise[IO, FsError]): IO[Bytes] =
        remaining
          .modify {
            case head :: tail => (tail, head)
            case Nil          => (Nil, Bytes.empty)
          }
          .flatMap { chunk =>
            events.update(_ :+ Event.Read(requested, chunk.size)) *>
              (if failAt.contains(FsError.Read) then errors.raise(FsError.Read) else IO.pure(chunk))
          }
      def close(handle: Unit)(using errors: Raise[IO, FsError]): IO[Unit] =
        events.update(
          _ :+ Event.Close
        ) *> (if failAt.contains(FsError.Close) then errors.raise(FsError.Close) else IO.unit)
    Memory(new InjectionFiles(operations), events)

  private def run(mem: Memory, source: InjectionData[(String, Json)]): IO[Either[Error, Pairs]] =
    Handle
      .allow[Error] {
        InjectionReader
          .fold(mem.files, source, Vector.empty[(String, Json)])(
            text => Either.cond(text != "bad", text, Control.Key),
            json => Either.cond(!json.isNull, json, Control.Value),
            (acc, pair) => Either.cond(pair._1 != "stop", acc :+ pair, Control.Fold)
          )
          .map(_.asRight[Error])
      }
      .rescue(error => IO.pure(Left(error)))

  test("no source and embedded entries never allocate, read, hash or close a file") {
    val entries = Vector("x" -> Json.fromInt(1), "x" -> Json.fromInt(2))
    for
      mem <- memory(Nil, Some(FsError.Open))
      none <- run(mem, InjectionData.NoInjection)
      embedded <- run(mem, InjectionData.Embedded(entries))
      events <- mem.events.get
    yield
      assertEquals(none, Right(Vector.empty))
      assertEquals(embedded, Right(entries))
      assertEquals(events, Vector.empty)
  }

  test("hash covers the entire last demanded chunk, but never an undemanded tail") {
    val first = bytes("{\"x\":1,\"x\":2} ignored remainder")
    val tail = bytes("unrequested")
    for
      mem <- memory(List(first, tail))
      result <- run(mem, InjectionData.FromFile(path, hash(first)))
      events <- mem.events.get
      other <- memory(List(first, tail))
      fullFileHash <- run(other, InjectionData.FromFile(path, hash(first ++ tail)))
    yield
      assertEquals(result, Right(Vector("x" -> Json.fromInt(1), "x" -> Json.fromInt(2))))
      assertEquals(events, Vector(Event.Open(path), Event.Read(65536, first.size), Event.Close))
      assertEquals(
        fullFileHash,
        Left(
          InjectionFiles.Failure.Body(
            InjectionReader.Content.HashMismatch(path, hash(first ++ tail), hash(first).bytes)
          )
        )
      )
  }

  test("short reads preserve bytes, encounter order and incremental digest") {
    val input = bytes("{\"x\":1,\"y\":2}")
    val chunks = input.value.grouped(3).map(v => Bytes(v.toVector)).toList
    for
      mem <- memory(chunks)
      result <- run(mem, InjectionData.FromFile(path, hash(input)))
      events <- mem.events.get
    yield
      assertEquals(result, Right(Vector("x" -> Json.fromInt(1), "y" -> Json.fromInt(2))))
      assertEquals(
        events,
        Vector(Event.Open(path)) ++ chunks.map(b => Event.Read(65536, b.size)) :+ Event.Close
      )
  }

  test("entry syntax precedes key decoding; key precedes value and later-entry syntax") {
    val samples = Vector(
      "{\"bad\":null}" -> InjectionFiles.Failure.Body(
        InjectionReader.Content.Key(path, "bad", Control.Key)
      ),
      "{\"ok\":null}" -> InjectionFiles.Failure.Body(
        InjectionReader.Content.Value(path, "ok", Control.Value)
      ),
      "{\"bad\":1,\"later\":?}" -> InjectionFiles.Failure.Body(
        InjectionReader.Content.Key(path, "bad", Control.Key)
      ),
      "{\"stop\":1,\"later\":?}" -> InjectionFiles.Failure.Body(
        InjectionReader.Content.Fold(Control.Fold)
      )
    )
    samples.traverse_ { (text, expected) =>
      memory(List(bytes(text)))
        .flatMap(mem => run(mem, InjectionData.FromFile(path, wrongHash)))
        .map(result => assertEquals(result, Left(expected)))
    } *> memory(List(bytes("{\"bad\":?}"))).flatMap { mem =>
      run(mem, InjectionData.FromFile(path, wrongHash)).map {
        case Left(InjectionFiles.Failure.Body(InjectionReader.Content.Syntax(`path`, _))) => ()
        case other => fail(s"expected completed-value syntax failure, got $other")
      }
    }
  }

  test("native bracket's four synchronous body/close outcomes retain close precedence") {
    (for bodyFails <- Vector(false, true); closeFails <- Vector(false, true)
    yield (bodyFails, closeFails)).traverse_ { (bodyFails, closeFails) =>
      for
        mem <- memory(Nil, Option.when(closeFails)(FsError.Close))
        result <- Handle
          .allow[Error] {
            mem.files
              .use[Content, Int](path) { _ =>
                if bodyFails then
                  summon[Raise[IO, Error]].raise(
                    InjectionFiles.Failure.Body(InjectionReader.Content.Fold(Control.Fold))
                  )
                else IO.pure(7)
              }
              .map(_.asRight[Error])
          }
          .rescue(error => IO.pure(Left(error)))
        events <- mem.events.get
      yield
        val expected: Either[Error, Int] =
          if closeFails then Left(InjectionFiles.Failure.File(FsError.Close))
          else if bodyFails then
            Left(InjectionFiles.Failure.Body(InjectionReader.Content.Fold(Control.Fold)))
          else Right(7)
        assertEquals(result, expected)
        assertEquals(events, Vector(Event.Open(path), Event.Close))
    }
  }

  test("a foreign body defect is retained unless a close failure replaces it") {
    val defect = new IllegalStateException("controlled body defect")
    Vector(false, true).traverse_ { closeFails =>
      for
        mem <- memory(Nil, Option.when(closeFails)(FsError.Close))
        result <- Handle
          .allow[Error] {
            mem.files.use[Content, Unit](path)(_ => IO.raiseError(defect)).map(_.asRight[Error])
          }
          .rescue(error => IO.pure(Left(error)))
      yield
        val expected: Error =
          if closeFails then InjectionFiles.Failure.File(FsError.Close)
          else InjectionFiles.Failure.Defect(defect)
        assertEquals(result, Left(expected))
    }
  }

  test("open failure owns no handle; read failure is released before being raised") {
    Vector(FsError.Open, FsError.Read).traverse_ { failure =>
      for
        mem <- memory(List(bytes("{}")), Some(failure))
        result <- run(mem, InjectionData.FromFile(path, wrongHash))
        events <- mem.events.get
      yield
        assertEquals(result, Left(InjectionFiles.Failure.File(failure)))
        assertEquals(events.count(_ == Event.Close), if failure == FsError.Open then 0 else 1)
    }
  }
