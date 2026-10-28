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

  /** A node-to-client connection that failed closes alone, whatever the failure: the reference's
    * policy for local connections is the diffusion's `rethrowPolicy <> mempty` (ouroboros-network
    * 2fb69829, `ouroboros-network/lib/Ouroboros/Network/Diffusion.hs` 286-322 and 355;
    * ouroboros-consensus 82ecba32, `Ouroboros/Consensus/Node.hs` 812-813), whose only
    * `ShutdownNode` is an `IOManagerError`, which only Windows raises.
    */
  def reportLocal[F[_]](
      remote: String,
      failure: HandlerFailure[NetworkError],
      emit: NodeEvent => F[Unit]
  ): F[Unit] =
    val end = failure match
      case HandlerFailure.Domain(NetworkError.Defect(cause)) =>
        InboundEnd.Connection(ConnectionEnd.Defect(cause))
      case HandlerFailure.Domain(error) => InboundEnd.Connection(ConnectionEnd.fromNetwork(error))
      case HandlerFailure.Defect(cause) => InboundEnd.Defect(cause)
    emit(NodeEvent.Inbound(remote, InboundEvent.Closed(end)))
