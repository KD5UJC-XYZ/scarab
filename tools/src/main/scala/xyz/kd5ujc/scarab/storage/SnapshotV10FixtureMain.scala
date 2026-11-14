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

/** Brings the V9 snapshot fixtures to V10 with the registered migration's V9 → V10 step
  * ([[SnapshotMigration.toCurrent]], its `step`), into a fresh directory, and writes the conversion
  * manifest. Arguments: the V9 fixture directory and a directory that does not exist.
  * `SnapshotGoldenSuite` checks every output against [[SnapshotV10.fromV9]] of its source. (Before
  * V11 this ran the whole migration, whose current version was V10; [[SnapshotV11FixtureMain]]
  * takes these files on to V11.)
  */
object SnapshotV10FixtureMain extends Assertions:
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
              FormatVersion.V9,
              FormatVersion.V10
            )
          )
          .as(
            List(Migrated(FileKind.SnapshotFile, FormatVersion.V9, FormatVersion.V10, names.size))
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
        "sourceFile" -> s"fixtures/snapshot-v9/$name".asJson,
        "sourceSha256" -> sha256(before).asJson,
        "targetSha256" -> sha256(after).asJson,
        "sourceBytes" -> before.length.asJson,
        "targetBytes" -> after.length.asJson
      )
    }
    val manifest = Json.obj(
      "format" -> "SCRBSNPA".asJson,
      "conversion" -> ("registered SnapshotMigration V9 -> V10 step (SnapshotV10.fromV9): the " +
        "reference build 11.1 recorded after the magic; the binding, the sidecar and the Haskell " +
        "bytes unchanged").asJson,
      "migrated" -> migrated.map(m => s"${m.kind} ${m.from} -> ${m.to}: ${m.count}").asJson,
      "files" -> files.asJson
    )
    Files.writeString(target.resolve("manifest.json"), manifest.spaces2 + "\n")
