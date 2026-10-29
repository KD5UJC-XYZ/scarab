// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockNoVerdict
import xyz.kd5ujc.scarab.ledger.Header

/** The blocks the node has no verdict on, so that chain selection does not judge them again on
  * every delivery (issue 567). Unlike [[InvalidBlocks]] no peer is blamed for them: a ChainSync
  * client does not punish a peer that announces one. The set lives in memory only, so a restart (a
  * fixed build) judges such a block again, and it is pruned at the immutable tip as the invalid set
  * is.
  */
final class NoVerdictBlocks[F[_]: Async] private (
    state: Ref[F, Map[Bytes, NoVerdictBlocks.Withheld]]
):
  def record(header: Header, cause: BlockNoVerdict): F[Unit] =
    state.update(_.updated(header.hash, NoVerdictBlocks.Withheld(header.slot, cause)))

  def hashes: F[Set[Bytes]] = state.get.map(_.keySet)

  def lookup(hash: Bytes): F[Option[BlockNoVerdict]] = state.get.map(_.get(hash).map(_.cause))

  def pruneBefore(immutableSlot: BigInt): F[Unit] =
    state.update(_.filter((_, withheld) => withheld.slot >= immutableSlot))

object NoVerdictBlocks:
  private final case class Withheld(slot: BigInt, cause: BlockNoVerdict)

  def create[F[_]: Async]: F[NoVerdictBlocks[F]] =
    Ref.of[F, Map[Bytes, Withheld]](Map.empty).map(new NoVerdictBlocks(_))
