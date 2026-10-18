// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Outcome}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath}
import java.security.{KeyPairGenerator, MessageDigest, Signature}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.crypto.witness.PublicKey32
import xyz.kd5ujc.scarab.ledger.{Block, StorageError}
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.*

class LedgerSnapshotImportSuite extends CatsEffectSuite:
  import StorageTestKit.{ok, tempDir}
  import LedgerSnapshotImport.{Error, Evidence, Trust}
  private val fixture = JPath.of("fixtures/eras")
  private val setup =
    NodeSetup.fromDirectory(fixture.resolve("genesis")).fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private val raws = JFiles
    .readAllLines(fixture.resolve("blocks.hex"))
    .asScala
    .toVector
    .filter(_.nonEmpty)
    .map(s => Bytes.fromHex(s).fold(fail(_), identity))
  private val blocks = raws.map(raw =>
    Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
  )
  private lazy val captures =
    val states = blocks.indices
      .scanLeft(setup.mirrorStart) { (before, index) =>
        MirrorSteps
          .block(setup, before, blocks(index).header, raws(index))
          .fold(e => fail(e.reason), identity)
      }
      .tail
    blocks.indices
      .groupBy(i => blocks(i).era)
      .view
      .mapValues(_.last)
      .toMap
      .map { (era, i) => (era, (states(i), raws(i))) }
  private val genesis = setup.handOver.fold(fail("expected Byron genesis"))(_.protocolParams)
  private val boot = setup.mirrorStart.extras match
    case MirrorExtras.InByron(_, b, _) => b
    case MirrorExtras.InShelley(_, c)  => c.boot

  private def attempt[A](body: Handle[IO, Error] ?=> IO[A]): IO[Either[Error, A]] =
    Handle.allow[Error](body.map(_.asRight[Error])).rescue(e => IO.pure(Left(e)))
  private def config(source: Path, db: Path, trust: Trust = Trust.Unverified) =
    LedgerSnapshotImport.Config(
      source,
      db,
      setup.identity,
      setup.geometry.byron.epochSlots,
      genesis,
      boot,
      trust
    )
  private def anchor(state: MirrorState): BlockRef =
    StateMirror.anchorOf(state.ext.headerState.tip).getOrElse(fail("capture has no tip"))
  private def fingerprint(root: Path): Map[String, Bytes] =
    val walk = JFiles.walk(root.toNioPath)
    try
      walk
        .iterator()
        .asScala
        .filter(JFiles.isRegularFile(_))
        .map { p =>
          root.toNioPath.relativize(p).toString -> Bytes.wrap(
            MessageDigest.getInstance("SHA-256").digest(JFiles.readAllBytes(p))
          )
        }
        .toMap
    finally walk.close()

  private def source(root: Path, era: Era): IO[(Path, Path, MirrorState, Bytes)] =
    val (state, rawBlock) = captures(era)
    val ledger = root / "source" / "ledger" / anchor(state).slot.toString
    val db = root / "db"
    val file = HaskellLedgerFile.of(state.ext).fold(e => fail(e.toString), identity)
    val raw = HaskellLedgerFile.encode(file).fold(e => fail(e.toString), identity)
    val writeTables = state.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = i.era
        ok {
          for
            store <- MemoryUtxoStore[IO]
            utxo = i.state.nes.es.ls.utxo.utxo.utxo
            _ <- store.write(utxo.toMap.map((key, out) => key -> Some(out)), 0)
            bytes <- IO.ref(Vector.empty[Bytes])
            summary <- Handle
              .allow[SnapshotTables.Error] {
                SnapshotTableStream.writeStore[IO, e](store)(b => bytes.update(_ :+ b))
              }
              .rescue(e => IO.raiseError(new AssertionError(e.toString)))
            chunks <- bytes.get
            _ <- IO.blocking(
              JFiles.write((ledger / "tables").toNioPath, chunks.flatMap(_.toArray).toArray)
            )
          yield summary.crc
        }
      case _ => IO.pure(SnapshotCrc.empty)
    for
      _ <- IO.blocking(JFiles.createDirectories(ledger.toNioPath))
      _ <- IO.blocking(JFiles.write((ledger / "state").toNioPath, raw.toArray))
      crc <- writeTables
      meta = SnapshotMeta
        .encode(SnapshotMeta.fromCrcs(SnapshotCrc.of(raw), crc))
        .fold(e => fail(e.toString), identity)
      _ <- IO.blocking(JFiles.writeString((ledger / "meta").toNioPath, meta))
      _ <- ok {
        NetworkIdentityFile.write[IO](db, setup.identity) *>
          FileImmutableStore
            .open[IO](db / "immutable")
            .use((store, _) => store.append(anchor(state), rawBlock))
      }
    yield (ledger, db, state, raw)

  private def restored(db: Path, state: MirrorState): IO[StateSnapshot[MirrorState]] = ok {
    val format = SnapshotV5.format[IO](SnapshotCheck.Full)
    val path = db / "snapshots" / FileLedgerSnapshots.nameOf(anchor(state))
    for
      bytes <- IO.blocking(JFiles.readAllBytes(path.toNioPath))
      read <- format
        .decode(bytes.dropRight(32))
        .flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))
    yield read
  }

  Era.values.filter(_ != Era.Byron).foreach { era =>
    test(
      s"$era imports its state and tables into unchanged V5, preserving original bytes and immutable input"
    ) {
      tempDir.use { root =>
        for
          input <- source(root, era)
          (ledger, db, state, original) = input
          before <- IO(fingerprint(db / "immutable"))
          report <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
          _ <- IO(assertEquals(report.map(_.evidence), Right(Evidence.Unverified)))
          read <- restored(db, state)
          _ <- IO {
            val content = JFiles
              .readAllBytes(
                (db / "snapshots" / FileLedgerSnapshots.nameOf(anchor(state))).toNioPath
              )
              .dropRight(32)
            assert(content.takeRight(original.size).sameElements(original.toArray))
            val sidecar = SnapshotV5.sidecarOf(content).fold(e => fail(e.message), identity)
            assertEquals(sidecar.gaps, Set.empty)
            assertEquals(fingerprint(db / "immutable"), before)
            assertEquals(read.anchor, Some(anchor(state)))
          }
          entries <- ok(RocksUtxoStore.resource[IO](root / "restored").use { store =>
            store
              .restore(db / "utxo" / "checkpoints" / StateBacking.stem(anchor(state))) *> store.all
          })
          _ <- IO(assertEquals(entries, MirrorStep.utxoOf(state).fold(Map.empty)(_.toMap)))
        yield ()
      }
    }
  }

  private def signed(root: Path, ledger: Path, omitMeta: Boolean = false): IO[(Path, PublicKey32)] =
    IO.blocking {
      val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val key = PublicKey32
        .fromArray(pair.getPublic.getEncoded.takeRight(32))
        .fold(e => fail(e.toString), identity)
      val relative = (root / "source").toNioPath.relativize(ledger.toNioPath).toString
      val data =
        Vector("state", "tables", "meta").filterNot(name => omitMeta && name == "meta").map {
          name =>
            val digest = Bytes.wrap(
              MessageDigest
                .getInstance("SHA-256")
                .digest(JFiles.readAllBytes((ledger / name).toNioPath))
            )
            s"$relative/$name" -> io.circe.Json.fromString(digest.hex)
        }
      val unsigned = io.circe.Json
        .obj("data" -> io.circe.Json.obj(data*), "signature" -> io.circe.Json.fromString("00" * 64))
      val manifest =
        AncillaryManifest.parse(unsigned.noSpaces).fold(e => fail(e.toString), identity)
      val signer = Signature.getInstance("Ed25519")
      signer.initSign(pair.getPrivate)
      signer.update(manifest.hash.toArray)
      val signed = unsigned.mapObject(
        _.add("signature", io.circe.Json.fromString(Bytes.wrap(signer.sign()).hex))
      )
      val path = root / "source" / "ancillary_manifest.json"
      JFiles.writeString(path.toNioPath, signed.noSpaces)
      path -> key
    }

  test("signature and all file digests are required by the verified trust path") {
    tempDir.use { root =>
      for
        input <- source(root, Era.Conway)
        (ledger, db, _, _) = input
        key <- signed(root, ledger)
        report <- attempt(
          LedgerSnapshotImport.run[IO](config(ledger, db, Trust.Verify(key._1, key._2)))
        )
        _ <- IO(assert(report.exists(_.evidence.isInstanceOf[Evidence.Verified])))
      yield ()
    }
  }

  test("metadata failure and destination collision preserve every preexisting database byte") {
    tempDir.use { root =>
      for
        input <- source(root, Era.Conway)
        (ledger, db, _, _) = input
        _ <- IO.blocking(
          JFiles.writeString(
            (ledger / "meta").toNioPath,
            "{\"backend\":\"utxohd-mem\",\"checksum\":0,\"tablesCodecVersion\":1}"
          )
        )
        before <- IO(fingerprint(db))
        bad <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
        _ <- IO {
          assert(bad.left.exists {
            case Error.Metadata(_: MetaError.ChecksumMismatch) => true; case _ => false
          })
          assertEquals(fingerprint(db), before)
        }
        _ <- IO.blocking(JFiles.createDirectories((db / "snapshots").toNioPath))
        collided <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
        _ <- IO {
          assertEquals(collided, Left(Error.Collision("snapshots")))
          assertEquals(fingerprint(db), before)
        }
      yield ()
    }
  }

  test("an owned database refuses import and aliases share the same ownership inode") {
    tempDir.use { root =>
      for
        input <- source(root, Era.Conway)
        (ledger, db, _, _) = input
        before <- IO(fingerprint(db))
        result <- ok(DatabaseOwnership.resource[IO](db).use { _ =>
          attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
        })
        _ <- IO {
          assertEquals(result, Left(Error.Storage(StorageError.DatabaseInUse(db.toString))))
          assertEquals(fingerprint(db), before)
        }
      yield ()
    }
  }

  private def rewriteTables(ledger: Path, entries: Vector[SnapshotTables.Entry]): IO[Unit] =
    IO.blocking {
      val prefix = SnapshotTables.prefix(entries.size.toLong).fold(e => fail(e.toString), identity)
      val bodies =
        entries.map(e => SnapshotTables.encodeEntry(e).fold(e => fail(e.toString), identity))
      val raw = Bytes.wrap(
        (prefix +: bodies :+ SnapshotTables.suffix(entries.size.toLong)).flatMap(_.toArray).toArray
      )
      JFiles.write((ledger / "tables").toNioPath, raw.toArray)
      val state = Bytes.wrap(JFiles.readAllBytes((ledger / "state").toNioPath))
      val meta = SnapshotMeta
        .encode(SnapshotMeta.fromCrcs(SnapshotCrc.of(state), SnapshotCrc.of(raw)))
        .fold(e => fail(e.toString), identity)
      JFiles.writeString((ledger / "meta").toNioPath, meta)
      ()
    }
  private def tableEntries(ledger: Path): Vector[SnapshotTables.Entry] =
    SnapshotTables
      .decode(Bytes.wrap(JFiles.readAllBytes((ledger / "tables").toNioPath)))
      .fold(e => fail(e.toString), _._1)

  test("unsorted unique tables are accepted, and pre-protocol9 duplicates retain the last value") {
    Vector(Era.Babbage, Era.Conway).traverse_ { era =>
      tempDir.use { root =>
        for
          input <- source(root, era)
          (ledger, db, state, _) = input
          entries <- IO(tableEntries(ledger))
          changed =
            if era == Era.Babbage then entries.take(1) ++ entries.reverse else entries.reverse
          _ <- rewriteTables(ledger, changed)
          result <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
          _ <- IO(
            assertEquals(result.map(_.count), Right(MirrorStep.utxoOf(state).fold(0L)(_.count)))
          )
        yield ()
      }
    }
  }

  test("a nonadjacent protocol9 duplicate spanning disk batches is refused before publication") {
    tempDir.use { root =>
      for
        input <- source(root, Era.Conway)
        (ledger, db, _, _) = input
        entries <- IO(tableEntries(ledger))
        first = entries.headOption.getOrElse(fail("missing captured output"))
        padding = Vector.tabulate(1024) { index =>
          val key = java.nio.ByteBuffer.allocate(34).putInt(index + 123456).array()
          first.copy(key = Bytes.wrap(key))
        }
        _ <- rewriteTables(ledger, first +: (padding :+ first))
        before <- IO(fingerprint(db))
        result <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
        _ <- IO {
          assert(result.left.exists { case Error.Duplicate(_) => true; case _ => false })
          assertEquals(fingerprint(db), before)
        }
      yield ()
    }
  }

  test(
    "cancelling a source copy removes staging, preserves database bytes, and releases ownership"
  ) {
    tempDir.use { root =>
      for
        input <- source(root, Era.Conway)
        (ledger, db, _, _) = input
        _ <- IO.blocking {
          val file = java.nio.channels.FileChannel
            .open((ledger / "tables").toNioPath, java.nio.file.StandardOpenOption.WRITE)
          try file.write(java.nio.ByteBuffer.wrap(Array(0.toByte)), 512L * 1024 * 1024 - 1)
          finally file.close()
        }
        before <- IO(fingerprint(db))
        fiber <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db))).start
        _ <- cats.Monad[IO].tailRecM(0) { attempt =>
          IO.blocking {
            val dirs = JFiles.list(root.toNioPath)
            try
              dirs
                .iterator()
                .asScala
                .exists(p =>
                  p.getFileName.toString.startsWith(".db.import-") &&
                    JFiles.exists(p.resolve("source/tables"))
                )
            finally dirs.close()
          }.flatMap {
            case true                     => IO.pure(Right(()))
            case false if attempt < 10000 => IO.sleep(1.millis).as(Left(attempt + 1))
            case _ => IO.raiseError(new AssertionError("source copy did not start"))
          }
        }
        _ <- fiber.cancel
        outcome <- fiber.join
        _ <- IO {
          outcome match
            case Outcome.Canceled() => ()
            case other              => fail(s"expected cancellation: $other")
          assertEquals(fingerprint(db), before)
          val dirs = JFiles.list(root.toNioPath)
          try
            assert(
              !dirs.iterator().asScala.exists(_.getFileName.toString.startsWith(".db.import-"))
            )
          finally dirs.close()
        }
        _ <- ok(DatabaseOwnership.resource[IO](db).use(_ => IO.unit))
      yield ()
    }
  }

  test("verified imports refuse changed consumed bytes and incomplete manifest coverage") {
    Vector(false, true).traverse_ { missing =>
      tempDir.use { root =>
        for
          input <- source(root, Era.Conway)
          (ledger, db, _, _) = input
          key <- signed(root, ledger, omitMeta = missing)
          _ <-
            if missing then IO.unit
            else
              IO.blocking {
                JFiles.writeString((ledger / "meta").toNioPath, "changed after signing")
              }.void
          before <- IO(fingerprint(db))
          result <- attempt(
            LedgerSnapshotImport.run[IO](config(ledger, db, Trust.Verify(key._1, key._2)))
          )
          _ <- IO {
            assert(result.left.exists {
              case Error.Ancillary(_: AncillaryManifest.Error.Unlisted) if missing        => true
              case Error.Ancillary(_: AncillaryManifest.Error.DigestMismatch) if !missing => true
              case _                                                                      => false
            })
            assertEquals(fingerprint(db), before)
          }
        yield ()
      }
    }
  }

  test("database aliases acquire the same ownership inode") {
    tempDir.use { root =>
      val db = root / "db"
      val alias = root / "alias"
      for
        _ <- IO.blocking {
          JFiles.createDirectories(db.toNioPath)
          JFiles.createSymbolicLink(alias.toNioPath, db.toNioPath)
        }
        result <- ok(DatabaseOwnership.resource[IO](db).use { _ =>
          StorageTestKit.attempt(DatabaseOwnership.resource[IO](alias).use(_ => IO.unit))
        })
        _ <- IO(assertEquals(result, Left(StorageError.DatabaseInUse(alias.toString))))
      yield ()
    }
  }

  test(
    "Byron, missing immutable point, and source IO failure leave existing database bytes unchanged"
  ) {
    Vector("byron", "point", "io").traverse_ { failure =>
      tempDir.use { root =>
        for
          input <- source(root, Era.Conway)
          (ledger, db, state, _) = input
          _ <- IO.blocking {
            failure match
              case "byron" =>
                Vector("state", "tables", "meta").foreach { name =>
                  JFiles.copy(
                    JPath.of("fixtures/haskell-state/byron-25", name),
                    (ledger / name).toNioPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                  )
                }
              case "point" =>
                val chunk = anchor(state).slot / FileImmutableStore.DefaultChunkSlots
                val index = (db / "immutable" / f"$chunk%05d.index").toNioPath
                JFiles.write(index, FileImmutableStore.IndexMagic)
              case "io" =>
                JFiles.setPosixFilePermissions(
                  (ledger / "meta").toNioPath,
                  Set.empty[java.nio.file.attribute.PosixFilePermission].asJava
                )
              case other => fail(other)
          }
          before <- IO(fingerprint(db))
          result <- attempt(LedgerSnapshotImport.run[IO](config(ledger, db)))
          _ <- IO {
            assert(
              result.left.exists {
                case Error.Byron if failure == "byron"                            => true
                case Error.Storage(_: StorageError.Missing) if failure == "point" => true
                case Error.Storage(_: StorageError.Io) if failure == "io"         => true
                case _                                                            => false
              },
              result.toString
            )
            assertEquals(fingerprint(db), before)
          }
          _ <- ok(DatabaseOwnership.resource[IO](db).use(_ => IO.unit))
        yield ()
      }
    }
  }
