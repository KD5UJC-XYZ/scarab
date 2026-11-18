// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import org.rocksdb.{ColumnFamilyDescriptor, ColumnFamilyHandle, DBOptions, Options, RocksDB}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosFetch, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}

/** The Leios store against the reference's LeiosDB behaviour (ouroboros-consensus 2416493b
  * `Storage/LeiosDB/Impl/SQLite/Insert.hs`, `Read.hs`, `Maintenance.hs`): completion, the
  * transactions a write drops, local fills, collection, promotion, reopening, and the format
  * record.
  */
class LeiosStoreSuite extends CatsEffectSuite:
  import LeiosStoreSuite.*

  private def store(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    allowing(RocksLeiosStore.resource[IO](dir))

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  dirs.test("a point is written once, and points scan by slot") { dir =>
    store(dir / "leios").use { s =>
      ok(for
        first <- s.writeEbPoint(point(20, 1), word32(100))
        again <- s.writeEbPoint(point(20, 1), word32(999))
        _ <- s.writeEbPoint(point(10, 2), word32(50))
        points <- s.scanEbPoints
      yield
        assertEquals(first, Vector.empty)
        assertEquals(again, Vector.empty)
        assertEquals(points, Vector(point(10, 2), point(20, 1))))
    }
  }

  test("a body's encoding and bounds are the reference's bytes") {
    // one transaction of 300 bytes whose hash is 32 bytes of 0x11: `{ h'11..11' => 300 }`
    val listed = LeiosTxHash.fromBytes(Bytes(Vector.fill(32)(0x11.toByte)))
    val one = LeiosEb(NonEmptyVector.of(listed -> word32(300)))
    assertEquals(one.encoded.hex, "a15820" + "11" * 32 + "19012c")
    assertEquals(LeiosEb.decode(one.encoded), Right(one))
    assertEquals(one.txBounds.map(_.hex), Some("00000000" + "0000012c"))
  }

  test("a body's encoding is the map MsgLeiosBlock carries, at every head width") {
    // 300 entries: a two-byte map head; sizes of every integer width
    val sizes = Vector(1L, 30L, 300L, 70000L)
    val wide = LeiosEb(
      NonEmptyVector.fromVectorUnsafe(
        Vector.tabulate(300)(i => LeiosEb.hashTx(tx(i)) -> word32(sizes(i % 4)))
      )
    )
    val small = LeiosEb(NonEmptyVector.fromVectorUnsafe(Vector.tabulate(30)(i => entry(tx(i)))))
    Vector(wide, small, body(1)).foreach { b =>
      assertEquals(
        LeiosFetch.encode(LeiosFetch.Message.Block(b.transactions.toVector)),
        Right(Bytes(Vector(0x82.toByte, 0x02.toByte)) ++ b.encoded)
      )
      assertEquals(LeiosEb.decode(b.encoded), Right(b))
    }
  }

  dirs.test("a body before its point is refused") { dir =>
    store(dir / "leios").use { s =>
      attempt(s.writeEbBody(point(5, 1), body(3), Vector.empty)).map(r =>
        assertEquals(r, Left(LeiosStoreError.PointNotWritten(point(5, 1))))
      )
    }
  }

  dirs.test("a hash that is not 32 bytes is refused") { dir =>
    store(dir / "leios").use { s =>
      attempt(
        s.writeEbPoint(EbPoint(slot(1), EbHash.fromBytes(Bytes(Vector[Byte](1, 2)))), word32(1))
      )
        .map(r => assertEquals(r, Left(LeiosStoreError.HashLength(2))))
    }
  }

  dirs.test("the write that stores the last transaction completes every point of the hash") { dir =>
    val eb = body(3)
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(10, 1), word32(1))
        _ <- s.writeEbPoint(point(12, 1), word32(1))
        written <- s.writeEbBody(point(10, 1), eb, Vector.empty)
        stored <- s.lookupEbBody(hash(1))
        before <- s.lookupEbClosure(hash(1))
        partly <- s.writeTxs(point(10, 1), Vector(0 -> tx(0), 2 -> tx(2)))
        middle <- s.batchRetrieveTxs(hash(1), Vector(2, 1, 7, -1, 2))
        rest <- s.writeTxs(point(12, 1), Vector(1 -> tx(1)))
        after <- s.lookupEbClosure(hash(1))
        repeated <- s.writeTxs(point(10, 1), Vector(1 -> tx(1)))
        complete <- s.scanCompleteEbClosuresNotOlderThanSlot(slot(11))
      yield
        assertEquals(written, BodyWritten(Vector.empty, Vector.empty))
        assertEquals(stored, Some(eb.encoded))
        assertEquals(before, None)
        assertEquals(partly, Vector.empty)
        assertEquals(middle, Vector(1 -> zeroes(tx(1).size), 2 -> tx(2)))
        assertEquals(rest, Vector(point(10, 1), point(12, 1)))
        assertEquals(after, Some(Vector(0, 1, 2).map(i => LeiosEb.hashTx(tx(i)) -> tx(i))))
        assertEquals(repeated, Vector.empty)
        assertEquals(complete, Vector(point(12, 1))))
    }
  }

  dirs.test("a write drops what is not in the body, of the wrong size, zero-led, or repeated") {
    dir =>
      val eb = body(2)
      store(dir / "leios").use { s =>
        ok(for
          _ <- s.writeEbPoint(point(1, 1), word32(1))
          none <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
          _ <- s.writeEbBody(point(1, 1), eb, Vector.empty)
          dropped <- s.writeTxs(
            point(1, 1),
            Vector(
              2 -> tx(2),
              -1 -> tx(0),
              0 -> tx(0).take(3),
              0 -> zeroLed(tx(0)),
              1 -> tx(1),
              1 -> tx(0),
              1 -> sameSize(tx(1))
            )
          )
          held <- s.batchRetrieveTxs(hash(1), Vector(0, 1))
        yield
          assertEquals(none, Vector.empty)
          assertEquals(dropped, Vector.empty)
          assertEquals(held, Vector(0 -> zeroes(tx(0).size), 1 -> tx(1))))
      }
  }

  dirs.test(
    "a novel point of a complete hash is complete on arrival; a second body changes nothing"
  ) { dir =>
    val eb = body(1)
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(1, 1), word32(1))
        _ <- s.writeEbBody(point(1, 1), eb, Vector.empty)
        first <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
        novel <- s.writeEbPoint(point(9, 1), word32(1))
        again <- s.writeEbBody(point(9, 1), eb, Vector.empty)
      yield
        assertEquals(first, Vector(point(1, 1)))
        assertEquals(novel, Vector(point(9, 1)))
        assertEquals(again, BodyWritten(Vector.empty, Vector.empty)))
    }
  }

  dirs.test("local fills copy a transaction another endorser block holds, if its hash matches") {
    dir =>
      val source = body(2)
      // the target lists tx 1 of the source at its offset 0, then a transaction of its own
      val target = LeiosEb(NonEmptyVector.of(entry(tx(1)), entry(tx(7))))
      store(dir / "leios").use { s =>
        ok(for
          _ <- s.writeEbPoint(point(1, 1), word32(1))
          _ <- s.writeEbBody(point(1, 1), source, Vector.empty)
          _ <- s.writeTxs(point(1, 1), Vector(0 -> tx(0), 1 -> tx(1)))
          _ <- s.writeEbPoint(point(2, 2), word32(1))
          // tx 0 of the source is not the one listed at 1, and the body has no offset 5
          written <- s.writeEbBody(
            point(2, 2),
            target,
            Vector(
              LocalFill(1, TxLocation(hash(1), 0)),
              LocalFill(0, TxLocation(hash(1), 1)),
              LocalFill(5, TxLocation(hash(1), 1))
            )
          )
          held <- s.batchRetrieveTxs(hash(2), Vector(0, 1))
          completed <- s.writeTxs(point(2, 2), Vector(1 -> tx(7)))
        yield
          assertEquals(written, BodyWritten(Vector.empty, Vector(0)))
          assertEquals(held, Vector(0 -> tx(1), 1 -> zeroes(tx(7).size)))
          assertEquals(completed, Vector(point(2, 2))))
      }
  }

  dirs.test("fills that fill the whole body complete it with the body") { dir =>
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(1, 1), word32(1))
        _ <- s.writeEbBody(point(1, 1), body(1), Vector.empty)
        _ <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
        _ <- s.writeEbPoint(point(3, 2), word32(1))
        _ <- s.writeEbPoint(point(4, 2), word32(1))
        written <- s.writeEbBody(point(3, 2), body(1), Vector(LocalFill(0, TxLocation(hash(1), 0))))
      yield assertEquals(written, BodyWritten(Vector(point(3, 2), point(4, 2)), Vector(0))))
    }
  }

  dirs.test(
    "collection removes old volatile points, then what no point names; immutable ones stay"
  ) { dir =>
    store(dir / "leios").use { s =>
      ok(for
        // hash 1: only an old point; hash 2: an old point and one at the frontier; hash 3: promoted
        _ <- Vector(point(1, 1), point(2, 2), point(100, 2), point(3, 3)).traverse_(p =>
          s.writeEbPoint(p, word32(1))
        )
        _ <- Vector(point(1, 1), point(2, 2), point(3, 3)).traverse_(p =>
          s.writeEbBody(p, body(1), Vector.empty) *> s.writeTxs(p, Vector(0 -> tx(0)))
        )
        _ <- s.promoteToImmutable(Vector(point(3, 3)))
        _ <- s.garbageCollect(slot(100))
        points <- s.scanEbPoints
        bodies <- Vector(1, 2, 3).traverse(i => s.lookupEbBody(hash(i)).map(_.isDefined))
        txs <- s.batchRetrieveTxs(hash(1), Vector(0))
        complete <- s.scanCompleteEbClosuresNotOlderThanSlot(slot(0))
        _ <- s.garbageCollect(slot(101))
        later <- s.scanEbPoints
      yield
        assertEquals(points, Vector(point(2, 2), point(3, 3), point(100, 2)))
        assertEquals(bodies, Vector(false, true, true))
        assertEquals(txs, Vector.empty)
        // the immutable point is not one the reference's volatile read lists
        assertEquals(complete, Vector(point(2, 2), point(100, 2)))
        assertEquals(later, Vector(point(3, 3))))
    }
  }

  dirs.test(
    "promotion pins without transactions and marks copied with them; pinned points survive"
  ) { dir =>
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(1, 1), word32(1))
        _ <- s.writeEbBody(point(1, 1), body(2), Vector.empty)
        _ <- s.promoteToImmutable(Vector(point(1, 1)))
        pinned <- s.stored(hash(1))
        _ <- s.garbageCollect(slot(50))
        kept <- s.scanEbPoints
        _ <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
        copied <- s.stored(hash(1))
      yield
        assertEquals(pinned.points.map(_._3), Vector(EbStatus.Certified))
        assertEquals(kept, Vector(point(1, 1)))
        assertEquals(copied.points.map(_._3), Vector(EbStatus.Immutable)))
    }
  }

  dirs.test("a reopened store keeps its points, bodies and completion") { dir =>
    val eb = body(2)
    for
      _ <- store(dir / "leios").use(s =>
        ok(
          s.writeEbPoint(point(1, 1), word32(1)) *> s.writeEbPoint(point(2, 1), word32(1)) *>
            s.writeEbBody(point(1, 1), eb, Vector.empty) *> s.writeTxs(
              point(1, 1),
              Vector(0 -> tx(0))
            )
        )
      )
      completed <- store(dir / "leios").use(s => ok(s.writeTxs(point(2, 1), Vector(1 -> tx(1)))))
    yield assertEquals(completed, Vector(point(1, 1), point(2, 1)))
  }

  dirs.test("a released store refuses calls") { dir =>
    store(dir / "leios").allocated.flatMap { (s, release) =>
      release *> attempt(s.scanEbPoints).map(r => assertEquals(r, Left(LeiosStoreError.Closed)))
    }
  }

  dirs.test("a store of another version, or a foreign database, is not opened") { dir =>
    val other = dir / "other"
    val foreign = dir / "foreign"
    for
      _ <- store(other).use_
      _ <- IO.blocking(
        rewriteFormat(other, Bytes.wrap("SCRBLEIO".getBytes ++ Array[Byte](0, 0, 0, 2)))
      )
      refused <- attemptOpen(other)
      _ <- IO.blocking(defaultFamilyOnly(foreign))
      alien <- attemptOpen(foreign)
    yield
      assertEquals(
        refused,
        Left(
          LeiosStoreError.UnknownFormat(Bytes.wrap("SCRBLEIO".getBytes ++ Array[Byte](0, 0, 0, 2)))
        )
      )
      assert(alien.left.exists {
        case LeiosStoreError.ForeignDatabase(_) => true
        case _                                  => false
      })
  }

  private def attemptOpen(dir: Path): IO[Either[LeiosStoreError, Unit]] =
    attempt(RocksLeiosStore.resource[IO](dir).use_)

