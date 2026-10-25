// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, PeerSharing}
import xyz.kd5ujc.scarab.protocol.PeerSharing.{Address, Amount}
import scala.concurrent.duration.*

import Peers.*

/** PeerSharing client and server over in-memory bearers: the answers, the reference's limits (more
  * peers than asked for, a message over 5760 bytes, no answer within 60 s) and the negotiation of
  * the flag on a whole connection.
  */
class PeerSharingSuite extends FailsOnUncaught:
  private val magic = 42L
  private val Protocol = Protocols.PeerSharing

  private def amount(n: Int): Amount = Amount.from(n).getOrElse(fail(s"amount $n"))
  private def addr(i: Int): Address =
    Address.V4(
      PeerSharing.Word32.from(0x0a000000L + i).getOrElse(fail("word")),
      PeerSharing.Port.from(3000 + i).getOrElse(fail("port"))
    )
  private def fat(i: Int): Address =
    val w = PeerSharing.Word32.from(0xfffffff0L + i % 16).getOrElse(fail("word"))
    Address.V6(w, w, w, w, PeerSharing.Port.from(65000 + i % 500).getOrElse(fail("port")))

  private def source(peers: Vector[Address]): PeerSharingServer.Source[IO] =
    _ => IO.pure(peers)

  /** A client and a server joined by an in-memory bearer, the server serving conversations. */
  private def pair(
      peers: PeerSharingServer.Source[IO],
      client: PeerSharingClient.Settings = PeerSharingClient.Settings(),
      server: PeerSharingServer.Settings = PeerSharingServer.Settings()
  )(using Net): Resource[IO, PeerSharingClient[IO]] =
    linked(protocols = Set(Protocol)).flatMap { (c, s) =>
      for
        cc <- Resource.eval(c.channel(Protocol))
        sc <- Resource.eval(s.channel(Protocol))
        _ <- Fibers
          .scoped[IO, NetworkError, Unit](NetworkError.fromThrowable("test peer-sharing", _))(
            PeerSharingServer.run(sc, peers, server)
          )
          .flatMap(_.fold(s.abort, _ => IO.unit))
          .background
        api <- Resource.eval(PeerSharingClient(cc, client))
      yield api
    }

  test("a request is answered with the peers the source names, at most the amount asked for"):
    typed(
      pair(source((1 to 5).toVector.map(addr))).use(api =>
        (api.request(amount(2)), api.request(amount(0)), api.request(amount(255))).tupled
      )
    ).map(r =>
      assertEquals(
        r,
        Right(((1 to 2).toVector.map(addr), Vector.empty[Address], (1 to 5).toVector.map(addr)))
      )
    )

  test("the source sees the amount asked for"):
    typed(
      Resource
        .eval(Ref.of[IO, Vector[Int]](Vector.empty))
        .flatMap { seen =>
          pair(a => seen.update(_ :+ a.value).as(Vector.empty)).evalMap(api =>
            (api.request(amount(7)) *> api.request(amount(200)) *> seen.get)
          )
        }
        .use(IO.pure)
    ).map(r => assertEquals(r, Right(Vector(7, 200))))

  test("a source that returns more than was asked for is cut to the amount"):
    typed(pair(source((1 to 40).toVector.map(addr))).use(_.request(amount(10))))
      .map(r => assertEquals(r.map(_.size), Right(10)))

  test("an answer that would not fit 5760 bytes is cut to what fits"):
    typed(pair(source(Vector.tabulate(255)(fat))).use(_.request(amount(255)))).map {
      case Right(peers) =>
        assert(peers.nonEmpty && peers.size < 255, peers.size.toString)
        assertEquals(peers, Vector.tabulate(peers.size)(fat))
        val wire = PeerSharing.encode(
          PeerSharing.State.Busy,
          PeerSharing.Role.Server,
          PeerSharing.Message.SharePeers(peers)
        )
        assert(wire.exists(_.size <= PeerSharing.UpstreamMessageBytes))
      case Left(error) => fail(error.message)
    }

  test("the client says Done and the server ends the conversation, and the next one starts afresh"):
    typed {
      linked(protocols = Set(Protocol)).use { (c, s) =>
        for
          cc <- c.channel(Protocol)
          sc <- s.channel(Protocol)
          served <- Ref.of[IO, Int](0)
          server <- Fibers
            .scoped[IO, NetworkError, Unit](NetworkError.fromThrowable("test peer-sharing", _)) {
              (PeerSharingServer.run(sc, source(Vector(addr(1))), PeerSharingServer.Settings()) *>
                served.update(_ + 1)).foreverM
            }
            .flatMap(_.fold(s.abort, _ => IO.unit))
            .start
          first <- PeerSharingClient(cc)
          one <- first.request(amount(1))
          _ <- first.done
          again <- typed(first.request(amount(1))) // the client is finished
          second <- PeerSharingClient(cc)
          two <- second.request(amount(1))
          n <- served.get
          _ <- server.cancel
        yield (one, again.isLeft, two, n)
      }
    }.map(r => assertEquals(r, Right((Vector(addr(1)), true, Vector(addr(1)), 1))))

  /** A server that answers a request by sending whatever `reply` says. */
  private def scripted(
      reply: Amount => Bytes
  )(using Net): Resource[IO, PeerSharingClient[IO]] =
    linked(protocols = Set(Protocol)).flatMap { (c, s) =>
      for
        cc <- Resource.eval(c.channel(Protocol))
        sc <- Resource.eval(s.channel(Protocol))
        _ <- Fibers
          .scoped[IO, NetworkError, Unit](NetworkError.fromThrowable("test peer-sharing", _)) {
            sc.recv(5760)(PeerSharing.decoder(PeerSharing.State.Idle, PeerSharing.Role.Client))
              .flatMap {
                case PeerSharing.Message.ShareRequest(a) => sc.send(reply(a))
                case _                                   => IO.unit
              }
          }
          .flatMap(_.fold(s.abort, _ => IO.unit))
          .background
        api <- Resource.eval(PeerSharingClient(cc))
      yield api
    }

  private def sharePeers(
      peers: Vector[Address],
      limits: PeerSharing.Limits = PeerSharing.Limits()
  ) =
    PeerSharing
      .encode(
        PeerSharing.State.Busy,
        PeerSharing.Role.Server,
        PeerSharing.Message.SharePeers(peers),
        limits
      )
      .fold(e => fail(e.message), identity)

  test("more peers than requested is a protocol violation"):
    typed(scripted(_ => sharePeers(Vector(addr(1), addr(2), addr(3)))).use(_.request(amount(2))))
      .map {
        case Left(NetworkError.IllegalTransition(Protocol, detail)) =>
          assertEquals(
            detail,
            NetworkError.RuntimeViolation.PeerSharingPolicy(
              PeerSharing.Violation.TooManyPeers(amount(2), 3)
            )
          )
        case other => fail(s"unexpected $other")
      }

  test("a client created in one scope raises a bad answer in its request fiber's own scope"):
    typed {
      scripted(_ => sharePeers(Vector(addr(1), addr(2)))).use { client =>
        Fibers
          .scoped[IO, NetworkError, Vector[Address]](
            NetworkError.fromThrowable("request test", _)
          ) {
            client.request(amount(1))
          }
          .start
          .flatMap(_.joinWithNever)
      }
    }.map { result =>
      assert(
        result.exists(_.left.exists(_.isInstanceOf[NetworkError.IllegalTransition])),
        result.toString
      )
    }

  test("exactly the amount requested is fine"):
    typed(scripted(_ => sharePeers(Vector(addr(1), addr(2)))).use(_.request(amount(2))))
      .map(r => assertEquals(r, Right(Vector(addr(1), addr(2)))))

  test("an answer over 5760 bytes ends the conversation"):
    val big = PeerSharing.Limits(100000)
    typed(scripted(_ => sharePeers(Vector.tabulate(300)(fat), big)).use(_.request(amount(255))))
      .map(r =>
        assert(
          r.left.exists {
            case NetworkError.Decode(Protocol, _) | NetworkError.IngressLimit(Protocol, _) => true
            case _                                                                         => false
          },
          r.toString
        )
      )

  test("an answer that is not a SharePeers is a protocol error"):
    val request = PeerSharing
      .encode(
        PeerSharing.State.Idle,
        PeerSharing.Role.Client,
        PeerSharing.Message.ShareRequest(amount(1))
      )
      .fold(e => fail(e.message), identity)
    typed(scripted(_ => request).use(_.request(amount(1)))).map(r =>
      assert(r.left.exists(_.isInstanceOf[NetworkError.Decode]), r.toString)
    )

  test("no answer within 60 seconds times out in StBusy"):
    TestControl
      .executeEmbed(
        typed(scripted(_ => Bytes(Vector.empty)).use(_.request(amount(1))))
      )
      .map(r => assertEquals(r, Left(NetworkError.Timeout(Protocol, "StBusy"))))

  test("a slow answer inside the timeout is accepted"):
    TestControl
      .executeEmbed(
        typed(
          pair(_ => IO.sleep(59.seconds).as(Vector(addr(1)))).use(_.request(amount(1)))
        )
      )
      .map(r => assertEquals(r, Right(Vector(addr(1)))))

  test("the server does not time out while it waits for a request"):
    TestControl
      .executeEmbed(
        typed(pair(source(Vector(addr(1)))).use(api => IO.sleep(2.hours) *> api.request(amount(1))))
      )
      .map(r => assertEquals(r, Right(Vector(addr(1)))))

  test("a server meets a message it cannot decode: it fails the conversation"):
    typed {
      linked(protocols = Set(Protocol)).use { (c, s) =>
        for
          cc <- c.channel(Protocol)
          sc <- s.channel(Protocol)
          _ <- cc.send(Bytes(Vector(0x82, 0x01, 0x00).map(_.toByte))) // SharePeers from a client
          _ <- PeerSharingServer.run(sc, source(Vector.empty))
        yield ()
      }
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.Decode]), r.toString))

  // --- the flag on a whole connection ---------------------------------------------------------

  private val handlers = ServerHandlers[IO, Bytes](
    ChainSyncFixtures.rawItem,
    Resource.pure[IO, ChainSyncServer.Source[IO, Bytes]](new ChainSyncServer.Source[IO, Bytes]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(Peers.tip(1)))
      def poll = IO.pure(None)
      def await = IO.never),
    (_, _) => IO.pure(None),
    new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes]) = IO.unit
    ,
    peerSharing = Some(source(Vector(addr(1), addr(2))))
  )

  private def connection(ours: Boolean, theirs: Boolean)(using Net) =
    Resource
      .eval(Bearer.inMemory[IO]())
      .flatMap { (a, b) =>
        typed(
          NodeToNodeServer
            .accept(b, magic, handlers, NodeToNodeServer.Settings(offerPeerSharing = theirs))
        ).void.background
          .flatMap(_ =>
            NodeToNode.warmOver[IO, Bytes](
              a,
              magic,
              ChainSyncFixtures.rawItem,
              NodeToNode.Settings(offerPeerSharing = ours)
            )
          )
      }

  test("both sides offer the flag: the client asks and the server answers on the connection"):
    typed(
      connection(ours = true, theirs = true).use(w => w.peerSharing.traverse(_.request(amount(5))))
    ).map(r => assertEquals(r, Right(Some(Vector(addr(1), addr(2))))))

  test("the flag is the conjunction: either side off, no client"):
    for
      a <- typed(connection(ours = true, theirs = false).use(w => IO.pure(w.peerSharing.isDefined)))
      b <- typed(connection(ours = false, theirs = true).use(w => IO.pure(w.peerSharing.isDefined)))
      c <- typed(
        connection(ours = false, theirs = false).use(w => IO.pure(w.peerSharing.isDefined))
      )
    yield assertEquals((a, b, c), (Right(false), Right(false), Right(false)))

  test("a server without a source answers with no peers"):
    val bare = ServerHandlers[IO, Bytes](
      handlers.codec,
      handlers.chainSync,
      handlers.blocks,
      handlers.transactions
    )
    typed(
      Resource
        .eval(Bearer.inMemory[IO]())
        .flatMap { (a, b) =>
          typed(
            NodeToNodeServer
              .accept(b, magic, bare, NodeToNodeServer.Settings(offerPeerSharing = true))
          ).void.background.flatMap(_ =>
            NodeToNode.warmOver[IO, Bytes](
              a,
              magic,
              ChainSyncFixtures.rawItem,
              NodeToNode.Settings(offerPeerSharing = true)
            )
          )
        }
        .use(_.peerSharing.traverse(_.request(amount(5))))
    ).map(r => assertEquals(r, Right(Some(Vector.empty[Address]))))
