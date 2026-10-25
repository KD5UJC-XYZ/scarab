// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.data.{Kleisli, StateT}
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  BlockFailure,
  EpochGeometry,
  LedgerGlobals,
  PlutusStats,
  Redeemer,
  TestLedger,
  Tx,
  TxIn,
  TxOut,
  Utxo,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.testByteStrings
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** BBODY of Conway on its own: what the rule says of a block before and after its transactions (the
  * transactions themselves are `ConwayLedgerSuite` 's): the commitment of the body, the limits of
  * the block, the block counted for its issuer.
  */
class BbodySuite extends FunSuite:
  private val globals = Globals(
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    EpochGeometry(Instant.EPOCH, 1000, 10000, 5, BigDecimal("0.05"))
  )

  private def ok[A](either: Either[VocabError, A]): A =
    either.fold(e => fail(s"vocabulary: $e"), identity)

  private val issuer: KeyHash[StakePool] =
    ok(KeyHash.fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(7.toByte))))

  private val commitment = BodyCommitment(100, Bytes.fromArray(Array.fill(32)(1.toByte)))

  private def state(pp: xyz.kd5ujc.scarab.ledger.ProtocolParams, utxo: Utxo = Utxo.empty) =
    val empty = NewEpochState.empty[Conway](ok(EpochNo.fromBigInt(5)), pp)
    val ls = empty.es.ls
    BbodyState(
      Counted(ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(utxo))), PlutusStats.zero),
      BlocksMade.empty
    )

  private def run(
      pp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      txs: Vector[Tx],
      from: BbodyState,
      declared: BodyCommitment = commitment
  ) =
    Sts(Bbody)(
      globals,
      BbodyEnv(TestLedger.env, pp, ok(Coin.fromBigInt(0))),
      from,
      BbodySignal(txs, issuer, declared, commitment)
    )

  private val pp = params(10)

  private def redeeming(units: ExUnits, id: String): Tx =
    val b = body(id, Vector(txIn(1)), Vector.empty, 0)
    tx(
      b,
      Vector.empty,
      Witnesses.empty.copy(redeemers = Vector(Redeemer(0, 0, Bytes.empty, units)))
    )

  test("a block with no transactions is counted for its issuer, once each") {
    val first = run(pp, Vector.empty, state(pp)).fold(f => fail(f.reason), identity)
    assertEquals(first.blocksMade.blocks.get(issuer).map(_.bits), Some(1L))
    val second = run(pp, Vector.empty, first).fold(f => fail(f.reason), identity)
    assertEquals(second.blocksMade.blocks.get(issuer).map(_.bits), Some(2L))
  }

  test("a body that is not what the header commits to is refused") {
    val other = BodyCommitment(101, commitment.hash)
    assertEquals(run(pp, Vector.empty, state(pp), other), Left(BlockFailure.BodyCommitment))
  }

  test("the execution units of the block are limited, the failure naming the prefix that exceeds") {
    val half = ExUnits(pp.maxBlockExUnits.mem / 2 + 1, 1)
    val txs = Vector(redeeming(half, "a"), redeeming(half, "b"), redeeming(half, "c"))
    assertEquals(
      run(pp, txs, state(pp)),
      Left(BlockFailure.BlockExUnitsTooBig(half + half, pp.maxBlockExUnits))
    )
    assertEquals(Bbody.exUnitsExceeded(pp, txs.take(1)), None)
  }

  test("BBODY agrees with the reference stack on signed transactions and failures") {
    type R[A] = Either[BlockFailure, A]
    type S[A] = StateT[R, BbodyState, A]
    type K[A] = Kleisli[S, Globals, A]
    given Monad[K] = Kleisli.catsDataMonadForKleisli[S, Globals]
    val alice = key()
    val input = txIn(1)
    val utxo = Utxo.empty.updated(input, out(alice.address(), 10000000))
    val signed = tx(
      body("differential", Vector(input), Vector(out(alice.address(), 9999800)), 200),
      Vector(alice)
    )
    val from = state(pp, utxo)
    val env = BbodyEnv(TestLedger.env, pp, ok(Coin.fromBigInt(0)))
    val cases = Vector(
      (Vector(signed), commitment),
      (Vector(signed), commitment.copy(size = commitment.size + 1)),
      (Vector(signed, signed), commitment)
    )
    val results = cases.map { (txs, declared) =>
      val signal = BbodySignal(txs, issuer, declared, commitment)
      val onRuleT = Sts(Bbody)(globals, env, from, signal)
      val onStack = Bbody
        .transition[K](env, signal)(using summon, summon, summon, summon)
        .run(globals)
        .run(from)
        .map(_._1)
      assertEquals(onRuleT, onStack)
      onRuleT
    }
    assert(results.head.isRight)
    assertEquals(results(1), Left(BlockFailure.BodyCommitment))
    assert(results(2).isLeft, "the second transaction spends the first transaction's spent input")
  }

  /** The output of a transaction holding a reference script of `size` bytes (Plutus V3). */
  private def withScript(address: Address, size: Int): TxOut =
    def head(major: Int, n: Int): Vector[Byte] =
      if n < 24 then Vector(((major << 5) | n).toByte)
      else if n < 256 then Vector(((major << 5) | 24).toByte, n.toByte)
      else Vector(((major << 5) | 25).toByte, (n >> 8).toByte, n.toByte)
    def cborBytes(b: Vector[Byte]) = head(2, b.size) ++ b
    val wrapped = head(4, 2) ++ Vector(0x03.toByte) ++ cborBytes(Vector.fill(size)(0x42.toByte))
    val raw = head(5, 3) ++ Vector(0x00.toByte) ++ cborBytes(address.raw.value) ++
      Vector(0x01.toByte) ++ head(0, 5) ++ Vector(0x03.toByte, 0xd8.toByte, 0x18.toByte) ++
      cborBytes(wrapped)
    TxOut.fromBytes(Bytes(raw)).fold(e => fail(e.toString), identity)

  test(
    "the reference scripts of a block: the set as the block meets it to version 10, with the outputs of the transactions before from 11"
  ) {
    val alice = key()
    val held = TxIn(Bytes.fromArray(Array.fill(32)(9.toByte)), 0)
    val utxo = Utxo.empty.updated(held, withScript(alice.address(), 300))
    val first = tx(
      body("first", Vector(txIn(1)), Vector(withScript(alice.address(), 500)), 0),
      Vector.empty
    )
    val created = TxIn(first.body.id, 0)
    // the second transaction names the output of the first and the output the set holds
    val second = tx(
      body("second", Vector(held), Vector.empty, 0, referenceInputs = Vector(created)),
      Vector.empty
    )
    assertEquals(Bbody.refScriptsSize(pp, utxo, Vector(first, second)), 300L)
    assertEquals(
      Bbody.refScriptsSize(pp.copy(protocolMajor = 11), utxo, Vector(first, second)),
      800L
    )
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
    var result: Option[Either[Throwable, Either[BlockFailure, BbodyState]]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(run(pp.copy(maxBlockExUnits = ExUnits(1, 1)), txs, state(pp, utxo)))
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
