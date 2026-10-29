// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

class PeerGenesisLifecycleSuite extends FailsOnUncaught:
  private enum Operation:
    case Waiting, RequestTimeout, RestartTimeout, NoIntersection, RollbackEnd, LegacyRollbackEnd,
      DrainBatch

  for operation <- Operation.values do
    test(operation match
      case Operation.Waiting =>
        "quiesce interrupts a waiting CSJ instruction without submitting or cancelling wire requests"
      case Operation.RequestTimeout =>
        "streaming header timeout uses the supervised producer's own error scope"
      case Operation.RestartTimeout =>
        "internal restart timeout uses the supervised producer's own error scope"
      case Operation.NoIntersection =>
        "a normal drained ChainSync exit sends Done and preserves the bearer"
      case Operation.RollbackEnd =>
        "rollback before the intersection drains the batch and preserves the bearer"
      case Operation.LegacyRollbackEnd =>
        "non-streaming rollback termination drains before returning its typed result"
      case Operation.DrainBatch =>
        "draining discards every later ordered reply after cancelling one validation") {
      for
        waiting <- Deferred[IO, Unit]
        validations <- cats.effect.Ref.of[IO, Int](0)
        collected <- cats.effect.Ref.of[IO, Int](0)
        intersections <- cats.effect.Ref.of[IO, Int](0)
        timeout: NetworkError.Timeout = NetworkError.Timeout(2, "NextCanAwait")
        closeSignal <- Deferred[IO, NetworkError]
        chainSync = new ChainSyncClient[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            intersections.update(_ + 1) *> {
              if operation == Operation.RestartTimeout then
                Raise[IO, NetworkError]
                  .raise[NetworkError, Option[(ChainSync.Point, ChainSync.Tip)]](
                    timeout
                  )
              else if operation == Operation.NoIntersection then IO.pure(None)
              else
                IO.pure(
                  Option.when(points.contains(ChainSync.Point.Origin))(
                    ChainSync.Point.Origin -> ChainSync.Tip(
                      ChainSync.Point.Origin,
                      ChainSync.UInt64.saturating(0)
                    )
                  )
                )
            }
          def findIntersectResult(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            findIntersect(points).map(
              _.fold[ChainSyncClient.Intersection](
                ChainSyncRuntime.Intersection.NotFound(ChainSync.Tip.Origin)
              ) { (point, remoteTip) => ChainSyncRuntime.Intersection.Found(point, remoteTip) }
            )
          def requestNext(using Raise[IO, NetworkError]) =
            IO.raiseError[ChainSyncClient.Update[NtNHeader]](new AssertionError("CSJ is waiting"))
          def requestNextBatch(@scala.annotation.unused n: Int)(using Raise[IO, NetworkError]) =
            if operation == Operation.RequestTimeout then
              Raise[IO, NetworkError]
                .raise[NetworkError, Vector[ChainSyncClient.Update[NtNHeader]]](timeout)
            else if (
                operation == Operation.RollbackEnd || operation == Operation.LegacyRollbackEnd || operation == Operation.DrainBatch
              )
            then
              IO.pure(
                Vector.fill(2)(
                  ChainSyncClient.Update.RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
                )
              )
            else
              IO.raiseError[Vector[ChainSyncClient.Update[NtNHeader]]](
                new AssertionError("CSJ is waiting")
              )
          override def requestNextBatchObserved(n: Int)(
              onUpdate: ChainSyncClient.Update[NtNHeader] => IO[Unit]
          )(using Raise[IO, NetworkError], cats.Monad[IO]) =
            if operation == Operation.DrainBatch then
              val updates = Vector.fill(2)(
                ChainSyncClient.Update.RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
              )
              updates.traverse_(u => collected.update(_ + 1) *> onUpdate(u)).as(updates)
            else super.requestNextBatchObserved(n)(onUpdate)
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
          def idling = IO.pure(true)
        blockFetch = new BlockFetchClient[IO]:
          def requestRange(
              @scala.annotation.unused from: ChainSync.Point,
              @scala.annotation.unused to: ChainSync.Point
          )(using Raise[IO, NetworkError]) =
            IO.raiseError[Option[Vector[Bytes]]](new AssertionError("no candidate was announced"))
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
            IO.raiseError(new AssertionError("no wire channel expected"))
          def channel(protocol: Int, @scala.annotation.unused role: Mux.Role)(using
              Raise[IO, NetworkError]
          ): IO[MuxChannel[IO]] = channel(protocol)
          def closed = closeSignal.get
          def abort(reason: NetworkError) = closeSignal.complete(reason).void
        control = new GenesisPeer[IO]:
          def restartCandidates = IO.pure(Vector(ChainSync.Point.Origin))
          def failed[A](error: ChainSyncJumping.Error): IO[A] =
            IO.raiseError(new AssertionError(error))
          def next = operation match
            case Operation.Waiting =>
              waiting.complete(()).void *> IO
                .never[ChainSyncJumping.Instruction[GenesisPeer.Checkpoint]]
            case Operation.RequestTimeout | Operation.RollbackEnd | Operation.LegacyRollbackEnd |
                Operation.DrainBatch =>
              IO.pure(ChainSyncJumping.Instruction.RunNormally)
            case Operation.RestartTimeout | Operation.NoIntersection =>
              IO.pure(ChainSyncJumping.Instruction.Restart)
          def observed(
              @scala.annotation.unused gate: HeaderGate.Grafting[IO],
              @scala.annotation.unused event: SyncEvent
          ) = IO.unit
          def processJump(
              @scala.annotation.unused gate: HeaderGate.Grafting[IO],
              @scala.annotation.unused info: ChainSyncJumping.JumpInfo[GenesisPeer.Checkpoint],
              @scala.annotation.unused goodPoint: Boolean,
              @scala.annotation.unused found: Option[ChainSync.Point]
          ) = IO.pure(GenesisPeer.Applied.Retained)
          def restarted(
              @scala.annotation.unused gate: HeaderGate.Grafting[IO],
              @scala.annotation.unused point: ChainSync.Point
          ) = IO.unit
        _ <- Peer
          .over(
            NodeToNodeConnection(15, chainSync, blockFetch, mux),
            Peer
              .Settings(SocketAddress[Host](ip"10.5.0.1", port"3001"), 42, byronEpochSlots = 21600),
            _ => IO.unit,
            chain = TestChains.chain(
              Option.when(
                (operation == Operation.RollbackEnd || operation == Operation.LegacyRollbackEnd || operation == Operation.DrainBatch)
              )(
                new HeaderCheck[IO]:
                  def start(@scala.annotation.unused point: ChainSync.Point) =
                    IO.pure(new HeaderGate[IO]:
                      def accept(@scala.annotation.unused batch: Vector[SyncEvent])(using
                          Raise[IO, PeerError]
                      ) = IO.unit
                      override def acceptOrRestart(
                          @scala.annotation.unused batch: Vector[SyncEvent]
                      )(using Raise[IO, PeerError], cats.Functor[IO]) =
                        if operation == Operation.DrainBatch then
                          validations.getAndUpdate(_ + 1).flatMap {
                            case 0 => waiting.complete(()).void *> IO.never[HeaderGate.Acceptance]
                            case _ =>
                              IO.raiseError(new AssertionError("a drained reply was validated"))
                          }
                        else IO.pure(HeaderGate.Acceptance.End(ChainSync.Point.Origin))
                      def counts = IO.pure(HeaderGate.Counts(0, 0, 0)))
              ),
              Option.when(operation != Operation.LegacyRollbackEnd)(control)
            ),
            heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin
          )
          .use { peer =>
            if operation != Operation.Waiting && operation != Operation.DrainBatch then
              Handle
                .allow[PeerError](
                  (peer
                    .intersect(Vector(ChainSync.Point.Origin))
                    .void
                    .whenA(
                      (operation == Operation.RollbackEnd || operation == Operation.LegacyRollbackEnd || operation == Operation.DrainBatch)
                    ) *> peer.next)
                    .as(Right(()): Either[PeerError, Unit])
                )
                .rescue(e => IO.pure(Left(e)))
                .map(result =>
                  assertEquals(
                    result,
                    Left(
                      if operation == Operation.NoIntersection then PeerError.NoIntersection
                      else if (
                          operation == Operation.RollbackEnd || operation == Operation.LegacyRollbackEnd || operation == Operation.DrainBatch
                        )
                      then PeerError.RolledBackPastIntersection(ChainSync.Point.Origin)
                      else PeerError.Unavailable(PeerError.Unavailability.Network(timeout))
                    )
                  )
                ) *> (if operation == Operation.NoIntersection || (operation == Operation.RollbackEnd || operation == Operation.LegacyRollbackEnd || operation == Operation.DrainBatch)
                      then
                        for
                          _ <- peer.quiesce.timeout(2.seconds)
                          before <- intersections.get
                          repeated <- Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)))
                          after <- intersections.get
                          closed <- closeSignal.tryGet
                        yield
                          val expected =
                            if operation == Operation.NoIntersection then PeerError.NoIntersection
                            else PeerError.RolledBackPastIntersection(ChainSync.Point.Origin)
                          assertEquals(repeated, Left(Fault.Failed(expected)))
                          assertEquals(before, after)
                          assertEquals(closed, None)
                      else IO.unit)
            else
              Handle
                .allow[PeerError](
                  peer
                    .intersect(Vector(ChainSync.Point.Origin))
                    .void
                    .whenA(operation == Operation.DrainBatch)
                )
                .rescue(e => IO(fail(e.message))) *> Resource
                .make(
                  Handle.allow[PeerError](peer.next).rescue(error => IO(fail(error.message))).start
                )(_.cancel)
                .use { running =>
                  waiting.get *> running.cancel *> peer.quiesce.timeout(2.seconds) *>
                    (if operation == Operation.DrainBatch then
                       (validations.get, collected.get, closeSignal.tryGet).tupled.map {
                         (validated, received, failure) =>
                           assertEquals(validated, 1)
                           assertEquals(received, 2)
                           assertEquals(failure, None)
                       }
                     else IO.unit)
                }
          }
      yield ()
    }
