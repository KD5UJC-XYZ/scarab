// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.network.selection.{ConsensusMode, Targets}
import xyz.kd5ujc.scarab.protocol.{ConnectionState, Handshake}
import xyz.kd5ujc.scarab.protocol.ConnectionState.{DataFlow, Provenance, TimeoutExpired}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The opt-in outbound limit ([[OutboundLimit]]): off by default, as the reference has none
  * (`acquireOutboundConnectionImpl`, ouroboros-network `c45735a5`, `ConnectionManager/Core.hs`
  * 1390-1418), and when set it counts the connections we dial from their reservation until the
  * outbound side lets them go or the reservation is released, whatever their state.
  */
class OutboundLimitSuite extends FailsOnUncaught with ScalaCheckSuite:
  private def peer(n: Int): SocketAddress[IpAddress] =
    SocketAddress(IpAddress.fromString(s"10.${n / 250}.${n % 250}.1").get, Port.fromInt(3001).get)

  private def hard(n: Int): AcceptedConnectionsLimit =
    AcceptedConnectionsLimit.default.copy(acceptedConnectionsHardLimit = Word32.fromUnsigned(n))

  private def atMost(n: Int): OutboundLimit =
    OutboundLimit.AtMost(OutboundLimit.Connections.from(n).toOption.get)

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

  private def attempt[A](program: Handle[IO, ConnectionError] ?=> IO[A]) =
    Handle
      .allow[ConnectionError](program.map(_.asRight[ConnectionError]))
      .rescue(e => IO.pure(Left(e)))

  /** What a dial of `peer(n)` got, released at once: `reserved`, `reused` or the refusal. */
  private def dial(
      cm: ConnectionManager[IO],
      n: Int,
      kind: OutboundLimit.Dial = OutboundLimit.Dial.Counted
  ) =
    attempt(cm.acquire(peer(n), Provenance.Outbound, IO.pure(kind)).use {
      case Acquired.Reserved(_)  => IO.pure("reserved")
      case Acquired.Reused(_, _) => IO.pure("reused")
    })

  private def accepted(cm: ConnectionManager[IO], n: Int, flow: DataFlow)(using
      Raise[IO, ConnectionError]
  ): IO[(Connection[IO], IO[Unit])] =
    cm.include(peer(n), None).allocated.flatMap { (c, release) =>
      handle(flow).flatMap(h => c.negotiated(h).as((c, release)))
    }

  /** A connection we dial to `peer(n)`, negotiated `flow`: its resource stays held, as the dialling
    * fiber of `OutboundPeers` holds it until the mux closes or the manager ends it.
    */
  private def dialled(
      cm: ConnectionManager[IO],
      n: Int,
      flow: DataFlow,
      kind: OutboundLimit.Dial = OutboundLimit.Dial.Counted
  )(using
      Raise[IO, ConnectionError]
  ): IO[(Connection[IO], IO[Unit])] =
    cm.acquire(peer(n), Provenance.Outbound, IO.pure(kind)).allocated.flatMap {
      case (Acquired.Reserved(c), release) =>
        handle(flow).flatMap(h => c.connected(None) *> c.negotiated(h).as((c, release)))
      case (other, _) => IO.raiseError(new AssertionError(s"not a reservation: $other"))
    }

  /** `n` connections we dialled duplex, whose peers run their own protocols on them and which we
    * then let go: each stays `Inbound Duplex`, the peer's, for as long as the peer likes.
    */
  private def keptByPeers(cm: ConnectionManager[IO], from: Int, n: Int)(using
      Raise[IO, ConnectionError]
  ): IO[Vector[ConnectionState]] =
    (from until from + n).toVector.traverse { k =>
      dialled(cm, k, DataFlow.Duplex).flatMap((c, _) =>
        c.promotedToWarmRemote *> c.releaseOutbound *> c.state
      )
    }

  test("without a limit, as in the reference, connections the peers keep never refuse a dial") {
    attempt {
      for
        cm <- ConnectionManager.create[IO]()
        // many connections, whether the peers keep them or we use them
        kept <- keptByPeers(cm, 1, 70)
        _ <- (101 until 171).toVector.traverse(k => dialled(cm, k, DataFlow.Duplex))
        snapshot <- cm.snapshot
        fresh <- dial(cm, 200)
      yield (kept, snapshot.dials, fresh)
    }.map {
      case Left(e) => fail(e.message)
      case Right((kept, dials, fresh)) =>
        assertEquals(kept.distinct, Vector(ConnectionState.Inbound(DataFlow.Duplex)))
        assertEquals(dials, 70, "only the 70 connections in outbound use are dials in use")
        assertEquals(fresh, Right("reserved"), "a new peer is dialled")
    }
  }

  test("the default limits are unlimited") {
    assertEquals(ConnectionManager.Limits().outbound, OutboundLimit.Unlimited)
  }

  test("a limit does not count the connections we let go that the peers keep using") {
    val limit = 4
    attempt {
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(limit)))
        kept <- keptByPeers(cm, 1, limit)
        counted <- cm.snapshot.map(_.dials)
        fresh <- dial(cm, 100)
        // as many again, and more: only the dials in use count
        more <- keptByPeers(cm, 101, 2 * limit)
        after <- cm.snapshot
        again <- dial(cm, 200)
        // the peers still use the connections they keep, and our side can reuse them
        reused <- dial(cm, 1)
      yield (kept, counted, fresh, more, after, again, reused)
    }.map {
      case Left(e) => fail(e.message)
      case Right((kept, counted, fresh, more, after, again, reused)) =>
        assertEquals(
          (kept ++ more).distinct,
          Vector(ConnectionState.Inbound(DataFlow.Duplex))
        )
        assertEquals(counted, 0, "no connection is in outbound use")
        assertEquals(fresh, Right("reserved"))
        assertEquals(after.counters.inbound, 3 * limit, "the peers keep all of them")
        assertEquals(after.dials, 0)
        assertEquals(again, Right("reserved"), "only the dials in use count")
        assertEquals(reused, Right("reused"))
    }
  }

  test("a dial is refused only once the dials in outbound use reach the limit") {
    attempt {
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(2)))
        (first, _) <- dialled(cm, 1, DataFlow.Duplex)
        (_, releaseSecond) <- dialled(cm, 2, DataFlow.Unidirectional)
        // a reuse of the peer's own connection dials nothing, at the limit too
        _ <- accepted(cm, 3, DataFlow.Duplex)
        reused <- dial(cm, 3)
        atLimit <- cm.snapshot.map(_.dials)
        refused <- dial(cm, 4)
        _ <- first.releaseOutbound
        afterRelease <- dial(cm, 4)
        // the reservation's own release frees its place too
        _ <- dialled(cm, 5, DataFlow.Duplex)
        refusedAgain <- dial(cm, 6)
        _ <- releaseSecond
        afterClose <- dial(cm, 6)
      yield (reused, atLimit, refused, afterRelease, refusedAgain, afterClose)
    }.map {
      case Left(e) => fail(e.message)
      case Right((reused, atLimit, refused, afterRelease, refusedAgain, afterClose)) =>
        assertEquals(reused, Right("reused"))
        assertEquals(atLimit, 2, "the reuse takes no place")
        assertEquals(refused, Left(ConnectionError.OutboundLimit(peer(4).toString, 2)))
        assertEquals(afterRelease, Right("reserved"), "the released use gave its place back")
        assertEquals(refusedAgain, Left(ConnectionError.OutboundLimit(peer(6).toString, 2)))
        assertEquals(afterClose, Right("reserved"), "the released reservation gave its place back")
    }
  }

  // `releaseOutbound` of an `OutboundDup Ticking` connection at the inbound hard limit prunes one
  // connection, and when it spares this one the state stays outbound (`Core.hs` 1918-1925 writes
  // nothing on that branch), as in the reference. The outbound side let go of it all the same, so
  // it holds no place.
  test("a connection that a prune spares in an outbound state holds no place once let go") {
    val seeds = (0L until 16L).toVector
    seeds
      .traverse { seed =>
        attempt {
          for
            cm <- ConnectionManager.create[IO](
              ConnectionManager.Limits(accepted = hard(1), outbound = atMost(1), seed = seed)
            )
            _ <- accepted(cm, 1, DataFlow.Duplex)
            (ours, _) <- dialled(cm, 2, DataFlow.Duplex)
            before <- ours.state
            _ <- ours.releaseOutbound
            after <- ours.state
            dials <- cm.snapshot.map(_.dials)
            fresh <- dial(cm, 3)
          yield (before, after, dials, fresh)
        }
      }
      .map { runs =>
        val ok = runs.collect { case Right(r) => r }
        assertEquals(ok.size, seeds.size, runs.collect { case Left(e) => e }.toString)
        val spared = ok.filter(_._2 != ConnectionState.Terminated)
        assert(spared.nonEmpty, "no seed spared the releasing connection")
        ok.foreach { (before, after, dials, fresh) =>
          assertEquals(before, ConnectionState.OutboundDup(TimeoutExpired.Ticking))
          assertEquals(dials, 0, s"a connection left $after holds a place")
          assertEquals(fresh, Right("reserved"))
        }
        spared.foreach { (_, after, _, _) =>
          assertEquals(after, ConnectionState.OutboundDup(TimeoutExpired.Ticking))
        }
      }
  }

  test("a reservation whose use ends in an error gives its place back") {
    val boom = new RuntimeException("the dial failed")
    attempt {
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(1)))
        failed <- cm
          .acquire(peer(1), Provenance.Outbound, IO.pure(OutboundLimit.Dial.Counted))
          .use(_ => IO.raiseError[Unit](boom))
          .attempt
        typed <- attempt(
          cm.acquire(peer(2), Provenance.Outbound, IO.pure(OutboundLimit.Dial.Counted))
            .use(_ => ConnectionError.Pruned(peer(2).toString).raise[IO, Unit])
        )
        dials <- cm.snapshot.map(_.dials)
        fresh <- dial(cm, 3)
      yield (failed, typed, dials, fresh)
    }.map {
      case Left(e) => fail(e.message)
      case Right((failed, typed, dials, fresh)) =>
        assertEquals(failed, Left(boom))
        assertEquals(typed, Left(ConnectionError.Pruned(peer(2).toString)))
        assertEquals(dials, 0)
        assertEquals(fresh, Right("reserved"))
    }
  }

  test("a reservation cancelled while it is held gives its place back") {
    attempt {
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(1)))
        held <- Deferred[IO, Unit]
        holder <- cm
          .acquire(peer(1), Provenance.Outbound, IO.pure(OutboundLimit.Dial.Counted))
          .use(_ => held.complete(()) *> IO.never[Unit])
          .start
        _ <- held.get
        during <- (cm.snapshot.map(_.dials), dial(cm, 2)).tupled
        _ <- holder.cancel
        outcome <- holder.join
        after <- (cm.snapshot.map(_.dials), dial(cm, 3)).tupled
      yield (during, outcome.isCanceled, after)
    }.map {
      case Left(e) => fail(e.message)
      case Right((during, cancelled, after)) =>
        assertEquals(during, (1, Left(ConnectionError.OutboundLimit(peer(2).toString, 1))))
        assert(cancelled, "the holder was cancelled")
        assertEquals(after, (0, Right("reserved")))
    }
  }

  // The reservation is committed before its report is written. When the report raises, the
  // reference leaves the reservation in its table (`Core.hs` 1409-1423), and so does the manager;
  // the place of its dial is given back, since no one dials through it.
  test(
    "a reservation whose report fails keeps its entry, as the reference does, and holds no place"
  ) {
    val boom = new RuntimeException("log failed")
    Ref.of[IO, Int](0).flatMap { opened =>
      val sink: ConnectionEvents[IO] = {
        case ConnectionEvent.Opened(_, _) =>
          opened.getAndUpdate(_ + 1).flatMap(n => IO.raiseError[Unit](boom).whenA(n == 0))
        case _ => IO.unit
      }
      attempt {
        for
          cm <- ConnectionManager.create[IO](
            ConnectionManager.Limits(outbound = atMost(1)),
            Some(sink)
          )
          failed <- cm
            .acquire(peer(1), Provenance.Outbound, IO.pure(OutboundLimit.Dial.Counted))
            .use_
            .attempt
          snapshot <- cm.snapshot
          fresh <- dial(cm, 2)
          again <- dial(cm, 1)
        yield (failed, snapshot, fresh, again)
      }.map {
        case Left(e) => fail(e.message)
        case Right((failed, snapshot, fresh, again)) =>
          assertEquals(failed, Left(boom))
          assertEquals(
            snapshot.connections,
            Vector(peer(1) -> ConnectionState.ReservedOutbound),
            "the reservation stays, as in the reference"
          )
          assertEquals(snapshot.dials, 0, "no place is held for it")
          assertEquals(fresh, Right("reserved"))
          assertEquals(
            again,
            Left(
              ConnectionError.AlreadyConnected(peer(1).toString, ConnectionState.ReservedOutbound)
            )
          )
      }
    }
  }

  property("a limit is a positive number of connections") {
    forAll(Gen.choose(-1000, 1000)) { n =>
      OutboundLimit.Connections.from(n) match
        case Right(c) => assert(n > 0 && c.value == n, s"$n accepted as ${c.value}")
        case Left(e) =>
          assertEquals(e, OutboundLimit.NotPositive(n))
          assert(n <= 0, s"$n refused")
    }
  }

  test("the governor needs its largest established targets of ordinary and of big ledger peers") {
    val praos = OutboundLimit.Needed.of(Targets.relay, Targets.sync, ConsensusMode.PraosMode)
    assertEquals(praos, OutboundLimit.Needed(30, 10))
    assertEquals(praos.total, 40)
    // Genesis switches between the deadline and the sync targets: the larger of each kind
    val genesis = OutboundLimit.Needed.of(Targets.relay, Targets.sync, ConsensusMode.GenesisMode)
    assertEquals(genesis, OutboundLimit.Needed(30, 40))
    assertEquals(genesis.total, 70)
  }

  test("a limit fits from the governor's needs up, and is refused below them") {
    val needed = OutboundLimit.Needed(30, 10)
    assertEquals(OutboundLimit.Unlimited.fits(needed), Right(OutboundLimit.Unlimited))
    assertEquals(atMost(40).fits(needed), Right(atMost(40)))
    assertEquals(atMost(500).fits(needed), Right(atMost(500)))
    assertEquals(
      atMost(39).fits(needed),
      Left(OutboundLimit.BelowTargets(OutboundLimit.Connections.from(39).toOption.get, needed))
    )
  }

  test("a local root's dial is never refused by the limit and holds no place") {
    attempt {
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(1)))
        _ <- dialled(cm, 1, DataFlow.Duplex)
        roots <- (2 to 6).toVector.traverse(k =>
          dialled(cm, k, DataFlow.Duplex, OutboundLimit.Dial.LocalRoot)
        )
        held <- cm.snapshot.map(_.dials)
        another <- dial(cm, 7, OutboundLimit.Dial.LocalRoot)
        counted <- dial(cm, 8)
        // a local root's release gives back nothing it did not take
        _ <- roots.traverse_((c, release) => c.releaseOutbound *> release)
        after <- cm.snapshot.map(_.dials)
        stillRefused <- dial(cm, 9)
      yield (held, another, counted, after, stillRefused)
    }.map {
      case Left(e) => fail(e.message)
      case Right((held, another, counted, after, stillRefused)) =>
        assertEquals(held, 1, "only the counted dial holds a place")
        assertEquals(another, Right("reserved"))
        assertEquals(counted, Left(ConnectionError.OutboundLimit(peer(8).toString, 1)))
        assertEquals(after, 1)
        assertEquals(stillRefused, Left(ConnectionError.OutboundLimit(peer(9).toString, 1)))
    }
  }

  test("a connection left out of the limit gives back the place it holds, once, and no other") {
    attempt {
      for
        cm <- ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(2)))
        (a, releaseA) <- dialled(cm, 1, DataFlow.Duplex)
        (b, _) <- dialled(cm, 2, DataFlow.Duplex)
        (root, _) <- dialled(cm, 3, DataFlow.Duplex, OutboundLimit.Dial.LocalRoot)
        full <- dial(cm, 4)
        _ <- a.exempt
        once <- cm.snapshot.map(_.dials)
        // again, and through handles that hold no place: nothing more is given back
        _ <- a.exempt *> root.exempt
        again <- cm.snapshot.map(_.dials)
        _ <- a.releaseOutbound *> releaseA
        released <- cm.snapshot.map(_.dials)
        _ <- dialled(cm, 5, DataFlow.Duplex)
        refused <- dial(cm, 6)
        _ <- b.exempt
        left <- cm.snapshot.map(_.dials)
      yield (full, once, again, released, refused, left)
    }.map {
      case Left(e) => fail(e.message)
      case Right((full, once, again, released, refused, left)) =>
        assertEquals(full, Left(ConnectionError.OutboundLimit(peer(4).toString, 2)))
        assertEquals(once, 1, "the exempted connection's place is given back")
        assertEquals(again, 1, "once only, and only its own")
        assertEquals(released, 1, "its release gives back nothing more")
        assertEquals(refused, Left(ConnectionError.OutboundLimit(peer(6).toString, 2)))
        assertEquals(left, 1)
    }
  }

  test("replacing 100 local roots under a limit at the governor's targets refuses nothing") {
    val needed = OutboundLimit.Needed.of(Targets.relay, Targets.sync, ConsensusMode.PraosMode)
    attempt {
      for
        cm <- ConnectionManager.create[IO](
          ConnectionManager.Limits(outbound = atMost(needed.total))
        )
        // the ordinary and big ledger peers at their targets, every place taken
        _ <- (1 to needed.total).toVector.traverse(k => dialled(cm, k, DataFlow.Duplex))
        old <- (101 to 200).toVector.traverse(k =>
          dialled(cm, k, DataFlow.Duplex, OutboundLimit.Dial.LocalRoot)
        )
        // a reload or a new DNS answer replaces them: the new ones are dialled while the old are
        // still held
        fresh <- (201 to 300).toVector.traverse(k =>
          attempt(dialled(cm, k, DataFlow.Duplex, OutboundLimit.Dial.LocalRoot))
        )
        during <- cm.snapshot.map(_.dials)
        counted <- dial(cm, 301)
        _ <- old.traverse_((c, release) => c.releaseOutbound *> release)
        after <- cm.snapshot.map(_.dials)
      yield (fresh, during, counted, after)
    }.map {
      case Left(e) => fail(e.message)
      case Right((fresh, during, counted, after)) =>
        assertEquals(fresh.collect { case Left(e) => e }, Vector.empty, "no local root refused")
        assertEquals(during, needed.total, "the local roots hold no place")
        assertEquals(
          counted,
          Left(ConnectionError.OutboundLimit(peer(301).toString, needed.total)),
          "a counted dial is still refused at the limit"
        )
        assertEquals(after, needed.total)
    }
  }
