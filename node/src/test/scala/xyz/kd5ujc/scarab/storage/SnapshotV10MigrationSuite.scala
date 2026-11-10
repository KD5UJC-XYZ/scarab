// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, ReferenceLayout}

import StorageTestKit.*

/** V10 records the reference build whose layout a snapshot's Haskell state bytes are in (#826); V9
  * and the versions before it recorded none, and held the 11.1 layout only. A V9 file migrates as
  * 11.1 with its binding, sidecar and Haskell bytes unchanged (ADR 0006), and a V10 file recorded
  * under a build of another layout is not read: there is no conversion between builds.
  */
class SnapshotV10MigrationSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), _.mirrorEnv())

  private def content(dir: String, name: String): Array[Byte] =
    FileLedgerSnapshots
      .verified(JFiles.readAllBytes(JPath.of(dir).resolve(name)))
      .fold(e => fail(e.message), identity)

  private val names = Vector(
    "eras/byron-25.bin",
    "eras/shelley-149.bin",
    "eras/babbage-727.bin",
    "eras/conway-730.bin",
    "conway-native-730.bin"
  )

  /** A V10 content of the same sections as `v10`, recorded under `build`. */
  private def recordedUnder(build: ReferenceBuild, v10: Array[Byte]): Array[Byte] =
    val (_, binding, sidecar, haskell) =
      SnapshotV10.sections(v10, ReferenceLayout.Release11_1).fold(e => fail(e.message), identity)
    SnapshotV10.framed(build, binding, sidecar, haskell)

  test("a committed V9 file migrates once to V10, recorded as 11.1, its sections byte for byte") {
    names.traverse_ { name =>
      tempDir.use { dir =>
        val file = dir / "snapshot-00000000000000000025-25.bin"
        for
          _ <- IO.blocking(
            JFiles.copy(JPath.of("fixtures/snapshot-v9").resolve(name), file.toNioPath)
          )
          first <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          second <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          after <- IO.blocking(JFiles.readAllBytes(file.toNioPath))
          migrated <- NativeSnapshots.decode[IO](after, env)
          maintained <- NativeSnapshots
            .read[IO](JPath.of("fixtures/snapshot-v10").resolve(name), env)
        yield
          assertEquals(
            first,
            SnapshotMigration.Report(
              List(Migrated(FileKind.SnapshotFile, FormatVersion.V9, FormatVersion.V10, 1)),
              Nil
            ),
            name
          )
          assertEquals(second, SnapshotMigration.Report.empty, name)
          assertEquals(
            after.toVector,
            JFiles.readAllBytes(JPath.of("fixtures/snapshot-v10").resolve(name)).toVector,
            name
          )
          val v9 = content("fixtures/snapshot-v9", name)
          val v10 = FileLedgerSnapshots.verified(after).fold(e => fail(e.message), identity)
          val (binding, sidecar, haskell) =
            SnapshotV9.sections(v9).fold(e => fail(e.message), identity)
          assertEquals(
            SnapshotV10.sections(v10, ReferenceLayout.Release11_1),
            Right((ReferenceBuild.Release11_1, binding, sidecar, haskell)),
            name
          )
          assert(migrated.isRight, s"$name: the migrated file does not read")
          assertEquals(migrated, maintained, name)
      }
    }
  }

  test("the 11.1 codec reads no other layout, so a V9 file can hold 11.1 bytes only") {
    // a Dijkstra telescope or tip is refused with DijkstraNotYet.LedgerState (DijkstraNotYetSuite);
    // the Leios build writes a three-part ExtLedgerState (ouroboros-consensus 2416493b
    // `Ledger/Extended.hs:420-442`), which the 11.1 decoder refuses as the reference's does
    // (`decodeListLenOf 2`, 82ecba32 `Ledger/Extended.hs:268-280`), and trailing bytes
    names.filter(_ != "eras/byron-25.bin").foreach { name =>
      val (_, _, haskell) =
        SnapshotV9
          .sections(content("fixtures/snapshot-v9", name))
          .fold(e => fail(e.message), identity)
      val bytes = haskell.toArray
      assert(HaskellLedgerFile.decode(haskell).isRight, name)
      assertEquals(bytes(2) & 0xff, 0x82, s"$name: [1, [ledger, header]]")
      val threeParts = Bytes.wrap(bytes.updated(2, 0x83.toByte) :+ 0xf6.toByte)
      assert(HaskellLedgerFile.decode(threeParts).isLeft, s"$name: a three-part state")
      assert(HaskellLedgerFile.decode(Bytes.wrap(bytes :+ 0.toByte)).isLeft, s"$name: trailing")
    }
  }

  test("a V10 file recorded under another build is refused before its state bytes are read") {
    val v10 = content("fixtures/snapshot-v10", "eras/conway-730.bin")
    val (_, binding, sidecar, _) =
      SnapshotV10.sections(v10, ReferenceLayout.Release11_1).fold(e => fail(e.message), identity)
    // the Haskell section is not a CBOR item: only a decoder that reads it would notice
    val leios =
      SnapshotV10.framed(
        ReferenceBuild.LeiosB004fe0c,
        binding,
        sidecar,
        Bytes.wrap(Array(0xff.toByte))
      )
    assertEquals(SnapshotV10.recordedOf(leios), Right("leios-b004fe0c"))
    assertEquals(
      SnapshotV10.decode(env, ReferenceLayout.Release11_1, leios).map(_.anchor),
      Left(
        StorageError.ReferenceBuildMismatch(
          "snapshot",
          ReferenceBuild.LeiosB004fe0c,
          ReferenceBuild.Release11_1
        )
      )
    )
    val unknown = SnapshotV10.Magic ++ Array(4.toByte) ++ "11.9".getBytes("US-ASCII") ++
      v10.drop(SnapshotV10.Magic.length + 5)
    assertEquals(
      SnapshotV10.decode(env, ReferenceLayout.Release11_1, unknown).map(_.anchor),
      Left(StorageError.UnknownReferenceBuild("snapshot", "11.9", ReferenceBuild.Release11_1))
    )
    // the sidecar and the binding are Scarab's, whatever the build: they still read
    assertEquals(SnapshotV10.sidecarOf(leios), Right(sidecar))
    assertEquals(SnapshotV10.bindingOf(unknown), Right(binding))
  }

  test(
    "a restore skips a snapshot recorded under another build, names both builds, and takes an older one"
  ) {
    given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
    given SnapshotFormat[IO, MirrorState] =
      SnapshotV10.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)
    def named(file: String): (String, Array[Byte]) =
      val raw = content("fixtures/snapshot-v10", file)
      val anchor = SnapshotV10
        .sidecarOf(raw)
        .toOption
        .flatMap(_.anchor)
        .getOrElse(fail(s"$file has no anchor"))
      FileLedgerSnapshots.nameOf(anchor) -> raw
    val (older, olderContent) = named("eras/conway-729.bin")
    val (newer, newerContent) = named("eras/conway-730.bin")
    def put(dir: fs2.io.file.Path, name: String, content: Array[Byte]) =
      IO.blocking(JFiles.write((dir / name).toNioPath, FileLedgerSnapshots.closed(content)))
    val mismatch = StorageError.ReferenceBuildMismatch(
      "snapshot",
      ReferenceBuild.LeiosB004fe0c,
      ReferenceBuild.Release11_1
    )
    def restore(dir: fs2.io.file.Path) = ok(
      FileLedgerSnapshots
        .open[IO, MirrorState](dir)
        .flatMap(_.restore(_ => IO.pure(Right(()))))
    )
    for
      both <- tempDir.use { dir =>
        put(dir, older, olderContent) *>
          put(dir, newer, recordedUnder(ReferenceBuild.LeiosB004fe0c, newerContent)) *>
          restore(dir)
      }
      alone <- tempDir.use { dir =>
        put(dir, newer, recordedUnder(ReferenceBuild.LeiosB004fe0c, newerContent)) *> restore(dir)
      }
    yield
      assertEquals(both.snapshot.map(_._1), Some(older))
      assertEquals(
        both.skipped,
        Vector(LedgerSnapshots.Skipped(newer, mismatch, LedgerSnapshots.Unused.Deleted))
      )
      assert(mismatch.message.contains("leios-b004fe0c"), mismatch.message)
      assert(mismatch.message.contains("11.1 (stable, cardano-node 11.1.3)"), mismatch.message)
      // with nothing else to restore, the stored blocks are replayed from genesis
      assertEquals(alone.snapshot, None)
      assertEquals(
        alone.skipped,
        Vector(LedgerSnapshots.Skipped(newer, mismatch, LedgerSnapshots.Unused.Deleted))
      )
  }
