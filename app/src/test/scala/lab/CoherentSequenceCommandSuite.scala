// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}

class CoherentSequenceCommandSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def text(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def n(v: V) = Node(v, Bytes.empty)
  private def arr(v: V*) = V.Arr(v.toVector.map(n))
  private def map(v: (V, V)*) = V.Map(v.toVector.map((a, b) => n(a) -> n(b)))
  private def encode(v: V): Bytes = get(Cbor.encode(v))
  private def zero(size: Int) = V.ByteString(Bytes(Vector.fill(size)(0.toByte)))
  private def manifest(files: Map[String, Bytes]): Bytes = text(
    "format\tcoherent-sequence-oracle-v1\n" + CoherentSequenceCommand.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private def opaque =
    CoherentSequenceCommand.sources.values.map(_ -> text("opaque owned bytes")).toMap
  // Structural-only fixtures: these zero-signature headers are never passed to a coordinator.
  private def tx(number: Int, witness: Int = 0): Bytes = encode(
    arr(
      map(V.UInt(2) -> V.UInt(number)),
      map(V.UInt(0) -> arr(V.UInt(witness))),
      V.Bool(true),
      V.Null
    )
  )
  private def block(transactions: Vector[Bytes]): SequenceInput.Block =
    val header = encode(
      arr(
        arr(
          V.UInt(1),
          V.UInt(20),
          zero(32),
          zero(32),
          zero(32),
          arr(zero(64), zero(80)),
          V.UInt(0),
          zero(32),
          arr(zero(32), V.UInt(0), V.UInt(0), zero(64)),
          arr(V.UInt(11), V.UInt(2))
        ),
        zero(448)
      )
    )
    val envelope = encode(arr(V.UInt(6), V.Tag(24, n(V.ByteString(header)))))
    val parts = transactions.map(t => get(Cbor.decode(t)).value.asInstanceOf[V.Arr].value)
    val raw = encode(
      arr(
        V.UInt(7),
        arr(
          get(Cbor.decode(header)).value,
          V.Arr(parts.map(_.head)),
          V.Arr(parts.map(_(1))),
          map(),
          arr()
        )
      )
    )
    get(SequenceInput.block(BoundedChainFollower.Original(envelope, raw)))
  private def row(original: BoundedChainFollower.Original): String =
    s"""{"record":"transfer-range-block","headerEnvelopeHex":"${original.envelope.hex}","rawBlockHex":"${original.block.hex}"}"""
  test("oracle ownership pins exact eight files without parsing post-state") {
    val b = get(CoherentSequenceCommand.bindOracle(manifest(opaque), opaque))
    assertEquals(b.originals, opaque)
    assertEquals(b.manifestDigest, ClusterHeaderObservation.sha256(manifest(opaque)))
  }
  test("success report and oracle ownership cannot be manufactured or copied") {
    assert(compileErrors("val r: lab.CoherentSequenceCommand.Report = null; r.copy()").nonEmpty)
    assert(
      compileErrors(
        "new lab.CoherentSequenceCommand.OwnedOracle(Map.empty, Map.empty, null)"
      ).nonEmpty
    )
  }
  test("strict manifest fields format duplicates encoding and source pins reject") {
    val m = new String(manifest(opaque).toArray, "UTF-8")
    Vector(
      text(m + "format\tcoherent-sequence-oracle-v1\n"),
      text(m + "extra\t00\n"),
      text(m.replace("coherent-sequence-oracle-v1", "coherent-branch-oracle-v1")),
      text(m.linesIterator.filterNot(_.startsWith("captureSha256")).mkString("\n")),
      Bytes(Vector(0xc3.toByte, 0x28.toByte)),
      text("x" * 8193)
    )
      .foreach(b => assert(CoherentSequenceCommand.bindOracle(b, opaque).isLeft))
    opaque.keys.foreach(name =>
      assert(
        CoherentSequenceCommand
          .bindOracle(manifest(opaque), opaque.updated(name, text("tampered")))
          .isLeft
      )
    )
    assert(CoherentSequenceCommand.bindOracle(manifest(opaque), opaque - "post-tips.md").isLeft)
  }
  test("capture ownership has a separate bound exceeding four MiB but bounded by twenty MiB") {
    val large = opaque.updated("scala-sequence-capture.md", text("x" * 4194305))
    assert(CoherentSequenceCommand.bindOracle(manifest(large), large).isRight)
    val wrong = opaque.updated("post-ledger-state.md", text("x" * 4194305))
    assert(CoherentSequenceCommand.bindOracle(manifest(wrong), wrong).isLeft)
    val txTooLarge = opaque.updated("signed-transaction-0-cbor.md", text("x" * 131075))
    assert(CoherentSequenceCommand.bindOracle(manifest(txTooLarge), txTooLarge).isLeft)
  }
  test("capture records preserve order and reject counts outside two through eight") {
    val a = block(Vector.empty).original
    val b = block(Vector(tx(1))).original
    assertEquals(get(CoherentSequenceCommand.captures(text(row(a) + "\n" + row(b)))), Vector(a, b))
    assert(CoherentSequenceCommand.captures(text(row(a))).isLeft)
    assert(CoherentSequenceCommand.captures(text(Vector.fill(9)(row(a)).mkString("\n"))).isLeft)
    assert(CoherentSequenceCommand.captures(text(Vector.fill(1025)("{}").mkString("\n"))).isLeft)
  }
  test("structural grouping binds exact body and witness pairs in actual chain order") {
    val a = tx(1); val b = tx(2)
    val result = get(
      CoherentSequenceCommand.checkGrouping(
        Vector(block(Vector.empty), block(Vector(b, a))),
        Vector(text(a.hex), text(b.hex))
      )
    )
    assertEquals(result.submissionOrder, Vector(1, 0))
    assertEquals(result.blockIndex, 1)
    assertEquals(result.emptyBlocks, 1)
    assertEquals(
      result.transactionIds,
      Vector(get(TransactionId.fromEnvelope(b)), get(TransactionId.fromEnvelope(a)))
    )
  }
  test("grouping compares spans without asserting submitted envelope byte equality") {
    val a = tx(1); val b = tx(2)
    val indefinite = Bytes(Vector(0x9f.toByte) ++ a.value.tail ++ Vector(0xff.toByte))
    assert(indefinite != a)
    assert(
      CoherentSequenceCommand
        .checkGrouping(
          Vector(block(Vector(a, b)), block(Vector.empty)),
          Vector(text(indefinite.hex), text(b.hex))
        )
        .isRight
    )
  }
  test("split extra duplicated missing or mismatched witness transactions reject") {
    val a = tx(1); val b = tx(2); val expected = Vector(text(a.hex), text(b.hex))
    Vector(
      Vector(block(Vector(a)), block(Vector(b))),
      Vector(block(Vector(a, b))),
      Vector(block(Vector(a, a)), block(Vector.empty)),
      Vector(block(Vector(a, b, tx(3))), block(Vector.empty)),
      Vector(block(Vector(tx(1, 7), b)), block(Vector.empty))
    ).foreach(bs => assert(CoherentSequenceCommand.checkGrouping(bs, expected).isLeft))
  }
  test("post endpoint brackets bind hash slot block epoch and Conway era") {
    val tip = s"""{"era":"Conway","hash":"${"12" * 32}","slot":20,"block":3,"epoch":0}"""
    val parsed = get(CoherentSequenceCommand.endpoint(text(s"[$tip,$tip]")))
    assertEquals(parsed._1.slot, BigInt(20))
    assertEquals(parsed._1.blockNo, BigInt(3))
    assert(CoherentSequenceCommand.endpoint(text(s"[$tip]")).isLeft)
    assert(
      CoherentSequenceCommand
        .endpoint(text(s"[$tip,${tip.replace("\"block\":3", "\"block\":4")}]"))
        .isLeft
    )
    assert(
      CoherentSequenceCommand
        .endpoint(
          text(s"[${tip.replace("Conway", "Babbage")},${tip.replace("Conway", "Babbage")}]")
        )
        .isLeft
    )
  }
  test("CLI arity fails without reading inputs or opening network sessions") {
    CoherentSequenceCommand
      .run(List("capture"))
      .map(code => assertEquals(code.code, 2))
      .unsafeToFuture()
  }
  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def loadFiles = CoherentSequenceCommand.sources.values.map { name =>
      val bound = if name == "scala-sequence-capture.md" then 20 * 1024 * 1024 else 4194304
      val stream = Files.newInputStream(dir.resolve(name))
      val data =
        try stream.readNBytes(bound + 1)
        finally stream.close()
      assert(data.length <= bound)
      name -> Bytes.fromArray(data)
    }.toMap
    test("retained full real sequence matches final oracle and all internal rollback prefixes") {
      CoherentSequenceCommand
        .observe(dir, dir)
        .map { value =>
          val report = get(value)
          val json = ReferenceJson.parse(text(CoherentSequenceCommand.render(report)))
          Vector(
            "passed",
            "scopedSuccess",
            "referencePostStateMatched",
            "wholeTupleRollbackReapply",
            "transactionGroupingVerified",
            "fiveNonceFieldsMatched",
            "finalCountersMatched"
          )
            .foreach(name =>
              assertEquals(ReferenceJson.field(json, name), ReferenceJson.Json.Lit("true"))
            )
          Vector(
            "prefixReferenceCompared",
            "endpointEqualityProvesContinuity",
            "fullLedgerValidated",
            "consensusValidated"
          )
            .foreach(name =>
              assertEquals(ReferenceJson.field(json, name), ReferenceJson.Json.Lit("false"))
            )
          assert(report.capturedBlocks >= 2 && report.capturedBlocks <= 8)
          assertEquals(
            report.finalRevision,
            BigInt(report.capturedBlocks * (report.capturedBlocks + 2))
          )
        }
        .unsafeToFuture()
    }
    test("retained rehashed bad post protocol fails only after real sequence derivation") {
      val context = get(SequenceInput.load(dir))
      val changed = loadFiles.updated("post-protocol-state.md", text("not JSON"))
      val owned = get(CoherentSequenceCommand.bindOracle(manifest(changed), changed))
      CoherentSequenceCommand
        .assess(context, owned)
        .map { result =>
          result match
            case Left(CoherentSequenceCommand.Failure.Rejected("post-oracle", _)) => ()
            case other => fail(other.toString)
        }
        .unsafeToFuture()
    }
    Vector("fees", "nonce").foreach { kind =>
      test(s"retained rehashed changed $kind rejects the final oracle after derivation") {
        val context = get(SequenceInput.load(dir))
        val files = loadFiles
        val name = if kind == "fees" then "post-ledger-state.md" else "post-protocol-state.md"
        val original = new String(files(name).toArray, "UTF-8")
        val pattern =
          if kind == "fees" then """"fees"\s*:\s*([0-9]+)""".r
          else """"evolvingNonce"\s*:\s*"([0-9a-f]{64})"""".r
        val matched =
          pattern.findFirstMatchIn(original).getOrElse(fail("expected retained oracle field"))
        val old = matched.group(1)
        val replacement =
          if kind == "fees" then (BigInt(old) + 1).toString
          else (if old.head == '0' then "1" else "0") + old.tail
        val mutated =
          original.substring(0, matched.start(1)) + replacement + original.substring(matched.end(1))
        val changed = files.updated(name, text(mutated))
        ReferenceJson.parse(changed(name))
        val owned = get(CoherentSequenceCommand.bindOracle(manifest(changed), changed))
        CoherentSequenceCommand
          .assess(context, owned)
          .map {
            case Left(CoherentSequenceCommand.Failure.Rejected(stage, _)) =>
              assertEquals(stage, if kind == "fees" then "post-ledger-oracle" else "post-oracle")
            case other => fail(other.toString)
          }
          .unsafeToFuture()
      }
    }
    test("retained rehashed reordered originals reject the exact contiguous window") {
      val context = get(SequenceInput.load(dir))
      val files = loadFiles
      val found = get(CoherentSequenceCommand.captures(files("scala-sequence-capture.md")))
      val changed =
        files.updated("scala-sequence-capture.md", text(found.reverse.map(row).mkString("\n")))
      val owned = get(CoherentSequenceCommand.bindOracle(manifest(changed), changed))
      CoherentSequenceCommand
        .assess(context, owned)
        .map(result => assert(result.isLeft))
        .unsafeToFuture()
    }
    test("retained rehashed capture omission cannot stand in for the full original sequence") {
      val context = get(SequenceInput.load(dir))
      val files = loadFiles
      val found = get(CoherentSequenceCommand.captures(files("scala-sequence-capture.md")))
      val changed =
        files.updated("scala-sequence-capture.md", text(found.tail.map(row).mkString("\n")))
      val owned = get(CoherentSequenceCommand.bindOracle(manifest(changed), changed))
      CoherentSequenceCommand
        .assess(context, owned)
        .map(result => assert(result.isLeft))
        .unsafeToFuture()
    }
  }
