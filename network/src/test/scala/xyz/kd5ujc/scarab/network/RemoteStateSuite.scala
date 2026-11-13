// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.util.concurrent.TimeoutException
import xyz.kd5ujc.scarab.protocol.Handshake
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance
import scala.concurrent.duration.*

import NodeToNodeServer.{Activity, Responders}
import ProtocolResults.Temperature

/** The inbound governor's remote state of a connection and its counters (ouroboros-network
  * `2fb69829`): what the mux tracer writes on a responder's start and clean end, by the protocol's
  * temperature (`inboundGovernorMuxTracer`, `InboundGovernor.hs` 629-708), the states the
  * governor's step moves the connection through (`RemoteIdle`, `RemoteWarm`, `RemoteHot`,
  * `RemoteCold`, 463-570) and their `Counters` (`InboundGovernor/State.hs` 113-145). Every test
  * runs under `TestControl`: the clock moves only once every fiber waits, so a wait that times out
  * shows that nothing more was going to happen.
  */
class RemoteStateSuite extends FailsOnUncaught:
  private val remote: SocketAddress[IpAddress] = SocketAddress(ip"10.0.0.1", port"3001")

  /** A mux that carries nothing. */
  private final class Stub(done: Deferred[IO, NetworkError]) extends Mux[IO]:
    def awaitResponderActivity(using Raise[IO, NetworkError]) = IO.never
    def installApplication(initiators: Set[Int], responders: Set[Int])(using
        Raise[IO, NetworkError]
    ) = IO.unit
    def channel(protocol: Int)(using Raise[IO, NetworkError]) =
      NetworkError.Timeout(protocol, "stub").raise[IO, MuxChannel[IO]]
    def channel(protocol: Int, role: Mux.Role)(using Raise[IO, NetworkError]) = channel(protocol)
    def closed = done.get
    def failure = done.tryGet
    def abort(reason: NetworkError) = done.complete(reason).void

  private def agreed(mux: Mux[IO]): Negotiated[IO] =
    Negotiated(mux, HandshakeClient.Agreed(14, Handshake.Data(42L, initiatorOnly = false)))

  /** A connection whose remote states are written to `log` too, in order. */
  private final class Logged(c: Connection[IO], log: Ref[IO, Vector[RemoteSt]])
      extends Connection[IO]:
    export c.{
      remote,
      id,
      provenance,
      state,
      connected,
      negotiated,
      handshakeFailed,
      promotedToWarmRemote,
      demotedToColdRemote,
      releaseInbound,
      releaseOutbound,
      awaitOutboundDemotion,
      stopped,
      close,
      closed,
      unregisterRemote
    }
    def updateRemoteState(state: RemoteSt) =
      log.update(_ :+ state) *> c.updateRemoteState(state)

  /** The activities waiting, `n` of them, in order. */
  private def taken(responders: Responders[IO], n: Int): IO[Vector[Activity]] =
    responders.next.replicateA(n).map(_.toVector)

  /** Whether another activity waits: none if the wait times out once every fiber is idle. */
  private def nothingMore(responders: Responders[IO]): IO[Boolean] =
    responders.next.timeout(1.hour).attempt.map {
      case Left(_: TimeoutException) => true
      case _                         => false
    }

  // ------------------------------------------------------------------ the mux tracer's messages

  test(
    "a hot responder alone: awake and promoted to hot as it starts, demoted and idle as it ends"
  ) {
    TestControl.executeEmbed(
      for
        responders <- Responders.create[IO]
        _ <- responders.running(Temperature.Hot)(IO.unit)
        seen <- taken(responders, 4)
        more <- nothingMore(responders)
      yield
        assertEquals(
          seen,
          Vector(Activity.Awake, Activity.PromotedToHot, Activity.DemotedToWarm, Activity.WaitIdle)
        )
        assert(more, "nothing more waits")
    )
  }

  test("an established responder alone: awake as it starts, idle as it ends, never hot") {
    TestControl.executeEmbed(
      for
        responders <- Responders.create[IO]
        _ <- responders.running(Temperature.Established)(IO.unit)
        seen <- taken(responders, 2)
        more <- nothingMore(responders)
      yield
        assertEquals(seen, Vector(Activity.Awake, Activity.WaitIdle))
        assert(more, "nothing more waits")
    )
  }

  test(
    "only the first hot start promotes, only the last hot end demotes, only the last end idles"
  ) {
    TestControl.executeEmbed(
      for
        responders <- Responders.create[IO]
        // KeepAlive, then ChainSync and BlockFetch inside it, which end first
        _ <- responders.running(Temperature.Established)(
          responders.running(Temperature.Hot)(responders.running(Temperature.Hot)(IO.unit))
        )
        seen <- taken(responders, 4)
        more <- nothingMore(responders)
        // a hot one that outlives the established one inside it
        _ <- responders.running(Temperature.Hot)(
          responders.running(Temperature.Established)(IO.unit)
        )
        again <- taken(responders, 4)
        moreAgain <- nothingMore(responders)
      yield
        val cycle =
          Vector(Activity.Awake, Activity.PromotedToHot, Activity.DemotedToWarm, Activity.WaitIdle)
        assertEquals(seen, cycle)
        assert(more, "nothing more waits")
        assertEquals(again, cycle)
        assert(moreAgain, "nothing more waits")
    )
  }

  test("a responder that fails stops the counting: nothing more is signalled on the connection") {
    TestControl.executeEmbed(
      for
        responders <- Responders.create[IO]
        failed <- responders
          .running(Temperature.Hot)(
            IO.raiseError[Unit](new IllegalStateException("protocol error"))
          )
          .attempt
        seen <- taken(responders, 2)
        // a later responder's start and end are not counted any more
        _ <- responders.running(Temperature.Established)(IO.unit)
        more <- nothingMore(responders)
      yield
        assert(failed.isLeft)
        assertEquals(seen, Vector(Activity.Awake, Activity.PromotedToHot))
        assert(more, "nothing more waits")
    )
  }

  test("a responder that is cancelled stops the counting too") {
    TestControl.executeEmbed(
      for
        responders <- Responders.create[IO]
        fiber <- responders.running(Temperature.Hot)(IO.never[Unit]).start
        seen <- taken(responders, 2)
        _ <- fiber.cancel
        _ <- responders.running(Temperature.Hot)(IO.unit)
        more <- nothingMore(responders)
      yield
        assertEquals(seen, Vector(Activity.Awake, Activity.PromotedToHot))
        assert(more, "nothing more waits")
    )
  }

  // ------------------------------------------------------------------ the remote states

  /** An accepted duplex connection; with `used`, our outbound side uses it too. */
  private def accepted(cm: ConnectionManager[IO], mux: Mux[IO], used: Boolean)(using
      Raise[IO, ConnectionError]
  ): Resource[IO, Connection[IO]] =
    for
      connection <- cm.include(remote, None)
      _ <- Resource.eval(connection.negotiated(agreed(mux)))
      _ <- if used then cm.acquire(remote, Provenance.Outbound).void else Resource.unit[IO]
    yield connection

  /** A duplex connection we dialled and use. */
  private def dialled(cm: ConnectionManager[IO], mux: Mux[IO])(using
      Raise[IO, ConnectionError]
  ): Resource[IO, Connection[IO]] =
    cm.acquire(remote, Provenance.Outbound).evalMap {
      case Acquired.Reserved(connection) =>
        connection.connected(None) *> connection.negotiated(agreed(mux)).as(connection)
      case other => IO.raiseError(new AssertionError(s"not a reservation: $other"))
    }

  /** A responder of `temperature` the test lets end: start it, once it is counted, and the action
    * that ends it cleanly.
    */
  private def responder(responders: Responders[IO], temperature: Temperature): IO[IO[Unit]] =
    (Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap { (counted, gate) =>
      responders
        .running(temperature)(counted.complete(()) *> gate.get)
        .start
        .flatMap(fiber => counted.get.as(gate.complete(()) *> fiber.joinWithUnit))
    }

  /** The tracker over the connection `open` makes, run with `steps` (given the manager, the
    * connection's identity and the responders) while it lasts; the remote states written, in order.
    */
  private def tracked(
      open: Handle[IO, ConnectionError] ?=> (
          ConnectionManager[IO],
          Mux[IO]
      ) => Resource[IO, Connection[IO]]
  )(steps: (ConnectionManager[IO], Long, Responders[IO]) => IO[Unit]): IO[Vector[RemoteSt]] =
    TestControl.executeEmbed(
      for
        log <- Ref.of[IO, Vector[RemoteSt]](Vector.empty)
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits())
        done <- Deferred[IO, NetworkError]
        mux = Stub(done)
        responders <- Responders.create[IO]
        _ <- Handle
          .allow[ConnectionError](
            open(cm, mux).use { owned =>
              val logged = Logged(owned, log)
              NodeToNodeServer
                .remoteActivity(mux, responders, 5.seconds, logged)
                .use(_ => steps(cm, owned.id, responders))
            }
          )
          .rescue(e => IO.raiseError[Unit](new AssertionError(e.message)))
        written <- log.get
      yield written
    )

  private def reaches(cm: ConnectionManager[IO], id: Long, state: RemoteSt): IO[Unit] =
    cm.awaitRemoteStates(_.get(id).contains(state))

  test(
    "remote states: idle, warm on the first responder, hot on the first hot one, warm when the " +
      "hot ones end, idle when the last ends; a commit leaves it untracked"
  ) {
    tracked((cm, mux) => accepted(cm, mux, used = false)) { (cm, id, responders) =>
      for
        registered <- cm.remoteStates
        keepAlive <- responder(responders, Temperature.Established)
        _ <- reaches(cm, id, RemoteSt.Warm)
        chainSync <- responder(responders, Temperature.Hot)
        _ <- reaches(cm, id, RemoteSt.Hot)
        hot <- cm.remoteStates.map(s => InboundGovernorCounters.of(s.values))
        blockFetch <- responder(responders, Temperature.Hot)
        _ <- chainSync
        _ <- blockFetch
        _ <- reaches(cm, id, RemoteSt.Warm)
        _ <- keepAlive
        _ <- reaches(cm, id, RemoteSt.Idle)
        // the idle timer expires and the manager closes the connection: it is tracked no more
        _ <- cm.awaitRemoteStates(!_.contains(id))
      yield
        assertEquals(registered, Map(id -> RemoteSt.Idle))
        assertEquals(hot, InboundGovernorCounters(cold = 0, idle = 0, warm = 0, hot = 1))
    }.map(written =>
      assertEquals(
        written,
        Vector(RemoteSt.Idle, RemoteSt.Warm, RemoteSt.Hot, RemoteSt.Warm, RemoteSt.Idle)
      )
    )
  }

  test("remote states: an expiry our outbound side outlives is cold, and the peer wakes it warm") {
    tracked((cm, mux) => accepted(cm, mux, used = true)) { (cm, id, responders) =>
      for
        _ <- reaches(cm, id, RemoteSt.Cold)
        cold <- cm.remoteStates.map(s => InboundGovernorCounters.of(s.values))
        keepAlive <- responder(responders, Temperature.Established)
        _ <- reaches(cm, id, RemoteSt.Warm)
        _ <- keepAlive
        _ <- reaches(cm, id, RemoteSt.Idle)
      yield assertEquals(cold, InboundGovernorCounters(cold = 1, idle = 0, warm = 0, hot = 0))
    }.map(written =>
      assertEquals(written, Vector(RemoteSt.Idle, RemoteSt.Cold, RemoteSt.Warm, RemoteSt.Idle))
    )
  }

  test("remote states: on a connection we dialled too, and untracked when the connection ends") {
    TestControl.executeEmbed(
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits())
        done <- Deferred[IO, NetworkError]
        mux = Stub(done)
        responders <- Responders.create[IO]
        during <- Handle
          .allow[ConnectionError](
            dialled(cm, mux).use { owned =>
              NodeToNodeServer.remoteActivity(mux, responders, 5.seconds, owned).use { _ =>
                responder(responders, Temperature.Hot)
                  .flatMap(end => reaches(cm, owned.id, RemoteSt.Hot) *> cm.remoteStates <* end)
              }
            }
          )
          .rescue(e => IO.raiseError[Map[Long, RemoteSt]](new AssertionError(e.message)))
        after <- cm.remoteStates
      yield
        assertEquals(during.values.toList, List(RemoteSt.Hot))
        assertEquals(after, Map.empty[Long, RemoteSt])
    )
  }

  test(
    "remote states: a responder that fails leaves the state as it was until the connection ends"
  ) {
    tracked((cm, mux) => accepted(cm, mux, used = false)) { (cm, id, responders) =>
      for
        failed <- responders
          .running(Temperature.Hot)(
            IO.raiseError[Unit](new IllegalStateException("protocol error"))
          )
          .attempt
        _ <- reaches(cm, id, RemoteSt.Hot)
        // the remote state moves no more, whatever the peer starts: the wait times out
        keepAlive <- responder(responders, Temperature.Established)
        _ <- keepAlive
        moved <- cm
          .awaitRemoteStates(_.get(id) != Some(RemoteSt.Hot))
          .timeout(1.hour)
          .attempt
      yield
        assert(failed.isLeft)
        assert(moved.left.exists(_.isInstanceOf[TimeoutException]), moved.toString)
    }.map(written => assertEquals(written, Vector(RemoteSt.Idle, RemoteSt.Warm, RemoteSt.Hot)))
  }

  // ------------------------------------------------------------------ the counters

  test("the counters count each tracked connection in its remote state (`counters`)") {
    val states = Vector.fill(1)(RemoteSt.Cold) ++ Vector.fill(2)(RemoteSt.Idle) ++
      Vector.fill(3)(RemoteSt.Warm) ++ Vector.fill(4)(RemoteSt.Hot)
    assertEquals(
      InboundGovernorCounters.of(states),
      InboundGovernorCounters(cold = 1, idle = 2, warm = 3, hot = 4)
    )
    assertEquals(InboundGovernorCounters.of(Nil), InboundGovernorCounters.empty)
  }
