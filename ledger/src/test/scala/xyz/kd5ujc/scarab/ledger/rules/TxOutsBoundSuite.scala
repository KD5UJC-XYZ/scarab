// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  PredicateFailure,
  TestLedger,
  TxChecks,
  TxIn,
  TxOut,
  UtxoFailure,
  Utxo as NodeUtxo
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** A transaction's outputs are numbered with the bounded `TxIx`, a `Word16`
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:918-921`, cardano-ledger f649f975):
  * `txouts` creates the first 65,536 and no more (`State/UTxO.hs:146-158`), the instant stake takes
  * what `txouts` creates (`Shelley/Rules/Utxo.hs:613-628`), the collateral return's index is the
  * one after the last output or `maxBound` (`Babbage/Collateral.hs:43-61`), and Allegra's and
  * Mary's output checks read what `txouts` creates (`Allegra/Rules/Utxo.hs:205-216`).
  */
class TxOutsBoundSuite extends FunSuite:
  private val Count = 65537
  private val owner = TestLedger.key()
  private val staking = TestLedger.key()
  private val out = TestLedger.out(owner.baseAddress(staking), 2_000_000)
  private val spent = TxIn(Bytes(Vector.fill(32)(7.toByte)), 0)
  private val held = TestLedger.out(owner.baseAddress(staking), 3_000_000)

  test("a TxIx is a position up to maxBound, 65,535; a count past it has none") {
    assertEquals(TxIx.maxBound.index, 65535)
    assertEquals(TxIx.txIxFromIntegral(-1), None)
    assertEquals(TxIx.txIxFromIntegral(0).map(_.index), Some(0))
    assertEquals(TxIx.txIxFromIntegral(65535).map(_.index), Some(65535))
    assertEquals(TxIx.txIxFromIntegral(65536), None)
  }

  test("txouts creates the first 65,536 outputs, at indices 0 to 65,535") {
    val id = Bytes(Vector.fill(32)(1.toByte))
    val created = TxIn.txouts(id, Vector.fill(Count)(out))
    assertEquals(created.size, 65536)
    assertEquals(created.map(_._1.index), Vector.tabulate(65536)(BigInt(_)))
    assert(created.forall(_._1.id == id))
  }

  test("a collateral return's index follows the last output, or is maxBound past it") {
    val id = Bytes(Vector.fill(32)(2.toByte))
    assertEquals(
      Vector(0, 3, 65535, 65536, 65537).map(n => TxIn.mkCollateralTxIn(id, n).index),
      Vector(0, 3, 65535, 65535, 65535).map(BigInt(_))
    )
    assertEquals(TxIn.collOuts(id, 65536, Some(out)), Some(TxIn(id, 65535) -> out))
    assertEquals(TxIn.collOuts(id, 65536, Option.empty[TxOut]), None)
  }

  private def body(outputs: Vector[TxOut], collateralReturn: Option[TxOut] = None) =
    TestLedger.body(
      "wide",
      Vector(spent),
      outputs,
      0,
      collateral = Vector(spent),
      collateralReturn = collateralReturn
    )

  test("Babbage's UTXO creates and stakes the outputs txouts creates, 65,536 of 65,537") {
    val utxo = NodeUtxo.fromMap(Map(spent -> held))
    val state = Empty.ledgerState[Babbage](TestLedger.params(8)).utxo
    val start = state.copy[Babbage](utxo = UtxoTables(utxo))
    val outs = Vector.fill(Count)(out)
    val b = body(outs)
    val next = ShelleyUtxo
      .update[Babbage](
        start,
        StAnnTx(TestLedger.tx(b, Vector.empty), utxo),
        TxChecks.Collateral.none,
        0,
        0
      )
      .fold(e => fail(e.toString), identity)
    val created = next.utxo.utxo.toMap.keySet.filter(_.id == b.id)
    assertEquals(created.size, 65536)
    assertEquals(created.map(_.index).max, BigInt(65535))
    assertEquals(
      Right(next.instantStake),
      ShelleyInstantStakeOps.update(start.instantStake, outs.take(65536), Vector(held))
    )
  }

  test("Conway's UTXO creates and stakes the outputs txouts creates, 65,536 of 65,537") {
    val utxo = NodeUtxo.fromMap(Map(spent -> held))
    val state = Empty.ledgerState[Conway](TestLedger.params(10)).utxo
    val start = state.copy[Conway](utxo = UtxoTables(utxo))
    val outs = Vector.fill(Count)(out)
    val b = body(outs)
    val next = Utxo
      .update(
        start,
        StAnnTx(TestLedger.tx(b, Vector.empty), utxo),
        TxChecks.Collateral.none,
        0,
        0
      )
      .fold(e => fail(e.toString), identity)
    val created = next.utxo.utxo.toMap.keySet.filter(_.id == b.id)
    assertEquals(created.size, 65536)
    assertEquals(
      Right(next.instantStake),
      InstantStakeOps.update(start.instantStake, outs.take(65536), Vector(held))
    )
  }

  test("a failed transaction of 65,536 outputs returns its collateral at index 65,535") {
    val utxo = NodeUtxo.fromMap(Map(spent -> held))
    val start = Empty
      .ledgerState[Conway](TestLedger.params(10))
      .utxo
      .copy[Conway](utxo = UtxoTables(utxo))
    val b = body(Vector.fill(65536)(out), Some(out))
    val next = Utxo
      .update(
        start,
        StAnnTx(TestLedger.tx(b, Vector.empty, isValid = false), utxo),
        TxChecks.Collateral(Vector(held), 1_000_000),
        0,
        0
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(next.utxo.utxo.toMap.keySet.filter(_.id == b.id), Set(TxIn(b.id, 65535)))
  }

  test("Mary checks the outputs txouts creates, Shelley every output") {
    // the 65,537th output is below the minimum: past the last index, Mary does not see it
    val small = TestLedger.out(owner.address(), 1)
    val tx = TestLedger.tx(body(Vector.fill(65536)(out) :+ small), Vector.empty)
    val pp = TestLedger.params(4)
    def failures[E <: EraTag](using PreConway[E]) =
      Test
        .failures(UtxoTests.shelleyAfterPpup[E](ReferenceBuild.Release11_1, pp, tx))
        .map(_.iterator.toList)
    assertEquals(failures[Mary], None)
    assertEquals(
      failures[Shelley],
      Some(List(PredicateFailure.utxo(UtxoFailure.OutputTooSmall(Vector(small)))))
    )
  }
