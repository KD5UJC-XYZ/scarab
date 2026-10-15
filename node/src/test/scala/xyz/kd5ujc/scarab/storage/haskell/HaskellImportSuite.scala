// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import cats.effect.IO
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath, StandardCopyOption}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.{FileImmutableStore, ImmutableStore}
import xyz.kd5ujc.scarab.storage.StorageTestKit.{ok, tempDir}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import HaskellDbKit.*

/** The Haskell ImmutableDB reader and `import-haskell-db`, over three real finished chunks of the
  * Preview database (`fixtures/haskell-immutable/preview`, see its README) and over a
  * Byron-to-Conway database built from `fixtures/eras` by the upstream encoding rules
  * (epoch-boundary blocks have no real Haskell database to test against here).
  */
class HaskellImportSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  private val previewRoot = JPath.of("fixtures/haskell-immutable/preview")
  private val previewSetup =
    NodeSetup
      .fromDirectory(JPath.of("fixtures/preview/genesis"))
      .fold(e => fail(e.message), identity)
  private val previewChunk = ChunkSlots
    .fromSecurityParam(previewSetup.geometry.securityParam.toLong)
    .getOrElse(fail("chunk size"))

  private def sha256(a: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(a).map("%02x".format(_)).mkString

  /** slot, header hash, block number, sha256 of the block bytes, length: taken from the blocks our
    * own ImmutableStore holds for the same slots (the K1 Preview sync), which are byte for byte the
    * Haskell node's.
    */
  private final case class Golden(slot: Long, hash: String, blockNo: Long, sha: String, length: Int)
  private val golden: Vector[Golden] =
    JFiles
      .readAllLines(previewRoot.resolve("expected-blocks.tsv"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(_.split('\t').toList match
        case s :: h :: n :: d :: l :: Nil => Golden(s.toLong, h, n.toLong, d, l.toInt)
        case other                        => fail(s"bad golden line $other"))

  private def fsPath(p: JPath) = Path.fromNioPath(p)

  private def copyDir(from: JPath, to: JPath): Unit =
    val walk = JFiles.walk(from)
    try
      walk.iterator().asScala.foreach { p =>
        val target = to.resolve(from.relativize(p).toString)
        if JFiles.isDirectory(p) then JFiles.createDirectories(target)
        else JFiles.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES)
      }
    finally walk.close()

  private def config(
      haskell: JPath,
      db: JPath,
      setup: NodeSetup,
      chunkSlots: ChunkSlots,
      stop: Option[Long] = None
  ) =
    HaskellImport.Config(
      fsPath(haskell),
      fsPath(db),
      setup.identity,
      setup.geometry.byron.epochSlots,
      chunkSlots,
      stop,
      batch = 100
    )

  private def stored(db: JPath): IO[Vector[ImmutableStore.Stored]] =
    ok {
      FileImmutableStore
        .open[IO](fsPath(db) / "immutable")
        .use((store, _) => store.stream(ChainSync.Point.Origin).compile.toVector)
    }

  // ---- the real Preview chunks ----

  test("the reader lists the chunks and yields exactly the blocks our own store holds") {
    val before = fingerprint(previewRoot)
    attemptRead {
      val reader =
        HaskellImmutableReader[IO](fsPath(previewRoot.resolve("immutable")), previewChunk)
      for
        chunks <- reader.chunks
        blocks <- reader.blocks().compile.toVector
        contents <- reader.contents().compile.toVector
      yield (chunks, blocks, contents)
    }.map {
      case Left(e) => fail(e.message)
      case Right((chunks, blocks, contents)) =>
        assertEquals(chunks.map(_.value), Vector(0, 1, 2))
        assertEquals(blocks.size, golden.size)
        assert(blocks.forall(!_.isEbb), "Preview has no Byron blocks")
        blocks.zip(golden).foreach { (b, g) =>
          assertEquals(b.slot, g.slot)
          assertEquals(b.hash.hex, g.hash)
          assertEquals(b.bytes.size, g.length)
          assertEquals(sha256(b.bytes.toArray), g.sha)
        }
        assertEquals(contents.map(_.mostRecent), Vector(false, false, true))
        assertEquals(contents.flatMap(_.dropped), Vector.empty)
        assertEquals(fingerprint(previewRoot), before, "the source must not be touched")
    }
  }

  test("importing the Preview chunks reproduces our store's blocks and records the network") {
    tempDir.use { dir =>
      val db = dir.toNioPath.resolve("scala-db")
      attempt(
        HaskellImport.run[IO](
          config(previewRoot, db, previewSetup, previewChunk),
          _ => IO.unit
        )
      ).flatMap {
        case Left(e) => IO(fail(e.message))
        case Right(report) =>
          assertEquals(report.appended, golden.size.toLong)
          assertEquals(report.skipped, 0L)
          assertEquals(report.chunks, 3)
          assertEquals(report.tip.map(_.slot), Some(BigInt(golden.last.slot)))
          assertEquals(report.stoppedAtSlot, false)
          stored(db).map { blocks =>
            assertEquals(blocks.size, golden.size)
            blocks.zip(golden).foreach { (s, g) =>
              assertEquals(s.ref.slot, BigInt(g.slot))
              assertEquals(s.ref.hash.hex, g.hash)
              assertEquals(s.ref.blockNo, BigInt(g.blockNo))
              assertEquals(sha256(s.raw.toArray), g.sha)
            }
            assertEquals(
              JFiles.readString(db.resolve("network-identity")),
              previewSetup.identity.render
            )
          }
      }
    }
  }

  test("the import stops at a slot, and a second run continues after the stored blocks") {
    tempDir.use { dir =>
      val db = dir.toNioPath.resolve("scala-db")
      val stop = 5000L
      val first = golden.count(_.slot <= stop).toLong
      for
        r1 <- attempt(
          HaskellImport.run[IO](
            config(previewRoot, db, previewSetup, previewChunk, Some(stop)),
            _ => IO.unit
          )
        )
        _ = assertEquals(r1.map(r => (r.appended, r.stoppedAtSlot)), Right((first, true)))
        r2 <- attempt(
          HaskellImport.run[IO](config(previewRoot, db, previewSetup, previewChunk), _ => IO.unit)
        )
        _ = assertEquals(r2.map(r => (r.skipped, r.appended)), Right((first, golden.size - first)))
        r3 <- attempt(
          HaskellImport.run[IO](config(previewRoot, db, previewSetup, previewChunk), _ => IO.unit)
        )
        _ = assertEquals(r3.map(r => (r.skipped, r.appended)), Right((golden.size.toLong, 0L)))
        blocks <- stored(db)
      yield assertEquals(blocks.map(_.ref.hash.hex), golden.map(_.hash))
    }
  }

  test("a database of another protocol magic is refused before anything is written") {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      copyDir(previewRoot, haskell)
      JFiles.writeString(haskell.resolve("protocolMagicId"), "764824073")
      val db = dir.toNioPath.resolve("scala-db")
      attempt(
        HaskellImport.run[IO](config(haskell, db, previewSetup, previewChunk), _ => IO.unit)
      ).map { r =>
        assertEquals(r.map(_ => ()), Left(HaskellImportError.MagicMismatch(764824073L, 2L)))
        assert(!JFiles.exists(db.resolve("immutable")), "the target must not be created")
      }
    }
  }

  test("a database whose first block names another genesis is refused") {
    tempDir.use { dir =>
      val db = dir.toNioPath.resolve("scala-db")
      val other =
        previewSetup.identity.copy(byronGenesis = Some(Bytes.fromArray(Array.fill(32)(9.toByte))))
      val cfg = config(previewRoot, db, previewSetup, previewChunk).copy(identity = other)
      attempt(HaskellImport.run[IO](cfg, _ => IO.unit)).map {
        case Left(HaskellImportError.ForeignGenesis(found, _)) =>
          assertEquals(found, "no predecessor")
        case other => fail(s"expected ForeignGenesis, got $other")
      }
    }
  }

  test("a corrupt finished chunk is a typed error; the newest chunk is cut back instead") {
    tempDir.use { dir =>
      // The newest chunk (2) lost the end of its last block and has a torn secondary entry.
      val tornNewest = dir.toNioPath.resolve("torn-newest")
      copyDir(previewRoot, tornNewest)
      val chunk2 = tornNewest.resolve("immutable/00002.chunk")
      val data2 = JFiles.readAllBytes(chunk2)
      JFiles.write(chunk2, data2.take(data2.length - 100))
      val sec2 = tornNewest.resolve("immutable/00002.secondary")
      JFiles.write(sec2, JFiles.readAllBytes(sec2) ++ Array.fill(28)(0x55.toByte))
      val dbA = dir.toNioPath.resolve("db-a")
      val before = fingerprint(tornNewest)

      // A finished chunk (1) lost its last bytes.
      val damaged = dir.toNioPath.resolve("damaged")
      copyDir(previewRoot, damaged)
      val chunk1 = damaged.resolve("immutable/00001.chunk")
      val data1 = JFiles.readAllBytes(chunk1)
      JFiles.write(chunk1, data1.take(data1.length - 100))
      val dbB = dir.toNioPath.resolve("db-b")
      for
        a <- attempt(
          HaskellImport.run[IO](config(tornNewest, dbA, previewSetup, previewChunk), _ => IO.unit)
        )
        report = a.fold(e => fail(e.message), identity)
        // Every block of chunks 0 and 1, and those of chunk 2 before the cut.
        inChunks01 = golden.count(_.slot < 2 * 4320L)
        _ = assertEquals(report.appended, golden.size - 1L)
        _ = assert(report.appended > inChunks01)
        _ = assertEquals(report.truncation.map(_._1.value), Some(2))
        _ = assertEquals(report.truncation.map(_._2.entries), Some(1))
        _ = assertEquals(fingerprint(tornNewest), before, "the source must not be repaired")
        b <- attempt(
          HaskellImport.run[IO](config(damaged, dbB, previewSetup, previewChunk), _ => IO.unit)
        )
      yield b match
        case Left(HaskellImportError.Source(HaskellImmutableError.Invalid(c, _))) =>
          assertEquals(c.value, 1)
        case other => fail(s"expected a typed chunk fault, got $other")
    }
  }

  test("a missing chunk in the middle is a typed error") {
    tempDir.use { dir =>
      val gap = dir.toNioPath.resolve("gap")
      copyDir(previewRoot, gap)
      List("chunk", "primary", "secondary").foreach(e =>
        JFiles.delete(gap.resolve(s"immutable/00001.$e"))
      )
      attemptRead(
        HaskellImmutableReader[IO](fsPath(gap.resolve("immutable")), previewChunk)
          .blocks()
          .compile
          .toVector
      ).map { r =>
        assertEquals(
          r.map(_ => ()).left.map(_.message),
          Left(HaskellImmutableError.ChunkGap(ChunkNo.of(1).get, ChunkNo.of(2).get).message)
        )
      }
    }
  }

  // ---- Byron epoch-boundary blocks (built by the upstream encoding rules) ----

  private val erasSetup =
    NodeSetup.fromDirectory(JPath.of("fixtures/eras/genesis")).fold(e => fail(e.message), identity)
  private val erasChunk = ChunkSlots
    .fromSecurityParam(erasSetup.geometry.securityParam.toLong)
    .getOrElse(fail("chunk size"))
  private val erasRaw: Vector[Bytes] =
    JFiles
      .readAllLines(JPath.of("fixtures/eras/blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val erasItems: Vector[Item] = erasRaw.map { raw =>
    val block = Block
      .decode(raw, erasSetup.geometry.byron.epochSlots)
      .fold(e => fail(e.message), identity)
    val h = block.header
    val position =
      if h.isBoundary then BlockPosition.Ebb((h.slot / erasSetup.geometry.byron.epochSlots).toLong)
      else BlockPosition.Regular(h.slot.toLong)
    val at = raw.toArray.indexOfSlice(h.raw.toArray)
    // Byron header hashes are over a wrapper, so only the Shelley-based header slice is recorded.
    val (offset, size) =
      if at >= 0 && h.hash == xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(h.raw) then
        (at, h.raw.size)
      else (0, 0)
    Item(position, raw.toArray, h.hash, offset, size)
  }

  test("a Byron-to-Conway database: the epoch-boundary block is read, flagged and imported") {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      write(haskell, erasSetup.identity.magic, erasChunk, erasItems)
      val db = dir.toNioPath.resolve("scala-db")
      for
        read <- attemptRead(
          HaskellImmutableReader[IO](fsPath(haskell.resolve("immutable")), erasChunk)
            .blocks()
            .compile
            .toVector
        )
        blocks = read.fold(e => fail(e.message), identity)
        _ = assertEquals(blocks.map(_.bytes), erasRaw)
        _ = assert(blocks.head.isEbb, "epoch 0 starts with its boundary block")
        _ = assertEquals(blocks.head.position, BlockPosition.Ebb(0L))
        _ = assertEquals(
          blocks.count(_.isEbb),
          erasItems.count(_.position.isInstanceOf[BlockPosition.Ebb])
        )
        r <- attempt(HaskellImport.run[IO](config(haskell, db, erasSetup, erasChunk), _ => IO.unit))
        report = r.fold(e => fail(e.message), identity)
        _ = assertEquals(report.appended, erasRaw.size.toLong)
        out <- stored(db)
      yield
        assertEquals(out.map(_.raw), erasRaw)
        assert(out.head.ref.boundary)
        assertEquals(out.count(_.ref.boundary), blocks.count(_.isEbb))
    }
  }

  test("a block missing from the source breaks the chain link, typed") {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      write(haskell, erasSetup.identity.magic, erasChunk, erasItems.patch(10, Nil, 1))
      val db = dir.toNioPath.resolve("scala-db")
      attempt(HaskellImport.run[IO](config(haskell, db, erasSetup, erasChunk), _ => IO.unit)).map {
        case Left(HaskellImportError.BrokenLink(_, _, _))      => ()
        case Left(HaskellImportError.BlockNumberJump(_, _, _)) => ()
        case other => fail(s"expected a broken link, got $other")
      }
    }
  }
