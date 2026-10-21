// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.{Deferred, IO}
import cats.effect.testkit.TestControl
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import scala.concurrent.duration.*

class UpstreamGenesisServerSuite extends FailsOnUncaught:
  test("native scheduler starts offline until the first actual state is published") {
    UpstreamGenesisCases.read.flatMap {
      case Left(error) => IO(fail(error.toString))
      case Right(cases) =>
        val scenario = cases.headOption.fold(fail("native corpus is empty"))(identity)
        val universe = UpstreamGenesisBlocks.build(scenario).fold(p => fail(p.toString), identity)
        TestControl.executeEmbed {
          for
            server <- UpstreamGenesisServer.create(universe, p => IO(fail(p.toString)))
            entered <- Deferred[IO, Unit]
            completed <- Deferred[IO, Unit]
            request <- (entered.complete(()) *> server.chainSync.intersect(Vector(Point.Origin)))
              .flatTap(_ => completed.complete(()))
              .start
            _ <- entered.get *> IO.sleep(1.second)
            early <- completed.tryGet
            _ <- IO(assertEquals(early, None))
            _ <- server.publish(
              UpstreamGenesisCases.NodeState(Point.Origin, Point.Origin, Point.Origin)
            )
            reply <- request.joinWithNever
          yield assertEquals(reply, Right(Point.Origin -> Tip.Origin))
        }
    }
  }
