// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.io.IOException
import scala.concurrent.duration.*
import lab.cbor.Bytes
import scala.jdk.CollectionConverters.*

class ValidatorTransitionsSuite extends munit.FunSuite:
  import ValidatorTransitions.*
  private def get[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def token(view: View): ValidatedCheckpoint.Token = view.confirmation.tokenOption.get
  private def publish(backend: Backend[IO], block: SequenceInput.Block): IO[View] =
    for
      before <- backend.snapshot
      prepared <- backend.prepare(before, block).map(get(_))
      next <- backend.publish(prepared).map(get(_))
    yield next
  private def temporary =
    Resource.make(IO.blocking(Files.createTempDirectory("validator-transitions-").toRealPath())) {
      directory =>
        IO.blocking {
          require(
            directory.getParent == Path
              .of(System.getProperty("java.io.tmpdir"))
              .toRealPath() && directory.getFileName.toString.startsWith("validator-transitions-")
          )
          val paths = Files.walk(directory)
          try
            paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
              require(path.toAbsolutePath.normalize().startsWith(directory))
              Files.delete(path)
            }
          finally paths.close()
        }
    }

  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val root = Path.of(location)
    def context = get(SequenceInput.load(root))
    def blocks = get(
      CoherentSequenceCommand.captures(
        Bytes.fromArray(Files.readAllBytes(root.resolve("scala-sequence-capture.md")))
      )
    ).map(o => get(SequenceInput.block(o)))

    test("views and prepared values cannot cross volatile/durable sessions") {
      temporary
        .use { directory =>
          (for _ <- inMemory[IO](context).use { memory =>
              durableCreate[IO](directory, context, 8, 2.seconds, _ => IO.unit).use { disk =>
                for
                  m <- memory.snapshot
                  d <- disk.snapshot
                  candidate <- memory.prepare(m, blocks.head).map(get(_))
                  foreignView <- disk.prepare(m, blocks.head)
                  foreignCandidate <- disk.publish(candidate)
                  foreignRollback <- disk.rollbackTo(m, m.state.acquisition.anchor)
                  after <- disk.snapshot
                yield
                  assertEquals(m.confirmation, Confirmation.Volatile)
                  assertEquals(foreignView, Left(Rejection.ForeignSession))
                  assertEquals(foreignCandidate, Left(Rejection.ForeignSession))
                  assertEquals(foreignRollback, Left(Rejection.ForeignSession))
                  assert(after eq d)
              }
            }
          yield ())
        }
        .unsafeToFuture()
    }

    test(
      "durable reopen is loaded-verified; no-op keeps token and classification; rollback/reapply ack"
    ) {
      temporary
        .use { directory =>
          (for
            saved <- durableCreate[IO](directory, context, 8, 2.seconds, _ => IO.unit).use {
              backend =>
                blocks.traverse(block => publish(backend, block)).map(_.last)
            }
            _ <- durableResume[IO](
              directory,
              context.id,
              token(saved),
              20.seconds,
              2.seconds,
              _ => IO.unit
            ).use { backend =>
              for
                loaded <- backend.snapshot
                noOp <- backend.rollbackTo(loaded, loaded.state.acquisition.tip).map(get(_))
                rolled <- backend.rollbackTo(noOp, noOp.state.acquisition.anchor).map(get(_))
                reapplied <- blocks.traverse(block => publish(backend, block)).map(_.last)
              yield
                assertEquals(loaded.confirmation, Confirmation.LoadedVerified(token(saved)))
                assert(noOp eq loaded)
                assertEquals(rolled.state.revision, saved.state.revision + blocks.size)
                assertEquals(token(rolled).generation, token(saved).generation + 1)
                assertEquals(rolled.confirmation, Confirmation.Acknowledged(token(rolled)))
                assertEquals(reapplied.state.id, saved.state.id)
                assertEquals(reapplied.state.ledger.outputMap, saved.state.ledger.outputMap)
                assertEquals(reapplied.state.ledger.fees, saved.state.ledger.fees)
            }
          yield ())
        }
        .unsafeToFuture()
    }

    test("concurrent candidates serialize publication/token update and reject stale capabilities") {
      temporary
        .use { directory =>
          (for _ <- durableCreate[IO](directory, context, 8, 2.seconds, _ => IO.unit).use {
              backend =>
                for
                  before <- backend.snapshot
                  p1 <- backend.prepare(before, blocks.head).map(get(_))
                  p2 <- backend.prepare(before, blocks.head).map(get(_))
                  both <- (backend.publish(p1), backend.publish(p2)).parTupled
                  after <- backend.snapshot
                  staleRollback <- backend.rollbackTo(before, before.state.acquisition.anchor)
                  cached <- backend.lastConfirmed
                yield
                  assertEquals(Vector(both._1, both._2).count(_.isRight), 1)
                  assertEquals(Vector(both._1, both._2).count(_ == Left(Rejection.StaleView)), 1)
                  assertEquals(staleRollback, Left(Rejection.StaleView))
                  assertEquals(token(after).generation, token(before).generation + 1)
                  assertEquals(after.state.revision, before.state.revision + 1)
                  assertEquals(cached.confirmation, after.confirmation)
            }
          yield ())
        }
        .unsafeToFuture()
    }

    Vector(CoherentSequence.DurablePhase.BeforeDisk, CoherentSequence.DurablePhase.AfterDisk)
      .foreach { phase =>
        test(
          s"$phase exception terminates without querying poisoned runtime or advancing acknowledgement cache"
        ) {
          temporary
            .use { directory =>
              (for
                armed <- Ref.of[IO, Boolean](false)
                _ <- durableCreateObserved[IO](
                  directory,
                  context,
                  2.seconds,
                  _ => IO.unit,
                  NioValidatedCheckpointStore.NoFaults,
                  observed =>
                    armed.get.flatMap(enabled =>
                      if enabled && observed == phase then
                        IO.raiseError(new IOException("scripted storage boundary"))
                      else IO.unit
                    )
                ).use { backend =>
                  for
                    before <- backend.snapshot
                    bytesBefore <- IO.blocking(
                      Files.readAllBytes(directory.resolve("validated.bin")).toVector
                    )
                    prepared <- backend.prepare(before, blocks.head).map(get(_))
                    _ <- armed.set(true)
                    result <- backend.publish(prepared).attempt
                    cached <- backend.lastConfirmed
                    snapshot <- backend.snapshot.attempt
                    retried <- backend.publish(prepared).attempt
                    bytesAfter <- IO.blocking(
                      Files.readAllBytes(directory.resolve("validated.bin")).toVector
                    )
                  yield
                    val failure = result.swap.toOption.get.asInstanceOf[StorageFailure]
                    assert(failure.potentiallyOlderThanDisk)
                    assert(failure.lastConfirmed eq cached)
                    assertEquals(cached.confirmation, before.confirmation)
                    assertEquals(cached.state.id, before.state.id)
                    assert(snapshot.swap.toOption.get eq failure)
                    assert(retried.swap.toOption.get eq failure)
                    assertEquals(
                      bytesAfter != bytesBefore,
                      phase == CoherentSequence.DurablePhase.AfterDisk
                    )
                }
              yield ())
            }
            .unsafeToFuture()
        }
      }

    test(
      "cancelled recorder publication terminates with cached old acknowledgement and unchanged disk"
    ) {
      temporary
        .use { directory =>
          (for
            entered <- Deferred[IO, Unit]
            _ <- durableCreate[IO](
              directory,
              context,
              8,
              2.seconds,
              pending =>
                if pending.previous.isEmpty then IO.unit else entered.complete(()).void *> IO.never
            ).use { backend =>
              for
                before <- backend.snapshot
                raw <- IO.blocking(Files.readAllBytes(directory.resolve("validated.bin")).toVector)
                prepared <- backend.prepare(before, blocks.head).map(get(_))
                fiber <- backend.publish(prepared).start
                _ <- entered.get
                _ <- fiber.cancel.timeout(3.seconds)
                cached <- backend.lastConfirmed
                terminal <- backend.snapshot.attempt
                after <- IO.blocking(
                  Files.readAllBytes(directory.resolve("validated.bin")).toVector
                )
              yield
                assertEquals(cached.confirmation, before.confirmation)
                assert(terminal.swap.toOption.get.isInstanceOf[StorageFailure])
                assertEquals(after, raw)
            }
          yield ())
        }
        .unsafeToFuture()
    }

    test(
      "cancellation after disk installation reports old confirmation and forbids fresh queries"
    ) {
      temporary
        .use { directory =>
          for
            armed <- Ref.of[IO, Boolean](false)
            entered <- Deferred[IO, Unit]
            proceed <- Deferred[IO, Unit]
            _ <- durableCreateObserved[IO](
              directory,
              context,
              2.seconds,
              _ => IO.unit,
              NioValidatedCheckpointStore.NoFaults,
              phase =>
                armed.get.flatMap(enabled =>
                  if enabled && phase == CoherentSequence.DurablePhase.AfterDisk then
                    entered.complete(()).void *> proceed.get
                  else IO.unit
                )
            ).use { backend =>
              (for
                before <- backend.snapshot
                oldBytes <- IO.blocking(
                  Files.readAllBytes(directory.resolve("validated.bin")).toVector
                )
                prepared <- backend.prepare(before, blocks.head).map(get(_))
                _ <- armed.set(true)
                publication <- backend.publish(prepared).start
                _ <- entered.get
                cancellation <- publication.cancel.start
                _ <- IO.sleep(20.millis)
                _ <- proceed.complete(())
                _ <- cancellation.joinWithNever.timeout(3.seconds)
                result <- publication.join
                cached <- backend.lastConfirmed
                snapshot <- backend.snapshot.attempt
                newBytes <- IO.blocking(
                  Files.readAllBytes(directory.resolve("validated.bin")).toVector
                )
              yield
                assert(result.isCanceled)
                assertEquals(cached.confirmation, before.confirmation)
                assertEquals(cached.state.id, before.state.id)
                assert(newBytes != oldBytes)
                val failure = snapshot.swap.toOption.get.asInstanceOf[StorageFailure]
                assert(failure.potentiallyOlderThanDisk)
                assert(failure.lastConfirmed eq cached)
              ).guarantee(proceed.complete(()).void)
            }
          yield ()
        }
        .unsafeToFuture()
    }

    test("resource close waits for publication and keeps only the returned acknowledgement") {
      temporary
        .use { directory =>
          (for
            entered <- Deferred[IO, Unit]
            proceed <- Deferred[IO, Unit]
            released <- Deferred[IO, Unit]
            allocated <- durableCreate[IO](
              directory,
              context,
              8,
              3.seconds,
              p => if p.previous.isEmpty then IO.unit else entered.complete(()).void *> proceed.get
            ).allocated
            (backend, release) = allocated
            _ <- (for
              before <- backend.snapshot
              prepared <- backend.prepare(before, blocks.head).map(get(_))
              publication <- backend.publish(prepared).start
              _ <- entered.get
              closing <- release.guarantee(released.complete(()).void).start
              _ <- IO.cede
              early <- released.tryGet
              _ <- proceed.complete(())
              published <- publication.joinWithNever.map(get(_))
              _ <- closing.joinWithNever
              cached <- backend.lastConfirmed
              closed <- backend.snapshot.attempt
            yield
              assertEquals(early, None)
              assertEquals(cached.confirmation, published.confirmation)
              assertEquals(token(published).generation, token(before).generation + 1)
              assert(closed.isLeft)
            ).guarantee(proceed.complete(()).void *> release)
          yield ())
        }
        .unsafeToFuture()
    }
  }
