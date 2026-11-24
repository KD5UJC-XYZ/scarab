// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import scala.collection.immutable.HashMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** A fragment of a chain anchored somewhere in it: the anchor, the point just before the oldest
  * block and not itself a block of the fragment, and the blocks after it, oldest first
  * (`AnchoredFragment`, ouroboros-network c45735a56c567fa977969173d18943bac6bb3821
  * `ouroboros-network/api/lib/Ouroboros/Network/AnchoredFragment.hs` 206-230).
  *
  * The reference keeps the blocks in a finger tree measured by slot. Here they are a `Vector`, with
  * a persistent map from each hash on the fragment (the anchor's among them) to its position, and
  * the anchor's position. Adding a block adds one key; dropping `d` blocks from either end removes
  * only their `d` keys. Along one line of updates each block's key is added once and removed at
  * most once, so adding, rolling back and dropping the oldest cost amortised O(1) per block, and a
  * point is looked up in O(1) (a hash lookup), whatever the fragment's length. Every structure is
  * immutable: a fragment is shared with other fibers as it is.
  *
  * A chain holds a block once, so the points of a fragment are distinct; its lookups rely on that.
  *
  * @param anchorAt
  *   the position of the anchor; the block at index `i` is at `anchorAt + 1 + i`
  */
final class AnchoredFragment[A] private (
    val anchorPoint: Point,
    blocks: Vector[A],
    anchorAt: Long,
    positions: HashMap[Bytes, Long]
)(using pointOf: AnchoredFragment.HasPoint[A]):

  /** The number of blocks, the anchor not counted (`length`, `AnchoredSeq.hs` 372-373). */
  def length: Int = blocks.size

  /** No block after the anchor (`null`, `AnchoredSeq.hs` 376-377). */
  def isEmpty: Boolean = blocks.isEmpty

  /** The blocks, oldest first (`toOldestFirst`, `AnchoredSeq.hs` 265-266). */
  def toOldestFirst: Vector[A] = blocks

  /** The newest block's point, the anchor's when there is no block (`headPoint`,
    * `AnchoredFragment.hs` 327-328).
    */
  def headPoint: Point = blocks.lastOption.fold(anchorPoint)(pointOf.point)

  /** The points from the newest block back to the anchor (`selectPointsSpec`'s list,
    * `AnchoredFragment.hs` 405-416).
    */
  def pointsNewestFirst: Iterator[Point] =
    blocks.reverseIterator.map(pointOf.point) ++ Iterator.single(anchorPoint)

  /** The point is the anchor or a block of the fragment (`withinFragmentBounds`,
    * `AnchoredFragment.hs` 452-457).
    */
  def withinFragmentBounds(point: Point): Boolean = offsetOf(point).isDefined

  /** The point is a block of the fragment; the anchor is not (`pointOnFragment`,
    * `AnchoredFragment.hs` 430-432).
    */
  def pointOnFragment(point: Point): Boolean = offsetOf(point).exists(_ > 0)

  /** The block after `point`, the anchor or a block (`successorBlock`, `AnchoredFragment.hs`
    * 418-426).
    */
  def successorBlock(point: Point): Option[A] = offsetOf(point).flatMap(blocks.lift)

  /** Add a block after the newest (`addBlock`, `AnchoredFragment.hs` 357-359). */
  def addBlock(block: A): AnchoredFragment[A] =
    val position = anchorAt + 1 + blocks.size
    new AnchoredFragment(
      anchorPoint,
      blocks :+ block,
      anchorAt,
      positions.updated(pointOf.point(block).hash, position)
    )

  /** The fragment rolled back so that `point`, the anchor or a block, is its head; none when the
    * point is not on it (`rollback`, `AnchoredFragment.hs` 369-372).
    */
  def rollback(point: Point): Option[AnchoredFragment[A]] = offsetOf(point).map(headAt)

  /** The fragment split after `point`: the part up to it, and the part anchored at it; none when
    * the point is not on it (`splitAfterPoint`, `AnchoredFragment.hs` 498-508). Each part costs
    * what the other drops: [[rollback]] and [[afterPoint]] build one of them alone.
    */
  def splitAfterPoint(point: Point): Option[(AnchoredFragment[A], AnchoredFragment[A])] =
    offsetOf(point).map(offset => (headAt(offset), anchoredAt(offset)))

  /** The part of the fragment anchored at `point`, as `snd <$> splitAfterPoint af point`; none when
    * the point is not on it.
    */
  def afterPoint(point: Point): Option[AnchoredFragment[A]] = offsetOf(point).map(anchoredAt)

  /** At most the `n` newest blocks, anchored at the block before them (`anchorNewest`,
    * `AnchoredSeq.hs` 501-520). A negative `n` keeps none.
    */
  def anchorNewest(n: Int): AnchoredFragment[A] =
    if n >= blocks.size then this else anchoredAt(blocks.size - n.max(0))

  /** The newest point this fragment shares with `other`, the anchors included (`intersectionPoint`,
    * `AnchoredFragment.hs` 726-733). The reference searches by binary search; as both are chains,
    * the first of `other` 's points from its newest that lies on this fragment is the same point.
    */
  def intersectionPoint[B](other: AnchoredFragment[B]): Option[Point] =
    other.pointsNewestFirst.find(withinFragmentBounds)

  /** How far `point` is from the anchor: 0 the anchor, `i + 1` the block at index `i`. */
  private[consensus] def offsetOf(point: Point): Option[Int] = point match
    case Point.Origin => Option.when(anchorPoint == Point.Origin)(0)
    case Point.Block(_, hash) =>
      offsetOfHash(hash).filter(offset => pointAt(offset).contains(point))

  /** [[offsetOf]] the point with this hash, whatever its slot. */
  private[consensus] def offsetOfHash(hash: Bytes): Option[Int] =
    positions
      .get(hash)
      .map(_ - anchorAt)
      // within 0 to the number of blocks, so the narrowing to an Int is exact
      .collect { case offset if offset >= 0 && offset <= blocks.size => offset.toInt }

  /** The point `offset` away from the anchor. */
  private[consensus] def pointAt(offset: Int): Option[Point] =
    if offset == 0 then Some(anchorPoint) else blocks.lift(offset - 1).map(pointOf.point)

  /** The fragment whose head is `offset` away from the anchor (0: no block). */
  private[consensus] def headAt(offset: Int): AnchoredFragment[A] =
    if offset >= blocks.size then this
    else
      new AnchoredFragment(
        anchorPoint,
        blocks.take(offset.max(0)),
        anchorAt,
        unindexed(positions, offset.max(0), blocks.size)
      )

  /** The fragment anchored at the block `offset` away from the anchor (0: this fragment). */
  private[consensus] def anchoredAt(offset: Int): AnchoredFragment[A] =
    blocks.lift(offset.min(blocks.size) - 1).fold(this) { anchor =>
      val dropped = offset.min(blocks.size)
      val withoutAnchor = anchorPoint match
        case Point.Block(_, hash) if positions.get(hash).contains(anchorAt) =>
          positions.removed(hash)
        case Point.Block(_, _) | Point.Origin => positions
      // the new anchor, the block at index dropped - 1, keeps its key
      val index = unindexed(withoutAnchor, 0, dropped - 1)
      new AnchoredFragment(pointOf.point(anchor), blocks.drop(dropped), anchorAt + dropped, index)
    }

  /** `index` without the keys of the blocks at indices `from` until `until`. A key is removed only
    * where it names that block's position.
    */
  private def unindexed(index: HashMap[Bytes, Long], from: Int, until: Int): HashMap[Bytes, Long] =
    blocks.iterator
      .slice(from, until)
      .zip(Iterator.from(from))
      .foldLeft(index) { case (map, (block, at)) =>
        val hash = pointOf.point(block).hash
        if map.get(hash).contains(anchorAt + 1 + at) then map.removed(hash) else map
      }

  /** The number of hashes indexed: one for each block, and the anchor's when it is a block. */
  private[consensus] def indexSize: Int = positions.size

  override def equals(other: Any): Boolean = other match
    case that: AnchoredFragment[?] =>
      (this eq that) || (anchorPoint == that.anchorPoint && blocks == that.toOldestFirst)
    case _ => false

  override def hashCode: Int = (anchorPoint, blocks).##

  override def toString: String = s"AnchoredFragment($anchorPoint, $blocks)"

object AnchoredFragment:

  /** A block of a fragment names its point, never Origin, which only an anchor can be
    * (`HasHeader`'s `blockPoint`, ouroboros-network c45735a56c567fa977969173d18943bac6bb3821
    * `ouroboros-network/api/lib/Ouroboros/Network/Block.hs` 137-138 and 249-252).
    */
  trait HasPoint[A]:
    def point(block: A): Point.Block

  object HasPoint:
    given HasPoint[Point.Block] with
      def point(block: Point.Block): Point.Block = block

    given HasPoint[Header] with
      def point(block: Header): Point.Block = block.blockPoint

  /** The fragment of `blocks` after `anchor`, oldest first (`fromOldestFirst`). */
  def fromOldestFirst[A](anchor: Point, blocks: Vector[A])(using
      pointOf: HasPoint[A]
  ): AnchoredFragment[A] =
    val anchorKey = anchor match
      case Point.Block(_, hash) => HashMap(hash -> 0L)
      case Point.Origin         => HashMap.empty[Bytes, Long]
    new AnchoredFragment(
      anchor,
      blocks,
      0L,
      blocks.iterator.zipWithIndex.foldLeft(anchorKey) { case (map, (block, at)) =>
        map.updated(pointOf.point(block).hash, 1L + at)
      }
    )

  /** The fragment with no block after `anchor` (`Empty`, `AnchoredSeq.hs` 168-171). */
  def empty[A: HasPoint](anchor: Point): AnchoredFragment[A] = fromOldestFirst(anchor, Vector.empty)
