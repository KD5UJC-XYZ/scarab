// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.CountDownLatch
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The node's own release of its peer network ends the connections without reporting any of them
  * lost (issue 916). The reference closes the job pool of its peer state actions, which holds every
  * connection's monitor, inside the scope of its connection manager: each monitor is cancelled, and
  * waited for, before that scope ends (ouroboros-network c45735a5 `Ouroboros/Network/Diffusion.hs`
  * 769-786, 801-826, `PeerSelection/PeerStateActions.hs` 630, 836-848, `network-mux`
  * `Control/Concurrent/JobPool.hs` 54-55, 70-73). A monitor that outlived its connection would take
  * the release for a loss and report the peer as reconnecting.
  *
  * Each seed orders the fibers of the release differently. A monitor that ends together with its
  * connection, rather than before it, reports the loss under some orders only, so the release is
  * tried under many.
  */
class PeerNetworkReleaseSuite extends FailsOnUncaught:
  private val magic = 42L
  private val addresses = Vector(InMemoryPeers.address(1), InMemoryPeers.address(2))
  private val seeds =
    (1 to 64).toVector.map(n =>
      Base64.getEncoder.encodeToString(n.toString.getBytes(StandardCharsets.UTF_8))
    )

  /** A peer with nothing to serve: its ChainSync has no header to announce. */
  private val handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    val blocks = new BlockFetchServer.Blocks[IO]:
      def range(from: ChainSync.Point, to: ChainSync.Point) = IO.pure(None)
    val transactions = new TxSubmissionServer.Sink[IO]:
      def reader = RelayedTransactions
      def known(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(false)
      def submit(originals: Vector[xyz.kd5ujc.scarab.codec.Bytes])(using
          cats.mtl.Raise[IO, NetworkError]
      ) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  /** What the node reported lost, and what it was told stops it, when the network is released with
    * every peer hot, under the scheduling `seed`.
    */
  private def released(seed: String): IO[(Vector[PeerError], Vector[Fatal])] =
    Settled.seededTimed(seed) { _ =>
      NodeScope.resource.use { node =>
        given cats.mtl.Handle[IO, NodeError] = node
        for
          lost <- Ref.of[IO, Vector[PeerError]](Vector.empty)
          fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
          following <- CountDownLatch[IO](addresses.size)
          pool <- BlockPool.create[IO]
          peers <- InMemoryPeers.create(magic)
          _ <- addresses.traverse_(peers.serve(_, handlers))
          manager <- ConnectionManager.create[IO]()
          hot = OutboundPeers.Hot[IO](
            magic = magic,
            network = NodeToNode.Settings(),
            peer = Peer.Settings(
              SocketAddress(host"127.0.0.1", port"3001"),
              magic,
              byronEpochSlots = ForgedChain.geometry.byron.epochSlots
            ),
            follow = (_, _) => following.release *> IO.never,
            heightSnapshot = ChainSyncHeights.origin,
            headers = None,
            transactions = None,
            observe = None,
            pool = pool,
            versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            lost = error => lost.update(_ :+ error),
            fatal = fault => fatals.update(_ :+ fault)
          )
          view = new PeerNetwork.ChainView[IO]:
            def tip = IO.pure(None)
            def geometry = IO.pure(ForgedChain.geometry)
          local = RootConfig(false, DiffusionMode.InitiatorOnly, Provenance.Outbound, false)
          roots = PeerNetwork.Roots(
            List(RootGroupSpec(addresses.size, addresses.size, addresses.toList.map(_ -> local))),
            Set.empty,
            None,
            UseLedgerPeers.Disabled
          )
          fetchMode <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
          _ <- PeerNetwork
            .resource[IO](
              PeerNetwork.Settings(roots, Targets.relay, seed = 7L),
              peers,
              manager,
              OutboundPeers.Config(),
              hot,
              view,
              fetchMode,
              _ => IO.unit,
              (_, _) => IO.unit,
              _ => IO.unit,
              _ => IO.unit,
              GsmRunner.Marker.none[IO],
              LedgerPeersSource.none[IO]
            )
            // every peer hot, then the network released as the node releases it when it stops;
            // the bound only turns sessions that never start into a failure that says so
            .use(net => net.run.background.use(_ => following.await.timeout(60.seconds)))
          reported <- (lost.get, fatals.get).tupled
        yield reported
      }
    }

  test("releasing the peer network reports no connection lost, whatever the order of its fibers") {
    seeds
      .traverse(seed => released(seed).map(seed -> _))
      .map { results =>
        val reported = results.filter { case (_, (lost, fatals)) =>
          lost.nonEmpty || fatals.nonEmpty
        }
        assertEquals(reported, Vector.empty, s"${reported.size} of ${seeds.size} seeds")
      }
  }
