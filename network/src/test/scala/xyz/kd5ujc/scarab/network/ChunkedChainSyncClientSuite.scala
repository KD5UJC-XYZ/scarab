// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.Chain
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import xyz.kd5ujc.scarab.codec.{Bytes, ChunkedBytes}
import xyz.kd5ujc.scarab.protocol.{CardanoBlockFetch, ChainSync}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import scala.concurrent.duration.*

class ChunkedChainSyncClientSuite extends FailsOnUncaught:
  private val config = Mux.Config.nodeToClient.copy(sduSize = 31, receiveBufferLimit = 128)
  private val codec = CardanoBlockFetch.localPayloadCodec
  private val settings = ChainSyncClient.Settings(limits = ChainSync.Limits.NodeToClient)
  private val body = ChunkedBytes
    .fromChunks(
      Chain.fromSeq(
        Vector.fill(20000)(Bytes.fromArray(Array[Byte](0xa5.toByte, 0x80.toByte, 0xff.toByte)))
      )
    )
    .fold(e => fail(e.toString), identity)

  test(
    "ordinary and rolling local clients retain fragmented opaque payloads beyond the receive array"
  ) {
    TestControl
      .executeEmbed(
        Peers
          .typed {
            Peers
              .linked(Set(Protocols.LocalChainSync), config.copy(role = Mux.Role.Initiator), config)
              .use { (clientMux, serverMux) =>
                for
                  c <- clientMux.channel(Protocols.LocalChainSync)
                  s <- serverMux.channel(Protocols.LocalChainSync)
                  offered <- Ref.of[IO, Long](0L)
                  source = new ChainSyncServer.Source[IO, CardanoBlockFetch.LocalBlock]:
                    def intersect(points: Chain[Point]) =
                      offered.set(points.length).as(Right(Point.Origin -> Tip.Origin))
                    def poll = IO.pure(
                      Some(
                        ChainSyncServer.Update
                          .RollForward(CardanoBlockFetch.localBlock(body), Tip.Origin)
                      )
                    )
                    def await = IO.never
                  _ <- Peers
                    .typed(ChainSyncServer.run(s, codec, source, ChainSyncServer.NodeToClient))
                    .background
                    .use { served =>
                      for
                        ordinary <- ChainSyncClient(c, codec, settings)
                        _ <- ordinary.findIntersect(Chain.fromSeq(Vector.fill(20000)(Point.Origin)))
                        one <- ordinary.requestNext
                        _ <- ChainSyncClient.resource(c, codec, clientMux.abort, settings, 2).use {
                          pipeline =>
                            for
                              _ <- pipeline.findIntersect(Chain.one(Point.Origin))
                              replies <- pipeline.requestNextBatch(2)
                              _ <- pipeline.done
                            yield replies.foreach {
                              case ChainSyncClient.Update.RollForward(block, tip) =>
                                assertEquals(block.bytes, body)
                                assertEquals(tip, Tip.Origin)
                              case other => fail(s"unexpected pipelined response: $other")
                            }
                        }
                        ended <- served.flatMap(_.embedNever)
                        seen <- offered.get
                      yield
                        one match
                          case ChainSyncClient.Update.RollForward(block, _) =>
                            assertEquals(block.bytes, body)
                          case other => fail(s"unexpected ordinary response: $other")
                        assertEquals(ended, Right(()))
                        assertEquals(seen, 1L)
                    }
                yield ()
              }
          }
          .map(result => assertEquals(result, Right(())))
      )
      .timeout(30.seconds)
  }
