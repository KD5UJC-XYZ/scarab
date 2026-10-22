// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.*

/** The key witnesses a transaction needs include those of its collateral inputs, whether or not it
  * has redeemers, in every era that has collateral (Alonzo on): `getShelleyWitsVKeyNeededNoGov`
  * (cardano-ledger f649f975, `Shelley/UTxO.hs`) takes the payment key of every
  * `spendableInputsTxBodyF` input, which is the inputs and the collateral inputs
  * (`Alonzo/TxBody.hs` `allInputsTxBodyF`, `Babbage/TxBody.hs` `babbageSpendableInputsTxBodyF`,
  * `Conway/TxBody.hs` `spendableInputsTxBodyF`), and `getAlonzoWitsVKeyNeeded` /
  * `getConwayWitsVKeyNeeded` add only more keys. These tests call the production UTXOW checks.
  */
class CollateralWitnessSuite extends FunSuite:
  private val alice = TestLedger.key()
  private val bob = TestLedger.key()
  private val funds = BigInt(10000000)
  private val fee = BigInt(200)
  private val input = TestLedger.txIn(0)
  private val collateral = TestLedger.txIn(1)
  private val utxo = Utxo.fromMap(
    Map(
      input -> TestLedger.out(alice.address(), funds),
      collateral -> TestLedger.out(bob.address(), funds)
    )
  )

  private def transaction(signers: Vector[TestLedger.Key], coll: Vector[TxIn]): Tx =
    TestLedger.tx(
      TestLedger.body(
        "collateral-witness",
        Vector(input),
        Vector(TestLedger.out(alice.address(), funds - fee)),
        fee,
        collateral = coll
      ),
      signers
    )

  private def check(major: Int, tx: Tx) =
    val pp = TestLedger.params(major)
    Utxow.witnesses(UtxoEnv(TestLedger.env, pp, ()), Map.empty, StAnnTx(tx, utxo))

  for major <- Vector(5, 6, 7, 8, 9, 10, 11) do
    test(s"protocol $major: a collateral input's key must sign, with no redeemers") {
      val tx = transaction(Vector(alice), Vector(collateral))
      assert(tx.witnesses.redeemers.isEmpty)
      assertEquals(
        check(major, tx),
        Left(
          LedgerFailure.Rejected(
            tx.body.id,
            PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(Set(bob.hash)))
          )
        )
      )
    }

    test(s"protocol $major: signing the collateral input's key satisfies the witnesses") {
      assertEquals(
        check(major, transaction(Vector(alice, bob), Vector(collateral))),
        Right(())
      )
    }

    test(s"protocol $major: a transaction without collateral needs only its inputs' keys") {
      assertEquals(check(major, transaction(Vector(alice), Vector.empty)), Right(()))
    }
