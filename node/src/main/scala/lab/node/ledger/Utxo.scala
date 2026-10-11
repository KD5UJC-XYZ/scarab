// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

/** The UTxO set as a pure value, split the way UTxO-HD splits it.
  *
  * The set itself lives in a backing store (`lab.node.storage.UtxoStore`); the ledger state holds
  * only what the backing store does not yet reflect:
  *
  *   - `diff`: the changes made since the backing store's flushed point, newest per key. A change
  *     carries the `stamp` (block number) that made it, so a flush knows what is new and a trim
  *     knows what the backing store already has. Every state in the volatile window carries its own
  *     diff (a persistent map, so they share structure), which is what makes a rollback free.
  *   - `resident`: the read set. Before a block is applied the node fetches the entries its
  *     transactions need from the backing store, so the rules stay pure functions of this value;
  *     afterwards it is dropped.
  *
  * Alongside the set, the value maintains what the epoch boundary used to recompute from the whole
  * set: the lovelace total, the entry count and the stake each credential (and each certificate
  * pointer) controls through its addresses. They are updated as transactions spend and create
  * outputs, so no rule ever walks the set.
  *
  * A value built by [[Utxo.fromMap]] holds everything in `diff`, so it is complete without a store
  * (rule tests, genesis).
  */
final case class Utxo(
    diff: Map[TxIn, Utxo.Change],
    resident: Map[TxIn, TxOut],
    /** Lovelace in base addresses, by the stake credential of the address. */
    stake: Map[Credential, BigInt],
    /** Lovelace in pointer addresses, by pointer. */
    ptrStake: Map[Ptr, BigInt],
    /** Total lovelace in the set. */
    coin: BigInt,
    count: Long,
    /** Stamp given to the changes made now (the block number being applied). */
    stamp: Long,
    /** Changes with a stamp up to this one are in the backing store and were trimmed from `diff`.
      */
    flushed: Long
):
  import Utxo.*

  def get(in: TxIn): Option[TxOut] = diff.get(in) match
    case Some(change) => change.out
    case None         => resident.get(in)

  def apply(in: TxIn): TxOut = get(in).getOrElse(throw new NoSuchElementException(in.render))
  def contains(in: TxIn): Boolean = get(in).isDefined
  def size: Int = count.toInt
  def isEmpty: Boolean = count == 0

  /** The entries of `keys` that neither the diff nor the read set decide: the ones to fetch. */
  def missing(keys: Iterable[TxIn]): Set[TxIn] =
    keys.iterator.filter(k => !diff.contains(k) && !resident.contains(k)).toSet

  /** Remove the entries (absent ones are ignored). */
  def --(ins: IterableOnce[TxIn]): Utxo = ins.iterator.foldLeft(this)(_.remove(_))

  /** Add the entries, replacing existing ones. */
  def ++(created: IterableOnce[(TxIn, TxOut)]): Utxo =
    created.iterator.foldLeft(this) { case (u, (in, out)) => u.put(in, out) }

  def updated(in: TxIn, out: TxOut): Utxo = put(in, out)

  private def put(in: TxIn, out: TxOut): Utxo =
    val base = get(in).fold(this)(old => removed(in, old))
    base
      .credit(out)
      .copy(diff = base.diff.updated(in, Change(Some(out), stamp)), count = base.count + 1)

  private def remove(in: TxIn): Utxo = get(in).fold(this)(old => removed(in, old))

  private def removed(in: TxIn, old: TxOut): Utxo =
    debit(old).copy(diff = diff.updated(in, Change(None, stamp)), count = count - 1)

  private def credit(out: TxOut): Utxo = adjust(out, out.value.coin)
  private def debit(out: TxOut): Utxo = adjust(out, -out.value.coin)

  private def adjust(out: TxOut, delta: BigInt): Utxo =
    val moved = copy(coin = coin + delta)
    out.address.kind match
      case Address.Kind.Shelley(_, _, Some(credential), _) =>
        moved.copy(stake = bump(stake, credential, delta))
      case Address.Kind.Shelley(_, _, None, Some(ptr)) =>
        moved.copy(ptrStake = bump(ptrStake, ptr, delta))
      case _ => moved

  /** Every entry, for tools and tests; only complete when nothing was flushed (see [[Utxo]]). */
  def toMap: Map[TxIn, TxOut] =
    (resident ++ diff.collect { case (in, Change(Some(out), _)) => in -> out }) --
      diff.collect { case (in, Change(None, _)) => in }

  def keys: Iterable[TxIn] = toMap.keys
  def valuesIterator: Iterator[TxOut] = toMap.valuesIterator
  def iterator: Iterator[(TxIn, TxOut)] = toMap.iterator
  def toVector: Vector[(TxIn, TxOut)] = toMap.toVector

  /** Changes made from now on carry this stamp. */
  def at(blockNo: Long): Utxo = if blockNo == stamp then this else copy(stamp = blockNo)

  /** Add fetched entries to the read set. */
  def withResident(fetched: Map[TxIn, TxOut]): Utxo =
    if fetched.isEmpty then this else copy(resident = resident ++ fetched)

  /** Drop the read set and the changes the backing store now holds (stamps up to `upTo`). */
  def settled(upTo: Long): Utxo =
    val kept =
      if upTo > flushed then diff.filter { case (_, c) => c.stamp > upTo } else diff
    if kept.eq(diff) && resident.isEmpty && upTo <= flushed then this
    else copy(diff = kept, resident = Map.empty, flushed = math.max(flushed, upTo))

  /** The changes newer than `after`, to be written to the backing store. */
  def changesAfter(after: Long): Iterator[(TxIn, Option[TxOut])] =
    diff.iterator.collect { case (in, c) if c.stamp > after => in -> c.out }

object Utxo:
  /** The latest state of one key since the flushed point: `None` is a deletion. */
  final case class Change(out: Option[TxOut], stamp: Long)

  val empty: Utxo = Utxo(Map.empty, Map.empty, Map.empty, Map.empty, 0, 0, 0, -1)

  def fromMap(entries: Map[TxIn, TxOut]): Utxo = empty ++ entries

  def apply(entries: Map[TxIn, TxOut]): Utxo = fromMap(entries)

  private def bump[K](m: Map[K, BigInt], key: K, delta: BigInt): Map[K, BigInt] =
    val next = m.getOrElse(key, BigInt(0)) + delta
    if next == 0 then m - key else m.updated(key, next)
