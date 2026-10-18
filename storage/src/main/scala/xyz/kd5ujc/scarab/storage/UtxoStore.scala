// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError
import cats.effect.{Ref, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.{TxIn, TxOut, Utxo}

/** The backing store of the UTxO set: the state of the set at the newest immutable block, which is
  * everything older than the last `k` blocks. The volatile part is not here; it lives in the ledger
  * states themselves as [[Utxo]] diffs, so a rollback never reaches the store.
  *
  * The set is read by key (the read set of a block, fetched before the pure rules run) and written
  * in batches (the changes of the blocks that just became immutable). A store is "at" the stamp of
  * its last batch; a checkpoint is a durable copy of it at that moment, kept next to a ledger
  * snapshot so that both restore to the same block.
  */
trait UtxoStore[F[_]]:
  /** The entries of `keys` the store holds (absent keys are simply missing from the result). */
  def read(keys: Iterable[TxIn]): F[Map[TxIn, TxOut]]

  /** Apply the batch atomically (`None` deletes) and move the store to `stamp`. */
  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long): F[Unit]

  /** The stamp of the last batch (`-1` for a store nothing was written to). */
  def flushed: F[Long]

  /** Every entry (tools and tests; a disk store streams it into memory, so only for small sets). */
  def all: F[Map[TxIn, TxOut]]

  /** The entries whose output satisfies `keep`, in one pass over the set (the redeem outputs the
    * block that enters Allegra returns to the reserves, `Epoch.entersAllegra`).
    */
  def select(keep: TxOut => Boolean): F[Map[TxIn, TxOut]]

  /** The entries whose output pays to one of `addresses` (the ledger's address bytes), in one pass
    * that does not decode the outputs it passes over (`GetUTxOByAddress`); the same entries as
    * `select(out => addresses(out.address.raw))`.
    */
  def selectAddresses(addresses: Set[Bytes]): F[Map[TxIn, TxOut]]

  /** Visit every entry without collecting them (the state digests of `--dump-state`). */
  def scan(visit: (TxIn, TxOut) => Unit): F[Unit]

  /** Every entry in ascending Haskell table-key order, without collecting a disk-backed set. The
    * caller holds the store offline for a consistent snapshot export.
    */
  def entries: Stream[F, (TxIn, TxOut)]

  /** Number of entries. */
  def size: F[Long]

  /** Durable copy of the current contents in `dir` (created; must not exist). */
  def checkpoint(dir: Path): F[Unit]

  /** Make the store equal to the checkpoint in `dir`; `false` if there is none. */
  def restore(dir: Path): F[Boolean]

  /** Forget everything (a fresh store at stamp -1). */
  def reset: F[Unit]

  /** Native memory the store holds, in bytes by name (RocksDB: table readers, memtables, block
    * cache); empty for the memory store. For the memory report only.
    */
  def memory: F[Map[String, Long]]

