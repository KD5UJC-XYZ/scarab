// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.MessageDigest
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion}
import xyz.kd5ujc.scarab.ledger.state.{Gap, NonMyopic}
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era

import StorageTestKit.*

/** Complete fixed files from a native V5 writer and verified historical conversions. Maintained
  * test inputs use the current format; original capture provenance remains pinned separately.
  */
class SnapshotGoldenSuite extends CatsEffectSuite:
  private val fixtures = JPath.of("fixtures/snapshot-v5")
  private val legacyGaps = Set(Gap.NonMyopic, Gap.PendingPulsingUpdate, Gap.ShelleyTransition)
  private val currentGaps = Set(Gap.NonMyopic, Gap.ShelleyTransition)

  private final case class Golden(
      version: FormatVersion,
      file: String,
      sha256: String,
      migratedSha256: String,
      preview: Boolean,
      gaps: Set[Gap]
  ):
    def anchor: BlockRef =
      val (slot, hash, number) =
        if preview then
          (86400, "4a9761ddc291b0c352d1712b624759132936a07b3e02d1d3bdaaf17b9abfe683", 4320)
        else (3567, "89cdfee8525e74ec5188e54c0ae7dc373025531e8e3465524e01e8b25fd2fef9", 730)
      BlockRef(
        BigInt(slot),
        Bytes.fromHex(hash).fold(e => fail(e.message), identity),
        BigInt(number)
      )

    def environment: ConsensusMirrorEnv =
      val directory =
        if preview then JPath.of("fixtures/preview/genesis") else JPath.of("fixtures/eras/genesis")
      NodeSetup.fromDirectory(directory).fold(e => fail(e.message), _.mirrorEnv())

  private val previewMigration = "02c716ef8de7d837c86c4b8da3588a583a1f17f5a93fdc0c152916e3d6534bcd"
  private val conwayMigration = "ce2dcdd6bcc747169817defc3158cb364c79d72cbbea41319b51360b5edeabf2"
  private val current = "cc5d5d6a248499a0b1505d0a44174f97d447ffadebde5f1d5408c30d2a206734"
  private val goldens = Vector(
    Golden(
      FormatVersion.V5,
      "preview-4320.bin",
      previewMigration,
      previewMigration,
      true,
      legacyGaps
    ),
    Golden(
      FormatVersion.V5,
      "eras/conway-730.bin",
      conwayMigration,
      conwayMigration,
      false,
      legacyGaps
    ),
    Golden(FormatVersion.V5, "conway-native-730.bin", current, current, false, currentGaps)
  )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  test("every maintained native snapshot golden uses the current complete-file format") {
    assertEquals(
      goldens.map(_.version).toSet,
      Set(FileKind.SnapshotFile.current)
    )
    goldens.foreach { g =>
      val raw = JFiles.readAllBytes(fixtures.resolve(g.file))
      assertEquals(sha256(raw), g.sha256, g.file)
      val content = FileLedgerSnapshots.verified(raw).fold(e => fail(e.message), identity)
      val found = new String(content.take(8), "US-ASCII")
      assertEquals(found, FileKind.SnapshotFile.identifier(g.version), g.file)
    }
  }

  test("the complete era conversion manifest pins every file and carried metadata") {
    val raw = JFiles.readAllBytes(fixtures.resolve("manifest.json"))
    assertEquals(sha256(raw), "874580cea6a2435cab02d0a25ae74764968b25871871317f5a19489459a3bf41")
    val manifest = io.circe.parser
      .parse(new String(raw, "UTF-8"))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(
      manifest.get[String]("conversionSourceCommit").toOption,
      Some("648aed117f826d06117f616b5c0a3716a50485a7")
    )
    val entries = manifest.get[Vector[io.circe.Json]]("files").fold(e => fail(e.message), identity)
    assertEquals(entries.size, 15)
    entries.foreach { entry =>
      val cursor = entry.hcursor
      def field[A: io.circe.Decoder](name: String): A =
        cursor.get[A](name).fold(e => fail(e.message), identity)
      val file = field[String]("file")
      val bytes = JFiles.readAllBytes(fixtures.resolve(file))
      assertEquals(sha256(bytes), field[String]("targetSha256"), file)
      val sidecar = FileLedgerSnapshots
        .verified(bytes)
        .flatMap(SnapshotV5.sidecarOf)
        .fold(e => fail(e.message), identity)
      assertEquals(sidecar.gaps.map(_.toString), field[Set[String]]("gaps"), file)
      assertEquals(
        sidecar.observed.map((era, epoch) => era.toString -> epoch.toString),
        field[Map[String, String]]("observed"),
        file
      )
      assertEquals(sidecar.anchor.map(_.slot.toString), Some(field[String]("slot")), file)
      assertEquals(sidecar.anchor.map(_.hash.hex), Some(field[String]("hash")), file)
      assertEquals(sidecar.anchor.map(_.blockNo.toString), Some(field[String]("blockNo")), file)
    }
  }

  private def semanticState(g: Golden, snapshot: StateSnapshot[MirrorState]): Unit =
    assertEquals(snapshot.anchor, Some(g.anchor), g.file)
    assertEquals(snapshot.state.utxo.count, if g.preview then 4L else 3L, g.file)
    assertEquals(
      snapshot.state.utxo.coin,
      if g.preview then BigInt("29999998493562207") else BigInt("8996400000"),
      g.file
    )
    snapshot.state.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        assertEquals(i.state.nes.epoch.toBigInt, if g.preview then BigInt(1) else BigInt(8), g.file)
        // The older state never held non-myopic likelihoods or the pot. Migration must not invent them.
        assertEquals(i.state.nes.es.nonMyopic, NonMyopic.empty, g.file)
      case _ => fail(s"${g.file}: a Shelley-based snapshot became Byron")

  goldens.foreach { g =>
    test(s"${g.file}: current file restores its state and carried gaps without rewriting") {
      given Ask[IO, ConsensusMirrorEnv] = Ask.const(g.environment)
      given SnapshotFormat[IO, MirrorState] = SnapshotV5.format[IO](SnapshotCheck.Full)
      tempDir.use { dir =>
        val name = FileLedgerSnapshots.nameOf(g.anchor)
        val file = dir / name
        for
          original <- NativeSnapshots
            .read[IO](fixtures.resolve(g.file), g.environment)
            .map(_.fold(e => fail(e.message), identity))
          _ <- IO(JFiles.copy(fixtures.resolve(g.file), file.toNioPath))
          first <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          second <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          raw <- IO(JFiles.readAllBytes(file.toNioPath))
          content = FileLedgerSnapshots.verified(raw).fold(e => fail(e.message), identity)
          sidecar = SnapshotV5.sidecarOf(content).fold(e => fail(e.message), identity)
          restored <- ok(
            FileLedgerSnapshots
              .open[IO, MirrorState](dir)
              .flatMap(_.restore(a => IO.pure(a.contains(g.anchor))))
          )
        yield
          val migrated =
            if g.version == FormatVersion.V5 then Nil
            else List(Migrated(FileKind.SnapshotFile, g.version, FormatVersion.V5, 1))
          assertEquals(first, SnapshotMigration.Report(migrated, Nil))
          assertEquals(second, SnapshotMigration.Report.empty)
          assertEquals(sha256(raw), g.migratedSha256, g.file)
          assertEquals(sidecar.gaps, g.gaps, g.file)
          assertEquals(sidecar.observed, Map.empty[Era, BigInt], g.file)
          assertEquals(restored.skipped, Vector.empty)
          val back = restored.snapshot.map(_._2).getOrElse(fail(s"${g.file}: not restored"))
          assertEquals(back, original, g.file)
          semanticState(g, back)
      }
    }
  }
