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

/** The value consumed and produced of the UTXO rule with a mint field, in Conway (on the era types,
  * `conwayUtxoTransition`) and in Mary, Alonzo and Babbage ([[UtxoChecks]]): the minted assets
  * consumed and the burned produced (`getConsumedMaryValue` and `burnedMultiAssets`, cardano-ledger
  * f649f975 `Mary/UTxO.hs:72-97`), not the signed field on one side.
  */
class ValueConservationSuite extends FunSuite:
  private val policy = Bytes.fromArray(Array.fill(28)(5.toByte))
  private val name = Bytes.fromArray(Array(1.toByte))
  private val owner = TestLedger.key()

  private def valueFailure(fs: Vector[PredicateFailure]) = fs.collect {
    case PredicateFailure.Utxow(UtxowFailure.Utxo(f @ UtxoFailure.ValueNotConserved(_))) => f
  }

  test("burning two tokens of one held reports one consumed and two produced, in Conway") {
    import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
    import xyz.kd5ujc.scarab.ledger.testByteStrings
    import ConwayTestKit.Cb.*
    // the output held, `[address, [10, {policy: {name: 1}}]]`, with its true bytes
    val heldRaw = arr(
      bytes(owner.address().raw),
      arr(uint(10), map(bytes(policy) -> map(bytes(name) -> uint(1))))
    )
    val heldOut = TxOut.fromBytes(heldRaw).fold(e => fail(e.message), identity)
    val built = ConwayTestKit.tx(
      ConwayTestKit.Body(
        Vector(txIn(1)),
        Vector(ConwayTestKit.out(owner.address(), 9)),
        1,
        mint = Map(policy -> Map(name -> BigInt(-2)))
      ),
      Vector(owner)
    )
    val pp = params(10)
    val ls = NewEpochState.empty[Conway](EpochNo.fromUnsigned(5), pp).es.ls
    val state = ls.utxo.copy[Conway](utxo = UtxoTables(NodeUtxo.fromMap(Map(txIn(1) -> heldOut))))
    val failures = xyz.kd5ujc.scarab.ledger.era.EraUtxoRun
      .judge(built.typed, pp, ls.cert, state)
      .fold(d => fail(s"a defect: $d"), _.left.toOption.fold(Vector.empty)(_.toChain.toVector))
    assertEquals(
      failures.collect {
        case xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxoPredFailure.ValueNotConservedUTxO(m) =>
          UtxoFailure.ValueNotConserved(
            m.storedBy(xyz.kd5ujc.scarab.ledger.era.StoredTxOut.conway.storedValue)
          )
      },
      oneConsumedTwoProduced
    )
  }

  private val held = TxOut(owner.address(), Value(10, Map(policy -> Map(name -> BigInt(1)))))

  private val burnTwo = tx(
    body(
      "burn",
      Vector(txIn(1)),
      Vector(out(owner.address(), 9)),
      1,
      mint = Map(policy -> Map(name -> BigInt(-2)))
    ),
    Vector(owner)
  )

  private val oneConsumedTwoProduced = Vector(
    UtxoFailure.ValueNotConserved(
      Mismatch(
        Value(10, Map(policy -> Map(name -> BigInt(1)))),
        Value(10, Map(policy -> Map(name -> BigInt(2))))
      )
    )
  )

  private def preConway[E <: EraTag](using ShelleyBasedEra[E], PreConway[E]) =
    val pp = params(10)
    val cert = PreConway[E].cert.get(
      xyz.kd5ujc.scarab.ledger.state.Build.nesAt[E](EpochNo.fromUnsigned(5), pp).es.ls
    )
    val checks = UtxoChecks.of[E](
      UtxoEnv(TestLedger.env, pp, cert),
      StAnnTx(burnTwo, NodeUtxo(Map(txIn(1) -> held))),
      0,
      0
    )
    valueFailure(Test.failures(checks.beforePpup).fold(Vector.empty)(_.toChain.toVector))

  test("Mary, Alonzo and Babbage also report one consumed and two produced for that burn") {
    assertEquals(preConway[Mary], oneConsumedTwoProduced, "Mary")
    assertEquals(preConway[Alonzo], oneConsumedTwoProduced, "Alonzo")
    assertEquals(preConway[Babbage], oneConsumedTwoProduced, "Babbage")
  }

  test("the minted assets are the positive quantities, the burned the negative ones negated") {
    val other = Bytes.fromArray(Array(2.toByte))
    val mint = Map(policy -> Map(name -> BigInt(3), other -> BigInt(-4)))
    assertEquals(Value.minted(mint), Value(0, Map(policy -> Map(name -> BigInt(3)))))
    assertEquals(Value.burned(mint), Value(0, Map(policy -> Map(other -> BigInt(4)))))
    assertEquals(Value.burned(Map(policy -> Map(name -> BigInt(1)))), Value.zero)
  }
