// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.genesis.PicosClock
import xyz.kd5ujc.scarab.network.selection.{FetchGsv, GenesisBlockFetch, Gsm}
import scala.concurrent.duration.*

import FetchTesting.*

class FetchPerformanceRefreshSuite extends FailsOnUncaught:
  private val incumbentModel = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 5)
  private val fresh = FetchGsv.fromSample(
    1000 * FetchGsv.PicosPerSecond,
    1000 * FetchGsv.PicosPerSecond + FetchGsv.PicosPerSecond / 100
  )

  test("a candidate trigger uses fresh KeepAlive samples while the incumbent remains blocked") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO]().flatMap { coordinator =>
        (coordinator.session(0), coordinator.session(1)).tupled.use { (incumbent, waiting) =>
          for
            measured <- Ref.of[IO, FetchGsv.PeerGsv](FetchGsv.default)
            callbacks <- Ref.of[IO, Int](0)
            admitted <- Deferred[IO, Vector[xyz.kd5ujc.scarab.ledger.Header]]
            _ <- incumbent.follows(Vector(header(1)), incumbentModel)
            _ <- coordinator.step
            held <- incumbent.requestedHeaders
            _ <- waiting.follows(Vector(header(2)))
            _ <- Peer
              .refreshFetchPerformance(
                measured.get,
                sample => callbacks.update(_ + 1) *> waiting.updatePerformance(sample)
              )
              .background
              .use { _ =>
                // A grace far longer than the test: only a fingerprint change decides.
                coordinator
                  .watchDecisions(BigInt(3600) * 1000000000000L, Some(PicosClock.system[IO]))
                  .background
                  .use { _ =>
                    waiting
                      .acquire(IO.never)
                      .flatMap(_.traverse_(a => admitted.complete(a.headers).void))
                      .background
                      .use { _ =>
                        for
                          _ <- IO.sleep(50.millis)
                          before <- admitted.tryGet
                          firstCallbacks <- callbacks.get
                          _ <- measured.set(fresh)
                          _ <- IO.sleep(50.millis)
                          sampled <- admitted.tryGet
                          count <- callbacks.get
                          // A new candidate head is a real fingerprint change; the decision
                          // reads the sample the independent poller collected.
                          _ <- waiting.follows(Vector(header(2), header(3)))
                          headers <- admitted.get
                        yield
                          assertEquals(held, Vector(header(1)))
                          assertEquals(before, None)
                          assertEquals(firstCallbacks, 1)
                          // State.hs:419–425: GSV is non-trigger state in both fetch modes.
                          assertEquals(sampled, None)
                          assertEquals(count, 2)
                          assertEquals(headers, Vector(header(2), header(3)))
                      }
                  }
              }
          yield ()
        }
      }
    }
  }

  // The first iteration decides at once, then sleeps out the 10ms Praos interval; the second
  // registers its grace at its start (State.hs:100–107, 150), so a 10s grace expires at 10.01s.
  Vector(0.seconds -> 10.millis, 10.seconds -> 10010.millis).foreach { (grace, expected) =>
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
            for
              admitted <- Deferred[IO, FiniteDuration]
              _ <- incumbent.follows(Vector(header(1)), incumbentModel)
              _ <- coordinator.step
              _ <- waiting.follows(Vector(header(2)))
              at <- waiting
                .acquire(IO.never)
                .flatMap(a =>
                  IO(assertEquals(a.map(_.headers), Some(Vector(header(2))))) *>
                    IO.monotonic.flatMap(admitted.complete).void
                )
                .background
                .use { _ =>
                  coordinator
                    .watchDecisions(BigInt(grace.toNanos) * 1000, Some(PicosClock.system[IO]))
                    .background
                    .use { _ =>
                      for
                        _ <- IO.sleep(5.millis)
                        _ <- waiting.updatePerformance(fresh)
                        _ <- IO.sleep(expected - 6.millis)
                        early <- admitted.tryGet
                        _ <- IO(assertEquals(early, None))
                        at <- admitted.get
                      yield at
                    }
                }
            yield assertEquals(at, expected)
          }
        yield ()
      }
    }
  }

  test("a delayed header announcement cannot replace a newer measured performance model") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      (coordinator.session(0), coordinator.session(1)).tupled.use { (incumbent, waiting) =>
        for
          _ <- incumbent.follows(Vector(header(1)), incumbentModel)
          _ <- coordinator.step
          _ <- waiting.updatePerformance(fresh)
          _ <- waiting.follows(Vector(header(2)))
          _ <- coordinator.step
          admitted <- waiting.requestedHeaders
        yield assertEquals(admitted, Vector(header(2)))
      }
    }
  }
