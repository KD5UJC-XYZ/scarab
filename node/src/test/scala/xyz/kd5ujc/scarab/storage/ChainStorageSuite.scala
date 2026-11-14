// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
import xyz.kd5ujc.scarab.node.WorkerTesting
import scala.annotation.unused
import cats.effect.{Deferred, IO, Outcome, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Raise, Stateful}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.FollowerEvents
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{Block, CheckpointProblem, FileKind, FormatVersion}
import xyz.kd5ujc.scarab.ledger.{IdentityProvenance, StoredNetworkIdentity}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.NetworkIdentity
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.ledger.{ChainError, PeerError, StorageError}
import xyz.kd5ujc.scarab.node.{Fatal, Follower, Main}
import xyz.kd5ujc.scarab.node.log.NodeEvent
import xyz.kd5ujc.scarab.consensus.BlockRules
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  BlockRule,
  LedgerFailure,
  ProviderDefect,
  Redeemer,
  ScriptLocation,
  ValidationError,
  VerificationDefect
}
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ExUnits}
import xyz.kd5ujc.scarab.node.{MirrorRules, MirrorSteps}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.node.SyncEvent
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import StorageTestKit.*

/** Restart behaviour over the committed Byron-to-Conway capture (`fixtures/eras`): the follower
  * writes through [[ChainStorage]], a fresh open recovers the same state from the newest snapshot
  * plus a replay of the stored blocks, and recovery survives lost or damaged snapshots.
  */
class ChainStorageSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: Duration = 3.minutes

  private val fixtures = java.nio.file.Path.of("fixtures/eras")
  private val files = Files.forAsync[IO]

  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(fixtures.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private val geometry = setup.geometry
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private given SnapshotFormat[IO, MirrorState] =
    SnapshotV11.format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)
  private val k = geometry.k.toInt

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
  private val headers: Vector[Header] =
    raws.map(Block.decode(_, geometry.byron.epochSlots).fold(e => fail(e.message), _.header))
  private def ref(i: Int) =
    BlockRef(headers(i).slot, headers(i).hash, headers(i).blockNo, headers(i).isBoundary)

  /** State after the first `n` blocks, computed without any storage. */
  private lazy val states: Vector[MirrorState] =
    headers.indices.foldLeft(Vector(setup.mirrorStart)) { (acc, i) =>
      MirrorSteps.block(setup, acc.last, headers(i), raws(i)) match
        case Right(next) => acc :+ next
        case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
    }

  private val noEvents: FollowerEvents[IO] = _ => IO.unit

  private def storageEvents(log: Ref[IO, Vector[StorageEvent]]): StorageEvents[IO] =
    e => log.update(_ :+ e)

  private def config(every: Int, keep: Int = 4) =
    ChainStorage.Config(
      snapshotPolicy = SnapshotPolicy.EverySlots(every),
      keepSnapshots = keep,
      chunkSlots = 100,
      byronEpochSlots = geometry.byron.epochSlots,
      progressEvery = 100
    )

  @unused private def rules(using Handle[IO, NodeError]) =
    MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      xyz.kd5ujc.scarab.plutus.PlutusBackends.backend
    )

  /** Open the storage, follow blocks `[from, until)` with the real follower, wait for snapshots. */
  private def run(
      dir: Path,
      until: Int,
      every: Int = 120,
      log: Ref[IO, Vector[StorageEvent]]
  ): IO[(Option[BlockRef], VolatileChain[MirrorState])] =
    runWith(dir, until, every, log, None).map((anchor, chain, _) => (anchor, chain))

  /** A peer that announces the blocks up to `at` (exclusive), takes the last `back` of them back
    * and announces them again.
    */
  private final case class Rollback(at: Int, back: Int)

  /** As [[run]], with the UTxO set in a store (`memory` or `rocksdb`) under the database directory;
    * the last component is the complete UTxO set at the end.
    */
  private def runWith(
      dir: Path,
      until: Int,
      every: Int,
      log: Ref[IO, Vector[StorageEvent]],
      store: Option[String],
      keep: Int = 4,
      migrate: Boolean = false,
      rollback: Option[Rollback] = None
  ): IO[
    (
        Option[BlockRef],
        VolatileChain[MirrorState],
        Map[xyz.kd5ujc.scarab.ledger.TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
    )
  ] =
    Handle
      .allow[NodeError] {
        val backed: Resource[IO, (Option[UtxoStore[IO]], StateBacking[IO, MirrorState])] =
          store match
            case None => Resource.pure((None, StateBacking.none[IO, MirrorState]))
            case Some("memory") =>
              Resource.eval(MemoryUtxoStore[IO]).map { s =>
                (
                  Some(s: UtxoStore[IO]),
                  new UtxoBacking[IO, MirrorState](s, dir / "utxo" / "checkpoints")
                )
              }
            case Some(_) =>
              RocksUtxoStore.resource[IO](dir / "utxo" / "live", 8L * 1024 * 1024).map { s =>
                (
                  Some(s: UtxoStore[IO]),
                  new UtxoBacking[IO, MirrorState](s, dir / "utxo" / "checkpoints")
                )
              }
        backed
          .use { (utxoStore, backing) =>
            val rules =
              MirrorRules[IO](
                setup.praos,
                setup.globals,
                setup.byronRules,
                setup.handOver,
                setup.byronHistory,
                xyz.kd5ujc.scarab.plutus.PlutusBackends.backend,
                utxoStore = utxoStore
              )
            ChainStorage
              .open[IO, MirrorState](
                dir,
                config(every, keep),
                rules,
                setup.mirrorStart,
                storageEvents(log),
                backing,
                _ => IO.unit,
                snapshotMigration = Option.when(migrate)(SnapshotMigration.toCurrent[IO])
              )
              .use { recovered =>
                for
                  chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
                    VolatileChain(
                      k,
                      Anchor(recovered.anchor, recovered.state),
                      Vector.empty,
                      setup.origin
                    )
                  )
                  given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
                  queue <- Queue.unbounded[IO, SyncEvent]
                  resumeFrom = recovered.anchor
                    .fold(0)(a => headers.indexWhere(_.hash == a.hash) + 1)
                  forward = (from: Int, to: Int) =>
                    (from until to).toList.traverse_(i =>
                      queue.offer(SyncEvent.RollForward(headers(i), ChainSync.Tip.Origin))
                    )
                  _ <- rollback match
                    case None                     => forward(resumeFrom, until)
                    case Some(Rollback(at, back)) =>
                      // the peer announces blocks up to `at`, takes the last `back` of them
                      // back, and announces them again
                      forward(resumeFrom, at) *>
                        queue.offer(
                          SyncEvent.RollBackward(headers(at - back - 1).point, ChainSync.Tip.Origin)
                        ) *> forward(at - back, until)
                  done <- Deferred[IO, Unit]
                  offered <- Ref.of[IO, Vector[Vector[ChainSync.Point]]](Vector.empty)
                  bodies = headers.map(_.hash).zip(raws).toMap
                  peer = new WorkerTesting.Scripted[IO]:
                    def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
                      offered.update(_ :+ candidates).as(candidates.headOption)
                    def next(using Raise[IO, PeerError]) = queue.tryTake.flatMap {
                      case Some(event) => IO.pure(event)
                      case None        => done.complete(()) *> IO.never[SyncEvent]
                    }
                    def fetch(header: Header)(using Raise[IO, PeerError]) =
                      IO.pure(bodies(header.hash))
                  follower <- Follower.create[IO, MirrorState](
                    rules,
                    noEvents,
                    geometry,
                    persistence = Some(recovered.persistence)
                  )
                  _ <- xyz.kd5ujc.scarab.node.WorkerTesting
                    .pool(follower)
                    .flatMap(blocks => WorkerTesting.follow(follower, blocks, peer, done.get))
                    .timeout(60.seconds)
                  // snapshots are written in the background: they land before the state is read
                  _ <- recovered.snapshotsWritten.timeout(60.seconds)
                  chain <- chainRef.get
                  all <- utxoStore.fold(IO.pure(chain.state.utxo.toMap))(s =>
                    UtxoStore.materialize(s, chain.state.utxo)
                  )
                yield (recovered.anchor, chain.copy(blocks = chain.blocks), all)
              }
          }
          .map(_.asRight[NodeError])
      }
      .rescue(e => IO.pure(e.asLeft))
      .flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  private def events(@unused dir: Path): IO[Ref[IO, Vector[StorageEvent]]] =
    Ref.of[IO, Vector[StorageEvent]](Vector.empty)

  private def snapshotsIn(dir: Path): IO[List[Path]] =
    files
      .list(dir / "snapshots")
      .filter(_.fileName.toString.endsWith(".bin"))
      .compile
      .toList
      .map(_.sortBy(_.toString).reverse)

  test("restart: snapshot plus replay recovers the state of the stored chain") {
    tempDir.use { dir =>
      for
        log1 <- events(dir)
        r_chain1 <- run(dir, 500, log = log1)
        chain1 = r_chain1._2
        _ <- IO(assertState(chain1.state, states(500)))
        snaps <- snapshotsIn(dir)
        _ <- IO(assert(snaps.nonEmpty, "snapshots were written"))
        written <- log1.get.map(_.collect { case e: StorageEvent.SnapshotWritten => e })
        _ <- IO(assert(written.nonEmpty))
        log2 <- events(dir)
        r_chain2 <- run(dir, 731, log = log2)
        anchor = r_chain2._1
        chain2 = r_chain2._2
        e2 <- log2.get
        // The second run restored a snapshot and replayed only the blocks after it.
        _ <- IO(assert(e2.exists(_.isInstanceOf[StorageEvent.SnapshotRestored]), e2.toString))
        replayed = e2.collectFirst { case StorageEvent.ReplayFinished(_, n, _) => n }.getOrElse(-1L)
        _ <- IO(assert(replayed >= 0 && replayed < 500 - k, s"replayed $replayed blocks"))
        // The immutable tip after run 1 is block 500 - k - 1; the restart anchors exactly there.
        _ <- IO(assertEquals(anchor.map(_.hash), Some(ref(500 - k - 1).hash)))
        _ <- IO(assertState(chain2.state, states(731)))
        _ <- IO(assertEquals(chain2.tip, headers.last.point))
      yield ()
    }
  }

  test("restart without any snapshot replays the stored blocks from genesis") {
    tempDir.use { dir =>
      for
        log1 <- events(dir)
        _ <- run(dir, 300, log = log1)
        _ <- snapshotsIn(dir).flatMap(_.traverse_(files.delete))
        log2 <- events(dir)
        r_chain <- run(dir, 400, log = log2)
        chain = r_chain._2
        e2 <- log2.get
        _ <- IO(assert(!e2.exists(_.isInstanceOf[StorageEvent.SnapshotRestored])))
        _ <- IO(
          assertEquals(
            e2.collectFirst { case StorageEvent.ReplayFinished(_, n, _) => n },
            Some((300 - k).toLong)
          )
        )
        _ <- IO(assertState(chain.state, states(400)))
      yield ()
    }
  }

  test("unavailable replay reports no block rejection and the stored chain remains recoverable") {
    tempDir.use { dir =>
      for
        log <- events(dir)
        _ <- run(dir, 300, log = log)
        _ <- snapshotsIn(dir).flatMap(_.traverse_(files.delete))
        _ <- List(
          ScriptLocation.Phase1(Bytes.empty),
          ScriptLocation.Phase2(Redeemer(0, 0, Bytes.empty, ExUnits.zero))
        ).traverse_ { location =>
          Handle
            .allow[NodeError] {
              val unavailable: BlockRules[IO, MirrorState] = BlockRules.from { (_, header, _) =>
                Raise[IO, ValidationError].raise[ValidationError, MirrorState](
                  ValidationError(
                    header.point,
                    BlockFailure.Tx(
                      LedgerFailure.Unavailable(
                        Bytes.empty,
                        location,
                        EvalFailure.Unsupported(xyz.kd5ujc.scarab.plutus.BackendFailure.NoResult)
                      )
                    )
                  )
                )
              }
              ChainStorage
                .open[IO, MirrorState](
                  dir,
                  config(120),
                  unavailable,
                  setup.mirrorStart,
                  storageEvents(log),
                  StateBacking.none[IO, MirrorState],
                  _ => IO.unit
                )
                .use(_ => IO[Unit](fail("replay must stop without an evaluator verdict")))
            }
            .rescue { error =>
              val (event, code) = Main.startupFailure(error)
              IO {
                assertEquals(code.code, 8)
                event match
                  case NodeEvent.Faulted(Fatal.Unavailable(unavailable)) =>
                    assertEquals(unavailable.failure.location, location)
                  case other => fail(s"replay emitted a verdict instead of unavailable: $other")
              }
            }
        }
        recovered <- run(dir, 400, log = log)
        _ <- IO(assertState(recovered._2.state, states(400)))
      yield ()
    }
  }

  test("restart with the newest snapshot damaged falls back to an older one") {
    tempDir.use { dir =>
      for
        log1 <- events(dir)
        _ <- run(dir, 600, every = 60, log = log1)
        snaps <- snapshotsIn(dir)
        _ <- IO(assert(snaps.size >= 2, s"need two snapshots, have ${snaps.size}"))
        // Truncate the newest in the middle (a crash during a non-atomic write cannot happen, but
        // disk corruption can), and flip a byte in the second newest's tail.
        newest = snaps.head
        bytes <- files.readAll(newest).compile.to(Array)
        _ <- fs2.Stream
          .emits(bytes.take(bytes.length / 3).toSeq)
          .through(files.writeAll(newest))
          .compile
          .drain
        log2 <- events(dir)
        r_chain <- run(dir, 650, log = log2)
        chain = r_chain._2
        e2 <- log2.get
        skipped = e2.collect { case e: StorageEvent.SnapshotSkipped => e }
        _ <- IO(assertEquals(skipped.map(_.name).toList, List(newest.fileName.toString)))
        _ <- IO(assert(skipped.head.error.isInstanceOf[StorageError.Corrupt[?]]))
        restored = e2.collect { case e: StorageEvent.SnapshotRestored => e.name }
        _ <- IO(assertEquals(restored.toList, List(snaps(1).fileName.toString)))
        _ <- IO(assertState(chain.state, states(650)))
      yield ()
    }
  }

  test("restart after a crash that tore the newest chunk keeps a consistent prefix") {
    tempDir.use { dir =>
      for
        log1 <- events(dir)
        _ <- run(dir, 400, log = log1)
        chunks <- files
          .list(dir / "immutable")
          .filter(_.fileName.toString.endsWith(".chunk"))
          .compile
          .toList
          .map(_.sortBy(_.toString))
        last = chunks.last
        size <- files.size(last)
        // Lose the last 100 bytes of the newest chunk, as if the OS never wrote them.
        _ <- files.open(last, fs2.io.file.Flags(fs2.io.file.Flag.Write)).use(_.truncate(size - 100))
        log2 <- events(dir)
        r_chain <- run(dir, 500, log = log2)
        chain = r_chain._2
        e2 <- log2.get
        opened = e2.collectFirst { case StorageEvent.ImmutableOpened(r) => r }.get
        _ <- IO(assert(opened.truncatedEntries >= 1, opened.toString))
        _ <- IO(assertState(chain.state, states(500)))
      yield ()
    }
  }

  /** The chain's state replayed from the blocks and the state a snapshot returns are the same
    * state, up to the UTxO set's bookkeeping (what was flushed, the diff), which the checkpoint and
    * the store keep apart (see [[sameLedger]]).
    */
  private def assertState(got: MirrorState, want: MirrorState, clue: => Any = "state"): Unit =
    assertEquals(
      MirrorSteps.firstDifference(
        MirrorStep.mapUtxo(got)(_ => xyz.kd5ujc.scarab.ledger.Utxo.empty),
        MirrorStep.mapUtxo(want)(_ => xyz.kd5ujc.scarab.ledger.Utxo.empty)
      ),
      None,
      clue
    )
    assertEquals(got.utxo.stake, want.utxo.stake, clue)
    assertEquals(got.utxo.coin, want.utxo.coin, clue)
    assertEquals(got.utxo.count, want.utxo.count, clue)

  private def sameLedger(x: MirrorState, y: MirrorState): Unit = assertState(x, y)

  for kind <- List("memory", "rocksdb") do
    test(s"restart with the UTxO set in a $kind store: snapshot, checkpoint and replay agree") {
      tempDir.use { dir =>
        for
          log1 <- events(dir)
          first <- runWith(dir, 500, 120, log1, Some(kind))
          _ <- IO(sameLedger(first._2.state, states(500)))
          _ <- IO(assertEquals(first._3, states(500).utxo.toMap))
          checkpoints <- files
            .list(dir / "utxo" / "checkpoints")
            .compile
            .toList
          _ <- IO(assert(checkpoints.nonEmpty, "a checkpoint goes with every snapshot"))
          log2 <- events(dir)
          second <- runWith(dir, 731, 120, log2, Some(kind))
          e2 <- log2.get
          _ <- IO(assert(e2.exists(_.isInstanceOf[StorageEvent.SnapshotRestored]), e2.toString))
          _ <- IO(sameLedger(second._2.state, states(731)))
          _ <- IO(assertEquals(second._3, states(731).utxo.toMap))
        yield ()
      }
    }

  test("a snapshot whose UTxO checkpoint is missing stops the start, and nothing is deleted") {
    // the reference's missing tables file raises from readIncremental (82ecba32 Util/CBOR.hs:206),
    // outside the failures tryNewestFirst falls back on: a briefly absent mount never deletes state
    tempDir.use { dir =>
      for
        log1 <- events(dir)
        _ <- runWith(dir, 600, 60, log1, Some("rocksdb"))
        snaps <- snapshotsIn(dir)
        _ <- IO(assert(snaps.size >= 2, s"need two snapshots, have ${snaps.size}"))
        newest = snaps.head.fileName.toString.stripSuffix(".bin")
        _ <- IO(
          RocksUtxoStore.deleteTree(
            (dir / "utxo" / "checkpoints" / newest).toNioPath
          )
        )
        log2 <- events(dir)
        again <- runWith(dir, 650, 120, log2, Some("rocksdb")).attempt
        after <- snapshotsIn(dir)
        e2 <- log2.get
        _ <- IO {
          assert(
            again.left.exists(_.getMessage.contains("checkpoint directory does not exist")),
            again.toString
          )
          assertEquals(after, snaps)
          assert(!e2.exists(_.isInstanceOf[StorageEvent.SnapshotDeleted]))
        }
      yield ()
    }
  }

  test("a migrated snapshot whose checkpoint mixes key layouts is deleted at its restore") {
    // its Legacy binding admits it by its directory; the keys refuse at the restore, which the
    // fallback treats as tables that do not decode (82ecba32 API.hs:625-631)
    tempDir.use { dir =>
      for
        left <- checkpointDatabase(dir, CheckpointLayout.BeforeK6, 600, 60, 4, 1, previous = true)
        _ <- IO(assert(left.size >= 2, s"snapshots left: ${left.size}"))
        newest = FileLedgerSnapshots.nameOf(left.head)
        _ <- IO {
          org.rocksdb.RocksDB.loadLibrary()
          val options = new org.rocksdb.Options()
          val db = org.rocksdb.RocksDB.open(options, checkpointOf(dir, newest).toNioPath.toString)
          try db.put(Array.fill[Byte](34)(7), Array[Byte](1))
          finally
            db.close()
            options.close()
        }
        log <- events(dir)
        _ <- runWith(dir, 731, 120, log, Some("rocksdb"), 4, migrate = true)
        seen <- log.get
        snapshotLeft <- IO.blocking(JFiles.exists((dir / "snapshots" / newest).toNioPath))
        checkpointLeft <- IO.blocking(JFiles.exists(checkpointOf(dir, newest).toNioPath))
      yield
        val skipped = seen.collect { case StorageEvent.SnapshotSkipped(name, e) => name -> e }
        assertEquals(skipped.map(_._1).toList, List(newest))
        skipped.headOption.map(_._2) match
          case Some(StorageError.UtxoCheckpoint(_, CheckpointProblem.MixedKeyLayouts)) => ()
          case other => fail(s"expected mixed key layouts, got $other")
        assertEquals(
          seen.collect { case StorageEvent.SnapshotDeleted(name) => name }.toList,
          List(newest)
        )
        assertEquals(
          seen.collect { case StorageEvent.SnapshotRestored(_, a) => a }.toList,
          List(left.lift(1))
        )
        assert(!snapshotLeft && !checkpointLeft)
    }
  }

  /** `inside` run while the database in `dir` is open over RocksDB, before any block is followed.
    */
  private def whileOpen[A](dir: Path, log: Ref[IO, Vector[StorageEvent]])(inside: IO[A]): IO[A] =
    Handle
      .allow[NodeError] {
        RocksUtxoStore
          .resource[IO](dir / "utxo" / "live", 8L * 1024 * 1024)
          .use { store =>
            ChainStorage
              .open[IO, MirrorState](
                dir,
                config(120, 4),
                MirrorRules[IO](
                  setup.praos,
                  setup.globals,
                  setup.byronRules,
                  setup.handOver,
                  setup.byronHistory,
                  xyz.kd5ujc.scarab.plutus.PlutusBackends.backend,
                  utxoStore = Some(store)
                ),
                setup.mirrorStart,
                storageEvents(log),
                new UtxoBacking[IO, MirrorState](store, dir / "utxo" / "checkpoints"),
                _ => IO.unit
              )
              .use(_ => inside)
          }
          .map(_.asRight[NodeError])
      }
      .rescue(e => IO.pure(e.asLeft))
      .flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  /** What a start stopped between deleting a snapshot and trimming its checkpoint leaves. */
  private def orphanOf(dir: Path): Path =
    dir / "utxo" / "checkpoints" / "snapshot-00000000000000000001-1"

  test("a checkpoint no snapshot lists is removed when the database opens") {
    tempDir.use { dir =>
      val orphan = orphanOf(dir)
      for
        log1 <- events(dir)
        _ <- runWith(dir, 600, 60, log1, Some("rocksdb"))
        _ <- IO.blocking(JFiles.createDirectories(orphan.toNioPath))
        log2 <- events(dir)
        // before any block is followed or any snapshot written
        left <- whileOpen(dir, log2)(IO.blocking(JFiles.exists(orphan.toNioPath)))
      yield assert(!left, s"$orphan is still there once the database is open")
    }
  }

  test("a checkpoint that cannot be deleted at the start is reported and the start goes on") {
    // the reference's deletion of a temporary snapshot directory, tables included, is best effort
    // (82ecba32 Snapshots.hs:316-330)
    tempDir.use { dir =>
      val orphan = orphanOf(dir)
      val locked = java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")
      val open = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
      for
        log1 <- events(dir)
        _ <- runWith(dir, 600, 60, log1, Some("rocksdb"))
        snaps <- snapshotsIn(dir)
        _ <- IO.blocking {
          JFiles.createDirectories(orphan.toNioPath)
          JFiles.write((orphan / "CURRENT").toNioPath, Array[Byte](1))
          JFiles.setPosixFilePermissions(orphan.toNioPath, locked)
        }
        log2 <- events(dir)
        restoredWhileOpen <- whileOpen(dir, log2)(
          log2.get.map(_.collect { case StorageEvent.SnapshotRestored(name, _) => name })
        ).guarantee(IO.blocking(JFiles.setPosixFilePermissions(orphan.toNioPath, open)).void)
        seen <- log2.get
        left <- IO.blocking(JFiles.exists(orphan.toNioPath))
      yield
        assertEquals(restoredWhileOpen.toList, List(snaps.head.fileName.toString))
        assertEquals(
          seen.collect { case StorageEvent.CheckpointNotDeleted(name, _) => name }.toList,
          List(orphan.fileName.toString)
        )
        assert(seen.collect { case StorageEvent.CheckpointNotDeleted(_, e) => e }.forall {
          case _: StorageError.Io => true
          case _                  => false
        })
        assert(left)
    }
  }

  /** Whether block `i` changed the UTxO set: a Shelley-based block whose changes carry its number.
    */
  private def changesUtxo(i: Int): Boolean = headers(i) match
    case _: Header.Shelley =>
      states(i + 1).utxo.diff.exists(_._2.stamp == headers(i).blockNo.toLong)
    case _ => false

  private def exported(
      dir: Path,
      out: Path,
      anchor: BlockRef
  ): IO[Either[LedgerSnapshotExport.Error, LedgerSnapshotExport.Report]] =
    val slot = xyz.kd5ujc.scarab.ledger.state.SlotNo
      .fromBigInt(anchor.slot)
      .fold(e => fail(e.toString), identity)
    Handle
      .allow[LedgerSnapshotExport.Error](
        LedgerSnapshotExport
          .run[IO](LedgerSnapshotExport.Config(dir, out, Some(slot)))
          .map(_.asRight[LedgerSnapshotExport.Error])
      )
      .rescue(e => IO.pure(Left(e)))

  // In this chain only eight Shelley-based blocks change the UTxO set, so nearly every snapshot is
  // of a block that changed nothing, the shutdown snapshots among them.
  test("every snapshot exports, the shutdown one included, also of a block that changed no UTxO") {
    tempDir.use { root =>
      val dir = root / "db"
      for
        log1 <- events(dir)
        _ <- runWith(dir, 500, 120, log1, Some("rocksdb"), keep = 100)
        log2 <- events(dir)
        second <- runWith(dir, 731, 120, log2, Some("rocksdb"), keep = 100)
        e1 <- log1.get
        e2 <- log2.get
        written = (e1 ++ e2).collect { case StorageEvent.SnapshotWritten(_, Some(a), _, _) => a }
        shutdowns = (e1 ++ e2).collect { case StorageEvent.ShutdownSnapshot(Some(a), true) => a }
        restored = e2.collectFirst { case StorageEvent.SnapshotRestored(_, a) => a }.flatten
        results <- written.distinct.traverse(a => exported(dir, root / "out", a).map(a -> _))
      yield
        assertEquals(shutdowns.map(_.blockNo), Vector(BigInt(500 - k - 1), BigInt(731 - k - 1)))
        assert(shutdowns.forall(a => !changesUtxo(a.blockNo.toInt)), shutdowns)
        assert(
          written.exists(a => !shutdowns.contains(a) && !changesUtxo(a.blockNo.toInt)),
          s"a periodic snapshot of a block that changed nothing, among $written"
        )
        // the restart restores the first shutdown snapshot and reaches the replayed state
        assertEquals(restored, shutdowns.headOption)
        assertState(second._2.state, states(731))
        assertEquals(second._3, states(731).utxo.toMap)
        assertEquals(results.collect { case (a, Left(e)) => a.blockNo -> e }, Vector.empty)
        assertEquals(results.collect { case (a, Right(r)) if r.anchor != a => a }, Vector.empty)
    }
  }

  // ---- volatile blocks, the shutdown snapshot and the network identity (D4, D5) ----

  private def volatileConfig(every: Int, identity: Option[NetworkIdentity]) =
    config(every).copy(identity = identity, volatileFileBlocks = 8, volatileCollectBlocks = 8)

  /** What one start of the node did. */
  private final case class Session(
      resumed: Int,
      dropped: Int,
      replayed: Long,
      restoredAnchor: Option[BlockRef],
      anchor: Option[BlockRef],
      chain: VolatileChain[MirrorState],
      offered: Vector[Vector[ChainSync.Point]],
      events: Vector[StorageEvent],
      startupReplayed: Int
  )

  /** Open the database as `Main` does (storage, then resume the volatile chain), follow the blocks
    * up to `until` with the real follower (those after the resumed tip), and release the storage (a
    * graceful stop). `tap(n)` runs before the follower's n-th event (0-based); the tests copy the
    * database there to stand in for a crash at that point.
    */
  private def session(
      dir: Path,
      until: Int,
      every: Int = 120,
      identity: Option[NetworkIdentity] = Some(setup.identity),
      tap: Int => IO[Unit] = _ => IO.unit,
      copies: Option[SnapshotCopies] = None,
      adoptLegacy: Boolean = false,
      selectionOnStartup: Boolean = false,
      wrapRules: BlockRules[IO, MirrorState] => BlockRules[IO, MirrorState] = identity
  ): IO[Session] =
    Ref.of[IO, Vector[StorageEvent]](Vector.empty).flatMap { log =>
      Handle
        .allow[NodeError] {
          val unbacked = StateBacking.none[IO, MirrorState]
          val backing = copies.fold(unbacked)(_.backing(unbacked))
          val events = copies.fold(storageEvents(log))(_.events(storageEvents(log)))
          val nodeRules = wrapRules(
            MirrorRules[IO](
              setup.praos,
              setup.globals,
              setup.byronRules,
              setup.handOver,
              setup.byronHistory,
              xyz.kd5ujc.scarab.plutus.PlutusBackends.backend
            )
          )
          ChainStorage
            .open[IO, MirrorState](
              dir,
              volatileConfig(every, identity).copy(adoptLegacyNetworkIdentity = adoptLegacy),
              nodeRules,
              setup.mirrorStart,
              events,
              backing,
              _ => IO.unit
            )
            .use { recovered =>
              for
                resumed <- ChainStorage.resume[IO, MirrorState](
                  recovered,
                  k,
                  setup.origin,
                  nodeRules,
                  geometry.byron.epochSlots,
                  events,
                  _ => IO.unit
                )
                chainRef <- Ref.of[IO, VolatileChain[MirrorState]](
                  VolatileChain(
                    k,
                    Anchor(recovered.anchor, recovered.state),
                    resumed.map(r => r.ref -> r.state),
                    setup.origin
                  )
                )
                given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(chainRef)
                start <- chainRef.get.map(
                  _.tipRef.fold(0)(t => headers.indexWhere(_.hash == t.hash) + 1)
                )
                queue <- Queue.unbounded[IO, SyncEvent]
                _ <- (start until until).toList.traverse_(i =>
                  queue.offer(SyncEvent.RollForward(headers(i), ChainSync.Tip.Origin))
                )
                done <- Deferred[IO, Unit]
                offered <- Ref.of[IO, Vector[Vector[ChainSync.Point]]](Vector.empty)
                count <- Ref.of[IO, Int](0)
                bodies = headers.map(_.hash).zip(raws).toMap
                peer = new WorkerTesting.Scripted[IO]:
                  def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
                    offered.update(_ :+ candidates).as(candidates.headOption)
                  def next(using Raise[IO, PeerError]) =
                    count.getAndUpdate(_ + 1).flatMap(tap) *>
                      queue.tryTake.flatMap {
                        case Some(event) => IO.pure(event)
                        case None        => done.complete(()) *> IO.never[SyncEvent]
                      }
                  def fetch(header: Header)(using Raise[IO, PeerError]) =
                    IO.pure(bodies(header.hash))
                startupApplications <- Ref.of[IO, Int](0)
                follower <- Follower.create[IO, MirrorState](
                  BlockRules.from((state, header, raw) =>
                    startupApplications.update(_ + 1) *> nodeRules(state, header, raw)
                  ),
                  noEvents,
                  geometry,
                  persistence =
                    Some(copies.fold(recovered.persistence)(_.persistence(recovered.persistence))),
                  initialViews = resumed.map(r => r.ref.hash -> r.view).toMap
                )
                startupReplayed <-
                  (if selectionOnStartup then
                     xyz.kd5ujc.scarab.node.RestartSelectionControl
                       .resource(
                         follower,
                         resumed.map(r =>
                           headers
                             .find(_.hash == r.ref.hash)
                             .getOrElse(
                               fail("resumed block missing its original fixture header")
                             ) -> bodies(r.ref.hash)
                         )
                       )
                       .use(_ => startupApplications.get)
                   else startupApplications.get)
                _ <- xyz.kd5ujc.scarab.node.WorkerTesting
                  .pool(follower)
                  .flatMap(blocks => WorkerTesting.follow(follower, blocks, peer, done.get))
                  .timeout(60.seconds)
                chain <- chainRef.get
                offers <- offered.get
              yield (resumed, recovered.anchor, chain, offers, startupReplayed)
            }
            .flatMap { (resumed, anchor, chain, offers, startupReplayed) =>
              // the storage is released by now: the shutdown snapshot has been taken
              log.get.map { events =>
                Session(
                  resumed.size,
                  events
                    .collectFirst { case StorageEvent.VolatileResumed(_, d, _) => d }
                    .getOrElse(-1),
                  events
                    .collectFirst { case StorageEvent.ReplayFinished(_, n, _) => n }
                    .getOrElse(-1L),
                  events.collectFirst { case StorageEvent.SnapshotRestored(_, a) => a }.flatten,
                  anchor,
                  chain,
                  offers,
                  events,
                  startupReplayed
                ).asRight[NodeError]
              }
            }
        }
        .rescue(e => IO.pure(e.asLeft))
        .flatMap(_.fold(e => IO.raiseError(new SessionRefused(e)), IO.pure))
    }

  /** A session that ended in a typed error of the node, which a test can inspect. */
  private final class SessionRefused(val error: NodeError) extends AssertionError(error.message)

  /** The message of the refusal a session ends in. */
  private def refusal(s: IO[Session]): IO[String] =
    s.attempt.map {
      case Left(e: AssertionError) => e.getMessage
      case Left(other)             => fail(s"expected a refusal, got $other")
      case Right(_)                => fail("expected a refusal, the database opened")
    }

  /** A live copy waits for the previous snapshot and excludes persistence mutations. Checkpoint
    * registration is synchronous, before the snapshot worker starts; its terminal event follows all
    * snapshot writes and pruning. Shutdown starts only after the session's taps finish.
    */
  private final class SnapshotCopies(
      lock: Semaphore[IO],
      pending: Ref[IO, Option[Deferred[IO, Unit]]]
  ):
    private def awaitSnapshot: IO[Unit] = pending.get.flatMap(_.traverse_(_.get))

    def started: IO[Unit] =
      awaitSnapshot *> Deferred[IO, Unit].flatMap(done => pending.set(Some(done)))

    def finished: IO[Unit] = pending.get.flatMap(_.traverse_(_.complete(()).void))

    def quiet[A](body: IO[A]): IO[A] = lock.permit.use(_ => awaitSnapshot *> body)

    def events(underlying: StorageEvents[IO]): StorageEvents[IO] = event =>
      underlying.emit(event) *> (event match
        case StorageEvent.SnapshotWritten(_, _, _, _) | StorageEvent.SnapshotRefused(_, _, _) |
            StorageEvent.SnapshotFailed(_) =>
          finished
        case _ => IO.unit)

    def backing(underlying: StateBacking[IO, MirrorState]): StateBacking[IO, MirrorState] =
      new StateBacking[IO, MirrorState]:
        def advance(state: MirrorState)(using Raise[IO, StorageError]) = underlying.advance(state)
        def settled(state: MirrorState)(using Raise[IO, StorageError]) = underlying.settled(state)
        def checkpoint(anchor: BlockRef)(using Raise[IO, StorageError]) =
          started *> underlying.checkpoint(anchor)
        def admit(anchor: Option[BlockRef], binding: CheckpointBinding)(using
            Raise[IO, StorageError]
        ) =
          underlying.admit(anchor, binding)
        def restore(anchor: Option[BlockRef])(using Raise[IO, StorageError]) =
          underlying.restore(anchor)
        def reset(using Raise[IO, StorageError]) = underlying.reset
        def retain(stems: Set[String])(using Raise[IO, StorageError]) = underlying.retain(stems)
        def trim(stems: Set[String])(using Raise[IO, StorageError]) = underlying.trim(stems)

    def persistence(
        underlying: ChainPersistence[IO, MirrorState]
    ): ChainPersistence[IO, MirrorState] =
      new ChainPersistence[IO, MirrorState]:
        def extended(
            ref: BlockRef,
            raw: Bytes,
            immutable: Vector[BlockRef],
            anchor: Anchor[MirrorState]
        )(using
            Raise[IO, StorageError],
            Raise[IO, ChainError]
        ) = quiet(underlying.extended(ref, raw, immutable, anchor))
        def rolledBack(to: ChainSync.Point)(using Raise[IO, StorageError], Raise[IO, ChainError]) =
          quiet(underlying.rolledBack(to))

  private object SnapshotCopies:
    def create: IO[SnapshotCopies] =
      (Semaphore[IO](1), Ref.of[IO, Option[Deferred[IO, Unit]]](None))
        .mapN(new SnapshotCopies(_, _))

  test("crash copies wait for snapshots and exclude persistence until the copy finishes") {
    for
      copies <- SnapshotCopies.create
      copying <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      persisted <- Deferred[IO, Unit]
      _ <- copies.started
      control <- TestControl.execute(
        copies.quiet(copying.complete(()).void *> release.get).background.use { _ =>
          copying.get *> copies.quiet(persisted.complete(()).void)
        }
      )
      _ <- Resource
        .make(IO.pure(control))(_ =>
          copies.finished *> release.complete(()).void *> control.tickAll
        )
        .use { clock =>
          for
            _ <- clock.tickAll
            before <- copying.tryGet
            waiting <- clock.results
            _ <- IO {
              assertEquals(before, None, "copy must wait for the in-flight snapshot")
              assertEquals(
                waiting,
                None,
                "the controlled program is blocked on snapshot completion"
              )
            }
            _ <- copies.finished *> clock.tickAll
            entered <- copying.tryGet
            early <- persisted.tryGet
            _ <- IO {
              assertEquals(entered, Some(()), "snapshot completion permits copying")
              assertEquals(early, None, "persistence must wait for the copy")
            }
            _ <- release.complete(()) *> clock.tickAll
            after <- persisted.tryGet
            result <- clock.results
            _ <- IO {
              assertEquals(after, Some(()), "persistence resumes after the copy")
              assert(
                result.exists {
                  case Outcome.Succeeded(()) => true
                  case _                     => false
                },
                result.toString
              )
            }
          yield ()
        }
    yield ()
  }

  private def copyTree(from: Path, to: Path): IO[Unit] = IO.blocking {
    val src = from.toNioPath
    val walk = JFiles.walk(src)
    try
      walk.iterator.asScala.foreach { p =>
        val target = to.toNioPath.resolve(src.relativize(p).toString)
        if JFiles.isDirectory(p) then JFiles.createDirectories(target)
        else JFiles.copy(p, target)
      }
    finally walk.close()
  }

  test(
    "selection startup after real storage resume neither rolls back nor revalidates the suffix"
  ) {
    tempDir.use { dir =>
      for
        first <- session(dir, 500)
        second <- session(dir, 500, selectionOnStartup = true)
        third <- session(dir, 500, selectionOnStartup = true)
      yield
        assertEquals(second.resumed, k)
        assertEquals(second.startupReplayed, 0)
        assertEquals(second.chain, first.chain)
        assertEquals(third.resumed, k)
        assertEquals(third.startupReplayed, 0)
        assertEquals(third.chain, first.chain)
    }
  }

  test("restart resumes at the previous tip: the volatile blocks are not fetched again") {
    tempDir.use { dir =>
      for
        first <- session(dir, 500)
        _ <- IO(assertEquals(first.resumed, 0))
        second <- session(dir, 731)
        // the last k blocks came back from the log, applied on top of the immutable state
        _ <- IO(assertEquals(second.resumed, k))
        _ <- IO(assertEquals(second.dropped, 0))
        _ <- IO(assertEquals(second.chain.tip, headers(730).point))
        _ <- IO(assertState(second.chain.state, states(731)))
        // the first intersection offered the previous tip, not the immutable anchor
        _ <- IO(assertEquals(second.offered.head.head, headers(499).point))
        _ <- IO(assertEquals(second.anchor.map(_.hash), Some(ref(500 - k - 1).hash)))
        // a restart without the log (the behaviour before D4) ends in the same state
        _ <- files.deleteRecursively(dir / "volatile")
        third <- session(dir, 731)
        _ <- IO(assertEquals(third.resumed, 0))
        _ <- IO(assertState(third.chain.state, states(731)))
        _ <- IO(assertEquals(third.chain.tip, second.chain.tip))
      yield ()
    }
  }

  test("a graceful stop writes a snapshot at the immutable tip: the restart replays nothing") {
    tempDir.use { dir =>
      for
        // the periodic policy never fires (a huge interval): only the shutdown snapshot exists
        first <- session(dir, 500, every = 1000000)
        _ <- IO(assertEquals(first.replayed, 0L))
        shutdown = first.events.collect { case e: StorageEvent.ShutdownSnapshot => e }
        _ <- IO(assertEquals(shutdown.map(_.written), Vector(true)))
        snaps <- snapshotsIn(dir)
        _ <- IO(assertEquals(snaps.size, 1))
        second <- session(dir, 500, every = 1000000)
        _ <- IO(assertEquals(second.replayed, 0L))
        _ <- IO(assertEquals(second.restoredAnchor.map(_.hash), Some(ref(500 - k - 1).hash)))
        _ <- IO(assertEquals(second.resumed, k))
        _ <- IO(assertState(second.chain.state, states(500)))
        // nothing new: the stop does not write the same snapshot again
        _ <- IO(
          assertEquals(
            second.events.collect { case e: StorageEvent.ShutdownSnapshot => e.written },
            Vector(false)
          )
        )
      yield ()
    }
  }

  test("without the shutdown snapshot the restart replays the stored blocks after the last one") {
    tempDir.use { dir =>
      for
        _ <- session(dir, 500, every = 1000000)
        snaps <- snapshotsIn(dir)
        _ <- snaps.traverse_(files.delete)
        second <- session(dir, 500, every = 1000000)
        _ <- IO(assertEquals(second.replayed, (500 - k).toLong))
      yield ()
    }
  }

  test("a stop before any block becomes immutable writes no snapshot and restarts at the tip") {
    tempDir.use { dir =>
      for
        _ <- session(dir, k - 1)
        snaps <- snapshotsIn(dir)
        _ <- IO(assertEquals(snaps, Nil))
        second <- session(dir, k + 2)
        _ <- IO(assertEquals(second.resumed, k - 1))
        _ <- IO(assertState(second.chain.state, states(k + 2)))
      yield ()
    }
  }

  test("crash recovery: database copies at quiescent storage boundaries restart") {
    tempDir.use { root =>
      val points = (1 to 700 by 61).toList
      val live = root / "live"
      for
        _ <- files.createDirectories(live)
        taken <- Ref.of[IO, Vector[Int]](Vector.empty)
        copies <- SnapshotCopies.create
        tap = (n: Int) =>
          if points.contains(n) then
            copies.quiet(copyTree(live, root / s"crash-$n")) *> taken.update(_ :+ n)
          else IO.unit
        liveSession <- session(live, 731, tap = tap, copies = Some(copies))
        captured <- taken.get
        _ <- IO {
          assert(captured.size >= 10, captured.toString)
          assertEquals(
            liveSession.events.collect { case StorageEvent.SnapshotFailed(failure) => failure },
            Vector.empty
          )
        }
        results <- captured.traverse(n => session(root / s"crash-$n", 731).map(s => (n, s)))
        _ <- results.traverse_ { (n, s) =>
          IO {
            assertState(s.chain.state, states(731), s"restart from the copy at event $n")
            assertEquals(s.chain.tip, headers(730).point)
          }
        }
        // some of the copies resumed volatile blocks, so the log really was in use
        _ <- IO(assert(results.exists(_._2.resumed > 0)))
      yield ()
    }
  }

  test("a crash that tears the volatile log at every cut keeps a consistent chain") {
    tempDir.use { root =>
      val live = root / "live"
      for
        _ <- files.createDirectories(live)
        _ <- session(live, 300)
        logs <- files
          .list(live / "volatile")
          .filter(_.fileName.toString.endsWith(".log"))
          .compile
          .toList
          .map(_.sortBy(_.toString))
        newest = logs.last
        whole <- IO(JFiles.readAllBytes(newest.toNioPath))
        offsets = VolatileStore.decodeAll(whole).map(_._2)
        // cut the newest file at each record boundary and one byte either side
        cuts = (offsets.flatMap(o => List(o - 1, o, o + 1)) :+ 0)
          .filter(c => c >= 0 && c <= whole.length)
          .distinct
        _ <- cuts.traverse_ { cut =>
          val copy = root / s"cut-$cut"
          for
            _ <- copyTree(live, copy)
            _ <- IO(
              JFiles.write(
                (copy / "volatile" / newest.fileName.toString).toNioPath,
                whole.take(cut)
              )
            )
            s <- session(copy, 340)
            _ <- IO(assertState(s.chain.state, states(340), s"cut at $cut"))
            _ <- IO(assertEquals(s.chain.tip, headers(339).point))
          yield ()
        }
      yield ()
    }
  }

  test(
    "a provider defect on a stored volatile block stops the start and rolls nothing back (issue 393)"
  ) {
    val defect = ProviderDefect.Ed25519(
      VerificationError.ImplementationFailure(new IllegalStateException("provider broke"))
    )
    def failingOn(last: Header): BlockRules[IO, MirrorState] => BlockRules[IO, MirrorState] =
      base =>
        BlockRules.from { (state, header, raw) =>
          if header.hash == last.hash then
            Raise[IO, ValidationError].raise[ValidationError, MirrorState](
              ValidationError(header.point, BlockFailure.Unverifiable(BlockRule.Byron, defect))
            )
          else base(state, header, raw)
        }
    tempDir.use { dir =>
      for
        first <- session(dir, 500)
        tail = first.chain.blocks.lastOption.getOrElse(fail("expected a volatile suffix"))._1
        header = headers.find(_.hash == tail.hash).getOrElse(fail("missing fixture header"))
        stopped <- session(dir, 500, wrapRules = failingOn(header)).attempt
        // control: the same database, healthy, keeps the whole tail it selected
        again <- session(dir, 500)
      yield
        stopped match
          case Left(refused: SessionRefused) =>
            val held = VerificationDefect(header.point, VerificationDefect.Site.Header, defect)
            assertEquals(refused.error, held)
            assertEquals(Main.startupFailure(refused.error)._2.code, 7)
          case other => fail(s"expected the start to stop on the defect, got $other")
        // nothing was cut back: the tail is as it was selected
        assertEquals(again.resumed, k)
        assertEquals(again.dropped, 0)
        assertEquals(again.chain, first.chain)
    }
  }

  test("a volatile log that holds a block that does not apply is cut back to the valid part") {
    tempDir.use { dir =>
      for
        _ <- session(dir, 500)
        // log a block that does not follow the tip (505 skips 500 to 504), then restart
        _ <- Handle
          .allow[StorageError] {
            VolatileStore
              .open[IO](dir / "volatile", 8)
              .use((v, _) => v.append(ref(505), raws(505)))
          }
          .rescue(e => IO.raiseError(new AssertionError(e.message)))
        again <- session(dir, 520)
        _ <- IO(assertEquals(again.resumed, k))
        _ <- IO(assertEquals(again.dropped, 1))
        _ <- IO(assertState(again.chain.state, states(520)))
        // the cut was logged: the next start does not see the refused block again
        third <- session(dir, 520)
        _ <- IO(assertEquals(third.dropped, 0))
        _ <- IO(assertState(third.chain.state, states(520)))
      yield ()
    }
  }

  test(
    "resume drops selected volatile records whose slot, height or boundary metadata disagrees with originals"
  ) {
    enum Field:
      case Slot, Height, Boundary
    tempDir.use { root =>
      Field.values.toVector.traverse_ { field =>
        val dir = root / field.toString
        for
          _ <- files.createDirectories(dir)
          first <- session(dir, 500)
          original = first.chain.blocks.lastOption.getOrElse(fail("expected a volatile suffix"))._1
          prefix = first.chain.blocks
            .dropRight(1)
            .lastOption
            .fold(first.chain.anchor.point)(_._1.point)
          inconsistent = field match
            case Field.Slot     => original.copy(slot = original.slot + 1)
            case Field.Height   => original.copy(blockNo = original.blockNo + 1)
            case Field.Boundary => original.copy(boundary = !original.boundary)
          _ <- Handle
            .allow[StorageError] {
              VolatileStore.open[IO](dir / "volatile", 8).use { (store, _) =>
                store.rollback(prefix) *> store.append(inconsistent, raws(499))
              }
            }
            .rescue(error => IO.raiseError(new AssertionError(error.message)))
          restarted <- session(dir, 500, selectionOnStartup = true)
          again <- session(dir, 500, selectionOnStartup = true)
          _ <- IO {
            assertEquals(restarted.resumed, k - 1, field.toString)
            assertEquals(restarted.dropped, 1, field.toString)
            assertEquals(restarted.startupReplayed, 0, field.toString)
            assertEquals(restarted.chain, first.chain, field.toString)
            assertEquals(again.resumed, k, field.toString)
            assertEquals(again.dropped, 0, field.toString)
            assertEquals(again.startupReplayed, 0, field.toString)
            assertEquals(again.chain, first.chain, field.toString)
          }
        yield ()
      }
    }
  }

  test(
    "network identity: a V6 file is bound at the next open, and adopts a later Dijkstra genesis once"
  ) {
    tempDir.use { dir =>
      val id = setup.identity
      val file = (dir / NetworkIdentityFile.FileName).toNioPath
      def hash(b: Byte) = Some(
        xyz.kd5ujc.scarab.ledger.GenesisFileHash(
          xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(Bytes.fromArray(Array[Byte](b)))
        )
      )
      val withDijkstra = id.copy(dijkstraGenesis = hash(9))
      def recorded(s: Session) =
        s.events.collect { case StorageEvent.NetworkRecorded(i, a, d) => (i, a, d) }
      for
        _ <- session(dir, 40)
        _ <- IO.blocking(
          JFiles.writeString(file, id.renderAs(xyz.kd5ujc.scarab.ledger.FormatVersion.V6))
        )
        _ <- session(dir, 60)
        migrated <- IO.blocking(JFiles.readString(file))
        adopting <- session(dir, 60, identity = Some(withDijkstra))
        adopted <- IO.blocking(JFiles.readString(file))
        again <- session(dir, 60, identity = Some(withDijkstra))
        other <- refusal(session(dir, 60, identity = Some(id.copy(dijkstraGenesis = hash(8)))))
      yield
        assertEquals(id.dijkstraGenesis, None)
        assertEquals(migrated, id.render)
        assertEquals(adopted, withDijkstra.render)
        assertEquals(recorded(adopting), Vector((withDijkstra, false, true)))
        assertEquals(recorded(again), Vector.empty)
        assert(other.contains("belongs to another network"), other)
    }
  }

  test("network identity: recorded at creation, accepted on reopen, refused on mismatch") {
    tempDir.use { dir =>
      val id = setup.identity
      def hashOf(b: Byte) =
        xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(Bytes.fromArray(Array[Byte](b)))
      for
        _ <- session(dir, 40)
        recorded <- Handle
          .allow[StorageError](NetworkIdentityFile.read[IO](dir).map(_.asRight[StorageError]))
          .rescue(e => IO.pure(e.asLeft))
        _ <- IO(
          assertEquals(
            recorded,
            Right(Some(StoredNetworkIdentity.Complete(id, IdentityProvenance.Fresh)))
          )
        )
        _ <- session(dir, 60)
        wrongs = List(
          id.copy(magic = id.magic + 1),
          id.copy(shelleyGenesis = hashOf(1)),
          id.copy(alonzoGenesis = xyz.kd5ujc.scarab.ledger.GenesisFileHash(hashOf(3))),
          id.copy(conwayGenesis = xyz.kd5ujc.scarab.ledger.GenesisFileHash(hashOf(4))),
          id.copy(byronGenesis = None),
          id.copy(byronGenesis = Some(hashOf(2)))
        )
        _ <- wrongs.traverse_ { wrong =>
          refusal(session(dir, 60, identity = Some(wrong))).map { message =>
            assert(message.contains("belongs to another network"), message)
            assert(message.contains(s"magic ${id.magic}"), message)
          }
        }
        // the refusals changed nothing
        again <- session(dir, 80)
        _ <- IO(assertState(again.chain.state, states(80)))
      yield ()
    }
  }

  test("network identity: a database from before the identity existed is adopted or refused") {
    tempDir.use { root =>
      val id = setup.identity
      for
        // the old behaviour: no identity configured, so none is recorded
        _ <- session(root / "old", 120, identity = None)
        _ <- IO(assert(!JFiles.exists((root / "old" / NetworkIdentityFile.FileName).toNioPath)))
        _ <- copyTree(root / "old", root / "foreign")
        needsConfirmation <- refusal(session(root / "old", 140))
        _ <- IO(assert(needsConfirmation.contains("contains data but has no network identity")))
        adopted <- session(root / "old", 140, adoptLegacy = true)
        _ <- IO(assertState(adopted.chain.state, states(140)))
        _ <- IO(
          assertEquals(
            adopted.events.collect { case StorageEvent.NetworkRecorded(_, a, _) => a },
            Vector(true)
          )
        )
        _ <- IO(assert(JFiles.exists((root / "old" / NetworkIdentityFile.FileName).toNioPath)))
        // the same database with another Byron genesis: its first block names another predecessor
        other = id.copy(byronGenesis =
          Some(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(Bytes.fromArray(Array[Byte](9))))
        )
        message <- refusal(
          session(root / "foreign", 140, identity = Some(other), adoptLegacy = true)
        )
        _ <- IO(assert(message.contains("first block names"), message))
        _ <- IO(
          assert(!JFiles.exists((root / "foreign" / NetworkIdentityFile.FileName).toNioPath))
        )
        // an empty database simply records the identity
        _ <- files.createDirectories(root / "empty")
        _ <- session(root / "empty", 3)
        _ <- IO(assert(JFiles.exists((root / "empty" / NetworkIdentityFile.FileName).toNioPath)))
      yield ()
    }
  }

  test("identity adoption preflights immutable headers before writing identity or backup") {
    tempDir.use { root =>
      val dir = root / "legacy"
      val identityFile = dir / NetworkIdentityFile.FileName
      val legacy = setup.identity.renderAs(xyz.kd5ujc.scarab.ledger.FormatVersion.V2)
      def snapshotTree: IO[Map[String, Vector[Byte]]] = IO.blocking {
        val walk = JFiles.walk(dir.toNioPath)
        try
          walk
            .iterator()
            .asScala
            .filter(JFiles.isRegularFile(_))
            .map { file =>
              dir.toNioPath.relativize(file).toString -> JFiles.readAllBytes(file).toVector
            }
            .toList
            .toMap
        finally walk.close()
      }
      def rejectWith(mutator: IO[Unit], expected: String): IO[Unit] =
        for
          _ <- session(dir, 650, identity = None)
          _ <- IO.blocking {
            val snapshots = dir.toNioPath.resolve("snapshots")
            val stream = JFiles.list(snapshots)
            try
              assert(
                stream.iterator().asScala.exists { file =>
                  JFiles.isRegularFile(file) &&
                  new String(JFiles.readAllBytes(file).take(8), StandardCharsets.ISO_8859_1) ==
                    "SCRBSNPB"
                },
                "expected a current V11 snapshot before the immutable-header refusal"
              )
            finally stream.close()
          }
          _ <- IO(JFiles.write(identityFile.toNioPath, legacy.getBytes(StandardCharsets.UTF_8)))
          _ <- mutator
          before <- snapshotTree
          message <- refusal(session(dir, 650, adoptLegacy = true))
          after <- snapshotTree
          _ <- IO {
            assert(message.contains(expected), message)
            assertEquals(after, before)
            assert(!JFiles.exists(dir.toNioPath.resolve("network-identity.legacy-v2")))
          }
        yield ()
      for
        _ <- rejectWith(
          IO {
            val path = (dir / "immutable" / "00000.index").toNioPath
            val old = JFiles.readAllBytes(path)
            JFiles.write(path, "SCRBIDX9".getBytes(StandardCharsets.US_ASCII) ++ old.drop(8))
          },
          "unknown chunk index format"
        )
        _ <- IO.blocking(RocksUtxoStore.deleteTree(dir.toNioPath))
        _ <- rejectWith(
          IO {
            JFiles.write(
              (dir / "immutable" / "format").toNioPath,
              "scarab immutable store v9 chunkSlots=100\n".getBytes(StandardCharsets.UTF_8)
            )
          },
          "unknown immutable store format"
        )
      yield ()
    }
  }

  test("missing-identity adoption checks the first block beyond an empty earliest chunk") {
    tempDir.use { dir =>
      for
        _ <- session(dir, 140, identity = None)
        _ <- IO {
          val index = (dir / "immutable" / "00000.index").toNioPath
          assert(JFiles.exists((dir / "immutable" / "00001.index").toNioPath))
          JFiles.write(index, FileImmutableStore.IndexMagic)
        }
        message <- refusal(session(dir, 140, adoptLegacy = true))
        _ <- IO {
          assert(message.contains("first block names"), message)
          assert(!JFiles.exists((dir / NetworkIdentityFile.FileName).toNioPath))
        }
      yield ()
    }
  }

  test("network identity text round trips and rejects anything else") {
    val id = setup.identity
    assertEquals(
      NetworkIdentity.parse("f", id.render),
      Right(StoredNetworkIdentity.Complete(id, IdentityProvenance.Fresh))
    )
    val shelleyOnly = id.copy(byronGenesis = None)
    assertEquals(
      NetworkIdentity.parse("f", shelleyOnly.render),
      Right(StoredNetworkIdentity.Complete(shelleyOnly, IdentityProvenance.Fresh))
    )
    assert(NetworkIdentity.parse("f", "garbage").isLeft)
    assert(NetworkIdentity.parse("f", id.render.replace("magic", "magick")).isLeft)
    assert(NetworkIdentity.parse("f", id.render.replace("v9", "v3")).isLeft)
    assert(NetworkIdentity.parse("f", id.render.replace("dijkstra-genesis", "dijkstra")).isLeft)
  }

  // ---- V5 and V7 snapshots paired with current and pre-K6 checkpoint key layouts (#145, #395)

  private enum CheckpointLayout:
    case Current, BeforeK6

  private def checkpointOf(dir: Path, snapshotFile: String): Path =
    dir / "utxo" / "checkpoints" / snapshotFile.stripSuffix(".bin")

  /** Snapshot files with the chosen checkpoint key layout: current V7 files, or, with `previous`,
    * the V5 files a build before checkpoint binding wrote (the same body under the V5 magic). Drop
    * the newest snapshots so the live store is ahead, as after a crash; the original historical key
    * migration remains supported independently of the snapshot payload format.
    */
  private def checkpointDatabase(
      dir: Path,
      layout: CheckpointLayout,
      until: Int,
      every: Int,
      keep: Int,
      drop: Int,
      previous: Boolean = false
  ): IO[List[BlockRef]] =
    for
      log <- events(dir)
      _ <- runWith(dir, until, every, log, Some("rocksdb"), keep)
      names <- snapshotsIn(dir).map(_.map(_.fileName.toString).sorted.reverse)
      _ <- names.take(drop).traverse_ { n =>
        files.delete(dir / "snapshots" / n) *>
          IO(RocksUtxoStore.deleteTree(checkpointOf(dir, n).toNioPath))
      }
      kept = names.drop(drop)
      anchors <- kept.traverse { n =>
        val file = dir / "snapshots" / n
        for
          raw <- IO(JFiles.readAllBytes(file.toNioPath))
          content = FileLedgerSnapshots.verified(raw).fold(e => fail(e.message), identity)
          snapshot <- summon[SnapshotFormat[IO, MirrorState]]
            .decode(content)
            .map(_.fold(e => fail(e.message), identity))
          _ <- IO.whenA(layout == CheckpointLayout.BeforeK6)(
            IO(LegacyUtxoCheckpoint.rewrite(checkpointOf(dir, n).toNioPath))
          )
          _ <- IO.whenA(previous) {
            val (_, _, sidecar, haskell) =
              SnapshotV11
                .sections(content, ReferenceLayout.Release11_1)
                .fold(e => fail(e.message), identity)
            IO.blocking(
              JFiles.write(
                file.toNioPath,
                FileLedgerSnapshots.closed(SnapshotV5.framed(PreviousSidecars.of(sidecar), haskell))
              )
            ).void
          }
        yield snapshot.anchor.getOrElse(fail(s"$n is a snapshot of the origin"))
      }
    yield anchors

  /** Restore the newest usable snapshot/checkpoint, replay its immutable tail and continue the
    * chain. The old checkpoint key layout must convert without requiring an old snapshot reader;
    * `previous` V5 files are migrated to V7 once, and the restored one is reported unbound.
    */
  private def reopened(
      dir: Path,
      layout: CheckpointLayout,
      left: List[BlockRef],
      until: Int,
      every: Int,
      keep: Int,
      rollback: Option[Rollback] = None,
      previous: Boolean = false
  ): IO[Unit] =
    val label = layout.toString
    for
      log <- events(dir)
      again <- runWith(
        dir,
        until,
        every,
        log,
        Some("rocksdb"),
        keep,
        migrate = true,
        rollback = rollback
      )
      seen <- log.get
    yield
      assertEquals(
        seen.collect {
          case StorageEvent.FormatMigrated(m) if m.kind == FileKind.SnapshotFile => m
        }.toList,
        if previous then
          List(Migrated(FileKind.SnapshotFile, FormatVersion.V5, FormatVersion.V11, left.size))
        else List.empty[Migrated],
        s"$label: only V5 snapshot files are rewritten, once"
      )
      assertEquals(
        seen.collect { case StorageEvent.SnapshotUnbound(_, a) => a }.toList,
        if previous then List(left.headOption) else Nil,
        s"$label: a migrated V5 snapshot is restored unbound"
      )
      assertEquals(
        seen.collect { case e: StorageEvent.SnapshotSkipped => e.name }.toList,
        List.empty[String],
        s"$label: no snapshot is skipped"
      )
      assertEquals(
        seen.collect { case StorageEvent.SnapshotRestored(_, a) => a }.toList,
        List(left.headOption),
        s"$label: the newest snapshot left is restored"
      )
      val replayed =
        seen.collectFirst { case StorageEvent.ReplayFinished(_, n, _) => n }.getOrElse(-1L)
      assert(replayed > 0, s"$label: replayed $replayed blocks after the restore")
      assertEquals(again._2.tip, headers(until - 1).point)
      assertState(again._2.state, states(until), s"$label: the state")
      assertEquals(again._3, states(until).utxo.toMap, s"$label: the UTxO set")

  for layout <- CheckpointLayout.values do
    test(
      s"a V5 database with ${layout} UTxO checkpoints, its live store ahead of the snapshot, migrates, restores and replays"
    ) {
      tempDir.use { dir =>
        for
          left <- checkpointDatabase(
            dir,
            layout,
            until = 600,
            every = 60,
            keep = 4,
            drop = 1,
            previous = true
          )
          _ <- IO(assert(left.size >= 2, s"snapshots left: ${left.size}"))
          _ <- reopened(dir, layout, left, until = 731, every = 120, keep = 4, previous = true)
        yield ()
      }
    }

  test("a V7 database restores its snapshots against the checkpoints they were written with") {
    tempDir.use { dir =>
      for
        left <- checkpointDatabase(dir, CheckpointLayout.Current, 600, 60, 4, 1)
        _ <- IO(assert(left.size >= 2, s"snapshots left: ${left.size}"))
        _ <- reopened(dir, CheckpointLayout.Current, left, until = 731, every = 120, keep = 4)
      yield ()
    }
  }

  test(
    "a snapshot whose checkpoint is not the one it recorded is deleted with it, for an older one"
  ) {
    tempDir.use { dir =>
      for
        left <- checkpointDatabase(dir, CheckpointLayout.Current, 600, 60, 4, 1)
        _ <- IO(assert(left.size >= 2, s"snapshots left: ${left.size}"))
        newest = FileLedgerSnapshots.nameOf(left.head)
        // the same entries in other table files: a directory the snapshot was not written with
        _ <- IO(LegacyUtxoCheckpoint.rewrite(checkpointOf(dir, newest).toNioPath))
        log <- events(dir)
        _ <- runWith(dir, 731, 120, log, Some("rocksdb"), 4, migrate = true)
        seen <- log.get
        checkpointLeft <- IO.blocking(JFiles.exists(checkpointOf(dir, newest).toNioPath))
      yield
        // the reference deletes a temporary snapshot it cannot use (82ecba32 API.hs:628-631), and
        // the trim path takes its checkpoint with it
        assertEquals(
          seen.collect { case StorageEvent.SnapshotDeleted(name) => name }.toList,
          List(newest)
        )
        assert(!checkpointLeft, s"the checkpoint of $newest is still there")
        val skipped = seen.collect { case StorageEvent.SnapshotSkipped(name, e) => name -> e }
        assertEquals(skipped.map(_._1).toList, List(newest))
        skipped.headOption.map(_._2) match
          case Some(StorageError.UtxoCheckpoint(_, CheckpointProblem.DigestMismatch(_, _))) => ()
          case other => fail(s"expected a digest mismatch, got $other")
        assertEquals(
          seen.collect { case StorageEvent.SnapshotRestored(_, a) => a }.toList,
          List(left.lift(1))
        )
    }
  }

  test(
    "a current snapshot database with many blocks, many checkpoints and a rollback restores and replays"
  ) {
    val layout = CheckpointLayout.Current
    tempDir.use { dir =>
      for
        left <- checkpointDatabase(dir, layout, until = 700, every = 25, keep = 100, drop = 2)
        _ <- IO(assert(left.size >= 15, s"checkpoints left: ${left.size}"))
        checkpoints <- files.list(dir / "utxo" / "checkpoints").compile.count
        _ <- IO(assertEquals(checkpoints, left.size.toLong))
        // the peer takes three blocks back after the first 725 and announces them again
        _ <- reopened(
          dir,
          layout,
          left,
          until = 731,
          every = 120,
          keep = 100,
          Some(Rollback(725, 3))
        )
      yield ()
    }
  }
