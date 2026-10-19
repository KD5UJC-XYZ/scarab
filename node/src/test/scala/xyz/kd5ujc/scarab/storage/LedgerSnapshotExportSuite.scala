// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Outcome, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, ProtocolExtras, StateMirror}
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, Nonce, StorageError, TxIn, Utxo}
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.state.{Gap, NodeExtras, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.haskell.state.*

/** The state and tables are original node 11.1.3 fixtures, not Scala re-encodings. Only the V5
  * sidecar and checkpoint are reconstructed, as the import command does.
  */
class LedgerSnapshotExportSuite extends CatsEffectSuite:
  import StorageTestKit.{ok, tempDir}
  private val setup = NodeSetup
    .fromDirectory(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), identity)
  private val boot = setup.mirrorStart.extras match
    case MirrorExtras.InByron(_, b, _) => b
    case MirrorExtras.InShelley(_, c)  => c.boot
  private val extras: MirrorExtras.InShelley = MirrorExtras.InShelley(
    NodeExtras.empty,
    ConsensusExtras(boot, ByronState.initial, ProtocolExtras(0, Nonce.Neutral))
  )

  private def attempt[A](
      body: Handle[IO, LedgerSnapshotExport.Error] ?=> IO[A]
  ): IO[Either[LedgerSnapshotExport.Error, A]] =
    Handle
      .allow[LedgerSnapshotExport.Error](body.map(_.asRight[LedgerSnapshotExport.Error]))
      .rescue(e => IO.pure(Left(e)))

  private def fingerprint(root: Path): Map[String, Bytes] =
    val walk = JFiles.walk(root.toNioPath)
    try
      walk
        .iterator()
        .asScala
        .filter(JFiles.isRegularFile(_))
        .map { path =>
          root.toNioPath.relativize(path).toString ->
            Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(JFiles.readAllBytes(path)))
        }
        .toMap
    finally walk.close()

  private def readonly(db: Path): Resource[IO, Unit] =
    def permissions(readonly: Boolean): IO[Unit] = IO.blocking {
      val walk = JFiles.walk(db.toNioPath)
      val paths =
        try walk.iterator().asScala.toVector
        finally walk.close()
      val order = if readonly then paths.reverse else paths
      order.foreach { path =>
        val mode =
          if JFiles.isDirectory(path) then if readonly then "r-xr-xr-x" else "rwxr-xr-x"
          else if readonly then "r--r--r--"
          else "rw-r--r--"
        JFiles.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
      }
    }
    Resource.make(permissions(true))(_ => permissions(false))

  private def fixture(root: Path, name: String): IO[(Path, SnapshotSidecar, Bytes)] =
    val source = Path("fixtures/haskell-state") / name
    val raw = Bytes.wrap(JFiles.readAllBytes((source / "state").toNioPath))
    val file = HaskellLedgerFile.decode(raw).fold(e => fail(e.render), identity)
    val ext = file.ext.getOrElse(fail("expected Shelley-based reference state"))
    val anchor = StateMirror.anchorOf(file.header.tip).getOrElse(fail("expected reference tip"))
    val db = root / "db"
    ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(_) => fail("unexpected Byron fixture")
      case state: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = state.era
        ok {
          RocksUtxoStore.resource[IO](db / "utxo" / "live").use { store =>
            Handle
              .allow[LedgerSnapshotImport.Error] {
                for
                  loaded <- Handle
                    .allow[SnapshotTables.Error] {
                      LedgerSnapshotImport.loadTables[IO, e](
                        source,
                        store,
                        anchor.blockNo.toLong
                      )
                    }
                    .rescue(e => IO.raiseError(new AssertionError(e.toString)))
                  _ <- IO.blocking(
                    JFiles.createDirectories((db / "utxo" / "checkpoints").toNioPath)
                  )
                  _ <- store.checkpoint(db / "utxo" / "checkpoints" / StateBacking.stem(anchor))
                  sidecar = SnapshotSidecar(
                    Some(anchor),
                    Map.empty,
                    Set.empty,
                    SnapshotSidecar.Chain.InShelley(loaded._1, Params.empty, None, extras)
                  )
                  _ <- write(db, sidecar, raw)
                yield (db, sidecar, raw)
              }
              .rescue(e => IO.raiseError(new AssertionError(e.toString)))
          }
        }

  private def write(db: Path, sidecar: SnapshotSidecar, raw: Bytes): IO[Unit] = IO.blocking {
    val anchor = sidecar.anchor.getOrElse(fail("test snapshot has no anchor"))
    JFiles.createDirectories((db / "snapshots").toNioPath)
    JFiles.write(
      (db / "snapshots" / FileLedgerSnapshots.nameOf(anchor)).toNioPath,
      FileLedgerSnapshots.closed(SnapshotV5.framed(sidecar, raw))
    )
    ()
  }

  Vector("shelley-1000", "allegra-1800", "mary-2308", "alonzo-3013", "babbage-4000", "conway-4593")
    .foreach { name =>
      test(s"$name exports original state bytes and equal outputs without writing the source") {
        tempDir.use { root =>
          for
            input <- fixture(root, name)
            (db, sidecar, raw) = input
            before <- IO(fingerprint(db))
            result <- readonly(db).use(_ =>
              attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out")))
            )
            report = result.fold(e => fail(e.message), identity)
            _ <- IO {
              assertEquals(fingerprint(db), before)
              val writtenFiles = JFiles.list(report.directory.toNioPath)
              try
                assertEquals(
                  writtenFiles.iterator().asScala.map(_.getFileName.toString).toVector.sorted,
                  Vector("meta", "state", "tables")
                )
              finally writtenFiles.close()
              val outputFiles = JFiles.list((root / "out").toNioPath)
              try
                assertEquals(
                  outputFiles.iterator().asScala.map(_.getFileName.toString).toVector,
                  Vector(report.directory.fileName.toString)
                )
              finally outputFiles.close()
              assertEquals(report.anchor, sidecar.anchor.getOrElse(fail("reference anchor")))
              assertEquals(
                JFiles.readAllBytes((report.directory / "state").toNioPath).toVector,
                raw.toArray.toVector
              )
              val tables = Bytes.wrap(JFiles.readAllBytes((report.directory / "tables").toNioPath))
              val (written, summary) =
                SnapshotTables.decode(tables).fold(e => fail(e.toString), identity)
              val source = Path("fixtures/haskell-state") / name
              val (original, _) = SnapshotTables
                .decode(Bytes.wrap(JFiles.readAllBytes((source / "tables").toNioPath)))
                .fold(e => fail(e.toString), identity)
              val ext = HaskellLedgerFile
                .decode(raw)
                .toOption
                .flatMap(_.ext)
                .getOrElse(fail("reference state"))
              ext.ledgerState.state match
                case EraLedgerState.ByronCurrent(_) => fail("reference era")
                case state: EraLedgerState.InShelleyEra[e] =>
                  given ShelleyBasedEra[e] = state.era
                  def decoded(entries: Vector[SnapshotTables.Entry]) =
                    entries
                      .map(entry =>
                        entry.key -> TxOutMemPack
                          .decode[e](entry.value)
                          .fold(e => fail(e.toString), identity)
                      )
                      .toMap
                  assertEquals(decoded(written), decoded(original))
              val meta = SnapshotMeta
                .parse(JFiles.readString((report.directory / "meta").toNioPath))
                .fold(e => fail(e.toString), identity)
              assertEquals(SnapshotMeta.verify(meta, SnapshotCrc.of(raw), summary.crc), Right(()))
              assertEquals(report.outputs, summary.entries)
            }
          yield ()
        }
      }
    }

  test("a nonexistent source is refused without creating a source or ownership inode") {
    tempDir.use { root =>
      val db = root / "absent-parent" / "db"
      attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))).map {
        result =>
          assertEquals(result, Left(LedgerSnapshotExport.Error.MissingSource(db)))
          assert(!JFiles.exists((root / "absent-parent").toNioPath))
          assert(!JFiles.exists((root / "out").toNioPath))
      }
    }
  }

  test("gaps, a missing selected slot, collisions and nested outputs preserve the database") {
    tempDir.use { root =>
      for
        input <- fixture(root, "conway-4593")
        (db, sidecar, raw) = input
        _ <- write(db, sidecar.copy(gaps = Set(Gap.NonMyopic)), raw)
        before <- IO(fingerprint(db))
        gaps <- attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out")))
        _ <- IO(assertEquals(gaps, Left(LedgerSnapshotExport.Error.Gaps(Set(Gap.NonMyopic)))))
        missing <- attempt(
          LedgerSnapshotExport.run[IO](
            LedgerSnapshotExport.Config(db, root / "out", Some(SlotNo.fromUnsigned(-1L)))
          )
        )
        _ <- IO(
          assert(missing.left.exists(_.isInstanceOf[LedgerSnapshotExport.Error.MissingSnapshot]))
        )
        nested <- attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, db / "out")))
        _ <- IO {
          assertEquals(nested, Left(LedgerSnapshotExport.Error.OutputWithinSource(db / "out")))
          assertEquals(fingerprint(db), before)
        }
        _ <- write(db, sidecar, raw)
        exported <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))
        )
        report = exported.fold(e => fail(e.message), identity)
        outputBefore <- IO(fingerprint(report.directory))
        collision <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))
        )
        _ <- IO {
          assertEquals(collision, Left(LedgerSnapshotExport.Error.Collision(report.directory)))
          assertEquals(fingerprint(report.directory), outputBefore)
        }
      yield ()
    }
  }

  test("Byron, corrupt checksums and old formats are refused without migration or publication") {
    tempDir.use { root =>
      for
        input <- fixture(root, "conway-4593")
        (db, sidecar, raw) = input
        file = HaskellLedgerFile.decode(raw).fold(e => fail(e.render), identity)
        byron = sidecar.copy(chain =
          SnapshotSidecar.Chain.InByron(file.header.chainDep.state, extras.consensus)
        )
        _ <- write(db, byron, Bytes.empty)
        refused <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))
        )
        _ <- IO(assertEquals(refused, Left(LedgerSnapshotExport.Error.Byron)))
        _ <- write(db, sidecar, raw)
        path = db / "snapshots" / FileLedgerSnapshots.nameOf(
          sidecar.anchor.getOrElse(fail("anchor"))
        )
        content = SnapshotV5.framed(sidecar, raw)
        _ <- IO.blocking(
          JFiles.write(
            path.toNioPath,
            FileLedgerSnapshots.closed(content).updated(12, (content(12) ^ 1).toByte)
          )
        )
        corruptBefore <- IO(fingerprint(db))
        corrupt <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))
        )
        _ <- IO {
          assert(corrupt.left.exists {
            case LedgerSnapshotExport.Error.Storage(_: StorageError.Corrupt[?]) => true
            case _                                                              => false
          })
          assertEquals(fingerprint(db), corruptBefore)
        }
        _ <- IO.blocking(
          JFiles.write(path.toNioPath, FileLedgerSnapshots.closed(content.updated(7, '4'.toByte)))
        )
        oldBefore <- IO(fingerprint(db))
        old <- attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out")))
        _ <- IO {
          assertEquals(
            old,
            Left(
              LedgerSnapshotExport.Error.Storage(
                StorageError.RetiredVersion(
                  "snapshot format",
                  FileKind.SnapshotFile,
                  FormatVersion.V4,
                  FormatVersion.V5
                )
              )
            )
          )
          assertEquals(fingerprint(db), oldBefore)
          val listed = JFiles.list((root / "out").toNioPath)
          try assert(!listed.findAny().isPresent)
          finally listed.close()
        }
      yield ()
    }
  }

  test("the checkpoint count and persisted stamp must agree with the selected snapshot") {
    tempDir.use { root =>
      for
        input <- fixture(root, "conway-4593")
        (db, sidecar, raw) = input
        anchor = sidecar.anchor.getOrElse(fail("anchor"))
        chain = sidecar.chain match
          case s: SnapshotSidecar.Chain.InShelley => s
          case _                                  => fail("Shelley sidecar")
        _ <- write(
          db,
          sidecar.copy(chain = chain.copy(utxo = chain.utxo.copy(count = chain.utxo.count + 1))),
          raw
        )
        countBefore <- IO(fingerprint(db))
        count <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))
        )
        _ <- IO {
          assert(count.left.exists {
            case LedgerSnapshotExport.Error.Tables(_: SnapshotTables.Error.CountMismatch) => true
            case _                                                                        => false
          })
          assertEquals(fingerprint(db), countBefore)
        }
        _ <- write(db, sidecar, raw)
        checkpoint = db / "utxo" / "checkpoints" / StateBacking.stem(anchor)
        _ <- ok(RocksUtxoStore.resource[IO](checkpoint).use { store =>
          store.write(Map.empty, chain.utxo.flushed + 1) *> store.checkpoint(root / "replacement")
        })
        _ <- IO.blocking {
          RocksUtxoStore.deleteTree(checkpoint.toNioPath)
          JFiles.move((root / "replacement").toNioPath, checkpoint.toNioPath)
        }
        stampBefore <- IO(fingerprint(db))
        stamp <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out"))
        )
        _ <- IO {
          assertEquals(
            stamp,
            Left(
              LedgerSnapshotExport.Error.CheckpointStamp(chain.utxo.flushed, chain.utxo.flushed + 1)
            )
          )
          assertEquals(fingerprint(db), stampBefore)
          val listed = JFiles.list((root / "out").toNioPath)
          try assert(!listed.findAny().isPresent)
          finally listed.close()
        }
      yield ()
    }
  }

  test("another participating writer refuses export; an exact named slot succeeds after release") {
    tempDir.use { root =>
      for
        input <- fixture(root, "conway-4593")
        (db, sidecar, _) = input
        refused <- ok(DatabaseOwnership.resource[IO](db).use { _ =>
          attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out")))
        })
        _ <- IO(
          assertEquals(
            refused,
            Left(LedgerSnapshotExport.Error.Storage(StorageError.DatabaseInUse(db.toString)))
          )
        )
        slot = SlotNo
          .fromBigInt(sidecar.anchor.getOrElse(fail("anchor")).slot)
          .fold(e => fail(e.toString), identity)
        accepted <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, root / "out", Some(slot)))
        )
        _ <- IO(assert(accepted.isRight))
      yield ()
    }
  }

  test(
    "cancelling a wide table export removes staging and releases ownership without changing source"
  ) {
    tempDir.use { root =>
      for
        input <- fixture(root, "conway-4593")
        (db, sidecar, raw) = input
        anchor = sidecar.anchor.getOrElse(fail("anchor"))
        chain = sidecar.chain match
          case s: SnapshotSidecar.Chain.InShelley => s
          case _                                  => fail("Shelley sidecar")
        checkpoint = db / "utxo" / "checkpoints" / StateBacking.stem(anchor)
        wide <- ok(RocksUtxoStore.resource[IO](db / "utxo" / "live").use { store =>
          for
            entries <- store.all
            out = entries.values.headOption.getOrElse(fail("fixture output"))
            rows = Vector
              .tabulate(100000) { n =>
                val id = Bytes.wrap(
                  Array.tabulate[Byte](32)(i =>
                    if i < 28 then 0.toByte else (n >>> (8 * (31 - i))).toByte
                  )
                )
                TxIn(id, 0) -> out
              }
              .toMap
            _ <- store.reset
            _ <- store.write(rows.map((key, value) => key -> Some(value)), chain.utxo.stamp)
            _ <- IO.blocking(RocksUtxoStore.deleteTree(checkpoint.toNioPath))
            _ <- store.checkpoint(checkpoint)
          yield Utxo
            .fromMap(rows)
            .copy(
              stamp = chain.utxo.stamp,
              flushed = chain.utxo.flushed,
              diff = Map.empty,
              resident = Map.empty
            )
        })
        _ <- write(db, sidecar.copy(chain = chain.copy(utxo = wide)), raw)
        before <- IO(fingerprint(db))
        out = root / "out"
        fiber <- attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, out))).start
        _ <- (fs2.Stream
          .awakeEvery[IO](2.millis)
          .evalMap { _ =>
            IO.blocking {
              if !JFiles.exists(out.toNioPath) then false
              else
                val walk = JFiles.walk(out.toNioPath)
                try
                  walk
                    .iterator()
                    .asScala
                    .exists(path => path.getFileName.toString == "tables" && JFiles.size(path) > 0)
                finally walk.close()
            }
          }
          .filter(identity)
          .take(1)
          .compile
          .drain
          .timeout(20.seconds) *>
          fiber.cancel *> fiber.join.flatMap {
            case Outcome.Canceled() => IO.unit
            case other =>
              IO.raiseError(new AssertionError(s"export completed before cancellation: $other"))
          }).guarantee(fiber.cancel)
        _ <- ok(DatabaseOwnership.resource[IO](db).use(_ => IO.unit))
        _ <- IO {
          assertEquals(fingerprint(db), before)
          val listed = JFiles.list(out.toNioPath)
          try assert(!listed.findAny().isPresent)
          finally listed.close()
        }
      yield ()
    }
  }

  test("canonical source aliases share ownership and cannot hide an output inside the source") {
    tempDir.use { root =>
      for
        input <- fixture(root, "conway-4593")
        (db, _, _) = input
        alias = root / "alias"
        _ <- IO.blocking(JFiles.createSymbolicLink(alias.toNioPath, db.toNioPath))
        before <- IO(fingerprint(db))
        held <- ok(DatabaseOwnership.resource[IO](db).use { _ =>
          attempt(LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(alias, root / "out")))
        })
        _ <- IO(
          assertEquals(
            held,
            Left(LedgerSnapshotExport.Error.Storage(StorageError.DatabaseInUse(alias.toString)))
          )
        )
        nested <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(db, alias / "nested"))
        )
        _ <- IO {
          assertEquals(
            nested,
            Left(LedgerSnapshotExport.Error.OutputWithinSource(alias / "nested"))
          )
          assert(!JFiles.exists((db / "nested").toNioPath))
        }
        accepted <- attempt(
          LedgerSnapshotExport.run[IO](LedgerSnapshotExport.Config(alias, root / "out"))
        )
        _ <- IO {
          assert(accepted.isRight)
          assertEquals(fingerprint(db), before)
        }
      yield ()
    }
  }
