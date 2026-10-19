// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Outcome, Ref, Resource}
import cats.effect.std.Queue
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import fs2.io.file.Path
import java.io.IOException
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules}
import xyz.kd5ujc.scarab.ledger.{NodeError, StorageError, StorageStructureError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class SnapshotFailureSuite extends FailsOnUncaught:
  private def point(n: Int): BlockRef = StorageTestKit.block(n)._1

  private given SnapshotFormat[IO, Int] with
    def encode(snapshot: StateSnapshot[Int]) = IO.pure(Right(Array(snapshot.state.toByte)))
    def decode(content: Array[Byte]) =
      IO.pure(Right(StateSnapshot(Some(point(1)), content.headOption.fold(0)(_.toInt))))

  private val rules: BlockRules[IO, Int] = BlockRules.from((_, _, _) => IO.pure(0))

  private def opened(
      dir: Path,
      events: StorageEvents[IO],
      backing: StateBacking[IO, Int],
      every: Int
  )(using Handle[IO, NodeError]): Resource[IO, ChainStorage.Recovered[IO, Int]] =
    ChainStorage.open[IO, Int](
      dir,
      ChainStorage.Config(SnapshotPolicy.EverySlots(every), byronEpochSlots = 21600),
      rules,
      0,
      events,
      backing,
      _ => IO.unit
    )

  private def extend(recovered: ChainStorage.Recovered[IO, Int], n: Int)(using
      Handle[IO, NodeError]
  ): IO[Unit] =
    val (ref, raw) = StorageTestKit.block(n)
    recovered.persistence.extended(ref, raw, Vector(ref), Anchor(Some(ref), n))

  private def typed[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](body).rescue(error => IO(fail(error.message)))

  private def backing(
      retainBody: Raise[IO, StorageError] ?=> IO[Unit] = IO.unit,
      advanceBody: Raise[IO, StorageError] ?=> IO[Unit] = IO.unit
  ): StateBacking[IO, Int] = new StateBacking[IO, Int]:
    def advance(state: Int)(using Raise[IO, StorageError]) = advanceBody
    def settled(state: Int)(using Raise[IO, StorageError]) = IO.pure(state)
    def checkpoint(anchor: BlockRef)(using Raise[IO, StorageError]) = IO.unit
    def available(anchor: Option[BlockRef])(using Raise[IO, StorageError]) = IO.pure(true)
    def restore(anchor: Option[BlockRef])(using Raise[IO, StorageError]) = IO.pure(true)
    def reset(using Raise[IO, StorageError]) = IO.unit
    def retain(stems: Set[String])(using Raise[IO, StorageError]) = retainBody

  test(
    "a periodic storage failure keeps its cause, frees the worker and allows the next snapshot"
  ) {
    StorageTestKit.tempDir.use { dir =>
      val cause = new IOException("retain failed")
      val error = StorageError.Io("checkpoints", cause)
      for
        calls <- Ref.of[IO, Int](0)
        events <- Queue.unbounded[IO, StorageEvent]
        _ <- typed {
          opened(
            dir,
            event => events.offer(event),
            backing(retainBody = calls.getAndUpdate(_ + 1).flatMap {
              case 0 => error.raise[IO, Unit]
              case _ => IO.unit
            }),
            every = 1
          ).use { recovered =>
            for
              _ <- extend(recovered, 1)
              failure <- events.take
                .iterateUntil(_.isInstanceOf[StorageEvent.SnapshotFailed])
                .timeout(3.seconds)
              _ <- IO(
                assertEquals(failure, StorageEvent.SnapshotFailed(SnapshotFailure.Storage(error)))
              )
              _ <- extend(recovered, 2)
              _ <- events.take
                .iterateUntil {
                  case StorageEvent.SnapshotWritten(_, Some(at), _, _) => at == point(2)
                  case _                                               => false
                }
                .timeout(3.seconds)
            yield ()
          }
        }
        // The shutdown and store finalizers completed; reopening uses the same directory safely.
        _ <- StorageTestKit.ok(FileImmutableStore.open[IO](dir / "immutable").use(_ => IO.unit))
      yield ()
    }
  }

  for defect <- List(false, true) do
    test(s"shutdown preserves ${
        if defect then "defects" else "storage failures"
      } and releases the store") {
      StorageTestKit.tempDir.use { dir =>
        val cause = new IllegalStateException("shutdown failure")
        val error = StorageError.Missing("shutdown backing")
        for
          calls <- Ref.of[IO, Int](0)
          events <- Ref.of[IO, Vector[StorageEvent]](Vector.empty)
          _ <- typed {
            opened(
              dir,
              event => events.update(_ :+ event),
              backing(advanceBody = calls.getAndUpdate(_ + 1).flatMap {
                case 0 => IO.unit
                case _ => if defect then IO.raiseError(cause) else error.raise[IO, Unit]
              }),
              every = 1000
            ).use(recovered => extend(recovered, 1))
          }
          observed <- events.get
          failures = observed.collect { case StorageEvent.SnapshotFailed(failure) => failure }
          _ <- IO(
            assertEquals(
              failures,
              Vector(
                if defect then SnapshotFailure.Defect(cause) else SnapshotFailure.Storage(error)
              )
            )
          )
          _ <- IO(assert(!observed.exists(_.isInstanceOf[StorageEvent.SnapshotWritten])))
          _ <- StorageTestKit.ok(FileImmutableStore.open[IO](dir / "immutable").use(_ => IO.unit))
        yield ()
      }
    }

  test("snapshot operations use a fresh caller scope after their opening scope has ended") {
    StorageTestKit.tempDir.use { dir =>
      for
        snapshots <- StorageTestKit.ok(FileLedgerSnapshots.open[IO, Int](dir))
        refused <- SnapshotFailure.capture(snapshots.write(StateSnapshot(None, 0)))
        _ <- IO(
          assertEquals(
            refused,
            Left(
              SnapshotFailure.Storage(
                StorageError.OutOfOrder(StorageStructureError.GenesisSnapshot)
              )
            )
          )
        )
        _ <- IO.blocking(java.nio.file.Files.delete(dir.toNioPath))
        _ <- IO.blocking(java.nio.file.Files.createFile(dir.toNioPath))
        io <- SnapshotFailure.capture(snapshots.list)
      yield io match
        case Left(SnapshotFailure.Storage(StorageError.Io(_, cause))) =>
          assert(cause.isInstanceOf[IOException])
        case other => fail(s"expected classified filesystem failure: $other")
    }
  }

  test("a format refusal remains a value and does not publish a snapshot") {
    val refusal = SnapshotRefusal.WouldDiffer(List(SnapshotDifference.Anchor))
    val refusing = new SnapshotFormat[IO, Int]:
      def encode(snapshot: StateSnapshot[Int]) = IO.pure(Left(refusal))
      def decode(content: Array[Byte]) = summon[SnapshotFormat[IO, Int]].decode(content)
    StorageTestKit.tempDir.use { dir =>
      for
        snapshots <- StorageTestKit.ok(
          FileLedgerSnapshots.open[IO, Int](dir)(using summon, refusing, summon)
        )
        result <- SnapshotFailure.capture(snapshots.write(StateSnapshot(Some(point(1)), 1)))
        files <- SnapshotFailure.capture(snapshots.list)
      yield
        assertEquals(result, Right(Left(refusal)))
        assertEquals(files, Right(Vector.empty))
    }
  }

  test("immutable sync failures use the invoking scope and preserve the filesystem cause") {
    StorageTestKit.tempDir.use { dir =>
      for
        closed <- StorageTestKit.ok {
          FileImmutableStore.open[IO](dir).use { (store, _) =>
            val (ref, raw) = StorageTestKit.block(1)
            store.append(ref, raw).as(store)
          }
        }
        result <- SnapshotFailure.capture(closed.sync)
      yield result match
        case Left(SnapshotFailure.Storage(StorageError.Io(_, cause))) =>
          assert(cause.isInstanceOf[IOException])
        case other => fail(s"expected classified sync failure: $other")
    }
  }

  test("a defect while constructing a snapshot effect is captured before it leaves the scope") {
    val cause = new IllegalStateException("snapshot construction defect")
    IO.defer(SnapshotFailure.capture[IO, Unit](throw cause)).map { result =>
      assertEquals(result, Left(SnapshotFailure.Defect(cause)))
    }
  }

  test("format defects retain their original exception and cancellation stays cancellation") {
    val cause = new IllegalArgumentException("format defect")
    val broken = new SnapshotFormat[IO, Int]:
      def encode(snapshot: StateSnapshot[Int]) = IO.raiseError(cause)
      def decode(content: Array[Byte]) = summon[SnapshotFormat[IO, Int]].decode(content)
    StorageTestKit.tempDir.use { dir =>
      for
        snapshots <- StorageTestKit.ok(
          FileLedgerSnapshots.open[IO, Int](dir)(using summon, broken, summon)
        )
        result <- SnapshotFailure.capture(snapshots.write(StateSnapshot(Some(point(1)), 1)))
        _ <- IO(assertEquals(result, Left(SnapshotFailure.Defect(cause))))
        fiber <- SnapshotFailure.capture(IO.never[Unit]).start
        _ <- fiber.cancel
        outcome <- fiber.join
      yield assert(outcome match
        case Outcome.Canceled() => true
        case _                  => false)
    }
  }
