// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.Monad
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.node.StorageError
import lab.node.ledger.{
  Account,
  Credential,
  DRepState,
  LedgerState,
  PoolState,
  Snapshot,
  Table,
  TxIn,
  TxOut
}

/** The backing store of the ledger state at the newest immutable block (roadmap D1): the UTxO set
  * and the tables of accounts, pools, DReps and the stake snapshots. Everything older than the last
  * `k` blocks is here; the volatile part lives in the ledger states as the diffs of [[Utxo]] and
  * [[Table]] values, so a rollback never reaches the store.
  *
  * One store holds all of them so that a flush is one atomic batch and a checkpoint is one
  * consistent copy: the stamp the UTxO store was at is the stamp of every table.
  *
  * Rows are bytes here: a key is `generation u64 big-endian | key encoding` and the codecs belong
  * to the typed layer ([[LedgerStore.Slot]]), so the interpreters know nothing about the ledger.
  */
trait LedgerStore[F[_]] extends UtxoStore[F]:
  /** The values of `keys` (encoded keys, without the generation) in `space`; absent: `None`. */
  def readRows(space: Table.Space, keys: Vector[Array[Byte]]): F[Vector[Option[Array[Byte]]]]

  /** Every row of `space` as (key without the generation, value). */
  def scanRows(space: Table.Space): F[Vector[(Array[Byte], Array[Byte])]]

  /** Apply the UTxO changes and the row changes atomically, delete the rows of every generation of
    * a table below the one listed in `retain`, and move the store to `stamp`.
    */
  def writeAll(
      utxo: Iterable[(TxIn, Option[TxOut])],
      rows: Vector[LedgerStore.TableWrite],
      retain: Vector[(String, Long)],
      stamp: Long
  ): F[Unit]

