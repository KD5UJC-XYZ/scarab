// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Fixtures
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.{GenesisBlockFetch, Gsm}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

import FetchTesting.*

class GenesisFetchCoordinatorSuite extends FailsOnUncaught:
  test(
    "Genesis handover permits overlap, retains old flight, and wakes cached recovery borrowers"
  ) {
    TestControl.executeEmbed {
      val headers = Fixtures.chain(2)
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
          for
            _ <- slow.follows(headers)
            _ <- fast.follows(headers)
            _ <- coordinator.step
            first <- slow.requestedHeaders
            // The dynamo starves past the grace period and is demoted (Decision/Genesis.hs).
            _ <- IO.sleep(11.seconds) *> coordinator.step
            replacement <- fast.requestedHeaders
            flight <- coordinator.inFlight
            seen <- Ref.of[IO, Boolean](false)
            borrower <- fast.await(headers.head.hash).flatMap(_ => seen.set(true)).start
            _ <- IO.cede
            before <- seen.get
            _ <- fetched.update(_ + headers.head.hash) *> coordinator.refreshGenesis
            _ <- borrower.joinWithNever
            after <- seen.get
          yield
            assertEquals(first, headers)
            assertEquals(replacement, headers)
            assertEquals(flight(slow.identity).blocks, headers.map(_.hash).toSet)
            assertEquals(flight(slow.identity).requests, 1)
            assertEquals(before, false)
            assert(after)
        }
        observations <- demoted.get
        _ <- IO(assert(observations.nonEmpty))
      yield ()
    }
  }
