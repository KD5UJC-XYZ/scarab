// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import io.circe.Json
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, ByronState}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{ConfigError, StorageError}
import xyz.kd5ujc.scarab.ledger.state.Gap
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.{AtomicFile, FileLedgerSnapshots, SnapshotCheck, SnapshotRefusal, SnapshotV5, StateSnapshot}

/** Offline one-shot bridge compiled against source648aed11. Original data must be mounted read-only. */
object Snapshot83BridgeMain extends IOApp:
  private final case class Expected(
      anchor: Option[BlockRef], count: Long, coin: BigInt,
      gaps: Set[Gap], observed: Map[Era, BigInt], byron: Option[ByronState]
  )
  private enum Failure:
    case Config(cause: ConfigError)
    case Storage(cause: StorageError)
    case Conversion(file: String, cause: SnapshotRefusal)
    case Metadata(file: String, expected: Expected, actual: Expected)
    case TargetDiffers(file: String)
    case MissingAnchor(file: String)
    case FilenameMismatch(file: String, expected: String)
    case UnsafePaths

  private type Program[A] = EitherT[IO, Failure, A]
  private def pure[A](value: Either[Failure, A]): Program[A] = EitherT.fromEither[IO](value)
  private def storage[A](value: Either[StorageError, A]): Program[A] = pure(value.leftMap(Failure.Storage(_)))
  private def effect[A](value: IO[A]): Program[A] = EitherT.liftF(value)
  private def digest(raw: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 0xff}%02x").mkString

  private def flat(
      env: ConsensusMirrorEnv, anchor: Option[BlockRef], state: legacy.NodeState
  ): Either[Failure, Expected] =
    legacy.Upgrade.node(state, env.forks, anchor)
      .leftMap(e => Failure.Storage(StorageError.Corrupt("historical vocabulary", e.toString)))
      .map { s =>
        val byron = Option.when(legacy.StateMirror.eraOf(env, s) == Era.Byron)(s.byron)
        Expected(anchor, if byron.isDefined then 0L else s.ledger.utxo.count,
          if byron.isDefined then BigInt(0) else s.ledger.utxo.coin,
          legacy.StateMirror.gaps(env, s), env.observed, byron)
      }

  private def expected(env: ConsensusMirrorEnv, content: Array[Byte]): Either[Failure, Expected] =
    if content.startsWith(legacy.SnapshotV3.Magic) then
      legacy.SnapshotV3.sections(content).leftMap(Failure.Storage(_)).flatMap { (s, _) =>
        s.chain match
          case legacy.SnapshotSidecar.Chain.InByron(old) =>
            flat(env, s.anchor, old).map(_.copy(gaps = s.gaps, observed = s.observed))
          case legacy.SnapshotSidecar.Chain.InShelley(utxo, _, _, _) =>
            Right(Expected(s.anchor, utxo.count, utxo.coin, s.gaps, s.observed, None))
      }
    else if content.startsWith(legacy.v4.SnapshotV4.Magic) then
      legacy.v4.SnapshotV4.sections(content).leftMap(Failure.Storage(_)).map { (s, _) =>
        s.chain match
          case legacy.v4.SnapshotSidecar.Chain.InByron(_, _, extras) =>
            Expected(s.anchor, 0L, BigInt(0), s.gaps, s.observed, Some(extras.byron))
          case legacy.v4.SnapshotSidecar.Chain.InShelley(u, _, _, _) =>
            Expected(s.anchor, u.count, u.coin, s.gaps, s.observed, None)
      }
    else
      legacy.SnapshotV2.decodeNode(content).leftMap(Failure.Storage(_))
        .flatMap(s => flat(env, s.anchor, s.state))

  private def observed(snapshot: StateSnapshot[MirrorState], content: Array[Byte]): Either[Failure, Expected] =
    SnapshotV5.sidecarOf(content).leftMap(Failure.Storage(_)).map(s =>
      Expected(snapshot.anchor, snapshot.state.utxo.count, snapshot.state.utxo.coin, s.gaps, s.observed,
        snapshot.state.ext.ledgerState.state match
          case xyz.kd5ujc.scarab.consensus.state.EraLedgerState.ByronCurrent(b) => Some(b)
          case _ => None))

  private def atomic(file: Path, bytes: Array[Byte]): Program[Unit] =
    EitherT(
      Handle.allow[StorageError](
        AtomicFile.replace[IO](fs2.io.file.Path.fromNioPath(file), bytes)
          .as(Right(()): Either[Failure, Unit])
      ).rescue(e => IO.pure(Left(Failure.Storage(e))))
    )

  private def convert(env: ConsensusMirrorEnv, input: Path, outputDir: Path): Program[Json] =
    given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
    for
      raw <- effect(IO.blocking(Files.readAllBytes(input)))
      content <- storage(FileLedgerSnapshots.verified(raw))
      before <- pure(expected(env, content))
      anchor <- pure(before.anchor.toRight(Failure.MissingAnchor(input.toString)))
      name = FileLedgerSnapshots.nameOf(anchor)
      _ <- pure(Either.cond(!FileLedgerSnapshots.isSnapshot(input.getFileName.toString) || input.getFileName.toString == name,
        (), Failure.FilenameMismatch(input.toString, name)))
      output = outputDir.resolve(name)
      migrated <- pure(SnapshotV5.migrated(env, content).leftMap(Failure.Conversion(input.toString, _)))
      decoded <- EitherT(SnapshotV5.format[IO](SnapshotCheck.Full).decode(migrated)).leftMap(Failure.Storage(_))
      after <- pure(observed(decoded, migrated))
      _ <- pure(Either.cond(before == after, (), Failure.Metadata(input.toString, before, after)))
      closed = FileLedgerSnapshots.closed(migrated)
      present <- effect(IO.blocking(Files.exists(output)))
      _ <- if present then
        effect(IO.blocking(Files.readAllBytes(output))).flatMap(existing =>
          pure(Either.cond(java.util.Arrays.equals(existing, closed), (), Failure.TargetDiffers(output.toString))))
      else atomic(output, closed)
      reread <- effect(IO.blocking(Files.readAllBytes(output)))
      _ <- storage(FileLedgerSnapshots.verified(reread))
      row = Json.obj(
        "source" -> Json.fromString(input.toString), "target" -> Json.fromString(output.toString),
        "sourceSha256" -> Json.fromString(digest(raw)), "targetSha256" -> Json.fromString(digest(reread)),
        "sourceBytes" -> Json.fromInt(raw.length), "targetBytes" -> Json.fromInt(reread.length),
        "sourceHeader20" -> Json.fromString(content.take(20).map(b => f"${b & 0xff}%02x").mkString),
        "slot" -> before.anchor.fold(Json.Null)(a => Json.fromString(a.slot.toString)),
        "hash" -> before.anchor.fold(Json.Null)(a => Json.fromString(a.hash.hex)),
        "blockNo" -> before.anchor.fold(Json.Null)(a => Json.fromString(a.blockNo.toString)),
        "utxoCount" -> Json.fromLong(before.count), "utxoCoin" -> Json.fromString(before.coin.toString),
        "gaps" -> Json.arr(before.gaps.toVector.map(_.toString).sorted.map(Json.fromString)*),
        "observed" -> Json.obj(before.observed.toVector.sortBy(_._1.ordinal).map((era, epoch) => era.toString -> Json.fromString(epoch.toString))*),
        "byronStatePreserved" -> Json.fromBoolean(before.byron.isDefined),
        "verified" -> Json.True, "reusedVerifiedOutput" -> Json.fromBoolean(present)
      )
      _ <- effect(IO.println(row.noSpaces))
    yield row

  private def program(input: Path, output: Path, genesis: Path): Program[Unit] =
    for
      _ <- pure(Either.cond(input.toAbsolutePath.normalize != output.toAbsolutePath.normalize &&
        !output.toAbsolutePath.normalize.startsWith(input.toAbsolutePath.normalize), (), Failure.UnsafePaths))
      setup <- pure(NodeSetup.fromDirectory(genesis).leftMap(Failure.Config(_)))
      files <- effect(IO.blocking {
        val stream = Files.list(input)
        try stream.iterator.asScala.filter(p => p.getFileName.toString.endsWith(".bin")).toVector.sorted
        finally stream.close()
      })
      _ <- effect(IO.blocking(Files.createDirectories(output)))
      rows <- files.traverse(file => convert(setup.mirrorEnv(), file, output))
      manifest = Json.obj("bridgeSource" -> Json.fromString("648aed117f826d06117f616b5c0a3716a50485a7"),
        "complete" -> Json.True, "files" -> Json.arr(rows*))
      _ <- atomic(output.resolve("conversion-manifest.json"), (manifest.spaces2 + "\n").getBytes("UTF-8"))
    yield ()

  def run(args: List[String]): IO[ExitCode] = args match
    case input :: output :: genesis :: Nil =>
      program(Path.of(input), Path.of(output), Path.of(genesis)).value.flatMap {
        case Right(_) => IO.pure(ExitCode.Success)
        case Left(e) => IO.println(s"Bridge refused: $e").as(ExitCode.Error)
      }
    case _ => IO.println("Usage: Snapshot83BridgeMain <read-only snapshot-dir> <new output-dir> <genesis-dir>")
      .as(ExitCode.Error)
