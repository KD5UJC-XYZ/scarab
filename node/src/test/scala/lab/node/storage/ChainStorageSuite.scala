// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.nio.file.Files as JFiles
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.node.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import NodeCodecs.given
import StorageTestKit.*

/** Restart behaviour over the committed Byron-to-Conway capture (`fixtures/eras`): the follower
  * writes through [[ChainStorage]], a fresh open recovers the same state from the newest snapshot
  * plus a replay of the stored blocks, and recovery survives lost or damaged snapshots.
  */
class ChainStorageSuite extends CatsEffectSuite:
  private val fixtures = java.nio.file.Path.of("fixtures/eras")
  private val files = Files.forAsync[IO]

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val geometry = setup.geometry
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
        case Right(next)          => acc :+ next
        case Left((rule, reason)) => fail(s"block $i rejected by $rule: $reason")
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

  private def rules(using Handle[IO, NodeError]) =
    NodeRules[IO](setup.praos, setup.globals, setup.byronRules)

  /** Open the storage, follow blocks `[from, until)` with the real follower, wait for snapshots. */
  private def run(
      dir: Path,
      until: Int,
      every: Int = 120,
      log: Ref[IO, Vector[StorageEvent]]
  ): IO[(Option[BlockRef], VolatileChain[NodeState])] =
    Handle
      .allow[NodeError] {
        ChainStorage
          .open[IO, NodeState](dir, config(every), rules, setup.start, storageEvents(log), StateBacking.none[IO, NodeState])
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
              resumeFrom = recovered.anchor.fold(0)(a => headers.indexWhere(_.hash == a.hash) + 1)
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
              follower = Follower[IO, NodeState](
                rules,
                noEvents,
                geometry,
                persistence = Some(recovered.persistence)
              )
              _ <- IO.race(follower.session(peer), done.get.timeout(60.seconds))
              // Snapshots are written in the background; let them land.
              _ <- IO.sleep(500.millis)
              chain <- chainRef.get
            yield (recovered.anchor, chain.copy(blocks = chain.blocks))
          }
          .map(_.asRight[NodeError])
      }
      .rescue(e => IO.pure(e.asLeft))
      .flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  private def events(dir: Path): IO[Ref[IO, Vector[StorageEvent]]] =
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
        _ <- IO(assertEquals(chain1.state, states(500)))
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
        _ <- IO(assertEquals(chain2.state, states(731)))
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
        _ <- IO(assertEquals(chain.state, states(400)))
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
        _ <- IO(assertEquals(chain.state, states(650)))
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
        _ <- IO(assertEquals(chain.state, states(500)))
      yield ()
    }
  }
