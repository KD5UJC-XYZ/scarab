// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import munit.Assertions
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}

/** Brings the V10 snapshot fixtures to V11, the current version, with the registered migration's
  * V10 → V11 step ([[SnapshotMigration.toCurrent]], its `step`), into a fresh directory, and writes
  * the conversion manifest. Arguments: the V10 fixture directory and a directory that does not
  * exist. `SnapshotGoldenSuite` checks every output against [[SnapshotV11.fromV10]] of its source.
  */
object SnapshotV11FixtureMain extends Assertions:
  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def main(args: Array[String]): Unit =
    val source = Path.of(args(0))
    val target = Path.of(args(1))
    assert(!Files.exists(target), s"$target exists")
    val names = Files
      .walk(source)
      .iterator
      .asScala
      .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".bin"))
      .map(p => source.relativize(p).toString)
      .toVector
      .sorted
    names.foreach { name =>
      Files.createDirectories(target.resolve(name).getParent)
      Files.copy(source.resolve(name), target.resolve(name))
    }
    val migration = SnapshotMigration.toCurrent[IO]
    val migrated = Handle
      .allow[StorageError](
        names.toList
          .traverse(n =>
            migration.step(
              fs2.io.file.Path.fromNioPath(target.resolve(n)),
              FormatVersion.V10,
              FormatVersion.V11
            )
          )
          .as(
            List(Migrated(FileKind.SnapshotFile, FormatVersion.V10, FormatVersion.V11, names.size))
          )
          .map(_.asRight[StorageError])
      )
      .rescue(e => IO.pure(Left(e)))
      .unsafeRunSync()
      .fold(e => fail(e.message), identity)
    val files = names.map { name =>
      val before = Files.readAllBytes(source.resolve(name))
      val after = Files.readAllBytes(target.resolve(name))
      Json.obj(
        "file" -> name.asJson,
        "sourceFile" -> s"fixtures/snapshot-v10/$name".asJson,
        "sourceSha256" -> sha256(before).asJson,
        "targetSha256" -> sha256(after).asJson,
        "sourceBytes" -> before.length.asJson,
        "targetBytes" -> after.length.asJson
      )
    }
    val manifest = Json.obj(
      "format" -> "SCRBSNPB".asJson,
      "conversion" -> ("registered SnapshotMigration V10 -> V11 step (SnapshotV11.fromV10): the " +
        "recorded build, the binding and the Haskell bytes unchanged, the sidecar read in the " +
        "frozen V10 schema and written in the current one, the bootstrap state's flag and lite " +
        "ledger dropped and its era and epoch verdicts kept as the follow state").asJson,
      "migrated" -> migrated.map(m => s"${m.kind} ${m.from} -> ${m.to}: ${m.count}").asJson,
      "files" -> files.asJson
    )
    Files.writeString(target.resolve("manifest.json"), manifest.spaces2 + "\n")
