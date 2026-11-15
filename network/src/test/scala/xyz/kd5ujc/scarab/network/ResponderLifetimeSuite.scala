// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, Fiber, IO, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.Stream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  ChainSyncFixtures,
  ConnectionError,
  ConnectionState,
  Handshake,
  KeepAlive,
  NtnVersion,
  PeerSharing,
  RelayOffer,
  TxSubmission2,
  TxSubmission2Fixtures
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance}
import scala.concurrent.duration.*

import Peers.*

/** The life of a connection's responders. The reference's inbound governor starts each responder on
  * demand and, while the connection is registered, starts it again on demand when it ends cleanly
  * (ouroboros-network c45735a5 `InboundGovernor.hs` 410-443; 457-477 at 4646039f, which no longer
  * restarts once the connection is unregistered). That serves a peer that demotes us from hot to
  * warm, which ends ChainSync, BlockFetch and TxSubmission2 with `MsgDone`, and later promotes us
  * again, which starts them afresh on the same connection (`PeerStateActions.hs` 952-1034,
  * `startProtocols` 1164-1195). Scarab's responders start again on demand for the life of the
  * connection's resource (`NodeToNodeServer.start`). These tests pin that each conversation is
  * served again, each conversation's first message in an SDU of its own, and that every responder
  * ends when the connection is released, whatever releases it, and none starts after.
  */
