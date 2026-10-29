// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  GenesisInternalProblem,
  NodeError,
  PeerError,
  TestGeometry
}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** An invariant of the Genesis code met in a fiber of an outbound connection ends that connection,
  * uncharged, and the node goes on (ADR 0017, issue 583). The fiber that met it waits in `never`
  * until the connection's teardown cancels it: the hot session lives under the connection's own
  * supervisor, which the connection's release ends.
  */
class OutboundGenesisInvariantSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds
  private val magic = 42L
  private val peer = InMemoryPeers.address(9)
  private val geometry =
    EpochGeometry(Instant.EPOCH, 1000, 100, TestGeometry.k(2), TestGeometry.f("1"))
  private val local =
    ChainSelection.Local(ChainSync.Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)

  /** A peer whose chain is empty: the session's intersection is the origin. */
  private def handlers: ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) =
        IO.pure(Right(ChainSync.Point.Origin -> ChainSync.Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never[ChainSyncServer.Update[NtNHeader]]
    val blocks = new BlockFetchServer.Blocks[IO]:
      def range(from: ChainSync.Point, to: ChainSync.Point) = IO.pure(None)
    val transactions = new TxSubmissionServer.Sink[IO]:
      def known(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(false)
      def submit(originals: Vector[xyz.kd5ujc.scarab.codec.Bytes])(using
          Raise[IO, NetworkError]
      ) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  /** The in-memory peers, with the TCP identity that the Genesis controls of a connection need. */
  private final class Identified(underlying: InMemoryPeers) extends Transport[IO, PeerNetwork.Addr]:
    def resolve(address: PeerNetwork.Addr) = underlying.resolve(address)
    def bearer(address: PeerNetwork.Addr, remote: SocketAddress[IpAddress])(using
        Raise[IO, NetworkError]
    ): Resource[IO, Bearer[IO]] =
      underlying.bearer(address, remote).map { inner =>
        new Bearer[IO]:
          override def connectionId =
            Some(
              IO.pure(
                ConnectionId.fromSocketAddresses(SocketAddress(ip"10.9.9.9", port"3001"), remote)
              )
            )
          def reads = inner.reads
          def write(bytes: fs2.Chunk[Byte]) = inner.write(bytes)
          def close = inner.close
      }

  /** The node's header check as a Genesis node has it, whose gate meets an invariant of the Genesis
    * code when it starts, the way the header gate does at its intersection (`invariant(...) *>
    * F.never`): told to `invariant`, the node-wide channel until the connection gives its own.
    * `cancelled` completes when the fiber that waits after it is cancelled.
    */
  private final class Breaking(
      problem: GenesisInternalProblem,
      invariant: GenesisInternalProblem => IO[Unit],
      cancelled: Deferred[IO, Unit]
  ) extends HeaderCheck.Grafting[IO]:
    def startGrafting(intersection: ChainSync.Point): IO[HeaderGate.Grafting[IO]] =
      (invariant(problem) *> IO.never[HeaderGate.Grafting[IO]])
        .onCancel(cancelled.complete(()).void)
    def start(intersection: ChainSync.Point): IO[HeaderGate[IO]] = startGrafting(intersection).widen
    def captureGrafting(using cats.Applicative[IO]) = IO.pure(None)
    override def withInvariant(told: GenesisInternalProblem => IO[Unit]): HeaderCheck.Grafting[IO] =
      Breaking(problem, told, cancelled)

  test(
    "a Genesis invariant met by an outbound session's header gate ends that session, not the node"
  ) {
    val problem = GenesisInternalProblem.HeaderSelectionUnavailable(ChainSync.Point.Origin)
    for
      cancelled <- Deferred[IO, Unit]
      released <- Deferred[IO, Vector[Observation.Lost]]
      observed <- Ref.of[IO, Vector[Observation.Lost]](Vector.empty)
      losses <- Ref.of[IO, Vector[PeerError]](Vector.empty)
      fatalities <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      toNode <- Ref.of[IO, Vector[GenesisInternalProblem]](Vector.empty)
      peers <- InMemoryPeers.create(magic)
      _ <- peers.serve(peer, handlers)
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, observation: Observation.Lost, _, _) =>
            observed
              .updateAndGet(_ :+ observation)
              .flatMap(all => released.complete(all).void.whenA(observation.released))
          case _ => IO.unit
      outcome <- (for
        node <- NodeScope.resource
        supervisor <- Supervisor[IO](await = false)
        outbound <- Resource.eval {
          given Handle[IO, NodeError] = node
          for
            pool <- BlockPool.create[IO]
            manager <- ConnectionManager.create[IO]()
            metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
            governor <- GenesisGovernor.create[IO](
              IO.pure(
                GenesisGovernor.View(Fragment(ChainSync.Point.Origin, Vector.empty), 2, _ => None)
              )
            )
            // held to the node's clock, as every live session's check is in the node (`Main`)
            check = HeaderClock.grafting[IO](
              SlotClock.system[IO](IO.pure(geometry)),
              Breaking(problem, p => toNode.update(_ :+ p), cancelled)
            )
            runtime <- GenesisRuntime.create[IO](
              governor,
              GenesisSettings(),
              IO.pure(local),
              IO.pure(geometry),
              IO.pure(BigInt(0)),
              IO.unit,
              check
            )
            // as the follower does: find the intersection, then take what the peer announces
            follow: OutboundPeers.Follow[IO] = (session, _) =>
              session.intersect(Vector(ChainSync.Point.Origin)) *> session.next.foreverM
            hot = OutboundPeers.Hot[IO](
              magic = magic,
              network = NodeToNode.Settings(),
              peer = Peer.Settings(
                SocketAddress(host"127.0.0.1", port"3001"),
                magic,
                byronEpochSlots = 21600
              ),
              follow = follow,
              prepare = _ => IO.unit,
              heightSnapshot = ChainSyncHeights.origin,
              headers = None,
              transactions = None,
              observe = None,
              pool = pool,
              versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
              lost = error => losses.update(_ :+ error),
              fatal = value => fatalities.update(_ :+ value),
              genesis = Some(runtime)
            )
            outbound <- OutboundPeers.create(
              Identified(peers),
              manager,
              OutboundPeers.Config(),
              hot,
              supervisor,
              report,
              RefStateful(metrics): Stateful[IO, PeerMetrics[PeerNetwork.Addr]]
            )
          yield outbound
        }
      yield outbound).use { outbound =>
        for
          established <- outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
          activated <- outbound.activate(peer)
          seen <- released.get.timeoutTo(15.seconds, observed.get)
          waiter <- cancelled.tryGet
          lost <- losses.get
          stopped <- fatalities.get
          told <- toNode.get
        yield (established, activated, seen, waiter, lost, stopped, told)
      }
      (established, activated, seen, waiter, lost, stopped, told) = outcome
    yield
      assert(established.isRight)
      assertEquals(activated, Right(()))
      assertEquals(lost, Vector(PeerError.GenesisInvariant(problem)))
      assertEquals(seen.map(_.released), Vector(false, true))
      assertEquals(seen.map(_.failure), Vector(FailureClass.NotThePeers, FailureClass.NotThePeers))
      assertEquals(waiter, Some(()))
      assertEquals(stopped, Vector.empty[Fatal])
      assertEquals(told, Vector.empty)
  }
