// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet, TreeMap, TreeSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Header

/** The processed VolatileDB forest: each block by the hash of its own header, with the reference's
  * successors index, the hashes of the blocks after each predecessor. At ouroboros-consensus
  * 82ecba32 that is `currentSuccMap` (`Storage/VolatileDB/Impl/State.hs:105-106`), which
  * `putBlockImpl` grows with `insertMapSet` and `garbageCollectFile` shrinks with `deleteMapSet`
  * (`Storage/VolatileDB/Impl.hs:436,518-543`, `Storage/VolatileDB/Impl/Util.hs:107-136`), and
  * `filterByPredecessor` reads (`Storage/VolatileDB/Impl.hs:545-551`). It holds every processed
  * block, hidden or not: what chain selection does not see is filtered where it reads, as the
  * reference wraps the index (`ignoreInvalidSuc`, `Storage/ChainDB/Impl/ChainSel.hs:1406-1417`).
  *
  * Two invariants hold by construction, since a forest is only built by the operations below: a
  * block's key is its header's hash, and no block's predecessors lead back to it (a block that
  * would close such a cycle is refused, [[ProcessedForest.Cycle]]). A walk along predecessors or
  * successors therefore ends, and only the blocks it reaches are read.
  */
private[node] final class ProcessedForest[A] private (
    val byHash: Map[Bytes, A],
    index: Map[Option[Bytes], SortedSet[Bytes]],
    bySlot: SortedMap[BigInt, Set[Bytes]]
)(using block: ProcessedForest.Block[A]):
  import ProcessedForest.*

  def get(hash: Bytes): Option[A] = byHash.get(hash)
  def contains(hash: Bytes): Boolean = byHash.contains(hash)
  def size: Int = byHash.size
  def header(hash: Bytes): Option[Header] = byHash.get(hash).map(block.header)

  /** `filterByPredecessor`: the blocks whose predecessor is `predecessor` (`None` for genesis), in
    * the order of their hashes, as the reference's `Set` of hashes holds them.
    */
  def successors(predecessor: Option[Bytes]): SortedSet[Bytes] =
    index.getOrElse(predecessor, Empty)

  /** The successors index itself, to compare with one rebuilt from the blocks. */
  private[node] def successorIndex: Map[Option[Bytes], SortedSet[Bytes]] = index

  /** The blocks by slot, to compare with the blocks grouped by slot. */
  private[node] def slotIndex: Map[BigInt, Set[Bytes]] = bySlot

  /** The forest with `value`, or this forest when a block of its hash is here already. A block
    * whose predecessors lead back to it is refused. A cycle has to pass through the new block, the
    * only new edge, so it is looked for only when a block after it is here already: then the walk
    * from its predecessor, through blocks of this acyclic forest, ends at it or where the forest
    * does.
    */
  def inserted(value: A): Either[Cycle, ProcessedForest[A]] =
    val header = block.header(value)
    val hash = header.hash
    @tailrec def reaches(current: Option[Bytes]): Boolean = current match
      case Some(found) if found == hash => true
      case Some(found) =>
        byHash.get(found) match
          case Some(known) => reaches(block.header(known).prevHash)
          case None        => false
      case None => false
    if byHash.contains(hash) then Right(this)
    else if header.prevHash.contains(hash) || (index.contains(Some(hash)) && reaches(
        header.prevHash
      ))
    then Left(Cycle(hash))
    else
      Right(
        new ProcessedForest(
          byHash.updated(hash, value),
          index.updated(header.prevHash, successors(header.prevHash) + hash),
          bySlot.updated(header.slot, bySlot.getOrElse(header.slot, Set.empty[Bytes]) + hash)
        )
      )

  /** The forest with the blocks of `values` it does not hold already, the last of a hash in
    * `values` taken, as the recovered forest is joined to the processed one at the start. They are
    * inserted in slot order, so that a block's predecessor usually precedes it and the cycle check
    * walks nothing; the first block that would close a cycle refuses the whole of them.
    */
  def seeded(values: Iterable[A]): Either[Cycle, ProcessedForest[A]] =
    values
      .map(value => block.header(value).hash -> value)
      .toMap
      .valuesIterator
      .toVector
      .sortBy(value => (block.header(value).slot, block.header(value).hash))(using
        Ordering.Tuple2(using Ordering[BigInt], hashOrder)
      )
      .foldLeft[Either[Cycle, ProcessedForest[A]]](Right(this))((forest, value) =>
        forest.flatMap(_.inserted(value))
      )

  /** The forest without `hash`, as `deleteMapSet` drops it from its predecessor's successors, and
    * the predecessor with its last successor.
    */
  def removed(hash: Bytes): ProcessedForest[A] =
    byHash.get(hash).fold(this) { value =>
      val header = block.header(value)
      new ProcessedForest(
        byHash - hash,
        deleteMapSet(index, header.prevHash, hash),
        deleteSlot(bySlot, header.slot, hash)
      )
    }

  /** The forest without the blocks of a slot below `slot`: the collection below the immutable tip,
    * which reads only the blocks it removes.
    */
  def collectedBelow(slot: BigInt): ProcessedForest[A] =
    bySlot.rangeUntil(slot).valuesIterator.flatten.foldLeft(this)(_.removed(_))

private[node] object ProcessedForest:
  /** What the forest reads of a block: its header. */
  trait Block[-A]:
    def header(value: A): Header

  given Block[Header] = header => header

  /** The block of `hash` would be its own ancestor. */
  final case class Cycle(hash: Bytes)

  /** Unsigned lexicographic order of the hashes, shorter first on a common prefix: the order of the
    * reference's `Set` of `HeaderHash` (a `ShortByteString`), which [[Bytes.compareTo]] gives
    * without allocating.
    */
  val hashOrder: Ordering[Bytes] = (a, b) => a.compareTo(b)

  private val Empty: SortedSet[Bytes] = TreeSet.empty(using hashOrder)

  def empty[A: Block]: ProcessedForest[A] =
    new ProcessedForest[A](Map.empty, Map.empty, TreeMap.empty)

  /** A forest of `values`, as [[ProcessedForest.seeded]] builds it on an empty one. */
  def of[A: Block](values: Iterable[A]): Either[Cycle, ProcessedForest[A]] = empty[A].seeded(values)

  /** `deleteMapSet` (`Storage/VolatileDB/Impl/Util.hs:121-136`, ouroboros-consensus 82ecba32): the
    * key goes with its last value.
    */
  private def deleteMapSet(
      map: Map[Option[Bytes], SortedSet[Bytes]],
      key: Option[Bytes],
      value: Bytes
  ): Map[Option[Bytes], SortedSet[Bytes]] = map.get(key).fold(map) { values =>
    val remaining = values - value
    if remaining.isEmpty then map - key else map.updated(key, remaining)
  }

  /** As [[deleteMapSet]], for the blocks of a slot. */
  private def deleteSlot(
      map: SortedMap[BigInt, Set[Bytes]],
      slot: BigInt,
      value: Bytes
  ): SortedMap[BigInt, Set[Bytes]] = map.get(slot).fold(map) { values =>
    val remaining = values - value
    if remaining.isEmpty then map - slot else map.updated(slot, remaining)
  }
