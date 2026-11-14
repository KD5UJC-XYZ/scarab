// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.FollowState
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, GenDelegPair, Nonce, ParamUpdate}
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

import StorageTestKit.*

/** V11 holds the follow state where V10 held the bootstrap (ledger-lite) state (#988): the flag,
  * set by no writer since V5, and the lite ledger, read by none, are gone; the era of the last
  * block applied and the epoch verdicts, which the follower's events are made from, are kept. A V10
  * file migrates with its recorded build, binding and Haskell bytes unchanged and its sidecar
  * converted, whatever its flag and lite ledger held (ADR 0006).
  */
class SnapshotV11MigrationSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), _.mirrorEnv())

  private val v10 = JPath.of("fixtures/snapshot-v10")
  private val v11 = JPath.of("fixtures/snapshot-v11")

  private val names: Vector[String] =
    val walk = JFiles.walk(v10)
    try
      walk.iterator.asScala
        .filter(_.getFileName.toString.endsWith(".bin"))
        .map(v10.relativize(_).toString)
        .toVector
        .sorted
    finally walk.close()

  private def content(dir: JPath, name: String): Array[Byte] =
    FileLedgerSnapshots
      .verified(JFiles.readAllBytes(dir.resolve(name)))
      .fold(e => fail(e.message), identity)

  private def sections(name: String) =
    SnapshotV10.sections(content(v10, name)).fold(e => fail(e.message), identity)

  private def boot(sidecar: FrozenSidecar.SnapshotSidecar): FrozenSidecar.BootstrapState =
    sidecar.chain match
      case FrozenSidecar.Chain.InByron(_, extras)      => extras.boot
      case FrozenSidecar.Chain.InShelley(_, _, _, ext) => ext.consensus.boot

  private def follow(sidecar: SnapshotSidecar): FollowState = sidecar.chain match
    case SnapshotSidecar.Chain.InByron(_, extras)         => extras.follow
    case SnapshotSidecar.Chain.InShelley(_, _, _, mirror) => mirror.consensus.follow

  test("the frozen V10 schema writes every committed V10 file's sections back byte for byte") {
    assertEquals(names.size, 17)
    names.foreach { name =>
      val (build, binding, sidecar, haskell) = sections(name)
      assertEquals(build, ReferenceBuild.Release11_1.name, name)
      assertEquals(
        SnapshotV10.framed(ReferenceBuild.Release11_1, binding, sidecar, haskell).toVector,
        content(v10, name).toVector,
        name
      )
    }
  }

  test("the committed V10 files hold eras and epoch verdicts, which the migration has to keep") {
    val boots = names.map(name => boot(sections(name)._3))
    assert(boots.forall(!_.active), "a writer since V5 set the flag")
    assert(boots.exists(_.era.isDefined), "no fixture holds an era")
    assert(boots.exists(_.stats.exists(_.total > 0)), "no fixture holds verdicts of an epoch")
    assert(boots.exists(_.completed.exists(_.total > 0)), "no fixture holds a completed epoch")
  }

  test("a committed V10 file converts to V11: the follow state is its era and epoch verdicts") {
    names.foreach { name =>
      val source = content(v10, name)
      val (build, binding, previous, haskell) = sections(name)
      val converted = SnapshotV11.fromV10(source).fold(e => fail(e.message), identity)
      assertEquals(converted.toVector, content(v11, name).toVector, name)
      val (recorded, bound, sidecar, bytes) =
        SnapshotV11
          .sections(converted, xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout.Release11_1)
          .fold(e => fail(e.message), identity)
      assertEquals(recorded.name, build, name)
      assertEquals(bound, binding, name)
      assertEquals(bytes, haskell, name)
      val was = boot(previous)
      assertEquals(follow(sidecar), FollowState(was.era, was.stats, was.completed), name)
      assertEquals(sidecar.anchor, previous.anchor, name)
      assertEquals(sidecar.observed, previous.observed, name)
      assertEquals(sidecar.gaps, previous.gaps, name)
      (sidecar.chain, previous.chain) match
        case (
              SnapshotSidecar.Chain.InByron(pending, extras),
              FrozenSidecar.Chain.InByron(oldPending, oldExtras)
            ) =>
          assertEquals(pending, oldPending, name)
          assertEquals(extras.byron, oldExtras.byron, name)
          assertEquals(extras.protocol, oldExtras.protocol, name)
        case (
              SnapshotSidecar.Chain.InShelley(utxo, params, prevParams, mirror),
              FrozenSidecar.Chain.InShelley(oldUtxo, oldParams, oldPrevParams, oldMirror)
            ) =>
          assertEquals(utxo, oldUtxo, name)
          assertEquals(params, oldParams, name)
          assertEquals(prevParams, oldPrevParams, name)
          assertEquals(mirror.ledger, oldMirror.ledger, name)
          assertEquals(mirror.consensus.byron, oldMirror.consensus.byron, name)
          assertEquals(mirror.consensus.protocol, oldMirror.consensus.protocol, name)
        case _ => fail(s"$name: the migration changed the family of era")
    }
  }

  test("a committed V10 file migrates once to V11 at every entry point and reads as maintained") {
    names.traverse_ { name =>
      tempDir.use { dir =>
        val file = dir / "snapshot-00000000000000000025-25.bin"
        val migration = SnapshotMigration.toCurrent[IO]
        for
          _ <- IO.blocking(JFiles.copy(v10.resolve(name), file.toNioPath))
          _ <- ok(migration.preflight(dir))
          detected <- ok(migration.detect(file))
          first <- ok(migration.run(dir))
          second <- ok(migration.run(dir))
          after <- IO.blocking(JFiles.readAllBytes(file.toNioPath))
          migrated <- NativeSnapshots.decode[IO](after, env)
          maintained <- NativeSnapshots.read[IO](v11.resolve(name), env)
        yield
          assertEquals(detected, FormatVersion.V10, name)
          assertEquals(
            first,
            SnapshotMigration.Report(
              List(Migrated(FileKind.SnapshotFile, FormatVersion.V10, FormatVersion.V11, 1)),
              Nil
            ),
            name
          )
          assertEquals(second, SnapshotMigration.Report.empty, name)
          assertEquals(after.toVector, JFiles.readAllBytes(v11.resolve(name)).toVector, name)
          assert(migrated.isRight, s"$name: the migrated file does not read")
          assertEquals(migrated, maintained, name)
      }
    }
  }

  /** A lite ledger that holds something in every field. */
  private val heldLite: FrozenSidecar.LiteLedger =
    val key = Bytes(Vector.fill(28)(7.toByte))
    val vrf = Bytes(Vector.fill(32)(8.toByte))
    FrozenSidecar.LiteLedger(
      5,
      FrozenSidecar.LiteParams((2, 0), Rational(1, 2), Nonce.Hash(vrf), 65536, 16384, 1100),
      Map(key -> GenDelegPair(key, vrf)),
      Vector(FrozenSidecar.FutureGenDeleg(77, key, GenDelegPair(key, vrf))),
      Map(key -> ParamUpdate(Map(12 -> Bytes(Vector(0xf6.toByte))))),
      Map(key -> ParamUpdate(Map(2 -> Bytes(Vector(1.toByte))))),
      Map(key -> FrozenSidecar.PoolEntry(Set(vrf), Some(9))),
      3
    )

  test("the flag and the lite ledger of a V10 file are dropped, whatever they held") {
    names.foreach { name =>
      val (_, binding, previous, haskell) = sections(name)
      val live = FrozenSidecar.live(previous)
      def written(active: Boolean, lite: FrozenSidecar.LiteLedger) =
        SnapshotV11
          .fromV10(
            SnapshotV10.framed(
              ReferenceBuild.Release11_1,
              binding,
              PreviousSidecars.of(live, active, lite),
              haskell
            )
          )
          .fold(e => fail(e.message), identity)
      val plain = written(false, PreviousSidecars.emptyLite)
      val held = written(true, heldLite)
      assertEquals(held.toVector, plain.toVector, name)
      assertEquals(held.toVector, content(v11, name).toVector, name)
      assertEquals(SnapshotV11.sidecarOf(held), Right(live), name)
    }
  }

  test("a V10 file recorded under another build migrates with its build, not decoded") {
    val (_, binding, sidecar, _) = sections("eras/conway-730.bin")
    // the Haskell section is not a CBOR item: the migration does not read it
    val leios = SnapshotV10.framed(
      ReferenceBuild.LeiosB004fe0c,
      binding,
      sidecar,
      Bytes.wrap(Array(0xff.toByte))
    )
    val converted = SnapshotV11.fromV10(leios).fold(e => fail(e.message), identity)
    assertEquals(SnapshotV11.recordedOf(converted), Right("leios-b004fe0c"))
    assertEquals(SnapshotV11.bindingOf(converted), Right(binding))
    assertEquals(SnapshotV11.sidecarOf(converted), Right(FrozenSidecar.live(sidecar)))
    assertEquals(converted.last, 0xff.toByte)
  }

  test("a directory of V5 to V11 files migrates every one to V11 in one run") {
    val sources = Vector(
      "fixtures/snapshot-v5" -> FormatVersion.V5,
      "fixtures/snapshot-v7" -> FormatVersion.V7,
      "fixtures/snapshot-v8" -> FormatVersion.V8,
      "fixtures/snapshot-v9" -> FormatVersion.V9,
      "fixtures/snapshot-v10" -> FormatVersion.V10,
      "fixtures/snapshot-v11" -> FormatVersion.V11
    )
    val files = Vector(
      "eras/byron-25.bin",
      "eras/shelley-149.bin",
      "eras/alonzo-539.bin",
      "eras/babbage-727.bin",
      "eras/conway-730.bin",
      "preview-4320.bin"
    )
    tempDir.use { dir =>
      val placed = sources.zip(files).zipWithIndex.map { case (((source, version), file), i) =>
        (dir / f"snapshot-$i%020d-$i.bin", source, version, file)
      }
      for
        _ <- placed.traverse_((target, source, _, file) =>
          IO.blocking(JFiles.copy(JPath.of(source).resolve(file), target.toNioPath))
        )
        report <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        after <- placed.traverse((target, _, _, file) =>
          IO.blocking(JFiles.readAllBytes(target.toNioPath).toVector -> file)
        )
      yield
        assertEquals(
          report.migrated,
          placed
            .collect { case (_, _, v, _) if v != FormatVersion.V11 => v }
            .sorted
            .map(Migrated(FileKind.SnapshotFile, _, FormatVersion.V11, 1))
            .toList
        )
        after.foreach((bytes, file) =>
          assertEquals(bytes, JFiles.readAllBytes(v11.resolve(file)).toVector, file)
        )
    }
  }

  test("an interrupted V10 to V11 step leaves the V10 file, and the next run converts it") {
    tempDir.use { dir =>
      val name = "eras/conway-730.bin"
      val file = dir / "snapshot-00000000000000003567-730.bin"
      val temporary: Path = dir / "snapshot-00000000000000003567-730.bin.tmp"
      for
        _ <- IO.blocking(JFiles.copy(v10.resolve(name), file.toNioPath))
        // a crash after the temporary file was written, before the rename
        _ <- IO.blocking(JFiles.write(temporary.toNioPath, Array[Byte](1, 2, 3)))
        before <- IO.blocking(JFiles.readAllBytes(file.toNioPath).toVector)
        report <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        after <- IO.blocking(JFiles.readAllBytes(file.toNioPath).toVector)
      yield
        assertEquals(before, JFiles.readAllBytes(v10.resolve(name)).toVector)
        assertEquals(
          report.migrated,
          List(Migrated(FileKind.SnapshotFile, FormatVersion.V10, FormatVersion.V11, 1))
        )
        assertEquals(after, JFiles.readAllBytes(v11.resolve(name)).toVector)
    }
  }
