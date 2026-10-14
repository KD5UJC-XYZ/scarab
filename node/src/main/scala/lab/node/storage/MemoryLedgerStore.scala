// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.{Ref, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import lab.cbor.Bytes
import lab.node.StorageError
import lab.node.ledger.{Table, TxIn, TxOut}

/** The ledger store in memory: tests, the devnet and `follow` without `--db`. Rows are kept
  * encoded, as the disk store keeps them, so the codecs and the generation prefix are exercised the
  * same way. Checkpoints are files written with the snapshot codec, so a node with a database but
  * no disk store still restarts.
  */
final class MemoryLedgerStore[F[_]: Sync] private (state: Ref[F, MemoryLedgerStore.Contents])(using
    Raise[F, StorageError]
) extends LedgerStore[F]:
  import MemoryLedgerStore.Contents
  private val F = Sync[F]

  def read(keys: Iterable[TxIn]): F[Map[TxIn, TxOut]] =
    state.get.map(c => keys.iterator.flatMap(k => c.entries.get(k).map(k -> _)).toMap)

  def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long): F[Unit] =
    writeAll(changes, Vector.empty, Vector.empty, stamp)

  def writeAll(
      utxo: Iterable[(TxIn, Option[TxOut])],
      rows: Vector[LedgerStore.TableWrite],
      retain: Vector[(String, Long)],
      stamp: Long
  ): F[Unit] =
    state.update { c =>
      val entries = utxo.foldLeft(c.entries) {
        case (m, (in, Some(out))) => m.updated(in, out.copy(raw = out.raw.compact))
        case (m, (in, None))      => m - in
      }
      val written = rows.foldLeft(c.rows) { (all, w) =>
        val table = w.changes.foldLeft(all.getOrElse(w.space.name, Map.empty[Bytes, Bytes])) {
          case (m, (k, Some(v))) => m.updated(key(w.space.gen, k), Bytes.wrap(v))
          case (m, (k, None))    => m - key(w.space.gen, k)
        }
        all.updated(w.space.name, table)
      }
      val kept = retain.foldLeft(written) { case (all, (name, from)) =>
        all.updatedWith(name)(_.map(_.filter { case (k, _) => MemoryLedgerStore.genOf(k) >= from }))
      }
      Contents(entries, math.max(c.stamp, stamp), kept)
    }

  private def key(gen: Long, k: Array[Byte]): Bytes = Bytes.wrap(LedgerStore.genPrefix(gen) ++ k)

  def readRows(space: Table.Space, keys: Vector[Array[Byte]]): F[Vector[Option[Array[Byte]]]] =
    state.get.map { c =>
      val table = c.rows.getOrElse(space.name, Map.empty)
      keys.map(k => table.get(key(space.gen, k)).map(_.toArray))
    }

  def scanRows(space: Table.Space): F[Vector[(Array[Byte], Array[Byte])]] =
    state.get.map { c =>
      c.rows
        .getOrElse(space.name, Map.empty)
        .iterator
        .collect {
          case (k, v) if MemoryLedgerStore.genOf(k) == space.gen =>
            k.toArray.drop(8) -> v.toArray
        }
        .toVector
    }

  def flushed: F[Long] = state.get.map(_.stamp)
  def all: F[Map[TxIn, TxOut]] = state.get.map(_.entries)
  def size: F[Long] = state.get.map(_.entries.size.toLong)
  def reset: F[Unit] = state.set(Contents(Map.empty, -1, Map.empty))
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
          Codec[Map[String, Map[Bytes, Bytes]]].write(out, c.rows)
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
            val stamp = in.fixed64()
            Contents(entries, stamp, Codec[Map[String, Map[Bytes, Bytes]]].read(in))
          finally is.close()
        }.flatMap(c => state.set(c).as(true))
    }

object MemoryLedgerStore:
  /** `rows`: by table, the encoded key (with its generation prefix) to the encoded value. */
  final case class Contents(
      entries: Map[TxIn, TxOut],
      stamp: Long,
      rows: Map[String, Map[Bytes, Bytes]]
  )

  private def genOf(k: Bytes): Long = java.nio.ByteBuffer.wrap(k.toArray, 0, 8).getLong

  def apply[F[_]: Sync](using Raise[F, StorageError]): F[MemoryLedgerStore[F]] =
    Ref.of[F, Contents](Contents(Map.empty, -1, Map.empty)).map(new MemoryLedgerStore[F](_))
