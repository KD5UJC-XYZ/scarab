// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import java.nio.file.{Files, Path as JPath}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorExtras,
  ProtocolExtras,
  StateMirror
}
import xyz.kd5ujc.scarab.ledger.{GenesisFileHash, Nonce, StorageError}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayNewEpochState,
  Gap,
  NodeExtras,
  ShelleyBasedEra
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, SnapshotTables}
import xyz.kd5ujc.scarab.storage.scls.{
  SclsBase,
  SclsAdmission,
  SclsExport,
  SclsImport,
  SclsInterchange,
  SclsEntrySorter,
  SclsFraming,
  SclsRecord,
  SclsOutput,
  SclsStreaming,
  SclsValidation
}

class SclsBaseSuite extends CatsEffectSuite:
  import StorageTestKit.{ok, tempDir}
  private val setup =
    NodeSetup.fromDirectory(JPath.of("fixtures/eras/genesis")).fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val source = Path("fixtures/haskell-state/conway-4593")
  private val raw = Bytes.wrap(Files.readAllBytes((source / "state").toNioPath))
  private val file = HaskellLedgerFile.decode(raw).fold(e => fail(e.toString), identity)
  private val anchor = StateMirror.anchorOf(file.header.tip).getOrElse(fail("reference anchor"))
  private val nes = file.ext.getOrElse(fail("complete reference state")).ledgerState.state match
    case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) => state.nes
    case _ => fail("Conway reference state")
  private val gov = ConwayNewEpochState.gov.get(nes)
  private val boot = setup.mirrorStart.extras match
    case MirrorExtras.InByron(_, value, _) => value
    case MirrorExtras.InShelley(_, value)  => value.boot
  private val extras: MirrorExtras.InShelley = MirrorExtras.InShelley(
    NodeExtras.empty,
    ConsensusExtras(boot, ByronState.initial, ProtocolExtras(0, Nonce.Neutral))
  )

  private def attempt[A](
      body: Handle[IO, SclsBase.Error] ?=> IO[A]
  ): IO[Either[SclsBase.Error, A]] =
    Handle.allow[SclsBase.Error](body.map(_.asRight[SclsBase.Error])).rescue(e => IO.pure(Left(e)))

  private def fixture(
      root: Path,
      gaps: Set[Gap] = Set.empty,
      checkpointStamp: Long = anchor.blockNo.toLong,
      wrongPoint: Boolean = false,
      countOffset: Long = 0
  ): IO[SclsBase.Config] =
    val db = root / "db"
    ok {
      RocksUtxoStore.resource[IO](db / "utxo" / "live").use { store =>
        Handle
          .allow[LedgerSnapshotImport.Error] {
            Handle
              .allow[SnapshotTables.Error] {
                for
                  loaded <- LedgerSnapshotImport.loadTables[IO, Conway](
                    source,
                    store,
                    anchor.blockNo.toLong
                  )
                  _ <- store.write(Vector.empty, checkpointStamp)
                  _ <- IO.blocking(Files.createDirectories((db / "utxo" / "checkpoints").toNioPath))
                  _ <- store.checkpoint(db / "utxo" / "checkpoints" / StateBacking.stem(anchor))
                  _ <- NetworkIdentityFile.write[IO](db, setup.identity)
                  sidecar = SnapshotSidecar(
                    Some(
                      if wrongPoint then anchor.copy(hash = Bytes.wrap(Array.fill[Byte](32)(7)))
                      else anchor
                    ),
                    Map.empty,
                    gaps,
                    SnapshotSidecar.Chain
                      .InShelley(
                        loaded._1.copy(count = loaded._1.count + countOffset),
                        gov.curPParams,
                        Some(gov.prevPParams),
                        extras
                      )
                  )
                  _ <- IO.blocking {
                    Files.createDirectories((db / "snapshots").toNioPath)
                    Files.write(
                      (db / "snapshots" / FileLedgerSnapshots.nameOf(anchor)).toNioPath,
                      FileLedgerSnapshots.closed(SnapshotV5.framed(sidecar, raw))
                    )
                  }
                yield SclsBase.Config(db, root, anchor, setup.identity)
              }
              .rescue(e => IO.raiseError(new AssertionError(e.toString)))
          }
          .rescue(e => IO.raiseError(new AssertionError(e.toString)))
      }
    }

  private def fingerprint(root: Path): Map[String, Bytes] =
    val walk = Files.walk(root.toNioPath)
    try
      walk
        .iterator()
        .asScala
        .filter(Files.isRegularFile(_))
        .map { path =>
          root.toNioPath.relativize(path).toString -> Bytes.wrap(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
          )
        }
        .toMap
    finally walk.close()

  private def readonly(db: Path): Resource[IO, Unit] =
    def mode(readOnly: Boolean): IO[Unit] = IO.blocking {
      val walk = Files.walk(db.toNioPath)
      val paths =
        try walk.iterator().asScala.toVector
        finally walk.close()
      (if readOnly then paths.reverse else paths).foreach { path =>
        val permissions =
          if Files.isDirectory(path) then if readOnly then "r-xr-xr-x" else "rwxr-xr-x"
          else if readOnly then "r--r--r--"
          else "rw-r--r--"
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
      }
    }
    Resource.make(mode(true))(_ => mode(false))

  test(
    "owned complete base streams the original checkpoint without changing read-only source files"
  ) {
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        result <- readonly(config.db).use { _ =>
          attempt(SclsBase.resource[IO](config).use { base =>
            ok(base.entries.compile.toVector).map { rows =>
              assertEquals(base.context.anchor, anchor)
              assertEquals(base.context.identity, setup.identity)
              assertEquals(base.ledger.bcur, nes.bcur)
              assertEquals(ConwayNewEpochState.gov.get(base.ledger).curPParams, gov.curPParams)
              assertEquals(rows.size.toLong, base.utxo.count)
              assertEquals(rows.size, 3)
              assert(rows.forall(_._2.raw.nonEmpty))
            }
          })
        }
        _ <- IO {
          assertEquals(result, Right(()))
          assertEquals(fingerprint(config.db), before)
          val paths = Files.list(root.toNioPath)
          try
            assert(
              !paths
                .iterator()
                .asScala
                .exists(_.getFileName.toString.startsWith("scarab-scls-base-"))
            )
          finally paths.close()
        }
      yield ()
    }
  }

  test("missing or different complete identity is refused without changing source data") {
    tempDir.use { root =>
      for
        config <- fixture(root)
        changed = config.identity.copy(conwayGenesis =
          GenesisFileHash(Bytes.wrap(Array.fill[Byte](32)(8)))
        )
        mismatch <- attempt(
          SclsBase.resource[IO](config.copy(identity = changed)).use(_ => IO.unit)
        )
        _ <- IO(assert(mismatch.left.exists {
          case SclsBase.Error.Storage(_: StorageError.NetworkMismatch) => true
          case _                                                       => false
        }))
        _ <- IO.blocking(Files.delete((config.db / NetworkIdentityFile.FileName).toNioPath))
        before <- IO(fingerprint(config.db))
        missing <- attempt(SclsBase.resource[IO](config).use(_ => IO.unit))
        _ <- IO {
          assertEquals(missing, Left(SclsBase.Error.MissingIdentity))
          assertEquals(fingerprint(config.db), before)
        }
      yield ()
    }
  }

  test("offline native base exports the exact independently produced Haskell SCLS artifact") {
    val original =
      Bytes.wrap(Files.readAllBytes(JPath.of("fixtures/scls/conway-4593/reference.scls")))
    val limit = SclsFraming.Limit.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
    val reference = SclsValidation
      .validate(SclsFraming.decode(original, limit).fold(e => fail(e.toString), identity))
      .fold(e => fail(e.toString), identity)
    val streaming =
      SclsStreaming.Config.fromInts(1024 * 1024, 1000, 4096).fold(e => fail(e.toString), identity)
    val sorter = SclsEntrySorter.Config.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        written <- Ref.of[IO, Vector[Bytes]](Vector.empty)
        result <- attempt(SclsBase.resource[IO](config).use { base =>
          Handle
            .allow[SclsExport.Error] {
              SclsExport.write[IO](
                base,
                root,
                reference.manifest.summary,
                SclsExport.Config(streaming, sorter)
              )(bytes => written.update(_ :+ bytes))
            }
            .rescue(e => IO(fail(e.toString)))
        })
        output <- written.get.map(_.foldLeft(Bytes.empty)(_ ++ _))
        _ <- IO {
          assertEquals(result.map(_.manifest), Right(reference.manifest))
          assertEquals(output, original)
          assertEquals(fingerprint(config.db), before)
        }
      yield ()
    }
  }

  test("gaps, a different sidecar point and a mismatched checkpoint generation are refused") {
    Vector("gaps", "point", "stamp").traverse_ { kind =>
      tempDir.use { root =>
        for
          config <- fixture(
            root,
            gaps = if kind == "gaps" then Set(Gap.NonMyopic) else Set.empty,
            checkpointStamp =
              if kind == "stamp" then anchor.blockNo.toLong - 1 else anchor.blockNo.toLong,
            wrongPoint = kind == "point"
          )
          before <- IO(fingerprint(config.db))
          result <- attempt(SclsBase.resource[IO](config).use(_ => IO.unit))
          _ <- IO {
            assert(
              result.left.exists {
                case SclsBase.Error.Gaps(_)               => kind == "gaps"
                case SclsBase.Error.PointMismatch(_, _)   => kind == "point"
                case SclsBase.Error.CheckpointStamp(_, _) => kind == "stamp"
                case _                                    => false
              },
              result.toString
            )
            assertEquals(fingerprint(config.db), before)
          }
        yield ()
      }
    }
  }

  test("export refuses inconsistent UTxO bookkeeping before emitting any artifact bytes") {
    val streaming =
      SclsStreaming.Config.fromInts(1024 * 1024, 1000, 4096).fold(e => fail(e.toString), identity)
    val sorter = SclsEntrySorter.Config.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
    tempDir.use { root =>
      for
        config <- fixture(root, countOffset = 1)
        emitted <- Ref.of[IO, Int](0)
        result <- attempt(SclsBase.resource[IO](config).use { base =>
          Handle
            .allow[SclsExport.Error](
              SclsExport
                .write[IO](
                  base,
                  root,
                  SclsRecord.Summary("test", "test", None),
                  SclsExport.Config(streaming, sorter)
                )(_ => emitted.update(_ + 1))
                .map(_.asRight[SclsExport.Error])
            )
            .rescue(e => IO.pure(Left(e)))
        })
        calls <- emitted.get
        _ <- IO {
          assertEquals(result, Right(Left(SclsExport.Error.UtxoCount(4L, BigInt(3)))))
          assertEquals(calls, 0)
        }
      yield ()
    }
  }

  private val importConfig = SclsImport.Config(
    SclsExport.Config(
      SclsStreaming.Config.fromInts(1024 * 1024, 1000, 4096).fold(e => fail(e.toString), identity),
      SclsEntrySorter.Config.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
    )
  )
  private val referenceScls =
    Bytes.wrap(Files.readAllBytes(JPath.of("fixtures/scls/conway-4593/reference.scls")))

  private def importing[A](
      body: Handle[IO, SclsImport.Error] ?=> IO[A]
  ): IO[Either[SclsImport.Error, A]] =
    Handle
      .allow[SclsImport.Error](body.map(_.asRight[SclsImport.Error]))
      .rescue(e => IO.pure(Left(e)))

  test("SCLS import reconstructs a complete native snapshot and checkpoint in owned staging") {
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        result <- attempt(SclsBase.resource[IO](config).use { base =>
          importing(
            SclsImport
              .resource[IO](
                base,
                base.context,
                Stream.emits(referenceScls.value).covary[IO],
                root,
                importConfig
              )
              .use { prepared =>
                for
                  checkpointRows <- ok(
                    RocksUtxoCheckpoint
                      .resource[IO](prepared.checkpoint, root)
                      .use(_.entries.compile.toVector)
                  )
                  baseRows <- ok(base.entries.compile.toVector)
                  snapshotBytes <- IO.blocking(
                    Files.readAllBytes(
                      (prepared.artifacts / "snapshots" / FileLedgerSnapshots
                        .nameOf(anchor)).toNioPath
                    )
                  )
                  content <- IO.fromEither(
                    FileLedgerSnapshots
                      .verified(snapshotBytes)
                      .leftMap(e => new AssertionError(e.toString))
                  )
                  native <- ok(SnapshotV5.format[IO](SnapshotCheck.Full).decode(content)).flatMap(
                    result => IO.fromEither(result.leftMap(e => new AssertionError(e.toString)))
                  )
                yield
                  assertEquals(checkpointRows, baseRows)
                  assertEquals(prepared.snapshot.anchor, Some(anchor))
                  assertEquals(native.anchor, Some(anchor))
                  assertEquals(prepared.utxo.count, 3L)
                  assertEquals(SnapshotV5.sidecarOf(content).map(_.gaps), Right(Set.empty[Gap]))
                  prepared.directory
              }
          )
        })
        _ <- IO {
          val staged =
            result.flatMap(_.leftMap(e => fail(e.toString))).fold(e => fail(e.toString), identity)
          assert(!Files.exists(staged.toNioPath))
          assertEquals(fingerprint(config.db), before)
        }
      yield ()
    }
  }

  test("truncated and trailing SCLS inputs leave no prepared artifacts and preserve the source") {
    Vector(
      Bytes.wrap(referenceScls.value.dropRight(1).toArray),
      referenceScls ++ Bytes.wrap(Array[Byte](0))
    ).traverse_ { bad =>
      tempDir.use { root =>
        for
          config <- fixture(root)
          before <- IO(fingerprint(config.db))
          result <- attempt(SclsBase.resource[IO](config).use { base =>
            importing(
              SclsImport
                .resource[IO](
                  base,
                  base.context,
                  Stream.emits(bad.value).covary[IO],
                  root,
                  importConfig
                )
                .use(_ => IO.unit)
            )
          })
          _ <- IO {
            assert(result.exists(_.isLeft), result.toString)
            assertEquals(fingerprint(config.db), before)
            val paths = Files.list(root.toNioPath)
            try
              assert(
                !paths
                  .iterator()
                  .asScala
                  .exists(_.getFileName.toString.startsWith("scarab-scls-import-"))
              )
            finally paths.close()
          }
        yield ()
      }
    }
  }

  test("SCLS import refuses a different exact point or complete identity and removes staging") {
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        result <- attempt(SclsBase.resource[IO](config).use { base =>
          Vector(
            base.context.copy(anchor = anchor.copy(hash = Bytes.wrap(Array.fill[Byte](32)(9)))),
            base.context.copy(identity =
              setup.identity
                .copy(alonzoGenesis = GenesisFileHash(Bytes.wrap(Array.fill[Byte](32)(9))))
            )
          ).traverse_ { expected =>
            importing(
              SclsImport
                .resource[IO](
                  base,
                  expected,
                  Stream.emits(referenceScls.value).covary[IO],
                  root,
                  importConfig
                )
                .use(_ => IO.unit)
            ).map { result =>
              assert(
                result.left.exists {
                  case SclsImport.Error.Admission(_) => true
                  case _                             => false
                },
                result.toString
              )
            }
          }
        })
        _ <- IO {
          assertEquals(result, Right(()))
          assertEquals(fingerprint(config.db), before)
          val paths = Files.list(root.toNioPath)
          try
            assert(
              !paths
                .iterator()
                .asScala
                .exists(_.getFileName.toString.startsWith("scarab-scls-import-"))
            )
          finally paths.close()
        }
      yield ()
    }
  }

  test("canceling a stalled SCLS input closes native staging and releases source ownership") {
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        started <- Deferred[IO, Unit]
        fiber <- attempt(SclsBase.resource[IO](config).use { base =>
          importing(
            SclsImport
              .resource[IO](
                base,
                base.context,
                Stream.exec(started.complete(()).void) ++ Stream.never[IO],
                root,
                importConfig
              )
              .use(_ => IO.unit)
          )
        }).start
        _ <- started.get
        _ <- fiber.cancel
        result <- fiber.join
        reacquired <- attempt(SclsBase.resource[IO](config).use(_ => IO.unit))
        _ <- IO {
          assert(result.isCanceled)
          assertEquals(reacquired, Right(()))
          assertEquals(fingerprint(config.db), before)
          val paths = Files.list(root.toNioPath)
          try
            assert(
              !paths
                .iterator()
                .asScala
                .exists(_.getFileName.toString.startsWith("scarab-scls-import-"))
            )
          finally paths.close()
        }
      yield ()
    }
  }

  test("base scratch rejects the database and descendants reached through symlinks") {
    Vector("same", "descendant", "symlink").traverse_ { kind =>
      tempDir.use { root =>
        for
          config <- fixture(root)
          scratch <- IO.blocking {
            kind match
              case "same" => config.db
              case "descendant" =>
                val path = config.db / "scratch"
                Files.createDirectories(path.toNioPath)
                path
              case "symlink" =>
                val path = root / "scratch-link"
                Files.createSymbolicLink(
                  path.toNioPath,
                  (config.db / "utxo").toNioPath.toAbsolutePath
                )
                path
          }
          before <- IO(fingerprint(config.db))
          result <- attempt(
            SclsBase.resource[IO](config.copy(scratch = scratch)).use(_ => IO.unit)
          )
          _ <- IO {
            assert(result.left.exists {
              case SclsBase.Error.ScratchInsideDatabase(_, _) => true
              case _                                          => false
            })
            assertEquals(fingerprint(config.db), before)
          }
        yield ()
      }
    }
  }

  test("export sorter scratch cannot resolve inside the source database") {
    val streaming =
      SclsStreaming.Config.fromInts(1024 * 1024, 1000, 4096).fold(e => fail(e.toString), identity)
    val sorter = SclsEntrySorter.Config.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        emitted <- Ref.of[IO, Int](0)
        result <- Handle
          .allow[SclsBase.Error] {
            SclsBase
              .resource[IO](config)
              .use { base =>
                Handle
                  .allow[SclsExport.Error](
                    SclsExport
                      .write[IO](
                        base,
                        config.db,
                        SclsRecord.Summary("test", "test", None),
                        SclsExport.Config(streaming, sorter)
                      )(_ => emitted.update(_ + 1))
                      .map(_.asRight[SclsExport.Error])
                  )
                  .rescue(error => IO.pure(Left(error)))
              }
              .map(_.asRight[SclsBase.Error])
          }
          .rescue(error => IO.pure(Left(error)))
        calls <- emitted.get
        _ <- IO {
          assert(result.exists(_.left.exists {
            case SclsExport.Error.Base(SclsBase.Error.ScratchInsideDatabase(_, _)) => true
            case _                                                                 => false
          }))
          assertEquals(calls, 0)
          assertEquals(fingerprint(config.db), before)
        }
      yield ()
    }
  }

  private def interchange[A](
      body: Handle[IO, SclsInterchange.Error] ?=> IO[A]
  ): IO[Either[SclsInterchange.Error, A]] =
    Handle
      .allow[SclsInterchange.Error](body.map(_.asRight[SclsInterchange.Error]))
      .rescue(e => IO.pure(Left(e)))

  test(
    "file export validates closed bytes, publishes exact reference data and refuses an existing path"
  ) {
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        out = root / "reference.scls"
        summary = SclsValidation
          .validate(
            SclsFraming
              .decode(
                referenceScls,
                SclsFraming.Limit.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
              )
              .fold(e => fail(e.toString), identity)
          )
          .fold(e => fail(e.toString), identity)
          .manifest
          .summary
        outputConfig = SclsInterchange.ExportConfig(config, out, summary, importConfig.projection)
        result <- interchange(SclsInterchange.exportFile[IO](outputConfig))
        original <- IO.blocking(Bytes.wrap(Files.readAllBytes(out.toNioPath)))
        collision <- interchange(SclsInterchange.exportFile[IO](outputConfig))
        _ <- IO {
          assert(result.isRight, result.toString)
          assertEquals(original, referenceScls)
          assertEquals(collision, Left(SclsInterchange.Error.Collision(out)))
          assertEquals(Bytes.wrap(Files.readAllBytes(out.toNioPath)), original)
          assertEquals(fingerprint(config.db), before)
          val paths = Files.list(root.toNioPath)
          try
            assert(
              !paths.iterator().asScala.exists(_.getFileName.toString.startsWith(".scarab-scls-"))
            )
          finally paths.close()
        }
      yield ()
    }
  }

  test("file export and staged import reject paths resolving inside the source database") {
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        result <- interchange(
          SclsInterchange.exportFile[IO](
            SclsInterchange.ExportConfig(
              config,
              config.db / "state.scls",
              SclsRecord.Summary("test", "test", None),
              importConfig.projection
            )
          )
        )
        imported <- attempt(SclsBase.resource[IO](config).use { base =>
          importing(
            SclsImport
              .resource[IO](
                base,
                base.context,
                Stream.emits(referenceScls.value).covary[IO],
                config.db,
                importConfig
              )
              .use(_ => IO.unit)
          )
        })
        _ <- IO {
          assert(
            result.left.exists {
              case SclsInterchange.Error.Base(SclsBase.Error.ScratchInsideDatabase(_, _)) => true
              case _                                                                      => false
            },
            result.toString
          )
          assert(
            imported.exists(_.left.exists {
              case SclsImport.Error.Base(SclsBase.Error.ScratchInsideDatabase(_, _)) => true
              case _                                                                 => false
            }),
            imported.toString
          )
          assertEquals(fingerprint(config.db), before)
        }
      yield ()
    }
  }

  test(
    "import rejects entries beyond its complete-base budget before consuming the terminal input"
  ) {
    val limit = SclsFraming.Limit.fromInt(1024 * 1024).fold(e => fail(e.toString), identity)
    val frames = SclsFraming.decode(referenceScls, limit).fold(e => fail(e.toString), identity)
    val rows = frames.flatMap { frame =>
      SclsRecord.decode(frame).fold(e => fail(e.toString), identity) match
        case chunk: SclsRecord.Record.Chunk =>
          SclsRecord.entries(chunk).fold(e => fail(e.toString), identity).map(chunk.namespace -> _)
        case _ => Vector.empty
    }
    val first =
      rows.find(_._1 == SclsRecord.Namespace.Blocks).getOrElse(fail("reference blocks row"))
    val extra =
      first._1 -> first._2.copy(key = Bytes.wrap(Array.fill[Byte](28)(0)) ++ first._2.key.drop(28))
    assert(!rows.exists(_._2.key == extra._2.key))
    val sorted = (rows :+ extra).sortWith { (a, b) =>
      val namespace = a._1.bytes.compareTo(b._1.bytes)
      namespace < 0 || (namespace == 0 && a._2.key.compareTo(b._2.key) < 0)
    }
    val outputConfig =
      SclsOutput.Config.fromInts(1024 * 1024, 1000).fold(e => fail(e.toString), identity)
    val (initial, header) = SclsOutput.begin(outputConfig).fold(e => fail(e.toString), identity)
    val (end, chunks) = sorted
      .foldLeftM((initial, Vector(header))) { case ((builder, bytes), (namespace, entry)) =>
        builder.push(namespace, entry).map((next, emitted) => (next, bytes ++ emitted))
      }
      .fold(e => fail(e.toString), identity)
    val artifact = (chunks ++ end
      .finish(anchor.slot, SclsRecord.Summary("test", "test", None))
      .fold(e => fail(e.toString), identity)).foldLeft(Bytes.empty)(_ ++ _)
    tempDir.use { root =>
      for
        config <- fixture(root)
        before <- IO(fingerprint(config.db))
        result <- attempt(SclsBase.resource[IO](config).use { base =>
          importing(
            SclsImport
              .resource[IO](
                base,
                base.context,
                Stream.emits(artifact.value).covary[IO] ++ Stream
                  .raiseError[IO](new AssertionError("read beyond namespace budget")),
                root,
                importConfig
              )
              .use(_ => IO.unit)
          )
        })
        _ <- IO {
          assertEquals(
            result,
            Right(
              Left(
                SclsImport.Error.Admission(
                  SclsAdmission.Error.NamespaceEntries(SclsRecord.Namespace.Blocks, 2, 3)
                )
              )
            )
          )
          assertEquals(fingerprint(config.db), before)
        }
      yield ()
    }
  }
