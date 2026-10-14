// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.{Ref, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import lab.cbor.Bytes
import lab.node.StorageError
import lab.node.ledger.{TxIn, TxOut, Utxo}

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
        val changes = utxo.changesAfter(at).toVector
        // The store is now at the state's newest change (stamps only grow along a chain).
        store.write(
          changes,
          math.max(at, utxo.diff.valuesIterator.map(_.stamp).maxOption.getOrElse(at))
        )
    }

  /** What a transaction can read from the UTxO set: inputs, reference inputs and collateral. */
  def readSet(txs: Iterable[lab.node.ledger.Tx]): Vector[TxIn] =
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

  /** Value encoding shared by the stores: the original CBOR when it round-trips, else the codec. */
  object Coding:
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

    def decodeValue(bytes: Array[Byte]): Either[String, TxOut] =
      if bytes.isEmpty then Left("empty value")
      else if bytes(0) == 1 then
        TxOut.fromBytes(Bytes.wrap(java.util.Arrays.copyOfRange(bytes, 1, bytes.length)))
      else Codec.decode[TxOut](java.util.Arrays.copyOfRange(bytes, 1, bytes.length))

    /** `id[32] | index` as a big-endian u32; an index that does not fit cannot exist in a set. */
    def encodeKey(in: TxIn): Option[Array[Byte]] =
      if in.id.size != 32 || in.index < 0 || in.index > Int.MaxValue then None
      else
        val k = new Array[Byte](36)
        in.id.copyTo(k, 0)
        val i = in.index.toInt
        k(32) = (i >>> 24).toByte
        k(33) = (i >>> 16).toByte
        k(34) = (i >>> 8).toByte
        k(35) = i.toByte
        Some(k)

    def decodeKey(k: Array[Byte]): TxIn =
      val i =
        ((k(32) & 0xff) << 24) | ((k(33) & 0xff) << 16) | ((k(34) & 0xff) << 8) | (k(35) & 0xff)
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
  def size: F[Long] = state.get.map(_.entries.size.toLong)
  def reset: F[Unit] = state.set(Contents(Map.empty, -1))
  def memory: F[Map[String, Long]] = F.pure(Map.empty)

  private def guard[A](what: String)(fa: => A): F[A] =
    F.blocking(fa).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => StorageError.Io(what, String.valueOf(e)).raise[F, A]
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
            val entries = Codec[Map[TxIn, TxOut]].read(in)
            Contents(entries, in.fixed64())
          finally is.close()
        }.flatMap(c => state.set(c).as(true))
    }

object MemoryUtxoStore:
  final case class Contents(entries: Map[TxIn, TxOut], stamp: Long)

  def apply[F[_]: Sync](using Raise[F, StorageError]): F[MemoryUtxoStore[F]] =
    Ref.of[F, Contents](Contents(Map.empty, -1)).map(new MemoryUtxoStore[F](_))
