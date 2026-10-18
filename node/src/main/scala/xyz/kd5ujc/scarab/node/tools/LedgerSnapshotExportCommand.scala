// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Json
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.state.SlotNo
import xyz.kd5ujc.scarab.storage.LedgerSnapshotExport

object LedgerSnapshotExportCommand:
  val Usage = "usage: export-ledger-snapshot --db <dir> --out <ledger dir> [--slot N]"
  private val Flags = Set("--db", "--out", "--slot")

  def parse(args: List[String]): Either[ConfigError, LedgerSnapshotExport.Config] =
    def bad[A](problem: ConfigProblem): Either[ConfigError, A] = Left(
      ConfigError(ConfigProblem.WithUsage(problem, Usage))
    )
    @annotation.tailrec
    def loop(
        rest: List[String],
        values: Map[String, String]
    ): Either[ConfigError, Map[String, String]] = rest match
      case Nil => Right(values)
      case flag :: value :: tail
          if Flags.contains(flag) && !values.contains(flag) && !value.startsWith("--") =>
        loop(tail, values.updated(flag, value))
      case flag :: _ if values.contains(flag) => bad(ConfigProblem.DuplicateFlag(flag))
      case flag :: _ if Flags.contains(flag)  => bad(ConfigProblem.MissingFlagValue(flag))
      case flag :: _                          => bad(ConfigProblem.UnknownFlag(flag))
    def path(text: String): Either[ConfigError, Path] =
      Either
        .catchNonFatal(java.nio.file.Path.of(text))
        .leftMap(e => ConfigError(ConfigProblem.InvalidPath(text, e)))
        .map(Path.fromNioPath)
    for
      values <- loop(args, Map.empty)
      db <- values.get("--db").toRight(ConfigError(ConfigProblem.MissingFlag("--db"))).flatMap(path)
      out <- values
        .get("--out")
        .toRight(ConfigError(ConfigProblem.MissingFlag("--out")))
        .flatMap(path)
      slot <- values.get("--slot").traverse { text =>
        for
          _ <- Either.cond(
            text.nonEmpty && text.forall(c => c >= '0' && c <= '9'),
            (),
            ConfigError(ConfigProblem.FlagValue("--slot", text))
          )
          number <- Either
            .catchNonFatal(BigInt(text))
            .leftMap(_ => ConfigError(ConfigProblem.FlagValue("--slot", text)))
          slot <- SlotNo
            .fromBigInt(number)
            .leftMap(_ => ConfigError(ConfigProblem.FlagValue("--slot", text)))
        yield slot
      }
    yield LedgerSnapshotExport.Config(db, out, slot)

  def run(args: List[String]): IO[ExitCode] = parse(args) match
    case Left(error) => IO.println(error.message).as(ExitCode(2))
    case Right(config) =>
      Handle
        .allow[LedgerSnapshotExport.Error](LedgerSnapshotExport.run[IO](config).map(Some(_)))
        .rescue(error => log("error", "message" -> Json.fromString(error.message)).as(None))
        .flatMap {
          case None => IO.pure(ExitCode(1))
          case Some(report) =>
            log(
              "done",
              "slot" -> Json.fromBigInt(report.anchor.slot),
              "hash" -> Json.fromString(report.anchor.hash.hex),
              "era" -> Json.fromString(report.era.toString),
              "outputs" -> Json.fromLong(report.outputs),
              "directory" -> Json.fromString(report.directory.toString)
            ).as(ExitCode.Success)
        }

  private def log(event: String, fields: (String, Json)*): IO[Unit] =
    IO.println(
      Json
        .obj(
          (Vector(
            "kind" -> Json.fromString("exportLedgerSnapshot"),
            "event" -> Json.fromString(event)
          ) ++ fields)*
        )
        .noSpaces
    )
