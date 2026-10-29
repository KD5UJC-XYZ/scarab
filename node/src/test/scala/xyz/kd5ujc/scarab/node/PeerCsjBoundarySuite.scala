// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain, SelectView}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

class PeerCsjBoundarySuite extends FailsOnUncaught:
  private enum Action:
    case Jump, Restart
  for action <- Action.values do
    test(s"a CSJ $action after one reply drains the remaining pipeline before FindIntersect") {
      for
        collected <- Ref.of[IO, Int](0)
        accepted <- Ref.of[IO, Int](0)
        nextCalls <- Ref.of[IO, Int](0)
        intersections <- Ref.of[IO, Int](0)
        applied <- Deferred[IO, (Int, Int)]
        closeSignal <- Deferred[IO, NetworkError]
        check = (collected.get, accepted.get).tupled.flatMap(applied.complete).void
        checkpoint = GenesisPeer.Checkpoint(
          HeaderChain.at[NodeHeaderState](ChainSync.Point.Origin, None),
          ChainSelection
            .Local(ChainSync.Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty),
          ChainSync.Point.Origin
        )
        chainSync = new ChainSyncClient[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            intersections.update(_ + 1).as(points.headOption.map(_ -> ChainSync.Tip.Origin))
          def findIntersectResult(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            findIntersect(points).map(
              _.fold[ChainSyncClient.Intersection](
                ChainSyncRuntime.Intersection.NotFound(ChainSync.Tip.Origin)
              ) { (point, remoteTip) => ChainSyncRuntime.Intersection.Found(point, remoteTip) }
            )
          def requestNext(using Raise[IO, NetworkError]) =
            IO.raiseError[ChainSyncClient.Update[NtNHeader]](new AssertionError("batch required"))
          def requestNextBatch(@scala.annotation.unused n: Int)(using Raise[IO, NetworkError]) =
            IO.raiseError[Vector[ChainSyncClient.Update[NtNHeader]]](
              new AssertionError("observed batch required")
            )
          override def requestNextBatchObserved(@scala.annotation.unused n: Int)(
              callback: ChainSyncClient.Update[NtNHeader] => IO[Unit]
          )(using Raise[IO, NetworkError], cats.Monad[IO]) =
            val updates = Vector.fill(2)(
              ChainSyncClient.Update.RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
            )
            updates.traverse_(u => collected.update(_ + 1) *> callback(u)).as(updates)
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
          def idling = IO.pure(false)
        blockFetch = new BlockFetchClient[IO]:
          def requestRange(
              @scala.annotation.unused from: ChainSync.Point,
              @scala.annotation.unused to: ChainSync.Point
          )(using Raise[IO, NetworkError]) =
            IO.raiseError[Option[Vector[Bytes]]](new AssertionError("no blocks announced"))
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
        mux = new Mux[IO]:
          def installApplication(initiators: Set[Int], responders: Set[Int])(using
              cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
          ): IO[Unit] = IO.unit
          def awaitResponderActivity(using
              cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
          ) =
            IO.never[Unit]
          def channel(@scala.annotation.unused protocol: Int)(using
              Raise[IO, NetworkError]
          ): IO[MuxChannel[IO]] =
            IO.raiseError(new AssertionError("no channel expected"))
          def channel(protocol: Int, @scala.annotation.unused role: Mux.Role)(using
              Raise[IO, NetworkError]
          ) = channel(protocol)
          def closed = closeSignal.get
          def failure = closeSignal.tryGet
          def abort(error: NetworkError) = closeSignal.complete(error).void
        control = new GenesisPeer[IO]:
          def restartCandidates = IO.pure(Vector(ChainSync.Point.Origin))
          def failed[A](problem: ChainSyncJumping.Error): IO[A] =
            IO.raiseError(new AssertionError(problem))
          def next = nextCalls.getAndUpdate(_ + 1).flatMap {
            case 0 => IO.pure(ChainSyncJumping.Instruction.RunNormally)
            case 1 =>
              IO.pure(action match
                case Action.Jump =>
                  ChainSyncJumping.Instruction.Jump(
                    ChainSyncJumping.JumpInfo(ChainSync.Point.Origin, Vector.empty, checkpoint),
                    true
                  )
                case Action.Restart => ChainSyncJumping.Instruction.Restart)
            case _ => IO.never[ChainSyncJumping.Instruction[GenesisPeer.Checkpoint]]
          }
          def current(@scala.annotation.unused gate: HeaderGate.Grafting[IO]) = IO.unit
          def observed(
              @scala.annotation.unused gate: HeaderGate.Grafting[IO],
              @scala.annotation.unused event: SyncEvent
          ) = accepted.update(_ + 1)
          def processJump(
              @scala.annotation.unused gate: HeaderGate.Grafting[IO],
              @scala.annotation.unused info: ChainSyncJumping.JumpInfo[GenesisPeer.Checkpoint],
              @scala.annotation.unused goodPoint: Boolean,
              @scala.annotation.unused found: Option[ChainSync.Point]
          ) = check.as(GenesisPeer.Applied.Retained)
          def restarted(
              @scala.annotation.unused gate: HeaderGate.Grafting[IO],
              @scala.annotation.unused point: ChainSync.Point
          ) =
            intersections.get.flatMap(n => check.whenA(n > 1))
        _ <- Peer
          .over(
            NodeToNodeConnection(15, chainSync, blockFetch, mux),
            Peer
              .Settings(SocketAddress[Host](ip"10.5.0.1", port"3001"), 42, byronEpochSlots = 21600),
            _ => IO.unit,
            chain = TestChains.chain(None, Some(control)),
            heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin
          )
          .use { peer =>
            Resource
              .make(
                Fault
                  .fiber(peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next)
                  .start
              )(
                _.cancel
              )
              .use { running =>
                applied.get
                  .timeoutTo(
                    2.seconds,
                    (
                      collected.get,
                      accepted.get,
                      nextCalls.get,
                      intersections.get,
                      closeSignal.tryGet
                    ).tupled.flatMap(s => IO(fail(s"CSJ_STATE $action $s")))
                  )
                  .flatMap { (read, handled) =>
                    IO { assertEquals(read, 2); assertEquals(handled, 1) }
                  } *> running.cancel *> peer.quiesce.timeout(2.seconds) *>
                  closeSignal.tryGet.map(result => assertEquals(result, None))
              }
          }
      yield ()
    }
