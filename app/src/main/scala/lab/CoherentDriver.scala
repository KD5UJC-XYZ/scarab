// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.network.ChainSync

/** The mutation surface used by the ephemeral driver. An admission owner can fence this surface
  * without exposing its underlying runtime. Preparation is immutable and remains outside its gate.
  */
private[lab] trait CoherentDriver[F[_]]:
  import CoherentSequence.*
  def maxBlocks: Int
  def snapshot: F[Snapshot]
  def prepare(block: SequenceInput.Block): F[Result[Candidate]]
  private[lab] def prepareSyntheticBlock(
      fence: Fence,
      block: SequenceInput.Block
  ): F[Result[Candidate]]
  def prepareSyntheticSuccessor(
      fence: Fence,
      headerHash: Bytes,
      slot: BigInt
  ): F[Result[SyntheticSuccessor]]
  private[lab] def prepareSyntheticSuccessorBlock(
      fence: Fence,
      preview: SyntheticSuccessor,
      block: SequenceInput.Block
  ): F[Result[Candidate]]
  def publish(candidate: Candidate): F[Result[Applied]]
  def rollbackTo(fence: Fence, target: ChainSync.Point): F[Result[Snapshot]]
  def advanceAnchor(fence: Fence, through: ChainSync.Point): F[Result[Snapshot]]
