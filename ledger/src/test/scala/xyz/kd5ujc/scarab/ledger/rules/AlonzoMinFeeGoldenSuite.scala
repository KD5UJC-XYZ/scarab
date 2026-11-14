// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Mismatch,
  PredicateFailure,
  ProtocolParams,
  TestLedger,
  Tx,
  Utxo as NodeUtxo,
  UtxoFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

/** The minimum fee of an Alonzo transaction counts its scripts' execution units at the prices of
  * the protocol parameters (`alonzoMinFeeTx` and `totExUnits`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:445-464`, cardano-ledger f649f975), over the
  * size of `[body, witnesses, auxiliary data]` as received (`sizeAlonzoTxF` and
  * `toCBORForSizeComputation`, `:324-332`, `:432-443`).
  *
  * The upstream golden "Alonzo Block"
  * (`eras/alonzo/test-suite/test/Test/Cardano/Ledger/Alonzo/Golden.hs:230-268`) reads the block of
  * cardano-node issue 4228, whose first transaction is invalid with `FeeTooSmallUTxO (Coin 1006053)
  * (Coin 1001829)` and whose minimum fee "Nodes that had the bug determined ... to be 1001829", and
  * requires that minimum fee to be 1,006,053 lovelace under `emptyPParams` with a fee per byte of
  * 44, a fixed fee of 155,381 and prices 0.0577 (memory) and 0.0000721 (steps). That block is
  * `fixtures/reference/alonzo-min-fee/hex-block-node-issue-4228.cbor`, byte for byte. Scarab's UTXO
  * checks of Alonzo ([[UtxoChecks.of]]), which compute the minimum fee, must refuse the
  * transaction's fee of 1,001,829 against a minimum of 1,006,053.
  */
class AlonzoMinFeeGoldenSuite extends FunSuite:
  private val golden = Path.of("fixtures/reference/alonzo-min-fee/hex-block-node-issue-4228.cbor")

  /** The parameters the golden sets on `emptyPParams`, and the other fields as `emptyAlonzoPParams`
    * has them where Scarab has the field
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:481-509`, cardano-ledger f649f975: a
    * maximum transaction size of 2048, `nOpt` 100, a collateral percentage of 150, at most 5
    * collateral inputs, protocol version 5.0, every other field zero). The minimum fee reads none
    * of the other fields.
    */
  private val pp = ProtocolParams(
    minFeeA = 44,
    minFeeB = 155381,
    maxTxSize = 2048,
    maxBlockBodySize = 0,
    maxValueSize = 0,
    coinsPerUTxOByte = 0,
    keyDeposit = 0,
    poolDeposit = 0,
    eMax = 0,
    nOpt = 100,
    a0 = Rational.zero,
    rho = Rational.zero,
    tau = Rational.zero,
    minPoolCost = 0,
    protocolMajor = 5,
    drepDeposit = 0,
    drepActivity = 0,
    govActionDeposit = 0,
    govActionLifetime = 0,
    minFeeRefScriptCostPerByte = Rational.zero,
    maxBlockHeaderSize = 0,
    priceMem = Rational(577, 10000),
    priceSteps = Rational(721, 10000000),
    collateralPercentage = 150,
    maxCollateralInputs = 5
  )

  /** The first transaction of the block as Scarab's block decoder reads it. The file is the Alonzo
    * block alone; the decoder reads it as BlockFetch carries it, after its era tag (5).
    */
  private lazy val firstTx: Tx =
    val block = Bytes
      .fromHex(Files.readString(golden).trim)
      .fold(e => fail(e.toString), identity)
    val tagged = Bytes(Vector(0x82.toByte, 0x05.toByte)) ++ block
    Block.decodeShelley(tagged) match
      case Right(shelley: Block.Shelley) =>
        assertEquals(shelley.era, Era.Alonzo)
        assertEquals(shelley.transactions.size, 18)
        val first = shelley.transactions.headOption.getOrElse(fail("no transaction"))
        Tx.fromBlock(first, Era.Alonzo).fold(e => fail(e.toString), identity)
      case other => fail(s"not an Alonzo block: $other")

  private def feeFailures(tx: Tx): Vector[UtxoFailure] =
    val cert =
      PreConway[Alonzo].cert.get(NewEpochState.empty[Alonzo](EpochNo.fromUnsigned(5), pp).es.ls)
    val checks = UtxoChecks.of[Alonzo](
      UtxoEnv(TestLedger.env, pp, cert),
      StAnnTx(tx, NodeUtxo(Map.empty)),
      0,
      0
    )
    Test.failures(checks.beforePpup).fold(Vector.empty)(_.toChain.toVector).collect {
      case PredicateFailure.Utxow(UtxowFailure.Utxo(f @ UtxoFailure.FeeTooSmall(_))) => f
    }

  test("the transaction is the golden's: its id, fee, size and execution units") {
    assertEquals(
      firstTx.body.id.hex,
      "5fde6536f622b0be2f911250d313d6b03d76c204c7ca7372a30f30b7b0795c97"
    )
    assertEquals(firstTx.body.fee, BigInt(1001829))
    assertEquals(firstTx.size, 5238L)
    assertEquals(firstTx.witnesses.totalExUnits, ExUnits(7000000, 3000000000L))
  }

  test("the minimum fee is 1,006,053 lovelace, and the fee of 1,001,829 is refused (issue 4228)") {
    assertEquals(
      feeFailures(firstTx),
      Vector(UtxoFailure.FeeTooSmall(Mismatch(BigInt(1001829), BigInt(1006053))))
    )
  }
