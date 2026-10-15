// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import com.comcast.ip4s.*
import fs2.io.net.Network
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSyncFixtures
import xyz.kd5ujc.scarab.protocol.ChainSync.Message
import munit.CatsEffectSuite

import Peers.*

class NodeToNodeSuite extends CatsEffectSuite:
  private val magic = 42L
  private val script = List(
    List(Message.RollForward(header(1), tip(2))),
    List(Message.AwaitReply, Message.RollBackward(point(0), tip(2)))
  )
  private val ranges = Map((point(1), point(1)) -> Vector(block(1)))

  private def peer(mux: Mux[IO], pings: Ref[IO, Int])(using Net) =
    serveAll(mux, magic, ps => Message.IntersectFound(ps.head, tip(2)), script, ranges, pings)

  private def conversation(connection: NodeToNodeConnection[IO, Bytes])(using Net) =
    for
      found <- connection.chainSync.findIntersect(Vector(point(0)))
      first <- connection.chainSync.requestNext
      body <- connection.blockFetch.requestRange(point(1), point(1))
      second <- connection.chainSync.requestNext
    yield (connection.version, found, first, body, second)

  private val expected = Right(
    (
      14,
      Some(point(0) -> tip(2)),
      ChainSyncClient.Update.RollForward(header(1), tip(2)),
      Some(Vector(block(1))),
      ChainSyncClient.Update.RollBackward(point(0), tip(2))
    )
  )

  test("ChainSync and BlockFetch run on one in-memory connection"):
    typed {
      for
        pings <- Ref.of[IO, Int](0)
        result <- (for
          pair <- Resource.eval(Bearer.inMemory[IO]())
          (clientEnd, serverEnd) = pair
          server <- Mux.resource(serverEnd, Set(0, 2, 3, 8), Mux.Config(role = Mux.Role.Responder))
          _ <- peer(server, pings)
          connection <- NodeToNode.over(clientEnd, magic, ChainSyncFixtures.rawItem)
        yield connection).use(conversation)
      yield result
    }.map(r => assertEquals(r, expected))

  test("a refused handshake fails the connection resource"):
    typed {
      for
        pings <- Ref.of[IO, Int](0)
        result <- (for
          pair <- Resource.eval(Bearer.inMemory[IO]())
          server <- Mux.resource(pair._2, Set(0, 2, 3, 8), Mux.Config(role = Mux.Role.Responder))
          _ <- serveAll(server, 7L, _ => Message.IntersectNotFound(tip(0)), Nil, Map.empty, pings)
          connection <- NodeToNode.over(pair._1, magic, ChainSyncFixtures.rawItem)
        yield connection).use(c => IO.pure(c.version))
      yield result
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.HandshakeRefused]), r.toString))

  test("TCP: a host name resolves and one socket carries every protocol"):
    typed {
      Network[IO].bind(SocketAddress(ip"127.0.0.1", port"0")).use { server =>
        val bound = server.address.asIpUnsafe
        val accept = server.accept
          .map(socket =>
            fs2.Stream.eval(
              (for
                pings <- Resource.eval(Ref.of[IO, Int](0))
                server <- Mux.resource(
                  Bearer.fromSocket(socket),
                  Set(0, 2, 3, 8),
                  Mux.Config(role = Mux.Role.Responder)
                )
                _ <- peer(server, pings)
              yield ()).useForever.attempt.void
            )
          )
          .parJoinUnbounded
          .compile
          .drain
        accept.background.use { _ =>
          NodeToNode
            .connect[IO, Bytes](
              SocketAddress(host"localhost", bound.port),
              magic,
              ChainSyncFixtures.rawItem
            )
            .use(conversation)
        }
      }
    }.map(r => assertEquals(r, expected))

  test("TCP: a closed port is a typed connect error"):
    typed(
      NodeToNode
        .connect[IO, Bytes](
          SocketAddress(host"localhost", port"1"),
          magic,
          ChainSyncFixtures.rawItem
        )
        .use(c => IO.pure(c.version))
    ).map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.Connect]), r.toString))

  test("TCP: an unresolvable host name is a typed connect error"):
    typed(
      NodeToNode
        .connect[IO, Bytes](
          SocketAddress(host"no-such-host.invalid", port"3001"),
          magic,
          ChainSyncFixtures.rawItem
        )
        .use(c => IO.pure(c.version))
    ).map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.Connect]), r.toString))
