// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync

class CoherentSequenceSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-sequence-context-v1\n" + SequenceInput.sources.toVector
      .sortBy(_._1)
      .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(files(name)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(files: Map[String, Bytes]): SequenceInput.Context = get(
    SequenceInput.bind(manifest(files), files)
  )
  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val bytes =
      try in.readNBytes(4194305)
      finally in.close()
    assert(bytes.nonEmpty && bytes.length <= 4194304)
    Bytes.fromArray(bytes)
  private def sameContent(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.id, b.id)
    assertEquals(a.contextId, b.contextId)
    assertEquals(a.acquisition.anchor, b.acquisition.anchor)
    assertEquals(a.acquisition.originals, b.acquisition.originals)
    assertEquals(a.certificates.state.id, b.certificates.state.id)
    assertEquals(a.certificates.state.counters, b.certificates.state.counters)
    assertEquals(a.nonces.id, b.nonces.id)
    assertEquals(a.nonces.fields, b.nonces.fields)
    assertEquals(
      a.eligibility.map(e => (e.contextId, e.headers)),
      b.eligibility.map(e => (e.contextId, e.headers))
    )
    assertEquals(a.ledger.id, b.ledger.id)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.slot, b.ledger.slot)
    assertEquals(a.scopedAppliedTip, b.scopedAppliedTip)
  private def unchanged(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    sameContent(a, b)
    assertEquals(a.revision, b.revision)

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
  test("sequence capabilities cannot be manufactured or copied") {
    assert(compileErrors("val s: lab.CoherentSequence.State = null; s.copy()").nonEmpty)
    assert(compileErrors("new lab.CoherentSequence.Fence(new Object(), null, BigInt(0))").nonEmpty)
    assert(compileErrors("new lab.CoherentSequence.Snapshot(null, null)").nonEmpty)
    assert(
      compileErrors(
        "new lab.CoherentSequence.Candidate(null, null, null, null, null, null, null, null)"
      ).nonEmpty
    )
    assert(compileErrors("new lab.CoherentSequence.Applied(null, null, null, null)").nonEmpty)
  }
  test("supplied anchor is not an applied tip; current-tip rollback is a stable no-op") {
    (for
      runtime <- CoherentSequence.create[IO](syntheticContext).map(get(_))
      before <- runtime.snapshot
      result <- runtime.rollbackTo(before.fence, before.state.acquisition.tip).map(get(_))
      again <- runtime.rollbackTo(before.fence, before.state.acquisition.tip).map(get(_))
    yield
      unchanged(result.state, before.state)
      unchanged(again.state, before.state)
      assertEquals(before.state.scopedAppliedTip, None)
      assertEquals(before.state.revision, BigInt(0))
      assert(!before.state.fullLedgerValidated && !before.state.consensusValidated)
    ).unsafeToFuture()
  }
  test("identical supplied contexts cannot exchange runtime fences") {
    val context = syntheticContext
    (for
      a <- CoherentSequence.create[IO](context).map(get(_))
      b <- CoherentSequence.create[IO](context).map(get(_))
      sa <- a.snapshot; sb <- b.snapshot
      rejected <- b.rollbackTo(sa.fence, sb.state.acquisition.anchor)
      after <- b.snapshot
    yield
      sameContent(sa.state, sb.state)
      assertEquals(rejected, Left(CoherentSequence.Failure.ForeignFence))
      unchanged(after.state, sb.state)
    ).unsafeToFuture()
  }
  test("outside-window rollback and invalid capacities do not allocate applied state") {
    val context = syntheticContext
    (for
      low <- CoherentSequence.create[IO](context, 0)
      high <- CoherentSequence.create[IO](context, 9)
      runtime <- CoherentSequence.create[IO](context).map(get(_))
      before <- runtime.snapshot
      outside <- runtime.rollbackTo(before.fence, ChainSync.Point.Origin)
      after <- runtime.snapshot
    yield
      assert(low.isLeft && high.isLeft)
      assertEquals(outside, Left(CoherentSequence.Failure.OutsideRetainedWindow))
      unchanged(after.state, before.state)
    ).unsafeToFuture()
  }

  sys.env.get("SEQUENCE_FREEZE_EVIDENCE").foreach { location =>
    val directory = Path.of(location)
    // Explicitly synthetic complete empty ledger. The freeze capture exported no UTxO state;
    // these tests establish coordinator behavior, never reference ledger agreement.
    def syntheticFiles: Map[String, Bytes] = SequenceInput.sources.values.map { name =>
      val bytes = name match
        case "pre-utxo.md"      => raw("{}")
        case "pre-utxo-cbor.md" => raw("a0")
        case other              => read(directory.resolve(other))
      name -> bytes
    }.toMap
    def context = bind(syntheticFiles)
    def originals =
      get(ClusterHeaderObservation.capturesBounded(directory.resolve("scala-nonce-freeze.md"), 16))
        .take(2)
        .map(c => BoundedChainFollower.Original(c.headerEnvelope, c.block))
    def blocks =
      val result = originals.map(o => get(SequenceInput.block(o)))
      assertEquals(result.size, 2)
      assert(result.forall(_.transactionMemos.isEmpty))
      result
    test(
      "synthetic ledger with two real originals rolls back every prefix and reproduces content with newer revisions"
    ) {
      val bs = blocks
      (for
        runtime <- CoherentSequence.create[IO](context).map(get(_))
        anchor <- runtime.snapshot
        firstCandidate <- runtime.prepare(bs(0)).map(get(_))
        first <- runtime.publish(firstCandidate).map(get(_))
        firstSnap <- runtime.snapshot
        second <- runtime.prepare(bs(1)).map(get(_)).flatMap(runtime.publish).map(get(_))
        tip <- runtime.snapshot
        noop <- runtime.rollbackTo(tip.fence, tip.state.acquisition.tip).map(get(_))
        prefix <- runtime.rollbackTo(tip.fence, first.state.acquisition.tip).map(get(_))
        stale <- runtime.rollbackTo(firstSnap.fence, anchor.state.acquisition.anchor)
        restored <- runtime.rollbackTo(prefix.fence, anchor.state.acquisition.anchor).map(get(_))
        staleCandidate <- runtime.publish(firstCandidate)
        again1 <- runtime.prepare(bs(0)).map(get(_)).flatMap(runtime.publish).map(get(_))
        again2 <- runtime.prepare(bs(1)).map(get(_)).flatMap(runtime.publish).map(get(_))
        aba <- runtime.rollbackTo(tip.fence, anchor.state.acquisition.anchor)
        freshTip <- runtime.snapshot
        allUndone <- runtime.rollbackTo(freshTip.fence, anchor.state.acquisition.anchor).map(get(_))
      yield
        unchanged(noop.state, tip.state)
        sameContent(prefix.state, first.state)
        assertEquals(prefix.state.revision, BigInt(3))
        sameContent(restored.state, anchor.state)
        assertEquals(restored.state.revision, BigInt(4))
        assertEquals(restored.state.scopedAppliedTip, None)
        sameContent(again1.state, first.state)
        sameContent(again2.state, second.state)
        assertEquals(again2.state.revision, BigInt(6))
        assertEquals(stale, Left(CoherentSequence.Failure.StaleFence))
        assertEquals(aba, Left(CoherentSequence.Failure.StaleFence))
        assertEquals(staleCandidate, Left(CoherentSequence.Failure.StaleCandidate))
        sameContent(allUndone.state, anchor.state)
        assertEquals(allUndone.state.revision, BigInt(8))
        assertEquals(second.state.nonces.certificateStateId, second.state.certificates.state.id)
        assertEquals(second.state.nonces.lastSlot, second.state.ledger.slot)
        assertEquals(second.state.scopedAppliedTip, Some(second.state.acquisition.tip))
        assertEquals(second.state.ledger.outputMap.hex, "a0")
      ).unsafeToFuture()
    }
    test(
      "real original candidates are runtime-owned and cannot publish across identical contexts"
    ) {
      val c = context; val block = blocks.head
      (for
        a <- CoherentSequence.create[IO](c).map(get(_))
        b <- CoherentSequence.create[IO](c).map(get(_))
        before <- b.snapshot
        candidate <- a.prepare(block).map(get(_))
        rejected <- b.publish(candidate)
        after <- b.snapshot
      yield
        assertEquals(rejected, Left(CoherentSequence.Failure.ForeignCandidate))
        unchanged(after.state, before.state)
      ).unsafeToFuture()
    }
    test("two concurrent publishers admit one whole tuple and cancellation admits none") {
      val block = blocks.head
      (for
        runtime <- CoherentSequence.create[IO](context).map(get(_))
        before <- runtime.snapshot
        ready <- Deferred[IO, Unit]
        fiber <- (runtime.prepare(block).flatMap(c => IO(get(c))) *> ready.complete(
          ()
        ) *> IO.never).start
        _ <- ready.get; _ <- fiber.cancel
        cancelled <- runtime.snapshot
        candidate <- runtime.prepare(block).map(get(_))
        results <- (runtime.publish(candidate), runtime.publish(candidate)).parTupled
        after <- runtime.snapshot
      yield
        unchanged(cancelled.state, before.state)
        assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
        assertEquals(after.state.revision, BigInt(1))
        val accepted = Vector(results._1, results._2).flatMap(_.toOption).head
        sameContent(after.state, accepted.state)
        assertEquals(after.state.nonces.id, accepted.nonceObservation.after.id)
      ).unsafeToFuture()
    }
    test("bounded history never evicts or reanchors; explicit rollback frees one-slot capacity") {
      val bs = blocks
      (for
        runtime <- CoherentSequence.create[IO](context, 1).map(get(_))
        anchor <- runtime.snapshot
        first <- runtime.prepare(bs.head).map(get(_)).flatMap(runtime.publish).map(get(_))
        tip <- runtime.snapshot
        rejected <- runtime.prepare(bs(1))
        after <- runtime.snapshot
        restored <- runtime.rollbackTo(tip.fence, anchor.state.acquisition.anchor).map(get(_))
        pending <- runtime.prepare(bs.head)
      yield
        rejected match
          case Left(CoherentSequence.Failure.Unsupported("window", _)) => ()
          case other                                                   => fail(other.toString)
        unchanged(after.state, tip.state)
        sameContent(restored.state, anchor.state)
        assert(pending.isRight)
      ).unsafeToFuture()
    }
    test("skipping a real predecessor rejects without publishing any stage") {
      (for
        runtime <- CoherentSequence.create[IO](context).map(get(_))
        before <- runtime.snapshot
        rejected <- runtime.prepare(blocks(1))
        after <- runtime.snapshot
      yield
        assert(rejected.isLeft)
        unchanged(after.state, before.state)
      ).unsafeToFuture()
    }
    def rebound(stage: String): SequenceInput.Context =
      val files = syntheticFiles
      val changed = stage match
        case "certificate" =>
          val old = new String(files("pre-ledger-state.md").toArray, "UTF-8")
          val modified = context.certificates.registrations.values
            .foldLeft(old)((text, key) => text.replace(key.hex, "00" * 32))
          files.updated("pre-ledger-state.md", raw(modified))
        case "nonce" =>
          val old = new String(files("pre-protocol-state.md").toArray, "UTF-8")
          files.updated(
            "pre-protocol-state.md",
            raw(
              old.replaceAll(
                """("epochNonce"\s*:\s*)(null|"[0-9a-f]{64}")""",
                "$1\"" + "00" * 32 + "\""
              )
            )
          )
        case "eligibility" =>
          val old = new String(files("pre-ledger-state.md").toArray, "UTF-8")
          files.updated(
            "pre-ledger-state.md",
            raw(
              old
                .replaceAll(
                  """("individualPoolStake"\s*:\s*\{[^}]*"numerator"\s*:\s*)[0-9]+""",
                  "$1 0"
                )
                .replaceAll("""("individualTotalPoolStake"\s*:\s*)[0-9]+""", "$1 0")
            )
          )
      bind(changed)
    Vector("certificate", "nonce", "eligibility").foreach { stage =>
      test(s"synthetic $stage rejection leaves the entire real-header tuple unchanged") {
        (for
          runtime <- CoherentSequence.create[IO](rebound(stage)).map(get(_))
          before <- runtime.snapshot
          rejected <- runtime.prepare(blocks.head)
          after <- runtime.snapshot
        yield
          rejected match
            case Left(CoherentSequence.Failure.Rejected(actual, _)) => assertEquals(actual, stage)
            case other                                              => fail(other.toString)
          unchanged(after.state, before.state)
        ).unsafeToFuture()
      }
    }
    test(
      "synthetically shifted header is rejected as outside epoch before signature or ledger work"
    ) {
      val c = context
      val original = originals.head
      val h = get(ReferenceCaptureCommand.header(original.envelope))
      val header = get(Cbor.decode(h.raw)).value.asInstanceOf[Value.Arr].value
      val headerBody = header.head.value.asInstanceOf[Value.Arr].value
      val changedSlot = (c.epoch + 1) * c.nonces.context.epochLength
      val nextBody = headerBody.updated(1, Node(Value.UInt(changedSlot), Bytes.empty))
      val changedHeader =
        get(Cbor.encode(Value.Arr(header.updated(0, Node(Value.Arr(nextBody), Bytes.empty)))))
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
      val blockParts = get(Cbor.decode(original.block)).value
        .asInstanceOf[Value.Arr]
        .value(1)
        .value
        .asInstanceOf[Value.Arr]
        .value
      val rawBlock = Bytes(
        Vector(0x82.toByte, 0x07.toByte, 0x85.toByte) ++ changedHeader.value ++
          blockParts.drop(1).flatMap(_.original.value)
      )
      val candidateInput =
        get(SequenceInput.block(BoundedChainFollower.Original(envelope, rawBlock)))
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        before <- runtime.snapshot
        rejected <- runtime.prepare(candidateInput)
        after <- runtime.snapshot
      yield
        rejected match
          case Left(CoherentSequence.Failure.Unsupported("epoch", _)) => ()
          case other                                                  => fail(other.toString)
        unchanged(after.state, before.state)
      ).unsafeToFuture()
    }
    test(
      "original body mutation fails before publication despite a structurally usable memo adapter"
    ) {
      val original = originals.head
      val outer = get(Cbor.decode(original.block)).value.asInstanceOf[Value.Arr].value
      val body = outer(1).value.asInstanceOf[Value.Arr].value
      // Change empty bodies and witnesses to matching empty-map entries; leave authenticated header untouched.
      val changedBody = body
        .updated(
          1,
          Node(Value.Arr(Vector(Node(Value.Map(Vector.empty), Bytes.empty))), Bytes.empty)
        )
        .updated(
          2,
          Node(Value.Arr(Vector(Node(Value.Map(Vector.empty), Bytes.empty))), Bytes.empty)
        )
      // Preserve original signed header bytes while changing only block body components.
      val encoded = Bytes(
        Vector(0x82.toByte, 0x07.toByte, 0x85.toByte) ++ body(0).original.value ++
          get(Cbor.encode(changedBody(1).value)).value ++ get(
            Cbor.encode(changedBody(2).value)
          ).value ++
          body(3).original.value ++ body(4).original.value
      )
      val changed = get(SequenceInput.block(original.copy(block = encoded)))
      (for
        runtime <- CoherentSequence.create[IO](context).map(get(_))
        before <- runtime.snapshot
        rejected <- runtime.prepare(changed)
        after <- runtime.snapshot
      yield
        assert(rejected.isLeft)
        unchanged(after.state, before.state)
      ).unsafeToFuture()
    }
  }

  sys.env.get("COHERENT_POSITIVE_INPUT").foreach { location =>
    def input = get(BranchInput.load(Path.of(location)))
    test(
      "unaltered real one-transaction block publishes as one sequence revision then rolls back"
    ) {
      val original = input
      val c = get(SequenceInput.fromInput(original))
      val b = get(SequenceInput.block(original.original))
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        before <- runtime.snapshot
        applied <- runtime.prepare(b).map(get(_)).flatMap(runtime.publish).map(get(_))
        tip <- runtime.snapshot
        restored <- runtime.rollbackTo(tip.fence, before.state.acquisition.anchor).map(get(_))
      yield
        assertEquals(applied.state.revision, BigInt(1))
        assertEquals(applied.ledgerObservation.candidate.transactionMemos.size, 1)
        assertEquals(applied.state.nonces.lastSlot, original.header.slot)
        sameContent(restored.state, before.state)
        assertEquals(restored.state.revision, BigInt(2))
      ).unsafeToFuture()
    }
    test(
      "late block-ledger fee failure leaves certificate nonce eligibility and applied tip untouched"
    ) {
      val original = input
      val files = SequenceInput.sources.values.map(n => n -> original.originals(n)).toMap
      val parameters = new String(files("pre-parameters.md").toArray, "UTF-8")
      val c = bind(
        files.updated(
          "pre-parameters.md",
          raw(parameters.replaceAll("""("txFeeFixed"\s*:\s*)[0-9]+""", "$1 999999999"))
        )
      )
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        before <- runtime.snapshot
        rejected <- runtime.prepare(get(SequenceInput.block(original.original)))
        after <- runtime.snapshot
      yield
        rejected match
          case Left(CoherentSequence.Failure.LedgerRejected(_)) => ()
          case other                                            => fail(other.toString)
        unchanged(after.state, before.state)
      ).unsafeToFuture()
    }
  }
