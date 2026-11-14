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

/** Brings the V8 snapshot fixtures to V9 with the registered migration's V8 → V9 step
  * ([[SnapshotMigration.toCurrent]], its `step`), into a fresh directory, and writes the conversion
  * manifest. Arguments: the V8 fixture directory and a directory that does not exist.
  * `SnapshotGoldenSuite` checks every output against [[SnapshotV9.fromV8]] of its source. (Before
  * V10 this ran the whole migration, whose current version was V9; [[SnapshotV10FixtureMain]] takes
  * these files on to V10.)
  */
object SnapshotV9FixtureMain extends Assertions:
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
              FormatVersion.V8,
              FormatVersion.V9
            )
          )
          .as(List(Migrated(FileKind.SnapshotFile, FormatVersion.V8, FormatVersion.V9, names.size)))
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
        "sourceFile" -> s"fixtures/snapshot-v8/$name".asJson,
        "sourceSha256" -> sha256(before).asJson,
        "targetSha256" -> sha256(after).asJson,
        "sourceBytes" -> before.length.asJson,
        "targetBytes" -> after.length.asJson
      )
    }
    val manifest = Json.obj(
      "format" -> "SCRBSNP9".asJson,
      "conversion" -> ("registered SnapshotMigration V8 -> V9 step (SnapshotV9.fromV8): the " +
        "binding and the Haskell bytes unchanged, the sidecar read in the frozen V8 schema and " +
        "written in the current one").asJson,
      "migrated" -> migrated.map(m => s"${m.kind} ${m.from} -> ${m.to}: ${m.count}").asJson,
      "files" -> files.asJson
    )
    Files.writeString(target.resolve("manifest.json"), manifest.spaces2 + "\n")
