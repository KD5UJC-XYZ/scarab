// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, RocksUtxoStore, StorageTestKit, UtxoStore}

class SnapshotTableStreamSuite extends CatsEffectSuite:
  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(30, "min")
  import SnapshotTables.*
  import SnapshotTableStream.*

  private def right[A](e: Either[Error, A]): A = e.fold(e => fail(e.toString), identity)
  private val bytes =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/tables")))
  private val original = right(decode(bytes))._1
  private def input(b: Bytes, size: Int = 1): Stream[IO, Byte] =
    Stream.emits(b.value).covary[IO].chunkN(size).unchunks

  private def attempt[A](body: Handle[IO, Error] ?=> IO[A]): IO[Either[Error, A]] =
    Handle.allow[Error](body.map(_.asRight[Error])).rescue(e => IO.pure(e.asLeft[A]))

  private def encoded(entries: Vector[Entry], count: Long)(using
      Handle[IO, Error]
  ): IO[(Bytes, Summary)] =
    for
      out <- Ref.of[IO, Bytes](Bytes.empty)
      summary <- write[IO](count, Stream.emits(entries).covary[IO])(b => out.update(_ ++ b))
      bytes <- out.get
    yield (bytes, summary)

  test("streaming reader/writer preserves reference bytes and emits one final checksum") {
    attempt {
      for
        read <- fold(input(bytes), 0L)((n, _) => IO.pure(n + 1))
        result <- encoded(original, original.size.toLong)
        events <- SnapshotTableStream.events(input(bytes, 17)).compile.toVector
      yield
        assertEquals(result._1, bytes)
        assertEquals(result._2.crc.value, read._2.crc.value)
        assertEquals(
          events.collect { case Event.Complete(s) => s.entries },
          Vector(original.size.toLong)
        )
    }.map(result => assert(result.isRight, result.toString))
  }

  test("writer framing changes at 23 and rejects wrong counts and nonascending keys") {
    val entries = (0 until 24).toVector.map(n =>
      Entry(Bytes.wrap(Array.fill[Byte](33)(0) :+ n.toByte), original.head.value)
    )
    attempt {
      for
        short <- encoded(entries.take(23), 23)
        long <- encoded(entries, 24)
      yield
        assertEquals(short._1.take(2).value, Vector(0x81.toByte, 0xb7.toByte))
        assertEquals(long._1.take(2).value, Vector(0x81.toByte, 0xbf.toByte))
        assertEquals(long._1.value.last, 0xff.toByte)
        assertEquals(right(decode(long._1))._1, entries)
    }.flatMap { good =>
      assert(good.isRight)
      Vector(
        (original, 0L),
        (original, 99L),
        (original.reverse, original.size.toLong),
        (original.take(1) ++ original.take(1), 2L)
      ).traverse_ { (es, count) =>
        attempt(encoded(es, count)).map(r => assert(r.isLeft, r.toString))
      }
    }
  }

  test("semantic sink accepts unsorted unique maps and detects nonadjacent Conway duplicates") {
    def run(entries: Vector[Entry]): IO[Either[Error, Map[TxIn, TxOut]]] = attempt {
      for
        values <- Ref.of[IO, Map[TxIn, TxOut]](Map.empty)
        sink = new SemanticSink[IO, Conway]:
          def insert(key: TxIn, value: TxOut): IO[Unit] =
            values
              .modify { m =>
                if m.contains(key) then (m, false) else (m.updated(key, value), true)
              }
              .flatMap(fresh => if fresh then IO.unit else Error.DuplicateKey(key).raise[IO, Unit])
        raw = entries.foldLeft(Bytes.wrap(Array(0x81.toByte, 0xbf.toByte)))((acc, e) =>
          acc ++ right(encodeEntry(e))
        ) ++ Bytes.wrap(Array(0xff.toByte))
        _ <- readInto[IO, Conway](input(raw), sink)
        all <- values.get
      yield all
    }
    for
      good <- run(original.reverse)
      duplicate <- run(original ++ original.take(1))
    yield
      assertEquals(good.map(_.size), Right(original.size))
      assert(duplicate.left.exists(_.isInstanceOf[Error.DuplicateKey]), duplicate.toString)
  }

  test("truncated and trailing inputs never produce a completed table") {
    Vector(bytes.drop(1), bytes.take(bytes.size - 1), bytes ++ Bytes.wrap(Array(0))).traverse_ {
      malformed =>
        attempt(SnapshotTableStream.events(input(malformed)).compile.toVector).map(r =>
          assert(r.isLeft, r.toString)
        )
    }
  }

  test("memory and RocksDB exports stream in Haskell key order and convert only at the boundary") {
    StorageTestKit.ok {
      StorageTestKit.tempDir.use { dir =>
        RocksUtxoStore.resource[IO](dir / "utxo", 8L * 1024 * 1024).use { rocks =>
          for
            memory <- MemoryUtxoStore[IO]
            outs = original.map(e =>
              val key = UtxoStore.Coding.decodeKey(e.key.toArray)
              val value = TxOutMemPack.decode[Conway](e.value).fold(e => fail(e.toString), identity)
              key -> value
            )
            _ <- Vector[UtxoStore[IO]](memory, rocks).traverse_ { store =>
              for
                _ <- store.write(outs.reverse.map((in, out) => in -> Some(out)), 7L)
                ordered <- store.entries.compile.toVector
                exported <- attempt {
                  for
                    bytes <- Ref.of[IO, Bytes](Bytes.empty)
                    summary <- writeStore[IO, Conway](store)(b => bytes.update(_ ++ b))
                    raw <- bytes.get
                  yield (raw, summary)
                }
              yield
                assertEquals(ordered.map(_._1), outs.map(_._1))
                val (raw, summary) = exported.fold(e => fail(e.toString), identity)
                assertEquals(summary.entries, outs.size.toLong)
                val decoded = right(decode(raw))._1.map(e =>
                  UtxoStore.Coding.decodeKey(e.key.toArray) -> TxOutMemPack
                    .decode[Conway](e.value)
                    .fold(e => fail(e.toString), identity)
                )
                assertEquals(decoded, outs)
            }
          yield ()
        }
      }
    }
  }

  test("a full preprod table streams through MemPack conversion and writes byte-identical bytes") {
    val source = sys.env.get("SCARAB_MEMPACK_TABLES")
    assume(source.nonEmpty, "SCARAB_MEMPACK_TABLES names the pinned preprod tables file")
    source.traverse_ { name =>
      val path = Path.of(name).toAbsolutePath
      def sourceBytes = fs2.io.file.Files[IO].readAll(fs2.io.file.Path.fromNioPath(path))
      StorageTestKit.tempDir.use { dir =>
        val output = dir.resolve("tables").toNioPath
        attempt {
          for
            begin <- IO.monotonic
            read <- fold(sourceBytes, 0L)((n, _) => IO.pure(n + 1))
            result <- cats.effect.Resource
              .fromAutoCloseable(
                IO.blocking(
                  new java.io.BufferedOutputStream(Files.newOutputStream(output), 1 << 20)
                )
              )
              .use { stream =>
                val converted = entries(sourceBytes).evalMap { entry =>
                  val packed =
                    TxOutMemPack.decode[Conway](entry.value).flatMap(TxOutMemPack.encode[Conway])
                  packed.fold(
                    e => Error.InvalidValue(entry.key, e).raise[IO, Entry],
                    b => IO.pure(entry.copy(value = b))
                  )
                }
                write[IO](read._2.entries, converted)(b => IO.blocking(b.withRange(stream.write)))
              }
            mismatch <- IO.blocking(Files.mismatch(path, output))
            state <- IO.blocking(Bytes.wrap(Files.readAllBytes(path.getParent.resolve("state"))))
            meta <- IO.blocking(
              SnapshotMeta
                .parse(Files.readString(path.getParent.resolve("meta")))
                .fold(e => fail(e.toString), identity)
            )
            end <- IO.monotonic
          yield
            assertEquals(read._2.entries, 4526058L)
            assertEquals(read._2.crc.value, 1172950672L)
            assertEquals(result.crc.value, read._2.crc.value)
            assertEquals(mismatch, -1L)
            assertEquals(SnapshotMeta.verify(meta, SnapshotCrc.of(state), result.crc), Right(()))
            println(
              s"PREPROD tables entries=${result.entries} crc=${result.crc.value} byteIdentical=true elapsed=${end - begin}"
            )
        }.map(result => assert(result.isRight, result.toString))
      }
    }
  }
