// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Message, Role, State}

/** Wire transitions from ouroboros-network 2fb69829a19b5c04e4fb212bdd78fb63f9d2d776,
  * ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/Type.hs:109–136. AwaitReply
  * acknowledges a request; its eventual roll instruction completes that same request.
  */
class ChainSyncBatchSuite extends FailsOnUncaught:
  private val protocol = Protocols.ChainSync
  private val limits = ChainSync.Limits()

  private def receive(channel: MuxChannel[IO])(using Raise[IO, NetworkError]): IO[Message[Bytes]] =
    channel.recv(limits.maxMessageBytes)(
      ChainSync.decodePrefix(State.Idle, Role.Client, _, ChainSyncFixtures.rawItem, limits)
    )

  private def send(channel: MuxChannel[IO], state: State, message: Message[Bytes])(using
      Raise[IO, NetworkError]
  ): IO[Unit] =
    ChainSync
      .encode(state, Role.Server, message, ChainSyncFixtures.rawItem, limits)
      .fold(error => NetworkError.IllegalTransition(protocol, error).raise[IO, Unit], channel.send)

  private def serve(channel: MuxChannel[IO])(using Raise[IO, NetworkError]): IO[Unit] =
    for
      // This barrier prevents any reply until the entire batch has arrived. A client that waits
      // for each reply before sending its next request cannot complete this conversation.
      requests <- Vector.fill(3)(()).traverse(_ => receive(channel))
      _ <- IO(assertEquals(requests, Vector.fill(3)(Message.RequestNext)))
      _ <- send(channel, State.NextCanAwait, Message.RollForward(Peers.header(1), Peers.tip(1)))
      _ <- send(channel, State.NextCanAwait, Message.AwaitReply)
      _ <- send(
        channel,
        State.NextMustReply,
        Message.RollBackward(ChainSync.Point.Origin, Peers.tip(2))
      )
      _ <- send(channel, State.NextCanAwait, Message.RollForward(Peers.header(2), Peers.tip(2)))
      next <- receive(channel)
      _ <- IO(assertEquals(next, Message.RequestNext))
      _ <- send(channel, State.NextCanAwait, Message.RollForward(Peers.header(3), Peers.tip(3)))
      done <- receive(channel)
      _ <- IO(assertEquals(done, Message.Done))
    yield ()

  for sduSize <- List(1, 65535) do
    test(s"mixed ChainSync batch keeps request alignment across $sduSize-byte SDUs"):
      TestControl
        .executeEmbed(
          Peers.typed {
            Peers
              .linked(
                protocols = Set(protocol),
                client = Mux.Config(maxSduPayload = sduSize),
                server = Mux.Config(role = Mux.Role.Responder, maxSduPayload = sduSize)
              )
              .use { (clientMux, serverMux) =>
                for
                  clientChannel <- clientMux.channel(protocol)
                  serverChannel <- serverMux.channel(protocol)
                  client <- ChainSyncClient(
                    clientChannel,
                    ChainSyncFixtures.rawItem,
                    ChainSyncClient
                      .Settings(canAwaitTimeout = 1.second, mustReplyTimeout = 1.second)
                  )
                  result <- Resource
                    .make(Peers.typed(serve(serverChannel)).start)(_.cancel)
                    .use { server =>
                      for
                        batch <- client.requestNextBatch(3)
                        idle <- client.idle
                        awaiting <- client.idling
                        next <- client.requestNext
                        _ <- client.done
                        served <- server.joinWithNever
                        _ <- Fibers.land[IO, NetworkError, Unit](served)
                      yield (batch, idle, awaiting, next)
                    }
                yield result
              }
          }
        )
        .map(result =>
          assertEquals(
            result,
            Right(
              (
                Vector(
                  ChainSyncClient.Update.RollForward(Peers.header(1), Peers.tip(1)),
                  ChainSyncClient.Update.RollBackward(ChainSync.Point.Origin, Peers.tip(2)),
                  ChainSyncClient.Update.RollForward(Peers.header(2), Peers.tip(2))
                ),
                true,
                false,
                ChainSyncClient.Update.RollForward(Peers.header(3), Peers.tip(3))
              )
            )
          )
        )
