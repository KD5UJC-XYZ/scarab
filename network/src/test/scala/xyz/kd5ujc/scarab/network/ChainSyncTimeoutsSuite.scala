// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Message, State}

class ChainSyncTimeoutsSuite extends FailsOnUncaught:
  import ChainSyncTimeouts.{PeerTrust, Policy}

  test("only an untrusted MustReply entry consumes a draw; explicit profiles retain their limits") {
    for
      draws <- Ref.of[IO, Int](0)
      draw = draws.update(_ + 1).as(601.0)
      untrusted = ChainSyncRuntime.ClientSettings()
      trusted = untrusted.copy(mustReply = Policy.Reference(PeerTrust.Trustable))
      first <- ChainSyncTimeouts.next(State.NextMustReply, untrusted)(draw)
      second <- ChainSyncTimeouts.next(State.NextMustReply, untrusted)(draw)
      forever <- ChainSyncTimeouts.next(State.NextMustReply, trusted)(draw)
      intersect <- ChainSyncTimeouts.next(State.Intersect, untrusted)(draw)
      canAwait <- ChainSyncTimeouts.next(State.NextCanAwait, untrusted)(draw)
      fixed <- ChainSyncTimeouts.next(
        State.NextMustReply,
        untrusted.copy(mustReply = Policy.Fixed(ProtocolDeadline.After(1.second)))
      )(draw)
      explicit <- ChainSyncTimeouts.next(
        State.NextMustReply,
        untrusted.copy(timeouts =
          Some(
            ChainSyncRuntime.Timeouts(
              ProtocolDeadline.Unbounded,
              ProtocolDeadline.Unbounded,
              ProtocolDeadline.After(2.seconds)
            )
          )
        )
      )(draw)
      count <- draws.get
    yield
      assertEquals(first, ProtocolDeadline.After(601.seconds))
      assertEquals(second, first)
      assertEquals(forever, ProtocolDeadline.Unbounded)
      assertEquals(intersect, ProtocolDeadline.After(10.seconds))
      assertEquals(canAwait, ProtocolDeadline.After(10.seconds))
      assertEquals(fixed, ProtocolDeadline.After(1.second))
      assertEquals(explicit, ProtocolDeadline.After(2.seconds))
      assertEquals(count, 2)
      assertEquals(
        ChainSyncClient
          .Settings(mustReply = Policy.Fixed(ProtocolDeadline.After(1.second)))
          .withPeerTrust(PeerTrust.Trustable)
          .mustReply,
        Policy.Fixed(ProtocolDeadline.After(1.second))
      )
  }

  private def request(channel: MuxChannel[IO])(using Raise[IO, NetworkError]): IO[Unit] =
    channel
      .recv(65535)(
        ChainSync.decodePrefix(State.Idle, ChainSync.Role.Client, _, ChainSyncFixtures.rawItem)
      )
      .map(message => assertEquals(message, Message.RequestNext))

  private def encode(state: State, message: Message[Bytes]): Bytes =
    ChainSync
      .encode(state, ChainSync.Role.Server, message, ChainSyncFixtures.rawItem)
      .fold(error => fail(error.message), identity)

  for
    rolling <- List(false, true)
    trust <- List(PeerTrust.Untrustable, PeerTrust.Trustable)
  do
    test(s"MustReply uses native bounds and trust with rolling=$rolling, trust=$trust") {
      TestControl.executeEmbed {
        Peers
          .typed {
            Peers.linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              for
                channel <- clientMux.channel(Protocols.ChainSync)
                remote <- serverMux.channel(Protocols.ChainSync)
                awaiting <- Deferred[IO, Unit]
                completed <- Deferred[IO, Either[NetworkError, ChainSyncClient.Update[Bytes]]]
                settings = ChainSyncClient.Settings().withPeerTrust(trust)
                client: Resource[IO, ChainSyncClient[IO, Bytes]] =
                  if rolling then
                    ChainSyncClient
                      .resource(
                        channel,
                        ChainSyncFixtures.rawItem,
                        clientMux.abort,
                        settings
                      )
                      .map(c => c: ChainSyncClient[IO, Bytes])
                  else Resource.eval(ChainSyncClient(channel, ChainSyncFixtures.rawItem, settings))
                _ <- Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        request(remote) *>
                          remote.send(encode(State.NextCanAwait, Message.AwaitReply)) *>
                          awaiting.complete(()).void *> IO.never
                      }
                      .start
                  )(_.cancel)
                  .use { _ =>
                    client.use { c =>
                      Resource
                        .make(
                          Fibers
                            .scoped[IO, NetworkError, ChainSyncClient.Update[Bytes]](
                              NetworkError.Defect(_)
                            )(c.requestNext)
                            .flatMap(completed.complete)
                            .start
                        )(_.cancel)
                        .use { _ =>
                          for
                            _ <- awaiting.get *> IO.sleep(600.seconds)
                            before <- completed.tryGet
                            _ = assertEquals(before, None)
                            _ <- IO.sleep(312.seconds)
                            after <- completed.tryGet
                          yield trust match
                            case PeerTrust.Trustable => assertEquals(after, None)
                            case PeerTrust.Untrustable =>
                              assertEquals(
                                after,
                                Some(
                                  Left(
                                    NetworkError.Timeout(
                                      Protocols.ChainSync,
                                      State.NextMustReply.toString
                                    )
                                  )
                                )
                              )
                        }
                    }
                  }
              yield ()
            }
          }
          .map(result => assertEquals(result, Right(())))
      }
    }

  for rolling <- List(false, true) do
    test(s"partial MustReply bytes do not renew the native budget with rolling=$rolling") {
      TestControl.executeEmbed {
        Peers
          .typed {
            Peers.linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
              for
                channel <- clientMux.channel(Protocols.ChainSync)
                remote <- serverMux.channel(Protocols.ChainSync)
                reply = encode(
                  State.NextMustReply,
                  Message.RollForward(Peers.header(1), Peers.tip(1))
                )
                settings = ChainSyncClient.Settings()
                client: Resource[IO, ChainSyncClient[IO, Bytes]] =
                  if rolling then
                    ChainSyncClient
                      .resource(
                        channel,
                        ChainSyncFixtures.rawItem,
                        clientMux.abort,
                        settings
                      )
                      .map(c => c: ChainSyncClient[IO, Bytes])
                  else Resource.eval(ChainSyncClient(channel, ChainSyncFixtures.rawItem, settings))
                result <- Resource
                  .make(
                    Fibers
                      .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                        request(remote) *> remote
                          .send(encode(State.NextCanAwait, Message.AwaitReply)) *>
                          IO.sleep(550.seconds) *> remote.send(reply.take(1)) *>
                          IO.sleep(450.seconds) *> remote.send(reply.drop(1)) *> IO.never
                      }
                      .start
                  )(_.cancel)
                  .use(_ =>
                    client.use(c =>
                      Fibers.scoped[IO, NetworkError, ChainSyncClient.Update[Bytes]](
                        NetworkError.Defect(_)
                      )(c.requestNext)
                    )
                  )
              yield assertEquals(
                result,
                Left(
                  NetworkError.Timeout(
                    Protocols.ChainSync,
                    State.NextMustReply.toString
                  )
                )
              )
            }
          }
          .map(result => assertEquals(result, Right(())))
      }
    }
