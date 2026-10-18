// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, PeerSharingAddress}
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.PeerSharing

class PeerSharingRuntimeSuite extends FailsOnUncaught:
  private def ip(n: Int) = SocketAddress(
    IpAddress.fromString(s"127.0.0.$n").getOrElse(fail("ip")),
    Port.fromInt(3000 + n).getOrElse(fail("port"))
  )
  private def peer(n: Int): PeerNetwork.Addr = SocketAddress[Host](ip(n).host, ip(n).port)
  private def record(advertise: Boolean = true, ever: Boolean = true, failures: Int = 0) =
    PeerRecord
      .cold(Origin.Other)
      .copy(advertise = Some(advertise), everConnected = ever, failCount = failures)
  private def state = GovernorState
    .initial[PeerNetwork.Addr](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))

  test("published answers are exactly advertised successful zero-failure peers, bounded10") {
    for
      runtime <- PeerSharingRuntime.create[IO](42L)
      initial <- runtime.source(PeerSharing.Amount.saturating(255))
      _ = assertEquals(initial.size, 0)
      peers = SortedMap
        .from((1 to 20).map(n => peer(n) -> record()))
        .updated(peer(21), record(advertise = false))
        .updated(peer(22), record(ever = false))
        .updated(peer(23), record(failures = 1))
      _ <- runtime.publish(state.copy(peers = peers))
      answer <- runtime.source(PeerSharing.Amount.saturating(255))
      _ = assertEquals(answer.size, 10)
      _ = assert(
        answer.flatMap(PeerSharingAddress.socket).forall(a => (1 to 20).exists(n => ip(n) == a))
      )
      one <- runtime.source(PeerSharing.Amount.saturating(1))
      _ = assertEquals(one.size, 1)
      _ <- runtime.publish(state)
      cleared <- runtime.source(PeerSharing.Amount.saturating(10))
    yield assertEquals(cleared.size, 0)
  }

  test("a successfully resolved DNS root advertises its actual IP, not a guessed address") {
    val dns =
      SocketAddress[Host](Host.fromString("relay.example").getOrElse(fail("host")), ip(1).port)
    val st = state.copy(peers = SortedMap(dns -> record()))
    for
      runtime <- PeerSharingRuntime.create[IO](3L)
      _ <- runtime.publish(st)
      before <- runtime.source(PeerSharing.Amount.saturating(10))
      _ = assertEquals(before.size, 0)
      _ <- runtime.connected(dns, ip(1))
      _ <- runtime.publish(st)
      after <- runtime.source(PeerSharing.Amount.saturating(10))
    yield assertEquals(after.flatMap(PeerSharingAddress.socket), Vector(ip(1)))
  }

  test(
    "inbound negotiated flags live exactly as long as their resource and changes wake discovery"
  ) {
    for
      runtime <- PeerSharingRuntime.create[IO](3L)
      during <- runtime.accepted(ip(1), sharing = true).use { _ =>
        runtime.changed *> runtime.inbound
      }
      _ = assertEquals(during, Map(peer(1) -> true))
      after <- runtime.changed *> runtime.inbound
    yield assertEquals(after, Map.empty[PeerNetwork.Addr, Boolean])
  }

  test("relay/producer defaults and explicit overrides follow POM") {
    IO {
      assert(ConfigSettings().peerSharingEnabled(producer = false))
      assert(!ConfigSettings().peerSharingEnabled(producer = true))
      assert(ConfigSettings(peerSharing = Some(true)).peerSharingEnabled(producer = true))
      assert(!ConfigSettings(peerSharing = Some(false)).peerSharingEnabled(producer = false))
      assert(
        !ConfigSettings(peerSharing = Some(true)).unsupported.exists(_.startsWith("PeerSharing:"))
      )
    }
  }
