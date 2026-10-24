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
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.ledger.state.{Gap, NonMyopic}
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era

import StorageTestKit.*

/** Complete fixed files: the maintained V7 inputs, and the V5 files (from a native V5 writer and
  * verified historical conversions) they were converted from, which remain the previous-version
  * inputs of the migration. Original capture provenance remains pinned separately.
  */
class SnapshotGoldenSuite extends CatsEffectSuite:
  private val previous = JPath.of("fixtures/snapshot-v5")
  private val fixtures = JPath.of("fixtures/snapshot-v7")
  private val legacyGaps = Set(Gap.NonMyopic, Gap.PendingPulsingUpdate, Gap.ShelleyTransition)
  private val currentGaps = Set(Gap.NonMyopic, Gap.ShelleyTransition)

  private final case class Golden(
      dir: JPath,
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

  private val previewV5 = "02c716ef8de7d837c86c4b8da3588a583a1f17f5a93fdc0c152916e3d6534bcd"
  private val conwayV5 = "ce2dcdd6bcc747169817defc3158cb364c79d72cbbea41319b51360b5edeabf2"
  private val nativeV5 = "cc5d5d6a248499a0b1505d0a44174f97d447ffadebde5f1d5408c30d2a206734"
  private val previewV7 = "692acd226a7da77730114ae646643f3f6b8f3557763cdba28b65eaaae37248bc"
  private val conwayV7 = "a104434df873e4c7406fa57d00fe4b861a773d697312744c8ac30ea1de6ff97a"
  private val nativeV7 = "27a5e02eee38bc3c565d8f85830ea01804406a2b562dca957db368d9883699b2"
  private def both(
      file: String,
      v5: String,
      v7: String,
      preview: Boolean,
      gaps: Set[Gap]
  ): Vector[Golden] =
    Vector(
      Golden(previous, FormatVersion.V5, file, v5, v7, preview, gaps),
      Golden(fixtures, FormatVersion.V7, file, v7, v7, preview, gaps)
    )
  private val goldens =
    both("preview-4320.bin", previewV5, previewV7, true, legacyGaps) ++
      both("eras/conway-730.bin", conwayV5, conwayV7, false, legacyGaps) ++
      both("conway-native-730.bin", nativeV5, nativeV7, false, currentGaps)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  test("every supported snapshot version has an immutable current-format golden") {
    assertEquals(
      goldens.map(_.version).toSet,
      FileKind.SnapshotFile.history.toList
        .map(_._1)
        .filter(FileKind.SnapshotFile.readableFrom <= _)
        .toSet
    )
    goldens.foreach { g =>
      val raw = JFiles.readAllBytes(g.dir.resolve(g.file))
      assertEquals(sha256(raw), g.sha256, g.file)
      val content = FileLedgerSnapshots.verified(raw).fold(e => fail(e.message), identity)
      val found = new String(content.take(8), "US-ASCII")
      assertEquals(found, FileKind.SnapshotFile.identifier(g.version), g.file)
    }
  }

  test("every maintained V7 file is the registered conversion of its V5 source") {
    val raw = JFiles.readAllBytes(fixtures.resolve("manifest.json"))
    val manifest = io.circe.parser
      .parse(new String(raw, "UTF-8"))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[String]("format").toOption, Some("SCRBSNP7"))
    val entries = manifest.get[Vector[io.circe.Json]]("files").fold(e => fail(e.message), identity)
    assertEquals(entries.size, 17)
    entries.foreach { entry =>
      val cursor = entry.hcursor
      def field(name: String): String =
        cursor.get[String](name).fold(e => fail(e.message), identity)
      val file = field("file")
      val source = JFiles.readAllBytes(previous.resolve(file))
      val target = JFiles.readAllBytes(fixtures.resolve(file))
      assertEquals(sha256(source), field("sourceSha256"), file)
      assertEquals(sha256(target), field("targetSha256"), file)
      val converted = FileLedgerSnapshots
        .verified(source)
        .flatMap(SnapshotV7.fromV5)
        .map(FileLedgerSnapshots.closed)
        .fold(e => fail(e.message), identity)
      assert(java.util.Arrays.equals(converted, target), file)
      val content = FileLedgerSnapshots.verified(target).fold(e => fail(e.message), identity)
      assertEquals(SnapshotV7.bindingOf(content), Right(CheckpointBinding.Legacy), file)
      assertEquals(
        SnapshotV7.sidecarOf(content),
        FileLedgerSnapshots.verified(source).flatMap(SnapshotV5.sidecarOf),
        file
      )
    }
  }

  test("the complete era conversion manifest pins every file and carried metadata") {
    val raw = JFiles.readAllBytes(previous.resolve("manifest.json"))
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
      val bytes = JFiles.readAllBytes(previous.resolve(file))
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
    test(s"${g.file} at ${g.version}: migrates once, then restores its state and carried gaps") {
      given Ask[IO, ConsensusMirrorEnv] = Ask.const(g.environment)
      given SnapshotFormat[IO, MirrorState] = SnapshotV7.format[IO](SnapshotCheck.Full)
      tempDir.use { dir =>
        val name = FileLedgerSnapshots.nameOf(g.anchor)
        val file = dir / name
        for
          original <- NativeSnapshots
            .read[IO](fixtures.resolve(g.file), g.environment)
            .map(_.fold(e => fail(e.message), identity))
          _ <- IO(JFiles.copy(g.dir.resolve(g.file), file.toNioPath))
          first <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          second <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          raw <- IO(JFiles.readAllBytes(file.toNioPath))
          content = FileLedgerSnapshots.verified(raw).fold(e => fail(e.message), identity)
          sidecar = SnapshotV7.sidecarOf(content).fold(e => fail(e.message), identity)
          restored <- ok(
            FileLedgerSnapshots
              .open[IO, MirrorState](dir)
              .flatMap(
                _.restore(s =>
                  IO.pure(
                    Either.cond(s.anchor.contains(g.anchor), (), StorageError.Missing("anchor"))
                  )
                )
              )
          )
        yield
          val migrated =
            if g.version == FormatVersion.V7 then Nil
            else List(Migrated(FileKind.SnapshotFile, g.version, FormatVersion.V7, 1))
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
