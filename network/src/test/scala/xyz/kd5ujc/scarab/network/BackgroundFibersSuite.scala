// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import scala.concurrent.duration.*

import Peers.*

/** The fibers a connection runs for its whole life end without an uncaught failure when the
  * connection is lost under them (issue 130). [[MuxChannel]] takes the calling fiber's `Raise`;
  * [[Fibers.scoped]] captures failures as values before they cross the fiber boundary. The harness
  * also demonstrates that an escaped capability is reported as an uncaught
  * `cats.mtl.Handle$Submarine`.
  */
class BackgroundFibersSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 30.seconds

  test("a scoped worker captures defects raised while its effect is constructed") {
    val cause = new IllegalStateException("worker construction defect")
    IO.defer(Fibers.scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(throw cause)).map {
      result => assertEquals(result, Left(NetworkError.Defect(cause)))
    }
  }

  test("the runtime of these suites sees a Submarine that escapes its scope (the harness works)") {
    // Deliberately use the completed scope's capability in a new fiber to test the reporter.
    for
      escaped <- cats.effect.Deferred[IO, cats.mtl.Raise[IO, NetworkError]]
      _ <- cats.mtl.Handle.allow[NetworkError](escaped.complete(summon).void).rescue(_ => IO.unit)
      raise <- escaped.get
      _ <- raise
        .raise[NetworkError.BearerClosed, Unit](
          NetworkError.BearerClosed(NetworkError.Closure.Aborted)
        )
        .start
      // the runtime reports the failure when the fiber ends
      seen <- awaitUncaught(10.seconds)
    yield
      assertEquals(seen.size, 1)
      assertEquals(seen.head.getClass.getName, "cats.mtl.Handle$Submarine")
  }

  test("a keep-alive ping that is waiting for its answer when the connection is lost") {
    Settled { settle =>
      typed {
        linked(protocols = Set(8)).use { (client, _) =>
          for
            k <- client.channel(8)
            // The peer never answers: once every fiber has done all it can, the pinger has sent its
            // ping and waits in `recv` for the answer.
            pinger <- KeepAliveClient
              .pinger(k, client, KeepAliveClient.Settings(responseTimeout = 30.seconds))
              .start
            _ <- settle
            _ <- client.abort(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
            outcome <- pinger.join
          yield outcome
        }
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
          def installApplication(initiators: Set[Int], responders: Set[Int])(using
              cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
          ): IO[Unit] = IO.unit
          def awaitResponderActivity(using
              cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
          ) =
            IO.never[Unit]
          def channel(protocol: Int)(using cats.mtl.Raise[IO, NetworkError]) = IO.pure(broken)
          def channel(protocol: Int, role: Mux.Role)(using cats.mtl.Raise[IO, NetworkError]) =
            IO.pure(broken)
          def closed = ended.get
          def failure = ended.tryGet
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
