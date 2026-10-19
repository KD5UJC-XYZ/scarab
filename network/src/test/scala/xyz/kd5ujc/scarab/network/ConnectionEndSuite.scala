// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import com.comcast.ip4s.*
import munit.CatsEffectSuite

class ConnectionEndSuite extends CatsEffectSuite:
  test("network failures, closures and defects keep their original causes") {
    val transport = new java.io.IOException("reset")
    val closure = NetworkError.Closure.Transport("read", transport)
    val failure = NetworkError.Timeout(2, "idle")
    val defect = new IllegalStateException("bug")
    assertEquals(
      ConnectionEnd.fromNetwork(NetworkError.BearerClosed(closure)),
      ConnectionEnd.Closed(closure)
    )
    ConnectionEnd.fromNetwork(failure) match
      case ConnectionEnd.Failed(error) => assert(error eq failure)
      case other                       => fail(s"unexpected $other")
    ConnectionEnd.fromNetwork(NetworkError.Defect(defect)) match
      case ConnectionEnd.Defect(cause) => assert(cause eq defect)
      case other                       => fail(s"unexpected $other")
    assertEquals(ConnectionEnd.Closed(closure).message, NetworkError.BearerClosed(closure).message)
    assertEquals(ConnectionEnd.Defect(defect).message, NetworkError.Defect(defect).message)
  }

  test("the manager keeps the first typed cause until resource release") {
    val failure = NetworkError.Timeout(2, "idle")
    for
      seen <- Ref.of[IO, Vector[ConnectionEvent]](Vector.empty)
      manager <- ConnectionManager.create[IO](events =
        Some(
          new ConnectionEvents[IO]:
            def emit(event: ConnectionEvent) = seen.update(_ :+ event)
        )
      )
      _ <- Handle
        .allow[ConnectionError] {
          manager.inbound(SocketAddress(ip"10.1.2.3", port"3001")).use { connection =>
            connection
              .close(ConnectionEnd.Failed(failure)) *> connection.close(ConnectionEnd.Released)
          }
        }
        .rescue(e => IO.raiseError(new AssertionError(e.message)))
      events <- seen.get
    yield assertEquals(
      events.collect { case ConnectionEvent.Closed(_, _, end) => end },
      Vector(ConnectionEnd.Failed(failure))
    )
  }
