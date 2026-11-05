// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.Chain
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, ProtocolError}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip, Message, State, Role}
import scala.concurrent.duration.*

class ChainSyncStreamingSuite extends FailsOnUncaught:
  private val config = Mux.Config.nodeToClient.copy(sduSize = 64, receiveBufferLimit = 128)
  private val settings = ChainSyncServer.NodeToClient
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def encoded(message: Message[Bytes]): Bytes =
    ChainSync
      .encode(
        State.Idle,
        Role.Client,
        message,
        ChainSyncFixtures.rawItem,
        ChainSync.Limits.NodeToClient
      )
      .fold(e => fail(e.message), identity)
  private def decode(state: State)(input: Bytes) =
    ChainSync.decodePrefix(
      state,
      Role.Server,
      input,
      ChainSyncFixtures.rawItem,
      ChainSync.Limits.NodeToClient
    )
  private def source(current: Ref[IO, Point], calls: Ref[IO, Vector[Long]]) =
    new ChainSyncServer.Source[IO, Bytes]:
      def intersect(points: Chain[Point]) =
        current.get.flatMap { at =>
          calls.update(_ :+ points.length) *> IO.pure(
            points.find(_ == at).map(_ -> Tip.Origin).toRight(Tip.Origin)
          )
        }
      def poll = current.get.map(p => Some(ChainSyncServer.Update.RollBackward(p, Tip.Origin)))
      def await = IO.never

  test(
    "a request larger than the receive array releases prefixes and preserves following messages"
  ) {
    TestControl
      .executeEmbed {
        Peers
          .typed {
            Peers
              .linked(Set(Protocols.LocalChainSync), config.copy(role = Mux.Role.Initiator), config)
              .use { (client, server) =>
                for
                  c <- client.channel(Protocols.LocalChainSync)
                  s <- server.channel(Protocols.LocalChainSync)
                  at <- Ref.of[IO, Point](Peers.point(9))
                  calls <- Ref.of[IO, Vector[Long]](Vector.empty)
                  _ <- Peers
                    .typed(
                      ChainSyncServer.run(s, ChainSyncFixtures.rawItem, source(at, calls), settings)
                    )
                    .background
                    .use { joined =>
                      val finished = joined.flatMap(_.embedNever)
                      val offered = Vector.fill(20000)(Point.Origin) :+ Peers.point(9)
                      val wire = encoded(Message.FindIntersect(offered)) ++ encoded(
                        Message.RequestNext
                      ) ++ encoded(Message.Done)
                      for
                        _ <- c.sendUnbounded(wire)
                        found <- c.recvUnbounded(decode(State.Intersect))
                        rollback <- c.recvUnbounded(decode(State.NextCanAwait))
                        result <- finished
                        seen <- calls.get
                      yield
                        assertEquals(found, Message.IntersectFound(Peers.point(9), Tip.Origin))
                        assertEquals(rollback, Message.RollBackward(Peers.point(9), Tip.Origin))
                        assertEquals(result, Right(()))
                        assertEquals(seen, Vector(20001L))
                    }
                yield ()
              }
          }
          .map(result => assertEquals(result, Right(())))
      }
      .timeout(20.seconds)
  }

  test("source state is observed after the final list terminator, and no partial offer moves it") {
    TestControl
      .executeEmbed {
        Peers
          .typed {
            Peers
              .linked(Set(Protocols.LocalChainSync), config.copy(role = Mux.Role.Initiator), config)
              .use { (client, server) =>
                for
                  c <- client.channel(Protocols.LocalChainSync)
                  s <- server.channel(Protocols.LocalChainSync)
                  at <- Ref.of[IO, Point](Point.Origin)
                  calls <- Ref.of[IO, Vector[Long]](Vector.empty)
                  _ <- Peers
                    .typed(
                      ChainSyncServer.run(s, ChainSyncFixtures.rawItem, source(at, calls), settings)
                    )
                    .background
                    .use { joined =>
                      val finished = joined.flatMap(_.embedNever)
                      val wire =
                        encoded(Message.FindIntersect(Vector(Point.Origin, Peers.point(9))))
                      for
                        _ <- c.sendUnbounded(wire.take(wire.size - 1)) *> c.flush
                        // TestControl runs runnable fibers before advancing this virtual delay.
                        _ <- IO.sleep(1.second)
                        before <- calls.get
                        _ <- at.set(Peers.point(9))
                        _ <- c.sendUnbounded(bytes("ff") ++ encoded(Message.Done))
                        found <- c.recvUnbounded(decode(State.Intersect))
                        result <- finished
                      yield
                        assertEquals(before, Vector.empty[Long])
                        assertEquals(found, Message.IntersectFound(Peers.point(9), Tip.Origin))
                        assertEquals(result, Right(()))
                    }
                yield ()
              }
          }
          .map(result => assertEquals(result, Right(())))
      }
      .timeout(20.seconds)
  }

  test("a malformed suffix after an offered match fails before consulting the source") {
    TestControl
      .executeEmbed {
        Peers
          .typed {
            Peers
              .linked(Set(Protocols.LocalChainSync), config.copy(role = Mux.Role.Initiator), config)
              .use { (client, server) =>
                for
                  c <- client.channel(Protocols.LocalChainSync)
                  s <- server.channel(Protocols.LocalChainSync)
                  at <- Ref.of[IO, Point](Point.Origin)
                  calls <- Ref.of[IO, Vector[Long]](Vector.empty)
                  result <- Peers
                    .typed(
                      ChainSyncServer.run(s, ChainSyncFixtures.rawItem, source(at, calls), settings)
                    )
                    .background
                    .use { joined =>
                      val finished = joined.flatMap(_.embedNever)
                      c.sendUnbounded(bytes("82049f8000ff")) *> finished
                    }
                  seen <- calls.get
                  cursor <- at.get
                yield
                  assertEquals(
                    result,
                    Left(
                      NetworkError
                        .Decode(Protocols.LocalChainSync, ProtocolError.ExpectedDefiniteArray)
                    )
                  )
                  assertEquals(seen, Vector.empty[Long])
                  assertEquals(cursor, Point.Origin)
              }
          }
          .map(result => assertEquals(result, Right(())))
      }
      .timeout(20.seconds)
  }
