// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, FollowerEvents}
import xyz.kd5ujc.scarab.ledger.PeerError

/** The report of a lost connection ([[OutboundPeers.Hot]]'s `lost`): each loss is numbered and
  * emitted once as [[FollowerEvent.Reconnecting]] to the sink it is given.
  *
  * The sink must be the one that feeds the metrics as well as the log (the events of the follower
  * alongside `EventMetrics.follower`): a log-only sink prints the reconnect and leaves
  * `scarab_peer_reconnects_total` at zero (issue 351).
  */
object Reconnects:
  def create[F[_]: Sync](events: FollowerEvents[F]): F[PeerError => F[Unit]] =
    Ref.of[F, Int](0).map { losses => error =>
      losses.updateAndGet(_ + 1).flatMap(n => events.emit(FollowerEvent.Reconnecting(error, n)))
    }
