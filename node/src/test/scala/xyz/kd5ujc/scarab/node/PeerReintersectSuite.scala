// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Header, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}

/** NoLongerIntersects in a pipelined Praos session: the client drains and finds a new intersection
  * with our current chain, ending the session only when there is none (consensus 82ecba329,
  * ChainSync/Client.hs:1497–1511, `findIntersectionTop`).
  */
class PeerReintersectSuite extends FailsOnUncaught:
  private final case class Run(
      offered: Vector[Vector[ChainSync.Point]],
      outstandingAtReintersection: Option[Int],
      events: Either[Fault, Vector[SyncEvent]]
  )

  private val chain = ForgedChain.forge(5000 until 5020).map { c =>
    assert(c.headers.size >= 3, s"forged ${c.headers.size} headers")
    c.headers.take(3)
  }

  private def wire(header: Header.Shelley): NtNHeader = NtNHeader(header.era, header.raw, None)

  /** The peer's first header detaches the candidate from our chain. The second FindIntersect finds
    * `headers(i)` when `reintersection` is `Some(i)`, after which the peer serves `headers(i + 1)`.
    */
  private def session(headers: Vector[Header.Shelley], reintersection: Option[Int]): IO[Run] =
    TestControl.executeEmbed {
      val tip =
        ChainSync.Tip(headers.last.point, ChainSync.UInt64.saturating(headers.last.blockNo))
      val ours = Vector(headers(0).point, ChainSync.Point.Origin)
      for
        offered <- Ref.of[IO, Vector[Vector[ChainSync.Point]]](Vector.empty)
        gates <- Ref.of[IO, Int](0)
        closeSignal <- Deferred[IO, NetworkError]
        pending <- Ref.of[IO, Vector[ChainSyncClient.Update[NtNHeader]]](
          Vector.fill(3)(ChainSyncClient.Update.RollForward(wire(headers(2)), tip))
        )
        inFlight <- Ref.of[IO, Int](0)
        atReintersection <- Ref.of[IO, Option[Int]](None)
        pipeline = new ChainSyncClient.Pipeline[IO, NtNHeader]:
          def findIntersect(points: Vector[ChainSync.Point])(using Raise[IO, NetworkError]) =
            offered.updateAndGet(_ :+ points).flatMap { calls =>
              if calls.size == 1 then IO.pure(Some(ChainSync.Point.Origin -> tip))
              else
                inFlight.get.flatMap(n => atReintersection.set(Some(n))) *> reintersection
                  .traverse { i =>
                    pending
                      .set(Vector(ChainSyncClient.Update.RollForward(wire(headers(i + 1)), tip)))
                      .as(headers(i).point -> tip)
                  }
            }
          def findIntersectResult(points: Vector[ChainSync.Point])(using
              Raise[IO, NetworkError]
          ) = IO(fail("no chain-sync jumping in a Praos session"))
          def submit(using Raise[IO, NetworkError]) =
            IO(fail("production submits a captured receiver hook"))
          def submitObserved(hook: ChainSyncClient.ReceiverHook[IO])(using
              Raise[IO, NetworkError]
          ) = inFlight.update(_ + 1)
          def tryCollect(onAwait: IO[Unit])(using Raise[IO, NetworkError]) =
            (inFlight.get, pending.get).tupled.flatMap {
              case (n, next +: rest) if n > 0 =>
                pending.set(rest) *> inFlight.update(_ - 1).as(Some(next))
              case _ => IO.pure(None)
            }
          def collect(onAwait: IO[Unit])(using Raise[IO, NetworkError]) =
            tryCollect(onAwait).flatMap(
              _.fold(IO.never[ChainSyncClient.Update[NtNHeader]])(IO.pure)
            )
          def serviceAwait(onAwait: IO[Unit])(using Raise[IO, NetworkError]) = IO.unit
          def outstanding = inFlight.get
          // The outstanding requests are answered at once and their replies discarded.
          def drain(onAwait: IO[Unit])(onUpdate: ChainSyncClient.Update[NtNHeader] => IO[Unit])(
              using Raise[IO, NetworkError]
          ) = inFlight.set(0) *> pending.set(Vector.empty)
          def requestNext(using Raise[IO, NetworkError]) = IO(fail("legacy request"))
          def requestNextBatch(n: Int)(using Raise[IO, NetworkError]) = IO(fail("legacy batch"))
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = inFlight.get.map(_ == 0)
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
          def failure = closeSignal.tryGet
          def abort(cause: NetworkError) = closeSignal.complete(cause).void
        blocks = new BlockFetchClient[IO]:
          def requestRange(from: ChainSync.Point, to: ChainSync.Point)(using
              Raise[IO, NetworkError]
          ): IO[Option[Vector[Bytes]]] = IO.never
          def done(using Raise[IO, NetworkError]) = IO.unit
          def idle = IO.pure(true)
        // The first gate finds the candidate detached; a gate started at the new intersection
        // accepts.
        gate = (_: ChainSync.Point) =>
          gates.getAndUpdate(_ + 1).map { n =>
            new HeaderGate[IO]:
              def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
              def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) = IO.unit
              override def acceptOrRestart(batch: Vector[SyncEvent])(using
                  Raise[IO, PeerError],
                  cats.Functor[IO]
              ) =
                IO.pure(
                  if n == 0 then HeaderGate.Acceptance.Restart(ChainSync.Point.Origin)
                  else HeaderGate.Acceptance.Accepted
                )
          }
        check = new HeaderCheck[IO]:
          def start(point: ChainSync.Point) = gate(point)
          override def capture(using cats.Applicative[IO]) =
            IO.pure(
              Some(
                HeaderCheck.Captured(
                  ours,
                  gate,
                  Map(headers(0).point -> Option(headers(0).blockNo))
                    .updated(ChainSync.Point.Origin, None)
                )
              )
            )
        events <- Peer
          .over(
            NodeToNodeConnection(15, pipeline, blocks, mux, chainSyncPipeline = Some(pipeline)),
            Peer.Settings(
              SocketAddress[Host](ip"10.7.0.1", port"3001"),
              42,
              byronEpochSlots = 21600,
              pipelineWindow =
                ChainSyncPipelineDecision.Window.checked(2, 4).fold(e => fail(e.toString), identity)
            ),
            _ => IO.unit,
            heightSnapshot = ChainSyncHeights.origin,
            headers = Some(check)
          )
          .use { peer =>
            Fault
              .fiber(
                peer.intersect(Vector(ChainSync.Point.Origin)) *>
                  peer.next.flatMap(first => peer.next.map(second => Vector(first, second)))
              )
              .timeout(1.minute)
          }
        calls <- offered.get
        drained <- atReintersection.get
      yield Run(calls, drained, events)
    }

  test("a detached candidate re-intersects with our chain and the session continues") {
    chain.flatMap { headers =>
      session(headers, Some(0)).map { run =>
        assertEquals(run.offered.size, 2)
        assertEquals(run.offered(1), Vector(headers(0).point, ChainSync.Point.Origin))
        // the pipe is drained before the new FindIntersect (drainThePipe, Client.hs:1500)
        assertEquals(run.outstandingAtReintersection, Some(0))
        val events = run.events.fold(f => fail(s"session ended: $f"), identity)
        assertEquals(
          events.map {
            case SyncEvent.RollBackward(point, _) => Left(point)
            case SyncEvent.RollForward(h, _)      => Right(h.point)
          },
          Vector(Left(headers(0).point), Right(headers(1).point))
        )
      }
    }
  }

  test("without an intersection with our chain the session ends as NoIntersection") {
    chain.flatMap(session(_, None)).map { run =>
      assertEquals(run.offered.size, 2)
      run.events match
        case Left(Fault.Failed(PeerError.NoIntersection)) => ()
        case other => fail(s"expected NoIntersection, got $other")
    }
  }
