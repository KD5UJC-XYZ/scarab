// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** The UTxO set as a pure value, split the way UTxO-HD splits it.
  *
  * The set itself lives in a backing store (`xyz.kd5ujc.scarab.storage.UtxoStore`); the ledger
  * state holds only what the backing store does not yet reflect:
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
  * @param stake
  *   Lovelace in base addresses, by the stake credential of the address.
  * @param ptrStake
  *   Lovelace in pointer addresses, by pointer.
  * @param coin
  *   Total lovelace in the set.
  * @param stamp
  *   Stamp given to the changes made now (the block number being applied).
  * @param flushed
  *   Changes with a stamp up to this one are in the backing store and were trimmed from `diff`.
  */
final case class Utxo(
    diff: Map[TxIn, Utxo.Change],
    resident: Map[TxIn, TxOut],
    stake: Map[Credential, BigInt],
    ptrStake: Map[Ptr, BigInt],
    coin: BigInt,
    count: Long,
    stamp: Long,
    flushed: Long
):
  import Utxo.*

  def get(in: TxIn): Option[TxOut] = diff.get(in) match
    case Some(change) => change.out
    case None         => resident.get(in)

  def contains(in: TxIn): Boolean = get(in).isDefined
  def size: Int = count.toInt
  def isEmpty: Boolean = count == 0

  /** The entries of `keys` that neither the diff nor the read set decide: the ones to fetch. */
  def missing(keys: Iterable[TxIn]): Set[TxIn] =
    keys.iterator.filter(k => !diff.contains(k) && !resident.contains(k)).toSet

  /** Remove the entries (absent ones are ignored). */
  def --(ins: IterableOnce[TxIn]): Utxo = ins.iterator.foldLeft(this)(_.remove(_))

  /** Remove entries whose outputs are known although this value may not see them: the backing store
    * can hold an entry that is neither in the diff nor in the read set, and `--` ignores those
    * (nothing says what to debit). Here `known` says it, so the deletion is recorded in the diff
    * (the store's copy is deleted when the changes are flushed) and the totals are debited. An
    * entry the diff has already deleted is left as it is; one the diff or the read set holds is
    * removed as `--` removes it.
    */
  def removeKnown(known: Map[TxIn, TxOut]): Utxo =
    known.foldLeft(this) { case (u, (in, out)) =>
      u.diff.get(in) match
        case Some(Change(None, _))       => u
        case Some(Change(Some(held), _)) => u.removed(in, held)
        case None                        => u.removed(in, u.resident.getOrElse(in, out))
    }

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

  /** The entries this value decides (the live changes of the diff and the read set) whose output
    * satisfies `keep`. A value over a backing store sees only those: a rule that needs every such
    * entry has the node fetch the stored ones into the read set first (as for a block's inputs).
    */
  def matching(keep: TxOut => Boolean): Map[TxIn, TxOut] =
    val live = diff.iterator.collect { case (in, Change(Some(out), _)) if keep(out) => in -> out }
    val read = resident.iterator.filter((in, out) => !diff.contains(in) && keep(out))
    (live ++ read).toMap

  def keys: Iterable[TxIn] = toMap.keys
  def valuesIterator: Iterator[TxOut] = toMap.valuesIterator
  def iterator: Iterator[(TxIn, TxOut)] = toMap.iterator
  def toVector: Vector[(TxIn, TxOut)] = toMap.toVector

  /** Changes made from now on carry this stamp. */
  def at(blockNo: Long): Utxo = if blockNo == stamp then this else copy(stamp = blockNo)

  /** Add fetched entries to the read set. */
  def withResident(fetched: Map[TxIn, TxOut]): Utxo =
    if fetched.isEmpty then this else copy(resident = resident ++ fetched)

  /** Drop the read set and, once enough have piled up (or when `force` d, for a snapshot), the
    * changes the backing store now holds (stamps up to `upTo`). Trimming walks the diff, so it is
    * batched rather than done for every block.
    */
  def settled(upTo: Long, force: Boolean = false): Utxo =
    val trim = upTo > flushed && (force || upTo - flushed >= Utxo.TrimEvery)
    val kept = if trim then diff.filter { case (_, c) => c.stamp > upTo } else diff
    if !trim && resident.isEmpty then this
    else copy(diff = kept, resident = Map.empty, flushed = if trim then upTo else flushed)

  /** The changes newer than `after`, to be written to the backing store. */
  def changesAfter(after: Long): Iterator[(TxIn, Option[TxOut])] =
    diff.iterator.collect { case (in, c) if c.stamp > after => in -> c.out }

  /** The changes newer than `after` and the newest stamp among them (`after` when there is none),
    * in one walk of the diff. The diff of a state in the volatile window holds the changes of up to
    * `k` blocks, so each walk of it is a cost per block that becomes immutable.
    */
  def changesSince(after: Long): (Vector[(TxIn, Option[TxOut])], Long) =
    val out = Vector.newBuilder[(TxIn, Option[TxOut])]
    var newest = after
    val it = diff.iterator
    while it.hasNext do
      val (in, c) = it.next()
      if c.stamp > after then
        out += (in -> c.out)
        if c.stamp > newest then newest = c.stamp
    (out.result(), newest)

object Utxo:
  /** Blocks of flushed changes allowed to stay in the diff before it is trimmed. */
  val TrimEvery = 128L

  /** The latest state of one key since the flushed point: `None` is a deletion. */
  final case class Change(out: Option[TxOut], stamp: Long)

  val empty: Utxo = Utxo(Map.empty, Map.empty, Map.empty, Map.empty, 0, 0, 0, -1)

  def fromMap(entries: Map[TxIn, TxOut]): Utxo = empty ++ entries

  def apply(entries: Map[TxIn, TxOut]): Utxo = fromMap(entries)

  private def bump[K](m: Map[K, BigInt], key: K, delta: BigInt): Map[K, BigInt] =
    val next = m.getOrElse(key, BigInt(0)) + delta
    if next == 0 then m - key else m.updated(key, next)
