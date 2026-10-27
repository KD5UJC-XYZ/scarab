// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import cats.Id
import java.nio.file.Path
import xyz.kd5ujc.scarab.plutus.*
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.rules.NativeRules
import xyz.kd5ujc.scarab.ledger.state.{Build, Coin, Conway, ConwayNewEpochState, NewEpochState}
import xyz.kd5ujc.scarab.protocol.Era

import TestLedger.*

class PreprodConwayRulesSuite extends FunSuite:
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
  private val ada = BigInt(1000000)

  private val params: ProtocolParams = TestLedger
    .params(c.protocolMajor)
    .copy(
      minFeeA = 44,
      minFeeB = 155381,
      maxTxSize = 16384,
      maxValueSize = 5000,
      coinsPerUTxOByte = 4310,
      keyDeposit = 2 * ada,
      priceMem = Rational(577, 10000),
      priceSteps = Rational(721, 10000000),
      maxTxExUnits = ExUnits(14000000, BigInt("10000000000")),
      maxBlockExUnits = ExUnits(62000000, BigInt("20000000000")),
      collateralPercentage = 150,
      maxCollateralInputs = 3,
      minFeeRefScriptCostPerByte = Rational(15),
      costModels = c.costModels
    )

  /** A Conway ledger at epoch 189 holding the outputs the transaction spends, references and puts
    * up as collateral, the reserves making up the rest of the supply.
    */
  private def world: NewEpochState[Conway] =
    assertEquals(NativeRules.eraOf(c.protocolMajor), Era.Conway, "a Conway transaction")
    val nes = Build.funded(Build.nes[Conway](189, params), c.spent ++ c.references ++ c.collateral)
    val reserves = Coin(Supply - nes.es.ls.utxo.utxo.utxo.coin)
    ConwayNewEpochState.reserves.replace(reserves)(nes)

  // preprod: the Shelley era starts at slot 86400, 1 s slots, at POSIX time 1655769600
  private def env(evaluator: ScriptEvaluator[Id]) = TxEnv(
    slot = c.slot,
    epoch = 189,
    network = 0,
    firstSlotNextEpoch = c.slot + 100,
    stabilityWindow = 129600,
    clock = Some(
      xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => (BigInt(1655769600) + slot - 86400) * 1000)
    ),
    evaluator = evaluator
  )

  test("the recorded transaction is a Conway Hydra fanout and a mint, both Plutus V2") {
    assert(c.tx.isValid)
    assertEquals(c.tx.witnesses.redeemers.size, 2)
    assertEquals(c.tx.body.fee, BigInt(2074032))
  }

  test(
    "the Conway rules accept it: the fanout script hashes outputs whose datums carry a constructor index of 2^64 - 1"
  ) {
    val after = NativeRules
      .txIn[Conway](world, c.tx, env(ScalusEvaluator.default))
      .fold(f => fail(f.message), identity)
    assertEquals(after.plutus.succeeded, 1L)
    assertEquals(after.plutus.scripts, 2L)
    assertEquals(after.state.es.ls.utxo.fees.value, c.tx.body.fee)
  }

  test("both scripts run within the units the redeemers declare") {
    val seen = scala.collection.mutable.ListBuffer.empty[(ExUnits, EvalResult)]
    val recording = new ScriptEvaluator[Id]:
      def evaluate(r: EvalRequest): EvalResult =
        val res = ScalusEvaluator.default.evaluate(r)
        seen += r.budget -> res
        res
    NativeRules.txIn[Conway](world, c.tx, env(recording)).fold(f => fail(f.message), identity)
    assertEquals(seen.size, 2)
    seen.foreach { (budget, res) =>
      assert(res.isSuccess, res)
      assert(res.spent.mem <= budget.mem && res.spent.steps <= budget.steps)
    }
  }

  test("the same transaction marked invalid is refused: its scripts succeed") {
    val invalid = c.tx.copy(isValid = false)
    NativeRules.txIn[Conway](world, invalid, env(ScalusEvaluator.default)) match
      case Left(
            LedgerFailure.Rejected.Single(
              _,
              PredicateFailure.Utxow(
                UtxowFailure.Utxo(
                  UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, _))
                )
              )
            )
          ) =>
        ()
      case other => fail(s"$other")
  }
