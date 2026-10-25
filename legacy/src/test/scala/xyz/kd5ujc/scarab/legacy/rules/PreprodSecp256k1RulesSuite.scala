// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy.*
import cats.Id
import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.*
import munit.FunSuite
import xyz.kd5ujc.scarab.legacy.FlatTestLedger.*

import TestLedger.*

/** Preprod transaction 97b31e35 (block 3,498,869, epoch 217): a Plutus V2 spend whose script calls
  * `verifyEcdsaSecp256k1Signature` with an all-zero signature; the reference answers `False`,
  * Scalus 1.2.0 and 1.3.0 fail (`Secp256k1`).
  */
class PreprodSecp256k1RulesSuite extends FunSuite:
  private val rs = RuleSet
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-97b31e35.json"))
  private val ada = BigInt(1000000)

  private def world: LedgerState =
    val utxo = c.spent ++ c.references ++ c.collateral
    val params = TestLedger
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
    val base = state(c.protocolMajor, utxo.toVector).copy(params = params, epoch = 217)
    base.copy(reserves = Supply - (base.total - base.reserves))

  // preprod: the Shelley era starts at slot 86400, 1 s slots, at POSIX time 1655769600
  private def env(evaluator: ScriptEvaluator[Id]) = TxEnv(
    slot = c.slot,
    epoch = 217,
    network = 0,
    firstSlotNextEpoch = c.slot + 100,
    stabilityWindow = 129600,
    clock = Some(
      xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => (BigInt(1655769600) + slot - 86400) * 1000)
    ),
    evaluator = evaluator
  )

  test("the recorded transaction is a Conway spend, valid on chain") {
    assert(c.tx.isValid)
    assertEquals(c.tx.witnesses.redeemers.size, 3)
  }

  test(
    "the Conway rules accept it: the script gets False, not an error, for the all-zero signature"
  ) {
    val after =
      rs.tx(world, c.tx, env(ScalusEvaluator.default)).fold(f => fail(f.message), identity)
    assertEquals(after.plutus.succeeded, 1L)
    assertEquals(after.fees, c.tx.body.fee)
  }
