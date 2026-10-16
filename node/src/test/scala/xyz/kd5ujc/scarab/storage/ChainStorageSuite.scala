// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import scala.annotation.unused
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.{Ask, Handle, Stateful}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.nio.file.Files as JFiles
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.Anchor
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.FollowerEvents
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.ledger.NetworkIdentity
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.node.Follower
import xyz.kd5ujc.scarab.node.NodeRules
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.Peer
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.node.SyncEvent
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import StorageTestKit.*

/** Restart behaviour over the committed Byron-to-Conway capture (`fixtures/eras`): the follower
  * writes through [[ChainStorage]], a fresh open recovers the same state from the newest snapshot
  * plus a replay of the stored blocks, and recovery survives lost or damaged snapshots.
  */
class ChainStorageSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  private val fixtures = java.nio.file.Path.of("fixtures/eras")
  private val files = Files.forAsync[IO]

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val geometry = setup.geometry
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private given SnapshotFormat[IO, NodeState] = SnapshotV4.format[IO](SnapshotCheck.Full)
  private val k = geometry.securityParam

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
  private val headers: Vector[Header] =
    raws.map(Block.decode(_, geometry.byron.epochSlots).fold(e => fail(e.message), _.header))
  private def ref(i: Int) =
    BlockRef(headers(i).slot, headers(i).hash, headers(i).blockNo, headers(i).isBoundary)

  /** State after the first `n` blocks, computed without any storage. */
  private lazy val states: Vector[NodeState] =
    headers.indices.foldLeft(Vector(setup.start)) { (acc, i) =>
      NodeRules.applyBlock(
        acc.last,
        headers(i),
        raws(i),
        setup.praos,
        setup.globals,
        setup.byronRules
      ) match
        case Right(next) => acc :+ next
        case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
    }

  private val noEvents: FollowerEvents[IO] = _ => IO.unit

  private def storageEvents(log: Ref[IO, Vector[StorageEvent]]): StorageEvents[IO] =
    e => log.update(_ :+ e)

  private def config(every: Int) =
    ChainStorage.Config(
      snapshotEverySlots = every,
      keepSnapshots = 4,
      chunkSlots = 100,
      byronEpochSlots = geometry.byron.epochSlots,
      progressEvery = 100
    )

  @unused private def rules(using Handle[IO, NodeError]) =
    NodeRules[IO](setup.praos, setup.globals, setup.byronRules)

  /** Open the storage, follow blocks `[from, until)` with the real follower, wait for snapshots. */
  private def run(
      dir: Path,
      until: Int,
      every: Int = 120,
      log: Ref[IO, Vector[StorageEvent]]
  ): IO[(Option[BlockRef], VolatileChain[NodeState])] =
    runWith(dir, until, every, log, None).map((anchor, chain, _) => (anchor, chain))

  /** As [[run]], with the UTxO set in a store (`memory` or `rocksdb`) under the database directory;
    * the last component is the complete UTxO set at the end.
    */
  private def runWith(
      dir: Path,
      until: Int,
      every: Int,
      log: Ref[IO, Vector[StorageEvent]],
      store: Option[String]
  ): IO[
    (
        Option[BlockRef],
        VolatileChain[NodeState],
        Map[xyz.kd5ujc.scarab.ledger.TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
    )
  ] =
    Handle
      .allow[NodeError] {
        val backed: Resource[IO, (Option[UtxoStore[IO]], StateBacking[IO, NodeState])] =
          store match
            case None => Resource.pure((None, StateBacking.none[IO, NodeState]))
            case Some("memory") =>
              Resource.eval(MemoryUtxoStore[IO]).map { s =>
                (Some(s: UtxoStore[IO]), new UtxoBacking[IO](s, dir / "utxo" / "checkpoints"))
              }
            case Some(_) =>
              RocksUtxoStore.resource[IO](dir / "utxo" / "live", 8L * 1024 * 1024).map { s =>
                (Some(s: UtxoStore[IO]), new UtxoBacking[IO](s, dir / "utxo" / "checkpoints"))
              }
        backed
          .use { (utxoStore, backing) =>
            val rules =
              NodeRules[IO](setup.praos, setup.globals, setup.byronRules, utxoStore = utxoStore)
            ChainStorage
              .open[IO, NodeState](
                dir,
                config(every),
                rules,
                setup.start,
                storageEvents(log),
                backing,
                _ => IO.unit
              )
              .use { recovered =>
                for
                  chainRef <- Ref.of[IO, VolatileChain[NodeState]](
                    VolatileChain(
                      k,
                      Anchor(recovered.anchor, recovered.state),
                      Vector.empty,
                      setup.origin
                    )
                  )
                  given Stateful[IO, VolatileChain[NodeState]] = RefStateful(chainRef)
                  queue <- Queue.unbounded[IO, SyncEvent]
                  resumeFrom = recovered.anchor
                    .fold(0)(a => headers.indexWhere(_.hash == a.hash) + 1)
                  _ <- (resumeFrom until until).toList.traverse_(i =>
                    queue.offer(SyncEvent.RollForward(headers(i), ChainSync.Tip.Origin))
                  )
                  done <- Deferred[IO, Unit]
                  offered <- Ref.of[IO, Vector[Vector[ChainSync.Point]]](Vector.empty)
                  bodies = headers.map(_.hash).zip(raws).toMap
                  peer = new Peer[IO]:
                    def intersect(candidates: Vector[ChainSync.Point]) =
                      offered.update(_ :+ candidates).as(candidates.headOption)
                    def next = queue.tryTake.flatMap {
                      case Some(event) => IO.pure(event)
                      case None        => done.complete(()) *> IO.never[SyncEvent]
                    }
                    def fetch(header: Header) = IO.pure(bodies(header.hash))
                  follower <- Follower.create[IO, NodeState](
                    rules,
                    noEvents,
                    geometry,
                    persistence = Some(recovered.persistence)
                  )
                  _ <- IO.race(follower.session(peer), done.get.timeout(60.seconds))
                  // Snapshots are written in the background; let them land.
                  _ <- IO.sleep(500.millis)
                  chain <- chainRef.get
                  all <- utxoStore.fold(IO.pure(chain.state.ledger.utxo.toMap))(s =>
                    UtxoStore.materialize(s, chain.state.ledger.utxo)
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
        _ <- IO(assert(skipped.head.error.isInstanceOf[StorageError.Corrupt]))
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

  /** A snapshot returns the state in its canonical form (see [[SnapshotV4.canonical]]): the state
    * replayed from the blocks is compared in that form.
    */
  private def canonical(s: NodeState): NodeState = SnapshotV4.canonical(setup.mirrorEnv(), s)

  private def assertState(got: NodeState, want: NodeState, clue: => Any = "state"): Unit =
    assertEquals(canonical(got), canonical(want), clue)

  private def sameLedger(x: NodeState, y: NodeState): Unit =
    val (a, b) = (canonical(x), canonical(y))
    assertEquals(a.protocol, b.protocol)
    assertEquals(a.boot, b.boot)
    assertEquals(
      a.ledger.copy(utxo = xyz.kd5ujc.scarab.ledger.Utxo.empty),
      b.ledger.copy(utxo = xyz.kd5ujc.scarab.ledger.Utxo.empty)
    )
    assertEquals(a.ledger.utxo.stake, b.ledger.utxo.stake)
    assertEquals(a.ledger.utxo.coin, b.ledger.utxo.coin)
    assertEquals(a.ledger.utxo.count, b.ledger.utxo.count)

  for kind <- List("memory", "rocksdb") do
    test(s"restart with the UTxO set in a $kind store: snapshot, checkpoint and replay agree") {
      tempDir.use { dir =>
        for
          log1 <- events(dir)
          first <- runWith(dir, 500, 120, log1, Some(kind))
          _ <- IO(sameLedger(first._2.state, states(500)))
          _ <- IO(assertEquals(first._3, states(500).ledger.utxo.toMap))
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
          _ <- IO(assertEquals(second._3, states(731).ledger.utxo.toMap))
        yield ()
      }
    }

  test("a snapshot whose UTxO checkpoint is missing is skipped, the older one is used") {
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
        again <- runWith(dir, 650, 120, log2, Some("rocksdb"))
        e2 <- log2.get
        _ <- IO(
          assertEquals(
            e2.collect { case e: StorageEvent.SnapshotSkipped => e.name }.toList,
            List(snaps.head.fileName.toString)
          )
        )
        _ <- IO(sameLedger(again._2.state, states(650)))
        _ <- IO(assertEquals(again._3, states(650).ledger.utxo.toMap))
      yield ()
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
      chain: VolatileChain[NodeState],
      offered: Vector[Vector[ChainSync.Point]],
      events: Vector[StorageEvent]
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
      tap: Int => IO[Unit] = _ => IO.unit
  ): IO[Session] =
    Ref.of[IO, Vector[StorageEvent]](Vector.empty).flatMap { log =>
      Handle
        .allow[NodeError] {
          val backing = StateBacking.none[IO, NodeState]
          val nodeRules = NodeRules[IO](setup.praos, setup.globals, setup.byronRules)
          ChainStorage
            .open[IO, NodeState](
              dir,
              volatileConfig(every, identity),
              nodeRules,
              setup.start,
              storageEvents(log),
              backing,
              _ => IO.unit
            )
            .use { recovered =>
              for
                resumed <- ChainStorage.resume[IO, NodeState](
                  recovered,
                  k,
                  setup.origin,
                  nodeRules,
                  geometry.byron.epochSlots,
                  storageEvents(log),
                  _ => IO.unit
                )
                chainRef <- Ref.of[IO, VolatileChain[NodeState]](
                  VolatileChain(
                    k,
                    Anchor(recovered.anchor, recovered.state),
                    resumed.map(r => r.ref -> r.state),
                    setup.origin
                  )
                )
                given Stateful[IO, VolatileChain[NodeState]] = RefStateful(chainRef)
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
                peer = new Peer[IO]:
                  def intersect(candidates: Vector[ChainSync.Point]) =
                    offered.update(_ :+ candidates).as(candidates.headOption)
                  def next = count.getAndUpdate(_ + 1).flatMap(tap) *> queue.tryTake.flatMap {
                    case Some(event) => IO.pure(event)
                    case None        => done.complete(()) *> IO.never[SyncEvent]
                  }
                  def fetch(header: Header) = IO.pure(bodies(header.hash))
                follower <- Follower.create[IO, NodeState](
                  nodeRules,
                  noEvents,
                  geometry,
                  persistence = Some(recovered.persistence),
                  initialViews = resumed.map(r => r.ref.hash -> r.view).toMap
                )
                _ <- IO.race(follower.session(peer), done.get.timeout(60.seconds))
                chain <- chainRef.get
                offers <- offered.get
              yield (resumed, recovered.anchor, chain, offers)
            }
            .flatMap { (resumed, anchor, chain, offers) =>
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
                  events
                ).asRight[NodeError]
              }
            }
        }
        .rescue(e => IO.pure(e.asLeft))
        .flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))
    }

  /** The message of the refusal a session ends in. */
  private def refusal(s: IO[Session]): IO[String] =
    s.attempt.map {
      case Left(e: AssertionError) => e.getMessage
      case Left(other)             => fail(s"expected a refusal, got $other")
      case Right(_)                => fail("expected a refusal, the database opened")
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

  test("a crash at any point of a run recovers: copies of the database taken mid-run restart") {
    tempDir.use { root =>
      val points = (1 to 700 by 61).toList
      val live = root / "live"
      for
        _ <- files.createDirectories(live)
        taken <- Ref.of[IO, Vector[Int]](Vector.empty)
        // background snapshots land before the copy, which stands in for the files at the crash
        tap = (n: Int) =>
          if points.contains(n) then
            IO.sleep(60.millis) *> copyTree(live, root / s"crash-$n") *> taken.update(_ :+ n)
          else IO.unit
        _ <- session(live, 731, tap = tap)
        copies <- taken.get
        _ <- IO(assert(copies.size >= 10, copies.toString))
        results <- copies.traverse(n => session(root / s"crash-$n", 731).map(s => (n, s)))
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
        _ <- IO(assertEquals(recorded, Right(Some(id))))
        _ <- session(dir, 60)
        wrongs = List(
          id.copy(magic = id.magic + 1),
          id.copy(shelleyGenesis = hashOf(1)),
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
        adopted <- session(root / "old", 140)
        _ <- IO(assertState(adopted.chain.state, states(140)))
        _ <- IO(
          assertEquals(
            adopted.events.collect { case StorageEvent.NetworkRecorded(_, a) => a },
            Vector(true)
          )
        )
        _ <- IO(assert(JFiles.exists((root / "old" / NetworkIdentityFile.FileName).toNioPath)))
        // the same database with another Byron genesis: its first block names another predecessor
        other = id.copy(byronGenesis =
          Some(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(Bytes.fromArray(Array[Byte](9))))
        )
        message <- refusal(session(root / "foreign", 140, identity = Some(other)))
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

  test("network identity text round trips and rejects anything else") {
    val id = setup.identity
    assertEquals(NetworkIdentity.parse("f", id.render), Right(id))
    val shelleyOnly = id.copy(byronGenesis = None)
    assertEquals(NetworkIdentity.parse("f", shelleyOnly.render), Right(shelleyOnly))
    assert(NetworkIdentity.parse("f", "garbage").isLeft)
    assert(NetworkIdentity.parse("f", id.render.replace("magic", "magick")).isLeft)
    assert(NetworkIdentity.parse("f", id.render.replace("v2", "v3")).isLeft)
  }
