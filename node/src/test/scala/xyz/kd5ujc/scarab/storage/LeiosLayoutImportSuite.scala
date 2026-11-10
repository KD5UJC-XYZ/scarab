// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState, MirrorExtras}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

import DecodeError.{Arity, Kind}

/** A reference snapshot of the Leios build (#826 step b), the last Conway state of a Leios devnet
  * (`fixtures/leios/state/conway-195`): imported under `--reference-build leios-b004fe0c` with its
  * bytes recorded as they are, exported with the same bytes, and refused under `11.1`, whose layout
  * does not read it.
  */
class LeiosLayoutImportSuite extends CatsEffectSuite:
  import StorageTestKit.{ok, tempDir}
  private val fixture = JPath.of("fixtures/leios/state")
  private val setup =
    NodeSetup.fromDirectory(fixture.resolve("genesis")).fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  // the devnet starts in Conway: the parameters are its genesis state's, as the import command
  // takes them (`LedgerSnapshotImportCommand.inputs`)
  private val genesis = setup.mirrorStart.ext.ledgerState.state match
    case i: EraLedgerState.InShelleyEra[e] =>
      given ShelleyBasedEra[e] = i.era
      EraLedger.curPParams(i.state.nes.es.ls.utxo.gov)
    case _ => fail("expected a Shelley-based genesis state")
  private val boot = setup.mirrorStart.extras match
    case MirrorExtras.InByron(_, b, _) => b
    case MirrorExtras.InShelley(_, c)  => c.boot
  private val leios = ReferenceLayout.of(ReferenceBuild.LeiosB004fe0c)

  private def read(name: String): Bytes =
    Bytes.wrap(JFiles.readAllBytes(fixture.resolve(s"conway-195/$name")))

  /** The block the state is the state after: slot 195 of the devnet's immutable chunks. */
  private val rawBlock = read("block")
  private val anchor =
    val h = Block
      .decode(rawBlock, setup.geometry.byron.epochSlots)
      .fold(e => fail(e.message), identity)
      .header
    BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)

  /** The reference's `ledger/195` directory, and a database that holds the network's identity and
    * the block of the snapshot's point.
    */
  private def source(root: Path): IO[(Path, Path)] =
    val ledger = root / "source" / "ledger" / "195"
    val db = root / "db"
    IO.blocking {
      JFiles.createDirectories(ledger.toNioPath)
      Vector("state", "tables", "meta").foreach(name =>
        JFiles.write((ledger / name).toNioPath, read(name).toArray)
      )
    } *> ok {
      NetworkIdentityFile.write[IO](db, setup.identity) *>
        FileImmutableStore
          .open[IO](db / "immutable")
          .use((store, _) => store.append(anchor, rawBlock))
    }.as((ledger, db))

  private def config(ledger: Path, db: Path, layout: ReferenceLayout) =
    LedgerSnapshotImport.Config(
      ledger,
      db,
      setup.identity,
      setup.geometry.byron.epochSlots,
      genesis,
      boot,
      LedgerSnapshotImport.Trust.Unverified,
      layout
    )

  private def imported(
      config: LedgerSnapshotImport.Config
  ): IO[Either[LedgerSnapshotImport.Error, LedgerSnapshotImport.Report]] =
    Handle
      .allow[LedgerSnapshotImport.Error](
        LedgerSnapshotImport.run[IO](config).map(_.asRight[LedgerSnapshotImport.Error])
      )
      .rescue(e => IO.pure(Left(e)))

  private def exported(
      config: LedgerSnapshotExport.Config
  ): IO[Either[LedgerSnapshotExport.Error, LedgerSnapshotExport.Report]] =
    Handle
      .allow[LedgerSnapshotExport.Error](
        LedgerSnapshotExport.run[IO](config).map(_.asRight[LedgerSnapshotExport.Error])
      )
      .rescue(e => IO.pure(Left(e)))

  test("slot 195 imports under leios-b004fe0c and exports its state byte for byte") {
    tempDir.use { root =>
      for
        input <- source(root)
        (ledger, db) = input
        report <- imported(config(ledger, db, leios))
        _ <- IO {
          val r = report.fold(e => fail(e.message), identity)
          assertEquals(r.build, ReferenceBuild.LeiosB004fe0c)
          assertEquals(r.anchor, anchor)
          assertEquals(r.era, Era.Conway)
          val content = JFiles
            .readAllBytes((db / "snapshots" / FileLedgerSnapshots.nameOf(anchor)).toNioPath)
            .dropRight(32)
          assertEquals(SnapshotV10.recordedOf(content), Right("leios-b004fe0c"))
          assert(content.takeRight(read("state").size).sameElements(read("state").toArray))
        }
        out <- exported(LedgerSnapshotExport.Config(db, root / "out", layout = leios))
        _ <- IO {
          val r = out.fold(e => fail(e.message), identity)
          assertEquals(r.build, ReferenceBuild.LeiosB004fe0c)
          assertEquals(r.anchor, anchor)
          Vector("state", "tables", "meta").foreach(name =>
            assertEquals(
              Bytes.wrap(JFiles.readAllBytes((r.directory / name).toNioPath)),
              read(name),
              name
            )
          )
        }
        // the export of another layout is refused: there is no conversion between layouts
        other <- exported(LedgerSnapshotExport.Config(db, root / "other"))
        _ <- IO(
          assertEquals(
            other.leftMap(_.toString),
            Left(
              LedgerSnapshotExport.Error
                .Storage(
                  xyz.kd5ujc.scarab.ledger.StorageError.ReferenceBuildMismatch(
                    "snapshot",
                    ReferenceBuild.LeiosB004fe0c,
                    ReferenceBuild.Release11_1
                  )
                )
                .toString
            )
          )
        )
      yield ()
    }
  }

  test("slot 195 is refused under 11.1 with the typed error, and nothing is published") {
    tempDir.use { root =>
      for
        input <- source(root)
        (ledger, db) = input
        report <- imported(config(ledger, db, ReferenceLayout.Release11_1))
        _ <- IO {
          report match
            case Left(LedgerSnapshotImport.Error.State(build, e)) =>
              assertEquals(build, ReferenceBuild.Release11_1)
              // `decodeListLenOf 2` of the 11.1 `ExtLedgerState` (82ecba32 `Extended.hs:268-280`)
              assertEquals(e.kind, Kind.BadArity(Arity.Exactly(2), 3))
            case other => fail(s"not refused as a state of another layout: $other")
          assert(!JFiles.exists((db / "snapshots").toNioPath))
        }
      yield ()
    }
  }
