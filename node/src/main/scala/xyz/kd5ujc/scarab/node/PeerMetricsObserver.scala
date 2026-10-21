// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.mtl.Stateful
import cats.syntax.all.*
import xyz.kd5ujc.scarab.network.selection.PeerMetrics

/** Attribute every client connection's arrivals to the shared peer-selection metrics board.
  * Connection direction does not change the reference's ChainSync/BlockFetch metric tracers:
  * consensus 82ecba329, Network/NodeToNode.hs:795–921; network 2fb69829,
  * PeerSelection/PeerMetric.hs:242–254 projects the connection's remote address for both boards.
  */
private[node] object PeerMetricsObserver:
  def apply[F[_]: Monad, A](peer: A, observe: Option[Peer.Observation => F[Unit]])(using
      metrics: Stateful[F, PeerMetrics[A]]
  ): Peer.Observation => F[Unit] = {
    case event @ Peer.Observation.HeadersArrived(slots, at) =>
      observe.traverse_(_(event)) *> metrics.modify(_.reportHeaders(peer, slots, at))
    case event @ Peer.Observation.BlocksArrived(blocks, at) =>
      observe.traverse_(_(event)) *> metrics.modify(_.reportFetches(peer, blocks, at))
    case event => observe.traverse_(_(event))
  }
