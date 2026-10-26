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
                client = Mux.Config(sduSize = sduSize),
                server = Mux.Config(role = Mux.Role.Responder, sduSize = sduSize)
              )
              .use { (clientMux, serverMux) =>
                for
                  clientChannel <- clientMux.channel(protocol)
                  serverChannel <- serverMux.channel(protocol)
                  client <- ChainSyncClient(
                    clientChannel,
                    ChainSyncFixtures.rawItem,
                    ChainSyncClient
                      .Settings(
                        canAwaitTimeout = 1.second,
                        mustReply = ChainSyncTimeouts.Policy.Fixed(ProtocolDeadline.After(1.second))
                      )
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

  test("streaming callbacks publish a prefix and pause on AwaitReply before the batch completes"):
    TestControl.executeEmbed {
      Peers
        .typed {
          Peers.linked(protocols = Set(protocol)).use { (clientMux, serverMux) =>
            for
              clientChannel <- clientMux.channel(protocol)
              serverChannel <- serverMux.channel(protocol)
              client <- ChainSyncClient(clientChannel, ChainSyncFixtures.rawItem)
              prefix <- cats.effect.Deferred[IO, Unit]
              waiting <- cats.effect.Deferred[IO, Unit]
              events <- cats.effect.Ref.of[IO, Vector[String]](Vector.empty)
              result <- client
                .observe(new ChainSyncClient.Observer[IO]:
                  def awaitReply = events.update(_ :+ "await") *> waiting.complete(()).void
                  def reply = events.update(_ :+ "reply"))
                .use { _ =>
                  val server = for
                    requests <- Vector.fill(2)(()).traverse(_ => receive(serverChannel))
                    _ <- IO(assertEquals(requests, Vector.fill(2)(Message.RequestNext)))
                    _ <- send(
                      serverChannel,
                      State.NextCanAwait,
                      Message.RollForward(Peers.header(1), Peers.tip(1))
                    )
                    _ <- prefix.get
                    _ <- send(serverChannel, State.NextCanAwait, Message.AwaitReply)
                    _ <- waiting.get
                    _ <- send(
                      serverChannel,
                      State.NextMustReply,
                      Message.RollBackward(ChainSync.Point.Origin, Peers.tip(2))
                    )
                  yield ()
                  Resource.make(Peers.typed(server).start)(_.cancel).use { serving =>
                    for
                      batch <- client.requestNextBatchObserved(2) {
                        case ChainSyncClient.Update.RollForward(_, _) =>
                          events.update(_ :+ "forward") *> prefix.complete(()).void
                        case ChainSyncClient.Update.RollBackward(_, _) =>
                          events.update(_ :+ "backward")
                      }
                      completed <- serving.joinWithNever
                      _ <- Fibers.land[IO, NetworkError, Unit](completed)
                      recorded <- events.get
                    yield (batch.size, recorded)
                  }
                }
            yield result
          }
        }
        .map(result =>
          assertEquals(result, Right((2, Vector("reply", "forward", "await", "reply", "backward"))))
        )
    }
