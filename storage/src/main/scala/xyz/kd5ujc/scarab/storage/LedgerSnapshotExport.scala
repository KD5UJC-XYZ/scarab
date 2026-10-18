// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Functor
import cats.effect.{Async, Resource}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Chunk
import fs2.io.file.{Files, Path}
import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.{Files as JFiles, LinkOption, StandardCopyOption, StandardOpenOption}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, StateMirror}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.state.{Gap, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellLedgerFile,
  MetaError,
  SnapshotCrc,
  SnapshotMeta,
  SnapshotTableStream,
  SnapshotTables
}

/** Offline Mem-backend export. The V5 Haskell section is already the reference state file:
  * consensus 82ecba3 Storage/LedgerDB/Snapshots.hs:395-408,457-463. Only the tables are converted,
  * at the interchange boundary. A suffixed directory is retained by the reference's policy
  * (Snapshots.hs:296-297,432-437).
  */
object LedgerSnapshotExport:
  final case class Config(db: Path, out: Path, slot: Option[SlotNo] = None)
  final case class Report(anchor: BlockRef, era: Era, outputs: Long, directory: Path)

  enum Error:
    case Storage(cause: StorageError)
    case State(cause: DecodeError)
    case Tables(cause: SnapshotTables.Error)
    case Metadata(cause: MetaError)
    case MissingSnapshot(slot: Option[SlotNo])
    case MissingSource(path: Path)
    case Origin, Byron
    case Gaps(found: Set[Gap])
    case AnchorMismatch(expected: BlockRef, actual: Option[BlockRef])
    case EraMismatch(ledger: Era, header: Era)
    case NameMismatch(expected: String, actual: String)
    case StampOutOfRange(blockNo: BigInt)
    case Unsettled(stamp: Long, flushed: Long, pending: Int)
    case CheckpointStamp(expected: Long, actual: Long)
    case NonemptyStateTables
    case Collision(path: Path)
    case OutputWithinSource(path: Path)

    def message: String = this match
      case Storage(cause)              => cause.message
      case State(cause)                => cause.render
      case Tables(cause)               => cause.toString
      case Metadata(cause)             => cause.toString
      case MissingSnapshot(Some(slot)) => "no stored snapshot at slot " + slot.toBigInt
      case MissingSnapshot(None)       => "no stored snapshot"
      case other                       => other.toString

  def run[F[_]: Async](config: Config)(using errors: Handle[F, Error]): F[Report] =
    val F = Async[F]
    val files = Files.forAsync[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = errors.raise(Error.Storage(error))
    given Raise[F, SnapshotTables.Error] = new Raise[F, SnapshotTables.Error]:
      def functor: Functor[F] = F
      def raise[E <: SnapshotTables.Error, A](error: E): F[A] = errors.raise(Error.Tables(error))
    def lift[A](result: Either[Error, A]): F[A] = result.fold(_.raise[F, A], F.pure)
    def guard[A](what: String)(body: => A): F[A] = AtomicFile.guard[F, A](what)(body)
    def fresh(path: Path): F[Unit] =
      guard(path.toString)(JFiles.exists(path.toNioPath, LinkOption.NOFOLLOW_LINKS))
        .flatMap(exists => F.whenA(exists)(Error.Collision(path).raise[F, Unit]))

    /** Resolve existing ancestors before creating anything, including a not-yet-existing output. */
    def separate: F[Unit] = guard(config.out.toString) {
      val db = config.db.toNioPath.toRealPath()
      @annotation.tailrec
      def canonical(
          at: java.nio.file.Path,
          suffix: List[java.nio.file.Path]
      ): java.nio.file.Path =
        if JFiles.exists(at) then suffix.foldLeft(at.toRealPath())(_.resolve(_)).normalize()
        else canonical(at.getParent, at.getFileName :: suffix)
      canonical(config.out.toNioPath.toAbsolutePath.normalize(), Nil).startsWith(db)
    }.flatMap(inside => F.whenA(inside)(Error.OutputWithinSource(config.out).raise[F, Unit]))

    def select: F[Path] =
      val snapshots = config.db / "snapshots"
      val prefix = config.slot.map { slot =>
        val number = slot.toBigInt
        f"snapshot-$number%020d-"
      }
      guard(snapshots.toString)(JFiles.isDirectory(snapshots.toNioPath)).flatMap {
        case false => Error.MissingSnapshot(config.slot).raise[F, Path]
        case true =>
          files
            .list(snapshots)
            .filter(path =>
              FileLedgerSnapshots.isSnapshot(path.fileName.toString) &&
                prefix.forall(path.fileName.toString.startsWith)
            )
            .map(_.fileName.toString)
            .compile
            .toVector
            .flatMap(names =>
              lift(names.maxOption.toRight(Error.MissingSnapshot(config.slot))).map(snapshots / _)
            )
      }

    def writeTables[E <: xyz.kd5ujc.scarab.ledger.state.EraTag: ShelleyBasedEra](
        target: Path,
        count: Long,
        store: UtxoCheckpoint[F]
    ): F[SnapshotTables.Summary] =
      Resource
        .make(guard(target.toString) {
          val raw = new FileOutputStream(target.toNioPath.toFile)
          (raw, new BufferedOutputStream(raw, 1024 * 1024))
        }) { (_, out) => guard(target.toString)(out.close()) }
        .use { (raw, out) =>
          SnapshotTableStream
            .writeOutputs[F, E](count, store.entries)(bytes =>
              guard(target.toString)(out.write(bytes.unsafeArray, bytes.unsafeOffset, bytes.size))
            )
            .flatTap(_ =>
              guard(target.toString) {
                out.flush()
                raw.getChannel.force(true)
                out.close()
              }
            )
        }

    def writeSnapshot(snapshot: Path, stage: Path): F[Report] =
      for
        fileBytes <- files.readAll(snapshot).compile.to(Chunk).map(_.toArray)
        content <- lift(FileLedgerSnapshots.verified(fileBytes).leftMap(Error.Storage(_)))
        sections <- lift(SnapshotV5.sections(content).leftMap(Error.Storage(_)))
        (sidecar, raw) = sections
        _ <- lift(Either.cond(sidecar.gaps.isEmpty, (), Error.Gaps(sidecar.gaps)))
        utxo <- sidecar.chain match
          case SnapshotSidecar.Chain.InByron(_, _) =>
            Error.Byron.raise[F, xyz.kd5ujc.scarab.ledger.Utxo]
          case SnapshotSidecar.Chain.InShelley(utxo, _, _, _) => F.pure(utxo)
        anchor <- lift(sidecar.anchor.toRight(Error.Origin))
        _ <- lift(
          Either.cond(
            snapshot.fileName.toString == FileLedgerSnapshots.nameOf(anchor),
            (),
            Error.NameMismatch(FileLedgerSnapshots.nameOf(anchor), snapshot.fileName.toString)
          )
        )
        haskell <- lift(HaskellLedgerFile.decode(raw).leftMap(Error.State(_)))
        ext <- lift(haskell.ext.toRight(Error.Byron))
        _ <- lift(
          Either.cond(
            StateMirror.anchorOf(haskell.header.tip).contains(anchor),
            (),
            Error.AnchorMismatch(anchor, StateMirror.anchorOf(haskell.header.tip))
          )
        )
        _ <- lift(
          Either.cond(
            ext.ledgerState.era == ext.headerState.chainDep.era,
            (),
            Error.EraMismatch(ext.ledgerState.era, ext.headerState.chainDep.era)
          )
        )
        _ <- lift(
          Either.cond(
            anchor.blockNo.isValidLong && anchor.blockNo >= 0,
            (),
            Error.StampOutOfRange(anchor.blockNo)
          )
        )
        _ <- lift(
          Either.cond(
            utxo.stamp == anchor.blockNo.toLong && utxo.flushed == utxo.stamp && utxo.diff.isEmpty,
            (),
            Error.Unsettled(utxo.stamp, utxo.flushed, utxo.diff.size)
          )
        )
        directory = config.out / (anchor.slot.toString + "_scarab")
        _ <- fresh(directory)
        logs = stage / "rocks-logs"
        _ <- files.createDirectories(logs)
        summary <- ext.ledgerState.state match
          case EraLedgerState.ByronCurrent(_) => Error.Byron.raise[F, SnapshotTables.Summary]
          case state: EraLedgerState.InShelleyEra[e] =>
            given ShelleyBasedEra[e] = state.era
            for
              _ <- lift(
                Either.cond(
                  state.state.nes.es.ls.utxo.utxo.utxo.isEmpty,
                  (),
                  Error.NonemptyStateTables
                )
              )
              result <- RocksUtxoCheckpoint
                .resource[F](config.db / "utxo" / "checkpoints" / StateBacking.stem(anchor), logs)
                .use { store =>
                  for
                    stamp <- store.flushed
                    _ <- lift(
                      Either.cond(
                        stamp == utxo.flushed,
                        (),
                        Error.CheckpointStamp(utxo.flushed, stamp)
                      )
                    )
                    written <- writeTables[e](stage / "tables", utxo.count, store)
                  yield written
                }
            yield result
        _ <- guard(logs.toString)(RocksUtxoStore.deleteTree(logs.toNioPath))
        _ <- AtomicFile.replaceAll[F](stage / "state", Seq(raw.toArray))
        meta = SnapshotMeta.fromCrcs(SnapshotCrc.of(raw), summary.crc)
        text <- lift(SnapshotMeta.encode(meta).leftMap(Error.Metadata(_)))
        _ <- AtomicFile.replaceAll[F](
          stage / "meta",
          Seq(text.getBytes("UTF-8"))
        )
        _ <- publish(stage, config.out, directory, DirectorySync.native[F])
      yield Report(anchor, haskell.era, summary.entries, directory)

    val stage = Resource.make(
      guard(config.out.toString) {
        Path.fromNioPath(JFiles.createTempDirectory(config.out.toNioPath, ".scarab-export-"))
      }
    )(dir => guard(dir.toString)(RocksUtxoStore.deleteTree(dir.toNioPath)))
    guard(config.db.toString)(JFiles.isDirectory(config.db.toNioPath)).flatMap {
      case false => Error.MissingSource(config.db).raise[F, Report]
      case true =>
        DatabaseOwnership.resource[F](config.db).use { _ =>
          (separate *> select.flatMap { snapshot =>
            files.createDirectories(config.out) *> stage.use(writeSnapshot(snapshot, _))
          }).handleErrorWith {
            case error: java.io.IOException =>
              errors.raise(Error.Storage(StorageError.Io(config.db.toString, error.toString)))
            case error => F.raiseError(error)
          }
        }
    }

  /** Caller-scoped persistence capability. Native I/O failures stay in the typed error channel,
    * including when the caller uses a transformer rather than IO.
    */
  private[storage] trait DirectorySync[F[_]]:
    def apply(path: Path): F[Unit]

  private[storage] object DirectorySync:
    def native[F[_]: Async](using Raise[F, StorageError]): DirectorySync[F] =
      new DirectorySync[F]:
        def apply(path: Path): F[Unit] = AtomicFile.guard[F, Unit](path.toString) {
          val channel = FileChannel.open(path.toNioPath, StandardOpenOption.READ)
          try channel.force(true)
          finally channel.close()
        }

  /** All resources and diagnostic cleanup finish before this transition. The owned stage itself is
    * renamed, so a successful Resource finalizer has no leftover directory to remove.
    */
  private[storage] def publish[F[_]: Async](
      stage: Path,
      out: Path,
      directory: Path,
      sync: DirectorySync[F]
  )(using errors: Handle[F, Error]): F[Unit] =
    val F = Async[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = errors.raise(Error.Storage(error))
    def guard[A](what: String)(body: => A): F[A] = AtomicFile.guard[F, A](what)(body)
    val fresh =
      guard(directory.toString)(JFiles.exists(directory.toNioPath, LinkOption.NOFOLLOW_LINKS))
        .flatMap(exists => F.whenA(exists)(Error.Collision(directory).raise[F, Unit]))
    F.uncancelable { _ =>
      fresh *> sync(stage) *>
        guard(directory.toString) {
          JFiles.move(stage.toNioPath, directory.toNioPath, StandardCopyOption.ATOMIC_MOVE)
          ()
        } *> errors.handleWith(sync(out))(error =>
          errors.handleWith(
            guard(directory.toString)(RocksUtxoStore.deleteTree(directory.toNioPath))
          )(_ => F.unit) *> errors.raise[Error, Unit](error)
        )
    }
