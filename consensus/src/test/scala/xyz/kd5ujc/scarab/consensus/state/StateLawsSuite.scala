// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.kernel.laws.discipline.EqTests
import cats.syntax.all.*
import monocle.law.discipline.LensTests
import munit.DisciplineSuite
import xyz.kd5ujc.scarab.consensus.state.StateGen.given
import xyz.kd5ujc.scarab.ledger.state.{
  Allegra,
  Babbage,
  Conway,
  EpochNo,
  ShelleyBasedEra,
  WithOrigin
}
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.protocol.Era

/** Eq laws of the consensus tree and Monocle laws of a lens of each shape. */
class StateLawsSuite extends DisciplineSuite:
  checkAll("Eq[Bound]", EqTests[Bound].eqv)
  checkAll("Eq[ShelleyLedgerState[Conway]]", EqTests[ShelleyLedgerState[Conway]].eqv)
  checkAll("Eq[EraLedgerState]", EqTests[EraLedgerState].eqv)
  checkAll("Eq[ChainDepState]", EqTests[ChainDepState].eqv)
  checkAll("Eq[PraosState]", EqTests[PraosState].eqv)
  checkAll("Eq[Telescope[EraLedgerState]]", EqTests[Telescope[EraLedgerState]].eqv)
  checkAll("Eq[Telescope[ChainDepState]]", EqTests[Telescope[ChainDepState]].eqv)
  checkAll("Eq[HeaderState]", EqTests[HeaderState].eqv)
  checkAll("Eq[ExtLedgerState]", EqTests[ExtLedgerState].eqv)

  // a field, an era-generic field, a field of a case class of the protocol tree, nested records
  checkAll("Bound.slot", LensTests(Bound.slotLens))
  checkAll("ShelleyLedgerState.nes[Conway]", LensTests(ShelleyLedgerState.nes[Conway]))
  checkAll(
    "ShelleyLedgerState.transition[Conway]",
    LensTests(ShelleyLedgerState.transition[Conway])
  )
  checkAll("ShelleyLedgerState.peras[Conway]", LensTests(ShelleyLedgerState.peras[Conway]))
  checkAll("PraosState.lastSlot", LensTests(PraosState.lastSlot))
  checkAll("PraosState.labNonce", LensTests(PraosState.labNonce))
  checkAll("HeaderState.tip", LensTests(HeaderState.tip))
  checkAll("HeaderState.chainDep", LensTests(HeaderState.chainDep))
  checkAll("ExtLedgerState.headerState", LensTests(ExtLedgerState.headerState))

  test("two eras' states are never equal, whatever they hold") {
    val babbage = EraLedgerState.inEra(
      ShelleyLedgerState[Babbage](
        WithOrigin.Origin,
        NewEpochState[Babbage](EpochNo.fromUnsigned(7)),
        ShelleyTransition(Word32.fromUnsigned(0))
      )
    )(using ShelleyBasedEra[Babbage])
    val allegra = EraLedgerState.inEra(
      ShelleyLedgerState[Allegra](
        WithOrigin.Origin,
        NewEpochState[Allegra](EpochNo.fromUnsigned(7)),
        ShelleyTransition(Word32.fromUnsigned(0))
      )
    )(using ShelleyBasedEra[Allegra])
    assert(babbage =!= allegra)
    assert(babbage === babbage)
    assertEquals((babbage.era, allegra.era), (Era.Babbage, Era.Allegra))
  }
