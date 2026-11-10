// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Supervisor
import fs2.concurrent.SignallingRef
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.Listeners.failing
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionIdentityError, ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance, TimeoutExpired}

/** The node's TCP transport on loopback (issue 854): an outbound socket is bound to the node's
  * listening address in `InitiatorAndResponder` mode (ouroboros-network `2fb69829`,
  * `ConnectionManager/Core.hs` 1472-1491), so a peer that dialled us from its listening address and
  * our dial to it are one TCP connection, which the connection manager reuses (1335-1370); and a
  * node that dials its own address is connected to itself.
  */
class SocketTransportSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val timeout = 3.seconds

  private type Identity = Either[ConnectionIdentityError, ConnectionId]

  private def addr(address: SocketAddress[IpAddress]): PeerNetwork.Addr =
    SocketAddress[Host](address.host, address.port)

  private def own(address: SocketAddress[IpAddress]): NodeAddresses =
    NodeAddresses.listening(address).fold(e => fail(s"not a node address: $e"), identity)

  private def listening: Resource[IO, TcpListener[IO]] =
    TcpSockets
      .listen[IO](Listeners.Loopback)
      .evalMap(r => IO(r.fold(e => fail(s"listen failed: $e"), identity)))

  private def accepted(listener: TcpListener[IO]): Resource[IO, TcpStream[IO]] =
    listener.accept.evalMap(r => IO(r.fold(e => fail(s"accept failed: $e"), identity)))

  /** The address a peer listening at `peer` sees our dial come from, in diffusion `mode`. */
  private def seenFrom(ours: NodeAddresses, mode: DiffusionMode): IO[SocketAddress[IpAddress]] =
    listening.use { peer =>
      Peers
        .typed(
          PeerNetwork
            .tcp[IO](timeout, ours)
            .bearer(addr(peer.address), peer.address, mode)
            .use(_ => accepted(peer).use(inbound => IO.pure(inbound.remote)))
        )
        .map(_.fold(e => fail(s"dial failed: $e"), identity))
    }

  test("an outbound connection comes from the listening address only in InitiatorAndResponder") {
    listening.use { node =>
      val ours = own(node.address)
      for
        duplex <- seenFrom(ours, DiffusionMode.InitiatorAndResponder)
        initiatorOnly <- seenFrom(ours, DiffusionMode.InitiatorOnly)
        notListening <- seenFrom(NodeAddresses.none, DiffusionMode.InitiatorAndResponder)
      yield
        assertEquals(duplex, node.address)
        assertNotEquals(initiatorOnly.port, node.address.port)
        assertNotEquals(notListening.port, node.address.port)
    }
  }

  test("a node listening on the wildcard address dials from its port on the interface it uses") {
    TcpSockets
      .listen[IO](SocketAddress(ip"0.0.0.0", Port.Wildcard))
      .evalMap(r => IO(r.fold(e => fail(s"listen failed: $e"), identity)))
      .use { node =>
        seenFrom(own(node.address), DiffusionMode.InitiatorAndResponder).map(seen =>
          assertEquals(seen, SocketAddress[IpAddress](ip"127.0.0.1", node.address.port))
        )
      }
  }

  /** What a side serves: an empty chain. */
  private def handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) =
        IO.pure(Right((ChainSync.Point.Origin, ChainSync.Tip.Origin)))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    ServerHandlers(
      NtNHeader.codec,
      Resource.pure(cursor),
      new BlockFetchServer.Blocks[IO]:
        def range(from: ChainSync.Point, to: ChainSync.Point) = IO.pure(None)
      ,
      new TxSubmissionServer.Sink[IO]:
        def reader = RelayedTransactions
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes])(using Raise[IO, NetworkError]) = IO.unit
    )

  /** The node's TCP transport, counting the dials it makes and keeping the identity of each
    * connection it opened.
    */
  private final class Counted(
      own: NodeAddresses,
      dials: Ref[IO, Int],
      ids: Ref[IO, Vector[Identity]]
  ) extends Transport[IO, PeerNetwork.Addr]:
    private val tcp = PeerNetwork.tcp[IO](timeout, own)
    def resolve(at: PeerNetwork.Addr) = tcp.resolve(at)
    def bearer(at: PeerNetwork.Addr, to: SocketAddress[IpAddress], mode: DiffusionMode)(using
        Raise[IO, NetworkError]
    ) = Resource.eval(dials.update(_ + 1)) *> tcp
      .bearer(at, to, mode)
      .evalTap(_.connectionId.traverse_(_.flatMap(id => ids.update(_ :+ id))))

  private final case class Node(
      address: SocketAddress[IpAddress],
      manager: ConnectionManager[IO],
      outbound: OutboundPeers[IO, PeerNetwork.Addr],
      dials: Ref[IO, Int],
      ids: Ref[IO, Vector[Identity]],
      changes: SignallingRef[IO, Vector[(ConnectionState, ConnectionState)]]
  ):
    def state(remote: SocketAddress[IpAddress]): IO[Option[ConnectionState]] =
      manager.lookup(remote).map(_.map(_.state))

  /** A node that listens on loopback, with its manager, inbound governor and outbound side, and the
    * node's TCP transport bound to its listening address.
    */
  private def node: Resource[IO, Node] =
    for
      scope <- NodeScope.resource
      supervisor <- Supervisor[IO](await = false)
      changes <- Resource.eval(
        SignallingRef.of[IO, Vector[(ConnectionState, ConnectionState)]](Vector.empty)
      )
      manager <- Resource.eval(
        ConnectionManager.create[IO](
          ConnectionManager.Limits(timeWait = Duration.Zero),
          Some {
            case ConnectionEvent.Changed(_, from, to) => changes.update(_ :+ (from -> to))
            case _                                    => IO.unit
          }
        )
      )
      governor <- Resource.eval(
        InboundGovernor.create[IO](
          manager,
          InboundGovernor.Config(magic, NodeToNodeServer.Settings()),
          handlers
        )
      )
      address <- BearerServer
        .listen[IO, NetworkError](Listeners.Loopback, maxConnections = None)((_, _) => IO.unit) {
          (bearer, remote) => governor.serve(bearer, remote).void
        }
        .map(_.address)
      dials <- Resource.eval(Ref.of[IO, Int](0))
      ids <- Resource.eval(Ref.of[IO, Vector[Identity]](Vector.empty))
      outbound <- Resource.eval {
        given Handle[IO, NodeError] = scope
        for
          pool <- BlockPool.create[IO]
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          given Stateful[IO, PeerMetrics[PeerNetwork.Addr]] = RefStateful(metrics)
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(),
            Peer
              .Settings(SocketAddress(host"127.0.0.1", port"3001"), magic, byronEpochSlots = 21600),
            (_, _) => IO.never[Unit],
            ChainSyncHeights.origin,
            None,
            None,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            _ => IO.unit,
            serve = Some(handlers)
          )
          outbound <- OutboundPeers.create(
            Counted(own(address), dials, ids),
            manager,
            OutboundPeers.Config(closeTimeout = 2.seconds),
            hot,
            supervisor,
            _ => IO.unit,
            summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]]
          )
        yield outbound
      }
    yield Node(address, manager, outbound, dials, ids, changes)

  private def inboundDuplex(state: Option[ConnectionState]): Boolean = state.exists {
    case ConnectionState.InboundIdle(DataFlow.Duplex) | ConnectionState.Inbound(DataFlow.Duplex) =>
      true
    case _ => false
  }

  private def outboundDuplex(state: Option[ConnectionState]): Boolean =
    state.contains(ConnectionState.Duplex) ||
      state.contains(ConnectionState.OutboundDup(TimeoutExpired.Ticking))

  test("a peer that dialled from its listening address is reused: the same four-tuple, no dial") {
    (node, listening).tupled.use { (us, peer) =>
      // the peer, listening at `peer.address`, dials us from that address offering duplex
      def dialled(using Raise[IO, NetworkError]) = TcpSockets.dial[IO](
        OutboundPlan.of(us.address, own(peer.address), DiffusionMode.InitiatorAndResponder),
        timeout
      )
      Peers
        .typed(
          dialled
            .flatMap(stream =>
              NodeToNode
                .warmOver[IO, NtNHeader](
                  stream.bearer,
                  magic,
                  NtNHeader.codec,
                  NodeToNode.Settings(),
                  Some(handlers)
                )
                .as(stream)
            )
            .use { stream =>
              for
                // the manager reports each change: wait for the peer's connection to be negotiated
                _ <- us.changes.discrete
                  .find(_.exists((_, to) => inboundDuplex(Some(to))))
                  .compile
                  .drain
                before <- us.state(peer.address)
                // our own dial to it would be the same four-tuple, which the kernel refuses
                second <- Peers.typed(
                  PeerNetwork
                    .tcp[IO](timeout, own(us.address))
                    .bearer(addr(peer.address), peer.address, DiffusionMode.InitiatorAndResponder)
                    .use(_ => IO.unit)
                )
                established <- us.outbound.establish(
                  addr(peer.address),
                  DiffusionMode.InitiatorAndResponder,
                  Provenance.Outbound
                )
                after <- us.state(peer.address)
                dials <- us.dials.get
                snapshot <- us.manager.snapshot
              yield
                assertEquals(stream.local, peer.address)
                assert(inboundDuplex(before), before.toString)
                assertEquals(
                  second,
                  Left(
                    NetworkError.Connect(
                      peer.address.toString,
                      TcpSocketError.Failed(
                        SocketStep.Connect(peer.address),
                        ErrnoCause.Known(Errno.EADDRNOTAVAIL)
                      )
                    )
                  )
                )
                assert(established.isRight, established.toString)
                assert(outboundDuplex(after), after.toString)
                assertEquals(dials, 0)
                assertEquals(snapshot.connections.map(_._1), Vector(peer.address))
            }
        )
        .map(result => assert(result.isRight, result.toString))
    }
  }

  // ouroboros-network `2fb69829`, `ConnectionManager/Core.hs` 1510-1521: bound to the address it
  // dials, the socket is connected to itself on Linux, and the listener accepts nothing
  test("a socket bound to the listening address and dialling it is connected to itself") {
    listening.use { node =>
      TcpSockets
        .connect[IO](
          OutboundPlan.of(node.address, own(node.address), DiffusionMode.InitiatorAndResponder),
          timeout
        )
        .use {
          case Left(error) => IO(fail(s"connect failed: $error"))
          case Right(stream) =>
            val bytes = fs2.Chunk.array("echo".getBytes)
            for
              _ <- stream.bearer.write(bytes)
              echoed <- stream.bearer.reads.take(bytes.size.toLong).compile.to(fs2.Chunk)
            yield
              assertEquals(stream.local, node.address)
              assertEquals(stream.remote, node.address)
              assertEquals(echoed, bytes)
        }
    }
  }

  test("a node that dials its own address negotiates with itself over one outbound connection") {
    node.use { us =>
      for
        established <- us.outbound.establish(
          addr(us.address),
          DiffusionMode.InitiatorAndResponder,
          Provenance.Outbound
        )
        state <- us.state(us.address)
        ids <- us.ids.get
        snapshot <- us.manager.snapshot
        changes <- us.changes.get
      yield
        assert(established.isRight, established.toString)
        assert(outboundDuplex(state), state.toString)
        // one connection, from our own address to it
        val self = ConnectionId.fromSocketAddresses(us.address, us.address)
        assertEquals(ids, Vector(self))
        assertEquals(snapshot.connections.map(_._1), Vector(us.address))
        // `Connected`, and no accepted side: the listener accepts nothing (no `SelfConn`)
        assert(
          changes.contains(
            ConnectionState.ReservedOutbound -> ConnectionState.Unnegotiated(Provenance.Outbound)
          ),
          changes.toString
        )
        assert(
          !changes.exists(_._2 == ConnectionState.Unnegotiated(Provenance.Inbound)),
          changes.toString
        )
        changes.foreach((from, to) =>
          assert(ConnectionState.valid(Some(from), Some(to)), s"invalid $from -> $to")
        )
    }
  }
