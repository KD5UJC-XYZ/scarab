// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import scala.concurrent.duration.*

/** What holds a fast peer back is the protocol and the session's own pipeline, not a queue that
  * stops the socket (the multiplexer's ingress queues never wait for a reader, see [[Mux]]): the
  * pipeline of a session asks for at most [[Peer.Depth]] batches ahead of the one the session is
  * applying, so a session that reads slowly keeps the peer from sending, and what the peer has sent
  * stays within the ingress limit of its protocol.
  */
class PipelineBackpressureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val readAhead = 4

  private lazy val forged: ForgedChain =
    ForgedChain.forge(5000 until 5060).unsafeRunSync()(using munitIORuntime)

  test("a session that applies slowly holds the pipeline back; reading faster releases it") {
    for
      honest <- ServedChain(forged)
      polls <- Ref.of[IO, Int](0)
      counting = new ServerHandlers[IO, NtNHeader](
        honest.codec,
        honest.chainSync.map { inner =>
          new ChainSyncServer.Source[IO, NtNHeader]:
            def intersect(points: Vector[ChainSync.Point]) = inner.intersect(points)
            def poll = polls.update(_ + 1) *> inner.poll
            def await = inner.await
        },
        honest.blocks,
        honest.transactions
      )
      pair <- Bearer.inMemory[IO](256)
      (theirs, ours) = pair
      served <- Handle
        .allow[NetworkError](NodeToNodeServer.accept(theirs, magic, counting).void)
        .rescue(_ => IO.unit)
        .start
      observed <- Handle
        .allow[PeerError] {
          Handle
            .allow[NetworkError](
              NodeToNode
                .over[IO, NtNHeader](ours, magic, NtNHeader.codec, NodeToNode.Settings())
                .use { connection =>
                  Peer
                    .over[IO](
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        magic,
                        readAhead = readAhead,
                        byronEpochSlots = ForgedChain.geometry.byron.epochSlots
                      ),
                      _ => IO.unit
                    )
                    .use { peer =>
                      val pull = peer.next.void
                      for
                        _ <- peer.intersect(Vector(ChainSync.Point.Origin))
                        // the session applies one instruction per 100 ms
                        _ <- (1 to 5).toList.traverse_(_ => pull *> IO.sleep(100.millis))
                        slow <- polls.get
                        // then it reads as fast as it can
                        _ <- (1 to 20).toList.traverse_(_ => pull)
                        fast <- polls.get
                      yield (slow, fast)
                    }
                }
            )
            .rescue(e => IO.raiseError(new RuntimeException(e.message)))
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      _ <- served.cancel
    yield
      val (slow, fast) = observed
      // 5 instructions were taken; the pipeline may hold `Depth` batches of `readAhead` besides the
      // one in use, and the first request goes alone
      val allowed = 1 + (Peer.Depth + 1) * readAhead
      assert(slow <= 5 + allowed, s"the peer was asked $slow times while the session took 5")
      assert(fast > slow, s"reading faster did not release the pipeline: $slow, then $fast")
  }
