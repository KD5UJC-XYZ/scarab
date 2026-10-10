// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Eq
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync

/** Identity of an applied block. */
final case class BlockRef(slot: BigInt, hash: Bytes, blockNo: BigInt):
  def point: ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.from(slot).toOption.get, hash)

object BlockRef:
  given Eq[BlockRef] = Eq.fromUniversalEquals

/** The immutable boundary of the volatile chain: the newest block older than `k` blocks (or
  * genesis), together with the state after it. Rolling back past the anchor is impossible.
  */
final case class Anchor[S](block: Option[BlockRef], state: S):
  def point: ChainSync.Point = block.fold(ChainSync.Point.Origin)(_.point)
  def blockNo: Option[BigInt] = block.map(_.blockNo)

/** The last `k` blocks, each paired with the state after applying it. Rollback restores the exact
  * state retained at the target, so state transitions never need inverse operations.
  */
final case class VolatileChain[S](k: Int, anchor: Anchor[S], blocks: Vector[(BlockRef, S)]):
  def tip: ChainSync.Point = blocks.lastOption.fold(anchor.point)(_._1.point)
  def tipRef: Option[BlockRef] = blocks.lastOption.map(_._1).orElse(anchor.block)
  def state: S = blocks.lastOption.fold(anchor.state)(_._2)
  def length: Int = blocks.size

  /** Append a block that extends the tip. Blocks older than `k` move into the anchor; the returned
    * vector holds blocks that became immutable, oldest first.
    */
  def extend(header: Header, next: S): Either[ChainError, (VolatileChain[S], Vector[BlockRef])] =
    val ref = BlockRef(header.slot, header.hash, header.blockNo)
    val connected = tipRef match
      case None => header.prevHash.isEmpty
      case Some(t) =>
        header.prevHash.contains(t.hash) && header.blockNo == t.blockNo + 1 && header.slot > t.slot
    if !connected then Left(ChainError.NotConnected(header.point, tip))
    else
      val grown = blocks :+ (ref -> next)
      val overflow = (grown.size - k).max(0)
      if overflow == 0 then Right(copy(blocks = grown) -> Vector.empty)
      else
        val (immutable, kept) = grown.splitAt(overflow)
        val (newest, newestState) = immutable.last
        Right(
          copy(anchor = Anchor(Some(newest), newestState), blocks = kept) -> immutable.map(_._1)
        )

  /** Roll back to `target`, which must be the anchor or a retained block. */
  def rollback(target: ChainSync.Point): Either[ChainError, VolatileChain[S]] =
    if target == anchor.point then Right(copy(blocks = Vector.empty))
    else
      val index = blocks.indexWhere(_._1.point == target)
      Either.cond(
        index >= 0,
        copy(blocks = blocks.take(index + 1)),
        ChainError.RollbackBeyondAnchor(target, anchor.point)
      )

  /** Intersection candidates, newest first: every retained block, then the anchor. */
  def candidates: Vector[ChainSync.Point] =
    blocks.reverseIterator.map(_._1.point).toVector :+ anchor.point

object VolatileChain:
  def genesis[S](k: Int, initial: S): VolatileChain[S] =
    VolatileChain(k, Anchor(None, initial), Vector.empty)

  given [S: Eq]: Eq[VolatileChain[S]] = Eq.instance { (a, b) =>
    a.k == b.k && a.anchor.block === b.anchor.block && a.anchor.state === b.anchor.state &&
    a.blocks.size == b.blocks.size && a.blocks.zip(b.blocks).forall { case ((r1, s1), (r2, s2)) =>
      r1 === r2 && s1 === s2
    }
  }
