// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{IO, Outcome, Resource}
import cats.effect.testkit.TestControl
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.LeiosHeader
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  AnnouncingHeader,
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
  LeiosNotifyServer,
  Mux,
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
  HfcHeader,
  IncrementalDecoder,
  LeiosFetch,
  LeiosNotify,
  LeiosWireError,
  ProtocolError,
  TxSubmission2Fixtures
}
import xyz.kd5ujc.scarab.storage.{
  LeiosEb,
  LeiosEbNotification,
  LeiosStoreLocks,
  LeiosStoreSuite,
  RocksLeiosStore
}
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given

import Peers.*

/** LeiosFetch and LeiosNotify on the Leios build's node-to-node connections (`withHot`,
  * ouroboros-consensus 2416493b `Ouroboros/Consensus/Network/NodeToNode.hs` 2000-2020 and
  * 2079-2100): one node's client, run by its hot activation, fetches an endorser block and its
  * closure from another's store server through the registered protocols, so the first node's store
  * completes the closure; the LeiosNotify server, with nothing to notify it, holds a peer's
  * requests and answers its quit, so the peer can demote the connection and promote it again; and a
  * release build's connection carries no Leios protocol.
  */
class LeiosFetchRegistrationSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, word32, slot, tx}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val magic = 42L
  private val connection = 7L
  private val issuer = Bytes(Vector.fill(28)(5.toByte))

  /** The public Leios testnet's announcing header 64849
    * (`fixtures/leios/testnet/headers-notable.tsv`, provenance in its README), the header a relay
    * announces with.
    */
  private val announcingHeader: LeiosHeader =
    java.nio.file.Files
      .readAllLines(java.nio.file.Path.of("fixtures/leios/testnet/headers-notable.tsv"))
      .asScala
      .map(_.split('\t'))
      .collectFirst { case cols if cols(0) == "announcing" => cols(5) }
      .flatMap(hex => Bytes.fromHex(hex).toOption)
      .flatMap(envelope => HfcHeader.decode(envelope).toOption)
      .collect { case HfcHeader.Dijkstra(raw) => raw }
      .flatMap(raw => LeiosHeader.decode(raw).toOption)
      .getOrElse(fail("the fixture's announcing header does not decode"))

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
    // no lead, so that the endorser block of slot 10 is offered from the origin
    val noLead = LeiosMinOfferLead(xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.fromUnsigned(0L))
    (store(dir / "a"), store(dir / "b"), Supervisor[IO](await = true)).tupled.use {
      (ours, theirs, settling) =>
        for
          _ <- ok(
            theirs
              .writeEbPoint(p, word32(size.toLong)) *> theirs.writeEbBody(p, eb, Vector.empty) *>
              theirs.writeTxs(p, txs.zipWithIndex.map((t, i) => i -> t))
          )
          acquired <- LeiosAcquired.create[IO]
          node <- ok(
            LeiosFetchNode.create[IO](
              acquired.notifying(ours),
              StdGen.mk(4),
              slot(0),
              settling,
              e => IO.raiseError(new AssertionError(e.toString)),
              IO.raiseError,
              _ => IO.pure(None),
              acquired.notifications.subscribe,
              IO.pure(None),
              noLead
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
          // another peer's LeiosNotify server holds three requests and is told of the endorser
          // block, whose body and closure the fetch then brings: it is offered both (the relay,
          // `hLeiosNotifyServer`'s pump at ouroboros-consensus 2416493b)
          announcement = AnnouncingHeader(
            announcingHeader,
            AnnouncementFields(ElId(p.slot, issuer), p.hash, size)
          )
          fetched <- node.notifier(LeiosVotesSuite.noVotes).flatTap(_.pump.background).use {
            downstream =>
              downstream.requested.replicateA_(3) *>
                node.announcedCentrally(announcement, ShouldRelay.DoRelay, IO.unit) *>
                typed(
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
                ).product(downstream.next.replicateA(3))
          }
          (completed, relayed) = fetched
          whole <- ok(ours.lookupEbClosure(p.hash))
        yield
          assertEquals(completed, Right(p))
          assertEquals(whole.map(_.map(_._2)), Some(txs))
          assertEquals(
            relayed,
            List(
              LeiosNotifyServer.Reply.Announcement(HfcHeader.Dijkstra(announcingHeader.raw)),
              LeiosNotifyServer.Reply.BlockOffer(p, word32(size.toLong)),
              LeiosNotifyServer.Reply.BlockTxsOffer(p)
            )
          )
    }
  }

  dirs.test("the hot protocols' Terminate lets the LeiosFetch client finish before the release") {
    dir =>
      (store(dir / "a"), store(dir / "b"), Supervisor[IO](await = true)).tupled.use {
        (ours, theirs, settling) =>
          for
            node <- ok(
              LeiosFetchNode.create[IO](
                ours,
                StdGen.mk(4),
                slot(0),
                settling,
                e => IO.raiseError(new AssertionError(e.toString)),
                IO.raiseError,
                _ => IO.pure(None),
                Resource.eval(Queue.unbounded[IO, LeiosEbNotification]),
                IO.pure(None),
                LeiosMinOfferLead.Default
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

  dirs.test(
    "a hot session that ends cleanly, the governor not yet told, ends its LeiosFetch client"
  ) { dir =>
    (store(dir / "a"), store(dir / "b"), Supervisor[IO](await = true)).tupled.use {
      (ours, theirs, settling) =>
        for
          node <- ok(
            LeiosFetchNode.create[IO](
              ours,
              StdGen.mk(4),
              slot(0),
              settling,
              e => IO.raiseError(new AssertionError(e.toString)),
              IO.raiseError,
              _ => IO.pure(None),
              Resource.eval(Queue.unbounded[IO, LeiosEbNotification]),
              IO.pure(None),
              LeiosMinOfferLead.Default
            )
          )
          (a, b) <- Bearer.inMemory[IO](64)
          // ChainSync returned: the session ends the hot protocols on the wire and releases the
          // activation, and only then does the governor tell them to stop and wait for them all
          program = typed(
            Served
              .accept(
                b,
                magic,
                handlers(Some(LeiosFetchStoreHandler(theirs))),
                NodeToNodeServer.Settings(bundle = HotBundle.Leios)
              )
              .background
              .surround(
                NodeToNode
                  .warmOverWithResults[IO, Bytes, Unit](
                    a,
                    magic,
                    ChainSyncFixtures.rawItem,
                    (),
                    NodeToNode.Settings(bundle = HotBundle.Leios)
                  )
                  .use(warm =>
                    (warm.applications.activateHot *>
                      warm.applications.startHot(false, leiosFetch = true)).flatMap(results =>
                      warm
                        .startHotProtocols(
                          results,
                          None,
                          None,
                          ChainSyncTimeouts.PeerTrust.Untrustable,
                          None,
                          leiosFetch = Some(node.initiator(connection))
                        )
                        .use(_.finishGracefully(false))
                    ) *> warm.applications.terminateHot *>
                      warm.applications.awaitAll(Set(ProtocolResults.Temperature.Hot))
                  )
              )
          )
          // no time passes: every result is in at once, none waits out the deactivation's 300 s
          control <- TestControl.execute(program)
          _ <- control.tick
          outcome <- control.results
          left <- node.shared.peers.get
        yield
          outcome match
            case Some(Outcome.Succeeded(results)) =>
              assertEquals(
                results,
                Right(
                  ProtocolResults.LastToFinish.AllSucceeded(
                    Map(
                      Protocols.ChainSync -> (),
                      Protocols.BlockFetch -> (),
                      Protocols.LeiosFetch -> ()
                    )
                  )
                )
              )
            case other => fail(s"the hot protocols' results are not all in: $other")
          assert(left.isEmpty)
    }
  }

  dirs.test("an activation released with its LeiosFetch client running ends it with the cause") {
    dir =>
      (store(dir / "a"), store(dir / "b"), Supervisor[IO](await = true)).tupled.use {
        (ours, theirs, settling) =>
          for
            node <- ok(
              LeiosFetchNode.create[IO](
                ours,
                StdGen.mk(4),
                slot(0),
                settling,
                e => IO.raiseError(new AssertionError(e.toString)),
                IO.raiseError,
                _ => IO.pure(None),
                Resource.eval(Queue.unbounded[IO, LeiosEbNotification]),
                IO.pure(None),
                LeiosMinOfferLead.Default
              )
            )
            (a, b) <- Bearer.inMemory[IO](64)
            // released neither ended on the wire nor told to stop
            released = (net: Net) ?=>
              activatedWith(a, HotBundle.Leios, node.initiator(connection))(IO.pure)
                .flatMap(_.snapshot.map(_.entries.get(Protocols.LeiosFetch).map(_.result)))
            ended <- typed(
              Served
                .accept(
                  b,
                  magic,
                  handlers(Some(LeiosFetchStoreHandler(theirs))),
                  NodeToNodeServer.Settings(bundle = HotBundle.Leios)
                )
                .background
                .surround(released)
            )
            left <- node.shared.peers.get
          yield
            assertEquals(
              ended,
              Right(
                Some(
                  ProtocolResults.Result.Errored(
                    NetworkError.BearerClosed(NetworkError.Closure.Aborted)
                  )
                )
              )
            )
            assert(left.isEmpty)
      }
  }

  /** The client's side of LeiosNotify on `channel`: messages sent, and replies read in `state`. */
  private final class NotifyClient(channel: MuxChannel[IO])(using Net):
    // the server's replies here carry no header: nothing is read by an era's decoder
    private given LeiosNotify.AnnouncementReader = new LeiosNotify.AnnouncementReader:
      def read(header: HfcHeader): Either[DomainError, Unit] = Right(())
    def send(message: LeiosNotify.Message): IO[Unit] =
      LeiosNotify
        .encode(message)
        .fold(e => IO.raiseError(new AssertionError(e.toString)), channel.send)
    def receive(state: LeiosNotify.State): IO[LeiosNotify.Message] =
      channel.recv(LeiosNotify.SmallByteLimit)(new IncrementalDecoder[LeiosNotify.Message]:
        type Cursor = Unit
        def initial: Cursor = ()
        def resume(cursor: Cursor, input: Bytes) =
          (
            (),
            LeiosNotify.decodePrefix(state, input) match
              case Right((m, n))                   => ChainSync.DecodeResult.Decoded(m, n)
              case Left(LeiosWireError.Incomplete) => ChainSync.DecodeResult.NeedMore
              case Left(e) => ChainSync.DecodeResult.Failed(ProtocolError.Leios(e))
          ))

    /** A conversation as the reference's pipelined client has one
      * (`leiosNotifyClientPeerPipelined`, `LeiosDemoOnlyTestNotify.hs` 523-634): `requests`
      * requests, then the quit, then the replies to what it left outstanding and the server's
      * `MsgDone`.
      */
    def conversation(requests: Int): IO[(Vector[LeiosNotify.Message], LeiosNotify.Message)] =
      send(LeiosNotify.Message.RequestNext).replicateA_(requests) *>
        send(LeiosNotify.Message.Quit) *>
        (
          receive(LeiosNotify.State.Busy).replicateA(requests).map(_.toVector),
          receive(LeiosNotify.State.Quit)
        ).tupled

  /** A Leios build's server over `b`, and our side over `a`, its LeiosNotify channel given to
    * `use`, with how the server's side of the connection ended.
    */
  private def notifying[A](a: Bearer[IO], b: Bearer[IO])(
      use: (NotifyClient, IO[ConnectionEnd]) => IO[A]
  )(using Net): IO[A] =
    Served
      .accept(b, magic, handlers(None), NodeToNodeServer.Settings(bundle = HotBundle.Leios))
      .background
      .use(end =>
        NodeToNode
          .warmOverWithResults[IO, Bytes, Unit](
            a,
            magic,
            ChainSyncFixtures.rawItem,
            (),
            NodeToNode.Settings(bundle = HotBundle.Leios)
          )
          .use(warm =>
            warm.mux
              .channel(Protocols.LeiosNotify, Mux.Role.Initiator)
              .flatMap(c => use(NotifyClient(c), end.flatMap(_.embedNever)))
          )
      )

  test("the LeiosNotify server holds the requests, answers the quit, and starts again after it") {
    for
      (a, b) <- Bearer.inMemory[IO](64)
      // a demotion with three requests held, a promotion on the same bearer, and a demotion with
      // none held (`start`'s `MsgQuit`)
      conversations <- typed(
        notifying(a, b)((client, _) =>
          (client.conversation(3), client.conversation(1), client.conversation(0)).tupled
        )
      )
    yield
      val canceled = LeiosNotify.Message.Canceled
      val done = LeiosNotify.Message.Done
      assertEquals(
        conversations,
        Right(((Vector.fill(3)(canceled), done), (Vector(canceled), done), (Vector.empty, done)))
      )
  }

  test("a LeiosNotify request beyond the pipelining depth ends the connection") {
    for
      (a, b) <- Bearer.inMemory[IO](64)
      ended <- typed(
        notifying(a, b)((client, end) =>
          client
            .send(LeiosNotify.Message.RequestNext)
            .replicateA_(LeiosNotifyServer.PipelineDepth) *>
            client.send(LeiosNotify.Message.RequestNext) *> end
        )
      )
    yield assertEquals(
      ended,
      Right(
        ConnectionEnd.Failed(
          NetworkError.LeiosNotifyExcessiveRequests(LeiosNotifyServer.PipelineDepth)
        )
      )
    )
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
