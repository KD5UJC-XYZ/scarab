// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import lab.cbor.Bytes
import lab.ledger.RestrictedReplay as R
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import ReplayStore.*
import NioReplayStore.Phase

private[runtime] object ReplayFixtures:
  val packet: Path =
    val local = Path.of("fixtures/restricted-replay")
    if Files.isDirectory(local) then local else Path.of("../fixtures/restricted-replay")
  def raw(name: String): Bytes = Bytes.fromArray(Files.readAllBytes(packet.resolve(name)))
  def checkpoint: Checkpoint = Checkpoint(
    raw("pparams.cbor"),
    raw("initial-utxo.cbor"),
    ReplayCodec.sha(raw("attribution.json").toArray)
  )
  val a = "value-conservation-1.cbor"
  val b = "missing-vkey-1.cbor"
  def directory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("restricted-durable-")))(path =>
      IO.blocking {
        val stream = Files.walk(path)
        try
          stream
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(p => Files.delete(p))
        finally stream.close()
      }
    )
  def good[A](result: Either[Rejection, A]): IO[A] =
    IO.fromEither(result.left.map(r => new RuntimeException(r.toString)))

class NioReplayStoreSuite extends munit.FunSuite:
  import ReplayFixtures.*
  override val munitTimeout = 90.seconds
  private def future(body: IO[Unit]) = body.unsafeToFuture()
  private def errorKind(result: Either[Throwable, ?], expected: StorageFailure): Unit = result match
    case Left(error: StorageException) => assertEquals(error.failure, expected)
    case other                         => fail(s"expected $expected, got $other")
  private def sameContent(left: R.State, right: R.State): Unit =
    assertEquals(left.stateId, right.stateId)
    assertEquals(left.outputMap, right.outputMap)
    assertEquals(left.feesSinceCheckpoint, right.feesSinceCheckpoint)
    assertEquals(
      left.utxo.view.mapValues(_.original).toMap,
      right.utxo.view.mapValues(_.original).toMap
    )
  private def committed(phase: Phase): Boolean =
    Phase.values.indexOf(phase) >= Phase.values.indexOf(Phase.HeadReplaced)
  private def seed(root: Path): IO[Unit] =
    NioReplayStore.create[IO](root, checkpoint).use(_ => IO.unit)
  private def applyA(store: ReplayStore[IO]): IO[Snapshot] =
    store.snapshot.flatMap(s => store.commit(s.version, Vector(raw(a))).flatMap(good))

  for (name, rejection) <- Vector("value-conservation" -> "balance", "missing-vkey" -> "coverage")
  do
    test(s"$name original outputs, fees, transaction and undo survive reopening") {
      future(directory.use { root =>
        for
          applied <- NioReplayStore.create[IO](root, checkpoint).use { store =>
            for
              before <- store.snapshot
              applied <- store.commit(before.version, Vector(raw(s"$name-1.cbor"))).flatMap(good)
              rejected <- store.commit(applied.version, Vector(raw(s"$name-2.cbor")))
              _ = rejected match
                case Left(Rejection.Ledger(R.Failure.Rejected(stage, _))) =>
                  assertEquals(stage, rejection)
                case other => fail(s"unexpected outcome $other")
              after <- store.snapshot
              _ = assertEquals(after.version.head, applied.version.head)
            yield applied
          }
          _ <- NioReplayStore.open[IO](root).use { store =>
            for
              reopened <- store.snapshot
              _ = sameContent(reopened.state, applied.state)
              _ = assertEquals(reopened.state.revision.number, BigInt(1))
              _ = assertEquals(reopened.undo.get.transitionId, applied.undo.get.transitionId)
              _ = assertEquals(
                reopened.undo.get.transactions.head.originalTransaction,
                raw(s"$name-1.cbor")
              )
              _ = assertEquals(reopened.state.feesSinceCheckpoint, BigInt(167041))
              expected = lab.ledger.Coverage
                .decodeResolved(raw(s"$name-final-utxo.cbor"))
                .toOption
                .get
              _ = assertEquals(
                reopened.state.utxo.view.mapValues(_.original).toMap,
                expected.view.mapValues(_.original).toMap
              )
              oldSession <- store.commit(applied.version, Vector.empty)
              _ = assertEquals(oldSession, Left(Rejection.StaleVersion))
              duplicate <- store.commit(reopened.version, Vector(raw(s"$name-1.cbor")))
              _ = assert(duplicate.isLeft)
              rolled <- store
                .rollback(reopened.version, reopened.undo.get.transitionId)
                .flatMap(good)
              _ = assertEquals(rolled.state.revision.number, BigInt(2))
              _ = assertEquals(rolled.state.feesSinceCheckpoint, BigInt(0))
              _ = assertEquals(rolled.state.checkpoint.originalUtxo, raw("initial-utxo.cbor"))
              _ = assertEquals(
                rolled.state.utxo.view.mapValues(_.original).toMap,
                lab.ledger.Coverage
                  .decodeResolved(raw("initial-utxo.cbor"))
                  .toOption
                  .get
                  .view
                  .mapValues(_.original)
                  .toMap
              )
            yield ()
          }
          _ <- NioReplayStore
            .open[IO](root)
            .use(_.snapshot.map { reopened =>
              assertEquals(reopened.state.revision.number, BigInt(2))
              assertEquals(reopened.state.feesSinceCheckpoint, BigInt(0))
              assertEquals(reopened.undo, None)
            })
        yield ()
      })
    }

  test("apply rollback branch-switch and reapply fence ABA and old undo") {
    future(
      directory.use(root =>
        NioReplayStore.create[IO](root, checkpoint).use { store =>
          for
            original <- store.snapshot
            first <- store.commit(original.version, Vector(raw(a))).flatMap(good)
            wrong <- store.rollback(first.version, Bytes(Vector.fill(32)(0.toByte)))
            _ = assertEquals(wrong, Left(Rejection.RollbackMismatch))
            restored <- store.rollback(first.version, first.undo.get.transitionId).flatMap(good)
            _ = sameContent(original.state, restored.state)
            stale <- store.commit(original.version, Vector(raw(b)))
            _ = assertEquals(stale, Left(Rejection.StaleVersion))
            second <- store.commit(restored.version, Vector(raw(b))).flatMap(good)
            _ = assertEquals(second.state.revision.number, BigInt(3))
            oldUndo <- store.rollback(second.version, first.undo.get.transitionId)
            _ = assertEquals(oldUndo, Left(Rejection.RollbackMismatch))
            restoredAgain <- store
              .rollback(second.version, second.undo.get.transitionId)
              .flatMap(good)
            repeated <- store.commit(restoredAgain.version, Vector(raw(a))).flatMap(good)
            _ = sameContent(first.state, repeated.state)
            _ = assertNotEquals(first.undo.get.transitionId, repeated.undo.get.transitionId)
            _ = assertEquals(repeated.state.revision.number, BigInt(5))
          yield ()
        }
      )
    )
  }

  test(
    "atomic genuine accepted/rejected batch writes no partial history; empty commit is exact no-op"
  ) {
    future(
      directory.use(root =>
        NioReplayStore.create[IO](root, checkpoint).use { store =>
          for
            before <- store.snapshot
            failed <- store.commit(before.version, Vector(raw(a), raw("value-conservation-2.cbor")))
            _ = assert(failed.isLeft)
            after <- store.snapshot
            _ = assert(after eq before)
            empty <- store.commit(before.version, Vector.empty).flatMap(good)
            _ = assert(empty eq before)
            _ = assertEquals(empty.historyLength, 0)
          yield ()
        }
      )
    )
  }

  test("concurrent commits from one snapshot have exactly one winner and one fee charge") {
    future(
      directory.use(root =>
        NioReplayStore.create[IO](root, checkpoint).use { store =>
          for
            before <- store.snapshot
            results <- Vector(raw(a), raw(b))
              .parTraverse(tx => store.commit(before.version, Vector(tx)))
            _ = assertEquals(results.count(_.isRight), 1)
            _ = assertEquals(results.count(_ == Left(Rejection.StaleVersion)), 1)
            after <- store.snapshot
            _ = assertEquals(after.state.feesSinceCheckpoint, BigInt(167041))
            _ = assertEquals(after.state.revision.number, BigInt(1))
          yield ()
        }
      )
    )
  }

  test("exclusive lock, close and reopen-session fencing cover reads and mutations") {
    future(directory.use { root =>
      for
        previous <- NioReplayStore.create[IO](root, checkpoint).use { store =>
          for
            before <- store.snapshot
            locked <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
            _ = errorKind(locked, StorageFailure.Locked)
          yield (store, before)
        }
        (store, before) = previous
        closedRead <- store.snapshot.attempt
        closedWrite <- store.commit(before.version, Vector(raw(a))).attempt
        _ = errorKind(closedRead, StorageFailure.Closed)
        _ = errorKind(closedWrite, StorageFailure.Closed)
        _ <- NioReplayStore.open[IO](root).use { next =>
          next
            .commit(before.version, Vector(raw(a)))
            .map(r => assertEquals(r, Left(Rejection.StaleVersion)))
        }
      yield ()
    })
  }

  for phase <- Phase.values do
    for undo <- Vector(false, true) do
      test(s"${
          if undo then "rollback" else "apply"
        } failure at $phase poisons owner and reopens old or complete new state") {
        future(directory.use { root =>
          val injected = new java.io.IOException(s"injected $phase")
          for
            _ <- seed(root)
            before <- NioReplayStore.open[IO](root).use(s => if undo then applyA(s) else s.snapshot)
            _ <- NioReplayStore.open[IO](root, fault = p => if p == phase then throw injected).use {
              store =>
                for
                  current <- store.snapshot
                  result <-
                    (if undo then store.rollback(current.version, current.undo.get.transitionId)
                     else store.commit(current.version, Vector(raw(a)))).attempt
                  _ = assertEquals(result.left.toOption, Some(injected))
                  brokenRead <- store.snapshot.attempt
                  brokenWrite <- store.commit(current.version, Vector.empty).attempt
                  brokenRollback <- store.rollback(current.version, Bytes.empty).attempt
                  _ = errorKind(brokenRead, StorageFailure.RequiresReopen)
                  _ = errorKind(brokenWrite, StorageFailure.RequiresReopen)
                  _ = errorKind(brokenRollback, StorageFailure.RequiresReopen)
                yield ()
            }
            _ <- NioReplayStore.open[IO](root).use { store =>
              store.snapshot.map { recovered =>
                val advanced = if committed(phase) then 1 else 0
                assertEquals(
                  recovered.state.revision.number,
                  before.state.revision.number + advanced
                )
                val expectedFees =
                  if undo then (if committed(phase) then 0 else 167041)
                  else (if committed(phase) then 167041
                        else 0)
                assertEquals(recovered.state.feesSinceCheckpoint, BigInt(expectedFees))
                assertEquals(recovered.undo.nonEmpty, expectedFees != 0)
                if !committed(phase) then sameContent(recovered.state, before.state)
              }
            }
          yield ()
        })
      }

  for phase <- Vector(
      Phase.ObjectPartWritten,
      Phase.ObjectForced,
      Phase.ObjectInstalled,
      Phase.HeadPartWritten,
      Phase.HeadForced,
      Phase.HeadReplaced,
      Phase.BeforePublication,
      Phase.Published
    )
  do
    test(s"cancellation during $phase is masked through one coherent publication") {
      future(directory.use { root =>
        val entered = new CountDownLatch(1)
        val continue = new CountDownLatch(1)
        val fault: Phase => Unit = p =>
          if p == phase then
            entered.countDown()
            if !continue.await(20, TimeUnit.SECONDS) then
              throw new RuntimeException("test release timed out")
        for
          _ <- seed(root)
          _ <- NioReplayStore.open[IO](root, fault = fault).use { store =>
            (for
              before <- store.snapshot
              // The call may finish as terminal success while cancellation is masked. An explicit
              // caller continuation supplies the cancelable boundary whose outcome we assert.
              fiber <- store.commit(before.version, Vector(raw(a))).flatTap(_ => IO.never).start
              _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
              requested <- Deferred[IO, Unit]
              finished <- Deferred[IO, Unit]
              cancel <- (requested.complete(()) *> fiber.cancel
                .guarantee(finished.complete(()).void)).start
              _ <- requested.get
              _ <- IO.sleep(25.millis)
              pending <- finished.tryGet
              _ = assertEquals(pending, None)
              _ <- IO(continue.countDown())
              _ <- cancel.joinWithNever
              outcome <- fiber.join
              _ = assert(outcome.isCanceled)
              current <- store.snapshot
              _ = assertEquals(current.state.revision.number, BigInt(1))
              _ = assertEquals(current.state.feesSinceCheckpoint, BigInt(167041))
              stale <- store.commit(before.version, Vector(raw(a)))
              _ = assertEquals(stale, Left(Rejection.StaleVersion))
            yield ()).guarantee(IO(continue.countDown()))
          }
          _ <- NioReplayStore
            .open[IO](root)
            .use(_.snapshot.map(s => assertEquals(s.state.feesSinceCheckpoint, BigInt(167041))))
        yield ()
      })
    }

  test("a reader waits at publication and cannot observe mixed head/state/fees") {
    future(directory.use { root =>
      val entered = new CountDownLatch(1)
      val continue = new CountDownLatch(1)
      for
        _ <- seed(root)
        _ <- NioReplayStore
          .open[IO](
            root,
            fault = p =>
              if p == Phase.HeadReplaced then
                entered.countDown()
                if !continue.await(20, TimeUnit.SECONDS) then
                  throw new RuntimeException("test release timed out")
          )
          .use { store =>
            (for
              writer <- applyA(store).start
              _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
              started <- Deferred[IO, Unit]
              completed <- Deferred[IO, Snapshot]
              reader <- (started.complete(()) *> store.snapshot
                .flatTap(s => completed.complete(s))).start
              _ <- started.get
              _ <- IO.sleep(25.millis)
              pending <- completed.tryGet
              _ = assertEquals(pending, None)
              _ <- IO(continue.countDown())
              written <- writer.joinWithNever
              read <- reader.joinWithNever
              _ = assertEquals(read.version.head, written.version.head)
              _ = sameContent(read.state, written.state)
              _ = assertEquals(read.undo.get.transitionId, written.undo.get.transitionId)
            yield ()).guarantee(IO(continue.countDown()))
          }
      yield ()
    })
  }

  test("selected missing or corrupt head fails closed even with complete older objects") {
    future(Vector(false, true).traverse_ { remove =>
      directory.use { root =>
        for
          _ <- NioReplayStore.create[IO](root, checkpoint).use(applyA)
          _ <- IO.blocking {
            if remove then Files.delete(root.resolve("head"))
            else Files.write(root.resolve("head"), Array[Byte](0, 1, 2))
          }
          result <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
          _ = errorKind(result, StorageFailure.Corrupt)
        yield ()
      }
    })
  }

  test("selected immutable corruption is not repaired or bypassed by fallback") {
    future(directory.use { root =>
      for
        applied <- NioReplayStore.create[IO](root, checkpoint).use(applyA)
        target = root.resolve("objects").resolve(s"${applied.version.head.hex}.bin")
        _ <- IO.blocking {
          val bytes = Files.readAllBytes(target)
          bytes(bytes.length - 1) = (bytes.last ^ 1).toByte
          Files.write(target, bytes)
        }
        result <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
        _ = errorKind(result, StorageFailure.Corrupt)
      yield ()
    })
  }

  test("rehashed false state, fee, revision, output and undo summaries cannot authorize success") {
    future(Vector("state", "fee", "revision", "output", "undo", "transaction").traverse_ { field =>
      directory.use { root =>
        for
          applied <- NioReplayStore.create[IO](root, checkpoint).use(applyA)
          _ <- IO.blocking {
            val head = ReplayCodec.readHead(Files.readAllBytes(root.resolve("head")))
            val record = ReplayCodec.readRecord(
              Files.readAllBytes(
                root.resolve("objects").resolve(s"${applied.version.head.hex}.bin")
              )
            )
            val bad = field match
              case "state"    => record.copy(stateId = Bytes(Vector.fill(32)(0.toByte)))
              case "fee"      => record.copy(fees = record.fees + 1)
              case "revision" => record.copy(revision = record.revision + 1)
              case "output"   => record.copy(outputMap = raw("initial-utxo.cbor"))
              case "undo"     => record.copy(top = None)
              case _ =>
                record.copy(operation =
                  ReplayCodec.Operation.Apply(Vector(raw("value-conservation-2.cbor")))
                )
            val bytes = ReplayCodec.record(bad)
            val hash = ReplayCodec.sha(bytes)
            Files.write(root.resolve("objects").resolve(s"${hash.hex}.bin"), bytes)
            Files.write(root.resolve("head"), ReplayCodec.head(head.copy(tip = hash)))
          }
          result <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
          _ = errorKind(result, StorageFailure.Corrupt)
        yield ()
      }
    })
  }

  test("journal, recovery, evidence and directory limits fail without publishing") {
    future(
      Vector(
        Limits(maxHistory = 1),
        Limits(maxTransactions = 1),
        Limits(maxRecoveryBytes = 1),
        Limits(maxEvidenceBytes = 1),
        Limits(maxStoredBytes = 1),
        Limits(maxFiles = 3)
      ).traverse_ { limits =>
        directory.use { root =>
          for
            _ <- seed(root)
            result <- NioReplayStore
              .open[IO](root, limits)
              .use { store =>
                for
                  before <- store.snapshot
                  applied <- store.commit(before.version, Vector(raw(a)))
                  _ <- applied match
                    case Left(_: Rejection.ResourceLimit) => IO.unit
                    case Right(next) =>
                      store.rollback(next.version, next.undo.get.transitionId).flatMap {
                        case Left(_: Rejection.ResourceLimit) => IO.unit
                        case Right(restored) =>
                          store.commit(restored.version, Vector(raw(b))).map(r => assert(r.isLeft))
                        case other => IO(fail(s"unexpected $other"))
                      }
                    case other => IO(fail(s"unexpected $other"))
                yield ()
              }
              .attempt
            _ = result.left.foreach(e => assert(e.isInstanceOf[StorageException]))
            _ <- NioReplayStore.open[IO](root).use(_.snapshot.void)
          yield ()
        }
      }
    )
  }

  test("recognized abandoned files consume quota but never become the selected head") {
    future(directory.use { root =>
      for
        _ <- seed(root)
        _ <- IO.blocking(Files.write(root.resolve("staging/object.tmp"), new Array[Byte](4096)))
        before <- NioReplayStore.open[IO](root).use(_.snapshot)
        _ = assertEquals(before.state.revision.number, BigInt(0))
        result <- NioReplayStore
          .open[IO](root, Limits(maxStoredBytes = 4096))
          .use(_.snapshot)
          .attempt
        _ = errorKind(result, StorageFailure.Corrupt)
      yield ()
    })
  }

  test("unsafe paths, unrelated entries and nonregular selected files are rejected") {
    future(directory.use { base =>
      val root = base.resolve("store")
      for
        _ <- seed(root)
        _ <- IO.blocking(Files.createSymbolicLink(base.resolve("alias"), root))
        link <- NioReplayStore.open[IO](base.resolve("alias")).use(_.snapshot).attempt
        _ = errorKind(link, StorageFailure.InvalidDirectory)
        _ <- IO.blocking(Files.write(root.resolve("surprise"), Array[Byte](1)))
        unrelated <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
        _ = errorKind(unrelated, StorageFailure.InvalidDirectory)
      yield ()
    })
  }

  test("cleanup failure is suppressed under the primary failed write") {
    val original = new java.io.IOException("primary force")
    val cleanup = new java.io.IOException("cleanup")
    val resource = new AutoCloseable:
      def close(): Unit = throw cleanup
    val result = intercept[java.io.IOException] {
      NioReplayStore.closing(resource)(_ => throw original)
    }
    assert(result eq original)
    assertEquals(result.getSuppressed.toVector, Vector(cleanup))
  }

  test("missing referenced immutable object fails instead of selecting older history") {
    future(directory.use { root =>
      for
        applied <- NioReplayStore.create[IO](root, checkpoint).use(applyA)
        _ <- IO.blocking(
          Files.delete(root.resolve("objects").resolve(s"${applied.version.head.hex}.bin"))
        )
        result <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
        _ = errorKind(result, StorageFailure.Corrupt)
      yield ()
    })
  }

  test("self-suppression cannot replace a primary failure") {
    val original = new java.io.IOException("shared primary")
    val resource = new AutoCloseable:
      def close(): Unit = throw original
    val result = intercept[java.io.IOException] {
      NioReplayStore.closing(resource)(_ => throw original)
    }
    assert(result eq original)
    assertEquals(result.getSuppressed.toVector, Vector.empty)
  }

  test("a canceled writer waiting for publication performs no extra commit") {
    future(directory.use { root =>
      val entered = new CountDownLatch(1)
      val continue = new CountDownLatch(1)
      for
        _ <- seed(root)
        _ <- NioReplayStore
          .open[IO](
            root,
            fault = p =>
              if p == Phase.HeadReplaced then
                entered.countDown()
                if !continue.await(20, TimeUnit.SECONDS) then
                  throw new RuntimeException("test release timed out")
          )
          .use { store =>
            (for
              before <- store.snapshot
              first <- store.commit(before.version, Vector(raw(a))).start
              _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
              started <- Deferred[IO, Unit]
              waiting <- (started
                .complete(()) *> store.commit(before.version, Vector(raw(b)))).start
              _ <- started.get
              _ <- waiting.cancel
              canceled <- waiting.join
              _ = assert(canceled.isCanceled)
              _ <- IO(continue.countDown())
              _ <- first.joinWithNever
              after <- store.snapshot
              _ = assertEquals(after.historyLength, 1)
              _ = assertEquals(after.state.feesSinceCheckpoint, BigInt(167041))
            yield ()).guarantee(IO(continue.countDown()))
          }
      yield ()
    })
  }

  for phase <- Phase.values do
    test(s"initialization failure at $phase leaves either no selected head or complete genesis") {
      future(directory.use { root =>
        val injected = new java.io.IOException(s"initialization $phase")
        for
          result <- NioReplayStore
            .create[IO](root, checkpoint, fault = p => if p == phase then throw injected)
            .use(_.snapshot)
            .attempt
          _ = assertEquals(result.left.toOption, Some(injected))
          reopened <- NioReplayStore.open[IO](root).use(_.snapshot).attempt
          _ =
            if committed(phase) then
              val state = reopened.fold(e => throw e, identity)
              assertEquals(state.state.revision.number, BigInt(0))
              assertEquals(state.state.feesSinceCheckpoint, BigInt(0))
            else errorKind(reopened, StorageFailure.Corrupt)
        yield ()
      })
    }

  test("remaining recovery-byte budget rejects referenced files before reading or hashing") {
    future(directory.use { root =>
      for
        applied <- NioReplayStore.create[IO](root, checkpoint).use(applyA)
        limit <- IO.blocking {
          val selected = ReplayCodec.readHead(Files.readAllBytes(root.resolve("head")))
          val seedSize = Files.size(root.resolve("objects").resolve(s"${selected.seed.hex}.bin"))
          val objectPath = root.resolve("objects").resolve(s"${applied.version.head.hex}.bin")
          val bytes = Files.readAllBytes(objectPath)
          Files.write(objectPath, bytes ++ new Array[Byte](1048576))
          seedSize + 16L
        }
        result <- NioReplayStore
          .open[IO](root, Limits(maxRecoveryBytes = limit))
          .use(_.snapshot)
          .attempt
        _ = errorKind(result, StorageFailure.Corrupt)
        _ = assert(result.left.toOption.get.getMessage.contains("file shape or byte bound"))
      yield ()
    })
  }

  test("a terminal masked commit may acknowledge success while cancellation waits") {
    future(directory.use { root =>
      val entered = new CountDownLatch(1)
      val continue = new CountDownLatch(1)
      for
        _ <- seed(root)
        _ <- NioReplayStore
          .open[IO](
            root,
            fault = p =>
              if p == Phase.HeadReplaced then
                entered.countDown()
                if !continue.await(20, TimeUnit.SECONDS) then
                  throw new RuntimeException("test release timed out")
          )
          .use { store =>
            (for
              before <- store.snapshot
              fiber <- store.commit(before.version, Vector(raw(a))).start
              _ <- IO.blocking(assert(entered.await(10, TimeUnit.SECONDS)))
              requested <- Deferred[IO, Unit]
              finished <- Deferred[IO, Unit]
              cancel <- (requested.complete(()) *> fiber.cancel
                .guarantee(finished.complete(()).void)).start
              _ <- requested.get
              _ <- IO.sleep(25.millis)
              pending <- finished.tryGet
              _ = assertEquals(pending, None)
              _ <- IO(continue.countDown())
              _ <- cancel.joinWithNever
              outcome <- fiber.join
              _ <- IO.println(s"terminal-cancel-outcome=$outcome")
              _ <- outcome match
                case cats.effect.Outcome.Succeeded(result) =>
                  result.flatMap(good).map(s => assertEquals(s.state.revision.number, BigInt(1)))
                case cats.effect.Outcome.Canceled()     => IO.unit
                case cats.effect.Outcome.Errored(error) => IO.raiseError(error)
              after <- store.snapshot
              _ = assertEquals(after.state.revision.number, BigInt(1))
              _ = assertEquals(after.state.feesSinceCheckpoint, BigInt(167041))
            yield ()).guarantee(IO(continue.countDown()))
          }
        _ <- NioReplayStore
          .open[IO](root)
          .use(_.snapshot.map(s => assertEquals(s.state.feesSinceCheckpoint, BigInt(167041))))
      yield ()
    })
  }
