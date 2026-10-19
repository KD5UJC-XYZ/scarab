// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.mtl.Raise
import fs2.Stream
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.{ImmutableStore, StorageTestKit}

import StorageTestKit.block

class ImmutablePeerSnapshotVerifierSuite extends CatsEffectSuite:
  private def checkedMatches(
      store: xyz.kd5ujc.scarab.storage.ImmutableStore[IO],
      slot: BigInt,
      hash: xyz.kd5ujc.scarab.codec.Bytes
  ): IO[Boolean] = IO.defer {
    given Raise[IO, StorageError] = testRaise
    ImmutablePeerSnapshotVerifier.matches(store, slot, hash)
  }

  private val testRaise: Raise[IO, StorageError] = new Raise[IO, StorageError]:
    def functor = IO.asyncForIO
    def raise[E <: StorageError, A](error: E): IO[A] =
      IO.raiseError(new AssertionError(error.message))

  private def pointStore: IO[(ImmutableStore[IO], Ref[IO, Vector[BlockRef]])] =
    Ref.of[IO, Vector[BlockRef]](Vector.empty).map { points =>
      val store = new ImmutableStore[IO]:
        def append(ref: BlockRef, raw: Bytes)(using Raise[IO, StorageError]) =
          points.update(_ :+ ref)
        def sync(using Raise[IO, StorageError]) = IO.unit
        def tip(using Raise[IO, StorageError]) = points.get.map(_.lastOption)
        def get(point: ChainSync.Point)(using Raise[IO, StorageError]) = IO.pure(None)
        def pointsAtSlot(slot: BigInt)(using Raise[IO, StorageError]) =
          points.get.map(_.filter(_.slot == slot))
        def pointAtOrAfterSlot(slot: BigInt)(using Raise[IO, StorageError]) =
          points.get.map(_.find(_.slot >= slot))
        def byBlockNo(n: BigInt)(using Raise[IO, StorageError]) =
          IO.pure(None)
        def stream(after: ChainSync.Point)(using Raise[IO, StorageError]) =
          Stream.empty
      store -> points
    }

  test("the check compares the exact slot and hash") {
    given Raise[IO, StorageError] = testRaise
    pointStore.flatMap { (store, points) =>
      for
        _ <- points.set((0 until 4).map(n => block(n)._1).toVector)
        tip <- store.tip
        _ <- IO(assertEquals(tip.map(_.slot), Some(BigInt(9))))
        indexed <- store.pointAtOrAfterSlot(BigInt(6))
        _ <- IO(assertEquals(indexed.map(_.slot), Some(BigInt(6))))
        exact <- checkedMatches(store, BigInt(6), block(2)._1.hash)
        wrongHash <- checkedMatches(store, BigInt(6), block(9)._1.hash)
        gap <- checkedMatches(store, BigInt(7), block(2)._1.hash)
        _ <- IO(assert(exact))
        _ <- IO(assert(!wrongHash))
        _ <- IO(assert(!gap))
      yield ()
    }
  }

  test("a point check waiting beyond the immutable tip is cancelable") {
    pointStore.flatMap { (store, _) =>
      for
        waiting <- checkedMatches(store, BigInt(6), block(2)._1.hash).start
        _ <- IO.sleep(scala.concurrent.duration.DurationInt(20).millis)
        _ <- waiting.cancel
        after <- waiting.join
        _ <- IO(assert(after.isCanceled))
      yield ()
    }
  }

  test("a waiting exact-slot check completes when that block is published") {
    pointStore.flatMap { (store, points) =>
      for
        _ <- points.set((0 until 3).map(n => block(n)._1).toVector)
        result <- Deferred[IO, Boolean]
        _ <- checkedMatches(store, BigInt(9), block(3)._1.hash)
          .flatMap(result.complete)
          .start
        _ <- IO.sleep(scala.concurrent.duration.DurationInt(20).millis)
        before <- result.tryGet
        _ <- points.update(_ :+ block(3)._1)
        matches <- result.get
        _ <- IO(assertEquals(before, None))
        _ <- IO(assert(matches))
      yield ()
    }
  }

  test("an immutable-index read failure remains a typed storage error") {
    val missing = StorageError.Missing("immutable index")
    Deferred[IO, StorageError].flatMap { raised =>
      val failing = new ImmutableStore[IO]:
        def append(ref: BlockRef, raw: Bytes)(using Raise[IO, StorageError]) = IO.unit
        def sync(using Raise[IO, StorageError]) = IO.unit
        def tip(using Raise[IO, StorageError]) = summon[Raise[IO, StorageError]].raise(missing)
        def get(point: ChainSync.Point)(using Raise[IO, StorageError]) = IO.pure(None)
        def pointsAtSlot(slot: BigInt)(using Raise[IO, StorageError]) = IO.pure(Vector.empty)
        def pointAtOrAfterSlot(slot: BigInt)(using Raise[IO, StorageError]) = IO.pure(None)
        def byBlockNo(n: BigInt)(using Raise[IO, StorageError]) = IO.pure(None)
        def stream(after: ChainSync.Point)(using Raise[IO, StorageError]) = Stream.empty
      val capture = new Raise[IO, StorageError]:
        def functor = IO.asyncForIO
        def raise[E <: StorageError, A](error: E): IO[A] = raised.complete(error) *> IO.never[A]
      IO.defer {
        given Raise[IO, StorageError] = capture
        ImmutablePeerSnapshotVerifier.matches(failing, BigInt(1), block(0)._1.hash)
      }.start
        .flatMap { fiber =>
          raised.get.flatMap(error => fiber.cancel.as(assertEquals(error, missing)))
        }
    }
  }
