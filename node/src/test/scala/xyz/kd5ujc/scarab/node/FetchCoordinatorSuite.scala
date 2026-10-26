// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, FiberIO, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{
  BlockFetchPolicy,
  FetchGsv,
  FetchMode,
  GenesisBlockFetch,
  Gsm
}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import scala.concurrent.duration.*

import FetchTesting.*

class FetchCoordinatorSuite extends FailsOnUncaught:
  private val origin =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)

  private def syncing(time: BigInt = 0): FetchCoordinator.GenesisControl[IO] =
    FetchCoordinator.GenesisControl(
      IO.pure(
        FetchCoordinator
          .GenesisContext(Gsm.Phase.Syncing, time, GenesisBlockFetch.Starvation.Ongoing)
      ),
      _ => IO.unit
    )

  /** The request's blocks have all arrived, into `cache`, and its ranges have ended. */
  private def complete(
      cache: Ref[IO, Set[Bytes]],
      session: FetchCoordinator.Session[IO],
      allocation: FetchCoordinator.Allocation
  ): IO[Unit] =
    allocation.headers.traverse_(h =>
      cache.update(_ + h.hash) *> session.received(allocation, h.hash)
    ) *> session.completed(allocation).replicateA_(allocation.requests) *>
      session.finished(allocation)

  /** A coordinator over `config` whose fetched blocks are those in the returned cache. */
  private def cached(
      config: BlockFetchPolicy.Config = BlockFetchPolicy.Config()
  ): IO[(Ref[IO, Set[Bytes]], FetchCoordinator[IO])] =
    Ref
      .of[IO, Set[Bytes]](Set.empty)
      .flatMap(cache =>
        FetchCoordinator.create[IO](config, fetched = Some(cache.get)).map(cache -> _)
      )

  test("native fetch priority ranks equal-height Conway tips beyond the eligibility restriction") {
    import xyz.kd5ujc.scarab.ledger.{HeaderVrf, VrfCert}
    val first = Fixtures.header(10, 100, None) match
      case h: Header.Shelley =>
        h.copy(vrf = HeaderVrf.Praos(VrfCert(Bytes(Vector.fill(64)(100.toByte)), Bytes.empty)))
      case other => fail(s"expected Shelley fixture: $other")
    val preferred = first.copy(
      slot = 106,
      issuerVkey = Bytes(Vector.fill(32)(2.toByte)),
      vrf = HeaderVrf.Praos(VrfCert(Bytes(Vector.fill(64)(1.toByte)), Bytes.empty))
    )
    assert(
      !ChainSelection.prefersCandidate(
        ChainSelection.Tiebreaker.Restricted(5),
        SelectView.of(first),
        SelectView.of(preferred)
      )
    )
    assert(FetchCoordinator.compareTips(first, preferred) > 0)
    assert(FetchCoordinator.compareTips(preferred, first) < 0)
    assert(FetchCoordinator.compareTips(first, first) == 0)
  }

  test("a candidate change alone triggers a request: no session offers anything") {
    // State.hs:395–416: the loop waits for the trigger fingerprint, here with a grace period far
    // longer than the test, so only the candidate head of the fingerprint can wake it.
    TestControl.executeEmbed {
      FetchCoordinator.create[IO](selection = Some(IO.pure(origin))).flatMap { coordinator =>
        coordinator.session(0).use { session =>
          val headers = Fixtures.chain(3)
          coordinator.watchDecisions(BigInt(3600) * 1000000000000L).background.use { _ =>
            for
              _ <- IO.sleep(1.second)
              before <- session.requested
              _ <- session.follows(headers)
              after <- session.acquire(IO.sleep(1.second))
            yield
              assertEquals(before, None)
              assertEquals(after.map(_.headers), Some(headers))
          }
        }
      }
    }
  }

  test("a nonpreferred peer waits for global bulk credit and is admitted after release") {
    cached().flatMap { (cache, coordinator) =>
      (coordinator.session(0), coordinator.session(1)).tupled.use { (a, b) =>
        for
          _ <- a.follows(Vector(header(1)))
          _ <- coordinator.step
          first <- a.requested
          _ <- b.follows(Vector(header(2)))
          _ <- coordinator.step
          waiting <- b.requestedHeaders
          _ <- first.traverse_(complete(cache, a, _))
          _ <- coordinator.step
          after <- b.requestedHeaders
        yield
          assertEquals(first.map(_.headers), Some(Vector(header(1))))
          assertEquals(waiting, Vector.empty)
          assertEquals(after, Vector(header(2)))
      }
    }
  }

  test("preferred-peer handover follows upstream even while an old peer is draining") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      (coordinator.session(1), coordinator.session(0)).tupled.use { (slow, fast) =>
        val measured = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 100)
        for
          _ <- slow.follows(Vector(header(1)))
          _ <- coordinator.step
          held <- slow.requestedHeaders
          _ <- fast.follows(Vector(header(2)), measured)
          _ <- coordinator.step
          handed <- fast.requestedHeaders
        yield
          assertEquals(held, Vector(header(1)))
          assertEquals(handed, Vector(header(2)))
      }
    }
  }

  test("a request its client never took stays in flight until its session ends") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      coordinator.session(1).use { b =>
        for
          _ <- coordinator.session(0).use { a =>
            a.follows(Vector(header(1))) *> b.follows(Vector(header(1))) *> coordinator.step *>
              b.requestedHeaders.flatMap(held => IO(assertEquals(held, Vector.empty)))
          }
          _ <- coordinator.step
          after <- b.requestedHeaders
        yield assertEquals(after, Vector(header(1)))
      }
    }
  }

  test("deadline targets fetching peers globally across disjoint blocks") {
    cached(BlockFetchPolicy.Config(deadlineConcurrency = 1)).flatMap { (cache, coordinator) =>
      (coordinator.session(0), coordinator.session(1)).tupled.use { (a, b) =>
        for
          _ <- coordinator.setMode(FetchMode.Deadline)
          _ <- a.follows(Vector(header(1)))
          _ <- coordinator.step
          first <- a.requested
          _ <- b.follows(Vector(header(2)))
          _ <- coordinator.step
          early <- b.requestedHeaders
          _ <- first.traverse_(complete(cache, a, _))
          _ <- coordinator.step
          later <- b.requestedHeaders
        yield
          assertEquals(early, Vector.empty)
          assertEquals(later, Vector(header(2)))
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
                    Gsm.Phase.Syncing,
                    BigInt(time.toNanos) * 1000,
                    GenesisBlockFetch.Starvation.Ongoing
                  )
                )
            },
            _ => IO.unit
          )
        )
        times <- coordinator.session(1).use { peer =>
          coordinator.watchDecisions(BigInt(10) * 1000000000000L).background.use { _ =>
            List
              .fill(9)(())
              .traverse_(_ => IO.sleep(1.second) *> peer.updatePerformance(FetchGsv.default)) *>
              IO.sleep(1100.millis) *> observations.get
          }
        }
      // The first decision runs at once; the next iteration starts after the Genesis interval
      // (State.hs:100–106) and registers its grace then (150), so it expires at 10.04s.
      yield assertEquals(times, Vector(Duration.Zero, 10040.millis))
    }
  }

  test("the decision interval is the one of the fetch mode the last decision ran in") {
    TestControl.executeEmbed {
      for
        phase <- Ref.of[IO, Gsm.Phase](Gsm.Phase.Syncing)
        times <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        coordinator <- FetchCoordinator.create[IO]()
        _ <- coordinator.installGenesis(
          FetchCoordinator.GenesisControl(
            (phase.get, IO.monotonic).tupled.flatMap { (current, time) =>
              times
                .update(_ :+ time)
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
        observed <- coordinator.watchDecisions(0).background.use { _ =>
          IO.sleep(5.millis) *> phase.set(Gsm.Phase.CaughtUp) *> coordinator.refreshGenesis *>
            IO.sleep(51.millis) *> times.get
        }
      // State.hs:100–106: 40ms after the Syncing decision, then 10ms after the caught-up one.
      yield assertEquals(observed, Vector(Duration.Zero, 40.millis, 50.millis))
    }
  }

  test("a borrower waits for a body in flight with another session and wakes once it is cached") {
    TestControl.executeEmbed {
      for
        cached <- Ref.of[IO, Set[Bytes]](Set.empty)
        coordinator <- FetchCoordinator.create[IO](fetched = Some(cached.get))
        _ <- coordinator.session(7).use { session =>
          for
            _ <- session.follows(Vector(header(1)))
            _ <- coordinator.step
            allocation <- session.requested
            returned <- Ref.of[IO, Boolean](false)
            waiter <- session.await(header(1).hash).flatMap(_ => returned.set(true)).start
            _ <- IO.sleep(1.second)
            early <- returned.get
            _ <- cached.update(_ + header(1).hash) *> coordinator.refreshGenesis
            _ <- waiter.joinWithNever
            late <- returned.get
            flight <- coordinator.inFlight
          yield
            assert(allocation.nonEmpty)
            assertEquals(early, false)
            assertEquals(late, true)
            assertEquals(flight(session.identity).requests, 1)
        }
      yield ()
    }
  }

  test("Genesis requests the plausible suffix without its cached blocks") {
    val headers = Fixtures.chain(2)
    for
      coordinator <- FetchCoordinator.create[IO](
        selection = Some(IO.pure(origin)),
        fetched = Some(IO.pure(Set(headers.head.hash)))
      )
      _ <- coordinator.installGenesis(syncing())
      requested <- coordinator.session(0).use { session =>
        session.follows(headers) *> coordinator.step *> session.requestedHeaders
      }
    yield assertEquals(requested, headers.tail)
  }

  test("Genesis requests the selected full suffix of the candidate") {
    val headers = Fixtures.chain(2)
    for
      coordinator <- FetchCoordinator.create[IO](selection = Some(IO.pure(origin)))
      _ <- coordinator.installGenesis(syncing())
      requested <- coordinator.session(0).use { session =>
        session.follows(headers) *> coordinator.step *> session.requestedHeaders
      }
    yield assertEquals(requested, headers)
  }

  test("a fork prefix declined before its chain was preferred is requested once it is (#430)") {
    // Our chain is 0, 1, 2 (we forged 1 and 2 while partitioned); the peer's is 0, 1', 2', 3'.
    // ChainSync delivers the peer's headers one at a time, so the decisions see 1' and 2' while
    // their chain is not longer than ours, and 3' then makes it preferred.
    val ours = Fixtures.chain(3)
    val fork = Fixtures.chain(3, salt = 7, from = Some(ours.head))
    val local = ChainSelection.Local(
      Point.Origin,
      SelectView(-1, 0, None, 0, None),
      ours.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )
    def upTo(n: Int) = ours.head +: fork.take(n)
    FetchCoordinator.create[IO](selection = Some(IO.pure(local))).flatMap { coordinator =>
      coordinator.session(0).use { session =>
        def decided(n: Int) = session.follows(upTo(n)) *> coordinator.step *> session.requested
        for
          _ <- coordinator.setMode(FetchMode.Deadline)
          first <- decided(1)
          second <- decided(2)
          third <- decided(3)
        yield
          assertEquals(first, None)
          assertEquals(second, None)
          // Decision.hs selectForkSuffixes (547–558) and filterNotAlreadyFetched (599–617): the
          // whole missing fork suffix of the now preferred candidate, as one range.
          assertEquals(third.map(_.headers), Some(fork))
          assertEquals(third.map(_.requests), Some(1))
      }
    }
  }

  test("GSV sampling is non-trigger state; a decision reads the sampled window") {
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
      result <- coordinator.session(0).use { session =>
        for
          before <- observations.get
          _ <- List
            .fill(100)(())
            .traverse_(_ => session.updatePerformance(FetchGsv.fromSample(1, 1000)))
          after <- observations.get
          _ <- session.follows(
            Vector(header(1), header(2)),
            FetchGsv.default,
            Map(header(1).hash -> 400000L, header(2).hash -> 400000L)
          )
          _ <- coordinator.step
          decided <- observations.get
          requested <- session.requestedHeaders
        yield (before, after, decided, requested)
      }
    yield
      val (before, after, decided, requested) = result
      assertEquals(after, before)
      assertEquals(decided, before + 1)
      // The new GSV gives a 393216-byte window; the first body crosses it (Decision.hs:1180).
      assertEquals(requested, Vector(header(1)))
  }

  test("Genesis declines a peer whose chain is not plausible, while another serves the preferred") {
    val short = Fixtures.chain(1, salt = 1)
    val preferred = Fixtures.chain(2)
    for
      selected <- Ref.of[IO, ChainSelection.Local](origin)
      cached <- Ref.of[IO, Set[Bytes]](Set.empty)
      coordinator <- FetchCoordinator
        .create[IO](selection = Some(selected.get), fetched = Some(cached.get))
      _ <- coordinator.installGenesis(syncing())
      result <- (coordinator.session(0), coordinator.session(1)).tupled.use { (older, better) =>
        for
          _ <- older.follows(short)
          _ <- better.follows(preferred)
          _ <- coordinator.step
          served <- better.requestedHeaders
          parked <- older.requestedHeaders
          _ <- cached.set(preferred.map(_.hash).toSet)
          _ <- selected.set(
            origin.copy(blocks =
              preferred.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
            )
          )
          _ <- coordinator.step
          after <- older.requestedHeaders
        yield (served, parked, after)
      }
    yield
      val (served, parked, after) = result
      assertEquals(served, preferred)
      assertEquals(parked, Vector.empty)
      assertEquals(after, Vector.empty)
  }

  for mode <- List(FetchMode.BulkSync, FetchMode.Deadline) do
    test(s"$mode assigns released global credit to the preferred candidate chain") {
      cached(BlockFetchPolicy.Config(bulkConcurrency = 1, deadlineConcurrency = 1))
        .flatMap { (cache, coordinator) =>
          (coordinator.session(0), coordinator.session(1), coordinator.session(2)).tupled.use {
            (holder, shorter, longer) =>
              for
                _ <- coordinator.setMode(mode)
                _ <- holder.follows(Vector(header(0)))
                _ <- coordinator.step
                held <- holder.requested
                _ <- shorter.follows(Vector(header(1)))
                _ <- longer.follows(Vector(header(2)))
                _ <- coordinator.step
                whileHeld <- (shorter.requestedHeaders, longer.requestedHeaders).tupled
                _ <- held.traverse_(complete(cache, holder, _))
                _ <- coordinator.step
                firstLonger <- longer.requested
                firstShorter <- shorter.requestedHeaders
                _ <- firstLonger.traverse_(complete(cache, longer, _))
                _ <- coordinator.step
                thenShorter <- shorter.requestedHeaders
              yield
                assertEquals(whileHeld, (Vector.empty, Vector.empty))
                assertEquals(firstLonger.map(_.headers), Some(Vector(header(2))))
                assertEquals(firstShorter, Vector.empty)
                assertEquals(thenShorter, Vector(header(1)))
          }
        }
    }

  test("a caught-up transition cannot overtake an already sampled dynamo demotion") {
    enum Event:
      case Demoting, Demoted, CaughtUp
    val headers = Fixtures.chain(2)
    TestControl.executeEmbed {
      for
        phase <- Ref.of[IO, Gsm.Phase](Gsm.Phase.Syncing)
        now <- Ref.of[IO, BigInt](0)
        events <- Ref.of[IO, Vector[Event]](Vector.empty)
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        coordinator <- FetchCoordinator.create[IO](selection = Some(IO.pure(origin)))
        _ <- coordinator.installGenesis(
          FetchCoordinator.GenesisControl(
            (phase.get, now.get).mapN((p, time) =>
              FetchCoordinator.GenesisContext(p, time, GenesisBlockFetch.Starvation.Ongoing)
            ),
            _ =>
              events.update(_ :+ Event.Demoting) *> entered.complete(()).void *>
                release.get *> events.update(_ :+ Event.Demoted)
          )
        )
        _ <- coordinator.session(0).use { session =>
          // The first decision makes this session the dynamo; the grace then expires.
          session.follows(headers) *> coordinator.step *> (for
            _ <- now.set(GenesisBlockFetch.DefaultGracePicos)
            _ <- coordinator.step.background.use { _ =>
              entered.get *> coordinator
                .transitionGenesis(
                  phase.set(Gsm.Phase.CaughtUp) *> events.update(_ :+ Event.CaughtUp)
                )
                .start
                .flatMap { transition =>
                  (for
                    _ <- IO.sleep(1.nano)
                    before <- phase.get
                    _ <- IO(assertEquals(before, Gsm.Phase.Syncing))
                    _ <- release.complete(()).void
                    _ <- transition.joinWithNever
                  yield ()).guarantee(release.complete(()).void *> transition.cancel)
                }
            }
            observed <- events.get
            _ <- IO(assertEquals(observed, Vector(Event.Demoting, Event.Demoted, Event.CaughtUp)))
            _ <- coordinator.step
            after <- events.get
            _ <- IO(assertEquals(after, observed))
          yield ()).guarantee(release.complete(()).void)
        }
      yield ()
    }
  }

  test("a stall report names who holds the decision lock (#371)") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      for
        held <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        workers <- Ref.of[IO, List[FiberIO[Unit]]](Nil)
        run = (work: IO[Unit]) => work.start.flatTap(fiber => workers.update(fiber :: _))
        result <- (for
          idle <- coordinator.lockHolder
          // a transaction that holds the lock until released, and a decision behind it
          _ <- run(coordinator.transitionGenesis(held.complete(()) *> release.get))
          _ <- held.get
          holding <- coordinator.lockHolder
          decision <- run(coordinator.step)
          _ <- release.complete(())
          _ <- decision.joinWithNever
        yield (idle, holding)).guarantee(
          held.complete(()).void *> release.complete(()).void *>
            workers.get.flatMap(_.traverse_(_.cancel))
        )
        after <- coordinator.lockHolder
      yield
        val (idle, holding) = result
        assertEquals(idle, None)
        assertEquals(holding.map(_.by), Some(FetchCoordinator.DecisionHolder.TransitionGenesis))
        assertEquals(after, None)
    }
  }

  private case object Boom extends RuntimeException("boom"), scala.util.control.NoStackTrace

  test("a cancelled or failed decision leaves no lock holder behind (#371)") {
    // 0: selection answers, 1: it fails, 2: it never answers
    Ref.of[IO, Int](0).flatMap { mode =>
      val selection = mode.get.flatMap {
        case 0 => IO.pure(origin)
        case 1 => IO.raiseError[ChainSelection.Local](Boom)
        case _ => IO.never[ChainSelection.Local]
      }
      FetchCoordinator.create[IO](selection = Some(selection)).flatMap { coordinator =>
        coordinator.session(0).use { _ =>
          for
            _ <- mode.set(1)
            failed <- coordinator.step.attempt
            afterFailure <- coordinator.lockHolder
            _ <- mode.set(2)
            stuck <- coordinator.step.start
            holding <- (IO.sleep(10.millis) *> coordinator.lockHolder)
              .iterateUntil(_.nonEmpty)
              .timeout(10.seconds)
            _ <- stuck.cancel
            afterCancel <- coordinator.lockHolder
            _ <- mode.set(0)
          yield
            assertEquals(failed, Left(Boom))
            assertEquals(afterFailure, None)
            assertEquals(holding.map(_.by), Some(FetchCoordinator.DecisionHolder.Decide))
            assertEquals(afterCancel, None)
        }
      }
    }
  }
