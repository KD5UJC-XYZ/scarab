// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import scala.concurrent.duration.*

import Peers.*

/** The fibers a connection runs for its whole life end without an uncaught failure when the
  * connection is lost under them (issue 130). The multiplexer's channels raise through the `Raise`
  * of the scope that opened the connection; a background fiber that lets such a raise escape ends
  * with a `cats.mtl.Handle$Submarine` that nobody can handle, and the runtime reports it.
  */
class BackgroundFibersSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 30.seconds

  test("the runtime of these suites sees a Submarine that escapes its scope (the harness works)") {
    // a fiber started inside an `allow` scope raises after the scope has ended: nobody can handle it
    for
      escaped <- cats.effect.Deferred[IO, cats.mtl.Raise[IO, NetworkError]]
      _ <- cats.mtl.Handle.allow[NetworkError](escaped.complete(summon).void).rescue(_ => IO.unit)
      raise <- escaped.get
      _ <- raise.raise[NetworkError.BearerClosed, Unit](NetworkError.BearerClosed("late")).start
      _ <- IO.sleep(300.millis)
      seen = takeUncaught()
    yield
      assertEquals(seen.size, 1)
      assertEquals(seen.head.getClass.getName, "cats.mtl.Handle$Submarine")
  }

  test("a keep-alive ping that is waiting for its answer when the connection is lost") {
    typed {
      linked(protocols = Set(8)).use { (client, _) =>
        for
          k <- client.channel(8)
          // the peer never answers: the pinger is inside `recv` when the connection goes
          pinger <- KeepAliveClient
            .pinger(k, client, KeepAliveClient.Settings(responseTimeout = 30.seconds))
            .start
          _ <- IO.sleep(200.millis)
          _ <- client.abort(NetworkError.BearerClosed("connection lost"))
          outcome <- pinger.join
        yield outcome
      }
    }.map {
      case Right(outcome) => assert(outcome.isSuccess, s"the pinger ended with $outcome")
      case Left(error)    => fail(s"unexpected $error")
    }
  }
