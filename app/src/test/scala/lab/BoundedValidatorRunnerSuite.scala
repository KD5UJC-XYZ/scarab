// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync
import lab.ledger.ClusterTransition as Ledger
import scala.concurrent.duration.*
import BoundedChainFollower.{Event, Original, Peer}
import BoundedValidatorRunner.{Policy, Stop}

class BoundedValidatorRunnerSuite extends munit.FunSuite:
  override val munitTimeout = 90.seconds
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-sequence-context-v1\n" + SequenceInput.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(files: Map[String, Bytes]) = get(SequenceInput.bind(manifest(files), files))
  private def anchor(c: SequenceInput.Context): ChainSync.Point =
    ChainSync.Point.Block(
      get(ChainSync.UInt64.from(c.certificateSeed.tip.slot)),
      c.certificateSeed.tip.hash
    )
  private def point(o: Original): ChainSync.Point =
    val h = get(ReferenceCaptureCommand.header(o.envelope))
    ChainSync.Point.Block(get(ChainSync.UInt64.from(h.slot)), h.hash)
  private def same(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.id, b.id)
    assertEquals(a.contextId, b.contextId)
    assertEquals(a.acquisition.originals, b.acquisition.originals)
    assertEquals(a.certificates.state.id, b.certificates.state.id)
    assertEquals(a.certificates.state.counters, b.certificates.state.counters)
    assertEquals(a.nonces.id, b.nonces.id)
    assertEquals(a.nonces.fields, b.nonces.fields)
    assertEquals(
      a.eligibility.map(e =>
        (
          e.contextId,
          e.headers.map(h => (h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator))
        )
      ),
      b.eligibility.map(e =>
        (
          e.contextId,
          e.headers.map(h => (h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator))
        )
      )
    )
    assertEquals(a.ledger.id, b.ledger.id)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.slot, b.ledger.slot)
    assertEquals(a.scopedAppliedTip, b.scopedAppliedTip)
  private def scripted(
      events: List[IO[Event]],
      intersection: ChainSync.Point,
      fetcher: ChainSync.Point => IO[Bytes] = _ =>
        IO.raiseError(new AssertionError("unexpected fetch")),
      onNext: IO[Unit] = IO.unit
  ): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[IO[Event]]](events)).map { queue =>
      new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point]) = IO.pure(intersection)
        def next = onNext *> queue.modify {
          case head :: tail => (tail, head)
          case Nil => (Nil, IO.raiseError[Event](new IllegalStateException("script exhausted")))
        }.flatten
        def fetch(p: ChainSync.Point) = fetcher(p)
    }
  private def events(values: Event*): List[IO[Event]] = values.toList.map(IO.pure)
  private def forwards(values: Vector[Original]): List[IO[Event]] =
    values.toList.map(o => IO.pure(Event.Forward(o.envelope)))
  private def run(c: SequenceInput.Context, peer: Resource[IO, Peer[IO]], p: Policy) =
    BoundedValidatorRunner.resource[IO](c, peer, p).use(_.run)
  // Public context is synthetic and used only for anchor/fence behavior; no header is invented.
  private def syntheticContext: SequenceInput.Context =
    val tip = s"""{"era":"Conway","hash":"${"12" * 32}","slot":20,"block":3,"epoch":0}"""
    bind(
      Map(
        "transfer-genesis.md" -> raw(
          """{"networkId":"Testnet","networkMagic":1082026,"epochLength":500,"securityParam":5,"activeSlotsCoeff":0.05,"slotsPerKESPeriod":129600,"maxKESEvolutions":60}"""
        ),
        "pre-tips.md" -> raw(s"[$tip,$tip]"),
        "pre-protocol-state.md" -> raw(
          s"""{"lastSlot":20,"oCertCounters":{"${"34" * 28}":0},"epochNonce":null,"candidateNonce":null,"evolvingNonce":null,"labNonce":null,"lastEpochBlockNonce":null}"""
        ),
        "pre-ledger-state.md" -> raw(
          s"""{"lastEpoch":0,"stakeDistrib":{"pdTotalActiveStake":100,"unPoolDistr":{"${"34" * 28}":{"individualPoolStakeVrf":"${"56" * 32}","individualPoolStake":{"numerator":1,"denominator":1},"individualTotalPoolStake":100}}},"stateBefore":{"esLState":{"utxoState":{"fees":0}}}}"""
        ),
        "pre-parameters.md" -> raw(
          """{"protocolVersion":{"major":9,"minor":0},"txFeePerByte":44,"txFeeFixed":155381,"maxTxSize":16384,"utxoCostPerByte":4310}"""
        ),
        "pre-utxo.md" -> raw("{}"),
        "pre-utxo-cbor.md" -> raw("a0")
      )
    )

  test("invalid policies reject before acquiring a peer") {
    val forbidden = Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("peer opened")))
    Vector(
      Policy(target = 0),
      Policy(target = 9),
      Policy(maxEvents = 0),
      Policy(target = 4, maxEvents = 3),
      Policy(maxEvents = 257),
      Policy(reconnects = -1),
      Policy(reconnects = 5),
      Policy(maxBytes = 0),
      Policy(maxBytes = 64L * 1024 * 1024 + 1),
      Policy(duration = Duration.Zero),
      Policy(duration = 121.seconds)
    ).traverse_ { p =>
      run(syntheticContext, forbidden, p).attempt.map { result =>
        assert(result.isLeft)
        assert(!result.swap.toOption.get.isInstanceOf[AssertionError])
      }
    }.unsafeToFuture()
  }
  test("await and current-anchor rollback loops consume event attempts without state changes") {
    val c = syntheticContext
    Vector(Event.Await, Event.Backward(anchor(c)))
      .traverse_ { e =>
        run(
          c,
          scripted(List.fill(5)(IO.pure(e)), anchor(c)),
          Policy(target = 1, maxEvents = 3, reconnects = 0)
        ).map { out =>
          assertEquals(out.reason, Stop.EventBudget)
          assertEquals(out.events, 3)
          assertEquals(out.returnedBytes, 0L)
          assertEquals(out.snapshot.state.revision, BigInt(0))
          assertEquals(out.snapshot.state.acquisition.size, 0)
        }
      }
      .unsafeToFuture()
  }
  test("a returned over-budget header is charged before parsing or fetching") {
    val c = syntheticContext
    run(
      c,
      scripted(events(Event.Forward(raw("xx"))), anchor(c)),
      Policy(target = 1, maxBytes = 1, reconnects = 0)
    ).map { out =>
      assertEquals(out.reason, Stop.ByteBudget)
      assertEquals(out.returnedBytes, 2L)
      assertEquals(out.events, 1)
      assertEquals(out.snapshot.state.revision, BigInt(0))
    }.unsafeToFuture()
  }
  test("unsupported outer era rejects before requesting block bytes") {
    val c = syntheticContext
    val envelope = get(
      Cbor.encode(
        Value.Arr(
          Vector(
            Node(Value.UInt(5), Bytes.empty),
            Node(
              Value.Tag(24, Node(Value.ByteString(raw("opaque header")), Bytes.empty)),
              Bytes.empty
            )
          )
        )
      )
    )
    Ref
      .of[IO, Int](0)
      .flatMap { fetched =>
        val peer = scripted(
          events(Event.Forward(envelope)),
          anchor(c),
          _ => fetched.update(_ + 1).as(Bytes.empty)
        )
        run(c, peer, Policy(target = 1)).flatMap { out =>
          fetched.get.map { count =>
            out.reason match
              case Stop.Unsupported("era", _) => ()
              case other                      => fail(other.toString)
            assertEquals(count, 0)
            assertEquals(out.events, 1)
            assertEquals(out.returnedBytes, envelope.size.toLong)
            assertEquals(out.snapshot.state.revision, BigInt(0))
          }
        }
      }
      .unsafeToFuture()
  }
  test(
    "all resource acquisition failures are terminal, including availability-classified failures"
  ) {
    Vector(true, false)
      .traverse_ { retryable =>
        Ref.of[IO, Int](0).flatMap { opens =>
          val error =
            if retryable then new BoundedValidatorRunner.Unavailable("offline")
            else new IllegalStateException("bad peer")
          val peer = Resource.eval(opens.update(_ + 1) *> IO.raiseError[Peer[IO]](error))
          run(syntheticContext, peer, Policy(target = 1, reconnects = 2)).flatMap { out =>
            opens.get.map { count =>
              assertEquals(count, 1)
              assertEquals(out.reconnects, 0)
              assertEquals(out.events, 0)
              assert(out.reason.isInstanceOf[Stop.PeerFailure])
            }
          }
        }
      }
      .unsafeToFuture()
  }
  test("failed next attempts count cumulatively across retryable reconnects") {
    val c = syntheticContext
    val peer =
      scripted(List(IO.raiseError(new BoundedValidatorRunner.Unavailable("lost next"))), anchor(c))
    run(c, peer, Policy(target = 1, maxEvents = 2, reconnects = 4))
      .map { out =>
        assertEquals(out.reason, Stop.EventBudget)
        assertEquals(out.events, 2)
        assertEquals(out.snapshot.state.revision, BigInt(0))
      }
      .unsafeToFuture()
  }
  test("deadline releases blocked peer and counts the pending event attempt") {
    val c = syntheticContext
    Ref
      .of[IO, Int](0)
      .flatMap { closed =>
        val peer = Resource
          .make(IO.unit)(_ => closed.update(_ + 1))
          .flatMap(_ => scripted(List(IO.never), anchor(c)))
        run(c, peer, Policy(target = 1, duration = 100.millis)).flatMap { out =>
          closed.get.map { count =>
            assertEquals(out.reason, Stop.TimeBudget)
            assertEquals(out.events, 1)
            assertEquals(count, 1)
            assertEquals(out.snapshot.state.revision, BigInt(0))
          }
        }
      }
      .unsafeToFuture()
  }
  test("cleanup failures including Unavailable are terminal and override completion") {
    val c = syntheticContext
    Vector[Throwable](
      new IllegalStateException("release failed"),
      new BoundedValidatorRunner.Unavailable("release unavailable")
    ).traverse_ { error =>
      val peer = Resource
        .make(IO.unit)(_ => IO.raiseError(error))
        .flatMap(_ => scripted(events(Event.Await), anchor(c)))
      run(c, peer, Policy(target = 1, maxEvents = 1, reconnects = 2)).map { out =>
        assert(out.reason.isInstanceOf[Stop.CleanupFailed])
        assertEquals(out.reconnects, 0)
        assertEquals(out.snapshot.state.revision, BigInt(0))
      }
    }.unsafeToFuture()
  }
  test("cleanup failure overrides deadline while retaining the readable tuple") {
    val c = syntheticContext
    val peer = Resource
      .make(IO.unit)(_ =>
        IO.raiseError(new BoundedValidatorRunner.Unavailable("cleanup after cancellation"))
      )
      .flatMap(_ => scripted(List(IO.never), anchor(c)))
    run(c, peer, Policy(target = 1, duration = 100.millis))
      .map { out =>
        assert(out.reason.isInstanceOf[Stop.CleanupFailed])
        assertEquals(out.events, 1)
        assertEquals(out.reconnects, 0)
        assertEquals(out.snapshot.state.revision, BigInt(0))
      }
      .unsafeToFuture()
  }
  test(
    "established availability failures exhaust retries while generic next failures do not retry"
  ) {
    val c = syntheticContext
    Vector(true, false)
      .traverse_ { retryable =>
        Ref.of[IO, Int](0).flatMap { opened =>
          val error =
            if retryable then new BoundedValidatorRunner.Unavailable("temporarily unavailable")
            else new IllegalStateException("protocol failure")
          val peer = Resource
            .eval(opened.update(_ + 1))
            .flatMap(_ => scripted(List(IO.raiseError(error)), anchor(c)))
          run(c, peer, Policy(target = 1, reconnects = 2)).flatMap { out =>
            opened.get.map { count =>
              assertEquals(count, if retryable then 3 else 1)
              assertEquals(out.events, count)
              assertEquals(out.reconnects, count - 1)
              if retryable then assert(out.reason.isInstanceOf[Stop.TransportExhausted])
              else assert(out.reason.isInstanceOf[Stop.PeerFailure])
            }
          }
        }
      }
      .unsafeToFuture()
  }
  test("typed coordinator unsupported and internal failures retain their classifications") {
    assertEquals(
      BoundedValidatorRunner.sequenceFailure(
        CoherentSequence.Failure.Unsupported("ledger", "Plutus")
      ),
      Stop.Unsupported("ledger", "Plutus")
    )
    Vector("internal", "rollback", "certificate-undo", "nonce-undo").foreach { stage =>
      assert(
        BoundedValidatorRunner
          .sequenceFailure(CoherentSequence.Failure.Rejected(stage, "broken invariant"))
          .isInstanceOf[Stop.Internal]
      )
    }
    assert(
      BoundedValidatorRunner
        .sequenceFailure(
          CoherentSequence.Failure
            .LedgerRejected(Ledger.Failure.InternalFailure("broken invariant"))
        )
        .isInstanceOf[Stop.Internal]
    )
  }
  test("one-shot guard rejects repeated run without reopening peer") {
    val c = syntheticContext
    Ref
      .of[IO, Int](0)
      .flatMap { opens =>
        val peer =
          Resource.eval(opens.update(_ + 1)).flatMap(_ => scripted(events(Event.Await), anchor(c)))
        BoundedValidatorRunner.resource[IO](c, peer, Policy(target = 1, maxEvents = 1)).use {
          runner =>
            for
              a <- runner.run
              b <- runner.run
              count <- opens.get
            yield
              assertEquals(a.reason, Stop.EventBudget)
              assertEquals(b.reason, Stop.AlreadyRun)
              assertEquals(count, 1)
              same(a.snapshot.state, b.snapshot.state)
        }
      }
      .unsafeToFuture()
  }
  test("canceling run leaves readable anchor snapshot and releases peer") {
    val c = syntheticContext
    (Deferred[IO, Unit], Ref.of[IO, Int](0)).tupled
      .flatMap { (entered, closed) =>
        val peer = Resource
          .make(IO.unit)(_ => closed.update(_ + 1))
          .flatMap(_ => scripted(List(entered.complete(()).void *> IO.never), anchor(c)))
        BoundedValidatorRunner.resource[IO](c, peer).use { runner =>
          for
            fiber <- runner.run.start
            _ <- entered.get
            simultaneous <- runner.run
            stillOpen <- closed.get
            _ <- fiber.cancel
            result <- fiber.join
            snap <- runner.snapshot
            count <- closed.get
            again <- runner.run
          yield
            assert(result.isCanceled)
            assertEquals(simultaneous.reason, Stop.AlreadyRun)
            assertEquals(stillOpen, 0)
            assertEquals(snap.state.revision, BigInt(0))
            assertEquals(count, 1)
            assertEquals(again.reason, Stop.AlreadyRun)
        }
      }
      .unsafeToFuture()
  }
  test("unknown intersection or rollback fails closed without reanchoring") {
    val c = syntheticContext
    val outside = ChainSync.Point.Block(ChainSync.UInt64.Zero, raw("x" * 32))
    Vector(scripted(Nil, outside), scripted(events(Event.Backward(outside)), anchor(c)))
      .traverse_ { peer =>
        run(c, peer, Policy(target = 1)).map { out =>
          assertEquals(out.reason, Stop.OutsideRetainedWindow)
          assertEquals(out.snapshot.state.acquisition.anchor, anchor(c))
          assertEquals(out.snapshot.state.revision, BigInt(0))
        }
      }
      .unsafeToFuture()
  }

  test("canceling a concurrent duplicate run never cancels or reopens the active owner") {
    val c = syntheticContext
    (
      Deferred[IO, Unit],
      Deferred[IO, Unit],
      Deferred[IO, Stop],
      Ref.of[IO, Int](0),
      Ref.of[IO, Int](0)
    ).tupled
      .flatMap { (entered, release, reported, opens, closes) =>
        val peer = Resource
          .make(opens.update(_ + 1))(_ => closes.update(_ + 1))
          .flatMap(_ =>
            scripted(List(entered.complete(()).void *> release.get.as(Event.Await)), anchor(c))
          )
        BoundedValidatorRunner.resource[IO](c, peer, Policy(target = 1, maxEvents = 1)).use {
          runner =>
            for
              first <- runner.run.start
              _ <- entered.get
              duplicate <- runner.run
                .flatMap(out => reported.complete(out.reason).void *> IO.never[Unit])
                .start
              reason <- reported.get
              _ <- duplicate.cancel
              canceled <- duplicate.join
              openedBefore <- opens.get
              closedBefore <- closes.get
              _ <- release.complete(())
              completed <- first.joinWithNever
              openedAfter <- opens.get
              closedAfter <- closes.get
            yield
              assertEquals(reason, Stop.AlreadyRun)
              assert(canceled.isCanceled)
              assertEquals(openedBefore, 1)
              assertEquals(closedBefore, 0)
              assertEquals(completed.reason, Stop.EventBudget)
              assertEquals(completed.events, 1)
              assertEquals(openedAfter, 1)
              assertEquals(closedAfter, 1)
        }
      }
      .unsafeToFuture()
  }
  test("one cumulative deadline spans established availability reconnects") {
    val c = syntheticContext
    TestControl
      .executeEmbed(
        (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled.flatMap { (opened, closed) =>
          val peer = Resource
            .make(opened.update(_ + 1))(_ => closed.update(_ + 1))
            .flatMap(_ =>
              scripted(
                List(
                  IO.sleep(6.seconds) *> IO.raiseError[Event](
                    new BoundedValidatorRunner.Unavailable("retry after six seconds")
                  )
                ),
                anchor(c)
              )
            )
          for
            start <- IO.monotonic
            out <- run(c, peer, Policy(target = 1, reconnects = 4, duration = 10.seconds))
            finish <- IO.monotonic
            acquisitions <- opened.get
            releases <- closed.get
          yield
            assertEquals(out.reason, Stop.TimeBudget)
            assertEquals(out.events, 2)
            assertEquals(out.reconnects, 1)
            assertEquals(out.returnedBytes, 0L)
            assertEquals(out.snapshot.state.revision, BigInt(0))
            assertEquals(acquisitions, 2)
            assertEquals(releases, 2)
            assertEquals(finish - start, 10.seconds)
        }
      )
      .unsafeToFuture()
  }
  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val directory = Path.of(location)
    def context = get(SequenceInput.load(directory))
    def originals =
      val stream = Files.newInputStream(directory.resolve("scala-sequence-capture.md"))
      val data =
        try stream.readNBytes(20 * 1024 * 1024 + 1)
        finally stream.close()
      assert(data.length <= 20 * 1024 * 1024)
      get(CoherentSequenceCommand.captures(Bytes.fromArray(data)))
    def fetch(os: Vector[Original])(p: ChainSync.Point): IO[Bytes] =
      IO(os.find(point(_) == p).get.block)
    def baseline(c: SequenceInput.Context, os: Vector[Original]): IO[CoherentSequence.Snapshot] =
      CoherentSequence.create[IO](c).map(get(_)).flatMap { runtime =>
        os.traverse_(o =>
          runtime
            .prepare(get(SequenceInput.block(o)))
            .map(get(_))
            .flatMap(runtime.publish)
            .map(get(_))
        ) *> runtime.snapshot
      }
    def bytes(os: Vector[Original]): Long = os.map(o => o.envelope.size.toLong + o.block.size).sum

    test(
      "retained synthetically shifted next-epoch header rejects before fetch and preserves anchor"
    ) {
      val c = context
      val original = originals.head
      val h = get(ReferenceCaptureCommand.header(original.envelope))
      val header = get(Cbor.decode(h.raw)).value.asInstanceOf[Value.Arr].value
      val body = header.head.value.asInstanceOf[Value.Arr].value
      val shifted =
        body.updated(1, Node(Value.UInt((c.epoch + 1) * c.nonces.context.epochLength), Bytes.empty))
      val changedHeader =
        get(Cbor.encode(Value.Arr(header.updated(0, Node(Value.Arr(shifted), Bytes.empty)))))
      val envelope = get(
        Cbor.encode(
          Value.Arr(
            Vector(
              Node(Value.UInt(6), Bytes.empty),
              Node(Value.Tag(24, Node(Value.ByteString(changedHeader), Bytes.empty)), Bytes.empty)
            )
          )
        )
      )
      Ref
        .of[IO, Int](0)
        .flatMap { fetched =>
          val peer = scripted(
            events(Event.Forward(envelope)),
            anchor(c),
            _ => fetched.update(_ + 1).as(original.block)
          )
          BoundedValidatorRunner.resource[IO](c, peer, Policy(target = 1)).use { runner =>
            for
              initial <- runner.snapshot
              out <- runner.run
              count <- fetched.get
            yield
              out.reason match
                case Stop.Unsupported("epoch", _) => ()
                case other                        => fail(other.toString)
              assertEquals(count, 0)
              assertEquals(out.events, 1)
              assertEquals(out.returnedBytes, envelope.size.toLong)
              same(out.snapshot.state, initial.state)
              assertEquals(out.snapshot.state.revision, initial.state.revision)
          }
        }
        .unsafeToFuture()
    }
    test("retained strict backpressure requests no next event during preparation") {
      val c = context; val os = originals
      (Deferred[IO, Unit], Deferred[IO, Unit], Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled
        .flatMap { (entered, release, requests, fetched) =>
          val peer = scripted(
            forwards(os),
            anchor(c),
            p => fetched.update(_ + 1) *> fetch(os)(p),
            requests.update(_ + 1)
          )
          val hook = (label: String) =>
            if label == "before-prepare" then entered.complete(()).void *> release.get else IO.unit
          BoundedValidatorRunner.resourceObserved[IO](c, peer, Policy(target = 1), hook).use {
            runner =>
              for
                fiber <- runner.run.start
                _ <- entered.get
                nr <- requests.get; nf <- fetched.get; before <- runner.snapshot
                _ <- release.complete(())
                out <- fiber.joinWithNever
              yield
                assertEquals(nr, 1); assertEquals(nf, 1)
                assertEquals(before.state.revision, BigInt(0))
                assertEquals(out.reason, Stop.TargetReached)
                assertEquals(out.snapshot.state.acquisition.originals, os.take(1))
          }
        }
        .unsafeToFuture()
    }
    Vector("before-prepare", "before-publish", "after-publish").foreach { label =>
      test(s"retained cancellation at $label preserves the complete atomic tuple") {
        val c = context; val os = originals.take(1)
        (Deferred[IO, Unit], Ref.of[IO, Int](0)).tupled
          .flatMap { (entered, closed) =>
            val peer = Resource
              .make(IO.unit)(_ => closed.update(_ + 1))
              .flatMap(_ => scripted(forwards(os), anchor(c), fetch(os)))
            val hook = (stage: String) =>
              if stage == label then entered.complete(()).void *> IO.never else IO.unit
            BoundedValidatorRunner.resourceObserved[IO](c, peer, Policy(target = 1), hook).use {
              runner =>
                for
                  expected <- baseline(c, if label == "after-publish" then os else Vector.empty)
                  fiber <- runner.run.start
                  _ <- entered.get
                  _ <- fiber.cancel
                  result <- fiber.join
                  snap <- runner.snapshot
                  count <- closed.get
                yield
                  assert(result.isCanceled)
                  same(snap.state, expected.state)
                  assertEquals(snap.state.revision, expected.state.revision)
                  assertEquals(count, 1)
            }
          }
          .unsafeToFuture()
      }
    }
    test("retained failed fetch reconnects at earlier anchor and replays without double fees") {
      val c = context; val os = originals
      Ref
        .of[IO, Int](0)
        .flatMap { opens =>
          val peer = Resource.eval(opens.getAndUpdate(_ + 1)).flatMap { attempt =>
            val script = if attempt == 0 then forwards(os.take(2)) else forwards(os)
            scripted(
              script,
              anchor(c),
              p =>
                if attempt == 0 && p == point(os(1)) then
                  IO.raiseError(new BoundedValidatorRunner.Unavailable("incomplete fetch"))
                else fetch(os)(p)
            ).map { underlying =>
              new Peer[IO]:
                def intersect(candidates: Vector[ChainSync.Point]) =
                  IO(
                    assertEquals(
                      candidates,
                      if attempt == 0 then Vector(anchor(c)) else Vector(point(os.head), anchor(c))
                    )
                  ) *> underlying.intersect(candidates)
                def next = underlying.next
                def fetch(p: ChainSync.Point) = underlying.fetch(p)
            }
          }
          for
            expected <- baseline(c, os)
            out <- run(c, peer, Policy(target = os.size, reconnects = 1))
          yield
            assertEquals(out.reason, Stop.TargetReached)
            same(out.snapshot.state, expected.state)
            assertEquals(out.snapshot.state.revision, BigInt(os.size + 2))
            assertEquals(out.events, os.size + 2)
            assertEquals(out.reconnects, 1)
            assertEquals(out.returnedBytes, bytes(os.take(1)) + os(1).envelope.size + bytes(os))
        }
        .unsafeToFuture()
    }
    test(
      "retained current-tip no-op and anchor rollback reapply preserve cumulative revision and bytes"
    ) {
      val c = context; val os = originals
      val script = forwards(os.take(1)) ++ events(
        Event.Backward(point(os.head)),
        Event.Backward(anchor(c))
      ) ++ forwards(os)
      (baseline(c, os), run(c, scripted(script, anchor(c), fetch(os)), Policy(target = os.size)))
        .mapN { (expected, out) =>
          assertEquals(out.reason, Stop.TargetReached)
          same(out.snapshot.state, expected.state)
          assertEquals(out.snapshot.state.revision, BigInt(os.size + 2))
          assertEquals(out.returnedBytes, bytes(os.take(1)) + bytes(os))
          assertEquals(out.events, os.size + 3)
        }
        .unsafeToFuture()
    }
    test("retained outside rollback preserves the already committed whole tuple") {
      val c = context; val os = originals.take(1)
      val outside = ChainSync.Point.Block(ChainSync.UInt64.Zero, raw("z" * 32))
      (
        baseline(c, os),
        run(
          c,
          scripted(forwards(os) ++ events(Event.Backward(outside)), anchor(c), fetch(os)),
          Policy(target = 2)
        )
      ).mapN { (expected, out) =>
        assertEquals(out.reason, Stop.OutsideRetainedWindow)
        same(out.snapshot.state, expected.state)
        assertEquals(out.snapshot.state.revision, BigInt(1))
      }.unsafeToFuture()
    }
    test("retained abandoned originals remain charged when replay exceeds byte budget") {
      val c = context; val os = originals.take(1)
      val script = forwards(os) ++ events(Event.Backward(anchor(c))) ++ forwards(os)
      (
        baseline(c, Vector.empty),
        run(
          c,
          scripted(script, anchor(c), fetch(os)),
          Policy(target = 2, maxBytes = 2 * bytes(os) - 1)
        )
      ).mapN { (expected, out) =>
        assertEquals(out.reason, Stop.ByteBudget)
        assertEquals(out.returnedBytes, 2 * bytes(os))
        assertEquals(out.events, 3)
        same(out.snapshot.state, expected.state)
        assertEquals(out.snapshot.state.revision, BigInt(2))
      }.unsafeToFuture()
    }
    test("retained reorg event budget stops after rollback without resetting attempts") {
      val c = context; val os = originals.take(1)
      val script = forwards(os) ++ events(Event.Backward(anchor(c))) ++ forwards(os)
      run(c, scripted(script, anchor(c), fetch(os)), Policy(target = 2, maxEvents = 2))
        .map { out =>
          assertEquals(out.reason, Stop.EventBudget)
          assertEquals(out.events, 2)
          assertEquals(out.returnedBytes, bytes(os))
          assertEquals(out.snapshot.state.revision, BigInt(2))
          assertEquals(out.snapshot.state.acquisition.size, 0)
        }
        .unsafeToFuture()
    }
    test("retained cancellation after rollback keeps restored tuple with monotonic revision") {
      val c = context; val os = originals.take(1)
      (Deferred[IO, Unit], Ref.of[IO, Int](0)).tupled
        .flatMap { (entered, rollbacks) =>
          // Initial anchor intersection invokes the hook too; block only the actual later rollback.
          val hook = (label: String) =>
            if label == "after-rollback" then
              rollbacks
                .getAndUpdate(_ + 1)
                .flatMap(n => if n == 1 then entered.complete(()).void *> IO.never else IO.unit)
            else IO.unit
          val peer =
            scripted(forwards(os) ++ events(Event.Backward(anchor(c))), anchor(c), fetch(os))
          BoundedValidatorRunner.resourceObserved[IO](c, peer, Policy(target = 2), hook).use {
            runner =>
              for
                initial <- runner.snapshot
                fiber <- runner.run.start
                _ <- entered.get
                _ <- fiber.cancel
                snap <- runner.snapshot
              yield
                same(snap.state, initial.state)
                assertEquals(snap.state.revision, BigInt(2))
          }
        }
        .unsafeToFuture()
    }
    test(
      "retained second transaction missing input rejects the whole block after valid first transaction"
    ) {
      val originalContext = context; val os = originals
      val blocks = os.map(o => get(SequenceInput.block(o)))
      val index = blocks.indexWhere(_.transactionMemos.size == 2)
      assert(index >= 0)
      val selected = blocks(index)
      def arr(node: Node): Vector[Node] = node.value match
        case Value.Arr(xs)        => xs
        case Value.Tag(_, nested) => arr(nested)
        case _                    => fail("array required")
      def inputs(memo: Bytes): Vector[Bytes] =
        val body = arr(get(Cbor.decode(memo))).head.value.asInstanceOf[Value.Map].value
        arr(body.find(_._1.value == Value.UInt(0)).get._2).map(n => get(Cbor.encode(n.value)))
      val firstInputs = inputs(selected.transactionMemos.head)
      val secondInputs = inputs(selected.transactionMemos(1))
      val utxo = get(
        Cbor.decode(
          get(
            Bytes.fromHex(
              new String(originalContext.originals("pre-utxo-cbor.md").toArray, "UTF-8").trim
            )
          )
        )
      ).value.asInstanceOf[Value.Map].value
      val victim = secondInputs
        .find(key =>
          !firstInputs.contains(key) && utxo.exists(p => get(Cbor.encode(p._1.value)) == key)
        )
        .getOrElse(fail("fixture needs independent pre-state input for second transaction"))
      val reduced = Value.Map(utxo.filterNot(p => get(Cbor.encode(p._1.value)) == victim))
      val victimParts = arr(get(Cbor.decode(victim)))
      val inputId = victimParts.head.value
        .asInstanceOf[Value.ByteString]
        .value
        .hex + "#" + victimParts(1).value.asInstanceOf[Value.UInt].value.toString
      def render(j: ReferenceJson.Json): String = j match
        case ReferenceJson.Json.Obj(values) =>
          values.toVector
            .sortBy(_._1)
            .map((k, v) => render(ReferenceJson.Json.Str(k)) + ":" + render(v))
            .mkString("{", ",", "}")
        case ReferenceJson.Json.Arr(values) => values.map(render).mkString("[", ",", "]")
        case ReferenceJson.Json.Str(value) =>
          "\"" + value.flatMap {
            case '\\'           => "\\\\"
            case '"'            => "\\\""
            case ch if ch < ' ' => f"\\u${ch.toInt}%04x"
            case ch             => ch.toString
          } + "\""
        case ReferenceJson.Json.Num(value) => value
        case ReferenceJson.Json.Lit(value) => value
      val json = ReferenceJson
        .parse(originalContext.originals("pre-utxo.md"))
        .asInstanceOf[ReferenceJson.Json.Obj]
        .fields
      assert(json.contains(inputId))
      val changed = bind(
        originalContext.originals
          .updated("pre-utxo-cbor.md", raw(get(Cbor.encode(reduced)).hex))
          .updated("pre-utxo.md", raw(render(ReferenceJson.Json.Obj(json - inputId))))
      )
      assert(
        Ledger.prepare(changed.ledger, selected.transactionMemos.head, selected.header.slot).isRight
      )
      assert(
        Ledger
          .prepareBlock(
            changed.ledger,
            selected.header.hash,
            selected.transactionMemos,
            selected.header.slot
          )
          .isLeft
      )
      (
        baseline(changed, os.take(index)),
        run(changed, scripted(forwards(os), anchor(changed), fetch(os)), Policy(target = os.size))
      ).mapN { (expected, out) =>
        out.reason match
          case Stop.Rejected(stage, reason) =>
            assert((stage + reason).toLowerCase.contains("ledger"))
          case other => fail(other.toString)
        same(out.snapshot.state, expected.state)
        assertEquals(out.snapshot.state.revision, BigInt(index))
        assertEquals(out.events, index + 1)
      }.unsafeToFuture()
    }
  }
