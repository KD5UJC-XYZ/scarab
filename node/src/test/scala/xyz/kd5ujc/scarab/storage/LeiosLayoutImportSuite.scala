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
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.era.GovStateRecord
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

import DecodeError.{Arity, Kind}

/** Reference snapshots of the Leios build (#826), of a Leios devnet (`fixtures/leios/state`):
  * imported under `--reference-build leios-b004fe0c` with their bytes recorded as they are,
  * exported with the same bytes, and refused under `11.1`, whose layout does not read them. The
  * last Conway state, `conway-195`, with the block of its point; the first Dijkstra state,
  * `dijkstra-203`, whose block was not kept, with its header's tip moved to the point of a Dijkstra
  * block that is (`fixtures/dijkstra-devnet`, another run of the same build).
  */
class LeiosLayoutImportSuite extends CatsEffectSuite:
  import StorageTestKit.{ok, tempDir}
  private val fixture = JPath.of("fixtures/leios/state")
  private val setup = LeiosStateFixture.setup
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  // the devnet starts in Conway: the parameters are its genesis state's, as the import command
  // takes them (`LedgerSnapshotImportCommand.inputs`)
  private val genesis = setup.mirrorStart.ext.ledgerState.state match
    case i: EraLedgerState.InShelleyEra[e] =>
      given ShelleyBasedEra[e] = i.era
      EraLedger.curPParams(i.state.nes.es.ls.utxo.gov)
    case _ => fail("expected a Shelley-based genesis state")
  private val leios = LeiosStateFixture.layout

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

  /** The reference's `ledger/<slot>` directory of `files`, and a database that holds the network's
    * identity and `block` at `point`.
    */
  private def sourceOf(
      root: Path,
      slot: BigInt,
      files: Vector[(String, Bytes)],
      point: BlockRef,
      block: Bytes
  ): IO[(Path, Path)] =
    val ledger = root / "source" / "ledger" / slot.toString
    val db = root / "db"
    IO.blocking {
      JFiles.createDirectories(ledger.toNioPath)
      files.foreach((name, bytes) => JFiles.write((ledger / name).toNioPath, bytes.toArray))
    } *> ok {
      NetworkIdentityFile.write[IO](db, setup.identity) *>
        FileImmutableStore
          .open[IO](db / "immutable")
          .use((store, _) => store.append(point, block))
    }.as((ledger, db))

  /** The reference's `ledger/195` directory, and a database that holds the network's identity and
    * the block of the snapshot's point.
    */
  private def source(root: Path): IO[(Path, Path)] =
    sourceOf(
      root,
      195,
      Vector("state", "tables", "meta").map(name => name -> read(name)),
      anchor,
      rawBlock
    )

  private def readDijkstra(name: String): Bytes = LeiosStateFixture.read("dijkstra-203", name)

  private val dijkstraBlock = LeiosStateFixture.first.raw
  private val dijkstraPoint = LeiosStateFixture.first.point
  private val movedState = LeiosStateFixture.movedState

  private def config(ledger: Path, db: Path, layout: ReferenceLayout) =
    LedgerSnapshotImport.Config(
      ledger,
      db,
      setup.identity,
      setup.geometry.byron.epochSlots,
      genesis,
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

  test(
    "slot 203, a Dijkstra state with its tip moved, imports under leios-b004fe0c and exports byte for byte"
  ) {
    val files = LeiosStateFixture.movedFiles
    tempDir.use { root =>
      for
        input <- sourceOf(root, dijkstraPoint.slot, files, dijkstraPoint, dijkstraBlock)
        (ledger, db) = input
        report <- imported(config(ledger, db, leios))
        _ <- IO {
          val r = report.fold(e => fail(e.message), identity)
          assertEquals(r.build, ReferenceBuild.LeiosB004fe0c)
          assertEquals(r.anchor, dijkstraPoint)
          assertEquals(r.era, Era.Dijkstra)
          val content = JFiles
            .readAllBytes((db / "snapshots" / FileLedgerSnapshots.nameOf(dijkstraPoint)).toNioPath)
            .dropRight(32)
          assertEquals(SnapshotV10.recordedOf(content), Right("leios-b004fe0c"))
          assert(content.takeRight(movedState.size).sameElements(movedState.toArray))
        }
        out <- exported(LedgerSnapshotExport.Config(db, root / "out", layout = leios))
        _ <- IO {
          val r = out.fold(e => fail(e.message), identity)
          assertEquals(r.build, ReferenceBuild.LeiosB004fe0c)
          assertEquals(r.era, Era.Dijkstra)
          files.foreach((name, bytes) =>
            assertEquals(
              Bytes.wrap(JFiles.readAllBytes((r.directory / name).toNioPath)),
              bytes,
              name
            )
          )
        }
      yield ()
    }
  }

  // the import writes the sidecar's parameter records whole, the fields of the eras before Dijkstra
  // from the genesis (`Params.completeIn`; the devnet starts in Conway with d = 1): a restore keeps
  // them, so the snapshot written again at the same anchor is the imported one, byte for byte, its
  // Dijkstra parameters still the governance state's (#826)
  test("slot 203 imported, restored and written again at its anchor is the same V10 file") {
    val files = LeiosStateFixture.movedFiles
    tempDir.use { root =>
      for
        input <- sourceOf(root, dijkstraPoint.slot, files, dijkstraPoint, dijkstraBlock)
        (ledger, db) = input
        report <- imported(config(ledger, db, leios))
        _ <- IO(report.fold(e => fail(e.message), identity))
        _ <- IO {
          val content = JFiles
            .readAllBytes((db / "snapshots" / FileLedgerSnapshots.nameOf(dijkstraPoint)).toNioPath)
            .dropRight(32)
          val sidecar = SnapshotV10.sidecarOf(content).fold(e => fail(e.message), identity)
          val params = sidecar.chain match
            case c: SnapshotSidecar.Chain.InShelley => c.params
            case other                              => fail(s"not a Shelley-based sidecar: $other")
          assertEquals(
            params.d,
            xyz.kd5ujc.scarab.codec.Rational.one,
            "the genesis's decentralisation"
          )
          val restored = SnapshotV10
            .decode(setup.mirrorEnv(), leios, content)
            .fold(e => fail(e.message), identity)
          assertEquals(restored.anchor, Some(dijkstraPoint))
          val again = SnapshotV10.encoded(restored, leios).fold(e => fail(e.toString), identity)
          assertEquals(Bytes.wrap(again).hex, Bytes.wrap(content).hex)
          // the governance state is authoritative for Dijkstra's fields: a change of its maximum
          // transaction size is the sidecar's, the genesis's decentralisation still kept
          val m = restored.state
          val changed = restored.copy(state = m.ext.ledgerState.state match
            case i: EraLedgerState.InShelleyEra[e] =>
              given ShelleyBasedEra[e] = i.era
              val nes = i.state.nes
              val ls = nes.es.ls
              val gov = i.era.gov.update(ls.utxo.gov)(
                _ => fail("not Dijkstra's record"),
                _ => fail("not Dijkstra's record"),
                g =>
                  g.copy(cgsCurPParams =
                    GovStateRecord.dijkstra
                      .pparams(GovStateRecord.view(g.cgsCurPParams).copy(maxTxSize = 12345))
                      .fold(e => fail(e.toString), identity)
                  )
              )
              val next =
                nes.copy[e](es = nes.es.copy[e](ls = ls.copy[e](utxo = ls.utxo.copy[e](gov = gov))))
              m.copy(ext =
                m.ext.copy(ledgerState =
                  m.ext.ledgerState.withState(EraLedgerState.inEra[e](i.state.copy[e](nes = next)))
                )
              )
            case other => fail(s"not a Shelley-based state: $other"))
          val written = SnapshotV10.encoded(changed, leios).fold(e => fail(e.toString), identity)
          val writtenParams =
            SnapshotV10.sidecarOf(written).fold(e => fail(e.message), identity).chain match
              case c: SnapshotSidecar.Chain.InShelley => c.params
              case other => fail(s"not a Shelley-based sidecar: $other")
          assertEquals(writtenParams.maxTxSize, BigInt(12345))
          assertEquals(writtenParams.d, xyz.kd5ujc.scarab.codec.Rational.one)
          assertEquals(writtenParams.copy(maxTxSize = params.maxTxSize), params)
        }
      yield ()
    }
  }

  test("slot 203 is refused under 11.1 with the typed error, and nothing is published") {
    val files = Vector("state", "tables", "meta").map(name => name -> readDijkstra(name))
    tempDir.use { root =>
      for
        input <- sourceOf(root, 203, files, dijkstraPoint, dijkstraBlock)
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
