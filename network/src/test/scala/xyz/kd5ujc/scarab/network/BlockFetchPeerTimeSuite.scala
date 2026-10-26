// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}
import scala.concurrent.duration.*

class BlockFetchPeerTimeSuite extends FailsOnUncaught:
  private val codec = CardanoBlockFetch.payloadCodec()
  private val settings = BlockFetchClient.Settings(timeouts =
    Some(
      BlockFetchRuntime.Timeouts(
        ProtocolDeadline.After(10.seconds),
        ProtocolDeadline.After(3.seconds)
      )
    )
  )
  private def send(
      channel: MuxChannel[IO],
      state: BlockFetch.State,
      message: BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]
  )(using Raise[IO, NetworkError]): IO[Unit] =
    channel.send(
      BlockFetch
        .encode(state, BlockFetch.Role.Server, message, codec)
        .fold(e => fail(e.message), identity)
    )
  private def request(channel: MuxChannel[IO])(using Raise[IO, NetworkError]): IO[Unit] =
    channel
      .recv(65535)(BlockFetch.decodePrefix(BlockFetch.State.Idle, BlockFetch.Role.Client, _, codec))
      .void
  private val block =
    CardanoBlockFetch.RawNtNBlock.from(Peers.block(1)).fold(e => fail(e.message), identity)

  for queued <- List(false, true) do
    test(s"local body consumer time is excluded from the streaming wait, queued=$queued") {
      TestControl.executeEmbed {
        Peers
          .typed {
            Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (ours, theirs) =>
              for
                c <- ours.channel(Protocols.BlockFetch)
                s <- theirs.channel(Protocols.BlockFetch)
                client =
                  if queued then BlockFetchClient.resource(c, ours.abort, settings)
                  else Resource.eval(BlockFetchClient(c, settings))
                result <- Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        request(s) *> send(
                          s,
                          BlockFetch.State.Busy,
                          BlockFetch.Message.StartBatch
                        ) *>
                          IO.sleep(500.millis) *> send(
                            s,
                            BlockFetch.State.Streaming,
                            BlockFetch.Message.Block(block)
                          ) *>
                          IO.sleep(500.millis) *> send(
                            s,
                            BlockFetch.State.Streaming,
                            BlockFetch.Message.BatchDone
                          )
                      }
                      .start
                  )(_.cancel)
                  .use { _ =>
                    client.use(
                      _.requestRangeStreaming(Peers.point(1), Peers.point(1))(
                        _ => IO.sleep(5.seconds).as(Right(())),
                        _ => IO.unit
                      )
                    )
                  }
              yield assertEquals(result, Some(Vector(block.bytes)))
            }
          }
          .map(result => assertEquals(result, Right(())))
      }
    }

    // Codec.hs:59–68: each message has its state's limit; silences add up without a budget.
    test(s"peer silences within the per-state limit add up past it, queued=$queued") {
      TestControl.executeEmbed {
        Peers
          .typed {
            Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (ours, theirs) =>
              for
                c <- ours.channel(Protocols.BlockFetch)
                s <- theirs.channel(Protocols.BlockFetch)
                client =
                  if queued then BlockFetchClient.resource(c, ours.abort, settings)
                  else Resource.eval(BlockFetchClient(c, settings))
                result <- Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        request(s) *> send(
                          s,
                          BlockFetch.State.Busy,
                          BlockFetch.Message.StartBatch
                        ) *>
                          IO.sleep(2.seconds) *> send(
                            s,
                            BlockFetch.State.Streaming,
                            BlockFetch.Message.Block(block)
                          ) *>
                          IO.sleep(2.seconds) *> send(
                            s,
                            BlockFetch.State.Streaming,
                            BlockFetch.Message.BatchDone
                          )
                      }
                      .start
                  )(_.cancel)
                  .use { _ =>
                    client.use(c =>
                      Fibers
                        .scoped[IO, NetworkError, Option[Vector[xyz.kd5ujc.scarab.codec.Bytes]]](
                          NetworkError.Defect(_)
                        )(c.requestRange(Peers.point(1), Peers.point(1)))
                    )
                  }
              yield assertEquals(result, Right(Some(Vector(block.bytes))))
            }
          }
          .map(result => assertEquals(result, Right(())))
      }
    }

  test("a queued request excludes the overlapping portion of the previous consumer callback") {
    TestControl.executeEmbed {
      Peers
        .typed {
          Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (ours, theirs) =>
            for
              c <- ours.channel(Protocols.BlockFetch)
              s <- theirs.channel(Protocols.BlockFetch)
              entered <- Deferred[IO, Unit]
              result <- BlockFetchClient.resource(c, ours.abort, settings).use { client =>
                Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        request(s) *> send(
                          s,
                          BlockFetch.State.Busy,
                          BlockFetch.Message.StartBatch
                        ) *>
                          send(s, BlockFetch.State.Streaming, BlockFetch.Message.Block(block)) *>
                          send(s, BlockFetch.State.Streaming, BlockFetch.Message.BatchDone) *>
                          request(s) *> send(s, BlockFetch.State.Busy, BlockFetch.Message.NoBlocks)
                      }
                      .start
                  )(_.cancel)
                  .use { _ =>
                    Resource
                      .make(
                        Fibers
                          .scoped[IO, NetworkError, Option[Vector[xyz.kd5ujc.scarab.codec.Bytes]]](
                            NetworkError.Defect(_)
                          )(
                            client.requestRangeStreaming(Peers.point(1), Peers.point(1))(
                              _ => entered.complete(()).void *> IO.sleep(8.seconds).as(Right(())),
                              _ => IO.unit
                            )
                          )
                          .start
                      )(_.cancel)
                      .use { first =>
                        entered.get *> IO.sleep(4.seconds) *> client
                          .requestRange(Peers.point(2), Peers.point(2))
                          .flatMap { second =>
                            first.joinWithNever.map(one => one -> second)
                          }
                      }
                  }
              }
            yield assertEquals(result, Right(Some(Vector(block.bytes))) -> None)
          }
        }
        .map(result => assertEquals(result, Right(())))
    }
  }
