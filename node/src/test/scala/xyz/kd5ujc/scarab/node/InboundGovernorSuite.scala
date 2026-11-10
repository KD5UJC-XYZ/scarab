// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import cats.effect.{IO, Ref, Resource}
import cats.mtl.Handle
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance}
import xyz.kd5ujc.scarab.network.*
import scala.concurrent.duration.*

/** The inbound governor over in-memory bearers: it serves an accepted connection and tracks the
  * peer's activity, and runs none of our clients on it (issue 854; the outbound side's use of the
  * connection is [[OutboundReuseSuite]]'s subject).
  */
class InboundGovernorSuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val remote: SocketAddress[IpAddress] = SocketAddress(ip"10.1.2.3", port"3001")

  /** An empty chain whose intersections are counted. */
  private def handlers(intersections: Ref[IO, Int]): ServerHandlers[IO, NtNHeader] =
    ServerHandlers[IO, NtNHeader](
      NtNHeader.codec,
      Resource.pure[IO, ChainSyncServer.Source[IO, NtNHeader]](
        new ChainSyncServer.Source[IO, NtNHeader]:
          def intersect(points: Vector[ChainSync.Point]) =
            intersections.update(_ + 1).as(Left(ChainSync.Tip.Origin))
          def poll = IO.pure(None)
          def await = IO.never
      ),
      (_, _) => IO.pure(None),
      new TxSubmissionServer.Sink[IO]:
        def reader = RelayedTransactions
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[Bytes])(using
            cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
        ) = IO.unit
    )

  private final case class Local(manager: ConnectionManager[IO], governor: InboundGovernor[IO])

  private def local: IO[Local] =
    for
      manager <- ConnectionManager.create[IO](ConnectionManager.Limits(timeWait = Duration.Zero))
      served <- Ref.of[IO, Int](0)
      governor <- InboundGovernor.create[IO](
        manager,
        InboundGovernor.Config(magic, NodeToNodeServer.Settings()),
        handlers(served)
      )
    yield Local(manager, governor)

  /** The peer at `remote` dials `us`, duplex when it serves, with `intersections` counting what our
    * side asks of its chain; `during` runs while the connection is up.
    */
  private def connect[A](us: Local, duplex: Boolean, intersections: Ref[IO, Int])(
      during: IO[ConnectionEnd] => IO[A]
  ): IO[(A, ConnectionEnd)] =
    Bearer.inMemory[IO](256).flatMap { (theirs, ours) =>
      us.governor.serve(ours, remote).start.flatMap { served =>
        val ended = served.joinWithNever.timeout(15.seconds)
        Handle
          .allow[NetworkError] {
            NodeToNode
              .warmOver[IO, NtNHeader](
                theirs,
                magic,
                NtNHeader.codec,
                NodeToNode.Settings(),
                Option.when(duplex)(handlers(intersections))
              )
              .use(_ => during(ended))
              .guarantee(theirs.close)
          }
          .rescue(e => IO(fail(e.message)))
          .flatMap(a => ended.map(a -> _))
      }
    }

  private def eventually[A](what: String)(read: IO[A])(ok: A => Boolean): IO[A] =
    read
      .flatMap(a => if ok(a) then IO.pure(a) else IO.sleep(20.millis) *> eventually(what)(read)(ok))
      .timeout(10.seconds)
      .adaptError(_ => new AssertionError(s"timed out: $what"))

  // the reference closes the connection for an exception in a bearer thread (`ShutdownPeer`,
  // issue 463): the cause is kept, the node goes on
  test("an exception of the inbound bearer's reader closes that connection, not the node") {
    val defect = new IllegalStateException("injected read defect")
    val bearer = new Bearer[IO]:
      def reads = fs2.Stream.raiseError[IO](defect)
      def write(bytes: fs2.Chunk[Byte]) = IO.unit
      def close = IO.unit
    for
      us <- local
      outcome <- us.governor.serve(bearer, remote).timeout(5.seconds)
      table <- us.manager.snapshot
    yield
      outcome match
        case ConnectionEnd.Failed(NetworkError.BearerDefect(cause)) => assert(cause eq defect)
        case other                                                  => fail(s"unexpected $other")
      assertEquals(table.counters, ConnectionState.Counters.zero)
  }

  test("a duplex peer that connects to us is served, and none of our clients runs on it") {
    for
      us <- local
      intersections <- Ref.of[IO, Int](0)
      outcome <- connect(us, duplex = true, intersections) { _ =>
        eventually("the connection is negotiated")(us.manager.snapshot)(
          _.connections.exists(_._2 match
            case ConnectionState.InboundIdle(DataFlow.Duplex) |
                ConnectionState.Inbound(DataFlow.Duplex) =>
              true
            case _ => false)
        ) <* IO.sleep(500.millis)
      }
      asked <- intersections.get
      after <- us.manager.snapshot
    yield
      assertEquals(outcome._1.counters.duplex, 1)
      assertEquals(outcome._1.counters.inbound, 1)
      assertEquals(outcome._1.counters.outbound, 0)
      assertEquals(asked, 0)
      assertEquals(after.counters, ConnectionState.Counters.zero)
  }

  test("a peer that only reads from us (initiator-only) is served") {
    for
      us <- local
      intersections <- Ref.of[IO, Int](0)
      outcome <- connect(us, duplex = false, intersections) { _ =>
        eventually("the connection is negotiated")(us.manager.snapshot)(
          _.connections.exists(_._2 match
            case ConnectionState.InboundIdle(DataFlow.Unidirectional) |
                ConnectionState.Inbound(DataFlow.Unidirectional) =>
              true
            case _ => false)
        )
      }
    yield assertEquals(outcome._1.counters.unidirectional, 1)
  }

  // `includeInboundConnectionImpl` (`ConnectionManager/Core.hs` 840-860)
  test("a second connection with the same identity is refused while the first is up") {
    for
      us <- local
      intersections <- Ref.of[IO, Int](0)
      outcome <- connect(us, duplex = false, intersections) { _ =>
        eventually("the first connection is admitted")(us.manager.snapshot)(
          _.counters.inbound == 1
        ) *> Bearer.inMemory[IO](64).flatMap((_, ours2) => us.governor.serve(ours2, remote))
      }
    yield outcome._1 match
      case ConnectionEnd.Refused(ConnectionError.ForbiddenOperation(address, _)) =>
        assertEquals(address, remote.toString)
      case other => fail(s"unexpected $other")
  }

  // an accepted connection and an outbound reservation coexist (`ConnMap.hs` 177-208): the
  // reservation is `UnknownLocalAddr` until it connects
  test("a reservation of ours does not refuse the peer's own connection") {
    for
      us <- local
      intersections <- Ref.of[IO, Int](0)
      outcome <- Handle
        .allow[ConnectionError](
          us.manager.acquire(remote, Provenance.Outbound).use { _ =>
            connect(us, duplex = false, intersections) { _ =>
              eventually("the inbound connection is established")(us.manager.snapshot)(
                _.counters.inbound == 1
              ).map(_.counters)
            }
          }
        )
        .rescue(e => IO(fail(e.message)))
    // a reservation counts in no direction (`connectionStateToCounters`)
    yield assertEquals(outcome._1.outbound -> outcome._1.inbound, (0, 1))
  }

  test("the connection JSON lists the table and its counters") {
    val json = InboundGovernor.json(
      ConnectionManager.Snapshot(
        ConnectionState.Counters.zero.copy(duplex = 1, inbound = 1),
        Vector(ConnectionManager.Listed(remote, ConnectionState.InboundIdle(DataFlow.Duplex), 7L))
      )
    )
    assertEquals(json.hcursor.downField("counters").get[Int]("inbound"), Right(1))
    assertEquals(
      json.hcursor.downField("connections").downArray.get[String]("state"),
      Right("InboundIdleSt Duplex")
    )
    assertEquals(json.hcursor.downField("connections").downArray.get[Long]("connection"), Right(7L))
    assert(json.hcursor.downField("inboundSessions").failed)
  }
