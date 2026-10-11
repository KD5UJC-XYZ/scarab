// SPDX-License-Identifier: Apache-2.0
package lab.node.relay

import lab.cbor.Bytes
import lab.network.{ChainSync, NtNHeader}
import lab.network.ChainSync.{Point, Tip, UInt64}
import lab.node.BlockRef

/** A block of the chain we serve: identity, the header as ChainSync carries it, and the original
  * block bytes as BlockFetch carries them.
  */
final case class Served(ref: BlockRef, header: NtNHeader, raw: Bytes)

/** Where a connected client's read pointer stands. */
enum Cursor:
  /** The newest point already delivered to the client. */
  case At(point: Point)

  /** The chain forked away from the client's pointer: the next instruction is a rollback. */
  case RollbackTo(point: Point)

/** What reading for a follower finds. */
enum Read:
  case Forward(block: Served)
  case Backward(point: Point)

  /** The pointer is older than the volatile chain: the next block is in the immutable store. */
  case Behind(point: Point)
  case AtTip

/** The volatile part of the chain we serve (the blocks newer than the immutable tip), with the read
  * pointer of every connected client. This is the ouroboros-consensus `ChainProducerState`: a
  * rollback rewrites the pointers of exactly those clients that had read past the new tip, so each
  * of them is told to roll back and then follows the new branch.
  *
  * Pure; [[ChainServer]] keeps it in a `Ref`.
  */
final case class ServedChain(
    anchor: Option[BlockRef],
    blocks: Vector[Served],
    /** Sequence number of `blocks.head`; sequence numbers only grow, so positions are
      * `seq - first`.
      */
    first: Long,
    seqs: Map[Bytes, Long],
    followers: Map[Long, Cursor],
    nextFollower: Long
):
  def anchorPoint: Point = anchor.fold[Point](Point.Origin)(_.point)

  def tipRef: Option[BlockRef] = blocks.lastOption.map(_.ref).orElse(anchor)

  def tip: Tip = tipRef.fold(Tip.Origin)(r => Tip(r.point, UInt64.saturating(r.blockNo)))

  private def position(point: Point): Option[Int] = point match
    case Point.Origin => None
    case Point.Block(_, hash) =>
      seqs.get(hash).map(s => (s - first).toInt).filter(i => blocks(i).ref.point == point)

  /** The point is the anchor or in the volatile chain (the immutable part is the store's). */
  def containsVolatile(point: Point): Boolean = point == anchorPoint || position(point).isDefined

  /** Append a block; `dropped` oldest blocks became immutable and `newAnchor` is the new boundary.
    */
  def extend(block: Served, dropped: Int, newAnchor: Option[BlockRef]): ServedChain =
    val kept = blocks.drop(dropped)
    val removed = blocks.take(dropped).map(_.ref.hash)
    copy(
      anchor = newAnchor.orElse(anchor),
      blocks = kept :+ block,
      first = first + dropped,
      seqs = (seqs -- removed) + (block.ref.hash -> (first + dropped + kept.size))
    )

  /** Roll the chain back to `to` (the anchor or a retained block). */
  def rollback(to: Point): Option[ServedChain] =
    val keep =
      if to == anchorPoint then Some(0) else position(to).map(_ + 1)
    keep.map { n =>
      val removed = blocks.drop(n).map(_.ref.hash)
      val after = copy(blocks = blocks.take(n), seqs = seqs -- removed)
      val moved = followers.map { (id, cursor) =>
        val valid = (point: Point) => point == after.anchorPoint || after.position(point).isDefined
        val stale =
          (point: Point) => this.position(point).isDefined && after.position(point).isEmpty
        id -> (cursor match
          case Cursor.At(p) if stale(p) => Cursor.RollbackTo(to)
          case Cursor.RollbackTo(p) if !valid(p) && this.position(p).isDefined =>
            Cursor.RollbackTo(to)
          case other => other)
      }
      after.copy(followers = moved)
    }

  /** The block after `point`, if it is volatile. */
  private def successor(point: Point): Read =
    val index =
      if point == anchorPoint then Some(-1)
      else position(point)
    index match
      case Some(i) => blocks.lift(i + 1).fold[Read](Read.AtTip)(Read.Forward(_))
      case None    => Read.Behind(point)

  def read(cursor: Cursor): Read = cursor match
    case Cursor.RollbackTo(p) => Read.Backward(p)
    case Cursor.At(p)         => successor(p)

  def open(): (ServedChain, Long) =
    (
      copy(
        followers = followers + (nextFollower -> Cursor.At(Point.Origin)),
        nextFollower = nextFollower + 1
      ),
      nextFollower
    )

  def close(id: Long): ServedChain = copy(followers = followers - id)

  def move(id: Long, cursor: Cursor): ServedChain =
    if followers.contains(id) then copy(followers = followers.updated(id, cursor)) else this

object ServedChain:
  def empty(anchor: Option[BlockRef]): ServedChain =
    ServedChain(anchor, Vector.empty, 0L, Map.empty, Map.empty, 0L)

  /** The sequence index agrees with the block vector (an invariant, used by the tests). */
  def isConsistent(chain: ServedChain): Boolean =
    chain.blocks.zipWithIndex.forall((b, i) =>
      chain.seqs.get(b.ref.hash).contains(chain.first + i)
    ) &&
      chain.seqs.size == chain.blocks.size
