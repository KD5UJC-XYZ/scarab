// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.mithril

import cats.Functor
import scala.util.Using
import cats.effect.{Async, Resource}
import cats.mtl.{Ask, Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import io.circe.Json
import java.nio.file.{Files as JFiles, LinkOption, StandardCopyOption, StandardOpenOption}
import java.nio.channels.FileChannel
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.storage.*
import xyz.kd5ujc.scarab.storage.haskell.{HaskellImport, HaskellImportError}

/** A certified bootstrap is published as one fresh directory. The official client operates only on
  * an owned staged copy, and the existing immutable/ledger importers finish before publication.
  * Ancillary authentication remains a separate operator-key decision, never a Mithril certificate.
  */
object MithrilBootstrap:
  enum Error:
    case Storage(error: StorageError)
    case Client(error: MithrilClient.Error)
    case Immutable(error: HaskellImportError)
    case Ledger(error: LedgerSnapshotImport.Error)
    case DestinationExists(path: String)
    case UnsafeSource(path: String)
    case NetworkMismatch(expected: Long, actual: Long)
    case MissingLedger, AmbiguousLedger, MissingManifest, TruncatedImmutable
    def message: String = this match
      case Storage(e)   => e.message
      case Client(e)    => e.message
      case Immutable(e) => e.message
      case Ledger(e)    => e.message
      case other        => other.toString

  final case class Config(
      destination: Path,
      request: MithrilClient.Request,
      immutable: HaskellImport.Config,
      ledger: LedgerSnapshotImport.Config
  )
  final case class Report(
      immutable: HaskellImport.Report,
      ledger: LedgerSnapshotImport.Report,
      artifact: MithrilClient.Artifact
  )

  def run[F[_]: Async](config: Config, client: MithrilClient[F])(using
      outer: Handle[F, Error],
      env: Ask[F, ConsensusMirrorEnv]
  ): F[Report] =
    val F = Async[F]
    val files = Files.forAsync[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = outer.raise(Error.Storage(error))
    given Raise[F, MithrilClient.Error] = new Raise[F, MithrilClient.Error]:
      def functor: Functor[F] = F
      def raise[E <: MithrilClient.Error, A](error: E): F[A] = outer.raise(Error.Client(error))
    given Raise[F, HaskellImportError] = new Raise[F, HaskellImportError]:
      def functor: Functor[F] = F
      def raise[E <: HaskellImportError, A](error: E): F[A] = outer.raise(Error.Immutable(error))

    def guard[A](what: String)(action: => A): F[A] = AtomicFile.guard[F, A](what)(action)
    def exists(path: Path): F[Boolean] =
      guard(path.toString)(JFiles.exists(path.toNioPath, LinkOption.NOFOLLOW_LINKS))
    def fresh(path: Path): F[Unit] = exists(path).flatMap(present =>
      F.whenA(present)(Error.DestinationExists(path.toString).raise[F, Unit])
    )
    def regular(path: Path): F[Unit] = guard(path.toString) {
      !JFiles.isSymbolicLink(path.toNioPath) &&
      (JFiles.isRegularFile(path.toNioPath, LinkOption.NOFOLLOW_LINKS) ||
        JFiles.isDirectory(path.toNioPath, LinkOption.NOFOLLOW_LINKS))
    }.flatMap(safe => F.unlessA(safe)(Error.UnsafeSource(path.toString).raise[F, Unit]))

    def separate(original: Path)(target: => java.nio.file.Path): F[Unit] =
      guard(original.toString) {
        val source = original.toNioPath.toRealPath()
        val destination = target
        !source.startsWith(destination) && !destination.startsWith(source)
      }.flatMap(disjoint =>
        F.unlessA(disjoint)(Error.UnsafeSource(original.toString).raise[F, Unit])
      )

    def copy(source: Path, target: Path): F[Unit] =
      files
        .walk(source)
        .evalMap { path =>
          val to = target / source.toNioPath.relativize(path.toNioPath).toString
          regular(path) *> guard(path.toString)(
            JFiles.isDirectory(path.toNioPath, LinkOption.NOFOLLOW_LINKS)
          ).flatMap {
            case true => guard(to.toString)(JFiles.createDirectories(to.toNioPath)).void
            case false =>
              files.createDirectories(to.parent.getOrElse(target)) *>
                files.readAll(path).through(files.writeAll(to)).compile.drain
          }
        }
        .compile
        .drain

    def source(work: Path): F[MithrilClient.Request] = config.request.source match
      case MithrilClient.Source.Download => F.pure(config.request)
      case MithrilClient.Source.Cached(original) =>
        val owned = work / "cached"
        copy(original, owned).as(config.request.copy(source = MithrilClient.Source.Cached(owned)))

    def ledger(directory: Path): F[Path] =
      val root = directory / "ledger"
      exists(root).flatMap {
        case false => Error.MissingLedger.raise[F, Path]
        case true =>
          files
            .list(root)
            .evalFilter(path =>
              regular(path) *> guard(path.toString)(
                JFiles.isDirectory(path.toNioPath, LinkOption.NOFOLLOW_LINKS)
              )
            )
            .compile
            .toVector
            .flatMap {
              case Vector(one) => F.pure(one)
              case Vector()    => Error.MissingLedger.raise[F, Path]
              case _           => Error.AmbiguousLedger.raise[F, Path]
            }
      }

    def prepare(stage: Path): F[Report] =
      val work = stage / ".mithril-work"
      for
        // Network magic was matched before stage creation. Record the configured identity before
        // work files, so strict importer preflight sees the fresh stage's identity.
        _ <- NetworkIdentityFile.write[F](stage, config.immutable.identity)
        _ <- guard(work.toString)(JFiles.createDirectory(work.toNioPath))
        requested <- source(work)
        verified <- client.acquire(requested, work)
        _ <- files.walk(verified.directory).evalMap(regular).compile.drain
        selected <- ledger(verified.directory)
        manifest = verified.directory / "ancillary_manifest.json"
        manifestExists <- exists(manifest)
        _ <- F.unlessA(manifestExists)(Error.MissingManifest.raise[F, Unit])
        // Official archives omit this operator file. The verified certificate network policy,
        // already matched to configured magic, supplies it only inside our owned import source.
        magic = verified.directory / "protocolMagicId"
        hasMagic <- exists(magic)
        _ <- F.unlessA(hasMagic)(guard(magic.toString) {
          JFiles.writeString(
            magic.toNioPath,
            config.immutable.identity.magic.toString,
            StandardOpenOption.CREATE_NEW
          )
          ()
        })
        imported <- HaskellImport
          .run[F](config.immutable.copy(haskellDb = verified.directory, db = stage), _ => F.unit)
        _ <- F.whenA(imported.truncation.nonEmpty)(Error.TruncatedImmutable.raise[F, Unit])
        trust = config.ledger.trust match
          case LedgerSnapshotImport.Trust.Verify(_, key) =>
            LedgerSnapshotImport.Trust.Verify(manifest, key)
          case LedgerSnapshotImport.Trust.Unverified => LedgerSnapshotImport.Trust.Unverified
        state <- Handle
          .allow[LedgerSnapshotImport.Error] {
            LedgerSnapshotImport.run[F](
              config.ledger.copy(ledger = selected, db = stage, trust = trust)
            )
          }
          .rescue(error => Error.Ledger(error).raise[F, LedgerSnapshotImport.Report])
        evidence = provenance(config, verified, state)
        _ <- guard(stage.toString) {
          val file = stage / "mithril-bootstrap.json"
          JFiles.writeString(
            file.toNioPath,
            evidence.noSpaces + "\n",
            StandardOpenOption.CREATE_NEW
          )
          Using.resource(FileChannel.open(file.toNioPath, StandardOpenOption.WRITE))(_.force(true))
        }
        // Required deletion runs in the normal effect before the final directory transaction.
        _ <- guard(work.toString)(RocksUtxoStore.deleteTree(work.toNioPath))
      yield Report(imported, state, verified.artifact)

    val operation = for
      _ <- fresh(config.destination)
      _ <- F.unlessA(config.request.network.magic == config.immutable.identity.magic)(
        Error
          .NetworkMismatch(config.request.network.magic, config.immutable.identity.magic)
          .raise[F, Unit]
      )
      // Reject overlap before ownership creates a parent or sibling lease inside the source.
      _ <- config.request.source match
        case MithrilClient.Source.Download => F.unit
        case MithrilClient.Source.Cached(original) =>
          separate(original)(config.destination.toNioPath.toFile.getCanonicalFile.toPath)
      report <- DatabaseOwnership.resource[F](config.destination).use { _ =>
        val stage = Resource.make(
          guard(config.destination.toString) {
            val requested = config.destination.toNioPath.toAbsolutePath.normalize()
            val parent = requested.getParent.toRealPath()
            Path.fromNioPath(
              JFiles.createTempDirectory(parent, s".${requested.getFileName}.mithril-")
            )
          }
        )(path => guard(path.toString)(RocksUtxoStore.deleteTree(path.toNioPath)))
        for
          destination <- guard(config.destination.toString) {
            val requested = config.destination.toNioPath.toAbsolutePath.normalize()
            Path.fromNioPath(requested.getParent.toRealPath().resolve(requested.getFileName))
          }
          _ <- fresh(destination)
          _ <- config.request.source match
            case MithrilClient.Source.Download => F.unit
            case MithrilClient.Source.Cached(original) =>
              separate(original)(destination.toNioPath)
          result <- stage.use(path =>
            prepare(path).flatTap(_ => publish(path, destination, DirectorySync.native[F]))
          )
        yield result
      }
    yield report
    operation.handleErrorWith {
      case error: java.io.IOException =>
        Error.Storage(StorageError.Io(config.destination.toString, error)).raise[F, Report]
      case error => F.raiseError(error)
    }

  private[storage] def provenance(
      config: Config,
      verified: MithrilClient.Verified,
      state: LedgerSnapshotImport.Report
  ): Json = Json.obj(
    "version" -> Json.fromInt(1),
    "artifact" -> Json.fromString(verified.artifact.text),
    "verifierSource" -> Json.fromString(MithrilClient.SourceRevision),
    "verifierSha256" -> Json.fromString(verified.verifier.hex),
    "genesisVerificationKeyInputSha256" -> Json.fromString(
      java.util.HexFormat
        .of()
        .formatHex(
          java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(config.request.roots.genesis.text.getBytes("UTF-8"))
        )
    ),
    "ancillaryManifestHash" -> (state.evidence match
      case LedgerSnapshotImport.Evidence.Verified(hash, _) => Json.fromString(hash.hex)
      case LedgerSnapshotImport.Evidence.Unverified        => Json.Null),
    "ancillaryVerificationKey" -> (state.evidence match
      case LedgerSnapshotImport.Evidence.Verified(_, key) => Json.fromString(key.hex)
      case LedgerSnapshotImport.Evidence.Unverified       => Json.Null),
    "networkMagic" -> Json.fromLong(config.immutable.identity.magic),
    "certificateNetwork" -> Json.fromString(verified.network.text),
    "certificateHash" -> Json.fromString(verified.certificate.text),
    "ledgerSlot" -> Json.fromBigInt(state.anchor.slot),
    "ledgerHash" -> Json.fromString(state.anchor.hash.hex),
    "mithrilArtifactVerified" -> Json.True,
    "ancillaryFilesMithrilCertified" -> Json.False,
    "ancillaryVerified" -> Json.fromBoolean(state.evidence match
      case LedgerSnapshotImport.Evidence.Verified(_, _) => true
      case LedgerSnapshotImport.Evidence.Unverified     => false)
  )

  private[storage] trait DirectorySync[F[_]]:
    def apply(path: Path): F[Unit]

  private[storage] object DirectorySync:
    def native[F[_]: Async](using Raise[F, StorageError]): DirectorySync[F] =
      new DirectorySync[F]:
        def apply(path: Path): F[Unit] = AtomicFile.guard[F, Unit](path.toString) {
          Using.resource(FileChannel.open(path.toNioPath, StandardOpenOption.READ))(_.force(true))
        }

  /** Publication is the last transition; no required work is deferred to a Resource release. */
  private[storage] def publish[F[_]: Async](stage: Path, destination: Path, sync: DirectorySync[F])(
      using errors: Handle[F, Error]
  ): F[Unit] =
    val F = Async[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E <: StorageError, A](error: E): F[A] = errors.raise(Error.Storage(error))
    def guard[A](action: => A): F[A] = AtomicFile.guard[F, A](destination.toString)(action)
    F.uncancelable { _ =>
      guard(JFiles.exists(destination.toNioPath, LinkOption.NOFOLLOW_LINKS)).flatMap { present =>
        F.whenA(present)(Error.DestinationExists(destination.toString).raise[F, Unit])
      } *> sync(stage) *>
        guard(
          JFiles.move(stage.toNioPath, destination.toNioPath, StandardCopyOption.ATOMIC_MOVE)
        ).void *>
        errors.handleWith(sync(destination.parent.getOrElse(Path(".")))) { primary =>
          errors.handleWith(guard(RocksUtxoStore.deleteTree(destination.toNioPath)))(_ => F.unit) *>
            primary.raise[F, Unit]
        }
    }
