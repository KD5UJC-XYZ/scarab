// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
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
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

import StorageTestKit.*

/** Complete fixed files: the maintained V10 inputs, the V9 files they were converted from, the V8
  * files those were converted from, the V7 files those were converted from, and the V5 files (from
  * a native V5 writer and verified historical conversions) at the start; the V5, V7, V8 and V9
  * files remain the previous-version inputs of the migration. Original capture provenance remains
  * pinned separately.
  */
class SnapshotGoldenSuite extends CatsEffectSuite:
  private val previous = JPath.of("fixtures/snapshot-v5")
  private val v7 = JPath.of("fixtures/snapshot-v7")
  private val v8 = JPath.of("fixtures/snapshot-v8")
  private val v9 = JPath.of("fixtures/snapshot-v9")
  private val fixtures = JPath.of("fixtures/snapshot-v10")
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
  private val previewV8 = "f5e2584a862e521162c2098a99dd3268e74300f7aae6fd069279cbe94d831ad0"
  private val conwayV8 = "2eba1699708be0c1c99a2224f596ebedfced2043e5c92354e7413a534f443e95"
  private val nativeV8 = "c27be6850661f3fe1c01e5662d55873cf7f60074a5a16f285a62ca806908b647"
  private val previewV9 = "ddaad24d00de33e82cd4459f2f86fa9cb0d9bafc1681fc01ca639f3283bf6acf"
  private val conwayV9 = "399826ff08ab1945ae258f53d7e3d23d060d587b6902a403107f5c656690d954"
  private val nativeV9 = "a91a5009ff79d542fb1a63bb6e59433b952b0047b3df0266580736f8f0ce74bc"
  private val previewV10 = "d1017fff1b9c58415115206fb825b45950ac8cb2826dac6e176aa48b237a47bb"
  private val conwayV10 = "3fc6824aeedfda88e54fc8bd9ac1664681778086a757d38a70aa390d3c2a3ed9"
  private val nativeV10 = "01d2ad4f9b2ab0962aafe20625620747f5e33c653016521bed46729805ceb859"
  private def all(
      file: String,
      hashes: (String, String, String, String, String),
      preview: Boolean,
      gaps: Set[Gap]
  ): Vector[Golden] =
    val (h5, h7, h8, h9, h10) = hashes
    Vector(
      Golden(previous, FormatVersion.V5, file, h5, h10, preview, gaps),
      Golden(v7, FormatVersion.V7, file, h7, h10, preview, gaps),
      Golden(v8, FormatVersion.V8, file, h8, h10, preview, gaps),
      Golden(v9, FormatVersion.V9, file, h9, h10, preview, gaps),
      Golden(fixtures, FormatVersion.V10, file, h10, h10, preview, gaps)
    )
  private val goldens =
    all(
      "preview-4320.bin",
      (previewV5, previewV7, previewV8, previewV9, previewV10),
      true,
      legacyGaps
    ) ++
      all(
        "eras/conway-730.bin",
        (conwayV5, conwayV7, conwayV8, conwayV9, conwayV10),
        false,
        legacyGaps
      ) ++
      all(
        "conway-native-730.bin",
        (nativeV5, nativeV7, nativeV8, nativeV9, nativeV10),
        false,
        currentGaps
      )

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

  test("every V7 file is the registered conversion of its V5 source") {
    val raw = JFiles.readAllBytes(v7.resolve("manifest.json"))
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
      val target = JFiles.readAllBytes(v7.resolve(file))
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

  test("every V8 file is the registered conversion of its V7 source") {
    val raw = JFiles.readAllBytes(v8.resolve("manifest.json"))
    val manifest = io.circe.parser
      .parse(new String(raw, "UTF-8"))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[String]("format").toOption, Some("SCRBSNP8"))
    val entries = manifest.get[Vector[io.circe.Json]]("files").fold(e => fail(e.message), identity)
    assertEquals(entries.size, 17)
    entries.foreach { entry =>
      val cursor = entry.hcursor
      def field(name: String): String =
        cursor.get[String](name).fold(e => fail(e.message), identity)
      val file = field("file")
      val source = JFiles.readAllBytes(v7.resolve(file))
      val target = JFiles.readAllBytes(v8.resolve(file))
      assertEquals(sha256(source), field("sourceSha256"), file)
      assertEquals(sha256(target), field("targetSha256"), file)
      val converted = FileLedgerSnapshots
        .verified(source)
        .flatMap(SnapshotV8.fromV7)
        .map(FileLedgerSnapshots.closed)
        .fold(e => fail(e.message), identity)
      assert(java.util.Arrays.equals(converted, target), file)
      val content = FileLedgerSnapshots.verified(target).fold(e => fail(e.message), identity)
      val sourceContent = FileLedgerSnapshots.verified(source).fold(e => fail(e.message), identity)
      assertEquals(SnapshotV8.bindingOf(content), SnapshotV7.bindingOf(sourceContent), file)
      assertEquals(SnapshotV8.sidecarOf(content), SnapshotV7.sidecarOf(sourceContent), file)
      // the binding and the Haskell bytes are kept; only the magic and the sidecar's schema
      // fingerprint differ, the states holding no Byron software proposal or application version
      val (_, _, haskell) = SnapshotV8.sections(content).fold(e => fail(e.message), identity)
      val (_, _, sourceHaskell) =
        SnapshotV7.sections(sourceContent).fold(e => fail(e.message), identity)
      assertEquals(haskell, sourceHaskell, file)
      assertEquals(content.length, sourceContent.length, file)
    }
  }

  test("every V9 file is the registered conversion of its V8 source") {
    val raw = JFiles.readAllBytes(v9.resolve("manifest.json"))
    val manifest = io.circe.parser
      .parse(new String(raw, "UTF-8"))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[String]("format").toOption, Some("SCRBSNP9"))
    val entries = manifest.get[Vector[io.circe.Json]]("files").fold(e => fail(e.message), identity)
    assertEquals(entries.size, 17)
    entries.foreach { entry =>
      val cursor = entry.hcursor
      def field(name: String): String =
        cursor.get[String](name).fold(e => fail(e.message), identity)
      val file = field("file")
      val source = JFiles.readAllBytes(v8.resolve(file))
      val target = JFiles.readAllBytes(v9.resolve(file))
      assertEquals(sha256(source), field("sourceSha256"), file)
      assertEquals(sha256(target), field("targetSha256"), file)
      val converted = FileLedgerSnapshots
        .verified(source)
        .flatMap(SnapshotV9.fromV8)
        .map(FileLedgerSnapshots.closed)
        .fold(e => fail(e.message), identity)
      assert(java.util.Arrays.equals(converted, target), file)
      val content = FileLedgerSnapshots.verified(target).fold(e => fail(e.message), identity)
      val sourceContent = FileLedgerSnapshots.verified(source).fold(e => fail(e.message), identity)
      assertEquals(SnapshotV9.bindingOf(content), SnapshotV8.bindingOf(sourceContent), file)
      assertEquals(SnapshotV9.sidecarOf(content), SnapshotV8.sidecarOf(sourceContent), file)
      // the binding, the sidecar's values and the Haskell bytes are kept; the magic, the sidecar's
      // schema fingerprint and its parameters' encoding differ, the parameters gaining the fields
      // Dijkstra adds at their defaults, the eras of a V8 file being encoded as before
      val (_, _, haskell) = SnapshotV9.sections(content).fold(e => fail(e.message), identity)
      val (_, _, sourceHaskell) =
        SnapshotV8.sections(sourceContent).fold(e => fail(e.message), identity)
      assertEquals(haskell, sourceHaskell, file)
      assertEquals(content.drop(8).take(4).toVector, sourceContent.drop(8).take(4).toVector, file)
    }
  }

  test("every maintained V10 file is the registered conversion of its V9 source") {
    val raw = JFiles.readAllBytes(fixtures.resolve("manifest.json"))
    val manifest = io.circe.parser
      .parse(new String(raw, "UTF-8"))
      .fold(e => fail(e.message), identity)
      .hcursor
    assertEquals(manifest.get[String]("format").toOption, Some("SCRBSNPA"))
    val entries = manifest.get[Vector[io.circe.Json]]("files").fold(e => fail(e.message), identity)
    assertEquals(entries.size, 17)
    entries.foreach { entry =>
      val cursor = entry.hcursor
      def field(name: String): String =
        cursor.get[String](name).fold(e => fail(e.message), identity)
      val file = field("file")
      val source = JFiles.readAllBytes(v9.resolve(file))
      val target = JFiles.readAllBytes(fixtures.resolve(file))
      assertEquals(sha256(source), field("sourceSha256"), file)
      assertEquals(sha256(target), field("targetSha256"), file)
      val converted = FileLedgerSnapshots
        .verified(source)
        .flatMap(SnapshotV10.fromV9)
        .map(FileLedgerSnapshots.closed)
        .fold(e => fail(e.message), identity)
      assert(java.util.Arrays.equals(converted, target), file)
      val content = FileLedgerSnapshots.verified(target).fold(e => fail(e.message), identity)
      val sourceContent = FileLedgerSnapshots.verified(source).fold(e => fail(e.message), identity)
      // the 11.1 build recorded; the binding, the sidecar and the Haskell bytes those of V9, byte
      // for byte: the V9 file after its magic, with the five bytes of the build in front
      assertEquals(SnapshotV10.recordedOf(content), Right("11.1"), file)
      val (build, binding, sidecar, haskell) =
        SnapshotV10
          .sections(content, ReferenceLayout.Release11_1)
          .fold(e => fail(e.message), identity)
      val (sourceBinding, sourceSidecar, sourceHaskell) =
        SnapshotV9.sections(sourceContent).fold(e => fail(e.message), identity)
      assertEquals(build, ReferenceBuild.Release11_1, file)
      assertEquals(binding, sourceBinding, file)
      assertEquals(sidecar, sourceSidecar, file)
      assertEquals(haskell, sourceHaskell, file)
      assertEquals(
        content.toVector,
        (SnapshotV10.Magic ++ Array(4.toByte) ++ "11.1".getBytes("US-ASCII") ++
          sourceContent.drop(SnapshotV9.Magic.length)).toVector,
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
      given SnapshotFormat[IO, MirrorState] =
        SnapshotV10.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)
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
          sidecar = SnapshotV10.sidecarOf(content).fold(e => fail(e.message), identity)
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
            if g.version == FormatVersion.V10 then Nil
            else List(Migrated(FileKind.SnapshotFile, g.version, FormatVersion.V10, 1))
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
