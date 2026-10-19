// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.effect.{Async, Ref, Resource}
import cats.effect.syntax.all.*
import cats.effect.std.Semaphore
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import java.io.IOException
import java.nio.file.{FileVisitResult, Files, LinkOption, Path as NioPath, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets.US_ASCII
import org.rocksdb.{
  BlockBasedTableConfig,
  LRUCache,
  Options,
  RocksDB,
  RocksDBException,
  WriteOptions
}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace}

/** A resource-owned on-disk ordering stage for SCLS entries. Its temporary RocksDB uses the default
  * unsigned bytewise comparator, so a packed namespace/key sort key has the same order as
  * [[SclsOutput]]. Key framing is `namespace UTF-8 | 0x00 | SCLS packed key`; all namespace names
  * are ASCII and contain no zero byte. Values are stored verbatim.
  */
trait SclsEntrySorter[F[_]]:
  def add(namespace: Namespace, entry: Entry)(using Raise[F, SclsEntrySorter.Error]): F[Unit]

  /** Consume the single sorted pass. Opening this stream seals the sorter. */
  def entries(using Raise[F, SclsEntrySorter.Error]): Stream[F, (Namespace, Entry)]

object SclsEntrySorter:
  private val WriteBufferBytes = 4L * 1024 * 1024
  private val BlockCacheBytes = 8L * 1024 * 1024
  private val MaximumOpenFiles = 64

  enum Operation:
    case ScratchRoot, TemporaryDirectory, Cache, Options, OpenDatabase, ReadDuplicate, WriteEntry
    case OpenIterator, SeekIterator, ReadEntry, IteratorStatus, CloseIterator
    case CloseDatabase, CloseOptions, CloseCache, RemoveTemporaryDirectory

  enum Error extends DomainError:
    case InvalidScratchRoot(path: String)
    case InvalidEntryLimit(found: Int)
    case EntryTooLarge(namespace: Namespace, actual: Long, maximum: Int)
    case Key(cause: SclsKey.Error)
    case DuplicateEntry(namespace: Namespace, key: Bytes)
    case InvalidSortKey(found: Bytes)
    case AddAfterSeal
    case EntriesAlreadyOpened
    case OperationAfterClose
    case Io(operation: Operation, cause: IOException)
    case Rocks(operation: Operation, cause: RocksDBException)
    case Unexpected(operation: Operation, cause: Throwable)

  /** `maxEntryBytes` includes the Rocks sort key (namespace, separator and packed key) plus the
    * exact value bytes. It bounds each caller-provided entry before this adapter allocates copies.
    */
  final class Config private (val maxEntryBytes: Int)

  object Config:
    def fromInt(maxEntryBytes: Int): Either[Error, Config] =
      Either.cond(
        maxEntryBytes > 0,
        new Config(maxEntryBytes),
        Error.InvalidEntryLimit(maxEntryBytes)
      )

  private enum Phase:
    case Open, Reading, Sealed, Closed

  private def failure(operation: Operation, cause: Throwable): Error = cause match
    case error: RocksDBException => Error.Rocks(operation, error)
    case error: IOException      => Error.Io(operation, error)
    case error                   => Error.Unexpected(operation, error)

  private def guarded[F[_]: Async, A](operation: Operation)(body: => A)(using
      errors: Raise[F, Error]
  ): F[A] =
    Async[F].blocking(body).attempt.flatMap {
      case Right(value) => Async[F].pure(value)
      case Left(cause)  => errors.raise(failure(operation, cause))
    }

  private def deleteTree(path: NioPath): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      Files.walkFileTree(
        path,
        new SimpleFileVisitor[NioPath]:
          override def visitFile(file: NioPath, attrs: BasicFileAttributes): FileVisitResult =
            Files.delete(file)
            FileVisitResult.CONTINUE

          override def postVisitDirectory(dir: NioPath, error: IOException): FileVisitResult =
            if error != null then throw error
            Files.delete(dir)
            FileVisitResult.CONTINUE
      )

  private def validateRoot[F[_]: Async](root: NioPath)(using
      errors: Raise[F, Error]
  ): F[Unit] =
    guarded(Operation.ScratchRoot) {
      Either.cond(
        Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS),
        (),
        Error.InvalidScratchRoot(root.toString)
      )
    }.flatMap(_.fold(_.raise[F, Unit], _ => Async[F].unit))

  private def temporaryDirectory[F[_]: Async](root: NioPath)(using
      errors: Raise[F, Error]
  ): Resource[F, NioPath] =
    Resource.make(
      guarded(Operation.TemporaryDirectory)(Files.createTempDirectory(root, "scarab-scls-sort-"))
    )(path => guarded(Operation.RemoveTemporaryDirectory)(deleteTree(path)))

  private final class RocksSorter[F[_]: Async] private[SclsEntrySorter] (
      db: RocksDB,
      writeOptions: WriteOptions,
      config: Config,
      phase: Ref[F, Phase],
      gate: Semaphore[F]
  ) extends SclsEntrySorter[F]:
    private val F = Async[F]

    private def sortKey(namespace: Namespace, key: Bytes): Array[Byte] =
      val name = namespace.name.getBytes(US_ASCII)
      val out = new Array[Byte](name.length + 1 + key.size)
      System.arraycopy(name, 0, out, 0, name.length)
      System.arraycopy(key.unsafeArray, key.unsafeOffset, out, name.length + 1, key.size)
      out(name.length) = 0
      out

    private def ensureOpen(using errors: Raise[F, Error]): F[Unit] =
      phase.get.flatMap {
        case Phase.Open                   => F.unit
        case Phase.Reading | Phase.Sealed => Error.AddAfterSeal.raise[F, Unit]
        case Phase.Closed                 => Error.OperationAfterClose.raise[F, Unit]
      }

    def add(namespace: Namespace, entry: Entry)(using
        errors: Raise[F, Error]
    ): F[Unit] =
      gate.permit.use { _ =>
        ensureOpen *> SclsKey
          .decode(namespace, entry.key)
          .fold(
            cause => Error.Key(cause).raise[F, Unit],
            _ =>
              val sortKeySize =
                namespace.name.getBytes(US_ASCII).length.toLong + 1L + entry.key.size
              val actual = sortKeySize + entry.value.size.toLong
              if actual > config.maxEntryBytes.toLong then
                Error.EntryTooLarge(namespace, actual, config.maxEntryBytes).raise[F, Unit]
              else
                val packed = sortKey(namespace, entry.key)
                guarded(Operation.ReadDuplicate)(db.get(packed) != null).flatMap {
                  case true => Error.DuplicateEntry(namespace, entry.key.compact).raise[F, Unit]
                  case false =>
                    guarded(Operation.WriteEntry)(db.put(writeOptions, packed, entry.value.toArray))
                }
          )
      }

    def entries(using errors: Raise[F, Error]): Stream[F, (Namespace, Entry)] =
      Stream
        .resource(
          gate.permit.flatMap { _ =>
            Resource.make(
              phase.get.flatMap {
                case Phase.Open =>
                  guarded(Operation.OpenIterator)(db.newIterator())
                    .flatTap(_ => phase.set(Phase.Reading))
                case Phase.Reading | Phase.Sealed =>
                  Error.EntriesAlreadyOpened.raise[F, org.rocksdb.RocksIterator]
                case Phase.Closed => Error.OperationAfterClose.raise[F, org.rocksdb.RocksIterator]
              }
            ) { it =>
              guarded(Operation.CloseIterator)(it.close()).guarantee(phase.set(Phase.Sealed))
            }
          }
        )
        .flatMap { it =>
          Stream.eval(guarded(Operation.SeekIterator)(it.seekToFirst())) *> Stream.repeatEval {
            guarded(Operation.ReadEntry) {
              if !it.isValid then Right(None)
              else
                val storedKey = it.key()
                val separator = storedKey.indexWhere(_ == 0.toByte)
                if separator < 1 then Left(Error.InvalidSortKey(Bytes.fromArray(storedKey)))
                else
                  val namespaceName = new String(storedKey, 0, separator, US_ASCII)
                  Namespace.values.find(_.name == namespaceName) match
                    case None => Left(Error.InvalidSortKey(Bytes.fromArray(storedKey)))
                    case Some(namespace) =>
                      val packedKeySize = storedKey.length - separator - 1
                      val key = Bytes.wrap(storedKey, separator + 1, packedKeySize)
                      SclsKey.decode(namespace, key).leftMap(Error.Key(_)).map { _ =>
                        val value = Bytes.wrap(it.value())
                        it.next()
                        Some(namespace -> Entry(key, value))
                      }
            }.flatMap { result =>
              result.fold(
                _.raise[F, Option[(Namespace, Entry)]],
                {
                  case None  => guarded(Operation.IteratorStatus)(it.status()).as(None)
                  case found => F.pure(found)
                }
              )
            }
          }.unNoneTerminate
        }

  /** Open the sorter under a caller-owned scratch root. Only the fresh child created here is
    * removed on release. The caller's `Raise` is used for acquisition and each later operation; the
    * returned sorter never captures the acquisition capability.
    */
  def resource[F[_]: Async](scratchRoot: Path, config: Config)(using
      errors: Raise[F, Error]
  ): Resource[F, SclsEntrySorter[F]] =
    val F = Async[F]
    val root = scratchRoot.toNioPath
    for
      _ <- Resource.eval(validateRoot[F](root))
      directory <- temporaryDirectory[F](root)
      _ <- Resource.eval(guarded(Operation.Cache)(RocksDB.loadLibrary()))
      cache <- Resource.make(guarded[F, LRUCache](Operation.Cache)(new LRUCache(BlockCacheBytes)))(
        cache => guarded(Operation.CloseCache)(cache.close())
      )
      options <- Resource.make(guarded[F, Options](Operation.Options) {
        val table = new BlockBasedTableConfig()
          .setBlockCache(cache)
          .setCacheIndexAndFilterBlocks(true)
          .setBlockSize(16 * 1024)
        new Options()
          .setCreateIfMissing(true)
          .setMaxOpenFiles(MaximumOpenFiles)
          .setWriteBufferSize(WriteBufferBytes)
          .setMaxWriteBufferNumber(2)
          .setMaxBackgroundJobs(2)
          .setTableFormatConfig(table)
      })(options => guarded(Operation.CloseOptions)(options.close()))
      writeOptions <- Resource.make(
        guarded[F, WriteOptions](Operation.Options)(new WriteOptions().setDisableWAL(true))
      )(writeOptions => guarded(Operation.CloseOptions)(writeOptions.close()))
      db <- Resource.make(
        guarded(Operation.OpenDatabase)(RocksDB.open(options, directory.toString))
      )(db => guarded(Operation.CloseDatabase)(db.close()))
      phase <- Resource.eval(Ref.of[F, Phase](Phase.Open))
      gate <- Resource.eval(Semaphore[F](1L))
      _ <- Resource.make(F.unit)(_ => gate.permit.use(_ => phase.set(Phase.Closed)))
    yield new RocksSorter[F](db, writeOptions, config, phase, gate)
