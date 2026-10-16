// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BlockRules, NodeState}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.Header

/** [[DifferentialRules]] as the node's own block rules (`--rules differential`): the chain, the
  * follower, the storage and everything that reads it stay over `NodeState`, and the new side rides
  * beside it, keyed by block.
  *
  * Design §2.5 has the node hold `VolatileChain[Paired]`. That retypes every consumer of the chain
  * (the forger, the mempool, the node-to-client query source, the storage, the header gate, the
  * relay) for a debugging harness that is deleted at J2.9. The new side's state after a block is
  * all the pair adds, and it is a function of the block it follows, so it is kept here by block
  * hash: the pair for a block is found by the hash its header names as its parent, a rollback and a
  * fork need nothing (the block they continue from is found, or the new side is seeded again), and
  * what is older than the volatile window is forgotten.
  */
object Shadowed:
  private val Sweep = 256L

  /** What the harness remembers of a block: where it is, and the new side's state after it. */
  private final case class Entry(at: BlockRef, neu: Option[MirrorState])

  /** The block rules of the node over the harness `harness`; `window` is the number of blocks (the
    * security parameter, twice) the new side's states are kept for.
    */
  def apply[F[_]](harness: DifferentialRules[F], window: Long)(using
      F: Sync[F]
  ): F[BlockRules[F, NodeState]] =
    Ref.of[F, Map[Bytes, Entry]](Map.empty).map { known =>
      new BlockRules[F, NodeState]:
        def apply(state: NodeState, header: Header, raw: Bytes): F[NodeState] =
          for
            parent <- header.prevHash.flatTraverse(h => known.get.map(_.get(h)))
            paired = Paired(state, parent.flatMap(_.neu), parent.map(_.at))
            next <- harness(paired, header, raw)
            at = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
            // forgetting walks the map, so it is done every so many blocks, not for each
            _ <- known.update { m =>
              val added = m.updated(header.hash, Entry(at, next.neu))
              if at.blockNo % Sweep != 0 then added
              else added.filter((_, e) => e.at.blockNo + window >= at.blockNo)
            }
          yield next.old
    }
