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
  private val journalCapacity = 8
  private val pipelineWindow =
    ChainSyncPipelineDecision.Window.checked(2, 4).fold(error => fail(error.toString), identity)

  private lazy val forged: ForgedChain =
    ForgedChain.forge(5000 until 5180).unsafeRunSync()(using munitIORuntime)

  test("body publication stays bounded while a slow consumer holds a long rolling header stream") {
    IO(forged).flatMap { chain =>
      val firstPulls = 5
      val furtherPulls = 20
      // Keep this resource/backpressure test away from a real finite-tip AwaitReply. The fixture
      // must exceed consumption plus every header, wire and body-publication credit.
      val possibleAhead = journalCapacity + pipelineWindow.high + Peer.Depth + 1
      assert(
        chain.headers.size > firstPulls + furtherPulls + possibleAhead,
        s"${chain.headers.size} headers must exceed ${firstPulls + furtherPulls} consumed plus $possibleAhead in flight"
      )
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
              _ <- (1 to firstPulls).toList.traverse_(_ => pull)
              // Virtual time advances after runnable publication work has exhausted its credits.
              _ <- IO.sleep(1.second)
              slow <- prepared.get
              slowTaken <- delivered.get.map(_.size)
              _ <- (1 to furtherPulls).toList.traverse_(_ => pull)
              fast <- prepared.get
              received <- delivered.get
            yield (slow, slowTaken, fast, received)
          }
        yield
          val (slow, slowTaken, fast, received) = observed
          // BlockFetch fetches the candidate independently of publication (State.hs:317–335);
          // the retained window bounds the headers accepted beyond what was published.
          assert(
            slow <= slowTaken + Peer.Depth + 1 + journalCapacity,
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

  test(
    "a stalled consumer leaves its permit, queue and reply readings in the stall report (#371)"
  ) {
    IO(forged).flatMap { chain =>
      val pulls = 5
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          BlockAdmissions.create[IO].flatMap { admissions =>
            coordinator.session(11L).use { session =>
              TestControl.executeEmbed {
                connected(finish = false, fetchSession = Some(session)) { (peer, _) =>
                  for
                    _ <- peer.intersect(Vector(ChainSync.Point.Origin))
                    _ <- (1 to pulls).toList.traverse_(_ => peer.next)
                    // The consumer stops pulling; the virtual clock advances once every runnable
                    // publication has used its credit, as in the bounded-publication test above.
                    _ <- IO.sleep(10.minutes)
                    report <- StallDiagnostics.capture(
                      StallDiagnostics.Sources(coordinator, admissions)
                    )
                  yield report
                }
              }
            }
          }
        }
        .map { report =>
          assert(chain.headers.size > pulls)
          val pipeline = report.sessions.flatMap(_.pipeline)
          assertEquals(pipeline.size, 1, report.toString)
          val p = pipeline.head
          assertEquals(p.permits, Peer.Depth)
          assertEquals(p.peer, SocketAddress(host"127.0.0.1", port"3001"))
          // the publisher has taken every permit and the consumer has taken none back
          assertEquals(p.permitsFree, 0L, p.toString)
          assert(p.queued > 0, p.toString)
          // publication keeps up with the candidate, which never waits for a body; this
          // coordinator decides nothing, so the full window's oldest block is never fetched and
          // capacity room is gone
          assertEquals(p.unpublishedHeaders, Some(0), p.toString)
          assertEquals(p.candidateTip, p.publishedTip, p.toString)
          assertEquals(p.capacityRoom, false, p.toString)
          // nothing arrived for the whole stall, and the last batch was handled after it arrived
          assert(p.lastReplyAcceptedAgo.exists(_ >= 10.minutes), p.toString)
          assert(p.lastReplyCollectedAgo.exists(_ >= 10.minutes), p.toString)
          assert(p.lastReplyHandledAgo.exists(_ >= 10.minutes), p.toString)
          assertEquals(report.admissions, BlockAdmissions.Occupancy(10, 0, 0))
        }
    }
  }

  private def connected[A](
      check: Option[HeaderCheck[IO]] = None,
      finish: Boolean = true,
      prepare: Peer.Prepare[IO] = _ => IO.unit,
      heightSnapshot: Peer.HeightSnapshot[IO] = ChainSyncHeights.origin,
      pipelineFailed: Option[Fault => IO[Unit]] = None,
      fetchSession: Option[FetchCoordinator.Session[IO]] = None
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
                .over[IO, NtNHeader](
                  ours,
                  magic,
                  NtNHeader.codec,
                  NodeToNode.Settings().pipelining(pipelineWindow.high)
                )
                .use { connection =>
                  Peer
                    .over[IO](
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        magic,
                        readAhead = 2,
                        byronEpochSlots = ForgedChain.geometry.byron.epochSlots,
                        pipelineWindow = pipelineWindow
                      ),
                      prepare,
                      chain = Peer.Chain.Praos(check),
                      pipelineFailed = pipelineFailed,
                      heightSnapshot = heightSnapshot,
                      retainedHeaders = journalCapacity,
                      fetchSession = fetchSession
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
        published <- Deferred[IO, Fault]
        _ <- connected(
          Some(held(entered, release, Some(fault))),
          finish = false,
          pipelineFailed = Some(problem => published.complete(problem).void)
        ) { (peer, intersections) =>
          for
            _ <- peer.intersect(Vector(ChainSync.Point.Origin))
            _ <- Fault.fiber(peer.next).background.use(_ => entered.get)
            _ <- release.complete(())
            // Release makes the check runnable; it does not mean its fault is installed.
            // Drain may cancel a check still in progress, so await the actual failure boundary.
            pending <- published.get
            first <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
            again <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
            count <- intersections.get
          yield
            assertEquals(pending, fault)
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
    val expected = Fault.Failed(PeerError.ConnectionDefect(defect))
    for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      published <- Deferred[IO, Fault]
      _ <- connected(
        finish = false,
        prepare = _ => entered.complete(()).void *> release.get *> IO.raiseError[Unit](defect),
        pipelineFailed = Some(problem => published.complete(problem).void)
      ) { (peer, intersections) =>
        for
          _ <- peer.intersect(Vector(ChainSync.Point.Origin))
          _ <- Fault.fiber(peer.next).background.use(_ => entered.get)
          _ <- release.complete(())
          // Release makes the prefetch runnable; the fetch client fails it in its own fiber. The
          // session records the failure before this callback, so the drain below meets it.
          pending <- published.get
          result <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
          count <- intersections.get
        yield
          // the connection's own exception, with its cause: that connection ends (issue 567)
          assertEquals(pending, expected)
          assertEquals(result, Left(expected))
          // no FindIntersect is sent on the connection the failure has aborted
          assertEquals(count, 1)
      }
    yield ()
  }
