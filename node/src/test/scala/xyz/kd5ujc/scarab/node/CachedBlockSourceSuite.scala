// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockFailure, NodeError, ValidationError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Late blame goes through the real outbound reporter and connection manager, after reconnecting
  * the source. Its innocent consumer's bearer stays open.
  */
class CachedBlockSourceSuite extends FailsOnUncaught:
  private val magic = 42L
  private val hash = Bytes.wrap(Array[Byte](1))
  private val block = Bytes.wrap(Array[Byte](2))
  private val refusal = ValidationError(ChainSync.Point.Origin, BlockFailure.HeaderDiffers)

  private def handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    val blocks = new BlockFetchServer.Blocks[IO]:
      def range(from: ChainSync.Point, to: ChainSync.Point, maxBlocks: Int) = IO.pure(None)
    val transactions = new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes]) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  test(
    "released attribution closes a reconnected source and reports its identity, never the consumer"
  ) {
    NodeScope.resource.use { node =>
      given Handle[IO, NodeError] = node
      Supervisor[IO](await = false).use { supervisor =>
        for
          pool <- BlockPool.create[IO]
          transport <- InMemoryPeers.create(magic)
          source = InMemoryPeers.address(1)
          consumer = InMemoryPeers.address(2)
          _ <- List(source, consumer).traverse_(transport.serve(_, handlers))
          manager <- ConnectionManager.create[IO]()
          events <- Ref.of[IO, Vector[Event[PeerNetwork.Addr]]](Vector.empty)
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
          outbound <- OutboundPeers.create(
            transport,
            manager,
            OutboundPeers.Config(),
            hot,
            supervisor,
            e => events.update(_ :+ e),
            summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]]
          )
          first <- outbound.establish(
            source,
            DiffusionMode.InitiatorAndResponder,
            Provenance.Outbound
          )
          _ <- pool.session(Some(outbound.rejectedCachedBlock(source, BigInt(0), _))).use {
            claims =>
              claims.claim(Vector(hash)) *> claims.fill(Vector(hash -> block))
          }
          _ <- outbound.close(source)
          second <- outbound.establish(
            source,
            DiffusionMode.InitiatorAndResponder,
            Provenance.Outbound
          )
          other <- outbound.establish(
            consumer,
            DiffusionMode.InitiatorAndResponder,
            Provenance.Outbound
          )
          remote <- transport
            .resolve(source)
            .flatMap(e => IO.fromOption(e.toOption)(new AssertionError("source address")))
          current <- manager
            .lookup(remote)
            .flatMap(IO.fromOption(_)(new AssertionError("source not connected")))
          _ <- pool.session.use { innocent =>
            for
              taken <- innocent.take(hash)
              cached <- IO.fromOption(taken)(new AssertionError("cached block lost on disconnect"))
              _ <- innocent.distrust(cached.origin, refusal)
              _ <- innocent.distrust(cached.origin, refusal)
            yield ()
          }
          _ <- current.closed.timeout(2.seconds)
          consumerRemote <- transport
            .resolve(consumer)
            .flatMap(e => IO.fromOption(e.toOption)(new AssertionError("consumer address")))
          innocent <- manager.lookup(consumerRemote)
          observed <- events.get
          rejected = observed.collect {
            case Event.Observed(peer, Observation.CachedBlockRejected(reason), _) =>
              peer -> reason
          }
          _ <- outbound.close(consumer)
        yield
          assertEquals(first, None)
          assertEquals(second, None)
          assertEquals(other, None)
          assert(innocent.isDefined)
          assertEquals(rejected, Vector(source -> refusal.message))
      }
    }
  }
