// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.Stateful
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.PeerMetrics

/** Both client directions use the reference's remote-address-keyed earliest-arrival board. */
class PeerMetricsObserverSuite extends FailsOnUncaught:
  test("shared arrivals retain the earliest peer and forward every observation exactly once") {
    for
      board <- Ref.of[IO, PeerMetrics[String]](PeerMetrics.empty())
      given Stateful[IO, PeerMetrics[String]] = RefStateful(board)
      received <- Ref.of[IO, Vector[Peer.Observation]](Vector.empty)
      downstream = Some((event: Peer.Observation) => received.update(_ :+ event))
      inbound = PeerMetricsObserver[IO, String]("inbound", downstream)
      outbound = PeerMetricsObserver[IO, String]("outbound", downstream)
      events = Vector(
        Peer.Observation.HeadersArrived(Vector(1L, 2L), 2.seconds),
        Peer.Observation.HeadersArrived(Vector(1L), 1.second),
        Peer.Observation.HeadersArrived(Vector(2L), 2.seconds),
        Peer.Observation.BlocksArrived(Vector(1L -> 20L, 2L -> 30L), 2.seconds),
        Peer.Observation.BlocksArrived(Vector(1L -> 20L), 1.second),
        Peer.Observation.BlocksArrived(Vector(2L -> 30L), 2.seconds),
        Peer.Observation.HeaderReceived
      )
      _ <- Vector(inbound, outbound, outbound, inbound, outbound, outbound, inbound)
        .zip(events)
        .traverse_((observe, event) => observe(event))
      result <- board.get
      forwarded <- received.get
    yield
      assertEquals(result.upstreamness, Map("inbound" -> 1, "outbound" -> 1))
      assertEquals(result.fetchynessBlocks, Map("inbound" -> 1, "outbound" -> 1))
      assertEquals(result.fetchynessBytes, Map("inbound" -> 30L, "outbound" -> 20L))
      assertEquals(forwarded, events)
  }

  test("concurrent inbound and outbound reports update one board without losing contributions") {
    for
      board <- Ref.of[IO, PeerMetrics[String]](PeerMetrics.empty())
      given Stateful[IO, PeerMetrics[String]] = RefStateful(board)
      inbound = PeerMetricsObserver[IO, String]("inbound", None)
      outbound = PeerMetricsObserver[IO, String]("outbound", None)
      _ <- (
        inbound(Peer.Observation.HeadersArrived(Vector(1L), 1.second)),
        outbound(Peer.Observation.HeadersArrived(Vector(2L), 1.second)),
        inbound(Peer.Observation.BlocksArrived(Vector(1L -> 20L), 1.second)),
        outbound(Peer.Observation.BlocksArrived(Vector(2L -> 30L), 1.second))
      ).parTupled
      result <- board.get
    yield
      assertEquals(result.sizes, (2, 2))
      assertEquals(result.hotScores.keySet, Set("inbound", "outbound"))
      assertEquals(result.upstreamness, Map("inbound" -> 1, "outbound" -> 1))
      assertEquals(result.fetchynessBlocks, Map("inbound" -> 1, "outbound" -> 1))
  }
