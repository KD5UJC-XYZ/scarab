// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.ChainSync
import scala.concurrent.duration.*
import BoundedChainFollower.{Event, Original, Peer}
import BoundedValidatorRunner.{Policy, Stop}

class LiveValidatorCommandSuite extends munit.FunSuite:
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
  private def scripted(
      events: List[IO[Event]],
      intersection: ChainSync.Point,
      fetcher: ChainSync.Point => IO[Bytes] = _ =>
        IO.raiseError(new AssertionError("unexpected fetch"))
  ): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[IO[Event]]](events)).map { queue =>
      new Peer[IO]:
        def intersect(candidates: Vector[ChainSync.Point]) = IO.pure(intersection)
        def next = queue.modify {
          case head :: tail => (tail, head)
          case Nil => (Nil, IO.raiseError[Event](new IllegalStateException("script exhausted")))
        }.flatten
        def fetch(p: ChainSync.Point) = fetcher(p)
    }
  private def field(line: String, name: String) =
    ReferenceJson.field(ReferenceJson.parse(raw(line)), name)
  private def record(line: String): String = ReferenceJson.parse(raw(line)) match
    case ReferenceJson.Json.Obj(fields) =>
      fields.get("record").orElse(fields.get("scope")) match
        case Some(ReferenceJson.Json.Str(value)) => value
        case _                                   => ""
    case _ => ""
  private val denied: Either[CoherentSequenceCommand.Failure, CoherentSequenceCommand.Report] =
    Left(CoherentSequenceCommand.Failure.Rejected("test-oracle", "sentinel"))
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

  test("success summary cannot be manufactured or copied") {
    assert(
      compileErrors("new lab.LiveValidatorCommand.Summary(null,null,0,0,0,0,false,0)").nonEmpty
    )
    assert(compileErrors("val x: lab.LiveValidatorCommand.Summary = null; x.copy()").nonEmpty)
  }
  test("invalid command arity fails before input or network access") {
    LiveValidatorCommand.run(Nil).map(code => assertEquals(code.code, 2)).unsafeToFuture()
  }
  test("successful resource release is counted after the underlying finalizer") {
    (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled
      .flatMap { (opens, closes) =>
        val underlying =
          Resource.make(IO.unit)(_ => closes.get.flatMap(n => IO(assertEquals(n, 0))))
        for
          _ <- LiveValidatorCommand.counted(underlying, opens, closes).use(_ => IO.unit)
          a <- opens.get; b <- closes.get
        yield
          assertEquals(a, 1); assertEquals(b, 1)
      }
      .unsafeToFuture()
  }
  test("counted acquisition stays cancelable and releases partially acquired resources") {
    (Deferred[IO, Unit], Ref.of[IO, Int](0), Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled
      .flatMap { (entered, opens, closes, partialClosed) =>
        val partial = Resource
          .make(IO.unit)(_ => partialClosed.update(_ + 1))
          .flatMap(_ => Resource.eval(entered.complete(()).void *> IO.never[Unit]))
        for
          fiber <- LiveValidatorCommand.counted(partial, opens, closes).use(_ => IO.unit).start
          _ <- entered.get
          _ <- fiber.cancel
          result <- fiber.join
          a <- opens.get; b <- closes.get; released <- partialClosed.get
        yield
          assert(result.isCanceled)
          assertEquals(a, 0); assertEquals(b, 0); assertEquals(released, 1)
      }
      .unsafeToFuture()
  }
  test("failed underlying finalizer is not falsely counted closed") {
    (Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled
      .flatMap { (opens, closes) =>
        val underlying =
          Resource.make(IO.unit)(_ => IO.raiseError(new IllegalStateException("release failed")))
        for
          failed <- LiveValidatorCommand
            .counted(underlying, opens, closes)
            .use(_ => IO.unit)
            .attempt
          a <- opens.get; b <- closes.get
        yield
          assert(failed.isLeft)
          assertEquals(a, 1); assertEquals(b, 0)
      }
      .unsafeToFuture()
  }
  test("ready follows checked intersection; non-target stop never evaluates oracle") {
    val c = syntheticContext
    (Ref.of[IO, Vector[String]](Vector.empty), Ref.of[IO, Int](0)).tupled
      .flatMap { (logs, reads) =>
        val peer = scripted(List(IO.pure(Event.Await)), anchor(c))
        for
          result <- LiveValidatorCommand.observe(
            c,
            peer,
            reads.update(_ + 1).as(denied),
            line => logs.update(_ :+ line),
            Policy(target = 1, maxEvents = 1)
          )
          lines <- logs.get; n <- reads.get
        yield
          assertEquals(result, Left(LiveValidatorCommand.Failure.Online(Stop.EventBudget)))
          assertEquals(n, 0)
          assertEquals(lines.map(record), Vector("live-validator-ready", "live-validator-stop"))
          assertEquals(field(lines.head, "revision"), ReferenceJson.Json.Num("0"))
          assertEquals(field(lines.head, "coordinatorChecked"), ReferenceJson.Json.Lit("true"))
          assertEquals(field(lines.last, "resourcesFinalized"), ReferenceJson.Json.Lit("true"))
          assertEquals(field(lines.last, "peerOpens"), ReferenceJson.Json.Num("1"))
          assertEquals(field(lines.last, "peerCloses"), ReferenceJson.Json.Num("1"))
      }
      .unsafeToFuture()
  }
  test("unoffered intersection emits no readiness and does not read post oracle") {
    val c = syntheticContext
    Ref
      .of[IO, Vector[String]](Vector.empty)
      .flatMap { logs =>
        val outside = ChainSync.Point.Block(ChainSync.UInt64.Zero, raw("x" * 32))
        for
          result <- LiveValidatorCommand.observe(
            c,
            scripted(Nil, outside),
            IO.raiseError(new AssertionError("post read")),
            line => logs.update(_ :+ line),
            Policy(target = 1)
          )
          lines <- logs.get
        yield
          assertEquals(
            result,
            Left(LiveValidatorCommand.Failure.Online(Stop.OutsideRetainedWindow))
          )
          assertEquals(lines.map(record), Vector("live-validator-stop"))
      }
      .unsafeToFuture()
  }
  test("failed readiness callback terminates online work and still releases peer") {
    val c = syntheticContext
    Ref
      .of[IO, Int](0)
      .flatMap { closed =>
        val peer =
          Resource.make(IO.unit)(_ => closed.update(_ + 1)).flatMap(_ => scripted(Nil, anchor(c)))
        val emit = (line: String) =>
          if record(line) == "live-validator-ready" then
            IO.raiseError(new IllegalStateException("sink failed"))
          else IO.unit
        for
          result <- LiveValidatorCommand.observe(
            c,
            peer,
            IO.raiseError(new AssertionError("post read")),
            emit,
            Policy(target = 1)
          )
          n <- closed.get
        yield
          result match
            case Left(LiveValidatorCommand.Failure.Online(Stop.Internal(_))) => ()
            case other                                                       => fail(other.toString)
          assertEquals(n, 1)
      }
      .unsafeToFuture()
  }
  test("canceling a blocked online peer finalizes it without evaluating post oracle") {
    val c = syntheticContext
    (Deferred[IO, Unit], Ref.of[IO, Int](0)).tupled
      .flatMap { (entered, closed) =>
        val peer = Resource
          .make(IO.unit)(_ => closed.update(_ + 1))
          .flatMap(_ => scripted(List(entered.complete(()).void *> IO.never), anchor(c)))
        for
          fiber <- LiveValidatorCommand
            .observe(
              c,
              peer,
              IO.raiseError(new AssertionError("post read")),
              _ => IO.unit,
              Policy(target = 1)
            )
            .start
          _ <- entered.get
          _ <- fiber.cancel
          out <- fiber.join
          n <- closed.get
        yield
          assert(out.isCanceled)
          assertEquals(n, 1)
      }
      .unsafeToFuture()
  }
  test("post-manifest wait is bounded and its supplier stays unevaluated until ready") {
    TestControl
      .executeEmbed(Ref.of[IO, Int](0).flatMap { reads =>
        for
          start <- IO.monotonic
          expired <- LiveValidatorCommand
            .awaitOracle(IO.pure(false), reads.update(_ + 1).as(denied), 2.seconds)
            .attempt
          afterExpiry <- reads.get
          now <- IO.monotonic
          loaded <- LiveValidatorCommand.awaitOracle(
            IO.monotonic.map(_ >= now + 1.second),
            reads.update(_ + 1).as(denied),
            2.seconds
          )
          count <- reads.get
          end <- IO.monotonic
        yield
          assert(expired.isLeft)
          assertEquals(afterExpiry, 0)
          assertEquals(loaded, denied)
          assertEquals(count, 1)
          assertEquals(end - start, 3.seconds)
      })
      .unsafeToFuture()
  }
  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def context = get(SequenceInput.load(dir))
    def originals =
      val stream = Files.newInputStream(dir.resolve("scala-sequence-capture.md"))
      val bytes =
        try stream.readNBytes(20 * 1024 * 1024 + 1)
        finally stream.close()
      assert(bytes.length <= 20 * 1024 * 1024)
      get(CoherentSequenceCommand.captures(Bytes.fromArray(bytes)))
    def peer(c: SequenceInput.Context, os: Vector[Original]) = scripted(
      os.toList.map(o => IO.pure(Event.Forward(o.envelope))),
      anchor(c),
      p => IO(os.find(point(_) == p).get.block)
    )
    test("retained online publication precedes post oracle and complete offline tuple matches") {
      val c = context; val os = originals
      (Ref.of[IO, Vector[String]](Vector.empty), Ref.of[IO, Boolean](false)).tupled
        .flatMap { (logs, closed) =>
          val source = Resource.make(IO.unit)(_ => closed.set(true)).flatMap(_ => peer(c, os))
          val oracle = for
            released <- closed.get
            lines <- logs.get
            _ <- IO {
              assert(released)
              assertEquals(record(lines.last), "live-validator-stop")
              assertEquals(field(lines.last, "typedStop"), ReferenceJson.Json.Str("TargetReached"))
            }
            report <- CoherentSequenceCommand.observe(dir, dir)
          yield report
          for
            result <- LiveValidatorCommand.observe(
              c,
              source,
              oracle,
              line => logs.update(_ :+ line),
              Policy(target = os.size)
            )
            lines <- logs.get
          yield
            val summary = get(result)
            assert(
              LiveValidatorCommand
                .sameTuple(summary.outcome.snapshot.state, summary.report.finalState)
            )
            val names = lines.map(record)
            assertEquals(names.head, "live-validator-ready")
            assertEquals(names.count(_ == "live-validator-applied"), os.size)
            assertEquals(names.count(_ == "transfer-range-block"), os.size)
            val downloaded = lines.filter(l => record(l) == "live-validator-download")
            assertEquals(downloaded.size, os.size * 2)
            val applied = lines.filter(l => record(l) == "live-validator-applied")
            downloaded.grouped(2).zipWithIndex.foreach { (pair, index) =>
              assertEquals(
                pair.map(l => field(l, "phase")),
                Vector(ReferenceJson.Json.Str("announced"), ReferenceJson.Json.Str("fetched"))
              )
              pair.foreach { line =>
                assertEquals(field(line, "appliedRevision"), ReferenceJson.Json.Num(index.toString))
                assertEquals(field(line, "appliedClaim"), ReferenceJson.Json.Lit("false"))
                if index == 0 then
                  assertEquals(field(line, "scopedAppliedTip"), ReferenceJson.Json.Lit("null"))
                else
                  assertEquals(
                    field(line, "scopedAppliedTip"),
                    field(applied(index - 1), "scopedAppliedTip")
                  )
                  assertEquals(field(line, "appliedStateId"), field(applied(index - 1), "stateId"))
              }
            }
            assertEquals(names.last, "coherent-sequence-observation")
            assert(
              names.lastIndexOf("live-validator-applied") < names.indexOf("live-validator-stop")
            )
            assertEquals(
              get(
                CoherentSequenceCommand.captures(
                  raw(lines.filter(l => record(l) == "transfer-range-block").mkString("\n"))
                )
              ),
              os
            )
            val finalLine = LiveValidatorCommand.render(summary)
            Vector(
              "passed",
              "onlineBeforePostOracle",
              "downloadCursorSeparate",
              "finalTupleReferenceMatched",
              "resourcesFinalized"
            ).foreach(k => assertEquals(field(finalLine, k), ReferenceJson.Json.Lit("true")))
            Vector(
              "liveForkClaim",
              "durableClaim",
              "fullLedgerValidated",
              "consensusValidated",
              "transportCounted"
            ).foreach(k => assertEquals(field(finalLine, k), ReferenceJson.Json.Lit("false")))
            assertEquals(summary.peerOpens, 1); assertEquals(summary.peerCloses, 1)
        }
        .unsafeToFuture()
    }
    test("retained oracle rejection cannot be masked by successful online publication") {
      val c = context; val os = originals
      LiveValidatorCommand
        .observe(c, peer(c, os), IO.pure(denied), _ => IO.unit, Policy(target = os.size))
        .map { result =>
          assertEquals(
            result,
            Left(LiveValidatorCommand.Failure.Rejected("test-oracle", "sentinel"))
          )
        }
        .unsafeToFuture()
    }
    test("retained final oracle for a longer prefix cannot certify a shorter online tuple") {
      val c = context; val os = originals
      LiveValidatorCommand
        .observe(
          c,
          peer(c, os.take(1)),
          CoherentSequenceCommand.observe(dir, dir),
          _ => IO.unit,
          Policy(target = 1)
        )
        .map {
          case Left(LiveValidatorCommand.Failure.Rejected("online-offline-tuple", _)) => ()
          case other => fail(other.toString)
        }
        .unsafeToFuture()
    }
    test("retained transport finalization mismatch prevents post oracle evaluation") {
      val c = context; val os = originals
      LiveValidatorCommand
        .observe(
          c,
          peer(c, os),
          IO.raiseError(new AssertionError("post read")),
          _ => IO.unit,
          Policy(target = os.size),
          Some(IO.pure((5, 4)))
        )
        .map {
          case Left(LiveValidatorCommand.Failure.Rejected("cleanup", _)) => ()
          case other                                                     => fail(other.toString)
        }
        .unsafeToFuture()
    }
  }
