// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.Utxo as NodeUtxo
import xyz.kd5ujc.scarab.ledger.rules.Utxo as ConwayUtxo

/** Source-derived controls for the standalone production UTXO rule, without UTXOW's early guard.
  * Native f649f9751074d2ab3de033fc3912f29c9862c1f5: Babbage/Rules/Utxo.hs:359–375;
  * Conway/Rules/Utxo.hs:238 uses that validation. These are local controls, not new oracle
  * captures.
  */
class UtxoInputExistenceSuite extends FunSuite:
  private val pp = TestLedger.params(10)
  private val alice = TestLedger.key()
  private val spent = TestLedger.txIn(0)
  private val collateral = TestLedger.txIn(1)
  private val reference = TestLedger.txIn(2)
  private val missing = TestLedger.txIn(3)
  private val funds = BigInt(10000000)
  private val fee = BigInt(200)
  private val output = TestLedger.out(alice.address(), funds)
  private val held = NodeUtxo.fromMap(Vector(spent, collateral, reference).map(_ -> output).toMap)
  private val ledger = Empty.ledgerState[Conway](pp)
  private val initial: Counted[UTxOState[Conway]] =
    Counted(ledger.utxo.copy[Conway](utxo = UtxoTables(held)), PlutusStats.zero)
  private val env = UtxoEnv(TestLedger.env, pp, ledger.cert)

  private def payment = TestLedger.body(
    "standalone-input-existence",
    Vector(spent),
    Vector(TestLedger.out(alice.address(), funds - fee)),
    fee,
    collateral = Vector(collateral),
    referenceInputs = Vector(reference)
  )

  private def transaction(body: TxBody, valid: Boolean = true): Tx =
    TestLedger.tx(body, Vector.empty, isValid = valid)

  private def run(tx: Tx): Either[LedgerFailure, Counted[UTxOState[Conway]]] =
    assert(tx.witnesses.redeemers.isEmpty)
    Sts(ConwayUtxo)(TestLedger.globals, env, initial, StAnnTx(tx, held))

  private def rejected(tx: Tx, failure: UtxoFailure): Unit =
    assertEquals(run(tx), Left(LedgerFailure.Rejected(tx.body.id, PredicateFailure.utxo(failure))))

  Vector(
    "spending" -> payment.copy(inputs = Vector(spent, missing)),
    "collateral" -> payment.copy(collateral = Vector(missing)),
    "reference" -> payment.copy(referenceInputs = Vector(missing))
  ).foreach { (role, body) =>
    test(
      s"standalone UTXO rejects missing $role inputs without redeemers under either validity flag"
    ) {
      Vector(true, false).foreach { valid =>
        rejected(transaction(body, valid), UtxoFailure.BadInputs(Set(missing)))
      }
    }
  }

  test("standalone UTXO rejects an empty spending set without redeemers") {
    rejected(transaction(payment.copy(inputs = Vector.empty)), UtxoFailure.InputSetEmpty)
  }

  test("an expired interval precedes missing inputs in standalone UTXO") {
    val body = payment.copy(collateral = Vector(missing), ttl = Some(env.ctx.slot))
    rejected(transaction(body), UtxoFailure.OutsideValidityInterval(None, body.ttl, env.ctx.slot))
  }

  test("an empty spending set precedes a fee refusal in standalone UTXO") {
    rejected(transaction(payment.copy(inputs = Vector.empty, fee = 0)), UtxoFailure.InputSetEmpty)
  }

  test("a fee refusal precedes missing inputs in standalone UTXO") {
    val body = payment.copy(collateral = Vector(missing), fee = 0)
    rejected(transaction(body), UtxoFailure.FeeTooSmall(Mismatch(BigInt(0), fee)))
  }

  test(
    "valid no-script standalone UTXO consumes spending inputs and retains collateral and references"
  ) {
    val tx = transaction(payment)
    val after = run(tx).fold(error => fail(error.message), identity)
    assert(!after.state.utxo.utxo.contains(spent))
    assertEquals(after.state.utxo.utxo.get(collateral), Some(output))
    assertEquals(after.state.utxo.utxo.get(reference), Some(output))
    assertEquals(after.state.utxo.utxo.get(TxIn(tx.body.id, 0)), tx.body.outputs.headOption)
    assertEquals(after.state.fees.value, fee)
    assertEquals(after.plutus, PlutusStats.zero)
  }
