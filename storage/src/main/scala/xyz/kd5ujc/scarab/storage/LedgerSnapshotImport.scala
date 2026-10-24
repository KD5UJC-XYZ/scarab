// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Functor
import cats.effect.{Async, Ref, Resource}
import cats.mtl.{Ask, Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Chunk
import fs2.io.file.{Files, Path}
import java.nio.file.{Files as JFiles, LinkOption}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BootstrapState, ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.crypto.witness.PublicKey32
import xyz.kd5ujc.scarab.ledger.{
  NetworkIdentity,
  Nonce,
  ProtocolParams,
  StorageError,
  TxIn,
  TxOut,
  Utxo,
  UtxoKeyLayout
}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, EraLedger}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.*

/** Offline import of the Mem backend. The source and immutable database are read-only; all writes
  * happen in an owned sibling staging directory. A snapshot is published last, after its durable
  * checkpoint, and no existing ledger artifact is replaced.
  */
object LedgerSnapshotImport:
  enum Trust:
    case Verify(manifest: Path, key: PublicKey32)
    case Unverified

  enum Evidence:
    case Verified(manifestHash: Bytes, key: Bytes)
    case Unverified

  enum Error:
    case Storage(error: StorageError)
    case Ancillary(error: AncillaryManifest.Error)
    case Metadata(error: MetaError)
    case State(error: xyz.kd5ujc.scarab.models.runtime.DecodeError)
    case SourceNotRegular(file: String)
    case Tables(error: SnapshotTables.Error)
    case Byron, Origin
    case StampOutOfRange(blockNo: BigInt)
    case Collision(artifact: String)
    case Duplicate(key: TxIn)
    case InstantStakeMismatch
    case LedgerTablesNotEmpty
    case EraMismatch(ledger: Era, header: Era)
    def message: String = this match
      case Storage(e) => e.message
      case State(e)   => e.render
      case other      => other.toString

  final case class Config(
      ledger: Path,
      db: Path,
      identity: NetworkIdentity,
      byronEpochSlots: BigInt,
      genesisParams: ProtocolParams,
      bootstrap: BootstrapState,
      trust: Trust
  )
  final case class Report(anchor: BlockRef, era: Era, count: Long, coin: BigInt, evidence: Evidence)

  private final case class Batch(utxo: Utxo, pending: Vector[(TxIn, TxOut)])
  private val BatchSize = 1024

  def run[F[_]: Async](config: Config)(using
      outer: Handle[F, Error],
      env: Ask[F, ConsensusMirrorEnv]
  ): F[Report] =
    val F = Async[F]
    val files = Files.forAsync[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = outer.raise(Error.Storage(error))
    given Raise[F, SnapshotTables.Error] = new Raise[F, SnapshotTables.Error]:
      def functor: Functor[F] = F
      def raise[E <: SnapshotTables.Error, A](error: E): F[A] = outer.raise(Error.Tables(error))
    def lift[A](value: Either[Error, A]): F[A] = value.fold(_.raise[F, A], F.pure)
    def guard[A](what: String)(value: => A): F[A] = AtomicFile.guard[F, A](what)(value)
    def collision: F[Unit] =
      Vector("utxo", "snapshots", "volatile", "received", "ledger").traverse_ { name =>
        guard(config.db.toString)(
          JFiles.exists((config.db / name).toNioPath, LinkOption.NOFOLLOW_LINKS)
        )
          .flatMap(exists => F.whenA(exists)(Error.Collision(name).raise[F, Unit]))
      }
    val stage = Resource.make(
      guard(config.db.toString) {
        val db = config.db.toNioPath.toAbsolutePath.normalize()
        Path.fromNioPath(JFiles.createTempDirectory(db.getParent, s".${db.getFileName}.import-"))
      }
    )(dir => guard(dir.toString)(RocksUtxoStore.deleteTree(dir.toNioPath)))

    /** The bytes copied are the bytes hashed and subsequently consumed; no second source read. */
    def copy(source: Path, target: Path): F[Bytes] =
      guard(source.toString)(JFiles.isRegularFile(source.toNioPath, LinkOption.NOFOLLOW_LINKS))
        .flatMap(ok => F.unlessA(ok)(Error.SourceNotRegular(source.toString).raise[F, Unit])) *>
        files.createDirectories(target.parent.getOrElse(Path("."))) *>
        files
          .readAll(source)
          .observe(files.writeAll(target))
          .through(fs2.hashing.Hashing.forSync[F].hash(fs2.hashing.HashAlgorithm.SHA256))
          .compile
          .lastOrError
          .map(hash => Bytes.wrap(hash.bytes.toArray))

    def materialize(stage: Path): F[(Path, Evidence)] = config.trust match
      case Trust.Unverified =>
        val target = stage / "source"
        Vector("state", "tables", "meta")
          .traverse_(name => copy(config.ledger / name, target / name))
          .as(target -> Evidence.Unverified)
      case Trust.Verify(manifestFile, key) =>
        for
          text <- files.readUtf8(manifestFile).compile.string
          manifest <- lift(AncillaryManifest.parse(text).leftMap(Error.Ancillary(_)))
          _ <- lift(manifest.verify(key).leftMap(Error.Ancillary(_)))
          base = manifestFile.parent.getOrElse(Path("."))
          root <- guard(base.toString)(base.toNioPath.toRealPath())
          ledger <- guard(config.ledger.toString)(config.ledger.toNioPath.toRealPath())
          relative <- lift(
            Either.cond(
              ledger.startsWith(root),
              root.relativize(ledger).toString,
              Error.Ancillary(AncillaryManifest.Error.UnsafePath(config.ledger.toString))
            )
          )
          _ <- Vector("state", "tables", "meta").traverse_ { name =>
            val path = s"$relative/$name"
            lift(
              Either.cond(
                manifest.files.exists(_.path == path),
                (),
                Error.Ancillary(AncillaryManifest.Error.Unlisted(path))
              )
            )
          }
          _ <- manifest.files.traverse_ { file =>
            val source = base / file.path
            for
              safe <- guard(source.toString) {
                file.path
                  .split('/')
                  .foldLeft((root, true)) { case ((parent, ok), part) =>
                    val next = parent.resolve(part)
                    (next, ok && !JFiles.isSymbolicLink(next))
                  }
                  ._2
              }
              _ <- F.unlessA(safe)(
                Error.Ancillary(AncillaryManifest.Error.UnsafePath(file.path)).raise[F, Unit]
              )
              digest <- copy(source, stage / "source" / file.path)
              _ <- lift(
                Either.cond(
                  digest == file.digest,
                  (),
                  Error.Ancillary(
                    AncillaryManifest.Error.DigestMismatch(file.path, file.digest, digest)
                  )
                )
              )
            yield ()
          }
        yield (stage / "source" / relative, Evidence.Verified(manifest.hash, key.bytes))

    def prepare(stage: Path, source: Path, evidence: Evidence): F[Report] =
      for
        raw <- files.readAll(source / "state").compile.to(Chunk).map(c => Bytes.wrap(c.toArray))
        file <- lift(HaskellLedgerFile.decode(raw).leftMap(Error.State(_)))
        ext <- lift(file.ext.toRight(Error.Byron))
        _ <- lift(
          Either.cond(
            ext.ledgerState.era == ext.headerState.chainDep.era,
            (),
            Error.EraMismatch(ext.ledgerState.era, ext.headerState.chainDep.era)
          )
        )
        anchor <- lift(StateMirror.anchorOf(file.header.tip).toRight(Error.Origin))
        _ <- lift(
          Either.cond(
            anchor.blockNo.isValidLong && anchor.blockNo >= 0,
            (),
            Error.StampOutOfRange(anchor.blockNo)
          )
        )
        _ <- SnapshotPoint
          .check[F](config.db, config.identity, anchor, file.era, config.byronEpochSlots)
        metaText <- files.readUtf8(source / "meta").compile.string
        meta <- lift(SnapshotMeta.parse(metaText).leftMap(Error.Metadata(_)))
        _ <- lift(
          Either.cond(
            meta.backend == SnapshotBackend.InMemory,
            (),
            Error.Metadata(MetaError.UnsupportedBackend(meta.backend))
          )
        )
        report <- ext.ledgerState.state match
          case EraLedgerState.ByronCurrent(_) => Error.Byron.raise[F, Report]
          case i: EraLedgerState.InShelleyEra[e] =>
            given ShelleyBasedEra[e] = i.era
            val state = i.state.nes.es.ls.utxo
            val stamp = anchor.blockNo.toLong
            val params =
              Params.complete(i.era, EraLedger.curPParams(state.gov), config.genesisParams)
            val previous =
              Params.complete(i.era, EraLedger.prevPParams(state.gov), config.genesisParams)
            val observed = (ext.ledgerState.past.map(_.start) :+ ext.ledgerState.current.start)
              .zip(Era.values)
              .map((bound, era) => era -> bound.epoch)
              .toMap
            val extras: MirrorExtras.InShelley = MirrorExtras.InShelley(
              NodeExtras.empty,
              ConsensusExtras(
                config.bootstrap
                  .copy(active = false, era = Some(file.era), stats = None, completed = None),
                ByronState.initial.copy(firstShelleyEpoch = observed.get(Era.Shelley)),
                ProtocolExtras(i.state.nes.epoch.toBigInt, Nonce.Neutral)
              )
            )
            for
              _ <- lift(Either.cond(state.utxo.utxo.isEmpty, (), Error.LedgerTablesNotEmpty))
              result <- RocksUtxoStore.resource[F](stage / "artifacts" / "utxo" / "live").use {
                store =>
                  for
                    loaded <- loadTables[F, e](source, store, stamp)
                    (utxo, summary) = loaded
                    _ <- lift(
                      SnapshotMeta
                        .verify(meta, SnapshotCrc.of(raw), summary.crc)
                        .leftMap(Error.Metadata(_))
                    )
                    _ <- lift(checkStake(i.era, state.instantStake, utxo))
                    checkpoint = stage / "artifacts" / "utxo" / "checkpoints" / StateBacking.stem(
                      anchor
                    )
                    _ <- files.createDirectories(checkpoint.parent.getOrElse(stage))
                    _ <- store.checkpoint(checkpoint)
                    digest <- guard(checkpoint.toString)(CheckpointDigest.of(checkpoint.toNioPath))
                  yield utxo -> CheckpointBinding.Bound(UtxoKeyLayout.current, digest)
              }
              (utxo, binding) = result
              sidecar = SnapshotSidecar(
                Some(anchor),
                observed,
                Set.empty,
                SnapshotSidecar.Chain.InShelley(utxo, params, Some(previous), extras)
              )
              content = SnapshotV7.framed(binding, sidecar, raw)
              back <- SnapshotV7.format[F](SnapshotCheck.Structure).decode(content)
              _ <- lift(back.leftMap(Error.Storage(_)).void)
              snapshots = stage / "artifacts" / "snapshots"
              _ <- files.createDirectories(snapshots)
              hash = MessageDigest.getInstance("SHA-256").digest(content)
              _ <- AtomicFile
                .replaceAll[F](snapshots / FileLedgerSnapshots.nameOf(anchor), Seq(content, hash))
            yield Report(anchor, file.era, utxo.count, utxo.coin, evidence)
      yield report

    DatabaseOwnership
      .resource[F](config.db)
      .use { _ =>
        collision *> stage.use { stage =>
          for
            materialized <- materialize(stage)
            (source, evidence) = materialized
            report <- prepare(stage, source, evidence)
            _ <- publish[F](stage, config.db, Move.native[F])
          yield report
        }
      }
      .handleErrorWith {
        case e: java.io.IOException =>
          Error.Storage(StorageError.Io(config.db.toString, e)).raise[F, Report]
        case e => F.raiseError(e)
      }

  /** The rename boundary, supplied as a capability so typed filesystem failures retain their
    * channel even when F is a transformer. Implementations must raise ImportError, not throw it.
    */
  private[storage] trait Move[F[_]]:
    def apply(from: Path, to: Path)(using Raise[F, Error]): F[Unit]

  private[storage] object Move:
    def native[F[_]: Async]: Move[F] = new Move[F]:
      def apply(from: Path, to: Path)(using outer: Raise[F, Error]): F[Unit] =
        given Raise[F, StorageError] = new Raise[F, StorageError]:
          def functor: Functor[F] = Async[F]
          def raise[E <: StorageError, A](error: E): F[A] = outer.raise(Error.Storage(error))
        AtomicFile.guard[F, Unit](to.toString) {
          JFiles.move(from.toNioPath, to.toNioPath)
          ()
        }

  private[storage] def publish[F[_]: Async](stage: Path, db: Path, move: Move[F])(using
      errors: Handle[F, Error]
  ): F[Unit] =
    val F = Async[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = errors.raise(Error.Storage(error))
    def guard[A](what: String)(action: => A): F[A] = AtomicFile.guard[F, A](what)(action)
    def sync(directory: Path): F[Unit] = guard(directory.toString) {
      val channel = java.nio.channels.FileChannel
        .open(directory.toNioPath, java.nio.file.StandardOpenOption.READ)
      try channel.force(true)
      finally channel.close()
    }
    def remove(artifact: Path): F[Unit] =
      guard(artifact.toString)(RocksUtxoStore.deleteTree(artifact.toNioPath))
    def rollback(action: F[Unit], artifact: Path): F[Unit] =
      errors.handleWith(action)(error =>
        errors.handleWith(remove(artifact))(_ => F.unit) *> errors.raise[Error, Unit](error)
      )
    def fresh: F[Unit] = Vector("utxo", "snapshots", "volatile", "received", "ledger").traverse_ {
      name =>
        guard(db.toString)(JFiles.exists((db / name).toNioPath, LinkOption.NOFOLLOW_LINKS))
          .flatMap(exists => F.whenA(exists)(errors.raise[Error, Unit](Error.Collision(name))))
    }
    val utxo = db / "utxo"
    val snapshots = db / "snapshots"
    F.uncancelable { _ =>
      fresh *> sync(stage / "artifacts" / "utxo" / "checkpoints") *>
        sync(stage / "artifacts" / "utxo") *> sync(stage / "artifacts") *>
        move(stage / "artifacts" / "utxo", utxo) *>
        rollback(
          sync(db) *> move(stage / "artifacts" / "snapshots", snapshots) *>
            rollback(sync(db), snapshots),
          utxo
        )
    }

  private[storage] def loadTables[F[_]: Async, E <: EraTag: ShelleyBasedEra](
      source: Path,
      store: UtxoStore[F],
      stamp: Long
  )(using
      Raise[F, Error],
      Raise[F, StorageError],
      Raise[F, SnapshotTables.Error]
  ): F[(Utxo, SnapshotTables.Summary)] =
    val F = Async[F]
    val files = Files.forAsync[F]
    def lift[A](value: Either[Error, A]): F[A] = value.fold(_.raise[F, A], F.pure)
    // `decodeTablesWithHint`, consensus82ecba3 Shelley/Ledger/Ledger.hs:376-387, runs
    // eraDecoder: the era's lowest binary version, independent of the live protocol parameters.
    val rejectsDuplicates = Versions.eraLow(summon[ShelleyBasedEra[E]]).atLeast(9)
    def flush(batch: Batch): F[Batch] =
      if batch.pending.isEmpty then F.pure(batch)
      else
        for
          previous <- store.read(batch.pending.map(_._1))
          result <- lift(
            batch.pending.foldLeft[Either[Error, Utxo]](Right(batch.utxo.withResident(previous))) {
              case (acc, (key, out)) =>
                acc.flatMap { utxo =>
                  if rejectsDuplicates && utxo.contains(key) then Left(Error.Duplicate(key))
                  else Right(utxo.updated(key, out))
                }
            }
          )
          _ <- store.write(result.diff.map((key, change) => key -> change.out), stamp)
          // Every batch uses the same snapshot stamp: settled normally trims only on advancement.
          clean = result.copy(diff = Map.empty, resident = Map.empty, flushed = stamp)
        yield Batch(clean, Vector.empty)
    for
      batch <- Ref.of[F, Batch](Batch(Utxo.empty.at(stamp), Vector.empty))
      sink = new SnapshotTableStream.SemanticSink[F, E]:
        def insert(key: TxIn, value: TxOut): F[Unit] =
          batch.get.flatMap { current =>
            val next = current.copy(pending = current.pending :+ (key -> value))
            (if next.pending.size >= BatchSize then flush(next) else F.pure(next))
              .flatMap(batch.set)
          }
      summary <- SnapshotTableStream.readInto[F, E](files.readAll(source / "tables"), sink)
      done <- batch.get.flatMap(flush)
      _ <- store.write(Vector.empty, stamp)
    yield done.utxo.copy(flushed = stamp) -> summary

  private[storage] def checkStake[E <: EraTag](
      era: ShelleyBasedEra[E],
      expected: InstantStake[E],
      utxo: Utxo
  ): Either[Error, Unit] =
    def credentials(m: scala.collection.immutable.SortedMap[Credential[Staking], CompactCoin]) =
      m.iterator.map((key, coin) => Convert.back(key) -> coin.toBigInt).toMap
    val same = era match
      case ShelleyBasedEra.SConway => credentials(expected.credentialStake) == utxo.stake
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        credentials(expected.credentialStake) == utxo.stake &&
        expected.ptrStake.iterator.map((key, coin) => key -> coin.toBigInt).toMap == utxo.ptrStake
    Either.cond(same, (), Error.InstantStakeMismatch)
