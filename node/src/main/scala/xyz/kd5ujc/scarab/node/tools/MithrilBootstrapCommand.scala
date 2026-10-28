// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.haskell.{ChunkSlots, HaskellImport}
import xyz.kd5ujc.scarab.storage.mithril.{MithrilBootstrap, MithrilClient}

object MithrilBootstrapCommand:
  val Usage =
    "usage: import-mithril --artifact <hash> --aggregator <url> --genesis-verification-key <file> --ancillary-verification-key <file> --mithril-client <pinned executable> --db <fresh directory> (--config <file> | --genesis-dir <dir>) [--source-db <cached Haskell database>] [--timeout-seconds N]"

  enum Problem:
    case Missing(flag: String)
    case Duplicate(flag: String)
    case Unknown(flag: String)
    case NeedsValue(flag: String)
    case InvalidPath(flag: String)
    case InvalidClient(error: MithrilClient.Error)
    case InvalidTimeout, ConflictingGenesis, InvalidChunkSize
    case Setup(error: ConfigError)
    def message: String = this match
      case Setup(error) => error.message
      case other        => other.toString

  final case class Options(
      artifact: MithrilClient.Artifact,
      endpoint: MithrilClient.Endpoint,
      genesisKey: Path,
      ancillaryKey: Path,
      executable: Path,
      db: Path,
      config: Option[Path],
      genesis: Option[Path],
      source: Option[Path],
      timeout: FiniteDuration
  )
  private val Flags = Set(
    "artifact",
    "aggregator",
    "genesis-verification-key",
    "ancillary-verification-key",
    "mithril-client",
    "db",
    "config",
    "genesis-dir",
    "source-db",
    "timeout-seconds"
  )

  def parse(args: List[String]): Either[Problem, Options] =
    @annotation.tailrec
    def loop(
        rest: List[String],
        values: Map[String, String]
    ): Either[Problem, Map[String, String]] = rest match
      case Nil => Right(values)
      case flag :: value :: tail if flag.startsWith("--") && !value.startsWith("--") =>
        val name = flag.drop(2)
        if !Flags.contains(name) then Left(Problem.Unknown(flag))
        else if values.contains(name) then Left(Problem.Duplicate(flag))
        else loop(tail, values.updated(name, value))
      case flag :: _ => Left(Problem.NeedsValue(flag))
    def path(flag: String, text: String): Either[Problem, Path] =
      Either
        .catchNonFatal(Path.of(text).toAbsolutePath.normalize())
        .leftMap(_ => Problem.InvalidPath(flag))
    for
      values <- loop(args, Map.empty)
      artifact <- values
        .get("artifact")
        .toRight(Problem.Missing("artifact"))
        .flatMap(MithrilClient.Artifact.parse(_).leftMap(Problem.InvalidClient(_)))
      endpoint <- values
        .get("aggregator")
        .toRight(Problem.Missing("aggregator"))
        .flatMap(MithrilClient.Endpoint.parse(_).leftMap(Problem.InvalidClient(_)))
      genesisKey <- values
        .get("genesis-verification-key")
        .toRight(Problem.Missing("genesis-verification-key"))
        .flatMap(path("genesis-verification-key", _))
      ancillaryKey <- values
        .get("ancillary-verification-key")
        .toRight(Problem.Missing("ancillary-verification-key"))
        .flatMap(path("ancillary-verification-key", _))
      executable <- values
        .get("mithril-client")
        .toRight(Problem.Missing("mithril-client"))
        .flatMap(path("mithril-client", _))
      db <- values.get("db").toRight(Problem.Missing("db")).flatMap(path("db", _))
      config <- values.get("config").traverse(path("config", _))
      genesis <- values.get("genesis-dir").traverse(path("genesis-dir", _))
      _ <- Either.cond(
        config.isDefined || genesis.isDefined,
        (),
        Problem.Missing("config or genesis-dir")
      )
      source <- values.get("source-db").traverse(path("source-db", _))
      timeout <- values
        .get("timeout-seconds")
        .fold[Either[Problem, FiniteDuration]](Right(30.minutes)) { text =>
          text.toLongOption
            .filter(n => n > 0 && n <= 7 * 24 * 60 * 60)
            .toRight(Problem.InvalidTimeout)
            .map(_.seconds)
        }
    yield Options(
      artifact,
      endpoint,
      genesisKey,
      ancillaryKey,
      executable,
      db,
      config,
      genesis,
      source,
      timeout
    )

  private def inputs(options: Options): Either[Problem, (NodeSetup, MithrilBootstrap.Config)] =
    for
      genesis <- Either
        .catchNonFatal(Files.readString(options.genesisKey))
        .leftMap(e =>
          Problem.Setup(ConfigError(ConfigProblem.FileRead("Mithril verification key", e)))
        )
        .flatMap(MithrilClient.VerificationKey.parse(_).leftMap(Problem.InvalidClient(_)))
      ancillary <- Either
        .catchNonFatal(Files.readString(options.ancillaryKey))
        .leftMap(e =>
          Problem.Setup(ConfigError(ConfigProblem.FileRead("Mithril verification key", e)))
        )
        .flatMap(MithrilClient.VerificationKey.parse(_).leftMap(Problem.InvalidClient(_)))
      imported <- LedgerSnapshotImportCommand
        .inputs(
          LedgerSnapshotImportCommand.Options(
            Path.of("."),
            options.db,
            options.config,
            options.genesis,
            Some(Path.of(".")),
            Some(options.ancillaryKey),
            false
          )
        )
        .leftMap(Problem.Setup(_))
      (setup, ledger) = imported
      slots <- ChunkSlots
        .fromSecurityParam(
          setup.byron.fold(setup.genesis.geometry.k.toLong)(_.securityParam.toLong)
        )
        .toRight(Problem.InvalidChunkSize)
      source = options.source.fold[MithrilClient.Source](MithrilClient.Source.Download)(p =>
        MithrilClient.Source.Cached(FsPath.fromNioPath(p))
      )
      network <- MithrilClient.Network
        .fromMagic(setup.identity.magic)
        .leftMap(Problem.InvalidClient(_))
      request = MithrilClient.Request(
        options.artifact,
        options.endpoint,
        MithrilClient.Roots(genesis, ancillary),
        network,
        MithrilClient.Executable.pinned(FsPath.fromNioPath(options.executable)),
        source,
        options.timeout
      )
      immutable = HaskellImport.Config(
        FsPath("."),
        FsPath.fromNioPath(options.db),
        setup.identity,
        setup.geometry.byron.epochSlots,
        slots
      )
    yield setup -> MithrilBootstrap.Config(
      FsPath.fromNioPath(options.db),
      request,
      immutable,
      ledger
    )

  def run(args: List[String]): IO[ExitCode] = parse(args) match
    case Left(problem) => IO.println(s"${problem.message}\n$Usage").as(ExitCode(2))
    case Right(options) =>
      IO.blocking(inputs(options)).flatMap {
        case Left(problem) => IO.println(problem.message).as(ExitCode(2))
        case Right((setup, config)) =>
          given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(IO.pure(Map.empty))
          Handle
            .allow[MithrilBootstrap.Error] {
              MithrilBootstrap.run[IO](config, MithrilClient.official[IO]).map(Some(_))
            }
            .rescue(error => log("error", "message" -> Json.fromString(error.message)).as(None))
            .flatMap {
              case None => IO.pure(ExitCode(1))
              case Some(report) =>
                log(
                  "done",
                  "artifact" -> Json.fromString(report.artifact.text),
                  "slot" -> Json.fromBigInt(report.ledger.anchor.slot),
                  "hash" -> Json.fromString(report.ledger.anchor.hash.hex),
                  "outputs" -> Json.fromLong(report.ledger.count)
                ).as(ExitCode.Success)
            }
      }

  private def log(event: String, fields: (String, Json)*): IO[Unit] =
    IO.println(
      Json
        .obj(
          (Vector(
            "kind" -> Json.fromString("importMithril"),
            "event" -> Json.fromString(event)
          ) ++ fields)*
        )
        .noSpaces
    )
