// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  ChainSyncFixtures,
  Handshake,
  KeepAlive,
  TxSubmission2
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import munit.CatsEffectSuite
import scala.concurrent.duration.*

import Peers.*

/** Our servers against our own clients over in-memory bearers, in both directions. */
class ServerSuite extends FailsOnUncaught:
  private val magic = 42L

  /** A chain of blocks numbered 1..n, mutable so tests can extend and fork it. */
  private final class Chain(initial: Vector[Int]):
    val blocks: Ref[IO, Vector[Int]] = Ref.unsafe(initial)
    def tip: IO[Tip] = blocks.get.map(b => b.lastOption.fold(Tip.Origin)(n => Peers.tip(n)))

    def source: Resource[IO, ChainSyncServer.Source[IO, Bytes]] =
      Resource.eval(Ref.of[IO, Int](0)).map { cursor =>
        new ChainSyncServer.Source[IO, Bytes]:
          def intersect(points: Vector[Point]) =
            for
              chain <- blocks.get
              t <- tip
              found = points.find {
                case Point.Origin => true
                case p            => chain.exists(n => Peers.point(n) == p)
              }
              _ <- found.traverse_ {
                case Point.Origin => cursor.set(0)
                case p            => cursor.set(chain.indexWhere(n => Peers.point(n) == p) + 1)
              }
            yield found.map(_ -> t).toRight(t)
          def poll =
            for
              chain <- blocks.get
              at <- cursor.get
              t <- tip
              next <-
                if at > chain.size then
                  // forked below the cursor: roll back to the new tip
                  cursor
                    .set(chain.size)
                    .as(
                      Some(
                        ChainSyncServer.Update
                          .RollBackward(
                            chain.lastOption.fold(Point.Origin: Point)(Peers.point),
                            t
                          )
                      )
                    )
                else if at < chain.size then
                  cursor
                    .set(at + 1)
                    .as(
                      Some(ChainSyncServer.Update.RollForward(header(chain(at)), t))
                    )
                else IO.pure(None)
            yield next
          def await: IO[ChainSyncServer.Update[Bytes]] =
            poll.flatMap(_.fold(IO.sleep(5.millis) *> await)(IO.pure))
      }

    def blockSource: BlockFetchServer.Blocks[IO] = (from, to, max) =>
      blocks.get.map { chain =>
        val i = chain.indexWhere(n => Peers.point(n) == from)
        val j = chain.indexWhere(n => Peers.point(n) == to)
        Option.when(i >= 0 && j >= i && j - i < max)(
          Stream.emits(chain.slice(i, j + 1).map(Peers.block).toList).covary[IO]
        )
      }

  private val noTxs: TxSubmissionServer.Sink[IO] = new TxSubmissionServer.Sink[IO]:
    def known(id: Bytes) = IO.pure(false)
    def submit(originals: Vector[Bytes]) = IO.unit

  private def handlers(chain: Chain, sink: TxSubmissionServer.Sink[IO] = noTxs) =
    ServerHandlers[IO, Bytes](ChainSyncFixtures.rawItem, chain.source, chain.blockSource, sink)

  private val txBytes: Int => Bytes = n =>
    // A Conway-shaped transaction: [body map, witnesses map, true, null]
    Bytes(Vector(0x84, 0xa1, n, 0x01, 0xa0, 0xf5, 0xf6).map(_.toByte))

  test("handshake: duplex needs both sides, and the highest common version wins"):
    def negotiate(clientOnly: Boolean, serverDuplex: Boolean, clientVersions: Vector[Int]) =
      typed {
        linked(protocols = Set(0)).use { (client, server) =>
          for
            c <- client.channel(0)
            s <- server.channel(0)
            serving <- typed(
              HandshakeServer.run(s, HandshakeServer.offers(magic, serverDuplex))
            ).start
            offers = clientVersions.map(_ -> Handshake.Data(magic, initiatorOnly = clientOnly))
            agreed <- HandshakeClient.run(c, offers)
            theirs <- serving.joinWithNever
          yield (
            agreed.version,
            agreed.data.initiatorOnly,
            theirs.map(a => (a.version, a.data.initiatorOnly))
          )
        }
      }
    for
      a <- negotiate(clientOnly = true, serverDuplex = true, Vector(14))
      b <- negotiate(clientOnly = false, serverDuplex = true, Vector(14, 15))
      c <- negotiate(clientOnly = false, serverDuplex = false, Vector(14))
    yield
      assertEquals(a, Right((14, true, Right((14, true)))))
      assertEquals(b, Right((15, false, Right((15, false)))))
      assertEquals(c, Right((14, true, Right((14, true)))))

  test("handshake: a magic mismatch is refused on both sides"):
    typed {
      linked(protocols = Set(0)).use { (client, server) =>
        for
          c <- client.channel(0)
          s <- server.channel(0)
          serving <- typed(HandshakeServer.run(s, HandshakeServer.offers(magic, true))).start
          theirs <- typed(HandshakeClient.run(c, Vector(14 -> Handshake.Data(magic + 1))))
          mine <- serving.joinWithNever
        yield (theirs.left.map(_.getClass.getSimpleName), mine.left.map(_.getClass.getSimpleName))
      }
    }.map(r => assertEquals(r, Right((Left("HandshakeRefused"), Left("HandshakeRefused")))))

  test("ChainSync server: intersection, roll forward, await at the tip, then a new block"):
    val chain = Chain(Vector(1, 2, 3))
    typed {
      linked(protocols = Set(2)).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2, Mux.Role.Responder)
          _ <- chain.source.use(src => ChainSyncServer.run(s, ChainSyncFixtures.rawItem, src)).start
          api <- ChainSyncClient(c, ChainSyncFixtures.rawItem)
          missing <- api.findIntersect(Vector(Peers.point(9)))
          found <- api.findIntersect(Vector(Peers.point(9), Peers.point(2), Point.Origin))
          first <- api.requestNext
          waiting <- api.requestNext.start
          _ <- IO.sleep(30.millis)
          _ <- chain.blocks.update(_ :+ 4)
          later <- waiting.joinWithNever
          _ <- IO.pure(()) // no pipelining at the tip
        yield (missing, found, first, later)
      }
    }.map { r =>
      assertEquals(
        r,
        Right(
          (
            None,
            Some(Peers.point(2) -> Peers.tip(3)),
            ChainSyncClient.Update.RollForward(header(3), Peers.tip(3)),
            ChainSyncClient.Update.RollForward(header(4), Peers.tip(4))
          )
        )
      )
    }

  test("ChainSync server: pipelined requests are answered in order, then a fork rolls back"):
    val chain = Chain(Vector(1, 2, 3, 4, 5))
    typed {
      linked(protocols = Set(2)).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2, Mux.Role.Responder)
          _ <- chain.source.use(src => ChainSyncServer.run(s, ChainSyncFixtures.rawItem, src)).start
          api <- ChainSyncClient(c, ChainSyncFixtures.rawItem)
          _ <- api.findIntersect(Vector(Point.Origin))
          batch <- api.requestNextBatch(4)
          // the chain forks back to block 2 and grows a different branch
          _ <- chain.blocks.set(Vector(1, 2))
          rolled <- api.requestNext
        yield (
          batch.map {
            case ChainSyncClient.Update.RollForward(h, _) => h.value.head.toInt; case _ => -1
          },
          rolled
        )
      }
    }.map {
      case Right((forwards, rolled)) =>
        assertEquals(forwards, Vector(1, 2, 3, 4))
        assertEquals(rolled, ChainSyncClient.Update.RollBackward(Peers.point(2), Peers.tip(2)))
      case other => fail(other.toString)
    }

  test("BlockFetch server: a range streams in order, an unknown or too long one gets NoBlocks"):
    val chain = Chain(Vector(1, 2, 3, 4))
    typed {
      linked(protocols = Set(3)).use { (client, server) =>
        for
          c <- client.channel(3)
          s <- server.channel(3, Mux.Role.Responder)
          _ <- BlockFetchServer
            .run(s, chain.blockSource, BlockFetchServer.Settings(maxBlocks = 3))
            .start
          api <- BlockFetchClient(c)
          some <- api.requestRange(Peers.point(2), Peers.point(4))
          unknown <- api.requestRange(Peers.point(2), Peers.point(9))
          tooLong <- api.requestRange(Peers.point(1), Peers.point(4))
          single <- api.requestRange(Peers.point(1), Peers.point(1))
        yield (some, unknown, tooLong, single)
      }
    }.map(r =>
      assertEquals(
        r,
        Right(
          (
            Some(Vector(2, 3, 4).map(Peers.block)),
            None,
            None,
            Some(Vector(Peers.block(1)))
          )
        )
      )
    )

  test("KeepAlive server echoes cookies and stops on Done"):
    typed {
      linked(protocols = Set(8)).use { (client, server) =>
        for
          c <- client.channel(8)
          s <- server.channel(8, Mux.Role.Responder)
          serving <- typed(KeepAliveServer.run(s)).start
          api <- KeepAliveClient(c)
          a <- api.ping
          b <- api.ping
          done <- KeepAlive
            .encode(
              KeepAlive.State.Client,
              KeepAlive.Role.Client,
              KeepAlive.Message.Done
            )
            .fold(e => IO.raiseError(RuntimeException(e.toString)), IO.pure)
          _ <- c.send(done)
          ended <- serving.joinWithNever
        yield (a >= Duration.Zero, b >= Duration.Zero, ended)
      }
    }.map(r => assertEquals(r, Right((true, true, Right(())))))

  test("KeepAlive server: a silent client times out"):
    typed {
      linked(protocols = Set(8)).use { (_, server) =>
        server
          .channel(8, Mux.Role.Responder)
          .flatMap(KeepAliveServer.run(_, KeepAliveServer.Settings(idleTimeout = 50.millis)))
      }
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.Timeout]), r.toString))

  test("TxSubmission2: the server collects what the client offers, skipping known transactions"):
    val txs = (1 to 6).map(txBytes).toVector
    val ids = txs.map(t => TxSubmission2.bodyId(t).toOption.get)
    for
      received <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      held <- Ref.of[IO, Vector[TxSubmissionClient.Held]](Vector.empty)
      bodies = txs.map(t => TxSubmission2.bodyId(t).toOption.get -> t).toMap
      source = new TxSubmissionClient.Source[IO]:
        def after(ticket: Long, max: Int) =
          held.get.map(_.filter(_.ticket > ticket).take(max))
        def awaitAfter(ticket: Long) =
          held.get.flatMap(h =>
            if h.exists(_.ticket > ticket) then IO.unit
            else IO.sleep(5.millis) *> awaitAfter(ticket)
          )
        def body(id: Bytes) = IO.pure(bodies.get(id))
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(id == ids(1))
        def submit(originals: Vector[Bytes]) = received.update(_ ++ originals)
      outcome <- typed {
        linked(protocols = Set(4)).use { (client, server) =>
          for
            c <- client.channel(4)
            s <- server.channel(4, Mux.Role.Responder)
            _ <- typed(TxSubmissionClient.run(c, source)).start
            _ <- typed(TxSubmissionServer.run(s, sink)).start
            _ <- IO.sleep(20.millis) // the server's first request blocks on an empty source
            _ <- held.set(
              ids.zipWithIndex
                .take(5)
                .map((id, i) => TxSubmissionClient.Held(id, txs(i).size, i + 1L))
            )
            _ <- received.get.iterateUntil(_.size >= 4).timeout(5.seconds)
            // later arrivals are offered too
            _ <- held.update(_ :+ TxSubmissionClient.Held(ids(5), txs(5).size, 6L))
            _ <- received.get.iterateUntil(_.size >= 5).timeout(5.seconds)
          yield ()
        }
      }
      got <- received.get
    yield
      assertEquals(outcome, Right(()))
      assertEquals(got, txs.patch(1, Nil, 1))

  test("duplex: one bearer carries a client and a server of the same protocol both ways"):
    val mine = Chain(Vector(1, 2, 3))
    val theirs = Chain(Vector(7, 8))
    typed {
      Resource
        .eval(Bearer.inMemory[IO]())
        .flatMap { (a, b) =>
          (
            Mux.resource(a, Set(0, 2), Mux.Config(role = Mux.Role.Initiator, duplex = true)),
            Mux.resource(b, Set(0, 2), Mux.Config(role = Mux.Role.Responder, duplex = true))
          ).tupled
        }
        .use { (left, right) =>
          for
            lServer <- left.channel(2, Mux.Role.Responder)
            lClient <- left.channel(2, Mux.Role.Initiator)
            rServer <- right.channel(2, Mux.Role.Responder)
            rClient <- right.channel(2, Mux.Role.Initiator)
            _ <- mine.source.use(ChainSyncServer.run(lServer, ChainSyncFixtures.rawItem, _)).start
            _ <- theirs.source.use(ChainSyncServer.run(rServer, ChainSyncFixtures.rawItem, _)).start
            leftApi <- ChainSyncClient(lClient, ChainSyncFixtures.rawItem)
            rightApi <- ChainSyncClient(rClient, ChainSyncFixtures.rawItem)
            _ <- leftApi.findIntersect(Vector(Point.Origin))
            _ <- rightApi.findIntersect(Vector(Point.Origin))
            // both directions at once
            pair <- (leftApi.requestNext, rightApi.requestNext).parTupled
          yield pair
        }
    }.map(r =>
      assertEquals(
        r,
        Right(
          (
            ChainSyncClient.Update.RollForward(header(7), Peers.tip(8)),
            ChainSyncClient.Update.RollForward(header(1), Peers.tip(3))
          )
        )
      )
    )

  test("a connection that is not duplex closes on the other side's mode bit"):
    typed {
      linked(
        protocols = Set(2),
        client = Mux.Config(role = Mux.Role.Responder),
        server = Mux.Config(role = Mux.Role.Responder)
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          _ <- c.send(Bytes(Vector(0x81.toByte, 0x00)))
          reason <- server.closed.timeout(2.seconds)
        yield reason
      }
    }.map {
      case Right(reason) => assertEquals(reason, NetworkError.IllegalTransition(Protocols.ChainSync, NetworkError.RuntimeViolation.WrongModeBit))
      case other         => fail(other.toString)
    }

  test("a client that breaks the protocol ends the connection with a typed error, nothing escapes"):
    // each server fiber runs in an error scope of its own and ends the connection with a value;
    // the suite runs on a runtime that fails the test on any fiber that dies unobserved
    typed {
      linked(
        protocols = Set(0, 2, 3, 4, 8),
        server = Mux.Config(role = Mux.Role.Responder)
      ).use { (client, server) =>
        NodeToNodeServer
          .start(server, handlers(Chain(Vector(1, 2, 3))))
          .use { _ =>
            for
              c <- client.channel(2)
              // not a ChainSync message in the idle state
              _ <- c.send(Bytes(Vector(0x82.toByte, 0x09, 0x00)))
              reason <- server.closed.timeout(2.seconds)
            yield reason
          }
      }
    }.map {
      case Right(NetworkError.Decode(protocol, _)) => assertEquals(protocol, Protocols.ChainSync)
      case other                                   => fail(s"unexpected $other")
    }

  test("TCP: a node-to-node client connects to the accepting server and syncs"):
    val chain = Chain(Vector(1, 2, 3))
    typed {
      BearerServer
        .listen[IO](Some(ip"127.0.0.1"), None) { (bearer, _) =>
          typed(NodeToNodeServer.accept(bearer, magic, handlers(chain))).void
        }
        .flatMap(bound =>
          NodeToNode.connect[IO, Bytes](
            SocketAddress(bound.host, bound.port),
            magic,
            ChainSyncFixtures.rawItem
          )
        )
        .use { connection =>
          for
            found <- connection.chainSync.findIntersect(Vector(Point.Origin))
            first <- connection.chainSync.requestNext
            blocks <- connection.blockFetch.requestRange(Peers.point(1), Peers.point(3))
          yield (connection.version, found, first, blocks)
        }
    }.map(r =>
      assertEquals(
        r,
        Right(
          (
            15,
            Some(Point.Origin -> Peers.tip(3)),
            ChainSyncClient.Update.RollForward(header(1), Peers.tip(3)),
            Some(Vector(1, 2, 3).map(Peers.block))
          )
        )
      )
    )

  test("TCP: a wrong network magic is refused by the server"):
    val chain = Chain(Vector(1))
    typed {
      BearerServer
        .listen[IO](Some(ip"127.0.0.1"), None) { (bearer, _) =>
          typed(NodeToNodeServer.accept(bearer, magic, handlers(chain))).void
        }
        .flatMap(bound =>
          NodeToNode.connect[IO, Bytes](
            SocketAddress(bound.host, bound.port),
            magic + 1,
            ChainSyncFixtures.rawItem
          )
        )
        .use(c => IO.pure(c.version))
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.HandshakeRefused]), r.toString))
