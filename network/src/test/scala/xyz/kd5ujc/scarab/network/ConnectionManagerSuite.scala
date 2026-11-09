// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.protocol.{ConnectionState, Handshake}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{Counters, DataFlow, Provenance, TimeoutExpired}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

/** The connection manager against the reference's (ouroboros-network `2fb69829`,
  * `ConnectionManager/Core.hs`): admission, outbound acquisition and reuse, the releases of either
  * side, the waits, time-wait and pruning. Schedules are controlled: the virtual clock of
  * `TestControl`, and the order of the calls; every change the manager reports is checked against
  * the reference's `verifyAbstractTransition` ([[ConnectionState.valid]]).
  */
class ConnectionManagerSuite extends FailsOnUncaught with ScalaCheckSuite:
  private def peer(n: Int): SocketAddress[IpAddress] =
    SocketAddress(IpAddress.fromString(s"10.0.0.$n").get, Port.fromInt(3001).get)

  private def local(port: Int): ConnectionId.Endpoint =
    ConnectionId.Endpoint
      .fromSocketAddress(SocketAddress(ip"10.9.9.9", Port.fromInt(port).get))
      .toOption
      .get

  private def hard(n: Int): AcceptedConnectionsLimit =
    AcceptedConnectionsLimit.default.copy(acceptedConnectionsHardLimit = Word32.fromUnsigned(n))

  /** A mux that carries nothing: the manager only publishes it and stops it. */
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

  private def handle(flow: DataFlow): IO[Negotiated[IO]] =
    Deferred[IO, NetworkError].map(done =>
      Negotiated(
        Stub(done),
        HandshakeClient.Agreed(
          14,
          Handshake.Data(42L, initiatorOnly = flow == DataFlow.Unidirectional)
        )
      )
    )

  /** A manager whose every reported change is kept, for [[allValid]]. */
  private final case class Managed(
      cm: ConnectionManager[IO],
      events: IO[Vector[ConnectionEvent]]
  ):
    def changes: IO[Vector[(ConnectionState, ConnectionState)]] =
      events.map(_.collect { case ConnectionEvent.Changed(_, from, to) => from -> to })

  /** Run `program` with a manager; connection errors come back as values, and every change the
    * manager reported must be one the reference accepts.
    */
  private def managed[A](limits: ConnectionManager.Limits = ConnectionManager.Limits())(
      program: Handle[IO, ConnectionError] ?=> Managed => IO[A]
  ): IO[Either[ConnectionError, A]] =
    Ref.of[IO, Vector[ConnectionEvent]](Vector.empty).flatMap { seen =>
      Handle
        .allow[ConnectionError] {
          ConnectionManager
            .create[IO](limits, Some(event => seen.update(_ :+ event)))
            .flatMap(cm => program(Managed(cm, seen.get)))
            .map(_.asRight[ConnectionError])
        }
        .rescue(e => IO.pure(e.asLeft[A]))
        .flatTap(_ => seen.get.map(allValid))
    }

  private def allValid(events: Vector[ConnectionEvent]): Unit =
    events.foreach {
      case ConnectionEvent.Changed(remote, from, to) =>
        assert(ConnectionState.valid(Some(from), Some(to)), s"$remote: invalid $from -> $to")
      case _ => ()
    }

  private def result[A](either: Either[ConnectionError, A]): A =
    either.fold(e => fail(e.message), identity)

  private def attempt[A](program: Handle[IO, ConnectionError] ?=> IO[A]) =
    Handle
      .allow[ConnectionError](program.map(_.asRight[ConnectionError]))
      .rescue(e => IO.pure(Left(e)))

  private def state(cm: ConnectionManager[IO], n: Int): IO[Option[ConnectionState]] =
    cm.lookup(peer(n)).map(_.map(_.state))

  /** An accepted connection from `peer(n)`, negotiated `flow`; its release ends it. */
  private def accepted(
      cm: ConnectionManager[IO],
      n: Int,
      flow: DataFlow,
      at: Option[ConnectionId.Endpoint] = None
  )(using Raise[IO, ConnectionError]): IO[(Connection[IO], Negotiated[IO], IO[Unit])] =
    cm.include(peer(n), at).allocated.flatMap { (c, release) =>
      handle(flow).flatMap(h => c.negotiated(h).as((c, h, release)))
    }

  /** The outbound acquisition of `peer(n)`, kept: what it got, and its resource's release. */
  private def acquired(
      cm: ConnectionManager[IO],
      n: Int,
      provenance: Provenance = Provenance.Outbound
  )(using
      Raise[IO, ConnectionError]
  ): IO[(Acquired[IO], IO[Unit])] =
    cm.acquire(peer(n), provenance).allocated

  /** A connection we dial to `peer(n)`, negotiated `flow`; its release ends it. */
  private def dialled(cm: ConnectionManager[IO], n: Int, flow: DataFlow)(using
      Raise[IO, ConnectionError]
  ): IO[(Connection[IO], Negotiated[IO], IO[Unit])] =
    acquired(cm, n).flatMap {
      case (Acquired.Reserved(c), release) =>
        handle(flow).flatMap(h => c.connected(None) *> c.negotiated(h).as((c, h, release)))
      case (other, _) => IO.raiseError(new AssertionError(s"not a reservation: $other"))
    }

  // ------------------------------------------------------------------ admission

  test("an inbound connection is admitted, negotiated and released; the table follows") {
    managed(ConnectionManager.Limits(timeWait = Duration.Zero)) { m =>
      for
        (c, _, release) <- accepted(m.cm, 1, DataFlow.Duplex)
        live <- state(m.cm, 1)
        counted <- m.cm.snapshot.map(_.counters)
        _ <- release
        gone <- state(m.cm, 1)
        closed <- c.closed.timeout(1.second).attempt
      yield (live, counted, gone, closed.isRight)
    }.map { r =>
      val (live, counted, gone, closed) = result(r)
      assertEquals(live, Some(ConnectionState.InboundIdle(DataFlow.Duplex)))
      assertEquals(counted, Counters.zero.copy(duplex = 1, inbound = 1))
      assertEquals(gone, None)
      assert(closed)
    }
  }

  // `includeInboundConnectionImpl` (`Core.hs` 840-860): an accepted connection whose identity an
  // established one has is a `ForbiddenOperation`
  test("a second accepted connection with the same identity is refused") {
    managed() { m =>
      accepted(m.cm, 1, DataFlow.Duplex) *> attempt(m.cm.include(peer(1), None).use_)
    }.map(r =>
      assertEquals(
        result(r),
        Left(
          ConnectionError.ForbiddenOperation(
            peer(1).toString,
            ConnectionState.InboundIdle(DataFlow.Duplex)
          )
        )
      )
    )
  }

  test("the hard limit refuses an accepted connection; the per-address limit too") {
    managed(ConnectionManager.Limits(hard(2), maxInboundPerAddress = 1)) { m =>
      for
        _ <- accepted(m.cm, 1, DataFlow.Duplex, Some(local(1)))
        sameHost <- attempt(m.cm.include(peer(1), Some(local(2))).use_)
        _ <- accepted(m.cm, 2, DataFlow.Duplex)
        third <- attempt(m.cm.include(peer(3), None).use_)
      yield (sameHost, third)
    }.map { r =>
      val (sameHost, third) = result(r)
      assertEquals(sameHost, Left(ConnectionError.AddressLimit(peer(1).toString, 1)))
      assertEquals(third, Left(ConnectionError.InboundLimit(peer(3).toString, 2L)))
    }
  }

  test("a failing or cancelled owner still releases the connection") {
    managed(ConnectionManager.Limits(timeWait = Duration.Zero)) { m =>
      for
        _ <- m.cm
          .include(peer(1), None)
          .use(_ => IO.raiseError(new RuntimeException("boom")))
          .attempt
        failed <- state(m.cm, 1)
        fiber <- m.cm.include(peer(2), None).use(_ => IO.never[Unit]).start
        _ <- IO.sleep(10.millis)
        _ <- fiber.cancel
        cancelled <- state(m.cm, 2)
        last <- m.cm.snapshot
      yield (failed, cancelled, last)
    }.map { r =>
      val (failed, cancelled, last) = result(r)
      assertEquals(failed, None)
      assertEquals(cancelled, None)
      assertEquals(last.connections, Vector.empty)
    }
  }

  // ------------------------------------------------------------------ acquisition

  // inbound first: `Awake^{Duplex}_{Local}` and `PromotedToWarm^{Duplex}_{Local}` (`Core.hs`
  // 1335-1370); the handle is the one the inbound side published
  test("inbound first: the outbound side reuses the accepted duplex connection and its handle") {
    managed() { m =>
      for
        (_, published, _) <- accepted(m.cm, 1, DataFlow.Duplex)
        (got, _) <- acquired(m.cm, 1)
        idle <- state(m.cm, 1)
        _ <- accepted(m.cm, 2, DataFlow.Duplex)
        connection2 <- m.cm.lookup(peer(2))
        counters <- m.cm.snapshot.map(_.counters)
      yield (got, published, idle, connection2.map(_.state), counters)
    }.map { r =>
      val (got, published, idle, second, counters) = result(r)
      got match
        case Acquired.Reused(_, h) => assert(h eq published)
        case other                 => fail(s"not reused: $other")
      assertEquals(idle, Some(ConnectionState.OutboundDup(TimeoutExpired.Ticking)))
      assertEquals(second, Some(ConnectionState.InboundIdle(DataFlow.Duplex)))
      assertEquals(counters.outbound, 1)
    }
  }

  test("an active inbound duplex connection is promoted to duplex use") {
    managed() { m =>
      for
        (c, _, _) <- accepted(m.cm, 1, DataFlow.Duplex)
        _ <- c.promotedToWarmRemote
        _ <- acquired(m.cm, 1)
        now <- state(m.cm, 1)
        counters <- m.cm.snapshot.map(_.counters)
      yield (now, counters)
    }.map { r =>
      val (now, counters) = result(r)
      assertEquals(now, Some(ConnectionState.Duplex))
      assertEquals(counters, Counters(1, 1, 0, 1, 1, 0))
    }
  }

  test(
    "refusals: a unidirectional inbound, an existing outbound, an outbound idle, an unknown inbound peer"
  ) {
    managed(ConnectionManager.Limits(outboundIdle = 1.hour)) { m =>
      for
        _ <- accepted(m.cm, 1, DataFlow.Unidirectional)
        uni <- attempt(acquired(m.cm, 1))
        (c2, _, _) <- dialled(m.cm, 2, DataFlow.Unidirectional)
        exists <- attempt(acquired(m.cm, 2))
        _ <- c2.releaseOutbound.start
        _ <- m.cm
          .lookup(peer(2))
          .iterateUntil(_.exists(_.state == ConnectionState.OutboundIdle(DataFlow.Unidirectional)))
        idle <- attempt(acquired(m.cm, 2))
        unknown <- attempt(acquired(m.cm, 3, Provenance.Inbound))
        dialledUnknown <- m.cm.lookup(peer(3))
      yield (uni.void, exists.void, idle.void, unknown.void, dialledUnknown)
    }.map { r =>
      val (uni, exists, idle, unknown, dialledUnknown) = result(r)
      assertEquals(uni, Left(ConnectionError.ForbiddenConnection(peer(1).toString)))
      assertEquals(
        exists,
        Left(ConnectionError.AlreadyConnected(peer(2).toString, ConnectionState.OutboundUni))
      )
      assertEquals(
        idle,
        Left(
          ConnectionError.ForbiddenOperation(
            peer(2).toString,
            ConnectionState.OutboundIdle(DataFlow.Unidirectional)
          )
        )
      )
      assertEquals(unknown, Left(ConnectionError.InboundConnectionNotFound(peer(3).toString)))
      assertEquals(dialledUnknown, None)
    }
  }

  // outbound first: a dialled duplex connection, then the peer's own connection from another
  // local address: two connections, as the reference keys them (`ConnMap.hs` 45-50)
  test(
    "outbound first, and a reservation racing an accept: both are admitted, the reservation is found first"
  ) {
    managed() { m =>
      for
        (reserved, _) <- acquired(m.cm, 1)
        // the peer's connection arrives before ours connects
        (theirs, _, _) <- accepted(m.cm, 1, DataFlow.Duplex, Some(local(4000)))
        second <- attempt(acquired(m.cm, 1))
        c = reserved match
          case Acquired.Reserved(c) => c
          case other                => fail(s"not reserved: $other")
        h <- handle(DataFlow.Duplex)
        _ <- c.connected(Some(local(5000))) *> c.negotiated(h)
        ours <- c.state
        inbound <- theirs.state
        snapshot <- m.cm.snapshot
      yield (second.void, ours, inbound, snapshot)
    }.map { r =>
      val (second, ours, inbound, snapshot) = result(r)
      assertEquals(
        second,
        Left(ConnectionError.AlreadyConnected(peer(1).toString, ConnectionState.ReservedOutbound))
      )
      assertEquals(ours, ConnectionState.OutboundDup(TimeoutExpired.Ticking))
      assertEquals(inbound, ConnectionState.InboundIdle(DataFlow.Duplex))
      assertEquals(snapshot.connections.size, 2)
      assertEquals(snapshot.counters, Counters(0, 2, 0, 1, 1, 0))
    }
  }

  // an accepted connection with the identity our connection just took: `SelfConn` (`Core.hs`
  // 845-855), then `SelfConnNegotiated` (924-930)
  test("an accepted connection with our own identity takes the entry (a self-connection)") {
    managed() { m =>
      for
        (reserved, _) <- acquired(m.cm, 1)
        c = reserved match
          case Acquired.Reserved(c) => c
          case other                => fail(s"not reserved: $other")
        _ <- c.connected(Some(local(3001)))
        theirs <- m.cm.include(peer(1), Some(local(3001))).allocated.map(_._1)
        taken <- theirs.state
        h <- handle(DataFlow.Duplex)
        _ <- theirs.negotiated(h)
        negotiated <- theirs.state
        changes <- m.changes
      yield (taken, negotiated, changes)
    }.map { r =>
      val (taken, negotiated, changes) = result(r)
      assertEquals(taken, ConnectionState.Unnegotiated(Provenance.Inbound))
      assertEquals(negotiated, ConnectionState.InboundIdle(DataFlow.Duplex))
      assert(
        changes.contains(
          ConnectionState.Unnegotiated(Provenance.Outbound) -> ConnectionState.Unnegotiated(
            Provenance.Inbound
          )
        )
      )
    }
  }

  // `There` (`Core.hs` 1663-1755): the acquisition waits, without holding the table, for the
  // accepted connection's handshake
  test("an acquisition waits for an unnegotiated accepted connection, then reuses it") {
    TestControl
      .executeEmbed(managed() { m =>
        for
          (theirs, _) <- m.cm.include(peer(1), None).allocated
          waiting <- attempt(acquired(m.cm, 1)).start
          _ <- IO.sleep(1.second)
          // the table is not held: another peer is admitted meanwhile
          _ <- accepted(m.cm, 2, DataFlow.Duplex)
          h <- handle(DataFlow.Duplex)
          _ <- theirs.negotiated(h)
          got <- waiting.joinWithNever
          now <- state(m.cm, 1)
        yield (got.map(_._1), now)
      })
      .map { r =>
        val (got, now) = result(r)
        got match
          case Right(Acquired.Reused(_, _)) => ()
          case other                        => fail(s"not reused: $other")
        assertEquals(now, Some(ConnectionState.OutboundDup(TimeoutExpired.Ticking)))
      }
  }

  test(
    "a handshake that fails while an acquisition waits settles it as Disconnected, and frees the address"
  ) {
    TestControl
      .executeEmbed(managed() { m =>
        for
          (theirs, _) <- m.cm.include(peer(1), None).allocated
          waiting <- attempt(acquired(m.cm, 1)).start
          _ <- IO.sleep(1.second)
          _ <- theirs.handshakeFailed
          got <- waiting.joinWithNever
          // no time-wait in the table after a handshake failure (`Core.hs` 1782-1838)
          again <- attempt(acquired(m.cm, 1))
        yield (got.map(_._1), again.map(_._1))
      })
      .map { r =>
        val (got, again) = result(r)
        assertEquals(
          got.void,
          Left(ConnectionError.Disconnected(peer(1).toString, ConnectionState.Terminated))
        )
        again match
          case Right(Acquired.Reserved(_)) => ()
          case other                       => fail(s"not reserved: $other")
      }
  }

  // `retry` on `TerminatingState` (`Core.hs` 1379-1388), the time-wait (666-702), and a new
  // accepted connection in place of a terminating one (859-860)
  test(
    "an acquisition waits out the time-wait; an accepted connection replaces a terminating one"
  ) {
    TestControl
      .executeEmbed(managed(ConnectionManager.Limits(outboundIdle = 1.second)) { m =>
        for
          (c, _, release) <- dialled(m.cm, 1, DataFlow.Unidirectional)
          _ <- c.releaseOutbound
          _ <- release
          terminating <- m.cm.snapshot.map(_.connections.map(_._2))
          start <- IO.monotonic
          waiting <- attempt(acquired(m.cm, 1)).start
          got <- waiting.joinWithNever
          end <- IO.monotonic
          // the time-wait of another one, replaced by an accepted connection with its identity
          (theirs, _, gone) <- accepted(m.cm, 2, DataFlow.Duplex)
          _ <- theirs.releaseInbound
          _ <- gone
          replaced <- m.cm.include(peer(2), None).allocated.map(_._1)
          fresh <- replaced.state
        yield (terminating, got.map(_._1), end - start, fresh)
      })
      .map { r =>
        val (terminating, got, waited, fresh) = result(r)
        assertEquals(terminating, Vector(ConnectionState.Terminating))
        got match
          case Right(Acquired.Reserved(_)) => ()
          case other                       => fail(s"not reserved: $other")
        assert(waited >= 59.seconds && waited <= 61.seconds, waited.toString)
        assertEquals(fresh, ConnectionState.Unnegotiated(Provenance.Inbound))
      }
  }

  // ------------------------------------------------------------------ release

  // `releaseOutboundConnection` of a reused connection (`Core.hs` 1952-2014): the accepted
  // connection stays; its owner did not let go
  test("an outbound release leaves the inbound connection alive") {
    managed() { m =>
      for
        (theirs, _, _) <- accepted(m.cm, 1, DataFlow.Duplex)
        (got, _) <- acquired(m.cm, 1)
        ours = got match
          case Acquired.Reused(c, _) => c
          case other                 => fail(s"not reused: $other")
        released <- ours.releaseOutbound
        _ <- ours.awaitOutboundDemotion
        idle <- state(m.cm, 1)
        _ <- theirs.promotedToWarmRemote
        (again, _) <- acquired(m.cm, 1)
        ours2 = again match
          case Acquired.Reused(c, _) => c
          case other                 => fail(s"not reused: $other")
        _ <- ours2.releaseOutbound
        inbound <- state(m.cm, 1)
        closed <- theirs.closed.timeout(100.millis).attempt
      yield (released, idle, inbound, closed.isLeft)
    }.map { r =>
      val (released, idle, inbound, open) = result(r)
      assertEquals(
        released,
        OperationResult.Success(ConnectionState.OutboundDup(TimeoutExpired.Ticking))
      )
      assertEquals(idle, Some(ConnectionState.InboundIdle(DataFlow.Duplex)))
      assertEquals(inbound, Some(ConnectionState.Inbound(DataFlow.Duplex)))
      assert(open)
    }
  }

  test("an inbound close ends the outbound use") {
    managed() { m =>
      for
        (_, published, release) <- accepted(m.cm, 1, DataFlow.Duplex)
        (got, _) <- acquired(m.cm, 1)
        ours = got match
          case Acquired.Reused(c, _) => c
          case other                 => fail(s"not reused: $other")
        demoted <- ours.awaitOutboundDemotion.start
        _ <- release
        _ <- demoted.joinWithNever
        now <- ours.state
        stopped <- published.mux.failure
        after <- ours.releaseOutbound
      yield (now, stopped, after)
    }.map { r =>
      val (now, stopped, after) = result(r)
      assertEquals(now, ConnectionState.Terminated)
      assertEquals(stopped, None)
      assertEquals(after, OperationResult.Success(ConnectionState.Terminated))
    }
  }

  // `Commit^{dataFlow}` ends an idle accepted connection: it is stopped, held in time-wait, and
  // counts as terminating only
  test("releaseInbound commits an idle accepted connection: it is stopped and held in time-wait") {
    TestControl
      .executeEmbed(managed() { m =>
        for
          (c, h, release) <- accepted(m.cm, 1, DataFlow.Duplex)
          committed <- c.releaseInbound
          end <- c.stopped
          cause <- h.mux.failure
          _ <- release
          held <- m.cm.snapshot
          _ <- IO.sleep(61.seconds)
          after <- m.cm.snapshot
        yield (committed, end, cause, held, after)
      })
      .map { r =>
        val (committed, end, cause, held, after) = result(r)
        assertEquals(committed, OperationResult.Success(RemoteRelease.Commit))
        assertEquals(end, ConnectionEnd.Released)
        assert(cause.isDefined)
        assertEquals(held.counters, Counters.zero.copy(terminating = 1))
        assertEquals(after.connections, Vector.empty)
      }
  }

  // ------------------------------------------------------------------ pruning

  // `promotedToWarmRemote` of an outbound duplex connection past the hard limit (`Core.hs`
  // 2079-2120), with the reference's prune policy: an inbound connection goes before ours
  test(
    "remote promotion past the hard limit prunes an inbound connection, never the upstream one"
  ) {
    managed(ConnectionManager.Limits(hard(2))) { m =>
      for
        (a, ha, _) <- accepted(m.cm, 1, DataFlow.Duplex)
        (b, hb, _) <- accepted(m.cm, 2, DataFlow.Duplex)
        (ours, _, _) <- dialled(m.cm, 3, DataFlow.Duplex)
        promoted <- ours.promotedToWarmRemote
        states <- (a.state, b.state, ours.state).tupled
        stopped <- (ha.mux.failure, hb.mux.failure).tupled
        events <- m.events
      yield (promoted, states, stopped, events)
    }.map { r =>
      val (promoted, (sa, sb, ours), (fa, fb), events) = result(r)
      assertEquals(
        promoted,
        OperationResult.Success(ConnectionState.OutboundDup(TimeoutExpired.Ticking))
      )
      assertEquals(ours, ConnectionState.Duplex)
      assertEquals(Vector(sa, sb).count(_ == ConnectionState.Terminated), 1)
      assertEquals(Vector(fa, fb).count(_.isDefined), 1)
      assertEquals(
        events.collect { case ConnectionEvent.Pruned(p, n, c) => (p.size, n, c) },
        Vector((1, 1, 3))
      )
    }
  }

  // ------------------------------------------------------------------ model

  private enum Op:
    case Accept(peer: Int, local: Int)
    case Acquire(peer: Int, inbound: Boolean)
    case Negotiate(peer: Int, duplex: Boolean)
    case AwakeRemote(peer: Int)
    case IdleRemote(peer: Int)
    case ReleaseInbound(peer: Int)
    case ReleaseOutbound(peer: Int)
    case Close(peer: Int)
    case Wait

  private val ops: Gen[List[Op]] =
    val p = Gen.choose(1, 4)
    Gen.listOf(
      Gen.oneOf(
        Gen.zip(p, Gen.choose(1, 2)).map(Op.Accept(_, _)),
        Gen.zip(p, Gen.oneOf(true, false)).map(Op.Acquire(_, _)),
        Gen.zip(p, Gen.oneOf(true, false)).map(Op.Negotiate(_, _)),
        p.map(Op.AwakeRemote(_)),
        p.map(Op.IdleRemote(_)),
        p.map(Op.ReleaseInbound(_)),
        p.map(Op.ReleaseOutbound(_)),
        p.map(Op.Close(_)),
        Gen.const(Op.Wait)
      )
    )

  property(
    "every change is one the reference accepts, the counters follow the table within the hard limit, and nothing leaks"
  ) {
    val limits = ConnectionManager.Limits(
      hard(3),
      maxOutbound = 3,
      timeWait = 2.seconds,
      outboundIdle = 1.second
    )
    forAll(ops) { script =>
      TestControl
        .executeEmbed(managed(limits) { m =>
          // the owners of transports (with their release), and the outbound uses
          (
            Ref.of[IO, Map[Int, Vector[(Connection[IO], IO[Unit])]]](Map.empty),
            Ref.of[IO, Map[Int, Connection[IO]]](Map.empty)
          ).tupled.flatMap { (owners, uses) =>
            def owned(n: Int): IO[Vector[(Connection[IO], IO[Unit])]] =
              owners.get.map(_.getOrElse(n, Vector.empty))
            def quietly(program: Handle[IO, ConnectionError] ?=> IO[Unit]): IO[Unit] =
              Handle.allow[ConnectionError](program).rescue(_ => IO.unit)
            def step(op: Op): IO[Unit] = op match
              case Op.Accept(n, l) =>
                quietly(
                  m.cm
                    .include(peer(n), Some(local(l)))
                    .allocated
                    .flatMap(o =>
                      owners.update(m => m.updated(n, m.getOrElse(n, Vector.empty) :+ o))
                    )
                )
              case Op.Acquire(n, inbound) =>
                quietly(
                  IO.race(
                    m.cm
                      .acquire(peer(n), if inbound then Provenance.Inbound else Provenance.Outbound)
                      .allocated,
                    IO.sleep(3.seconds)
                  ).flatMap {
                    case Left((Acquired.Reserved(c), release)) =>
                      owners.update(m =>
                        m.updated(n, m.getOrElse(n, Vector.empty) :+ (c -> release))
                      ) *> uses.update(_.updated(n, c))
                    case Left((Acquired.Reused(c, _), _)) => uses.update(_.updated(n, c))
                    case Right(_)                         => IO.unit
                  }
                )
              case Op.Negotiate(n, duplex) =>
                owned(n).flatMap(_.traverse_ { (c, _) =>
                  quietly(
                    c.state.flatMap(s =>
                      c.connected(None).whenA(s == ConnectionState.ReservedOutbound)
                    ) *>
                      handle(if duplex then DataFlow.Duplex else DataFlow.Unidirectional)
                        .flatMap(c.negotiated)
                  )
                })
              case Op.AwakeRemote(n)    => owned(n).flatMap(_.traverse_(_._1.promotedToWarmRemote))
              case Op.IdleRemote(n)     => owned(n).flatMap(_.traverse_(_._1.demotedToColdRemote))
              case Op.ReleaseInbound(n) => owned(n).flatMap(_.traverse_(_._1.releaseInbound))
              case Op.ReleaseOutbound(n) =>
                uses
                  .modify(m => (m - n, m.get(n)))
                  .flatMap(_.traverse_(_.releaseOutbound.start.void))
              case Op.Close(n) =>
                owners.modify(m => (m - n, m.getOrElse(n, Vector.empty))).flatMap(_.traverse_(_._2))
              case Op.Wait => IO.sleep(1500.millis)
            for
              _ <- script.traverse_(op => step(op) *> IO.sleep(10.millis) *> check(m.cm, 3))
              _ <- owners.get.flatMap(_.values.flatten.toList.traverse_(_._2))
              _ <- IO.sleep(5.seconds)
              last <- m.cm.snapshot
            yield last.connections.isEmpty && last.counters == Counters.zero
          }
        })
        .map(result)
        .unsafeRunSync()
    }
  }

  /** After every step: the counters are those of the table and within the hard limit. */
  private def check(cm: ConnectionManager[IO], hardLimit: Int): IO[Unit] =
    cm.snapshot.flatMap { s =>
      val c = Counters.of(s.connections.map(_._2))
      IO.raiseUnless(c == s.counters && c.inbound <= hardLimit)(
        new IllegalStateException(s"$c vs ${s.counters}")
      )
    }
