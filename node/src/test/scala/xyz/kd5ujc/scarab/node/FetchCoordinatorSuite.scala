// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchGsv, FetchMode}
import scala.concurrent.duration.*

class FetchCoordinatorSuite extends FailsOnUncaught:
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

  test("a nonpreferred peer waits for global bulk credit and wakes after release") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO]().flatMap { coordinator =>
        (coordinator.session(0), coordinator.session(1)).tupled.use { (a, b) =>
          for
            _ <- a.announce(Some(header(1)), FetchGsv.default)
            _ <- b.announce(Some(header(2)), FetchGsv.default)
            held <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            seen <- Ref.of[IO, Vector[Int]](Vector.empty)
            first <- a
              .reserve(Vector(header(1)), FetchMode.BulkSync)
              .use(_ => held.complete(()) *> release.get)
              .start
            _ <- held.get
            second <- b
              .reserve(Vector(header(2)), FetchMode.BulkSync)
              .use(allocation => seen.update(_ ++ allocation.headers.map(_.slot.toInt)))
              .start
            _ <- IO.sleep(1.second)
            before <- seen.get
            _ <- release.complete(())
            _ <- first.joinWithNever *> second.joinWithNever
            after <- seen.get
          yield
            assertEquals(before, Vector.empty)
            assertEquals(after, Vector(2))
        }
      }
    }
  }

  test("preferred-peer handover follows upstream even while an old peer is draining") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO]().flatMap { coordinator =>
        (coordinator.session(1), coordinator.session(0)).tupled.use { (slow, fast) =>
          slow.announce(Some(header(1)), FetchGsv.default) *>
            slow.reserve(Vector(header(1)), FetchMode.BulkSync).use { _ =>
              val measured = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 100)
              fast.announce(Some(header(2)), measured) *>
                fast
                  .reserve(Vector(header(2)), FetchMode.BulkSync)
                  .use(a => IO(assertEquals(a.headers.map(_.slot), Vector(BigInt(2)))))
            }
        }
      }
    }
  }

  test("cancelling a waiting offer releases it without stealing later credit") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO]().flatMap { coordinator =>
        (coordinator.session(0), coordinator.session(1)).tupled.use { (a, b) =>
          for
            _ <- a.announce(Some(header(1)), FetchGsv.default)
            _ <- b.announce(Some(header(2)), FetchGsv.default)
            held <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            first <- a
              .reserve(Vector(header(1)), FetchMode.BulkSync)
              .use(_ => held.complete(()) *> release.get)
              .start
            _ <- held.get
            waiting <- b.reserve(Vector(header(2)), FetchMode.BulkSync).use(_ => IO.unit).start
            _ <- IO.sleep(1.second) *> waiting.cancel
            _ <- release.complete(()) *> first.joinWithNever
            _ <- b
              .reserve(Vector(header(3)), FetchMode.BulkSync)
              .use(allocation =>
                IO(assertEquals(allocation.headers.map(_.slot), Vector(BigInt(3))))
              )
          yield ()
        }
      }
    }
  }

  test("deadline targets fetching peers globally across disjoint blocks") {
    TestControl.executeEmbed {
      FetchCoordinator.create[IO](BlockFetchPolicy.Config(deadlineConcurrency = 1)).flatMap {
        coordinator =>
          (coordinator.session(0), coordinator.session(1)).tupled.use { (a, b) =>
            for
              _ <- a.announce(Some(header(1)), FetchGsv.default)
              _ <- b.announce(Some(header(2)), FetchGsv.default)
              held <- Deferred[IO, Unit]
              release <- Deferred[IO, Unit]
              admitted <- Ref.of[IO, Boolean](false)
              first <- a
                .reserve(Vector(header(1)), FetchMode.Deadline)
                .use(_ => held.complete(()) *> release.get)
                .start
              _ <- held.get
              second <- b
                .reserve(Vector(header(2)), FetchMode.Deadline)
                .use(_ => admitted.set(true))
                .start
              _ <- IO.sleep(1.second)
              early <- admitted.get
              _ <- release.complete(()) *> first.joinWithNever *> second.joinWithNever
            yield assert(!early)
          }
      }
    }
  }

  test("Genesis grace expiry wakes a stalled decision despite unrelated estimate refreshes") {
    TestControl.executeEmbed {
      for
        coordinator <- FetchCoordinator.create[IO]()
        observations <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        _ <- coordinator.installGenesis(
          FetchCoordinator.GenesisControl(
            IO.monotonic.flatMap { time =>
              observations
                .update(_ :+ time)
                .as(
                  FetchCoordinator.GenesisContext(
                    xyz.kd5ujc.scarab.network.selection.Gsm.Phase.Syncing,
                    BigInt(time.toNanos) * 1000,
                    xyz.kd5ujc.scarab.network.selection.GenesisBlockFetch.Starvation.Ongoing
                  )
                )
            },
            _ => IO.unit
          )
        )
        _ <- coordinator.session(1).use { peer =>
          coordinator.watchGenesis(BigInt(10) * 1000000000000L).background.use { _ =>
            List
              .fill(9)(())
              .traverse_(_ =>
                IO.sleep(1.second) *>
                  peer.updatePerformance(FetchGsv.default)
              ) *> IO.sleep(1100.millis) *>
              observations.get.map(times => assert(times.contains(10.seconds), times.toString))
          }
        }
      yield ()
    }
  }

  test("fallback waits for its own concurrent prefetch and wakes on a body before BatchDone") {
    TestControl.executeEmbed {
      for
        cached <- Ref.of[IO, Set[Bytes]](Set.empty)
        coordinator <- FetchCoordinator.create[IO](fetched = Some(cached.get))
        _ <- coordinator.session(7).use { session =>
          session.reserve(Vector(header(1)), FetchMode.BulkSync).use { _ =>
            for
              returned <- Ref.of[IO, Boolean](false)
              waiter <- session.await(header(1).hash).flatMap(_ => returned.set(true)).start
              _ <- IO.sleep(1.second)
              _ <- returned.get.assertEquals(false)
              _ <- cached.update(_ + header(1).hash) *> coordinator.refreshGenesis
              _ <- waiter.joinWithNever
              _ <- returned.get.assertEquals(true)
              flight <- coordinator.inFlight
              _ <- IO(assertEquals(flight(session.identity).requests, 1))
            yield ()
          }
        }
      yield ()
    }
  }
