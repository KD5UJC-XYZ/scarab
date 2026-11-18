// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

/** The bounds of a [[BoundedCache]]: at most `entries` entries, weighing at most `weight` in all.
  */
private[plutus] final case class Limits(entries: Int, weight: Weight)

/** A map that keeps its most recently used entries within [[Limits]]: a value, whose operations
  * return the map after them.
  *
  * Each entry carries its key as it was put and a stamp, larger for a more recent use; `order`
  * holds the keys by stamp, so that its first key is the least recently used. `weight` is the sum
  * of the entries' weights. A lookup leaves the key that was put in place, so a map holds only the
  * keys it was given to keep. A value heavier than the whole weight bound is never kept, and room
  * is made for a value before it is added, so a map within its limits stays within them, whatever
  * it is given, and its weight is exact.
  */
private[plutus] final case class Lru[K, V] private (
    limits: Limits,
    entries: Map[K, Lru.Entry[K, V]],
    order: TreeMap[Long, K],
    weight: Weight,
    next: Long
):
  def size: Int = entries.size

  /** The keys, the least recently used first. */
  def keys: Vector[K] = order.valuesIterator.toVector

  /** The value of `key`, leaving the order of use as it is. */
  def peek(key: K): Option[V] = entries.get(key).map(_.value)

  /** The map with `key`, if it has it, as the most recently used. */
  def touched(key: K): Lru[K, V] =
    entries.get(key).fold(this) { entry =>
      copy(
        entries = entries.updated(entry.key, entry.copy(stamp = next)),
        order = order.removed(entry.stamp).updated(next, entry.key),
        next = next + 1
      )
    }

  /** The map with `value` for `key` as the most recently used, its least recently used entries
    * evicted first to make room for it; the map unchanged if `weight` alone is beyond its limits.
    */
  def put(key: K, value: V, weight: Weight): Lru[K, V] =
    if limits.entries < 1 || weight.exceeds(limits.weight) then this
    else
      val room = Lru.evicted(removed(key), limits.entries - 1, limits.weight - weight)
      room.copy(
        entries = room.entries.updated(key, Lru.Entry(key, value, weight, next)),
        order = room.order.updated(next, key),
        weight = room.weight + weight,
        next = next + 1
      )

  private def removed(key: K): Lru[K, V] =
    entries.get(key).fold(this) { entry =>
      copy(
        entries = entries.removed(key),
        order = order.removed(entry.stamp),
        weight = weight - entry.weight
      )
    }

  private def eldest: Option[K] = order.headOption.map(_._2)

object Lru:
  final case class Entry[K, V](key: K, value: V, weight: Weight, stamp: Long)

  def empty[K, V](limits: Limits): Lru[K, V] =
    Lru(limits, Map.empty, TreeMap.empty, Weight.zero, 0L)

  /** `lru` without its least recently used entries, until it has at most `entries` weighing at most
    * `weight`.
    */
  @tailrec private def evicted[K, V](lru: Lru[K, V], entries: Int, weight: Weight): Lru[K, V] =
    if lru.size <= entries && !lru.weight.exceeds(weight) then lru
    else
      lru.eldest match
        case Some(key) => evicted(lru.removed(key), entries, weight)
        case None      => lru

/** The memoisation of [[ScarabEvaluator]], which never changes a result: an [[Lru]] within
  * `limits`, each entry weighed by `weigh` when it is put.
  *
  * A kept site of mutable state (FUN-2 (a), a performance-critical path): the memoisation saves a
  * script's decoding at each evaluation after its first, and on the recorded Leios testnet chain
  * decoding is about a sixth of decoding and evaluating, its 273 script requests naming 62 scripts
  * (`docs/performance.md` section 59). The interpreter it serves is a pure function in `Id`, called
  * from the pure ledger rules, which have no effect type to thread a `Ref` through; the map is
  * therefore held in one atomic cell, replaced whole by a pure function of the map before it
  * (`updateAndGet` runs it again if another thread replaced the map first). A lookup and its update
  * of the order of use are one replacement.
  */
private[plutus] final class BoundedCache[K, V] private (limits: Limits, weigh: (K, V) => Weight):
  private val state = new AtomicReference(Lru.empty[K, V](limits))

  /** The map as it is now. */
  def snapshot: Lru[K, V] = state.get

  def get(key: K): Option[V] = state.updateAndGet(_.touched(key)).peek(key)

  def getOrElseUpdate(key: K, compute: => V)(cacheable: V => Boolean): V =
    get(key).getOrElse {
      val value = compute
      if cacheable(value) then
        val weight = weigh(key, value)
        val _ = state.updateAndGet(_.put(key, value, weight))
      value
    }

object BoundedCache:
  def apply[K, V](limits: Limits, weigh: (K, V) => Weight): BoundedCache[K, V] =
    new BoundedCache(limits, weigh)

  /** A cache bounded by its number of entries alone. */
  def counted[K, V](entries: Int): BoundedCache[K, V] =
    new BoundedCache(Limits(entries, Weight.unbounded), (_, _) => Weight.zero)
