// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.syntax.all.*
import xyz.kd5ujc.scarab.network.{ConnectionEnd, HandlerFailure, NetworkError}
import xyz.kd5ujc.scarab.node.log.NodeEvent
import xyz.kd5ujc.scarab.ledger.ChainError

/** Listener failures belong to the node when they are defects, never to a peer's reputation. */
object ListenerFailures:
  def report[F[_]: Monad](
      remote: String,
      failure: HandlerFailure[NetworkError],
      emit: NodeEvent => F[Unit],
      stop: Fatal => F[Unit]
  ): F[Unit] =
    val (end, fatal) = failure match
      case HandlerFailure.Domain(error: NetworkError.StateAcquireFailure) =>
        (
          InboundEnd.Connection(ConnectionEnd.fromNetwork(error)),
          Some(Fatal.Internal(ChainError.StateAcquire(error)))
        )
      case HandlerFailure.Domain(NetworkError.Defect(cause)) =>
        (InboundEnd.Connection(ConnectionEnd.Defect(cause)), Some(Fatal.Defect(cause)))
      case HandlerFailure.Domain(error) =>
        (InboundEnd.Connection(ConnectionEnd.fromNetwork(error)), None)
      case HandlerFailure.Defect(cause) =>
        (InboundEnd.Defect(cause), Some(Fatal.Defect(cause)))
    fatal.traverse_(stop) *> emit(NodeEvent.Inbound(remote, InboundEvent.Closed(end)))
