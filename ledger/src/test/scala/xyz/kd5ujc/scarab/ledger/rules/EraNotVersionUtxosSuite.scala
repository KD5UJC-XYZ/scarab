// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** UTXOS runs in every era from Alonzo whatever the protocol version (cardano-ledger f649f975):
  * Alonzo's `utxosTransition` (`Alonzo/Rules/Utxos.hs:159-216`) and Babbage's
  * (`Babbage/Rules/Utxos.hs:120-220`) evaluate the scripts against the `is_valid` flag, and a
  * transaction that fails phase 2 pays its collateral (`Alonzo/Rules/Utxo.hs:775-784`,
  * `Babbage/Rules/Utxo.hs:474-485`, `collAdaBalance`, `Babbage/Collateral.hs:30-41`). A chain that
  * starts Alonzo by epoch (`TestAlonzoHardForkAtEpoch`) keeps the version of the era before it, so
  * Alonzo is checked here at version 4, through the UTXO rule's entry point (`ShelleyUtxo.sts`,
  * which embeds UTXOS), so that no check of UTXOW takes part.
  */
class EraNotVersionUtxosSuite extends FunSuite:
  private val ada = BigInt(1000000)
  private val alice = key()
  private val bob = key()

  /** The UTXO rule of Alonzo on `t` in a state holding `funds`, with the parameters of protocol
    * version `pv` in force.
    */
  private def run(
      pv: Int,
      funds: Vector[(TxIn, TxOut)],
      t: Tx,
      ctx: TxEnv
  ): Either[LedgerFailure, Counted[UTxOState[Alonzo]]] =
    val nes = NativeRules.stateIn[Alonzo](pv, funds)
    val ls = nes.es.ls
    val pre = summon[PreConway[Alonzo]]
    JudgedSts(ShelleyUtxo.sts[Alonzo])(LedgerFailure.Rejected(t.body.id, _))(
      NativeRules.globalsOf(ctx),
      UtxoEnv(ctx, params(pv), pre.cert.get(ls)),
      Counted(ls.utxo, PlutusStats.zero),
      StAnnTx(t, ls.utxo.utxo.utxo)
    )

  private def rule(r: Either[LedgerFailure, ?]): String = r match
    case Left(LedgerFailure.Rejected.Single(_, f)) => f.ruleName
    case Left(other)                               => other.message
    case Right(_)                                  => "accepted"

  test("Alonzo at version 4 refuses a transaction flagged invalid with no script to fail") {
    // no script: the evaluation of none passes (`Passes []`), against the flag
    val in = txIn(1)
    val b = body("plain", Vector(in), Vector(out(bob.address(), 40 * ada)), 60 * ada)
    val flagged = tx(b, Vector(alice), isValid = false)
    assertEquals(
      rule(run(4, Vector(in -> out(alice.address(), 100 * ada)), flagged, env)),
      "ValidationTagMismatch"
    )
  }

  /** A transaction spending an output locked by a Plutus V1 script, with a collateral input of 100
    * ada, and the state holding both.
    */
  private object Locked:
    val plutus = Script.plutus(ScriptKind.PlutusV1, Bytes(Vector.fill(10)(7)))
    private val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e.toString), identity)
    private val datum = Bytes(Vector(0x01))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val funds = Vector(
      collateralIn -> out(alice.address(), 100 * ada),
      lockedIn -> TxOut(scriptAddress, Value.coin(50 * ada))
        .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    )
    private val pp = params(4)
    private val units = ExUnits(1000, 1000000)
    private val fee =
      BigInt(100 + 100) + (pp.priceMem * Rational(1000) + pp.priceSteps * Rational(1000000)).ceil
    private val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units))
    )
    private val b = body(
      "plutus",
      Vector(lockedIn),
      Vector(out(bob.address(), 50 * ada - fee)),
      fee,
      collateral = Vector(collateralIn)
    )
    def spending(isValid: Boolean): Tx = tx(b, Vector(alice), wits, isValid = isValid)

  test("Alonzo at version 4 evaluates the scripts against the is_valid flag") {
    val failing = env.copy(evaluator = scripted(false))
    assertEquals(
      rule(run(4, Locked.funds, Locked.spending(isValid = true), failing)),
      "ValidationTagMismatch"
    )
    val passing = env.copy(evaluator = scripted(true))
    val valid = run(4, Locked.funds, Locked.spending(isValid = true), passing)
      .fold(f => fail(f.message), identity)
    assertEquals((valid.plutus.succeeded, valid.plutus.failed), (1L, 0L))
  }

  test("Alonzo at version 4 takes the collateral of a transaction that fails phase 2") {
    val failing = env.copy(evaluator = scripted(false))
    val after = run(4, Locked.funds, Locked.spending(isValid = false), failing)
      .fold(f => fail(f.message), identity)
    assertEquals((after.plutus.succeeded, after.plutus.failed), (0L, 1L))
    val utxo = after.state.utxo.utxo
    assert(!utxo.contains(Locked.collateralIn) && utxo.contains(Locked.lockedIn))
    // the collateral's 100 ada are the fees (`collAdaBalance`, no collateral return)
    assertEquals(after.state.fees.value, 100 * ada)
  }
