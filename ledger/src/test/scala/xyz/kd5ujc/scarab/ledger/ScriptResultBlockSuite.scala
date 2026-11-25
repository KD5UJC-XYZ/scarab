// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Id
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.rules.EraBlock
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.*
import xyz.kd5ujc.scarab.protocol.Era

/** Synthetic BBODY controls use newly signed independent funded inputs. They do not turn the
  * API-derived oracle transactions into signed reference-admission evidence.
  *
  * The Plutus V1 and V2 cases run on Babbage blocks, on the flat body (`EraBlock.Body`, the eras
  * before Conway); the Plutus V3 cases on Conway blocks, on the era run the node applies them with
  * (`ConwayBlockRun`), each transaction built with real CBOR and signed.
  */
class ScriptResultBlockSuite extends FunSuite:
  import ContextReferenceCases.{field, Recorded}
  private val recordedApi =
    parse(Files.readString(Path.of("fixtures/reference/script-results/aggregation.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .map(Recorded(_))
      .filter(_.name.startsWith("api-"))
  private val cases =
    recordedApi.filter(r => r.name.startsWith("api-v1-") || r.name.startsWith("api-v2-"))
  private val v3Cases = recordedApi.filter(_.name.startsWith("api-v3-"))
  private val owner = TestLedger.key()

  private def body(
      recorded: Recorded,
      valid: Boolean,
      evaluator: ScriptEvaluator[Id],
      seen: Phase2.Observation => Unit
  ): EraBlock.Body[Babbage] =
    val pp = recorded.params.copy(
      protocolMajor = 8,
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
        ScriptIntegrity.hash[Babbage](unsigned, pp, recorded.tx.witnesses.scripts.map(_.kind).toSet)
      TestLedger.tx(b.copy(scriptDataHash = integrity), Vector(owner), witnesses, valid)
    }
    val utxo = Utxo.empty ++ transactions.flatMap(t =>
      (t.body.inputs ++ t.body.collateral).map(_ -> TestLedger.out(owner.address(), 10000000))
    )
    val initial =
      NewEpochState.empty[Babbage](EpochNo.fromBigInt(5).fold(e => fail(e.toString), identity), pp)
    val state: NewEpochState[Babbage] = initial.copy[Babbage](es =
      initial.es.copy[Babbage](ls =
        initial.es.ls
          .copy[Babbage](utxo = initial.es.ls.utxo.copy[Babbage](utxo = UtxoTables(utxo)))
      )
    )
    val header = PreparedBlockFixture.babbageHeader
    val block = Block.Shelley(
      Era.Babbage,
      header,
      transactions.map(t => Block.Transaction(t.body.raw, Bytes.empty, None, t.isValid)),
      100,
      Bytes.empty
    )
    EraBlock.program(
      EraBlock.Context(
        TestLedger.globals,
        _ => None,
        0L,
        evaluator = evaluator,
        observer = Some(seen),
        decoder = ScarabEvaluator.defaultDecoder
      )
    )(state, header, block, transactions.map(Right(_)), PlutusStats.zero)

  cases.foreach { recorded =>
    for valid <- Vector(true, false) do
      test(
        s"${recorded.name}, tag=$valid: full BBODY serial/prepared states, counters and observation order"
      ) {
        val serialSeen = Vector.newBuilder[Phase2.Observation]
        val preparedSeen = Vector.newBuilder[Phase2.Observation]
        val requests = body(recorded, valid, ScarabEvaluator.default, _ => ()).prepare.jobs
          .flatMap(_.requests.fold(e => fail(e.toString), identity))
        val results = requests.map { case (_, request) =>
          request -> ScarabEvaluator.default.evaluate(request)
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
                  LedgerFailure.Rejected.Single(
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
  private val limit = EvalFailure.Unsupported(BackendFailure.StackExhausted)

  test("prepared and serial landing preserve a backend outage after an earlier script failure") {
    val evaluator: ScriptEvaluator[Id] = request =>
      val result = ScarabEvaluator.default.evaluate(request)
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
      val result = ScarabEvaluator.default.evaluate(request)
      if result.isSuccess then throw defect else result
    val program = body(mixed, false, evaluator, _ => ())
    assert(intercept[IllegalStateException](program.serial) eq defect)
    val plan = program.prepare
    assert(plan.resolve(plan.jobs.map(plan.execute)).left.exists(_.cause eq defect))
  }

  // ---- the Plutus V3 cases, on Conway's era run ------------------------------------------------

  /** A Conway block of two transactions, each spending a funded input of its own with collateral of
    * its own and minting what `recorded` mints with its scripts and redeemers, flagged `valid`,
    * signed by a key of the test's own; the ledger state that holds their inputs; and the
    * parameters, the recorded case's without fees or a deposit per byte.
    */
  private final case class EraBlockOf(
      raw: Bytes,
      state: LedgerState[Conway],
      params: era.PParams[Conway]
  )

  private def eraBlockOf(recorded: Recorded, valid: Boolean): EraBlockOf =
    import era.conway.ConwayImp
    import ConwayImp.B
    val pp = recorded.params.copy(
      minFeeA = 0,
      minFeeB = 0,
      coinsPerUTxOByte = 0,
      priceMem = Rational.zero,
      priceSteps = Rational.zero
    )
    val params = era.PParams[Conway](
      era.conway.ConwayPParams.fromStored(pp).fold(e => fail(s"parameters: $e"), identity)
    )
    val key = TestLedger.key()
    val address = B.keyAddress(key)
    def fields(raw: Bytes): Vector[(Node, Node)] = era.dijkstra.DijkstraCorpus.node(raw).value match
      case CborValue.Map(entries) => entries
      case other                  => fail(s"not a map: $other")
    def keyed(entries: Vector[(Node, Node)], k: Int): Boolean =
      entries.exists(_._1.value == CborValue.UInt(k))
    val recordedTx = era.dijkstra.DijkstraCorpus.node(recorded.original).value match
      case CborValue.Arr(Vector(b, w, _, _)) => (fields(b.original), fields(w.original))
      case other                             => fail(s"not a transaction: $other")
    val mint = recordedTx._1
      .collectFirst { case (k, v) if k.value == CborValue.UInt(9) => v }
      .getOrElse(fail("no mint"))
    // the recorded witnesses but its signatures: the scripts, the redeemers and any datum
    val more = recordedTx._2.collect {
      case (k, v) if !keyed(Vector(k -> v), 0) =>
        k.value match
          case CborValue.UInt(n) => n.toInt -> v
          case other             => fail(s"a witness key $other")
    }
    val funds = Vector.tabulate(4)(i => TxIn(Bytes(Vector.fill(32)((i + 1).toByte)), 0))
    val utxo = ConwayImp.outputs(funds.map(_ -> ConwayImp.Out(address, 10000000))*)
    val txs = Vector.tabulate(2) { index =>
      def withHash(integrity: Bytes): ConwayImp.Built =
        val body = B.body(
          0 -> B.set(ConwayImp.inNode(funds(index * 2))),
          1 -> B.arr(B.arr(B.bytes(address), B.arr(B.uint(9000000), mint))),
          2 -> B.uint(1000000),
          9 -> mint,
          11 -> B.bytes(integrity),
          13 -> B.set(ConwayImp.inNode(funds(index * 2 + 1)))
        )
        ConwayImp.Built(B.encode(body), B.encode(B.witnesses(body, Vector(key), more*)), valid)
      val integrity = era.ScriptIntegrity
        .mkScriptIntegrity[Conway, era.TxLevel.Top](
          params,
          withHash(Bytes(Vector.fill(32)(0.toByte))).era,
          Set(PlutusLanguage.V3)
        )(using
          era.EraTx.conway,
          era.AlonzoEraPParams.conway,
          era.EraTxWits.conway,
          era.EraTx.conway.bodyOf.top
        )
        .map(era.ScriptIntegrity.hashScriptIntegrity(_))
        .getOrElse(fail("no script integrity"))
      withHash(integrity)
    }
    val nes = Build.funded[Conway](Build.nes[Conway](0L, pp), utxo)
    EraBlockOf(ConwayImp.blockOf(10, txs), nes.es.ls, params)

  /** The node's evaluator, each request's result computed once, so that a prepared plan and the
    * serial run hold the same results (a failure's term compares by identity).
    */
  private def remembering: ScriptEvaluator[Id] =
    val results = scala.collection.concurrent.TrieMap.empty[EvalRequest, EvalResult]
    request => results.getOrElseUpdate(request, ScarabEvaluator.default.evaluate(request))

  private def eraProgram(
      block: EraBlockOf,
      evaluator: ScriptEvaluator[Id],
      seen: era.rules.ConwayPhase2.Observation => Unit
  ): era.rules.ConwayBlockRun.Body =
    era.conway.ConwayImp.program(block.raw, block.state, block.params)(
      evaluator,
      Some(era.rules.ConwayBlockRun.Observing(seen, None))
    )

  private val tagMismatch: era.rules.BbodyPredFailure[Conway] => Boolean =
    case era.rules.ConwayBbodyPredFailure.LedgersFailure(
          era.rules.ShelleyLedgersPredFailure.LedgerFailure(
            era.rules.ConwayLedgerPredFailure.ConwayUtxowFailure(
              era.rules.ConwayUtxowPredFailure.UtxoFailure(
                era.rules.ConwayUtxoPredFailure.UtxosFailure(
                  era.rules.ConwayUtxosPredFailure.ValidationTagMismatch(_, _)
                )
              )
            )
          )
        ) =>
      true
    case _ => false

  v3Cases.foreach { recorded =>
    for valid <- Vector(true, false) do
      test(
        s"${recorded.name}, tag=$valid: Conway's era run, serial and prepared: state, counters and observation order"
      ) {
        val block = eraBlockOf(recorded, valid)
        val evaluator = remembering
        val serialSeen = Vector.newBuilder[era.rules.ConwayPhase2.Observation]
        val preparedSeen = Vector.newBuilder[era.rules.ConwayPhase2.Observation]
        val serial = eraProgram(block, evaluator, o => { serialSeen += o; () }).serial
        val plan = eraProgram(block, evaluator, o => { preparedSeen += o; () }).prepare
        val planned = plan.resolve(plan.jobs.reverse.map(plan.execute))
        (planned, serial) match
          case (Right(Right(Right(p))), Right(Right(s))) =>
            assert(cats.Eq[LedgerState[Conway]].eqv(p.state.ls, s.state.ls))
            assertEquals(p.state.blocksMade, s.state.blocksMade)
            assertEquals(p.plutus, s.plutus)
          case (other, _) => assertEquals(other, Right(serial))
        val observed = serialSeen.result()
        assertEquals(preparedSeen.result(), observed)
        assert(observed.nonEmpty, s"ordinary rules did not reach phase 2: $serial")
        val failed = field[Vector[io.circe.Json]](recorded.oracle, "failures").nonEmpty
        serial match
          case Right(Right(applied)) =>
            assertEquals(valid, !failed)
            assertEquals(applied.plutus.scripts, 6L)
            assertEquals(plan.jobs.size, 2)
          case Right(Left(era.rules.ConwayBlockRun.Refusal.Failed(failures))) =>
            assertEquals(valid, failed)
            assert(failures.toChain.toVector.forall(tagMismatch), s"$failures")
            assertEquals(observed.size, 1)
          case other => fail(s"did not reach a ledger verdict: $other")
      }
  }
