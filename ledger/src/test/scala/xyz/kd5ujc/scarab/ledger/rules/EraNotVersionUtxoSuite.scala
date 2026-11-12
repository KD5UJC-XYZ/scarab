// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Each era's UTXO runs its own checks whatever the protocol version (cardano-ledger f649f975):
  * Shelley's `utxoInductive` (`Shelley/Rules/Utxo.hs:336-403`), Allegra's and Mary's
  * `utxoTransition` (`Allegra/Rules/Utxo.hs:168-216`), Alonzo's (`Alonzo/Rules/Utxo.hs:505-568`)
  * and Babbage's `babbageUtxoValidation` (`Babbage/Rules/Utxo.hs:340-412`), each with the minimum
  * of its own `getMinCoinTxOut`. A chain that starts an era by epoch (`Test<Era>HardForkAtEpoch`)
  * keeps the version of the era before it, so each era is checked here at a version it does not
  * usually run at, through the rule's entry point.
  */
class EraNotVersionUtxoSuite extends FunSuite:
  private val alice = TestLedger.key()
  private val funds = BigInt(1000000000000L)
  private val fee = BigInt(200)
  private val input = TestLedger.txIn(0)
  private val slot = TestLedger.env.slot
  private val policy = Bytes(Vector.fill(28)(7.toByte))
  private val other = Bytes(Vector.fill(28)(8.toByte))

  /** `count` assets of one each under `p`, with names of 32 bytes. */
  private def assets(p: Bytes, count: Int): Map[Bytes, Map[Bytes, BigInt]] =
    Map(
      p -> (0 until count).map(i => Bytes(Vector.fill(31)(0.toByte) :+ i.toByte) -> BigInt(1)).toMap
    )

  /** A value of `count` assets: 120 of them serialise to more than 4000 bytes and less than 5000.
    */
  private def big(p: Bytes, count: Int = 120): Value = Value(0, assets(p, count))

  private def out(coin: BigInt, value: Value = Value.zero): TxOut =
    TxOut(alice.address(), Value(coin, value.assets))

  /** The failures UTXO records for a transaction spending one input that holds `held`, in order.
    */
  private def failures[E <: EraTag](
      pp: ProtocolParams,
      held: Value,
      outputs: Vector[TxOut],
      ttl: Option[BigInt] = None,
      validityStart: Option[BigInt] = None,
      collateral: Vector[(TxIn, TxOut)] = Vector.empty,
      returned: Option[TxOut] = None,
      redeemers: Vector[Redeemer] = Vector.empty
  )(using pre: PreConway[E]): List[PredicateFailure] =
    val utxo = Utxo.fromMap(((input -> TxOut(alice.address(), held)) +: collateral).toMap)
    val body = TestLedger.body(
      "era-not-version",
      Vector(input),
      outputs,
      fee,
      ttl = ttl,
      validityStart = validityStart,
      collateral = collateral.map(_._1),
      collateralReturn = returned
    )
    val tx = TestLedger.tx(body, Vector(alice), Witnesses.empty.copy(redeemers = redeemers))
    val cert = pre.cert.get(Empty.ledgerState[E](pp)(using pre.era))
    Test
      .failures(ShelleyUtxo.validate[E](UtxoEnv(TestLedger.env, pp, cert), StAnnTx(tx, utxo), 0, 0))
      .fold(List.empty[PredicateFailure])(_.iterator.toList)

  private def utxo(f: UtxoFailure*): List[PredicateFailure] = f.toList.map(PredicateFailure.utxo)

  test("Shelley at version 3 keeps its inclusive time to live (Shelley/Rules/Utxo.hs:414-423)") {
    val pp = TestLedger.params(3)
    val outs = Vector(out(funds - fee))
    assertEquals(failures[Shelley](pp, Value.coin(funds), outs, ttl = Some(slot)), Nil)
    assertEquals(
      failures[Shelley](pp, Value.coin(funds), outs, ttl = Some(slot - 1)),
      utxo(UtxoFailure.ExpiredUTxO(Mismatch(slot - 1, slot)))
    )
  }

  test("Allegra at version 2 runs the validity interval (Allegra/Rules/Utxo.hs:176,225-235)") {
    val pp = TestLedger.params(2)
    val outs = Vector(out(funds - fee))
    // the upper bound is exclusive (`inInterval`, `Allegra/Scripts.hs:504-509`)
    assertEquals(
      failures[Allegra](pp, Value.coin(funds), outs, ttl = Some(slot)),
      utxo(UtxoFailure.OutsideValidityInterval(None, Some(slot), slot))
    )
    assertEquals(failures[Allegra](pp, Value.coin(funds), outs, ttl = Some(slot + 1)), Nil)
    assertEquals(
      failures[Allegra](pp, Value.coin(funds), outs, validityStart = Some(slot + 1)),
      utxo(UtxoFailure.OutsideValidityInterval(Some(slot + 1), None, slot))
    )
  }

  test("Mary at version 2 refuses a value over 4000 bytes (Allegra/Rules/Utxo.hs:211,237-254)") {
    val pp = TestLedger.params(2)
    val value = big(policy)
    assert(value.serializedSize > 4000)
    val o = out(funds - fee, value)
    assertEquals(
      failures[Mary](pp, Value(funds, value.assets), Vector(o)),
      utxo(UtxoFailure.OutputTooBig(Vector((o.value.serializedSize, 4000, o))))
    )
  }

  test("Mary at version 2 never finds an output with assets too small (Mary/TxOut.hs:33)") {
    // `Val.pointwise (<)` against an ada-only minimum is false for any asset
    // (`Allegra/Rules/Utxo.hs:256-270`), whatever `scaledMinDeposit` says
    val pp = TestLedger.params(2)
    val value = big(policy, 1)
    val small = out(1, value)
    val outs = Vector(small, out(funds - fee - 1))
    assertEquals(failures[Mary](pp, Value(funds, value.assets), outs), Nil)
    assertEquals(
      failures[Mary](pp, Value.coin(funds), Vector(out(1), out(funds - fee - 1))),
      utxo(UtxoFailure.OutputTooSmall(Vector(out(1))))
    )
  }

  test("Mary at version 5 lists the outputs too big in their order, against 4000 bytes") {
    val pp = TestLedger.params(5)
    val (a, b) = (big(policy, 118), big(other, 118))
    assert(a.serializedSize > 4000 && a.serializedSize < pp.maxValueSize)
    val outs = Vector(out(1000000, a), out(funds - fee - 1000000, b))
    assertEquals(
      failures[Mary](pp, Value(funds, a.assets ++ b.assets), outs),
      utxo(UtxoFailure.OutputTooBig(outs.map(o => (o.value.serializedSize, 4000, o))))
    )
  }

  test("Alonzo at version 4 takes its minimum from coinsPerUTxOWord (Alonzo/TxOut.hs:370-372)") {
    // the Alonzo translation leaves `minUTxOValue` at 0
    val pp = TestLedger.params(4).copy(minUTxOValue = 0)
    val small = out(1000)
    val outs = Vector(small, out(funds - fee - 1000))
    assertEquals(
      failures[Alonzo](pp, Value.coin(funds), outs),
      utxo(UtxoFailure.OutputTooSmall(Vector(small)))
    )
  }

  test("Alonzo at version 4 limits a value by maxValSize, last output first") {
    // `validateOutputTooBigUTxO` (`Alonzo/Rules/Utxo.hs:400-424`)
    val pp = TestLedger.params(4).copy(minUTxOValue = 0)
    val value = big(policy)
    assert(value.serializedSize > 4000 && value.serializedSize < pp.maxValueSize)
    val held = Value(funds, value.assets)
    assertEquals(failures[Alonzo](pp, held, Vector(out(funds - fee, value))), Nil)
    val tight = pp.copy(maxValueSize = 4000)
    val (a, b) = (big(policy, 118), big(other, 118))
    val outs = Vector(out(100000000, a), out(funds - fee - 100000000, b))
    assertEquals(
      failures[Alonzo](tight, Value(funds, a.assets ++ b.assets), outs),
      utxo(UtxoFailure.OutputTooBig(outs.reverse.map(o => (o.value.serializedSize, 4000, o))))
    )
  }

  /** Babbage's parameters at version 6, as the Babbage translation leaves them
    * (`Babbage/PParams.hs:389-391`).
    */
  private val babbageAt6 =
    TestLedger.params(6).copy(coinsPerUTxOByte = 4310, coinsPerUTxOWord = 0, minUTxOValue = 0)

  test("Babbage at version 6 takes its minimum from coinsPerUTxOByte (Babbage/TxOut.hs:230)") {
    val small = out(1000)
    val outs = Vector(small, out(funds - fee - 1000))
    // `validateOutputTooSmallUTxO` (`Babbage/Rules/Utxo.hs:302-321`) reports the minimum too
    assertEquals(
      failures[Babbage](babbageAt6, Value.coin(funds), outs),
      utxo(UtxoFailure.BabbageOutputTooSmall(Vector(small -> (160 + small.ledgerSize) * 4310)))
    )
  }

  test("Babbage at version 6 takes the collateral return off the collateral") {
    // `feesOK` (`Babbage/Rules/Utxo.hs:170-192`, `validateTotalCollateral`): the balance is the
    // collateral less its return, 100 here against the 300 that 150% of the fee needs
    val held = TestLedger.txIn(1) -> out(1000000000)
    val returned = out(1000000000 - 100)
    val redeemer = Redeemer(0, 0, Bytes.empty, ExUnits.zero)
    assertEquals(
      failures[Babbage](
        babbageAt6,
        Value.coin(funds),
        Vector(out(funds - fee)),
        collateral = Vector(held),
        returned = Some(returned),
        redeemers = Vector(redeemer)
      ),
      utxo(UtxoFailure.InsufficientCollateral(100, 300))
    )
  }
