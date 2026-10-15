// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.protocol.ConnectionState
import xyz.kd5ujc.scarab.protocol.ConnectionState.{Counters, DataFlow, Provenance}
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

class ConnectionManagerSuite extends CatsEffectSuite with ScalaCheckSuite:
  private def peer(n: Int): SocketAddress[IpAddress] =
    SocketAddress(IpAddress.fromString(s"10.0.0.$n").get, Port.fromInt(3001).get)

  /** Run `program` with a manager; connection errors come back as values. */
  private def managed[A](
      limits: ConnectionManager.Limits = ConnectionManager.Limits()
  )(
      program: Handle[IO, ConnectionError] ?=> ConnectionManager[IO] => IO[A]
  ): IO[Either[ConnectionError, A]] =
    Handle
      .allow[ConnectionError] {
        ConnectionManager.create[IO](limits).flatMap(program(_)).map(_.asRight[ConnectionError])
      }
      .rescue(e => IO.pure(e.asLeft[A]))

  private def result[A](either: Either[ConnectionError, A]): A =
    either.fold(e => fail(e.message), identity)

  test("an inbound connection is admitted, negotiated and released; the table follows") {
    managed() { cm =>
      cm.inbound(peer(1))
        .use { c =>
          for
            before <- cm.snapshot
            s0 <- c.state
            _ <- c.negotiated(DataFlow.Duplex)
            s1 <- c.state
            during <- cm.snapshot
          yield (before.counters, s0, s1, during.counters)
        }
        .flatMap(r => cm.snapshot.map(after => (r, after.counters)))
    }.map { r =>
      val ((before, s0, s1, during), after) = result(r)
      assertEquals(before, Counters(1, 0, 0, 0))
      assertEquals(s0, ConnectionState.Unnegotiated(Provenance.Inbound))
      assertEquals(s1, ConnectionState.Established(Provenance.Inbound, DataFlow.Duplex))
      assertEquals(during, Counters(1, 0, 1, 1))
      assertEquals(after, Counters.zero)
    }
  }

  test("one connection per peer address, in either direction") {
    managed() { cm =>
      cm.inbound(peer(1)).use { _ =>
        for
          second <- Handle
            .allow[ConnectionError](
              cm.outbound(peer(1)).use(_ => IO.unit).as(None)
            )
            .rescue(e => IO.pure(Some(e)))
          third <- Handle
            .allow[ConnectionError](
              cm.inbound(peer(1)).use(_ => IO.unit).as(None)
            )
            .rescue(e => IO.pure(Some(e)))
          other <- cm.outbound(peer(2)).use(_ => IO.pure(true))
          snapshot <- cm.snapshot
        yield (second, third, other, snapshot.counters)
      }
    }.map { r =>
      val (second, third, other, counters) = result(r)
      assert(second.exists(_.isInstanceOf[ConnectionError.AlreadyConnected]), second.toString)
      assert(third.exists(_.isInstanceOf[ConnectionError.AlreadyConnected]), third.toString)
      assert(other)
      assertEquals(counters, Counters(1, 0, 0, 0))
    }
  }

  test("with our clients off the inbound connection, both directions to one peer coexist") {
    managed() { cm =>
      cm.inbound(peer(1)).use { _ =>
        cm.outbound(peer(1), alongsideInbound = true).use { _ =>
          for
            snapshot <- cm.snapshot
            // one per direction still: a second outbound is refused
            again <- Handle
              .allow[ConnectionError](
                cm.outbound(peer(1), alongsideInbound = true).use(_ => IO.unit).as(None)
              )
              .rescue(e => IO.pure(Some(e)))
          yield (snapshot.counters, again)
        }
      }
    }.map { r =>
      val (counters, again) = result(r)
      assertEquals(counters, Counters(1, 1, 0, 0))
      assert(again.exists(_.isInstanceOf[ConnectionError.AlreadyConnected]), again.toString)
    }
  }

  test("an outbound connection refuses an inbound one unless our clients do not use it") {
    managed() { cm =>
      cm.outbound(peer(1)).use { _ =>
        for
          refused <- Handle
            .allow[ConnectionError](cm.inbound(peer(1)).use(_ => IO.unit).as(None))
            .rescue(e => IO.pure(Some(e)))
          allowed <- cm.inbound(peer(1), alongsideOutbound = true).use(_ => IO.pure(true))
        yield (refused, allowed)
      }
    }.map { r =>
      val (refused, allowed) = result(r)
      assert(refused.exists(_.isInstanceOf[ConnectionError.AlreadyConnected]), refused.toString)
      assert(allowed)
    }
  }

  test("the limits of each direction are hard, and a released connection frees its slot") {
    val limits = ConnectionManager.Limits(maxInbound = 2, maxOutbound = 1)
    managed(limits) { cm =>
      def attempt(
          r: Handle[IO, ConnectionError] ?=> Resource[IO, Connection[IO]]
      ): IO[Option[ConnectionError]] =
        Handle
          .allow[ConnectionError](r.use(_ => IO.unit).as(Option.empty[ConnectionError]))
          .rescue(e => IO.pure(Some(e)))
      (cm.inbound(peer(1)), cm.inbound(peer(2)), cm.outbound(peer(3))).tupled
        .use { _ =>
          for
            inbound <- attempt(cm.inbound(peer(4)))
            outbound <- attempt(cm.outbound(peer(5)))
          yield (inbound, outbound)
        }
        .flatMap { refused =>
          // all three were released: the slots are free again
          (attempt(cm.inbound(peer(4))), attempt(cm.outbound(peer(5)))).tupled.map(refused -> _)
        }
    }.map { r =>
      val ((inbound, outbound), (again, againOut)) = result(r)
      assertEquals(inbound, Some(ConnectionError.InboundLimit(peer(4).toString, 2)))
      assertEquals(outbound, Some(ConnectionError.OutboundLimit(peer(5).toString, 1)))
      assertEquals((again, againOut), (None, None))
    }
  }

  test("a failing or cancelled owner still releases the connection") {
    managed() { cm =>
      for
        failed <- cm
          .inbound(peer(1))
          .use(_ => IO.raiseError[Unit](new RuntimeException("boom")))
          .attempt
        afterFailure <- cm.snapshot
        fiber <- cm.outbound(peer(2)).use(_ => IO.never).start
        _ <- IO.sleep(50.millis)
        during <- cm.snapshot
        _ <- fiber.cancel
        afterCancel <- cm.snapshot
      yield (failed.isLeft, afterFailure.counters, during.counters, afterCancel.counters)
    }.map { r =>
      val (failed, afterFailure, during, afterCancel) = result(r)
      assert(failed)
      assertEquals(afterFailure, Counters.zero)
      assertEquals(during, Counters(0, 1, 0, 0))
      assertEquals(afterCancel, Counters.zero)
    }
  }

  test("an event the state does not allow is a typed error and changes nothing") {
    managed() { cm =>
      cm.outbound(peer(1)).use { c =>
        for
          early <- Handle
            .allow[ConnectionError](c.negotiated(DataFlow.Duplex).as(None))
            .rescue(e => IO.pure(Some(e)))
          _ <- c.connected
          _ <- c.negotiated(DataFlow.Unidirectional)
          state <- c.state
          late <- Handle.allow[ConnectionError](c.connected.as(None)).rescue(e => IO.pure(Some(e)))
        yield (early, state, late)
      }
    }.map { r =>
      val (early, state, late) = result(r)
      assert(early.exists(_.isInstanceOf[ConnectionError.IllegalTransition]), early.toString)
      assertEquals(state, ConnectionState.Established(Provenance.Outbound, DataFlow.Unidirectional))
      assert(late.exists(_.isInstanceOf[ConnectionError.IllegalTransition]), late.toString)
    }
  }

  test("lookup shows a live connection and its closure can be awaited; events are reported") {
    val events = Ref.unsafe[IO, Vector[ConnectionEvent]](Vector.empty)
    val sink = new ConnectionEvents[IO]:
      def emit(event: ConnectionEvent) = events.update(_ :+ event)
    Handle
      .allow[ConnectionError] {
        ConnectionManager
          .create[IO](ConnectionManager.Limits(), Some(sink))
          .flatMap { cm =>
            for
              waiter <- IO.deferred[Boolean]
              owner <- cm.inbound(peer(7)).use { c =>
                for
                  live <- cm.lookup(peer(7))
                  _ <- live.get.closed.productR(waiter.complete(true)).start
                  _ <- c.negotiated(DataFlow.Duplex)
                  _ <- c.close("test over")
                yield live.map(_.state)
              }
              woke <- waiter.get.timeout(2.seconds)
              gone <- cm.lookup(peer(7))
              seen <- events.get
            yield (owner, woke, gone, seen)
          }
          .map(_.asRight[ConnectionError])
      }
      .rescue(e => IO.pure(e.asLeft))
      .map { r =>
        val (owner, woke, gone, seen) = result(r)
        assertEquals(owner, Some(ConnectionState.Unnegotiated(Provenance.Inbound)))
        assert(woke)
        assertEquals(gone, None)
        assertEquals(
          seen.collect { case ConnectionEvent.Closed(_, _, reason) => reason },
          Vector("test over")
        )
        assert(seen.head.isInstanceOf[ConnectionEvent.Opened])
      }
  }

  // ------------------------------------------------------------------ model

  private enum Op:
    case OpenInbound(peer: Int)
    case OpenOutbound(peer: Int)
    case Negotiate(peer: Int, duplex: Boolean)
    case Release(peer: Int)

  private val ops: Gen[List[Op]] =
    val p = Gen.choose(1, 5)
    Gen.listOf(
      Gen.oneOf(
        p.map(Op.OpenInbound(_)),
        p.map(Op.OpenOutbound(_)),
        Gen.zip(p, Gen.oneOf(true, false)).map(Op.Negotiate(_, _)),
        p.map(Op.Release(_))
      )
    )

  property(
    "the manager agrees with a model of the table, and its counters stay within the limits"
  ) {
    val limits = ConnectionManager.Limits(maxInbound = 3, maxOutbound = 2)
    forAll(ops) { script =>
      val outcome = Handle
        .allow[ConnectionError] {
          ConnectionManager.create[IO](limits).flatMap { cm =>
            // the connections we hold, with the release that ends each
            Ref.of[IO, Map[Int, (Connection[IO], IO[Unit])]](Map.empty).flatMap { held =>
              def open(n: Int, r: Handle[IO, ConnectionError] ?=> Resource[IO, Connection[IO]])
                  : IO[Unit] =
                Handle
                  .allow[ConnectionError] {
                    r.allocated.flatMap { case (c, release) =>
                      held.update(_.updated(n, (c, release)))
                    }
                  }
                  .rescue(_ => IO.unit)
              def step(op: Op): IO[Unit] = op match
                case Op.OpenInbound(n)  => open(n, cm.inbound(peer(n)))
                case Op.OpenOutbound(n) => open(n, cm.outbound(peer(n)))
                case Op.Negotiate(n, duplex) =>
                  held.get.flatMap(_.get(n).traverse_ { case (c, _) =>
                    Handle
                      .allow[ConnectionError] {
                        for
                          s <- c.state
                          _ <- (if s == ConnectionState.Reserved then c.connected else IO.unit)
                          _ <- c
                            .negotiated(if duplex then DataFlow.Duplex else DataFlow.Unidirectional)
                        yield ()
                      }
                      .rescue(_ => IO.unit)
                  })
                case Op.Release(n) =>
                  held.modify(m => (m - n, m.get(n))).flatMap(_.traverse_(_._2))
              for
                _ <- script.traverse_(op => step(op) *> check(cm))
                _ <- held.get.flatMap(_.values.toList.traverse_(_._2))
                last <- cm.snapshot
              yield last.counters == Counters.zero
            }
          }
        }
        .rescue(_ => IO.pure(false))
        .unsafeRunSync()
      outcome
    }
  }

  /** After every step: the counters are those of the table and within the limits. */
  private def check(cm: ConnectionManager[IO]): IO[Unit] =
    cm.snapshot.flatMap { s =>
      val c = Counters.of(s.connections.map(_._2))
      IO.raiseUnless(c == s.counters && c.inbound <= 3 && c.outbound <= 2)(
        new IllegalStateException(s"$c vs ${s.counters}")
      )
    }
