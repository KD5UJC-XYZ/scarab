// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import xyz.kd5ujc.scarab.storage.haskell.{ChunkSlots, HaskellImport, HaskellImportError}
import cats.effect.{ExitCode, IO}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import java.nio.file.Path
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.node.NodeSetup

/** `import-haskell-db --haskell-db <dir> (--genesis-dir <dir> | --config <config.json>) --db <dir>
  * [--stop-slot N]`
  *
  * Reads the ImmutableDB of a Haskell node (`<haskell-db>/immutable`, never written) and appends
  * its blocks to our immutable store under `--db`, so that `follow --db <dir>` replays them locally
  * instead of downloading them. The chain links, the block numbers, the header hashes and the
  * protocol magic are checked; a database of another network is refused. Re-running it continues
  * after the blocks already stored.
  */
object HaskellImportCommand:
  val Usage: String =
    "usage: import-haskell-db --haskell-db <dir> (--genesis-dir <dir> | --config <config.json>) --db <dir> [--stop-slot N]"

  final case class Options(
      haskellDb: Path,
      db: Path,
      genesisDir: Option[Path],
      config: Option[Path],
      stopSlot: Option[Long]
  )

  private val Flags = Set("haskell-db", "genesis-dir", "config", "db", "stop-slot")

  def parse(args: List[String]): Either[ConfigError, Options] =
    def bad[A](problem: ConfigProblem): Either[ConfigError, A] = Left(
      ConfigError(ConfigProblem.WithUsage(problem, Usage))
    )
    @annotation.tailrec
    def go(rest: List[String], acc: Map[String, String]): Either[ConfigError, Map[String, String]] =
      rest match
        case Nil => Right(acc)
        case flag :: value :: tail if flag.startsWith("--") =>
          val name = flag.drop(2)
          if !Flags.contains(name) then bad(ConfigProblem.UnknownFlag(flag))
          else if acc.contains(name) then bad(ConfigProblem.DuplicateFlag(flag))
          else go(tail, acc.updated(name, value))
        case flag :: Nil if flag.startsWith("--") => bad(ConfigProblem.MissingFlagValue(flag))
        case other :: _                           => bad(ConfigProblem.UnexpectedArgument(other))
    for
      flags <- go(args, Map.empty)
      haskell <- flags
        .get("haskell-db")
        .toRight(
          ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlag("--haskell-db"), Usage))
        )
      db <- flags
        .get("db")
        .toRight(ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlag("--db"), Usage)))
      _ <- Either.cond(
        flags.contains("genesis-dir") || flags.contains("config"),
        (),
        ConfigError(
          ConfigProblem.WithUsage(
            ConfigProblem.RequiredOneOf(Set("--genesis-dir", "--config")),
            Usage
          )
        )
      )
      stop <- flags.get("stop-slot").traverse { t =>
        t.toLongOption
          .filter(_ >= 0)
          .toRight(
            ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue("--stop-slot", t), Usage))
          )
      }
    yield Options(
      Path.of(haskell),
      Path.of(db),
      flags.get("genesis-dir").map(Path.of(_)),
      flags.get("config").map(Path.of(_)),
      stop
    )

  def run(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(e) => IO.println(e.message).as(ExitCode(2))
      case Right(options) =>
        IO.blocking(setupOf(options)).flatMap {
          case Left(e) => IO.println(e.message).as(ExitCode(2))
          case Right((setup, chunkSlots)) =>
            val config = HaskellImport.Config(
              FsPath.fromNioPath(options.haskellDb),
              FsPath.fromNioPath(options.db),
              setup.identity,
              setup.geometry.byron.epochSlots,
              chunkSlots,
              options.stopSlot
            )
            Handle
              .allow[HaskellImportError] {
                HaskellImport.run[IO](config, progress).map(Some(_))
              }
              .rescue(e => log("error", "message" -> Json.fromString(e.message)).as(None))
              .flatMap {
                case Some(r) =>
                  log(
                    "done",
                    "appended" -> Json.fromLong(r.appended),
                    "skipped" -> Json.fromLong(r.skipped),
                    "chunks" -> Json.fromInt(r.chunks),
                    "tipSlot" -> r.tip.fold(Json.Null)(t => Json.fromBigInt(t.slot)),
                    "tipHash" -> r.tip.fold(Json.Null)(t => Json.fromString(t.hash.hex)),
                    "tipBlockNo" -> r.tip.fold(Json.Null)(t => Json.fromBigInt(t.blockNo)),
                    "stoppedAtSlot" -> Json.fromBoolean(r.stoppedAtSlot),
                    "ignoredEntries" -> Json.fromInt(r.truncation.fold(0)(_._2.entries)),
                    "ignoredBytes" -> Json.fromLong(r.truncation.fold(0L)(_._2.bytes)),
                    "ignoredReason" -> r.truncation
                      .flatMap(_._2.cause)
                      .fold(Json.Null)(c => Json.fromString(c.message)),
                    "seconds" -> Json.fromLong(r.elapsed.toSeconds)
                  ).as(ExitCode.Success)
                case None => IO.pure(ExitCode(1))
              }
        }

  /** The network setup and the chunk size (10 * k of the first era). */
  private def setupOf(options: Options): Either[ConfigError, (NodeSetup, ChunkSlots)] =
    for
      setup <- options.config match
        case Some(file) => NodeSetup.fromConfigFile(file, options.genesisDir)
        case None =>
          options.genesisDir
            .toRight(ConfigError(ConfigProblem.MissingGenesis))
            .flatMap(NodeSetup.fromDirectory)
      // Use 10 * k of the first era, including the Byron genesis when the chain starts there.
      chunkSlots <- ChunkSlots
        .fromSecurityParam(
          setup.byron.fold(setup.genesis.geometry.securityParam.toLong)(_.securityParam.toLong)
        )
        .toRight(ConfigError(ConfigProblem.MissingChunkSize))
    yield (setup, chunkSlots)

  private def progress(p: HaskellImport.Progress): IO[Unit] =
    log(
      "progress",
      "appended" -> Json.fromLong(p.appended),
      "skipped" -> Json.fromLong(p.skipped),
      "chunks" -> Json.fromInt(p.chunks),
      "slot" -> Json.fromLong(p.slot)
    )

  private def log(event: String, fields: (String, Json)*): IO[Unit] =
    IO.println(
      Json
        .obj(
          ("kind", Json.fromString("importHaskellDb")) +: (
            "event",
            Json.fromString(event)
          ) +: fields*
        )
        .noSpaces
    )
