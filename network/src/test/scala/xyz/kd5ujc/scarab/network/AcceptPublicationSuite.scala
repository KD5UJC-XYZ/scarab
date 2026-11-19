// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  ChainSyncFixtures,
  ConnectionState,
  Handshake,
  TxSubmission2Fixtures
}

/** An accepted connection is announced to the inbound governor only once the manager published it:
  * the reference writes `NewConnection` in the `Right` branch of its inbound negotiation only
  * (`ConnectionManager/Core.hs` 982-995 at ouroboros-network `c45735a5`, the same at `4646039f` and
  * `2f1c154f`), so a refused publication announces nothing.
  */
class AcceptPublicationSuite extends FailsOnUncaught:
  import Peers.*

  private val magic = 42L

  private val source = new ChainSyncServer.Source[IO, Bytes]:
    def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
    def poll = IO.pure(None)
    def await = IO.never[ChainSyncServer.Update[Bytes]]
  private val handlers = ServerHandlers[IO, Bytes](
    ChainSyncFixtures.rawItem,
    Resource.pure(source),
    (_, _) => IO.pure(None),
    new TxSubmissionServer.Sink[IO]:
      def reader = TxSubmission2Fixtures.structural
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
          Raise[IO, NetworkError]
      ) = IO.unit
  )

  /** `c`, with each publication of its handshake's handle counted in `published`; when `refuse`,
    * the manager refuses it, as it does for a connection it ended meanwhile.
    */
  private final class Publishing(c: Connection[IO], published: Ref[IO, Int], refuse: Boolean)
      extends Connection[IO]:
    export c.{
      remote,
      id,
      provenance,
      state,
      connected,
      handshakeFailed,
      promotedToWarmRemote,
      demotedToColdRemote,
      releaseInbound,
      releaseOutbound,
      awaitOutboundDemotion,
      exempt,
      stopped,
      close,
      closed,
      unregisterRemote,
      updateRemoteState
    }
    def negotiated(handle: Negotiated[IO])(using Raise[IO, ConnectionError]) =
      published.update(_ + 1) *> (
        if refuse then
          ConnectionError
            .Disconnected(c.remote.toString, ConnectionState.Terminated, None)
            .raise[IO, Announcement]
        else c.negotiated(handle)
      )

  /** For one accepted connection whose handshake the client completes: how many times its handle
    * was published to the manager, and how many times `accept` ran its `negotiated` resource;
    * `refuse` makes the manager refuse the publication.
    */
  private def announced(refuse: Boolean): IO[(Int, Int)] =
    TestControl.executeEmbed(
      (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).flatMapN { (published, calls) =>
        typed {
          for
            manager <- ConnectionManager.create[IO](ConnectionManager.Limits())
            pair <- Bearer.inMemory[IO](256)
            (theirs, ours) = pair
            served = Handle
              .allow[ConnectionError](
                manager.include(Served.remote, None).use { c =>
                  NodeToNodeServer.accept(
                    ours,
                    magic,
                    handlers,
                    Publishing(c, published, refuse),
                    negotiated = (_, _) => Resource.eval(calls.update(_ + 1))
                  )
                }
              )
              .rescue(error => IO.pure(ConnectionEnd.Refused(error)))
            offers = Handshake.nodeToNodeOffers(
              NodeToNode.Settings().maxVersion,
              Handshake.Data(magic, initiatorOnly = false, peerSharing = false)
            )
            client = Mux
              .handshakeResource(theirs, NodeToNodeServer.Protocol, Mux.Config(duplex = true))
              .use(mux => mux.channel(Protocols.Handshake).flatMap(HandshakeClient.run(_, offers)))
              .attempt
              .guarantee(theirs.close)
            _ <- (served, client).parTupled
          yield ()
        } *> (published.get, calls.get).tupled
      }
    )

  test("an accepted connection the manager publishes is announced once") {
    announced(refuse = false).map { (published, announcements) =>
      assertEquals(published, 1, "the handshake's handle is published once")
      assertEquals(announcements, 1, "the published connection is announced once")
    }
  }

  test("an accepted connection whose publication the manager refuses is not announced") {
    announced(refuse = true).map { (published, announcements) =>
      assertEquals(published, 1, "the manager is asked to publish the handshake's handle")
      assertEquals(announcements, 0, "announced after a refused publication")
    }
  }
