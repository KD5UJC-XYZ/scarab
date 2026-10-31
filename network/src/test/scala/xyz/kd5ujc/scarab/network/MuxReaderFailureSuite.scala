// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.effect.syntax.all.*
import fs2.Chunk
import scala.concurrent.duration.*

import Peers.*

/** A bearer whose reads fail ends the connection with that failure, and the runtime is told nothing
  * else: the failure does not cross fs2's `merge` (inside `Pull.timed`), whose result fiber ends
  * with an upstream's error before the downstream joins it, so that cats-effect reported the error
  * as well (issue 647).
  */
class MuxReaderFailureSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 2.minutes

  // Before the fix, the default runtime reported the failure about once in 2,500 rounds.
  private val rounds = 20000

  test("a failing bearer read is the connection's closure and nothing the runtime reports") {
    val cause = new IllegalStateException("injected")
    val bearer = new Bearer[IO]:
      def reads = fs2.Stream.raiseError[IO](cause)
      def write(bytes: Chunk[Byte]) = IO.never
      def close = IO.unit
    (1 to rounds).toList
      .parTraverseN(16)(_ => typed(Mux.resource(bearer, Set(2)).use(_.closed)))
      .map { results =>
        val wrong = results.filterNot {
          case Right(NetworkError.BearerDefect(found)) => found eq cause
          case _                                       => false
        }
        assertEquals(wrong, Nil)
      }
  }
