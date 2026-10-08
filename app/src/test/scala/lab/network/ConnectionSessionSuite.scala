// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{IO, Ref, Deferred}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*
import ChainSync.*

class ConnectionSessionSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
  private val accept = bytes("000000008000000883010e8402f500f4")
  private val proposal = bytes("00000000000000098200a10e8402f500f4")
  private val request = bytes("00000000000200028100")
  private val rollback = bytes("0000000080020006830380828000")
  private val awaitReply = bytes("00000000800200028101")
  private val offers = Vector(14 -> Handshake.Data(2))
  private val policy =
    SessionDeadlines[IO](10.seconds, 100.seconds, 10.seconds, IO.pure(Some(700.seconds)))
  private def run(body: IO[Unit]) = TestControl.executeEmbed(body).unsafeToFuture()
  private def script(chunks: Vector[Bytes], eof: Boolean = true): IO[ByteTransport[IO]] = for
    pending <- Ref.of[IO, Vector[Bytes]](chunks)
    closed <- Ref.of[IO, Boolean](false)
  yield new ByteTransport[IO]:
    def read: IO[Option[Bytes]] = pending.modify(xs => (xs.drop(1), xs.headOption)).flatMap {
      case None if !eof => IO.never
      case value        => IO.pure(value)
    }
    def write(b: Bytes): IO[Unit] = IO.unit
    def close: IO[Unit] = closed.set(true)
    def isClosed: IO[Boolean] = closed.get
  private def client(
      chunks: Vector[Bytes]
  )(body: ConnectionSession[IO, ChainSyncFixtures.OpaqueNtNHeaderFixture] => IO[Unit]): IO[Unit] =
    script(chunks).flatMap(t =>
      ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use(body)
    )

  test(
    "accept plus every partial application frame split preserves phase decoder; remainder waits for request"
  ) {
    run((1 until rollback.size).toVector.traverse_ { split =>
      for
        requested <- Deferred[IO, Unit]
        n <- Ref.of[IO, Int](0)
        t = new ByteTransport[IO]:
          def read = n.getAndUpdate(_ + 1).flatMap { i =>
            if i == 0 then IO.pure(Some(Bytes(accept.value ++ rollback.value.take(split))))
            else requested.get.as(Some(Bytes(rollback.value.drop(split))))
          }
          def write(b: Bytes) = if b == request then requested.complete(()).void else IO.unit
          def close = IO.unit
          def isClosed = IO.pure(false)
        _ <- ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use { s =>
          s.negotiate(offers) *> s.send(Message.RequestNext) *> s.receive
            .map(m => assertEquals(m, Message.RollBackward(Point.Origin, Tip.Origin)))
        }
      yield ()
    })
  }
  test("every split reassembles the literal handshake") {
    run((1 until accept.size).toVector.traverse_ { split =>
      client(Vector(Bytes(accept.value.take(split)), Bytes(accept.value.drop(split)))) { s =>
        s.negotiate(offers).void
      }
    })
  }
  test("responder accepts before exposing coalesced RequestNext") {
    run(script(Vector(Bytes(proposal.value ++ request.value))).flatMap { t =>
      ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Server, policy).use { s =>
        s.negotiate(offers) *> s.receive.map(m => assertEquals(m, Message.RequestNext))
      }
    })
  }
  test("protocol zero CBOR suffix cannot become application bytes") {
    run(client(Vector(bytes("000000008000000a83010e8402f500f48100"))) { s =>
      s.negotiate(offers).attempt.map(r => assert(r.isLeft))
    })
  }
  test("unsolicited server response is rejected in Idle") {
    run(client(Vector(Bytes(accept.value ++ rollback.value))) { s =>
      s.negotiate(offers) *> s.receive.attempt.map(r => assert(r.isLeft))
    })
  }
  test("wrong protocol, direction, empty SDU and malformed CBOR terminate") {
    run(
      Vector(
        "00000000800500028101",
        "00000000000200028101",
        "0000000080020000",
        "0000000080020001ff"
      ).traverse_ { hex =>
        client(Vector(accept, bytes(hex))) { s =>
          for
            _ <- s.negotiate(offers)
            _ <- s.send(Message.RequestNext)
            result <- s.receive.attempt
            status <- s.status
          yield { assert(result.isLeft); assert(status.closed.nonEmpty) }
        }
      }
    )
  }
  test("EOF in mux header, payload and message poisons") {
    run(Vector("0000", "00000000800200068303", "000000008002000183").traverse_ { hex =>
      client(Vector(accept, bytes(hex))) { s =>
        s.negotiate(offers) *> s.send(Message.RequestNext) *> s.receive.attempt.map(r =>
          assert(r.isLeft)
        )
      }
    })
  }
  test("AwaitReply leaves server agency and rejects another client request") {
    run(client(Vector(accept, awaitReply)) { s =>
      for
        _ <- s.negotiate(offers)
        _ <- s.send(Message.RequestNext)
        got <- s.receive
        _ = assertEquals(got, Message.AwaitReply)
        result <- s.send(Message.RequestNext).attempt
      yield assert(result.isLeft)
    })
  }
  test("coalesced AwaitReply and rollback are decoded against successive states") {
    run(client(Vector(accept, Bytes(awaitReply.value ++ rollback.value))) { s =>
      for
        _ <- s.negotiate(offers)
        _ <- s.send(Message.RequestNext)
        first <- s.receive
        second <- s.receive
      yield {
        assertEquals(first, Message.AwaitReply);
        assertEquals(second, Message.RollBackward(Point.Origin, Tip.Origin))
      }
    })
  }
  test("unoffered intersection rejected independently of fixture model") {
    run(client(Vector(accept, bytes("0000000080020006830580828000"))) { s =>
      s.negotiate(offers) *> s.send(Message.FindIntersect(Vector.empty)) *> s.receive.attempt.map(
        r => assert(r.isLeft)
      )
    })
  }
  test("query and refusal results close without active view") {
    run(Vector("00000000800000098203a10e8402f500f4", "000000008000000682028200810e").traverse_ {
      hex =>
        client(Vector(Bytes(bytes(hex).value ++ rollback.value))) { s =>
          for
            _ <- s.negotiate(offers)
            status <- s.status
            result <- s.send(Message.RequestNext).attempt
          yield { assert(!status.active); assert(status.closed.nonEmpty); assert(result.isLeft) }
        }
    })
  }
  test("recognized but unsupported negotiated application version fails") {
    run(client(Vector(bytes("000000008000000883010f8402f500f4"))) { s =>
      s.negotiate(Vector(15 -> Handshake.Data(2))).attempt.map(r => assert(r.isLeft))
    })
  }
  test("state deadline expires during application inactivity") {
    run(client(Vector(accept)) { s =>
      for
        _ <- s.negotiate(offers)
        _ <- s.send(Message.RequestNext)
        _ <- IO.sleep(10.seconds)
        result <- s.receive.attempt
        status <- s.status
      yield { assert(result.isLeft); assert(status.closed.nonEmpty) }
    })
  }
  test("AwaitReply replaces deadline and stale CanAwait timer cannot close MustReply") {
    run(script(Vector(accept, awaitReply), false).flatMap { t =>
      ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use { s =>
        for
          _ <- s.negotiate(offers)
          _ <- s.send(Message.RequestNext)
          _ <- IO.sleep(9.seconds)
          _ <- s.receive
          _ <- IO.sleep(2.seconds)
          status <- s.status
          _ = assertEquals(status.state, State.NextMustReply)
          _ = assertEquals(status.closed, None)
          _ <- IO.sleep(698.seconds)
          result <- s.receive.attempt
        yield assert(result.isLeft)
      }
    })
  }
  test("admitted cancellation closes even transport whose read ignores close") {
    run(for
      entered <- Deferred[IO, Unit]
      base <- script(Vector(accept), false)
      n <- Ref.of[IO, Int](0)
      t = new ByteTransport[IO]:
        def read = n
          .getAndUpdate(_ + 1)
          .flatMap(i => if i == 0 then base.read else entered.complete(()) *> IO.never)
        def write(b: Bytes) = base.write(b)
        def close = base.close
        def isClosed = base.isClosed
      _ <- ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use { s =>
        for
          _ <- s.negotiate(offers)
          _ <- s.send(Message.RequestNext)
          fiber <- s.receive.start
          _ <- entered.get
          _ <- fiber.cancel
          status <- s.status
          closed <- t.isClosed
        yield {
          assert(closed); assert(status.closed.nonEmpty); assertEquals(status.events.size, 2)
        }
      }
    yield ())
  }
  test("canceling queued permit leaves admitted operation alive") {
    run(for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      n <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Boolean](false)
      t = new ByteTransport[IO]:
        def read = n
          .getAndUpdate(_ + 1)
          .flatMap(i =>
            if i == 0 then IO.pure(Some(accept))
            else entered.complete(()) *> release.get.as(Some(rollback))
          )
        def write(b: Bytes) = IO.unit
        def close = closed.set(true)
        def isClosed = closed.get
      _ <- ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use { s =>
        for
          _ <- s.negotiate(offers)
          _ <- s.send(Message.RequestNext)
          active <- s.receive.start
          _ <- entered.get
          queued <- s.receive.start
          _ <- IO.cede
          _ <- queued.cancel
          before <- s.status
          _ = assertEquals(before.closed, None)
          _ <- release.complete(())
          got <- active.joinWithNever
          after <- s.status
        yield {
          assertEquals(got, Message.RollBackward(Point.Origin, Tip.Origin));
          assertEquals(after.closed, None)
        }
      }
    yield ())
  }

  test("large NtC send segments beyond 5760 and bounded loopback consumes Done before release") {
    val raw = Bytes.fromArray(
      java.nio.file.Files
        .readAllBytes(java.nio.file.Path.of("fixtures/chain-sync/ntc-block-conway.cbor"))
    )
    val payload = ChainSyncFixtures.ntcBlock.decode(raw).toOption.get
    assert(raw.size > 5760)
    run(Loopback.pair[IO](Loopback.Limits(1, 31)).use { case (a, b) =>
      val cfg = ConnectionSession.Config(maxSduPayload = 19, maxChunkBytes = 31)
      (
        ConnectionSession.resource(a, ConnectionSession.NtC16, Role.Client, policy, cfg),
        ConnectionSession.resource(b, ConnectionSession.NtC16, Role.Server, policy, cfg)
      ).tupled.use { case (client, server) =>
        val ntcOffers = Vector(16 -> Handshake.Data(2))
        for
          _ <- (client.negotiate(ntcOffers), server.negotiate(ntcOffers)).parTupled
          _ <- (client.send(Message.RequestNext), server.receive).parTupled
          pair <- (server.send(Message.RollForward(payload, Tip.Origin)), client.receive).parTupled
          _ = pair._2 match
            case Message.RollForward(p, _) => assertEquals(p.bytes, raw)
            case _                         => fail("expected opaque full block")
          done <- (client.done, server.receive).parTupled
          _ = assertEquals(done._2, Message.Done)
          status <- client.status
        yield {
          assert(status.peakIngressBytes <= cfg.maxIngressBytes);
          assert(status.peakFrames <= cfg.maxFrames)
        }
      }
    })
  }
  test("trusted indefinite MustReply stays open until close interrupts read") {
    run(for
      base <- script(Vector(accept, awaitReply), false)
      _ <- ConnectionSession
        .resource(
          base,
          ConnectionSession.NtN14,
          Role.Client,
          policy.copy(mustReply = IO.pure(None))
        )
        .use { s =>
          for
            _ <- s.negotiate(offers)
            _ <- s.send(Message.RequestNext)
            _ <- s.receive
            _ <- IO.sleep(10000.seconds)
            status <- s.status
            _ = assertEquals(status.closed, None)
            read <- s.receive.attempt.start
            _ <- IO.cede
            _ <- s.close
            result <- read.joinWithNever
          yield assert(result.isLeft)
        }
    yield ())
  }
  test("handshake trickle does not extend monotonic phase deadline") {
    run(for
      pending <- Ref.of[IO, Vector[Byte]](accept.value)
      closed <- Ref.of[IO, Boolean](false)
      t = new ByteTransport[IO]:
        def read = IO.sleep(1.second) *> pending.modify(xs =>
          (xs.drop(1), xs.headOption.map(b => Bytes(Vector(b))))
        )
        def write(b: Bytes) = IO.unit
        def close = closed.set(true)
        def isClosed = closed.get
      _ <- ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use { s =>
        for
          result <- s.negotiate(offers).attempt
          now <- IO.monotonic
        yield { assert(result.isLeft); assertEquals(now, 10.seconds) }
      }
    yield ())
  }
  test("bounded ingress bytes and frame count fail before retaining excessive data") {
    run(
      Vector(
        ConnectionSession.Config(maxIngressBytes = 8),
        ConnectionSession.Config(maxFrames = 1)
      ).traverse_ { cfg =>
        script(Vector(Bytes(accept.value ++ rollback.value))).flatMap { t =>
          ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy, cfg).use {
            s =>
              for
                result <- s.negotiate(offers).attempt
                status <- s.status
              yield {
                assert(result.isLeft); assert(status.peakIngressBytes <= cfg.maxIngressBytes);
                assert(status.peakFrames <= cfg.maxFrames)
              }
          }
        }
      }
    )
  }
  test("per-message limit distinguishes coalesced valid messages from oversized message") {
    val cfg = ConnectionSession.Config(chainLimits = Limits(maxMessageBytes = 6))
    run(script(Vector(accept, Bytes(awaitReply.value ++ rollback.value))).flatMap { t =>
      ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy, cfg).use { s =>
        for
          _ <- s.negotiate(offers)
          _ <- s.send(Message.RequestNext)
          _ <- s.receive
          _ <- s.receive
        yield ()
      }
    } *> script(Vector(accept, bytes("000000008002000783025864000000"))).flatMap { t =>
      ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy, cfg).use { s =>
        s.negotiate(offers) *> s.send(Message.RequestNext) *> s.receive.attempt
          .map(r => assert(r.isLeft))
      }
    })
  }
  test("failed partial multi-frame write poisons without state commit") {
    run(for
      writes <- Ref.of[IO, Int](0)
      base <- script(Vector(proposal, request))
      t = new ByteTransport[IO]:
        def read = base.read
        def write(b: Bytes) = writes
          .getAndUpdate(_ + 1)
          .flatMap(n =>
            if n >= 2 then IO.raiseError(new RuntimeException("injected write failure"))
            else IO.unit
          )
        def close = base.close
        def isClosed = base.isClosed
      _ <- ConnectionSession
        .resource(
          t,
          ConnectionSession.NtN14,
          Role.Server,
          policy,
          ConnectionSession.Config(maxSduPayload = 8)
        )
        .use { s =>
          for
            _ <- s.negotiate(offers)
            _ <- s.receive
            payload = ChainSyncFixtures.ntnHeader
              .decode(bytes("8206d818483c4845414445523e"))
              .toOption
              .get
            result <- s.send(Message.RollForward(payload, Tip.Origin)).attempt
            status <- s.status
          yield {
            assert(result.isLeft); assertEquals(status.state, State.NextCanAwait);
            assertEquals(status.events.size, 2); assert(status.closed.nonEmpty)
          }
        }
    yield ())
  }
  test("close wins against late uncancelable write completion and prevents commit") {
    run(for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      writes <- Ref.of[IO, Int](0)
      base <- script(Vector(accept))
      t = new ByteTransport[IO]:
        def read = base.read
        def write(b: Bytes) = writes
          .getAndUpdate(_ + 1)
          .flatMap(n =>
            if n == 0 then IO.unit else IO.uncancelable(_ => entered.complete(()) *> release.get)
          )
        def close = base.close
        def isClosed = base.isClosed
      _ <- ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Client, policy).use { s =>
        for
          _ <- s.negotiate(offers)
          send <- s.send(Message.RequestNext).attempt.start
          _ <- entered.get
          _ <- s.close
          before <- s.status
          _ <- release.complete(())
          result <- send.joinWithNever
          after <- s.status
        yield {
          assert(result.isLeft); assertEquals(after, before); assertEquals(after.state, State.Idle)
        }
      }
    yield ())
  }
  test("close interrupts full queue write without requiring operation gate") {
    run(Loopback.pair[IO](Loopback.Limits(1, 1024)).use { case (a, _) =>
      for
        blocked <- Deferred[IO, Unit]
        writes <- Ref.of[IO, Int](0)
        t = new ByteTransport[IO]:
          def read = a.read
          def write(b: Bytes) = writes
            .getAndUpdate(_ + 1)
            .flatMap(n => (if n == 1 then blocked.complete(()).void else IO.unit) *> a.write(b))
          def close = a.close
          def isClosed = a.isClosed
        _ <- ConnectionSession
          .resource(
            t,
            ConnectionSession.NtN14,
            Role.Client,
            policy,
            ConnectionSession.Config(maxSduPayload = 1)
          )
          .use { s =>
            for
              fiber <- s.negotiate(offers).attempt.start
              _ <- blocked.get
              _ <- s.close
              result <- fiber.joinWithNever
              status <- s.status
              count <- writes.get
            yield {
              assert(result.isLeft); assertEquals(status.events, Vector.empty);
              assertEquals(count, 2)
            }
          }
      yield ()
    })
  }

  test(
    "responder terminal query/refusal writes are consumed before close and typed results remain stable"
  ) {
    run((0 until 30).toVector.traverse_ { index =>
      for
        writes <- Ref.of[IO, Vector[Bytes]](Vector.empty)
        closed <- Ref.of[IO, Boolean](false)
        query = index % 2 == 0
        input = if query then bytes("00000000000000098200a10e8402f500f5") else proposal
        t = new ByteTransport[IO]:
          def read = IO.pure(Some(input))
          def write(b: Bytes) = writes.update(_ :+ b)
          def close = closed.set(true)
          def isClosed = closed.get
        _ <- ConnectionSession.resource(t, ConnectionSession.NtN14, Role.Server, policy).use { s =>
          for
            result <- s.negotiate(if query then offers else Vector(14 -> Handshake.Data(3)))
            status <- s.status
            consumed <- writes.get
          yield {
            assert(
              if query then result.isInstanceOf[Handshake.Result.QueryResult]
              else result.isInstanceOf[Handshake.Result.Rejected]
            )
            assert(consumed.nonEmpty)
            assert(status.closed.nonEmpty)
            assert(!status.active)
          }
        }
      yield ()
    })
  }
  test("query true negotiated Accept never activates application") {
    run(client(Vector(bytes("000000008000000883010e8402f500f5"))) { s =>
      for
        result <- s.negotiate(offers)
        status <- s.status
      yield {
        assert(result.isInstanceOf[Handshake.Result.Negotiated]); assert(!status.active);
        assert(status.closed.nonEmpty)
      }
    })
  }
  test("MustReply sampler is interrupted by close and cannot commit later") {
    run(for
      sampling <- Deferred[IO, Unit]
      t <- script(Vector(accept, awaitReply))
      _ <- ConnectionSession
        .resource(
          t,
          ConnectionSession.NtN14,
          Role.Client,
          policy.copy(mustReply = sampling.complete(()) *> IO.never)
        )
        .use { s =>
          for
            _ <- s.negotiate(offers)
            _ <- s.send(Message.RequestNext)
            fiber <- s.receive.attempt.start
            _ <- sampling.get
            _ <- s.close
            result <- fiber.joinWithNever
            status <- s.status
          yield { assert(result.isLeft); assertEquals(status.state, State.NextCanAwait) }
        }
    yield ())
  }
  test("source default samples 601 through 911 seconds and trust is configured locally") {
    run(for
      untrusted <- SessionDeadlines.default[IO]()
      trusted <- SessionDeadlines.default[IO](true)
      samples <- Vector.fill(100)(untrusted.mustReply).sequence
      infinite <- trusted.mustReply
    yield {
      assertEquals(untrusted.canAwait, 10.seconds)
      assert(samples.forall(_.exists(d => d >= 601.seconds && d <= 911.seconds)))
      assertEquals(infinite, None)
    })
  }

  test("cancel admitted partial send after first SDU poisons and records no successful event") {
    run(for
      blocked <- Deferred[IO, Unit]
      writes <- Ref.of[IO, Int](0)
      base <- script(Vector(proposal, request))
      t = new ByteTransport[IO]:
        def read = base.read
        def write(b: Bytes) = writes
          .getAndUpdate(_ + 1)
          .flatMap(n => if n < 2 then IO.unit else blocked.complete(()) *> IO.never)
        def close = base.close
        def isClosed = base.isClosed
      _ <- ConnectionSession
        .resource(
          t,
          ConnectionSession.NtN14,
          Role.Server,
          policy,
          ConnectionSession.Config(maxSduPayload = 8)
        )
        .use { s =>
          for
            _ <- s.negotiate(offers)
            _ <- s.receive
            payload = ChainSyncFixtures.ntnHeader
              .decode(bytes("8206d818483c4845414445523e"))
              .toOption
              .get
            fiber <- s.send(Message.RollForward(payload, Tip.Origin)).start
            _ <- blocked.get
            _ <- fiber.cancel
            before <- s.status
            _ <- IO.sleep(1000.seconds)
            after <- s.status
            count <- writes.get
          yield {
            assertEquals(count, 3); assertEquals(before, after); assertEquals(after.events.size, 2);
            assertEquals(after.state, State.NextCanAwait); assert(after.closed.nonEmpty)
          }
        }
    yield ())
  }
  test(
    "event trace evicts within cap and escaped view stays closed after resource and timer release"
  ) {
    run(for
      t <- script(Vector(accept, rollback, rollback, rollback))
      session <- ConnectionSession
        .resource(
          t,
          ConnectionSession.NtN14,
          Role.Client,
          policy,
          ConnectionSession.Config(maxEvents = 2)
        )
        .use { s =>
          s.negotiate(offers) *> Vector
            .fill(3)(s.send(Message.RequestNext) *> s.receive)
            .sequence
            .as(s)
        }
      before <- session.status
      _ <- IO.sleep(10000.seconds)
      result <- session.send(Message.RequestNext).attempt
      after <- session.status
    yield {
      assertEquals(before.events, Vector("send:RequestNext", "receive:RollBackward"))
      assert(before.closed.nonEmpty)
      assertEquals(before, after)
      assert(result.isLeft)
    })
  }

  test("paired Loopback query and refusal return both typed outcomes before physical release") {
    run(Vector(true, false).traverse_ { query =>
      Loopback.pair[IO](Loopback.Limits(1, 64)).use { case (a, b) =>
        (
          ConnectionSession.resource(a, ConnectionSession.NtN14, Role.Client, policy),
          ConnectionSession.resource(b, ConnectionSession.NtN14, Role.Server, policy)
        ).tupled
          .use { case (client, server) =>
            for
              results <- (
                client.negotiate(Vector(14 -> Handshake.Data(2, query = query))),
                server.negotiate(Vector(14 -> Handshake.Data(if query then 2 else 3)))
              ).parTupled
              cs <- client.status
              ss <- server.status
              physicallyClosed <- a.isClosed
              clientReuse <- client.send(Message.RequestNext).attempt
              serverReuse <- server.receive.attempt
            yield {
              assert(
                if query then
                  results._1.isInstanceOf[Handshake.Result.QueryResult] && results._2
                    .isInstanceOf[Handshake.Result.QueryResult]
                else
                  results._1.isInstanceOf[Handshake.Result.Rejected] && results._2
                    .isInstanceOf[Handshake.Result.Rejected]
              )
              assert(cs.closed.nonEmpty && ss.closed.nonEmpty && !cs.active && !ss.active)
              assert(!physicallyClosed)
              assert(clientReuse.isLeft && serverReuse.isLeft)
            }
          }
          .flatMap(_ => a.isClosed.map(value => assert(value)))
      }
    })
  }
