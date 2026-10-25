// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Point}

/** Queued requests use the wire directly: the ordinary client serializes whole ranges. */
class BlockFetchQueuedRangesSuite extends FailsOnUncaught:
  private val codec = CardanoBlockFetch.payloadCodec()
  private type Message = BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]

  private def encoded(state: BlockFetch.State, role: BlockFetch.Role, message: Message): Bytes =
    BlockFetch.encode(state, role, message, codec).fold(e => fail(e.message), identity)

  private def block(n: Int): CardanoBlockFetch.RawNtNBlock =
    CardanoBlockFetch.RawNtNBlock.from(Peers.block(n)).fold(e => fail(e.message), identity)

  private def hex(value: String): Bytes =
    Bytes.fromHex(value).fold(e => fail(e.message), identity)

  test("queued ranges preserve complete FIFO batches across NoBlocks before ClientDone"):
    // BlockFetch's request/response order is observable; the upstream client's scheduling is not.
    // ouroboros-network 2fb69829, ouroboros-network/protocols/tests-lib/
    // Ouroboros/Network/Protocol/BlockFetch/Test.hs:150-169.
    val first = Peers.point(1) -> Peers.point(2)
    val absent = Peers.point(3) -> Peers.point(4)
    val last = Peers.point(5) -> Peers.point(5)
    val expected: Vector[(BlockFetch.State, Message)] = Vector(
      BlockFetch.State.Busy -> BlockFetch.Message.StartBatch,
      BlockFetch.State.Streaming -> BlockFetch.Message.Block(block(1)),
      BlockFetch.State.Streaming -> BlockFetch.Message.Block(block(2)),
      BlockFetch.State.Streaming -> BlockFetch.Message.BatchDone,
      BlockFetch.State.Busy -> BlockFetch.Message.NoBlocks,
      BlockFetch.State.Busy -> BlockFetch.Message.StartBatch,
      BlockFetch.State.Streaming -> BlockFetch.Message.Block(block(5)),
      BlockFetch.State.Streaming -> BlockFetch.Message.BatchDone
    )
    // Literal control tags and tag-24 byte-string headers, independent of the production encoder.
    val expectedBytes = Vector(
      hex("8102"),
      hex("8204d8185865") ++ Peers.block(1),
      hex("8204d8185866") ++ Peers.block(2),
      hex("8105"),
      hex("8103"),
      hex("8102"),
      hex("8204d8185869") ++ Peers.block(5),
      hex("8105")
    )

    Peers
      .typed {
        for
          streaming <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          requests <- Ref.of[IO, Vector[(Point, Point)]](Vector.empty)
          source = new BlockFetchServer.Blocks[IO]:
            def range(from: Point, to: Point): IO[Option[Stream[IO, Bytes]]] =
              requests.update(_ :+ (from -> to)).as {
                if (from -> to) == first then
                  Some(
                    Stream.exec(streaming.complete(()).void *> release.get) ++
                      Stream.emits(Vector(Peers.block(1), Peers.block(2)))
                  )
                else if (from -> to) == last then Some(Stream.emit(Peers.block(5)))
                else None
              }
          result <- Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (client, server) =>
            for
              c <- client.channel(Protocols.BlockFetch)
              s <- server.channel(Protocols.BlockFetch)
              result <- Resource
                .make(Peers.typed(BlockFetchServer.run(s, source)).start)(_.cancel)
                .use { serving =>
                  for
                    _ <- c.send(
                      encoded(
                        BlockFetch.State.Idle,
                        BlockFetch.Role.Client,
                        BlockFetch.Message.RequestRange(first._1, first._2)
                      )
                    )
                    _ <- streaming.get
                    // The first stream is blocked while every remaining range reaches the bearer.
                    _ <- Vector[Message](
                      BlockFetch.Message.RequestRange(absent._1, absent._2),
                      BlockFetch.Message.RequestRange(last._1, last._2)
                    ).traverse_(message =>
                      c.send(encoded(BlockFetch.State.Idle, BlockFetch.Role.Client, message))
                    )
                    _ <- c.flush
                    before <- requests.get
                    _ <- IO(assertEquals(before, Vector(first)))
                    _ <- release.complete(())
                    observed <- expected.traverse { (state, _) =>
                      c.recv(2500000) { bytes =>
                        BlockFetch.decodePrefix(state, BlockFetch.Role.Server, bytes, codec) match
                          case DecodeResult.Decoded(message, consumed) =>
                            DecodeResult.Decoded((message, bytes.take(consumed)), consumed)
                          case DecodeResult.NeedMore      => DecodeResult.NeedMore
                          case DecodeResult.Failed(error) => DecodeResult.Failed(error)
                      }
                    }
                    // Client.hs:130–132 permits Done only after all pipelined replies are collected.
                    _ <- c.send(
                      encoded(
                        BlockFetch.State.Idle,
                        BlockFetch.Role.Client,
                        BlockFetch.Message.ClientDone
                      )
                    )
                    _ <- c.flush
                    ended <- serving.joinWithNever
                    order <- requests.get
                  yield (observed, ended, order)
                }
            yield result
          }
        yield result
      }
      .timeout(5.seconds)
      .map { outcome =>
        val (observed, ended, order) = outcome.fold(e => fail(e.message), identity)
        assertEquals(observed.map(_._1), expected.map(_._2))
        assertEquals(observed.map(_._2), expectedBytes)
        assertEquals(order, Vector(first, absent, last))
        assertEquals(ended, Right(()))
      }
