// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.{Deferred, IO}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.ledger.{NodeError, StorageError}
import xyz.kd5ujc.scarab.network.ChainSyncServer.Update
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.Fatal
import xyz.kd5ujc.scarab.protocol.NtNHeader
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import xyz.kd5ujc.scarab.storage.{FileImmutableStore, ImmutableStore}
import xyz.kd5ujc.scarab.storage.StorageTestKit.tempDir

/** A FindIntersect answered while our own chain selection switches forks. The reference checks each
  * offered point against the chain as it is and moves the follower in the same transaction
  * (`checkIfPointOnChain`, ouroboros-consensus 82ecba32, `Storage/ChainDB/Impl/Follower.hs`
  * 457-525), so a follower never stands on a point the chain no longer holds. The store's lookup of
  * a point outside the volatile chain is held on a gate while the chain forks; only completed
  * `Deferred` values order the steps.
  */
class IntersectForkSuite extends FailsOnUncaught:
  import RelayKit.*

  private def ok[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](body).rescue(error => IO(fail(error.message)))

  /** A point that is neither volatile nor stored, so an intersection asks the store for it. */
  private val unstored: Point = ref(77).point

  /** `underlying`, except that reading `unstored` signals `entered` and then waits for `gate`. */
  private def gated(
      underlying: ImmutableStore[IO],
      entered: Deferred[IO, Unit],
      gate: Deferred[IO, Unit]
  ): ImmutableStore[IO] = new ImmutableStore[IO]:
    def append(ref: BlockRef, raw: Bytes)(using Raise[IO, StorageError]): IO[Unit] =
      underlying.append(ref, raw)
    def sync(using Raise[IO, StorageError]): IO[Unit] = underlying.sync
    def tip(using Raise[IO, StorageError]): IO[Option[BlockRef]] = underlying.tip
    def get(point: Point)(using Raise[IO, StorageError]): IO[Option[Bytes]] =
      if point == unstored then entered.complete(()) *> gate.get *> underlying.get(point)
      else underlying.get(point)
    def refOf(point: Point.Block)(using Raise[IO, StorageError]): IO[Option[BlockRef]] =
      underlying.refOf(point)
    def pointsAtSlot(slot: BigInt)(using Raise[IO, StorageError]): IO[Vector[BlockRef]] =
      underlying.pointsAtSlot(slot)
    def pointAtOrAfterSlot(slot: BigInt)(using Raise[IO, StorageError]): IO[Option[BlockRef]] =
      underlying.pointAtOrAfterSlot(slot)
    def byBlockNo(n: BigInt)(using Raise[IO, StorageError]): IO[Option[ImmutableStore.Stored]] =
      underlying.byBlockNo(n)
    def stream(after: Point)(using Raise[IO, StorageError]): Stream[IO, ImmutableStore.Stored] =
      underlying.stream(after)

  /** `step` 's value, or `None` once the fatal sink has a report it did not have before: a storage
    * failure under the cursor reports to the sink and then never returns.
    */
  private def untilFatal[A](fatals: SignallingRef[IO, Vector[Fatal]])(step: IO[A]): IO[Option[A]] =
    fatals.get.flatMap(before =>
      IO.race(fatals.waitUntil(_.size > before.size), step).map(_.toOption)
    )

  /** What a follower is told: the intersection, then the next three instructions. */
  private final case class Observed(
      tip: Tip,
      intersection: Option[Either[Tip, (Point, Tip)]],
      instructions: List[Option[Option[Update[NtNHeader]]]],
      fatal: Vector[Fatal]
  )

  /** Blocks 1 to 5, all volatile. `offered` is sent while the store is asked for `unstored`, and in
    * that window block 5 leaves the chain and the chain forks to 5'.
    */
  private def raceFork(offered: Vector[Point]): IO[Observed] =
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            entered <- Deferred[IO, Unit]
            gate <- Deferred[IO, Unit]
            fatals <- SignallingRef.of[IO, Vector[Fatal]](Vector.empty)
            server <- ChainServer.open[IO](
              Some(gated(store, entered, gate)),
              None,
              headerOf,
              failure => fatals.update(_ :+ failure)
            )
            persistence = server.persistence[Unit]
            _ <- (1 to 5).toList.traverse_(i =>
              persistence.extended(ref(i), raw(i), Vector.empty, Anchor[Unit](None, ()))
            )
            observed <- server.source.use { source =>
              untilFatal(fatals)(source.intersect(offered)).background
                .use { intersecting =>
                  for
                    asked <- IO.race(entered.get, intersecting)
                    _ <- asked.fold(
                      _ => IO.unit,
                      _ => IO(fail("the intersection never asked the store for that point"))
                    )
                    _ <- persistence.rolledBack(ref(4).point)
                    _ <- persistence.extended(
                      ref(5, salt = 1),
                      raw(5, salt = 1),
                      Vector.empty,
                      Anchor[Unit](None, ())
                    )
                    forked <- server.tip
                    _ <- gate.complete(())
                    found <- intersecting.flatMap(_.embedNever)
                    instructions <- List.fill(3)(untilFatal(fatals)(source.poll)).sequence
                  yield (forked, found, instructions)
                }
                .guarantee(gate.complete(()).void)
            }
            (forked, found, instructions) = observed
            reported <- fatals.get
          yield Observed(forked, found, instructions, reported)
        }
      }
    }

  private def forward(n: Int, salt: Int, tip: Tip): Option[Option[Update[NtNHeader]]] =
    Some(Some(Update.RollForward(served(n, salt).header, tip)))

  test("a point that leaves the chain during the intersection is not found, and the next one is") {
    for observed <- raceFork(Vector(unstored, ref(5).point, ref(3).point))
    yield
      assertEquals(observed.fatal, Vector.empty[Fatal], "the node reported a fatal failure")
      assertEquals(observed.tip, Tip(ref(5, salt = 1).point, observed.tip.blockNo))
      assertEquals(observed.intersection, Some(Right(ref(3).point -> observed.tip)))
      assertEquals(
        observed.instructions,
        List(
          Some(Some(Update.RollBackward(ref(3).point, observed.tip))),
          forward(4, 0, observed.tip),
          forward(5, 1, observed.tip)
        )
      )
  }

  test("an intersection whose only point on the chain leaves it during the lookup is not found") {
    for observed <- raceFork(Vector(unstored, ref(5).point))
    yield
      assertEquals(observed.fatal, Vector.empty[Fatal], "the node reported a fatal failure")
      assertEquals(observed.intersection, Some(Left(observed.tip)))
      // the follower was not moved: it still starts with its roll-back to Origin
      assertEquals(
        observed.instructions,
        List(
          Some(Some(Update.RollBackward(Point.Origin, observed.tip))),
          forward(1, 0, observed.tip),
          forward(2, 0, observed.tip)
        )
      )
  }

  test("a storage failure reading the block after the cursor still stops the node") {
    val error = StorageError.Io("immutable chunk", new java.io.IOException("read failed"))
    val failing = new ImmutableStore[IO]:
      def append(ref: BlockRef, raw: Bytes)(using Raise[IO, StorageError]) = IO.unit
      def sync(using Raise[IO, StorageError]) = IO.unit
      def tip(using Raise[IO, StorageError]) = IO.pure(Some(ref(1)))
      def get(point: Point)(using Raise[IO, StorageError]) =
        IO.pure(Option.when(point == ref(1).point)(raw(1)))
      def refOf(point: Point.Block)(using Raise[IO, StorageError]) =
        IO.pure(Option.when(point == ref(1).point)(ref(1)))
      def pointsAtSlot(slot: BigInt)(using Raise[IO, StorageError]) = IO.pure(Vector.empty)
      def pointAtOrAfterSlot(slot: BigInt)(using Raise[IO, StorageError]) = IO.pure(None)
      def byBlockNo(n: BigInt)(using Raise[IO, StorageError]) = IO.pure(None)
      def stream(after: Point)(using Raise[IO, StorageError]) =
        Stream.eval(error.raise[IO, ImmutableStore.Stored])
    for
      fatals <- SignallingRef.of[IO, Vector[Fatal]](Vector.empty)
      server <- ChainServer.open[IO](
        Some(failing),
        None,
        headerOf,
        failure => fatals.update(_ :+ failure)
      )
      observed <- server.source.use { source =>
        for
          found <- source.intersect(Vector(ref(1).point))
          back <- source.poll
          next <- untilFatal(fatals)(source.poll)
        yield (found, back, next)
      }
      (found, back, next) = observed
      reported <- fatals.get
    yield
      assertEquals(found.map(_._1), Right(ref(1).point))
      assertEquals(back.collect { case Update.RollBackward(p, _) => p }, Some(ref(1).point))
      assertEquals(next, None, "the poll answered after a storage failure")
      assertEquals(reported, Vector[Fatal](Fatal.Storage(error)))
  }
