// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, NtNHeader, PeerSharing}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance}

/** The governor's peer-share request against the outbound side (issue 1439). A close says
  * PeerSharing's `Done` first and then releases the connection, and its link stays in the outbound
  * table until the connection manager lets go of the connection. All that time the governor counts
  * the peer as established, closing, and may ask it (`availableForPeerShare`,
  * `Governor/KnownPeers.hs` 223-224, and the demotion's wait, `Governor/EstablishedPeers.hs`
  * 970-980, at ouroboros-network c45735a5). The reference's controller is gone with its client
  * (`bracketPeerSharingClient`, `PeerSharing.hs` 81-93 at ouroboros-network c45735a5), so the
  * request answers `PeerSharingNotRegisteredYet` (`PeerSelectionActions.hs` 104-123 at
  * ouroboros-network c45735a5): no failure, no blame, and the connection stays.
  */
class OutboundPeerShareSuite extends FailsOnUncaught:
  private val magic = 42L
  private val peer: PeerNetwork.Addr = SocketAddress[Host](ip"10.1.2.3", port"3001")
  private val remote: SocketAddress[IpAddress] = SocketAddress(ip"10.1.2.3", port"3001")
  private val shared: PeerSharing.Address = PeerSharing.Address.V4(
    PeerSharing.Word32.from(0x0a000001L).getOrElse(fail("word")),
    PeerSharing.Port.from(3001).getOrElse(fail("port"))
  )
  private val five = PeerSharing.Amount.saturating(5)

  /** An empty chain, and one address to share. */
  private val handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    ServerHandlers(
      NtNHeader.codec,
      Resource.pure(cursor),
      new BlockFetchServer.Blocks[IO]:
        def range(from: ChainSync.Point, to: ChainSync.Point)(using Raise[IO, NetworkError]) =
          IO.pure(Peers.unknown)
      ,
      new TxSubmissionServer.Sink[IO]:
        def reader = RelayedTransactions
        def known(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(false)
        def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
            Raise[IO, NetworkError]
        ) = IO.unit
      ,
      peerSharing = Some(_ => IO.pure(Vector(shared)))
    )

  /** The peer, on an in-memory bearer, offering peer sharing. */
  private object Sharing extends Transport[IO, PeerNetwork.Addr]:
    def resolve(at: PeerNetwork.Addr) = IO.pure(Right(remote))
    def bearer(at: PeerNetwork.Addr, to: SocketAddress[IpAddress], mode: DiffusionMode)(using
        Raise[IO, NetworkError]
    ) =
      Resource.eval(Bearer.inMemory[IO](256)).flatMap { (theirs, ours) =>
        Handle
          .allow[NetworkError](
            Served
              .accept(theirs, magic, handlers, NodeToNodeServer.Settings(offerPeerSharing = true))
              .void
          )
          .rescue(_ => IO.unit)
          .background
          .as(ours)
      }

  private final case class World(
      outbound: OutboundPeers[IO, PeerNetwork.Addr],
      manager: ConnectionManager[IO],
      idle: Deferred[IO, Unit],
      reports: Ref[IO, Vector[Event[PeerNetwork.Addr]]]
  ):
    def state: IO[Option[ConnectionState]] = manager.lookup(remote).map(_.map(_.state))

  /** A node that does not listen, as a syncing one, so its connections are unidirectional. The
    * close of one says PeerSharing's `Done` and then releases the connection, which waits
    * `OutboundIdle` for the outbound idle timeout (`releaseOutboundConnectionImpl`,
    * `ConnectionManager/Core.hs` 1875-1887 and 2003-2027 at ouroboros-network c45735a5); here an
    * hour, so the close is in progress, and the link in the outbound table, for the whole test.
    * `idle` is completed once the connection is `OutboundIdle`.
    */
  private def world: Resource[IO, World] =
    for
      node <- NodeScope.resource
      supervisor <- Supervisor[IO](await = false)
      monitors <- Supervisor[IO](await = false)
      built <- Resource.eval {
        given Handle[IO, NodeError] = node
        for
          pool <- BlockPool.create[IO]
          idle <- Deferred[IO, Unit]
          manager <- ConnectionManager.create[IO](
            ConnectionManager.Limits(timeWait = Duration.Zero, outboundIdle = 1.hour),
            Some {
              case ConnectionEvent.Changed(_, _, ConnectionState.OutboundIdle(_)) =>
                idle.complete(()).void
              case _ => IO.unit
            }
          )
          reports <- Ref.of[IO, Vector[Event[PeerNetwork.Addr]]](Vector.empty)
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          given Stateful[IO, PeerMetrics[PeerNetwork.Addr]] = RefStateful(metrics)
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(offerPeerSharing = true),
            Peer
              .Settings(SocketAddress(host"10.1.2.3", port"3001"), magic, byronEpochSlots = 21600),
            (_, _) => IO.never[Unit],
            ChainSyncHeights.origin,
            None,
            None,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            _ => IO.unit
          )
          outbound <- OutboundPeers.create(
            Sharing,
            manager,
            OutboundPeers.Config(),
            hot,
            supervisor,
            monitors,
            event => reports.update(_ :+ event),
            summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]]
          )
        yield World(outbound, manager, idle, reports)
      }
    yield built

  private def lost(event: Event[PeerNetwork.Addr]): Boolean = event match
    case Event.Observed(_, _: Observation.Lost, _, _) => true
    case _                                            => false

  test("a request while the close is in progress, after its Done, answers not registered") {
    world.use { w =>
      for
        established <- w.outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
        handle <- IO.fromEither(established.leftMap(e => new AssertionError(e.toString)))
        before <- w.outbound.requestPeerShare(peer, five)(_ => Some(peer))
        // the close, in progress for the rest of the test, is cancelled however the test ends
        observed <- handle.close.background.use { _ =>
          for
            // the close said `Done` before it released the connection into `OutboundIdle`
            _ <- w.idle.get
            after <- w.outbound.requestPeerShare(peer, five)(_ => Some(peer))
            monitoring <- handle.read
            state <- w.state
            reports <- w.reports.get
          yield (after, monitoring, state, reports)
        }
        (after, monitoring, state, reports) = observed
      yield
        assert(handle.peerSharing, "peer sharing was agreed")
        assertEquals(before, PeerShare.Result.Peers(Vector(peer)))
        assertEquals(after, PeerShare.Result.NotRegistered[PeerNetwork.Addr]())
        assertEquals(monitoring.failure, None)
        assertEquals(state, Some(ConnectionState.OutboundIdle(DataFlow.Unidirectional)))
        assert(!reports.exists(lost), reports.toString)
    }
  }
