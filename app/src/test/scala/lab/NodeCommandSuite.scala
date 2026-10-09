// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync
import scala.concurrent.duration.*
import BoundedChainFollower.{Event, Original, Peer}
import BoundedValidatorRunner.{Policy, Stop, Outcome}

class NodeCommandSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-sequence-context-v1\n" + SequenceInput.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(files: Map[String, Bytes]) = get(SequenceInput.bind(manifest(files), files))
  private val required =
    List("--profile", NodeCommand.Profile, "--bootstrap", "/supplied-bootstrap", "--port", "3001")
  private def config(extra: String*) = get(NodeCommand.Config.parse(required ++ extra))
  private def anchor(c: SequenceInput.Context): ChainSync.Point =
    ChainSync.Point.Block(
      get(ChainSync.UInt64.from(c.certificateSeed.tip.slot)),
      c.certificateSeed.tip.hash
    )
  private def point(o: Original): ChainSync.Point =
    val h = get(ReferenceCaptureCommand.header(o.envelope))
    ChainSync.Point.Block(get(ChainSync.UInt64.from(h.slot)), h.hash)
  private def scripted(
      values: List[IO[Event]],
      intersection: ChainSync.Point,
      fetcher: ChainSync.Point => IO[Bytes] = _ =>
        IO.raiseError(new AssertionError("unexpected fetch"))
  ): Resource[IO, Peer[IO]] =
    Resource.eval(Ref.of[IO, List[IO[Event]]](values)).map { queue =>
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
  private def record(line: String) = ReferenceJson.string(field(line, "record"))
  private def pointJson(p: ChainSync.Point): ReferenceJson.Json = p match
    case ChainSync.Point.Origin => ReferenceJson.parse(raw("{\"origin\":true}"))
    case ChainSync.Point.Block(slot, hash) =>
      ReferenceJson.parse(raw(s"""{"hash":"${hash.hex}","slot":${slot.value}}"""))
  private def pointsJson(points: Vector[ChainSync.Point]): ReferenceJson.Json =
    ReferenceJson.parse(
      raw(points.map(p => ValidatedRestartCapture.canonical(pointJson(p))).mkString("[", ",", "]"))
    )
  private def acquisitionOnly(line: String): Unit =
    assertEquals(field(line, "acquisitionOnly"), ReferenceJson.Json.Lit("true"))
    assertEquals(field(line, "appliedClaim"), ReferenceJson.Json.Lit("false"))

  private val noPeer =
    Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("unexpected peer acquisition")))
  // Fake engine is used only to exercise typed outcome and resource-boundary handling at an anchor.
  private def engine(
      reason: Stop,
      release: IO[Unit] = IO.unit,
      failRun: Boolean = false
  ): NodeCommand.EngineFactory = new NodeCommand.EngineFactory:
    def resource(
        context: SequenceInput.Context,
        peer: Resource[IO, Peer[IO]],
        policy: Policy,
        onTransition: String => IO[Unit]
    ): Resource[IO, NodeCommand.Engine] =
      Resource.make(CoherentSequence.create[IO](context).map(get(_)))(_ => release).map { runtime =>
        new NodeCommand.Engine:
          def snapshot = runtime.snapshot.map(s => NodeCommand.EngineView(s.state))
          def run = if failRun then IO.raiseError(new IllegalStateException("engine run failed"))
          else
            snapshot.map(s =>
              NodeCommand.EngineOutcome(s, NodeCommand.Ending.Completed(reason), 0, 0L, 0)
            )
      }
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

  test("explicit profile and bootstrap parse with safe bounded defaults") {
    val c = config()
    assertEquals(c.profile, CoherentSequence.ProfileId)
    assertEquals(c.bootstrap, Path.of("/supplied-bootstrap"))
    assertEquals(c.port, 3001)
    assertEquals(c.blocks, 4); assertEquals(c.seconds, 60)
    assertEquals(c.events, 64); assertEquals(c.bytes, 32L * 1024 * 1024)
    assertEquals(c.reconnects, 0)
    assert(c.policy.valid)
    assertEquals(NodeCommand.NetworkMagic, 1082026L)
    assert(
      compileErrors(
        "new lab.NodeCommand.Config(null,null,1,1,1,1,1L,0,null,8,false,None,None,None,None,None,None)"
      ).nonEmpty
    )
    assert(compileErrors("val c: lab.NodeCommand.Config = null; c.copy()").nonEmpty)
  }
  test("bounded mode preserves defaults and audit is explicitly opt-in") {
    val normal = config()
    assertEquals(normal.mode, "bounded-volatile")
    assertEquals(normal.rollbackCapacity, 8)
    assertEquals(normal.audit, false)
    assertEquals(normal.policy.advanceWindow, false)
    val explicit = config("--mode", "bounded-volatile", "--audit", "true")
    assertEquals(explicit.policy, normal.policy)
    assert(explicit.audit)
  }
  test("sustained volatile mode requires explicit capacity and maps cumulative policy bounds") {
    val twelve =
      config("--mode", "sustained-volatile", "--rollback-capacity", "4", "--blocks", "12")
    assertEquals(twelve.blocks, 12)
    assertEquals(twelve.policy.advanceWindow, true)
    assertEquals(twelve.policy.rollbackCapacity, 4)
    assertEquals(twelve.events, 64)
    val upper = config(
      "--mode",
      "sustained-volatile",
      "--rollback-capacity",
      "1",
      "--blocks",
      "256",
      "--events",
      "256",
      "--seconds",
      "120",
      "--bytes",
      "67108864",
      "--audit",
      "true"
    )
    assert(upper.policy.valid)
    assertEquals(upper.policy.target, 256)
  }
  test("unintegrated durable modes and incomplete sustained configurations fail closed") {
    val sustained = List("--mode", "sustained-volatile", "--blocks", "12")
    Vector(
      List("--mode", "sustained-durable"),
      List("--mode", "bounded-durable"),
      List("--mode", "other"),
      sustained,
      sustained ++ List("--rollback-capacity", "0"),
      sustained ++ List("--rollback-capacity", "9"),
      List("--mode", "sustained-volatile", "--rollback-capacity", "4"),
      List("--mode", "sustained-volatile", "--rollback-capacity", "4", "--blocks", "8"),
      List(
        "--mode",
        "sustained-volatile",
        "--rollback-capacity",
        "4",
        "--blocks",
        "257",
        "--events",
        "256"
      ),
      List("--rollback-capacity", "4"),
      List("--audit", "yes"),
      List("--audit", "TRUE"),
      List("--mode", "bounded-volatile", "--mode", "bounded-volatile")
    )
      .foreach(extra => assert(NodeCommand.Config.parse(required ++ extra).isLeft))
    assert(
      NodeCommand.Config
        .parse(required ++ List("--mode", "sustained-durable"))
        .swap
        .toOption
        .get
        .contains("checkpoint v1")
    )
    assert(
      NodeCommand.Config
        .parse(required ++ List("--mode", "bounded-durable"))
        .swap
        .toOption
        .get
        .contains("expected context required")
    )
  }
  test("sustained target twelve cannot be fabricated from an unvalidated synthetic anchor") {
    val settings = config(
      "--mode",
      "sustained-volatile",
      "--rollback-capacity",
      "4",
      "--blocks",
      "12",
      "--events",
      "12"
    )
    (for
      invalid <- NodeCommand.execute(
        settings,
        syntheticContext,
        noPeer,
        _ => IO.unit,
        engine(Stop.TargetReached)
      )
      exhausted <- NodeCommand.execute(
        settings,
        syntheticContext,
        scripted(List.fill(12)(IO.pure(Event.Await)), anchor(syntheticContext)),
        _ => IO.unit
      )
    yield
      assert(invalid.isLeft)
      val report = get(exhausted)
      assertEquals(report.outcome.reason, Stop.EventBudget)
      assertEquals(report.outcome.snapshot.state.depth, BigInt(0))
      assertEquals(
        field(NodeCommand.render(report), "mode"),
        ReferenceJson.Json.Str("sustained-volatile")
      )
      assertEquals(
        field(NodeCommand.render(report), "scopedTargetReached"),
        ReferenceJson.Json.Lit("false")
      )
    ).unsafeToFuture()
  }
  test("audit state projection is token-free and emitted only after engine finalization") {
    Ref
      .of[IO, Boolean](false)
      .flatMap { released =>
        val settings = config("--audit", "true")
        val output = (line: String) =>
          if record(line) != "node-state" then IO.unit
          else
            released.get.flatMap { done =>
              IO {
                assert(done)
                assertEquals(field(line, "depth"), ReferenceJson.Json.Str("0"))
                assertEquals(field(line, "compactedBlocks"), ReferenceJson.Json.Str("0"))
                assertEquals(field(line, "derivedAnchorId"), ReferenceJson.Json.Lit("null"))
                val fields =
                  ReferenceJson.parse(raw(line)).asInstanceOf[ReferenceJson.Json.Obj].fields
                assertEquals(
                  fields.keySet,
                  Set(
                    "record",
                    "projection",
                    "revision",
                    "depth",
                    "compactedBlocks",
                    "derivedAnchorId"
                  )
                )
              }
            }
        NodeCommand
          .execute(
            settings,
            syntheticContext,
            noPeer,
            output,
            engine(Stop.EventBudget, released.set(true))
          )
          .map(result => assert(result.isRight))
      }
      .unsafeToFuture()
  }
  test("audit output failure after cleanup cannot yield a terminal success report") {
    Ref
      .of[IO, Boolean](false)
      .flatMap { released =>
        val output = (line: String) =>
          if record(line) == "node-state" then
            IO.raiseError(new IllegalStateException("audit output failed"))
          else IO.unit
        for
          result <- NodeCommand.execute(
            config("--audit", "true"),
            syntheticContext,
            noPeer,
            output,
            engine(Stop.EventBudget, released.set(true))
          )
          done <- released.get
        yield
          assert(done)
          assertEquals(
            result,
            Left(NodeCommand.Failure("output", "Rejected", "audit output failed"))
          )
      }
      .unsafeToFuture()
  }
  test("all supported explicit upper and lower policy boundaries parse") {
    val lower = config(
      "--blocks",
      "1",
      "--seconds",
      "1",
      "--events",
      "1",
      "--bytes",
      "1",
      "--reconnects",
      "0"
    )
    val upper = config(
      "--blocks",
      "8",
      "--seconds",
      "120",
      "--events",
      "256",
      "--bytes",
      "67108864",
      "--reconnects",
      "4"
    )
    assert(lower.policy.valid && upper.policy.valid)
    assertEquals(upper.policy, Policy(8, 256, 4, 67108864L, 120.seconds))
  }
  test("duplicate unknown missing and malformed flags reject before any I/O") {
    Vector(
      Nil,
      required.drop(2),
      required.dropRight(1),
      required :+ "--blocks",
      required ++ List("--port", "3002"),
      required ++ List("--host", "example.com"),
      required.updated(1, "other-profile"),
      required.updated(3, ""),
      required.updated(3, "x" * 4097),
      required.updated(3, "bad\u0000path"),
      required.updated(3, "--port"),
      required.updated(5, "0"),
      required.updated(5, "65536"),
      required.updated(5, "03001"),
      required.updated(5, "+3001"),
      required.updated(5, " 3001"),
      required.updated(5, "99999999999999999999999999999999")
    )
      .foreach(args => assert(NodeCommand.Config.parse(args).isLeft, args.toString))
    NodeCommand
      .run(required ++ List("--unknown", "x"))
      .map(code => assertEquals(code.code, 2))
      .unsafeToFuture()
  }
  test("out-of-range and noncanonical policy values reject") {
    Vector(
      "--blocks" -> "0",
      "--blocks" -> "9",
      "--seconds" -> "0",
      "--seconds" -> "121",
      "--events" -> "3",
      "--events" -> "257",
      "--bytes" -> "0",
      "--bytes" -> "67108865",
      "--reconnects" -> "-1",
      "--reconnects" -> "5",
      "--seconds" -> "1.0",
      "--seconds" -> "01",
      "--bytes" -> "1e6"
    ).foreach { (flag, value) =>
      assert(NodeCommand.Config.parse(required ++ List(flag, value)).isLeft)
    }
  }
  test("normal budget exits are explicitly stopped and never caught-up success") {
    Vector(Stop.EventBudget, Stop.ByteBudget, Stop.TimeBudget)
      .traverse_ { stop =>
        NodeCommand.execute(config(), syntheticContext, noPeer, _ => IO.unit, engine(stop)).map {
          result =>
            val report = get(result)
            val json = NodeCommand.render(report)
            assertEquals(NodeCommand.exitCode(report).code, 0)
            assertEquals(field(json, "status"), ReferenceJson.Json.Str("budget-stopped"))
            assertEquals(field(json, "budgetStop"), ReferenceJson.Json.Lit("true"))
            Vector(
              "caughtUp",
              "scopedTargetReached",
              "bootstrapValidated",
              "fullLedgerValidated",
              "consensusValidated",
              "durable"
            )
              .foreach(k => assertEquals(field(json, k), ReferenceJson.Json.Lit("false")))
            assertEquals(field(json, "scopedAppliedTip"), ReferenceJson.Json.Lit("null"))
        }
      }
      .unsafeToFuture()
  }
  test("typed unsupported rejected and failure outcomes retain nonzero exits") {
    Vector(
      Stop.Unsupported("ledger", "Plutus"),
      Stop.Rejected("header", "bad proof"),
      Stop.PeerFailure("disconnected"),
      Stop.TransportExhausted("offline"),
      Stop.CleanupFailed("release"),
      Stop.Internal("invariant"),
      Stop.OutsideRetainedWindow,
      Stop.AlreadyRun
    ).traverse_ { stop =>
      NodeCommand.execute(config(), syntheticContext, noPeer, _ => IO.unit, engine(stop)).map {
        result =>
          val report = get(result)
          assertEquals(NodeCommand.exitCode(report).code, 2)
          assertEquals(
            field(NodeCommand.render(report), "typedStop"),
            ReferenceJson.Json.Str(NodeCommand.classification(stop)._1)
          )
      }
    }.unsafeToFuture()
  }
  test("engine cannot advertise reached target without the requested scoped prefix") {
    NodeCommand
      .execute(config(), syntheticContext, noPeer, _ => IO.unit, engine(Stop.TargetReached))
      .map {
        case Left(NodeCommand.Failure("engine", "Internal", _, _, _, _, _, _)) => ()
        case other => fail(other.toString)
      }
      .unsafeToFuture()
  }
  test("engine run and finalizer failures cannot become successful terminal reports") {
    Vector(
      engine(Stop.EventBudget, IO.raiseError(new IllegalStateException("release failed"))),
      engine(Stop.EventBudget, failRun = true)
    ).traverse_ { factory =>
      NodeCommand.execute(config(), syntheticContext, noPeer, _ => IO.unit, factory).map {
        case Left(NodeCommand.Failure("engine-resource", "Internal", _, _, _, _, _, _)) => ()
        case Right(report) =>
          assert(report.outcome.reason.isInstanceOf[Stop.CleanupFailed])
          assert(report.outcome.cleanupFailure.nonEmpty)
          assertEquals(NodeCommand.exitCode(report).code, 2)
        case other => fail(other.toString)
      }
    }.unsafeToFuture()
  }
  test("bootstrap output failure is terminal before a peer can open") {
    NodeCommand
      .execute(
        config(),
        syntheticContext,
        noPeer,
        _ => IO.raiseError(new IllegalStateException("output failed"))
      )
      .map {
        case Left(NodeCommand.Failure("output", "Rejected", _, _, _, _, _, _)) => ()
        case other => fail(other.toString)
      }
      .unsafeToFuture()
  }
  test("default engine emits supplied bootstrap and checked intersection before bounded stop") {
    val c = syntheticContext
    Ref
      .of[IO, Vector[String]](Vector.empty)
      .flatMap { logs =>
        for
          result <- NodeCommand.execute(
            config("--blocks", "1", "--events", "1"),
            c,
            scripted(List(IO.pure(Event.Await)), anchor(c)),
            s => logs.update(_ :+ s)
          )
          lines <- logs.get
        yield
          val report = get(result)
          assertEquals(report.outcome.reason, Stop.EventBudget)
          assertEquals(
            lines.map(record),
            Vector(
              "node-bootstrap",
              "node-intersection-offered",
              "node-intersection-selected",
              "node-rollback"
            )
          )
          assertEquals(field(lines.head, "sourceBound"), ReferenceJson.Json.Lit("true"))
          assertEquals(field(lines.head, "scopedAppliedTip"), ReferenceJson.Json.Lit("null"))
          assertEquals(field(lines(3), "initialIntersection"), ReferenceJson.Json.Lit("true"))
          assertEquals(field(lines(1), "offeredPoints"), pointsJson(Vector(anchor(c))))
          assertEquals(field(lines(2), "selectedPoint"), pointJson(anchor(c)))
          assertEquals(field(lines(2), "offeredMatch"), ReferenceJson.Json.Lit("true"))
          lines.slice(1, 3).foreach(acquisitionOnly)
          assert(
            !ReferenceJson
              .parse(raw(lines(3)))
              .asInstanceOf[ReferenceJson.Json.Obj]
              .fields
              .contains("projection")
          )
          assertEquals(report.peerOpens, 1); assertEquals(report.peerCloses, 1)
      }
      .unsafeToFuture()
  }
  test(
    "unoffered actual Origin intersection is reported before rejecting it without next or fetch"
  ) {
    val c = syntheticContext
    (for
      logs <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- NodeCommand.execute(
        config("--blocks", "1"),
        c,
        scripted(Nil, ChainSync.Point.Origin),
        line => logs.update(_ :+ line)
      )
      lines <- logs.get
    yield
      val report = get(result)
      assertEquals(report.outcome.reason, Stop.OutsideRetainedWindow)
      assertEquals(report.outcome.events, 0)
      assertEquals(report.outcome.snapshot.state.depth, BigInt(0))
      assertEquals(
        lines.map(record),
        Vector("node-bootstrap", "node-intersection-offered", "node-intersection-selected")
      )
      assertEquals(field(lines(1), "offeredPoints"), pointsJson(Vector(anchor(c))))
      assertEquals(field(lines(2), "selectedPoint"), pointJson(ChainSync.Point.Origin))
      assertEquals(field(lines(2), "offeredMatch"), ReferenceJson.Json.Lit("false"))
      lines.drop(1).foreach(acquisitionOnly)
    ).unsafeToFuture()
  }

  test("unsupported era remains typed and no block is fetched") {
    val c = syntheticContext
    val envelope = get(
      Cbor.encode(
        Value.Arr(
          Vector(
            Node(Value.UInt(5), Bytes.empty),
            Node(Value.Tag(24, Node(Value.ByteString(raw("opaque")), Bytes.empty)), Bytes.empty)
          )
        )
      )
    )
    NodeCommand
      .execute(
        config("--blocks", "1"),
        c,
        scripted(List(IO.pure(Event.Forward(envelope))), anchor(c)),
        _ => IO.unit
      )
      .map { result =>
        val report = get(result)
        report.outcome.reason match
          case Stop.Unsupported("era", _) => ()
          case other                      => fail(other.toString)
        assertEquals(NodeCommand.exitCode(report).code, 2)
        assertEquals(report.outcome.snapshot.state.revision, BigInt(0))
      }
      .unsafeToFuture()
  }
  test(
    "canceling the lexical run releases peer and engine resources without hidden background work"
  ) {
    val c = syntheticContext
    (Deferred[IO, Unit], Ref.of[IO, Int](0), Ref.of[IO, Int](0)).tupled
      .flatMap { (entered, peersClosed, enginesClosed) =>
        val peer = Resource
          .make(IO.unit)(_ => peersClosed.update(_ + 1))
          .flatMap(_ => scripted(List(entered.complete(()).void *> IO.never), anchor(c)))
        val factory = new NodeCommand.EngineFactory:
          def resource(
              context: SequenceInput.Context,
              p: Resource[IO, Peer[IO]],
              policy: Policy,
              onTransition: String => IO[Unit]
          ) =
            Resource
              .make(IO.unit)(_ => enginesClosed.update(_ + 1))
              .flatMap(_ => NodeCommand.defaultFactory.resource(context, p, policy, onTransition))
        for
          fiber <- NodeCommand.execute(config(), c, peer, _ => IO.unit, factory).start
          _ <- entered.get
          _ <- fiber.cancel
          out <- fiber.join
          p <- peersClosed.get; e <- enginesClosed.get
        yield
          assert(out.isCanceled)
          assertEquals(p, 1); assertEquals(e, 1)
      }
      .unsafeToFuture()
  }
  test("cancelable peer acquisition releases partial resources before cancellation status") {
    val c = syntheticContext
    (Deferred[IO, Unit], Ref.of[IO, Int](0), Ref.of[IO, Boolean](false)).tupled
      .flatMap { (entered, partialClosed, reported) =>
        val peer = Resource
          .make(IO.unit)(_ => partialClosed.update(_ + 1))
          .flatMap(_ => Resource.eval(entered.complete(()).void *> IO.never[Peer[IO]]))
        val output = (line: String) =>
          partialClosed.get.flatMap { count =>
            IO {
              assertEquals(count, 1)
              assertEquals(field(line, "typedStop"), ReferenceJson.Json.Str("Cancelled"))
              assertEquals(field(line, "status"), ReferenceJson.Json.Str("cancelled"))
              assertEquals(field(line, "stateAvailable"), ReferenceJson.Json.Lit("false"))
            } *> reported.set(true)
          }
        for
          fiber <- NodeCommand
            .withCancellationStatus(NodeCommand.execute(config(), c, peer, _ => IO.unit), output)
            .start
          _ <- entered.get
          _ <- fiber.cancel
          result <- fiber.join
          done <- reported.get
        yield
          assert(result.isCanceled)
          assert(done)
      }
      .unsafeToFuture()
  }
  test("transition output failure terminates run after finalizing the established peer") {
    val c = syntheticContext
    Ref
      .of[IO, Int](0)
      .flatMap { closed =>
        val peer =
          Resource.make(IO.unit)(_ => closed.update(_ + 1)).flatMap(_ => scripted(Nil, anchor(c)))
        val output = (line: String) =>
          if record(line) == "node-rollback" then
            IO.raiseError(new IllegalStateException("transition sink failed"))
          else IO.unit
        for
          result <- NodeCommand.execute(config(), c, peer, output)
          count <- closed.get
        yield
          val report = get(result)
          assert(report.outcome.reason.isInstanceOf[Stop.Internal])
          assertEquals(NodeCommand.exitCode(report).code, 2)
          assertEquals(count, 1)
      }
      .unsafeToFuture()
  }
  test("failure JSON bounds and escapes externally supplied diagnostic text") {
    val rendered =
      NodeCommand.renderFailure(NodeCommand.Failure("x\n\"", "Rejected", "\\\n\"" + "a" * 2000))
    assertEquals(field(rendered, "stage"), ReferenceJson.Json.Str("x\n\""))
    assertEquals(ReferenceJson.string(field(rendered, "detail")).length, 1024)
    assertEquals(field(rendered, "scopedTargetReached"), ReferenceJson.Json.Lit("false"))
  }

  private def durableArgs(base: Path, c: SequenceInput.Context): List[String] = List(
    "--mode",
    "bounded-durable",
    "--store-action",
    "create",
    "--store",
    base.resolve("store").toString,
    "--receipts",
    base.resolve("receipts").toString,
    "--expected-context",
    c.id.hex,
    "--blocks",
    "1",
    "--events",
    "1"
  )
  private def durableConfig(base: Path, c: SequenceInput.Context) =
    get(NodeCommand.Config.parse(required ++ durableArgs(base, c)))

  test("durable configuration requires explicit external pinned resume authority before I/O") {
    val c = syntheticContext
    val args = durableArgs(Path.of("/explicit-durable-test"), c)
    assertEquals(get(NodeCommand.Config.parse(required ++ args)).rollbackCapacity, 8)
    val resumed = args.updated(args.indexOf("create"), "resume") ++ List(
      "--resume-receipt",
      "/external-receipt.json",
      "--resume-sha256",
      "12" * 32
    )
    assert(NodeCommand.Config.parse(required ++ resumed).isRight)
    Vector(
      args.updated(args.indexOf("create"), "resume"),
      args ++ List("--resume-receipt", "/external-receipt.json"),
      args ++ List("--rollback-capacity", "8"),
      args.updated(args.indexOf(c.id.hex), "AB" * 32),
      args.updated(
        args.indexOf("/explicit-durable-test/receipts"),
        "/explicit-durable-test/store/receipt"
      ),
      args.updated(args.indexOf("/explicit-durable-test/store"), "/explicit-durable-test/../store"),
      args.updated(args.indexOf("bounded-durable"), "bounded-volatile"),
      resumed.updated(resumed.indexOf("12" * 32), "bad")
    ).foreach(extra => assert(NodeCommand.Config.parse(required ++ extra).isLeft))
  }

  test("durable mode rejects an injected volatile engine before peer acquisition") {
    val c = syntheticContext
    NodeCommand
      .execute(
        durableConfig(Path.of("/not-opened"), c),
        c,
        noPeer,
        _ => IO.unit,
        engine(Stop.EventBudget)
      )
      .map { result =>
        assert(result.isLeft)
        assertEquals(
          field(NodeCommand.renderFailure(result.swap.toOption.get), "durable"),
          ReferenceJson.Json.Lit("true")
        )
      }
      .unsafeToFuture()
  }

  test("durable create acknowledgment and loaded no-op resume remain distinct") {
    val c = syntheticContext
    val base = Files.createTempDirectory("node-durable-confirmations-")
    val settings = durableConfig(base, c)
    (for
      firstFactory <- NodeCommand.factoryFor(settings, c)
      first <- NodeCommand.execute(
        settings,
        c,
        scripted(List(IO.pure(Event.Await)), anchor(c)),
        _ => IO.unit,
        firstFactory
      )
      firstReport = get(first)
      receipt = firstReport.outcome.snapshot.receipt.get
      resumeArgs = durableArgs(base, c)
        .updated(3, "resume")
        .updated(7, base.resolve("receipts-b").toString) ++ List(
        "--resume-receipt",
        receipt.path,
        "--resume-sha256",
        receipt.sha256
      )
      resumed = get(NodeCommand.Config.parse(required ++ resumeArgs))
      factory <- NodeCommand.factoryFor(resumed, c)
      logs <- Ref.of[IO, Vector[String]](Vector.empty)
      second <- NodeCommand.execute(
        resumed,
        c,
        Resource
          .eval(logs.get.map { lines =>
            assertEquals(lines.map(record), Vector("node-bootstrap", "node-loaded"))
          })
          .flatMap(_ => scripted(List(IO.pure(Event.Await)), anchor(c))),
        line => logs.update(_ :+ line),
        factory
      )
      lines <- logs.get
    yield
      assertEquals(firstReport.outcome.snapshot.confirmation, NodeCommand.Confirmation.Acknowledged)
      assertEquals(firstReport.outcome.snapshot.confirmedGeneration, Some(0L))
      val out = get(second).outcome
      assertEquals(out.snapshot.confirmation, NodeCommand.Confirmation.LoadedVerified)
      assertEquals(out.snapshot.confirmedGeneration, Some(0L))
      assertEquals(out.snapshot.receipt, Some(receipt))
      assertEquals(out.snapshot.state.id, firstReport.outcome.snapshot.state.id)
      assert(!Files.exists(base.resolve("receipts-b")))
      assertEquals(
        field(lines(1), "projection"),
        ValidatedRestartCapture.projection(out.snapshot.state)
      )
      assertEquals(field(lines.last, "confirmation"), ReferenceJson.Json.Str("loaded-verified"))
    ).unsafeToFuture()
  }

  test("independent expected context mismatch stops before creating checkpoint files") {
    val c = syntheticContext
    val base = Files.createTempDirectory("node-context-mismatch-")
    val args = durableArgs(base, c).map(v => if v == c.id.hex then "12" * 32 else v)
    NodeCommand
      .factoryFor(get(NodeCommand.Config.parse(required ++ args)), c)
      .attempt
      .map { out =>
        assert(out.isLeft)
        assert(!Files.exists(base.resolve("store")))
        assert(!Files.exists(base.resolve("receipts")))
      }
      .unsafeToFuture()
  }

  test(
    "failed acknowledgment receipt preserves actual acknowledged state without a peer or stale reference"
  ) {
    val c = syntheticContext
    val base = Files.createTempDirectory("node-ack-sink-failure-")
    val settings = durableConfig(base, c)
    (for
      factory <- NodeCommand.factoryFor(
        settings,
        c,
        Some(_ => IO.raiseError(new IllegalStateException("private sink detail")))
      )
      result <- NodeCommand.execute(settings, c, noPeer, _ => IO.unit, factory)
    yield
      val failure = result.swap.toOption.get
      assertEquals(failure.kind, "ReceiptFailure")
      assert(failure.externalReceiptStale)
      assert(!failure.potentiallyOlderThanDisk)
      assertEquals(failure.lastConfirmed.get.confirmation, NodeCommand.Confirmation.Acknowledged)
      assertEquals(failure.lastConfirmed.get.confirmedGeneration, Some(0L))
      assertEquals(failure.lastConfirmed.get.receipt, None)
      assert(!NodeCommand.renderFailure(failure).contains("private sink detail"))
    ).unsafeToFuture()
  }

  test("cached storage failure retains cleanup uncertainty and suppresses final audit projection") {
    val c = syntheticContext
    val settings = get(
      NodeCommand.Config.parse(
        required ++ durableArgs(Path.of("/unused-storage"), c) ++ List("--audit", "true")
      )
    )
    val factory = new NodeCommand.EngineFactory:
      def resource(
          context: SequenceInput.Context,
          peer: Resource[IO, Peer[IO]],
          policy: Policy,
          hook: String => IO[Unit]
      ) =
        Resource.eval(CoherentSequence.create[IO](context).map(get(_))).map { runtime =>
          new NodeCommand.Engine:
            def snapshot = runtime.snapshot.map(s =>
              NodeCommand.EngineView(
                s.state,
                NodeCommand.Confirmation.Acknowledged,
                Some(0L),
                Some(NodeCommand.ReceiptReference("/explicit-test-receipt", "12" * 32))
              )
            )
            def run = snapshot.map(v =>
              NodeCommand.EngineOutcome(
                v,
                NodeCommand.Ending.StorageFailure(true),
                0,
                0L,
                0,
                Some(Stop.CleanupFailed("separate cleanup failure"))
              )
            )
        }
    (for
      logs <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- NodeCommand.execute(settings, c, noPeer, line => logs.update(_ :+ line), factory)
      lines <- logs.get
    yield
      val report = get(result)
      val rendered = NodeCommand.render(report)
      assertEquals(NodeCommand.exitCode(report).code, 2)
      assertEquals(field(rendered, "typedStop"), ReferenceJson.Json.Str("StorageFailure"))
      assertEquals(field(rendered, "potentiallyOlderThanDisk"), ReferenceJson.Json.Lit("true"))
      assertEquals(field(rendered, "cleanupFailure"), ReferenceJson.Json.Str("CleanupFailed"))
      assertEquals(field(rendered, "peerResourcesFinalized"), ReferenceJson.Json.Lit("false"))
      assert(!lines.exists(record(_) == "node-state"))
    ).unsafeToFuture()
  }

  test("durable cancellation metadata never labels the execution volatile") {
    (Deferred[IO, Unit], Ref.of[IO, Vector[String]](Vector.empty)).tupled
      .flatMap { (entered, logs) =>
        for
          fiber <- NodeCommand
            .withCancellationStatus(
              entered.complete(()).void *> IO.never[Unit],
              line => logs.update(_ :+ line),
              durableRequested = true
            )
            .start
          _ <- entered.get
          _ <- fiber.cancel
          lines <- logs.get
        yield
          assertEquals(lines.size, 1)
          assertEquals(field(lines.head, "durable"), ReferenceJson.Json.Lit("true"))
          assertEquals(field(lines.head, "inMemory"), ReferenceJson.Json.Lit("false"))
          assertEquals(field(lines.head, "stateAvailable"), ReferenceJson.Json.Lit("false"))
      }
      .unsafeToFuture()
  }

  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val directory = Path.of(location)
    def context = get(SequenceInput.load(directory))
    def originals =
      val in = Files.newInputStream(directory.resolve("scala-sequence-capture.md"))
      val bytes =
        try in.readNBytes(20 * 1024 * 1024 + 1)
        finally in.close()
      assert(bytes.length <= 20 * 1024 * 1024)
      get(CoherentSequenceCommand.captures(Bytes.fromArray(bytes)))
    def forwards(os: Vector[Original]) = os.toList.map(o => IO.pure(Event.Forward(o.envelope)))
    def fetch(os: Vector[Original])(p: ChainSync.Point) = IO(os.find(point(_) == p).get.block)
    test(
      "retained durable resume reports ordered offers and acknowledged full rollback before next event"
    ) {
      val c = context; val os = originals.take(2)
      assertEquals(os.size, 2)
      assertEquals(os.map(o => get(SequenceInput.block(o)).transactionMemos.size).sum, 2)
      val base = Files.createTempDirectory("node-intersection-rollback-")
      def args(target: Int, events: Int) =
        durableArgs(base, c).updated(11, target.toString).updated(13, events.toString) ++ List(
          "--audit",
          "true"
        )
      val settings = get(NodeCommand.Config.parse(required ++ args(2, 2)))
      (for
        initialRuntime <- CoherentSequence.create[IO](c).map(get(_))
        initial <- initialRuntime.snapshot
        factory <- NodeCommand.factoryFor(settings, c)
        first <- NodeCommand.execute(
          settings,
          c,
          scripted(forwards(os), anchor(c), fetch(os)),
          _ => IO.unit,
          factory
        )
        a = get(first)
        receipt = a.outcome.snapshot.receipt.get
        resumed = get(
          NodeCommand.Config.parse(
            required ++ args(3, 3)
              .updated(3, "resume")
              .updated(7, base.resolve("receipts-b").toString) ++ List(
              "--resume-receipt",
              receipt.path,
              "--resume-sha256",
              receipt.sha256
            )
          )
        )
        factoryB <- NodeCommand.factoryFor(resumed, c)
        logs <- Ref.of[IO, Vector[String]](Vector.empty)
        nextCount <- Ref.of[IO, Int](0)
        peer = Resource.pure[IO, Peer[IO]](new Peer[IO]:
          def intersect(offered: Vector[ChainSync.Point]) = logs.get.map { lines =>
            assertEquals(offered, a.outcome.snapshot.state.acquisition.candidates)
            assertEquals(
              lines.map(record),
              Vector("node-bootstrap", "node-loaded", "node-intersection-offered")
            )
            assertEquals(
              field(lines(1), "projection"),
              ValidatedRestartCapture.projection(a.outcome.snapshot.state)
            )
            assertEquals(field(lines(1), "confirmation"), ReferenceJson.Json.Str("loaded-verified"))
            assertEquals(field(lines.last, "offeredPoints"), pointsJson(offered))
            anchor(c)
          }
          def next = logs.get.flatMap { lines =>
            IO {
              val rolled = lines.find(record(_) == "node-rollback").get
              assertEquals(field(rolled, "confirmation"), ReferenceJson.Json.Str("acknowledged"))
              assertEquals(
                field(rolled, "confirmedGeneration"),
                ReferenceJson.Json.Num((a.outcome.snapshot.confirmedGeneration.get + 1).toString)
              )
              assertEquals(
                field(rolled, "revision"),
                ReferenceJson.Json.Num((a.outcome.snapshot.state.revision + 2).toString)
              )
              assertEquals(field(rolled, "depth"), ReferenceJson.Json.Num("0"))
              assertEquals(
                field(rolled, "projection"),
                ValidatedRestartCapture.projection(initial.state)
              )
            } *> nextCount.update(_ + 1).as(Event.Await)
          }
          def fetch(p: ChainSync.Point) =
            IO.raiseError[Bytes](new AssertionError("unexpected fetch during rollback-only resume")))
        second <- NodeCommand.execute(resumed, c, peer, line => logs.update(_ :+ line), factoryB)
        lines <- logs.get
        events <- nextCount.get
      yield
        val b = get(second)
        assertEquals(a.outcome.reason, Stop.TargetReached)
        assert(a.outcome.snapshot.state.ledger.fees > initial.state.ledger.fees)
        assertEquals(b.outcome.reason, Stop.EventBudget)
        assertEquals(events, 3)
        assertEquals(b.outcome.snapshot.state.ledger.outputMap, initial.state.ledger.outputMap)
        assertEquals(b.outcome.snapshot.state.ledger.fees, initial.state.ledger.fees)
        assertEquals(b.outcome.snapshot.state.id, initial.state.id)
        assertEquals(
          b.outcome.snapshot.confirmedGeneration,
          a.outcome.snapshot.confirmedGeneration.map(_ + 1)
        )
        assertEquals(b.outcome.snapshot.state.revision, a.outcome.snapshot.state.revision + 2)
        val selected = lines.find(record(_) == "node-intersection-selected").get
        assertEquals(field(selected, "selectedPoint"), pointJson(anchor(c)))
        assertEquals(field(selected, "offeredMatch"), ReferenceJson.Json.Lit("true"))
        acquisitionOnly(selected)
        acquisitionOnly(lines.find(record(_) == "node-intersection-offered").get)
        assertEquals(
          lines.take(5).map(record),
          Vector(
            "node-bootstrap",
            "node-loaded",
            "node-intersection-offered",
            "node-intersection-selected",
            "node-rollback"
          )
        )
      ).unsafeToFuture()
    }
    test(
      "retained durable resume at cumulative target opens no peer and writes no new acknowledgment"
    ) {
      val c = context; val os = originals.take(1)
      val base = Files.createTempDirectory("node-retained-durable-")
      val settings = durableConfig(base, c)
      (for
        factory <- NodeCommand.factoryFor(settings, c)
        first <- NodeCommand.execute(
          settings,
          c,
          scripted(forwards(os), anchor(c), fetch(os)),
          _ => IO.unit,
          factory
        )
        a = get(first)
        receipt = a.outcome.snapshot.receipt.get
        resumed = get(
          NodeCommand.Config.parse(
            required ++ durableArgs(base, c)
              .updated(3, "resume")
              .updated(7, base.resolve("receipts-b").toString) ++ List(
              "--resume-receipt",
              receipt.path,
              "--resume-sha256",
              receipt.sha256
            )
          )
        )
        factoryB <- NodeCommand.factoryFor(resumed, c)
        second <- NodeCommand.execute(resumed, c, noPeer, _ => IO.unit, factoryB)
      yield
        val b = get(second)
        assertEquals(a.outcome.reason, Stop.TargetReached)
        assertEquals(b.outcome.reason, Stop.TargetReached)
        assertEquals(b.peerOpens, 0)
        assertEquals(b.outcome.snapshot.confirmation, NodeCommand.Confirmation.LoadedVerified)
        assertEquals(b.outcome.snapshot.receipt, Some(receipt))
        assertEquals(b.outcome.snapshot.confirmedGeneration, a.outcome.snapshot.confirmedGeneration)
        assertEquals(
          ValidatedRestartCapture.projection(b.outcome.snapshot.state),
          ValidatedRestartCapture.projection(a.outcome.snapshot.state)
        )
        assert(!Files.exists(base.resolve("receipts-b")))
      ).unsafeToFuture()
    }
    test(
      "retained node publishes source-bound originals with separate download cursors and no oracle"
    ) {
      val c = context; val os = originals
      Ref
        .of[IO, Vector[String]](Vector.empty)
        .flatMap { logs =>
          for
            result <- NodeCommand.execute(
              config("--blocks", os.size.toString),
              c,
              scripted(forwards(os), anchor(c), fetch(os)),
              s => logs.update(_ :+ s)
            )
            lines <- logs.get
          yield
            val report = get(result)
            assertEquals(report.outcome.reason, Stop.TargetReached)
            assertEquals(NodeCommand.exitCode(report).code, 0)
            assertEquals(report.outcome.snapshot.state.acquisition.originals, os)
            assertEquals(report.outcome.snapshot.state.revision, BigInt(os.size))
            val downloads = lines.filter(s => record(s) == "node-download")
            val applied = lines.filter(s => record(s) == "node-applied")
            assertEquals(downloads.size, os.size * 2)
            assertEquals(applied.size, os.size)
            downloads.grouped(2).zipWithIndex.foreach { (pair, i) =>
              assertEquals(
                pair.map(s => field(s, "phase")),
                Vector(ReferenceJson.Json.Str("announced"), ReferenceJson.Json.Str("fetched"))
              )
              pair.foreach { line =>
                assertEquals(field(line, "revision"), ReferenceJson.Json.Num(i.toString))
                assertEquals(field(line, "downloadIsApplied"), ReferenceJson.Json.Lit("false"))
                if i == 0 then
                  assertEquals(field(line, "scopedAppliedTip"), ReferenceJson.Json.Lit("null"))
                else
                  assertEquals(
                    field(line, "scopedAppliedTip"),
                    field(applied(i - 1), "scopedAppliedTip")
                  )
              }
            }
            assertEquals(
              field(NodeCommand.render(report), "status"),
              ReferenceJson.Json.Str("scoped-target-reached")
            )
            assertEquals(
              field(NodeCommand.render(report), "caughtUp"),
              ReferenceJson.Json.Lit("false")
            )
            assertEquals(report.peerOpens, report.peerCloses)
        }
        .unsafeToFuture()
    }
    test(
      "retained sustained audit retains every fetched original across actual window compaction"
    ) {
      val c = context; val os = originals
      val settings = config(
        "--mode",
        "sustained-volatile",
        "--rollback-capacity",
        "1",
        "--blocks",
        "12",
        "--audit",
        "true"
      )
      Ref
        .of[IO, Vector[String]](Vector.empty)
        .flatMap { logs =>
          for
            result <- NodeCommand.execute(
              settings,
              c,
              scripted(forwards(os), anchor(c), fetch(os)),
              line => logs.update(_ :+ line)
            )
            lines <- logs.get
          yield
            val report = get(result)
            // Real retained originals are finite; exhausting this script must not claim target12.
            assert(report.outcome.reason.isInstanceOf[Stop.PeerFailure])
            assertEquals(report.outcome.snapshot.state.depth, BigInt(os.size))
            assertEquals(report.outcome.snapshot.state.compactedBlocks, BigInt(os.size - 1))
            assertEquals(report.outcome.snapshot.state.acquisition.size, 1)
            assert(report.outcome.snapshot.state.derivedAnchorId.nonEmpty)
            val captures = lines.filter(line => record(line) == "transfer-range-block")
            assertEquals(get(CoherentSequenceCommand.captures(raw(captures.mkString("\n")))), os)
            captures.foreach { line =>
              assertEquals(field(line, "acquisitionOnly"), ReferenceJson.Json.Lit("true"))
              assertEquals(field(line, "appliedClaim"), ReferenceJson.Json.Lit("false"))
            }
            val applied = lines.filter(line => record(line) == "node-applied")
            applied.zip(os).foreach { (line, original) =>
              assertEquals(
                field(line, "transactionCount"),
                ReferenceJson.Json
                  .Num(get(SequenceInput.block(original)).transactionMemos.size.toString)
              )
            }
            assertEquals(lines.count(line => record(line) == "node-anchor-advance"), os.size - 1)
            assertEquals(record(lines.last), "node-state")
            assertEquals(
              field(lines.last, "projection"),
              ValidatedRestartCapture.projection(report.outcome.snapshot.state)
            )
            assertEquals(field(lines.last, "depth"), ReferenceJson.Json.Str(os.size.toString))
            assertEquals(
              field(NodeCommand.render(report), "depth"),
              ReferenceJson.Json.Num(os.size.toString)
            )
            assertEquals(
              field(NodeCommand.render(report), "scopedTargetReached"),
              ReferenceJson.Json.Lit("false")
            )
        }
        .unsafeToFuture()
    }
    test("audit fetch refuses a point different from the original announced header") {
      val c = context; val os = originals
      Ref
        .of[IO, Int](0)
        .flatMap { fetched =>
          val factory = new NodeCommand.EngineFactory:
            def resource(
                ctx: SequenceInput.Context,
                p: Resource[IO, Peer[IO]],
                policy: Policy,
                hook: String => IO[Unit]
            ) =
              Resource.eval(CoherentSequence.create[IO](ctx).map(get(_))).map { runtime =>
                new NodeCommand.Engine:
                  def snapshot = runtime.snapshot.map(s => NodeCommand.EngineView(s.state))
                  def run = p.use { peer =>
                    peer.intersect(Vector(anchor(ctx))) *> peer.next *> peer.fetch(anchor(ctx)) *>
                      snapshot.map(s =>
                        NodeCommand.EngineOutcome(
                          s,
                          NodeCommand.Ending.Completed(Stop.EventBudget),
                          1,
                          0L,
                          0
                        )
                      )
                  }
              }
          val source =
            scripted(forwards(os.take(1)), anchor(c), _ => fetched.update(_ + 1).as(os.head.block))
          for
            result <- NodeCommand.execute(
              config("--audit", "true"),
              c,
              source,
              _ => IO.unit,
              factory
            )
            count <- fetched.get
          yield
            result match
              case Left(NodeCommand.Failure("fetch", "Rejected", _, _, _, _, _, _)) => ()
              case other => fail(other.toString)
            assertEquals(count, 0)
        }
        .unsafeToFuture()
    }
    test("retained rollback trace restores scoped anchor before reapplying the same originals") {
      val c = context; val os = originals
      val script = forwards(os.take(1)) ++ List(IO.pure(Event.Backward(anchor(c)))) ++ forwards(os)
      Ref
        .of[IO, Vector[String]](Vector.empty)
        .flatMap { logs =>
          for
            result <- NodeCommand.execute(
              config("--blocks", os.size.toString),
              c,
              scripted(script, anchor(c), fetch(os)),
              s => logs.update(_ :+ s)
            )
            lines <- logs.get
          yield
            val report = get(result)
            val rolled = lines.filter(s => record(s) == "node-rollback")
            assertEquals(rolled.size, 2)
            assertEquals(field(rolled.last, "initialIntersection"), ReferenceJson.Json.Lit("false"))
            assertEquals(field(rolled.last, "scopedAppliedTip"), ReferenceJson.Json.Lit("null"))
            assertEquals(field(rolled.last, "revision"), ReferenceJson.Json.Num("2"))
            assertEquals(report.outcome.snapshot.state.acquisition.originals, os)
            assertEquals(report.outcome.snapshot.state.revision, BigInt(os.size + 2))
        }
        .unsafeToFuture()
    }
  }
