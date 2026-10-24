// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes}
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.protocol.Era

class AuxiliaryScriptsSuite extends FunSuite:
  import ContextReferenceCases.{field, hex}
  private val rows =
    parse(Files.readString(Path.of("fixtures/reference/auxiliary-scripts/auxiliary.json")))
      .fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
  private val base = Vector(5, 7, 9).map { pv =>
    val era = if pv == 5 then "alonzo" else if pv == 7 then "babbage" else "conway"
    val json = parse(Files.readString(Path.of(s"fixtures/reference/v1-context/$era-pv$pv.json")))
      .fold(error => fail(error.message), identity)
      .asArray
      .flatMap(_.headOption)
      .getOrElse(fail("no base"))
    era -> PlutusCase.fromJson(json).tx
  }.toMap

  private def withAuxiliary(tx: Tx, bytes: Bytes): Tx =
    tx.copy(
      body = tx.body.copy(auxDataHash = Some(Blake2b.hash256.hash(bytes))),
      auxiliary = Some(bytes)
    )

  private def auxiliary(kind: String): Bytes =
    hex(
      field[String](
        rows
          .find(row => field[String](row, "name") == s"conway-pv10-$kind")
          .getOrElse(fail(s"missing $kind")),
        "auxiliary"
      )
    )

  private def synthetic(bytes: Bytes, valid: Boolean): Tx =
    withAuxiliary(
      TestLedger.tx(
        TestLedger.body("auxiliary-check", Vector.empty, Vector.empty, 0),
        Vector.empty,
        isValid = valid
      ),
      bytes
    )

  private def invalidMetadata(result: Either[LedgerFailure, Unit]): Unit = result match
    case Left(
          LedgerFailure.Rejected.Single(_, PredicateFailure.Utxow(UtxowFailure.InvalidMetadata))
        ) =>
      ()
    case other => fail(s"not InvalidMetadata: $other")

  rows.foreach { row =>
    test(s"${field[String](row, "name")}: independent auxiliary decoder and validity predicate") {
      val major = field[Int](row, "protocolMajor")
      val era = if major < 7 then Era.Alonzo else if major < 9 then Era.Babbage else Era.Conway
      val bytes = hex(field[String](row, "auxiliary"))
      val reference = field[Json](row, "oracle")
      val decoded = field[Boolean](reference, "decoded")
      assertEquals(AuxiliaryScripts.read(bytes, major).isRight, decoded)
      val original = base(era.toString.toLowerCase)
      val witnesses = original.witnesses.raw.getOrElse(fail("no original witnesses"))
      assertEquals(
        Tx.fromBlock(Block.Transaction(original.body.raw, witnesses, Some(bytes), true), era)
          .isRight,
        decoded
      )
      for valid <- Vector(true, false) do
        val checked = TxChecks.auxiliaryChecks(
          TestLedger.params(major),
          synthetic(bytes, valid),
          TestLedger.env
        )
        if !decoded then
          checked match
            case Left(LedgerFailure.Undecodable(_, _: TxDefect.AuxiliaryData)) => ()
            case other => fail(s"structural failure became a predicate: $other")
        else if field[Boolean](reference, "valid") then assertEquals(checked, Right(()))
        else invalidMetadata(checked)
    }
  }

  test("structural decoder failures precede hashes; well-formed Flat failures follow hashes") {
    val calls = new AtomicInteger()
    val decoder: ScriptDecoder[Id] = (language, major, code) =>
      calls.incrementAndGet()
      ScalusEvaluator.defaultDecoder.validate(language, major, code)
    for valid <- Vector(true, false) do
      val malformedEnvelope = synthetic(auxiliary("v1-script-not-bytes"), valid)
      val badHash =
        malformedEnvelope.copy(body = malformedEnvelope.body.copy(auxDataHash = Some(Bytes.empty)))
      assert(
        TxChecks
          .auxiliaryChecks(TestLedger.params(10), badHash, TestLedger.env.copy(decoder = decoder))
          .left
          .exists(_.isInstanceOf[LedgerFailure.Undecodable])
      )
      val malformedFlat = synthetic(auxiliary("v1-malformed"), valid)
      val wrongHash =
        malformedFlat.copy(body = malformedFlat.body.copy(auxDataHash = Some(Bytes.empty)))
      TxChecks.auxiliaryChecks(
        TestLedger.params(10),
        wrongHash,
        TestLedger.env.copy(decoder = decoder)
      ) match
        case Left(
              LedgerFailure.Rejected.Single(
                _,
                PredicateFailure.Utxow(_: UtxowFailure.ConflictingMetadataHash)
              )
            ) =>
          ()
        case other => fail(s"script admission preceded hash validation: $other")
    assertEquals(calls.get(), 0)
  }

  private val unavailable: ScriptDecoder[Id] = (_, _, _) =>
    Left(ScriptDecodeFailure.Unavailable(BackendFailure.NoResult))
  test("an auxiliary backend outage is unavailable for either validity tag") {
    for valid <- Vector(true, false) do
      TxChecks.auxiliaryChecks(
        TestLedger.params(10),
        synthetic(auxiliary("v1-wellformed"), valid),
        TestLedger.env.copy(decoder = unavailable)
      ) match
        case Left(
              LedgerFailure.Unavailable(
                _,
                _: ScriptLocation.Phase1,
                EvalFailure.Unsupported(BackendFailure.NoResult)
              )
            ) =>
          ()
        case other => fail(s"backend outage became a script verdict: $other")
  }

  test("deep metadata decodes and validates on a 256KiB stack") {
    val bytes =
      Bytes(Vector(0xa1.toByte, 0.toByte) ++ Vector.fill(20000)(0x81.toByte) ++ Vector(0.toByte))
    assertEquals(PreprodDeepTx.onSmallStack(AuxiliaryScripts.read(bytes, 10)), Right(Vector.empty))
  }

  private def body(
      transactions: Vector[Tx],
      valid: Boolean,
      decoder: ScriptDecoder[Id]
  ): EraBlock.Body =
    val fixture = PreparedBlockFixture
    val block = Block.Shelley(
      Era.Conway,
      fixture.header,
      transactions.map(tx => Block.Transaction(tx.body.raw, Bytes.empty, tx.auxiliary, tx.isValid)),
      100,
      Bytes.empty
    )
    EraBlock.program(
      EraBlock.Context(
        TestLedger.globals,
        (_, _) => Era.Conway,
        evaluator = TestLedger.scripted(valid),
        decoder = decoder
      )
    )(fixture.state, fixture.header, block, transactions.map(Right(_)), PlutusStats.zero)

  test(
    "late auxiliary failure refuses full BBODY in serial/prepared paths without publishing its prefix"
  ) {
    for valid <- Vector(true, false) do
      val initial = PreparedBlockFixture.state
      val transactions = PreparedBlockFixture.txs.map(_.copy(isValid = valid))
      val bad = transactions.updated(1, withAuxiliary(transactions(1), auxiliary("v1-malformed")))
      val program = body(bad, valid, ScalusEvaluator.defaultDecoder)
      val serial = program.serial
      val plan = program.prepare
      assertEquals(plan.jobs.size, 1)
      assertEquals(plan.resolve(plan.jobs.map(plan.execute)), Right(serial))
      serial match
        case EraBlock.Outcome.Refused(
              BlockFailure.Tx(
                LedgerFailure.Rejected
                  .Single(_, PredicateFailure.Utxow(UtxowFailure.InvalidMetadata))
              )
            ) =>
          ()
        case other => fail(s"late auxiliary failure did not refuse BBODY: $other")
      assertEquals(PreparedBlockFixture.state, initial)
      body(transactions, valid, ScalusEvaluator.defaultDecoder).serial match
        case EraBlock.Outcome.Applied(_: EraNes, _) => ()
        case other => fail(s"refused block contaminated the reusable state: $other")
  }

  test(
    "late auxiliary backend outage remains unavailable through full BBODY and prepared landing"
  ) {
    for valid <- Vector(true, false) do
      val transactions = PreparedBlockFixture.txs.map(_.copy(isValid = valid))
      val supplied = auxiliary("v1-wellformed")
      val code = AuxiliaryScripts
        .read(supplied, 10)
        .fold(error => fail(error.toString), identity)
        .headOption
        .getOrElse(fail("no auxiliary script"))
        .code
      val decoder: ScriptDecoder[Id] = (language, major, script) =>
        if script == code then unavailable.validate(language, major, script)
        else ScalusEvaluator.defaultDecoder.validate(language, major, script)
      val program =
        body(transactions.updated(1, withAuxiliary(transactions(1), supplied)), valid, decoder)
      val serial = program.serial
      val plan = program.prepare
      assertEquals(plan.resolve(plan.jobs.map(plan.execute)), Right(serial))
      serial match
        case EraBlock.Outcome.Refused(BlockFailure.Tx(_: LedgerFailure.Unavailable)) => ()
        case other => fail(s"BBODY backend outage became consensus failure: $other")
  }

  test("an unavailable auxiliary decode dominates malformed Flat flags in either script order") {
    val good = AuxiliaryScripts
      .read(auxiliary("v1-wellformed"), 10)
      .fold(error => fail(error.toString), identity)
      .headOption
      .getOrElse(fail("no script"))
      .code
    val shortBytes = f"${0x40 + good.size}%02x" + good.hex
    val decoder: ScriptDecoder[Id] = (language, major, script) =>
      if script == good then unavailable.validate(language, major, script)
      else ScalusEvaluator.defaultDecoder.validate(language, major, script)
    for scripts <- Vector("41ff" + shortBytes, shortBytes + "41ff"); valid <- Vector(true, false) do
      val supplied = hex("d90103a10282" + scripts)
      TxChecks.auxiliaryChecks(
        TestLedger.params(10),
        synthetic(supplied, valid),
        TestLedger.env.copy(decoder = decoder)
      ) match
        case Left(_: LedgerFailure.Unavailable) => ()
        case other => fail(s"incomplete auxiliary result became a predicate: $other")
  }
