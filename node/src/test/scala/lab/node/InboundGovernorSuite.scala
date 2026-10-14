// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream
import lab.cbor.Bytes
import lab.network.{ChainSync, ConnectionState, NtNHeader}
import lab.network.ChainSync.Point
import lab.network.runtime.*
import lab.node.forge.ForgedChain
import lab.node.relay.ChainServer
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** The inbound governor over in-memory bearers: a peer that only connects to us, offering duplex,
  * is followed over that same connection, with its headers validated before its blocks are fetched
  * (G2 with G6).
  */
class InboundGovernorSuite extends CatsEffectSuite:
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
      val extend: Vector[Bytes] => IO[Unit]
  )

  private def serving(raws: Vector[Bytes]): IO[Remote] =
    for
      server <- ChainServer.open[IO](
        None,
        None,
        ChainServer.headerOfBlock(ForgedChain.geometry.byron.epochSlots)
      )
      extend = (more: Vector[Bytes]) =>
        Handle
          .allow[ChainError] {
            more.traverse_ { raw =>
              val header =
                Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley].header
              server
                .persistence[NodeState]
                .extended(
                  BlockRef(header.slot, header.hash, header.blockNo),
                  raw,
                  Vector.empty,
                  Anchor(None, forged.genesis)
                )
            }
          }
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      _ <- extend(raws)
      ranges <- Ref.of[IO, Vector[(Point, Point)]](Vector.empty)
      counting = new BlockFetchServer.Blocks[IO]:
        def range(from: Point, to: Point, max: Int) =
          ranges.update(_ :+ (from -> to)) *> server.blocks.range(from, to, max)
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes]) = IO.unit
    yield new Remote(
      ServerHandlers[IO, NtNHeader](NtNHeader.codec, server.source, counting, sink),
      ranges,
      extend
    )

  /** Our node: follower, connection manager and governor. */
  private final class Local(
      val chain: Ref[IO, VolatileChain[NodeState]],
      val manager: ConnectionManager[IO],
      val governor: InboundGovernor[IO],
      val applied: Ref[IO, Vector[Bytes]]
  )

  private def local(
      headerFirst: Boolean = true,
      runClients: Boolean = true,
      retryDelay: FiniteDuration = 10.seconds
  ): IO[Local] =
    for
      ref <- Ref.of[IO, VolatileChain[NodeState]](
        VolatileChain.genesis(ForgedChain.geometry.securityParam, forged.genesis)
      )
      given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
      applied <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      follower <- Handle
        .allow[NodeError] {
          val rules = NodeRules[IO](ForgedChain.config, ForgedChain.globals)
          Follower.create[IO, NodeState](
            rules,
            _ => IO.unit,
            ForgedChain.geometry,
            persistence = Some(new lab.node.storage.ChainPersistence[IO, NodeState]:
              def extended(
                  ref: BlockRef,
                  raw: Bytes,
                  immutable: Vector[BlockRef],
                  anchor: Anchor[NodeState]
              ) = applied.update(_ :+ raw)
              def rolledBack(to: ChainSync.Point) = IO.unit)
          )
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      manager <- ConnectionManager.create[IO](ConnectionManager.Limits(maxInbound = 4))
      headerCheck = Option.when(headerFirst)(
        HeaderCheck.forChain[IO, NodeState, lab.node.consensus.PraosState](
          NodeHeaderRules(ForgedChain.config, ForgedChain.globals),
          Compute.inline[IO]
        )
      )
      settings = Peer.Settings(
        remote,
        magic,
        readAhead = 16,
        byronEpochSlots = ForgedChain.geometry.byron.epochSlots
      )
      governor <- InboundGovernor.create[IO](
        manager,
        InboundGovernor.Config(
          magic,
          NodeToNodeServer.Settings(),
          settings,
          runClients,
          retryDelay
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
        follow = peer => follower.session(peer),
        prepare = _ => IO.unit,
        headers = headerCheck
      )
    yield new Local(ref, manager, governor, applied)

  /** The remote peer dials `us` (duplex when it serves `remoteNode` 's chain, else initiator-only);
    * `during` runs while the connection is up and is given the time at which our side ended. When
    * it is done the peer lets go. Returns its result and why our side of the connection ended.
    */
  private def connect[A](remoteNode: Option[Remote], us: Local)(
      during: IO[String] => IO[A]
  ): IO[(A, String)] =
    Bearer.inMemory[IO](256).flatMap { (theirs, ours) =>
      us.governor.serve(ours, remote).start.flatMap { served =>
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
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
          .flatMap(a => ended.map(a -> _))
      }
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
    yield
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
    yield
      assert(outcome._2.contains("invalid header"), outcome._2)
      assertEquals(ranges, Vector.empty[(Point, Point)])
      assertEquals(chain.tip, ChainSync.Point.Origin)
      assert(sessions.head.ended.exists(_.contains("invalid header")), sessions.toString)
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
      assert(outcome._2.contains("invalid header"), outcome._2)
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
    yield
      assertEquals(outcome._1.counters, ConnectionState.Counters(1, 0, 0, 1))
      assertEquals(sessions, Vector.empty)
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
    yield assert(outcome._1.contains("already connected"), outcome._1)
  }

  test("a peer that shares no point with our chain is tried again on the same connection") {
    for
      first <- serving(forged.raws)
      us <- local(retryDelay = 100.millis)
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
    yield
      val ((waiting, following, table), _) = outcome
      // retried without ending the session or the connection
      assert(waiting.head.ended.isEmpty, waiting.toString)
      assertEquals(following.head.intersection, Some(Points.show(last.point)))
      assertEquals(table.counters.inbound, 1)
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
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
    yield assertEquals(outcome._1.outbound -> outcome._1.inbound, (1, 1))
  }
