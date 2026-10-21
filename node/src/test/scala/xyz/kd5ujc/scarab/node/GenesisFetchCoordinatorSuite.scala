// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Fixtures
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{FetchGsv, FetchMode, GenesisBlockFetch, Gsm}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

class GenesisFetchCoordinatorSuite extends FailsOnUncaught:
  test(
    "Genesis handover permits overlap, retains old flight, and wakes cached recovery borrowers"
  ) {
    TestControl.executeEmbed {
      val headers = Fixtures.chain(2)
      val candidate = ChainSelection.Candidate(Point.Origin, headers)
      val local = ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
      for
        fetched <- Ref.of[IO, Set[Bytes]](Set.empty)
        demoted <- Ref.of[IO, Vector[Long]](Vector.empty)
        coordinator <- FetchCoordinator
          .create[IO](selection = Some(IO.pure(local)), fetched = Some(fetched.get))
        _ <- coordinator.installGenesis(
          FetchCoordinator.GenesisControl(
            IO.monotonic.map(t =>
              FetchCoordinator.GenesisContext(
                Gsm.Phase.Syncing,
                BigInt(t.toNanos) * 1000,
                GenesisBlockFetch.Starvation.Ongoing
              )
            ),
            id => demoted.update(_ :+ id)
          )
        )
        _ <- (coordinator.session(1), coordinator.session(2)).tupled.use { (slow, fast) =>
          slow.announce(headers.lastOption, FetchGsv.default, candidate = Some(candidate)) *>
            fast.announce(headers.lastOption, FetchGsv.default, candidate = Some(candidate)) *>
            slow.reserve(headers, FetchMode.BulkSync, Some(candidate)).use { _ =>
              for
                ready <- Deferred[IO, Unit]
                release <- Deferred[IO, Unit]
                replacement <- fast
                  .reserve(headers, FetchMode.BulkSync, Some(candidate))
                  .use { allocation =>
                    for
                      _ <- IO(assertEquals(allocation.headers, headers))
                      _ <- IO(assert(allocation.allowOverlap))
                      flight <- coordinator.inFlight
                      _ <- IO(assertEquals(flight(slow.identity).blocks, headers.map(_.hash).toSet))
                      _ <- IO(assertEquals(flight(slow.identity).requests, 1))
                      _ <- ready.complete(()) *> release.get
                      _ <- fetched.update(_ + headers.head.hash)
                    yield ()
                  }
                  .start
                _ <- IO.sleep(11.seconds) *> coordinator.refreshGenesis
                _ <- ready.get
                seen <- Ref.of[IO, Boolean](false)
                borrower <- fast.await(headers.head.hash).flatMap(_ => seen.set(true)).start
                _ <- IO.cede
                before <- seen.get
                _ <- IO(assertEquals(before, false))
                _ <- release.complete(()) *> replacement.joinWithNever
                _ <- borrower.joinWithNever
                after <- seen.get
                _ <- IO(assert(after))
              yield ()
            }
        }
        observations <- demoted.get
        _ <- IO(assert(observations.nonEmpty))
      yield ()
    }
  }
