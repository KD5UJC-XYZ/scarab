// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSyncFixtures
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import munit.CatsEffectSuite
import scala.concurrent.duration.*

import Peers.*

/** The warm and the hot temperature of a connection: KeepAlive alone, then ChainSync, BlockFetch
  * and TxSubmission2 started, ended on the wire and started again on the same bearer.
  */
class WarmHotSuite extends FailsOnUncaught:
  private val magic = 42L

  /** A chain of blocks 1..3 that a client reads from the start, then waits at the tip. */
  private val handlers: ServerHandlers[IO, Bytes] =
    val source = Resource.eval(Ref.of[IO, Int](0)).map { cursor =>
      new ChainSyncServer.Source[IO, Bytes]:
        def intersect(points: Vector[Point]) =
          cursor.set(0).as(Right(points.head -> Peers.tip(3)))
        def poll = cursor.get.flatMap { at =>
          if at < 3 then
            cursor
              .set(at + 1)
              .as(Some(ChainSyncServer.Update.RollForward(header(at + 1), Peers.tip(3))))
          else IO.pure(None)
        }
        def await = poll.flatMap(_.fold(IO.never)(IO.pure))
    }
    val blocks: BlockFetchServer.Blocks[IO] = (from, to, max) =>
      val all = (1 to 3).toVector
      val i = all.indexWhere(n => Peers.point(n) == from)
      val j = all.indexWhere(n => Peers.point(n) == to)
      IO.pure(
        Option.when(i >= 0 && j >= i && j - i < max)(
          Stream.emits(all.slice(i, j + 1).map(Peers.block).toList).covary[IO]
        )
      )
    val sink = new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes]) = IO.unit
    ServerHandlers[IO, Bytes](ChainSyncFixtures.rawItem, source, blocks, sink)

  /** A source that never has a transaction: the client's offer stays a blocking request. */
  private val emptySource: TxSubmissionClient.Source[IO] = new TxSubmissionClient.Source[IO]:
    def after(ticket: Long, max: Int) = IO.pure(Vector.empty)
    def awaitAfter(ticket: Long) = IO.never
    def body(id: Bytes) = IO.pure(None)

  private def warm(using Net): Resource[IO, WarmConnection[IO, Bytes]] =
    Resource
      .eval(Bearer.inMemory[IO]())
      .flatMap { (ours, theirs) =>
        typed(NodeToNodeServer.accept(theirs, magic, handlers)).void.background
          .flatMap(_ => NodeToNode.warmOver[IO, Bytes](ours, magic, ChainSyncFixtures.rawItem))
      }

  test("a connection is activated, finished and activated again on one bearer"):
    def round(warm: WarmConnection[IO, Bytes])(using Net) =
      warm.activate(Some(emptySource)).use { hot =>
        for
          found <- hot.chainSync.findIntersect(Vector(Point.Origin))
          first <- hot.chainSync.requestNext
          blocks <- hot.blockFetch.requestRange(Peers.point(1), Peers.point(2))
          // the server's TxSubmission2 request arrives while the conversation runs
          _ <- IO.sleep(100.millis)
          ended <- hot.finish(5.seconds)
        yield (found.isDefined, first, blocks, ended)
      }
    typed(warm.use(w => (round(w), round(w)).tupled)).map { r =>
      val expected = (
        true,
        ChainSyncClient.Update.RollForward(header(1), Peers.tip(3)),
        Some(Vector(1, 2).map(Peers.block)),
        true
      )
      assertEquals(r, Right((expected, expected)))
    }

  test("a connection with a request outstanding cannot be finished"):
    typed(
      warm.use(
        _.activate().use { hot =>
          for
            _ <- hot.chainSync.findIntersect(Vector(Point.Origin))
            _ <- List.fill(3)(hot.chainSync.requestNext).sequence
            // the fourth request waits at the tip for a block that never comes
            _ <- hot.chainSync.requestNext.timeoutTo(200.millis, IO.unit.as(null))
            ended <- hot.finish(1.second)
          yield ended
        }
      )
    ).map(r => assertEquals(r, Right(false)))
