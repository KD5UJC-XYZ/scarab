// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState, MirrorExtras}
import xyz.kd5ujc.scarab.ledger.ConfigError
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.LedgerSnapshotImport
import xyz.kd5ujc.scarab.storage.haskell.state.AncillaryManifest

object LedgerSnapshotImportCommand:
  val Usage =
    "usage: import-ledger-snapshot --haskell-ledger <dir> --db <dir> (--config <file> | --genesis-dir <dir>) [--ancillary-manifest <file>] (--ancillary-verification-key <file> | --no-verify-ancillary)"
  final case class Options(
      ledger: Path,
      db: Path,
      config: Option[Path],
      genesis: Option[Path],
      manifest: Option[Path],
      key: Option[Path],
      unverified: Boolean
  )
  private val flags = Set(
    "haskell-ledger",
    "db",
    "config",
    "genesis-dir",
    "ancillary-manifest",
    "ancillary-verification-key"
  )

  def parse(args: List[String]): Either[ConfigError, Options] =
    def bad[A](detail: String): Either[ConfigError, A] = Left(ConfigError(s"$detail\n$Usage"))
    @annotation.tailrec
    def loop(
        rest: List[String],
        values: Map[String, String],
        unverified: Boolean
    ): Either[ConfigError, (Map[String, String], Boolean)] = rest match
      case Nil                                            => Right(values -> unverified)
      case "--no-verify-ancillary" :: tail if !unverified => loop(tail, values, true)
      case flag :: value :: tail
          if flags.contains(flag.stripPrefix("--")) && flag
            .startsWith("--") && !value.startsWith("--") =>
        val name = flag.drop(2)
        if values.contains(name) then bad(s"duplicate $flag")
        else loop(tail, values.updated(name, value), unverified)
      case flag :: _ => bad(s"unknown, duplicate or incomplete argument $flag")
    for
      parsed <- loop(args, Map.empty, false)
      (values, unverified) = parsed
      ledger <- values
        .get("haskell-ledger")
        .toRight(ConfigError(s"--haskell-ledger is required\n$Usage"))
      db <- values.get("db").toRight(ConfigError(s"--db is required\n$Usage"))
      _ <- Either.cond(
        values.contains("config") || values.contains("genesis-dir"),
        (),
        ConfigError(s"--config or --genesis-dir is required\n$Usage")
      )
      _ <- Either.cond(
        unverified != values.contains("ancillary-verification-key"),
        (),
        ConfigError(
          s"supply an ancillary verification key, or explicitly accept an unauthenticated ledger with --no-verify-ancillary\n$Usage"
        )
      )
      _ <- Either.cond(
        !unverified || !values.contains("ancillary-manifest"),
        (),
        ConfigError("--ancillary-manifest requires verification")
      )
    yield Options(
      Path.of(ledger),
      Path.of(db),
      values.get("config").map(Path.of(_)),
      values.get("genesis-dir").map(Path.of(_)),
      values.get("ancillary-manifest").map(Path.of(_)),
      values.get("ancillary-verification-key").map(Path.of(_)),
      unverified
    )

  private def inputs(
      options: Options
  ): Either[ConfigError, (NodeSetup, LedgerSnapshotImport.Config)] =
    for
      setup <- options.config.fold(
        options.genesis
          .toRight(ConfigError("no genesis"))
          .flatMap(NodeSetup.fromDirectory)
      )(NodeSetup.fromConfigFile(_, options.genesis))
      params <- setup.handOver
        .map(_.protocolParams)
        .orElse(setup.mirrorStart.ext.ledgerState.state match
          case i: EraLedgerState.InShelleyEra[e] =>
            given xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra[e] = i.era
            Some(EraLedger.curPParams(i.state.nes.es.ls.utxo.gov))
          case _ => None)
        .toRight(ConfigError("no genesis protocol parameters"))
      boot = setup.mirrorStart.extras match
        case MirrorExtras.InByron(_, b, _) => b
        case MirrorExtras.InShelley(_, c)  => c.boot
      trust <- options.key match
        case None => Right(LedgerSnapshotImport.Trust.Unverified)
        case Some(path) =>
          Either
            .catchNonFatal(Files.readString(path))
            .leftMap(e => ConfigError(s"cannot read ancillary key: ${e.getMessage}"))
            .flatMap(text =>
              AncillaryManifest.verificationKey(text).leftMap(e => ConfigError(e.toString))
            )
            .flatMap { key =>
              val manifest = options.manifest.orElse(
                Option(options.ledger.toAbsolutePath.getParent)
                  .flatMap(p => Option(p.getParent))
                  .map(_.resolve("ancillary_manifest.json"))
              )
              manifest
                .toRight(ConfigError("--ancillary-manifest is required for this snapshot path"))
                .map(path => LedgerSnapshotImport.Trust.Verify(FsPath.fromNioPath(path), key))
            }
    yield setup -> LedgerSnapshotImport.Config(
      FsPath.fromNioPath(options.ledger),
      FsPath.fromNioPath(options.db),
      setup.identity,
      setup.geometry.byron.epochSlots,
      params,
      boot,
      trust
    )

  def run(args: List[String]): IO[ExitCode] = parse(args) match
    case Left(error) => IO.println(error.message).as(ExitCode(2))
    case Right(options) =>
      IO.blocking(inputs(options)).flatMap {
        case Left(error) => IO.println(error.message).as(ExitCode(2))
        case Right((setup, config)) =>
          given Ask[IO, ConsensusMirrorEnv] = setup.mirrorAsk(IO.pure(Map.empty))
          Handle
            .allow[LedgerSnapshotImport.Error](LedgerSnapshotImport.run[IO](config).map(Some(_)))
            .rescue(error => log("error", "message" -> Json.fromString(error.message)).as(None))
            .flatMap {
              case None => IO.pure(ExitCode(1))
              case Some(report) =>
                val verified = report.evidence match
                  case LedgerSnapshotImport.Evidence.Verified(_, _) => true
                  case LedgerSnapshotImport.Evidence.Unverified     => false
                log(
                  "done",
                  "slot" -> Json.fromBigInt(report.anchor.slot),
                  "hash" -> Json.fromString(report.anchor.hash.hex),
                  "era" -> Json.fromString(report.era.toString),
                  "outputs" -> Json.fromLong(report.count),
                  "coin" -> Json.fromBigInt(report.coin),
                  "ancillaryVerified" -> Json.fromBoolean(verified)
                ).as(ExitCode.Success)
            }
      }

  private def log(event: String, fields: (String, Json)*): IO[Unit] =
    IO.println(
      Json
        .obj(
          (Vector(
            "kind" -> Json.fromString("importLedgerSnapshot"),
            "event" -> Json.fromString(event)
          ) ++ fields)*
        )
        .noSpaces
    )
