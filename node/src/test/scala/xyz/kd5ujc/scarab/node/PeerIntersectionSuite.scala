// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, VolatileChain}
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.relay.{ChainServer, RelayKit}
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import scala.concurrent.duration.*

/** The client offers its retained fragment on the wire; the production server chooses the first
  * point it shares and puts its follower there. No block-validation or fork-choice rule is mocked
  * into the selection of that intersection.
  */
class PeerIntersectionSuite extends FailsOnUncaught:
  private final case class Result(
      offered: Vector[ChainSync.Point],
      found: Option[ChainSync.Point],
      started: Option[ChainSync.Point],
      next: ChainSyncClient.Update[NtNHeader]
  )

  private def fork(length: Int, common: Int, anchored: Boolean): IO[Result] =
    TestControl
      .executeEmbed {
        val anchor = Option.when(anchored)(RelayKit.ref(100))
        val first = if anchored then 101 else 1
        val refs = Vector.tabulate(length)(i => RelayKit.ref(first + i))
        val local = VolatileChain(length, Anchor(anchor, ()), refs.map(_ -> ()))
        val heights = (anchor.toVector ++ refs)
          .map(r => r.point -> Option(r.blockNo))
          .toMap
          .updated(ChainSync.Point.Origin, None)
        for
          offered <- Ref.of[IO, Vector[ChainSync.Point]](Vector.empty)
          started <- Ref.of[IO, Option[ChainSync.Point]](None)
          current <- Ref.of[IO, VolatileChain[Unit]](local)
          server <- ChainServer.open[IO](
            None,
            anchor,
            RelayKit.headerOf,
            cause => IO(fail(s"server failure: $cause"))
          )
          _ <- Handle
            .allow[NodeError] {
              Vector.range(0, length + 1).traverse_ { i =>
                val salt = if i < common then 0 else 1
                server
                  .persistence[Unit]
                  .extended(
                    RelayKit.ref(first + i, salt),
                    RelayKit.raw(first + i, salt),
                    Vector.empty,
                    Anchor(anchor, ())
                  )
              }
            }
            .rescue(error => IO(fail(error.message)))
          check = new HeaderCheck[IO]:
            def start(point: ChainSync.Point) =
              started.set(Some(point)).as(HeaderGate.none[IO])
            override def capture(using cats.Applicative[IO]) =
              IO.pure(Some(HeaderCheck.Captured(local.candidates, start, heights)))
          blocks = new BlockFetchClient[IO]:
            def requestRange(from: ChainSync.Point, to: ChainSync.Point)(using
                Raise[IO, NetworkError]
            ): IO[Option[Vector[Bytes]]] = IO(fail("intersection negotiation fetches no bodies"))
            def done(using Raise[IO, NetworkError]) = IO.unit
            def idle = IO.pure(true)
          result <- Peers.typed {
            Peers.linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              server.source.use { source =>
                val observed = new ChainSyncServer.Source[IO, NtNHeader]:
                  def intersect(points: Vector[ChainSync.Point]) =
                    offered.set(points) *> source.intersect(points)
                  def poll = source.poll
                  def await = source.await
                for
                  clientChannel <- clientMux.channel(Protocols.ChainSync)
                  serverChannel <- serverMux.channel(Protocols.ChainSync)
                  result <- Peers
                    .typed(ChainSyncServer.run(serverChannel, NtNHeader.codec, observed))
                    .background
                    .use { _ =>
                      ChainSyncClient[IO, NtNHeader](clientChannel, NtNHeader.codec).flatMap {
                        client =>
                          Peer
                            .over(
                              NodeToNodeConnection(15, client, blocks, clientMux),
                              Peer.Settings(
                                SocketAddress[Host](ip"10.7.3.1", port"3001"),
                                42,
                                byronEpochSlots = 21600
                              ),
                              ChainSyncHeights.of(current),
                              chain = Peer.Chain.Praos(Some(check))
                            )
                            .use { peer =>
                              for
                                found <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
                                next <- client.requestNext
                                sent <- offered.get
                                gate <- started.get
                              yield Result(
                                sent,
                                found.fold(f => fail(s"client failure: $f"), identity),
                                gate,
                                next
                              )
                            }
                      }
                    }
                yield result
              }
            }
          }
        yield result.fold(error => fail(error.message), identity)
      }
      .timeout(20.seconds)

  test("a fork at offset 21 intersects there rather than at the old doubling offset 22") {
    fork(length = 100, common = 79, anchored = false).map { result =>
      val expected = RelayKit.ref(79).point
      assertEquals(result.found, Some(expected))
      assertEquals(result.started, Some(expected))
      assertEquals(
        result.offered.take(8),
        Vector(100, 99, 98, 97, 95, 92, 87, 79).map(RelayKit.ref(_).point)
      )
      assertEquals(result.offered.last, ChainSync.Point.Origin)
      result.next match
        case ChainSyncClient.Update.RollBackward(point, _) => assertEquals(point, expected)
        case other => fail(s"expected server rollback, got $other")
    }
  }

  test("a short anchored fork also uses Fibonacci offsets instead of offering every point") {
    fork(length = 16, common = 12, anchored = true).map { result =>
      // The latest shared block is 112 (offset 4), which the native selection does not offer.
      val expected = RelayKit.ref(111).point
      assertEquals(result.found, Some(expected))
      assertEquals(result.started, Some(expected))
      assertEquals(
        result.offered,
        Vector(116, 115, 114, 113, 111, 108, 103, 100).map(RelayKit.ref(_).point)
      )
      result.next match
        case ChainSyncClient.Update.RollBackward(point, _) => assertEquals(point, expected)
        case other => fail(s"expected server rollback, got $other")
    }
  }
