// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.protocol.Era

/** Synthetic BBODY controls use newly signed independent funded inputs. They do not turn the
  * API-derived oracle transactions into signed reference-admission evidence.
  */
class ScriptResultBlockSuite extends FunSuite:
  import ContextReferenceCases.{field, Recorded}
  private val cases =
    parse(Files.readString(Path.of("fixtures/reference/script-results/aggregation.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .map(Recorded(_))
      .filter(_.name.startsWith("api-"))
  private val owner = TestLedger.key()

  private def body(
      recorded: Recorded,
      valid: Boolean,
      evaluator: ScriptEvaluator[Id],
      seen: Phase2.Observation => Unit
  ): EraBlock.Body =
    val pp = recorded.params.copy(
      minFeeA = 0,
      minFeeB = 0,
      coinsPerUTxOByte = 0,
      priceMem = Rational.zero,
      priceSteps = Rational.zero
    )
    val transactions = Vector.tabulate(2) { index =>
      val b = TestLedger.body(
        s"${recorded.name}-$index",
        Vector(TestLedger.txIn(index * 2)),
        Vector(TxOut(owner.address(), Value(9000000, recorded.tx.body.mint))),
        1000000,
        mint = recorded.tx.body.mint,
        collateral = Vector(TestLedger.txIn(index * 2 + 1))
      )
      val witnesses = recorded.tx.witnesses.copy(vkeys = Vector.empty, raw = None)
      val unsigned = TestLedger.tx(b, Vector.empty, witnesses, valid)
      val integrity =
        ScriptIntegrity.hash(unsigned, pp, recorded.tx.witnesses.scripts.map(_.kind).toSet)
      TestLedger.tx(b.copy(scriptDataHash = integrity), Vector(owner), witnesses, valid)
    }
    val utxo = Utxo.empty ++ transactions.flatMap(t =>
      (t.body.inputs ++ t.body.collateral).map(_ -> TestLedger.out(owner.address(), 10000000))
    )
    val initial =
      NewEpochState.empty[Conway](EpochNo.fromBigInt(5).fold(e => fail(e.toString), identity), pp)
    val state: EraNes = EraNes.InEra(
      ShelleyBasedEra.SConway,
      initial.copy(es =
        initial.es.copy(ls =
          initial.es.ls.copy(utxo = initial.es.ls.utxo.copy(utxo = UtxoTables(utxo)))
        )
      )
    )
    val header = PreparedBlockFixture.header
    val block = Block.Shelley(
      Era.Conway,
      header,
      transactions.map(t => Block.Transaction(t.body.raw, Bytes.empty, None, t.isValid)),
      100,
      Bytes.empty
    )
    EraBlock.program(
      EraBlock.Context(
        TestLedger.globals,
        (_, _) => Era.Conway,
        evaluator = evaluator,
        observer = Some(seen)
      )
    )(state, header, block, transactions.map(Right(_)), PlutusStats.zero)

  cases.foreach { recorded =>
    for valid <- Vector(true, false) do
      test(
        s"${recorded.name}, tag=$valid: full BBODY serial/prepared states, counters and observation order"
      ) {
        val serialSeen = Vector.newBuilder[Phase2.Observation]
        val preparedSeen = Vector.newBuilder[Phase2.Observation]
        val requests = body(recorded, valid, ScalusEvaluator.default, _ => ()).prepare.jobs
          .flatMap(_.requests.fold(e => fail(e.toString), identity))
        val results = requests.map { case (_, request) =>
          request -> ScalusEvaluator.default.evaluate(request)
        }.toMap
        val evaluator: ScriptEvaluator[Id] =
          request => results.getOrElse(request, fail("foreign request"))
        val serial = body(recorded, valid, evaluator, o => { serialSeen += o; () }).serial
        val planned = body(recorded, valid, evaluator, o => { preparedSeen += o; () }).prepare
        assertEquals(planned.resolve(planned.jobs.reverse.map(planned.execute)), Right(serial))
        val observed = serialSeen.result()
        assertEquals(
          preparedSeen.result().map(_.copy(clock = None)),
          observed.map(_.copy(clock = None))
        )
        assert(observed.nonEmpty, s"ordinary rules did not reach phase 2: $serial")
        val failed = field[Vector[io.circe.Json]](recorded.oracle, "failures").nonEmpty
        serial match
          case EraBlock.Outcome.Applied(_, stats) =>
            assertEquals(valid, !failed)
            assertEquals(stats.scripts, 6L)
            assertEquals(planned.jobs.size, 2)
          case EraBlock.Outcome.Refused(
                BlockFailure.Tx(
                  LedgerFailure.Rejected(
                    _,
                    PredicateFailure.Utxow(
                      UtxowFailure.Utxo(UtxoFailure.Utxos(_: UtxosFailure.ValidationTagMismatch))
                    )
                  )
                )
              ) =>
            assertEquals(valid, failed)
            assertEquals(observed.size, 1)
          case other => fail(s"did not reach a ledger verdict: $other")
      }
  }

  private val mixed = cases.find(_.name == "api-v2-failpassfail").getOrElse(fail("no mixed case"))
  private val limit = EvalFailure.Unsupported(BackendFailure.NoResult)

  test("prepared and serial landing preserve a backend outage after an earlier script failure") {
    val evaluator: ScriptEvaluator[Id] = request =>
      val result = ScalusEvaluator.default.evaluate(request)
      if result.isSuccess then EvalResult.Failure(limit, ExUnits.zero, Vector.empty) else result
    for valid <- Vector(true, false) do
      val program = body(mixed, valid, evaluator, _ => ())
      val serial = program.serial
      val plan = program.prepare
      assertEquals(plan.resolve(plan.jobs.map(plan.execute)), Right(serial))
      serial match
        case EraBlock.Outcome.Refused(BlockFailure.Tx(_: LedgerFailure.Unavailable)) => ()
        case other => fail(s"outage became consensus refusal: $other")
  }

  test("a defect after the first script failure is original in serial and prepared landing") {
    val defect = new IllegalStateException("mixed transaction backend defect")
    val evaluator: ScriptEvaluator[Id] = request =>
      val result = ScalusEvaluator.default.evaluate(request)
      if result.isSuccess then throw defect else result
    val program = body(mixed, false, evaluator, _ => ())
    assert(intercept[IllegalStateException](program.serial) eq defect)
    val plan = program.prepare
    assert(plan.resolve(plan.jobs.map(plan.execute)).left.exists(_.cause eq defect))
  }
