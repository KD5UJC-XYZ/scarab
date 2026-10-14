// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import scala.util.control.ControlThrowable

/** A keyed table of the ledger state as a pure value, split the way [[Utxo]] is split (roadmap D1).
  *
  * The rows live in a backing store (`lab.node.storage.LedgerStore`); the value holds only what the
  * store does not reflect yet:
  *
  *   - `diff`: the changes since the store's flushed point, newest per key, each stamped with the
  *     block number that made it. Every state in the volatile window carries its own diff (a
  *     persistent map, so they share structure), which makes a rollback free and the store always
  *     an immutable point.
  *   - `read`: the rows a block needs, fetched before the rules run and dropped afterwards (`None`:
  *     known absent).
  *
  * Alongside the rows the value maintains the row count and two sums ([[Weigh]]) the rules ask for
  * (the supply check, the obligation), so no rule walks the table.
  *
  * The rules stay pure. A read of a row the value does not decide (neither in the diff nor in the
  * read set, with part of the table in the store) throws [[Table.Miss]], a control signal and not a
  * domain error: the caller (`LedgerStore.completing`) fetches the rows it names and runs the rule
  * again. A scan (`toMap`, `iterator`, ...) of a table that is partly in the store throws a `Miss`
  * for the whole table, which is how the epoch boundary gets the accounts. Nothing is ever answered
  * from an incomplete view.
  *
  * A table that never had part of it flushed (`flushed < 0`) holds everything in its diff: genesis,
  * tests, a node without a database.
  */
final case class Table[K, V](
    space: Table.Space,
    diff: Map[K, Table.Change[V]],
    read: Map[K, Option[V]],
    /** `read` holds every row of the store (a scan was fetched). */
    loaded: Boolean,
    count: Long,
    /** Sum of [[Weigh.a]] over the rows. */
    sumA: BigInt,
    /** Sum of [[Weigh.b]] over the rows. */
    sumB: BigInt,
    /** Stamp given to the changes made now (the block number being applied). */
    stamp: Long,
    /** Changes with a stamp up to this one are in the store and were trimmed from `diff`. */
    flushed: Long
):
  import Table.*

  /** The diff and the read set together decide every key. */
  private def decided: Boolean = loaded || flushed < 0

  def get(key: K): Option[V] = diff.get(key) match
    case Some(change) => change.value
    case None =>
      read.get(key) match
        case Some(v)         => v
        case None if decided => None
        case None            => throw Miss(space, Set(key), all = false)

  def contains(key: K): Boolean = get(key).isDefined
  def getOrElse(key: K, default: => V): V = get(key).getOrElse(default)
  def apply(key: K): V =
    get(key).getOrElse(throw new NoSuchElementException(s"key not found: $key"))

  def size: Int = count.toInt
  def isEmpty: Boolean = count == 0
  def nonEmpty: Boolean = count != 0

  def updated(key: K, value: V)(using w: Weigh[V]): Table[K, V] =
    val old = get(key)
    val (a0, b0) = old.fold(Zero)(w.weigh)
    val (a1, b1) = w.weigh(value)
    copy(
      diff = diff.updated(key, Change(Some(value), stamp)),
      count = if old.isEmpty then count + 1 else count,
      sumA = sumA - a0 + a1,
      sumB = sumB - b0 + b1
    )

  def -(key: K)(using w: Weigh[V]): Table[K, V] =
    get(key) match
      case None => this
      case Some(old) =>
        val (a0, b0) = w.weigh(old)
        copy(
          diff = diff.updated(key, Change(None, stamp)),
          count = count - 1,
          sumA = sumA - a0,
          sumB = sumB - b0
        )

  def --(keys: IterableOnce[K])(using w: Weigh[V]): Table[K, V] =
    keys.iterator.foldLeft(this)(_ - _)

  def updatedWith(key: K)(f: Option[V] => Option[V])(using w: Weigh[V]): Table[K, V] =
    f(get(key)) match
      case Some(v) => updated(key, v)
      case None    => this - key

  /** Every row, merged: only for a table that is entirely in view (see the class comment). */
  lazy val toMap: Map[K, V] =
    if !decided then throw Miss(space, Set.empty[Any], all = true)
    else
      val base = read.collect { case (k, Some(v)) => k -> v }
      diff.foldLeft(base) {
        case (m, (k, Change(Some(v), _))) => m.updated(k, v)
        case (m, (k, Change(None, _)))    => m - k
      }

  def iterator: Iterator[(K, V)] = toMap.iterator
  def valuesIterator: Iterator[V] = toMap.valuesIterator
  def keySet: Set[K] = toMap.keySet
  def keys: Iterable[K] = toMap.keys

  /** Replace every row for which `f` returns another value. The rule for a bulk edit (a boundary).
    */
  def mapRows(f: (K, V) => V)(using w: Weigh[V]): Table[K, V] =
    toMap.foldLeft(this) { case (t, (k, v)) =>
      val n = f(k, v)
      if n == v then t else t.updated(k, n)
    }

  /** The keys of `ks` that a read would miss on: those neither the diff nor the read set decide. */
  def undecided(ks: Iterable[K]): Set[K] =
    if decided then Set.empty
    else ks.iterator.filter(k => !diff.contains(k) && !read.contains(k)).toSet

  /** The table as just its rows, in the diff: equal for equal contents however they are split
    * between the store and the diff (tests and diagnostics; the whole table must be in view).
    */
  def logical: Table[K, V] =
    copy(
      diff = toMap.map((k, v) => k -> Change(Some(v), 0L)),
      read = Map.empty,
      loaded = false,
      stamp = 0,
      flushed = -1
    )

  /** Changes made from now on carry this stamp. */
  def at(blockNo: Long): Table[K, V] = if blockNo == stamp then this else copy(stamp = blockNo)

  /** Add fetched rows (`None`: known absent) to the read set. */
  def withRead(rows: Map[K, Option[V]]): Table[K, V] =
    if rows.isEmpty then this else copy(read = read ++ rows)

  /** The whole stored table was fetched into the read set. */
  def withLoaded(rows: Map[K, V]): Table[K, V] =
    copy(read = rows.map((k, v) => k -> Some(v)), loaded = true)

  /** Drop the read set and, once enough have piled up (or when `force` d, for a snapshot), the
    * changes the store now holds (stamps up to `upTo`). Trimming walks the diff, so it is batched.
    */
  def settled(upTo: Long, force: Boolean = false): Table[K, V] =
    val trim = upTo > flushed && (force || upTo - flushed >= TrimEvery)
    if !trim && read.isEmpty && !loaded then this
    else
      copy(
        diff = if trim then diff.filter { case (_, c) => c.stamp > upTo } else diff,
        read = Map.empty,
        loaded = false,
        flushed = if trim then upTo else flushed
      )

  /** The changes newer than `after`, to be written to the store. */
  def changesAfter(after: Long): Iterator[(K, Option[V])] =
    diff.iterator.collect { case (k, c) if c.stamp > after => k -> c.value }

  /** The newest stamp among the changes (or `after` when there are none newer). */
  def newestStamp(after: Long): Long = diff.valuesIterator.foldLeft(after)((m, c) => m.max(c.stamp))

