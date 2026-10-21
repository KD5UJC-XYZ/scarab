// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Resource}
import cats.mtl.{Handle, Raise}
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

class PeerGenesisLifecycleSuite extends FailsOnUncaught:
  private enum Operation:
    case Waiting, RequestTimeout, RestartTimeout, NoIntersection

  for operation <- Operation.values do
    test(operation match
      case Operation.Waiting =>
        "quiesce interrupts a waiting CSJ instruction without submitting or cancelling wire requests"
      case Operation.RequestTimeout =>
        "streaming header timeout uses the supervised producer's own error scope"
      case Operation.RestartTimeout =>
        "internal restart timeout uses the supervised producer's own error scope"
      case Operation.NoIntersection =>
        "a normal drained ChainSync exit sends Done and preserves the bearer") {
      for
        waiting <- Deferred[IO, Unit]
        timeout: NetworkError.Timeout = NetworkError.Timeout(2, "NextCanAwait")
        closeSignal <- Deferred[IO, NetworkError]
        chainSync = new ChainSyncClient[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            if operation == Operation.RestartTimeout then
              Raise[IO, NetworkError].raise[NetworkError, Option[(ChainSync.Point, ChainSync.Tip)]](
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
          def requestNext(using Raise[IO, NetworkError]) =
            IO.raiseError[ChainSyncClient.Update[NtNHeader]](new AssertionError("CSJ is waiting"))
          def requestNextBatch(@scala.annotation.unused n: Int)(using Raise[IO, NetworkError]) =
            if operation == Operation.RequestTimeout then
              Raise[IO, NetworkError]
                .raise[NetworkError, Vector[ChainSyncClient.Update[NtNHeader]]](timeout)
            else
              IO.raiseError[Vector[ChainSyncClient.Update[NtNHeader]]](
                new AssertionError("CSJ is waiting")
              )
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
            case Operation.RequestTimeout => IO.pure(ChainSyncJumping.Instruction.RunNormally)
            case Operation.RestartTimeout | Operation.NoIntersection =>
              IO.pure(ChainSyncJumping.Instruction.Restart)
          def observed(
              @scala.annotation.unused gate: HeaderGate[IO],
              @scala.annotation.unused event: SyncEvent
          ) = IO.unit
          def processJump(
              @scala.annotation.unused info: ChainSyncJumping.JumpInfo[GenesisPeer.Checkpoint],
              @scala.annotation.unused goodPoint: Boolean,
              @scala.annotation.unused found: Option[ChainSync.Point],
              @scala.annotation.unused gate: HeaderGate[IO]
          ) = IO.pure(GenesisPeer.Applied.Retained)
          def restarted(
              @scala.annotation.unused point: ChainSync.Point,
              @scala.annotation.unused gate: HeaderGate[IO]
          ) = IO.unit
        _ <- Peer
          .over(
            NodeToNodeConnection(15, chainSync, blockFetch, mux),
            Peer
              .Settings(SocketAddress[Host](ip"10.5.0.1", port"3001"), 42, byronEpochSlots = 21600),
            _ => IO.unit,
            genesis = Some(control)
          )
          .use { peer =>
            if operation != Operation.Waiting then
              Handle
                .allow[PeerError](peer.next.as(Right(()): Either[PeerError, Unit]))
                .rescue(e => IO.pure(Left(e)))
                .map(result =>
                  assertEquals(
                    result,
                    Left(
                      if operation == Operation.NoIntersection then PeerError.NoIntersection
                      else PeerError.Unavailable(PeerError.Unavailability.Network(timeout))
                    )
                  )
                ) *> (if operation == Operation.NoIntersection then
                        peer.quiesce.timeout(2.seconds) *> closeSignal.tryGet
                          .map(reason => assertEquals(reason, None))
                      else IO.unit)
            else
              Resource
                .make(
                  Handle.allow[PeerError](peer.next).rescue(error => IO(fail(error.message))).start
                )(_.cancel)
                .use { running =>
                  waiting.get *> running.cancel *> peer.quiesce.timeout(2.seconds)
                }
          }
      yield ()
    }
