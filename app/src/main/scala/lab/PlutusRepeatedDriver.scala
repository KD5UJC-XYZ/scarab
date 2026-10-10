// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import lab.cbor.Bytes
import lab.network.ChainSync

/** Native-checked preparation stays outside the admission publication gate. Every mutation still
  * passes through the same admission owner, updating pins and notifying the pending pool.
  */
private[lab] object PlutusRepeatedDriver:
  def apply(
      runtime: CoherentSequence.Runtime[IO],
      owner: SubmissionOwner[IO],
      oracle: NativeLikelihoodOracle.Oracle[IO]
  ): CoherentDriver[IO] = new CoherentDriver[IO]:
    import CoherentSequence.*
    val maxBlocks = owner.maxBlocks
    def snapshot = owner.snapshot
    def prepare(block: SequenceInput.Block) =
      snapshot.flatMap(s => runtime.prepareRepeatedBlock(s.fence, block, None, oracle.generate))
    private[lab] def prepareSyntheticBlock(fence: Fence, block: SequenceInput.Block) =
      runtime.prepareRepeatedBlock(fence, block, None, oracle.generate)
    def prepareSyntheticSuccessor(fence: Fence, headerHash: Bytes, slot: BigInt) =
      owner.prepareSyntheticSuccessor(fence, headerHash, slot)
    private[lab] def prepareSyntheticSuccessorBlock(
        fence: Fence,
        preview: SyntheticSuccessor,
        block: SequenceInput.Block
    ) = runtime.prepareRepeatedBlock(fence, block, Some(preview), oracle.generate)
    def publish(candidate: Candidate) = owner.publish(candidate)
    def rollbackTo(fence: Fence, target: ChainSync.Point) = owner.rollbackTo(fence, target)
    def advanceAnchor(fence: Fence, through: ChainSync.Point) = owner.advanceAnchor(fence, through)
