// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain}
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Per-connection interpreter for the shared Genesis controls. It holds values and resource-owned
  * callbacks; transport operations continue to use the producing fiber's error capabilities. The
  * session's header state is the session's own: it hands its gate to every call that reads or
  * changes that state, as the reference's ChainSync client updates the jump information and applies
  * a jump to its own `KnownIntersectionState` (consensus 82ecba329 ChainSync/Client.hs:1254–1272,
  * 1307–1380).
  */
trait GenesisPeer[F[_]]:
  def restartCandidates: F[Vector[Point]]
  def failed[A](error: ChainSyncJumping.Error): F[A]
  def next: F[ChainSyncJumping.Instruction[GenesisPeer.Checkpoint]]
  def nextAfterReply: F[ChainSyncJumping.Instruction[GenesisPeer.Checkpoint]] = next

  /** The session's gate accepted `event`. */
  def observed(gate: HeaderGate.Grafting[F], event: SyncEvent): F[Unit]

  /** Apply a jump the peer answered (`found`) to the session's gate. */
  def processJump(
      gate: HeaderGate.Grafting[F],
      info: ChainSyncJumping.JumpInfo[GenesisPeer.Checkpoint],
      goodPoint: Boolean,
      found: Option[Point]
  ): F[GenesisPeer.Applied]

  /** The session found a new intersection at `point`, with `gate` started there. */
  def restarted(gate: HeaderGate.Grafting[F], point: Point): F[Unit]

object GenesisPeer:
  final case class Checkpoint(
      history: HeaderChain[NodeHeaderState],
      selection: ChainSelection.Local,
      intersection: Point
  )

  enum Applied:
    case Retained, Restart
    case Grafted(candidate: ChainSelection.Candidate)
