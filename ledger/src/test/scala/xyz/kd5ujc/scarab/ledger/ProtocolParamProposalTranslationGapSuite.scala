// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, EraLedger, EraNes, Hfc, NativeRules, Translate}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  FuturePParams,
  Genesis,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  Sorted
}
import xyz.kd5ujc.scarab.protocol.Era

/** A pending Alonzo update is translated with the state, even when the next epoch will adopt it
  * (`Babbage/Translation.hs:155-168`, cardano-ledger f649f975).
  */
class ProtocolParamProposalTranslationGapSuite extends FunSuite:
  import TestLedger.*

  private val constants = NativeRules.globals(
    LedgerGlobals(Supply, 10000, Rational(1, 20), 0, updateQuorum = 2)
  )
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private def proposed(enterMajor: Int): NewEpochState[Alonzo] =
    val alice = key()
    val genesis = Vector(key(), key())
    val delegates = Vector(key(), key())
    val input = txIn(1)
    val funded = NativeRules.stateIn[Alonzo](6, Vector(input -> out(alice.address(), 100000000)))
    val ls = funded.es.ls
    val gs = Sorted.map(genesis.zip(delegates).map { (g, d) =>
      Convert.keyHash[Genesis](g.hash).fold(e => fail(e.toString), identity) ->
        GenDelegPair(d.hash, Bytes.empty)
    })
    val start = funded.copy(es = funded.es.copy(ls = EraLedger.withGenDelegs(ls, gs, Sorted.empty)))
    val epoch = start.epoch.toBigInt
    val context = env.copy(
      epoch = epoch,
      slot = constants.firstSlot(epoch) + 1,
      firstSlotNextEpoch = constants.firstSlot(epoch + 1),
      stabilityWindow = constants.stabilityWindow
    )
    def update(fields: Map[Int, Bytes], target: BigInt): Update =
      Update(genesis.map(_.hash -> ParamUpdate(fields)).toMap, target)
    val fields = Map(17 -> hex("199c43")) ++
      Option.when(enterMajor == 7)(14 -> hex("820700"))
    val early = body(
      "early",
      Vector(input),
      Vector(out(alice.address(), 99999800)),
      200,
      update = Some(update(fields, epoch))
    )
    val afterEarly = NativeRules
      .txIn(start, tx(early, Vector(alice) ++ delegates), context)
      .fold(e => fail(e.message), _.state)
    val late = body(
      "late",
      Vector(TxIn(early.id, 0)),
      Vector(out(alice.address(), 99999600)),
      200,
      update = Some(update(Map(17 -> hex("19bb83")), epoch + 1))
    )
    val afterLate = NativeRules
      .txIn(
        afterEarly,
        tx(late, Vector(alice) ++ delegates),
        context.copy(slot = context.firstSlotNextEpoch - 1)
      )
      .fold(e => fail(e.message), _.state)
    assert(afterLate.es.ls.utxo.gov.curProposals.nonEmpty)
    assert(afterLate.es.ls.utxo.gov.futureProposals.nonEmpty)
    afterLate

  for major <- Vector(6, 7) do
    test(s"Babbage entry at version $major translates both stored Alonzo proposal maps") {
      val entered = Translate.alonzoToBabbage(proposed(major))
      val gov = entered.es.ls.utxo.gov
      assertEquals(
        (
          gov.curProposals.values.toVector.map(ParamUpdate.typed(_)(17)),
          gov.futureProposals.values.toVector.map(ParamUpdate.typed(_)(17))
        ),
        (
          Vector.fill(2)(ParamUpdate.Typed.Count(5000)),
          Vector.fill(2)(ParamUpdate.Typed.Count(6000))
        )
      )
    }

    test(s"late Alonzo votes are 6000 per byte after Babbage entry at version $major") {
      val source = NativeRules.tick(
        EraNes.InEra(ShelleyBasedEra.SAlonzo, proposed(major)),
        constants.firstSlot(6) - 1,
        constants.ledger
      )
      val target = SlotNo.fromBigInt(constants.firstSlot(6)).fold(e => fail(e.toString), identity)
      val entered = Hfc
        .tick(constants, p => Option.when(p.era == Era.Alonzo)(BigInt(6)))(source, 1L, target)
        .fold(e => fail(e.toString), identity)
      val babbage = entered match
        case EraNes.InEra(ShelleyBasedEra.SBabbage, nes) => nes
        case other                                       => fail(s"not Babbage: $other")
      assertEquals(
        EraLedger.curPParams[Babbage](babbage.es.ls.utxo.gov).protocolMajor,
        BigInt(major)
      )
      assertEquals(
        babbage.es.ls.utxo.gov.futurePParams match
          case FuturePParams.PotentialPParamsUpdate(Some(pp)) => pp.coinsPerUTxOByte
          case other => fail(s"not a voted update: $other"),
        BigInt(6000)
      )
      val beforeAdoption = NativeRules.tick(entered, constants.firstSlot(7) - 1, constants.ledger)
      val adopted = NativeRules.boundary(beforeAdoption, constants.ledger) match
        case EraNes.InEra(ShelleyBasedEra.SBabbage, nes) => nes
        case other                                       => fail(s"not Babbage: $other")
      assertEquals(adopted.es.ls.utxo.gov.curPParams.coinsPerUTxOByte, BigInt(6000))
    }

  test("the translation removes obsolete fields, converts only key 17 and preserves other bytes") {
    val fee = hex("190001")
    val update = ParamUpdate(
      Map(
        0 -> fee,
        12 -> hex("d81e820102"),
        13 -> hex("8100"),
        15 -> hex("01"),
        17 -> hex("1b0000000000009c43")
      )
    )
    assertEquals(ParamUpdate.toBabbage(update).fields, Map(0 -> fee, 17 -> hex("191388")))
    assertEquals(ParamUpdate.toBabbage(ParamUpdate(Map(0 -> fee))), ParamUpdate(Map(0 -> fee)))
    assertEquals(update.fields(17), hex("1b0000000000009c43"))
  }

  test("an unread legacy per-word value remains unread and cannot be adopted in Babbage") {
    val unread = ParamUpdate(Map(0 -> hex("01"), 17 -> hex("6161")))
    val translated = ParamUpdate.toBabbage(unread)
    assertEquals(translated, unread)
    assertEquals(ParamUpdate.typed(translated)(17), ParamUpdate.Typed.Unread(hex("6161")))
    assertEquals(UpdateChecks.voted[Babbage](params(7), Map(Bytes.empty -> translated), 1), None)
  }

  test("the largest decoded per-word cost converts exactly without narrowing") {
    val update = ParamUpdate(Map(17 -> hex("1bffffffffffffffff")))
    assertEquals(
      ParamUpdate.typed(ParamUpdate.toBabbage(update))(17),
      ParamUpdate.Typed.Count(((BigInt(1) << 64) - 1) / 8)
    )
  }
