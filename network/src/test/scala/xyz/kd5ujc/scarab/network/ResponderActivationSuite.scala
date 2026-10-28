// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures}

/** Native cardano-diffusion NodeToNode.hs:310 and network-mux Mux.hs:424–449: KeepAlive's
  * StartOnDemandAny is activated by remote initiator bytes, independently of our own clients.
  */
class ResponderActivationSuite extends FailsOnUncaught:
  import Peers.*

  private val source = new ChainSyncServer.Source[IO, Bytes]:
    def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
    def poll = IO.pure(None)
    def await = IO.never[ChainSyncServer.Update[Bytes]]
  private val handlers = ServerHandlers[IO, Bytes](
    ChainSyncFixtures.rawItem,
    Resource.pure(source),
    (_, _) => IO.pure(None),
    new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes])(using
          cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
      ) = IO.unit
  )

  private def duplex(using Net): Resource[IO, (Mux[IO], Mux[IO])] =
    linked(
      protocols = NodeToNodeServer.Protocol,
      client = Mux.Config(duplex = true),
      server = Mux.Config(role = Mux.Role.Responder, duplex = true)
    )

  private def assertOpen(mux: Mux[IO]): IO[Unit] =
    mux.closed
      .map(Option(_))
      .timeoutTo(1.nanosecond, IO.pure(None))
      .map(seen => assertEquals(seen, None))

  private def keepAliveTimeout(reason: NetworkError): Unit = reason match
    case NetworkError.Timeout(protocol, phase) =>
      assertEquals(protocol, Protocols.KeepAlive)
      assertEquals(phase, "client")
    case other => fail(s"expected the responder's KeepAlive deadline, got $other")

  test("unused duplex responders stay open beyond 97s; our own replies do not arm them") {
    TestControl
      .executeEmbed(typed {
        duplex
          .flatMap { (ours, remote) =>
            NodeToNodeServer.start(ours, handlers, initiators = true).as(ours -> remote)
          }
          .use { (ours, remote) =>
            for
              replies <- remote.channel(Protocols.ChainSync, Mux.Role.Responder)
              _ <- replies.send(Bytes(Vector(0.toByte)))
              _ <- IO.sleep(200.seconds)
              _ <- assertOpen(ours)
            yield ()
          }
      })
      .map(result => assertEquals(result, Right(())))
  }

  for protocol <- Vector(Protocols.ChainSync, Protocols.BlockFetch) do
    test(s"remote protocol $protocol arms the unchanged 97s KeepAlive silence bound") {
      TestControl
        .executeEmbed(typed {
          duplex
            .flatMap { (ours, remote) =>
              NodeToNodeServer.start(ours, handlers, initiators = true).as(ours -> remote)
            }
            .use { (ours, remote) =>
              for
                _ <- IO.sleep(200.seconds)
                _ <- assertOpen(ours)
                began <- IO.monotonic
                channel <- remote.channel(protocol, Mux.Role.Initiator)
                _ <-
                  if protocol == Protocols.ChainSync then
                    ChainSyncClient[IO, Bytes](channel, ChainSyncFixtures.rawItem)
                      .flatMap(_.findIntersect(Vector(ChainSync.Point.Origin)))
                      .void
                  else
                    BlockFetchClient[IO](channel).flatMap(_.requestRange(point(1), point(2))).void
                _ <- ours.awaitResponderActivity
                _ <- IO.sleep(96.seconds)
                _ <- assertOpen(ours)
                reason <- ours.closed
                ended <- IO.monotonic
                _ <- IO {
                  keepAliveTimeout(reason)
                  assertEquals(ended - began, 97.seconds)
                }
              yield ()
            }
        })
        .map(result => assertEquals(result, Right(())))
    }

  test("the first KeepAlive cookie activates the responder and is answered immediately") {
    TestControl
      .executeEmbed(typed {
        duplex
          .flatMap { (ours, remote) =>
            NodeToNodeServer.start(ours, handlers, initiators = true).as(ours -> remote)
          }
          .use { (ours, remote) =>
            for
              _ <- IO.sleep(200.seconds)
              channel <- remote.channel(Protocols.KeepAlive, Mux.Role.Initiator)
              client <- KeepAliveClient[IO](channel)
              duration <- client.ping
              _ <- IO(assertEquals(duration, Duration.Zero))
              _ <- IO.sleep(96.seconds)
              _ <- assertOpen(ours)
              reason <- ours.closed
              _ <- IO(keepAliveTimeout(reason))
            yield ()
          }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("waiting for responder activation returns a typed connection failure") {
    val reason = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
    TestControl
      .executeEmbed(typed {
        duplex.use { (ours, _) =>
          ours.abort(reason) *> ours.awaitResponderActivity
        }
      })
      .map(result => assertEquals(result, Left(reason)))
  }

  test("negotiated PeerSharing activates KeepAlive with the unchanged deadline") {
    TestControl
      .executeEmbed(typed {
        duplex
          .flatMap { (ours, remote) =>
            NodeToNodeServer
              .start(ours, handlers, peerSharing = true, initiators = true)
              .as(ours -> remote)
          }
          .use { (ours, remote) =>
            for
              _ <- IO.sleep(200.seconds)
              channel <- remote.channel(Protocols.PeerSharing, Mux.Role.Initiator)
              client <- PeerSharingClient[IO](channel)
              began <- IO.monotonic
              peers <- client.request(xyz.kd5ujc.scarab.protocol.PeerSharing.Amount.saturating(1))
              _ <- IO(assertEquals(peers, Vector.empty))
              reason <- ours.closed
              ended <- IO.monotonic
              _ <- IO {
                keepAliveTimeout(reason)
                assertEquals(ended - began, 97.seconds)
              }
            yield ()
          }
      })
      .map(result => assertEquals(result, Right(())))
  }
