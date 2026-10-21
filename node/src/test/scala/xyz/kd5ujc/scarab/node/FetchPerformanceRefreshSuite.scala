// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Semaphore
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.genesis.PicosClock
import xyz.kd5ujc.scarab.network.selection.{FetchGsv, FetchMode, GenesisBlockFetch, Gsm}
import scala.concurrent.duration.*

class FetchPerformanceRefreshSuite extends FailsOnUncaught:
  private def header(n: Int): Header = Header.Byron(
    Bytes.empty,
    Bytes(Vector(n.toByte)),
    false,
    0,
    n,
    21600,
    n,
    Some(Bytes(Vector((n - 1).toByte)))
  )

  test(
    "a candidate trigger uses fresh KeepAlive samples while headers and incumbent remain blocked"
  ) {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO]().flatMap { coordinator =>
        (coordinator.session(0), coordinator.session(1)).tupled.use { (incumbent, waiting) =>
          val incumbentModel = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 5)
          val fresh = FetchGsv.fromSample(
            1000 * FetchGsv.PicosPerSecond,
            1000 * FetchGsv.PicosPerSecond + FetchGsv.PicosPerSecond / 100
          )
          for
            measured <- Ref.of[IO, FetchGsv.PeerGsv](FetchGsv.default)
            callbacks <- Ref.of[IO, Int](0)
            headerCredit <- Semaphore[IO](0)
            headerProgress <- Ref.of[IO, Int](0)
            admitted <- Deferred[IO, Unit]
            _ <- incumbent.announce(Some(header(1)), incumbentModel)
            _ <- waiting.announce(Some(header(2)), FetchGsv.default)
            _ <- incumbent.reserve(Vector(header(1)), FetchMode.BulkSync).use { held =>
              IO(assertEquals(held.headers, Vector(header(1)))) *>
                (headerCredit.permit.use(_ => headerProgress.update(_ + 1))).background.use { _ =>
                  Peer
                    .refreshFetchPerformance(
                      measured.get,
                      sample => callbacks.update(_ + 1) *> waiting.updatePerformance(sample)
                    )
                    .background
                    .use { _ =>
                      waiting
                        .reserve(Vector(header(2)), FetchMode.BulkSync)
                        .use(allocation =>
                          IO(assertEquals(allocation.headers, Vector(header(2)))) *>
                            admitted.complete(()).void
                        )
                        .background
                        .use { _ =>
                          for
                            _ <- IO.sleep(50.millis)
                            before <- admitted.tryGet
                            firstCallbacks <- callbacks.get
                            _ <- IO(assertEquals(before, None))
                            _ <- IO(assertEquals(firstCallbacks, 1))
                            _ <- measured.set(fresh)
                            _ <- IO.sleep(50.millis)
                            sampled <- admitted.tryGet
                            count <- callbacks.get
                            // State.hs:396–425: GSV is non-trigger state in both fetch modes.
                            _ <- IO(assertEquals(sampled, None))
                            _ <- IO(assertEquals(count, 2))
                            // A new candidate head is a real fingerprint change. The blocked
                            // producer and incumbent do not need to make progress for this
                            // decision to use the sample collected by the independent poller.
                            _ <- waiting.announce(Some(header(3)), FetchGsv.default)
                            _ <- admitted.get
                            after <- admitted.tryGet
                            produced <- headerProgress.get
                            _ <- IO(assertEquals(after, Some(())))
                            _ <- IO(assertEquals(produced, 0))
                          yield ()
                        }
                    }
                }
            }
          yield ()
        }
      }
    }
  }

  Vector(0.seconds -> 10.millis, 10.seconds -> 10.seconds).foreach { (grace, expected) =>
    test(s"Praos decisions use sampled estimates at the native $grace grace/rate boundary") {
      TestControl.executeEmbed {
        for
          coordinator <- FetchCoordinator.create[IO]()
          _ <- Option
            .when(grace > Duration.Zero)(
              FetchCoordinator.GenesisControl[IO](
                IO.pure(
                  FetchCoordinator.GenesisContext(
                    Gsm.Phase.CaughtUp,
                    0,
                    GenesisBlockFetch.Starvation.Ongoing
                  )
                ),
                _ => IO.unit
              )
            )
            .traverse_(coordinator.installGenesis)
          _ <- (coordinator.session(0), coordinator.session(1)).tupled.use { (incumbent, waiting) =>
            val incumbentModel = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 5)
            val fresh = FetchGsv.fromSample(
              1000 * FetchGsv.PicosPerSecond,
              1000 * FetchGsv.PicosPerSecond + FetchGsv.PicosPerSecond / 100
            )
            for
              admitted <- Deferred[IO, FiniteDuration]
              _ <- incumbent.announce(Some(header(1)), incumbentModel)
              _ <- waiting.announce(Some(header(2)), FetchGsv.default)
              _ <- incumbent.reserve(Vector(header(1)), FetchMode.BulkSync).use { _ =>
                waiting
                  .reserve(Vector(header(2)), FetchMode.BulkSync)
                  .use { allocation =>
                    IO(assertEquals(allocation.headers, Vector(header(2)))) *>
                      IO.monotonic.flatMap(admitted.complete).void
                  }
                  .background
                  .use { _ =>
                    coordinator
                      .watchDecisions(
                        BigInt(grace.toNanos) * 1000,
                        Some(PicosClock.system[IO])
                      )
                      .background
                      .use { _ =>
                        for
                          _ <- IO.sleep(5.millis)
                          _ <- waiting.updatePerformance(fresh)
                          _ <- IO.sleep(expected - 6.millis)
                          early <- admitted.tryGet
                          _ <- IO(assertEquals(early, None))
                          at <- admitted.get
                          _ <- IO(assertEquals(at, expected))
                        yield ()
                      }
                  }
              }
            yield ()
          }
        yield ()
      }
    }
  }

  test("a delayed header announcement cannot replace a newer measured performance model") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO]().flatMap { coordinator =>
        (coordinator.session(0), coordinator.session(1)).tupled.use { (incumbent, waiting) =>
          val incumbentModel = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 5)
          val fresh = FetchGsv.fromSample(
            1000 * FetchGsv.PicosPerSecond,
            1000 * FetchGsv.PicosPerSecond + FetchGsv.PicosPerSecond / 100
          )
          for
            admitted <- Deferred[IO, Unit]
            _ <- incumbent.announce(Some(header(1)), incumbentModel)
            _ <- waiting.announce(Some(header(2)), FetchGsv.default)
            _ <- incumbent.reserve(Vector(header(1)), FetchMode.BulkSync).use { _ =>
              waiting.updatePerformance(fresh) *>
                waiting.announce(Some(header(2)), FetchGsv.default) *>
                waiting
                  .reserve(Vector(header(2)), FetchMode.BulkSync)
                  .use(allocation =>
                    IO(assertEquals(allocation.headers, Vector(header(2)))) *>
                      admitted.complete(()).void
                  )
                  .background
                  .use { _ =>
                    IO.sleep(50.millis) *> admitted.tryGet
                      .flatMap(result => IO(assertEquals(result, Some(()))))
                  }
            }
          yield ()
        }
      }
    }
  }
