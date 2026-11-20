// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files as JFiles, NoSuchFileException}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules}
import xyz.kd5ujc.scarab.ledger.{Block, NodeError, StorageError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.{NodeSetup, ReceivedBlockRecovery}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The received log is collected on the reference's schedule: 60 s after each copy of blocks to the
  * immutable store, rounded up to a 10 s mark, for the slot of the immutable tip
  * (`copyToImmutableDBRunner`, `scheduleGC` and `gcScheduleRunner`,
  * `Storage/ChainDB/Impl/Background.hs` 257-296 and 466-652, ouroboros-consensus 82ecba329). The
  * storage runs under TestControl: each test offers its steps to the program that holds it open,
  * and moves the clock from outside.
  */
class ReceivedCollectionSuite extends FailsOnUncaught:
  private given SnapshotFormat[IO, Int] with
    def encode(snapshot: StateSnapshot[Int]) = IO.pure(Right(Array(snapshot.state.toByte)))
    def decode(content: Array[Byte]) =
      IO.pure(
        Right(StateSnapshot(None, content.headOption.fold(0)(_.toInt), CheckpointBinding.Unbacked))
      )

  private val rules: BlockRules[IO, Int] = BlockRules.from((_, _, _) => IO.pure(0))

  private val fixtures = java.nio.file.Path.of("fixtures/eras")
  private val epochSlots: BigInt =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(fixtures.resolve("genesis"))
      .fold(e => fail(e.message), _.geometry.byron.epochSlots)

  /** Recorded blocks, in slots that rise, with no boundary block: a restart replays the immutable
    * store's blocks, so they must decode; the rules accept any.
    */
  private val blocks: Vector[(BlockRef, Bytes)] =
    JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .take(40)
      .map { line =>
        val raw = Bytes.fromHex(line).fold(e => fail(e.message), identity)
        val header = Block.decode(raw, epochSlots).fold(e => fail(e.message), _.header)
        BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary) -> raw
      }
      .filterNot(_._1.boundary)
      .foldLeft(Vector.empty[(BlockRef, Bytes)])((kept, b) =>
        if kept.lastOption.forall(_._1.slot < b._1.slot) then kept :+ b else kept
      )

  // one block to a file of the received log, so that each block's file goes on its own
  private val config = ChainStorage.Config(
    SnapshotPolicy.EverySlots(1_000_000),
    byronEpochSlots = epochSlots,
    receivedFileBlocks = 1
  )

  private def typed[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](body).rescue(error => IO(fail(error.message)))

  /** A step of the program that holds the storage open. */
  private enum Step:
    case Act(body: ChainStorage.Recovered[IO, Int] => IO[Unit])
    case Restart
    case Stop

  /** The storage in `dir` under TestControl: [[act]] runs a step in the program, at the virtual
    * time now; [[advance]] moves the clock; [[hashes]] reads what the received log holds.
    */
  private final class Node(
      control: TestControl[Unit],
      steps: Queue[IO, Step],
      held: Ref[IO, Option[ReceivedBlocks[IO]]]
  ):
    def act(body: ChainStorage.Recovered[IO, Int] => IO[Unit]): IO[Unit] =
      steps.offer(Step.Act(body)) *> control.tick
    def restart: IO[Unit] = steps.offer(Step.Restart) *> control.tick

    /** What can run now, run, with the clock where it is. */
    def tick: IO[Unit] = control.tick

    /** The clock moved on by `by`, each timer due on the way run at its own time. */
    def advance(by: FiniteDuration): IO[Unit] =
      control.tick *> control.nextInterval.flatMap { next =>
        if next > Duration.Zero && next < by then control.advanceAndTick(next) *> advance(by - next)
        else control.advanceAndTick(by)
      }
    def hashes: IO[Set[Bytes]] = held.get.flatMap(_.fold(IO.pure(Set.empty[Bytes]))(_.hashes))
    def results: IO[Option[Outcome[cats.Id, Throwable, Unit]]] = control.results
    def stop: IO[Unit] = steps.offer(Step.Stop) *> control.tickAll

  /** The snapshot files in `dir` deleted. */
  private def snapshotsDropped(dir: Path): Unit =
    val snapshots = (dir / "snapshots").toNioPath
    Using.resource(JFiles.list(snapshots))(_.iterator.asScala.toVector).foreach(JFiles.delete)

  /** The steps offered, run in order until a restart (true) or the end (false). */
  private def run(steps: Queue[IO, Step], recovered: ChainStorage.Recovered[IO, Int]): IO[Boolean] =
    steps.take.flatMap {
      case Step.Act(step) => step(recovered) *> run(steps, recovered)
      case Step.Restart   => IO.pure(true)
      case Step.Stop      => IO.pure(false)
    }

  private def node[A](body: (Path, Node) => IO[A]): IO[A] =
    nodeWith(StateBacking.none[IO, Int])(body)

  /** A backing store whose `advance` waits for `gate` the first time, as backing-store work that
    * takes time while the clock moves on.
    */
  private def gated(gate: Deferred[IO, Unit]): StateBacking[IO, Int] =
    new StateBacking[IO, Int]:
      private val none = StateBacking.none[IO, Int]
      def advance(state: Int)(using Raise[IO, StorageError]) = gate.get
      def settled(state: Int)(using Raise[IO, StorageError]) = none.settled(state)
      def checkpoint(anchor: BlockRef)(using Raise[IO, StorageError]) = none.checkpoint(anchor)
      def admit(anchor: Option[BlockRef], binding: CheckpointBinding)(using
          Raise[IO, StorageError]
      ) = none.admit(anchor, binding)
      def restore(anchor: Option[BlockRef])(using Raise[IO, StorageError]) = none.restore(anchor)
      def reset(using Raise[IO, StorageError]) = none.reset
      def retain(stems: Set[String])(using Raise[IO, StorageError]) = none.retain(stems)
      def trim(stems: Set[String])(using Raise[IO, StorageError]) = none.trim(stems)

  /** The storage with `backing`, the program stopped and run to completion however `body` ends. */
  private def nodeWith[A](backing: StateBacking[IO, Int])(body: (Path, Node) => IO[A]): IO[A] =
    StorageTestKit.tempDir.use { dir =>
      for
        steps <- Queue.unbounded[IO, Step]
        held <- Ref.of[IO, Option[ReceivedBlocks[IO]]](None)
        // one start of the storage, until a restart (true) or the end (false). The snapshot the
        // stop writes goes before a restart: this suite's states are not in a snapshot format the
        // start's checks read, and the restart replays the immutable blocks instead
        session = typed(
          ChainStorage
            .open[IO, Int](
              dir,
              config,
              rules,
              0,
              _ => IO.unit,
              backing,
              _ => IO.unit
            )(ReceivedBlockRecovery.startUp(epochSlots))
            .use(recovered => held.set(Some(recovered.received)) *> run(steps, recovered))
        ).flatTap(again => IO.blocking(snapshotsDropped(dir)).whenA(again))
        _ <- IO(assert(blocks.size >= 4, s"four blocks to copy, found ${blocks.size}"))
        control <- TestControl.execute(session.iterateWhile(identity).void)
        _ <- control.tick
        n = Node(control, steps, held)
        result <- body(dir, n).guarantee(n.stop)
        ended <- n.results
        _ <- IO(assertEquals(ended, Some(Outcome.succeeded[cats.Id, Throwable, Unit](()))))
      yield result
    }

  private def block(i: Int): (BlockRef, Bytes) = blocks(i - 1)
  private def hashesOf(is: Int*): Set[Bytes] = is.map(block(_)._1.hash).toSet

  private def put(is: Int*)(recovered: ChainStorage.Recovered[IO, Int]): IO[Unit] =
    StorageTestKit.ok(is.toList.traverse_(i => recovered.received.put(block(i)._1, block(i)._2)))

  /** Block `i` applied and made immutable at once: copied to the immutable store. */
  private def copied(is: Int*)(recovered: ChainStorage.Recovered[IO, Int]): IO[Unit] =
    typed(is.toList.traverse_ { i =>
      val (ref, raw) = block(i)
      recovered.persistence.extended(ref, raw, Vector(ref), Anchor(Some(ref), i))
    })

  test("the received log is collected 60 s after blocks are copied to the immutable store") {
    node { (_, n) =>
      for
        _ <- n.act(put(1, 2, 3))
        // both copies at 0 s: one collection, at 60 s, below block 2's slot
        _ <- n.act(copied(1, 2))
        start <- n.hashes
        _ <- n.advance(60.seconds - 1.nanosecond)
        early <- n.hashes
        _ <- n.advance(1.nanosecond)
        due <- n.hashes
      yield
        assertEquals(start, hashesOf(1, 2, 3))
        assertEquals(early, hashesOf(1, 2, 3), "nothing is collected before 60 s")
        assertEquals(due, hashesOf(2, 3), "block 1's file, wholly below block 2's slot, at 60 s")
    }
  }

  test("a collection is timed from the copy, not from the backing-store work after it") {
    // blocks 1 and 2 are copied in one batch at 0 s; the backing store, brought to block 2 after
    // the copy, takes 7 s. The collection is due 60 s after the copy, as the reference's copy runner
    // schedules it once the copy returns (`copyToImmutableDBRunner`, `Background.hs` 257-296,
    // ouroboros-consensus 82ecba329), not at 70 s, 60 s after the backing store is done
    Deferred[IO, Unit].flatMap { gate =>
      nodeWith(gated(gate)) { (_, n) =>
        val (ref1, raw1) = block(1)
        val (ref2, raw2) = block(2)
        val release = gate.complete(()).void
        (for
          _ <- n.act(put(1, 2, 3))
          _ <- n.act(recovered =>
            typed(recovered.persistence.extended(ref1, raw1, Vector.empty, Anchor(None, 0)))
          )
          _ <- n.act(recovered =>
            typed(
              recovered.persistence.extended(ref2, raw2, Vector(ref1, ref2), Anchor(Some(ref2), 2))
            )
          )
          // the copy is done and the backing store is at work: 7 s pass
          _ <- n.advance(7.seconds)
          _ <- release *> n.tick
          _ <- n.advance(53.seconds - 1.nanosecond)
          early <- n.hashes
          _ <- n.advance(1.nanosecond)
          due <- n.hashes
        yield
          assertEquals(early, hashesOf(1, 2, 3), "nothing is collected before 60 s")
          assertEquals(due, hashesOf(2, 3), "block 1's file at 60 s, 60 s after the copy")
        ).guarantee(release)
      }
    }
  }

  test("copies 10 s apart are collected each at its own time, below its own slot") {
    node { (_, n) =>
      for
        _ <- n.act(put(1, 2, 3, 4))
        _ <- n.act(copied(1, 2))
        _ <- n.advance(10.seconds)
        _ <- n.act(copied(3))
        _ <- n.advance(50.seconds)
        first <- n.hashes
        _ <- n.advance(10.seconds - 1.nanosecond)
        between <- n.hashes
        _ <- n.advance(1.nanosecond)
        second <- n.hashes
      yield
        assertEquals(first, hashesOf(2, 3, 4), "at 60 s, below block 2's slot")
        assertEquals(between, first, "nothing more before 70 s")
        assertEquals(second, hashesOf(3, 4), "at 70 s, below block 3's slot")
    }
  }

  test("a restart drops what was scheduled; the next copy's collection covers it") {
    node { (_, n) =>
      for
        _ <- n.act(put(1, 2, 3, 4))
        _ <- n.act(copied(1, 2))
        _ <- n.advance(30.seconds)
        _ <- n.restart
        _ <- n.advance(170.seconds)
        restarted <- n.hashes
        // at 200 s: due at 260 s, below block 3's slot, which takes block 1's file too
        _ <- n.act(copied(3))
        _ <- n.advance(60.seconds - 1.nanosecond)
        early <- n.hashes
        _ <- n.advance(1.nanosecond)
        due <- n.hashes
      yield
        assertEquals(restarted, hashesOf(1, 2, 3, 4), "no collection runs after the restart")
        assertEquals(early, hashesOf(1, 2, 3, 4), "nor before 60 s after the next copy")
        assertEquals(due, hashesOf(3, 4), "then every file below block 3's slot goes")
    }
  }

  test("a collection that fails is reported, and the storage refuses the next block") {
    node { (dir, n) =>
      for
        failed <- Ref.of[IO, Option[NodeError]](None)
        reported <- Ref.of[IO, Option[ChainStorage.CollectionStopped]](None)
        _ <- n.act(put(1, 2, 3))
        _ <- n.act(copied(1, 2))
        // the received log's directory goes: the collection at 60 s cannot sync it
        _ <- IO.blocking {
          val received = (dir / "received").toNioPath
          Using.resource(JFiles.list(received))(_.iterator.asScala.toVector).foreach(JFiles.delete)
          JFiles.delete(received)
        }
        _ <- n.advance(60.seconds)
        _ <- n.act(_.collectionsStopped.flatMap(stopped => reported.set(Some(stopped))))
        _ <- n.act { recovered =>
          Handle
            .allow[NodeError] {
              val (ref, raw) = block(3)
              recovered.persistence.extended(ref, raw, Vector(ref), Anchor(Some(ref), 3))
            }
            .rescue(error => failed.set(Some(error)))
        }
        error <- failed.get
        stopped <- reported.get
      yield
        stopped match
          case Some(
                ChainStorage.CollectionStopped.Failed(StorageError.Io(_, _: NoSuchFileException))
              ) =>
            ()
          case other =>
            fail(s"the collection's failure is reported for the node to stop, got $other")
        error match
          case Some(StorageError.Io(_, _: NoSuchFileException)) => ()
          case other => fail(s"the next block is refused with the collection's failure, got $other")
    }
  }

  test("the storage's open cuts a received-log file at the first record the node's check refuses") {
    // three records to a file: [1 M 4] [5 U 6] [7], M holding block 2's reference over block 3's
    // body (it decodes, but its header is not the record's) and U block 6's reference over bytes
    // that do not decode. The node's start-up check refuses both, as the reference's parse stops
    // at a block it cannot read (`parseBlockFile`, `BlockReadErr`,
    // `Storage/VolatileDB/Impl/Parser.hs` 70-107, ouroboros-consensus 82ecba329): each file is cut
    // there and the third is read all the same, at this open and the next
    StorageTestKit.tempDir.use { dir =>
      val mismatched = block(2)._1 -> block(3)._2
      val undecodable = block(6)._1 -> Bytes(Vector(0xff.toByte, 0x00.toByte))
      val written =
        Vector(block(1), mismatched, block(4), block(5), undecodable, block(6), block(7))
      def opened: IO[Set[Bytes]] =
        typed(
          ChainStorage
            .open[IO, Int](
              dir,
              config,
              rules,
              0,
              _ => IO.unit,
              StateBacking.none[IO, Int],
              _ => IO.unit
            )(ReceivedBlockRecovery.startUp(epochSlots))
            .use(_.received.hashes)
        )
      for
        _ <- IO(assert(blocks.size >= 7, s"seven blocks, found ${blocks.size}"))
        _ <- StorageTestKit.ok(
          VolatileStore
            .open[IO](dir / "received", 3)
            .use((log, _) => written.traverse_((ref, raw) => log.append(ref, raw)))
        )
        first <- opened
        records <- IO(
          Using
            .resource(JFiles.list((dir / "received").toNioPath))(
              _.iterator.asScala.toVector.sortBy(_.toString)
            )
            .map(f => VolatileStore.decodeAll(JFiles.readAllBytes(f)).size)
        )
        again <- opened
      yield
        assertEquals(first, hashesOf(1, 5, 7), "each file cut at its refused record")
        assertEquals(records.take(3), Vector(1, 1, 1), "one record left in each file")
        assertEquals(again, first, "and the next open reads the same")
    }
  }
