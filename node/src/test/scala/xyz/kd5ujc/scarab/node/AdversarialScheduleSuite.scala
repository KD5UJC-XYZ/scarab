// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.Monad
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, FollowerEvent, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, ChainError, NodeError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.{
  DiffusionMode,
  FetchMode,
  RootConfig,
  RootGroupSpec,
  Targets,
  UseLedgerPeers
}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.relay.ChainServer
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Fixed-seed, virtual-time schedules against independent production follower/network runtimes. The
  * nodes exchange signed Praos blocks through ChainServer and NodeToNodeServer on in-memory
  * bearers; adversarial behavior is a bounded wrapper around one real ChainServer response path.
  */
class AdversarialScheduleSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val magic = 780L
  private val tcp: SocketAddress[Host] = SocketAddress(host"127.0.0.1", port"3001")
  private val seeds = (1L to 16L).toVector
  private lazy val branches: (ForgedChain, ForgedChain) =
    ForgedChain
      .competingForks(5000 until 5064, 5064 until 5128, leftSeed = 1, rightSeed = 2)
      .unsafeRunSync()(using munitIORuntime)

  private final case class Runtime(
      address: PeerNetwork.Addr,
      chain: Ref[IO, VolatileChain[MirrorState]],
      history: Ref[IO, Vector[ChainSync.Point]],
      clockReads: Ref[IO, Vector[Instant]],
      fatals: Ref[IO, Vector[Fatal]],
      pool: BlockPool[IO],
      network: PeerNetwork.Running[IO, PeerNetwork.Addr],
      handlers: ServerHandlers[IO, NtNHeader]
  )

  private final case class Observation(
      checkpoint: String,
      tips: Vector[ChainSync.Point],
      histories: Vector[Vector[ChainSync.Point]]
  )

  private def address(n: Int): PeerNetwork.Addr = InMemoryPeers.address(100 + n)

  private def initialChain(genesis: MirrorState, source: Option[ForgedChain]) =
    source.fold(VolatileChain.genesis(ForgedChain.geometry.securityParam, genesis)) { forged =>
      forged.headers
        .zip(forged.states)
        .foldLeft(
          VolatileChain.genesis(ForgedChain.geometry.securityParam, genesis)
        ) { case (chain, (header, state)) =>
          chain.extend(header, state).fold(e => throw new AssertionError(e.message), _._1)
        }
    }

  private def record(history: Ref[IO, Vector[ChainSync.Point]], event: FollowerEvent): IO[Unit] =
    event match
      case FollowerEvent.RolledBack(_, to, _) =>
        history.update { points =>
          if to == ChainSync.Point.Origin then Vector.empty
          else points.takeWhile(_ != to) :+ to
        }
      case FollowerEvent.Extended(block, _, _, _) => history.update(_ :+ block.point)
      case _                                      => IO.unit

  private def gated(
      handlers: ServerHandlers[IO, NtNHeader],
      released: Deferred[IO, Unit]
  ): ServerHandlers[IO, NtNHeader] =
    new ServerHandlers[IO, NtNHeader](
      handlers.codec,
      handlers.chainSync.map { inner =>
        new ChainSyncServer.Source[IO, NtNHeader]:
          def intersect(points: Vector[ChainSync.Point]) = inner.intersect(points)
          def poll = released.get *> inner.poll
          def await = released.get *> inner.await
      },
      handlers.blocks,
      handlers.transactions,
      handlers.peerSharing
    )

  private def runtime(
      peers: InMemoryPeers,
      genesis: MirrorState,
      address: PeerNetwork.Addr,
      others: List[PeerNetwork.Addr],
      source: Option[ForgedChain],
      gate: Option[Deferred[IO, Unit]],
      clockOffset: FiniteDuration,
      seed: Long
  )(using Handle[IO, NodeError]): Resource[IO, Runtime] =
    val start = initialChain(genesis, source)
    for
      chain <- Resource.eval(Ref.of[IO, VolatileChain[MirrorState]](start))
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chain)
      history <- Resource.eval(
        Ref.of[IO, Vector[ChainSync.Point]](
          source.fold(Vector.empty[ChainSync.Point])(_.headers.map(_.point))
        )
      )
      clockReads <- Resource.eval(Ref.of[IO, Vector[Instant]](Vector.empty))
      fatals <- Resource.eval(Ref.of[IO, Vector[Fatal]](Vector.empty))
      server <- Resource.eval(
        ChainServer
          .open[IO](
            None,
            None,
            ChainServer.headerOfBlock(ForgedChain.geometry.byron.epochSlots),
            fatalSink(fatals)
          )
      )
      _ <- Resource.eval(
        Handle
          .allow[ChainError] {
            source.toList.flatMap(_.raws).traverse_ { raw =>
              val header = Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley].header
              server
                .persistence[MirrorState]
                .extended(
                  BlockRef(header.slot, header.hash, header.blockNo),
                  raw,
                  Vector.empty,
                  Anchor(None, genesis)
                )
            }
          }
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      )
      events = new FollowerEvents[IO]:
        def emit(event: FollowerEvent) = record(history, event)
      follower <- Resource.eval(
        Follower.create[IO, MirrorState](
          ForgedChain.rules,
          events,
          ForgedChain.geometry,
          persistence = Some(server.persistence[MirrorState]),
          initialViews = source.fold(Map.empty[Bytes, xyz.kd5ujc.scarab.consensus.SelectView])(
            _.headers.map(h => h.hash -> xyz.kd5ujc.scarab.consensus.SelectView.of(h)).toMap
          )
        )
      )
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes]) = IO.unit
      handlers = ServerHandlers[IO, NtNHeader](NtNHeader.codec, server.source, server.blocks, sink)
      advertised = gate.fold(handlers)(gated(handlers, _))
      _ <- Resource.eval(peers.serve(address, advertised))
      _ <- Resource.make(IO.unit)(_ => peers.stop(address))
      pool <- Resource.eval(BlockPool.create[IO])
      manager <- Resource.eval(ConnectionManager.create[IO]())
      mode <- Resource.eval(Ref.of[IO, FetchMode](FetchMode.BulkSync))
      root = RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
      roots = PeerNetwork.Roots(
        List(RootGroupSpec(others.size, others.size, others.map(_ -> root))),
        Set.empty,
        None,
        UseLedgerPeers.Disabled
      )
      clock = new SlotClock[IO]:
        def now =
          IO.realTime
            .map(t => Instant.EPOCH.plusNanos((t + clockOffset).toNanos))
            .flatTap(now => clockReads.update(_ :+ now))
        def geometry = IO.pure(ForgedChain.geometry)
      headerCheck = HeaderClock.clocked[IO](clock, HeaderCheck.none[IO])
      hot = OutboundPeers.Hot[IO](
        magic = magic,
        network = NodeToNode.Settings(),
        peer = Peer.Settings(
          tcp,
          magic,
          readAhead = 8,
          byronEpochSlots = ForgedChain.geometry.byron.epochSlots
        ),
        follow = (peer, track) => follower.session(peer, track),
        prepare = _ => IO.unit,
        heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin,
        headers = Some(headerCheck),
        transactions = None,
        observe = None,
        pool = pool,
        versus = follower.versus,
        lost = _ => IO.unit,
        fatal = fatalSink(fatals)
      )
      view = new PeerNetwork.ChainView[IO]:
        def tip = chain.get.map(_.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
        def geometry = IO.pure(ForgedChain.geometry)
      network <- PeerNetwork.resource[IO](
        PeerNetwork.Settings(roots, Targets.relay, seed = seed),
        peers,
        manager,
        OutboundPeers.Config(),
        hot,
        view,
        mode,
        _ => IO.unit,
        (_, _) => IO.unit,
        _ => IO.unit,
        _ => IO.unit,
        GsmRunner.Marker.none[IO],
        LedgerPeersSource.none[IO]
      )
    yield Runtime(address, chain, history, clockReads, fatals, pool, network, advertised)

  private def running[A](nodes: Vector[Runtime])(body: IO[A]): IO[A] =
    nodes.traverse(_.network.run.background).use(_ => body)

  private def converged(nodes: Vector[Runtime]): IO[Boolean] =
    nodes.traverse(_.chain.get.map(_.tip)).map(tips => tips.distinct.size == 1)

  private def eventuallyConverged(nodes: Vector[Runtime], left: Int): IO[Boolean] =
    Monad[IO].tailRecM(left) { remaining =>
      converged(nodes).flatMap {
        case true                    => IO.pure(Right(true))
        case false if remaining <= 0 => IO.pure(Right(false))
        case false                   => IO.sleep(1.second).as(Left(remaining - 1))
      }
    }

  private def observe(checkpoint: String, nodes: Vector[Runtime]): IO[Observation] =
    for
      tips <- nodes.traverse(_.chain.get.map(_.tip))
      histories <- nodes.traverse(_.history.get)
    yield Observation(checkpoint, tips, histories)

  private def commonPrefix(observation: Observation): Vector[ChainSync.Point] =
    observation.histories.reduce { (left, right) =>
      left.zip(right).takeWhile(_ == _).map(_._1)
    }

  private def boundedByK(observation: Observation, k: Int): Boolean =
    val common = commonPrefix(observation).size
    val minimum = observation.histories.map(_.size).min
    common >= (minimum - k).max(0)

  private def noFatalOutcome(fatals: Vector[Fatal]): Option[Vector[Fatal]] =
    Option.when(fatals.nonEmpty)(fatals)

  private def fatalSink(events: Ref[IO, Vector[Fatal]]): Fatal => IO[Unit] =
    fatal => events.update(_ :+ fatal)

  private def noPendingFixtureClaims(
      nodeIndex: Int,
      pool: BlockPool[IO],
      hashes: Vector[Bytes]
  ): IO[Unit] =
    for
      originals <- hashes.traverse { hash =>
        pool
          .take(hash)
          .timeoutTo(
            1.second,
            IO.raiseError(
              new AssertionError(s"node $nodeIndex retained pending fixture hash $hash")
            )
          )
      }
      // Received originals survive their delivering session, as in VolatileDB. They remain
      // readable and suppress duplicate fetches; only missing bodies become claimable again.
      retained <- hashes.traverse(pool.takeFilled)
      _ <- IO(assertEquals(retained.map(_.map(_.block)), originals))
      claimable <- pool.session.use(_.claim(hashes))
    yield assertEquals(
      claimable,
      retained.map(_.isEmpty),
      s"node $nodeIndex fixture hashes remained claimed after peer-network resources closed"
    )

  private def base64Seed(seed: Long): String =
    Base64.getEncoder.encodeToString(seed.toString.getBytes(StandardCharsets.UTF_8))

  private def runSeed(seed: Long): IO[Unit] =
    val (left, right) = branches
    val nodeAddresses = Vector(address(0), address(1), address(2))
    val trace =
      Vector(s"seed=$seed", "start: node 1 ChainSync withheld", "clock offsets: 0ms,-1000ms,-500ms")
    val delay = ((seed % 4) + 1).seconds
    val clockBase = (left.headers.last.slot.max(right.headers.last.slot) - 1).toLong.seconds
    node {
      for
        peers <- InMemoryPeers.create(magic)
        hold <- Deferred[IO, Unit]
        runtimes <-
          Vector(
            runtime(
              peers,
              left.genesis,
              nodeAddresses(0),
              nodeAddresses.filterNot(_ == nodeAddresses(0)).toList,
              Some(left),
              None,
              clockBase,
              seed
            ),
            runtime(
              peers,
              left.genesis,
              nodeAddresses(1),
              nodeAddresses.filterNot(_ == nodeAddresses(1)).toList,
              Some(right),
              Some(hold),
              clockBase - 1.second,
              seed + 1
            ),
            runtime(
              peers,
              left.genesis,
              nodeAddresses(2),
              nodeAddresses.filterNot(_ == nodeAddresses(2)).toList,
              None,
              None,
              clockBase - 500.millis,
              seed + 2
            )
          ).sequence.use { nodes =>
            running(nodes) {
              for
                initial <- observe("initial", nodes)
                _ <- IO.sleep(delay)
                whileWithheld <- observe("withheld", nodes)
                _ <- hold.complete(()).void
                firstConvergence <- eventuallyConverged(nodes, 90)
                afterRelease <- observe("after-release", nodes)
                samples <- nodes.traverse(_.clockReads.get)
                openBefore <- peers.connections
                _ <- peers.kill(nodeAddresses(1))
                _ <- IO.sleep(1.second)
                _ <- peers.serve(nodeAddresses(1), nodes(1).handlers)
                _ <- IO.sleep(20.seconds)
                afterRecovery <- observe("after-recovery", nodes)
                openAfter <- peers.connections
              yield
                val selectedK = ForgedChain.geometry.securityParam
                val observations = Vector(initial, whileWithheld, afterRelease, afterRecovery)
                val traceText =
                  s"$trace, wait=$delay, release, converge=$firstConvergence, close/reopen node1; " +
                    observations
                      .map(o =>
                        s"${o.checkpoint}:tips=${o.tips},lengths=${o.histories.map(_.size)}"
                      )
                      .mkString(" | ")
                observations.foreach { observation =>
                  assert(
                    boundedByK(observation, selectedK),
                    s"seed $seed checkpoint ${observation.checkpoint} violated common-prefix k=$selectedK; $traceText"
                  )
                }
                assert(firstConvergence, s"seed $seed failed to converge after release: $traceText")
                assertEquals(
                  afterRelease.tips.distinct.size,
                  1,
                  s"seed $seed tips diverged after release; $traceText"
                )
                assertEquals(
                  afterRecovery.tips.distinct.size,
                  1,
                  s"seed $seed tips diverged after endpoint recovery; $traceText"
                )
                assert(
                  afterRelease.histories(2).size > initial.histories(2).size,
                  s"seed $seed responsive sources did not grow node2 after release; $traceText"
                )
                assert(
                  samples.count(_.nonEmpty) >= 2,
                  s"seed $seed did not exercise at least two node SlotClocks: ${samples.map(_.size)}; $traceText"
                )
                assert(
                  openAfter.getOrElse(nodeAddresses(1), 0) > openBefore
                    .getOrElse(nodeAddresses(1), 0),
                  s"seed $seed did not reconnect to the restored peer: before=$openBefore after=$openAfter; $traceText"
                )
            }.as(nodes)
          }
        pools = runtimes.map(_.pool)
        fixtureHashes = (left.headers ++ right.headers).map(_.hash).distinct
        _ <- pools.zipWithIndex.traverse_ { case (pool, index) =>
          noPendingFixtureClaims(index, pool, fixtureHashes)
        }
        poolSizes <- pools.traverse(_.size)
        fatals <- runtimes.traverse(_.fatals.get)
        opened <- peers.connections
        closed <- peers.closedConnections
      yield
        assert(
          poolSizes.forall(_ <= BlockPool.Retained),
          s"seed $seed exceeded the documented bounded completed-block retention: $poolSizes"
        )
        val addresses = nodeAddresses.map(a => a -> opened.getOrElse(a, 0)).toMap
        val closedByAddress = nodeAddresses.map(a => a -> closed.getOrElse(a, 0)).toMap
        assertEquals(closedByAddress, addresses, s"seed $seed left bearer ends unclosed")
        fatals.zipWithIndex.foreach { case (events, index) =>
          assert(
            noFatalOutcome(events).isEmpty,
            s"seed $seed node $index after teardown reported terminal outcomes: ${events.mkString(", ")}"
          )
        }
    }

  private def node[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](body).rescue(e => IO.raiseError(new RuntimeException(e.message)))

  test(
    "sixteen named schedules converge three real nodes across a bounded fork and peer recovery"
  ) {
    seeds.traverse_ { seed =>
      TestControl.executeEmbed(runSeed(seed), seed = Some(base64Seed(seed)))
    }
  }

  test("the fatal observer makes an injected terminal outcome fail the schedule invariant") {
    val injected = Fatal.Defect(new RuntimeException("negative-control"))
    for
      observed <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      testSink = fatalSink(observed)
      _ <- testSink(injected)
      values <- observed.get
    yield assertEquals(
      noFatalOutcome(values),
      Some(Vector(injected))
    )
  }
