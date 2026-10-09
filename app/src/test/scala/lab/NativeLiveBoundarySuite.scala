// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.network.ChainSync
import scala.concurrent.duration.*

class NativeLiveBoundarySuite extends munit.FunSuite:
  private val F = SyntheticBoundaryCompositionFixture
  private val L = NativeLiveBoundary
  private def point(b: SequenceInput.Block): Point =
    Point(b.header.hash, b.header.slot, b.header.blockNo)
  private def wire(p: Point): ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.from(p.slot).toOption.get, p.hash)

  test("network next waits for publication and stops at first applied successor") {
    (for
      runtime <- F.boundaryRuntime
      initial <- runtime.snapshot.map(_.state.certificates.state.tip)
      cursor <- Ref.of[IO, Int](0)
      fetches <- Ref.of[IO, Int](0)
      callbacks <- Ref.of[IO, Vector[L.Observation]](Vector.empty)
      released <- Ref.of[IO, Boolean](false)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point]) = IO {
          assertEquals(candidates, Vector(wire(initial)))
          wire(initial)
        }
        def next = for
          index <- cursor.get
          state <- runtime.snapshot
          _ = assertEquals(
            state.state.certificates.state.tip,
            if index == 0 then initial else point(F.blocks(index - 1))
          )
          _ <- cursor.update(_ + 1)
        yield BoundedChainFollower.Event.Forward(F.blocks(index).original.envelope)
        def fetch(p: ChainSync.Point) = for
          index <- fetches.getAndUpdate(_ + 1)
          _ = assertEquals(p, wire(point(F.blocks(index))))
        yield F.blocks(index).original.block
      outcome <- L.run(
        runtime,
        Resource.make(IO.pure(peer))(_ => released.set(true)),
        initial,
        F.epochLength
      )(o => callbacks.update(_ :+ o))
      records <- callbacks.get
      count <- cursor.get
      closed <- released.get
      expected = F.blocks.takeWhile(_.header.slot < F.epochLength).size + 1
      _ = assertEquals(count, expected)
      _ = assert(outcome.boundaryReached && closed)
      _ = assertEquals(outcome.reason, "boundaryApplied")
      _ = assertEquals(outcome.peerOpens, 1)
      _ = assertEquals(outcome.peerCloses, 1)
      _ = assertEquals(outcome.driver.get.counters.acceptedBlocks, expected.toLong)
      _ = assertEquals(outcome.snapshot.state.certificates.state.tip, point(F.blocks(expected - 1)))
      _ = assert(outcome.snapshot.state.acquisition.size <= 8)
      _ = assertEquals(records.size, 2 * expected)
      _ = records.grouped(2).foreach { pair =>
        assertEquals(pair(0).applied, None)
        assert(pair(1).applied.nonEmpty)
        assertEquals(pair(0).original, pair(1).original)
        assertEquals(pair(0).announced, pair(1).announced)
        assert(pair(0).arrived <= pair(0).fetched)
        assert(pair(1).fetched <= pair(1).applied.get)
      }
    yield ()).unsafeToFuture()
  }

  test("foreign intersection rejects before fetch and releases peer") {
    (for
      runtime <- F.boundaryRuntime
      initial <- runtime.snapshot.map(_.state.certificates.state.tip)
      pulls <- Ref.of[IO, Int](0)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(point(F.blocks.head)))
        def next = pulls.update(_ + 1).as(BoundedChainFollower.Event.Await)
        def fetch(p: ChainSync.Point) = pulls.update(_ + 1).as(Bytes.empty)
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      n <- pulls.get
      _ = assertEquals(n, 0)
      _ = assert(!outcome.boundaryReached)
      _ = assert(outcome.reason.contains("foreign intersection"))
      _ = assertEquals(outcome.peerCloses, 1)
      _ = assertEquals(outcome.snapshot.state.certificates.state.tip, initial)
    yield ()).unsafeToFuture()
  }

  test("announced successor cannot cause success before rejected signature publication") {
    (for
      runtime <- F.boundaryRuntime
      initial <- runtime.snapshot.map(_.state.certificates.state.tip)
      cursor <- Ref.of[IO, Int](0)
      crossing = F.blocks.takeWhile(_.header.slot < F.epochLength).size
      originals = F.blocks.take(crossing) :+ F.invalidSignature(F.blocks(crossing))
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next =
          cursor.get.map(i => BoundedChainFollower.Event.Forward(originals(i).original.envelope))
        def fetch(p: ChainSync.Point) =
          cursor.getAndUpdate(_ + 1).map(i => originals(i).original.block)
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      _ = assert(!outcome.boundaryReached)
      _ = assert(outcome.driver.exists(_.stop.isInstanceOf[EphemeralStreaming.Stop.Rejected]))
      _ = assertEquals(outcome.observations.last.applied, None)
      _ = assertEquals(outcome.snapshot.state.certificates.state.tip, point(F.blocks(crossing - 1)))
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }

  test("fetch failure preserves prior state and closes resource") {
    (for
      runtime <- F.boundaryRuntime
      initial <- runtime.snapshot.map(_.state.certificates.state.tip)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next = IO.pure(BoundedChainFollower.Event.Forward(F.blocks.head.original.envelope))
        def fetch(p: ChainSync.Point) =
          IO.raiseError[Bytes](new RuntimeException("scripted fetch failure"))
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      _ = assert(!outcome.boundaryReached)
      _ = assert(outcome.reason.contains("scripted fetch failure"))
      _ = assertEquals(outcome.snapshot.state.certificates.state.tip, initial)
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }

  test("deadline covers stalled intersection and releases acquired peer") {
    TestControl
      .executeEmbed(for
        runtime <- F.boundaryRuntime
        initial <- runtime.snapshot.map(_.state.certificates.state.tip)
        released <- Ref.of[IO, Boolean](false)
        peer = new BoundedChainFollower.Peer[IO]:
          def intersect(c: Vector[ChainSync.Point]) = IO.never[ChainSync.Point]
          def next = IO.never[BoundedChainFollower.Event]
          def fetch(p: ChainSync.Point) = IO.never[Bytes]
        outcome <- L.run(
          runtime,
          Resource.make(IO.pure(peer))(_ => released.set(true)),
          initial,
          F.epochLength,
          EphemeralStreaming.Limits(duration = 50.millis)
        )(_ => IO.unit)
        closed <- released.get
        _ = assert(closed && !outcome.boundaryReached)
        _ = assertEquals(outcome.reason, "deadline")
        _ = assertEquals(outcome.peerCloses, 1)
        _ = assertEquals(outcome.snapshot.state.certificates.state.tip, initial)
      yield ())
      .unsafeToFuture()
  }

  test("await events are bounded independently of delivered block events") {
    (for
      runtime <- F.boundaryRuntime
      initial <- runtime.snapshot.map(_.state.certificates.state.tip)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next = IO.pure(BoundedChainFollower.Event.Await)
        def fetch(p: ChainSync.Point) =
          IO.raiseError[Bytes](new RuntimeException("unexpected fetch"))
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength,
        EphemeralStreaming.Limits(maxEvents = 3, maxBlocks = 3)
      )(_ => IO.unit)
      _ = assertEquals(outcome.networkEvents, 3L)
      _ = assert(outcome.reason.contains("network event limit"))
      _ = assert(!outcome.boundaryReached)
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }

  test("exact selected intersection rollback is a once-only state-preserving confirmation") {
    (for
      runtime <- F.boundaryRuntime
      before <- runtime.snapshot
      initial = before.state.certificates.state.tip
      cursor <- Ref.of[IO, Int](0)
      fetched <- Ref.of[IO, Boolean](false)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next = cursor.getAndUpdate(_ + 1).flatMap {
          case 0 => IO.pure(BoundedChainFollower.Event.Backward(wire(initial)))
          case 1 =>
            runtime.snapshot.map { after =>
              assertEquals(after.state.id, before.state.id)
              assertEquals(after.state.revision, before.state.revision)
              BoundedChainFollower.Event.Forward(F.blocks.head.original.envelope)
            }
          case _ => IO.raiseError(new RuntimeException("script complete"))
        }
        def fetch(p: ChainSync.Point) = fetched.set(true).as(F.blocks.head.original.block)
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      didFetch <- fetched.get
      _ = assert(didFetch && !outcome.boundaryReached)
      _ = assertEquals(outcome.initialIntersectionConfirmations, 1)
      _ = assertEquals(outcome.observations.size, 1)
      _ = assert(outcome.observations.head.applied.nonEmpty)
      _ = assertEquals(outcome.snapshot.state.certificates.state.tip, point(F.blocks.head))
      _ = assert(outcome.reason.contains("script complete"))
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }

  test("different point initial rollback rejects before fetching") {
    (for
      runtime <- F.boundaryRuntime
      before <- runtime.snapshot
      initial = before.state.certificates.state.tip
      fetched <- Ref.of[IO, Boolean](false)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next = IO.pure(BoundedChainFollower.Event.Backward(wire(point(F.blocks.head))))
        def fetch(p: ChainSync.Point) = fetched.set(true).as(Bytes.empty)
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      didFetch <- fetched.get
      _ = assert(!didFetch && !outcome.boundaryReached)
      _ = assertEquals(outcome.initialIntersectionConfirmations, 0)
      _ = assertEquals(outcome.snapshot.state.id, before.state.id)
      _ = assert(outcome.reason.contains("rollback unsupported"))
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }

  test("repeated exact initial rollback rejects without changing runtime") {
    (for
      runtime <- F.boundaryRuntime
      before <- runtime.snapshot
      initial = before.state.certificates.state.tip
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next = IO.pure(BoundedChainFollower.Event.Backward(wire(initial)))
        def fetch(p: ChainSync.Point) =
          IO.raiseError[Bytes](new RuntimeException("unexpected fetch"))
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      _ = assert(!outcome.boundaryReached)
      _ = assertEquals(outcome.initialIntersectionConfirmations, 1)
      _ = assertEquals(outcome.networkEvents, 2L)
      _ = assertEquals(outcome.snapshot.state.id, before.state.id)
      _ = assert(outcome.reason.contains("rollback unsupported"))
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }

  test("rollback to initial point after a forward rejects and preserves published block") {
    (for
      runtime <- F.boundaryRuntime
      initial <- runtime.snapshot.map(_.state.certificates.state.tip)
      cursor <- Ref.of[IO, Int](0)
      peer = new BoundedChainFollower.Peer[IO]:
        def intersect(c: Vector[ChainSync.Point]) = IO.pure(wire(initial))
        def next = cursor.getAndUpdate(_ + 1).map {
          case 0 => BoundedChainFollower.Event.Forward(F.blocks.head.original.envelope)
          case _ => BoundedChainFollower.Event.Backward(wire(initial))
        }
        def fetch(p: ChainSync.Point) = IO.pure(F.blocks.head.original.block)
      outcome <- L.run(
        runtime,
        Resource.pure[IO, BoundedChainFollower.Peer[IO]](peer),
        initial,
        F.epochLength
      )(_ => IO.unit)
      _ = assert(!outcome.boundaryReached)
      _ = assertEquals(outcome.initialIntersectionConfirmations, 0)
      _ = assertEquals(outcome.snapshot.state.certificates.state.tip, point(F.blocks.head))
      _ = assertEquals(outcome.observations.size, 1)
      _ = assert(outcome.observations.head.applied.nonEmpty)
      _ = assert(outcome.reason.contains("rollback unsupported"))
      _ = assertEquals(outcome.peerCloses, 1)
    yield ()).unsafeToFuture()
  }