object LedgerStore:
  /** Changes of one table: encoded key (no generation) and the new encoded value or a delete. */
  final case class TableWrite(
      space: Table.Space,
      changes: Vector[(Array[Byte], Option[Array[Byte]])]
  )

  /** The generation prefix of a stored key. */
  def genPrefix(gen: Long): Array[Byte] = java.nio.ByteBuffer.allocate(8).putLong(gen).array()

  /** A table of the state with its place in it and its codecs. */
  final class Slot[K, V](
      val name: String,
      val get: LedgerState => Table[K, V],
      val set: (LedgerState, Table[K, V]) => LedgerState
  )(using val key: Codec[K], val value: Codec[V]):
    def space(s: LedgerState): Table.Space = get(s).space

  import NodeCodecs.given

  val accounts: Slot[Credential, Account] =
    Slot(LedgerState.AccountsTable, _.accounts, (s, t) => s.copy(accounts = t))
  val pools: Slot[Bytes, PoolState] =
    Slot(LedgerState.PoolsTable, _.pools, (s, t) => s.copy(pools = t))
  val dreps: Slot[Credential, DRepState] =
    Slot(LedgerState.DRepsTable, _.dreps, (s, t) => s.copy(dreps = t))

  // The three snapshots are slots of the same two stored tables (see `Snapshot`): the lens tells
  // them apart, the stored table name is the one the table's space carries.
  private def stakeSlot(
      pick: LedgerState => Snapshot,
      put: (LedgerState, Snapshot) => LedgerState
  ): Slot[Credential, BigInt] =
    Slot(Snapshot.StakeTable, s => pick(s).stake, (s, t) => put(s, pick(s).copy(stake = t)))
  private def delegSlot(
      pick: LedgerState => Snapshot,
      put: (LedgerState, Snapshot) => LedgerState
  ): Slot[Credential, Bytes] =
    Slot(
      Snapshot.DelegTable,
      s => pick(s).delegations,
      (s, t) => put(s, pick(s).copy(delegations = t))
    )

  private def putMark(s: LedgerState, n: Snapshot) = s.copy(snapshots = s.snapshots.copy(mark = n))
  private def putSet(s: LedgerState, n: Snapshot) = s.copy(snapshots = s.snapshots.copy(set = n))
  private def putGo(s: LedgerState, n: Snapshot) = s.copy(snapshots = s.snapshots.copy(go = n))

  val markStake = stakeSlot(_.snapshots.mark, putMark)
  val markDeleg = delegSlot(_.snapshots.mark, putMark)
  val setStake = stakeSlot(_.snapshots.set, putSet)
  val setDeleg = delegSlot(_.snapshots.set, putSet)
  val goStake = stakeSlot(_.snapshots.go, putGo)
  val goDeleg = delegSlot(_.snapshots.go, putGo)

  val slots: Vector[Slot[?, ?]] =
    Vector[Slot[?, ?]](
      accounts,
      pools,
      dreps,
      markStake,
      markDeleg,
      setStake,
      setDeleg,
      goStake,
      goDeleg
    )

  /** The stored tables: the interpreters create them. */
  val tableNames: Vector[String] =
    Vector(LedgerState.AccountsTable, LedgerState.PoolsTable, LedgerState.DRepsTable) ++
      Vector(Snapshot.StakeTable, Snapshot.DelegTable)

  private def encodeKey[K, V](slot: Slot[K, V], k: K): Array[Byte] = Codec.encode(k)(using slot.key)

  /** The changes of every table newer than `after`, encoded; and for the stake snapshots the oldest
    * generation still in use (older ones are deleted with the flush).
    */
  def changes(state: LedgerState, after: Long): (Vector[TableWrite], Vector[(String, Long)]) =
    def of[K, V](slot: Slot[K, V]): Option[TableWrite] =
      val t = slot.get(state)
      val cs = t.changesAfter(after).toVector
      Option.when(cs.nonEmpty)(
        TableWrite(
          t.space,
          cs.map { case (k, v) =>
            encodeKey(slot, k) -> v.map(Codec.encode(_)(using slot.value))
          }
        )
      )
    val writes = slots.flatMap(s => of(s))
    // A generation is held by one snapshot at a time (each rotation makes a new one, the empty
    // generation 0 has no rows), so no table is written twice.
    val live = Vector(state.snapshots.mark, state.snapshots.set, state.snapshots.go).map(_.gen)
    val retain = Vector(Snapshot.StakeTable, Snapshot.DelegTable).map(_ -> live.min)
    writes -> retain

  /** The newest stamp among the changes of the state's tables (or `after`). */
  def newestStamp(state: LedgerState, after: Long): Long =
    def of[K, V](slot: Slot[K, V]): Long = slot.get(state).newestStamp(after)
    slots.foldLeft(after)((m, s) => m.max(of(s)))

  /** Flush the diff of an immutable state: write every change the store does not have yet. */
  def advance[F[_]: Monad](store: LedgerStore[F], state: LedgerState): F[Unit] =
    store.flushed.flatMap { at =>
      val utxo = state.utxo
      val (rows, retain) = changes(state, at)
      val utxoNew = utxo.stamp > at || utxo.diff.exists(_._2.stamp > at)
      if !utxoNew && rows.isEmpty then Monad[F].unit
      else
        val stamp = math.max(
          newestStamp(state, at),
          math.max(at, utxo.diff.valuesIterator.map(_.stamp).maxOption.getOrElse(at))
        )
        store.writeAll(utxo.changesAfter(at).toVector, rows, retain, stamp)
    }

  private def decoded[F[_]: Monad, A](what: String, c: Codec[A], bytes: Array[Byte])(using
      Raise[F, StorageError]
  ): F[A] =
    Codec
      .decode(bytes)(using c)
      .fold(e => StorageError.Corrupt(what, e).raise[F, A], _.pure[F])

  /** Fetch what `miss` names into the tables of `state` it concerns. */
  def fill[F[_]: Monad](store: LedgerStore[F], state: LedgerState, miss: Table.Miss)(using
      Raise[F, StorageError]
  ): F[LedgerState] =
    def one[K, V](slot: Slot[K, V], s: LedgerState): F[LedgerState] =
      val t = slot.get(s)
      if slot.name != miss.space.name || t.space != miss.space then s.pure[F]
      else if miss.all then
        store.scanRows(t.space).flatMap { rows =>
          rows
            .traverse { case (k, v) =>
              (
                decoded[F, K](slot.name, slot.key, k),
                decoded[F, V](slot.name, slot.value, v)
              ).tupled
            }
            .map(entries => slot.set(s, t.withLoaded(entries.toMap)))
        }
      else
        val keys = miss.keys.toVector.map(_.asInstanceOf[K])
        store.readRows(t.space, keys.map(encodeKey(slot, _))).flatMap { values =>
          keys
            .zip(values)
            .traverse { case (k, v) =>
              v.traverse(decoded[F, V](slot.name, slot.value, _)).map(k -> _)
            }
            .map(rows => slot.set(s, t.withRead(rows.toMap)))
        }
    slots.foldLeftM(state)((s, slot) => one(slot, s))

  /** Misses of one table (by point reads) after which the whole table is fetched instead. */
  private val Escalate = 3

  /** Run `step` over `state` and, whenever it reports a row the state does not decide
    * ([[Table.guarded]]), fetch what the miss names and run it again: a pure rule never sees an
    * incomplete view. Returns the state with the fetched rows in it (drop them with `settled`) and
    * the result. Without a store the tables are complete.
    */
  def completingF[F[_]: Monad, A](store: Option[LedgerStore[F]], state: LedgerState)(
      step: LedgerState => F[Either[Table.Miss, A]]
  )(using Raise[F, StorageError]): F[(LedgerState, A)] =
    (state, Map.empty[Table.Space, Int]).tailRecM { case (current, seen) =>
      step(current).flatMap {
        case Right(a) => (current, a).asRight[(LedgerState, Map[Table.Space, Int])].pure[F]
        case Left(miss) =>
          store match
            case None =>
              // A table with part of it out of view and no store to ask for the rest.
              StorageError
                .Missing(s"store for ${miss.space.name} rows")
                .raise[F, Either[(LedgerState, Map[Table.Space, Int]), (LedgerState, A)]]
            case Some(s) =>
              val n = seen.getOrElse(miss.space, 0) + 1
              val asked =
                if !miss.all && n > Escalate then Table.Miss(miss.space, Set.empty[Any], all = true)
                else miss
              fill(s, current, asked).map(next => (next, seen.updated(miss.space, n)).asLeft)
      }
    }

  /** [[completingF]] for a pure step. */
  def completing[F[_]: Monad, A](store: Option[LedgerStore[F]], state: LedgerState)(
      f: LedgerState => A
  )(using Raise[F, StorageError]): F[(LedgerState, A)] =
    completingF(store, state)(s => Table.guarded(f(s)).pure[F])

  /** The rows of the ledger tables a block is going to read, as far as they can be told from its
    * transactions (`accountKeys`, `poolKeys`, `drepKeys`), fetched before the first attempt. A miss
    * on a row that is missing from the list (one that depends on another row) is still handled, but
    * costs a second attempt of the block.
    */
  def prefetch[F[_]: Monad](
      store: LedgerStore[F],
      state: LedgerState,
      accountKeys: Set[Credential],
      poolKeys: Set[Bytes],
      drepKeys: Set[Credential]
  )(using Raise[F, StorageError]): F[LedgerState] =
    def fetch[K, V](slot: Slot[K, V], wanted: Set[K], s: LedgerState): F[LedgerState] =
      val t = slot.get(s)
      val missing = t.undecided(wanted)
      if missing.isEmpty then s.pure[F]
      else fill(store, s, Table.Miss(t.space, missing.toSet[Any], all = false))
    for
      a <- fetch(LedgerStore.accounts, accountKeys, state)
      p <- fetch(LedgerStore.pools, poolKeys, a)
      d <- fetch(LedgerStore.dreps, drepKeys, p)
    yield d

  /** The tables an epoch boundary reads whole, fetched whole: the accounts, pools and DReps and the
    * snapshot the rewards are calculated from. That is the `set` snapshot of the state before the
    * boundary: the rotation makes it `go` and the reward calculation reads it there.
    */
  def loadForBoundary[F[_]: Monad](store: LedgerStore[F], state: LedgerState)(using
      Raise[F, StorageError]
  ): F[LedgerState] =
    Vector[Slot[?, ?]](accounts, pools, dreps, setStake, setDeleg).foldLeftM(state) { (s, slot) =>
      def load[K, V](sl: Slot[K, V]): F[LedgerState] =
        if sl.get(s).flushed < 0 then s.pure[F] // all in its diff
        else fill(store, s, Table.Miss(sl.space(s), Set.empty[Any], all = true))
      load(slot)
    }

  /** The state with every table fetched whole: what a query wants of the ledger. */
  def materialize[F[_]: Monad](store: LedgerStore[F], state: LedgerState)(using
      Raise[F, StorageError]
  ): F[LedgerState] =
    slots.foldLeftM(state) { (s, slot) =>
      def load[K, V](sl: Slot[K, V]): F[LedgerState] =
        fill(store, s, Table.Miss(sl.space(s), Set.empty[Any], all = true))
      load(slot)
    }
