// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.Utxo as NodeUtxo
import xyz.kd5ujc.scarab.ledger.era.EraUtxoRun
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxoPredFailure

/** Source-derived controls for Conway's UTXO on its own (`conwayUtxoTransition` on the era types),
  * without UTXOW's early guard. Native f649f9751074d2ab3de033fc3912f29c9862c1f5:
  * Babbage/Rules/Utxo.hs:359–375; Conway/Rules/Utxo.hs:238 uses that validation. These are local
  * controls, not new oracle captures. The rule reports its failures in its order (one level down,
  * `EraUtxoRun.judge`), and the first is the one each control names.
  */
class UtxoInputExistenceSuite extends FunSuite:
  private val pp = TestLedger.params(10)
  private val alice = TestLedger.key()
  private val spent = TestLedger.txIn(0)
  private val collateral = TestLedger.txIn(1)
  private val reference = TestLedger.txIn(2)
  private val missing = TestLedger.txIn(3)
  private val funds = BigInt(10000000)
  private val fee = BigInt(2000)
  private val output = ConwayTestKit.out(alice.address(), funds)
  private val held = NodeUtxo.fromMap(Vector(spent, collateral, reference).map(_ -> output).toMap)
  private val ledger = Empty.ledgerState[Conway](pp)
  private val initial: UTxOState[Conway] = ledger.utxo.copy[Conway](utxo = UtxoTables(held))

  private def payment = ConwayTestKit.Body(
    Vector(spent),
    Vector(ConwayTestKit.out(alice.address(), funds - fee)),
    fee,
    collateral = Vector(collateral),
    referenceInputs = Vector(reference)
  )

  private def transaction(body: ConwayTestKit.Body, valid: Boolean = true): ConwayTestKit.Built =
    ConwayTestKit.tx(body, Vector.empty, isValid = valid)

  private def run(
      tx: ConwayTestKit.Built
  ): Either[List[ConwayUtxoPredFailure[Conway]], EraUtxoRun.S] =
    assert(tx.flat.witnesses.redeemers.isEmpty)
    EraUtxoRun.judge(tx.typed, pp, ledger.cert, initial) match
      case Left(defect)    => fail(s"a defect: $defect")
      case Right(Left(fs)) => Left(fs.toChain.toList)
      case Right(Right(s)) => Right(s)

  /** That `tx` is refused, its first failure the one `expected` accepts. */
  private def rejected(tx: ConwayTestKit.Built)(
      expected: PartialFunction[ConwayUtxoPredFailure[Conway], Unit]
  ): Unit =
    run(tx) match
      case Left(first :: _) if expected.isDefinedAt(first) => expected(first)
      case other => fail(s"not the expected refusal: $other")

  Vector(
    "spending" -> payment.copy(inputs = Vector(spent, missing)),
    "collateral" -> payment.copy(collateral = Vector(missing)),
    "reference" -> payment.copy(referenceInputs = Vector(missing))
  ).foreach { (role, body) =>
    test(
      s"standalone UTXO rejects missing $role inputs without redeemers under either validity flag"
    ) {
      Vector(true, false).foreach { valid =>
        rejected(transaction(body, valid)) { case ConwayUtxoPredFailure.BadInputsUTxO(ins) =>
          assertEquals(ins.toSortedSet.toSet, Set(missing))
        }
      }
    }
  }

  test("standalone UTXO rejects an empty spending set without redeemers") {
    rejected(transaction(payment.copy(inputs = Vector.empty))) {
      case ConwayUtxoPredFailure.InputSetEmptyUTxO() =>
    }
  }

  test("an expired interval precedes missing inputs in standalone UTXO") {
    val slot = TestLedger.env.slot
    val tx = transaction(payment.copy(collateral = Vector(missing), ttl = Some(slot)))
    rejected(tx) { case ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(vi, at) =>
      assertEquals((vi.invalidBefore, vi.invalidHereafter, at), (None, Some(slot), slot))
    }
  }

  test("an empty spending set precedes a fee refusal in standalone UTXO") {
    rejected(transaction(payment.copy(inputs = Vector.empty, fee = 0))) {
      case ConwayUtxoPredFailure.InputSetEmptyUTxO() =>
    }
  }

  test("a fee refusal precedes missing inputs in standalone UTXO") {
    val tx = transaction(payment.copy(collateral = Vector(missing), fee = 0))
    // `getShelleyMinFeeTxUtxo`: the size's fee and the constant (`Shelley/Tx.hs`, cardano-ledger
    // f649f975); the transaction has no script and no reference script
    rejected(tx) { case ConwayUtxoPredFailure.FeeTooSmallUTxO(m) =>
      assertEquals(
        (m.supplied.value, m.expected.value),
        (BigInt(0), pp.minFeeA * tx.flat.size + pp.minFeeB)
      )
    }
  }

  test(
    "valid no-script standalone UTXO consumes spending inputs and retains collateral and references"
  ) {
    val tx = transaction(payment)
    val (after, events) = run(tx).fold(fs => fail(s"refused: $fs"), identity)
    val utxo = after.utxo.utxo
    assert(!utxo.contains(spent))
    assertEquals(utxo.get(collateral), Some(output))
    assertEquals(utxo.get(reference), Some(output))
    assertEquals(utxo.get(TxIn(tx.id, 0)), tx.flat.body.outputs.headOption)
    assertEquals(after.fees.value, fee)
    assertEquals(EraUtxoRun.counters(valid = true, events), Some((0L, 0L, 0L)))
  }
