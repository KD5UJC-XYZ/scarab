// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.ledger.StorageStructureError
import cats.effect.{Resource, Sync}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import java.nio.ByteBuffer
import org.rocksdb.{Options, RocksDB, RocksDBException, Status}
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut}

/** An offline checkpoint, with no write, restore, recovery or migration capability. */
trait UtxoCheckpoint[F[_]]:
  def flushed: F[Long]
  def entries: Stream[F, (TxIn, TxOut)]

object RocksUtxoCheckpoint:
  /** Native diagnostic files belong to logs, outside the checkpoint: even opening a read-only
    * RocksDB must not add a LOG or OPTIONS file to the source directory.
    */
  def resource[F[_]: Sync](dir: Path, logs: Path)(using
      errors: Raise[F, StorageError]
  ): Resource[F, UtxoCheckpoint[F]] =
    val F = Sync[F]
    def guard[A](what: String)(body: => A): F[A] =
      F.blocking(body).attempt.flatMap {
        case Right(value) => F.pure(value)
        case Left(error: RocksDBException)
            if error.getStatus != null && error.getStatus.getCode == Status.Code.Corruption =>
          errors.raise(StorageError.Corrupt(what, StorageStructureError.RocksFailure(error)))
        case Left(error) => errors.raise(StorageError.Io(what, error))
      }
    def lift[A](result: Either[StorageError, A]): F[A] =
      result.fold(_.raise[F, A], F.pure)
    for
      options <- Resource.make(guard(dir.toString) {
        RocksDB.loadLibrary()
        new Options().setMaxOpenFiles(64).setDbLogDir(logs.toString)
      })(options => F.blocking(options.close()))
      db <- Resource.make(guard(dir.toString)(RocksDB.openReadOnly(options, dir.toString)))(db =>
        F.blocking(db.close())
      )
    yield new UtxoCheckpoint[F]:
      def flushed: F[Long] = guard(dir.toString) {
        Option(db.get(Array[Byte](0))).toRight(StorageError.Missing("checkpoint stamp")).flatMap {
          case bytes if bytes.length == 8 => Right(ByteBuffer.wrap(bytes).getLong)
          case bytes =>
            Left(
              StorageError
                .Corrupt("checkpoint stamp", StorageStructureError.StampLength(bytes.length))
            )
        }
      }.flatMap(lift)

      def entries: Stream[F, (TxIn, TxOut)] =
        Stream
          .resource(
            Resource.make(guard(dir.toString)(db.newIterator()))(it => F.blocking(it.close()))
          )
          .flatMap { it =>
            Stream.eval(guard(dir.toString)(it.seekToFirst())).drain ++ Stream
              .repeatEval(guard(dir.toString) {
                @annotation.tailrec
                def next(): Either[StorageError, Option[(TxIn, TxOut)]] =
                  if !it.isValid then
                    it.status()
                    Right(None)
                  else
                    val key = it.key()
                    if key.length == RocksUtxoStore.StampKeyLength && key(0) == 0 then
                      it.next()
                      next()
                    else if key.length != RocksUtxoStore.KeyLength then
                      Left(
                        StorageError
                          .Corrupt("checkpoint key", StorageStructureError.KeyLength(key.length))
                      )
                    else
                      val decoded = UtxoStore.Coding
                        .decodeValue(it.value())
                        .leftMap(reason => StorageError.Corrupt("checkpoint value", reason))
                      it.next()
                      decoded.map(out => Some(UtxoStore.Coding.decodeKey(key) -> out))
                next()
              }.flatMap(lift))
              .unNoneTerminate
          }
