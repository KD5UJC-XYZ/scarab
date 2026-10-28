// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import fs2.{Chunk, Stream}
import scala.concurrent.duration.*

/** A bearer whose reader throws ends that connection with [[NetworkError.BearerDefect]], the
  * original exception kept: in the reference an exception in a bearer thread is `ShutdownPeer`
  * (network `RethrowPolicy.hs`, `ConnectionHandler.hs` `classifyExceptions`), a decision about one
  * connection (issue 463). A transport `IOException` stays a closed connection.
  */
class BearerDefectSuite extends FailsOnUncaught:
  private def throwing(cause: Throwable): Bearer[IO] = new Bearer[IO]:
    def reads = Stream.raiseError[IO](cause)
    def write(bytes: Chunk[Byte]) = IO.unit
    def close = IO.unit

  private def closedWith(cause: Throwable): IO[NetworkError] =
    Peers
      .typed(Mux.resource(throwing(cause), Set(2), Mux.Config()).use(_.closed.timeout(5.seconds)))
      .map(_.fold(e => fail(s"the mux itself failed: ${e.message}"), identity))

  test("an exception of the bearer's reader closes the connection as a BearerDefect"):
    val cause = new IllegalStateException("injected reader defect")
    closedWith(cause).map {
      case NetworkError.BearerDefect(thrown) => assert(thrown eq cause)
      case other                             => fail(s"expected a BearerDefect, got $other")
    }

  test("control: an IOException of the bearer is a closed connection"):
    val cause = new java.io.IOException("connection reset")
    closedWith(cause).map {
      case NetworkError.BearerClosed(NetworkError.Closure.Transport(_, thrown)) =>
        assert(thrown eq cause)
      case other => fail(s"expected a transport closure, got $other")
    }
