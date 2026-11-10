// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{NodeError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance}

/** The outbound side over a duplex connection the peer opened (ouroboros-network `2fb69829`,
  * `ConnectionManager/Core.hs` 1335-1370, `PeerStateActions.hs` 777-905, 1094-1105): our clients
  * run only where the outbound governor promotes the peer, and then over the peer's own connection,
  * with no dial and no second handshake. The inbound governor runs none of its own (issue 854).
  */
class OutboundReuseSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val peer: PeerNetwork.Addr = SocketAddress[Host](ip"10.1.2.3", port"3001")
  private val remote: SocketAddress[IpAddress] = SocketAddress(ip"10.1.2.3", port"3001")
  private val limits = ConnectionManager.Limits(timeWait = Duration.Zero)

  /** What a side serves: an empty chain whose intersections are counted. */
  private def handlers(
      intersections: Ref[IO, Int],
      intersecting: Boolean = true
  ): ServerHandlers[IO, NtNHeader] =
    val cursor = new ChainSyncServer.Source[IO, NtNHeader]:
      def intersect(points: Vector[ChainSync.Point]) =
        intersections
          .update(_ + 1)
          .as(
            if intersecting then Right((ChainSync.Point.Origin, ChainSync.Tip.Origin))
            else Left(ChainSync.Tip.Origin)
          )
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

  /** A transport that dials nothing: reuse must not need it. */
  private final class NoDial(dials: Ref[IO, Int]) extends Transport[IO, PeerNetwork.Addr]:
    def resolve(at: PeerNetwork.Addr) = IO.pure(Right(remote))
    def bearer(at: PeerNetwork.Addr, to: SocketAddress[IpAddress])(using
        raise: Raise[IO, NetworkError]
    ) = Resource.eval(
      dials.update(_ + 1) *> raise.raise[NetworkError, Bearer[IO]](
        NetworkError.Connect(at.toString, new java.io.IOException("refused"))
      )
    )

  private final case class World(
      manager: ConnectionManager[IO],
      governor: InboundGovernor[IO],
      outbound: OutboundPeers[IO, PeerNetwork.Addr],
      dials: Ref[IO, Int],
      intersected: Deferred[IO, Unit],
      changes: Ref[IO, Vector[(ConnectionState, ConnectionState)]],
      reports: Ref[IO, Vector[Event[PeerNetwork.Addr]]]
  ):
    def state: IO[Option[ConnectionState]] = manager.lookup(remote).map(_.map(_.state))

  /** A mempool with nothing to offer: a blocking request for ids waits until it is asked to end. */
  private val nothingToOffer: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    def reader = RelayedTransactions
    def after(ticket: Long, max: Int) = IO.pure(Vector.empty)
    def awaitAfter(ticket: Long) = IO.never
    def body(id: Bytes) = IO.pure(None)

  private def world: Resource[IO, World] = worldWith()

  private def worldWith(
      transactions: Option[TxSubmissionClient.Source[IO]] = None,
      deactivateTimeout: FiniteDuration = 2.seconds
  ): Resource[IO, World] =
    for
      node <- NodeScope.resource
      supervisor <- Supervisor[IO](await = false)
      built <- Resource.eval {
        given Handle[IO, NodeError] = node
        for
          pool <- BlockPool.create[IO]
          changes <- Ref.of[IO, Vector[(ConnectionState, ConnectionState)]](Vector.empty)
          manager <- ConnectionManager.create[IO](
            limits,
            Some {
              case ConnectionEvent.Changed(_, from, to) => changes.update(_ :+ (from -> to))
              case _                                    => IO.unit
            }
          )
          ours <- Ref.of[IO, Int](0)
          governor <- InboundGovernor.create[IO](
            manager,
            InboundGovernor.Config(magic, NodeToNodeServer.Settings()),
            handlers(ours)
          )
          dials <- Ref.of[IO, Int](0)
          intersected <- Deferred[IO, Unit]
          reports <- Ref.of[IO, Vector[Event[PeerNetwork.Addr]]](Vector.empty)
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          given Stateful[IO, PeerMetrics[PeerNetwork.Addr]] = RefStateful(metrics)
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(),
            Peer
              .Settings(SocketAddress(host"10.1.2.3", port"3001"), magic, byronEpochSlots = 21600),
            // the follower's own rule: no intersection ends the session (`Follower.intersect`)
            (over, _) =>
              over.intersect(Vector(ChainSync.Point.Origin)).flatMap {
                case None => Raise[IO, NodeError].raise[NodeError, Unit](PeerError.NoIntersection)
                case Some(_) => intersected.complete(()) *> IO.never[Unit]
              },
            ChainSyncHeights.origin,
            None,
            transactions,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            _ => IO.unit
          )
          outbound <- OutboundPeers.create(
            NoDial(dials),
            manager,
            OutboundPeers.Config(closeTimeout = 2.seconds, deactivateTimeout = deactivateTimeout),
            hot,
            supervisor,
            event => reports.update(_ :+ event),
            summon[Stateful[IO, PeerMetrics[PeerNetwork.Addr]]]
          )
        yield World(manager, governor, outbound, dials, intersected, changes, reports)
      }
    yield built

  /** The peer dials us offering duplex and stays connected until `during` is done (or it closes the
    * connection itself with `close`).
    */
  private def peerConnects[A](w: World, served: Ref[IO, Int], intersecting: Boolean = true)(
      during: IO[Unit] => IO[A]
  ): IO[A] =
    Bearer.inMemory[IO](256).flatMap { (theirs, ours) =>
      w.governor.serve(ours, remote).start.flatMap { _ =>
        Handle
          .allow[NetworkError] {
            NodeToNode
              .warmOver[IO, NtNHeader](
                theirs,
                magic,
                NtNHeader.codec,
                NodeToNode.Settings(),
                Some(handlers(served, intersecting))
              )
              .use(_ => during(theirs.close))
          }
          .rescue(e => IO(fail(e.message)))
      }
    }

  private def eventually[A](what: String)(read: IO[A])(ok: A => Boolean): IO[A] =
    read
      .flatMap(a => if ok(a) then IO.pure(a) else IO.sleep(20.millis) *> eventually(what)(read)(ok))
      .timeout(10.seconds)
      .adaptError(_ => new AssertionError(s"timed out: $what"))

  private def allValid(changes: Vector[(ConnectionState, ConnectionState)]): Unit =
    changes.foreach((from, to) =>
      assert(ConnectionState.valid(Some(from), Some(to)), s"invalid $from -> $to")
    )

  private def accepted(state: Option[ConnectionState]): Boolean = state.exists {
    case ConnectionState.InboundIdle(DataFlow.Duplex) | ConnectionState.Inbound(DataFlow.Duplex) =>
      true
    case _ => false
  }

  test("a duplex peer that connected to us is followed once promoted, over its own connection") {
    world.use { w =>
      Ref.of[IO, Int](0).flatMap { served =>
        peerConnects(w, served) { _ =>
          for
            // the inbound governor alone runs no client on it
            before <- eventually("the peer's connection is negotiated")(w.state)(accepted)
            _ <- IO.sleep(200.millis)
            idle <- served.get
            established <- w.outbound.establish(
              peer,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound
            )
            used <- w.state
            activated <- w.outbound.activate(peer)
            _ <- w.intersected.get.timeout(5.seconds)
            asked <- served.get
            dials <- w.dials.get
            snapshot <- w.manager.snapshot
            changes <- w.changes.get
          yield
            assert(accepted(before))
            assertEquals(idle, 0)
            assert(established.isRight, established.toString)
            assert(
              used.contains(ConnectionState.Duplex) ||
                used.contains(ConnectionState.OutboundDup(ConnectionState.TimeoutExpired.Ticking)),
              used.toString
            )
            assertEquals(activated, Right(()))
            assertEquals(asked, 1)
            // no dial: one connection, no second handshake
            assertEquals(dials, 0)
            assertEquals(snapshot.connections.size, 1)
            allValid(changes)
        }
      }
    }
  }

  test("closing the outbound use leaves the peer's connection serving it; the peer goes cold") {
    world.use { w =>
      Ref.of[IO, Int](0).flatMap { served =>
        peerConnects(w, served) { _ =>
          for
            _ <- eventually("the peer's connection is negotiated")(w.state)(accepted)
            established <- w.outbound.establish(
              peer,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound
            )
            handle <- IO.fromEither(established.leftMap(e => new AssertionError(e.toString)))
            closed <- handle.close
            _ <- handle.awaitCold.timeout(10.seconds)
            after <- w.state
            changes <- w.changes.get
          yield
            assert(closed.isRight, closed.toString)
            assert(accepted(after), after.toString)
            allValid(changes)
        }
      }
    }
  }

  test("the peer closing its connection ends our use of it: the peer goes cold") {
    world.use { w =>
      Ref.of[IO, Int](0).flatMap { served =>
        peerConnects(w, served) { close =>
          for
            _ <- eventually("the peer's connection is negotiated")(w.state)(accepted)
            established <- w.outbound.establish(
              peer,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound
            )
            handle <- IO.fromEither(established.leftMap(e => new AssertionError(e.toString)))
            _ <- close
            _ <- handle.awaitCold.timeout(10.seconds)
            gone <- eventually("the connection is gone")(w.state)(_.isEmpty)
            dials <- w.dials.get
          yield
            assertEquals(gone, None)
            assertEquals(dials, 0)
        }
      }
    }
  }

  // `InboundConnectionNotFound` (`Core.hs` 1390-1399)
  test("a peer of inbound provenance that is not connected is not dialled") {
    world.use { w =>
      for
        established <- w.outbound.establish(
          peer,
          DiffusionMode.InitiatorAndResponder,
          Provenance.Inbound
        )
        dials <- w.dials.get
      yield
        established match
          case Left(
                EstablishmentError.Failed(
                  PeerError.Unavailable(
                    PeerError.Unavailability.Connection(
                      ConnectionError.InboundConnectionNotFound(_)
                    )
                  )
                )
              ) =>
            ()
          case other => fail(s"unexpected $other")
        assertEquals(dials, 0)
    }
  }

  // a hot session over the peer's own connection that finds no intersection returns normally
  // (`NoMoreIntersection`): its hot protocols end on the wire, TxSubmission2 with `MsgDone` at the
  // peer's next blocking request, and the peer is warm again on the same connection
  // (`PeerStateActions.hs` 990-1035), well within the deactivation timeout
  test("a hot session that finds no intersection over the peer's own connection goes back to warm") {
    worldWith(transactions = Some(nothingToOffer), deactivateTimeout = 60.seconds).use { w =>
      Ref.of[IO, Int](0).flatMap { served =>
        peerConnects(w, served, intersecting = false) { _ =>
          for
            _ <- eventually("the peer's connection is negotiated")(w.state)(accepted)
            established <- w.outbound.establish(
              peer,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound
            )
            handle <- IO.fromEither(established.leftMap(e => new AssertionError(e.toString)))
            _ <- handle.activate(ChainSyncTimeouts.PeerTrust.Untrustable)
            _ <- eventually("the hot session ended")(w.reports.get)(_.exists {
              case Event.Observed(_, Observation.HotEnded(_, _), _, _) => true
              case _                                                   => false
            })
            status <- handle.read
            after <- w.state
          yield
            assertEquals(status.status, PeerStateActions.Status.Warm)
            assert(after.exists(_.isLive), after.toString)
        }
      }
    }
  }
