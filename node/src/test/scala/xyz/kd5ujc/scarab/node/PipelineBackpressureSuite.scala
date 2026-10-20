// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import scala.concurrent.duration.*

/** What holds a fast peer back is the protocol and the session's own pipeline, not a queue that
  * stops the socket (the multiplexer's ingress queues never wait for a reader, see [[Mux]]): the
  * pipeline of a session asks for at most [[Peer.Depth]] batches ahead of the one the session is
  * applying, so a session that reads slowly keeps the peer from sending, and what the peer has sent
  * stays within the ingress limit of its protocol.
  */
class PipelineBackpressureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val readAhead = 4

  private lazy val forged: ForgedChain =
    ForgedChain.forge(5000 until 5060).unsafeRunSync()(using munitIORuntime)

  test("a session that applies slowly holds the pipeline back; reading faster releases it") {
    for
      honest <- ServedChain(forged)
      polls <- Ref.of[IO, Int](0)
      counting = new ServerHandlers[IO, NtNHeader](
        honest.codec,
        honest.chainSync.map { inner =>
          new ChainSyncServer.Source[IO, NtNHeader]:
            def intersect(points: Vector[ChainSync.Point]) = inner.intersect(points)
            def poll = polls.update(_ + 1) *> inner.poll
            def await = inner.await
        },
        honest.blocks,
        honest.transactions
      )
      pair <- Bearer.inMemory[IO](256)
      (theirs, ours) = pair
      served <- Handle
        .allow[NetworkError](NodeToNodeServer.accept(theirs, magic, counting).void)
        .rescue(_ => IO.unit)
        .start
      observed <- Handle
        .allow[PeerError] {
          Handle
            .allow[NetworkError](
              NodeToNode
                .over[IO, NtNHeader](ours, magic, NtNHeader.codec, NodeToNode.Settings())
                .use { connection =>
                  Peer
                    .over[IO](
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        magic,
                        readAhead = readAhead,
                        byronEpochSlots = ForgedChain.geometry.byron.epochSlots
                      ),
                      _ => IO.unit
                    )
                    .use { peer =>
                      val pull = peer.next.void
                      for
                        _ <- peer.intersect(Vector(ChainSync.Point.Origin))
                        // the session applies one instruction per 100 ms
                        _ <- (1 to 5).toList.traverse_(_ => pull *> IO.sleep(100.millis))
                        slow <- polls.get
                        // then it reads as fast as it can
                        _ <- (1 to 20).toList.traverse_(_ => pull)
                        fast <- polls.get
                      yield (slow, fast)
                    }
                }
            )
            .rescue(e => IO.raiseError(new RuntimeException(e.message)))
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      _ <- served.cancel
    yield
      val (slow, fast) = observed
      // 5 instructions were taken; the pipeline may hold `Depth` batches of `readAhead` besides the
      // one in use, and the first request goes alone
      val allowed = 1 + (Peer.Depth + 1) * readAhead
      assert(slow <= 5 + allowed, s"the peer was asked $slow times while the session took 5")
      assert(fast > slow, s"reading faster did not release the pipeline: $slow, then $fast")
  }

  private def connected[A](
      check: Option[HeaderCheck[IO]] = None,
      finish: Boolean = true,
      prepare: Peer.Prepare[IO] = _ => IO.unit
  )(
      body: (Peer.Controlled[IO], Ref[IO, Int]) => Raise[IO, PeerError] ?=> IO[A]
  ): IO[A] =
    for
      honest <- ServedChain(forged)
      intersections <- Ref.of[IO, Int](0)
      handlers = new ServerHandlers[IO, NtNHeader](
        honest.codec,
        honest.chainSync.map { inner =>
          new ChainSyncServer.Source[IO, NtNHeader]:
            def intersect(points: Vector[ChainSync.Point]) =
              intersections.update(_ + 1) *> inner.intersect(points)
            def poll = inner.poll
            def await = inner.await
        },
        honest.blocks,
        honest.transactions
      )
      pair <- Bearer.inMemory[IO](256)
      (theirs, ours) = pair
      result <- Handle
        .allow[NetworkError](NodeToNodeServer.accept(theirs, magic, handlers).void)
        .rescue(_ => IO.unit)
        .background
        .use { _ =>
          Handle
            .allow[NetworkError] {
              NodeToNode
                .over[IO, NtNHeader](ours, magic, NtNHeader.codec, NodeToNode.Settings())
                .use { connection =>
                  Peer
                    .over[IO](
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        magic,
                        readAhead = 2,
                        byronEpochSlots = ForgedChain.geometry.byron.epochSlots
                      ),
                      prepare,
                      headers = check
                    )
                    .use { peer =>
                      Handle
                        .allow[PeerError](body(peer, intersections))
                        .rescue(e => IO(fail(e.message)))
                        .flatTap { _ =>
                          (peer.quiesce *> peer.quiesce *> connection
                            .finish(2.seconds)
                            .flatMap(clean =>
                              IO(assert(clean, "drained hot protocols must finish"))
                            ))
                            .whenA(finish)
                        }
                    }
                }
            }
            .rescue(e => IO(fail(e.message)))
        }
    yield result

  test("repeated intersections discard old batches, restore capacity and permit clean quiescence") {
    for
      starts <- Ref.of[IO, Vector[ChainSync.Point]](Vector.empty)
      gates <- Ref.of[IO, Vector[ChainSync.Point]](Vector.empty)
      check = new HeaderCheck[IO]:
        def start(point: ChainSync.Point) = starts
          .update(_ :+ point)
          .as(new HeaderGate[IO]:
            def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
            def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
              gates.update(_ :+ point))
      _ <- connected(Some(check)) { (peer, intersections) =>
        (0 until 8).toVector.traverse_ { index =>
          val point = forged.headers(index).point
          for
            found <- peer.intersect(Vector(point))
            backward <- peer.next
            forward <- peer.next
            _ <- IO {
              assertEquals(found, Some(point))
              backward match
                case SyncEvent.RollBackward(actual, _) => assertEquals(actual, point)
                case other => fail(s"expected new intersection rollback, got $other")
              forward match
                case SyncEvent.RollForward(actual, _) =>
                  assertEquals(actual.point, forged.headers(index + 1).point)
                case other => fail(s"expected new intersection successor, got $other")
            }
          yield ()
        } *> intersections.get.map(n => assertEquals(n, 8))
      }
      started <- starts.get
      checked <- gates.get
    yield
      val expected = forged.headers.take(8).map(_.point)
      assertEquals(started, expected)
      val used = checked.foldLeft(Vector.empty[ChainSync.Point]) { (runs, point) =>
        if runs.lastOption.contains(point) then runs else runs :+ point
      }
      assertEquals(used, expected)
  }

  private def held(
      entered: Deferred[IO, Unit],
      release: Deferred[IO, Unit],
      result: Option[Fault],
      finalized: Option[Deferred[IO, Unit]] = None
  ): HeaderCheck[IO] = new HeaderCheck[IO]:
    def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
      def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
      def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
        (entered.complete(()).void *> release.get *>
          result.fold(IO.unit)(fault => Fault.land[IO, Unit](Left(fault))))
          .guarantee(finalized.traverse_(_.complete(()).void)))

  test(
    "a pending typed failure or defect survives drain and is replayed without a new wire request"
  ) {
    val error = PeerError.Unavailable(PeerError.Unavailability.SessionEnded)
    val defect = new IllegalStateException("injected header-check defect")
    Vector(Fault.Failed(error), Fault.Defect(defect)).traverse_ { fault =>
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        _ <- connected(Some(held(entered, release, Some(fault))), finish = false) {
          (peer, intersections) =>
            for
              _ <- peer.intersect(Vector(ChainSync.Point.Origin))
              _ <- Fault.fiber(peer.next).background.use(_ => entered.get)
              _ <- release.complete(())
              first <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
              again <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
              count <- intersections.get
            yield
              assertEquals(first, Left(fault))
              assertEquals(again, Left(fault))
              assertEquals(count, 1)
        }
      yield ()
    }
  }

  test(
    "a cancelled drain retains cancellation and releases its supervised work with the resource"
  ) {
    IO(forged).flatMap { _ =>
      TestControl.executeEmbed {
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          finalized <- Deferred[IO, Unit]
          _ <- connected(Some(held(entered, release, None, Some(finalized))), finish = false) {
            (peer, intersections) =>
              for
                _ <- peer.intersect(Vector(ChainSync.Point.Origin))
                _ <- Fault.fiber(peer.next).background.use(_ => entered.get)
                _ <- Resource
                  .make(Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin))).start)(_.cancel)
                  .use { draining =>
                    for
                      // Virtual time advances only once the drain is suspended behind the held gate.
                      _ <- IO.sleep(1.second)
                      _ <- draining.cancel
                      first <- draining.join
                      _ <- Resource
                        .make(Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin))).start)(
                          _.cancel
                        )
                        .use { again =>
                          for
                            second <- again.join.timeout(2.seconds)
                            count <- intersections.get
                          yield
                            assert(first.isInstanceOf[Outcome.Canceled[?, ?, ?]])
                            assert(second.isInstanceOf[Outcome.Canceled[?, ?, ?]])
                            assertEquals(count, 1)
                        }
                    yield ()
                  }
              yield ()
          }
          stopped <- finalized.tryGet
        yield assertEquals(stopped, Some(()))
      }
    }
  }

  test("a failed prefetch is landed while draining rather than waiting for unpublished batches") {
    val defect = new IllegalStateException("injected prefetch defect")
    for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      _ <- connected(
        finish = false,
        prepare = _ => entered.complete(()).void *> release.get *> IO.raiseError[Unit](defect)
      ) { (peer, intersections) =>
        for
          _ <- peer.intersect(Vector(ChainSync.Point.Origin))
          _ <- peer.next
          _ <- entered.get
          _ <- release.complete(())
          result <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
          count <- intersections.get
        yield
          assertEquals(result, Left(Fault.Defect(defect)))
          assertEquals(count, 1)
      }
    yield ()
  }
