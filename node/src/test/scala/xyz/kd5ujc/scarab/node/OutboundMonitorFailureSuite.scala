// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The monitor of one outbound connection that fails ends that connection, not the node; if ending
  * it fails too, the node's own connection state is broken and the node stops (ADR 0017, issue
  * 579). The failure is injected where the monitor observes the connection's applications.
  */
class OutboundMonitorFailureSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val peer = InMemoryPeers.address(9)

  private def handlers: ServerHandlers[IO, NtNHeader] =
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

  /** What the governor and the node were told, and the failure the connection's retained report
    * carried at the moment the node was told of each loss.
    */
  private final case class Told(
      losses: Vector[PeerError],
      retainedAtLoss: Vector[Option[PeerError]],
      observed: Vector[Observation.Lost],
      fatalities: Vector[Fatal]
  )

  /** What a test waits on: each completes, with everything told until then, once the governor was
    * told that the connection was released, or once the node was told to stop.
    */
  private final case class Settled(released: IO[Told], stopped: IO[Told])

  /** Run one outbound peer whose monitor throws `cause` once, the first time it observes the
    * connection's applications after `use` armed it, with `lost` as the node's loss callback.
    */
  private def run[A](lost: PeerError => IO[Unit], cause: Throwable)(
      use: (OutboundPeers[IO, PeerNetwork.Addr], IO[Unit], InMemoryPeers, Settled) => IO[A]
  ): IO[A] =
    for
      armed <- Ref.of[IO, Boolean](false)
      told <- Ref.of[IO, Told](Told(Vector.empty, Vector.empty, Vector.empty, Vector.empty))
      released <- Deferred[IO, Unit]
      stopped <- Deferred[IO, Unit]
      installed <- Ref.of[IO, Option[OutboundPeers[IO, PeerNetwork.Addr]]](None)
      peers <- InMemoryPeers.create(magic)
      _ <- peers.serve(peer, handlers)
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, observation: Observation.Lost, _, _) =>
            told.update(t => t.copy(observed = t.observed :+ observation)) *>
              released.complete(()).void.whenA(observation.released)
          case _ => IO.unit
      probe = armed.getAndSet(false).flatMap(IO.raiseError(cause).whenA(_))
      // the governor's and the node's callbacks are the observable condition, bounded
      settled = Settled(
        released.get.timeout(15.seconds) *> told.get,
        stopped.get.timeout(15.seconds) *> told.get
      )
      result <- (for
        node <- NodeScope.resource
        supervisor <- Supervisor[IO](await = false)
        monitors <- Supervisor[IO](await = false)
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
              follow = (_, _) => IO.never,
              heightSnapshot = ChainSyncHeights.origin,
              headers = None,
              transactions = None,
              observe = None,
              pool = pool,
              versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
              lost = error =>
                installed.get
                  .flatMap(_.flatTraverse(_.monitorPeerConnection(peer)))
                  .flatMap(_.flatTraverse(_.read.map(_.failure)))
                  .flatMap(retained =>
                    told.update(t =>
                      t.copy(
                        losses = t.losses :+ error,
                        retainedAtLoss = t.retainedAtLoss :+ retained
                      )
                    )
                  ) *> lost(error),
              fatal = problem =>
                told.update(t => t.copy(fatalities = t.fatalities :+ problem)) *>
                  stopped.complete(()).void
            )
            outbound <- OutboundPeers.create(
              peers,
              manager,
              OutboundPeers.Config(),
              hot,
              supervisor,
              monitors,
              report,
              RefStateful(metrics): Stateful[IO, PeerMetrics[PeerNetwork.Addr]],
              beforeObservation = Some(probe)
            )
            _ <- installed.set(Some(outbound))
          yield outbound
        }
      yield outbound).use(outbound => use(outbound, armed.set(true), peers, settled))
    yield result

  test("a monitor that throws ends its own connection, uncharged, and the node goes on") {
    val cause = new IllegalStateException("injected monitor defect")
    val defect = PeerError.ConnectionDefect(cause)
    run(_ => IO.unit, cause) { (outbound, arm, peers, settled) =>
      for
        _ <- arm
        established <- outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
        ended <- settled.released
        closed <- peers.closedConnections
        // the node goes on: the same peer is connected to again and promoted
        again <- outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
        promoted <- outbound.activate(peer)
      yield
        assert(established.isRight)
        assertEquals(ended.losses, Vector(defect))
        assertEquals(ended.observed.map(_.released), Vector(false, true))
        assertEquals(
          ended.observed.map(_.failure),
          Vector(FailureClass.NotThePeers, FailureClass.NotThePeers)
        )
        assertEquals(ended.fatalities, Vector.empty)
        assertEquals(closed.getOrElse(peer, 0), 1)
        // the defect is retained before the node is told of the loss, whichever of the release
        // and the mux's closure the connection sees first, and whether or not an application
        // was between messages
        assertEquals(ended.retainedAtLoss, Vector(Some(defect)))
        assert(again.isRight, again.toString)
        assertEquals(promoted, Right(()))
    }
  }

  test("a monitor whose recovery throws again stops the node with the defect") {
    val cause = new IllegalStateException("injected monitor defect")
    val again = new IllegalStateException("injected recovery defect")
    run(_ => IO.raiseError(again), cause) { (outbound, arm, _, settled) =>
      for
        _ <- arm
        established <- outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
        ended <- settled.stopped
      yield
        assert(established.isRight)
        assertEquals(ended.losses, Vector(PeerError.ConnectionDefect(cause)))
        assertEquals(ended.fatalities, Vector(Fatal.Defect(again)))
        assertEquals(ended.observed, Vector.empty)
    }
  }
