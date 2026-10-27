// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{FiberIO, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchGsv, FetchMode}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import scala.concurrent.duration.*

import FetchTesting.*

/** Observable request, cache publication and flow-control laws under controlled interleavings.
  */
class FetchCoordinationLawsSuite extends FailsOnUncaught:
  private def running[A](effect: IO[A]): Resource[IO, FiberIO[A]] =
    Resource.make(effect.start)(_.cancel)

  test("a request in flight holds back a borrower until its body is in the pool") {
    TestControl.executeEmbed {
      for
        coordinator <- FetchCoordinator.create[IO]()
        pool <- BlockPool.create[IO]
        _ <- (coordinator.session(0), coordinator.session(1), pool.session, pool.session).tupled
          .use { (source, borrower, sourceClaims, borrowedClaims) =>
            val h = header(1)
            val body = Bytes.wrap(Array[Byte](42))
            for
              _ <- source.follows(Vector(h))
              _ <- coordinator.step
              allocation <- source.requested
              borrowed <- Ref.of[IO, Option[Option[Bytes]]](None)
              _ <- running(
                borrower.await(h.hash) *> borrowedClaims
                  .take(h.hash)
                  .flatMap(result => borrowed.set(Some(result.map(_.block))))
              ).use { consumer =>
                for
                  _ <- IO.sleep(1.second)
                  early <- borrowed.get
                  flags <- sourceClaims.claim(Vector(h.hash), allowOverlap = true)
                  _ <- sourceClaims.fill(Vector(h.hash -> body))
                  _ <- allocation.traverse_(source.finished)
                  _ <- consumer.joinWithNever
                  delivered <- borrowed.get
                yield
                  assertEquals(allocation.map(_.headers), Some(Vector(h)))
                  assertEquals(early, None)
                  assertEquals(flags, Vector(true))
                  assertEquals(delivered, Some(Some(body)))
              }
            yield ()
          }
      yield ()
    }
  }

  test("a middle block in flight with another peer leaves two distinct request fragments") {
    FetchCoordinator.create[IO](BlockFetchPolicy.Config(bulkConcurrency = 2)).flatMap {
      coordinator =>
        (coordinator.session(0), coordinator.session(1)).tupled.use { (middleSource, outerSource) =>
          val headers = Vector(header(1), header(2), header(3))
          for
            _ <- middleSource.announce(
              Some(header(2)),
              FetchGsv.default,
              candidate = Some(ChainSelection.Candidate(Point.Origin, Vector(header(2))))
            )
            _ <- coordinator.step
            middle <- middleSource.requestedHeaders
            _ <- outerSource.follows(headers)
            _ <- coordinator.step
            selected <- outerSource.requested
          yield
            assertEquals(middle, Vector(header(2)))
            // Decision.hs filterNotAlreadyInFlightWithOtherPeers: two fragments, two ranges.
            assertEquals(selected.map(_.headers), Some(Vector(header(1), header(3))))
            assertEquals(selected.map(_.requests), Some(2))
        }
    }
  }

  test("per-block progress keeps a full window busy until its low watermark is reached") {
    Ref.of[IO, Set[Bytes]](Set.empty).flatMap { cache =>
      FetchCoordinator
        .create[IO](BlockFetchPolicy.Config(maxRequestsPerPeer = 2), fetched = Some(cache.get))
        .flatMap { coordinator =>
          coordinator.session(0).use { session =>
            val measured = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 10)
            val hints = Map(
              header(1).hash -> 100000L,
              header(2).hash -> 96608L,
              header(3).hash -> 196608L,
              header(4).hash -> 1L
            )
            for
              _ <- session.follows(
                Vector(header(1), header(2), header(3), header(4)),
                measured,
                hints
              )
              _ <- coordinator.step
              first <- session.requested
              _ <- coordinator.step
              full <- session.requestedHeaders
              _ <- cache
                .update(_ + header(1).hash) *> first.traverse_(session.received(_, header(1).hash))
              _ <- coordinator.step
              between <- session.requestedHeaders
              _ <- cache
                .update(_ + header(2).hash) *> first.traverse_(session.received(_, header(2).hash))
              _ <- coordinator.step
              next <- session.requestedHeaders
            yield
              assertEquals(measured.window.lowBytes, 196608L)
              assertEquals(measured.window.highBytes, 393216L)
              assertEquals(first.map(_.headers.size), Some(3))
              assertEquals(full, Vector.empty)
              assertEquals(between, Vector.empty)
              assertEquals(next, Vector(header(4)))
          }
        }
    }
  }

  test("range completion returns request credit before the request has finished") {
    Ref.of[IO, Set[Bytes]](Set.empty).flatMap { cache =>
      FetchCoordinator
        .create[IO](BlockFetchPolicy.Config(maxRequestsPerPeer = 1), fetched = Some(cache.get))
        .flatMap { coordinator =>
          coordinator.session(0).use { session =>
            for
              _ <- session.follows(Vector(header(1)))
              _ <- coordinator.step
              previous <- session.requested
              _ <- cache.update(_ + header(1).hash)
              _ <- previous.traverse_(session.received(_, header(1).hash))
              _ <- session.follows(Vector(header(1), header(2)))
              _ <- coordinator.step
              beforeCompletion <- session.requestedHeaders
              _ <- previous.traverse_(session.completed)
              _ <- coordinator.step
              afterCompletion <- session.requestedHeaders
            yield
              assertEquals(beforeCompletion, Vector.empty)
              assertEquals(afterCompletion, Vector(header(2)))
          }
        }
    }
  }

  test(
    "switching modes permits deadline duplication and drains existing work before bulk admission"
  ) {
    Ref.of[IO, Set[Bytes]](Set.empty).flatMap { cache =>
      FetchCoordinator
        .create[IO](
          BlockFetchPolicy.Config(bulkConcurrency = 1, deadlineConcurrency = 2),
          fetched = Some(cache.get)
        )
        .flatMap { coordinator =>
          (coordinator.session(0), coordinator.session(1), coordinator.session(2)).tupled.use {
            (a, b, c) =>
              for
                _ <- a.follows(Vector(header(1)))
                _ <- coordinator.step
                first <- a.requested
                _ <- b.follows(Vector(header(1)))
                _ <- coordinator.step
                refused <- b.requestedHeaders
                _ <- coordinator.setMode(FetchMode.Deadline)
                _ <- coordinator.step
                duplicate <- b.requested
                _ <- coordinator.setMode(FetchMode.BulkSync)
                _ <- c.follows(Vector(header(2)))
                _ <- coordinator.step
                whileBoth <- c.requestedHeaders
                _ <- cache.update(_ + header(1).hash) *> duplicate.traverse_(b.finished)
                _ <- coordinator.step
                whileFirst <- c.requestedHeaders
                _ <- first.traverse_(a.finished)
                _ <- coordinator.step
                selected <- c.requestedHeaders
              yield
                assertEquals(first.map(_.headers), Some(Vector(header(1))))
                assertEquals(refused, Vector.empty)
                assertEquals(duplicate.map(_.headers), Some(Vector(header(1))))
                assertEquals(whileBoth, Vector.empty)
                assertEquals(whileFirst, Vector.empty)
                assertEquals(selected, Vector(header(2)))
          }
        }
    }
  }
