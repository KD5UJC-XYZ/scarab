// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import java.io.OutputStream
import java.nio.file.Files
import java.security.{DigestInputStream, MessageDigest}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.haskell.state.SnapshotMeta

/** Reuse the authenticated S3 artifact; never repeat its 4.5M-output load or access a network. */
class PreprodSnapshotExportSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 10.minutes

  private def fingerprint(db: Path): Map[String, Bytes] =
    val walk = Files.walk(db.toNioPath)
    try
      walk
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map { path =>
          val digest = MessageDigest.getInstance("SHA-256")
          val in = new DigestInputStream(Files.newInputStream(path), digest)
          try in.transferTo(OutputStream.nullOutputStream())
          finally in.close()
          db.toNioPath.relativize(path).toString -> Bytes.wrap(digest.digest())
        }
        .toMap
    finally walk.close()

  test("the authenticated preprod checkpoint exports byte-identical canonical state and tables") {
    val configured = sys.env.get("SCARAB_EXPORT_DB")
    assume(configured.nonEmpty, "SCARAB_EXPORT_DB names the preserved authenticated S3 database")
    configured.traverse_ { source =>
      val db = Path(source)
      val fixture = Path(sys.env.getOrElse("SCARAB_EXPORT_FIXTURE", "/snapshot"))
      StorageTestKit.tempDir.use { temp =>
        val out = sys.env.get("SCARAB_EXPORT_OUT").fold(temp / "out")(Path(_))
        for
          before <- IO.blocking(fingerprint(db))
          began <- IO.monotonic
          report <- Handle
            .allow[LedgerSnapshotExport.Error] {
              LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, out))
            }
            .rescue(error => IO.raiseError(new AssertionError(error.message)))
          elapsed <- IO.monotonic.map(_ - began)
          nanos = elapsed.toNanos
          _ <- IO.blocking {
            assertEquals(report.anchor.slot, BigInt(136511997))
            assertEquals(report.anchor.blockNo, BigInt(5300868))
            assertEquals(report.outputs, 4526058L)
            val original = fixture / "ledger" / "136511997"
            assertEquals(
              Files.mismatch(
                (original / "state").toNioPath,
                (report.directory / "state").toNioPath
              ),
              -1L
            )
            assertEquals(
              Files.mismatch(
                (original / "tables").toNioPath,
                (report.directory / "tables").toNioPath
              ),
              -1L
            )
            val originalMeta = SnapshotMeta.parse(Files.readString((original / "meta").toNioPath))
            val writtenMeta = SnapshotMeta.parse(
              Files.readString((report.directory / "meta").toNioPath)
            )
            assertEquals(writtenMeta, originalMeta)
            assertEquals(fingerprint(db), before)
          }
          _ <- IO.println(
            s"""{"snapshotExportSummary":{"outputs":4526058,"elapsedNanos":$nanos}}"""
          )
        yield ()
      }
    }
  }
