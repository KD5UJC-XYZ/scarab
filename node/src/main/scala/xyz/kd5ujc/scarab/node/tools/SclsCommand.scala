// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import java.nio.file.{Path, Paths}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor as CborCodec}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.state.SlotNo
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.scls.{
  SclsBase,
  SclsEntrySorter,
  SclsExport,
  SclsImport,
  SclsInterchange,
  SclsRecord,
  SclsStreaming
}

/** Offline SCLS file commands. Parsing is strict; the base point and identity come from explicit
  * local node configuration/genesis inputs and the caller-specified exact immutable block.
  */
object SclsCommand:
  private val ExportUsage =
    "usage: export-scls --db <complete base> --scratch <external dir> (--config <file> [--genesis-dir <override>] | --genesis-dir <dir>) --slot N --block-no N --block-hash <64 hex> --out <file> [--record-bytes N] [--comment <text>]"
  private val ImportUsage =
    "usage: import-scls --db <complete base> --scratch <external dir> (--config <file> [--genesis-dir <override>] | --genesis-dir <dir>) --slot N --block-no N --block-hash <64 hex> --input <file> --target-db <existing target db> [--record-bytes N]"

  private val DefaultRecordBytes = 16 * 1024 * 1024
  private val MaxRecordBytes = 256 * 1024 * 1024
  private val SortKeyAllowance = 4096

  private[tools] final case class BaseOptions(
      db: Path,
      scratch: Path,
      config: Option[Path],
      genesis: Option[Path],
      anchor: BlockRef
  )
  final case class ExportOptions(
      base: BaseOptions,
      out: Path,
      comment: Option[String],
      recordBytes: Int
  )
  final case class ImportOptions(base: BaseOptions, input: Path, target: Path, recordBytes: Int)

  private val CommonFlags = Set(
    "--db",
    "--scratch",
    "--config",
    "--genesis-dir",
    "--slot",
    "--block-no",
    "--block-hash",
    "--record-bytes"
  )
  private val ExportFlags = CommonFlags ++ Set("--out", "--comment")
  private val ImportFlags = CommonFlags ++ Set("--input", "--target-db")

  def parseExport(args: List[String]): Either[ConfigError, ExportOptions] =
    for
      values <- parseFlags(args, ExportFlags, ExportUsage)
      base <- parseBase(values, ExportUsage)
      out <- requiredPath(values, "--out", ExportUsage)
      comment = values.get("--comment")
      recordBytes <- parseRecordBytes(values, ExportUsage)
    yield ExportOptions(base, out, comment, recordBytes)

  def parseImport(args: List[String]): Either[ConfigError, ImportOptions] =
    for
      values <- parseFlags(args, ImportFlags, ImportUsage)
      base <- parseBase(values, ImportUsage)
      input <- requiredPath(values, "--input", ImportUsage)
      target <- requiredPath(values, "--target-db", ImportUsage)
      recordBytes <- parseRecordBytes(values, ImportUsage)
    yield ImportOptions(base, input, target, recordBytes)

  private def parseFlags(
      args: List[String],
      flags: Set[String],
      usage: String
  ): Either[ConfigError, Map[String, String]] =
    def bad[A](problem: ConfigProblem): Either[ConfigError, A] =
      Left(ConfigError(ConfigProblem.WithUsage(problem, usage)))
    @annotation.tailrec
    def loop(
        rest: List[String],
        values: Map[String, String]
    ): Either[ConfigError, Map[String, String]] =
      rest match
        case Nil => Right(values)
        case flag :: value :: tail
            if flags.contains(flag) && !values.contains(flag) && !value.startsWith("--") =>
          loop(tail, values.updated(flag, value))
        case flag :: _ if values.contains(flag) => bad(ConfigProblem.DuplicateFlag(flag))
        case flag :: _ if flags.contains(flag)  => bad(ConfigProblem.MissingFlagValue(flag))
        case flag :: _                          => bad(ConfigProblem.UnknownFlag(flag))
    loop(args, Map.empty)

  private def parseBase(
      values: Map[String, String],
      usage: String
  ): Either[ConfigError, BaseOptions] =
    def required(name: String): Either[ConfigError, String] =
      values
        .get(name)
        .toRight(ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlag(name), usage)))
    def path(name: String): Either[ConfigError, Path] =
      required(name).flatMap(parsePath)
    def unsigned(name: String): Either[ConfigError, BigInt] =
      required(name).flatMap { value =>
        Either
          .cond(value.nonEmpty && value.forall(c => c >= '0' && c <= '9'), value, ())
          .flatMap(text => Either.catchNonFatal(BigInt(text)).leftMap(_ => ()))
          .leftMap(_ =>
            ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(name, value), usage))
          )
      }
    for
      db <- path("--db")
      scratch <- path("--scratch")
      _ <- Either.cond(
        values.contains("--config") || values.contains("--genesis-dir"),
        (),
        ConfigError(
          ConfigProblem.WithUsage(
            ConfigProblem.RequiredOneOf(Set("--config", "--genesis-dir")),
            usage
          )
        )
      )
      config <- values.get("--config").traverse(parsePath)
      genesis <- values.get("--genesis-dir").traverse(parsePath)
      slotValue <- unsigned("--slot")
      slot <- SlotNo
        .fromBigInt(slotValue)
        .leftMap(_ =>
          ConfigError(
            ConfigProblem.WithUsage(ConfigProblem.FlagValue("--slot", slotValue.toString), usage)
          )
        )
      blockNo <- unsigned("--block-no")
      _ <- Either.cond(
        blockNo.isValidLong,
        (),
        ConfigError(
          ConfigProblem.WithUsage(ConfigProblem.FlagValue("--block-no", blockNo.toString), usage)
        )
      )
      hashText <- required("--block-hash")
      hash <- Bytes
        .fromHex(hashText)
        .leftMap(error => ConfigError(ConfigProblem.WithUsage(ConfigProblem.Hex(error), usage)))
      _ <- Either.cond(
        hash.size == 32,
        (),
        ConfigError(
          ConfigProblem.WithUsage(ConfigProblem.ByteLength("block hash", 32, hash.size), usage)
        )
      )
    yield BaseOptions(db, scratch, config, genesis, BlockRef(slot.toBigInt, hash, blockNo))

  private def requiredPath(
      values: Map[String, String],
      name: String,
      usage: String
  ): Either[ConfigError, Path] =
    values
      .get(name)
      .toRight(ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlag(name), usage)))
      .flatMap(parsePath)

  private def parsePath(value: String): Either[ConfigError, Path] =
    Either
      .catchNonFatal(Paths.get(value))
      .leftMap(error => ConfigError(ConfigProblem.InvalidPath(value, error)))

  private def parseRecordBytes(
      values: Map[String, String],
      usage: String
  ): Either[ConfigError, Int] =
    values.get("--record-bytes") match
      case None => Right(DefaultRecordBytes)
      case Some(text) =>
        Either
          .cond(text.nonEmpty && text.forall(c => c >= '0' && c <= '9'), text, ())
          .flatMap(value => Either.catchNonFatal(BigInt(value)).leftMap(_ => ()))
          .flatMap(value => Either.cond(value >= 4096 && value <= MaxRecordBytes, value, ()))
          .map(_.toInt)
          .leftMap(_ =>
            ConfigError(
              ConfigProblem.WithUsage(ConfigProblem.FlagValue("--record-bytes", text), usage)
            )
          )

  private[tools] def valueLimits(recordBytes: Int): CborCodec.Limits =
    CborCodec.Limits(
      maxInputBytes = recordBytes,
      maxStringBytes = recordBytes
    )

  private def codec(recordBytes: Int): Either[ConfigError, SclsExport.Config] =
    for
      streaming <- SclsStreaming.Config
        .fromInts(
          recordBytes,
          1000,
          64 * 1024,
          valueLimits(recordBytes)
        )
        .leftMap(error => ConfigError(ConfigProblem.FlagValue("--record-bytes", error.toString)))
      sorter <- SclsEntrySorter.Config
        .fromInt(recordBytes + SortKeyAllowance)
        .leftMap(error => ConfigError(ConfigProblem.FlagValue("--record-bytes", error.toString)))
    yield SclsExport.Config(streaming, sorter)

  def runExport(args: List[String]): IO[ExitCode] = parseExport(args) match
    case Left(error) => IO.println(error.message).as(ExitCode(2))
    case Right(options) =>
      codec(options.recordBytes) match
        case Left(error) => IO.println(error.message).as(ExitCode(2))
        case Right(codec) =>
          withSetup(options.base) { (setup, base) =>
            val summary = SclsRecord.Summary("local", "scarab-scala-lab", options.comment)
            given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(IO.pure(Map.empty))
            Handle
              .allow[SclsInterchange.Error](
                SclsInterchange
                  .exportFile[IO](
                    SclsInterchange
                      .ExportConfig(base, FsPath.fromNioPath(options.out), summary, codec)
                  )
                  .map(report => Some(report): Option[SclsInterchange.Report])
              )
              .rescue(error =>
                report("exportScls", error.toString).as(Option.empty[SclsInterchange.Report])
              )
              .flatMap {
                case None => IO.pure(ExitCode(1))
                case Some(result) =>
                  report(
                    "exportScls",
                    s"slot=${result.anchor.slot} blockNo=${result.anchor.blockNo} hash=${result.anchor.hash.hex} outputs=${result.outputs} path=${result.path}"
                  ).as(ExitCode.Success)
              }
          }

  def runImport(args: List[String]): IO[ExitCode] = parseImport(args) match
    case Left(error) => IO.println(error.message).as(ExitCode(2))
    case Right(options) =>
      codec(options.recordBytes) match
        case Left(error) => IO.println(error.message).as(ExitCode(2))
        case Right(codec) =>
          withSetup(options.base) { (setup, base) =>
            given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(IO.pure(Map.empty))
            Handle
              .allow[SclsInterchange.Error](
                SclsInterchange
                  .importFile[IO](
                    SclsInterchange.ImportConfig(
                      base,
                      FsPath.fromNioPath(options.input),
                      FsPath.fromNioPath(options.target),
                      setup.geometry.byron.epochSlots,
                      SclsImport.Config(codec)
                    )
                  )
                  .map(report => Some(report): Option[SclsInterchange.Report])
              )
              .rescue(error =>
                report("importScls", error.toString).as(Option.empty[SclsInterchange.Report])
              )
              .flatMap {
                case None => IO.pure(ExitCode(1))
                case Some(result) =>
                  report(
                    "importScls",
                    s"slot=${result.anchor.slot} blockNo=${result.anchor.blockNo} hash=${result.anchor.hash.hex} outputs=${result.outputs} path=${result.path}"
                  ).as(ExitCode.Success)
              }
          }

  private def withSetup(
      base: BaseOptions
  )(run: (NodeSetup, SclsBase.Config) => IO[ExitCode]): IO[ExitCode] =
    IO.blocking {
      base.config match
        case Some(config) => NodeSetup.fromConfigFile(config, base.genesis)
        case None =>
          base.genesis.fold[Either[ConfigError, NodeSetup]](
            Left(ConfigError(ConfigProblem.MissingGenesis))
          )(NodeSetup.fromDirectory)
    }.flatMap {
      case Left(error) => IO.println(error.message).as(ExitCode(2))
      case Right(setup) =>
        val config = SclsBase.Config(
          FsPath.fromNioPath(base.db),
          FsPath.fromNioPath(base.scratch),
          base.anchor,
          setup.identity
        )
        run(setup, config)
    }

  private def report(event: String, message: String): IO[Unit] =
    IO.println(
      Json.obj("kind" -> Json.fromString(event), "message" -> Json.fromString(message)).noSpaces
    )