class ResponderLifetimeSuite extends FailsOnUncaught:
  private val magic = 42L
  private val codec = ChainSyncFixtures.rawItem
  private given TxSubmission2.TransactionReader = TxSubmission2Fixtures.structural

  private val parkable =
    Set(Protocols.ChainSync, Protocols.BlockFetch, Protocols.TxSubmission2, Protocols.PeerSharing)

  /** What a handler of ours saw: a ChainSync conversation's source opened and closed, a handler
    * parked until its responder is cancelled, and cancelled.
    */
  private enum Seen:
    case Opened, Closed
    case Parked(protocol: Int)
    case Unparked(protocol: Int)

  private final class Probe(log: Ref[IO, Vector[Seen]]):
    def seen: IO[Vector[Seen]] = log.get
    private def note(seen: Seen): IO[Unit] = log.update(_ :+ seen)

    /** A handler that never returns: only the cancellation of its responder ends it. */
    def park[A](protocol: Int): IO[A] =
      IO.uncancelable(poll =>
        note(Seen.Parked(protocol)) *> poll(IO.never[A]).onCancel(note(Seen.Unparked(protocol)))
      )

    /** Our handlers. ChainSync parks at the tip; with `parking`, BlockFetch, TxSubmission2 and
      * PeerSharing park as soon as the peer asks them something.
      */
    def handlers(parking: Boolean): ServerHandlers[IO, Bytes] =
      val source = new ChainSyncServer.Source[IO, Bytes]:
        def intersect(points: Vector[Point]) = IO.pure(Right(Point.Origin -> Tip.Origin))
        def poll = IO.pure(None)
        def await = park(Protocols.ChainSync)
      val blocks: BlockFetchServer.Blocks[IO] = (_, _) =>
        if parking then park(Protocols.BlockFetch)
        else IO.pure(Some(Stream.emit(Peers.block(1)).covary[IO]))
      val sink = new TxSubmissionServer.Sink[IO]:
        def reader = TxSubmission2Fixtures.structural
        def known(id: Bytes) = if parking then park(Protocols.TxSubmission2) else IO.pure(false)
        def submit(originals: Vector[Bytes])(using Raise[IO, NetworkError]) = IO.unit
      val peers: PeerSharingServer.Source[IO] = _ =>
        if parking then park(Protocols.PeerSharing) else IO.pure(Vector.empty)
      ServerHandlers[IO, Bytes](
        codec,
        Resource.make(note(Seen.Opened))(_ => note(Seen.Closed)).as(source),
        blocks,
        sink,
        Some(peers)
      )

  private object Probe:
    def create: IO[Probe] = Ref.of[IO, Vector[Seen]](Vector.empty).map(Probe(_))

  private def count(seen: Vector[Seen], one: Seen): Int = seen.count(_ == one)

  // ------------------------------------------------------------------ the peer's side

  /** The peer's side of a connection we accepted, and what the test sees of ours: the end that
    * [[NodeToNodeServer.accept]] returns, and its fiber.
    */
  private final case class Accepted(
      mux: Mux[IO],
      manager: ConnectionManager[IO],
      end: IO[Either[NetworkError, ConnectionEnd]],
      server: Fiber[IO, Throwable, Unit],
      close: IO[Unit]
  )

  /** Accept a connection over an in-memory bearer as the node does ([[NodeToNodeServer.accept]]
    * under a manager), run the peer's handshake on the other end, which agrees PeerSharing, and
    * hand what is seen to `body`.
    */
  private def accepted[A](
      handlers: ServerHandlers[IO, Bytes],
      limits: ConnectionManager.Limits = ConnectionManager.Limits(timeWait = Duration.Zero),
      events: ConnectionEvent => IO[Unit] = _ => IO.unit
  )(body: Net ?=> Accepted => IO[A]): IO[Either[NetworkError, A]] =
    typed {
      Resource
        .eval((Bearer.inMemory[IO](), Deferred[IO, Either[NetworkError, ConnectionEnd]]).tupled)
        .flatMap { case ((ours, theirs), ended) =>
          for
            manager <- Resource.eval(
              ConnectionManager.create[IO](limits, Some(event => events(event)))
            )
            settings = NodeToNodeServer.Settings(duplex = false, offerPeerSharing = true)
            server <- Resource.make(
              typed(
                Handle
                  .allow[ConnectionError](
                    manager
                      .include(Served.remote, None)
                      .use(NodeToNodeServer.accept(theirs, magic, handlers, _, settings))
                  )
                  .rescue(e => IO.pure(ConnectionEnd.Refused(e)))
              ).flatMap(ended.complete).void.start
            )(_.cancel)
            mux <- Mux.handshakeResource(
              ours,
              NodeToNodeServer.Protocol,
              Mux.Config(role = Mux.Role.Initiator)
            )
            agreed <- Resource.eval(
              mux
                .channel(Protocols.Handshake)
                .flatMap(
                  HandshakeClient.run(
                    _,
                    Handshake.nodeToNodeOffers(
                      NtnVersion.LatestReleased,
                      Handshake.Data(magic, initiatorOnly = true, peerSharing = true)
                    ),
                    10.seconds
                  )
                )
            )
            _ <- Resource.eval(
              IO(assert(agreed.data.peerSharing, "the connection agreed no PeerSharing")) *>
                mux.installApplication(NodeToNodeServer.applicationProtocols(true), Set.empty)
            )
          yield Accepted(mux, manager, ended.get, server, ours.close)
        }
        .use(body)
    }

  private def chainSyncClient(mux: Mux[IO])(using Net): IO[ChainSyncClient[IO, Bytes]] =
    mux.channel(Protocols.ChainSync, Mux.Role.Initiator).flatMap(ChainSyncClient(_, codec))

  private def blockFetchClient(mux: Mux[IO])(using Net): IO[BlockFetchClient[IO]] =
    mux.channel(Protocols.BlockFetch, Mux.Role.Initiator).flatMap(BlockFetchClient[IO](_))

  private def keepAliveClient(mux: Mux[IO])(using Net): IO[KeepAliveClient[IO]] =
    mux.channel(Protocols.KeepAlive, Mux.Role.Initiator).flatMap(KeepAliveClient[IO](_))

  private def peerSharingClient(mux: Mux[IO])(using Net): IO[PeerSharingClient[IO]] =
    mux.channel(Protocols.PeerSharing, Mux.Role.Initiator).flatMap(PeerSharingClient[IO](_))

  private val one: PeerSharing.Amount = PeerSharing.Amount.from(1).getOrElse(fail("amount"))

  private def tx(state: TxSubmission2.State, message: TxSubmission2.Message): Bytes =
    TxSubmission2.encode(state, ChainSync.Role.Client, message).fold(e => fail(e.message), identity)

  /** The peer's TxSubmission2 client, by hand: `MsgInit`, then the server's first request. */
  private def txInit(channel: MuxChannel[IO])(using Net): IO[TxSubmission2.Message] =
    channel.send(tx(TxSubmission2.State.Init, TxSubmission2.Message.Init)) *>
      channel.recv(TxSubmission2.maxMessageBytes(TxSubmission2.State.Idle))(
        TxSubmission2.decodePrefix(TxSubmission2.State.Idle, ChainSync.Role.Server, _)
      )

  /** The peer's clients on `mux` drive each of our responders into a state that only its
    * cancellation ends: ChainSync waits at the tip in our source; BlockFetch, TxSubmission2 and
    * PeerSharing in a parked handler; KeepAlive, having answered a cookie, waits for the next one.
    * Each client runs in an error scope of its own for the life of the resource.
    */
  private def parkAll(mux: Mux[IO]): Resource[IO, Unit] =
    val chainSync = typed(chainSyncClient(mux).flatMap { client =>
      client.findIntersect(Vector(Point.Origin)) *> client.requestNext
    })
    val blockFetch =
      typed(blockFetchClient(mux).flatMap(_.requestRange(Peers.point(1), Peers.point(1))))
    val transactions = typed(
      mux.channel(Protocols.TxSubmission2, Mux.Role.Initiator).flatMap { channel =>
        txInit(channel) *> channel.send(
          tx(
            TxSubmission2.State.Ids(true),
            TxSubmission2.Message.ReplyIds(
              Vector(RelayOffer(Bytes(Vector.fill(32)(1.toByte)), 100L))
            )
          )
        ) *> IO.never[Unit]
      }
    )
    val peerSharing = typed(peerSharingClient(mux).flatMap(_.request(one)))
    for
      _ <- chainSync.background
      _ <- blockFetch.background
      _ <- transactions.background
      _ <- peerSharing.background
      _ <- Resource.eval(typed(keepAliveClient(mux).flatMap(_.ping)))
    yield ()

  /** Every parkable responder parked once, the one ChainSync source open, nothing ended. */
  private def allParked(seen: Vector[Seen]): Boolean =
    parkable.forall(p => count(seen, Seen.Parked(p)) == 1) && count(seen, Seen.Opened) == 1 &&
      seen.size == parkable.size + 1

  /** Each parked handler cancelled once, the ChainSync source closed, and nothing else started. */
  private def assertAllEnded(seen: Vector[Seen]): Unit =
    parkable.foreach { p =>
      assertEquals(count(seen, Seen.Parked(p)), 1, s"protocol $p parked once: $seen")
      assertEquals(count(seen, Seen.Unparked(p)), 1, s"protocol $p cancelled: $seen")
    }
    assertEquals(count(seen, Seen.Opened), 1, s"one ChainSync conversation: $seen")
    assertEquals(count(seen, Seen.Closed), 1, s"the ChainSync source released: $seen")
    assertEquals(seen.size, 2 * parkable.size + 2, s"nothing else seen: $seen")

  // ------------------------------------------------------------------ served again

  /** The peer's KeepAlive and PeerSharing conversations, each answered once. */
  private def establishedRound(
      mux: Mux[IO]
  )(using Net): IO[(KeepAliveClient[IO], PeerSharingClient[IO], Vector[PeerSharing.Address])] =
    for
      keepAlive <- keepAliveClient(mux)
      _ <- keepAlive.ping
      peerSharing <- peerSharingClient(mux)
      shared <- peerSharing.request(one)
    yield (keepAlive, peerSharing, shared)

  // `deactivatePeerConnection` (ouroboros-network c45735a5 `PeerStateActions.hs` 986-1034:
  // `Terminate` for the hot protocols) then `activatePeerConnection` (952-981, `startProtocols`
  // 1164-1195): the peer's hot initiators end and start afresh on one connection while its
  // established ones run
  test(
    "a peer that demotes us from hot to warm and promotes us again is served again: each hot " +
      "conversation ends with MsgDone and starts afresh on the connection"
  ) {
    Settled { settle =>
      Probe.create.flatMap { probe =>
        accepted(probe.handlers(parking = false)) { peer =>
          def remote: IO[List[RemoteSt]] = settle *> peer.manager.remoteStates.map(_.values.toList)
          // one hot round: each conversation answered, then ended with `MsgDone`
          val hotRound =
            for
              chainSync <- chainSyncClient(peer.mux)
              found <- chainSync.findIntersect(Vector(Point.Origin))
              blockFetch <- blockFetchClient(peer.mux)
              blocks <- blockFetch.requestRange(Peers.point(1), Peers.point(1))
              txs <- peer.mux.channel(Protocols.TxSubmission2, Mux.Role.Initiator)
              request <- txInit(txs)
              during <- remote
              _ <- chainSync.done *> blockFetch.done
              _ <- txs.send(tx(TxSubmission2.State.Ids(true), TxSubmission2.Message.Done))
              after <- remote
            yield (found, blocks, request, during, after)
          for
            (_, _, shared) <- establishedRound(peer.mux)
            warm <- remote
            first <- hotRound
            second <- hotRound
            seen <- probe.seen
          yield (shared, warm, first, second, seen)
        }
      }
    }.map {
      case Right((shared, warm, first, second, seen)) =>
        val served = (
          Some(Point.Origin -> Tip.Origin),
          Some(Vector(Peers.block(1))),
          List(RemoteSt.Hot),
          List(RemoteSt.Warm)
        )
        assertEquals(shared, Vector.empty)
        assertEquals(warm, List(RemoteSt.Warm))
        Vector(first, second).foreach { case (found, blocks, request, during, after) =>
          assertEquals((found, blocks, during, after), served)
          request match
            case TxSubmission2.Message.RequestIds(true, 0, _) => ()
            case other => fail(s"not a first blocking request: $other")
        }
        assertEquals(seen, Vector(Seen.Opened, Seen.Closed, Seen.Opened, Seen.Closed))
      case Left(error) => fail(error.message)
    }
  }

  // ------------------------------------------------------------------ released

  /** What releases an accepted connection. */
  private enum Release:
    /** The peer closes its end of the bearer. */
    case PeerCloses

    /** The peer breaks a mini-protocol: a KeepAlive response sent as the client. */
    case PeerViolates

    /** The manager prunes it to admit a duplex connection of ours past the hard limit
      * (ouroboros-network c45735a5 `Core.hs` 2079-2122, `prunePolicy` `Diffusion/Policies.hs`
      * 234-254).
      */
    case Pruned

    /** The node shuts its server down: the fiber of `accept` is cancelled. */
    case Shutdown

  /** A mux that carries nothing, for the duplex connection a prune admits. */
  private final class Stub(done: Deferred[IO, NetworkError]) extends Mux[IO]:
    def awaitResponderActivity(using Raise[IO, NetworkError]) = IO.never
    def installApplication(initiators: Set[Int], responders: Set[Int])(using
        Raise[IO, NetworkError]
    ) = IO.unit
    def channel(protocol: Int)(using Raise[IO, NetworkError]) =
      NetworkError.Timeout(protocol, "stub").raise[IO, MuxChannel[IO]]
    def channel(protocol: Int, role: Mux.Role)(using Raise[IO, NetworkError]) = channel(protocol)
    def closed = done.get
    def failure = done.tryGet
    def abort(reason: NetworkError) = done.complete(reason).void

  /** Our duplex connection to another peer, promoted by that peer past the hard limit. */
  private def promoteOther(manager: ConnectionManager[IO]): IO[Unit] =
    Handle
      .allow[ConnectionError](
        manager
          .acquire(SocketAddress(ip"192.0.2.2", port"3001"), Provenance.Outbound)
          .allocated
          .flatMap {
            case (Acquired.Reserved(other), _) =>
              Deferred[IO, NetworkError].flatMap { done =>
                other.connected(None) *> other.negotiated(
                  Negotiated(
                    Stub(done),
                    HandshakeClient.Agreed(14, Handshake.Data(magic, initiatorOnly = false))
                  )
                ) *> other.promotedToWarmRemote.void
              }
            case (other, _) => IO.raiseError(new AssertionError(s"not a reservation: $other"))
          }
      )
      .rescue(e => IO.raiseError(new AssertionError(e.message)))

  /** Release the connection `how` says; the end `accept` returned, none when it was cancelled. */
  private def release(how: Release, peer: Accepted)(using Net): IO[Option[ConnectionEnd]] =
    how match
      case Release.PeerCloses => peer.close *> peer.end.map(_.toOption)
      case Release.PeerViolates =>
        val response =
          KeepAlive
            .encode(
              KeepAlive.State.Server,
              KeepAlive.Role.Server,
              KeepAlive.Message.Response(KeepAlive.Cookie.Zero)
            )
            .fold(e => fail(e.message), identity)
        peer.mux.channel(Protocols.KeepAlive, Mux.Role.Initiator).flatMap(_.send(response)) *>
          peer.end.map(_.toOption)
      case Release.Pruned   => promoteOther(peer.manager) *> peer.end.map(_.toOption)
      case Release.Shutdown => peer.server.cancel.as(None)

  private def expected(how: Release, end: Option[ConnectionEnd]): Unit =
    (how, end) match
      case (Release.PeerCloses, Some(ConnectionEnd.Closed(NetworkError.Closure.PeerClosed))) => ()
      case (
            Release.PeerViolates,
            Some(ConnectionEnd.Failed(NetworkError.Decode(Protocols.KeepAlive, _)))
          ) =>
        ()
      // the manager stops the connection and aborts its mux in one step; either ends `accept`
      case (Release.Pruned, Some(ConnectionEnd.Refused(ConnectionError.Pruned(_)))) => ()
      case (
            Release.Pruned,
            Some(ConnectionEnd.Closed(NetworkError.Closure.AbortedBy(ConnectionError.Pruned(_))))
          ) =>
        ()
      case (Release.Shutdown, None) => ()
      case other                    => fail(s"unexpected end: $other")

  for how <- Release.values do
    test(s"every responder of an accepted connection ends when it is released: $how") {
      val hardLimit =
        AcceptedConnectionsLimit.default.copy(acceptedConnectionsHardLimit = Word32.fromUnsigned(1))
      Settled { settle =>
        Probe.create.flatMap { probe =>
          accepted(
            probe.handlers(parking = true),
            ConnectionManager.Limits(hardLimit, timeWait = Duration.Zero)
          ) { peer =>
            parkAll(peer.mux).use { _ =>
              for
                parked <- Settled.observed(settle, "every responder parked")(probe.seen)(allParked)
                busy <- peer.manager.remoteStates.map(_.values.toList)
                end <- release(how, peer)
                _ <- settle
                seen <- probe.seen
                tracked <- peer.manager.remoteStates
                live <- peer.manager.lookup(Served.remote)
              yield (parked, busy, end, seen, tracked, live)
            }
          }
        }
      }.map {
        case Right((parked, busy, end, seen, tracked, live)) =>
          assertEquals(busy, List(RemoteSt.Hot), s"parked: $parked")
          expected(how, end)
          assertAllEnded(seen)
          assertEquals(tracked, Map.empty[Long, RemoteSt], "the remote state is unregistered")
          assertEquals(live.map(_.state), None, "the connection is out of the table")
        case Left(error) => fail(error.message)
      }
    }

  // `CommitRemote` after the protocol idle timeout (ouroboros-network c45735a5
  // `InboundGovernor.hs` 506-522, `Core.hs` 1188-1201). A responder whose first byte comes once
  // the timer has expired waits until the expiry is settled, as the reference's mux tracer retries
  // on an expired `RemoteIdle` (`InboundGovernor.hs` 646-648 at c45735a5, 691-693 at 4646039f);
  // settled by a commit, it never starts, and ends with the connection
  test(
    "a responder whose first byte comes while the idle timer's commit releases the connection " +
      "does not start, and ends with it"
  ) {
    val committing =
      ConnectionState.InboundIdle(DataFlow.Unidirectional) -> ConnectionState.Terminating
    Settled
      .timed { settle =>
        (Probe.create, Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap {
          (probe, entered, resume) =>
            accepted(
              probe.handlers(parking = false),
              events = {
                case ConnectionEvent.Changed(_, from, to) if (from -> to) == committing =>
                  entered.complete(()) *> resume.get
                case _ => IO.unit
              }
            ) { peer =>
              for
                chainSync <- peer.mux.channel(Protocols.ChainSync, Mux.Role.Initiator)
                first <- ChainSyncClient(chainSync, codec)
                // ChainSync's first byte starts KeepAlive too (`StartOnDemandAny`, network-mux
                // `Mux.hs` 571-577 at c45735a5)
                found <- first.findIntersect(Vector(Point.Origin))
                _ <- first.done
                // ChainSync's `MsgDone` is taken before the KeepAlive conversation starts
                _ <- settle
                keepAlive <- keepAliveClient(peer.mux)
                _ <- keepAlive.ping *> keepAlive.done
                // every responder has ended: the idle timer runs, expires, and its commit is held
                _ <- entered.get
                _ <- chainSync.send(
                  ChainSync
                    .encode(
                      ChainSync.State.Idle,
                      ChainSync.Role.Client,
                      ChainSync.Message.FindIntersect(Vector(Point.Origin)),
                      codec
                    )
                    .fold(e => fail(e.message), identity)
                )
                reply <- typed(
                  chainSync.recv(65535)(
                    ChainSync
                      .decodePrefix(ChainSync.State.Intersect, ChainSync.Role.Server, _, codec)
                  )
                ).start
                _ <- settle
                held <- probe.seen
                tracked <- peer.manager.remoteStates.map(_.values.toList)
                _ <- resume.complete(())
                end <- peer.end
                // every fiber has done all it can, and the peer's request has had no answer
                answered <- IO.race(reply.join, settle)
                _ <- reply.cancel
                seen <- probe.seen
                after <- peer.manager.remoteStates
              yield (found, held, tracked, end, answered, seen, after)
            }
        }
      }
      .map {
        case Right((found, held, tracked, end, answered, seen, after)) =>
          assertEquals(found, Some(Point.Origin -> Tip.Origin))
          assertEquals(held, Vector(Seen.Opened, Seen.Closed), "nothing starts while held")
          assertEquals(tracked, List(RemoteSt.Idle), "still registered while the commit is held")
          assertEquals(end, Right(ConnectionEnd.Released))
          assertEquals(answered, Right(()), "the peer's request is not answered")
          assertEquals(seen, Vector(Seen.Opened, Seen.Closed), "nothing started after")
          assertEquals(after, Map.empty[Long, RemoteSt])
        case Left(error) => fail(error.message)
      }
  }

  // a connection we dialled runs our responders for the life of its resource
  // ([[NodeToNode.negotiate]]), which the node holds until the mux closes or the manager ends it
  test("every responder of a connection we dialled ends when its resource is released") {
    Settled { settle =>
      (Probe.create, Bearer.inMemory[IO](), Deferred[IO, Boolean]).tupled.flatMap {
        case (probe, (ours, theirs), sharing) =>
          typed {
            // the peer accepts, and drives our responders as soon as it has negotiated
            typed(
              Served.accept[IO, Bytes](
                theirs,
                magic,
                probe.handlers(parking = false),
                NodeToNodeServer.Settings(offerPeerSharing = true),
                negotiated = (agreed, mux) =>
                  Resource.eval(sharing.complete(agreed.data.peerSharing).void) *> parkAll(mux)
              )
            ).background.surround {
              ConnectionManager.create[IO](ConnectionManager.Limits()).flatMap { manager =>
                Handle
                  .allow[ConnectionError](
                    manager.acquire(Served.remote, Provenance.Outbound).use {
                      case Acquired.Reserved(connection) =>
                        connection.connected(None) *>
                          NodeToNode
                            .negotiate[IO, Bytes](
                              ours,
                              magic,
                              connection,
                              NodeToNode.Settings(offerPeerSharing = true),
                              serve = Some(probe.handlers(parking = true))
                            )
                            .use(_ =>
                              Settled.observed(settle, "every responder parked")(probe.seen)(
                                allParked
                              ) *> manager.remoteStates.map(_.values.toList)
                            )
                            .flatMap(busy =>
                              (settle *> probe.seen, manager.remoteStates, sharing.get).tupled
                                .map((seen, tracked, agreed) => (busy, seen, tracked, agreed))
                            )
                      case other => IO.raiseError(new AssertionError(s"not reserved: $other"))
                    }
                  )
                  .rescue(e => IO.raiseError(new AssertionError(e.message)))
              }
            }
          }
      }
    }.map {
      case Right((busy, seen, tracked, agreed)) =>
        assert(agreed, "the connection agreed no PeerSharing")
        assertEquals(busy, List(RemoteSt.Hot))
        assertAllEnded(seen)
        assertEquals(tracked, Map.empty[Long, RemoteSt], "the remote state is unregistered")
      case Left(error) => fail(error.message)
    }
  }
