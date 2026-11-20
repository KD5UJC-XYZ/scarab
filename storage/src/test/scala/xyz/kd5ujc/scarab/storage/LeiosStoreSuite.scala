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
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))

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
    val one = eb(NonEmptyVector.of(listed -> word32(300)))
    assertEquals(one.encoded.hex, "a15820" + "11" * 32 + "19012c")
    assertEquals(LeiosEb.decode(one.encoded), Right(one))
    assertEquals(one.txBounds.map(_.hex), Some("00000000" + "0000012c"))
  }

  test("a body's encoding is the map MsgLeiosBlock carries, at every head width") {
    // 300 entries: a two-byte map head; sizes of every integer width
    val sizes = Vector(1L, 30L, 300L, 70000L)
    val wide = eb(
      NonEmptyVector.fromVectorUnsafe(
        Vector.tabulate(300)(i => LeiosEb.hashTx(tx(i)) -> word32(sizes(i % 4)))
      )
    )
    val small = eb(NonEmptyVector.fromVectorUnsafe(Vector.tabulate(30)(i => entry(tx(i)))))
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
      val target = eb(NonEmptyVector.of(entry(tx(1)), entry(tx(7))))
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
    "collection removes old volatile points, then what no point names; immutable ones are evicted"
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
        complete <- s.scanCompleteEbClosuresNotOlderThanSlot(slot(100))
        _ <- s.garbageCollect(slot(101))
        later <- s.scanEbPoints
      yield
        // hash 3's point is evicted: kept, with its body, but not in the reference's volatile file
        assertEquals(points, Vector(point(2, 2), point(100, 2)))
        assertEquals(bodies, Vector(false, true, true))
        assertEquals(txs, Vector.empty)
        assertEquals(complete, Vector(point(100, 2)))
        assertEquals(later, Vector.empty))
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
        // collected in the same session: the copied point is evicted, its body and
        // transactions stay
        _ <- s.garbageCollect(slot(50))
        collected <- s.scanEbPoints
        stored <- s.lookupEbBody(hash(1))
        held <- s.batchRetrieveTxs(hash(1), Vector(0))
      yield
        assertEquals(pinned.points.map(_._3), Vector(EbStatus.Certified))
        assertEquals(kept, Vector(point(1, 1)))
        assertEquals(copied.points.map(_._3), Vector(EbStatus.Immutable))
        assertEquals(collected, Vector.empty)
        assertEquals(stored, Some(body(2).encoded))
        assertEquals(held, Vector(0 -> tx(0))))
    }
  }

  dirs.test("a pinned point copied by a local fill is collected in the same session") { dir =>
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(1, 1), word32(1))
        _ <- s.writeEbBody(point(1, 1), body(1), Vector.empty)
        _ <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
        _ <- s.writeEbPoint(point(2, 2), word32(1))
        _ <- s.promoteToImmutable(Vector(point(2, 2)))
        written <- s.writeEbBody(point(2, 2), body(1), Vector(LocalFill(0, TxLocation(hash(1), 0))))
        _ <- s.garbageCollect(slot(10))
        collected <- s.scanEbPoints
        closure <- s.lookupEbClosure(hash(2))
      yield
        assertEquals(written.filled, Vector(0))
        // hash 1's volatile point and hash 2's copied one both go from the volatile view
        assertEquals(collected, Vector.empty)
        assertEquals(closure.map(_.map(_._2)), Some(Vector(tx(0)))))
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

  test("a body whose hash is not 32 bytes is refused before it reaches a store") {
    val short = LeiosTxHash.fromBytes(Bytes(Vector.fill(31)(1.toByte)))
    assertEquals(
      LeiosEb.from(NonEmptyVector.of(entry(tx(0)), short -> word32(5))),
      Left(LeiosEb.Error.BadEntry(1))
    )
  }

  dirs.test("a point whose body was refused takes the corrected body") { dir =>
    val short = LeiosTxHash.fromBytes(Bytes(Vector.fill(31)(1.toByte)))
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(1, 1), word32(1))
        refused = LeiosEb.from(NonEmptyVector.of(short -> word32(11)))
        written <- s.writeEbBody(point(1, 1), body(1), Vector.empty)
        completed <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
      yield
        assertEquals(refused, Left(LeiosEb.Error.BadEntry(0)))
        assertEquals(written, BodyWritten(Vector.empty, Vector.empty))
        assertEquals(completed, Vector(point(1, 1))))
    }
  }

  dirs.test(
    "a read sees the store at one moment: a write between two reads of a view is not seen"
  ) { dir =>
    store(dir / "leios").use { s =>
      ok(for
        _ <- s.writeEbPoint(point(1, 1), word32(1))
        _ <- s.writeEbBody(point(1, 1), body(3), Vector.empty)
        _ <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
        seen <- s.viewing { v =>
          for
            before <- s.batchRetrieveTxsIn(v, hash(1), Vector(0, 1, 2))
            // a write of two offsets, which completes the closure, commits between the reads
            completed <- s.writeTxs(point(1, 1), Vector(1 -> tx(1), 2 -> tx(2)))
            after <- s.batchRetrieveTxsIn(v, hash(1), Vector(0, 1, 2))
            closure <- s.lookupEbClosureIn(v, hash(1))
            complete <- s.scanCompleteIn(v, slot(0))
          yield (before, completed, after, closure, complete)
        }
        now <- s.batchRetrieveTxs(hash(1), Vector(0, 1, 2))
        closure <- s.lookupEbClosure(hash(1))
      yield
        val (before, completed, after, inView, complete) = seen
        val unwritten = Vector(0 -> tx(0), 1 -> zeroes(tx(1).size), 2 -> zeroes(tx(2).size))
        assertEquals(completed, Vector(point(1, 1)))
        assertEquals(before, unwritten)
        assertEquals(after, unwritten)
        assertEquals(inView, None)
        assertEquals(complete, Vector.empty)
        assertEquals(now, Vector(0, 1, 2).map(i => i -> tx(i)))
        assertEquals(closure.map(_.size), Some(3)))
    }
  }

  dirs.test(
    "the scan from the immutable tip lists a promoted point the collection kept, as the reference"
  ) { dir =>
    for
      _ <- store(dir / "leios").use { s =>
        ok(for
          // hash 1 announced on both sides of the frontier, hash 2 only before it; both complete
          _ <- Vector(point(10, 1), point(30, 1), point(5, 2)).traverse_(p =>
            s.writeEbPoint(p, word32(1))
          )
          _ <- Vector(point(10, 1), point(5, 2)).traverse_(p =>
            s.writeEbBody(p, body(1), Vector.empty) *> s.writeTxs(p, Vector(0 -> tx(0)))
          )
          _ <- s.promoteToImmutable(Vector(point(10, 1), point(5, 2)))
          _ <- s.garbageCollect(slot(20))
        yield ())
      }
      scanned <- store(dir / "leios").use(s =>
        ok(s.scanCompleteEbClosuresNotOlderThanSlot(slot(20)))
      )
    yield
      // the reference keeps both of hash 1's points in its volatile file (the one at 30 vetoes
      // their collection) and lists the one at 30
      assertEquals(scanned, Vector(point(30, 1)))
  }

  dirs.test(
    "a hash collected, announced again and reopened is not scanned until it is fetched again"
  ) { dir =>
    val h = 4
    for
      evictedReads <- store(dir / "leios").use { s =>
        ok(for
          _ <- s.writeEbPoint(point(5, h), word32(1))
          _ <- s.writeEbBody(point(5, h), body(2), Vector.empty)
          _ <- s.writeTxs(point(5, h), Vector(0 -> tx(0), 1 -> tx(1)))
          _ <- s.promoteToImmutable(Vector(point(5, h)))
          _ <- s.garbageCollect(slot(10))
          points <- s.scanEbPoints
          closure <- s.lookupEbClosure(hash(h))
          // announced again at the collection's slot: not complete, its body being elsewhere
          again <- s.writeEbPoint(point(10, h), word32(1))
        yield (points, closure.map(_.size), again))
      }
      scanned <- store(dir / "leios").use(s =>
        ok(s.scanCompleteEbClosuresNotOlderThanSlot(slot(10)))
      )
      fetched <- store(dir / "leios").use { s =>
        ok(for
          // the reference drops transactions it has no volatile body for
          early <- s.writeTxs(point(10, h), Vector(0 -> tx(0)))
          written <- s.writeEbBody(point(10, h), body(2), Vector.empty)
          emptied <- s.batchRetrieveTxs(hash(h), Vector(0, 1))
          closure <- s.lookupEbClosure(hash(h))
          completed <- s.writeTxs(point(10, h), Vector(0 -> tx(0), 1 -> tx(1)))
          scanned <- s.scanCompleteEbClosuresNotOlderThanSlot(slot(10))
          points <- s.scanEbPoints
        yield (early, written, emptied, closure, completed, scanned, points))
      }
    yield
      val (points, closure, again) = evictedReads
      assertEquals(points, Vector.empty)
      assertEquals(closure, Some(2))
      assertEquals(again, Vector.empty)
      assertEquals(scanned, Vector.empty)
      val (early, written, emptied, refetching, completed, rescanned, repointed) = fetched
      assertEquals(early, Vector.empty)
      assertEquals(written, BodyWritten(Vector.empty, Vector.empty))
      assertEquals(emptied, Vector(0 -> zeroes(tx(0).size), 1 -> zeroes(tx(1).size)))
      assertEquals(refetching, None)
      // the evicted point is immutable again once the body is written again, so that the store
      // keeps the endorser block; the reference holds it in its immutable file only, and lists and
      // completes the point at 10 alone
      assertEquals(completed, Vector(point(5, h), point(10, h)))
      assertEquals(rescanned, Vector(point(10, h)))
      assertEquals(repointed, Vector(point(5, h), point(10, h)))
  }

  dirs.test("an endorser block fetched again after its eviction survives a later collection") {
    dir =>
      val h = 4
      for
        _ <- store(dir / "leios").use { s =>
          ok(for
            _ <- s.writeEbPoint(point(5, h), word32(1))
            _ <- s.writeEbBody(point(5, h), body(2), Vector.empty)
            _ <- s.writeTxs(point(5, h), Vector(0 -> tx(0), 1 -> tx(1)))
            _ <- s.promoteToImmutable(Vector(point(5, h)))
            _ <- s.garbageCollect(slot(8))
            // announced again at a new slot, fetched again whole, then collected with no promotion
            _ <- s.writeEbPoint(point(10, h), word32(1))
            _ <- s.writeEbBody(point(10, h), body(2), Vector.empty)
            _ <- s.writeTxs(point(10, h), Vector(0 -> tx(0), 1 -> tx(1)))
            _ <- s.garbageCollect(slot(20))
          yield ())
        }
        found <- store(dir / "leios").use(s =>
          ok((s.lookupEbBody(hash(h)), s.lookupEbClosure(hash(h)), s.scanEbPoints).tupled)
        )
      yield
        val (stored, closure, points) = found
        assertEquals(stored, Some(body(2).encoded))
        assertEquals(closure.map(_.map(_._2)), Some(Vector(tx(0), tx(1))))
        // the volatile point went; the immutable one is evicted again
        assertEquals(points, Vector.empty)
  }

  dirs.test("a first open into a pre-created empty directory makes the store there") { dir =>
    val leios = dir / "leios"
    for
      _ <- IO.blocking(java.nio.file.Files.createDirectories(leios.toNioPath))
      points <- store(leios).use(s => ok(s.writeEbPoint(point(1, 1), word32(1)) *> s.scanEbPoints))
    yield assertEquals(points, Vector(point(1, 1)))
  }

  dirs.test("a first open whose rename cannot be made durable fails; the next syncs it first") {
    dir =>
      val leios = dir / "leios"
      val injected = new java.io.IOException("injected sync failure")
      for
        locks <- LeiosStoreLocks.make[IO]
        failed <- attempt(firstOpen(leios, locks, _ => IO.raiseError(injected)))
        published <- IO.blocking(java.nio.file.Files.exists(leios.toNioPath.resolve("CURRENT")))
        synced <- IO.ref(Vector.empty[java.nio.file.Path])
        again <- attempt(firstOpen(leios, locks, target => synced.update(_ :+ target)))
        syncs <- synced.get
      yield
        assertEquals(failed, Left(LeiosStoreError.Io(injected)))
        assert(published, "the rename was done; only its sync failed")
        assertEquals(again, Right(()))
        // the store found published is made durable before it is taken
        assertEquals(syncs, Vector(leios.toNioPath))
  }

  dirs.test("a first open makes missing ancestors durable, outermost first, then the store") {
    dir =>
      val leios = dir / "a" / "b" / "leios"
      for
        locks <- LeiosStoreLocks.make[IO]
        synced <- IO.ref(Vector.empty[java.nio.file.Path])
        opened <- attempt(firstOpen(leios, locks, target => synced.update(_ :+ target)))
        syncs <- synced.get
      yield
        assertEquals(opened, Right(()))
        // each made directory's entry in its parent, then the store's in its own
        assertEquals(
          syncs.map(_.toAbsolutePath),
          Vector(dir / "a", dir / "a" / "b", leios).map(_.toNioPath.toAbsolutePath)
        )
  }

  dirs.test("cancelling a first open that waits for the lock gives its permit back") { dir =>
    val leios = dir / "leios"
    for
      locks <- LeiosStoreLocks.make[IO]
      tried <- IO.deferred[java.nio.channels.FileChannel]
      // a holder in another process: every attempt finds the lock held
      fiber <- attempt(
        RocksLeiosStore
          .resourceWith[IO](leios, locks, channel => tried.complete(channel).as(None))
          .use_
      ).start
      channel <- tried.get
      _ <- fiber.cancel
      outcome <- fiber.join
      closed <- IO(!channel.isOpen)
      later <- attempt(RocksLeiosStore.resource[IO](leios, locks).use_)
    yield
      assert(outcome.isCanceled, outcome.toString)
      assert(closed, "the cancelled open closed its lock file")
      // the permit was given back: an open with the same instance takes it and the lock
      assertEquals(later, Right(()))
  }

  dirs.test("a store opens though its staging directory cannot be read") { dir =>
    val leios = dir / "leios"
    val root = RocksLeiosStore.stagingRoot(leios.toNioPath)
    for
      _ <- store(leios).use(s => ok(s.writeEbPoint(point(1, 1), word32(1))))
      _ <- IO.blocking {
        java.nio.file.Files.createDirectories(root)
        root.toFile.setReadable(false)
      }
      points <- store(leios)
        .use(s => ok(s.scanEbPoints))
        .guarantee(IO.blocking(root.toFile.setReadable(true)).void)
    yield assertEquals(points, Vector(point(1, 1)))
  }

  dirs.test(
    "a scan from before the last collection lists what the reference's volatile file holds"
  ) { dir =>
    store(dir / "leios").use { s =>
      ok(for
        // A: promoted at 3 (evicted by the collection); B: volatile at 5 (collected);
        // C: promoted at 15 and announced at 25 (kept, the point at 25 vetoes it)
        _ <- Vector(point(3, 1), point(5, 2), point(15, 3), point(25, 3)).traverse_(p =>
          s.writeEbPoint(p, word32(1))
        )
        _ <- Vector(point(3, 1), point(5, 2), point(15, 3)).traverse_(p =>
          s.writeEbBody(p, body(1), Vector.empty) *> s.writeTxs(p, Vector(0 -> tx(0)))
        )
        _ <- s.promoteToImmutable(Vector(point(3, 1), point(15, 3)))
        _ <- s.garbageCollect(slot(20))
        scanned <- s.scanCompleteEbClosuresNotOlderThanSlot(slot(0))
      yield assertEquals(scanned, Vector(point(15, 3), point(25, 3))))
    }
  }

  dirs.test("collection works through its volatile points in batches") { dir =>
    val many = RocksLeiosStore.GcBatch * 2 + 10
    store(dir / "leios").use { s =>
      ok(for
        _ <- (0 until many).toVector.traverse_(i => s.writeEbPoint(point(i.toLong, 7), word32(1)))
        _ <- s.writeEbPoint(point(1, 8), word32(1))
        _ <- s.writeEbPoint(point(5000, 8), word32(1))
        _ <- s.garbageCollect(slot(5000))
        left <- s.scanEbPoints
        _ <- s.garbageCollect(slot(5000))
        again <- s.scanEbPoints
      yield
        // hash 7's points all go; hash 8's old point stays, vetoed by its point at the frontier
        assertEquals(left, Vector(point(1, 8), point(5000, 8)))
        assertEquals(again, left))
    }
  }

  dirs.test("a first open interrupted before its store was published starts again") { dir =>
    val leios = dir / "leios"
    val root = RocksLeiosStore.stagingRoot(leios.toNioPath)
    for
      // what an interrupted first open leaves: its marked staging directory, nothing at `leios`;
      // and a directory there that is not one, which stays
      _ <- IO.blocking {
        val stage = root.resolve("0b5e3c1a-0000-4000-8000-000000000001")
        java.nio.file.Files.createDirectories(stage)
        java.nio.file.Files
          .write(stage.resolve(RocksLeiosStore.StagingMarker), Array.emptyByteArray)
        java.nio.file.Files.write(stage.resolve("CURRENT"), "MANIFEST-000001\n".getBytes)
        java.nio.file.Files.createDirectories(root.resolve("kept"))
      }
      points <- store(leios).use(s => ok(s.writeEbPoint(point(1, 1), word32(1)) *> s.scanEbPoints))
      left <- IO.blocking(listing(root))
      reopened <- store(leios).use(s => ok(s.scanEbPoints))
    yield
      assertEquals(points, Vector(point(1, 1)))
      assertEquals(left, Vector("kept"))
      assertEquals(reopened, points)
  }

  dirs.test("a first open leaves a database beside it alone") { dir =>
    val leios = dir / "leios"
    val beside = dir / "leios.init"
    for
      _ <- store(beside).use(s => ok(s.writeEbPoint(point(9, 9), word32(1))))
      before <- IO.blocking(listing(beside.toNioPath))
      _ <- store(leios).use_
      after <- IO.blocking(listing(beside.toNioPath))
      kept <- store(beside).use(s => ok(s.scanEbPoints))
    yield
      assertEquals(after, before)
      assertEquals(kept, Vector(point(9, 9)))
  }

  dirs.test("two concurrent first opens both find one store, every time") { dir =>
    (0 until 25).toVector.traverse_ { i =>
      val leios = dir / s"leios-$i"
      for
        // the two opens share the process's one instance, as the node's opens do
        locks <- LeiosStoreLocks.make[IO]
        both <- (
          attempt(firstOpen(leios, locks, _ => IO.unit)),
          attempt(firstOpen(leios, locks, _ => IO.unit))
        ).parTupled
        points <- store(leios).use(s =>
          ok(s.writeEbPoint(point(1, 1), word32(1)) *> s.scanEbPoints)
        )
        staged <- IO.blocking(
          java.nio.file.Files.exists(RocksLeiosStore.stagingRoot(leios.toNioPath))
        )
      yield
        assertEquals(both, (Right(()), Right(())))
        assertEquals(points, Vector(point(1, 1)))
        assert(!staged, "no staging directory is left")
    }
  }

  dirs.test("collection takes a hash whole, and decides each hash in the write that takes it") {
    dir =>
      val frontier = slot(100000)
      val batch = RocksLeiosStore.GcBatch
      // fillers 0 to batch - 2 at slots 0 to batch - 2; hash H is the batch's last hash, at slot
      // batch - 1 and again past the batch's boundary; J and K come after the boundary
      val fillers = (0 until batch - 1).toVector.map(i => EbPoint(slot(i.toLong), wide(i)))
      val h = wide(100000)
      val j = wide(100001)
      val k = wide(100002)
      store(dir / "leios").use { s =>
        ok(for
          _ <- fillers.traverse_(p => s.writeEbPoint(p, word32(1)))
          _ <- Vector(
            EbPoint(slot(batch.toLong - 1), h),
            EbPoint(slot(batch.toLong + 100), h),
            EbPoint(slot(batch.toLong + 50), j),
            EbPoint(slot(batch.toLong + 60), k)
          ).traverse_(p => s.writeEbPoint(p, word32(1)))
          cursor <- s.collectBatch(frontier, None)
          afterFirst <- s.scanEbPoints
          // between the writes: J is announced at the frontier, K is promoted
          _ <- s.writeEbPoint(EbPoint(frontier, j), word32(1))
          _ <- s.promoteToImmutable(Vector(EbPoint(slot(batch.toLong + 60), k)))
          _ <- cursor.fold(IO.unit)(c => s.collectBatch(frontier, Some(c)).void)
          last <- s.scanEbPoints
        yield
          // H went whole in the first write, its point past the boundary with it
          assertEquals(afterFirst.filter(_.hash == h), Vector.empty)
          assertEquals(
            afterFirst.map(_.hash).toSet,
            Set(j, k)
          )
          assertEquals(
            last,
            Vector(
              EbPoint(slot(batch.toLong + 50), j),
              EbPoint(slot(batch.toLong + 60), k),
              EbPoint(frontier, j)
            )
          ))
      }
  }

  dirs.test("an export reads the store at one moment, whatever a collection does meanwhile") {
    dir =>
      store(dir / "leios").use { s =>
        ok(for
          _ <- s.writeEbPoint(point(1, 1), word32(1))
          _ <- s.writeEbBody(point(1, 1), body(1), Vector.empty)
          _ <- s.writeTxs(point(1, 1), Vector(0 -> tx(0)))
          read <- s.exporting { (hashes, stored) =>
            s.garbageCollect(slot(10)) *> hashes.traverse(stored)
          }
          now <- s.scanEbPoints
        yield
          assertEquals(read.map(_.points.map(_._1)), Vector(Vector(slot(1))))
          assertEquals(read.map(_.txs.values.toVector), Vector(Vector(tx(0))))
          assertEquals(now, Vector.empty))
      }
  }

  test("a body has no product mirror, whose fromProduct would skip the check of its hashes") {
    assert(compileErrors("summon[scala.deriving.Mirror.ProductOf[LeiosEb]]").nonEmpty)
  }

  dirs.test(
    "a directory that holds anything but a store, and a store with no format record, are refused"
  ) { dir =>
    val stray = dir / "stray"
    val unstamped = dir / "unstamped"
    for
      _ <- IO.blocking {
        java.nio.file.Files.createDirectories(stray.toNioPath)
        java.nio.file.Files.write(stray.toNioPath.resolve("notes"), "x".getBytes)
      }
      strayed <- attemptOpen(stray)
      _ <- store(unstamped).use_
      _ <- IO.blocking(deleteFormat(unstamped))
      missing <- attemptOpen(unstamped)
    yield
      assertEquals(strayed, Left(LeiosStoreError.ForeignDatabase(Vector.empty)))
      assertEquals(missing, Left(LeiosStoreError.FormatMissing))
  }

  private def attemptOpen(dir: Path): IO[Either[LeiosStoreError, Unit]] =
    LeiosStoreLocks
      .make[IO]
      .flatMap(locks => attempt(RocksLeiosStore.resource[IO](dir, locks).use_))

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
    eb(NonEmptyVector.fromVectorUnsafe(Vector.tabulate(n)(i => entry(tx(i)))))

  def eb(transactions: NonEmptyVector[(LeiosTxHash, Word32)]): LeiosEb =
    LeiosEb.from(transactions).fold(e => throw new AssertionError(e.toString), identity)

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

  /** A first open of `dir` that opens nothing, its parent directory synced with `sync`. */
  def firstOpen(dir: Path, locks: LeiosStoreLocks[IO], sync: java.nio.file.Path => IO[Unit])(using
      Handle[IO, LeiosStoreError]
  ): IO[Unit] =
    firstOpenWith(dir, locks, sync, channel => IO.blocking(Option(channel.tryLock())))

  /** [[firstOpen]], attempting the lock with `attemptLock`. */
  def firstOpenWith(
      dir: Path,
      locks: LeiosStoreLocks[IO],
      sync: java.nio.file.Path => IO[Unit],
      attemptLock: java.nio.channels.FileChannel => IO[Option[java.nio.channels.FileLock]]
  )(using Handle[IO, LeiosStoreError]): IO[Unit] =
    IO.uncancelable(poll =>
      RocksLeiosStore.firstOpen[IO, Unit](dir, locks, sync, attemptLock, poll)(IO.unit)
    )

  /** Hash `i` of many: distinct for every `i` below 2^31. */
  def wide(i: Int): EbHash =
    EbHash.fromBytes(
      Bytes(Vector.fill(28)(7.toByte) ++ Vector.tabulate(4)(b => (i >>> (8 * (3 - b))).toByte))
    )

  /** The names in `dir`, sorted. */
  def listing(dir: java.nio.file.Path): Vector[String] =
    scala.util.Using.resource(java.nio.file.Files.list(dir))(
      _.iterator.asScala.map(_.getFileName.toString).toVector.sorted
    )

  /** Remove the store's format record, as a store stamped by nothing would lack it. */
  def deleteFormat(dir: Path): Unit =
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
      db.delete(meta, "format".getBytes)
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
