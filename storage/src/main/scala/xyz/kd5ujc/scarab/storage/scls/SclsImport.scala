// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.Functor
import cats.effect.{Async, Ref, Resource}
import cats.mtl.{Ask, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorState,
  Telescope,
  TelescopeError
}
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut, Utxo, UtxoKeyLayout}
import xyz.kd5ujc.scarab.ledger.rules.ConwayEpoch
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
  ConwayNewEpochState,
  NewEpochState,
  ShelleyBasedEra,
  UtxoTables
}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.storage.{
  AtomicFile,
  CheckpointBinding,
  CheckpointDigest,
  FileLedgerSnapshots,
  NetworkIdentityFile,
  LedgerSnapshotImport,
  RocksUtxoStore,
  SnapshotCheck,
  SnapshotSidecar,
  SnapshotV7,
  StateBacking,
  StateSnapshot,
  UtxoStore
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, StateNotWritable}

/** Import into resource-owned staging against an exact complete base. This component has no
  * publication capability. All metadata is folded directly into the typed immutable builder and
  * UTxO batches are bounded independently of the total set size.
  */
object SclsImport:
  final case class Config(projection: SclsExport.Config)

  enum Error extends DomainError:
    case Storage(cause: StorageError)
    case BaseProjection(cause: SclsExport.Error)
    case Base(cause: SclsBase.Error)
    case Streaming(cause: SclsStreaming.Error)
    case Key(cause: SclsKey.Error)
    case Value(cause: SclsValue.Error)
    case Mapping(cause: SclsStateMapper.Error)
    case Admission(cause: SclsAdmission.Error)
    case Duplicate(input: TxIn)
    case UtxoSummaryMismatch
    case InstantStake(cause: LedgerSnapshotImport.Error)
    case Deposited(expected: BigInt, found: BigInt)
    case Telescope(cause: TelescopeError)
    case State(cause: DecodeError)
    case NotWritable(cause: StateNotWritable)
    case NativeReadback
    case UnsupportedBase

  final class Prepared private[SclsImport] (
      val directory: Path,
      val snapshot: StateSnapshot[MirrorState],
      val utxo: Utxo,
      val admitted: SclsAdmission.Admitted
  ):
    def artifacts: Path = directory / "artifacts"
    def checkpoint: Path =
      artifacts / "utxo" / "checkpoints" / StateBacking.stem(admitted.context.anchor)

  private final case class Batch(utxo: Utxo, pending: Vector[(TxIn, TxOut)])
  private val BatchSize = 1024

  def resource[F[_]: Async](
      base: SclsBase.Source[F],
      expected: SclsAdmission.Context,
      input: Stream[F, Byte],
      scratch: Path,
      config: Config
  )(using outer: Raise[F, Error], env: Ask[F, ConsensusMirrorEnv]): Resource[F, Prepared] =
    val F = Async[F]
    def mapped[E](wrap: E => Error): Raise[F, E] = new Raise[F, E]:
      def functor: Functor[F] = F
      def raise[E2 <: E, A](error: E2): F[A] = outer.raise(wrap(error))
    given storageErrors: Raise[F, StorageError] = mapped(Error.Storage(_))
    given baseErrors: Raise[F, SclsBase.Error] = mapped(Error.Base(_))
    given exportErrors: Raise[F, SclsExport.Error] = mapped(Error.BaseProjection(_))
    given streamingErrors: Raise[F, SclsStreaming.Error] = mapped(Error.Streaming(_))
    def lift[A](value: Either[Error, A]): F[A] = value.fold(_.raise[F, A], F.pure)
    def guard[A](path: Path)(body: => A): F[A] = AtomicFile.guard[F, A](path.toString)(body)
    val stamp = base.utxo.stamp

    def flush(store: UtxoStore[F], batch: Batch): F[Batch] =
      if batch.pending.isEmpty then F.pure(batch)
      else
        for
          previous <- store.read(batch.pending.map(_._1))
          next <- lift(
            batch.pending.foldLeft[Either[Error, Utxo]](Right(batch.utxo.withResident(previous))) {
              case (result, (key, value)) =>
                result.flatMap { utxo =>
                  if utxo.contains(key) then Left(Error.Duplicate(key))
                  else Right(utxo.updated(key, value))
                }
            }
          )
          _ <- store.write(next.diff.map((key, value) => key -> value.out), stamp)
        yield Batch(
          next.copy(diff = Map.empty, resident = Map.empty, flushed = stamp),
          Vector.empty
        )

    def reconstruct(
        ledger: NewEpochState[Conway],
        utxo: Utxo
    ): Either[Error, StateSnapshot[MirrorState]] =
      val withUtxo = ConwayNewEpochState.ls.modify(ls =>
        ls.copy[Conway](utxo = ls.utxo.copy[Conway](utxo = UtxoTables(utxo)))
      )(ledger)
      val before = base.snapshot.state
      before.ext.ledgerState.state match
        case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) =>
          Telescope
            .make(
              before.ext.ledgerState.past,
              before.ext.ledgerState.current.copy(
                state =
                  EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state.copy(nes = withUtxo))
              )
            )
            .leftMap(Error.Telescope(_))
            .map { telescope =>
              base.snapshot
                .copy(state = before.copy(ext = before.ext.copy(ledgerState = telescope)))
            }
        case _ => Left(Error.UnsupportedBase)

    def prepare(stage: Path): F[Prepared] =
      for
        baseProjection <- SclsExport.write[F](
          base,
          scratch,
          SclsRecord.Summary("base projection", "Scarab", None),
          config.projection
        )(_ => F.unit)
        expectedCounts = baseProjection.manifest.namespaces
          .map(info => info.namespace -> info.entries)
          .toMap
        seen <- Ref.of[F, Map[SclsRecord.Namespace, BigInt]](Map.empty)
        builder <- Ref.of[F, SclsStateMapper.Builder](SclsStateMapper.begin(base.ledger))
        batch <- Ref.of[F, Batch](Batch(Utxo.empty.at(stamp), Vector.empty))
        prepared <- RocksUtxoStore.resource[F](stage / "artifacts" / "utxo" / "live").use { store =>
          for
            incoming <- SclsStreaming.read[F](input, config.projection.streaming) {
              (namespace, raw) =>
                for
                  budget <- seen.modify { previous =>
                    val count = previous.getOrElse(namespace, BigInt(0)) + 1
                    val expected = expectedCounts.getOrElse(namespace, BigInt(0))
                    if count > expected then
                      previous -> Left(
                        Error.Admission(
                          SclsAdmission.Error.NamespaceEntries(namespace, expected, count)
                        )
                      )
                    else previous.updated(namespace, count) -> Right(())
                  }
                  _ <- lift(budget)
                  key <- lift(SclsKey.decode(namespace, raw.key).leftMap(Error.Key(_)))
                  value <- lift(
                    SclsValue
                      .decode(namespace, raw.value, config.projection.streaming.valueLimits)
                      .leftMap(Error.Value(_))
                  )
                  _ <- (key, value) match
                    case (SclsKey.Key.Utxo(input), SclsValue.Entry.Utxo(output)) =>
                      batch.get.flatMap { current =>
                        val next = current.copy(pending = current.pending :+ (input -> output))
                        (if next.pending.size >= BatchSize then flush(store, next)
                         else F.pure(next)).flatMap(batch.set)
                      }
                    case _ =>
                      builder.get.flatMap(current =>
                        lift(current.push(key, value).leftMap(Error.Mapping(_)))
                          .flatMap(builder.set)
                      )
                yield ()
            }
            admitted <- lift(
              SclsAdmission
                .check(expected, base.context, baseProjection, incoming)
                .leftMap(Error.Admission(_))
            )
            complete <- batch.get.flatMap(flush(store, _))
            _ <- store.write(Vector.empty, stamp)
            utxo = complete.utxo.copy(flushed = stamp)
            _ <- lift(
              Either.cond(
                utxo.count == base.utxo.count && utxo.coin == base.utxo.coin && utxo.stake == base.utxo.stake && utxo.ptrStake == base.utxo.ptrStake,
                (),
                Error.UtxoSummaryMismatch
              )
            )
            finalBuilder <- builder.get
            ledger <- lift(finalBuilder.finish.leftMap(Error.Mapping(_)))
            ls = ConwayNewEpochState.ls.get(ledger)
            _ <- lift(
              LedgerSnapshotImport
                .checkStake(ShelleyBasedEra.SConway, ls.utxo.instantStake, utxo)
                .leftMap(Error.InstantStake(_))
            )
            obligation = ConwayEpoch.totalObligation(ls)
            _ <- lift(
              Either.cond(
                Coin.value(ls.utxo.deposited) == obligation,
                (),
                Error.Deposited(obligation, Coin.value(ls.utxo.deposited))
              )
            )
            snapshot <- lift(reconstruct(ledger, utxo))
            checkpoint = stage / "artifacts" / "utxo" / "checkpoints" / StateBacking.stem(
              base.context.anchor
            )
            _ <- guard(checkpoint)(Files.createDirectories(checkpoint.toNioPath.getParent))
            _ <- store.checkpoint(checkpoint)
            digest <- guard(checkpoint)(CheckpointDigest.of(checkpoint.toNioPath))
          yield (snapshot, utxo, admitted, CheckpointBinding.Bound(UtxoKeyLayout.current, digest))
        }
        (snapshot, utxo, admitted, binding) = prepared
        file <- lift(HaskellLedgerFile.of(snapshot.state.ext).leftMap(Error.Telescope(_)))
        haskell <- lift(HaskellLedgerFile.encode(file).leftMap(Error.NotWritable(_)))
        sidecar <- base.sidecar.chain match
          case SnapshotSidecar.Chain.InShelley(_, params, previous, extras) =>
            F.pure(
              base.sidecar.copy(chain =
                SnapshotSidecar.Chain.InShelley(utxo, params, previous, extras)
              )
            )
          case _ => Error.UnsupportedBase.raise[F, SnapshotSidecar]
        content = SnapshotV7.framed(binding, sidecar, haskell)
        readback <- SnapshotV7.format[F](SnapshotCheck.Full).decode(content)
        back <- lift(readback.leftMap(Error.Storage(_)))
        backFile <- lift(HaskellLedgerFile.of(back.state.ext).leftMap(Error.Telescope(_)))
        backBytes <- lift(HaskellLedgerFile.encode(backFile).leftMap(Error.NotWritable(_)))
        _ <- lift(
          Either.cond(
            back.anchor == snapshot.anchor && backBytes == haskell,
            (),
            Error.NativeReadback
          )
        )
        snapshots = stage / "artifacts" / "snapshots"
        _ <- guard(snapshots)(Files.createDirectories(snapshots.toNioPath))
        _ <- AtomicFile.replace[F](
          snapshots / FileLedgerSnapshots.nameOf(base.context.anchor),
          FileLedgerSnapshots.closed(content)
        )
        _ <- NetworkIdentityFile.write[F](stage / "artifacts", base.context.identity)
        reconstructed <- SclsBase
          .resource[F](
            SclsBase.Config(
              stage / "artifacts",
              scratch,
              base.context.anchor,
              base.context.identity
            )
          )
          .use { rebuilt =>
            SclsExport.write[F](
              rebuilt,
              scratch,
              SclsRecord.Summary("reconstructed projection", "Scarab", None),
              config.projection
            )(_ => F.unit)
          }
        _ <- lift(
          SclsAdmission
            .check(expected, base.context, reconstructed, admitted.incoming)
            .leftMap(Error.Admission(_))
        )
      yield new Prepared(stage, snapshot, utxo, admitted)

    Resource.eval(SclsBase.externalScratch[F](base.database, scratch)).flatMap { external =>
      Resource
        .make(guard(external) {
          Path.fromNioPath(Files.createTempDirectory(external.toNioPath, "scarab-scls-import-"))
        })(path => guard(path)(RocksUtxoStore.deleteTree(path.toNioPath)))
        .evalMap(prepare)
    }
