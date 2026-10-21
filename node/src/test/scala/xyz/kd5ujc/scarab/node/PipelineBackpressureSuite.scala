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

/** Body publication has bounded credit independently of the native rolling request window. Real
  * protocol engines and a small retained journal exercise slow consumption, reintersection,
  * failures and guaranteed cancellation cleanup.
  */
class PipelineBackpressureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L

  private lazy val forged: ForgedChain =
    ForgedChain.forge(5000 until 5060).unsafeRunSync()(using munitIORuntime)

  test("body publication stays bounded while a slow consumer holds a long rolling header stream") {
    IO(forged).flatMap { chain =>
      TestControl.executeEmbed {
        for
          prepared <- Ref.of[IO, Int](0)
          delivered <- Ref
            .of[IO, Vector[(ChainSync.Point, xyz.kd5ujc.scarab.codec.Bytes)]](Vector.empty)
          observed <- connected(prepare = raws => prepared.update(_ + raws.size)) { (peer, _) =>
            val pull = peer.next.flatMap {
              case SyncEvent.RollForward(header, _) =>
                peer.fetch(header).flatMap(raw => delivered.update(_ :+ (header.point -> raw)))
              case SyncEvent.RollBackward(_, _) => IO.unit
            }
            for
              _ <- peer.intersect(Vector(ChainSync.Point.Origin))
              _ <- (1 to 5).toList.traverse_(_ => pull)
              // Virtual time advances after runnable publication work has exhausted its credits.
              _ <- IO.sleep(1.second)
              slow <- prepared.get
              slowTaken <- delivered.get.map(_.size)
              _ <- (1 to 20).toList.traverse_(_ => pull)
              fast <- prepared.get
              received <- delivered.get
            yield (slow, slowTaken, fast, received)
          }
        yield
          val (slow, slowTaken, fast, received) = observed
          assert(
            slow <= slowTaken + Peer.Depth + 1,
            s"$slow bodies prepared for $slowTaken consumed with ${Peer.Depth} publication credits"
          )
          assert(fast >= received.size)
          assert(fast > slow, "twenty completed body reads must release blocked publication work")
          assertEquals(
            received,
            chain.headers.zip(chain.raws).take(received.size).map { (header, raw) =>
              header.point -> raw
            }
          )
      }
    }
  }

  private def connected[A](
      check: Option[HeaderCheck[IO]] = None,
      finish: Boolean = true,
      prepare: Peer.Prepare[IO] = _ => IO.unit,
      heightSnapshot: Peer.HeightSnapshot[IO] = ChainSyncHeights.origin
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
                      headers = check,
                      heightSnapshot = heightSnapshot,
                      retainedHeaders = 8
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
      offered <- Ref.of[IO, Vector[ChainSync.Point]](Vector(ChainSync.Point.Origin))
      metadata = new Peer.HeightSnapshot[IO]:
        def capture = offered.get.map(points =>
          Peer.HeightSnapshot.Captured(
            points,
            forged.headers
              .map(h => h.point -> Option(h.blockNo))
              .toMap
              .updated(ChainSync.Point.Origin, None)
          )
        )
        def unavailable[A](cause: xyz.kd5ujc.scarab.ledger.ChainSyncInternalProblem): IO[A] =
          IO(fail(cause.message))
      check = new HeaderCheck[IO]:
        def start(point: ChainSync.Point) = starts
          .update(_ :+ point)
          .as(new HeaderGate[IO]:
            def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
            def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
              gates.update(_ :+ point))
      _ <- connected(Some(check), heightSnapshot = metadata) { (peer, intersections) =>
        (0 until 8).toVector.traverse_ { index =>
          val point = forged.headers(index).point
          for
            _ <- offered.set(Vector(point))
            found <- peer.intersect(Vector(point))
            first <- peer.next
            // Candidate publication may coalesce a no-op rollback at the intersection.
            forward <- first match
              case SyncEvent.RollBackward(actual, _) =>
                IO(assertEquals(actual, point)) *> peer.next
              case event => IO.pure(event)
            _ <- forward match
              case SyncEvent.RollForward(actual, _) =>
                peer.fetch(actual).map { raw =>
                  assertEquals(found, Some(point))
                  assertEquals(actual.point, forged.headers(index + 1).point)
                  assertEquals(raw, forged.raws(index + 1))
                }
              case other => IO(fail(s"expected new intersection successor, got $other"))
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
      finalized: Option[Deferred[IO, Unit]] = None,
      holdCancellation: Boolean = false
  ): HeaderCheck[IO] = new HeaderCheck[IO]:
    def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
      def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
      def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
        (entered.complete(()).void *>
          (if holdCancellation then IO.uncancelable(_ => release.get) else release.get) *>
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
          _ <- connected(
            Some(held(entered, release, None, Some(finalized), holdCancellation = true)),
            finish = false
          ) { (peer, intersections) =>
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
                .guarantee(release.complete(()).void)
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
          _ <- Fault.fiber(peer.next).background.use(_ => entered.get)
          _ <- release.complete(())
          result <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
          count <- intersections.get
        yield
          assertEquals(result, Left(Fault.Defect(defect)))
          assertEquals(count, 1)
      }
    yield ()
  }
