// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Mismatch,
  PredicateFailure,
  TestLedger,
  TxOut,
  Utxo as NodeUtxo,
  UtxoFailure,
  UtxowFailure,
  Value
}
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.state.*

/** The value consumed and produced of Conway's UTXO with a mint field: the minted assets consumed
  * and the burned produced (`getConsumedMaryValue` and `burnedMultiAssets`, cardano-ledger f649f975
  * `Mary/UTxO.hs:72-97`), not the signed field on one side.
  */
class ValueConservationSuite extends FunSuite:
  private val policy = Bytes.fromArray(Array.fill(28)(5.toByte))
  private val name = Bytes.fromArray(Array(1.toByte))
  private val owner = TestLedger.key()

  private def valueFailure(fs: Vector[PredicateFailure]) = fs.collect {
    case PredicateFailure.Utxow(UtxowFailure.Utxo(f @ UtxoFailure.ValueNotConserved(_))) => f
  }

  test("burning two tokens of one held reports one consumed and two produced") {
    val held = TxOut(owner.address(), Value(10, Map(policy -> Map(name -> BigInt(1)))))
    val b = body(
      "burn",
      Vector(txIn(1)),
      Vector(out(owner.address(), 9)),
      1,
      mint = Map(policy -> Map(name -> BigInt(-2)))
    )
    val t = tx(b, Vector(owner))
    val pp = params(10)
    val cert = NewEpochState.empty[Conway](EpochNo.fromUnsigned(5), pp).es.ls.cert
    val test = Utxo.validate(
      UtxoEnv(TestLedger.env, pp, cert),
      StAnnTx(t, NodeUtxo(Map(txIn(1) -> held))),
      0,
      0
    )
    assertEquals(
      valueFailure(Test.failures(test).fold(Vector.empty)(_.toChain.toVector)),
      Vector(
        UtxoFailure.ValueNotConserved(
          Mismatch(
            Value(10, Map(policy -> Map(name -> BigInt(1)))),
            Value(10, Map(policy -> Map(name -> BigInt(2))))
          )
        )
      )
    )
  }

  test("the minted assets are the positive quantities, the burned the negative ones negated") {
    val other = Bytes.fromArray(Array(2.toByte))
    val mint = Map(policy -> Map(name -> BigInt(3), other -> BigInt(-4)))
    assertEquals(Value.minted(mint), Value(0, Map(policy -> Map(name -> BigInt(3)))))
    assertEquals(Value.burned(mint), Value(0, Map(policy -> Map(other -> BigInt(4)))))
    assertEquals(Value.burned(Map(policy -> Map(name -> BigInt(1)))), Value.zero)
  }
