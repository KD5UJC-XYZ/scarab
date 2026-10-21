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

  test("native fetch priority ranks equal-height Conway tips beyond the eligibility restriction") {
    import xyz.kd5ujc.scarab.ledger.{Fixtures, HeaderVrf, VrfCert}
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
    val first = Fixtures.header(10, 100, None) match
      case h: Header.Shelley => h.copy(vrf = HeaderVrf.Praos(VrfCert(Bytes(Vector.fill(64)(100.toByte)), Bytes.empty)))
      case other => fail(s"expected Shelley fixture: $other")
    val preferred = first.copy(slot = 106, issuerVkey = Bytes(Vector.fill(32)(2.toByte)),
      vrf = HeaderVrf.Praos(VrfCert(Bytes(Vector.fill(64)(1.toByte)), Bytes.empty))))
    assert(!ChainSelection.prefersCandidate(ChainSelection.Tiebreaker.Restricted(5),
      SelectView.of(first), SelectView.of(preferred)))
    assert(FetchCoordinator.compareTips(first, preferred) > 0)
    assert(FetchCoordinator.compareTips(preferred, first) < 0)
    assert(FetchCoordinator.compareTips(first, first) == 0)
  }

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
          coordinator.watchDecisions(BigInt(10) * 1000000000000L).background.use { _ =>
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

  test("a real mode-change decision resets the zero-grace watcher to the new native cadence") {
    import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
    TestControl.executeEmbed {
      for
        phase <- Ref.of[IO, Gsm.Phase](Gsm.Phase.Syncing)
        wake <- Deferred[IO, FiniteDuration]
        coordinator <- FetchCoordinator.create[IO]()
        _ <- coordinator.installGenesis(
          FetchCoordinator.GenesisControl(
            (phase.get, IO.monotonic).tupled.flatMap { (current, time) =>
              wake
                .complete(time)
                .void
                .whenA(time == 15.millis)
                .as(
                  FetchCoordinator.GenesisContext(
                    current,
                    BigInt(time.toNanos) * 1000,
                    GenesisBlockFetch.Starvation.Ongoing
                  )
                )
            },
            _ => IO.unit
          )
        )
        _ <- coordinator.watchDecisions(0).background.use { _ =>
          for
            _ <- IO.sleep(5.millis)
            _ <- phase.set(Gsm.Phase.CaughtUp) *> coordinator.refreshGenesis
            _ <- IO.sleep(9.millis)
            early <- wake.tryGet
            _ <- IO(assertEquals(early, None))
            at <- wake.get
            _ <- IO(assertEquals(at, 15.millis))
          yield ()
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

  test("a cached Genesis offer is not returned as remaining work from the full candidate suffix") {
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
    import xyz.kd5ujc.scarab.ledger.Fixtures
    import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    val headers = Fixtures.chain(2)
    val candidate = ChainSelection.Candidate(Point.Origin, headers)
    val local = ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
    for
      coordinator <- FetchCoordinator.create[IO](
        selection = Some(IO.pure(local)),
        fetched = Some(IO.pure(Set(headers.head.hash)))
      )
      _ <- coordinator.installGenesis(
        FetchCoordinator.GenesisControl(
          IO.pure(
            FetchCoordinator
              .GenesisContext(Gsm.Phase.Syncing, 0, GenesisBlockFetch.Starvation.Ongoing)
          ),
          _ => IO.unit
        )
      )
      _ <- coordinator.session(0).use { session =>
        session.announce(headers.lastOption, FetchGsv.default, candidate = Some(candidate)) *>
          session.reserve(Vector(headers.head), FetchMode.BulkSync, Some(candidate)).use {
            allocation =>
              IO {
                assertEquals(allocation.headers, headers.tail)
                assertEquals(allocation.remaining, Vector.empty)
              }
          }
      }
    yield ()
  }

  test(
    "Genesis requests the selected full suffix even when publication offers only its last header"
  ) {
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
    import xyz.kd5ujc.scarab.ledger.Fixtures
    import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    val headers = Fixtures.chain(2)
    val candidate = ChainSelection.Candidate(Point.Origin, headers)
    val local = ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
    for
      coordinator <- FetchCoordinator.create[IO](selection = Some(IO.pure(local)))
      _ <- coordinator.installGenesis(
        FetchCoordinator.GenesisControl(
          IO.pure(
            FetchCoordinator
              .GenesisContext(Gsm.Phase.Syncing, 0, GenesisBlockFetch.Starvation.Ongoing)
          ),
          _ => IO.unit
        )
      )
      _ <- coordinator.session(0).use { session =>
        session.announce(headers.lastOption, FetchGsv.default, candidate = Some(candidate)) *>
          session.reserve(Vector(headers.last), FetchMode.BulkSync, Some(candidate)).use {
            allocation =>
              IO {
                assertEquals(allocation.headers, headers)
                assertEquals(allocation.remaining, Vector.empty)
              }
          }
      }
    yield ()
  }

  test("GSV sampling is non-trigger state until a candidate, status or grace event") {
    import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
    for
      observations <- Ref.of[IO, Int](0)
      coordinator <- FetchCoordinator.create[IO]()
      _ <- coordinator.installGenesis(
        FetchCoordinator.GenesisControl(
          observations
            .update(_ + 1)
            .as(
              FetchCoordinator
                .GenesisContext(Gsm.Phase.CaughtUp, 0, GenesisBlockFetch.Starvation.Ongoing)
            ),
          _ => IO.unit
        )
      )
      _ <- coordinator.session(0).use { session =>
        for
          before <- observations.get
          _ <- List
            .fill(100)(())
            .traverse_(_ => session.updatePerformance(FetchGsv.fromSample(1, 1000)))
          after <- observations.get
          _ <- IO(assertEquals(after, before))
          _ <- coordinator.refreshGenesis
          triggered <- observations.get
          _ <- IO(assertEquals(triggered, before + 1))
          _ <- session.announce(
            Some(header(2)),
            FetchGsv.default,
            Map(header(1).hash -> 400000L, header(2).hash -> 400000L)
          )
          _ <- session.reserve(Vector(header(1), header(2)), FetchMode.BulkSync).use { allocation =>
            // The new GSV gives a 393216-byte window; the old default admitted both bodies.
            IO(assertEquals(allocation.headers, Vector(header(1))))
          }
        yield ()
      }
    yield ()
  }

  test("Genesis parks an uncached offer which cannot serve the preferred chain's oldest point") {
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
    import xyz.kd5ujc.scarab.ledger.Fixtures
    import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    TestControl.executeEmbed {
      val short = Fixtures.chain(1, salt = 1)
      val preferred = Fixtures.chain(2)
      val origin =
        ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
      val shortCandidate = ChainSelection.Candidate(Point.Origin, short)
      val preferredCandidate = ChainSelection.Candidate(Point.Origin, preferred)
      for
        selected <- Ref.of[IO, ChainSelection.Local](origin)
        cached <- Ref.of[IO, Set[Bytes]](Set.empty)
        result <- Ref.of[IO, Option[FetchCoordinator.Allocation]](None)
        coordinator <- FetchCoordinator
          .create[IO](selection = Some(selected.get), fetched = Some(cached.get))
        _ <- coordinator.installGenesis(
          FetchCoordinator.GenesisControl(
            IO.pure(
              FetchCoordinator
                .GenesisContext(Gsm.Phase.Syncing, 0, GenesisBlockFetch.Starvation.Ongoing)
            ),
            _ => IO.unit
          )
        )
        _ <- (coordinator.session(0), coordinator.session(1)).tupled.use { (older, better) =>
          for
            _ <- older.announce(
              short.lastOption,
              FetchGsv.default,
              candidate = Some(shortCandidate)
            )
            _ <- better.announce(
              preferred.lastOption,
              FetchGsv.default,
              candidate = Some(preferredCandidate)
            )
            waiting <- older
              .reserve(short, FetchMode.BulkSync, Some(shortCandidate))
              .use(a => result.set(Some(a)))
              .start
            _ <- IO.sleep(1.second)
            _ <- result.get.assertEquals(None)
            _ <- better.reserve(preferred, FetchMode.BulkSync, Some(preferredCandidate)).use {
              allocation =>
                IO(assertEquals(allocation.headers, preferred)) *>
                  cached.set(preferred.map(_.hash).toSet) *> coordinator.refreshGenesis
            }
            _ <- IO.sleep(1.second)
            _ <- result.get.assertEquals(None)
            _ <- selected.set(
              origin.copy(blocks =
                preferred.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
              )
            ) *> coordinator.refreshGenesis
            _ <- waiting.joinWithNever
            after <- result.get
            _ <- IO(
              assertEquals(
                after.flatMap(_.refusal),
                Some(BlockFetchPolicy.Refusal.ChainNotPlausible)
              )
            )
            _ <- IO(assertEquals(after.map(_.remaining), Some(Vector.empty)))
          yield ()
        }
      yield ()
    }
  }

  for mode <- List(FetchMode.BulkSync, FetchMode.Deadline) do
    test(s"$mode assigns released global credit to the preferred candidate chain") {
      TestControl.executeEmbed {
        for
          coordinator <- FetchCoordinator.create[IO](
            BlockFetchPolicy.Config(bulkConcurrency = 1, deadlineConcurrency = 1)
          )
          first <- Deferred[IO, Int]
          release <- Deferred[IO, Unit]
          order <- Ref.of[IO, Vector[Int]](Vector.empty)
          _ <- (coordinator.session(0), coordinator.session(1), coordinator.session(2)).tupled.use {
            (holder, shorter, longer) =>
              for
                held <- holder.reserve(Vector(header(0)), mode).allocated
                _ <- shorter.announce(Some(header(1)), FetchGsv.default)
                _ <- longer.announce(Some(header(2)), FetchGsv.default)
                a <- shorter
                  .reserve(Vector(header(1)), mode)
                  .use(allocation =>
                    order.update(_ :+ allocation.headers.head.slot.toInt) *>
                      first.complete(1).void *> release.get
                  )
                  .start
                b <- longer
                  .reserve(Vector(header(2)), mode)
                  .use(allocation =>
                    order.update(_ :+ allocation.headers.head.slot.toInt) *>
                      first.complete(2).void *> release.get
                  )
                  .start
                _ <- IO.sleep(1.second) *> held._2
                winner <- first.get
                _ <- IO(assertEquals(winner, 2))
                _ <- release.complete(()) *> a.joinWithNever *> b.joinWithNever
                observed <- order.get
                _ <- IO(assertEquals(observed, Vector(2, 1)))
              yield ()
          }
        yield ()
      }
    }
