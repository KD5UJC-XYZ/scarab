// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSyncFixtures
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import scala.concurrent.duration.*

import Peers.*

/** BF's sending thread has ended once MsgClientDone is sent, even while another hot protocol waits
  * to finish. A late retained-block refusal must not abort that reusable bearer.
  */
class BlockFetchRetirementSuite extends FailsOnUncaught:
  private val handlers =
    val source = new ChainSyncServer.Source[IO, Bytes]:
      def intersect(points: Vector[Point]) = IO.pure(Right(Point.Origin -> Tip.Origin))
      def poll = IO.pure(None)
      def await = IO.never
    val blocks: BlockFetchServer.Blocks[IO] = (_, _) => IO.pure(None)
    val sink = new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes]) = IO.unit
    ServerHandlers(ChainSyncFixtures.rawItem, Resource.pure(source), blocks, sink)

  private def warm(using Net): Resource[IO, WarmConnection[IO, Bytes]] =
    Resource.eval(Bearer.inMemory[IO]()).flatMap { (ours, theirs) =>
      typed(NodeToNodeServer.accept(theirs, 42L, handlers)).void.background
        .flatMap(_ => NodeToNode.warmOver[IO, Bytes](ours, 42L, ChainSyncFixtures.rawItem))
    }

  List(false, true).foreach { chainSyncAlreadyEnded =>
    test(
      s"BF attribution ends before a paused Tx finish (CS already done=$chainSyncAlreadyEnded)"
    ) {
      typed {
        for
          live <- Ref.of[IO, Boolean](true)
          aborted <- Ref.of[IO, Boolean](false)
          txEntered <- Deferred[IO, Unit]
          txRelease <- Deferred[IO, Unit]
          result <- warm.use { connection =>
            connection
              .activate(blockFetchEnded = Some(live.set(false)))
              .use { hot =>
                val paused = new TxSubmissionClient.Running[IO]:
                  def finish(within: FiniteDuration) =
                    txEntered.complete(()).void *> txRelease.get.as(true)
                val finishing = NodeToNodeConnection(
                  hot.version,
                  hot.chainSync,
                  hot.blockFetch,
                  hot.mux,
                  Some(paused),
                  blockFetchEnded = Some(live.set(false))
                )
                for
                  _ <- hot.chainSync.findIntersect(Vector(Point.Origin))
                  _ <- hot.chainSync.done.whenA(chainSyncAlreadyEnded)
                  ended <- Resource
                    .make(
                      Fibers
                        .scoped[IO, NetworkError, Boolean](NetworkError.Defect(_))(
                          if chainSyncAlreadyEnded then finishing.finishAfterChainSync(5.seconds)
                          else finishing.finish(5.seconds)
                        )
                        .start
                    )(_.cancel)
                    .use { fiber =>
                      for
                        _ <- txEntered.get
                        // The late punishment uses the same source-registry liveness decision as the
                        // governors. Its source must already be retired while Tx remains blocked.
                        _ <- live.get.ifM(
                          aborted.set(true) *> hot.mux
                            .abort(NetworkError.BearerClosed(NetworkError.Closure.Aborted)),
                          IO.unit
                        )
                        isLive <- live.get
                        didAbort <- aborted.get
                        _ <- IO(assert(!isLive && !didAbort))
                        _ <- txRelease.complete(())
                        done <- fiber.joinWithNever
                      yield done
                    }
                yield ended
              }
              .flatMap { finished =>
                connection.activate().use { next =>
                  next.chainSync.findIntersect(Vector(Point.Origin)).flatMap { found =>
                    next.finish(5.seconds).map(reused => (finished, found, reused))
                  }
                }
              }
          }
        yield result
      }.map(result =>
        assertEquals(result, Right((Right(true), Some(Point.Origin -> Tip.Origin), true)))
      )
    }
  }
