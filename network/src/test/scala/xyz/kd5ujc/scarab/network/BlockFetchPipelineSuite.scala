// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}

/** Client-side pipelining: a server can wait for several requests before answering any of them. */
class BlockFetchPipelineSuite extends FailsOnUncaught:
  private val codec = CardanoBlockFetch.payloadCodec()
  private type Message = BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]

  private def send(channel: MuxChannel[IO], state: BlockFetch.State, message: Message)(using
      cats.mtl.Raise[IO, NetworkError]
  ): IO[Unit] =
    channel.send(
      BlockFetch
        .encode(state, BlockFetch.Role.Server, message, codec)
        .fold(e => fail(e.message), identity)
    )

  private def request(channel: MuxChannel[IO])(using
      cats.mtl.Raise[IO, NetworkError]
  ): IO[Message] = channel.recv(65535)(
    BlockFetch.decodePrefix(BlockFetch.State.Idle, BlockFetch.Role.Client, _, codec)
  )

  test("concurrent requests are sent before collection, with ordered absent and nonempty replies"):
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            s <- serverMux.channel(Protocols.BlockFetch)
            result <- BlockFetchClient.resource(c, clientMux.abort).use { client =>
              for
                sent <- Vector.fill(3)(()).traverse(_ => Deferred[IO, Unit])
                blockObserved <- Deferred[IO, Unit]
                release <- Deferred[IO, Unit]
                events <- Ref.of[IO, Vector[(Int, BlockFetchRuntime.Event)]](Vector.empty)
                observe = (i: Int, event: BlockFetchRuntime.Event) =>
                  events.update(_ :+ (i -> event)) *> (event match
                    case _: BlockFetchRuntime.Event.Sent      => sent(i).complete(()).void
                    case _: BlockFetchRuntime.Event.Block     => blockObserved.complete(()).void
                    case _: BlockFetchRuntime.Event.Completed => IO.unit)
                first <- Peers
                  .typed(
                    client.requestRangeObserved(Peers.point(1), Peers.point(1))(
                      observe(0, _)
                    )
                  )
                  .start
                _ <- sent(0).get
                second <- Peers
                  .typed(
                    client.requestRangeObserved(Peers.point(2), Peers.point(2))(
                      observe(1, _)
                    )
                  )
                  .start
                _ <- sent(1).get
                third <- Peers
                  .typed(
                    client.requestRangeObserved(Peers.point(3), Peers.point(3))(
                      observe(2, _)
                    )
                  )
                  .start
                _ <- sent(2).get
                requests <- Vector.fill(3)(()).traverse(_ => request(s))
                idle <- client.idle
                invalidDone <- Peers.typed(client.done)
                _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.NoBlocks)
                _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.StartBatch)
                block = CardanoBlockFetch.RawNtNBlock
                  .from(Peers.block(2))
                  .fold(e => fail(e.message), identity)
                _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.Block(block))
                _ <- blockObserved.get
                before <- events.get
                _ <- IO(assert(!before.exists {
                  case (1, _: BlockFetchRuntime.Event.Completed) => true
                  case _                                         => false
                }))
                _ <- release.complete(())
                _ <- release.get
                _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.BatchDone)
                _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.NoBlocks)
                results <- (first.joinWithNever, second.joinWithNever, third.joinWithNever).tupled
                after <- events.get
                _ <- client.done
                done <- request(s)
              yield (requests, idle, invalidDone, results, after, done)
            }
          yield result
        }
      }
      .timeout(5.seconds)
      .map { result =>
        val (requests, idle, invalidDone, results, events, done) =
          result.fold(e => fail(e.message), identity)
        assertEquals(
          requests,
          (1 to 3)
            .map(i => BlockFetch.Message.RequestRange(Peers.point(i), Peers.point(i)))
            .toVector
        )
        assert(!idle)
        assert(invalidDone.isLeft)
        assertEquals(results, (Right(None), Right(Some(Vector(Peers.block(2)))), Right(None)))
        assertEquals(
          events.collect { case (i, _: BlockFetchRuntime.Event.Completed) => i },
          Vector(0, 1, 2)
        )
        assertEquals(
          events.collect { case (_, BlockFetchRuntime.Event.Block(bytes, _)) => bytes },
          Vector(Peers.block(2).size)
        )
        assertEquals(done, BlockFetch.Message.ClientDone)
      }

  test(
    "request credit bounds sent and queued work; cancellation before admission keeps connection usable"
  ):
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            s <- serverMux.channel(Protocols.BlockFetch)
            result <- BlockFetchClient
              .resource(c, clientMux.abort, BlockFetchClient.Settings(maxInFlightRequests = 1))
              .use { client =>
                for
                  sent <- Deferred[IO, Unit]
                  first <- Peers
                    .typed(client.requestRangeObserved(Peers.point(1), Peers.point(1)) {
                      case _: BlockFetchRuntime.Event.Sent => sent.complete(()).void
                      case _                               => IO.unit
                    })
                    .start
                  _ <- sent.get
                  _ <- request(s)
                  waiting <- Peers.typed(client.requestRange(Peers.point(2), Peers.point(2))).start
                  _ <- waiting.cancel
                  _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.NoBlocks)
                  a <- first.joinWithNever
                  third <- Peers.typed(client.requestRange(Peers.point(3), Peers.point(3))).start
                  received <- request(s)
                  _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.NoBlocks)
                  b <- third.joinWithNever
                yield (a, b, received)
              }
          yield result
        }
      }
      .timeout(5.seconds)
      .map { result =>
        val (a, b, received) = result.fold(e => fail(e.message), identity)
        assertEquals(a, Right(None))
        assertEquals(b, Right(None))
        assertEquals(received, BlockFetch.Message.RequestRange(Peers.point(3), Peers.point(3)))
      }

  test("cancelling a submitted request aborts the bearer and fails every pending collector"):
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, _) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            result <- BlockFetchClient.resource(c, clientMux.abort).use { client =>
              for
                sent1 <- Deferred[IO, Unit]
                sent2 <- Deferred[IO, Unit]
                first <- Peers
                  .typed(client.requestRangeObserved(Peers.point(1), Peers.point(1)) {
                    case _: BlockFetchRuntime.Event.Sent => sent1.complete(()).void
                    case _                               => IO.unit
                  })
                  .start
                _ <- sent1.get
                second <- Peers
                  .typed(client.requestRangeObserved(Peers.point(2), Peers.point(2)) {
                    case _: BlockFetchRuntime.Event.Sent => sent2.complete(()).void
                    case _                               => IO.unit
                  })
                  .start
                _ <- sent2.get
                _ <- first.cancel
                ended <- second.joinWithNever
                again <- Peers.typed(client.requestRange(Peers.point(3), Peers.point(3)))
                idle <- client.idle
              yield (ended, again, idle)
            }
          yield result
        }
      }
      .timeout(5.seconds)
      .map { result =>
        val (ended, again, idle) = result.fold(e => fail(e.message), identity)
        val expected = Left(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
        assertEquals(ended, expected)
        assertEquals(again, expected)
        assert(!idle)
      }

  test("a Busy timeout fails the entire pipeline with the original typed cause"):
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, _) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            result <- BlockFetchClient
              .resource(
                c,
                clientMux.abort,
                BlockFetchClient.Settings(timeLimits = BlockFetch.TimeLimits(busy = 100.millis))
              )
              .use { client =>
                for
                  sent <- Deferred[IO, Unit]
                  first <- Peers
                    .typed(client.requestRangeObserved(Peers.point(1), Peers.point(1)) {
                      case _: BlockFetchRuntime.Event.Sent => sent.complete(()).void
                      case _                               => IO.unit
                    })
                    .start
                  _ <- sent.get
                  second <- Peers.typed(client.requestRange(Peers.point(2), Peers.point(2))).start
                  results <- (first.joinWithNever, second.joinWithNever).tupled
                  again <- Peers.typed(client.requestRange(Peers.point(3), Peers.point(3)))
                yield (results, again)
              }
          yield result
        }
      }
      .timeout(5.seconds)
      .map { result =>
        val (results, again) = result.fold(e => fail(e.message), identity)
        val expected = Left(NetworkError.Timeout(Protocols.BlockFetch, "Busy"))
        assertEquals(results, (expected, expected))
        assertEquals(again, expected)
      }

  test("resource release settles pending requests before stopping its collector"):
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, _) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            sent <- Deferred[IO, Unit]
            pending <- BlockFetchClient.resource(c, clientMux.abort).use { client =>
              Peers
                .typed(client.requestRangeObserved(Peers.point(1), Peers.point(1)) {
                  case _: BlockFetchRuntime.Event.Sent => sent.complete(()).void
                  case _                               => IO.unit
                })
                .start
                .flatTap(_ => sent.get)
            }
            result <- pending.joinWithNever
          yield result
        }
      }
      .timeout(5.seconds)
      .map { result =>
        assertEquals(result, Right(Left(NetworkError.BearerClosed(NetworkError.Closure.Aborted))))
      }

  for pipelined <- List(false, true) do
    test(
      s"each original body is delivered before the next body or BatchDone; pipelined=$pipelined"
    ) {
      Peers
        .typed {
          Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
            for
              c <- clientMux.channel(Protocols.BlockFetch)
              s <- serverMux.channel(Protocols.BlockFetch)
              result <- (if pipelined then BlockFetchClient.resource(c, clientMux.abort)
                         else cats.effect.Resource.eval(BlockFetchClient(c))).use { client =>
                for
                  first <- Deferred[IO, Unit]
                  second <- Deferred[IO, Unit]
                  bodies <- Ref.of[IO, Vector[xyz.kd5ujc.scarab.codec.Bytes]](Vector.empty)
                  completed <- Ref.of[IO, Boolean](false)
                  one = CardanoBlockFetch.RawNtNBlock
                    .from(Peers.block(1))
                    .fold(e => fail(e.message), identity)
                  two = CardanoBlockFetch.RawNtNBlock
                    .from(Peers.block(2))
                    .fold(e => fail(e.message), identity)
                  downloading <- Peers
                    .typed(
                      client.requestRangeStreaming(Peers.point(1), Peers.point(2))(
                        body =>
                          bodies.update(_ :+ body) *> (if body == one.bytes then first else second)
                            .complete(())
                            .as(Right(())),
                        {
                          case _: BlockFetchRuntime.Event.Completed => completed.set(true)
                          case _                                    => IO.unit
                        }
                      )
                    )
                    .start
                  _ <- request(s)
                  _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.StartBatch)
                  _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.Block(one))
                  _ <- first.get
                  beforeSecond <- bodies.get
                  beforeDone <- completed.get
                  _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.Block(two))
                  _ <- second.get
                  _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.BatchDone)
                  outcome <- downloading.joinWithNever
                  _ <- client.done
                yield
                  assertEquals(beforeSecond, Vector(one.bytes))
                  assert(!beforeDone)
                  assertEquals(outcome, Right(Some(Vector(one.bytes, two.bytes))))
              }
            yield result
          }
        }
        .map(result => assertEquals(result, Right(())))
    }

  test("a typed streaming consumer failure aborts ordered collection and settles queued requests") {
    val refused = NetworkError.IngressLimit(Protocols.BlockFetch, 999)
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            s <- serverMux.channel(Protocols.BlockFetch)
            result <- BlockFetchClient.resource(c, clientMux.abort).use { client =>
              for
                first <- Peers
                  .typed(
                    client.requestRangeStreaming(Peers.point(1), Peers.point(1))(
                      _ => IO.pure(Left(refused)),
                      _ => IO.unit
                    )
                  )
                  .start
                _ <- request(s)
                second <- Peers.typed(client.requestRange(Peers.point(2), Peers.point(2))).start
                _ <- request(s)
                _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.StartBatch)
                block = CardanoBlockFetch.RawNtNBlock
                  .from(Peers.block(1))
                  .fold(e => fail(e.message), identity)
                _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.Block(block))
                a <- first.joinWithNever
                b <- second.joinWithNever
                reason <- clientMux.closed
              yield
                assertEquals(a, Left(refused))
                assertEquals(b, Left(refused))
                assertEquals(reason, refused)
            }
          yield result
        }
      }
      .map(result => assertEquals(result, Right(())))
  }

  /** Serve one range of `blocks`, each `apart` after the previous one. */
  private def serveSlowly(
      channel: MuxChannel[IO],
      blocks: Vector[CardanoBlockFetch.RawNtNBlock],
      apart: FiniteDuration
  )(using cats.mtl.Raise[IO, NetworkError]): IO[Unit] =
    request(channel) *> send(channel, BlockFetch.State.Busy, BlockFetch.Message.StartBatch) *>
      blocks.traverse_(block =>
        IO.sleep(apart) *> send(
          channel,
          BlockFetch.State.Streaming,
          BlockFetch.Message.Block(block)
        )
      ) *> send(channel, BlockFetch.State.Streaming, BlockFetch.Message.BatchDone)

  private def raw(bytes: Int, n: Int): CardanoBlockFetch.RawNtNBlock =
    CardanoBlockFetch.RawNtNBlock
      .from(xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(bytes)(n.toByte)))
      .fold(e => fail(e.message), identity)

  test("a range that keeps progressing within the per-state limit may take far longer than 120 s"):
    // Codec.hs:59–68: Busy and Streaming each wait longWait (60 s) per message, and a range
    // has no deadline as a whole; a block every 50 s is compliant.
    cats.effect.testkit.TestControl
      .executeEmbed {
        Peers.typed {
          Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
            val blocks = Vector.tabulate(5)(n => raw(100 + n, n))
            for
              c <- clientMux.channel(Protocols.BlockFetch)
              s <- serverMux.channel(Protocols.BlockFetch)
              result <- Fibers
                .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                  serveSlowly(s, blocks, 50.seconds)
                )
                .background
                .use(_ =>
                  BlockFetchClient
                    .resource(c, clientMux.abort)
                    .use(client =>
                      (client.requestRange(Peers.point(1), Peers.point(5)), IO.monotonic).tupled
                    )
                )
            yield (result, blocks)
          }
        }
      }
      .map { outcome =>
        val ((fetched, at), blocks) = outcome.fold(e => fail(e.message), identity)
        assertEquals(fetched, Some(blocks.map(_.bytes)))
        assert(at >= 250.seconds, at.toString)
      }

  test("a Byron-sized range of more than 10 MB is received whole"):
    // Bytes are bounded per message (Codec.hs:42–44) and by the protocol's ingress queue
    // (Cardano/Network/NodeToNode.hs:348–367), not per range.
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
          val blocks = Vector.tabulate(6)(n => raw(2000000, n))
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            s <- serverMux.channel(Protocols.BlockFetch)
            fetched <- Fibers
              .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                serveSlowly(s, blocks, Duration.Zero)
              )
              .background
              .use(_ =>
                BlockFetchClient
                  .resource(c, clientMux.abort)
                  .use(
                    _.requestBlocks(Peers.point(1), Peers.point(6), blocks.size)(
                      _ => IO.pure(Right(())),
                      _ => IO.unit
                    )
                  )
              )
          yield (fetched, blocks)
        }
      }
      .timeout(30.seconds)
      .map { outcome =>
        val (fetched, blocks) = outcome.fold(e => fail(e.message), identity)
        assert(blocks.map(_.bytes.size.toLong).sum > 10000000L)
        assertEquals(fetched, Some(blocks.map(_.bytes)))
      }

  for pipelined <- List(false, true) do
    test(s"too few requested blocks fail before Completed; pipelined=$pipelined") {
      Peers
        .typed {
          Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
            for
              c <- clientMux.channel(Protocols.BlockFetch)
              s <- serverMux.channel(Protocols.BlockFetch)
              result <- (if pipelined then BlockFetchClient.resource(c, clientMux.abort)
                         else cats.effect.Resource.eval(BlockFetchClient(c))).use { client =>
                for
                  events <- Ref.of[IO, Vector[BlockFetchRuntime.Event]](Vector.empty)
                  result <- cats.effect.Resource
                    .make(
                      Peers
                        .typed(
                          client.requestBlocks(Peers.point(1), Peers.point(2), 2)(
                            _ => IO.pure(Right(())),
                            event => events.update(_ :+ event)
                          )
                        )
                        .start
                    )(_.cancel)
                    .use { downloading =>
                      for
                        _ <- request(s)
                        _ <- send(s, BlockFetch.State.Busy, BlockFetch.Message.StartBatch)
                        _ <- send(s, BlockFetch.State.Streaming, BlockFetch.Message.BatchDone)
                        outcome <- downloading.joinWithNever
                        seen <- events.get
                      yield (outcome, seen)
                    }
                yield result
              }
            yield result
          }
        }
        .timeout(5.seconds)
        .map { result =>
          val (outcome, seen) = result.fold(e => fail(e.message), identity)
          assertEquals(
            outcome,
            Left(
              NetworkError.IllegalTransition(
                Protocols.BlockFetch,
                NetworkError.RuntimeViolation.BlockFetchTooFewBlocks(2, 0)
              )
            )
          )
          assert(!seen.exists(_.isInstanceOf[BlockFetchRuntime.Event.Completed]))
        }
    }
