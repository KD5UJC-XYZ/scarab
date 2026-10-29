// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.{
  Bearer,
  ChainSyncServer,
  ConnectionEnd,
  HandlerFailure,
  HandshakeClient,
  LocalStateQueryServer,
  LocalTxSubmissionServer,
  Mux,
  NetworkError,
  NodeToClientServer
}
import xyz.kd5ujc.scarab.ledger.{ChainError, StorageError}
import xyz.kd5ujc.scarab.protocol.{Handshake, LocalStateQuery}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Role, Tip}
import xyz.kd5ujc.scarab.node.log.NodeEvent

class ListenerFailuresSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private def reported(failure: HandlerFailure[NetworkError]) =
    for
      events <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      _ <- ListenerFailures.report[IO](
        "listener-test",
        failure,
        event => events.update(_ :+ event),
        fatal => fatals.update(_ :+ fatal)
      )
      observed <- events.get
      stopped <- fatals.get
    yield (observed, stopped)

  test("an intentional network close reports the original reason without stopping the node") {
    val error = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
    reported(HandlerFailure.Domain(error)).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.fromNetwork(error)))
          )
        )
      )
      assertEquals(fatals, Vector.empty)
    }
  }

  test("an incoherent local state acquisition is an internal fatal with its typed cause") {
    val cause: NetworkError.StateAcquireFailure =
      NetworkError.StateAcquireFailure(Point.Origin, 5L, 4L, 9)
    reported(HandlerFailure.Domain(cause)).map { (events, fatals) =>
      assertEquals(fatals, Vector(Fatal.Internal(ChainError.StateAcquire(cause))))
      assertEquals(
        FailurePolicy.disposition(SessionEnd.of(ChainError.StateAcquire(cause))),
        Disposition.StopNode(Fatal.Internal(ChainError.StateAcquire(cause)))
      )
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.fromNetwork(cause)))
          )
        )
      )
    }
  }

  test(
    "real local acquisition releases its view and sibling protocol before reporting the invariant fatal"
  ) {
    val cause: NetworkError.StateAcquireFailure =
      NetworkError.StateAcquireFailure(Point.Origin, 5L, 4L, 9)
    for
      viewReleased <- Ref.of[IO, Boolean](false)
      siblingStarted <- Deferred[IO, Unit]
      siblingReleased <- Ref.of[IO, Boolean](false)
      stopped <- Deferred[IO, Fatal]
      source = new LocalStateQueryServer.Source[IO, StorageError]:
        def acquire(target: LocalStateQuery.Target) =
          Resource.make(
            siblingStarted.get.as(
              Left(
                LocalStateQueryServer.AcquireFailure
                  .Incoherent(cause.point, cause.flushed, cause.pinned, cause.selected)
              ): Either[LocalStateQueryServer.AcquireFailure[
                StorageError
              ], LocalStateQueryServer.Session[IO, StorageError]]
            )
          )(_ => viewReleased.set(true))
      sibling = Resource.make(
        siblingStarted
          .complete(())
          .as(new ChainSyncServer.Source[IO, RawNtNBlock]:
            def intersect(points: Vector[Point]) = IO.pure(Left(Tip.Origin))
            def poll = IO.pure(None)
            def await = IO.never)
      )(_ => siblingReleased.set(true))
      ends <- Bearer.inMemory[IO]()
      (client, server) = ends
      _ <- Handle
        .allow[NetworkError] {
          NodeToClientServer
            .acceptBackend[IO, StorageError](
              server,
              42L,
              _ => IO.pure(LocalTxSubmissionServer.Response.Accepted),
              Some(source),
              chainSync = Some(sibling)
            )
            .flatMap(_ =>
              IO[Unit](fail("incoherent acquisition returned as an ordinary connection ending"))
            )
        }
        .rescue(error =>
          ListenerFailures.report[IO](
            "local-test",
            HandlerFailure.Domain(error),
            _ => IO.unit,
            fatal =>
              (viewReleased.get, siblingReleased.get).tupled.flatMap { (view, protocol) =>
                IO(
                  assert(view && protocol, "fatal reported before local resources released")
                ) *> stopped.complete(fatal).void
              }
          )
        )
        .background
        .use { _ =>
          Handle
            .allow[NetworkError] {
              Mux.resource(client, Set(0, 5, 6, 7), Mux.Config()).use { mux =>
                for
                  handshake <- mux.channel(0)
                  _ <- HandshakeClient.run(
                    handshake,
                    Vector(23 -> Handshake.Data(42L)),
                    suite = Handshake.Suite.NodeToClient
                  )
                  query <- mux.channel(7)
                  acquire <- IO.fromEither(
                    LocalStateQuery
                      .encode(
                        LocalStateQuery.State.Idle,
                        Role.Client,
                        LocalStateQuery.Message.Acquire(LocalStateQuery.Target.VolatileTip)
                      )
                      .leftMap(error => new AssertionError(error.message))
                  )
                  _ <- query.send(acquire)
                  _ <- query.flush
                  fatal <- stopped.get.timeout(3.seconds)
                yield assertEquals(fatal, Fatal.Internal(ChainError.StateAcquire(cause)))
              }
            }
            .rescue(error => IO(fail(error.message)))
        }
    yield ()
  }

  // issue 567: a handler's own exception ends its connection; the node goes on
  test("an unexpected handler failure reports the connection with its defect and does not stop") {
    val cause = new IllegalStateException("listener handler defect")
    reported(HandlerFailure.Defect(cause)).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Defect(cause))
          )
        )
      )
      assertEquals(fatals, Vector.empty[Fatal])
    }
  }

  test("a typed network defect also retains its original cause and does not stop the node") {
    val cause = new IllegalArgumentException("typed defect")
    reported(HandlerFailure.Domain(NetworkError.Defect(cause))).map { (events, fatals) =>
      assertEquals(
        events,
        Vector(
          NodeEvent.Inbound(
            "listener-test",
            InboundEvent.Closed(InboundEnd.Connection(ConnectionEnd.Defect(cause)))
          )
        )
      )
      assertEquals(fatals, Vector.empty[Fatal])
    }
  }
