// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, Compute, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, Header, HeaderError, NodeError, PeerError, Points}
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.PeerMetrics
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.relay.ChainServer
import scala.concurrent.duration.*

/** The inbound governor over in-memory bearers: a peer that only connects to us, offering duplex,
  * is followed over that same connection, with its headers validated before its blocks are fetched
  * (G2 with G6).
  */
class InboundGovernorSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val remote: SocketAddress[IpAddress] =
    SocketAddress(ip"10.1.2.3", port"3001")

  private lazy val forged: ForgedChain = ForgedChain.forge(5000 until 5060).unsafeRunSync()

  private def flipInside(raw: Bytes, header: Header.Shelley): Bytes =
    // the KES signature is a 448-byte string that appears verbatim in the block
    val bytes = raw.toArray
    val needle = header.kesSignature.toArray
    val at = bytes.indices.find(i => bytes.startsWith(needle, i)).getOrElse(fail("kes not found"))
    bytes(at + 100) = (bytes(at + 100) ^ 0x01).toByte
    Bytes.fromArray(bytes)

  /** The serving peer: its chain, and the block ranges it was asked for. */
  private final class Remote(
      val handlers: ServerHandlers[IO, NtNHeader],
      val ranges: Ref[IO, Vector[(Point, Point)]],
      val intersections: Ref[IO, Int],
      val extend: Vector[Bytes] => IO[Unit]
  )

  private def serving(raws: Vector[Bytes]): IO[Remote] =
    for
      server <- ChainServer.open[IO](
        None,
        None,
        ChainServer.headerOfBlock(ForgedChain.geometry.byron.epochSlots),
        failure => IO.raiseError(new AssertionError(failure.message))
      )
      extend = (more: Vector[Bytes]) =>
        Handle
          .allow[xyz.kd5ujc.scarab.ledger.NodeError] {
            more.traverse_ { raw =>
              val header =
                Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley].header
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
          .rescue(e => IO(fail(e.message)))
      _ <- extend(raws)
      ranges <- Ref.of[IO, Vector[(Point, Point)]](Vector.empty)
      intersections <- Ref.of[IO, Int](0)
      counting = new BlockFetchServer.Blocks[IO]:
        def range(from: Point, to: Point, max: Int) =
          ranges.update(_ :+ (from -> to)) *> server.blocks.range(from, to, max)
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes]) = IO.unit
    yield new Remote(
      ServerHandlers[IO, NtNHeader](
        NtNHeader.codec,
        server.source.map(source =>
          new ChainSyncServer.Source[IO, NtNHeader]:
            def intersect(points: Vector[Point]) =
              intersections.update(_ + 1) *> source.intersect(points)
            def poll = source.poll
            def await = source.await
        ),
        counting,
        sink
      ),
      ranges,
      intersections,
      extend
    )

  /** Our node: follower, connection manager and governor. */
  private final class Local(
      val chain: Ref[IO, VolatileChain[MirrorState]],
      val manager: ConnectionManager[IO],
      val governor: InboundGovernor[IO],
      val applied: Ref[IO, Vector[Bytes]],
      val metrics: Ref[IO, PeerMetrics[PeerNetwork.Addr]]
  )

  private def local(
      headerFirst: Boolean = true,
      runClients: Boolean = true,
      transactions: Option[TxSubmissionClient.Source[IO]] = None,
      readAhead: Int = 16,
      clock: Option[SlotClock[IO]] = None,
      fatal: Fatal => IO[Unit] = _ => IO.unit,
      pool: Option[BlockPool[IO]] = None,
      events: Option[InboundEvents[IO]] = None,
      keepAliveInterval: FiniteDuration = 30.seconds,
      followSession: Option[InboundGovernor.Follow[IO]] = None
  )(using RepromotionWait[IO]): IO[Local] =
    for
      ref <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
      )
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
      given Handle[IO, NodeError] <- NodeScope.forTest
      applied <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      follower <- Handle
        .allow[NodeError] {
          val rules = ForgedChain.rules
          Follower.create[IO, MirrorState](
            rules,
            _ => IO.unit,
            ForgedChain.geometry,
            persistence = Some(new xyz.kd5ujc.scarab.storage.ChainPersistence[IO, MirrorState]:
              def extended(
                  ref: BlockRef,
                  raw: Bytes,
                  immutable: Vector[BlockRef],
                  anchor: Anchor[MirrorState]
              )(using
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.ChainError]
              ) = applied.update(_ :+ raw)
              def rolledBack(to: ChainSync.Point)(using
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
                  cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.ChainError]
              ) = IO.unit)
          )
        }
        .rescue(e => IO(fail(e.message)))
      manager <- ConnectionManager.create[IO](ConnectionManager.Limits(maxInbound = 4))
      metrics <- Ref.of[IO, PeerMetrics[PeerNetwork.Addr]](PeerMetrics.empty())
      validating = Option.when(headerFirst)(
        HeaderCheck.forChain[IO, MirrorState, xyz.kd5ujc.scarab.consensus.state.ProtocolState](
          NodeHeaderRules[MirrorState](ForgedChain.config, ForgedChain.globals),
          Compute.inline[IO]
        )
      )
      // with a clock, the headers are also held to it (AUD-01), whether or not they are validated
      headerCheck = clock.fold(validating)(c =>
        Some(HeaderClock.clocked[IO](c, validating.getOrElse(HeaderCheck.none[IO])))
      )
      settings = Peer.Settings(
        remote,
        magic,
        readAhead = readAhead,
        network =
          NodeToNode.Settings(keepAlive = KeepAliveClient.Settings(interval = keepAliveInterval)),
        byronEpochSlots = ForgedChain.geometry.byron.epochSlots
      )
      heights = new Peer.HeightSnapshot[IO]:
        def capture = ref.get.map { chain =>
          Peer.HeightSnapshot.Captured(
            chain.blocks.reverse.map(_._1.point) :+ chain.anchor.point,
            (chain.anchor.block.toVector ++ chain.blocks.map(_._1))
              .map(block => block.point -> Option(block.blockNo))
              .toMap
              .updated(Point.Origin, None)
          )
        }
        def unavailable[A](cause: xyz.kd5ujc.scarab.ledger.ChainSyncInternalProblem): IO[A] =
          IO(fail(cause.message))
      governor <- InboundGovernor.create[IO](
        manager,
        InboundGovernor.Config(
          magic,
          NodeToNodeServer.Settings(),
          settings,
          runClients
        ),
        ServerHandlers[IO, NtNHeader](
          NtNHeader.codec,
          Resource.pure[IO, ChainSyncServer.Source[IO, NtNHeader]](
            new ChainSyncServer.Source[IO, NtNHeader]:
              def intersect(points: Vector[Point]) = IO.pure(Left(ChainSync.Tip.Origin))
              def poll = IO.pure(None)
              def await = IO.never
          ),
          (_, _, _) => IO.pure(None),
          new TxSubmissionServer.Sink[IO]:
            def known(id: Bytes) = IO.pure(false)
            def submit(originals: Vector[Bytes]) = IO.unit
        ),
        follow = peer => followSession.fold(follower.session(peer))(_(peer)),
        prepare = _ => IO.unit,
        fatal = fatal,
        headers = headerCheck,
        transactions = transactions,
        events = events,
        pool = pool,
        heightSnapshot = heights,
        peerMetrics = Some(metrics)
      )
    yield new Local(ref, manager, governor, applied, metrics)

  /** The remote peer dials `us` (duplex when it serves `remoteNode` 's chain, else initiator-only);
    * `during` runs while the connection is up and is given the time at which our side ended. When
    * it is done the peer lets go. Returns its result and why our side of the connection ended.
    */
  private def connect[A](
      remoteNode: Option[Remote],
      us: Local,
      observeWrite: fs2.Chunk[Byte] => IO[Unit] = _ => IO.unit
  )(
      during: IO[ConnectionEnd] => IO[A]
  ): IO[(A, ConnectionEnd)] =
    Bearer.inMemory[IO](256).flatMap { (theirs, ours) =>
      val observed = new Bearer[IO]:
        def reads = ours.reads
        def write(bytes: fs2.Chunk[Byte]) = observeWrite(bytes) *> ours.write(bytes)
        def close = ours.close
      us.governor.serve(observed, remote).start.flatMap { served =>
        val ended = served.joinWithNever.timeout(15.seconds)
        Handle
          .allow[NetworkError] {
            NodeToNode
              .over[IO, NtNHeader](
                theirs,
                magic,
                NtNHeader.codec,
                NodeToNode.Settings(),
                None,
                remoteNode.map(_.handlers)
              )
              .use(_ => during(ended))
              .guarantee(theirs.close)
          }
          .rescue(e => IO(fail(e.message)))
          .flatMap(a => ended.map(a -> _))
      }
    }

  private def peerFailure(end: ConnectionEnd): PeerError = end match
    case ConnectionEnd.Closed(NetworkError.Closure.AbortedBy(error: PeerError)) => error
    case other => fail(s"unexpected $other")

  test("an unexpected inbound transport exception remains a defect with the original cause") {
    val defect = new IllegalStateException("injected read defect")
    val bearer = new Bearer[IO]:
      def reads = fs2.Stream.raiseError[IO](defect)
      def write(bytes: fs2.Chunk[Byte]) = IO.unit
      def close = IO.unit
    for
      reported <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      us <- local(runClients = false, fatal = value => reported.update(_ :+ value))
      outcome <- us.governor.serve(bearer, remote).timeout(5.seconds)
      fatalities <- reported.get
      table <- us.manager.snapshot
    yield
      outcome match
        case ConnectionEnd.Defect(cause) => assert(cause eq defect)
        case other                       => fail(s"unexpected $other")
      fatalities match
        case Vector(Fatal.Defect(cause)) => assert(cause eq defect)
        case other                       => fail(s"unexpected $other")
      assertEquals(table.counters, ConnectionState.Counters.zero)
  }

  test("connection JSON renders a typed session failure only at presentation") {
    val failure = InboundGovernor.SessionFailure.Peer(PeerError.NoIntersection)
    val json = InboundGovernor.json(
      ConnectionManager.Snapshot(ConnectionState.Counters.zero, Vector.empty),
      Vector(InboundGovernor.Session(remote.toString, ended = Some(failure)))
    )
    assertEquals(
      json.hcursor.downField("inboundSessions").downArray.get[String]("ended"),
      Right(failure.message)
    )
  }

  private def eventually[A](what: String)(read: IO[A])(ok: A => Boolean): IO[A] =
    def loop(left: Int): IO[A] =
      read.flatMap { a =>
        if ok(a) then IO.pure(a)
        else if left == 0 then IO.raiseError(new RuntimeException(s"timed out: $what ($a)"))
        else IO.sleep(50.millis) *> loop(left - 1)
      }
    loop(200)

  test("a duplex peer that only connects to us is followed over that connection") {
    for
      remoteNode <- serving(forged.raws)
      us <- local()
      last = forged.headers.last
      outcome <- connect(Some(remoteNode), us) { _ =>
        for
          tip <- eventually("our chain reaches the peer's tip")(us.chain.get.map(_.tip))(
            _ == last.point
          )
          sessions <- us.governor.sessions
          table <- us.manager.snapshot
        yield (tip, sessions, table)
      }
      ((tip, sessions, table), _) = outcome
      ranges <- remoteNode.ranges.get
      applied <- us.applied.get
      after <- us.manager.snapshot
      metrics <- us.metrics.get
    yield
      assertEquals(metrics.upstreamness.get(remote), Some(forged.headers.size))
      assertEquals(metrics.fetchynessBlocks.get(remote), Some(forged.headers.size))
      assertEquals(metrics.fetchynessBytes.get(remote), Some(forged.raws.map(_.size.toLong).sum))
      assertEquals(tip, last.point)
      assertEquals(table.counters, ConnectionState.Counters(1, 0, 1, 1))
      assertEquals(
        table.connections.map(_._2),
        Vector(
          ConnectionState.Established(
            ConnectionState.Provenance.Inbound,
            ConnectionState.DataFlow.Duplex
          )
        )
      )
      assertEquals(sessions.size, 1)
      assert(sessions.head.headers >= forged.headers.size.toLong, sessions.head.toString)
      assert(sessions.head.intersection.isDefined)
      // blocks came through BlockFetch, in ranges the peer was asked for
      assert(ranges.nonEmpty)
      assertEquals(applied, forged.raws)
      // the connection is gone with its peer
      assertEquals(after.counters, ConnectionState.Counters.zero)
  }

  test("a header with a broken signature drops the peer before any block is requested") {
    val tampered = forged.raws.updated(0, flipInside(forged.raws.head, forged.headers.head))
    for
      remoteNode <- serving(tampered)
      us <- local()
      outcome <- connect(Some(remoteNode), us)(ended => ended)
      ranges <- remoteNode.ranges.get
      chain <- us.chain.get
      sessions <- us.governor.sessions
      table <- us.manager.snapshot
      metrics <- us.metrics.get
    yield
      val failure = peerFailure(outcome._2)
      assert(failure.isInstanceOf[PeerError.InvalidHeader])
      assertEquals(ranges, Vector.empty[(Point, Point)])
      assertEquals(metrics.sizes, (0, 0))
      assertEquals(chain.tip, ChainSync.Point.Origin)
      sessions.head.ended match
        case Some(InboundGovernor.SessionFailure.Peer(error)) => assert(error eq failure)
        case other                                            => fail(s"unexpected $other")
      assertEquals(table.counters, ConnectionState.Counters.zero)
  }

  /** A clock that reads `from` now and then runs at the speed of the test's own time. */
  private def ticking(from: java.time.Instant): IO[SlotClock[IO]] =
    IO.monotonic.map(began =>
      new SlotClock[IO]:
        def now = IO.monotonic.map(t => from.plusNanos((t - began).toNanos))
        def geometry = IO.pure(ForgedChain.geometry)
    )

  test("a peer whose headers are in the future is dropped, a healthy peer is followed") {
    // the node's clock stands at the onset of the tenth block's slot
    val reference = forged.headers(9)
    val healthy = forged.raws.take(10)
    for
      now <- IO.pure(ForgedChain.geometry.slotStart(reference.slot))
      clock <- ticking(now)
      us <- local(clock = Some(clock))
      good <- serving(healthy)
      _ <- connect(Some(good), us) { _ =>
        eventually("our chain reaches the healthy peer's tip")(us.chain.get.map(_.tip))(
          _ == reference.point
        )
      }
      early <- serving(forged.raws)
      outcome <- connect(Some(early), us)(ended => ended)
      ranges <- early.ranges.get
      chain <- us.chain.get
      sessions <- us.governor.sessions
      table <- us.manager.snapshot
    yield
      val failure = peerFailure(outcome._2)
      failure match
        case PeerError.InvalidHeader(_, _: HeaderError.FarFuture) => ()
        case other                                                => fail(s"unexpected $other")
      assert(
        sessions.exists(_.ended.exists {
          case InboundGovernor.SessionFailure.Peer(error) => error eq failure
          case _                                          => false
        }),
        sessions.toString
      )
      // no block of the batch with a future header was requested, and nothing in the future applied
      assert(chain.tip != forged.headers.last.point)
      assert(chain.tipRef.forall(t => t.slot <= reference.slot + 20), chain.tipRef.toString)
      assert(ranges.size < forged.headers.size, ranges.toString)
      assertEquals(table.counters, ConnectionState.Counters.zero)
  }

  test("the last block's header is tampered: its block is never requested, the others applied") {
    val n = forged.raws.size
    val tampered = forged.raws.updated(n - 1, flipInside(forged.raws.last, forged.headers.last))
    val badHeader =
      Block.decodeShelley(tampered.last).toOption.get.asInstanceOf[Block.Shelley].header
    for
      remoteNode <- serving(tampered)
      us <- local()
      outcome <- connect(Some(remoteNode), us)(ended => ended)
      ranges <- remoteNode.ranges.get
      chain <- us.chain.get
    yield
      assert(peerFailure(outcome._2).isInstanceOf[PeerError.InvalidHeader])
      assert(ranges.forall { case (_, to) => to != badHeader.point }, ranges.toString)
      // headers are checked a batch at a time, before any block of the batch: the batch with the
      // bad header is not fetched at all (how many blocks the batches before it held depends on the
      // pipeline's timing), the bad block itself never is
      val applied = forged.headers.indexWhere(_.point == chain.tip)
      assert(applied >= 0 && applied <= n - 2, s"applied up to block $applied of $n")
  }

  test("a peer that only reads from us (initiator-only) is served and not followed") {
    for
      us <- local()
      outcome <- connect(None, us) { _ =>
        eventually("the connection is established")(us.manager.snapshot)(
          _.connections.exists(
            _._2 == ConnectionState.Established(
              ConnectionState.Provenance.Inbound,
              ConnectionState.DataFlow.Unidirectional
            )
          )
        ).flatMap(snapshot => IO.sleep(200.millis).as(snapshot))
      }
      sessions <- us.governor.sessions
      chain <- us.chain.get
      metrics <- us.metrics.get
    yield
      assertEquals(outcome._1.counters, ConnectionState.Counters(1, 0, 0, 1))
      assertEquals(sessions, Vector.empty)
      assertEquals(metrics.sizes, (0, 0))
      assertEquals(chain.tip, ChainSync.Point.Origin)
  }

  test("a second connection from the same address is refused while the first is up") {
    for
      us <- local()
      outcome <- connect(None, us) { _ =>
        eventually("the first connection is admitted")(us.manager.snapshot)(
          _.counters.inbound == 1
        ) *> Bearer.inMemory[IO](64).flatMap((_, ours2) => us.governor.serve(ours2, remote))
      }
    yield outcome._1 match
      case ConnectionEnd.Refused(ConnectionError.AlreadyConnected(address, _)) =>
        assertEquals(address, remote.toString)
      case other => fail(s"unexpected $other")
  }

  /** A mempool that never holds a transaction: a blocking request for ids waits forever. */
  private val nothingToOffer: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    def after(ticket: Long, max: Int) = IO.pure(Vector.empty)
    def awaitAfter(ticket: Long) = IO.never
    def body(id: Bytes) = IO.pure(None)

  test("an inbound lagging candidate re-intersects and resumes on the same duplex connection") {
    for
      remoteNode <- serving(forged.raws)
      waiting <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      first <- Ref.of[IO, Boolean](true)
      intersections <- Ref.of[IO, Vector[Point]](Vector.empty)
      delayed = new Remote(
        new ServerHandlers[IO, NtNHeader](
          remoteNode.handlers.codec,
          remoteNode.handlers.chainSync.map { inner =>
            new ChainSyncServer.Source[IO, NtNHeader]:
              def intersect(points: Vector[Point]) = inner.intersect(points).flatTap {
                case Right((point, _)) => intersections.update(_ :+ point)
                case Left(_)           => IO.unit
              }
              def poll = inner.poll.flatTap {
                case Some(_: ChainSyncServer.Update.RollForward[?]) =>
                  first.getAndSet(false).flatMap { hold =>
                    (waiting.complete(()).void *> release.get).whenA(hold)
                  }
                case _ => IO.unit
              }
              def await = inner.await
          },
          remoteNode.handlers.blocks,
          remoteNode.handlers.transactions
        ),
        remoteNode.ranges,
        remoteNode.intersections,
        remoteNode.extend
      )
      us <- local(transactions = Some(nothingToOffer), readAhead = 2)
      prefix = forged.headers.size / 2
      advanced = forged.headers
        .zip(forged.states)
        .take(prefix)
        .foldLeft(
          VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
        ) { case (chain, (header, state)) =>
          chain.extend(header, state).fold(e => fail(e.message), _._1)
        }
      result <- connect(Some(delayed), us) { _ =>
        for
          _ <- waiting.get.timeout(10.seconds)
          // Model the faster session's validated adoption while the inbound reply is delayed.
          _ <- us.chain.set(advanced)
          _ <- release.complete(())
          _ <- eventually("the recovered follower reaches the remote tip")(us.chain.get.map(_.tip))(
            _ == forged.headers.last.point
          )
          sessions <- us.governor.sessions
          points <- intersections.get
          fetched <- remoteNode.ranges.get
          applied <- us.applied.get
          connections <- us.manager.snapshot
        yield (sessions, points, fetched, applied, connections)
      }
    yield
      val ((sessions, points, fetched, applied, connections), _) = result
      assertEquals(points, Vector(Point.Origin, advanced.tip))
      assertEquals(sessions.size, 1)
      assertEquals(sessions.head.number, BigInt(1))
      assertEquals(sessions.head.ended, None)
      assertEquals(sessions.head.intersection, Some(Points.show(advanced.tip)))
      assertEquals(connections.counters.inbound, 1)
      assert(fetched.nonEmpty)
      assertEquals(applied, forged.raws.drop(prefix))
  }

  test("a peer that shares no point with our chain is tried again on the same connection") {
    for
      waits <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
      given RepromotionWait[IO] = new RepromotionWait[IO]:
        def delay(duration: FiniteDuration): IO[Unit] =
          waits.update(_ :+ duration) *> IO.sleep(100.millis)
      first <- serving(forged.raws)
      // with transactions to offer, our TxSubmission2 client runs beside ChainSync: a retry must
      // finish the old activation before sending the next MsgInit.
      us <- local(transactions = Some(nothingToOffer))
      last = forged.headers.last
      _ <- connect(Some(first), us) { _ =>
        eventually("our chain reaches the peer's tip")(us.chain.get.map(_.tip))(_ == last.point)
      }
      // a peer with an empty chain shares nothing with ours (our anchor is past Origin)
      behind <- serving(Vector.empty)
      outcome <- connect(Some(behind), us) { _ =>
        for
          waiting <- eventually("the session is retried")(us.governor.sessions)(
            _.exists(_.retries >= 2)
          )
          // the peer catches up: the next attempt finds our tip and follows
          _ <- behind.extend(forged.raws)
          following <- eventually("the session follows")(us.governor.sessions)(
            _.exists(_.intersection.isDefined)
          )
          table <- us.manager.snapshot
        yield (waiting, following, table)
      }
      requested <- waits.get
    yield
      val ((waiting, following, table), _) = outcome
      // retried without ending the session or the connection
      assert(waiting.head.ended.isEmpty, waiting.toString)
      assertEquals(following.head.intersection, Some(Points.show(last.point)))
      assertEquals(table.counters.inbound, 1)
      assert(requested.nonEmpty)
      assert(requested.forall(_ == 120.seconds), requested.toString)
  }

  test("cached inbound originals punish only their exact adding connection generation") {
    import xyz.kd5ujc.scarab.ledger.{BlockFailure, ValidationError}
    val header = forged.headers.head
    val refusal = ValidationError(header.point, BlockFailure.HeaderDiffers)
    for
      seen <- Ref.of[IO, Vector[InboundEvent]](Vector.empty)
      aborted <- Ref.of[IO, Vector[(Int, PeerError)]](Vector.empty)
      us <- local(events =
        Some(
          new InboundEvents[IO]:
            def emit(remote: String, event: InboundEvent) = seen.update(_ :+ event)
        )
      )
      pool <- BlockPool.create[IO]
      source <- us.governor.source(remote, e => aborted.update(_ :+ (1 -> e))).allocated
      (old, release) = source
      _ <- pool.session(Some(us.governor.rejectedCachedBlock(old, _))).use { claims =>
        claims.claim(Vector(header.hash)) *> claims.fill(Vector(header.hash -> forged.raws.head))
      }
      // The original survives the sending session. Closing that bearer must discard its abort
      // callback, while the pool's bounded origin keeps immutable attribution metadata.
      _ <- release
      _ <- us.governor.source(remote, e => aborted.update(_ :+ (2 -> e))).use { current =>
        pool.session.use { taker =>
          taker.take(header.hash).flatMap {
            case Some(taken) => taker.distrust(taken.origin, refusal)
            case None        => IO(fail("delivered original disappeared before attribution"))
          }
        } *> us.governor.rejectedCachedBlock(current, refusal) *>
          IO(assert(current.generation > old.generation))
      }
      generations <- Vector.fill(70)(()).traverse { _ =>
        us.governor.source(remote, _ => IO.unit).use(key => IO.pure(key.generation))
      }
      punished <- aborted.get
      emitted <- seen.get
    yield
      assertEquals(generations, (3 to 72).map(BigInt(_)).toVector)
      assertEquals(punished, Vector(2 -> PeerError.InvalidBlock(refusal)))
      assertEquals(
        emitted,
        Vector(
          InboundEvent.CachedBlockRejected(refusal, old.generation, false),
          InboundEvent.CachedBlockRejected(refusal, old.generation + 1, true)
        )
      )
  }

  test(
    "a retained original from an old hot activation cannot punish its successor on the same bearer"
  ) {
    import cats.mtl.Raise
    import xyz.kd5ujc.scarab.ledger.{BlockFailure, ValidationError}
    import xyz.kd5ujc.scarab.protocol.{Mux as WireMux, KeepAlive}
    val header = forged.headers.head
    val refusal = ValidationError(header.point, BlockFailure.HeaderDiffers)
    val decoder = WireMux.Decoder.create().fold(e => fail(e.toString), identity)
    cats.effect.testkit.TestControl.executeEmbed {
      for
        awaiting <- Deferred[IO, Unit]
        delivered <- Deferred[IO, Unit]
        finishFirst <- Deferred[IO, Unit]
        enteredWarm <- Deferred[IO, FiniteDuration]
        promote <- Deferred[IO, Unit]
        secondHot <- Deferred[IO, Unit]
        activations <- Ref.of[IO, Int](0)
        seen <- Ref.of[IO, Vector[InboundEvent]](Vector.empty)
        wire <- Ref.of[IO, (WireMux.Decoder, Vector[Int])]((decoder, Vector.empty))
        given RepromotionWait[IO] = new RepromotionWait[IO]:
          def delay(duration: FiniteDuration): IO[Unit] =
            enteredWarm.complete(duration).void *> promote.get
        pool <- BlockPool.create[IO]
        follow: InboundGovernor.Follow[IO] = (peer: Peer[IO]) =>
          (node: Raise[IO, NodeError]) ?=>
            Handle
              .allow[PeerError] {
                for
                  activation <- activations.getAndUpdate(_ + 1)
                  intersection <- peer.intersect(Vector(Point.Origin))
                  _ <- IO(
                    assertEquals(
                      intersection,
                      Some(if activation == 0 then Point.Origin else forged.headers.last.point)
                    )
                  )
                  _ <-
                    if activation == 0 then
                      for
                        announced <- cats.Monad[IO].tailRecM(()) { _ =>
                          peer.next.flatMap {
                            case SyncEvent.RollForward(value, _) => IO.pure(Right(value))
                            case SyncEvent.RollBackward(point, _) =>
                              IO(assertEquals(point, Point.Origin)).as(Left(()))
                          }
                        }
                        raw <- peer.fetch(announced)
                        _ <- IO(assertEquals(raw, forged.raws.head))
                        _ <- delivered.complete(()) *> finishFirst.get
                        missing <- peer.intersect(Vector(forged.headers.last.point))
                        _ <- IO(assertEquals(missing, None))
                        // The real failed FindIntersect has sent MsgDone. This is the same normal
                        // NoMoreIntersection result the native client returns after draining.
                        _ <- summon[Raise[IO, PeerError]].raise[PeerError, Unit](
                          PeerError.NoIntersection
                        )
                      yield ()
                    else secondHot.complete(()).void *> IO.never[Unit]
                yield ()
              }
              .rescue(error => node.raise[NodeError, Unit](error))
        us <- local(
          pool = Some(pool),
          followSession = Some(follow),
          readAhead = 1,
          keepAliveInterval = 20.millis,
          events = Some(
            new InboundEvents[IO]:
              def emit(remote: String, event: InboundEvent) = seen.update(_ :+ event)
          )
        )
        // Observe the real rolling request waiting at the remote tip. Drain it with the next
        // original block after the sender has stopped; a fixed batch count is no longer a boundary.
        remoteNode <- serving(forged.raws.take(Peer.Depth + 1))
        server = new Remote(
          new ServerHandlers[IO, NtNHeader](
            remoteNode.handlers.codec,
            remoteNode.handlers.chainSync.map { inner =>
              new ChainSyncServer.Source[IO, NtNHeader]:
                def intersect(points: Vector[Point]) = inner.intersect(points)
                def poll = inner.poll
                def await = awaiting.complete(()).void *> inner.await
            },
            remoteNode.handlers.blocks,
            remoteNode.handlers.transactions
          ),
          remoteNode.ranges,
          remoteNode.intersections,
          remoteNode.extend
        )
        advanced = forged.headers
          .zip(forged.states)
          .foldLeft(
            VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
          ) { case (chain, (value, state)) =>
            chain.extend(value, state).fold(error => fail(error.message), _._1)
          }
        observe = (bytes: fs2.Chunk[Byte]) =>
          wire.update { (before, cookies) =>
            val (after, sdus) =
              before.feed(Bytes.fromArray(bytes.toArray)).fold(e => fail(e.toString), identity)
            val requests = sdus
              .filter(s =>
                s.protocol == Protocols.KeepAlive && s.direction == WireMux.Direction.Initiator
              )
              .flatMap(s =>
                KeepAlive
                  .decode(KeepAlive.State.Client, KeepAlive.Role.Client, s.payload)
                  .fold(
                    e => fail(e.toString),
                    {
                      case KeepAlive.Message.Request(cookie) => Vector(cookie.value)
                      case other => fail(s"unexpected client message $other")
                    }
                  )
              )
            after -> (cookies ++ requests)
          }
        _ <- connect(Some(server), us, observe) { _ =>
          for
            _ <- delivered.get.timeoutTo(
              5.seconds,
              (
                activations.get,
                seen.get,
                server.ranges.get,
                server.intersections.get,
                us.governor.sessions
              ).tupled.flatMap(state => IO(fail(s"hot1 body was not delivered: $state")))
            )
            _ <- eventually("all submitted actual block ranges can drain")(
              server.ranges.get
            )(_.exists(_._2 == forged.headers(Peer.Depth).point))
            old <- us.governor
              .cachedSource(remote)
              .flatMap(
                _.liftTo[IO](
                  new AssertionError("the delivering hot activation has no active source key")
                )
              )
            original <- pool.session
              .use(_.take(header.hash))
              .flatMap(
                _.liftTo[IO](
                  new AssertionError("the actual delivered original was not retained")
                )
              )
            _ <- awaiting.get
            // Model another session advancing the selected chain beyond this remote's history.
            _ <- us.chain.set(advanced)
            _ <- finishFirst.complete(())
            // TestControl advances only once the local drain is suspended on the actual wire reply.
            _ <- IO.sleep(1.second)
            _ <- server.extend(forged.raws.slice(Peer.Depth + 1, Peer.Depth + 2))
            delay <- enteredWarm.get.timeoutTo(
              5.seconds,
              IO(fail("hot1 failed FindIntersect did not finish draining"))
            )
            _ <- IO(assertEquals(delay, 120.seconds))
            warmSource <- us.governor.cachedSource(remote)
            _ <- IO(assertEquals(warmSource, None))
            _ <- server.extend(forged.raws.drop(Peer.Depth + 2))
            _ <- promote.complete(())
            _ <- secondHot.get.timeoutTo(
              5.seconds,
              IO(fail("hot2 did not reach its actual intersection"))
            )
            current <- us.governor
              .cachedSource(remote)
              .flatMap(
                _.liftTo[IO](
                  new AssertionError("the successor hot activation has no active source key")
                )
              )
            before <- wire.get.map(_._2.size)
            // Native ClientInterface.hs:193 captures the adding BlockFetch thread; the punishment
            // DSL's mkPunishThisThread (InvalidBlockPunishment.hs:48–52) cannot target hot2's thread.
            _ <- pool.session.use(_.distrust(original.origin, refusal))
            _ <- eventually("the same bearer's KeepAlive survives old-hot punishment")(
              wire.get.map(_._2.size)
            )(_ > before)
            active <- us.governor.cachedSource(remote)
            table <- us.manager.snapshot
            events <- seen.get
            sessions <- us.governor.sessions
            intersections <- server.intersections.get
            fetched <- server.ranges.get
            _ <- IO {
              assert(current.generation > old.generation)
              assertEquals(active, Some(current))
              assertEquals(table.counters.inbound, 1)
              assertEquals(intersections, 3)
              assert(fetched.nonEmpty)
              assertEquals(sessions.head.ended, None)
              assertEquals(
                events.collect { case event: InboundEvent.CachedBlockRejected => event },
                Vector(InboundEvent.CachedBlockRejected(refusal, old.generation, false))
              )
            }
          yield ()
        }
        cookies <- wire.get.map(_._2)
      yield assertEquals(cookies, cookies.indices.toVector)
    }
  }

  test("an inbound validation refusal invalidates recovered originals through the counted peer") {
    val expected = forged.headers.head
    // The peer advertises its valid first header. An unrelated recovered original is cached
    // under that hash; the current peer did not deliver those bytes.
    val invalid = forged.raws(1)
    for
      persisted <- Ref.of[IO, Map[Bytes, Bytes]](Map(expected.hash -> invalid))
      invalidated <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool.create[IO](
        BlockPool.FetchPolicy(),
        IO.pure(
          xyz.kd5ujc.scarab.consensus.ChainSelection.Local(
            Point.Origin,
            xyz.kd5ujc.scarab.consensus.SelectView(-1, 0, None, 0, None),
            Vector.empty
          )
        ),
        BlockPool.Recovered(
          persisted.get.map(_.keySet),
          hash => persisted.get.map(_.get(hash)),
          hash => invalidated.update(_ + hash) *> persisted.update(_ - hash)
        )
      )
      us <- local(headerFirst = false, pool = Some(pool))
      bad <- serving(forged.raws.take(1))
      outcome <- connect(Some(bad), us)(ended => ended)
      rejected <- invalidated.get
      remaining <- persisted.get
      cached <- pool.takeFilled(expected.hash)
      chain <- us.chain.get
    yield
      peerFailure(outcome._2) match
        case PeerError.Diverged(PeerError.Divergence.ForeignBlock(refusal)) =>
          assertEquals(refusal.point, expected.point)
        case other => fail(s"cached source refusal blamed the requesting peer: $other")
      assertEquals(rejected, Set(expected.hash))
      assertEquals(remaining, Map.empty[Bytes, Bytes])
      assertEquals(cached, None)
      assertEquals(chain.tip, ChainSync.Point.Origin)
  }

  test("warm repromotion gates hot requests, retains one KeepAlive, and cancels with its bearer") {
    import xyz.kd5ujc.scarab.protocol.{Mux as WireMux, KeepAlive}
    val decoder = WireMux.Decoder.create().fold(e => fail(e.toString), identity)
    for
      entered <- Deferred[IO, FiniteDuration]
      release <- Deferred[IO, Unit]
      second <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      wire <- Ref.of[IO, (WireMux.Decoder, Vector[Int])]((decoder, Vector.empty))
      given RepromotionWait[IO] = new RepromotionWait[IO]:
        def delay(duration: FiniteDuration): IO[Unit] =
          calls.getAndUpdate(_ + 1).flatMap {
            case 0 => entered.complete(duration).void *> release.get
            case _ =>
              second.complete(()).void *> IO.never[Unit].onCancel(cancelled.complete(()).void)
          }
      observe = (bytes: fs2.Chunk[Byte]) =>
        wire.update { (before, cookies) =>
          val (after, sdus) =
            before.feed(Bytes.fromArray(bytes.toArray)).fold(e => fail(e.toString), identity)
          val requests = sdus
            .filter(s =>
              s.protocol == Protocols.KeepAlive && s.direction == WireMux.Direction.Initiator
            )
            .flatMap { s =>
              KeepAlive
                .decode(KeepAlive.State.Client, KeepAlive.Role.Client, s.payload)
                .fold(
                  e => fail(e.toString),
                  {
                    case KeepAlive.Message.Request(cookie) => Vector(cookie.value)
                    case other => fail(s"unexpected client message $other")
                  }
                )
            }
          after -> (cookies ++ requests)
        }
      first <- serving(forged.raws)
      us <- local(transactions = Some(nothingToOffer), keepAliveInterval = 20.millis)
      _ <- connect(Some(first), us) { _ =>
        eventually("the local immutable anchor advances")(us.chain.get.map(_.tip))(
          _ == forged.headers.last.point
        )
      }
      behind <- serving(Vector.empty)
      _ <- connect(Some(behind), us, observe) { _ =>
        for
          delay <- entered.get.timeout(5.seconds)
          requests <- behind.intersections.get
          _ <- eventually("KeepAlive remains live while hot protocols are waiting")(
            wire.get.map(_._2)
          )(_.size >= 3)
          still <- behind.intersections.get
          manager <- us.manager.snapshot
          _ <- IO {
            assertEquals(delay, 120.seconds)
            assertEquals(requests, 1)
            assertEquals(still, 1)
            assertEquals(manager.counters.inbound, 1)
          }
          _ <- release.complete(())
          _ <- second.get.timeout(5.seconds)
          again <- behind.intersections.get
          _ <- IO(assertEquals(again, 2))
        yield ()
      }
      _ <- cancelled.get.timeout(5.seconds)
      cookies <- wire.get.map(_._2)
      after <- us.manager.snapshot
    yield
      assertEquals(cookies, cookies.indices.toVector)
      assertEquals(after.counters, ConnectionState.Counters.zero)
  }

  test("a connection we opened to the peer does not refuse the peer's own inbound connection") {
    for
      us <- local()
      outcome <- Handle
        .allow[ConnectionError](
          us.manager.outbound(remote).use { _ =>
            connect(None, us) { _ =>
              eventually("the inbound connection is established")(us.manager.snapshot)(
                _.counters.inbound == 1
              ).map(_.counters)
            }
          }
        )
        .rescue(e => IO(fail(e.message)))
    yield assertEquals(outcome._1.outbound -> outcome._1.inbound, (1, 1))
  }
