// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.leios

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.{EbStatus, LeiosEb, RocksLeiosStore, StoredEb}
import xyz.kd5ujc.scarab.storage.LeiosStoreSuite.*

/** The import and export of the reference's Leios database (`leios.vol.db`, `leios.imm.db`;
  * ouroboros-consensus 2416493b `Storage/LeiosDB/Impl/SQLite/Schema.hs`): a store exported and
  * imported again is the same store, a database written as the reference writes it reads as the
  * reference reads it, and what does not fit the schema's invariants is refused.
  */
class LeiosReferenceDbSuite extends CatsEffectSuite:
  import LeiosReferenceDbSuite.*

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  private def store(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    allowing(RocksLeiosStore.resource[IO](dir))

  /** Everything the store holds, hash by hash. */
  private def contents(s: RocksLeiosStore[IO]): IO[Vector[StoredEb]] =
    ok(s.exporting((hashes, stored) => hashes.traverse(stored)))

  dirs.test("a store exported and imported again is the same store") { dir =>
    val reference = dir / "reference"
    for
      written <- store(dir / "a").use { s =>
        ok(for
          // 6: promoted, then collected: evicted
          _ <- s.writeEbPoint(point(1, 6), word32(1))
          _ <- s.writeEbBody(point(1, 6), body(1), Vector.empty)
          _ <- s.writeTxs(point(1, 6), Vector(0 -> tx(0)))
          _ <- s.promoteToImmutable(Vector(point(1, 6)))
          _ <- s.garbageCollect(slot(2))
          // 1: complete, at two slots; 2: one of two transactions; 3: a point without a body
          _ <- Vector(
            point(10, 1),
            point(11, 1),
            point(12, 2),
            point(13, 3),
            point(20, 4),
            point(21, 4)
          )
            .traverse_(p => s.writeEbPoint(p, word32(300)))
          _ <- s.writeEbBody(point(10, 1), body(2), Vector.empty)
          _ <- s.writeTxs(point(10, 1), Vector(0 -> tx(0), 1 -> tx(1)))
          _ <- s.writeEbBody(point(12, 2), body(2), Vector.empty)
          _ <- s.writeTxs(point(12, 2), Vector(1 -> tx(1)))
          // 4: promoted, then announced again
          _ <- s.writeEbBody(point(20, 4), body(3), Vector.empty)
          _ <- s.writeTxs(point(20, 4), Vector(0 -> tx(0), 1 -> tx(1), 2 -> tx(2)))
          _ <- s.promoteToImmutable(Vector(point(20, 4)))
          _ <- s.writeEbPoint(point(30, 4), word32(1))
          // 5: promoted, at one slot
          _ <- s.writeEbPoint(point(40, 5), word32(1))
          _ <- s.writeEbBody(point(40, 5), body(1), Vector.empty)
          _ <- s.writeTxs(point(40, 5), Vector(0 -> tx(0)))
          _ <- s.promoteToImmutable(Vector(point(40, 5)))
        yield ()) *> exported(s, reference).product(contents(s))
      }
      (counts, before) = written
      after <- store(dir / "b").use(s => imported(reference, s) *> contents(s))
      volStatuses <- IO.blocking(
        query(reference / "leios.vol.db", "SELECT status FROM ebs ORDER BY ebSlot")
      )
      immSlots <- IO.blocking(
        query(reference / "leios.imm.db", "SELECT ebSlot FROM ebs ORDER BY ebSlot")
      )
      journal <- IO.blocking(query(reference / "leios.vol.db", "PRAGMA journal_mode"))
      pageSize <- IO.blocking(query(reference / "leios.imm.db", "PRAGMA page_size"))
    yield
      assertEquals(after, before)
      assertEquals(before.flatMap(_.points).count(_._3 == EbStatus.Evicted), 1)
      assertEquals(counts, LeiosReferenceDb.Counts(points = 11, bodies = 7, transactions = 8))
      // the volatile file has every point that is not evicted, the promoted ones copied (2); the
      // immutable file has the newest promoted or evicted point of each hash: 6's, 4's and 5's
      assertEquals(volStatuses, Vector("0", "0", "0", "0", "2", "2", "0", "2"))
      assertEquals(immSlots, Vector("1", "21", "40"))
      assertEquals(journal, Vector("wal"))
      assertEquals(pageSize, Vector("4096"))
  }

  dirs.test("a database written as the reference writes it reads as the reference reads it") {
    dir =>
      val reference = dir / "reference"
      val partly = body(3)
      val whole = body(2)
      for
        _ <- IO.blocking(Using.resource(open(reference / "leios.vol.db")) { c =>
          referenceSchema(c, volatile = true)
          // points: volatile, pinned, copied, marked for collection
          Vector((5L, 1, 0), (6L, 2, 1), (7L, 3, 2), (8L, 4, 3)).foreach((slot, h, status) =>
            update(
              c,
              "INSERT INTO ebs (ebSlot, ebHashBytes, ebBytesSize, status) VALUES (?, ?, ?, ?)"
            )(
              slot,
              hash(h).bytes.toArray,
              100L,
              status.toLong
            )
          )
          // 1 misses its middle transaction, which the closure holds as zeroes
          insertBody(c, 1, partly, Some(Vector(Some(tx(0)), None, Some(tx(2)))))
          insertBody(c, 2, whole, None)
          insertBody(c, 3, whole, Some(Vector(Some(tx(0)), Some(tx(1)))))
        })
        _ <- IO.blocking(Using.resource(open(reference / "leios.imm.db")) { c =>
          referenceSchema(c, volatile = false)
          update(c, "INSERT INTO ebs (ebSlot, ebHashBytes, ebBytesSize) VALUES (?, ?, ?)")(
            7L,
            hash(3).bytes.toArray,
            100L
          )
          insertBody(c, 3, whole, Some(Vector(Some(tx(0)), Some(tx(1)))))
          // 5 is in the immutable file only: the reference's collection evicted it
          update(c, "INSERT INTO ebs (ebSlot, ebHashBytes, ebBytesSize) VALUES (?, ?, ?)")(
            6L,
            hash(5).bytes.toArray,
            100L
          )
          insertBody(c, 5, whole, Some(Vector(Some(tx(0)), Some(tx(1)))))
        })
        result <- store(dir / "store").use { s =>
          imported(reference, s).flatMap(counts =>
            ok(
              for
                body1 <- s.lookupEbBody(hash(1))
                closure1 <- s.lookupEbClosure(hash(1))
                some1 <- s.batchRetrieveTxs(hash(1), Vector(0, 1, 2))
                closure3 <- s.lookupEbClosure(hash(3))
                complete <- s.scanCompleteEbClosuresNotOlderThanSlot(slot(0))
                closure5 <- s.lookupEbClosure(hash(5))
                statuses <- Vector(1, 2, 3, 4, 5).traverse(i =>
                  s.stored(hash(i)).map(_.points.map(_._3))
                )
                withBody2 <- s.stored(hash(2))
              yield (
                counts,
                body1,
                closure1,
                some1,
                closure3,
                complete,
                statuses,
                withBody2,
                closure5
              )
            )
          )
        }
        (counts, body1, closure1, some1, closure3, complete, statuses, withBody2, closure5) = result
      yield
        // 3's body and closure come from the volatile file, 5's from the immutable one
        assertEquals(counts, LeiosReferenceDb.Counts(points = 6, bodies = 4, transactions = 6))
        assertEquals(body1, Some(partly.encoded))
        assertEquals(closure1, None)
        assertEquals(some1, Vector(0 -> tx(0), 1 -> zeroes(tx(1).size), 2 -> tx(2)))
        assertEquals(closure3, Some(Vector(0, 1).map(i => LeiosEb.hashTx(tx(i)) -> tx(i))))
        // 3 is complete and copied, and still in the volatile file: the reference lists it
        assertEquals(complete, Vector(point(7, 3)))
        assertEquals(
          statuses,
          Vector(
            Vector(EbStatus.Volatile),
            Vector(EbStatus.Certified),
            Vector(EbStatus.Immutable),
            Vector(EbStatus.Volatile),
            Vector(EbStatus.Evicted)
          )
        )
        assertEquals(closure5.map(_.size), Some(2))
        assertEquals(withBody2.txs.size, 0)
  }

  dirs.test(
    "bounds that are not the body's, and a database with one of its two files, are refused"
  ) { dir =>
    val reference = dir / "reference"
    val lone = dir / "lone"
    for
      _ <- IO.blocking(Using.resource(open(reference / "leios.vol.db")) { c =>
        referenceSchema(c, volatile = true)
        update(c, "INSERT INTO ebBodies (ebHashBytes, txBounds, body) VALUES (?, ?, ?)")(
          hash(1).bytes.toArray,
          Array[Byte](0, 0, 0, 0, 0, 0, 0, 1),
          body(1).encoded.toArray
        )
      })
      _ <- IO.blocking(
        Using.resource(open(reference / "leios.imm.db"))(referenceSchema(_, volatile = false))
      )
      disagree <- store(dir / "s").use(s => attemptImport(reference, s))
      _ <- IO.blocking(
        Using.resource(open(lone / "leios.vol.db"))(referenceSchema(_, volatile = true))
      )
      missing <- attemptRaise(LeiosReferenceDb.present[IO](lone))
      absent <- attemptRaise(LeiosReferenceDb.present[IO](dir / "nothing"))
    yield
      assertEquals(
        disagree,
        Left(LeiosReferenceDb.Error.BoundsDisagree(LeiosReferenceDb.Partition.Volatile, hash(1)))
      )
      assertEquals(
        missing,
        Left(LeiosReferenceDb.Error.PartitionMissing(LeiosReferenceDb.Partition.Immutable))
      )
      assertEquals(absent, Right(false))
  }

  dirs.test("an import writes nothing beside the source, which may be read-only") { dir =>
    val reference = dir / "reference"
    def listing = IO.blocking(
      Using.resource(java.nio.file.Files.list(reference.toNioPath))(
        _.iterator.asScala.map(_.getFileName.toString).toVector.sorted
      )
    )
    for
      _ <- store(dir / "a").use { s =>
        ok(
          s.writeEbPoint(point(1, 1), word32(1)) *> s.writeEbBody(
            point(1, 1),
            body(1),
            Vector.empty
          )
        ) *>
          exported(s, reference)
      }
      before <- listing
      _ <- store(dir / "b").use(imported(reference, _))
      after <- listing
      _ <- IO.blocking(reference.toNioPath.toFile.setWritable(false))
      readOnly <- store(dir / "c")
        .use(s => attemptImport(reference, s))
        .guarantee(IO.blocking(reference.toNioPath.toFile.setWritable(true)).void)
      last <- listing
    yield
      assertEquals(before, Vector("leios.imm.db", "leios.vol.db"))
      assertEquals(after, before)
      assertEquals(readOnly.map(_.points), Right(1L))
      assertEquals(last, before)
  }

  dirs.test("commits still in the write-ahead log are read") { dir =>
    val reference = dir / "reference"
    val writer = Resource.make(IO.blocking {
      val c = open(reference / "leios.vol.db")
      Using.resource(c.createStatement())(_.execute("pragma wal_autocheckpoint = 0;"))
      c
    })(c => IO.blocking(c.close()))
    for
      _ <- store(dir / "a").use(s =>
        ok(s.writeEbPoint(point(1, 1), word32(1))) *> exported(s, reference)
      )
      points <- writer.use { c =>
        IO.blocking(
          update(
            c,
            "INSERT INTO ebs (ebSlot, ebHashBytes, ebBytesSize, status) VALUES (?, ?, ?, ?)"
          )(
            9L,
            hash(9).bytes.toArray,
            5L,
            0L
          )
        ) *> store(dir / "b").use(s => imported(reference, s) *> ok(s.scanEbPoints))
      }
    yield assertEquals(points, Vector(point(1, 1), point(9, 9)))
  }

  dirs.test("an export does not write over an existing database") { dir =>
    for
      _ <- Files.forAsync[IO].createDirectories(dir / "reference")
      _ <- Files.forAsync[IO].createFile(dir / "reference" / "leios.imm.db")
      refused <- store(dir / "s").use(s =>
        attemptRaise(LeiosReferenceDb.exportFrom(s, dir / "reference"))
      )
    yield assertEquals(
      refused,
      Left(LeiosReferenceDb.Error.TargetExists(LeiosReferenceDb.Partition.Immutable))
    )
  }

object LeiosReferenceDbSuite:
  def attemptRaise[A](
      body: Handle[IO, LeiosReferenceDb.Error] ?=> IO[A]
  ): IO[Either[LeiosReferenceDb.Error, A]] =
    Handle
      .allow[LeiosReferenceDb.Error](body.map(_.asRight[LeiosReferenceDb.Error]))
      .rescue(e => IO.pure(e.asLeft[A]))

  def attemptImport(
      dir: Path,
      s: RocksLeiosStore[IO]
  ): IO[Either[LeiosReferenceDb.Error, LeiosReferenceDb.Counts]] =
    attemptRaise(LeiosReferenceDb.importInto(dir, s))

  def imported(dir: Path, s: RocksLeiosStore[IO]): IO[LeiosReferenceDb.Counts] =
    attemptImport(dir, s).flatMap(
      _.fold(e => IO.raiseError(new AssertionError(e.toString)), IO.pure)
    )

  def exported(s: RocksLeiosStore[IO], dir: Path): IO[LeiosReferenceDb.Counts] =
    attemptRaise(LeiosReferenceDb.exportFrom(s, dir))
      .flatMap(_.fold(e => IO.raiseError(new AssertionError(e.toString)), IO.pure))

  def open(path: Path): Connection =
    java.nio.file.Files.createDirectories(path.toNioPath.getParent)
    DriverManager.getConnection("jdbc:sqlite:" + path.toString)

  /** The reference's schema, as `Schema.hs:16-91` at 2416493b spells it, written here independently
    * of the export's copy.
    */
  def referenceSchema(c: Connection, volatile: Boolean): Unit =
    val status = if volatile then Vector("  status INTEGER NOT NULL DEFAULT 0,") else Vector.empty
    val ddl = Vector(
      "CREATE TABLE IF NOT EXISTS ebs (",
      "  ebSlot INTEGER NOT NULL,",
      "  ebHashBytes BLOB NOT NULL,",
      "  ebBytesSize INTEGER NOT NULL,"
    ) ++ status ++ Vector(
      "  PRIMARY KEY (ebSlot, ebHashBytes)",
      ");",
      "CREATE INDEX IF NOT EXISTS idx_ebs_ebHashBytes ON ebs(ebHashBytes);",
      "CREATE TABLE IF NOT EXISTS ebBodies (",
      "  ebHashBytes BLOB NOT NULL PRIMARY KEY,",
      "  txBounds BLOB NOT NULL,",
      "  body BLOB NOT NULL",
      ");",
      "CREATE TABLE IF NOT EXISTS ebClosures (",
      "  ebHashBytes BLOB NOT NULL PRIMARY KEY,",
      "  txs BLOB NOT NULL",
      ");"
    )
    Using.resource(c.createStatement()) { s =>
      s.execute("pragma page_size = 4096;")
      s.execute("pragma journal_mode = WAL;")
      ddl.mkString("\n").split(';').map(_.trim).filter(_.nonEmpty).foreach(s.executeUpdate)
    }

  def update(c: Connection, sql: String)(values: Any*): Unit =
    Using.resource(c.prepareStatement(sql)) { s =>
      values.zipWithIndex.foreach {
        case (v: Long, i)        => s.setLong(i + 1, v)
        case (v: Array[Byte], i) => s.setBytes(i + 1, v)
        case (v, _)              => throw new AssertionError(s"unexpected parameter $v")
      }
      s.executeUpdate()
    }

  /** A body as `sqlInsertEbBody` writes it, and its closure as the reference's tx writes leave it:
    * allocated with zeroes (`zeroblob`), each written transaction in place.
    */
  def insertBody(c: Connection, h: Int, eb: LeiosEb, closure: Option[Vector[Option[Bytes]]]): Unit =
    val bounds = eb.bounds
    update(c, "INSERT OR IGNORE INTO ebBodies (ebHashBytes, txBounds, body) VALUES (?1, ?2, ?3)")(
      hash(h).bytes.toArray,
      bounds.toArray.flatMap(b => Array.tabulate(4)(i => (b >>> (8 * (3 - i))).toByte)),
      eb.encoded.toArray
    )
    closure.foreach { txs =>
      val blob = new Array[Byte](bounds.last.toInt)
      txs.zipWithIndex.foreach((tx, i) => tx.foreach(_.copyTo(blob, bounds(i).toInt)))
      update(c, "INSERT INTO ebClosures (ebHashBytes, txs) VALUES (?1, ?2)")(
        hash(h).bytes.toArray,
        blob
      )
    }

  def query(path: Path, sql: String): Vector[String] =
    Using.resource(DriverManager.getConnection("jdbc:sqlite:" + path.toString)) { c =>
      Using.resource(c.createStatement()) { s =>
        Using.resource(s.executeQuery(sql)) { r =>
          Iterator.continually(r.next()).takeWhile(identity).map(_ => r.getString(1)).toVector
        }
      }
    }
