// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The loss of an outbound connection reaches the governor whichever of the connection's two fibers
  * claims it (issue 448). The hot session's fiber lives under the connection's own supervisor, so
  * the connection's teardown cancels it; a report made in that fiber went with it.
  */
class OutboundLossReportSuite extends FailsOnUncaught:
  private val magic = 42L
  private val peer = InMemoryPeers.address(9)

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

  /** How the wait for the loss reports ended. */
  private enum Ending:
    case Reported(seen: Vector[Observation.Lost])
    case Cancelled
    case Missing(seen: Vector[Observation.Lost])

  /** The in-memory peers, telling `released` once our end of a bearer has been released. */
  private final class Released(underlying: InMemoryPeers, released: Deferred[IO, Unit])
      extends Transport[IO, PeerNetwork.Addr]:
    def resolve(address: PeerNetwork.Addr) = underlying.resolve(address)
    def bearer(address: PeerNetwork.Addr, remote: SocketAddress[IpAddress])(using
        Raise[IO, NetworkError]
    ): Resource[IO, Bearer[IO]] =
      underlying.bearer(address, remote).onFinalize(released.complete(()).void)

  test("a hot session's loss report outlives the teardown of its connection (#448)") {
    for
      released <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      reported <- Deferred[IO, Vector[Observation.Lost]]
      observed <- Ref.of[IO, Vector[Observation.Lost]](Vector.empty)
      losses <- Ref.of[IO, Vector[PeerError]](Vector.empty)
      peers <- InMemoryPeers.create(magic)
      _ <- peers.serve(peer, handlers)
      // The session returns, which leaves the bearer open, so its own fiber claims the loss. Inside
      // the claim the peer dies: the connection's fiber sees the bearer close, finds the loss
      // claimed and tears the connection down, cancelling the session's fiber. The loss is
      // recorded only once the bearer has been released, after that teardown.
      lost = (error: PeerError) =>
        peers.kill(peer) *> released.get.onCancel(cancelled.complete(()).void) *>
          losses.update(_ :+ error)
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, observation: Observation.Lost, _) =>
            observed
              .updateAndGet(_ :+ observation)
              .flatMap(all => reported.complete(all).void.whenA(observation.released))
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
            hot = OutboundPeers.Hot[IO](
              magic = magic,
              network = NodeToNode.Settings(),
              peer = Peer.Settings(
                SocketAddress(host"127.0.0.1", port"3001"),
                magic,
                byronEpochSlots = 21600
              ),
              follow = (_, _) => IO.unit,
              prepare = _ => IO.unit,
              heightSnapshot = ChainSyncHeights.origin,
              headers = None,
              transactions = None,
              observe = None,
              pool = pool,
              versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
              lost = lost,
              fatal = _ => IO.unit
            )
            outbound <- OutboundPeers.create(
              new Released(peers, released),
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
          established <- outbound.establish(
            peer,
            DiffusionMode.InitiatorOnly,
            Provenance.Outbound
          )
          activated <- outbound.activate(peer)
          // the reports, or the cancellation that loses them; the bound only turns neither into a
          // failure that says so
          ended <- IO
            .race(cancelled.get.as(Ending.Cancelled), reported.get.map(Ending.Reported(_)))
            .map(_.merge)
            .timeoutTo(30.seconds, observed.get.map(Ending.Missing(_)))
          recorded <- losses.get
          opened <- peers.connections
        yield (established, activated, ended, recorded, opened)
      }
      (established, activated, ended, recorded, opened) = outcome
    yield
      assertEquals(established, None)
      assertEquals(activated, None)
      ended match
        case Ending.Cancelled => fail("the loss report was cancelled with its session's connection")
        case Ending.Missing(seen) => fail(s"the loss is not reported: $seen")
        case Ending.Reported(seen) =>
          assertEquals(seen.map(_.released), Vector(false, true))
          assertEquals(seen.map(_.failure), Vector(FailureClass.Transient, FailureClass.Transient))
      assertEquals(recorded, Vector(PeerError.Unavailable(PeerError.Unavailability.SessionEnded)))
      assertEquals(opened.getOrElse(peer, 0), 1)
  }
