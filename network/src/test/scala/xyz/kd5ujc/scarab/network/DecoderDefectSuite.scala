// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, NtnVersion}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

/** A decoder that throws on a peer's bytes ends that connection with
  * [[NetworkError.DecoderDefect]], raised where the decoder runs ([[MuxChannel.recv]]) through the
  * caller's `Raise`: the network `RethrowPolicy.hs` treats an exception in a mini-protocol thread
  * as `ShutdownPeer`, a decision about one connection. Every mini-protocol, client and server side,
  * and the node-to-client servers, are run with a decoder that throws.
  */
class DecoderDefectSuite extends FailsOnUncaught:
  import Peers.*

  private def injected: Throwable = new IllegalStateException("injected decoder defect")

  private def expectDecoderDefect(protocol: Int, cause: Throwable)(
      reason: NetworkError
  ): Unit = reason match
    case NetworkError.DecoderDefect(seen, thrown) =>
      assertEquals(seen, protocol)
      assert(thrown eq cause, s"the original exception is kept, got $thrown")
    case other => fail(s"expected a DecoderDefect of ${Protocols.name(protocol)}, got $other")

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
      def submit(originals: Vector[Bytes]) = IO.unit
  )
  private val transactions: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    def after(ticket: Long, max: Int) = IO.pure(Vector.empty)
    def awaitAfter(ticket: Long) = IO.never
    def body(txId: Bytes) = IO.pure(None)

  private val nodeToNode = Vector(
    Protocols.ChainSync,
    Protocols.BlockFetch,
    Protocols.TxSubmission2,
    Protocols.KeepAlive,
    Protocols.PeerSharing
  )

  private def duplex(using Net): Resource[IO, (Mux[IO], Mux[IO])] =
    linked(
      protocols = NodeToNodeServer.Protocol,
      client = Mux.Config(duplex = true),
      server = Mux.Config(role = Mux.Role.Responder, duplex = true)
    )

  /** Every client of the connection, on `mux`, as a node opens them. */
  private def clientsOf(mux: Mux[IO])(using Net): Resource[IO, NodeToNodeConnection[IO, Bytes]] =
    NodeToNode
      .warm(
        mux,
        Mux.Role.Initiator,
        NtnVersion.LatestReleased.number,
        ChainSyncFixtures.rawItem,
        peerSharing = true
      )
      .flatMap(_.activate(Some(transactions)))

  private def reasonOf(mux: Mux[IO]): IO[NetworkError] = mux.closed.timeout(10.seconds)

  for protocol <- nodeToNode do
    test(s"server: a throwing ${Protocols.name(protocol)} decoder closes the connection"):
      val cause = injected
      typed {
        duplex.use { (client, server) =>
          NodeToNodeServer
            .start(
              PoisonedMux(server, protocol, cause),
              handlers,
              peerSharing = true,
              initiators = true
            )
            .use { _ =>
              clientsOf(client).use(_ => reasonOf(server))
            }
        }
      }.map(result =>
        result.fold(e => fail(s"unexpected $e"), expectDecoderDefect(protocol, cause))
      )

    test(s"client: a throwing ${Protocols.name(protocol)} decoder closes the connection"):
      val cause = injected
      typed {
        duplex.use { (client, server) =>
          val poisoned = PoisonedMux(client, protocol, cause)
          NodeToNodeServer
            .start(server, handlers, peerSharing = true, initiators = true)
            .use { _ =>
              // the clients take the `Raise` of the fiber that makes them, so they are made, used
              // and failed in one scope; the owner closes the connection on its failure, as the
              // node does, and the clients that run alone fail and close it themselves
              typed {
                NodeToNode
                  .warm(
                    poisoned,
                    Mux.Role.Initiator,
                    NtnVersion.LatestReleased.number,
                    ChainSyncFixtures.rawItem,
                    peerSharing = true
                  )
                  .flatMap(warm => warm.activate(Some(transactions)).map(warm -> _))
                  .use { (warm, connection) =>
                    protocol match
                      case Protocols.ChainSync =>
                        connection.chainSync.findIntersect(Vector(ChainSync.Point.Origin)).void
                      case Protocols.BlockFetch =>
                        connection.blockFetch.requestRange(point(1), point(2)).void
                      case Protocols.PeerSharing =>
                        warm.peerSharing.traverse_(
                          _.request(
                            xyz.kd5ujc.scarab.protocol.PeerSharing.Amount.saturating(1)
                          ).void
                        )
                      case _ => reasonOf(poisoned).void
                  }
              }.flatMap(_.fold(poisoned.abort, _ => IO.unit)) *> reasonOf(poisoned)
            }
        }
      }.map(result =>
        result.fold(e => fail(s"unexpected $e"), expectDecoderDefect(protocol, cause))
      )

  test("recv raises the decoder's exception as DecoderDefect through the caller's Raise"):
    val cause = injected
    typed {
      linked(protocols = Set(Protocols.KeepAlive)).use { (client, _) =>
        client
          .channel(Protocols.KeepAlive)
          .flatMap(PoisonedMux.channel(_, cause).recv(100)(_ => DecodeResult.NeedMore))
      }
    }.map(result =>
      result.fold(expectDecoderDefect(Protocols.KeepAlive, cause), _ => fail("expected a failure"))
    )

  test("a decoder that refuses is still Decode"):
    typed {
      linked(protocols = Set(Protocols.KeepAlive)).use { (client, _) =>
        client
          .channel(Protocols.KeepAlive)
          .flatMap(
            _.recv(100)(_ =>
              DecodeResult.Failed(
                xyz.kd5ujc.scarab.protocol.ProtocolError.UnsolicitedKeepAliveResponse
              )
            )
          )
      }
    }.map(result =>
      assert(result.left.exists(_.isInstanceOf[NetworkError.Decode]), result.toString)
    )

  test("the node-to-client servers: a throwing decoder is DecoderDefect, not a node defect"):
    val cause = injected
    val protocols = Set(
      Protocols.LocalChainSync,
      Protocols.LocalTxSubmission,
      Protocols.LocalStateQuery,
      Protocols.LocalTxMonitor
    )
    def run(protocol: Int, serve: MuxChannel[IO] => Net ?=> IO[Any]): IO[Unit] =
      typed {
        linked(
          protocols = protocols,
          client = Mux.Config.nodeToClient.copy(role = Mux.Role.Initiator),
          server = Mux.Config.nodeToClient
        ).use { (_, server) =>
          server.channel(protocol).flatMap(c => serve(PoisonedMux.channel(c, cause)))
        }
      }.map(result =>
        result.fold(
          expectDecoderDefect(protocol, cause),
          ended => fail(s"expected a failure, got $ended")
        )
      )
    val stateQuery = new LocalStateQueryServer.Source[IO, Nothing]:
      def acquire(target: xyz.kd5ujc.scarab.protocol.LocalStateQuery.Target) =
        Resource.eval(IO.never)
    val mempool = new LocalTxMonitorServer.Source[IO]:
      def acquire = IO.never
      def awaitChange(seen: LocalTxMonitorServer.Snapshot) = IO.never
    val chain = new ChainSyncServer.Source[IO, RawNtNBlock]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never
    List(
      run(
        Protocols.LocalTxSubmission,
        LocalTxSubmissionServer.run(_, _ => IO.pure(LocalTxSubmissionServer.Response.Accepted))
      ),
      run(Protocols.LocalStateQuery, LocalStateQueryServer.run(_, stateQuery)),
      run(Protocols.LocalTxMonitor, LocalTxMonitorServer.run(_, mempool)),
      run(
        Protocols.LocalChainSync,
        ChainSyncServer.run(
          _,
          xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.payloadCodec(),
          chain,
          ChainSyncServer.NodeToClient
        )
      )
    ).sequence_
