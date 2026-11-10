// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, FiberIO, IO, Ref}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.protocol.{ConnectionState, Handshake}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{Provenance, TimeoutExpired}
import scala.concurrent.duration.*

/** The tracker of the peer's activity ([[NodeToNodeServer.remoteActivity]]) at the instant its
  * idle timer fires, with that instant under the test's control: the reference's inbound governor
  * reads its channel and its timers in one STM transaction (ouroboros-network `2fb69829`,
  * `InboundGovernor.hs` 240-275), commits an expired timer before the waiting messages (253-261)
  * and holds a responder that starts on an expired timer until the commit is settled (665-672).
  */
class RemoteActivitySuite extends FailsOnUncaught:
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

  // a responder starts as the timer fires, after it slept and before it returns; the tracker's
  // wait must neither lose that start nor take it before the commit
  test("a responder that starts as the idle timer fires is held for the commit, then wakes the connection") {
    TestControl.executeEmbed(
      for
        log <- Ref.of[IO, Vector[(ConnectionState, ConnectionState)]](Vector.empty)
        cm <- ConnectionManager.create[IO](
          ConnectionManager.Limits(),
          Some {
            case ConnectionEvent.Changed(_, from, to) => log.update(_ :+ (from -> to))
            case _                                    => IO.unit
          }
        )
        done <- Deferred[IO, NetworkError]
        mux = Stub(done)
        owned <- Handle
          .allow[ConnectionError](
            for
              (connection, _) <- cm.include(remote, None).allocated
              _ <- connection.negotiated(
                Negotiated(mux, HandshakeClient.Agreed(14, Handshake.Data(42L, initiatorOnly = false)))
              )
              // our outbound side uses it, so the expiry keeps it
              _ <- cm.acquire(remote, Provenance.Outbound).allocated
            yield connection
          )
          .rescue(e => IO.raiseError(new AssertionError(e.message)))
        responders <- NodeToNodeServer.Responders.create[IO]
        finish <- Deferred[IO, Unit]
        running <- Deferred[IO, FiberIO[Unit]]
        timer = (left: FiniteDuration) =>
          IO.sleep(left) *> responders.running(finish.get).start.flatMap(running.complete) *> IO.cede
        after <- NodeToNodeServer
          .remoteActivity(mux, responders, 5.seconds, owned, Some(timer))
          .use { _ =>
            IO.sleep(6.seconds) *> finish.complete(()) *> IO.sleep(1.second) *> owned.state
          }
        changes <- log.get
      yield (after, changes)
    ).map { (after, changes) =>
      val expiring =
        ConnectionState.OutboundDup(TimeoutExpired.Ticking) ->
          ConnectionState.OutboundDup(TimeoutExpired.Expired)
      assertEquals(
        changes.dropWhile(_ != expiring),
        Vector(
          expiring,
          ConnectionState.OutboundDup(TimeoutExpired.Expired) -> ConnectionState.Duplex,
          ConnectionState.Duplex -> ConnectionState.OutboundDup(TimeoutExpired.Ticking)
        )
      )
      assertEquals(after, ConnectionState.OutboundDup(TimeoutExpired.Ticking))
    }
  }
