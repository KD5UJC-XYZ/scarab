// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.ChainSync
import BoundedChainFollower.{Event, Original, Peer}
import BoundedValidatorRunner.Stop

class NodeV2CommandSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def hash(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def hex(s: String): Bytes = get(Bytes.fromHex(s))
  private def fields(line: String) =
    ReferenceJson.parse(raw(line)).asInstanceOf[ReferenceJson.Json.Obj].fields
  private def field(line: String, name: String) = fields(line)(name)
  private def record(line: String) =
    fields(line).get("record").map(ReferenceJson.string).getOrElse("")
  private def args(base: Path, action: String = "resume") = List(
    "--profile",
    NodeCommand.Profile,
    "--bootstrap",
    "/explicit-bootstrap",
    "--port",
    "3001",
    "--mode",
    "sustained-durable",
    "--blocks",
    "9",
    "--rollback-capacity",
    "2",
    "--store-action",
    action,
    "--store",
    base.resolve("store").toString,
    "--journal",
    base.resolve("journal").toString,
    "--receipts",
    base.resolve("receipts").toString,
    "--store-id",
    "11" * 32,
    "--expected-context",
    "22" * 32
  )
  private def update(a: List[String], key: String, value: String) =
    a.updated(a.indexOf(key) + 1, value)
  private val seedFlags = List(
    "--seed-capture",
    "/explicit-seed.md",
    "--seed-sha256",
    "33" * 32,
    "--compact-slot",
    "23",
    "--compact-hash",
    "44" * 32
  )
  private val noPeer =
    Resource.eval(IO.raiseError[Peer[IO]](new AssertionError("unexpected peer acquisition")))

  test("v2 create and resume grammar is explicit and maps sustained cumulative bounds") {
    val base = Path.of("/explicit-v2")
    val resume = get(NodeCommand.Config.parse(args(base)))
    assert(resume.durable && resume.policy.advanceWindow)
    assertEquals(resume.rollbackCapacity, 2)
    assertEquals(resume.v2.get.seedCapture, None)
    assertEquals(NodeCommand.v2Binding(resume).launchPolicy, ControllerJournalCodec.LaunchPolicy)
    val create = get(NodeCommand.Config.parse(args(base, "create") ++ seedFlags))
    assert(create.v2.get.compactThrough.nonEmpty)
    assertEquals(create.resumeReceipt, None)
    val high = update(
      update(update(args(base), "--blocks", "256"), "--rollback-capacity", "8"),
      "--store-action",
      "resume"
    ) ++ List("--events", "256", "--seconds", "120", "--bytes", "67108864")
    assert(get(NodeCommand.Config.parse(high)).policy.valid)
  }
  test("v2 rejects absent authority seed fallback v1 receipts and intersecting storage paths") {
    val base = Path.of("/explicit-v2")
    val a = args(base)
    Vector(
      a.filterNot(Set("--journal", base.resolve("journal").toString)),
      a.filterNot(Set("--store-id", "11" * 32)),
      a ++ seedFlags,
      args(base, "create"),
      a ++ List("--resume-receipt", "/old-v1.json", "--resume-sha256", "12" * 32),
      update(a, "--journal", base.resolve("store/journal").toString),
      update(a, "--receipts", base.resolve("journal/receipts").toString),
      update(a, "--journal", "/explicit-v2/../journal"),
      update(a, "--store-id", "AA" * 32),
      update(a, "--blocks", "8"),
      update(a, "--rollback-capacity", "9"),
      args(base, "create") ++ update(
        seedFlags,
        "--seed-capture",
        base.resolve("receipts/seed.md").toString
      ),
      args(base, "create") ++ update(seedFlags, "--compact-slot", "18446744073709551616"),
      args(base, "create") ++ update(seedFlags, "--compact-slot", "01"),
      update(a, "--mode", "bounded-durable"),
      update(a, "--mode", "sustained-volatile")
    )
      .foreach(v => assert(NodeCommand.Config.parse(v).isLeft, v.toString))
  }
  test("fence CLI bounds reject partial flags, wrong phase, range and reconnects") {
    val base = Path.of("/explicit-v2")
    val flags = List(
      "--completion-fence",
      "/explicit-fence",
      "--fence-id",
      "a" * 64,
      "--fence-phase",
      "A",
      "--minimum-depth",
      "9"
    )
    val a = args(base, "create") ++ seedFlags ++ flags
    assert(NodeCommand.Config.parse(a).isRight)
    Vector(
      args(base, "create") ++ seedFlags ++ List("--fence-id", "a" * 64),
      update(a, "--completion-fence", "relative"),
      update(a, "--fence-id", "A" * 64),
      update(a, "--fence-phase", "B"),
      update(a, "--minimum-depth", "8"),
      update(a, "--minimum-depth", "10"),
      update(a, "--blocks", "13"),
      a ++ List("--events", "129"),
      a ++ List("--bytes", "33554433"),
      a ++ List("--reconnects", "1")
    ).foreach(v => assert(NodeCommand.Config.parse(v).isLeft, v.toString))
    val b = update(args(base), "--blocks", "16") ++ update(
      update(flags, "--fence-phase", "B"),
      "--minimum-depth",
      "12"
    )
    assert(NodeCommand.Config.parse(b).isRight)
    assert(NodeCommand.Config.parse(update(b, "--blocks", "17")).isLeft)
    assert(NodeCommand.Config.parse(update(b, "--minimum-depth", "11")).isLeft)
  }
  private val claim = LocalDerivedCheckpoint.Claim(
    LocalDerivedCheckpoint.Token(hex("11" * 32), hex("22" * 32), hex("33" * 32), 7, hex("44" * 32)),
    LocalDerivedCheckpoint.Format,
    NodeCommand.Profile,
    LocalDerivedCheckpoint.Authority,
    hex("55" * 32),
    hex("66" * 32),
    2,
    4
  )
  test("v2 diagnostic receipt binds full claim and binding without masquerading as v1 authority") {
    val config = get(NodeCommand.Config.parse(args(Path.of("/explicit-v2"))))
    val binding = NodeCommand.v2Binding(config)
    val bytes = NodeV2Receipts.bytes(claim, binding, 2)
    val json = fields(new String(bytes.toArray, "UTF-8"))
    assertEquals(json.keySet, Set("format", "diagnosticOnly", "capacity", "binding", "claim"))
    assertEquals(json("diagnosticOnly"), ReferenceJson.Json.Lit("true"))
    assertEquals(json("claim"), NodeV2Receipts.claimJson(claim))
    assertEquals(json("binding"), NodeV2Receipts.bindingJson(binding))
    assert(NodeDurableReceipts.parse(bytes, hash(bytes), claim.token.contextId).isLeft)
    assert(
      hash(bytes) != hash(NodeV2Receipts.bytes(claim.copy(anchorId = hex("77" * 32)), binding, 2))
    )
    assert(
      hash(bytes) != hash(
        NodeV2Receipts.bytes(
          claim.copy(token = claim.token.copy(sessionId = hex("88" * 32))),
          binding,
          2
        )
      )
    )
  }
  test("v2 immutable receipt retries preserve identity and reject conflicting existing bytes") {
    val base = Files.createTempDirectory("node-v2-receipts-")
    val binding = NodeCommand.v2Binding(get(NodeCommand.Config.parse(args(base))))
    (for
      first <- NodeV2Receipts.record(base.resolve("receipts"), binding, 2, claim)
      repeated <- NodeV2Receipts.record(base.resolve("receipts"), binding, 2, claim)
      _ <- IO(Files.writeString(first.path, "conflicting evidence"))
      rejected <- NodeV2Receipts.record(base.resolve("receipts"), binding, 2, claim).attempt
    yield
      assertEquals(first, repeated)
      assert(rejected.isLeft)
      assertEquals(Files.readString(first.path), "conflicting evidence")
    ).unsafeToFuture()
  }

  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def context = get(SequenceInput.load(dir))
    def originals = get(
      CoherentSequenceCommand
        .captures(Bytes.fromArray(Files.readAllBytes(dir.resolve("scala-sequence-capture.md"))), 12)
    )
    def point(o: Original) =
      val b = get(SequenceInput.block(o))
      ChainSync.Point.Block(get(ChainSync.UInt64.from(b.header.slot)), b.header.hash)
    def seedFile(base: Path, os: Vector[Original]): Path =
      val bytes = raw(
        os.map(o =>
          s"""{"record":"transfer-range-block","headerEnvelopeHex":"${o.envelope.hex}","rawBlockHex":"${o.block.hex}"}"""
        ).mkString("\n") + "\n"
      )
      val path = base.resolve("seed.md"); Files.write(path, bytes.toArray); path
    def createConfig(
        base: Path,
        c: SequenceInput.Context,
        os: Vector[Original],
        target: Int = 9,
        extra: List[String] = Nil
    ) =
      val seed = seedFile(base, os)
      val p = point(os.head)
      get(
        NodeCommand.Config.parse(
          update(
            update(args(base, "create"), "--expected-context", c.id.hex),
            "--blocks",
            target.toString
          ) ++ List(
            "--seed-capture",
            seed.toString,
            "--seed-sha256",
            hash(Bytes.fromArray(Files.readAllBytes(seed))).hex,
            "--compact-slot",
            get(SequenceInput.block(os.head)).header.slot.toString,
            "--compact-hash",
            get(SequenceInput.block(os.head)).header.hash.hex,
            "--audit",
            "true"
          ) ++ extra
        )
      )
    def peer(os: Vector[Original], intersection: ChainSync.Point): Resource[IO, Peer[IO]] =
      Resource.eval(Ref.of[IO, List[Original]](os.toList)).map { queue =>
        new Peer[IO]:
          def intersect(offered: Vector[ChainSync.Point]) = IO.pure(intersection)
          def next = queue.modify {
            case h :: tail => (tail, IO.pure(Event.Forward(h.envelope)))
            case Nil => (Nil, IO.raiseError[Event](new IllegalStateException("script exhausted")))
          }.flatten
          def fetch(p: ChainSync.Point) = IO(os.find(point(_) == p).get.block)
      }
    test(
      "retained v2 create compacts and resumes exact journal claim without new acknowledgment export"
    ) {
      val c = context; val os = originals
      assert(os.size >= 9)
      val base = Files.createTempDirectory("node-v2-retained-")
      val config = createConfig(base, c, os.take(2))
      (for
        factory <- NodeCommand.factoryFor(config, c)
        logs <- Ref.of[IO, Vector[String]](Vector.empty)
        first <- NodeCommand.execute(
          config,
          c,
          peer(os.slice(2, 9), point(os(1))),
          line => logs.update(_ :+ line),
          factory
        )
        a = get(first)
        lines <- logs.get
        resumed = get(
          NodeCommand.Config.parse(
            update(
              update(args(base), "--expected-context", c.id.hex),
              "--receipts",
              base.resolve("resume-receipts").toString
            ) ++ List("--audit", "true")
          )
        )
        factoryB <- NodeCommand.factoryFor(resumed, c)
        loadedLogs <- Ref.of[IO, Vector[String]](Vector.empty)
        second <- NodeCommand.execute(
          resumed,
          c,
          noPeer,
          line => loadedLogs.update(_ :+ line),
          factoryB
        )
        loaded <- loadedLogs.get
      yield
        val b = get(second)
        assertEquals(a.outcome.reason, Stop.TargetReached)
        assertEquals(a.outcome.snapshot.state.depth, BigInt(9))
        assertEquals(a.outcome.snapshot.state.acquisition.size, 2)
        assertEquals(a.outcome.snapshot.state.compactedBlocks, BigInt(7))
        assertEquals(a.outcome.snapshot.confirmation, NodeCommand.Confirmation.V2Acknowledged)
        assertEquals(b.outcome.snapshot.confirmation, NodeCommand.Confirmation.V2LoadedVerified)
        assertEquals(b.outcome.snapshot.fullClaim, a.outcome.snapshot.fullClaim)
        assertEquals(b.outcome.snapshot.receipt, None)
        assertEquals(b.peerOpens, 0)
        assert(!Files.exists(base.resolve("resume-receipts")))
        assertEquals(
          ValidatedRestartCapture.projection(b.outcome.snapshot.state),
          ValidatedRestartCapture.projection(a.outcome.snapshot.state)
        )
        val supplied = ReferenceJson.parse(
          raw(
            s"""{"hash":"${c.certificateSeed.tip.hash.hex}","slot":${c.certificateSeed.tip.slot}}"""
          )
        )
        Vector(lines.head, loaded.head).foreach { bootstrap =>
          assertEquals(record(bootstrap), "node-bootstrap")
          assertEquals(field(bootstrap, "suppliedAnchor"), supplied)
          assert(field(bootstrap, "retainedAnchor") != supplied)
        }
        assertEquals(
          field(lines.head, "retainedAnchor"),
          ReferenceJson.parse(raw(s"""{"hash":"${get(
              SequenceInput.block(os.head)
            ).header.hash.hex}","slot":${get(SequenceInput.block(os.head)).header.slot}}"""))
        )
        val resumedAnchor = a.outcome.snapshot.state.acquisition.anchor match
          case ChainSync.Point.Block(slot, hash) =>
            ReferenceJson.parse(raw(s"""{"hash":"${hash.hex}","slot":${slot.value}}"""))
          case _ => fail("retained v2 anchor must be a block")
        assertEquals(field(loaded.head, "retainedAnchor"), resumedAnchor)
        val loadedRow = loaded.find(record(_) == "node-loaded").get
        assertEquals(
          field(loadedRow, "fullClaim"),
          NodeV2Receipts.claimJson(a.outcome.snapshot.fullClaim.get)
        )
        assertEquals(
          field(loadedRow, "projection"),
          ValidatedRestartCapture.projection(a.outcome.snapshot.state)
        )
        val transitions =
          lines.filter(l => Set("node-bootstrap", "node-applied", "node-anchor-advance")(record(l)))
        transitions.sliding(2).foreach { pair =>
          if pair.size == 2 && record(pair(1)) == "node-anchor-advance" then
            assertEquals(field(pair(1), "revision"), field(pair.head, "revision"))
            assert(
              ReferenceJson.string(field(pair(1), "stateId")) != ReferenceJson
                .string(field(pair.head, "stateId"))
            )
            assertEquals(
              ReferenceJson.uint(field(pair(1), "confirmedGeneration")),
              ReferenceJson.uint(field(pair.head, "confirmedGeneration")) + 1
            )
        }
        assertEquals(lines.count(record(_) == "node-anchor-advance"), 6)
      ).unsafeToFuture()
    }
    test(
      "retained singleton seed supports capacity one without widening sequence observer grammar"
    ) {
      val c = context; val os = originals.take(1)
      val base = Files.createTempDirectory("node-v2-singleton-")
      val path = seedFile(base, os)
      val p = point(os.head)
      val config = get(
        NodeCommand.Config.parse(
          update(
            update(args(base, "create"), "--expected-context", c.id.hex),
            "--rollback-capacity",
            "1"
          ) ++ List(
            "--seed-capture",
            path.toString,
            "--seed-sha256",
            hash(Bytes.fromArray(Files.readAllBytes(path))).hex,
            "--compact-slot",
            get(SequenceInput.block(os.head)).header.slot.toString,
            "--compact-hash",
            get(SequenceInput.block(os.head)).header.hash.hex
          )
        )
      )
      (for
        seed <- NodeV2Receipts.seed(path, hash(Bytes.fromArray(Files.readAllBytes(path))), c, p)
        factory <- NodeCommand.factoryFor(config, c)
        result <- NodeCommand.execute(config, c, peer(Vector.empty, p), _ => IO.unit, factory)
      yield
        assertEquals(seed.originals, os)
        assertEquals(seed.compactThrough, p)
        assert(CoherentSequenceCommand.captures(Bytes.fromArray(Files.readAllBytes(path))).isLeft)
        val out = get(result).outcome
        assert(out.reason.isInstanceOf[Stop.PeerFailure])
        assertEquals(out.snapshot.state.depth, BigInt(1))
        assertEquals(out.snapshot.state.compactedBlocks, BigInt(1))
        assertEquals(out.snapshot.state.acquisition.size, 0)
        assertEquals(out.snapshot.confirmation, NodeCommand.Confirmation.V2Acknowledged)
      ).unsafeToFuture()
    }
    test("retained seed pin and compact-point mismatch reject before stores or peers") {
      val c = context; val os = originals.take(2)
      val base = Files.createTempDirectory("node-v2-seed-reject-")
      val config = createConfig(base, c, os)
      (for
        wrongPin <- NodeV2Receipts
          .seed(config.v2.get.seedCapture.get, hex("99" * 32), c, point(os.head))
          .attempt
        wrongPoint <- NodeV2Receipts
          .seed(
            config.v2.get.seedCapture.get,
            config.v2.get.seedSha256.get,
            c,
            ChainSync.Point.Block(get(ChainSync.UInt64.from(1)), hex("99" * 32))
          )
          .attempt
      yield
        assert(wrongPin.isLeft && wrongPoint.isLeft)
        assert(!Files.exists(config.store.get))
        assert(!Files.exists(config.v2.get.journal))
      ).unsafeToFuture()
    }
    test("retained CLI rejects seed pin before acquiring journal checkpoint or peer") {
      val c = context; val os = originals.take(1)
      val base = Files.createTempDirectory("node-v2-cli-seed-rejection-")
      val path = seedFile(base, os)
      val header = get(SequenceInput.block(os.head)).header
      val command = update(
        update(args(base, "create"), "--bootstrap", dir.toString),
        "--expected-context",
        c.id.hex
      ) ++ List(
        "--seed-capture",
        path.toString,
        "--seed-sha256",
        "99" * 32,
        "--compact-slot",
        header.slot.toString,
        "--compact-hash",
        header.hash.hex
      )
      NodeCommand
        .run(command)
        .map { exit =>
          assertEquals(exit.code, 2)
          assert(!Files.exists(base.resolve("store")))
          assert(!Files.exists(base.resolve("journal")))
          assert(!Files.exists(base.resolve("receipts")))
        }
        .unsafeToFuture()
    }
    test("retained v2 acknowledgment export failure preserves actual claim and never opens peer") {
      val c = context; val base = Files.createTempDirectory("node-v2-export-failure-")
      val config = createConfig(base, c, originals.take(2))
      (for
        factory <- NodeCommand.v2FactoryFor(
          config,
          c,
          Some(_ => IO.raiseError(new IllegalStateException("export failed")))
        )
        result <- NodeCommand.execute(config, c, noPeer, _ => IO.unit, factory)
      yield
        val failure = result.swap.toOption.get
        assertEquals(failure.kind, "ReceiptFailure")
        assert(failure.externalReceiptStale && !failure.potentiallyOlderThanDisk)
        assertEquals(
          failure.lastConfirmed.get.confirmation,
          NodeCommand.Confirmation.V2Acknowledged
        )
        assert(failure.lastConfirmed.get.fullClaim.nonEmpty)
        assertEquals(failure.lastConfirmed.get.receipt, None)
      ).unsafeToFuture()
    }
    test("fenced A and B ACK only after close, bind exact state and preserve loaded prefix") {
      val c = context; val os = originals
      val base = Files.createTempDirectory("node-v2-fenced-").toRealPath()
      def flags(phase: String, minimum: Int) = List(
        "--completion-fence",
        base.resolve(s"fence-$phase").toString,
        "--fence-id",
        (if phase == "A" then "a" else "b") * 64,
        "--fence-phase",
        phase,
        "--minimum-depth",
        minimum.toString
      )
      def publish(phase: String, depth: Int) = IO.blocking {
        val h = get(ReferenceCaptureCommand.header(os(depth - 1).envelope))
        val id = (if phase == "A" then "a" else "b") * 64
        val text =
          s"version=live-completion-fence-v1\nfenceId=$id\nphase=$phase\ncontextId=${c.id.hex}\ndepth=$depth\nblockNo=${h.blockNo}\nslot=${h.slot}\nhash=${h.hash.hex}\n"
        val tmp = base.resolve(s"staged-$phase")
        Files.writeString(tmp, text)
        Files.move(tmp, base.resolve(s"fence-$phase"), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        ()
      }
      val aConfig = createConfig(base, c, os.take(2), 12, flags("A", 9))
      val bConfig = get(
        NodeCommand.Config.parse(
          update(update(args(base), "--expected-context", c.id.hex), "--blocks", "16") ++ flags(
            "B",
            12
          ) ++ List("--audit", "true")
        )
      )
      def run(config: NodeCommand.Config, phase: String, from: Int, depth: Int) =
        for
          logs <- Ref.of[IO, Vector[String]](Vector.empty)
          closed <- Ref.of[IO, Boolean](false)
          factory <- NodeCommand.factoryFor(config, c)
          owned = Resource
            .make(IO.unit)(_ => closed.set(true))
            .flatMap(_ => peer(os.drop(from), point(os(from - 1))))
          result <- NodeCommand.execute(
            config,
            c,
            owned,
            line =>
              logs.update(_ :+ line) *>
                (if record(line) == "node-fence-ready" then publish(phase, depth)
                 else if record(line) == "node-fence-ack" then
                   closed.get.flatMap(v => IO(assert(v))) *>
                     ValidatorTransitions
                       .combinedResume[IO](
                         CombinedLocalV2.Config(
                           NodeCommand.v2Binding(config),
                           2,
                           scala.concurrent.duration.DurationInt(20).seconds
                         )
                       )
                       .use { backend =>
                         backend.snapshot
                           .flatMap(value => IO(assertEquals(value.state.depth, BigInt(depth))))
                       }
                 else IO.unit),
            factory
          )
          lines <- logs.get
        yield (get(result), lines)
      (for
        a <- run(aConfig, "A", 2, 9)
        b <- run(bConfig, "B", 9, 12)
      yield
        assertEquals(a._1.outcome.reason, Stop.TargetReached)
        assertEquals(b._1.outcome.reason, Stop.TargetReached)
        assertEquals(a._1.outcome.snapshot.state.depth, BigInt(9))
        assertEquals(b._1.outcome.snapshot.state.depth, BigInt(12))
        assertEquals(
          field(b._2.find(record(_) == "node-loaded").get, "fullClaim"),
          field(a._2.find(record(_) == "node-fence-ack").get, "fullClaim")
        )
        Vector(a, b).foreach { (report, lines) =>
          assertEquals(lines.count(record(_) == "node-fence-ready"), 1)
          assertEquals(lines.count(record(_) == "node-fence-ack"), 1)
          assertEquals(record(lines.last), "node-fence-ack")
          val ack = lines.last
          val projection = ValidatedRestartCapture.canonical(
            ValidatedRestartCapture.projection(report.outcome.snapshot.state)
          )
          assertEquals(
            ReferenceJson.string(field(ack, "projectionSha256")),
            CompletionFence.sha256(projection.getBytes("UTF-8"))
          )
        }
      ).unsafeToFuture()
    }

  }
