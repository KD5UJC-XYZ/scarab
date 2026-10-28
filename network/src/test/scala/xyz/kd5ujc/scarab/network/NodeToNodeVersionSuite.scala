// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, NtnVersion}

import Peers.*

/** The node-to-node version a whole connection comes out with, for every pair of maxima the client
  * and the server can be configured with (`NodeToNode.Settings.maxVersion`,
  * `NodeToNodeServer.Settings.maxVersion`): the lower of the two, and the Peras flag never set.
  */
class NodeToNodeVersionSuite extends CatsEffectSuite:
  private val magic = 42L

  private val handlers = ServerHandlers[IO, Bytes](
    ChainSyncFixtures.rawItem,
    Resource.pure[IO, ChainSyncServer.Source[IO, Bytes]](new ChainSyncServer.Source[IO, Bytes]:
      def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(Peers.tip(1)))
      def poll = IO.pure(None)
      def await = IO.never),
    (_, _) => IO.pure(None),
    new TxSubmissionServer.Sink[IO]:
      def known(id: Bytes) = IO.pure(false)
      def submit(originals: Vector[Bytes])(using
          cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
      ) = IO.unit
  )

  private def connect(client: NtnVersion, server: NtnVersion) =
    typed(
      Resource
        .eval(Bearer.inMemory[IO]())
        .flatMap { (a, b) =>
          typed(
            NodeToNodeServer
              .accept(b, magic, handlers, NodeToNodeServer.Settings(maxVersion = server))
          ).void.background.flatMap(_ =>
            NodeToNode.warmOver[IO, Bytes](
              a,
              magic,
              ChainSyncFixtures.rawItem,
              NodeToNode.Settings(maxVersion = client)
            )
          )
        }
        .use(w => IO.pure(w.version))
    )

  test("a connection runs the lower of the two maxima, for all nine pairs"):
    val pairs = for c <- NtnVersion.All; s <- NtnVersion.All yield (c, s)
    pairs.traverse { (c, s) =>
      connect(c, s).map(v => assertEquals(v, Right(math.min(c.number, s.number)), s"$c $s"))
    }.void

  test("the defaults are the latest release: 15 on both sides"):
    typed(
      Resource
        .eval(Bearer.inMemory[IO]())
        .flatMap { (a, b) =>
          typed(NodeToNodeServer.accept(b, magic, handlers)).void.background.flatMap(_ =>
            NodeToNode.warmOver[IO, Bytes](a, magic, ChainSyncFixtures.rawItem)
          )
        }
        .use(w => IO.pure(w.version))
    ).map(v => assertEquals(v, Right(15)))

  test("the offers are the versions up to the maximum, without the Peras flag"):
    NtnVersion.All.foreach { max =>
      val offers = HandshakeServer.offers(magic, duplex = true, maxVersion = max)
      assertEquals(offers.map(_._1), NtnVersion.upTo(max).map(_.number))
      assert(offers.forall(!_._2.peras))
    }
    assertEquals(HandshakeServer.offers(magic, duplex = true).map(_._1), Vector(14, 15))
    assertEquals(HandshakeServer.localOffers(magic).map(_._1), (16 to 23).toVector)
