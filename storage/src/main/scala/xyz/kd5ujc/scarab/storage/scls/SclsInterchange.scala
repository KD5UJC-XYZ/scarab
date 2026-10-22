// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.{Applicative, Functor}
import cats.effect.{Async, Resource}
import cats.mtl.{Ask, Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.{Files as FsFiles, Path}
import java.io.{BufferedOutputStream, FileOutputStream, IOException}
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, StandardOpenOption}
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.{
  AtomicFile,
  DatabaseOwnership,
  LedgerSnapshotImport,
  RocksUtxoStore,
  SnapshotPoint
}

/** Offline file boundary. Output stays unpublished until its closed bytes have been read back;
  * import publishes only a complete native snapshot/checkpoint at a verified immutable point.
  */
object SclsInterchange:
  final case class ExportConfig(
      base: SclsBase.Config,
      out: Path,
      summary: SclsRecord.Summary,
      codec: SclsExport.Config
  )
  final case class ImportConfig(
      base: SclsBase.Config,
      input: Path,
      db: Path,
      byronEpochSlots: BigInt,
      codec: SclsImport.Config
  )
  final case class Report(
      anchor: BlockRef,
      outputs: Long,
      coin: BigInt,
      path: Path,
      manifest: SclsRecord.Record.Manifest
  )

  enum Error extends DomainError:
    case Storage(cause: StorageError)
    case Base(cause: SclsBase.Error)
    case Export(cause: SclsExport.Error)
    case Import(cause: SclsImport.Error)
    case Streaming(cause: SclsStreaming.Error)
    case Publication(cause: LedgerSnapshotImport.Error)
    case Collision(path: Path)
    case SourceNotRegular(path: Path)
    case DatabasesOverlap(source: Path, target: Path)
    case ReadbackMismatch

  private final class Capabilities[F[_]: Async](outer: Handle[F, Error]):
    val F = Async[F]
    def mapped[E](wrap: E => Error): Raise[F, E] = new Raise[F, E]:
      def functor: Functor[F] = F
      def raise[E2 <: E, A](error: E2): F[A] = outer.raise(wrap(error))
    given storageErrors: Raise[F, StorageError] = mapped(Error.Storage(_))
    given baseErrors: Raise[F, SclsBase.Error] = mapped(Error.Base(_))
    given exportErrors: Raise[F, SclsExport.Error] = mapped(Error.Export(_))
    given importErrors: Raise[F, SclsImport.Error] = mapped(Error.Import(_))
    given streamingErrors: Raise[F, SclsStreaming.Error] = mapped(Error.Streaming(_))
    given publicationErrors: Handle[F, LedgerSnapshotImport.Error] =
      new Handle[F, LedgerSnapshotImport.Error]:
        def applicative: Applicative[F] = F
        def raise[E <: LedgerSnapshotImport.Error, A](error: E): F[A] =
          outer.raise(Error.Publication(error))
        def handleWith[A](body: F[A])(recover: LedgerSnapshotImport.Error => F[A]): F[A] =
          outer.handleWith(body) {
            case Error.Publication(cause) => recover(cause)
            case other                    => outer.raise(other)
          }
    def lift[A](result: Either[Error, A]): F[A] = result.fold(outer.raise(_), F.pure)
    def guard[A](path: Path)(body: => A): F[A] = AtomicFile.guard[F, A](path.toString)(body)
    def sync(path: Path): F[Unit] = guard(path) {
      val channel = FileChannel.open(path.toNioPath, StandardOpenOption.READ)
      try channel.force(true)
      finally channel.close()
    }
    def fresh(path: Path): F[Unit] =
      guard(path)(Files.exists(path.toNioPath, LinkOption.NOFOLLOW_LINKS))
        .flatMap(found => F.whenA(found)(outer.raise[Error, Unit](Error.Collision(path))))
    def stage(parent: Path): Resource[F, Path] = Resource.make(guard(parent) {
      Path.fromNioPath(Files.createTempDirectory(parent.toNioPath, ".scarab-scls-"))
    })(path => guard(path)(RocksUtxoStore.deleteTree(path.toNioPath)))
    def ioErrors[A](path: Path)(body: F[A]): F[A] = body.handleErrorWith {
      case error: IOException => outer.raise(Error.Storage(StorageError.Io(path.toString, error)))
      case other              => F.raiseError(other)
    }

  def exportFile[F[_]: Async](config: ExportConfig)(using
      outer: Handle[F, Error],
      env: Ask[F, ConsensusMirrorEnv]
  ): F[Report] =
    val c = new Capabilities[F](outer)
    import c.*
    import c.given
    val files = FsFiles.forAsync[F]
    val parent = Path.fromNioPath(config.out.toNioPath.toAbsolutePath.normalize().getParent)
    ioErrors(config.out) {
      SclsBase.resource[F](config.base).use { base =>
        for
          external <- SclsBase.externalScratch[F](config.base.db, parent)
          _ <- fresh(config.out)
          report <- stage(external).use { stage =>
            val output = stage / "state.scls"
            for
              generated <- Resource
                .make(guard(output) {
                  val raw = new FileOutputStream(output.toNioPath.toFile)
                  (raw, new BufferedOutputStream(raw, 1024 * 1024))
                }) { (_, buffered) => guard(output)(buffered.close()) }
                .use { (raw, buffered) =>
                  SclsExport
                    .write[F](base, config.base.scratch, config.summary, config.codec) { bytes =>
                      guard(output)(
                        buffered.write(bytes.unsafeArray, bytes.unsafeOffset, bytes.size)
                      )
                    }
                    .flatTap(_ =>
                      guard(output) {
                        buffered.flush()
                        raw.getChannel.force(true)
                      }
                    )
                }
              readback <- SclsStreaming.read[F](files.readAll(output), config.codec.streaming)(
                (_, _) => F.unit
              )
              _ <- lift(Either.cond(generated == readback, (), Error.ReadbackMismatch))
              _ <- F.uncancelable { _ =>
                fresh(config.out) *> guard(config.out) {
                  Files.createLink(config.out.toNioPath, output.toNioPath)
                  ()
                } *> outer.handleWith(sync(parent)) { error =>
                  outer.handleWith(guard(config.out)(Files.delete(config.out.toNioPath)))(_ =>
                    F.unit
                  ) *>
                    outer.raise[Error, Unit](error)
                }
              }
            yield Report(
              base.context.anchor,
              base.utxo.count,
              base.utxo.coin,
              config.out,
              readback.manifest
            )
          }
        yield report
      }
    }

  def importFile[F[_]: Async](config: ImportConfig)(using
      outer: Handle[F, Error],
      env: Ask[F, ConsensusMirrorEnv]
  ): F[Report] =
    val c = new Capabilities[F](outer)
    import c.*
    import c.given
    val files = FsFiles.forAsync[F]
    ioErrors(config.input) {
      for
        paths <- guard(config.db) {
          val source = config.base.db.toNioPath.toRealPath()
          val target = config.db.toNioPath.toRealPath()
          (source, target)
        }
        (source, target) = paths
        _ <- lift(
          Either.cond(
            !source.startsWith(target) && !target.startsWith(source),
            (),
            Error.DatabasesOverlap(config.base.db, config.db)
          )
        )
        inputRegular <- guard(config.input)(
          Files.isRegularFile(config.input.toNioPath, LinkOption.NOFOLLOW_LINKS)
        )
        _ <- lift(Either.cond(inputRegular, (), Error.SourceNotRegular(config.input)))
        report <- DatabaseOwnership.resource[F](config.db).use { _ =>
          for
            _ <- Vector("utxo", "snapshots", "volatile", "received", "ledger")
              .traverse_(name => fresh(config.db / name))
            _ <- SnapshotPoint.check[F](
              config.db,
              config.base.identity,
              config.base.anchor,
              Era.Conway,
              config.byronEpochSlots
            )
            report <- SclsBase.resource[F](config.base).use { base =>
              val scratch = Path.fromNioPath(target.getParent)
              SclsImport
                .resource[F](base, base.context, files.readAll(config.input), scratch, config.codec)
                .use { prepared =>
                  LedgerSnapshotImport.publish[F](
                    prepared.directory,
                    config.db,
                    LedgerSnapshotImport.Move.native[F]
                  ) *>
                    F.pure(
                      Report(
                        base.context.anchor,
                        prepared.utxo.count,
                        prepared.utxo.coin,
                        config.db,
                        prepared.admitted.incoming.manifest
                      )
                    )
                }
            }
          yield report
        }
      yield report
    }
