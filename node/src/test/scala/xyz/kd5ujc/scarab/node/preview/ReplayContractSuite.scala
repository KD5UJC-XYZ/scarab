// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Phase2, PlutusCase, PreparedBlockFixture, PreprodDeepTx, Redeemer}
import xyz.kd5ujc.scarab.plutus.*

class ReplayContractSuite extends FunSuite:
  import ReplayTrace.*

  private val request = EvalRequest(
    PlutusLanguage.V2,
    7,
    Bytes.empty,
    Vector.empty,
    Vector(1, 2),
    ExUnits(10, 100)
  )
  private def evaluation(index: Int, result: EvalResult): Evaluation =
    Evaluation(Redeemer(0, index, Bytes.empty, request.budget), request, Result.from(result))
  private val passed = evaluation(0, EvalResult.Success(ExUnits(1, 10), Vector("pass")))
  private val firstFailure =
    evaluation(1, EvalResult.Failure(EvalFailure.OutOfBudget, ExUnits(2, 20), Vector("first")))
  private val secondFailure =
    evaluation(2, EvalResult.Failure(EvalFailure.OpenTerm(3), ExUnits(3, 30), Vector("second")))
  private val transaction = Transaction(
    Bytes.empty,
    7,
    false,
    Outcome.Failed(
      Vector(passed, firstFailure, secondFailure),
      Vector(
        Refusal(firstFailure.redeemer, request, Failure.from(EvalFailure.OutOfBudget)),
        Refusal(secondFailure.redeemer, request, Failure.from(EvalFailure.OpenTerm(3)))
      )
    )
  )

  test("transaction failures and all executed script failures are counted separately") {
    val counts = Counts().add(transaction)
    assertEquals(counts.failedTransactions, 1L)
    assertEquals(counts.evaluatedScripts, 3L)
    assertEquals(counts.failedScripts, 2L)
    assertEquals(counts.used, ExUnits(6, 60))
  }

  test("script order, full requests, used budgets and log order participate in equality") {
    val original = Vector(transaction)
    def changed(evaluations: Vector[Evaluation]) =
      Vector(transaction.copy(outcome = Outcome.Passed(evaluations)))
    val baseline = changed(Vector(passed, firstFailure))
    assert(ReplayTrace.compare(baseline, changed(Vector(firstFailure, passed))).nonEmpty)
    assert(
      ReplayTrace
        .compare(
          baseline,
          changed(Vector(passed.copy(request = request.copy(protocolMajor = 8)), firstFailure))
        )
        .nonEmpty
    )
    assert(
      ReplayTrace
        .compare(
          baseline,
          changed(
            Vector(
              passed.copy(result = Result.Passed(ExUnits(2, 10), Vector("pass"))),
              firstFailure
            )
          )
        )
        .nonEmpty
    )
    assert(
      ReplayTrace
        .compare(
          baseline,
          changed(
            Vector(
              passed.copy(result = Result.Passed(ExUnits(1, 10), Vector("different"))),
              firstFailure
            )
          )
        )
        .nonEmpty
    )
    assert(ReplayTrace.compare(original, Vector.empty).nonEmpty)
    assertEquals(ReplayTrace.compare(original, original), None)
  }

  test("same-backend native failure diagnostics compare as evidence without exception identity") {
    def failure(error: Throwable) =
      Failure.from(EvalFailure.Script(ScriptFailure(error, Vector("trace"))))
    assertEquals(
      failure(new IllegalArgumentException("first")),
      failure(new IllegalArgumentException("first"))
    )
    assertNotEquals(
      failure(new IllegalArgumentException("first")),
      failure(new IllegalArgumentException("second"))
    )
    assertNotEquals(
      failure(new IllegalArgumentException("first")),
      failure(new IllegalStateException("first"))
    )
    assertNotEquals(Failure.from(EvalFailure.OutOfBudget), Failure.from(EvalFailure.OpenTerm(0)))
  }

  private val environment = Map(
    "SCARAB_PREVIEW_CAPTURE" -> "/capture",
    "SCARAB_PREVIEW_SNAPSHOT" -> "/snapshot.bin",
    "SCARAB_PREVIEW_CHECKPOINT" -> "/checkpoint",
    "SCARAB_PREVIEW_OUTPUT" -> "/scratch",
    "SCARAB_PREVIEW_REFERENCE" -> "/native-reference"
  )

  test("configuration rejects invalid bounds, rounds and filesystem paths with typed cases") {
    assert(ReplayInputs.config(environment).isRight)
    assertEquals(
      ReplayInputs.config(environment.removed("SCARAB_PREVIEW_REFERENCE")),
      Left(ReplayInputs.Error.Missing(ReplayInputs.Setting.Reference))
    )
    assertEquals(
      ReplayInputs.config(environment.updated("SCARAB_PREVIEW_WORKERS", "1")),
      Left(ReplayInputs.Error.NoParallelWorkers)
    )
    assert(ReplayInputs.config(environment.updated("SCARAB_PREVIEW_WORKERS", "0,4")).isLeft)
    assertEquals(
      ReplayInputs.config(environment.updated("SCARAB_PREVIEW_ROUNDS", "0")),
      Left(ReplayInputs.Error.Rounds(0))
    )
    assert(ReplayInputs.config(environment.updated("SCARAB_PREVIEW_CAPTURE", "\u0000")).isLeft)
  }

  private def record(slot: Int, chunk: String = "00001.chunk", length: Int = 12): String =
    io.circe.Json
      .obj(
        "chunk" -> io.circe.Json.fromString(chunk),
        "offset" -> io.circe.Json.fromInt(0),
        "length" -> io.circe.Json.fromInt(length),
        "slot" -> io.circe.Json.fromInt(slot)
      )
      .noSpaces

  test("captured record validation rejects traversal, oversized reads and reordered blocks") {
    assert(ReplayInputs.records(Vector(record(1), record(2))).isRight)
    assert(ReplayInputs.records(Vector(record(1, "../00001.chunk"))).isLeft)
    assert(ReplayInputs.records(Vector(record(1, length = Int.MaxValue))).isLeft)
    assert(ReplayInputs.records(Vector(record(2), record(1))).isLeft)
    assert(ReplayInputs.records(Vector(record(1), record(1))).isLeft)
    assert(ReplayInputs.records(Vector(record(1, "\u0000"))).isLeft)
    assertEquals(ReplayInputs.records(Vector.empty), Left(ReplayInputs.Error.EmptyWindow))
  }

  test("expected invalid-transaction identities reject duplicate and valid rows") {
    val id = "00" * 32
    val row = s"""{"txId":"$id","isValid":false}"""
    assertEquals(ReplayInputs.expectedFailures(Vector(row)).map(_.size), Right(1))
    assert(ReplayInputs.expectedFailures(Vector(row, row)).isLeft)
    assert(ReplayInputs.expectedFailures(Vector(s"""{"txId":"$id","isValid":true}""")).isLeft)
    assert(ReplayInputs.expectedFailures(Vector("""{"txId":"00","isValid":false}""")).isLeft)
  }

  test("wide externally supplied records and traces are stack safe on 256 KiB") {
    PreprodDeepTx.onSmallStack {
      val entries = Vector.tabulate(20000)(i => record(i))
      assertEquals(ReplayInputs.records(entries).map(_.size), Right(entries.size))
      val traces = Vector.fill(20000)(transaction)
      assertEquals(ReplayTrace.compare(traces, traces), None)
      assertEquals(Counts().add(traces).failedTransactions, 20000L)
    }
  }

  test("the shared observer releases each block before accepting the next one") {
    val c = PlutusCase.all.headOption.getOrElse(fail("recorded Plutus fixtures missing"))
    val observation = Phase2.Observation(
      c.tx,
      c.params,
      c.slot,
      c.spent,
      c.references,
      None,
      Right(Phase2.Outcome.Passed(Phase2.Report(Vector.empty)))
    )
    val observer = new ReplayObservations
    observer.observe(observation)
    assertEquals(observer.drain().map(_.id), Vector(c.tx.body.id))
    assertEquals(observer.drain(), Vector.empty)
    observer.observe(observation)
    observer.observe(observation)
    assertEquals(observer.drain().size, 2)
  }

  test("batch scratch outputs must be distinct before any replay starts") {
    assert(ReplayInputs.windows(Vector("{}", "{}"), environment).isLeft)
    assertEquals(
      ReplayInputs.windows(Vector.empty, environment),
      Left(ReplayInputs.Error.EmptyWindow)
    )
  }

  test(
    "a window must continue its authenticated baseline without skipped blocks or another parent"
  ) {
    val header =
      PreparedBlockFixture.header.copy(slot = 8, blockNo = 3, prevHash = Some(Bytes.empty))
    val previous = xyz.kd5ujc.scarab.consensus.BlockRef(7, Bytes.empty, 2)
    assert(ReplayInputs.link(previous, header).isRight)
    assert(ReplayInputs.link(previous, header.copy(blockNo = 4)).isLeft)
    assert(ReplayInputs.link(previous, header.copy(prevHash = None)).isLeft)
    assert(ReplayInputs.link(previous, header.copy(slot = 7)).isLeft)
  }

  test("record metadata independently refuses a wrong slot, block, header hash or raw-byte SHA") {
    val header = PreparedBlockFixture.header
    val sha = "00" * 32
    val record = ReplayInputs.Record(
      "00001.chunk",
      0L,
      12,
      header.slot,
      Some(header.blockNo),
      Some(header.hash.hex),
      Some(sha)
    )
    assertEquals(record.check(header, sha), Right(()))
    assertEquals(
      record.copy(slot = header.slot + 1).check(header, sha),
      Left(ReplayInputs.Error.Slot(header.slot + 1, header.slot))
    )
    assertEquals(
      record.copy(block = Some(header.blockNo + 1)).check(header, sha),
      Left(ReplayInputs.Error.BlockNumber(header.blockNo + 1, header.blockNo))
    )
    val foreign = "ff" * 32
    assertEquals(
      record.copy(hash = Some(foreign)).check(header, sha),
      Left(ReplayInputs.Error.HeaderHash(foreign, header.hash))
    )
    assertEquals(
      record.copy(sha256 = Some(foreign)).check(header, sha),
      Left(ReplayInputs.Error.BlockHash(foreign, sha))
    )
  }

  test("JSONL bytes decode strictly before parsing their bound contents") {
    val bytes =
      (record(1) + "\n" + record(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(ReplayInputs.lines(bytes).flatMap(ReplayInputs.records).map(_.size), Right(2))
    assert(ReplayInputs.lines(Array(0xc3.toByte, 0x28.toByte)).isLeft)
  }
