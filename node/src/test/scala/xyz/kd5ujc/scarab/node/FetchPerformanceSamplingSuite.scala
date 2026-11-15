// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.FetchGsv

import FetchTesting.*

/** A session's registered GSV estimate is read by the decisions themselves, once each, as the
  * reference reads the peers' GSVs in its decision snapshot (network c45735a5
  * BlockFetch/State.hs:418–421): no loop polls it between decisions. Header announcements still
  * carry the estimate the session read when it announced, under the same newest-sample rule.
  */
class FetchPerformanceSamplingSuite extends FailsOnUncaught:
  private val incumbentModel = FetchGsv.fromSample(0, FetchGsv.PicosPerSecond / 5)
  private val fresh = FetchGsv.fromSample(
    1000 * FetchGsv.PicosPerSecond,
    1000 * FetchGsv.PicosPerSecond + FetchGsv.PicosPerSecond / 100
  )

  /** A reader of `estimate` that counts how often it is read. */
  private def counting(estimate: Ref[IO, FetchGsv.PeerGsv], reads: Ref[IO, Int]) =
    reads.update(_ + 1) *> estimate.get

  test("a decision requests with the estimate as it is when it decides") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      (coordinator.session(0), coordinator.session(1)).tupled.use { (incumbent, waiting) =>
        for
          estimate <- Ref.of[IO, FetchGsv.PeerGsv](FetchGsv.default)
          _ <- waiting.measured(estimate.get)
          _ <- incumbent.follows(Vector(header(1)), incumbentModel)
          _ <- coordinator.step
          held <- incumbent.requestedHeaders
          _ <- waiting.follows(Vector(header(2)))
          _ <- coordinator.step
          before <- waiting.requested
          _ <- estimate.set(fresh)
          _ <- coordinator.step
          after <- waiting.requested
        yield
          assertEquals(held, Vector(header(1)), "the incumbent holds the first block")
          assertEquals(before, None, "with the default estimate the waiting peer gets nothing")
          assertEquals(
            after.map(_.headers),
            Some(Vector(header(2))),
            "the fresh estimate admits it"
          )
          assertEquals(after.map(_.performance), Some(fresh), "the request carries that estimate")
          assertEquals(after.map(_.limits), Some(fresh.window), "and its limits")
      }
    }
  }

  test("only a decision reads the estimate, once for each decision") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      coordinator.session(0).use { session =>
        for
          estimate <- Ref.of[IO, FetchGsv.PeerGsv](FetchGsv.default)
          reads <- Ref.of[IO, Int](0)
          _ <- session.measured(counting(estimate, reads))
          _ <- List.range(0, 100).traverse_(n => estimate.set(fresh.copy(sampleTimePicos = n)))
          _ <- session.follows(Vector(header(1)))
          unread <- reads.get
          _ <- coordinator.step
          _ <- coordinator.step
          read <- reads.get
        yield
          assertEquals(unread, 0, "neither a new estimate nor an announcement reads it")
          assertEquals(read, 2, "each decision reads it once")
      }
    }
  }

  test("an older estimate never replaces a newer one, and one of the same time does") {
    val newer = fresh.copy(sampleTimePicos = 2000)
    val older = incumbentModel.copy(sampleTimePicos = 1000)
    val sameTime = incumbentModel.copy(sampleTimePicos = 2000)
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      coordinator.session(0).use { session =>
        for
          estimate <- Ref.of[IO, FetchGsv.PeerGsv](older)
          _ <- session.measured(estimate.get)
          _ <- session.follows(Vector(header(1)), newer)
          _ <- coordinator.step
          first <- session.requested
          _ <- estimate.set(sameTime)
          _ <- session.follows(Vector(header(1), header(2)), older)
          _ <- coordinator.step
          merged <- session.requested
        yield
          assertEquals(
            first.map(_.performance),
            Some(newer),
            "the older estimate lost to the newer"
          )
          assertEquals(
            merged.map(_.performance),
            Some(sameTime),
            "one of the same time replaced it"
          )
      }
    }
  }

  test("a delayed header announcement cannot replace a newer one's estimate") {
    val newer = fresh.copy(sampleTimePicos = 2000)
    val older = incumbentModel.copy(sampleTimePicos = 1000)
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      coordinator.session(0).use { session =>
        for
          _ <- session.follows(Vector(header(1)), newer)
          _ <- session.follows(Vector(header(1)), older)
          _ <- coordinator.step
          requested <- session.requested
        yield assertEquals(requested.map(_.performance), Some(newer))
      }
    }
  }

  test("an ended session's estimate is no longer read") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      for
        estimate <- Ref.of[IO, FetchGsv.PeerGsv](fresh)
        reads <- Ref.of[IO, Int](0)
        _ <- coordinator
          .session(0)
          .use(session => session.measured(counting(estimate, reads)) *> coordinator.step)
        during <- reads.get
        _ <- coordinator.step
        after <- reads.get
      yield
        assertEquals(during, 1, "read by the decision while the session lived")
        assertEquals(after, 1, "not read once it ended")
    }
  }
