// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSyncFixtures
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
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
    val blocks: BlockFetchServer.Blocks[IO] = (from, to) =>
      val all = (1 to 3).toVector
      val i = all.indexWhere(n => Peers.point(n) == from)
      val j = all.indexWhere(n => Peers.point(n) == to)
      IO.pure(
        Option.when(i >= 0 && j >= i)(
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

  private def warm(duplex: Boolean = false, settings: NodeToNode.Settings = NodeToNode.Settings())(
      using Net
  ): Resource[IO, WarmConnection[IO, Bytes]] =
    Resource
      .eval(Bearer.inMemory[IO]())
      .flatMap { (ours, theirs) =>
        typed(NodeToNodeServer.accept(theirs, magic, handlers)).void.background
          .flatMap(_ =>
            NodeToNode.warmOver[IO, Bytes](
              ours,
              magic,
              ChainSyncFixtures.rawItem,
              settings = settings,
              serve = Option.when(duplex)(handlers)
            )
          )
      }

  private final case class DuplexPair(
      local: WarmConnection[IO, Bytes],
      responses: Deferred[
        IO,
        (
            (Option[(Point, Tip)], ChainSyncClient.Update[Bytes], Boolean),
            (Option[(Point, Tip)], ChainSyncClient.Update[Bytes], Boolean)
        )
      ]
  )

  /** The inbound server runs on one end of the bearer pair and starts ChainSync clients from the
    * negotiated callback. This issues real peer-initiated protocol requests over our outbound
    * duplex connection.
    */
  private def duplexPair(closed: Ref[IO, Int])(using Net): Resource[IO, DuplexPair] =
    Resource
      .make(Bearer.inMemory[IO]()) { (ours, theirs) =>
        closed.update(_ + 1) *> ours.close *> closed.update(_ + 1) *> theirs.close
      }
      .flatMap { (ours, theirs) =>
        for
          responses <- Resource.eval(
            Deferred[
              IO,
              (
                  (Option[(Point, Tip)], ChainSyncClient.Update[Bytes], Boolean),
                  (Option[(Point, Tip)], ChainSyncClient.Update[Bytes], Boolean)
              )
            ]
          )
          _ <- Resource.make(
            Fibers
              .scoped[IO, NetworkError, Unit](NetworkError.fromThrowable("duplex peer", _)) {
                def request(mux: Mux[IO], version: Int) =
                  NodeToNode
                    .clients[IO, Bytes](
                      mux,
                      Mux.Role.Initiator,
                      version,
                      ChainSyncFixtures.rawItem
                    )
                    .use { connection =>
                      for
                        found <- connection.chainSync.findIntersect(Vector(Point.Origin))
                        next <- connection.chainSync.requestNext
                        ended <- connection.finish(5.seconds)
                      yield (found, next, ended)
                    }

                NodeToNodeServer
                  .accept[IO, Bytes](
                    theirs,
                    magic,
                    handlers,
                    negotiated = (agreed, mux) =>
                      Resource.eval(
                        (request(mux, agreed.version), request(mux, agreed.version)).tupled
                          .flatMap(responses.complete(_).void)
                      )
                  )
                  .void
              }
              .start
          )(_.cancel)
          local <- NodeToNode.warmOver[IO, Bytes](
            ours,
            magic,
            ChainSyncFixtures.rawItem,
            serve = Some(handlers)
          )
        yield DuplexPair(local, responses)
      }

  test("an outbound duplex offer retains the negotiated data flow"):
    typed(warm(duplex = true).use(connection => IO.pure(connection.duplex))).map { result =>
      assertEquals(result, Right(true))
    }

  test("a duplex responder answers inbound ChainSync, demotes, and releases on cancellation"):
    typed {
      Ref.of[IO, Int](0).flatMap { closed =>
        duplexPair(closed).allocated.flatMap { (pair, release) =>
          val exercise =
            for
              responses <- pair.responses.get.timeout(5.seconds)
              _ <- IO(assert(pair.local.duplex))
              first = responses._1
              second = responses._2
              _ <- IO(assert(first._1.isDefined))
              _ <- IO(
                assertEquals(first._2, ChainSyncClient.Update.RollForward(header(1), Peers.tip(3)))
              )
              _ <- IO(assert(first._3))
              _ <- IO(
                assertEquals(
                  second,
                  (
                    Some(Point.Origin -> Peers.tip(3)),
                    ChainSyncClient.Update.RollForward(header(1), Peers.tip(3)),
                    true
                  )
                )
              )
              beforeRelease <- closed.get
              _ <- IO(assertEquals(beforeRelease, 0))
            yield ()
          exercise.guarantee(release) *> closed.get.map(count => assertEquals(count, 2))
        }
      }
    }.map(result => assertEquals(result, Right(())))

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
    typed(warm().use(w => (round(w), round(w)).tupled)).map { r =>
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
      warm().use(
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

  for (name, policy, trust, horizon, timesOut) <- List(
      (
        "local trustable activation",
        ChainSyncTimeouts.Policy.Reference(ChainSyncTimeouts.PeerTrust.Untrustable),
        Some(ChainSyncTimeouts.PeerTrust.Trustable),
        912.seconds,
        false
      ),
      (
        "untrustable activation clears a reused trustable profile",
        ChainSyncTimeouts.Policy.Reference(ChainSyncTimeouts.PeerTrust.Trustable),
        None,
        912.seconds,
        true
      ),
      (
        "explicit fixed profile survives trustable activation",
        ChainSyncTimeouts.Policy.Fixed(ProtocolDeadline.After(2.seconds)),
        Some(ChainSyncTimeouts.PeerTrust.Trustable),
        3.seconds,
        true
      )
    )
  do
    test(s"warm ChainSync deadline routing: $name") {
      TestControl.executeEmbed {
        for
          awaiting <- Deferred[IO, Unit]
          exercise = typed {
            warm(settings =
              NodeToNode.Settings(chainSync = ChainSyncClient.Settings(mustReply = policy))
            ).use { connection =>
              trust.fold(connection.activate())(peer => connection.activate(peerTrust = peer)).use {
                active =>
                  active.chainSync
                    .observe(new ChainSyncClient.Observer[IO]:
                      def awaitReply = awaiting.complete(()).void
                      def reply = IO.unit)
                    .use { _ =>
                      active.chainSync.findIntersect(Vector(Point.Origin)) *>
                        List.fill(3)(()).traverse_(_ => active.chainSync.requestNext) *>
                        active.chainSync.requestNext
                    }
              }
            }
          }
          outcome <- IO.race(exercise, awaiting.get *> IO.sleep(horizon))
        yield
          if timesOut then
            assertEquals(
              outcome,
              Left(
                Left(
                  NetworkError.Timeout(
                    Protocols.ChainSync,
                    xyz.kd5ujc.scarab.protocol.ChainSync.State.NextMustReply.toString
                  )
                )
              )
            )
          else assertEquals(outcome, Right(()))
      }
    }
