// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  ExtLedgerState,
  MirrorState
}
import xyz.kd5ujc.scarab.ledger.{
  CheckpointProblem,
  Credential,
  GovAction,
  GovActionId,
  GovActionState,
  Proposal,
  Proposals,
  ProposalsError,
  RewardAccount,
  StorageError,
  UtxoKeyLayout
}
import xyz.kd5ujc.scarab.ledger.state.{ConwayGovState, ConwayNewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, ReferenceLayout}

import LedgerSnapshots.{Fallback, Unused}
import StorageTestKit.*

/** The restore's fallback mirrors the reference's `tryNewestFirst` (ouroboros-consensus 82ecba32
  * `Storage/LedgerDB/API.hs:599-646`): a snapshot it does not use is reported and the next older
  * one tried, down to genesis; one too recent, or failing for any reason but the reference's three
  * kept ones, is deleted, every native snapshot being temporary (`Snapshots.hs:288-297`).
  */
class SnapshotFallbackSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/eras/genesis"))
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

  /** The Conway proposals of a restored or decoded state. */
  private def proposalsOf(ext: ExtLedgerState): Proposals =
    ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(era, ls) =>
        era match
          case ShelleyBasedEra.SConway => ConwayNewEpochState.gov.get(ls.nes).proposals
          case _                       => fail("not a Conway fixture")
      case _ => fail("not a Shelley-based fixture")

  test("a snapshot holding proposals mkProposals refuses is restored as written, not deleted") {
    // ADR 0006: a native snapshot is read under the rules it was written under. The reference's
    // `state` reader rebuilds the proposals with `mkProposals` and keeps the first of a repeated id
    // (cardano-ledger f649f975, `Conway/Governance/Proposals.hs:338-354,401-406`); a snapshot
    // written before Scarab's reader of that file did so may hold a proposal under an action that is
    // not stored, and an id twice, and the restore reads it as written
    val (_, binding, sidecar, state) = sections
    val layout = ReferenceLayout.Release11_1
    val file = layout.decodeNative(state).fold(e => fail(e.render), identity)
    val ext = file.ext.getOrElse(fail("a Byron fixture"))
    val orphan = GovActionState(
      GovActionId(Bytes.wrap(Array.fill(32)(3.toByte)), 0),
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(
        0,
        RewardAccount(0, Credential.Key(Bytes.wrap(Array.fill(28)(9.toByte)))),
        GovAction.NoConfidence(Some(GovActionId(Bytes.wrap(Array.fill(32)(7.toByte)), 0)))
      ),
      1,
      7
    )
    val store = Proposals(
      proposalsOf(ext).actions ++ Vector(orphan, orphan.copy(proposedIn = 2)),
      proposalsOf(ext).roots
    )
    val edited = ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(era, ls) =>
        era match
          case ShelleyBasedEra.SConway =>
            val nes =
              ConwayNewEpochState.gov.andThen(ConwayGovState.proposals).replace(store)(ls.nes)
            ext.copy(ledgerState =
              ext.ledgerState.withState(EraLedgerState.InShelleyEra(era, ls.copy(nes = nes)))
            )
          case _ => fail("not a Conway fixture")
      case _ => fail("not a Shelley-based fixture")
    val haskell = HaskellLedgerFile
      .of(edited)
      .leftMap(_.toString)
      .flatMap(layout.encode(_).leftMap(_.toString))
      .fold(fail(_), identity)
    assertEquals(
      layout.decode(haskell).left.map(_.deepest.kind),
      Left(DecodeError.Kind.Domain(ProposalsError.Unattached(orphan.id)))
    )
    tempDir.use { dir =>
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- write(
          dir,
          newer,
          FileLedgerSnapshots.closed(
            SnapshotV10.framed(ReferenceBuild.Release11_1, binding, sidecar, haskell)
          )
        )
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assertEquals(restored.skipped, Vector.empty)
        assertEquals(restored.snapshot.map(_._1), Some(newer))
        assertEquals(restored.snapshot.map(s => proposalsOf(s._2.state.ext)), Some(store))
        assert(present, s"$newer on disk")
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

  test(
    "a snapshot whose checkpoint is not the recorded one, or whose keys do not decode, is deleted"
  ) {
    Vector(
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

  /** The older snapshot, then the newer one as `file` (none: a directory, which cannot be read);
    * the restore stops with what `stopped` accepts, and both files are still there.
    */
  private def stops(
      file: Option[Array[Byte]],
      stopped: StorageError => Boolean,
      refuse: StateSnapshot[MirrorState] => Option[StorageError] = _ => None
  ): IO[Unit] =
    tempDir.use { dir =>
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- file.fold(IO.blocking(JFiles.createDirectory((dir / newer).toNioPath)).void)(
          write(dir, newer, _)
        )
        result <- restore(dir, refuse)
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
        olderPresent <- IO.blocking(JFiles.exists((dir / older).toNioPath))
      yield
        assert(result.left.exists(stopped), result.toString)
        assert(present, s"$newer was deleted")
        assert(olderPresent, s"$older was deleted")
    }

  test("a file that cannot be read stops the start, as the reference raises it; nothing deleted") {
    stops(None, _.isInstanceOf[StorageError.Io])
  }

  test("an absent checkpoint is the reference's missing tables, which raise: the start stops") {
    // readIncremental opens the tables outside its ExceptT (82ecba32 Util/CBOR.hs:206)
    val absent = StorageError.UtxoCheckpoint(newer, CheckpointProblem.Absent)
    stops(
      Some(FileLedgerSnapshots.closed(newerContent)),
      _ == absent,
      s => Option.when(newerOnly(s))(absent)
    )
  }

  test("a deletion that fails does not stop the fallback (Snapshots.hs:320-330): reported, kept") {
    tempDir.use { dir =>
      val closed = FileLedgerSnapshots.closed(newerContent)
      val damaged = closed.updated(closed.length - 1, (closed.last ^ 1).toByte)
      val readOnly = java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")
      val writable = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(olderContent))
        _ <- write(dir, newer, damaged)
        _ <- IO.blocking(JFiles.setPosixFilePermissions(dir.toNioPath, readOnly))
        restored <- restore(dir).guarantee(
          IO.blocking(JFiles.setPosixFilePermissions(dir.toNioPath, writable)).void
        )
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        val result = restored.fold(e => fail(e.message), identity)
        assertEquals(result.snapshot.map(_._1), Some(older))
        assertEquals(result.skipped.map(s => (s.name, s.error)), Vector(newer -> corrupt))
        assert(
          result.skipped.forall(_.unused match
            case Unused.NotDeleted(_: StorageError.Io) => true
            case _                                     => false),
          result.skipped.toString
        )
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
      StorageError.UtxoCheckpoint("c", CheckpointProblem.DigestMismatch(Bytes.empty, Bytes.empty)),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.MixedKeyLayouts),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.IndexTooLarge(70000L)),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.UnknownKeyLength(5))
    )
    val kept = Vector(
      StorageError.VersionMismatch("snapshot sidecar schema", "00", "01"),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.Unbound),
      StorageError.UtxoCheckpoint(
        "c",
        CheckpointProblem.LayoutMismatch(UtxoKeyLayout.TxIdIndex16, UtxoKeyLayout.TxIdIndex16)
      )
    )
    val stopped = Vector(
      StorageError.Io("snapshot", new java.io.IOException("unreadable")),
      StorageError.UtxoCheckpoint("c", CheckpointProblem.Absent),
      StorageError.DecoderDefect("snapshot", new RuntimeException("defect")),
      StorageError.UnknownVersion("snapshot", xyz.kd5ujc.scarab.ledger.FileKind.SnapshotFile, "x")
    )
    deleted.foreach(e => assertEquals(Fallback.of(e), Fallback.Delete, e.message))
    kept.foreach(e => assertEquals(Fallback.of(e), Fallback.Keep, e.message))
    stopped.foreach(e => assertEquals(Fallback.of(e), Fallback.Stop, e.message))
  }

  // ---- the migration of previous files goes through the same fallback

  private def v9(file: String): Array[Byte] =
    FileLedgerSnapshots
      .verified(JFiles.readAllBytes(JPath.of("fixtures/snapshot-v9").resolve(file)))
      .fold(e => fail(e.message), identity)

  test("a damaged V9 newest is deleted by its migration; the good V9 older migrates and restores") {
    tempDir.use { dir =>
      val closed = FileLedgerSnapshots.closed(v9("eras/conway-730.bin"))
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(v9("eras/conway-729.bin")))
        _ <- write(dir, newer, closed.updated(closed.length - 1, (closed.last ^ 1).toByte))
        report <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
        present <- IO.blocking(JFiles.exists((dir / newer).toNioPath))
      yield
        assertEquals(
          report.migrated,
          List(
            Migrated(
              xyz.kd5ujc.scarab.ledger.FileKind.SnapshotFile,
              xyz.kd5ujc.scarab.ledger.FormatVersion.V9,
              xyz.kd5ujc.scarab.ledger.FormatVersion.V10,
              1
            )
          )
        )
        assertEquals(
          report.skipped,
          List(LedgerSnapshots.Skipped(newer, corrupt, Unused.Deleted))
        )
        assert(!present)
        assertEquals(restored.snapshot.map(_._1), Some(older))
    }
  }

  test("a V9 file of another sidecar schema is kept unconverted, and the restore skips it") {
    tempDir.use { dir =>
      val source = v9("eras/conway-730.bin")
      val body = 12 + java.nio.ByteBuffer.wrap(source, 8, 4).getInt
      val otherSchema = source.updated(body + 4, (source(body + 4) ^ 1).toByte)
      for
        _ <- write(dir, older, FileLedgerSnapshots.closed(v9("eras/conway-729.bin")))
        _ <- write(dir, newer, FileLedgerSnapshots.closed(otherSchema))
        report <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        after <- IO.blocking(JFiles.readAllBytes((dir / newer).toNioPath))
        restored <- restore(dir).map(_.fold(e => fail(e.message), identity))
      yield
        assertEquals(report.skipped.map(s => (s.name, s.unused)), List(newer -> Unused.Kept))
        assert(report.skipped.forall(_.error.isInstanceOf[StorageError.VersionMismatch]))
        assertEquals(after.toVector, FileLedgerSnapshots.closed(otherSchema).toVector)
        assertEquals(restored.snapshot.map(_._1), Some(older))
        assertEquals(restored.skipped.map(s => (s.name, s.unused)), Vector(newer -> Unused.Kept))
    }
  }

  test("V9 to V10 copies a binding the writer would encode otherwise, byte for byte") {
    // the Legacy binding's variant index as the two-byte LEB128 80 00, which the reader takes and
    // the writer would write as 00
    val source = v9("eras/conway-730.bin")
    val length = java.nio.ByteBuffer.wrap(source, 8, 4).getInt
    val binding = source.slice(12, 12 + length)
    assertEquals(binding.drop(8).toVector, Vector(0.toByte), "a Legacy binding")
    val stretched = source.take(8) ++ java.nio.ByteBuffer.allocate(4).putInt(length + 1).array() ++
      binding.take(8) ++ Array(0x80.toByte, 0x00.toByte) ++ source.drop(12 + length)
    assertEquals(SnapshotV9.bindingOf(stretched), Right(CheckpointBinding.Legacy))
    val converted = SnapshotV10.fromV9(stretched).fold(e => fail(e.message), identity)
    assertEquals(
      converted.toVector,
      (SnapshotV10.Magic ++ SnapshotV10.buildBytes(ReferenceBuild.Release11_1) ++
        stretched.drop(SnapshotV9.Magic.length)).toVector
    )
    assertEquals(SnapshotV10.bindingOf(converted), Right(CheckpointBinding.Legacy))
  }
