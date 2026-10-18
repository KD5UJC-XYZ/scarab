// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.mithril

import cats.effect.Async
import cats.effect.implicits.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.io.process.{ProcessBuilder, Processes}
import java.net.URI
import java.nio.file.{Files as JFiles, LinkOption}
import java.nio.file.attribute.PosixFilePermissions
import java.io.IOException
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.haskell.state.AncillaryManifest

/** The official verifier owns certificate-chain and immutable Merkle verification. Its exit code
  * alone is insufficient: at 769d862, cardano_db/verify.rs:161-170 returns success after reporting
  * an immutable verification error. Only the explicit successful JSON receipt proves completion.
  */
trait MithrilClient[F[_]]:
  def acquire(request: MithrilClient.Request, work: Path)(using
      Raise[F, MithrilClient.Error]
  ): F[MithrilClient.Verified]

object MithrilClient:
  val SourceRevision = "769d862dc2d7941192dac9088d1d86df967bf5cc"
  val Version = "0.13.28"
  val BinarySha256 = "43e126e151d6407b258e2ab37399b8fae4fe7e953f4e3f5366fdf133c1f2ca46"
  private val MaxOutput = 4 * 1024 * 1024

  enum Error:
    case InvalidArtifact, InvalidEndpoint, InvalidKey
    case NotRegular(path: String)
    case ExecutableDigest(expected: String, actual: String)
    case Launch(cause: IOException)
    case Defect(cause: Throwable)
    case TimedOut, OutputLimit
    case Exit(code: Int)
    case InvalidReceipt, RefusedVerification, MissingReceipt, AmbiguousReceipt
    case WrongDirectory(expected: String, actual: String)
    case WrongNetwork(expected: Network, actual: String)
    case WrongArtifact(expected: String, actual: String)
    case UnsupportedNetwork(magic: Long)
    def message: String = this match
      case Defect(cause) => s"Mithril verifier defect: ${cause.toString}"
      case other         => other.toString

  opaque type Artifact = String
  object Artifact:
    def parse(text: String): Either[Error, Artifact] =
      Either.cond(
        text.length == 64 && text.forall(c => c >= '0' && c <= '9' || c >= 'a' && c <= 'f'),
        text,
        Error.InvalidArtifact
      )
    extension (id: Artifact) def text: String = id

  opaque type Endpoint = String
  object Endpoint:
    def parse(text: String): Either[Error, Endpoint] =
      Either.catchNonFatal(URI.create(text)).leftMap(_ => Error.InvalidEndpoint).flatMap { uri =>
        val local = Set("127.0.0.1", "localhost", "[::1]").contains(uri.getHost)
        Either.cond(
          uri.getHost != null && uri.getUserInfo == null && uri.getFragment == null &&
            (uri.getScheme == "https" || uri.getScheme == "http" && local),
          text,
          Error.InvalidEndpoint
        )
      }
    extension (endpoint: Endpoint) def text: String = endpoint

  opaque type VerificationKey = String
  object VerificationKey:
    def parse(text: String): Either[Error, VerificationKey] =
      AncillaryManifest.verificationKey(text.trim).leftMap(_ => Error.InvalidKey).as(text.trim)
    extension (key: VerificationKey) def text: String = key

  enum Network(val text: String, val magic: Long):
    case Mainnet extends Network("mainnet", 764824073L)
    case Preprod extends Network("preprod", 1L)
    case Preview extends Network("preview", 2L)
  object Network:
    def fromMagic(magic: Long): Either[Error, Network] =
      Network.values.find(_.magic == magic).toRight(Error.UnsupportedNetwork(magic))

  final case class Roots(genesis: VerificationKey, ancillary: VerificationKey)
  final case class Executable private[storage] (path: Path, digest: Bytes)
  object Executable:
    def pinned(path: Path): Executable =
      // This fixed hexadecimal literal is decoded without a fallible operator-input path.
      Executable(path, Bytes.wrap(java.util.HexFormat.of().parseHex(BinarySha256)))

  enum Source:
    case Download

    /** A private staged copy, already isolated from the caller's original cached database. */
    case Cached(directory: Path)

  final case class Request(
      artifact: Artifact,
      endpoint: Endpoint,
      roots: Roots,
      network: Network,
      executable: Executable,
      source: Source,
      timeout: FiniteDuration = 30.minutes
  )
  final case class Verified private[storage] (
      directory: Path,
      artifact: Artifact,
      network: Network,
      certificate: Artifact,
      verifier: Bytes
  )
  final case class Receipt(
      directory: String,
      network: String,
      certificate: Artifact,
      artifact: Artifact
  )

  /** CardanoDatabase.compute_hash at the pinned common/entities/cardano_database.rs:82-88. */
  private[scarab] def artifact(epoch: BigInt, root: Artifact): Artifact =
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    hash.update(java.nio.ByteBuffer.allocate(8).putLong(epoch.toLong).array())
    hash.update(Artifact.text(root).getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    java.util.HexFormat.of().formatHex(hash.digest())

  enum ReceiptKind(val field: String):
    case Download extends ReceiptKind("db_directory")
    case Verify extends ReceiptKind("verified_db_directory")

  /** Unknown event fields are allowed; malformed JSON, explicit refusal and ambiguous success
    * receipts are not. Receipt paths must identify the owned directory, never a caller's database.
    */
  private[scarab] def receipt(output: String, kind: ReceiptKind): Either[Error, Receipt] =
    output.linesIterator
      .filter(_.nonEmpty)
      .toVector
      .traverse(line =>
        io.circe.jawn
          .JawnParser(allowDuplicateKeys = false)
          .parse(line)
          .leftMap(_ => Error.InvalidReceipt)
      )
      .flatMap { events =>
        if events.exists(_.hcursor.downField("verify_error").succeeded) then
          Left(Error.RefusedVerification)
        else
          events.filter(_.hcursor.downField(kind.field).succeeded) match
            case Vector(one) =>
              val cursor = one.hcursor
              val decoded = for
                directory <- cursor.get[String](kind.field)
                network <- cursor.get[String]("certificate_network")
                certificate <- cursor.get[String]("certificate_hash")
                epoch <- cursor.get[BigInt]("certificate_epoch")
                root <- cursor.get[String]("certificate_merkle_root")
              yield (directory, network, certificate, epoch, root)
              decoded.leftMap(_ => Error.InvalidReceipt).flatMap {
                case (directory, network, certificate, epoch, root) =>
                  for
                    cert <- Artifact.parse(certificate).leftMap(_ => Error.InvalidReceipt)
                    merkle <- Artifact.parse(root).leftMap(_ => Error.InvalidReceipt)
                    _ <- Either.cond(epoch >= 0 && epoch.bitLength <= 64, (), Error.InvalidReceipt)
                  yield Receipt(directory, network, cert, artifact(epoch, merkle))
              }
            case Vector() => Left(Error.MissingReceipt)
            case _        => Left(Error.AmbiguousReceipt)
      }

  def official[F[_]: Async]: MithrilClient[F] = new MithrilClient[F]:
    private val F = Async[F]
    private val files = Files.forAsync[F]
    private def boundary[A](action: F[A])(using Raise[F, Error]): F[A] =
      action.handleErrorWith {
        case e: IOException => Error.Launch(e).raise[F, A]
        case e              => Error.Defect(e).raise[F, A]
      }

    def acquire(request: Request, work: Path)(using Raise[F, Error]): F[Verified] =
      val expected = request.source match
        case Source.Download          => work / "download" / "db"
        case Source.Cached(directory) => directory
      val kind = request.source match
        case Source.Download  => ReceiptKind.Download
        case Source.Cached(_) => ReceiptKind.Verify
      val operation = request.source match
        case Source.Download =>
          List(
            "download",
            Artifact.text(request.artifact),
            "--download-dir",
            (work / "download").toString,
            "--include-ancillary",
            "--ancillary-verification-key",
            VerificationKey.text(request.roots.ancillary)
          )
        case Source.Cached(directory) =>
          List("verify", Artifact.text(request.artifact), "--db-dir", directory.toString)
      val program = work / "mithril-client"
      val isolated = work / "config"
      val args = List(
        "--json",
        "--config-directory",
        isolated.toString,
        "--aggregator-endpoint",
        Endpoint.text(request.endpoint),
        "cardano-db"
      ) ++ operation ++ List(
        "--genesis-verification-key",
        VerificationKey.text(request.roots.genesis)
      )

      // Drain both pipes even after the bound, retaining only enough to report the limit. Stopping
      // a reader early would let the child block on its pipe while its owner waits for exit.
      def collect(stream: Stream[F, Byte]): F[Vector[Byte]] =
        stream.chunks.compile.fold(Vector.empty[Byte]) { (kept, chunk) =>
          kept ++ chunk.take((MaxOutput + 1 - kept.size).max(0)).toVector
        }
      for
        regular <- boundary(
          F.blocking(
            JFiles.isRegularFile(request.executable.path.toNioPath, LinkOption.NOFOLLOW_LINKS)
          )
        )
        _ <- F.unlessA(regular)(Error.NotRegular(request.executable.path.toString).raise[F, Unit])
        // Hash the bytes copied, then execute only the private copy, avoiding verify/read races.
        digest <- boundary(
          files
            .readAll(request.executable.path)
            .observe(files.writeAll(program))
            .through(fs2.hashing.Hashing.forSync[F].hash(fs2.hashing.HashAlgorithm.SHA256))
            .compile
            .lastOrError
        )
        actual = Bytes.wrap(digest.bytes.toArray)
        _ <- F.unlessA(actual == request.executable.digest)(
          Error.ExecutableDigest(request.executable.digest.hex, actual.hex).raise[F, Unit]
        )
        _ <- boundary(F.blocking {
          JFiles.setPosixFilePermissions(
            program.toNioPath,
            PosixFilePermissions.fromString("r-x------")
          )
          JFiles.createDirectories(isolated.toNioPath)
          ()
        })
        builder = ProcessBuilder(program.toString, args)
          .withInheritEnv(false)
          .withExtraEnv(Map("HOME" -> work.toString, "LANG" -> "C.UTF-8"))
          .withWorkingDirectory(work)
        result <- boundary(Processes.forAsync[F].spawn(builder).use { process =>
          (collect(process.stdout), collect(process.stderr), process.exitValue).parTupled
        }).timeoutTo(request.timeout, Error.TimedOut.raise[F, (Vector[Byte], Vector[Byte], Int)])
        (stdout, _, code) = result
        _ <- F.unlessA(stdout.size <= MaxOutput)(
          Error.OutputLimit.raise[F, Unit]
        )
        _ <- F.unlessA(code == 0)(Error.Exit(code).raise[F, Unit])
        reported <- receipt(
          new String(stdout.toArray, java.nio.charset.StandardCharsets.UTF_8),
          kind
        ).fold(_.raise[F, Receipt], F.pure)
        _ <- F.unlessA(reported.network == request.network.text)(
          Error.WrongNetwork(request.network, reported.network).raise[F, Unit]
        )
        _ <- F.unlessA(reported.artifact == request.artifact)(
          Error
            .WrongArtifact(Artifact.text(request.artifact), Artifact.text(reported.artifact))
            .raise[F, Unit]
        )
        canonical <- boundary(F.blocking(expected.toNioPath.toRealPath()))
        _ <- F.unlessA(reported.directory == canonical.toString)(
          Error.WrongDirectory(canonical.toString, reported.directory).raise[F, Unit]
        )
      yield Verified(expected, request.artifact, request.network, reported.certificate, actual)
