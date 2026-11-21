// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError, LeiosStoreLocks}

/** The node's endorser-block store ([[LeiosStorage]]): the Leios build's run opens it at `leios/`
  * in its database directory and closes it with the run, as cardano-node b004fe0c opens and closes
  * its LeiosDB (`Run.hs:383-402`); a release build's run opens none, nor does a run whose
  * `LeiosDbConfig` keeps the store in memory or that has no database directory.
  */
class LeiosStorageSuite extends CatsEffectSuite:
  import LeiosStorageSuite.{*, given}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  private def opened(
      build: ReferenceBuild,
      backend: LeiosDbBackend,
      db: Option[Path]
  ): Resource[IO, Option[LeiosStore[IO]]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks =>
        // a configuration `follow` refuses opens nothing
        LeiosStorage
          .follows(build, backend, db)
          .fold(
            _ => Resource.pure[IO, Option[LeiosStore[IO]]](None),
            plan =>
              Resource
                .make(ok(LeiosStorage.resource[IO](plan, locks).allocated))(_._2)
                .map(_._1)
          )
      )

  private def exists(path: Path): IO[Boolean] = Files.forAsync[IO].exists(path)

  dirs.test(
    "the Leios build opens the store at leios/ of the database, and closes it with the run"
  ) { db =>
    for
      written <- opened(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite, Some(db)).use {
        case None => IO.raiseError(new AssertionError("no store under the Leios build"))
        case Some(s) =>
          for
            _ <- ok(s.writeEbPoint(point, word32(100)))
            points <- ok(s.scanEbPoints)
            opened <- exists(db / "leios")
          yield (points, opened)
      }
      // released: the next run opens it again and finds what the last one wrote
      again <- opened(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite, Some(db)).use(
        _.traverse(s => ok(s.scanEbPoints))
      )
    yield
      assertEquals(written, (Vector(point), true))
      assertEquals(again, Some(Vector(point)))
  }

  dirs.test("the store refuses calls once the run has released it") { db =>
    opened(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite, Some(db)).allocated.flatMap {
      (store, release) =>
        release *> store
          .traverse(s => attempt(s.scanEbPoints))
          .map(r => assertEquals(r, Some(Left(StorageError.LeiosStore(LeiosStoreError.Closed)))))
    }
  }

  dirs.test("a release build's run opens no store and makes nothing of it") { db =>
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2)
      .traverse(build =>
        opened(build, LeiosDbBackend.SQLite, Some(db)).use(store => IO.pure(store.isEmpty))
      )
      .flatMap(none =>
        (exists(db / "leios"), exists(db / "leios.lock")).tupled
          .map(made => assertEquals((none, made), (Vector(true, true), (false, false))))
      )
  }

  dirs.test(
    "an in-memory LeiosDbConfig, or a run without a database, is refused and opens nothing"
  ) { db =>
    (
      opened(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.InMemory, Some(db)).use(s =>
        IO.pure(s.isEmpty)
      ),
      opened(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite, None).use(s =>
        IO.pure(s.isEmpty)
      ),
      exists(db / "leios")
    ).tupled.map(r => assertEquals(r, (true, true, false)))
  }

  test("follow under the Leios build without a store to open is refused, naming the setting") {
    import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem, LeiosStoreMissing}
    val db = Some(Path("db"))
    def refused(missing: LeiosStoreMissing) =
      Left(ConfigError(ConfigProblem.LeiosStoreRequired(ReferenceBuild.LeiosB004fe0c, missing)))
    assertEquals(
      LeiosStorage.follows(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite, db),
      Right(LeiosStorage.Plan.OnDisk(Path("db")))
    )
    assertEquals(
      LeiosStorage.follows(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.InMemory, db),
      refused(LeiosStoreMissing.InMemoryBackend)
    )
    assertEquals(
      LeiosStorage.follows(ReferenceBuild.LeiosB004fe0c, LeiosDbBackend.SQLite, None),
      refused(LeiosStoreMissing.NoDatabase)
    )
    assert(
      ConfigProblem
        .LeiosStoreRequired(ReferenceBuild.LeiosB004fe0c, LeiosStoreMissing.InMemoryBackend)
        .message
        .startsWith("LeiosDbConfig.Backend InMemory:")
    )
    assert(
      ConfigProblem
        .LeiosStoreRequired(ReferenceBuild.LeiosB004fe0c, LeiosStoreMissing.NoDatabase)
        .message
        .startsWith("--db:")
    )
    // a release build needs none
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2).foreach { build =>
      Vector(LeiosDbBackend.SQLite, LeiosDbBackend.InMemory).foreach { backend =>
        assertEquals(LeiosStorage.follows(build, backend, None), Right(LeiosStorage.Plan.NoStore))
      }
    }
  }

  dirs.test("a store the node cannot open raises the store's typed cause") { db =>
    // a file where the store's directory goes: not a database
    Files
      .forAsync[IO]
      .writeAll(db / "leios")(fs2.Stream.emits(Array[Byte](1, 2, 3)))
      .compile
      .drain *>
      LeiosStoreLocks
        .resource[IO]
        .use(locks =>
          attempt(
            LeiosStorage.resource[IO](LeiosStorage.Plan.OnDisk(db), locks).use_
          )
        )
        .map {
          case Left(StorageError.LeiosStore(_: LeiosStoreError)) => ()
          case other => fail(s"expected the store's typed refusal, got $other")
        }
  }

object LeiosStorageSuite:
  val point: EbPoint =
    EbPoint(
      Word64.from(BigInt(7)).fold(e => throw new AssertionError(e.toString), identity),
      EbHash.fromBytes(Bytes(Vector.fill(32)(1.toByte)))
    )

  def word32(n: Long): Word32 =
    Word32.from(BigInt(n)).fold(e => throw new AssertionError(e.toString), identity)

  def attempt[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[Either[StorageError, A]] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  def ok[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[A] =
    attempt(body).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.toString)), IO.pure))

  /** The store's own calls, with its errors as the node lifts them. */
  given (using Handle[IO, StorageError]): cats.mtl.Raise[IO, LeiosStoreError] =
    LeiosStorage.lifted[IO]
