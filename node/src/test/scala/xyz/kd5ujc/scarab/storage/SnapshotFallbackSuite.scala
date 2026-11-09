// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{CheckpointProblem, StorageError, UtxoKeyLayout}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

import LedgerSnapshots.Unused
import StorageTestKit.*

/** The restore's fallback mirrors the reference's `tryNewestFirst` (ouroboros-consensus 82ecba32
  * `Storage/LedgerDB/API.hs:599-646`): a snapshot it does not use is reported and the next older
  * one tried, down to genesis; one too recent, or failing for any reason but the reference's three
  * kept ones, is deleted, every native snapshot being temporary (`Snapshots.hs:288-297`).
  */
class SnapshotFallbackSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), _.mirrorEnv())
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
  private given SnapshotFormat[IO, MirrorState] =
    SnapshotV10.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)

  private def content(file: String): Array[Byte] =
    FileLedgerSnapshots
      .verified(JFiles.readAllBytes(JPath.of("fixtures/snapshot-v10").resolve(file)))
      .fold(e => fail(e.message), identity)

  private def nameOf(content: Array[Byte]): String =
    SnapshotV10
      .sidecarOf(content)
      .toOption
      .flatMap(_.anchor)
      .map(FileLedgerSnapshots.nameOf)
      .getOrElse(fail("a fixture without an anchor"))

  private val olderContent = content("eras/conway-729.bin")
  private val newerContent = content("eras/conway-730.bin")
  private val older = nameOf(olderContent)
  private val newer = nameOf(newerContent)

  private def sections =
    SnapshotV10
      .sections(newerContent, ReferenceLayout.Release11_1)
      .fold(e => fail(e.message), identity)

  /** The newer snapshot recorded under `build`, or with Haskell bytes that are not a state. */
  private def framed(build: ReferenceBuild, haskell: Option[Bytes] = None): Array[Byte] =
    val (_, binding, sidecar, state) = sections
    SnapshotV10.framed(build, binding, sidecar, haskell.getOrElse(state))

  private def unknownBuild: Array[Byte] =
    SnapshotV10.Magic ++ Array(4.toByte) ++ "11.9".getBytes("US-ASCII") ++
      newerContent.drop(SnapshotV10.Magic.length + 5)

  /** The newer snapshot with one byte of its sidecar's fingerprint changed. */
  private def otherSidecarSchema: Array[Byte] =
    val at = 9 + (newerContent(8) & 0xff)
    val body = at + 4 + java.nio.ByteBuffer.wrap(newerContent, at, 4).getInt
    newerContent.updated(body + 4, (newerContent(body + 4) ^ 1).toByte)

  private def write(dir: Path, name: String, file: Array[Byte]): IO[Unit] =
    IO.blocking(JFiles.write((dir / name).toNioPath, file)).void

  private def restore(
      dir: Path,
      refuse: StateSnapshot[MirrorState] => Option[StorageError] = _ => None
  ): IO[Either[StorageError, LedgerSnapshots.Restored[MirrorState]]] =
    Handle
      .allow[StorageError](
        FileLedgerSnapshots
          .open[IO, MirrorState](dir, keep = 10)
          .flatMap(_.restore(s => IO.pure(refuse(s).toLeft(()))))
          .map(_.asRight[StorageError])
      )
      .rescue(e => IO.pure(Left(e)))

  private def newerOnly(s: StateSnapshot[MirrorState]): Boolean =
    s.anchor.map(FileLedgerSnapshots.nameOf).contains(newer)

  /** The older snapshot, then the newer one as `file`; the restore takes the older one, and the
    * newer one is skipped with `error` and deleted or kept as `unused` says.
    */
  private def falls(
      file: Array[Byte],
      error: StorageError,
      unused: Unused,
      refuse: StateSnapshot[MirrorState] => Option[StorageError] = _ => None
  ): IO[Unit] =
    tempDir.use { dir =>
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- write(dir, newer, file)
        restored <- restore(dir, refuse).map(_.fold(e => fail(e.message), identity))
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assertEquals(restored.snapshot.map(_._1), Some(older))
        assertEquals(restored.skipped, Vector(LedgerSnapshots.Skipped(newer, error, unused)))
        assertEquals(present, unused == Unused.Kept, s"$newer on disk")
    }

  private val corrupt = StorageError.Corrupt("snapshot", SnapshotReadError.ChecksumMismatch)

  test("a snapshot whose checksum fails is deleted (ReadSnapshotDataCorruption, :628-631)") {
    val closed = FileLedgerSnapshots.closed(newerContent)
    falls(closed.updated(closed.length - 1, (closed.last ^ 1).toByte), corrupt, Unused.Deleted)
  }

  test("a snapshot whose state does not decode is deleted (ReadSnapshotFailed, :628-631)") {
    val file = FileLedgerSnapshots.closed(
      framed(ReferenceBuild.Release11_1, Some(Bytes.wrap(Array(0x83.toByte, 0x01.toByte))))
    )
    tempDir.use { dir =>
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- write(dir, newer, file)
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assertEquals(restored.snapshot.map(_._1), Some(older))
        assertEquals(restored.skipped.map(s => (s.name, s.unused)), Vector(newer -> Unused.Deleted))
        assert(restored.skipped.forall(_.error.isInstanceOf[StorageError.Corrupt[?]]))
        assert(!present)
    }
  }

  test("a snapshot recorded under a build of another layout is deleted (:628-631)") {
    falls(
      FileLedgerSnapshots.closed(framed(ReferenceBuild.LeiosB004fe0c)),
      StorageError.ReferenceBuildMismatch(
        "snapshot",
        ReferenceBuild.LeiosB004fe0c,
        ReferenceBuild.Release11_1
      ),
      Unused.Deleted
    )
  }

  test("a snapshot recorded under an unknown build is deleted (:628-631)") {
    falls(
      FileLedgerSnapshots.closed(unknownBuild),
      StorageError.UnknownReferenceBuild("snapshot", "11.9", ReferenceBuild.Release11_1),
      Unused.Deleted
    )
  }

  test("a snapshot whose anchor is not stored is the too-recent one: deleted (:600-604)") {
    val missing = StorageError.Missing("snapshot anchor in the immutable store")
    falls(
      FileLedgerSnapshots.closed(newerContent),
      missing,
      Unused.Deleted,
      s => Option.when(newerOnly(s))(missing)
    )
  }

  test("a snapshot whose checkpoint is absent or not the recorded one is deleted (:628-631)") {
    Vector(
      CheckpointProblem.Absent,
      CheckpointProblem.DigestMismatch(Bytes.empty, Bytes.wrap(Array(1.toByte))),
      CheckpointProblem.MixedKeyLayouts
    ).traverse_ { problem =>
      val error = StorageError.UtxoCheckpoint(newer, problem)
      falls(
        FileLedgerSnapshots.closed(newerContent),
        error,
        Unused.Deleted,
        s => Option.when(newerOnly(s))(error)
      )
    }
  }

  test("a checkpoint of another layout, or none, is the backend mismatch: kept (:613-617)") {
    Vector(
      CheckpointProblem.LayoutMismatch(UtxoKeyLayout.TxIdIndex16, UtxoKeyLayout.TxIdIndex16),
      CheckpointProblem.Unbound
    ).traverse_ { problem =>
      val error = StorageError.UtxoCheckpoint(newer, problem)
      falls(
        FileLedgerSnapshots.closed(newerContent),
        error,
        Unused.Kept,
        s => Option.when(newerOnly(s))(error)
      )
    }
  }

  test("a sidecar of another schema is Scarab's envelope (ADR 0006): kept") {
    tempDir.use { dir =>
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- write(dir, newer, FileLedgerSnapshots.closed(otherSidecarSchema))
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assertEquals(restored.snapshot.map(_._1), Some(older))
        assertEquals(restored.skipped.map(s => (s.name, s.unused)), Vector(newer -> Unused.Kept))
        assert(restored.skipped.forall(_.error.isInstanceOf[StorageError.VersionMismatch]))
        assert(present)
    }
  }

  test("a file that cannot be read is not a decoding failure: kept") {
    tempDir.use { dir =>
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- IO.blocking(JFiles.createDirectory((dir / newer).toNioPath))
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assertEquals(restored.snapshot.map(_._1), Some(older))
        assertEquals(restored.skipped.map(s => (s.name, s.unused)), Vector(newer -> Unused.Kept))
        assert(restored.skipped.forall(_.error.isInstanceOf[StorageError.Io]))
        assert(present)
    }
  }

  test("an unknown format version is raised (ADR 0006): nothing restored, nothing deleted") {
    tempDir.use { dir =>
      val future = "SCRBSNPB".getBytes("US-ASCII") ++ newerContent.drop(8)
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- write(dir, newer, FileLedgerSnapshots.closed(future))
        result <- restore(dir)
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assert(result.left.exists(_.isInstanceOf[StorageError.UnknownVersion]), result.toString)
        assert(present)
    }
  }

  test("with nothing usable left the restore takes none: the blocks replay from genesis") {
    tempDir.use { dir =>
      for
        _ <- write(dir, newer, FileLedgerSnapshots.closed(framed(ReferenceBuild.LeiosB004fe0c)))
        _ <- write(dir, older, FileLedgerSnapshots.closed(unknownBuild))
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
        left <- IO.blocking(JFiles.list(dir.toNioPath)).map { listing =>
          try listing.toArray.length
          finally listing.close()
        }
      yield
        assertEquals(restored.snapshot, None)
        assertEquals(
          restored.skipped.map(s => (s.name, s.unused)),
          Vector(
            newer -> Unused.Deleted,
            older -> Unused.Deleted
          )
        )
        assertEquals(left, 0)
    }
  }

  test("every refusal maps to the reference's case") {
    val deleted = Vector(
      corrupt,
      StorageError.Missing("snapshot anchor in the immutable store"),
      StorageError.ReferenceBuildMismatch(
        "snapshot",
        ReferenceBuild.LeiosB004fe0c,
        ReferenceBuild.Release11_1
      ),
      StorageError.UnknownReferenceBuild("snapshot", "x", ReferenceBuild.Release11_1),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.Absent),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.DigestMismatch(Bytes.empty, Bytes.empty)),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.MixedKeyLayouts),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.IndexTooLarge(70000L)),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.UnknownKeyLength(5))
    )
    val kept = Vector(
      StorageError.VersionMismatch("snapshot sidecar schema", "00", "01"),
      StorageError.Io("snapshot", new java.io.IOException("unreadable")),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.Unbound),
      StorageError.UtxoCheckpoint(
        "c",
        CheckpointProblem.LayoutMismatch(UtxoKeyLayout.TxIdIndex16, UtxoKeyLayout.TxIdIndex16)
      )
    )
    deleted.foreach(e => assertEquals(Unused.of(e), Unused.Deleted, e.message))
    kept.foreach(e => assertEquals(Unused.of(e), Unused.Kept, e.message))
  }
