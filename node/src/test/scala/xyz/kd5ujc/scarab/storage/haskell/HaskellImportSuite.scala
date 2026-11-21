// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import cats.effect.{IO, Resource}
import cats.mtl.Raise
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, Path as JPath, StandardCopyOption}
import java.security.MessageDigest
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.protocol.{ChainSync, DijkstraNotYet}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.{
  FileImmutableStore,
  ImmutableStore,
  LeiosStoreLocks,
  LeiosStoreSuite,
  RocksLeiosStore
}
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
      .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/preview/genesis"))
      .fold(e => fail(e.message), identity)
  private val previewChunk = ChunkSlots
    .fromSecurityParam(previewSetup.geometry.k.toLong)
    .getOrElse(fail("chunk size"))

  /** One import run, as the command runs it: with an instance of the Leios stores' permits of its
    * own.
    */
  private def importing(config: HaskellImport.Config, progress: HaskellImport.Progress => IO[Unit])(
      using Raise[IO, HaskellImportError]
  ): IO[HaskellImport.Report] =
    LeiosStoreLocks.make[IO].flatMap(HaskellImport.run[IO](config, _, progress))

  private def leiosStore(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => LeiosStoreSuite.allowing(RocksLeiosStore.resource[IO](dir, locks)))

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

  /** What our store's index gives for each block the reference's index holds, and for another hash
    * at each of their slots: the reference answers a point's slot, hash and whether it is a
    * boundary block from its primary and secondary indexes alone (`getBlockComponentImpl`,
    * `ImmutableDB/Impl.hs` 447-496, ouroboros-consensus 82ecba32), and a point its index does not
    * hold is missing.
    */
  private def foundInIndex(
      db: JPath,
      blocks: Vector[HaskellBlock]
  ): IO[(Vector[Option[BlockRef]], Vector[Option[BlockRef]])] =
    def at(slot: Long, hash: Bytes) =
      new ChainSync.Point.Block(ChainSync.UInt64.saturating(BigInt(slot)), hash)
    val other = Bytes(Vector.fill(32)(0x5a.toByte))
    ok {
      FileImmutableStore
        .open[IO](fsPath(db) / "immutable")
        .use((store, _) =>
          (
            blocks.traverse(b => store.refOf(at(b.slot, b.hash))),
            blocks.traverse(b => store.refOf(at(b.slot, other)))
          ).tupled
        )
    }

  /** The reference index's answer for each block: its slot, hash and whether it is a boundary
    * block.
    */
  private def indexed(blocks: Vector[HaskellBlock]): Vector[Option[(BigInt, Bytes, Boolean)]] =
    blocks.map(b => Some((BigInt(b.slot), b.hash, b.isEbb)))

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
        importing(
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

  test(
    "each block of the Preview index is found in our store's index as the reference's gives it"
  ) {
    tempDir.use { dir =>
      val db = dir.toNioPath.resolve("scala-db")
      for
        read <- attemptRead(
          HaskellImmutableReader[IO](fsPath(previewRoot.resolve("immutable")), previewChunk)
            .blocks()
            .compile
            .toVector
        )
        blocks = read.fold(e => fail(e.message), identity)
        imported <- attempt(
          importing(config(previewRoot, db, previewSetup, previewChunk), _ => IO.unit)
        )
        _ = imported.fold(e => fail(e.message), identity)
        found <- foundInIndex(db, blocks)
        (refs, others) = found
      yield
        assertEquals(blocks.size, golden.size)
        assertEquals(refs.map(_.map(r => (r.slot, r.hash, r.boundary))), indexed(blocks))
        assertEquals(refs.map(_.map(_.blockNo)), golden.map(g => Some(BigInt(g.blockNo))))
        assertEquals(others.flatten, Vector.empty[BlockRef])
    }
  }

  test("the import stops at a slot, and a second run continues after the stored blocks") {
    tempDir.use { dir =>
      val db = dir.toNioPath.resolve("scala-db")
      val stop = 5000L
      val first = golden.count(_.slot <= stop).toLong
      for
        r1 <- attempt(
          importing(
            config(previewRoot, db, previewSetup, previewChunk, Some(stop)),
            _ => IO.unit
          )
        )
        _ = assertEquals(r1.map(r => (r.appended, r.stoppedAtSlot)), Right((first, true)))
        r2 <- attempt(
          importing(config(previewRoot, db, previewSetup, previewChunk), _ => IO.unit)
        )
        _ = assertEquals(r2.map(r => (r.skipped, r.appended)), Right((first, golden.size - first)))
        r3 <- attempt(
          importing(config(previewRoot, db, previewSetup, previewChunk), _ => IO.unit)
        )
        _ = assertEquals(r3.map(r => (r.skipped, r.appended)), Right((golden.size.toLong, 0L)))
        blocks <- stored(db)
      yield assertEquals(blocks.map(_.ref.hash.hex), golden.map(_.hash))
    }
  }

  test("a Leios node's endorser-block database is read into the Leios store beside the blocks") {
    import xyz.kd5ujc.scarab.storage.leios.LeiosReferenceDb
    import xyz.kd5ujc.scarab.storage.LeiosStoreSuite.{body, hash, ok as leiosOk, point, tx, word32}
    import xyz.kd5ujc.scarab.storage.leios.LeiosReferenceDbSuite.exported
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      copyDir(previewRoot, haskell)
      val db = dir.toNioPath.resolve("scala-db")
      for
        _ <- leiosStore(dir / "source").use { s =>
          leiosOk(
            s.writeEbPoint(point(7, 1), word32(1)) *> s.writeEbBody(
              point(7, 1),
              body(2),
              Vector.empty
            ) *>
              s.writeTxs(point(7, 1), Vector(0 -> tx(0), 1 -> tx(1)))
          ) *> exported(s, fsPath(haskell))
        }
        report <- attempt(
          importing(config(haskell, db, previewSetup, previewChunk), _ => IO.unit)
        )
        closure <- leiosStore(fsPath(db.resolve("leios"))).use(s =>
          leiosOk(s.lookupEbClosure(hash(1)))
        )
        plain <- attempt(
          importing(
            config(previewRoot, dir.toNioPath.resolve("plain"), previewSetup, previewChunk),
            _ => IO.unit
          )
        )
      yield
        assertEquals(report.map(_.leios), Right(Some(LeiosReferenceDb.Counts(1, 1, 2))))
        assertEquals(report.map(_.appended), Right(golden.size.toLong))
        assertEquals(closure.map(_.map(_._2)), Some(Vector(tx(0), tx(1))))
        assertEquals(plain.map(_.leios), Right(None))
        assert(
          !JFiles.exists(dir.toNioPath.resolve("plain").resolve("leios")),
          "no Leios database, no store"
        )
    }
  }

  test("a database of another protocol magic is refused before anything is written") {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      copyDir(previewRoot, haskell)
      JFiles.writeString(haskell.resolve("protocolMagicId"), "764824073")
      val db = dir.toNioPath.resolve("scala-db")
      attempt(
        importing(config(haskell, db, previewSetup, previewChunk), _ => IO.unit)
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
      attempt(importing(cfg, _ => IO.unit)).map {
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
          importing(config(tornNewest, dbA, previewSetup, previewChunk), _ => IO.unit)
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
          importing(config(damaged, dbB, previewSetup, previewChunk), _ => IO.unit)
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
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), identity)
  private val erasChunk = ChunkSlots
    .fromSecurityParam(erasSetup.geometry.k.toLong)
    .getOrElse(fail("chunk size"))
  private val erasRaw: Vector[Bytes] =
    JFiles
      .readAllLines(JPath.of("fixtures/eras/blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))

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
        r <- attempt(importing(config(haskell, db, erasSetup, erasChunk), _ => IO.unit))
        report = r.fold(e => fail(e.message), identity)
        _ = assertEquals(report.appended, erasRaw.size.toLong)
        out <- stored(db)
        found <- foundInIndex(db, blocks)
        (refs, others) = found
      yield
        assertEquals(out.map(_.raw), erasRaw)
        assert(out.head.ref.boundary)
        assertEquals(out.count(_.ref.boundary), blocks.count(_.isEbb))
        // a boundary block and the main block of its slot are each found as the reference's
        // index tells them apart
        assertEquals(refs.map(_.map(r => (r.slot, r.hash, r.boundary))), indexed(blocks))
        assertEquals(others.flatten, Vector.empty[BlockRef])
    }
  }

  test("a Dijkstra block is refused before it is stored under a release build") {
    val row = JFiles
      .readAllLines(JPath.of("fixtures/dijkstra-devnet/blocks.tsv"))
      .asScala
      .drop(1)
      .map(_.split('\t'))
      .find(_(0) == "8")
      .getOrElse(fail("the devnet fixture holds a Dijkstra block"))
    val raw = Bytes.fromHex(row(4)).fold(e => fail(e.message), identity)
    val h = Block
      .decode(raw, erasSetup.geometry.byron.epochSlots)
      .fold(e => fail(e.message), identity)
      .header
    val item = Item(
      BlockPosition.Regular(h.slot.toLong),
      raw.toArray,
      h.hash,
      raw.toArray.indexOfSlice(h.raw.toArray),
      h.raw.size
    )
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      write(haskell, erasSetup.identity.magic, erasChunk, Vector(item))
      val db = dir.toNioPath.resolve("scala-db")
      attempt(importing(config(haskell, db, erasSetup, erasChunk), _ => IO.unit))
        .flatMap { r =>
          assertEquals(
            r.map(_ => ()),
            Left(HaskellImportError.EraNotYet(h.slot.toLong, DijkstraNotYet.Rules))
          )
          stored(db).map(blocks => assertEquals(blocks, Vector.empty))
        }
    }
  }

  private val dijkstraSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.LeiosB004fe0c)(JPath.of("fixtures/dijkstra-devnet/genesis"))
      .fold(e => fail(e.message), identity)
  private val dijkstraChunk = ChunkSlots
    .fromSecurityParam(dijkstraSetup.geometry.k.toLong)
    .getOrElse(fail("chunk size"))

  /** The devnet chain of `fixtures/dijkstra-devnet`: Conway blocks, then Dijkstra blocks. */
  private val dijkstraRaw: Vector[Bytes] =
    JFiles
      .readAllLines(JPath.of("fixtures/dijkstra-devnet/blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .map(_.split('\t')(4))
      .map(hex => Bytes.fromHex(hex).fold(e => fail(e.message), identity))

  test(
    "under leios-b004fe0c a chain into Dijkstra is stored whole, its Dijkstra blocks checked and kept as they are"
  ) {
    val items = dijkstraRaw.map { raw =>
      val h = Block
        .decode(raw, dijkstraSetup.geometry.byron.epochSlots)
        .fold(e => fail(e.message), identity)
        .header
      Item(
        BlockPosition.Regular(h.slot.toLong),
        raw.toArray,
        h.hash,
        raw.toArray.indexOfSlice(h.raw.toArray),
        h.raw.size
      )
    }
    val dijkstra = dijkstraRaw.count(raw =>
      Block
        .decode(raw, dijkstraSetup.geometry.byron.epochSlots)
        .exists(_.isInstanceOf[Block.Dijkstra])
    )
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      write(haskell, dijkstraSetup.identity.magic, dijkstraChunk, items)
      val db = dir.toNioPath.resolve("scala-db")
      val leios = config(haskell, db, dijkstraSetup, dijkstraChunk)
        .copy(build = ReferenceBuild.LeiosB004fe0c)
      for
        r <- attempt(importing(leios, _ => IO.unit))
        report = r.fold(e => fail(e.message), identity)
        out <- stored(db)
      yield
        assert(dijkstra > 1, "the devnet chain enters Dijkstra")
        assertEquals(report.appended, dijkstraRaw.size.toLong)
        assertEquals(out.map(_.raw), dijkstraRaw)
    }
  }

  test("only the Leios testnet's build stores Dijkstra blocks") {
    assertEquals(
      ReferenceBuild.values.toVector.filter(HaskellImport.storesDijkstra),
      Vector(ReferenceBuild.LeiosB004fe0c)
    )
  }

  test("a block missing from the source breaks the chain link, typed") {
    tempDir.use { dir =>
      val haskell = dir.toNioPath.resolve("haskell")
      write(haskell, erasSetup.identity.magic, erasChunk, erasItems.patch(10, Nil, 1))
      val db = dir.toNioPath.resolve("scala-db")
      attempt(importing(config(haskell, db, erasSetup, erasChunk), _ => IO.unit)).map {
        case Left(HaskellImportError.BrokenLink(_, _, _))      => ()
        case Left(HaskellImportError.BlockNumberJump(_, _, _)) => ()
        case other => fail(s"expected a broken link, got $other")
      }
    }
  }
