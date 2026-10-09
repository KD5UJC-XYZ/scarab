// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync

class ValidatedCheckpointSuite extends munit.FunSuite:
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
  private val storeId = raw("s" * 32)
  private def image(context: SequenceInput.Context, runtime: CoherentSequence.Runtime[IO]) =
    runtime.snapshot.map(s => get(ValidatedCheckpoint.encode(context, s, storeId, 7, 8)))
  private def recover(data: (Bytes, ValidatedCheckpoint.Token)) =
    ValidatedCheckpoint.recover[IO](data._1, data._2.contextId, data._2, 20.seconds)
  // Rewrite untrusted wire fields, never construct coordinator state or receipt capabilities.
  private def rewrite(data: (Bytes, ValidatedCheckpoint.Token), index: Int, replacement: Bytes) =
    val input =
      new java.io.DataInputStream(new java.io.ByteArrayInputStream(data._1.toArray.dropRight(32)))
    val buffer = new java.io.ByteArrayOutputStream()
    val output = new java.io.DataOutputStream(buffer)
    var fieldIndex = 0
    def field(): Unit =
      val bytes = input.readNBytes(input.readInt())
      val next = if fieldIndex == index then replacement.toArray else bytes
      fieldIndex += 1
      output.writeInt(next.length); output.write(next)
    (0 until 4).foreach(_ => field())
    output.writeLong(input.readLong()); output.writeInt(input.readInt())
    (0 until 10).foreach(_ => field()) // revision, ID, manifest, seven originals
    val count = input.readInt(); output.writeInt(count)
    (0 until count * 2).foreach(_ => field())
    val payload = Bytes.fromArray(buffer.toByteArray)
    val digest = ClusterHeaderObservation.sha256(payload)
    (Bytes(payload.value ++ digest.value), data._2.copy(digest = digest))

  test("bounded envelope and exact independent pins/token reject malformed input") {
    val context = syntheticContext
    (for
      runtime <- CoherentSequence.create[IO](context).map(get(_))
      data <- image(context, runtime)
      restored <- recover(data).map(get(_))
      a <- runtime.snapshot; b <- restored.snapshot
      foreign <- restored.rollbackTo(a.fence, b.state.acquisition.anchor)
    yield
      unchanged(a.state, b.state)
      assertEquals(foreign, Left(CoherentSequence.Failure.ForeignFence))
      assert(ValidatedCheckpoint.decode(data._1, raw("x" * 32), data._2).isLeft)
      assert(ValidatedCheckpoint.decode(data._1, context.id, data._2.copy(generation = 8)).isLeft)
      assert(
        ValidatedCheckpoint
          .decode(data._1, context.id, data._2.copy(storeId = raw("z" * 32)))
          .isLeft
      )
      for bad <- Vector(
          Bytes.empty,
          Bytes(data._1.value.dropRight(1)),
          Bytes(data._1.value :+ 0.toByte),
          Bytes(Vector.fill(ValidatedCheckpoint.MaxBytes + 1)(0.toByte))
        )
      do assert(ValidatedCheckpoint.decode(bad, context.id, data._2).isLeft)
      for (index, value) <- Vector(
          0 -> raw("wrong-format"),
          1 -> raw("wrong-profile"),
          4 -> raw("01"),
          4 -> raw("1"),
          4 -> raw("18446744073709551616"),
          5 -> raw("short")
        )
      do
        val changed = rewrite(data, index, value)
        assert(ValidatedCheckpoint.decode(changed._1, context.id, changed._2).isLeft)
      def rebound(payload: Array[Byte]) =
        val body = Bytes.fromArray(payload)
        val hash = ClusterHeaderObservation.sha256(body)
        (Bytes(body.value ++ hash.value), data._2.copy(digest = hash))
      val payload = data._1.toArray.dropRight(32)
      val excessive = payload.clone()
      java.nio.ByteBuffer.wrap(excessive).putInt(Int.MaxValue)
      for changed <- Vector(rebound(excessive), rebound(payload :+ 0.toByte)) do
        assert(ValidatedCheckpoint.decode(changed._1, context.id, changed._2).isLeft)
      val input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))
      (0 until 4).foreach(_ => input.skipNBytes(input.readInt().toLong))
      input.readLong()
      val offset = payload.length - input.available()
      for capacity <- Vector(0, 9) do
        val changed = payload.clone()
        java.nio.ByteBuffer.wrap(changed).putInt(offset, capacity)
        val bound = rebound(changed)
        assert(ValidatedCheckpoint.decode(bound._1, context.id, bound._2).isLeft)
      assert(
        compileErrors(
          "new lab.ValidatedCheckpoint.Envelope(null,0,null,null,null,null,null)"
        ).nonEmpty
      )
    ).unsafeToFuture()
  }
  test("anchor recovery validates revision parity, boundary, final content and normal seed") {
    val context = syntheticContext
    (for
      runtime <- CoherentSequence.create[IO](context).map(get(_))
      data <- image(context, runtime)
      two <- recover(rewrite(data, 4, raw("2"))).map(get(_))
      snap <- two.snapshot
      near <- recover(
        rewrite(data, 4, raw((lab.ledger.ClusterTransition.MaxRevision - 1).toString))
      ).map(get(_)).flatMap(_.snapshot)
      badId <- recover(rewrite(data, 5, raw("x" * 32)))
      badSource <- recover(rewrite(data, 7, raw("{}")))
      normal <- CoherentSequence.create[IO](context).map(get(_)).flatMap(_.snapshot)
    yield
      assertEquals(snap.state.revision, BigInt(2))
      assertEquals(snap.state.scopedAppliedTip, None)
      assertEquals(near.state.revision, lab.ledger.ClusterTransition.MaxRevision - 1)
      assertEquals(normal.state.revision, BigInt(0))
      assert(badId.isLeft && badSource.isLeft)
    ).unsafeToFuture()
  }
  test("recovery yields before bounded stages and obeys cancellation/deadline") {
    (for
      runtime <- CoherentSequence.create[IO](syntheticContext).map(get(_))
      data <- image(syntheticContext, runtime)
      rejected <- ValidatedCheckpoint.recover[IO](
        data._1,
        data._2.contextId,
        data._2,
        Duration.Zero
      )
      entered <- Deferred[IO, Unit]; release <- Deferred[IO, Unit]
      observer = (label: String) =>
        if label == "context" then entered.complete(()).void *> release.get else IO.unit
      fiber <- ValidatedCheckpoint
        .recoverObserved[IO](data._1, data._2.contextId, data._2, 20.seconds, observer)
        .start
      _ <- entered.get
      _ <- fiber.cancel
      outcome <- fiber.join
    yield
      assert(rejected.isLeft)
      assert(outcome.isCanceled)
    ).unsafeToFuture()
  }
  sys.env.get("COHERENT_POSITIVE_INPUT").foreach { location =>
    test("retained single transaction replay preserves one block revision") {
      val input = get(BranchInput.load(Path.of(location)))
      val c = get(SequenceInput.fromInput(input))
      val block = get(SequenceInput.block(input.original))
      assertEquals(block.transactionMemos.size, 1)
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        _ <- runtime.prepare(block).map(get(_)).flatMap(runtime.publish).map(get(_))
        data <- image(c, runtime)
        restored <- recover(data).map(get(_))
        a <- runtime.snapshot; b <- restored.snapshot
      yield
        unchanged(a.state, b.state)
        assertEquals(b.state.revision, BigInt(1))
      ).unsafeToFuture()
    }
  }
  sys.env.get("COHERENT_SEQUENCE_EVIDENCE").foreach { location =>
    val directory = Path.of(location)
    def context = get(SequenceInput.load(directory))
    def originals = get(
      CoherentSequenceCommand.captures(
        Bytes.fromArray(Files.readAllBytes(directory.resolve("scala-sequence-capture.md")))
      )
    )
    def applyAll(
        runtime: CoherentSequence.Runtime[IO],
        values: Vector[BoundedChainFollower.Original]
    ) =
      values.traverse_(o =>
        runtime
          .prepare(get(SequenceInput.block(o)))
          .map(get(_))
          .flatMap(runtime.publish)
          .map(get(_))
      )
    test(
      "retained real empty/multi-transaction replay rebuilds every rollback prefix and fresh ownership"
    ) {
      val c = context; val os = originals
      val bs = os.map(o => get(SequenceInput.block(o)))
      assert(bs.exists(_.transactionMemos.isEmpty))
      assert(bs.exists(_.transactionMemos.size == 2))
      (for
        old <- CoherentSequence.create[IO](c).map(get(_))
        candidate <- old.prepare(bs.head).map(get(_))
        _ <- applyAll(old, os)
        tip <- old.snapshot
        data <- image(c, old)
        _ <- (0 to os.size).toVector.traverse_ { keep =>
          for
            recovered <- recover(data).map(get(_))
            before <- recovered.snapshot
            foreign <- recovered.publish(candidate)
            foreignFence <- recovered.rollbackTo(tip.fence, tip.state.acquisition.anchor)
            target =
              if keep == 0 then tip.state.acquisition.anchor
              else
                ChainSync.Point.Block(
                  get(
                    ChainSync.UInt64
                      .from(get(ReferenceCaptureCommand.header(os(keep - 1).envelope)).slot)
                  ),
                  get(ReferenceCaptureCommand.header(os(keep - 1).envelope)).hash
                )
            rolled <- recovered.rollbackTo(before.fence, target).map(get(_))
            saved <- image(c, recovered)
            again <- recover(saved).map(get(_))
            roundtrip <- again.snapshot
            _ <- applyAll(again, os.drop(keep))
            reapplied <- again.snapshot
          yield
            unchanged(rolled.state, roundtrip.state)
            sameContent(tip.state, reapplied.state)
            assertEquals(before.state.revision, BigInt(os.size))
            assertEquals(rolled.state.revision, BigInt(2 * os.size - keep))
            assertEquals(foreign, Left(CoherentSequence.Failure.ForeignCandidate))
            assertEquals(foreignFence, Left(CoherentSequence.Failure.ForeignFence))
        }
      yield ()).unsafeToFuture()
    }
    test("retained recovery cancels during preparation and expires a positive deadline") {
      val c = context
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        _ <- applyAll(runtime, originals)
        data <- image(c, runtime)
        entered <- Deferred[IO, Unit]; release <- Deferred[IO, Unit]
        observer = (label: String) =>
          if label == "prepare" then entered.complete(()).void *> release.get else IO.unit
        fiber <- ValidatedCheckpoint
          .recoverObserved[IO](data._1, c.id, data._2, 20.seconds, observer)
          .start
        _ <- entered.get
        _ <- fiber.cancel
        outcome <- fiber.join
        timed <- ValidatedCheckpoint.recoverObserved[IO](
          data._1,
          c.id,
          data._2,
          200.millis,
          observer
        )
      yield
        assert(outcome.isCanceled)
        assertEquals(timed, Left("recovery deadline"))
      ).unsafeToFuture()
    }
    test("retained replay reaches revision ceiling and rejects exhausted rollback and R below n") {
      val c = context; val first = originals.take(1)
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        _ <- applyAll(runtime, first)
        data <- image(c, runtime)
        atMax <- recover(rewrite(data, 4, raw(lab.ledger.ClusterTransition.MaxRevision.toString)))
          .map(get(_))
        current <- atMax.snapshot
        exhausted <- atMax.rollbackTo(current.fence, current.state.acquisition.anchor)
        below <- recover(rewrite(data, 4, raw("0")))
      yield
        assertEquals(current.state.revision, lab.ledger.ClusterTransition.MaxRevision)
        assertEquals(exhausted, Left(CoherentSequence.Failure.RevisionExhausted))
        assert(below.isLeft)
      ).unsafeToFuture()
    }
    test("matched original header/body mutation must still pass certificate verification") {
      val c = context; val os = originals
      val header = get(ReferenceCaptureCommand.header(os.head.envelope)).raw
      def altered(bytes: Bytes): Bytes =
        val position = bytes.value.indexOfSlice(header.value)
        assert(position >= 0)
        val i = position + header.size - 1
        Bytes(bytes.value.updated(i, (bytes.value(i) ^ 1).toByte))
      val modified =
        BoundedChainFollower.Original(altered(os.head.envelope), altered(os.head.block))
      assert(SequenceInput.block(modified).isRight)
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        _ <- applyAll(runtime, os)
        data <- image(c, runtime)
        invalid <- recover(rewrite(rewrite(data, 14, modified.envelope), 15, modified.block))
      yield assert(invalid.left.toOption.exists(_.contains("certificate")))).unsafeToFuture()
    }
    test("rebound context with impossible fees reaches full ledger rejection during recovery") {
      val c = context; val os = originals
      val parameterText = new String(c.originals("pre-parameters.md").toArray, "UTF-8")
      val changedFiles = c.originals.updated(
        "pre-parameters.md",
        raw(parameterText.replaceAll("""("txFeeFixed"\s*:\s*)[0-9]+""", "$1 999999999"))
      )
      val changedContext = bind(changedFiles)
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        _ <- applyAll(runtime, os)
        data <- image(c, runtime)
        modified = SequenceInput.sources.toVector
          .sortBy(_._1)
          .zipWithIndex
          .foldLeft(rewrite(rewrite(data, 3, changedContext.id), 6, manifest(changedFiles))) {
            case (current, ((_, name), index)) =>
              rewrite(current, 7 + index, changedFiles(name))
          }
        invalid <- recover((modified._1, modified._2.copy(contextId = changedContext.id)))
      yield assert(invalid.left.toOption.exists(_.contains("LedgerRejected")))).unsafeToFuture()
    }
    test("retained envelope with parseable repeated original fails full coordinator continuity") {
      val c = context; val os = originals
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        _ <- applyAll(runtime, os)
        data <- image(c, runtime)
        changed = rewrite(rewrite(data, 16, os.head.envelope), 17, os.head.block)
        failed <- recover(changed)
      yield assert(failed.isLeft)).unsafeToFuture()
    }
  }
