// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import xyz.kd5ujc.scarab.network.{ConnectionEnd, HandlerFailure, NetworkError}
import xyz.kd5ujc.scarab.node.log.NodeEvent

class ListenerFailuresSuite extends munit.CatsEffectSuite:
  private def reported(failure: HandlerFailure[NetworkError]) =
    for
      events <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      _ <- ListenerFailures.report[IO](
        "listener-test",
        failure,
        event => events.update(_ :+ event),
        fatal => fatals.update(_ :+ fatal)
      )
      observed <- events.get
      stopped <- fatals.get
    yield (observed, stopped)

  test("an intentional network close reports the original reason without stopping the node") {
    val error = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
    reported(HandlerFailure.Domain(error)).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.fromNetwork(error)))
          )
        )
      )
      assertEquals(fatals, Vector.empty)
    }
  }

  test("an unexpected handler failure stops with the original defect and reports the connection") {
    val cause = new IllegalStateException("listener handler defect")
    reported(HandlerFailure.Defect(cause)).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Defect(cause))
          )
        )
      )
      assertEquals(fatals, Vector(Fatal.Defect(cause)))
    }
  }

  test("a typed network defect also retains its original cause and stops the node") {
    val cause = new IllegalArgumentException("typed defect")
    reported(HandlerFailure.Domain(NetworkError.Defect(cause))).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.Defect(cause)))
          )
        )
      )
      assertEquals(fatals, Vector(Fatal.Defect(cause)))
    }
  }
