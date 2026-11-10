// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.governor

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.IO
import fs2.concurrent.SignallingRef
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, FollowerEvent, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, NodeError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.relay.ChainServer
import xyz.kd5ujc.scarab.protocol.NtNHeader

/** The governor, the real connection manager, the interpreter of the connections, the follower and
  * the block pool together, against forty peers on in-memory bearers: more than the established
  * target (30) and the active target (20). The node must converge to the targets, reach the tip of
  * the chain the peers serve, and repair the hot set when hot peers die.
  */
class FortyBearersSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val magic = 42L

  private lazy val forged: ForgedChain =
    ForgedChain.forge(5000 until 5060).unsafeRunSync()(using munitIORuntime)

  private def serving(raws: Vector[Bytes]): IO[ServerHandlers[IO, NtNHeader]] =
    for
      server <- ChainServer.open[IO](
        None,
        None,
        ChainServer.headerOfBlock(ForgedChain.geometry.byron.epochSlots),
        failure => IO.raiseError(new AssertionError(failure.message))
      )
      _ <- Handle
        .allow[xyz.kd5ujc.scarab.ledger.NodeError] {
          raws.traverse_ { raw =>
            val header = Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley].header
            server
              .persistence[MirrorState]
              .extended(
                BlockRef(header.slot, header.hash, header.blockNo),
                raw,
                Vector.empty,
                Anchor(None, forged.genesis)
              )
          }
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      sink = new TxSubmissionServer.Sink[IO]:
        def reader = RelayedTransactions
        def known(id: Bytes) = IO.pure(false)
        def submit(
            originals: Vector[Bytes]
        )(using cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]) = IO.unit
    yield ServerHandlers[IO, NtNHeader](NtNHeader.codec, server.source, server.blocks, sink)

  private def observed[A](changed: SignallingRef[IO, BigInt])(read: IO[A])(
      ok: A => Boolean
  ): IO[A] =
    changed.discrete.evalMap(_ => read).find(ok).compile.lastOrError.timeout(60.seconds)

  test("forty peers: the node converges to the targets, reaches the tip and repairs the hot set") {
    Settled.timed { _ =>
      for
        now <- IO.realTimeInstant
        clock = ForgedChain.geometry.copy(systemStart =
          now.minusMillis(100000L * ForgedChain.geometry.slotLengthMillis)
        )
        changed <- SignallingRef[IO, BigInt](BigInt(0))
        chainRef <- SignallingRef[IO, VolatileChain[MirrorState]](
          VolatileChain.genesis(ForgedChain.geometry.k.toInt, forged.genesis)
        )
        given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
        given Handle[IO, NodeError] <- NodeScope.forTest
        follower <- Handle
          .allow[NodeError] {
            Follower.create[IO, MirrorState](
              ForgedChain.rules,
              (_: FollowerEvent) => IO.unit,
              ForgedChain.geometry
            )
          }
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
        pool <- WorkerTesting.pool(follower)
        handlers <- serving(forged.raws)
        peers <- InMemoryPeers.create(magic)
        all = (1 to 40).map(InMemoryPeers.address).toList
        _ <- all.traverse_(peers.serve(_, handlers))
        manager <- ConnectionManager.create[IO](ConnectionManager.Limits(maxOutbound = 64))
        hot = OutboundPeers.Hot[IO](
          magic = magic,
          network = NodeToNode.Settings(),
          peer = Peer.Settings(
            SocketAddress(host"127.0.0.1", port"3001"),
            magic,
            readAhead = 8,
            byronEpochSlots = ForgedChain.geometry.byron.epochSlots
          ),
          follow = (peer, track) => follower.session(peer, track),
          heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin,
          headers = None,
          transactions = None,
          observe = None,
          pool = pool,
          versus = follower.versus,
          lost = _ => IO.unit,
          fatal = _ => IO.unit
        )
        view = new PeerNetwork.ChainView[IO]:
          def tip = chainRef.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
          def geometry = IO.pure(clock)
        roots = PeerNetwork.Roots(Nil, all.toSet, None, UseLedgerPeers.Disabled)
        modeRef <- SignallingRef[IO, FetchMode](FetchMode.BulkSync)
        network = PeerNetwork.resource[IO](
          PeerNetwork.Settings(roots, Targets.relay, seed = 11L),
          peers,
          manager,
          OutboundPeers.Config(),
          hot,
          view,
          modeRef,
          _ => changed.update(_ + 1),
          (_, _) => IO.unit,
          _ => IO.unit,
          _ => IO.unit,
          GsmRunner.Marker.none[IO],
          LedgerPeersSource.none[IO]
        )
        result <- network
          .flatTap(_ => FetchTesting.decisions(pool) *> WorkerTesting.selection(follower, pool))
          .use { net =>
            net.run.background.use { _ =>
              def counters = net.state.get.map(st => st.view(Duration.Zero).counters)
              for
                // the targets: 30 established, 20 of them hot, out of the 40 the node knows
                converged <- observed(changed)(
                  net.state.get.flatMap(st => IO.monotonic.map(st.view(_).counters))
                )(c => c.known == 40 && c.established == 30 && c.hot == 20)
                _ <- chainRef.waitUntil(_.tip == forged.headers.last.point).timeout(60.seconds)
                // eight hot peers die (32 are left, more than the established target): the node replaces them and the dead ones are not hot
                hotPeers <- net.state.get.map(
                  _.peers.collect { case (p, r) if r.status == Status.Hot => p }.toList
                )
                dead = hotPeers.take(8)
                _ <- dead.traverse_(peers.kill)
                repaired <- observed(changed)(
                  net.state.get.flatMap(st => IO.monotonic.map(t => st -> st.view(t).counters))
                ) { (st, c) =>
                  c.hot == 20 && c.established == 30 &&
                  dead.forall(p => st.peers.get(p).forall(!_.status.established))
                }
                fatal <- net.fatal.tryGet
                mode <- modeRef.get
                _ <- IO(assertEquals(mode, FetchMode.BulkSync))
                _ <- counters
              yield (converged, repaired._2, fatal)
            }
          }
      yield
        val (converged, repaired, fatal) = result
        assertEquals((converged.known, converged.established, converged.hot), (40, 30, 20))
        assertEquals((repaired.established, repaired.hot), (30, 20))
        assertEquals(fatal, None)
    }
  }

  /** Six peers that serve the same chain, a node that follows all of them, and the fetch mode
    * forced by the simulated clock: in `Deadline` the chain's tip is 40 slots behind the clock, in
    * `BulkSync` it is far behind. The node must reach the tip, with the chain of the peers, and the
    * peers must have reported what they were first with.
    */
  private def reachesTip(deadline: Boolean, concurrency: Int): IO[FetchMode] =
    Settled.timed { _ =>
      for
        now <- IO.realTimeInstant
        clock = ForgedChain.geometry.copy(systemStart =
          now.minusMillis(
            (if deadline then 5100L else 100000L) * ForgedChain.geometry.slotLengthMillis
          )
        )
        chainRef <- SignallingRef[IO, VolatileChain[MirrorState]](
          VolatileChain.genesis(ForgedChain.geometry.k.toInt, forged.genesis)
        )
        given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
        given Handle[IO, NodeError] <- NodeScope.forTest
        follower <- Handle
          .allow[NodeError] {
            Follower.create[IO, MirrorState](
              ForgedChain.rules,
              (_: FollowerEvent) => IO.unit,
              ForgedChain.geometry
            )
          }
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
        pool <- BlockPool.create[IO](BlockPool.FetchPolicy(concurrency), follower.fetchSelection)
        modeRef <- SignallingRef[IO, FetchMode](FetchMode.BulkSync)
        handlers <- serving(forged.raws)
        peers <- InMemoryPeers.create(magic)
        all = (1 to 6).map(InMemoryPeers.address).toList
        _ <- all.traverse_(peers.serve(_, handlers))
        manager <- ConnectionManager.create[IO](ConnectionManager.Limits(maxOutbound = 64))
        hot = OutboundPeers.Hot[IO](
          magic = magic,
          network = NodeToNode.Settings(),
          peer = Peer.Settings(
            SocketAddress(host"127.0.0.1", port"3001"),
            magic,
            readAhead = 8,
            byronEpochSlots = ForgedChain.geometry.byron.epochSlots
          ),
          follow = (peer, track) => follower.session(peer, track),
          heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin,
          headers = None,
          transactions = None,
          observe = None,
          pool = pool,
          versus = follower.versus,
          lost = _ => IO.unit,
          fatal = _ => IO.unit
        )
        view = new PeerNetwork.ChainView[IO]:
          def tip = chainRef.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
          def geometry = IO.pure(clock)
        network = PeerNetwork.resource[IO](
          PeerNetwork.Settings(
            PeerNetwork.Roots(Nil, all.toSet, None, UseLedgerPeers.Disabled),
            Targets.relay,
            seed = 3L
          ),
          peers,
          manager,
          OutboundPeers.Config(),
          hot,
          view,
          modeRef,
          _ => IO.unit,
          (_, _) => IO.unit,
          _ => IO.unit,
          _ => IO.unit,
          GsmRunner.Marker.none[IO],
          LedgerPeersSource.none[IO]
        )
        result <- network
          .flatTap(_ => FetchTesting.decisions(pool) *> WorkerTesting.selection(follower, pool))
          .use { net =>
            net.run.background.use { _ =>
              for
                _ <- chainRef.waitUntil(_.tip == forged.headers.last.point).timeout(60.seconds)
                // the mode is published once a second
                mode <- modeRef.discrete
                  .find(m => (m == FetchMode.Deadline) == deadline)
                  .compile
                  .lastOrError
                  .timeout(60.seconds)
                boards <- net.metrics.get.map(_.sizes)
                scores <- net.metrics.get.map(_.hotScores)
              yield (mode, boards, scores)
            }
          }
      yield
        val (mode, boards, scores) = result
        // both boards hold slots, and some peer was first with something
        assert(boards._1 > 0 && boards._2 > 0, s"boards $boards")
        assert(scores.values.exists(_.value > 0), scores.toString)
        mode
    }

  test("six peers: the node reaches the tip in bulk sync") {
    reachesTip(deadline = false, concurrency = 1).map(assertEquals(_, FetchMode.BulkSync))
  }

  test("six peers: the node reaches the same tip in deadline mode, one peer per block") {
    reachesTip(deadline = true, concurrency = 1).map(assertEquals(_, FetchMode.Deadline))
  }

  test("six peers: the node reaches the same tip in deadline mode with two peers per block") {
    reachesTip(deadline = true, concurrency = 2).map(assertEquals(_, FetchMode.Deadline))
  }
