// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.runtime.{Decoded, DecodeError}

/** The entry bounds of `{ + k => v }` maps in the generated decoders, against the reference
  * decoders.
  *
  * Conway and Dijkstra refuse an empty map where the CDDL has `+` (cardano-ledger
  * `Conway/TxBody.hs` guards on withdrawals, mint and voting procedures, `Mary/Value.hs`
  * `decodeMultiAsset`, `Conway/Governance/Procedures.hs` `DecCBOR VotingProcedures`,
  * `Alonzo/TxWits.hs` `DecCBOR (Annotator (RedeemersRaw era))`, `Dijkstra/TxBody.hs`). Mary, Alonzo
  * and Babbage decode a multi-asset map with a plain `decodeMap` and prune what is empty, so the
  * generated decoders of those eras (module option `lenient-maps` in `models.conf`) accept an empty
  * inner map although the CDDL says `{ + asset_name => a }`.
  */
class MapBoundsSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  /** Some alternative of the decoder refuses the input for the entry count of a `+` map: one or
    * more entries, none present.
    */
  private def refused[A](result: Decoded[A])(using munit.Location): Unit =
    def arity(e: DecodeError): Boolean = e.kind match
      case DecodeError.Kind.BadArity(DecodeError.Arity.Range(1, None), 0) => true
      case DecodeError.Kind.NoAlternative(cause)                          => cause.exists(arity)
      case _                                                              => false
    result match
      case Left(e) if arity(e) => ()
      case other               => fail(s"expected an arity refusal, got $other")

  private val policy = "581c" + "00" * 28
  private val account = "581d" + "00" * 29
  private val emptyMap = "a0"
  private val policyWithNoAssets = "a1" + policy + emptyMap

  test("Conway and Dijkstra refuse an empty Mint, an empty asset map and an empty Withdrawals") {
    refused(conway.Mint.decodeBytes(bytes(emptyMap)))
    refused(conway.Mint.decodeBytes(bytes(policyWithNoAssets)))
    refused(conway.Withdrawals.decodeBytes(bytes(emptyMap)))
    refused(dijkstra.Mint.decodeBytes(bytes(emptyMap)))
    refused(dijkstra.Mint.decodeBytes(bytes(policyWithNoAssets)))
    refused(dijkstra.Withdrawals.decodeBytes(bytes(emptyMap)))
  }

  test("Conway and Dijkstra still read one entry in each map") {
    val mint = "a1" + policy + "a1" + "4100" + "01"
    assert(conway.Mint.decodeBytes(bytes(mint)).isRight)
    assert(dijkstra.Mint.decodeBytes(bytes(mint)).isRight)
    val withdrawals = "a1" + account + "00"
    assert(conway.Withdrawals.decodeBytes(bytes(withdrawals)).isRight)
    assert(dijkstra.Withdrawals.decodeBytes(bytes(withdrawals)).isRight)
  }

  test("Conway and Dijkstra refuse an empty VotingProcedures and a voter without votes") {
    val voter = "82" + "00" + policy
    refused(conway.VotingProcedures.decodeBytes(bytes(emptyMap)))
    refused(conway.VotingProcedures.decodeBytes(bytes("a1" + voter + emptyMap)))
    refused(dijkstra.VotingProcedures.decodeBytes(bytes(emptyMap)))
    refused(dijkstra.VotingProcedures.decodeBytes(bytes("a1" + voter + emptyMap)))
  }

  test("Conway and Dijkstra refuse an empty redeemers map") {
    refused(conway.TransactionWitnessSet.decodeBytes(bytes("a1" + "05" + emptyMap)))
    refused(dijkstra.Redeemers.decodeBytes(bytes(emptyMap)))
  }

  test("Dijkstra refuses the other empty maps its transaction body guards") {
    refused(dijkstra.DirectDeposits.decodeBytes(bytes(emptyMap)))
    refused(dijkstra.RequiredTopLevelGuards.decodeBytes(bytes(emptyMap)))
    refused(dijkstra.AccountBalanceIntervals.decodeBytes(bytes(emptyMap)))
    refused(dijkstra.StartingAccountBalanceIntervals.decodeBytes(bytes(emptyMap)))
  }

  test("Mary, Alonzo and Babbage accept an empty asset map as the reference does") {
    assert(mary.Mint.decodeBytes(bytes(policyWithNoAssets)).isRight)
    assert(alonzo.Mint.decodeBytes(bytes(policyWithNoAssets)).isRight)
    assert(babbage.Mint.decodeBytes(bytes(policyWithNoAssets)).isRight)
    val value = "82" + "01" + policyWithNoAssets
    assert(mary.Value.decodeBytes(bytes(value)).isRight)
    assert(alonzo.Value.decodeBytes(bytes(value)).isRight)
    assert(babbage.Value.decodeBytes(bytes(value)).isRight)
  }

  test(
    "a value: Conway refuses an empty asset map and accepts an empty outer map, Dijkstra refuses both"
  ) {
    val noAssets = "82" + "01" + policyWithNoAssets
    val noPolicies = "82" + "01" + emptyMap
    refused(conway.Value.decodeBytes(bytes(noAssets)))
    refused(dijkstra.Value.decodeBytes(bytes(noAssets)))
    assert(conway.Value.decodeBytes(bytes(noPolicies)).isRight)
    refused(dijkstra.Value.decodeBytes(bytes(noPolicies)))
  }

  test("an expected arity renders as the message always did") {
    import DecodeError.Arity
    def message(expected: Arity): String =
      DecodeError.describe(DecodeError.Kind.BadArity(expected, 7))
    assertEquals(message(Arity.Exactly(2)), "expected 2 elements, got 7")
    assertEquals(message(Arity.AtLeast(1)), "expected at least 1 elements, got 7")
    assertEquals(message(Arity.Range(1, None)), "expected 1..* elements, got 7")
    assertEquals(message(Arity.Range(2, Some(4))), "expected 2..4 elements, got 7")
    assertEquals(message(Arity.OneOf(0, 1)), "expected 0 or 1 elements, got 7")
  }
