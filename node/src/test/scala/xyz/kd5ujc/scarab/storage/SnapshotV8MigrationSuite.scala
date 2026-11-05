// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatLoss, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.ledger.byron.{AppVersion, SoftwareProposal, UpdateState}
import xyz.kd5ujc.scarab.node.NodeSetup

import StorageTestKit.*

/** V8 (and V9 after it) keeps the installer metadata of the Byron update state's software proposals
  * and application versions, which V5 and V7 discarded (#726). A V5 or V7 snapshot whose Byron
  * update state holds none migrates as it is; one that holds any is refused with
  * [[StorageError.ResyncRequired]] at every entry point, before any file is rewritten, and nothing
  * is invented for it (ADR 0006, amendment of 2026-11-03).
  */
class SnapshotV8MigrationSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), _.mirrorEnv())

  private val byron = "eras/byron-25.bin"

  private def content(dir: String, name: String): Array[Byte] =
    FileLedgerSnapshots
      .verified(JFiles.readAllBytes(JPath.of(dir).resolve(name)))
      .fold(e => fail(e.message), identity)

  /** The binding, sidecar and Haskell bytes of the V7 Byron fixture. */
  private lazy val v7 = SnapshotV7
    .sections(content("fixtures/snapshot-v7", byron))
    .fold(e => fail(e.message), identity)

  private def hash(b: Int): Bytes = Bytes(Vector.fill(32)(b.toByte))

  private val proposal =
    SoftwareProposal("cardano-sl", 1, Map("linux" -> hash(1), "win64" -> hash(2)))
  private val application = AppVersion(1, 21, Map("macos64" -> hash(3)))

  private def withUpdate(sidecar: SnapshotSidecar)(f: UpdateState => UpdateState): SnapshotSidecar =
    sidecar.chain match
      case SnapshotSidecar.Chain.InByron(pending, extras) =>
        val ledger = extras.byron.ledger.getOrElse(fail("the Byron fixture has no ledger"))
        val byronState = extras.byron.copy(ledger = Some(ledger.copy(update = f(ledger.update))))
        sidecar.copy(chain =
          SnapshotSidecar.Chain.InByron(pending, extras.copy(byron = byronState))
        )
      case _ => fail("the fixture is not a Byron snapshot")

  private def updateOf(sidecar: SnapshotSidecar): UpdateState = sidecar.chain match
    case SnapshotSidecar.Chain.InByron(_, extras) =>
      extras.byron.ledger.map(_.update).getOrElse(fail("no Byron ledger"))
    case _ => fail("not a Byron snapshot")

  /** The losses: a software proposal, an application version, both. */
  private val losses: Vector[(String, UpdateState => UpdateState, FormatLoss)] = Vector(
    (
      "a software proposal",
      u => u.copy(registeredSoftware = Map(hash(9) -> proposal)),
      FormatLoss.ByronSoftwareMetadata(1, 0)
    ),
    (
      "an application version",
      u => u.copy(appVersions = Map("cardano-sl" -> application)),
      FormatLoss.ByronSoftwareMetadata(0, 1)
    ),
    (
      "both",
      u =>
        u.copy(
          registeredSoftware = Map(hash(9) -> proposal, hash(10) -> proposal.copy(name = "x")),
          appVersions = Map("cardano-sl" -> application)
        ),
      FormatLoss.ByronSoftwareMetadata(2, 1)
    )
  )

  /** A previous-version file of the Byron fixture with its update state changed by `f`, as the
    * writer of `version` wrote it (its metadata discarded).
    */
  private def previous(version: FormatVersion, f: UpdateState => UpdateState): Array[Byte] =
    val (binding, sidecar, haskell) = v7
    val changed = withUpdate(sidecar)(f)
    FileLedgerSnapshots.closed(
      if version == FormatVersion.V5 then SnapshotV5.framed(changed, haskell)
      else SnapshotV7.framed(binding, changed, haskell)
    )

  private def write(dir: Path, name: String, bytes: Array[Byte]): IO[Path] =
    IO.blocking(JFiles.write((dir / name).toNioPath, bytes)).as(dir / name)

  private def bytesOf(file: Path): IO[Vector[Byte]] =
    IO.blocking(JFiles.readAllBytes(file.toNioPath).toVector)

  test(
    "the frozen V7 writer reproduces the fixture's bytes: it is the schema V7 was written with"
  ) {
    val (binding, sidecar, haskell) = v7
    assert(
      java.util.Arrays.equals(
        SnapshotV7.framed(binding, sidecar, haskell),
        content("fixtures/snapshot-v7", byron)
      )
    )
  }

  test("a V7 Byron snapshot without software proposals or application versions migrates as it is") {
    tempDir.use { dir =>
      for
        file <- write(
          dir,
          "snapshot-00000000000000000025-25.bin",
          previous(FormatVersion.V7, identity)
        )
        report <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        after <- bytesOf(file)
      yield
        assertEquals(
          report,
          SnapshotMigration.Report(
            List(Migrated(FileKind.SnapshotFile, FormatVersion.V7, FormatVersion.V9, 1)),
            Nil
          )
        )
        assertEquals(
          after,
          JFiles.readAllBytes(JPath.of("fixtures/snapshot-v9").resolve(byron)).toVector
        )
        val sidecar = FileLedgerSnapshots
          .verified(after.toArray)
          .flatMap(SnapshotV9.sidecarOf)
          .fold(e => fail(e.message), identity)
        assertEquals(sidecar, v7._2)
    }
  }

  for
    version <- Vector(FormatVersion.V5, FormatVersion.V7)
    (what, change, loss) <- losses
  do
    test(s"a $version Byron snapshot holding $what is refused at every entry point, untouched") {
      tempDir.use { dir =>
        val migration = SnapshotMigration.toCurrent[IO]
        for
          refused <- write(dir, "snapshot-00000000000000000025-25.bin", previous(version, change))
          // a convertible file beside it is not rewritten either: the refusal comes first
          other <- write(
            dir,
            "snapshot-00000000000000000049-49.bin",
            previous(FormatVersion.V7, identity)
          )
          before <- (bytesOf(refused), bytesOf(other)).tupled
          preflight <- attempt(migration.preflight(dir))
          run <- attempt(migration.run(dir))
          next = FileKind.SnapshotFile.nextChangedVersion(version).getOrElse(fail("no next"))
          step <- attempt(migration.step(refused, version, next))
          detect <- attempt(migration.detect(refused))
          // the generic path, the convertible file first: every file is detected before any is
          // converted
          generic <- attempt(Migrate(migration, List(other, refused)))
          after <- (bytesOf(refused), bytesOf(other)).tupled
        yield
          val error =
            StorageError.ResyncRequired(refused.toString, FileKind.SnapshotFile, version, loss)
          assertEquals(preflight, Left(error))
          assertEquals(run, Left(error))
          assertEquals(step, Left(error))
          assertEquals(detect, Left(error))
          assertEquals(generic, Left(error))
          assertEquals(after, before)
      }
    }

  test("the readers of V5 and V7 sidecars refuse a lost metadata and invent none") {
    for (_, change, loss) <- losses do
      val v7Content = FileLedgerSnapshots
        .verified(previous(FormatVersion.V7, change))
        .fold(e => fail(e.message), identity)
      val v5Content = FileLedgerSnapshots
        .verified(previous(FormatVersion.V5, change))
        .fold(e => fail(e.message), identity)
      val refusal = (v: FormatVersion) =>
        Left(StorageError.ResyncRequired("snapshot", FileKind.SnapshotFile, v, loss))
      assertEquals(SnapshotV7.sidecarOf(v7Content), refusal(FormatVersion.V7))
      assertEquals(SnapshotV8.fromV7(v7Content), refusal(FormatVersion.V7))
      assertEquals(SnapshotV5.sidecarOf(v5Content), refusal(FormatVersion.V5))
      assertEquals(SnapshotV7.fromV5(v5Content), refusal(FormatVersion.V5))
  }

  test("V8 and V9 keep the installer metadata of software proposals and application versions") {
    val (binding, sidecar, haskell) = v7
    val update = (u: UpdateState) =>
      u.copy(
        registeredSoftware = Map(hash(9) -> proposal),
        appVersions = Map("cardano-sl" -> application)
      )
    val changed = withUpdate(sidecar)(update)
    val v8 = SnapshotV8.framed(binding, changed, haskell)
    assertEquals(SnapshotV8.sidecarOf(v8).map(updateOf), Right(updateOf(changed)))
    val written = SnapshotV9.fromV8(v8).fold(e => fail(e.message), identity)
    assertEquals(SnapshotV9.sidecarOf(written).map(updateOf), Right(updateOf(changed)))
    val state = SnapshotV9.decode(env, written).fold(e => fail(e.message), identity).state
    state.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(b) =>
        assertEquals(b.ledger.map(_.update), Some(updateOf(changed)))
      case _ => fail("not a Byron state")
  }

object SnapshotV8MigrationSuite extends munit.Assertions:
  /** The V7 Byron fixture with a software proposal registered in its update state, as the V7 writer
    * wrote it: its installer metadata discarded.
    */
  def refusedV7: Array[Byte] =
    val raw = JFiles.readAllBytes(JPath.of("fixtures/snapshot-v7/eras/byron-25.bin"))
    val sections = FileLedgerSnapshots.verified(raw).flatMap(SnapshotV7.sections)
    sections match
      case Right((binding, sidecar, haskell)) =>
        val chain = sidecar.chain match
          case SnapshotSidecar.Chain.InByron(pending, extras) =>
            val byron = extras.byron.copy(ledger = extras.byron.ledger.map { ledger =>
              val software =
                SoftwareProposal("cardano-sl", 1, Map("linux" -> Bytes(Vector.fill(32)(1))))
              ledger.copy(update =
                ledger.update.copy(registeredSoftware = Map(Bytes(Vector.fill(32)(9)) -> software))
              )
            })
            SnapshotSidecar.Chain.InByron(pending, extras.copy(byron = byron))
          case other => other
        FileLedgerSnapshots.closed(SnapshotV7.framed(binding, sidecar.copy(chain = chain), haskell))
      case Left(error) => fail(error.message)
