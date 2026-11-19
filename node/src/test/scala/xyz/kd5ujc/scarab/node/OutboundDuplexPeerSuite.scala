// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance}

/** A connection the node dials is registered with the inbound governor as a duplex peer when its
  * handshake agrees on duplex and the node runs an inbound governor, as the reference's connection
  * manager announces it (`NewConnection` with provenance `Outbound`, `ConnectionManager/Core.hs`
  * 1594-1625 at ouroboros-network `c45735a5`; the same at `4646039f` and `2f1c154f`): fresh from
  * the governor's step that handles it, mature at its first step 900 s or more later, and gone when
  * the connection ends. Over in-memory bearers, on the simulated clock.
  */
class OutboundDuplexPeerSuite extends FailsOnUncaught:
  private val magic = 42L
  private val peer = InMemoryPeers.address(7)
  private val none = Map.empty[PeerNetwork.Addr, Boolean]
  // the governor's steps begin 31.415927 s apart when no event wakes it; the 29th, at
  // 911.061883 s, is the first at or after 900 s
  private val due = 31415927.microseconds * 29

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
      def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
          cats.mtl.Raise[IO, NetworkError]
      ) = IO.unit
    ServerHandlers(NtNHeader.codec, Resource.pure(cursor), blocks, transactions)

  /** The outbound peers of a node whose inbound governor's steps run, with `sharing` 's duplex
    * peers registered from dialled connections when `responder`.
    */
  private def outbound(
      sharing: PeerSharingRuntime[IO],
      responder: Boolean
  ): Resource[IO, OutboundPeers[IO, PeerNetwork.Addr]] =
    for
      node <- NodeScope.resource
      supervisors <- (Supervisor[IO](await = false), Supervisor[IO](await = false)).tupled
      (supervisor, monitors) = supervisors
      _ <- sharing.maturing.background
      outbound <- Resource.eval {
        given Handle[IO, NodeError] = node
        for
          pool <- BlockPool.create[IO]
          _ <- supervisor.supervise(pool.fetchCoordinator.run)
          transport <- InMemoryPeers.create(magic)
          _ <- transport.serve(peer, handlers)
          manager <- ConnectionManager.create[IO](
            ConnectionManager.Limits(
              timeWait = Duration.Zero,
              outboundIdle = Duration.Zero
            )
          )
          metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
          hot = OutboundPeers.Hot[IO](
            magic,
            NodeToNode.Settings(offerPeerSharing = true),
            Peer
              .Settings(SocketAddress(host"127.0.0.1", port"3001"), magic, byronEpochSlots = 21600),
            (_, _) => IO.unit,
            ChainSyncHeights.origin,
            None,
            None,
            None,
            pool,
            _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
            _ => IO.unit,
            _ => IO.unit,
            serve = Some(handlers),
            inboundGovernor = Option.when(responder)(sharing.tracked(_, DataFlow.Duplex, _))
          )
          outbound <- OutboundPeers.create(
            transport,
            manager,
            OutboundPeers.Config(),
            hot,
            supervisor,
            monitors,
            _ => IO.unit,
            RefStateful(metrics): Stateful[IO, PeerMetrics[PeerNetwork.Addr]]
          )
        yield outbound
      }
    yield outbound

  /** What `inbound` reads at each of `times` while a connection dialled in `mode` lives, and once
    * it is closed at the last of them.
    */
  private def readings(
      mode: DiffusionMode,
      responder: Boolean,
      times: List[FiniteDuration]
  ): IO[(List[Map[PeerNetwork.Addr, Boolean]], Map[PeerNetwork.Addr, Boolean])] =
    val closing = times.lastOption.getOrElse(Duration.Zero)
    for
      gates <- (times :+ closing :+ closing).traverse(_ => Deferred[IO, Unit])
      control <- TestControl.execute(
        PeerSharingRuntime.create[IO](3L).flatMap { sharing =>
          outbound(sharing, responder).use { peers =>
            for
              established <- peers.establish(peer, mode, Provenance.Outbound)
              _ <- IO(assert(established.isRight, s"not established: $established"))
              seen <- gates.take(times.size).traverse(_.get *> sharing.inbound)
              _ <- gates(times.size).get *> peers.close(peer)
              after <- gates(times.size + 1).get *> sharing.inbound
            yield (seen, after)
          }
        }
      )
      _ <- control.tick
      _ <- (times :+ closing :+ closing)
        .zip(Duration.Zero :: (times :+ closing))
        .zip(gates)
        .traverse_ { case ((at, before), gate) =>
          control.tickFor(at - before) *> gate.complete(()) *> control.tick
        }
      outcome <- control.results
    yield outcome match
      case Some(Outcome.Succeeded(result)) => result
      case other                           => fail(s"the program did not end: $other")

  test("a dialled duplex connection's peer matures at the governor's first step 900 s after it") {
    readings(
      DiffusionMode.InitiatorAndResponder,
      responder = true,
      List(1.second, due - 1.nanosecond, due)
    )
      .map { case (seen, after) =>
        assertEquals(seen.take(2), List(none, none), "not offered before the step at 911.061883 s")
        assertEquals(seen.drop(2).map(_.keySet), List(Set(peer)), "offered from that step on")
        assertEquals(after, none, "gone once the connection is closed")
      }
  }

  test("a dialled unidirectional connection's peer is never a duplex peer") {
    readings(DiffusionMode.InitiatorOnly, responder = true, List(1.second, 2000.seconds))
      .map { case (seen, after) =>
        assertEquals(seen, List(none, none), "an initiator-only connection is not announced")
        assertEquals(after, none)
      }
  }

  test("without an inbound governor a dialled duplex connection is not registered") {
    readings(DiffusionMode.InitiatorAndResponder, responder = false, List(1.second, 2000.seconds))
      .map { case (seen, after) =>
        assertEquals(seen, List(none, none), "not in responder mode: nothing is announced")
        assertEquals(after, none)
      }
  }