object Table:
  /** Blocks of flushed changes allowed to stay in the diff before it is trimmed. */
  val TrimEvery = 128L

  /** Which stored table a value is the view of: `name` is the table, `gen` tells apart the
    * generations of a table that is replaced as a whole (the stake snapshots); the rows of a
    * generation are the keys that start with it.
    */
  final case class Space(name: String, gen: Long = 0)

  /** The latest state of one key since the flushed point: `None` is a deletion. */
  final case class Change[+V](value: Option[V], stamp: Long)

  /** A read the view cannot answer. `all`: the table is to be fetched whole; else `keys` are. */
  final class Miss(val space: Space, val keys: Set[Any], val all: Boolean)
      extends ControlThrowable(
        s"table ${space.name}/${space.gen}: ${if all then "scan" else keys.mkString(",")}"
      )

  /** Run a pure step, reporting a read it could not answer as a value. */
  def guarded[A](step: => A): Either[Miss, A] =
    try Right(step)
    catch case m: Miss => Left(m)

  private val Zero = (BigInt(0), BigInt(0))

  def empty[K, V](space: Space): Table[K, V] =
    Table(space, Map.empty, Map.empty, loaded = false, 0, 0, 0, 0, -1)

  /** A table holding all `rows` in its diff (complete without a store), the changes stamped
    * `stamp`: the number of the block that made the table.
    */
  def fromMap[K, V](space: Space, rows: Map[K, V], stamp: Long = 0)(using
      w: Weigh[V]
  ): Table[K, V] =
    val (a, b) = rows.valuesIterator.foldLeft(Zero) { case ((x, y), v) =>
      val (p, q) = w.weigh(v)
      (x + p, y + q)
    }
    Table(
      space,
      rows.map((k, v) => k -> Change(Some(v), stamp)),
      Map.empty,
      loaded = false,
      rows.size.toLong,
      a,
      b,
      stamp,
      -1
    )

/** The two sums a table keeps for its rows (what the rules ask of whole tables). */
trait Weigh[V]:
  def weigh(v: V): (BigInt, BigInt)

object Weigh:
  def none[V]: Weigh[V] = _ => (BigInt(0), BigInt(0))
  given stake: Weigh[BigInt] = v => (v, BigInt(0))
  given bytes: Weigh[lab.cbor.Bytes] = none
