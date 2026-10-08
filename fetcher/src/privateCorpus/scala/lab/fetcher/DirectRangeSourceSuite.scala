// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.chain.CardanoBlockIndex
import lab.network.{
  BlockFetch,
  BlockFetchSession,
  ByteTransport,
  CardanoBlockFetch,
  ChainSync,
  Handshake
}
import scala.concurrent.duration.*

/** Literal peer grammar intentionally does not call any production wire encoder. */
class DirectRangeSourceSuite extends munit.FunSuite:
  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private val fixtures =
    if Files.exists(Path.of("fixtures/chain-fetch")) then Path.of("fixtures/chain-fetch")
    else Path.of("../fixtures/chain-fetch")
  private def hex(s: String) = right(Bytes.fromHex(s))
  private def join(xs: Bytes*) = Bytes(xs.toVector.flatMap(_.value))
  private def uint(n: BigInt, major: Int = 0): Bytes =
    val (tag, width) =
      if n < 24 then (n.toInt, 0)
      else if n <= 255 then (24, 1)
      else if n <= 65535 then (25, 2)
      else if n <= BigInt("ffffffff", 16) then (26, 4)
      else (27, 8)
    Bytes(
      Vector(((major << 5) | tag).toByte) ++ (0 until width).reverse.map(i => (n >> (8 * i)).toByte)
    )
  private def frame(protocol: Int, payload: Bytes, responder: Boolean = true): Bytes =
    val p = protocol | (if responder then 32768 else 0)
    Bytes(
      Vector(0, 0, 0, 0, p >> 8, p, payload.size >> 8, payload.size).map(_.toByte) ++ payload.value
    )
  private def frames(payload: Bytes, protocol: Int = 3, size: Int = 65535): Bytes =
    Bytes(payload.value.grouped(size).flatMap(b => frame(protocol, Bytes(b)).value).toVector)
  private val accepted = frame(0, hex("83010e84182af500f4"))
  private val proposal = frame(0, hex("8200a10e84182af500f4"), false)
  private val done = frame(3, hex("8101"), false)
  private def wrapped(raw: Bytes): Bytes = join(hex("8204d818"), uint(raw.size, 2), raw)
  private def raw(era: String, i: Int): Bytes =
    Bytes.fromArray(Files.readAllBytes(fixtures.resolve(s"$era/$era-$i.cbor")))
  private def raws(era: String) = (0 until 4).map(raw(era, _)).toVector
  private def point(bytes: Bytes): CardanoBlockFetch.SpecificPoint =
    val b = right(CardanoBlockIndex.inspect(bytes, 1048576))
    right(
      CardanoBlockFetch.SpecificPoint.from(
        ChainSync.Point.Block(right(ChainSync.UInt64.from(b.slot)), b.headerHash)
      )
    )
  private def pointWire(p: CardanoBlockFetch.SpecificPoint) =
    join(hex("82"), uint(p.slot.value), hex("5820"), p.hash)
  private def request(b: EndpointBatch.Spec) =
    frame(3, join(hex("8300"), pointWire(b.first), pointWire(b.last)), false)
  private def response(bs: Vector[Bytes], terminal: Boolean = true): Bytes =
    frames(
      join(
        hex("8102"),
        Bytes(bs.flatMap(b => wrapped(b).value)),
        if terminal then hex("8105") else Bytes.empty
      )
    )
  private def batch(era: String) =
    val bs = raws(era)
    right(
      EndpointBatch.Spec.checked(
        LocalConfig.load(fixtures.resolve(s"$era.tsv")).spec.after,
        CardanoBlockFetch.InclusiveRange(point(bs.head), point(bs.last))
      )
    )
  private def selection(b: EndpointBatch.Spec, input: Long = 8388608L): FetchSpec =
    right(
      FetchSpec.checked(
        b.anchor,
        None,
        Some(right(Point.parse(s"${b.last.slot.value}:${b.last.hash.hex}"))),
        right(Limits.checked(4, 1048576, input, 8388608L, 256, 120))
      )
    )
  private def descriptor(era: String, b: EndpointBatch.Spec): PinnedDirectRangeDescriptor =
    val originals = Files
      .readString(fixtures.resolve(s"$era/source.tsv"))
      .split("\n")
      .drop(2)
      .map { line =>
        val p = line.split("\t"); PinnedDirectRangeDescriptor.Original(p(1).toInt, p(2))
      }
      .toVector
    right(
      PinnedDirectRangeDescriptor.checked(
        b,
        Handshake.Data(42),
        Digests.sha256(Files.readAllBytes(fixtures.resolve(s"$era/source.tsv"))),
        Digests.sha256(Files.readAllBytes(fixtures.resolve("PROVENANCE.md"))),
        originals,
        Digests.sha256(
          join(proposal, accepted, request(b), response(raws(era)), done).value.toArray
        ),
        Vector(
          "ouroboros-network:c45735a56c567fa977969173d18943bac6bb3821",
          "ouroboros-consensus:82ecba329d7d054340bf707d44fe6e9ac27cec40"
        ),
        networkLabel =
          if era == "babbage" then "preprod-source-provenanced"
          else "mainnet-labelled-unauthenticated"
      )
    )
  private final case class Peer(
      transport: ByteTransport[IO],
      writes: Ref[IO, Vector[Bytes]],
      closes: Ref[IO, Int]
  )
  private def peer(
      chunks: Vector[Bytes],
      delay: FiniteDuration = Duration.Zero,
      failClose: Boolean = false,
      eof: IO[Option[Bytes]] = IO.pure(None)
  ): Resource[IO, Peer] =
    Resource
      .eval(
        (
          Ref.of[IO, Vector[Bytes]](chunks),
          Ref.of[IO, Vector[Bytes]](Vector.empty),
          Ref.of[IO, Int](0)
        ).tupled
      )
      .flatMap { (reads, writes, closes) =>
        val t = new ByteTransport[IO]:
          def read = IO.sleep(delay) *> reads
            .modify {
              case h +: tail => (tail, Some(h))
              case _         => (Vector.empty, None)
            }
            .flatMap(_.fold(eof)(b => IO.pure(Some(b))))
          def write(b: Bytes) = writes.update(_ :+ b)
          def close = closes.update(
            _ + 1
          ) *> (if failClose then IO.raiseError(new RuntimeException("cleanup boom")) else IO.unit)
          def isClosed = closes.get.map(_ > 0)
        Resource.make(IO.pure(Peer(t, writes, closes)))(_.transport.close.attempt.void)
      }
  private def chunks(b: Bytes, n: Int = 65543): Vector[Bytes] =
    b.value.grouped(n).map(Bytes(_)).toVector
  private def store(
      root: Path,
      s: FetchSpec,
      d: PinnedDirectRangeDescriptor,
      resume: Boolean = false
  ) =
    NioSegmentStore.resource[IO](root, s, d.identity, resume)
  private def temp = IO.blocking(Files.createTempDirectory("direct-range-test-"))
  private def error(e: Either[Throwable, ?], code: Int): Unit = e match
    case Left(f: FetchError) => assertEquals(f.code, code)
    case x                   => fail(s"expected error $code, got $x")

  for era <- Vector("shelley", "allegra", "babbage") do
    test(
      s"$era literal coalesced and fragmented batch stores exact bytes and replays full resume"
    ) {
      val b = batch(era); val s = selection(b); val d = descriptor(era, b)
      val wire = join(accepted, response(raws(era)))
      (for
        root <- temp
        opens <- Ref.of[IO, Int](0)
        connection = Resource.eval(opens.update(_ + 1)) *> peer(chunks(wire, 97)).map(_.transport)
        results <- FixtureDirectRangeSource.resource[IO](d, s, connection).use { source =>
          for
            first <- store(root, s, d).use(source.run)
            second <- store(root, s, d, true).use(source.run)
            count <- opens.get
            admitted <- source.inputBytes
          yield (first, second, count, admitted)
        }
      yield
        assertEquals(results._1.reason, "endReached")
        assertEquals(results._1.snapshot, results._2.snapshot)
        assertEquals(results._3, 2)
        assertEquals(results._4, raws(era).map(_.size.toLong).sum * 2)
        assert(results._1.json.contains("\"networkAuthenticated\":false"))
        results._1.snapshot.records.zipWithIndex.foreach { (r, i) =>
          assertEquals(
            Bytes.fromArray(Files.readAllBytes(root.resolve(s"objects/${r.rawHash}.cbor"))),
            raw(era, i)
          )
        }
      ).unsafeToFuture()
    }

  test("one literal proposal, inclusive first request and ClientDone; no ChainSync request") {
    val b = batch("shelley"); val s = selection(b); val d = descriptor("shelley", b)
    (for
      root <- temp
      _ <- peer(chunks(join(accepted, response(raws("shelley"))))).use { p =>
        FixtureDirectRangeSource.resource[IO](d, s, Resource.pure(p.transport)).use { source =>
          store(root, s, d).use(source.run) *> p.writes.get
            .map(w => assertEquals(w, Vector(proposal, request(b), done)))
        }
      }
    yield ()).unsafeToFuture()
  }

  test("all acquisition failures leave a fresh checkpoint empty, including EOF after last") {
    val bs = raws("shelley"); val b = batch("shelley"); val s = selection(b);
    val d = descriptor("shelley", b)
    val cases = Vector(
      frames(hex("8103")) -> 3,
      response(Vector.empty) -> 3,
      response(bs.take(3)) -> 3,
      response(bs, false) -> 4,
      response(bs :+ bs.last) -> 5,
      response(Vector(bs(1), bs(0), bs(2), bs(3))) -> 5,
      response(Vector(bs.head, bs.head, bs(2), bs(3))) -> 5,
      response(Vector(hex("8202"))) -> 5,
      frames(hex("8102")) -> 4,
      join(response(bs), frames(hex("8105"))) -> 4,
      join(response(bs), hex("000000")) -> 4,
      frames(hex("8102"), 2) -> 4,
      frame(3, hex("8102"), false) -> 4,
      frames(join(hex("8102"), hex("8204d8185a00100001"))) -> 4
    )
    cases
      .traverse_ { (wire, code) =>
        for
          root <- temp
          _ <- peer(chunks(join(accepted, wire))).use { p =>
            FixtureDirectRangeSource.resource[IO](d, s, Resource.pure(p.transport)).use { source =>
              store(root, s, d).use { st =>
                source.run(st).attempt.flatMap { result =>
                  val checked =
                    if code == 3 then IO(assertEquals(result.toOption.map(_.complete), Some(false)))
                    else IO(error(result, code))
                  checked *> st.snapshot.map(snap => assertEquals(snap.records.size, 0))
                }
              }
            }
          }
        yield ()
      }
      .unsafeToFuture()
  }

  for era <- Vector("shelley", "babbage") do
    test(s"$era failed reacquisition preserves prefix and next session replays all overlap") {
      val bs = raws(era); val b = batch(era); val s = selection(b);
      val d = descriptor(era, b)
      (for
        root <- temp
        prefix <- store(root, s, d).use(st =>
          st.append(right(CardanoBlockIndex.inspect(bs.head, 1048576)))
        )
        _ <- peer(chunks(join(accepted, response(bs, false)))).use { p =>
          FixtureDirectRangeSource.resource[IO](d, s, Resource.pure(p.transport)).use { source =>
            store(root, s, d, true).use(st =>
              source.run(st).attempt.flatMap(r => IO(error(r, 4))) *> st.snapshot
                .map(snap => assertEquals(snap, prefix))
            )
          }
        }
        result <- peer(chunks(join(accepted, response(bs)))).use { p =>
          FixtureDirectRangeSource
            .resource[IO](d, s, Resource.pure(p.transport))
            .use(source => store(root, s, d, true).use(source.run))
        }
      yield assertEquals(result.snapshot.records.size, 4)).unsafeToFuture()
    }

  test("descriptor tuple, count selection and store/source identity mismatches fail closed") {
    val b = batch("shelley"); val s = selection(b); val d = descriptor("shelley", b)
    val count = right(FetchSpec.checked(b.anchor, Some(4), s.end, s.limits))
    (for
      rejected <- FixtureDirectRangeSource
        .resource[IO](d, count, Resource.eval(IO.never))
        .use(_ => IO.unit)
        .attempt
      _ = error(rejected, 2)
      root <- temp
      mismatch <- FixtureDirectRangeSource
        .resource[IO](d, s, Resource.eval(IO.never))
        .use(source =>
          NioSegmentStore
            .resource[IO](
              root,
              s,
              right(SourceIdentity.checked("00" * 32, "wrong", b.anchor)),
              false
            )
            .use(source.run)
        )
        .attempt
    yield error(mismatch, 6)).unsafeToFuture()
  }

  test("repeated invocation raw byte budgets include full replay, failed open adds no checkpoint") {
    val bs = raws("shelley"); val b = batch("shelley");
    val s = selection(b, bs.map(_.size.toLong).sum); val d = descriptor("shelley", b)
    (for
      root <- temp
      _ <- FixtureDirectRangeSource
        .resource[IO](d, s, peer(chunks(join(accepted, response(bs)))).map(_.transport))
        .use { source =>
          for
            first <- store(root, s, d).use(source.run)
            second <- store(root, s, d, true).use(source.run)
          yield
            assert(first.complete)
            assertEquals(second.reason, "inputBudget")
            assertEquals(first.snapshot, second.snapshot)
        }
    yield ()).unsafeToFuture()
  }

  test("primary protocol error survives failing close; state and whole timers use virtual time") {
    val b = batch("shelley")
    val scenarios = Vector(
      (
        Vector(accepted),
        BlockFetchSession.Config(times = BlockFetch.TimeLimits(busy = 1.second)),
        "state deadline expired"
      ),
      (
        Vector(accepted),
        BlockFetchSession.Config(times = BlockFetch.TimeLimits(wholeRequest = 1.second)),
        "whole request deadline expired"
      )
    )
    TestControl
      .executeEmbed(scenarios.traverse_ { (wire, cfg, reason) =>
        peer(wire, failClose = true, eof = IO.never).use { p =>
          BlockFetchSession.resource[IO](p.transport, cfg).use { session =>
            (session.negotiate(Handshake.Data(42)) *> session
              .request(b.range) *> session.receive).attempt.flatMap { result =>
              IO(assertEquals(result.left.toOption.map(_.getMessage), Some(reason))) *> p.closes.get
                .map(n => assert(n > 0))
            }
          }
        }
      })
      .unsafeToFuture()
  }

  test("whole deadline wins despite repeated Streaming messages resetting state deadline") {
    val b = batch("shelley"); val bs = raws("shelley")
    val wire = Vector(accepted, frames(hex("8102"))) ++ bs.map(x => frames(wrapped(x)))
    TestControl
      .executeEmbed(peer(wire, delay = 1.second, eof = IO.never).use { p =>
        BlockFetchSession
          .resource[IO](
            p.transport,
            BlockFetchSession.Config(times =
              BlockFetch.TimeLimits(streaming = 2.seconds, wholeRequest = 5.seconds)
            )
          )
          .use { session =>
            def loop: IO[Unit] = session.receive *> IO.defer(loop)
            (session.negotiate(Handshake.Data(42)) *> session.request(b.range) *> loop).attempt.map(
              r =>
                assertEquals(
                  r.left.toOption.map(_.getMessage),
                  Some("whole request deadline expired")
                )
            )
          }
      })
      .unsafeToFuture()
  }

  test("cancel admitted acquisition closes transport and preserves checkpoint") {
    val b = batch("shelley"); val s = selection(b); val d = descriptor("shelley", b)
    (for
      root <- temp
      entered <- Deferred[IO, Unit]
      _ <- peer(Vector(accepted), eof = entered.complete(()).void *> IO.never).use { p =>
        FixtureDirectRangeSource.resource[IO](d, s, Resource.pure(p.transport)).use { source =>
          store(root, s, d).use { st =>
            for
              fiber <- source.run(st).start
              _ <- entered.get *> fiber.cancel
              snap <- st.snapshot
              closed <- p.transport.isClosed
            yield
              assertEquals(snap.records.size, 0)
              assert(closed)
          }
        }
      }
    yield ()).unsafeToFuture()
  }

  test("BlockFetch rejects a second request and validates exact wire/frame budgets") {
    val b = batch("shelley")
    val wire = join(accepted, frames(hex("8103")))
    val cases = Vector(
      (BlockFetchSession.Config(maxWireBytes = wire.size, maxTotalFrames = 2), true),
      (BlockFetchSession.Config(maxWireBytes = wire.size - 1, maxTotalFrames = 2), false),
      (BlockFetchSession.Config(maxWireBytes = wire.size, maxTotalFrames = 1), false)
    )
    cases
      .traverse_ { (cfg, ok) =>
        peer(chunks(wire)).use { p =>
          BlockFetchSession.resource[IO](p.transport, cfg).use { session =>
            (session.negotiate(Handshake.Data(42)) *> session.request(
              b.range
            ) *> session.receive).attempt.flatMap { result =>
              IO(assertEquals(result.isRight, ok)) *>
                (if ok then session.request(b.range).attempt.map(r => assert(r.isLeft))
                 else IO.unit)
            }
          }
        }
      }
      .unsafeToFuture()
  }

  test("one MiB block crosses many SDUs and reads with explicit wrapper/framing headroom") {
    val b = batch("shelley")
    val payload = Bytes(Vector.fill(1048576)(0.toByte))
    val wire = join(accepted, frames(join(hex("8102"), wrapped(payload), hex("8105")), size = 4096))
    peer(chunks(wire, 777))
      .use { p =>
        BlockFetchSession.resource[IO](p.transport).use { session =>
          for
            _ <- session.negotiate(Handshake.Data(42)) *> session.request(b.range)
            start <- session.receive
            block <- session.receive
            end <- session.receive
            _ <- session.finish
            status <- session.status
          yield
            assertEquals(start, BlockFetch.Message.StartBatch)
            assertEquals(
              block,
              BlockFetch.Message.Block(right(CardanoBlockFetch.RawNtNBlock.from(payload)))
            )
            assertEquals(end, BlockFetch.Message.BatchDone)
            assert(status.peakIngressBytes <= 1114128)
        }
      }
      .unsafeToFuture()
  }

  test(
    "every partial application header survives coalesced handshake; handshake CBOR suffix fails"
  ) {
    val b = batch("shelley"); val app = frames(hex("8103"))
    (0 to 9).toVector
      .traverse_ { split =>
        val reads =
          Vector(join(accepted, Bytes(app.value.take(split))), Bytes(app.value.drop(split)))
            .filter(_.size > 0)
        peer(reads).use { p =>
          BlockFetchSession.resource[IO](p.transport).use { session =>
            (session.negotiate(Handshake.Data(42)) *> session.request(b.range) *> session.receive)
              .map(r => assertEquals(r, BlockFetch.Message.NoBlocks))
          }
        }
      }
      .flatMap { _ =>
        peer(Vector(frame(0, hex("83010e84182af500f48103")))).use { p =>
          BlockFetchSession
            .resource[IO](p.transport)
            .use(_.negotiate(Handshake.Data(42)))
            .attempt
            .map(r => assert(r.isLeft))
        }
      }
      .unsafeToFuture()
  }

  test("network is closed before append, canceling append retains only verified committed prefix") {
    val b = batch("shelley"); val s = selection(b); val d = descriptor("shelley", b)
    (for
      root <- temp
      entered <- Deferred[IO, Unit]
      _ <- peer(chunks(join(accepted, response(raws("shelley"))))).use { p =>
        FixtureDirectRangeSource.resource[IO](d, s, Resource.pure(p.transport)).use { source =>
          store(root, s, d).use { underlying =>
            val wrappedStore = new SegmentStore[IO]:
              def selectionIdentity = underlying.selectionIdentity
              def sourceIdentity = underlying.sourceIdentity
              def snapshot = underlying.snapshot
              def append(block: CardanoBlockIndex.IndexedBlock) =
                p.transport.isClosed.flatMap(closed => IO(assert(closed))) *>
                  underlying.append(block).flatTap(_ => entered.complete(()).void *> IO.never)
            for
              fiber <- source.run(wrappedStore).start
              _ <- entered.get *> fiber.cancel
              snap <- underlying.snapshot
            yield assertEquals(snap.records.size, 1)
          }
        }
      }
      complete <- FixtureDirectRangeSource
        .resource[IO](
          d,
          s,
          peer(chunks(join(accepted, response(raws("shelley"))))).map(_.transport)
        )
        .use(source => store(root, s, d, true).use(source.run))
    yield assertEquals(complete.snapshot.records.size, 4)).unsafeToFuture()
  }

  test("strict mismatch, query and refusal cannot leave usable negotiated capability") {
    val b = batch("shelley")
    val cases = Vector(
      (accepted, Handshake.Data(42, initiatorOnly = false)),
      (frame(0, hex("8203a10e84182af500f4")), Handshake.Data(42)),
      (frame(0, hex("82028200810e")), Handshake.Data(42)),
      (frame(0, hex("83010e84182af500f5")), Handshake.Data(42))
    )
    cases
      .traverse_ { (reply, data) =>
        peer(Vector(reply)).use { p =>
          BlockFetchSession.resource[IO](p.transport).use { session =>
            for
              failed <- session.negotiate(data).attempt
              request <- session.request(b.range).attempt
              status <- session.status
            yield
              assert(failed.isLeft)
              assert(request.isLeft)
              assert(status.closed.nonEmpty)
          }
        }
      }
      .unsafeToFuture()
  }

  test("canceling request queued behind negotiation does not consume its one admission") {
    val b = batch("shelley")
    (for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      readCount <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Boolean](false)
      t = new ByteTransport[IO]:
        def read = readCount
          .getAndUpdate(_ + 1)
          .flatMap(n =>
            if n == 0 then entered.complete(()).void *> release.get.as(Some(accepted))
            else IO.pure(Some(frames(hex("8103"))))
          )
        def write(b: Bytes) = IO.unit
        def close = closed.set(true)
        def isClosed = closed.get
      _ <- BlockFetchSession.resource[IO](t).use { session =>
        for
          negotiating <- session.negotiate(Handshake.Data(42)).start
          _ <- entered.get
          queued <- session.request(b.range).start
          _ <- IO.cede *> queued.cancel
          _ <- release.complete(()) *> negotiating.joinWithNever
          _ <- session.request(b.range)
          response <- session.receive
        yield assertEquals(response, BlockFetch.Message.NoBlocks)
      }
    yield ()).unsafeToFuture()
  }

  test("single-point source completes only after BatchDone; runOwned also bounds store recovery") {
    val base = batch("shelley");
    val one = right(
      EndpointBatch.Spec
        .checked(base.anchor, CardanoBlockFetch.InclusiveRange.single(base.first), Some(1))
    )
    val full = descriptor("shelley", base)
    val d = right(
      PinnedDirectRangeDescriptor.checked(
        one,
        Handshake.Data(42),
        full.identity.digest,
        full.identity.digest,
        full.originals.take(1),
        full.identity.digest,
        Vector("fixture-singleton-v1")
      )
    )
    val s = selection(one)
    (for
      root <- temp
      _ <- FixtureDirectRangeSource
        .resource[IO](
          d,
          s,
          peer(chunks(join(accepted, response(raws("shelley").take(1))))).map(_.transport)
        )
        .use { source =>
          source.runOwned(store(root, s, d)).map(r => assertEquals(r.snapshot.records.size, 1))
        }
      _ <- TestControl.executeEmbed(
        FixtureDirectRangeSource.resource[IO](d, s, Resource.eval(IO.never)).use { source =>
          source.runOwned(Resource.eval(IO.never[SegmentStore[IO]])).attempt.map(r => error(r, 3))
        }
      )
    yield ()).unsafeToFuture()
  }

  test("public finite scripted transport independently enforces literal handshake/request/done") {
    import lab.network.ScriptedByteTransport
    import ScriptedByteTransport.Step
    val b = batch("shelley"); val s = selection(b); val d = descriptor("shelley", b)
    val script = Vector(Step.Expect(proposal), Step.Receive(accepted), Step.Expect(request(b))) ++
      chunks(response(raws("shelley")), 97).map(Step.Receive(_)) :+ Step.Expect(done)
    (for
      root <- temp
      result <- FixtureDirectRangeSource
        .resource[IO](d, s, ScriptedByteTransport.resource[IO](script))
        .use(source => source.runOwned(store(root, s, d)))
    yield assert(result.complete)).unsafeToFuture()
  }

  test(
    "preprod fixture descriptor binds network label and synthetic handshake magic independently"
  ) {
    val b = batch("babbage"); val d = descriptor("babbage", b)
    def variant(label: String, magic: Long) = right(
      PinnedDirectRangeDescriptor.checked(
        b,
        Handshake.Data(magic),
        d.identity.digest,
        d.identity.digest,
        d.originals,
        d.identity.digest,
        Vector("pallas:0ea294c1273b9c4a5a0a9f767897800589f5e02b"),
        label
      )
    )
    val base = variant("preprod-source-provenanced", 42)
    assertNotEquals(base.identity.digest, variant("network-unestablished", 42).identity.digest)
    assertNotEquals(base.identity.digest, variant("preprod-source-provenanced", 1).identity.digest)
    assertEquals(d.identity.label, "fixture-blockfetch-preprod-source-provenanced")
  }

  test("TCP acquisition budget includes stalled transport acquisition before any session") {
    val b = batch("shelley")
    val limits =
      right(TcpFetchLimits.checked(right(lab.network.TcpLimits.checked()), acquisitionSeconds = 1))
    val d = right(
      TcpDirectRangeDescriptor.checked(
        right(lab.network.NumericPeer.checked("127.0.0.1", 12345)),
        "synthetic",
        42,
        b,
        Digests.text("claims"),
        "local-test",
        Vector.empty,
        limits
      )
    )
    TestControl
      .executeEmbed(for
        canceled <- Ref.of[IO, Boolean](false)
        connection = Resource.eval(IO.never[ByteTransport[IO]].onCancel(canceled.set(true)))
        result <- TcpDirectRangeSource.fromConnection[IO](d, connection).use { source =>
          val store = new SegmentStore[IO]:
            def selectionIdentity = d.spec.identity
            def sourceIdentity = d.identity
            def snapshot = IO.pure(Snapshot(Vector.empty, Digests.text("empty")))
            def append(b: lab.chain.CardanoBlockIndex.IndexedBlock) =
              IO.raiseError(new AssertionError("append before acquisition"))
          source.run(store)
        }
        stopped <- canceled.get
      yield
        assert(!result.complete)
        assertEquals(result.reason, "timeBudget during connection/acquisition")
        assert(stopped))
      .unsafeToFuture()
  }
