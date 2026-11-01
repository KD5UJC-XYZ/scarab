// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Order, Show}
import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet, TreeMap}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  GenDelegPair,
  ParamUpdate,
  PoolParams,
  ProtocolParams,
  Proposals,
  PulsingSnapshot,
  Ptr,
  RatifyState
}

// What the mirror needs of the types it shares with the node's own ledger: instances for the
// records reused as they are (the plan's "reused as-is" list). They are data of immutable
// collections, so equality is the structural one of a case class. They are orphans by necessity (the
// types are not ours to extend here) and top-level, so any file of this package finds them.

given Eq[Bytes] = Eq.fromUniversalEquals
given Show[Bytes] = Show.show(b => s"Bytes(${b.hex})")
given Show[Rational] = Show.fromToString
given Eq[Anchor] = Eq.fromUniversalEquals
given Show[Anchor] = Show.fromToString
given Eq[GenDelegPair] = Eq.fromUniversalEquals
given Show[GenDelegPair] = Show.fromToString
given Eq[PoolParams] = Eq.fromUniversalEquals
given Show[PoolParams] = Show.fromToString
given Eq[ParamUpdate] = Eq.fromUniversalEquals
given Show[ParamUpdate] = Show.fromToString
given Eq[ProtocolParams] = Eq.fromUniversalEquals
given Show[ProtocolParams] = Show.fromToString
given Eq[Proposals] = Eq.fromUniversalEquals
given Show[Proposals] = Show.fromToString
given Eq[Committee] = Eq.fromUniversalEquals
given Show[Committee] = Show.fromToString
given Eq[Constitution] = Eq.fromUniversalEquals
given Show[Constitution] = Show.fromToString
given Eq[PulsingSnapshot] = Eq.fromUniversalEquals
given Show[PulsingSnapshot] = Show.fromToString
given Eq[RatifyState] = Eq.fromUniversalEquals
given Show[RatifyState] = Show.fromToString

/** `Ptr` (`Credential.hs:258-259`, `data Ptr = Ptr !SlotNo32 !TxIx !CertIx deriving (Eq, Ord)`):
  * slot, then transaction index, then certificate index, which is the order the node's `Ptr`
  * already has.
  */
given Order[Ptr] = Order.fromOrdering[Ptr]
given Show[Ptr] = Show.fromToString

/** An order that carries its `Ordering`, made once. The sorted maps of the ledger are built from
  * one another constantly (`filter`, `map`, `from`), and `TreeMap.from` copies a sorted source in
  * linear time only when the two orderings are equal, which for `Order.toOrdering` (a new object on
  * every call) they never were. A [[SortOrder]] is an `Order` whose `ordering` is the same object
  * for every map built with it.
  */
abstract class SortOrder[A] extends Order[A]:
  final val ordering: Ordering[A] = new Ordering[A]:
    def compare(x: A, y: A): Int = SortOrder.this.compare(x, y)

/** Maps and sets whose iteration order is the Haskell order of the key, so that traversing one is
  * traversing its CBOR encoding. The order is the key's `cats.Order`, never an `Ordering` found by
  * accident.
  */
object Sorted:
  /** Entries that are already in ascending order of the key, as a map built in linear time (a
    * red-black tree from sorted input, `RedBlackTree.fromOrderedEntries`) rather than by one insert
    * each: `map` of the same entries makes about `n log n` comparisons. The caller promises the
    * order; a duplicate or an out-of-order key gives a tree that is not a map of its keys.
    */
  def fromAscending[K: Order, V](entries: scala.collection.IndexedSeq[(K, V)]): SortedMap[K, V] =
    TreeMap.from(Ascending(entries, orderingOf(Order[K])))(using orderingOf(Order[K]))

  /** The sorted source `TreeMap.from` builds a tree from without comparing: it reads the iterator
    * and the size, and takes the tree it is given when the ordering is its own. The operations of a
    * map answer from that tree, which is built the same way.
    */
  private final class Ascending[K, V](
      entries: scala.collection.IndexedSeq[(K, V)],
      val ordering: Ordering[K]
  ) extends SortedMap[K, V]:
    private lazy val tree: TreeMap[K, V] = TreeMap.from(this)(using ordering)
    override def iterator: Iterator[(K, V)] = entries.iterator
    override def size: Int = entries.size
    override def knownSize: Int = entries.size
    override def get(key: K): Option[V] = tree.get(key)
    override def removed(key: K): SortedMap[K, V] = tree.removed(key)
    override def updated[V1 >: V](key: K, value: V1): SortedMap[K, V1] = tree.updated(key, value)
    override def iteratorFrom(start: K): Iterator[(K, V)] = tree.iteratorFrom(start)
    override def keysIteratorFrom(start: K): Iterator[K] = tree.keysIteratorFrom(start)
    override def rangeImpl(from: Option[K], until: Option[K]): SortedMap[K, V] =
      tree.rangeImpl(from, until)

  /** A sorted map read at keys that come in ascending order, as when two sorted maps are walked
    * together (the accounts and the instant stake, the accounts and the rewards). A key is looked
    * for by stepping from the last one; after [[Cursor.Steps]] steps without reaching it, from the
    * root again (`iteratorFrom`, about `log n` comparisons). A dense walk then costs a comparison
    * for each entry, and a sparse one a seek for each key, where a lookup of each key costs about
    * seventeen comparisons for 100,000 entries. A key below one asked before reads as absent.
    */
  // The position is a mutable field: the epoch boundary's walks are built on this cursor
  // (`docs/performance.md` section 16, where it is 41 percent of the tick); a functional cursor
  // threads its position through every walk, and its measurement is #674.
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  final class Cursor[K, V](map: SortedMap[K, V])(using order: Order[K]):
    private var rest: scala.collection.BufferedIterator[(K, V)] = map.iterator.buffered

    @tailrec def at(key: K, stepped: Int = 0): Option[V] =
      if !rest.hasNext then None
      else
        val c = order.compare(rest.head._1, key)
        if c < 0 && stepped >= Cursor.Steps then
          rest = map.iteratorFrom(key).buffered
          at(key, 0)
        else if c < 0 then
          rest.next()
          at(key, stepped + 1)
        else if c == 0 then Some(rest.head._2)
        else None

  object Cursor:
    /** The steps taken before a key is looked for from the root. */
    val Steps = 8

  private def orderingOf[K](order: Order[K]): Ordering[K] = order match
    case s: SortOrder[K @unchecked] => s.ordering
    case other                      => other.toOrdering

  def map[K: Order, V](entries: IterableOnce[(K, V)]): SortedMap[K, V] =
    SortedMap.from(entries)(using orderingOf(Order[K]))

  def empty[K: Order, V]: SortedMap[K, V] = SortedMap.empty(using orderingOf(Order[K]))

  def set[K: Order](keys: IterableOnce[K]): SortedSet[K] =
    SortedSet.from(keys)(using orderingOf(Order[K]))

  def emptySet[K: Order]: SortedSet[K] = SortedSet.empty(using orderingOf(Order[K]))
