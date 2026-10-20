// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures}
import xyz.kd5ujc.scarab.protocol.ChainSync.Message
import munit.CatsEffectSuite
import scala.concurrent.duration.*

import Peers.*

class ClientSuite extends FailsOnUncaught:
  private val Origin = ChainSync.Point.Origin

  private def chainSync(
      intersect: Vector[ChainSync.Point] => Message[Bytes],
      script: List[List[Message[Bytes]]],
      settings: ChainSyncClient.Settings = ChainSyncClient.Settings()
  )(program: ChainSyncClient[IO, Bytes] => IO[Any])(using Net) =
    linked(protocols = Set(2)).use { (client, server) =>
      for
        c <- client.channel(2)
        s <- server.channel(2)
        _ <- serveChainSync(s, intersect, script).start
        api <- ChainSyncClient(c, ChainSyncFixtures.rawItem, settings)
        result <- program(api)
      yield result
    }

  private def found(points: Vector[ChainSync.Point]): Message[Bytes] =
    Message.IntersectFound(points.head, tip(9))

  test("ChainSync: findIntersect reports the peer's point and tip"):
    typed(chainSync(found, Nil)(_.findIntersect(Vector(point(3), Origin))))
      .map(r => assertEquals(r, Right(Some(point(3) -> tip(9)))))

  test("ChainSync: no common point is None"):
    typed(chainSync(_ => Message.IntersectNotFound(tip(9)), Nil)(_.findIntersect(Vector(Origin))))
      .map(r => assertEquals(r, Right(None)))

  test("ChainSync: an intersection that was not offered is a protocol violation"):
    typed(
      chainSync(_ => Message.IntersectFound(point(8), tip(9)), Nil)(_.findIntersect(Vector(Origin)))
    )
      .map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IllegalTransition]), r.toString))

  test("ChainSync: AwaitReply is waited out and roll instructions are returned in order"):
    val script = List(
      List(Message.RollForward(header(1), tip(1))),
      List(Message.AwaitReply, Message.RollBackward(point(0), tip(1))),
      List(Message.RollForward(header(2), tip(2)))
    )
    typed(chainSync(found, script) { api =>
      for
        a <- api.requestNext
        b <- api.requestNext
        c <- api.requestNext
      yield List(a, b, c)
    }).map(r =>
      assertEquals(
        r,
        Right(
          List(
            ChainSyncClient.Update.RollForward(header(1), tip(1)),
            ChainSyncClient.Update.RollBackward(point(0), tip(1)),
            ChainSyncClient.Update.RollForward(header(2), tip(2))
          )
        )
      )
    )

  test("ChainSync: findIntersect after Done is an illegal transition"):
    typed(chainSync(found, Nil)(api => api.done *> api.findIntersect(Vector(Origin))))
      .map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IllegalTransition]), r.toString))

  test("ChainSync: a reply illegal in the current state is a decode error"):
    // AwaitReply is only legal after RequestNext, not after FindIntersect.
    typed(
      linked(protocols = Set(2)).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- (s.recv(100)(b => ChainSync.DecodeResult.Decoded((), b.size)) *>
            sendChainSync(s, ChainSync.State.NextCanAwait, List(Message.AwaitReply))).start
          api <- ChainSyncClient(c, ChainSyncFixtures.rawItem)
          result <- api.findIntersect(Vector(Origin))
        yield result
      }
    ).map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.Decode]), r.toString))

  test("ChainSync: an unanswered request times out in its protocol state"):
    TestControl
      .executeEmbed(typed(chainSync(found, Nil)(_.requestNext)))
      .map(r => assertEquals(r, Left(NetworkError.Timeout(2, "NextCanAwait"))))

  test("ChainSync: an unanswered AwaitReply times out in the must-reply state"):
    TestControl
      .executeEmbed(
        typed(chainSync(found, List(List(Message.AwaitReply)))(_.requestNext))
      )
      .map(r => assertEquals(r, Left(NetworkError.Timeout(2, "NextMustReply"))))

  private def blockFetch(ranges: Map[(ChainSync.Point, ChainSync.Point), Vector[Bytes]])(
      program: BlockFetchClient[IO] => IO[Any]
  )(using Net) =
    linked(protocols = Set(3)).use { (client, server) =>
      for
        c <- client.channel(3)
        s <- server.channel(3)
        _ <- serveBlockFetch(s, ranges).start
        api <- BlockFetchClient(c, BlockFetchClient.Settings())
        result <- program(api)
      yield result
    }

  test("BlockFetch: a range returns its blocks, and requests can follow each other"):
    val blocks = Vector(block(1), block(2), block(3))
    val ranges = Map((point(1), point(3)) -> blocks, (point(5), point(5)) -> Vector(block(5)))
    typed(blockFetch(ranges) { api =>
      for
        first <- api.requestRange(point(1), point(3))
        second <- api.requestRange(point(5), point(5))
      yield (first, second)
    }).map(r => assertEquals(r, Right((Some(blocks), Some(Vector(block(5)))))))

  test("BlockFetch: a range the peer lacks is None and the connection stays usable"):
    typed(blockFetch(Map((point(2), point(2)) -> Vector(block(2)))) { api =>
      for
        none <- api.requestRange(point(1), point(1))
        some <- api.requestRange(point(2), point(2))
      yield (none, some)
    }).map(r => assertEquals(r, Right((None, Some(Vector(block(2)))))))

  test("BlockFetch: a block bigger than one SDU is reassembled"):
    val big = Bytes(Vector.fill(150000)(7.toByte))
    typed(blockFetch(Map((point(1), point(1)) -> Vector(big)))(_.requestRange(point(1), point(1))))
      .map(r => assertEquals(r, Right(Some(Vector(big)))))

  // D3: exercise reassembly across many SDUs and blocks, preserving the full payload.
  // Throughput is a benchmark concern; correctness must not depend on runner speed.
  test("BlockFetch: a range of 100 blocks of 90 KB preserves every block"):
    val blocks = Vector.tabulate(100)(i => Bytes(Vector.fill(90000)(i.toByte)))
    typed(blockFetch(Map((point(1), point(2)) -> blocks))(_.requestRange(point(1), point(2))))
      .map(r => assertEquals(r, Right(Some(blocks))))

  test("BlockFetch: a range with Origin is rejected before anything is sent"):
    typed(blockFetch(Map.empty)(_.requestRange(Origin, point(1))))
      .map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IllegalTransition]), r.toString))

  test("BlockFetch: an unanswered request times out in the busy state"):
    TestControl
      .executeEmbed(typed(linked(protocols = Set(3)).use { (client, _) =>
        client
          .channel(3)
          .flatMap(BlockFetchClient(_))
          .flatMap(_.requestRange(point(1), point(1)))
      }))
      .map(r => assertEquals(r, Left(NetworkError.Timeout(3, "Busy"))))

  test("BlockFetch: more blocks than the request limit is an ingress limit error"):
    val settings =
      BlockFetchClient.Settings(request = xyz.kd5ujc.scarab.protocol.BlockFetch.RequestLimits(2))
    typed(
      linked(protocols = Set(3)).use { (client, server) =>
        for
          c <- client.channel(3)
          s <- server.channel(3)
          _ <- serveBlockFetch(
            s,
            Map((point(1), point(3)) -> Vector(block(1), block(2), block(3)))
          ).start
          api <- BlockFetchClient(c, settings)
          result <- api.requestRange(point(1), point(3))
        yield result
      }
    ).map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IngressLimit]), r.toString))

  private def keepAlive(
      answer: Int => Option[Int],
      settings: KeepAliveClient.Settings = KeepAliveClient.Settings()
  )(program: (KeepAliveClient[IO], Mux[IO], Ref[IO, Int]) => IO[Any])(using Net) =
    linked(protocols = Set(8)).use { (client, server) =>
      for
        c <- client.channel(8)
        s <- server.channel(8)
        pings <- Ref.of[IO, Int](0)
        _ <- serveKeepAlive(s, pings, answer).start
        api <- KeepAliveClient(c, settings)
        result <- program(api, client, pings)
      yield result
    }

  test("KeepAlive: ping round-trips and cookies advance"):
    typed(keepAlive(Some(_))((api, _, pings) => api.ping *> api.ping *> pings.get))
      .map(r => assertEquals(r, Right(2)))

  test("KeepAlive: a mismatched cookie is a protocol violation"):
    typed(keepAlive(c => Some(c + 1))((api, _, _) => api.ping))
      .map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IllegalTransition]), r.toString))

  test("KeepAlive: a missing response times out"):
    TestControl
      .executeEmbed(typed(keepAlive(_ => None)((api, _, _) => api.ping)))
      .map(r => assertEquals(r, Left(NetworkError.Timeout(8, "server"))))

  test("KeepAlive: the background pinger pings on its interval"):
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(8)).use { (client, server) =>
          for
            c <- client.channel(8)
            s <- server.channel(8)
            pings <- Ref.of[IO, Int](0)
            _ <- serveKeepAlive(s, pings).start
            count <- KeepAliveClient
              .background(c, client)
              .surround(IO.sleep(100.seconds) *> pings.get)
          yield count
        }
      })
      .map(r => assertEquals(r, Right(4)))

  test("KeepAlive: a dead peer aborts the multiplexer so pending operations fail"):
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(2, 8)).use { (client, _) =>
          for
            c <- client.channel(2)
            k <- client.channel(8)
            api <- ChainSyncClient(
              c,
              ChainSyncFixtures.rawItem,
              ChainSyncClient.Settings(canAwaitTimeout = 1.hour)
            )
            result <- KeepAliveClient.background(k, client).surround(api.requestNext)
          yield result
        }
      })
      .map {
        // the pinger's own failure is the reason the whole connection ends with
        case Left(NetworkError.Timeout(protocol, _)) => assertEquals(protocol, Protocols.KeepAlive)
        case other                                   => fail(s"unexpected $other")
      }
