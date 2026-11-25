// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.rules.RedeemerIndexCases.*
import xyz.kd5ujc.scarab.ledger.state.Babbage
import xyz.kd5ujc.scarab.protocol.Era

/** The LocalTxSubmission refusal of a transaction that spends a script-locked output, withdraws
  * from the script's account, delegates it and mints with it as the policy, with no redeemer: the
  * node's pool judges it ([[RedeemerIndexCases.verdict]]) and the refusal is encoded as the node
  * sends it.
  *
  * The purposes are listed in the order of the era's `getScriptsNeeded`: spending, withdrawing,
  * certifying, minting (`Alonzo/UTxO.hs:318-322`, `Conway/UTxO.hs:67-73`, cardano-ledger f649f975),
  * by UTXOW's `MissingRedeemers` (`extSymmetricDifference`, `Alonzo/Rules/Utxow.hs:378-383`) and in
  * reverse by UTXOS's `CollectErrors` (`merge`, `Alonzo/Plutus/Evaluate.hs:141-185`). Each expected
  * value is derived by hand from the `EncCBOR` instances, field by field, as
  * [[PreConwayFailureCborSuite]]'s are; the nesting of each era's failures, and which of the two
  * Conway's MEMPOOL and Babbage's LEDGER report first, are those of the refusals the reference node
  * answered in [[PlutusFailureReferenceSuite]]. The reference node's refusal of such a transaction
  * on the devnet, in each era, is checked byte for byte in [[RedeemerIndexReferenceSuite]],
  * Conway's on its era rules (`conway-missing-redeemers-every-purpose`). Conway's case here is
  * encoder coverage only: no rule runs, the failures and their order are built by hand in its era's
  * types and written by [[ConwayFailureCbor]]. The crafted transaction has neither a body nor
  * witnesses in bytes Conway's decoder could read, so its era rules cannot judge it; the order the
  * rules give is the recorded case's.
  */
class MissingRedeemersRefusalSuite extends FunSuite:
  private val h = script.hash.hex

  /** `PlutusPurpose AsItem`, the same in both eras: `[tag, item]`. */
  private val spending = "8200" + "82" + "5820" + locked.id.hex + "00"
  // the reward account: header 0xf0 (a script credential on network 0) and the script's hash
  private val withdrawing = "8203" + "581d" + "f0" + h
  // a delegation is [2, credential, pool], a script credential [1, hash]
  private val certifying = "8202" + "83" + "02" + "8201" + "581c" + h + "581c" + poolId.hex
  private val minting = "8201" + "581c" + h
  private val inOrder = Vector(spending, withdrawing, certifying, minting)

  /** `[purpose, script hash]` each, in the order of the scripts needed. */
  private val missing = "84" + inOrder.map(p => "82" + p + "581c" + h).mkString

  /** `[0 NoRedeemer, purpose]` each, in reverse. */
  private val noRedeemers = "84" + inOrder.reverse.map("8200" + _).mkString

  test("Conway: UTXOS's CollectErrors, then UTXOW's MissingRedeemers spending to minting") {
    // encoder coverage only: the failures are built by hand, not given by a rule (see above)
    import cats.data.NonEmptyList
    import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
    import xyz.kd5ujc.scarab.ledger.era.{AsItem, CollectError}
    import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPlutusPurpose as P, ConwayTxCert}
    import xyz.kd5ujc.scarab.ledger.era.rules.{
      ConwayLedgerPredFailure as L,
      ConwayUtxoPredFailure as U,
      ConwayUtxosPredFailure as S,
      ConwayUtxowPredFailure as W
    }
    import xyz.kd5ujc.scarab.ledger.rules.Convert
    import xyz.kd5ujc.scarab.ledger.state.Conway
    // the delegation `[2, [1, script], pool]`, read by Conway's decoder
    val delegation = Bytes
      .fromHex("83" + "02" + "8201" + "581c" + h + "581c" + poolId.hex)
      .fold(e => fail(s"the delegation: $e"), identity)
    val delegated = ConwayTxCert
      .decode(
        Cbor.decode(delegation).fold(e => fail(s"the delegation: $e"), identity),
        xyz.kd5ujc.scarab.protocol.StateLayout.Node11_1
      )
      .fold(e => fail(s"the delegation: $e"), identity)
    // the scripts needed in `getScriptsNeeded`'s order: spending, withdrawing, certifying, minting
    val needed = NonEmptyList.of[P[AsItem]](
      P.ConwaySpending(AsItem(locked)),
      P.ConwayWithdrawing(AsItem(Convert.accountAddress(account).fold(e => fail(s"$e"), identity))),
      P.ConwayCertifying(AsItem(delegated)),
      P.ConwayMinting(AsItem(script.hash))
    )
    val failures = Vector[L[Conway]](
      L.ConwayUtxowFailure(
        W.UtxoFailure(
          U.UtxosFailure(S.CollectErrors(needed.reverse.map(CollectError.NoRedeemer[Conway](_))))
        )
      ),
      L.ConwayUtxowFailure(W.MissingRedeemers(needed.map(_ -> script.hash)))
    )
    // [6 era, [[1 UTXOW, [0 UTXO, [0 UTXOS, [1 CollectErrors, [...]]]]],
    //          [1 UTXOW, [10 MissingRedeemers, [...]]]]]
    assertEquals(
      ConwayFailureCborCheck.direct(failures).hex,
      "81" + "8206" + "82" +
        "8201" + "8200" + "8200" + "8201" + noRedeemers +
        "8201" + "820a" + missing
    )
  }

  test("Babbage: UTXOW's MissingRedeemers spending to minting, then UTXOS's CollectErrors") {
    val failures = verdict[Babbage](8)(unredeemed[Babbage]).swap.getOrElse(fail("accepted"))
    // [5 era, [[0 UTXOW, [1 Alonzo's UTXOW, [1 MissingRedeemers, [...]]]],
    //          [0 UTXOW, [2 UTXO, [1 Alonzo's UTXO, [7 UTXOS, [1 CollectErrors, [...]]]]]]]]
    assertEquals(
      FlatRefusal
        .applyTxError(Era.Babbage, failures)
        .fold(error => fail(error.message), identity)
        .map(_.hex),
      Some(
        "81" + "8205" + "82" +
          "8200" + "8201" + "8201" + missing +
          "8200" + "8202" + "8201" + "8207" + "8201" + noRedeemers
      )
    )
  }
