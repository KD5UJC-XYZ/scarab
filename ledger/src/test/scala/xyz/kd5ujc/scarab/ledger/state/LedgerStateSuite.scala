// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Order
import cats.kernel.laws.discipline.EqTests
import cats.syntax.all.*
import monocle.law.discipline.LensTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Proposals
import xyz.kd5ujc.scarab.ledger.state.Credential.{KeyHashObj, ScriptHashObj}
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.{*, given}
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.{*, given}

/** The ledger tree: instances, the order of its maps, and the lenses (Monocle laws). */
class LedgerStateSuite extends DisciplineSuite:
  given Cogen[PState] = cogenShow
  given conwayLedgerCogen: Cogen[LedgerState[Conway]] = cogenShow
  given babbageLedgerCogen: Cogen[LedgerState[Babbage]] = cogenShow
  given Cogen[UTxOState[Conway]] = cogenShow
  given Cogen[VState] = cogenShow
  given Cogen[ConwayAccounts] = cogenShow
  given Cogen[ConwayGovState] = cogenShow
  given Cogen[FuturePParams] = cogenShow
  given Cogen[StakePoolState] = cogenShow
  given poolsCogen: Cogen[SortedMap[KeyHash[StakePool], StakePoolState]] = cogenShow
  given drepsCogen: Cogen[SortedMap[Credential[DRepRole], DRepState]] = cogenShow
  given Cogen[ConwayCertState] = cogenShow
  given Cogen[Proposals] = cogenShow
  given Cogen[Coin] = cogenShow
  given Cogen[CompactCoin] = cogenShow
  given Arbitrary[Proposals] = Arbitrary(Gen.const(Proposals.empty))

  // instances of the era-generic composites, by the witness
  checkAll("Eq[LedgerState[Conway]]", EqTests[LedgerState[Conway]].eqv)
  checkAll("Eq[LedgerState[Babbage]]", EqTests[LedgerState[Babbage]].eqv)
  checkAll("Eq[ConwayGovState]", EqTests[ConwayGovState].eqv)

  // one lens of each shape: a field, a composed path, an era-generic path by the witness
  checkAll("PState.stakePools", LensTests(PState.stakePools))
  checkAll("StakePoolState.deposit", LensTests(StakePoolState.deposit))
  checkAll("ConwayCertState.vState", LensTests(ConwayCertState.vState))
  checkAll("ConwayLedgerState.pools", LensTests(ConwayLedgerState.pools))
  checkAll("ConwayLedgerState.dreps", LensTests(ConwayLedgerState.dreps))
  checkAll("ConwayLedgerState.accounts", LensTests(ConwayLedgerState.accounts))
  checkAll("ConwayLedgerState.gov", LensTests(ConwayLedgerState.gov))
  checkAll("ConwayLedgerState.proposals", LensTests(ConwayLedgerState.proposals))
  checkAll("LedgerState.pState[Conway]", LensTests(LedgerState.pState[Conway]))
  checkAll("LedgerState.pState[Babbage]", LensTests(LedgerState.pState[Babbage]))
  checkAll("UTxOState.fees", LensTests(UTxOState.fees[Conway]))

  private def keyOf(b: Int): KeyHash[Staking] =
    KeyHash
      .fromBytes[Staking](xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(28)(b.toByte)))
      .toOption
      .get
  private def scriptOf(b: Int): ScriptHash =
    ScriptHash
      .fromBytes(xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(28)(b.toByte)))
      .toOption
      .get

  test("a SortedMap of credentials iterates in the Haskell order: scripts first, then by hash") {
    val creds: List[Credential[Staking]] =
      List(
        KeyHashObj(keyOf(0x01)),
        ScriptHashObj(scriptOf(0xff)),
        KeyHashObj(keyOf(0x80)),
        ScriptHashObj(scriptOf(0x00))
      )
    val m = Sorted.map(creds.map(_ -> ()))
    assertEquals(
      m.keys.toList,
      List(
        ScriptHashObj(scriptOf(0x00)),
        ScriptHashObj(scriptOf(0xff)),
        KeyHashObj(keyOf(0x01)),
        KeyHashObj(keyOf(0x80))
      )
    )
  }

  test("a SortedMap of pointers iterates by slot, transaction index, certificate index") {
    import xyz.kd5ujc.scarab.ledger.Ptr
    val m = Sorted.map(List(Ptr(2, 0, 0), Ptr(1, 5, 0), Ptr(1, 0, 9), Ptr(1, 0, 1)).map(_ -> ()))
    assertEquals(m.keys.toList, List(Ptr(1, 0, 1), Ptr(1, 0, 9), Ptr(1, 5, 0), Ptr(2, 0, 0)))
  }

  test("FutureGenDeleg orders by slot first, unsigned") {
    val g = KeyHash
      .fromBytes[Genesis](xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(28)(0.toByte)))
      .toOption
      .get
    val early = FutureGenDeleg(SlotNo.fromUnsigned(5L), g)
    val late = FutureGenDeleg(SlotNo.fromUnsigned(-1L), g)
    assert(Order[FutureGenDeleg].lt(early, late))
  }

  test("equal ledger states are equal and differ in any field") {
    val a = LedgerState[Conway](
      conwayCert,
      UTxOState[Conway](
        UtxoTables(xyz.kd5ujc.scarab.ledger.Utxo.empty),
        Coin.zero,
        Coin.zero,
        conwayGov,
        conwayInstant,
        Coin.zero
      )
    )
    val fees = Coin(BigInt(1))
    assert(a === a)
    assert(a =!= LedgerState.utxo[Conway].andThen(UTxOState.fees[Conway]).replace(fees)(a))
  }

  test("the future parameters of a prediction") {
    assertEquals(FuturePParams.NoPParamsUpdate.nextParams, None)
    assertEquals(FuturePParams.DefinitePParamsUpdate(params).nextParams, Some(params))
    assertEquals(FuturePParams.PotentialPParamsUpdate(None).nextParams, None)
    assertEquals(FuturePParams.PotentialPParamsUpdate(Some(params)).nextParams, Some(params))
  }
