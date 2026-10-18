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
      _ <- raise
        .raise[NetworkError.BearerClosed, Unit](
          NetworkError.BearerClosed(NetworkError.Closure.Aborted)
        )
        .start
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
          _ <- client.abort(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
          outcome <- pinger.join
        yield outcome
      }
    }.map {
      case Right(outcome) => assert(outcome.isSuccess, s"the pinger ended with $outcome")
      case Left(error)    => fail(s"unexpected $error")
    }
  }

  for unexpected <- List(false, true)
  do
    test(
      s"a scoped keep-alive fiber preserves ${if unexpected then "defect" else "transport closure"}"
    ) {
      val cause =
        if unexpected then new IllegalStateException("injected")
        else new java.io.IOException("closed")
      val broken = new MuxChannel[IO]:
        def protocol = 8
        def send(bytes: xyz.kd5ujc.scarab.codec.Bytes)(using cats.mtl.Raise[IO, NetworkError]) =
          IO.raiseError[Unit](cause)
        def flush = IO.unit
        def recv[A](maxBytes: Int)(
            decode: xyz.kd5ujc.scarab.codec.Bytes => xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult[
              A
            ]
        )(using cats.mtl.Raise[IO, NetworkError]): IO[A] = IO.never
      for
        ended <- cats.effect.Deferred[IO, NetworkError]
        mux = new Mux[IO]:
          def channel(protocol: Int)(using cats.mtl.Raise[IO, NetworkError]) = IO.pure(broken)
          def channel(protocol: Int, role: Mux.Role)(using cats.mtl.Raise[IO, NetworkError]) =
            IO.pure(broken)
          def closed = ended.get
          def abort(reason: NetworkError) = ended.complete(reason).void
        outcome <- KeepAliveClient
          .pinger(broken, mux, KeepAliveClient.Settings())
          .start
          .flatMap(_.join)
        reason <- ended.get
      yield
        assert(outcome.isSuccess)
        reason match
          case NetworkError.Defect(found) if unexpected    => assert(found eq cause)
          case _: NetworkError.BearerClosed if !unexpected => ()
          case other                                       => fail(s"unexpected $other")
    }