object LeiosStoreSuite:
  def slot(n: Long): Word64 =
    Word64.from(BigInt(n)).fold(e => throw new AssertionError(e.toString), identity)
  def word32(n: Long): Word32 =
    Word32.from(BigInt(n)).fold(e => throw new AssertionError(e.toString), identity)

  def hash(i: Int): EbHash = EbHash.fromBytes(Bytes(Vector.fill(32)((i + 1).toByte)))
  def point(s: Long, h: Int): EbPoint = EbPoint(slot(s), hash(h))

  /** Transaction `i`: CBOR-like bytes that start with a non-zero byte, of a size that varies. */
  def tx(i: Int): Bytes = Bytes(0x84.toByte +: Vector.tabulate(10 + i)(j => (i * 7 + j + 1).toByte))
  def zeroLed(t: Bytes): Bytes = Bytes(0.toByte +: t.value.drop(1))
  def sameSize(t: Bytes): Bytes = Bytes(t.value.map(b => (b ^ 0x40).toByte))
  def zeroes(n: Int): Bytes = Bytes(Vector.fill(n)(0.toByte))

  def entry(t: Bytes): (LeiosTxHash, Word32) = LeiosEb.hashTx(t) -> word32(t.size.toLong)

  /** A body of transactions `0` to `n - 1`. */
  def body(n: Int): LeiosEb =
    LeiosEb(NonEmptyVector.fromVectorUnsafe(Vector.tabulate(n)(i => entry(tx(i)))))

  def attempt[A](body: Handle[IO, LeiosStoreError] ?=> IO[A]): IO[Either[LeiosStoreError, A]] =
    Handle
      .allow[LeiosStoreError](body.map(_.asRight[LeiosStoreError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  def ok[A](body: Handle[IO, LeiosStoreError] ?=> IO[A]): IO[A] =
    attempt(body).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.toString)), IO.pure))

  def allowing[A](body: Handle[IO, LeiosStoreError] ?=> Resource[IO, A]): Resource[IO, A] =
    Resource.make(ok(body.allocated))(_._2).map(_._1)

  /** Replace the store's format record, as a store of another version would hold it. */
  def rewriteFormat(dir: Path, record: Bytes): Unit =
    val names =
      scala.util.Using.resource(new Options())(o => RocksDB.listColumnFamilies(o, dir.toString))
    scala.util.Using.resource(new DBOptions()) { options =>
      val handles = new java.util.ArrayList[ColumnFamilyHandle]()
      val db = RocksDB.open(
        options,
        dir.toString,
        names.asScala.map(n => new ColumnFamilyDescriptor(n)).asJava,
        handles
      )
      val meta = handles.asScala
        .find(h => new String(h.getName) == "meta")
        .getOrElse(throw new AssertionError("meta"))
      db.put(meta, "format".getBytes, record.toArray)
      handles.asScala.foreach(_.close())
      db.close()
    }

  /** A RocksDB database of the default column family only. */
  def defaultFamilyOnly(dir: Path): Unit =
    scala.util.Using.resource(new Options().setCreateIfMissing(true)) { o =>
      val db = RocksDB.open(o, dir.toString)
      db.put("k".getBytes, "v".getBytes)
      db.close()
    }
