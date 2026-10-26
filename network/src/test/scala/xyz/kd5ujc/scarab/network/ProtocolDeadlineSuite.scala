// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.effect.testkit.TestControl
import fs2.Stream
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures}

/** Absent native timers stay absent even when the compatibility finite defaults are much shorter.
  */
class ProtocolDeadlineSuite extends FailsOnUncaught:
  for unbounded <- List(false, true) do
    test(s"ChainSync can-await deadline unbounded=$unbounded") {
      TestControl.executeEmbed {
        Peers
          .typed {
            Peers.linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              for
                c <- clientMux.channel(Protocols.ChainSync)
                s <- serverMux.channel(Protocols.ChainSync)
                source = new ChainSyncServer.Source[IO, Bytes]:
                  def intersect(points: Vector[ChainSync.Point]) =
                    IO.pure(Right(ChainSync.Point.Origin -> ChainSync.Tip.Origin))
                  def poll = IO
                    .sleep(2.seconds)
                    .as(
                      Some(
                        ChainSyncServer.Update
                          .RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
                      )
                    )
                  def await = IO.never
                client <- ChainSyncClient(
                  c,
                  ChainSyncFixtures.rawItem,
                  ChainSyncClient.Settings(
                    canAwaitTimeout = 1.milli,
                    timeouts = Some(
                      ChainSyncRuntime.Timeouts(
                        ProtocolDeadline.Unbounded,
                        if unbounded then ProtocolDeadline.Unbounded
                        else ProtocolDeadline.After(1.second),
                        ProtocolDeadline.Unbounded
                      )
                    )
                  )
                )
                result <- Fibers
                  .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                    ChainSyncServer.run(
                      s,
                      ChainSyncFixtures.rawItem,
                      source,
                      ChainSyncServer.Settings(idleDeadline = Some(ProtocolDeadline.Unbounded))
                    )
                  )
                  .background
                  .use { _ => client.requestNext }
              yield result
            }
          }
          .map { result =>
            if unbounded then
              assertEquals(
                result,
                Right(
                  ChainSyncClient.Update.RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
                )
              )
            else
              assertEquals(result, Left(NetworkError.Timeout(Protocols.ChainSync, "NextCanAwait")))
          }
      }
    }

  private enum Timer:
    case Streaming, None

  for timer <- Timer.values.toList do
    test(s"BlockFetch exact optional deadline $timer") {
      TestControl.executeEmbed {
        Peers
          .typed {
            Peers.linked(protocols = Set(Protocols.BlockFetch)).use { (clientMux, serverMux) =>
              for
                c <- clientMux.channel(Protocols.BlockFetch)
                s <- serverMux.channel(Protocols.BlockFetch)
                blocks = new BlockFetchServer.Blocks[IO]:
                  def range(from: ChainSync.Point, to: ChainSync.Point, maxBlocks: Int) =
                    IO.pure(
                      Some(Stream.sleep_[IO](2.seconds) ++ Stream.emit(Bytes(Vector(1.toByte))))
                    )
                timeouts = BlockFetchRuntime.Timeouts(
                  ProtocolDeadline.Unbounded,
                  if timer == Timer.Streaming then ProtocolDeadline.After(1.second)
                  else ProtocolDeadline.Unbounded
                )
                result <- Fibers
                  .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                    BlockFetchServer.run(
                      s,
                      blocks,
                      BlockFetchServer.Settings(idleDeadline = Some(ProtocolDeadline.Unbounded))
                    )
                  )
                  .background
                  .use { _ =>
                    BlockFetchClient
                      .resource(
                        c,
                        clientMux.abort,
                        BlockFetchClient.Settings(timeouts = Some(timeouts))
                      )
                      .use(_.requestRange(Peers.point(1), Peers.point(1)))
                  }
              yield result
            }
          }
          .map { result =>
            timer match
              case Timer.None => assertEquals(result, Right(Some(Vector(Bytes(Vector(1.toByte))))))
              case Timer.Streaming =>
                assertEquals(result, Left(NetworkError.Timeout(Protocols.BlockFetch, "Streaming")))
          }
      }
    }
