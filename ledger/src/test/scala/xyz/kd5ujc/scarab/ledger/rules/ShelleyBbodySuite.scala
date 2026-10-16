// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  EpochGeometry,
  LedgerGlobals,
  PlutusStats,
  ProtocolParams,
  Redeemer,
  TestLedger,
  Tx,
  TxIn,
  Utxo,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** BBODY of the eras before Conway on its own (the transactions themselves are `ShelleyLedgerSuite`
  * 's): what the rule says of a block before and after its transactions, as [[BbodySuite]] does for
  * Conway: the commitment of the body, the limit of the execution units of the block (Alonzo on),
  * and the block counted for its issuer unless its slot is one of the overlay schedule
  * (`incrBlocks`, `isOverlaySlot`).
  */
class ShelleyBbodySuite extends FunSuite:
  private val geometry = EpochGeometry(Instant.EPOCH, 1000, 10000, 5, BigDecimal("0.05"))

  private val globals = Globals(
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    geometry
  )

  private def ok[A](either: Either[VocabError, A]): A =
    either.fold(e => fail(s"vocabulary: $e"), identity)

  private val issuer: KeyHash[StakePool] =
    ok(KeyHash.fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte))))

  private val commitment = BodyCommitment(100, Bytes.fromArray(Array.fill(32)(1.toByte)))

  /** The slot `offset` slots into epoch 5. */
  private def slot(offset: Int): BigInt = geometry.firstSlot(5) + offset

  private def state[E <: EraTag](pp: ProtocolParams, utxo: Utxo = Utxo.empty)(using
      ShelleyBasedEra[E]
  ): ShelleyBbodyState[E] =
    val empty = NewEpochState.empty[E](ok(EpochNo.fromBigInt(5)), pp)
    val ls = empty.es.ls
    ShelleyBbodyState(
      Counted(ls.copy[E](utxo = ls.utxo.copy[E](utxo = UtxoTables(utxo))), PlutusStats.zero),
      BlocksMade.empty
    )

  private def run[E <: EraTag](
      pp: ProtocolParams,
      txs: Vector[Tx],
      from: ShelleyBbodyState[E],
      at: Int = 0,
      declared: BodyCommitment = commitment
  )(using ShelleyBasedEra[E], PreConway[E]) =
    Sts(ShelleyBbody.sts[E])(
      globals,
      ShelleyBbodyEnv(
        TestLedger.env.copy(slot = slot(at), epoch = 5),
        pp,
        ChainAccountState(Coin.zero, Coin.zero)
      ),
      from,
      BbodySignal(txs, issuer, declared, commitment)
    )

  private def made[E <: EraTag](s: ShelleyBbodyState[E]): Option[Long] =
    s.blocksMade.blocks.get(issuer).map(_.bits)

  private val half = Rational(1, 2)

  test("a block outside the overlay schedule is counted for its issuer, once each") {
    val pp = params(5).copy(d = half)
    // with d = 1/2 the slots at an even offset of the epoch are the overlay's
    val first = run[Alonzo](pp, Vector.empty, state[Alonzo](pp), at = 1)
      .fold(f => fail(f.reason), identity)
    assertEquals(made(first), Some(1L))
    val second = run[Alonzo](pp, Vector.empty, first, at = 3).fold(f => fail(f.reason), identity)
    assertEquals(made(second), Some(2L))
  }

  test("a block in a slot of the overlay is not counted, in the eras that have an overlay") {
    for pv <- Vector(2, 3, 4, 5) do
      val pp = params(pv).copy(d = half)
      val after = run[Shelley](pp, Vector.empty, state[Shelley](pp), at = 2)
        .fold(f => fail(f.reason), identity)
      assertEquals(made(after), None, s"pv $pv")
  }

  test("Babbage has no overlay: every block is counted, whatever d the parameters keep") {
    val pp = params(7).copy(d = half)
    val after = run[Babbage](pp, Vector.empty, state[Babbage](pp), at = 2)
      .fold(f => fail(f.reason), identity)
    assertEquals(made(after), Some(1L))
  }

  test("isOverlaySlot is the reference's: the slots of the schedule spread over the epoch") {
    // d = 1/5: one slot in five, the first of each five
    val overlay = (0 until 20).filter(i => ShelleyBbody.isOverlaySlot(0, Rational(1, 5), i))
    assertEquals(overlay, Vector(0, 5, 10, 15))
    assert((0 until 100).forall(i => !ShelleyBbody.isOverlaySlot(0, Rational.zero, i)))
    assert((0 until 100).forall(i => ShelleyBbody.isOverlaySlot(0, Rational(1), i)))
  }

  test("a body that is not what the header commits to is refused") {
    val pp = params(7)
    val other = BodyCommitment(101, commitment.hash)
    assertEquals(
      run[Babbage](pp, Vector.empty, state[Babbage](pp), declared = other),
      Left(BlockFailure.BodyCommitment)
    )
  }

  private def redeeming(units: ExUnits, id: String): Tx =
    tx(
      body(id, Vector(txIn(1)), Vector.empty, 0),
      Vector.empty,
      Witnesses.empty.copy(redeemers = Vector(Redeemer(0, 0, Bytes.empty, units)))
    )

  test("from Alonzo the execution units of the block are limited; before, they are not") {
    val pp = params(5)
    val half = ExUnits(pp.maxBlockExUnits.mem / 2 + 1, 1)
    val txs = Vector(redeeming(half, "a"), redeeming(half, "b"), redeeming(half, "c"))
    assertEquals(
      run[Alonzo](pp, txs, state[Alonzo](pp)),
      Left(BlockFailure.BlockExUnitsTooBig(half + half, pp.maxBlockExUnits))
    )
    assertEquals(Bbody.exUnitsExceeded(params(4), txs), None)
  }

  test("a block of 10,000 transactions runs on a 256 KiB stack") {
    val alice = key()
    val ada = BigInt(1000000)
    val inputs = Vector.tabulate(10000)(i =>
      TxIn(Bytes.fromArray(Array.tabulate[Byte](32)(j => (i >> (8 * (j % 4))).toByte)), i)
    )
    val utxo = Utxo.empty ++ inputs.map(_ -> out(alice.address(), 10 * ada))
    val txs = inputs.zipWithIndex.map { (in, i) =>
      tx(body(s"t$i", Vector(in), Vector(out(alice.address(), 10 * ada - 200)), 200), Vector(alice))
    }
    val pp = params(2)
    var result: Option[Either[Throwable, Either[BlockFailure, ShelleyBbodyState[Shelley]]]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(run[Shelley](pp, txs, state[Shelley](pp, utxo)))
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.getOrElse(fail("the thread did not finish")).fold(throw _, identity) match
      case Left(f) => fail(f.reason)
      case Right(after) =>
        assertEquals(after.ledger.state.utxo.fees.value, BigInt(200) * 10000)
        assertEquals(after.ledger.state.utxo.utxo.utxo.count, 10000L)
  }
