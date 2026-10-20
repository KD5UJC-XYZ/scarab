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

  test("collector timeout fails the entire pipeline with the original typed cause"):
    Peers
      .typed {
        Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, _) =>
          for
            c <- clientMux.channel(Protocols.BlockFetch)
            result <- BlockFetchClient
              .resource(
                c,
                clientMux.abort,
                BlockFetchClient.Settings(
                  timeLimits = BlockFetch.TimeLimits(wholeRequest = 100.millis)
                )
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
        val expected = Left(NetworkError.Timeout(Protocols.BlockFetch, "request"))
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
