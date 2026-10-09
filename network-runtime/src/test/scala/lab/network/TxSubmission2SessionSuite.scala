// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

class TxSubmission2SessionSuite extends munit.FunSuite:
  import ScriptedByteTransport.Step.*
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private val id = hex("d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6c")
  private val tx = hex("84a0a0f5f6")
  private val offer = RelayOffer(id, 10)
  private val proposal = hex("00000000000000098200a10e8402f500f4")
  private val accept = hex("000000008000000883010e8402f500f4")
  // Mux header encoded independently; payloads are literal reference-source-derived vectors.
  private def frame(payload: String, remote: Boolean = true): Bytes =
    val n = payload.length / 2
    hex("00000000" + (if remote then "8004" else "0004") + f"$n%04x" + payload)
  private val init = frame("8106", false)
  private val ids = frame(
    "82019f8282065820d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6c0aff",
    false
  )
  private val bodies = frame("82039f8206d8184584a0a0f5f6ff", false)
  private val request = frame(
    "82029f82065820d36a2619a672494604e11bb447cbcf5231e9f2ba25c2169177edc941bd50ad6cff"
  )
  private val requestIds = frame("8400f5000a")
  private val finalAck = frame("8400f5010a")
  private val done = frame("8104", false)
  private val prefix = Vector(Expect(proposal), Receive(accept), Expect(init))
  private val success = prefix ++ Vector(
    Receive(requestIds),
    Expect(ids),
    Receive(request),
    Expect(bodies),
    Receive(finalAck),
    Expect(done)
  )
  private def virtual[A](action: IO[A]) = TestControl.executeEmbed(action).unsafeToFuture()
  private def source(
      released: Ref[IO, Int],
      fetches: Ref[IO, Int],
      value: Bytes = tx,
      offered: Vector[RelayOffer] = Vector(offer)
  ): RelaySource[IO] = new RelaySource[IO]:
    def acquireBatch(limits: RelayLimits): Resource[IO, RelayLease[IO]] =
      Resource.make(IO.pure(new RelayLease[IO]:
        def offers = offered
        def original(id: Bytes) = fetches.update(_ + 1).as(Some(value))))(_ =>
        released.update(_ + 1)
      )
  private def scripted(
      script: Vector[ScriptedByteTransport.Step],
      cfg: TxSubmission2Session.Config = TxSubmission2Session.Config(),
      value: Bytes = tx,
      advertised: Vector[RelayOffer] = Vector(offer)
  ): IO[(Either[Throwable, TxSubmission2Session.Report], Int, Int, Boolean)] =
    for
      releases <- Ref.of[IO, Int](0)
      fetches <- Ref.of[IO, Int](0)
      result <- ScriptedByteTransport.resource[IO](script).use { transport =>
        TxSubmission2Session
          .resource[IO](transport, cfg)
          .use(_.run(Handshake.Data(2), source(releases, fetches, value, advertised)).attempt)
          .flatMap(r => transport.isClosed.map(closed => (r, closed)))
      }
      released <- releases.get
      fetched <- fetches.get
    yield (result._1, released, fetched, result._2)

  test("real handshake framing, one offer/body, FIFO acknowledgement and legal Done") {
    virtual(scripted(success).map { case (result, released, fetched, closed) =>
      val report = result.fold(throw _, identity)
      assertEquals(report.requests, 3)
      assertEquals(
        report.events,
        Vector(
          TxSubmission2Session.Event.Announced(Vector(id)),
          TxSubmission2Session.Event.BodiesWritten(Vector(id)),
          TxSubmission2Session.Event.Acknowledged(Vector(id)),
          TxSubmission2Session.Event.Finished
        )
      )
      assertEquals(released, 1); assertEquals(fetched, 1); assert(closed)
    })
  }
  test("pipelined body and acknowledgement requests preserve FIFO agency") {
    val together = Bytes(request.value ++ finalAck.value)
    virtual(
      scripted(
        prefix ++ Vector(
          Receive(requestIds),
          Expect(ids),
          Receive(together),
          Expect(bodies),
          Expect(done)
        )
      ).map(r => assert(r._1.isRight, r._1.toString))
    )
  }
  test("each inbound byte can arrive separately without changing output bytes") {
    val fragmented = success.flatMap {
      case Receive(bytes) => bytes.value.map(b => Receive(Bytes(Vector(b))))
      case step           => Vector(step)
    }
    virtual(scripted(fragmented).map(r => assert(r._1.isRight, r._1.toString)))
  }
  test(
    "malformed accounting requests fail closed, release once, and do not fetch on peer requests"
  ) {
    val failures = Vector(
      prefix ++ Vector(Receive(request)),
      prefix ++ Vector(Receive(frame("8400f50101"))),
      prefix ++ Vector(Receive(frame("8400f40001"))),
      prefix ++ Vector(Receive(requestIds), Expect(ids), Receive(frame("8400f50001"))),
      prefix ++ Vector(
        Receive(requestIds),
        Expect(ids),
        Receive(request),
        Expect(bodies),
        Receive(request)
      ),
      prefix ++ Vector(
        Receive(requestIds),
        Expect(ids),
        Receive(frame("82029f82065820" + "00" * 32 + "ff"))
      )
    )
    virtual(
      failures.traverse_(script =>
        scripted(script).map { r =>
          assert(r._1.isLeft); assertEquals(r._2, 1); assertEquals(r._3, 1); assert(r._4)
        }
      )
    )
  }
  test("EOF, wrong mux direction and wrong protocol cannot report completion") {
    virtual(
      Vector(
        prefix,
        prefix :+ Receive(frame("8400f50001", false)),
        prefix :+ Receive(hex("00000000800300028105"))
      ).traverse_(script =>
        scripted(script).map(r => { assert(r._1.isLeft); assertEquals(r._2, 1) })
      )
    )
  }
  test("empty lease terminates only in a blocking inventory request") {
    virtual(
      scripted(prefix ++ Vector(Receive(requestIds), Expect(done)), advertised = Vector.empty).map {
        r =>
          assert(r._1.isRight, r._1.toString); assertEquals(r._3, 0)
      }
    )
  }
  test("lease identity and advertised size fail before any handshake write") {
    virtual(
      Vector(
        Vector(offer.copy(advertisedSize = 5)),
        Vector(offer.copy(transactionId = hex("00" * 32))),
        Vector.fill(9)(offer)
      ).traverse_(offers =>
        scripted(Vector.empty, advertised = offers).map(r => {
          assert(r._1.isLeft); assertEquals(r._2, 1); assert(r._4)
        })
      )
    )
  }
  test("request and physical outgoing byte/frame caps include handshake") {
    virtual(
      Vector(
        TxSubmission2Session.Config(maxRequests = 1),
        TxSubmission2Session.Config(maxOutgoingBytes = 16),
        TxSubmission2Session.Config(maxOutgoingFrames = 1)
      ).traverse_(cfg =>
        scripted(success, cfg).map(r => { assert(r._1.isLeft); assertEquals(r._2, 1) })
      )
    )
  }
  test("immutable snapshot survives source replacement; every ID fetched once") {
    virtual(for
      releases <- Ref.of[IO, Int](0)
      value <- Ref.of[IO, Bytes](tx)
      fetches <- Ref.of[IO, Int](0)
      src = new RelaySource[IO]:
        def acquireBatch(limits: RelayLimits) = Resource.make(IO.pure(new RelayLease[IO]:
          def offers = Vector(offer)
          def original(id: Bytes) = fetches.update(_ + 1) *> value.get.map(Some(_))))(_ =>
          releases.update(_ + 1)
        )
      report <- ScriptedByteTransport.resource[IO](success).use { transport =>
        TxSubmission2Session
          .resource[IO](transport)
          .use(_.run(Handshake.Data(2), src, _ => value.set(hex("84a0a10080f5f6"))))
      }
      fetched <- fetches.get
      released <- releases.get
      _ = assertEquals(fetched, 1)
      _ = assertEquals(released, 1)
      _ = assertEquals(report.requests, 3)
    yield ())
  }
  private def hanging(
      entered: Deferred[IO, Unit],
      closes: Ref[IO, Int],
      hangWrite: Boolean
  ): ByteTransport[IO] = new ByteTransport[IO]:
    def read = entered.complete(()).void *> IO.never
    def write(bytes: Bytes) = if hangWrite then entered.complete(()).void *> IO.never else IO.unit
    def close = closes.update(_ + 1)
    def isClosed = closes.get.map(_ > 0)
  test("silent peer and stalled writer deadlines release lease and close exactly once") {
    virtual(Vector(false, true).traverse_ { hangWrite =>
      for
        entered <- Deferred[IO, Unit]
        closes <- Ref.of[IO, Int](0)
        releases <- Ref.of[IO, Int](0)
        fetches <- Ref.of[IO, Int](0)
        result <- TxSubmission2Session
          .resource[IO](hanging(entered, closes, hangWrite))
          .use(_.run(Handshake.Data(2), source(releases, fetches)))
          .attempt
        released <- releases.get
        closed <- closes.get
        _ = assert(result.isLeft); _ = assertEquals(released, 1); _ = assertEquals(closed, 1)
      yield ()
    })
  }
  test("cancellation interrupts blocked transport, releases lease and closes exactly once") {
    virtual(for
      entered <- Deferred[IO, Unit]
      closes <- Ref.of[IO, Int](0)
      releases <- Ref.of[IO, Int](0)
      fetches <- Ref.of[IO, Int](0)
      fiber <- TxSubmission2Session
        .resource[IO](hanging(entered, closes, false))
        .use(_.run(Handshake.Data(2), source(releases, fetches)))
        .start
      _ <- entered.get *> fiber.cancel
      released <- releases.get
      closed <- closes.get
      _ = assertEquals(released, 1); _ = assertEquals(closed, 1)
    yield ())
  }
  test("whole lifetime bounds source acquisition and unwinds already acquired pins") {
    virtual(for
      releases <- Ref.of[IO, Int](0)
      closes <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      src = new RelaySource[IO]:
        def acquireBatch(limits: RelayLimits): Resource[IO, RelayLease[IO]] =
          Resource.make(IO.unit)(_ => releases.update(_ + 1)) *> Resource.eval(
            IO.never[RelayLease[IO]]
          )
      result <- TxSubmission2Session
        .resource[IO](
          hanging(entered, closes, false),
          TxSubmission2Session.Config(lease = RelayLimits(maxLifetime = 2.seconds))
        )
        .use(_.run(Handshake.Data(2), src))
        .attempt
      released <- releases.get
      closed <- closes.get
      _ = assert(result.isLeft); _ = assertEquals(released, 1); _ = assertEquals(closed, 1)
    yield ())
  }

  test("65536-byte original is advertised with wrapper size and delivered across two SDUs") {
    val originalHex = "84a0a10059fff7" + "00" * 65527 + "f5f6"
    val original = hex(originalHex)
    assertEquals(original.size, 65536)
    val advertised = frame("82019f8282065820" + id.hex + "1a00010009ff", false)
    val replyHex = "82039f8206d8185a00010000" + originalHex + "ff"
    val parts = replyHex.grouped(65535 * 2).map(part => Expect(frame(part, false))).toVector
    val script = prefix ++ Vector(
      Receive(requestIds),
      Expect(advertised),
      Receive(request)
    ) ++ parts ++ Vector(Receive(finalAck), Expect(done))
    virtual(
      scripted(script, value = original, advertised = Vector(offer.copy(advertisedSize = 65545)))
        .map(r => assert(r._1.isRight, r._1.toString))
    )
  }
  test("buffered unsolicited terminal suffix prevents success") {
    val extra = Bytes(finalAck.value ++ Vector(0.toByte))
    val script = prefix ++ Vector(
      Receive(requestIds),
      Expect(ids),
      Receive(request),
      Expect(bodies),
      Receive(extra),
      Expect(done)
    )
    virtual(
      scripted(script).map(r =>
        assert(r._1.left.toOption.exists(_.getMessage.contains("unsolicited buffered")))
      )
    )
  }
  test("mismatched descriptor does not activate application traffic") {
    val wrongMagic = hex("000000008000000883010e8403f500f4")
    virtual(scripted(Vector(Expect(proposal), Receive(wrongMagic))).map(r => assert(r._1.isLeft)))
  }

  test(
    "query handshake rejected without writing and lease byte ceiling checked before negotiation"
  ) {
    virtual(for
      releases <- Ref.of[IO, Int](0)
      fetches <- Ref.of[IO, Int](0)
      query <- ScriptedByteTransport
        .resource[IO](Vector.empty)
        .use(t =>
          TxSubmission2Session
            .resource[IO](t)
            .use(_.run(Handshake.Data(2, query = true), source(releases, fetches)))
            .attempt
        )
      _ = assert(query.left.toOption.exists(_.getMessage.contains("query")))
      tooLarge <- scripted(
        Vector.empty,
        TxSubmission2Session.Config(lease = RelayLimits(maxOriginalBytes = 4))
      )
      _ = assert(tooLarge._1.left.toOption.exists(_.getMessage.contains("lease byte limit")))
    yield ())
  }
  test("physical close failure is surfaced by resource finalization and close is attempted once") {
    virtual(for
      closes <- Ref.of[IO, Int](0)
      releases <- Ref.of[IO, Int](0)
      fetches <- Ref.of[IO, Int](0)
      result <- ScriptedByteTransport.resource[IO](success).use { t =>
        val broken = new ByteTransport[IO]:
          def read = t.read
          def write(bytes: Bytes) = t.write(bytes)
          def isClosed = t.isClosed
          def close = closes.update(_ + 1) *> IO
            .raiseError(new IllegalStateException("physical close failed"))
        TxSubmission2Session
          .resource[IO](broken)
          .use(_.run(Handshake.Data(2), source(releases, fetches)))
          .attempt
      }
      closed <- closes.get
      released <- releases.get
      _ = assert(result.left.toOption.exists(_.getMessage.contains("physical close failed")))
      _ = assertEquals(closed, 1)
      _ = assertEquals(released, 1)
    yield ())
  }

  test("indefinite envelope is served byte-for-byte with its extra byte advertised") {
    val original = hex("9fa0a0f5f6ff")
    val advertised = frame("82019f8282065820" + id.hex + "0bff", false)
    val reply = frame("82039f8206d818469fa0a0f5f6ffff", false)
    val script = prefix ++ Vector(
      Receive(requestIds),
      Expect(advertised),
      Receive(request),
      Expect(reply),
      Receive(finalAck),
      Expect(done)
    )
    virtual(
      scripted(script, value = original, advertised = Vector(offer.copy(advertisedSize = 11))).map(
        r => assert(r._1.isRight, r._1.toString)
      )
    )
  }