object UtxoStore:
  /** Flush the diff of an immutable state: write every change the store does not have yet. */
  def advance[F[_]: Sync](store: UtxoStore[F], utxo: Utxo): F[Unit] =
    store.flushed.flatMap { at =>
      if utxo.stamp <= at && !utxo.diff.exists(_._2.stamp > at) then Sync[F].unit
      else
        // One walk of the diff for both: the changes to write and the stamp the store is then at
        // (the state's newest change; stamps only grow along a chain).
        val (changes, newest) = utxo.changesSince(at)
        store.write(changes, newest)
    }

  /** What a transaction can read from the UTxO set: inputs, reference inputs and collateral. */
  def readSet(txs: Iterable[xyz.kd5ujc.scarab.ledger.Tx]): Vector[TxIn] =
    txs.iterator.flatMap(t => t.body.inputs ++ t.body.referenceInputs ++ t.body.collateral).toVector

  /** `utxo` with the entries of `ins` it does not decide fetched from the store into its read set,
    * so the pure rules see them: the mempool and the forger read the ledger the way the block path
    * does. Without a store the set is already complete.
    */
  def complete[F[_]: Sync](store: Option[UtxoStore[F]], utxo: Utxo, ins: Iterable[TxIn]): F[Utxo] =
    store.fold(Sync[F].pure(utxo)) { s =>
      val wanted = utxo.missing(ins)
      if wanted.isEmpty then Sync[F].pure(utxo) else s.read(wanted).map(utxo.withResident)
    }

  /** The complete set as of `utxo`: the backing store with the state's changes on top. */
  def materialize[F[_]: Sync](store: UtxoStore[F], utxo: Utxo): F[Map[TxIn, TxOut]] =
    store.all.map { stored =>
      (stored ++ utxo.diff.collect { case (in, Utxo.Change(Some(out), _)) => in -> out }) --
        utxo.diff.collect { case (in, Utxo.Change(None, _)) => in }
    }

  /** The entries of `utxo` 's set whose output satisfies `keep`: one pass over the backing store
    * that keeps only those (a query for one address does not hold the whole set in memory), with
    * the state's changes on top.
    */
  def materializeWhere[F[_]: Sync](
      store: UtxoStore[F],
      utxo: Utxo,
      keep: TxOut => Boolean
  ): F[Map[TxIn, TxOut]] =
    store.select(keep).map { stored =>
      (stored -- utxo.diff.keys) ++ utxo.diff.collect {
        case (in, Utxo.Change(Some(out), _)) if keep(out) => in -> out
      }
    }

  /** [[materializeWhere]] for the outputs that pay to one of `addresses`: the store passes over the
    * others without decoding them, which is what makes a wallet's query fast on a large set.
    */
  def materializeAddresses[F[_]: Sync](
      store: UtxoStore[F],
      utxo: Utxo,
      addresses: Set[Bytes]
  ): F[Map[TxIn, TxOut]] =
    store.selectAddresses(addresses).map { stored =>
      (stored -- utxo.diff.keys) ++ utxo.diff.collect {
        case (in, Utxo.Change(Some(out), _)) if addresses(out.address.raw) => in -> out
      }
    }

  /** Value encoding shared by the stores: the original CBOR when it round-trips, else the codec. */
  object Coding:
    enum ValueError extends DomainError:
      case Empty
      case Cbor(cause: xyz.kd5ujc.scarab.ledger.LedgerDecodeError)
      case Binary(cause: xyz.kd5ujc.scarab.codec.BinaryError)
    final case class EntryError(input: TxIn, cause: ValueError) extends DomainError
    import NodeCodecs.given

    def encodeValue(out: TxOut): Array[Byte] =
      if out.raw.size > 0 && TxOut.fromBytes(out.raw) == Right(out) then
        val a = new Array[Byte](out.raw.size + 1)
        a(0) = 1
        out.raw.copyTo(a, 1)
        a
      else
        val body = Codec.encode(out)
        val a = new Array[Byte](body.length + 1)
        System.arraycopy(body, 0, a, 1, body.length)
        a

    def decodeValue(bytes: Array[Byte]): Either[ValueError, TxOut] =
      if bytes.isEmpty then Left(ValueError.Empty)
      else if bytes(0) == 1 then
        TxOut
          .fromBytes(Bytes.wrap(java.util.Arrays.copyOfRange(bytes, 1, bytes.length)))
          .leftMap(ValueError.Cbor(_))
      else
        Codec
          .decode[TxOut](java.util.Arrays.copyOfRange(bytes, 1, bytes.length))
          .leftMap(ValueError.Binary(_))

    /** Where the address of an encoded output lies, read from the head of its CBOR without decoding
      * the rest: `(offset, length)` in `bytes`, or `None` when the head is not one of the two
      * output forms in definite-length, short-header shape (the caller then decodes the output). An
      * output is `[address, value, ...]` or a map whose first entry is `0: address`.
      */
    def addressSpan(bytes: Array[Byte]): Option[(Int, Int)] =
      // bytes(0) is the codec tag; the CBOR starts at 1.
      def at(i: Int): Int = if i < bytes.length then bytes(i) & 0xff else -1
      val open = at(1)
      val start = (open >> 5, open & 0x1f) match
        case (4, n) if n == 2 || n == 3  => 2 // array(2|3): the address is its first item
        case (5, n) if n >= 1 && n <= 23 => if at(2) == 0 then 3 else -1 // map: first key is 0
        case _                           => -1
      if bytes.length == 0 || bytes(0) != 1 || start < 0 then None
      else
        val head = at(start)
        if head >> 5 != 2 then None
        else
          (head & 0x1f) match
            case n if n < 24              => Some((start + 1, n))
            case 24 if at(start + 1) >= 0 => Some((start + 2, at(start + 1)))
            case 25 if at(start + 2) >= 0 =>
              Some((start + 3, (at(start + 1) << 8) | at(start + 2)))
            case _ => None

    /** Whether the encoded output may pay to one of `addresses`: a sound over-approximation, so the
      * output is decoded only when `true`. The address in the output may carry bytes the ledger's
      * address drops (an era reads it cut), so the test is "starts with", and a pointer address
      * (its pointer is normalised on decoding) is always a candidate.
      */
    def mayPayTo(bytes: Array[Byte], addresses: Array[Array[Byte]]): Boolean =
      addressSpan(bytes).forall { case (offset, length) =>
        offset + length > bytes.length || length == 0 ||
        isPointer(bytes(offset)) ||
        addresses.exists(a =>
          a.length <= length && java.util.Arrays
            .equals(bytes, offset, offset + a.length, a, 0, a.length)
        )
      }

    private def isPointer(header: Byte): Boolean =
      val kind = (header & 0xff) >> 4
      kind == 4 || kind == 5

    /** `id[32] | index` as a big-endian u16, the key of the Haskell node's UTxO-HD tables (`TxIn =
      * txid ++ be16 ix`); an index that does not fit cannot exist in a set.
      */
    def encodeKey(in: TxIn): Option[Array[Byte]] =
      if in.id.size != 32 || in.index < 0 || in.index > 0xffff then None
      else
        val k = new Array[Byte](34)
        in.id.copyTo(k, 0)
        val i = in.index.toInt
        k(32) = (i >>> 8).toByte
        k(33) = i.toByte
        Some(k)

    def decodeKey(k: Array[Byte]): TxIn =
      val i = ((k(32) & 0xff) << 8) | (k(33) & 0xff)
      TxIn(Bytes.wrap(java.util.Arrays.copyOfRange(k, 0, 32)), BigInt(i))

