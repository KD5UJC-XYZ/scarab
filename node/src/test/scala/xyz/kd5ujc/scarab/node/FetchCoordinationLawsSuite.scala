// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, FiberIO, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchGsv, FetchMode}
import scala.concurrent.duration.*

/** Observable reservation, cache publication and flow-control laws under controlled interleavings.
  */
class FetchCoordinationLawsSuite extends FailsOnUncaught:
  private def header(n: Int): Header = Header.Byron(
    Bytes.empty,
    Bytes.wrap(Array(n.toByte)),
    false,
    0,
    n,
    21600,
    n,
    Some(Bytes.wrap(Array((n - 1).toByte)))
  )

  private def running[A](effect: IO[A]): Resource[IO, FiberIO[A]] =
    Resource.make(effect.start)(_.cancel)

  test("a granted reservation prevents a borrower racing ahead of the pool claim") {
    TestControl.executeEmbed {
      for
        coordinator <- FetchCoordinator.create[IO]()
        pool <- BlockPool.create[IO]
        _ <- (coordinator.session(0), coordinator.session(1), pool.session, pool.session).tupled
          .use { (source, borrower, sourceClaims, borrowedClaims) =>
            val h = header(1)
            val body = Bytes.wrap(Array[Byte](42))
            for
              granted <- Deferred[IO, Unit]
              publish <- Deferred[IO, Unit]
              borrowed <- Ref.of[IO, Option[Option[Bytes]]](None)
              _ <- running(source.reserve(Vector(h), FetchMode.BulkSync).use { allocation =>
                IO(assertEquals(allocation.headers, Vector(h))) *>
                  granted.complete(()) *> publish.get *>
                  sourceClaims
                    .claim(Vector(h.hash), FetchMode.BulkSync)
                    .flatMap(flags => IO(assertEquals(flags, Vector(true)))) *>
                  sourceClaims.fill(Vector(h.hash -> body))
              }).use { producer =>
                granted.get *> running(
                  borrower.await(h.hash) *> borrowedClaims
                    .take(h.hash)
                    .flatMap(result => borrowed.set(Some(result.map(_.block))))
                ).use { consumer =>
                  for
                    _ <- IO.sleep(1.second)
                    early <- borrowed.get
                    _ = assertEquals(early, None)
                    _ <- publish.complete(()) *> producer.joinWithNever *> consumer.joinWithNever
                    delivered <- borrowed.get
                  yield assertEquals(delivered, Some(Some(body)))
                }
              }
            yield ()
          }
      yield ()
    }
  }

  test("a claimed middle block leaves two distinct request fragments") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO](BlockFetchPolicy.Config(bulkConcurrency = 2)).flatMap {
        coordinator =>
          (coordinator.session(0), coordinator.session(1)).tupled.use {
            (middleSource, outerSource) =>
              val headers = Vector(header(1), header(2), header(3))
              middleSource.announce(Some(header(3)), FetchGsv.default) *>
                outerSource.announce(Some(header(3)), FetchGsv.default) *>
                middleSource.reserve(Vector(header(2)), FetchMode.BulkSync).use { middle =>
                  IO(assertEquals(middle.headers, Vector(header(2)))) *>
                    outerSource.reserve(headers, FetchMode.BulkSync).use { selected =>
                      IO {
                        assertEquals(selected.headers, Vector(header(1), header(3)))
                        assertEquals(selected.requests, 2)
                        assertEquals(selected.remaining, Vector.empty)
                      }
                    }
                }
          }
      }
    }
  }

  test("per-block progress keeps a full window busy until its low watermark is reached") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO](BlockFetchPolicy.Config(maxRequestsPerPeer = 2)).flatMap {
        coordinator =>
          coordinator.session(0).use { session =>
            val measured = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 10)
            val hints = Map(
              header(1).hash -> 100000L,
              header(2).hash -> 96608L,
              header(3).hash -> 196608L,
              header(4).hash -> 1L
            )
            for
              _ <- IO {
                assertEquals(measured.window.lowBytes, 196608L)
                assertEquals(measured.window.highBytes, 393216L)
              }
              _ <- session.announce(Some(header(4)), measured, hints)
              admitted <- Deferred[IO, Vector[Header]]
              seen <- Ref.of[IO, Boolean](false)
              _ <- session
                .reserve(Vector(header(1), header(2), header(3)), FetchMode.BulkSync)
                .use { first =>
                  IO(assertEquals(first.headers.size, 3)) *>
                    running(session.reserve(Vector(header(4)), FetchMode.BulkSync).use { next =>
                      seen.set(true) *> admitted.complete(next.headers)
                    }).use { pending =>
                      for
                        _ <- IO.sleep(1.second)
                        full <- seen.get
                        _ = assert(!full)
                        _ <- session.received(first, header(1).hash)
                        _ <- IO.sleep(1.second)
                        between <- seen.get
                        _ = assert(!between)
                        _ <- session.received(first, header(2).hash)
                        headers <- admitted.get
                        _ <- pending.joinWithNever
                      yield assertEquals(headers, Vector(header(4)))
                    }
                }
            yield ()
          }
      }
    }
  }

  test("batch completion returns request credit while preparation still holds its allocation") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO](BlockFetchPolicy.Config(maxRequestsPerPeer = 1)).flatMap {
        coordinator =>
          coordinator.session(0).use { session =>
            session.reserve(Vector(header(1)), FetchMode.BulkSync).use { previous =>
              for
                _ <- session.received(previous, header(1).hash)
                admitted <- Deferred[IO, Vector[Header]]
                seen <- Ref.of[IO, Boolean](false)
                _ <- running(session.reserve(Vector(header(2)), FetchMode.BulkSync).use { next =>
                  seen.set(true) *> admitted.complete(next.headers)
                }).use { waiting =>
                  for
                    _ <- IO.sleep(1.second)
                    beforeCompletion <- seen.get
                    _ = assert(!beforeCompletion)
                    _ <- session.completed(previous)
                    headers <- admitted.get
                    _ <- waiting.joinWithNever
                  yield assertEquals(headers, Vector(header(2)))
                }
              yield ()
            }
          }
      }
    }
  }

  test(
    "switching modes permits deadline duplication and drains existing work before bulk admission"
  ) {
    TestControl.executeEmbed {
      FetchCoordinator
        .create[IO](BlockFetchPolicy.Config(bulkConcurrency = 1, deadlineConcurrency = 2))
        .flatMap { coordinator =>
          (coordinator.session(0), coordinator.session(1), coordinator.session(2)).tupled.use {
            (a, b, c) =>
              for
                firstGranted <- Deferred[IO, Unit]
                secondGranted <- Deferred[IO, Unit]
                releaseFirst <- Deferred[IO, Unit]
                releaseSecond <- Deferred[IO, Unit]
                thirdGranted <- Deferred[IO, Vector[Header]]
                thirdSeen <- Ref.of[IO, Boolean](false)
                _ <- a.announce(Some(header(1)), FetchGsv.default)
                _ <- b.announce(Some(header(1)), FetchGsv.default)
                _ <- c.announce(Some(header(2)), FetchGsv.default)
                _ <- running(a.reserve(Vector(header(1)), FetchMode.BulkSync).use { allocation =>
                  IO(assertEquals(allocation.headers, Vector(header(1)))) *>
                    firstGranted.complete(()) *> releaseFirst.get
                }).use { first =>
                  firstGranted.get *> b.reserve(Vector(header(1)), FetchMode.BulkSync).use {
                    refused =>
                      IO(assertEquals(refused.headers, Vector.empty))
                  } *> running(b.reserve(Vector(header(1)), FetchMode.Deadline).use { duplicate =>
                    IO(assertEquals(duplicate.headers, Vector(header(1)))) *>
                      secondGranted.complete(()) *> releaseSecond.get
                  }).use { second =>
                    secondGranted.get *> running(
                      c.reserve(Vector(header(2)), FetchMode.BulkSync).use { next =>
                        thirdSeen.set(true) *> thirdGranted.complete(next.headers)
                      }
                    ).use { third =>
                      for
                        _ <- IO.sleep(1.second)
                        whileBoth <- thirdSeen.get
                        _ = assert(!whileBoth)
                        _ <- releaseSecond.complete(()) *> second.joinWithNever
                        _ <- IO.sleep(1.second)
                        whileFirst <- thirdSeen.get
                        _ = assert(!whileFirst)
                        _ <- releaseFirst.complete(()) *> first.joinWithNever
                        selected <- thirdGranted.get
                        _ <- third.joinWithNever
                      yield assertEquals(selected, Vector(header(2)))
                    }
                  }
                }
              yield ()
          }
        }
    }
  }
