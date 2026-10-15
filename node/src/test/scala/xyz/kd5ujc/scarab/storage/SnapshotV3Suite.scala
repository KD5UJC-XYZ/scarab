// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.{Ask, Raise}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{Block, FileKind, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.node.{NodeRules, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era

import StorageTestKit.*

/** The snapshot format V3 and the migration to it, on the era-walking capture (`fixtures/eras`):
  * the state before the first block and after every block, from Byron to Conway, is written as a V3
  * snapshot and read back (it must come back as the mirror normalises it), and V2 and V1 snapshot
  * files of those states are migrated.
  */
class SnapshotV3Suite extends CatsEffectSuite:
  private val fixtures = java.nio.file.Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val env = setup.mirrorEnv()

  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
  private given v3: SnapshotFormat[IO, NodeState] = SnapshotV3.format[IO]
  private val v2: SnapshotFormat[IO, NodeState] =
    given Codec[StateSnapshot[NodeState]] = NodeCodecs.nodeSnapshot
    SnapshotV2.format[IO, NodeState]

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The snapshots the chain passes through: the state after each block, with its anchor. */
  private lazy val snapshots: Vector[(Era, StateSnapshot[NodeState])] =
    blocks.indices
      .foldLeft(Vector.empty[(Era, StateSnapshot[NodeState])]) { (acc, i) =>
        val before = acc.lastOption.fold(setup.start)(_._2.state)
        val h = blocks(i).header
        NodeRules.applyBlock(before, h, raws(i), setup.praos, setup.globals, setup.byronRules) match
          case Right(next) =>
            acc :+ (blocks(i).era -> StateSnapshot(
              Some(BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)),
              next
            ))
          case Left((rule, reason)) => fail(s"block $i rejected by $rule: $reason")
      }

  private def normalised(s: StateSnapshot[NodeState]): StateSnapshot[NodeState] =
    s.copy(state = SnapshotV3.canonical(env, s.state))

  private def decoded(bytes: Array[Byte]): IO[StateSnapshot[NodeState]] =
    v3.decode(bytes).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  /** The writer's verdict on each state: the content, or the refusal. */
  private lazy val written
      : Vector[(Era, StateSnapshot[NodeState], Either[SnapshotRefusal, Array[Byte]])] =
    snapshots.map((era, s) => (era, s, v3.encode(s).unsafeRunSync()))

  /** The states the writer accepts. */
  private lazy val accepted: Vector[(Era, StateSnapshot[NodeState], Array[Byte])] =
    written.collect { case (era, s, Right(bytes)) => (era, s, bytes) }

  test("the eras fixture reaches every era the format must hold") {
    assertEquals(snapshots.map(_._1).distinct.toSet, Era.values.toSet)
    assertEquals(accepted.map(_._1).distinct.toSet, Era.values.toSet)
  }

  test("every state of the eras fixture comes back from its V3 snapshot, as the mirror has it") {
    accepted.traverse_ { (era, snapshot, bytes) =>
      val label = s"$era block ${snapshot.anchor.map(_.blockNo)}"
      decoded(bytes).map(back => assertEquals(back, normalised(snapshot), label))
    }
  }

  test("the writer refuses what would not restore: the Alonzo states with a pending update only") {
    // A parameter update of the fixture sets coinsPerUTxOByte (key 17) in the Alonzo era, whose
    // PParams has no such field: the Haskell encoding cannot hold the future parameters of the node
    val refused = written.collect { case (era, s, Left(r)) => (era, s.anchor.map(_.blockNo), r) }
    assert(refused.forall(_._1 == Era.Alonzo), refused.toString)
    assert(refused.forall(_._3.isInstanceOf[SnapshotRefusal.WouldNotRead]), refused.toString)
    assert(refused.size < written.count(_._1 == Era.Alonzo), refused.size.toString)
  }

  test("a Shelley-based snapshot has the Haskell state after the sidecar, Byron has none") {
    accepted.foreach { (era, _, bytes) =>
      val sidecar = java.nio.ByteBuffer.wrap(bytes, 8, 4).getInt
      val haskell = bytes.length - 12 - sidecar
      assertEquals(new String(bytes.take(8), "US-ASCII"), "SCRBSNP3")
      if era == Era.Byron then assertEquals(haskell, 0, era.toString)
      // [1, [telescope, header state]]: the state file of the reference node
      else assert(haskell > 0 && (bytes(12 + sidecar) & 0xff) == 0x82, era.toString)
    }
  }

  test("a damaged V3 file is a typed error: magic, sidecar length, fingerprint, Haskell section") {
    val bytes = accepted.last._3
    val cases: List[(String, Array[Byte])] = List(
      "magic" -> bytes.updated(7, '4'.toByte),
      "sidecar length" -> bytes.updated(8, 0x7f.toByte),
      "fingerprint" -> bytes.updated(12, (bytes(12) ^ 1).toByte),
      "haskell section" -> bytes.take(bytes.length - 40),
      "too short" -> bytes.take(5)
    )
    cases.traverse_((what, damaged) => v3.decode(damaged).map(r => assert(r.isLeft, what)))
  }

  // ---- the migration

  private def writeV2(dir: Path, ss: Vector[StateSnapshot[NodeState]]): IO[List[String]] =
    ok {
      given SnapshotFormat[IO, NodeState] = v2
      FileLedgerSnapshots.open[IO, NodeState](dir, keep = 100).flatMap { store =>
        ss.toList.traverse(s => store.write(s).map(_.toOption.map(_.name).getOrElse(fail("v2"))))
      }
    }

  private def restoreAll(dir: Path): IO[List[StateSnapshot[NodeState]]] =
    ok {
      FileLedgerSnapshots.open[IO, NodeState](dir, keep = 100).flatMap { store =>
        store.list.flatMap(
          _.toList.traverse(n =>
            store
              .restore(a => IO.pure(a.exists(x => FileLedgerSnapshots.nameOf(x) == n)))
              .map(_.snapshot.map(_._2).getOrElse(fail(s"$n does not read")))
          )
        )
      }
    }

  private val someSnapshots = Vector(snapshots.head, snapshots(snapshots.size / 2), snapshots.last)

  private def migration(using Raise[IO, StorageError]) = SnapshotMigration.toV3[IO]

  test("version 2 snapshots of every family of era are migrated, once, and read back as V3") {
    tempDir.use { dir =>
      for
        _ <- writeV2(dir, someSnapshots.map(_._2))
        first <- ok(migration.run(dir))
        second <- ok(migration.run(dir))
        back <- restoreAll(dir)
      yield
        assertEquals(
          first,
          SnapshotMigration.Report(
            List(Migrated(FileKind.SnapshotFile, FormatVersion.V2, FormatVersion.V3, 3)),
            Nil
          )
        )
        assertEquals(second, SnapshotMigration.Report.empty)
        assertEquals(back.toSet, someSnapshots.map(s => normalised(s._2)).toSet)
    }
  }

  test("version 1 snapshots (the former package names) are migrated through version 2") {
    tempDir.use { dir =>
      for
        names <- writeV2(dir, Vector(snapshots.last._2))
        file = (dir / names.head).toNioPath
        // as the build before the rename wrote it: number 1, another fingerprint, the same payload
        content = FileLedgerSnapshots
          .verified(JFiles.readAllBytes(file))
          .fold(e => fail(e.message), identity)
        old = content.clone()
        _ = java.nio.ByteBuffer.wrap(old, 8, 4).putInt(1)
        _ = java.util.Arrays.fill(old, 12, 20, 0x5a.toByte)
        _ = JFiles.write(file, FileLedgerSnapshots.closed(old))
        report <- ok(migration.run(dir))
        back <- restoreAll(dir)
      yield
        assertEquals(
          report.migrated,
          List(Migrated(FileKind.SnapshotFile, FormatVersion.V1, FormatVersion.V3, 1))
        )
        assertEquals(back, List(normalised(snapshots.last._2)))
    }
  }

  test("a snapshot that does not read is reported and left as it was; the others migrate") {
    tempDir.use { dir =>
      for
        names <- writeV2(dir, someSnapshots.map(_._2))
        bad = (dir / names.head).toNioPath
        _ = JFiles.write(bad, JFiles.readAllBytes(bad).updated(40, 0x77.toByte))
        before = JFiles.readAllBytes(bad).toList
        report <- ok(migration.run(dir))
      yield
        assertEquals(report.migrated.map(_.count), List(2))
        assertEquals(report.refused.map(_.name), List(names.head))
        assertEquals(JFiles.readAllBytes(bad).toList, before)
    }
  }

  test(
    "a snapshot of a format this build does not know refuses the run, and nothing is rewritten"
  ) {
    tempDir.use { dir =>
      for
        names <- writeV2(dir, Vector(snapshots.last._2))
        future = dir / "snapshot-99999999999999999999-9.bin"
        _ <- IO(JFiles.write(future.toNioPath, FileLedgerSnapshots.closed("SCRBSNP4....".getBytes)))
        before = JFiles.readAllBytes((dir / names.head).toNioPath).toList
        result <- attempt(migration.run(dir))
      yield
        assert(
          result.left.exists(_.isInstanceOf[StorageError.UnknownVersion]),
          result.toString
        )
        assertEquals(JFiles.readAllBytes((dir / names.head).toNioPath).toList, before)
    }
  }

  test("a database without snapshots migrates nothing") {
    tempDir.use { dir =>
      ok(migration.run(dir / "snapshots")).map(r =>
        assertEquals(r, SnapshotMigration.Report.empty)
      ) *>
        Files.forAsync[IO].exists(dir / "snapshots").map(e => assert(!e))
    }
  }
