// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.Functor
import cats.effect.{Async, Resource, Sync}
import cats.mtl.{Ask, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import java.nio.file.{Files, LinkOption}
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{NetworkIdentity, StorageError, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Gap, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.{
  CheckpointDigest,
  AtomicFile,
  DatabaseOwnership,
  FileLedgerSnapshots,
  NetworkIdentityFile,
  RocksUtxoCheckpoint,
  RocksUtxoStore,
  SnapshotCheck,
  SnapshotSidecar,
  SnapshotV7,
  StateBacking,
  StateSnapshot,
  UtxoCheckpoint
}

/** An offline, complete Conway base and its exact read-only checkpoint. No migration, database
  * recovery or production-writer capability is exposed by this resource.
  */
object SclsBase:
  final case class Config(db: Path, scratch: Path, anchor: BlockRef, identity: NetworkIdentity)

  enum Error extends DomainError:
    case Storage(cause: StorageError)
    case MissingSource(path: Path)
    case ScratchPath(path: Path, cause: Throwable)
    case InvalidScratchRoot(path: Path)
    case ScratchInsideDatabase(database: Path, scratch: Path)
    case MissingIdentity
    case Gaps(found: Set[Gap])
    case PointMismatch(expected: BlockRef, found: Option[BlockRef])
    case UnsupportedEra(found: Era)
    case EraMismatch(ledger: Era, header: Era)
    case Unsettled(stamp: Long, flushed: Long, pending: Int)
    case BlockNumberOutOfRange(found: BigInt)
    case CheckpointStamp(expected: Long, found: Long)

  final class Source[F[_]] private[SclsBase] (
      val context: SclsAdmission.Context,
      val sidecar: SnapshotSidecar,
      val snapshot: StateSnapshot[MirrorState],
      val ledger: NewEpochState[Conway],
      val utxo: Utxo,
      private[scls] val database: Path,
      checkpoint: UtxoCheckpoint[F]
  ):
    def entries(using Raise[F, StorageError]): Stream[F, (TxIn, TxOut)] = checkpoint.entries

  /** Resolve both paths before creating scratch data. Returning the real scratch path prevents a
    * configured symlink from redirecting later writes into the source database.
    */
  private[scls] def externalScratch[F[_]: Sync](database: Path, scratch: Path)(using
      Raise[F, Error]
  ): F[Path] =
    Sync[F]
      .blocking {
        for
          databaseReal <- Either
            .catchNonFatal(database.toNioPath.toRealPath())
            .leftMap(Error.ScratchPath(database, _))
          scratchReal <- Either
            .catchNonFatal(scratch.toNioPath.toRealPath())
            .leftMap(Error.ScratchPath(scratch, _))
          _ <- Either.cond(
            Files.isDirectory(scratchReal, LinkOption.NOFOLLOW_LINKS),
            (),
            Error.InvalidScratchRoot(scratch)
          )
          _ <- Either.cond(
            !scratchReal.startsWith(databaseReal),
            (),
            Error
              .ScratchInsideDatabase(Path.fromNioPath(databaseReal), Path.fromNioPath(scratchReal))
          )
        yield Path.fromNioPath(scratchReal)
      }
      .flatMap(_.fold(_.raise[F, Path], Sync[F].pure))

  def resource[F[_]: Async](config: Config)(using
      outer: Raise[F, Error],
      env: Ask[F, ConsensusMirrorEnv]
  ): Resource[F, Source[F]] =
    val F = Async[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = outer.raise(Error.Storage(error))
    def guard[A](path: Path)(body: => A): F[A] = AtomicFile.guard[F, A](path.toString)(body)
    def lift[A](value: Either[Error, A]): F[A] = value.fold(_.raise[F, A], F.pure)
    val path = config.db / "snapshots" / FileLedgerSnapshots.nameOf(config.anchor)
    val load = for
      exists <- guard(path)(Files.isRegularFile(path.toNioPath, LinkOption.NOFOLLOW_LINKS))
      _ <- lift(Either.cond(exists, (), Error.MissingSource(path)))
      raw <- guard(path)(Files.readAllBytes(path.toNioPath))
      content <- lift(FileLedgerSnapshots.verified(raw).leftMap(Error.Storage(_)))
      sections <- lift(SnapshotV7.sections(content).leftMap(Error.Storage(_)))
      (binding, sidecar, _) = sections
      _ <- lift(Either.cond(sidecar.gaps.isEmpty, (), Error.Gaps(sidecar.gaps)))
      _ <- lift(
        Either.cond(
          sidecar.anchor.contains(config.anchor),
          (),
          Error.PointMismatch(config.anchor, sidecar.anchor)
        )
      )
      utxo <- sidecar.chain match
        case SnapshotSidecar.Chain.InShelley(utxo, _, _, _) => F.pure(utxo)
        case SnapshotSidecar.Chain.InByron(_, _) => Error.UnsupportedEra(Era.Byron).raise[F, Utxo]
      _ <- lift(
        Either.cond(
          config.anchor.blockNo >= 0 && config.anchor.blockNo.isValidLong,
          (),
          Error.BlockNumberOutOfRange(config.anchor.blockNo)
        )
      )
      _ <- lift(
        Either.cond(
          utxo.stamp == config.anchor.blockNo.toLong && utxo.flushed == utxo.stamp && utxo.diff.isEmpty,
          (),
          Error.Unsettled(utxo.stamp, utxo.flushed, utxo.diff.size)
        )
      )
      decoded <- SnapshotV7.format[F](SnapshotCheck.Full).decode(content)
      snapshot <- lift(decoded.leftMap(Error.Storage(_)))
      ledgerEra = snapshot.state.ext.ledgerState.era
      headerEra = snapshot.state.ext.headerState.chainDep.era
      _ <- lift(Either.cond(ledgerEra == headerEra, (), Error.EraMismatch(ledgerEra, headerEra)))
      ledger <- snapshot.state.ext.ledgerState.state match
        case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) => F.pure(state.nes)
        case _ =>
          Error.UnsupportedEra(snapshot.state.ext.ledgerState.era).raise[F, NewEpochState[Conway]]
    yield (sidecar, snapshot, ledger, utxo, binding)

    for
      exists <- Resource.eval(guard(config.db)(Files.isDirectory(config.db.toNioPath)))
      _ <- Resource.eval(lift(Either.cond(exists, (), Error.MissingSource(config.db))))
      _ <- DatabaseOwnership.resource[F](config.db)
      scratch <- Resource.eval(externalScratch[F](config.db, config.scratch))
      identity <- Resource.eval(NetworkIdentityFile.verify[F](config.db, config.identity))
      _ <- Resource.eval(lift(Either.cond(identity, (), Error.MissingIdentity)))
      loaded <- Resource.eval(load)
      (sidecar, snapshot, ledger, utxo, binding) = loaded
      checkpointDir = config.db / "utxo" / "checkpoints" / StateBacking.stem(config.anchor)
      admitted <- Resource.eval(
        guard(checkpointDir)(CheckpointDigest.admit(checkpointDir.toNioPath, binding))
      )
      _ <- Resource.eval(lift(admitted.leftMap(Error.Storage(_))))
      logs <- Resource.make(guard(scratch) {
        Path.fromNioPath(Files.createTempDirectory(scratch.toNioPath, "scarab-scls-base-"))
      })(path => guard(path)(RocksUtxoStore.deleteTree(path.toNioPath)))
      checkpoint <- RocksUtxoCheckpoint.resource[F](checkpointDir, logs)
      stamp <- Resource.eval(checkpoint.flushed)
      _ <- Resource.eval(
        lift(Either.cond(stamp == utxo.flushed, (), Error.CheckpointStamp(utxo.flushed, stamp)))
      )
    yield new Source(
      SclsAdmission.Context(config.anchor, config.identity),
      sidecar,
      snapshot,
      ledger,
      utxo,
      config.db,
      checkpoint
    )
