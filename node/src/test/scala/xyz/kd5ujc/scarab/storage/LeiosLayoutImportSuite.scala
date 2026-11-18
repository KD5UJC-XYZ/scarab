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
import xyz.kd5ujc.scarab.consensus.state.{AnnTip, ConsensusMirrorEnv, EraLedgerState, TipInfo}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{BlockNo, Hash32, ShelleyBasedEra, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.{ReferenceLayout, SnapshotCrc, SnapshotMeta}

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
  private val setup =
    NodeSetup
      .fromDirectory(ReferenceBuild.LeiosB004fe0c)(fixture.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  // the devnet starts in Conway: the parameters are its genesis state's, as the import command
  // takes them (`LedgerSnapshotImportCommand.inputs`)
  private val genesis = setup.mirrorStart.ext.ledgerState.state match
    case i: EraLedgerState.InShelleyEra[e] =>
      given ShelleyBasedEra[e] = i.era
      EraLedger.curPParams(i.state.nes.es.ls.utxo.gov)
    case _ => fail("expected a Shelley-based genesis state")
  private val leios =
    ReferenceLayout.of(ReferenceBuild.LeiosB004fe0c).fold(e => fail(e.message), identity)

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

  private def readDijkstra(name: String): Bytes =
    Bytes.wrap(JFiles.readAllBytes(fixture.resolve(s"dijkstra-203/$name")))

  /** The first Dijkstra block of `fixtures/dijkstra-devnet` (`[8, block]`) and its point. */
  private val (dijkstraBlock, dijkstraPoint) =
    val raw = JFiles
      .readAllLines(JPath.of("fixtures/dijkstra-devnet/blocks.tsv"))
      .toArray(Array.empty[String])
      .toVector
      .drop(1)
      .map(_.split('\t'))
      .collectFirst { case row if row(0) == "8" => row(4) }
      .flatMap(Bytes.fromHex(_).toOption)
      .getOrElse(fail("no Dijkstra block in fixtures/dijkstra-devnet/blocks.tsv"))
    val h =
      Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity).header
    (raw, BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary))

  /** Slot 203's `state` with its header's tip at [[dijkstraPoint]], written in the Leios layout,
    * and its `meta` with the checksum of that state and the captured tables (`crcOfConcat`,
    * [[SnapshotCrc.combined]]).
    */
  private val (movedState, movedMeta) =
    val file = leios.decode(readDijkstra("state")).fold(e => fail(e.render), identity)
    val tip = AnnTip(
      SlotNo.fromUnsigned(dijkstraPoint.slot.toLong),
      BlockNo.fromUnsigned(dijkstraPoint.blockNo.toLong),
      TipInfo.ShelleyBasedTip(
        ShelleyBasedEra.SDijkstra,
        Hash32.fromBytes(dijkstraPoint.hash).fold(e => fail(e.toString), identity)
      )
    )
    val state = leios
      .encode(file.copy(header = file.header.copy(tip = WithOrigin.At(tip))))
      .fold(e => fail(e.violation.render), identity)
    val meta = SnapshotMeta
      .parse(new String(readDijkstra("meta").toArray, "UTF-8"))
      .flatMap(m =>
        SnapshotMeta.encode(
          m.copy(checksum =
            SnapshotCrc
              .combined(SnapshotCrc.of(state), SnapshotCrc.of(readDijkstra("tables")))
              .value
          )
        )
      )
      .fold(e => fail(e.toString), identity)
    (state, Bytes.wrap(meta.getBytes("UTF-8")))

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
    val files =
      Vector("state" -> movedState, "tables" -> readDijkstra("tables"), "meta" -> movedMeta)
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
