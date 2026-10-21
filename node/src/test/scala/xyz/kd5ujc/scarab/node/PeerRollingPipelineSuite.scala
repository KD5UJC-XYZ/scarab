// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Supervisor
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.ledger.{ChainSyncInternalProblem, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** The runtime receiver is tested independently; these schedules exercise the node's per-request
  * context, cancellation and publication integration while an earlier sender callback is blocked.
  */
class PeerRollingPipelineSuite extends FailsOnUncaught:
  private lazy val chain = ForgedChain.forge(5000 until 5012).unsafeRunSync()(using munitIORuntime)

  private enum Blocked:
    case Forecast, Jumping

  private def schedule(
      blocked: Blocked,
      abort: Boolean,
      blockedSend: Boolean = false,
      unknownRollback: Boolean = false,
      offeredHeight: Option[BigInt] = None
  ): IO[Unit] =
    Supervisor[IO](await = false).use { supervisor =>
      for
        entered <- Deferred[IO, Unit]
        released <- Deferred[IO, Unit]
        finalized <- Deferred[IO, Unit]
        closeSignal <- Deferred[IO, NetworkError]
        invariant <- Deferred[IO, ChainSyncInternalProblem]
        heights = offeredHeight.fold(ChainSyncHeights.origin) { height =>
          new Peer.HeightSnapshot[IO]:
            def capture = IO.pure(
              Peer.HeightSnapshot.Captured(
                Vector(chain.headers.head.point),
                Map(chain.headers.head.point -> Some(height))
              )
            )
            def unavailable[A](cause: ChainSyncInternalProblem): IO[A] =
              invariant.complete(cause) *> IO.never[A]
        }
        inFlight <- Ref.of[IO, Int](0)
        submitted <- Ref.of[IO, Int](0)
        replies <- Ref.of[IO, Int](0)
        captures <- Ref.of[IO, Int](0)
        awaited <- Ref.of[IO, Vector[Int]](Vector.empty)
        handled <- Ref.of[IO, Int](0)
        reason = NetworkError.BearerClosed(NetworkError.Closure.Aborted)
        mux = new Mux[IO]:
          def installApplication(initiators: Set[Int], responders: Set[Int])(using
              Raise[IO, NetworkError]
          ) = IO.unit
          def awaitResponderActivity(using Raise[IO, NetworkError]) = IO.never
          def channel(protocol: Int)(using Raise[IO, NetworkError]): IO[MuxChannel[IO]] =
            IO(fail(s"unexpected channel $protocol"))
          def channel(protocol: Int, role: Mux.Role)(using Raise[IO, NetworkError]) =
            channel(protocol)
          def closed = closeSignal.get
          def abort(cause: NetworkError) = closeSignal.complete(cause).void
        observer = new ChainSyncClient.Observer[IO]:
          override def capture(using cats.Applicative[IO]): IO[ChainSyncClient.Observer[IO]] =
            captures.updateAndGet(_ + 1).map { request =>
              new ChainSyncClient.Observer[IO]:
                def awaitReply =
                  awaited.update(_ :+ request) *> (if abort then mux.abort(reason) *> IO.never
                                                   else released.complete(()).void)
                def reply = IO.unit
            }
          def awaitReply = IO(fail("uncaptured request observer"))
          def reply = IO.unit
        tip = ChainSync.Tip(
          chain.headers.last.point,
          ChainSync.UInt64.from(chain.headers.last.blockNo).fold(e => fail(e.toString), identity)
        )
        pipeline = new ChainSyncClient.Pipeline[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            IO.pure(
              Some(offeredHeight.fold(ChainSync.Point.Origin)(_ => chain.headers.head.point) -> tip)
            )
          def submit(using Raise[IO, NetworkError]) =
            IO(fail("production must submit a captured receiver hook"))
          def submitObserved(hook: ChainSyncClient.ReceiverHook[IO])(using
              Raise[IO, NetworkError]
          ) =
            if blockedSend then
              (entered.complete(()) *> IO.never[Unit]).onCancel(
                finalized.complete(()).void *> mux.abort(reason)
              )
            else
              inFlight.update(_ + 1) *> submitted.updateAndGet(_ + 1).flatMap { count =>
                if count != 3 then IO.unit
                else
                  supervisor
                    .supervise(
                      entered.get *> Fibers
                        .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                          hook.awaitReply
                        )
                        .flatMap(_.fold(mux.abort, _ => IO.unit))
                    )
                    .void
              }
          def tryCollect(onAwait: IO[Unit])(using Raise[IO, NetworkError]) =
            submitted.get.flatMap { count =>
              if count < 3 then IO.pure(None)
              else
                replies
                  .modify(n => if n < 2 then (n + 1, Some(n)) else (n, None))
                  .flatMap(
                    _.traverse { index =>
                      inFlight
                        .update(_ - 1)
                        .as(
                          if unknownRollback then
                            ChainSyncClient.Update.RollBackward(chain.headers.head.point, tip)
                          else
                            ChainSyncClient.Update.RollForward(
                              NtNHeader(chain.headers(index).era, chain.headers(index).raw, None),
                              tip
                            ): ChainSyncClient.Update[NtNHeader]
                        )
                    }
                  )
            }
          def collect(onAwait: IO[Unit])(using Raise[IO, NetworkError]) =
            tryCollect(onAwait).flatMap(
              _.fold(IO.never[ChainSyncClient.Update[NtNHeader]])(IO.pure)
            )
          def serviceAwait(onAwait: IO[Unit])(using Raise[IO, NetworkError]) = IO.unit
          def outstanding = inFlight.get
          def drain(onAwait: IO[Unit])(onUpdate: ChainSyncClient.Update[NtNHeader] => IO[Unit])(
              using Raise[IO, NetworkError]
          ) = if unknownRollback then inFlight.set(0) else IO.never
          def requestNext(using Raise[IO, NetworkError]) = IO(fail("legacy request"))
          def requestNextBatch(n: Int)(using Raise[IO, NetworkError]) = IO(fail("legacy batch"))
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = inFlight.get.map(_ == 0)
          def idling = IO.pure(false)
        blockFetch = new BlockFetchClient[IO]:
          def requestRange(from: ChainSync.Point, to: ChainSync.Point)(using
              Raise[IO, NetworkError]
          ) =
            IO.pure(
              Some(
                chain.headers
                  .zip(chain.raws)
                  .dropWhile(_._1.point != from)
                  .takeWhile(_._1.point != to)
                  .map(_._2) ++ chain.headers.zip(chain.raws).find(_._1.point == to).map(_._2)
              )
            )
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
        check = new HeaderCheck[IO]:
          def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
            def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
            def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
              if blocked == Blocked.Forecast then
                handled
                  .getAndUpdate(_ + 1)
                  .flatMap(n =>
                    if n == 0 then
                      (entered.complete(()) *> released.get).guarantee(finalized.complete(()).void)
                    else IO.unit
                  )
              else IO.unit)
        control = new GenesisPeer[IO]:
          def next = IO.pure(ChainSyncJumping.Instruction.RunNormally)
          override def nextAfterReply =
            handled
              .getAndUpdate(_ + 1)
              .flatMap(n =>
                if n == 0 then
                  (entered.complete(()) *> released.get).guarantee(finalized.complete(()).void)
                else IO.unit
              )
              .as(ChainSyncJumping.Instruction.RunNormally)
          def restartCandidates = IO.pure(Vector(ChainSync.Point.Origin))
          def failed[A](error: ChainSyncJumping.Error): IO[A] = IO(fail(error.toString))
          def observed(gate: HeaderGate[IO], event: SyncEvent) = IO.unit
          def restarted(point: ChainSync.Point, gate: HeaderGate[IO]) = IO.unit
          def processJump(
              info: ChainSyncJumping.JumpInfo[GenesisPeer.Checkpoint],
              goodPoint: Boolean,
              found: Option[ChainSync.Point],
              gate: HeaderGate[IO]
          ) = IO.pure(GenesisPeer.Applied.Retained)
        _ <- Peer
          .over(
            NodeToNodeConnection(15, pipeline, blockFetch, mux, chainSyncPipeline = Some(pipeline)),
            Peer.Settings(
              SocketAddress(host"127.0.0.1", port"3001"),
              42,
              byronEpochSlots = ForgedChain.geometry.byron.epochSlots,
              pipelineWindow =
                ChainSyncPipelineDecision.Window.checked(2, 4).fold(e => fail(e.toString), identity)
            ),
            _ => IO.unit,
            heights,
            headers = Option.unless(unknownRollback || offeredHeight.nonEmpty)(check),
            chainSyncObserver = Some(observer),
            genesis = Option.when(blocked == Blocked.Jumping)(control)
          )
          .use { peer =>
            if offeredHeight.nonEmpty then
              Fault
                .fiber(IO.race(invariant.get, peer.intersect(Vector(chain.headers.head.point))))
                .map { result =>
                  val height = offeredHeight.fold(BigInt(0))(identity)
                  if height > ((BigInt(1) << 64) - 1) then
                    assertEquals(
                      result,
                      Right(Left(ChainSyncInternalProblem.InvalidBlockNumber(height)))
                    )
                  else assertEquals(result, Right(Right(Some(chain.headers.head.point))))
                }
            else if blockedSend then
              Fault
                .fiber(peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next)
                .background
                .use(_ => entered.get)
            else
              Fault
                .fiber {
                  peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next
                }
                .flatMap { result =>
                  for
                    requests <- awaited.get
                    _ <- IO(
                      assertEquals(requests, if unknownRollback then Vector.empty else Vector(3))
                    )
                    _ <-
                      if unknownRollback then
                        IO(
                          assertEquals(
                            result,
                            Left(
                              Fault.Failed(
                                PeerError.RolledBackPastIntersection(chain.headers.head.point)
                              )
                            )
                          )
                        )
                      else if abort then
                        finalized.get *> IO(assertEquals(result, Left(Peer.networkFault(reason))))
                      else IO(assert(result.isRight))
                  yield ()
                }
          }
        _ <- finalized.get.whenA(blockedSend)
      yield ()
    }

  test(
    "a later request's receiver hook unblocks sender forecast and retains its own captured context"
  ) {
    schedule(Blocked.Forecast, abort = false)
  }
  test("a later request's receiver hook unblocks sender CSJ waiting") {
    schedule(Blocked.Jumping, abort = false)
  }
  test("receiver disconnect cancels sender validation and preserves the original typed closure") {
    schedule(Blocked.Forecast, abort = true)
  }

  test("resource release cancels a backpressured node submission and runs its alignment cleanup") {
    schedule(Blocked.Forecast, abort = false, blockedSend = true)
  }

  test(
    "an unknown rollback with header validation disabled is a peer failure, not a local defect"
  ) {
    schedule(Blocked.Forecast, abort = false, unknownRollback = true)
  }

  test("offered block numbers preserve Word64 boundaries and reject oversized local metadata") {
    Vector((BigInt(1) << 64) - 1, BigInt(1) << 64).traverse_ { height =>
      schedule(Blocked.Forecast, abort = false, offeredHeight = Some(height))
    }
  }

  test("publication capacity preserves the actual shared point even when block numbers repeat") {
    import xyz.kd5ujc.scarab.consensus.ChainSelection
    val retained = chain.headers.size - 1
    assert(retained >= 2)
    val headers = chain.headers.take(retained).map(_.copy(blockNo = 1))
    val candidate = ChainSelection.Candidate(ChainSync.Point.Origin, headers)
    val empty = ChainSelection.Candidate(ChainSync.Point.Origin, Vector.empty)
    assert(!Peer.publicationRoom(empty, candidate, retained))
    assert(Peer.publicationRoom(empty.copy(headers = headers.take(1)), candidate, retained))
    val fork = empty.copy(headers = Vector(chain.headers.last.copy(blockNo = 1)))
    assert(!Peer.publicationRoom(fork, candidate, retained))
  }
