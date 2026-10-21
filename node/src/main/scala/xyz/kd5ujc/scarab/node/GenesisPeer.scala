// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain}
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Per-connection interpreter for the shared Genesis controls. It holds values and resource-owned
  * callbacks; transport operations continue to use the producing fiber's error capabilities.
  */
trait GenesisPeer[F[_]]:
  def restartCandidates: F[Vector[Point]]
  def failed[A](error: ChainSyncJumping.Error): F[A]
  def next: F[ChainSyncJumping.Instruction[GenesisPeer.Checkpoint]]
  def observed(gate: HeaderGate[F], event: SyncEvent): F[Unit]
  def processJump(
      info: ChainSyncJumping.JumpInfo[GenesisPeer.Checkpoint],
      goodPoint: Boolean,
      found: Option[Point],
      gate: HeaderGate[F]
  ): F[GenesisPeer.Applied]
  def restarted(point: Point, gate: HeaderGate[F]): F[Unit]

object GenesisPeer:
  final case class Checkpoint(
      history: HeaderChain[NodeHeaderState],
      selection: ChainSelection.Local,
      intersection: Point
  )

  enum Applied:
    case Retained, Restart
    case Grafted(candidate: ChainSelection.Candidate)