/** The UTxO set in memory: tests, the devnet and `follow` without `--db`. Checkpoints are files
  * written with the snapshot codec, so a node with a database but no disk store still restarts.
  */
final class MemoryUtxoStore[F[_]: Sync] private (state: Ref[F, MemoryUtxoStore.Contents])(using
    Raise[F, StorageError]
) extends UtxoStore[F]:
  import MemoryUtxoStore.Contents
  private val F = Sync[F]

  def read(keys: Iterable[TxIn]): F[Map[TxIn, TxOut]] =
    state.get.map(c => keys.iterator.flatMap(k => c.entries.get(k).map(k -> _)).toMap)

  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long): F[Unit] =
    state.update { c =>
      val next = changes.foldLeft(c.entries) {
        case (m, (in, Some(out))) => m.updated(in, out.copy(raw = out.raw.compact))
        case (m, (in, None))      => m - in
      }
      Contents(next, math.max(c.stamp, stamp))
    }

  def flushed: F[Long] = state.get.map(_.stamp)
  def all: F[Map[TxIn, TxOut]] = state.get.map(_.entries)
  def select(keep: TxOut => Boolean): F[Map[TxIn, TxOut]] =
    state.get.map(_.entries.filter((_, out) => keep(out)))
  def selectAddresses(addresses: Set[Bytes]): F[Map[TxIn, TxOut]] =
    select(out => addresses(out.address.raw))
  def scan(visit: (TxIn, TxOut) => Unit): F[Unit] =
    state.get.map(_.entries.foreach((k, v) => visit(k, v)))
  def entries: Stream[F, (TxIn, TxOut)] =
    Stream
      .eval(state.get)
      .flatMap(c =>
        Stream.emits(c.entries.toVector.sortBy { (in, _) =>
          (in.id, in.index)
        })
      )
  def size: F[Long] = state.get.map(_.entries.size.toLong)
  def reset: F[Unit] = state.set(Contents(Map.empty, -1))
  def memory: F[Map[String, Long]] = F.pure(Map.empty)

  private def guard[A](what: String)(fa: => A): F[A] =
    F.blocking(fa).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => StorageError.Io(what, e).raise[F, A]
    }

  def checkpoint(dir: Path): F[Unit] =
    state.get.flatMap { c =>
      guard(dir.toString) {
        java.nio.file.Files.createDirectories(dir.toNioPath)
        val file = dir.toNioPath.resolve("utxo.bin")
        val os = new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(file))
        try
          val out = Out(os)
          import NodeCodecs.given
          Codec[Map[TxIn, TxOut]].write(out, c.entries)
          out.fixed64(c.stamp)
          out.flush()
        finally os.close()
      }
    }

  def restore(dir: Path): F[Boolean] =
    val file = dir.toNioPath.resolve("utxo.bin")
    F.blocking(java.nio.file.Files.exists(file)).flatMap {
      case false => F.pure(false)
      case true =>
        guard(file.toString) {
          val size = java.nio.file.Files.size(file)
          val is = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file))
          try
            val in = In(is, size)
            import NodeCodecs.given
            for
              entries <- Codec[Map[TxIn, TxOut]].read(in)
              stamp <- in.fixed64()
            yield Contents(entries, stamp)
          finally is.close()
        }.flatMap(
          _.leftMap(e => StorageError.Corrupt(file.toString, e))
            .fold(_.raise[F, Boolean], c => state.set(c).as(true))
        )
    }

object MemoryUtxoStore:
  final case class Contents(entries: Map[TxIn, TxOut], stamp: Long)

  def apply[F[_]: Sync](using Raise[F, StorageError]): F[MemoryUtxoStore[F]] =
    Ref.of[F, Contents](Contents(Map.empty, -1)).map(new MemoryUtxoStore[F](_))
