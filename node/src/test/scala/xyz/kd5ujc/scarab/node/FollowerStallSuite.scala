// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, FollowerEvent, FollowerEvents}
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  BlockTransportError,
  ChainError,
  NodeError,
  PeerError,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.storage.ChainPersistence
import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ExUnits}
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, Redeemer, ScriptLocation}
import xyz.kd5ujc.scarab.protocol.NtNHeader
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.{
  DiffusionMode,
  FetchMode,
  RootConfig,
  RootGroupSpec,
  Targets,
  UseLedgerPeers
}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.metrics.{Catalogue, EventMetrics, Family, Metrics}
import xyz.kd5ujc.scarab.node.metrics.EventSink.alongside
import xyz.kd5ujc.scarab.node.relay.ChainServer
import scala.concurrent.duration.*

/** Two upstream peers on in-memory connections with the production wiring (the governor, one shared
  * [[BlockPool]], in-memory connections): one peer stalls (its ChainSync reply never comes, its
  * BlockFetch hangs), the other keeps serving. The follower must reach the tip, and no fiber may
  * end with an error nobody handles (issue 130: a `Handle$Submarine` that escaped, a claim that
  * stayed open and blocked the other session).
  */
class FollowerStallSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 90.seconds

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
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes]) = IO.unit
    yield ServerHandlers[IO, NtNHeader](NtNHeader.codec, server.source, server.blocks, sink)

  /** The same peer, stalling on every connection: it announces `headers` headers and then does not
    * answer a request for the next one (neither an instruction nor AwaitReply, so the client's wait
    * in NextCanAwait runs out), and it answers the first block range and none after it.
    */
  private def stalling(
      honest: ServerHandlers[IO, NtNHeader],
      headers: Int
  ): (ServerHandlers[IO, NtNHeader], Ref[IO, Int]) =
    val answered = Ref.unsafe[IO, Int](0)
    val handlers = new ServerHandlers[IO, NtNHeader](
      honest.codec,
      honest.chainSync.evalMap { inner =>
        Ref.of[IO, Int](0).map { served =>
          new ChainSyncServer.Source[IO, NtNHeader]:
            def intersect(points: Vector[Point]) = inner.intersect(points)
            def poll = served.getAndUpdate(_ + 1).flatMap { n =>
              if n < headers then inner.poll else IO.never
            }
            def await = inner.await
        }
      },
      new BlockFetchServer.Blocks[IO]:
        // counted across connections: only the very first range is answered
        def range(from: Point, to: Point) =
          answered.getAndUpdate(_ + 1).flatMap { n =>
            if n == 0 then honest.blocks.range(from, to) else IO.never
          }
      ,
      honest.transactions
    )
    (handlers, answered)

  /** Hold the healthy peer's intersection until an unanswered fetch and the ChainSync timeout have
    * occurred. Merely seeing the first answered range lets the healthy peer win before the scenario
    * exercises the intended failure.
    */
  private def after(
      asked: Ref[IO, Int],
      failed: Deferred[IO, Unit],
      honest: ServerHandlers[IO, NtNHeader]
  ): ServerHandlers[IO, NtNHeader] =
    def stalled: IO[Unit] =
      asked.get.flatMap(n => if n >= 2 then IO.unit else IO.sleep(10.millis) *> stalled)
    intersectAfter(stalled *> failed.get, honest)

  private val tcp: SocketAddress[Host] = SocketAddress(host"127.0.0.1", port"3001")

  private def eventually[A](what: String)(read: IO[A])(ok: A => Boolean): IO[A] =
    def loop(left: Int): IO[A] =
      read.flatMap { a =>
        if ok(a) then IO.pure(a)
        else if left == 0 then IO.raiseError(new RuntimeException(s"timed out: $what ($a)"))
        else IO.sleep(100.millis) *> loop(left - 1)
      }
    loop(400)

  /** The node's own scope, alive for the whole of a test: what the follower's rules and stores
    * raise through it is caught in the session that met it.
    */
  private def node[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](body).rescue(e => IO.raiseError(new RuntimeException(e.message)))

  /** A peer that answers the first block range with bytes that are not a CBOR item, and honestly
    * after that. The deferred value records a malformed block actually streamed.
    */
  private def garbling(
      honest: ServerHandlers[IO, NtNHeader]
  ): IO[(ServerHandlers[IO, NtNHeader], Deferred[IO, Bytes])] =
    (
      Ref.of[IO, Int](0),
      Deferred[IO, Bytes]
    ).mapN { (answered, malformed) =>
      val handlers = new ServerHandlers[IO, NtNHeader](
        honest.codec,
        honest.chainSync,
        new BlockFetchServer.Blocks[IO]:
          def range(from: Point, to: Point) =
            answered.getAndUpdate(_ + 1).flatMap { n =>
              honest.blocks.range(from, to).map { found =>
                if n == 0 then
                  found.map(_.map(_ => Bytes(Vector(0xff.toByte))).evalTap { raw =>
                    malformed.complete(raw).void
                  })
                else found
              }
            }
        ,
        honest.transactions
      )
      (handlers, malformed)
    }

  /** Hold back intersection before this peer can announce headers and claim shared blocks. */
  private def intersectAfter(
      ready: IO[Unit],
      honest: ServerHandlers[IO, NtNHeader]
  ): ServerHandlers[IO, NtNHeader] =
    new ServerHandlers[IO, NtNHeader](
      honest.codec,
      honest.chainSync.map { inner =>
        new ChainSyncServer.Source[IO, NtNHeader]:
          def intersect(points: Vector[Point]) = ready *> inner.intersect(points)
          def poll = inner.poll
          def await = inner.await
      },
      honest.blocks,
      honest.transactions
    )

  test("one of two peers stalls in NextCanAwait mid-fetch: the other keeps the follower going") {
    IO(forged).flatMap { _ =>
      TestControl.executeEmbed(
        node {
          for
            chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
              VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
            )
            given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
            seen <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
            events = new FollowerEvents[IO]:
              def emit(event: FollowerEvent) = seen.update(_ :+ event)
            follower <- Follower.create[IO, MirrorState](
              ForgedChain.rules,
              events,
              ForgedChain.geometry
            )
            fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
            pool <- BlockPool.create[IO]
            stalledFailure <- Deferred[IO, Unit]
            stall <- serving(forged.raws).map(stalling(_, headers = 16))
            (stalled, ranges) = stall
            healthy <- serving(forged.raws).map(after(ranges, stalledFailure, _))
            peers <- InMemoryPeers.create(magic)
            first = InMemoryPeers.address(1)
            second = InMemoryPeers.address(2)
            _ <- peers.serve(first, stalled) *> peers.serve(second, healthy)
            manager <- ConnectionManager.create[IO]()
            last = forged.headers.last
            hot = OutboundPeers.Hot[IO](
              magic = magic,
              network = NodeToNode.Settings(chainSync =
                ChainSyncClient.Settings(canAwaitTimeout = 1.second)
              ),
              peer = Peer.Settings(
                tcp,
                magic,
                readAhead = 8,
                byronEpochSlots = ForgedChain.geometry.byron.epochSlots
              ),
              follow = (peer, track) => follower.session(peer, track),
              prepare = _ => IO.unit,
              heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin,
              headers = None,
              transactions = None,
              observe = None,
              pool = pool,
              versus = follower.versus,
              lost = error =>
                events.emit(FollowerEvent.Reconnecting(error, 1)) *> (error match
                  case PeerError.Unavailable(
                        PeerError.Unavailability
                          .Network(NetworkError.Timeout(Protocols.ChainSync, _))
                      ) =>
                    ranges.get.flatMap(n => stalledFailure.complete(()).void.whenA(n >= 2))
                  case _ => IO.unit),
              fatal = f => fatals.update(_ :+ f)
            )
            view = new PeerNetwork.ChainView[IO]:
              def tip = chainRef.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
              def geometry = IO.pure(ForgedChain.geometry)
            local = RootConfig(
              false,
              DiffusionMode.InitiatorAndResponder,
              Provenance.Outbound,
              false
            )
            roots = PeerNetwork.Roots(
              List(RootGroupSpec(2, 2, List(first -> local, second -> local))),
              Set.empty,
              None,
              UseLedgerPeers.Disabled
            )
            modeRef <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
            network = PeerNetwork.resource[IO](
              PeerNetwork.Settings(roots, Targets.relay, seed = 7L),
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
            reconnects <- network.use { net =>
              // as in Main: the governor runs beside the node, and ends it only by a fault
              // Live cache occupancy is not pending-claim ownership. BlockPoolSuite checks
              // scoped claim release while allowing filled blocks to remain cached.
              net.run.background.use { _ =>
                eventually("our chain reaches the tip")(chainRef.get.map(_.tip))(_ == last.point) *>
                  // the stalling peer failed: a typed, logged loss of the connection
                  eventually("the stalling peer is lost")(seen.get)(_.exists {
                    case FollowerEvent.Reconnecting(_: PeerError.Unavailable, _) => true
                    case _                                                       => false
                  }) <*
                  net.fatal.tryGet.map(fault => assertEquals(fault, None))
              }
            }
            asked <- ranges.get
            stopped <- fatals.get
          yield
            assert(reconnects.nonEmpty)
            // the stalling peer was asked for a block range it never answered: its ChainSync stall came
            // while blocks of the batch were being fetched, not before any fetch
            assert(asked >= 2, s"the second, unanswered block range was not requested: $asked")
            val states = reconnects.collect {
              case FollowerEvent.Reconnecting(
                    PeerError.Unavailable(
                      PeerError.Unavailability.Network(
                        NetworkError.Timeout(Protocols.ChainSync, state)
                      )
                    ),
                    _
                  ) =>
                state
            }
            assert(states.contains("NextCanAwait"), s"unexpected ChainSync timeout states: $states")
            assertEquals(stopped, Vector.empty[Fatal])
        },
        seed = Some("MTM=")
      )
    }
  }

  test(
    "a malformed transported body loses its peer before ledger admission: the other peer continues"
  ) {
    TestControl.executeEmbed(
      node {
        for
          chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
            VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
          )
          given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
          seen <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
          rejected <- Deferred[IO, Unit]
          healthyReady <- Deferred[IO, Unit]
          events = new FollowerEvents[IO]:
            def emit(event: FollowerEvent) =
              seen.update(_ :+ event) *> (event match
                case FollowerEvent.Reconnecting(
                      PeerError.Protocol(
                        PeerError.ProtocolProblem
                          .FetchedBlockIntegrity(_: BlockTransportError.Decode)
                      ),
                      _
                    ) =>
                  rejected.complete(()).void
                case _ => IO.unit)
          follower <- Follower.create[IO, MirrorState](
            ForgedChain.rules,
            events,
            ForgedChain.geometry
          )
          metrics <- Metrics.create[IO](Catalogue.all)
          measured <- EventMetrics.create[IO](using summon, metrics)
          lost <- Reconnects.create[IO](events.alongside(measured.follower))
          fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
          pool <- BlockPool.create[IO]
          garbled <- serving(forged.raws).flatMap(garbling)
          (bad, malformed) = garbled
          good <- serving(forged.raws)
          peers <- InMemoryPeers.create(magic)
          first = InMemoryPeers.address(1)
          second = InMemoryPeers.address(2)
          // Force the healthy peer to attempt intersection first. Without the rejection gate it can
          // supply every shared block, leaving the malformed peer untested (CI regression #181).
          _ <- peers.serve(first, intersectAfter(healthyReady.get, bad)) *>
            peers.serve(
              second,
              intersectAfter(healthyReady.complete(()).void *> rejected.get, good)
            )
          manager <- ConnectionManager.create[IO]()
          last = forged.headers.last
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
            headers = None,
            transactions = None,
            observe = None,
            pool = pool,
            versus = follower.versus,
            lost = lost,
            fatal = f => fatals.update(_ :+ f)
          )
          view = new PeerNetwork.ChainView[IO]:
            def tip = chainRef.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
            def geometry = IO.pure(ForgedChain.geometry)
          local = RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
          roots = PeerNetwork.Roots(
            List(RootGroupSpec(2, 2, List(first -> local, second -> local))),
            Set.empty,
            None,
            UseLedgerPeers.Disabled
          )
          modeRef <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
          network = PeerNetwork.resource[IO](
            PeerNetwork.Settings(roots, Targets.relay, seed = 7L),
            peers,
            manager,
            OutboundPeers.Config(repromoteDelay = 1.second),
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
          _ <- network.use { net =>
            net.run.background.use { _ =>
              eventually("our chain reaches the tip")(chainRef.get.map(_.tip))(_ == last.point) *>
                // Native transport decoding precedes ChainDB admission: this exact typed protocol
                // failure forgets its sender without poisoning the valid announced header hash.
                eventually("the lying peer is lost")(seen.get)(_.exists {
                  case FollowerEvent.Reconnecting(
                        PeerError.Protocol(
                          PeerError.ProtocolProblem
                            .FetchedBlockIntegrity(_: BlockTransportError.Decode)
                        ),
                        _
                      ) =>
                    true
                  case _ => false
                }) *>
                net.fatal.tryGet.map(fault => assertEquals(fault, None))
            }
          }
          served <- malformed.tryGet
          stopped <- fatals.get
          invalid <- pool.invalidBlocks.snapshot
          logged <- seen.get.map(_.count(_.isInstanceOf[FollowerEvent.Reconnecting]))
          counted <- metrics.snapshot.map(
            _.families
              .get(Catalogue.peerReconnects.name)
              .collect { case f: Family.Counter => f.series.values.sum }
          )
        yield
          assertEquals(served, Some(Bytes(Vector(0xff.toByte))))
          assertEquals(stopped, Vector.empty[Fatal])
          assertEquals(invalid.entries, Map.empty)
          // every logged reconnect is counted (issue 351)
          assert(logged >= 1)
          assertEquals(counted, Some(logged.toDouble))
      },
      seed = Some("MTM=")
    )
  }

  private enum FatalCase:
    case Storage, Unavailable, UnavailablePhase1, Defect, NetworkDefect

  for injected <- FatalCase.values.toList
  do
    test(s"an injected $injected stops without reconnecting or blaming the peer") {
      val storageCause = new java.io.IOException("injected")
      node {
        for
          chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
            VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
          )
          given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
          seen <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
          events = new FollowerEvents[IO]:
            def emit(event: FollowerEvent) = seen.update(_ :+ event)
          stored <- Ref.of[IO, Int](0)
          failing = new ChainPersistence[IO, MirrorState]:
            def extended(
                ref: BlockRef,
                raw: Bytes,
                immutable: Vector[BlockRef],
                anchor: Anchor[MirrorState]
            )(using
                cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
                cats.mtl.Raise[IO, ChainError]
            ) = stored.getAndUpdate(_ + 1).flatMap { n =>
              if n != 4 then IO.unit
              else
                injected match
                  case FatalCase.Storage =>
                    summon[Handle[IO, NodeError]].raise[NodeError, Unit](
                      StorageError.Io("volatile", storageCause)
                    )
                  case FatalCase.Unavailable | FatalCase.UnavailablePhase1 =>
                    val failure = LedgerFailure.Unavailable(
                      Bytes.empty,
                      if injected == FatalCase.UnavailablePhase1 then
                        ScriptLocation.Phase1(Bytes.empty)
                      else ScriptLocation.Phase2(Redeemer(0, 0, Bytes.empty, ExUnits.zero)),
                      EvalFailure
                        .Unsupported(
                          xyz.kd5ujc.scarab.plutus.BackendFailure
                            .BuiltinUnavailable(scalus.uplc.DefaultFun.VerifyEd25519Signature)
                        )
                    )
                    summon[Handle[IO, NodeError]].raise[NodeError, Unit](
                      ValidationError(
                        ref.point,
                        BlockFailure.Tx(failure)
                      )
                    )
                  case FatalCase.Defect | FatalCase.NetworkDefect =>
                    IO.raiseError(new IllegalStateException("injected"))
            }
            def rolledBack(to: Point)(using
                cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
                cats.mtl.Raise[IO, ChainError]
            ) = IO.unit
          follower <- Follower.create[IO, MirrorState](
            ForgedChain.rules,
            events,
            ForgedChain.geometry,
            persistence = Some(failing)
          )
          fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
          pool <- BlockPool.create[IO]
          good <- serving(forged.raws)
          peers <- InMemoryPeers.create(magic)
          first = InMemoryPeers.address(1)
          _ <- peers.serve(first, good)
          transport: Transport[IO, PeerNetwork.Addr] =
            if injected != FatalCase.NetworkDefect then peers
            else
              new Transport[IO, PeerNetwork.Addr]:
                def resolve(peer: PeerNetwork.Addr) = peers.resolve(peer)
                def bearer(peer: PeerNetwork.Addr, remote: SocketAddress[IpAddress])(using
                    cats.mtl.Raise[IO, NetworkError]
                ) =
                  cats.effect.Resource.pure[IO, Bearer[IO]](new Bearer[IO]:
                    def reads =
                      fs2.Stream.raiseError[IO](new IllegalStateException("injected network"))
                    def write(bytes: fs2.Chunk[Byte]) = IO.unit
                    def close = IO.unit)
          manager <- ConnectionManager.create[IO]()
          last = forged.headers.last
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
            headers = None,
            transactions = None,
            observe = None,
            pool = pool,
            versus = follower.versus,
            lost = error => events.emit(FollowerEvent.Reconnecting(error, 1)),
            fatal = f => fatals.update(_ :+ f)
          )
          view = new PeerNetwork.ChainView[IO]:
            def tip = chainRef.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
            def geometry = IO.pure(ForgedChain.geometry)
          local = RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
          roots = PeerNetwork.Roots(
            List(RootGroupSpec(1, 1, List(first -> local))),
            Set.empty,
            None,
            UseLedgerPeers.Disabled
          )
          modeRef <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
          network = PeerNetwork.resource[IO](
            PeerNetwork.Settings(roots, Targets.relay, seed = 7L),
            transport,
            manager,
            OutboundPeers.Config(repromoteDelay = 1.second),
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
          _ <- pool.session.use { origin =>
            val unavailable =
              injected == FatalCase.Unavailable || injected == FatalCase.UnavailablePhase1
            val loaned = forged.headers
              .take(8)
              .zip(forged.raws.take(8))
              .map((header, raw) => header.hash -> raw)
            (origin.claim(loaned.map(_._1)) *> origin.fill(loaned)).whenA(unavailable) *>
              network.use { net =>
                net.run.background.use { _ =>
                  eventually("the node is told to stop")(fatals.get)(_.nonEmpty)
                }
              } *>
              (for
                blamed <- origin.blamed.map(Option(_)).timeoutTo(50.millis, IO.pure(None))
                retained <- pool.take(loaned.last._1)
              yield
                assertEquals(blamed, None)
                // The failing block was borrowed. Treating unavailability as a rejection would
                // call over.refused/distrust, blame its origin and discard this remaining block.
                assertEquals(retained, Some(loaned.last._2))
              ).whenA(unavailable)
          }
          stopped <- fatals.get
          tipNow <- chainRef.get.map(_.tip)
          lostEvents <- seen.get
        yield
          stopped match
            case Vector(Fatal.Storage(StorageError.Io("volatile", cause)))
                if injected == FatalCase.Storage =>
              assert(cause eq storageCause)
            case Vector(Fatal.Unavailable(error))
                if injected == FatalCase.Unavailable || injected == FatalCase.UnavailablePhase1 =>
              val phase1 = error.failure.location.isInstanceOf[ScriptLocation.Phase1]
              assertEquals(phase1, injected == FatalCase.UnavailablePhase1)
            case Vector(Fatal.Defect(cause)) if injected == FatalCase.Defect =>
              assertEquals(cause.getMessage, "injected")
            case Vector(Fatal.Defect(cause)) if injected == FatalCase.NetworkDefect =>
              assertEquals(cause.getMessage, "injected network")
            case other => fail(s"unexpected $other")
          // the tip that was published is one that is stored: never past the block that failed
          assert(tipNow != last.point, "the chain went past the block that could not be stored")
          // nobody is blamed: the loss is not a rejection or a protocol error of the peer
          assert(
            !lostEvents.exists {
              case FollowerEvent.Reconnecting(_, _) => true
              case _                                => false
            },
            lostEvents.toString
          )
      }
    }

  test("a decoder that throws on an outbound peer's input loses that connection, not the node") {
    val cause = new IllegalStateException("injected decoder defect")
    val site = PeerError.InputSite.ChainSyncHeader
    val defect: PeerError = PeerError.InputDefect(site, cause)
    node {
      for
        chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
          VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
        )
        given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
        follower <- Follower.create[IO, MirrorState](
          ForgedChain.rules,
          _ => IO.unit,
          ForgedChain.geometry
        )
        lost <- Ref.of[IO, Vector[PeerError]](Vector.empty)
        // completed by the second report: each connection is reported lost once, so two reports
        // are two connections, the second opened after the first was lost
        twice <- Deferred[IO, Unit]
        fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
        governor <- Ref.of[IO, Vector[Output[PeerNetwork.Addr]]](Vector.empty)
        pool <- BlockPool.create[IO]
        good <- serving(forged.raws)
        peers <- InMemoryPeers.create(magic)
        first = InMemoryPeers.address(1)
        _ <- peers.serve(first, good)
        manager <- ConnectionManager.create[IO]()
        hot = OutboundPeers.Hot[IO](
          magic = magic,
          network = NodeToNode.Settings(),
          peer = Peer.Settings(
            tcp,
            magic,
            readAhead = 8,
            byronEpochSlots = ForgedChain.geometry.byron.epochSlots
          ),
          // the session meets a decoder that throws, as `Peer` does at its decode sites
          follow = (_, _) => Peer.decodingInput[IO, Unit](site)(throw cause),
          prepare = _ => IO.unit,
          heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin,
          headers = None,
          transactions = None,
          observe = None,
          pool = pool,
          versus = follower.versus,
          lost = error =>
            lost.updateAndGet(_ :+ error).flatMap(all => twice.complete(()).whenA(all.size == 2)),
          fatal = f => fatals.update(_ :+ f)
        )
        view = new PeerNetwork.ChainView[IO]:
          def tip = chainRef.get.map(c => c.tipRef.map(t => (t.slot, t.hash.hashCode.toLong)))
          def geometry = IO.pure(ForgedChain.geometry)
        local = RootConfig(false, DiffusionMode.InitiatorAndResponder, Provenance.Outbound, false)
        roots = PeerNetwork.Roots(
          List(RootGroupSpec(1, 1, List(first -> local))),
          Set.empty,
          None,
          UseLedgerPeers.Disabled
        )
        modeRef <- Ref.of[IO, FetchMode](FetchMode.BulkSync)
        network = PeerNetwork.resource[IO](
          PeerNetwork.Settings(roots, Targets.relay, seed = 7L),
          peers,
          manager,
          OutboundPeers.Config(repromoteDelay = 1.second),
          hot,
          view,
          modeRef,
          {
            case Output.Counted(_) => IO.unit
            case output            => governor.update(_ :+ output)
          },
          (_, _) => IO.unit,
          _ => IO.unit,
          _ => IO.unit,
          GsmRunner.Marker.none[IO],
          LedgerPeersSource.none[IO]
        )
        stillRunning <- network.use { net =>
          net.run.background.use { outcome =>
            // what was reported, what was opened and what the governor did, for a run in which
            // the reports do not come
            def stuck(why: String): IO[Nothing] =
              (lost.get, fatals.get, peers.connections, governor.get).tupled.flatMap {
                (seen, stopped, opened, trace) =>
                  IO(
                    fail(
                      s"$why: lost $seen, fatal $stopped, connections $opened, governor" +
                        trace.takeRight(60).mkString("\n  ", "\n  ", "")
                    )
                  )
              }
            for
              // two reports, or the node ending before them; the bound only turns reports that
              // never come into a failure that says so
              waited <- IO
                .race(twice.get, outcome)
                .timeoutTo(60.seconds, stuck("two losses are not reported"))
              _ <- waited.fold(
                _ => IO.unit,
                ended => stuck(s"the node ended ($ended) before two losses were reported")
              )
              // the node does not end
              ended <- outcome.map(Option(_)).timeoutTo(500.millis, IO.pure(None))
            yield ended.isEmpty
          }
        }
        reported <- lost.get
        stopped <- fatals.get
        opened <- peers.connections
      yield
        assert(stillRunning, "the node kept running")
        assert(opened.getOrElse(first, 0) >= 2, opened.toString)
        assertEquals(stopped, Vector.empty[Fatal])
        assert(reported.nonEmpty && reported.forall(_ == defect), reported.toString)
        assertEquals(
          FailurePolicy.classify(defect),
          xyz.kd5ujc.scarab.network.selection.FailureClass.Transient
        )
    }
  }

  test("a peer error raised in a fiber of the connection is a value, raised again in the session") {
    Handle
      .allow[PeerError] {
        for
          // how a failed fiber of the connection reaches the session: as a value, not a Submarine
          fiber <- Fault
            .fiber[IO, Unit](
              PeerError.Unavailable(PeerError.Unavailability.SessionEnded).raise[IO, Unit]
            )
            .start
          failed <- fiber.joinWithNever
          _ <- Fault.land[IO, Unit](failed)
        yield Option.empty[PeerError]
      }
      .rescue(error => IO.pure(Some(error)))
      .map(raised =>
        assertEquals(raised, Some(PeerError.Unavailable(PeerError.Unavailability.SessionEnded)))
      )
  }
