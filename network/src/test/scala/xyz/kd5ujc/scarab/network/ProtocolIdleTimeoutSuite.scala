// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.TxSubmission2Fixtures
import cats.effect.{IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import cats.mtl.Handle
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, ConnectionState, Handshake}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance, TimeoutExpired}

/** ouroboros-network 2fb69829 `Ouroboros/Network/InboundGovernor.hs` (329-335, 463-481, 499-570)
  * and `ConnectionManager/Core.hs` (1110-1248, 1889-2048, 2048-2320), with
  * `defaultProtocolIdleTimeout = 5`: the peer's side of a connection is tracked; an accepted
  * connection that the peer leaves idle for the idle timeout is released, unless our outbound side
  * uses it, which keeps it (`TimeoutExpired`) until it lets go.
  */
class ProtocolIdleTimeoutSuite extends FailsOnUncaught:
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
      def submit(originals: Vector[Bytes])(using
          cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]
      ) = IO.unit
  )

  /** What a scenario sees: the peer's mux, the server's end once there is one, when the handshake
    * finished, the manager and every state change it reported.
    */
  private final case class Seen(
      mux: Mux[IO],
      ended: IO[Option[ConnectionEnd]],
      began: FiniteDuration,
      manager: ConnectionManager[IO],
      changes: IO[Vector[(ConnectionState, ConnectionState)]]
  )

  /** Accept on one side, run the peer's handshake on the other (duplex when `duplex`), and hand
    * what is seen to `body`.
    */
  private def scenario[A](
      body: Net ?=> Seen => IO[A],
      duplex: Boolean = false
  ): IO[Either[NetworkError, A]] =
    typed {
      Resource
        .eval(
          (
            Bearer.inMemory[IO](),
            Ref.of[IO, Vector[(ConnectionState, ConnectionState)]](Vector.empty)
          ).tupled
        )
        .flatMap { case ((ours, theirs), log) =>
          for
            manager <- Resource.eval(
              ConnectionManager.create[IO](
                ConnectionManager.Limits(timeWait = Duration.Zero),
                Some {
                  case ConnectionEvent.Changed(_, from, to) => log.update(_ :+ (from -> to))
                  case _                                    => IO.unit
                }
              )
            )
            ended <- Resource.eval(IO.deferred[Either[NetworkError, ConnectionEnd]])
            _ <- typed(
              Handle
                .allow[ConnectionError](
                  manager
                    .include(Served.remote, None)
                    .use(NodeToNodeServer.accept(theirs, magic, handlers, _))
                )
                .rescue(e => IO.pure(ConnectionEnd.Refused(e)))
            )
              .flatMap(ended.complete)
              .background
            mux <- Mux.handshakeResource(
              ours,
              NodeToNodeServer.Protocol,
              Mux.Config(role = Mux.Role.Initiator, duplex = duplex)
            )
            _ <- Resource.eval(
              mux
                .channel(xyz.kd5ujc.scarab.protocol.Protocols.Handshake)
                .flatMap(
                  HandshakeClient.run(
                    _,
                    Handshake.nodeToNodeOffers(
                      xyz.kd5ujc.scarab.protocol.NtnVersion.LatestReleased,
                      Handshake.Data(magic, initiatorOnly = !duplex)
                    ),
                    10.seconds
                  )
                ) *> mux.installApplication(
                NodeToNodeServer.applicationProtocols(false),
                if duplex then NodeToNodeServer.applicationProtocols(false) else Set.empty
              )
            )
            began <- Resource.eval(IO.monotonic)
          yield Seen(mux, ended.tryGet.map(_.flatMap(_.toOption)), began, manager, log.get)
        }
        .use(seen => body(seen))
    }

  /** The peer's KeepAlive client on its mux: a remote responder of ours starts on its first byte.
    */
  private def keepAlive(mux: Mux[IO])(using Net): IO[KeepAliveClient[IO]] =
    mux.channel(xyz.kd5ujc.scarab.protocol.Protocols.KeepAlive).flatMap(KeepAliveClient[IO](_))

  /** The outbound side's reuse of the accepted connection. */
  private def reuse(manager: ConnectionManager[IO]): IO[Connection[IO]] =
    Handle
      .allow[ConnectionError](
        manager.acquire(Served.remote, Provenance.Outbound).allocated.map(_._1).flatMap {
          case Acquired.Reused(connection, _) => IO.pure(connection)
          case Acquired.Reserved(_)           => IO.raiseError(new AssertionError("not reused"))
        }
      )
      .rescue(e => IO.raiseError(new AssertionError(e.message)))

  private def state(manager: ConnectionManager[IO]): IO[Option[ConnectionState]] =
    manager.lookup(Served.remote).map(_.map(_.state))

  /** Every change the manager reported is one the reference accepts. */
  private def allValid(changes: Vector[(ConnectionState, ConnectionState)]): Unit =
    changes.foreach((from, to) =>
      assert(ConnectionState.valid(Some(from), Some(to)), s"invalid $from -> $to")
    )

  test("a connection silent after the handshake is released at the idle timeout"):
    TestControl
      .executeEmbed(scenario { seen =>
        for
          _ <- IO.sleep(4900.millis)
          early <- seen.ended
          _ <- IO.sleep(200.millis)
          late <- seen.ended
          now <- IO.monotonic
        yield (early, late, now - seen.began)
      })
      .map {
        case Right((early, late, elapsed)) =>
          assertEquals(early, None)
          assertEquals(late, Some(ConnectionEnd.Released))
          assertEquals(elapsed, 5.1.seconds)
        case Left(error) => fail(error.message)
      }

  test("a responder SDU at 4.9 s keeps the connection past the idle timeout"):
    TestControl
      .executeEmbed(scenario { seen =>
        for
          _ <- IO.sleep(4900.millis)
          client <- keepAlive(seen.mux)
          _ <- client.ping
          _ <- IO.sleep(2.seconds)
          open <- seen.ended
          now <- state(seen.manager)
        yield (open, now)
      })
      .map {
        case Right((open, now)) =>
          assertEquals(open, None)
          assertEquals(now, Some(ConnectionState.Inbound(DataFlow.Unidirectional)))
        case Left(error) => fail(error.message)
      }

  // `WaitIdleRemote`: the timer is armed again when the last running responder ends (issue 461)
  test("the timer is armed again when the last responder ends: released one timeout later"):
    TestControl
      .executeEmbed(scenario { seen =>
        for
          _ <- IO.sleep(1.second)
          client <- keepAlive(seen.mux)
          _ <- client.ping
          _ <- IO.sleep(1.second)
          _ <- client.done
          endedAt <- IO.monotonic
          _ <- IO.sleep(4900.millis)
          early <- seen.ended
          _ <- IO.sleep(200.millis)
          late <- seen.ended
          changes <- seen.changes
        yield (early, late, endedAt - seen.began, changes)
      })
      .map {
        case Right((early, late, endedAt, changes)) =>
          assertEquals(endedAt, 2.seconds)
          assertEquals(early, None)
          assertEquals(late, Some(ConnectionEnd.Released))
          allValid(changes)
          assertEquals(
            changes.map(_._2),
            Vector(
              ConnectionState.InboundIdle(DataFlow.Unidirectional),
              ConnectionState.Inbound(DataFlow.Unidirectional),
              ConnectionState.InboundIdle(DataFlow.Unidirectional),
              ConnectionState.Terminating,
              ConnectionState.Terminated
            )
          )
        case Left(error) => fail(error.message)
      }

  test("no timer while a responder runs: an open KeepAlive keeps the connection"):
    TestControl
      .executeEmbed(scenario { seen =>
        for
          _ <- IO.sleep(1.second)
          client <- keepAlive(seen.mux)
          _ <- client.ping
          _ <- IO.sleep(30.seconds)
          open <- seen.ended
        yield open
      })
      .map {
        case Right(open) => assertEquals(open, None)
        case Left(error) => fail(error.message)
      }

  // `TimeoutExpired` (`Core.hs` 1148-1157), `KeepTr` and `RemoteCold` (`InboundGovernor.hs`
  // 547-563)
  test("expiry while the outbound side uses the connection keeps it: Ticking becomes Expired"):
    TestControl
      .executeEmbed(
        scenario(
          seen =>
            for
              _ <- IO.sleep(1.second)
              _ <- reuse(seen.manager)
              reused <- state(seen.manager)
              _ <- IO.sleep(30.seconds)
              open <- seen.ended
              now <- state(seen.manager)
              changes <- seen.changes
            yield (reused, open, now, changes),
          duplex = true
        )
      )
      .map {
        case Right((reused, open, now, changes)) =>
          assertEquals(reused, Some(ConnectionState.OutboundDup(TimeoutExpired.Ticking)))
          assertEquals(open, None)
          assertEquals(now, Some(ConnectionState.OutboundDup(TimeoutExpired.Expired)))
          allValid(changes)
        case Left(error) => fail(error.message)
      }

  // `DemotedToCold^{Duplex}_{Local}` before the timer fired: `InboundIdle`, released by the same
  // timer (`Core.hs` 1952-1985, 1188-1201)
  test("an outbound release before expiry leaves the connection to the idle timer"):
    TestControl
      .executeEmbed(
        scenario(
          seen =>
            for
              _ <- IO.sleep(1.second)
              connection <- reuse(seen.manager)
              _ <- IO.sleep(1.second)
              released <- connection.releaseOutbound
              after <- state(seen.manager)
              _ <- IO.sleep(2900.millis)
              early <- seen.ended
              _ <- IO.sleep(200.millis)
              late <- seen.ended
              changes <- seen.changes
            yield (released, after, early, late, changes),
          duplex = true
        )
      )
      .map {
        case Right((released, after, early, late, changes)) =>
          assertEquals(
            released,
            OperationResult.Success(ConnectionState.OutboundDup(TimeoutExpired.Ticking))
          )
          assertEquals(after, Some(ConnectionState.InboundIdle(DataFlow.Duplex)))
          assertEquals(early, None)
          assertEquals(late, Some(ConnectionEnd.Released))
          allValid(changes)
        case Left(error) => fail(error.message)
      }

  // `DemotedToCold^{Duplex}_{Local}` after it fired: `OutboundIdle`, closed after the outbound
  // idle timeout (`Core.hs` 1937-1950, 2017-2048)
  test("an outbound release after expiry closes the connection one outbound idle timeout later"):
    TestControl
      .executeEmbed(
        scenario(
          seen =>
            for
              _ <- IO.sleep(1.second)
              connection <- reuse(seen.manager)
              _ <- IO.sleep(10.seconds)
              start <- IO.monotonic
              released <- connection.releaseOutbound
              end <- IO.monotonic
              _ <- IO.sleep(100.millis)
              closed <- seen.ended
              changes <- seen.changes
            yield (released, end - start, closed, changes),
          duplex = true
        )
      )
      .map {
        case Right((released, waited, closed, changes)) =>
          assertEquals(released, OperationResult.Success(ConnectionState.Terminating))
          assertEquals(waited, 5.seconds)
          assertEquals(closed, Some(ConnectionEnd.Released))
          allValid(changes)
          assert(
            changes.contains(
              ConnectionState.OutboundDup(TimeoutExpired.Expired) ->
                ConnectionState.OutboundIdle(DataFlow.Duplex)
            )
          )
        case Left(error) => fail(error.message)
      }

  // `PromotedToWarm^{Duplex}_{Remote}` after `RemoteCold`, `DemotedToCold^{Duplex}_{Remote}`, and
  // `Awake^{Duplex}_{Remote}` of an outbound idle connection (`Core.hs` 2085-2192, 2286-2320)
  test("remote activity restores duplex use, and wakes an outbound idle connection"):
    TestControl
      .executeEmbed(
        scenario(
          seen =>
            for
              _ <- IO.sleep(1.second)
              connection <- reuse(seen.manager)
              _ <- IO.sleep(10.seconds)
              client <- keepAlive(seen.mux)
              _ <- client.ping
              busy <- state(seen.manager)
              _ <- client.done
              _ <- IO.sleep(100.millis)
              quiet <- state(seen.manager)
              _ <- IO.sleep(10.seconds)
              // expired again; released: `OutboundIdle`, then the peer wakes it up
              released <- connection.releaseOutbound.start
              _ <- IO.sleep(1.second)
              idle <- state(seen.manager)
              again <- keepAlive(seen.mux)
              _ <- again.ping
              outcome <- released.joinWithNever
              woken <- state(seen.manager)
              open <- seen.ended
              changes <- seen.changes
            yield (busy, quiet, idle, outcome, woken, open, changes),
          duplex = true
        )
      )
      .map {
        case Right((busy, quiet, idle, outcome, woken, open, changes)) =>
          assertEquals(busy, Some(ConnectionState.Duplex))
          assertEquals(quiet, Some(ConnectionState.OutboundDup(TimeoutExpired.Ticking)))
          assertEquals(idle, Some(ConnectionState.OutboundIdle(DataFlow.Duplex)))
          assertEquals(
            outcome,
            OperationResult.Unsupported(Some(ConnectionState.Inbound(DataFlow.Duplex)))
          )
          assertEquals(woken, Some(ConnectionState.Inbound(DataFlow.Duplex)))
          assertEquals(open, None)
          allValid(changes)
        case Left(error) => fail(error.message)
      }
