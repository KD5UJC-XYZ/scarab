// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
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
      def known(id: xyz.kd5ujc.scarab.codec.Bytes) = IO.pure(false)
      def submit(originals: Vector[xyz.kd5ujc.scarab.codec.Bytes])(using
          cats.mtl.Raise[IO, NetworkError]
      ) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  /** What the governor and the node were told. */
  private final case class Told(
      losses: Vector[PeerError],
      observed: Vector[Observation.Lost],
      fatalities: Vector[Fatal]
  )

  /** Run one outbound peer whose monitor throws `cause` once, the first time it observes the
    * connection's applications after `use` armed it, with `lost` as the node's loss callback.
    */
  private def run[A](lost: PeerError => IO[Unit], cause: Throwable)(
      use: (OutboundPeers[IO, PeerNetwork.Addr], IO[Unit], InMemoryPeers, Ref[IO, Told]) => IO[A]
  ): IO[A] =
    for
      armed <- Ref.of[IO, Boolean](false)
      told <- Ref.of[IO, Told](Told(Vector.empty, Vector.empty, Vector.empty))
      peers <- InMemoryPeers.create(magic)
      _ <- peers.serve(peer, handlers)
      report = (event: Event[PeerNetwork.Addr]) =>
        event match
          case Event.Observed(_, observation: Observation.Lost, _, _) =>
            told.update(t => t.copy(observed = t.observed :+ observation))
          case _ => IO.unit
      probe = armed.getAndSet(false).flatMap(IO.raiseError(cause).whenA(_))
      result <- (for
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
              follow = (_, _) => IO.never,
              prepare = _ => IO.unit,
              heightSnapshot = ChainSyncHeights.origin,
              headers = None,
              transactions = None,
              observe = None,
              pool = pool,
              versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
              lost = error => told.update(t => t.copy(losses = t.losses :+ error)) *> lost(error),
              fatal = problem => told.update(t => t.copy(fatalities = t.fatalities :+ problem))
            )
            outbound <- OutboundPeers.create(
              peers,
              manager,
              OutboundPeers.Config(),
              hot,
              supervisor,
              report,
              RefStateful(metrics): Stateful[IO, PeerMetrics[PeerNetwork.Addr]],
              beforeObservation = Some(probe)
            )
          yield outbound
        }
      yield outbound).use(outbound => use(outbound, armed.set(true), peers, told))
    yield result

  // the governor's and the node's callbacks are the observable condition; poll them, bounded
  private def settled(told: Ref[IO, Told])(done: Told => Boolean): IO[Told] =
    (IO.sleep(5.millis) *> told.get).iterateUntil(done).timeout(15.seconds)

  test("a monitor that throws ends its own connection, uncharged, and the node goes on") {
    val cause = new IllegalStateException("injected monitor defect")
    val defect = PeerError.ConnectionDefect(cause)
    run(_ => IO.unit, cause) { (outbound, arm, peers, told) =>
      for
        _ <- arm
        established <- outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
        ended <- settled(told)(_.observed.exists(_.released))
        closed <- peers.closedConnections
        // the retained report reads the applications' results, which take the abort a moment
        // after the release is observed: poll it, bounded
        retained <- (IO.sleep(5.millis) *> outbound
          .monitorPeerConnection(peer)
          .flatMap(_.traverse(_.read)))
          .iterateUntil(_.exists(_.failure.nonEmpty))
          .timeout(15.seconds)
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
        assertEquals(retained.flatMap(_.failure), Some(defect))
        assert(again.isRight, again.toString)
        assertEquals(promoted, Right(()))
    }
  }

  test("a monitor whose recovery throws again stops the node with the defect") {
    val cause = new IllegalStateException("injected monitor defect")
    val again = new IllegalStateException("injected recovery defect")
    run(_ => IO.raiseError(again), cause) { (outbound, arm, _, told) =>
      for
        _ <- arm
        established <- outbound.establish(peer, DiffusionMode.InitiatorOnly, Provenance.Outbound)
        ended <- settled(told)(_.fatalities.nonEmpty)
      yield
        assert(established.isRight)
        assertEquals(ended.losses, Vector(PeerError.ConnectionDefect(cause)))
        assertEquals(ended.fatalities, Vector(Fatal.Defect(again)))
        assertEquals(ended.observed, Vector.empty)
    }
  }
