// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  BytesSize,
  ElId,
  IsBigLedgerPeer,
  PeerOffer,
  StdGen,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.network.{
  Bearer,
  ChainSyncServer,
  ChainSyncTimeouts,
  ConnectionEnd,
  FailsOnUncaught,
  HotBundle,
  LeiosFetchInitiator,
  MuxChannel,
  NetworkError,
  NodeToNode,
  NodeToNodeServer,
  Peers,
  Protocols,
  Served,
  ProtocolResults,
  ServerHandlers,
  TxSubmissionServer
}
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  ChainSyncFixtures,
  EbPoint,
  LeiosFetch,
  TxSubmission2Fixtures
}
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given

import Peers.*

/** LeiosFetch on the Leios build's node-to-node connections (`withHot`, ouroboros-consensus
  * 2416493b `Ouroboros/Consensus/Network/NodeToNode.hs` 2000-2020 and 2079-2100): one node's
  * client, run by its hot activation, fetches an endorser block and its closure from another's
  * store server through the registered protocols, so the first node's store completes the closure;
  * and a release build's connection carries no Leios protocol.
  */
class LeiosFetchRegistrationSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, word32, slot, tx}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val magic = 42L
  private val connection = 7L
  private val issuer = Bytes(Vector.fill(28)(5.toByte))

  private val source = new ChainSyncServer.Source[IO, Bytes]:
    def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
    def poll = IO.pure(None)
    def await = IO.never[ChainSyncServer.Update[Bytes]]

  private def handlers(leios: Option[xyz.kd5ujc.scarab.network.LeiosFetchRequestHandler[IO]]) =
    ServerHandlers[IO, Bytes](
      ChainSyncFixtures.rawItem,
      Resource.pure(source),
      Peers.blocksOf((_, _) => IO.pure(Peers.unknown)),
      new TxSubmissionServer.Sink[IO]:
        def reader = TxSubmission2Fixtures.structural
        def known(id: Bytes) = IO.pure(false)
        def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
            Raise[IO, NetworkError]
        ) = IO.unit
      ,
      leiosFetch = leios
    )

  private def store(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))

  /** Our side of the connection over `bearer`, initiator only, its hot protocols activated with
    * `leios` 's LeiosFetch client, until `use` ends.
    */
  private def activated[A](bearer: Bearer[IO], bundle: HotBundle, leios: LeiosFetchInitiator[IO])(
      use: IO[A]
  )(using Net): IO[A] = activatedWith(bearer, bundle, leios)(_ => use)

  /** The same, `use` given the connection's protocol results. */
  private def activatedWith[A](
      bearer: Bearer[IO],
      bundle: HotBundle,
      leios: LeiosFetchInitiator[IO]
  )(use: ProtocolResults[IO, Unit] => IO[A])(using Net): IO[A] =
    NodeToNode
      .warmOverWithResults[IO, Bytes, Unit](
        bearer,
        magic,
        ChainSyncFixtures.rawItem,
        (),
        NodeToNode.Settings(bundle = bundle)
      )
      .flatMap(warm =>
        Resource
          .eval(
            warm.applications.activateHot *> warm.applications.startHot(false, leiosFetch = true)
          )
          .flatMap(results =>
            warm
              .startHotProtocols(
                results,
                None,
                None,
                ChainSyncTimeouts.PeerTrust.Untrustable,
                None,
                leiosFetch = Some(leios)
              )
              .as(warm.applications)
          )
      )
      .use(use)

  dirs.test(
    "a node fetches an endorser block and its closure from a peer's store over its hot protocols"
  ) { dir =>
    val txs = Vector.tabulate(40)(tx)
    val eb = LeiosStoreSuite.eb(
      NonEmptyVector.fromVectorUnsafe(txs.map(t => LeiosEb.hashTx(t) -> word32(t.size.toLong)))
    )
    val p = EbPoint(slot(10), LeiosEb.hashOf(eb.transactions.toVector))
    val size = BytesSize.wrap(eb.encoded.size.toLong)
    val closure = BytesSize.wrap(txs.map(_.size.toLong).sum)
    (store(dir / "a"), store(dir / "b")).tupled.use { (ours, theirs) =>
      for
        _ <- ok(
          theirs.writeEbPoint(p, word32(size.toLong)) *> theirs.writeEbBody(p, eb, Vector.empty) *>
            theirs.writeTxs(p, txs.zipWithIndex.map((t, i) => i -> t))
        )
        acquired <- LeiosAcquired.create[IO]
        node <- ok(
          LeiosFetchNode.create[IO](
            acquired.notifying(ours),
            StdGen.mk(4),
            slot(0),
            e => IO.raiseError(new AssertionError(e.toString))
          )
        )
        // the endorser block is focused, and the peer offers its body and its closure
        _ <- node.shared.outstanding.update(
          _.announced(AnnouncementFields(ElId(p.slot, issuer), p.hash, size), None)._1
        )
        vars <- LeiosPeerVars.create[IO](IsBigLedgerPeer.NotBigLedgerPeer)
        _ <- vars.offerings.set(
          SortedMap(p -> PeerOffer(Some(closure), Some(size), WhetherTxsClosureOffered.Offered))
        )
        _ <- node.shared.peers.update(_.updated(connection, vars))
        (a, b) <- Bearer.inMemory[IO](64)
        completed <- typed(
          Served
            .accept(
              b,
              magic,
              handlers(Some(LeiosFetchStoreHandler(theirs))),
              NodeToNodeServer.Settings(bundle = HotBundle.Leios)
            )
            .background
            .surround(
              activated(a, HotBundle.Leios, node.initiator(connection))(
                node
                  .kernel[Unit](
                    IO.pure(Some(p.slot)),
                    IO.pure(_ => Left(())),
                    Stream.empty,
                    IO.raiseError
                  )
                  .surround(node.shared.wake *> acquired.next)
                  .timeout(30.seconds)
              )
            )
        )
        whole <- ok(ours.lookupEbClosure(p.hash))
      yield
        assertEquals(completed, Right(p))
        assertEquals(whole.map(_.map(_._2)), Some(txs))
    }
  }

  dirs.test("the hot protocols' Terminate lets the LeiosFetch client finish before the release") {
    dir =>
      (store(dir / "a"), store(dir / "b")).tupled.use { (ours, theirs) =>
        for
          node <- ok(
            LeiosFetchNode.create[IO](
              ours,
              StdGen.mk(4),
              slot(0),
              e => IO.raiseError(new AssertionError(e.toString))
            )
          )
          (a, b) <- Bearer.inMemory[IO](64)
          // the activation is released as soon as the hot protocols are told to stop
          stoppedAtOnce = (net: Net) ?=>
            activatedWith(a, HotBundle.Leios, node.initiator(connection))(results =>
              results.terminateHot.as(results)
            ).flatMap(_.snapshot.map(_.entries.get(Protocols.LeiosFetch).map(_.result)))
          ended <- typed(
            Served
              .accept(
                b,
                magic,
                handlers(Some(LeiosFetchStoreHandler(theirs))),
                NodeToNodeServer.Settings(bundle = HotBundle.Leios)
              )
              .background
              .surround(stoppedAtOnce)
          )
          left <- node.shared.peers.get
        yield
          assertEquals(ended, Right(Some(ProtocolResults.Result.Returned(()))))
          // the client's bracket is released: the peer is no longer live
          assert(left.isEmpty)
      }
  }

  dirs.test("a release build's connection carries no Leios protocol: a LeiosFetch SDU ends it") {
    _ =>
      val sending = new LeiosFetchInitiator[IO]:
        def run(channel: MuxChannel[IO], onStop: IO[Unit] => Resource[IO, Unit])(using
            Raise[IO, NetworkError]
        ) =
          LeiosFetch
            .encode(LeiosFetch.Message.Done)
            .fold(e => IO.raiseError(new AssertionError(e.toString)), channel.send) *> IO.never
      // a release build adds nothing to the multiplexer's protocols
      assertEquals(HotBundle.Release.protocols, Set.empty[Int])
      assertEquals(NodeToNodeServer.Protocol.contains(Protocols.LeiosFetch), false)
      for
        (a, b) <- Bearer.inMemory[IO](64)
        // the release build's side accepts, and the connection ends with what ended it there
        ended <- typed(
          Served
            .accept(b, magic, handlers(None), NodeToNodeServer.Settings())
            .background
            .use(end => activated(a, HotBundle.Leios, sending)(end.flatMap(_.embedNever)))
        )
      yield ended match
        case Right(
              ConnectionEnd.Failed(
                NetworkError.IllegalTransition(
                  protocol,
                  NetworkError.RuntimeViolation.UnexpectedProtocol(unexpected)
                )
              )
            ) =>
          assertEquals((protocol, unexpected), (Protocols.LeiosFetch, Protocols.LeiosFetch))
        case other => fail(s"not ended over the unexpected protocol: $other")
  }
