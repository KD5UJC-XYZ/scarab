// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Same logical peer, overlapping connection attempts, and real OutboundPeers resources. The
  * distinct socket keys let the real connection manager admit both attempts while the test controls
  * which bearer reaches handshake first.
  */
class OutboundLinkIdentitySuite extends FailsOnUncaught:
  private val magic = 42L
  private val peer = InMemoryPeers.address(7)

  private def handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    val blocks = new BlockFetchServer.Blocks[IO]:
      def range(from: ChainSync.Point, to: ChainSync.Point, maxBlocks: Int) = IO.pure(None)
    val transactions = new TxSubmissionServer.Sink[IO]:
      def known(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(false)
      def submit(originals: Vector[xyz.kd5ujc.scarab.codec.Bytes]) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  private final class GatedTransport(
      underlying: InMemoryPeers,
      firstStarted: Deferred[IO, Unit],
      releaseFirst: Deferred[IO, Unit],
      failFirst: Boolean
  ) extends Transport[IO, PeerNetwork.Addr]:
    private val firstPort = port"31001"
    private val secondPort = port"31002"
    private val next = Ref.unsafe[IO, Int](0)

    def resolve(address: PeerNetwork.Addr) =
      next.getAndUpdate(_ + 1).map { attempt =>
        val port = if attempt == 0 then firstPort else secondPort
        Right(SocketAddress(ipv4"127.0.0.1", port))
      }

    def bearer(address: PeerNetwork.Addr, remote: SocketAddress[IpAddress])(using
        raise: Raise[IO, NetworkError]
    ): Resource[IO, Bearer[IO]] =
      if remote.port == firstPort then
        Resource.eval(firstStarted.complete(()).void *> releaseFirst.get).flatMap { _ =>
          if failFirst then
            Resource.eval(
              raise.raise[NetworkError, Bearer[IO]](
                NetworkError
                  .Connect(address.toString, new java.io.IOException("old attempt failed"))
              )
            )
          else underlying.bearer(address, remote)
        }
      else underlying.bearer(address, remote)

  private final case class Fixture(
      outbound: OutboundPeers[IO, PeerNetwork.Addr],
      transport: InMemoryPeers,
      manager: ConnectionManager[IO]
  )

  private def fixture(
      firstStarted: Deferred[IO, Unit],
      releaseFirst: Deferred[IO, Unit],
      failFirst: Boolean
  ): Resource[IO, Fixture] =
    for
      node <- NodeScope.resource
      _ <- Resource.eval(IO.unit)
      fixture <- Supervisor[IO](await = false).evalMap { supervisor =>
        given Handle[IO, NodeError] = node
        for
          pool <- BlockPool.create[IO]
          transport <- InMemoryPeers.create(magic)
          _ <- transport.serve(peer, handlers)
          manager <- ConnectionManager.create[IO](ConnectionManager.Limits(maxOutbound = 4))
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          given Stateful[IO, PeerMetrics[PeerNetwork.Addr]] = RefStateful(metrics)
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(),
            Peer
              .Settings(SocketAddress(host"127.0.0.1", port"3001"), magic, byronEpochSlots = 21600),
            (_, _) => IO.never[Unit],
            _ => IO.unit,
            None,
            None,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            _ => IO.unit
          )
          gated = new GatedTransport(transport, firstStarted, releaseFirst, failFirst)
          outbound <- OutboundPeers.create(
            gated,
            manager,
            OutboundPeers.Config(),
            hot,
            supervisor,
            _ => IO.unit,
            summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]]
          )
        yield Fixture(outbound, transport, manager)
      }
    yield fixture

  private def overlap(failFirst: Boolean): IO[Unit] =
    (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { (firstStarted, releaseFirst) =>
      fixture(firstStarted, releaseFirst, failFirst).use { current =>
        for
          oldAttempt <- current.outbound
            .establish(peer, DiffusionMode.InitiatorAndResponder, Provenance.Outbound)
            .start
          _ <- firstStarted.get.timeout(2.seconds)
          newResult <- current.outbound.establish(
            peer,
            DiffusionMode.InitiatorAndResponder,
            Provenance.Outbound
          )
          _ <- IO(assertEquals(newResult, None))
          _ <- releaseFirst.complete(())
          oldResult <- oldAttempt.joinWithNever.timeout(5.seconds)
          _ <- IO(assertEquals(oldResult, Some(FailureClass.Transient)))
          stillInstalled <- current.outbound.activate(peer).timeout(5.seconds)
          _ <- IO(assertEquals(stillInstalled, None))
          _ <- current.outbound.close(peer).timeout(5.seconds)
        yield ()
      }
    }

  test("an old failing attempt cannot remove a newer ready link for the same peer") {
    overlap(failFirst = true)
  }

  test("an old late handshake cannot overwrite a newer ready link for the same peer") {
    overlap(failFirst = false)
  }
