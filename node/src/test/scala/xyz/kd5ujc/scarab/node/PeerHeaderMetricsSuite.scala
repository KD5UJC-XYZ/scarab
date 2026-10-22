// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{HeaderError, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** Client.hs:1487–1520 records arrival before validation and reports only accepted headers. */
class PeerHeaderMetricsSuite extends FailsOnUncaught:
  private enum Result:
    case Accepted, Refused, Restart, End

  for result <- Result.values do
    test(s"header usefulness preserves arrival time and excludes $result failures") {
      ForgedChain.forge(5000 until 5002).flatMap { chain =>
        TestControl.executeEmbed {
          for
            entered <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            credited <- Deferred[IO, Unit]
            closeSignal <- Deferred[IO, NetworkError]
            reported <- Ref.of[IO, Vector[Peer.Observation]](Vector.empty)
            validationEnded <- Ref.of[IO, FiniteDuration](Duration.Zero)
            delivered <- Ref.of[IO, Boolean](false)
            header = chain.headers.head
            tip = ChainSync.Tip(header.point, ChainSync.UInt64.saturating(header.blockNo))
            client = new ChainSyncClient[IO, NtNHeader]:
              def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
                IO.pure(Some(ChainSync.Point.Origin -> tip))
              def findIntersectResult(points: Vector[ChainSync.Point])(using
                  Raise[IO, NetworkError]
              ) =
                IO.pure(ChainSyncRuntime.Intersection.Found(ChainSync.Point.Origin, tip))
              def requestNext(using Raise[IO, NetworkError]) =
                delivered.getAndSet(true).flatMap { sent =>
                  if sent then IO.never
                  else
                    IO.pure(
                      ChainSyncClient.Update
                        .RollForward(NtNHeader(header.era, header.raw, None), tip)
                    )
                }
              def requestNextBatch(n: Int)(using Raise[IO, NetworkError]) =
                requestNext.map(Vector(_))
              def done(using Raise[IO, NetworkError]) = IO.unit
              def idle = IO.pure(true)
              def idling = IO.pure(false)
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
            blocks = new BlockFetchClient[IO]:
              def requestRange(from: ChainSync.Point, to: ChainSync.Point)(using
                  Raise[IO, NetworkError]
              ): IO[Option[Vector[Bytes]]] = IO.never
              def done(using Raise[IO, NetworkError]) = IO.unit
              def idle = IO.pure(true)
            check = new HeaderCheck[IO]:
              def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
                def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
                def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) = IO.unit
                override def acceptOrRestart(batch: Vector[SyncEvent])(using
                    Raise[IO, PeerError],
                    cats.Functor[IO]
                ) =
                  entered.complete(()) *> release.get *> IO.sleep(2.seconds) *> IO.monotonic
                    .flatMap(validationEnded.set) *> (result match
                    case Result.Accepted => IO.pure(HeaderGate.Acceptance.Accepted)
                    case Result.Restart =>
                      IO.pure(HeaderGate.Acceptance.Restart(ChainSync.Point.Origin))
                    case Result.End => IO.pure(HeaderGate.Acceptance.End(ChainSync.Point.Origin))
                    case Result.Refused =>
                      Raise[IO, PeerError].raise[PeerError, HeaderGate.Acceptance](
                        PeerError
                          .InvalidHeader(header.point, HeaderError.FarFuture(header.slot, 5000, 0))
                      )))
            _ <- Peer
              .over(
                NodeToNodeConnection(15, client, blocks, mux),
                Peer.Settings(
                  SocketAddress[Host](ip"10.7.0.1", port"3001"),
                  42,
                  byronEpochSlots = 21600
                ),
                _ => IO.unit,
                heightSnapshot = ChainSyncHeights.origin,
                headers = Some(check),
                observe = Some(event =>
                  reported.update(_ :+ event) *> (event match
                    case Peer.Observation.HeadersArrived(_, _) => credited.complete(()).void
                    case _                                     => IO.unit)
                )
              )
              .use { peer =>
                Resource
                  .make(
                    Fault.fiber(peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next).start
                  )(_.cancel)
                  .use { running =>
                    for
                      _ <- entered.get
                      before <- reported.get
                      _ <- IO(assert(!before.exists {
                        case Peer.Observation.HeadersArrived(_, _) => true; case _ => false
                      }))
                      _ <- release.complete(())
                      _ <-
                        if result == Result.Accepted then credited.get
                        else running.joinWithNever.flatMap(outcome => IO(assert(outcome.isLeft)))
                      events <- reported.get
                      validatedAt <- validationEnded.get
                    yield
                      val arrivals = events.collect {
                        case Peer.Observation.HeadersArrived(slots, at) => slots -> at
                      }
                      if result == Result.Accepted then
                        assertEquals(arrivals.map(_._1), Vector(Vector(header.slot.toLong)))
                        assert(arrivals.forall(_._2 + 2.seconds <= validatedAt))
                      else assertEquals(arrivals, Vector.empty)
                  }
              }
          yield ()
        }
      }
    }
