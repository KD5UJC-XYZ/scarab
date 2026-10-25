// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, Handshake}

/** ouroboros-network 2fb69829 `Ouroboros/Network/ConnectionManager/Core.hs`
  * (`defaultProtocolIdleTimeout = 5`) and `InboundGovernor.hs`: an accepted connection that sends
  * nothing on a remote-initiated mini-protocol for the idle timeout after its handshake is
  * released.
  */
class ProtocolIdleTimeoutSuite extends FailsOnUncaught:
  import Peers.*

  private val magic = 42L

  private val source = new ChainSyncServer.Source[IO, Bytes]:
    def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
    def poll = IO.pure(None)
    def await = IO.never[ChainSyncServer.Update[Bytes]]
  private val handlers = ServerHandlers[IO, Bytes](
    ChainSyncFixtures.rawItem,
    Resource.pure(source),
    (_, _, _) => IO.pure(None),
    new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes]) = IO.unit
  )

  /** Accept on one side, run the client handshake on the other, and hand the client's mux to `body`
    * together with the server's eventual end and the time the handshake completed.
    */
  private def scenario[A](
      body: Net ?=> (Mux[IO], IO[Option[ConnectionEnd]], FiniteDuration) => IO[A]
  ): IO[Either[NetworkError, A]] =
    typed {
      Resource
        .eval(Bearer.inMemory[IO]())
        .flatMap { (ours, theirs) =>
          for
            ended <- Resource.eval(IO.deferred[Either[NetworkError, ConnectionEnd]])
            _ <- typed(NodeToNodeServer.accept(theirs, magic, handlers))
              .flatMap(ended.complete)
              .background
            mux <- Mux.handshakeResource(
              ours,
              NodeToNodeServer.Protocol,
              Mux.Config(role = Mux.Role.Initiator)
            )
            _ <- Resource.eval(
              mux
                .channel(xyz.kd5ujc.scarab.protocol.Protocols.Handshake)
                .flatMap(
                  HandshakeClient.run(
                    _,
                    Handshake.nodeToNodeOffers(
                      xyz.kd5ujc.scarab.protocol.NtnVersion.LatestReleased,
                      Handshake.Data(magic, initiatorOnly = true)
                    ),
                    10.seconds
                  )
                ) *> mux.installApplication(
                NodeToNodeServer.applicationProtocols(false),
                Set.empty
              )
            )
            began <- Resource.eval(IO.monotonic)
          yield (mux, ended, began)
        }
        .use { (mux, ended, began) =>
          body(mux, ended.tryGet.map(_.flatMap(_.toOption)), began)
        }
    }

  test("a connection silent after the handshake is released at the idle timeout"):
    TestControl
      .executeEmbed(scenario { (_, ended, began) =>
        for
          _ <- IO.sleep(4900.millis)
          early <- ended
          _ <- IO.sleep(200.millis)
          late <- ended
          now <- IO.monotonic
        yield (early, late, now - began)
      })
      .map {
        case Right((early, late, elapsed)) =>
          assertEquals(early, None)
          assertEquals(late, Some(ConnectionEnd.Released))
          assertEquals(elapsed, 5.1.seconds)
        case Left(error) => fail(error.message)
      }

  test("a responder SDU at 4.9 s keeps the connection past the idle timeout"):
    TestControl
      .executeEmbed(scenario { (mux, ended, _) =>
        for
          _ <- IO.sleep(4900.millis)
          channel <- mux.channel(xyz.kd5ujc.scarab.protocol.Protocols.KeepAlive)
          client <- KeepAliveClient[IO](channel)
          _ <- client.ping
          _ <- IO.sleep(2.seconds)
          open <- ended
        yield open
      })
      .map {
        case Right(open) => assertEquals(open, None)
        case Left(error) => fail(error.message)
      }
