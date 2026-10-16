// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.{Diff, Difference, Path}
import cats.{Eq, Show}
import cats.syntax.all.*
import monocle.{Focus, Lens}
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era

/** The ledger state of one era of the Cardano chain (`LedgerState (CardanoBlock c)` is a telescope
  * of these): Byron's, or a Shelley-based era's with the witness of which, packed so that the era
  * parameter is existential (the telescope holds states of different eras).
  *
  * Byron's current state is the node's own [[ByronState]] (a deliberate difference: the mirror does
  * not re-model the Byron ledger).
  */
enum EraLedgerState:
  case ByronCurrent(state: ByronState)
  case InShelleyEra[E <: EraTag](era: ShelleyBasedEra[E], state: ShelleyLedgerState[E])
      extends EraLedgerState

object EraLedgerState:
  /** A Shelley-based era's state, the era taken from the context. */
  def inEra[E <: EraTag](state: ShelleyLedgerState[E])(using
      era: ShelleyBasedEra[E]
  ): EraLedgerState = InShelleyEra(era, state)

  extension [E <: EraTag](era: ShelleyBasedEra[E])
    /** The era a witness stands for. */
    def toEra: Era = era match
      case ShelleyBasedEra.SShelley => Era.Shelley
      case ShelleyBasedEra.SAllegra => Era.Allegra
      case ShelleyBasedEra.SMary    => Era.Mary
      case ShelleyBasedEra.SAlonzo  => Era.Alonzo
      case ShelleyBasedEra.SBabbage => Era.Babbage
      case ShelleyBasedEra.SConway  => Era.Conway

  extension (s: EraLedgerState)
    /** The era the state is in. */
    def era: Era = s match
      case ByronCurrent(_)      => Era.Byron
      case InShelleyEra(era, _) => era.toEra

  given EraIndexed[EraLedgerState] = EraIndexed.instance((era, state) => state.era == era)

  /** Equal when in the same era (matching the two witnesses refines both era parameters to the one
    * era, with no cast) and equal there.
    */
  given Eq[EraLedgerState] = Eq.instance {
    case (ByronCurrent(a), ByronCurrent(b)) => a === b
    case (InShelleyEra(ea, a), InShelleyEra(eb, b)) =>
      import ShelleyBasedEra.*
      (ea, eb) match
        case (SShelley, SShelley) => eqIn(ea, a, b)
        case (SAllegra, SAllegra) => eqIn(ea, a, b)
        case (SMary, SMary)       => eqIn(ea, a, b)
        case (SAlonzo, SAlonzo)   => eqIn(ea, a, b)
        case (SBabbage, SBabbage) => eqIn(ea, a, b)
        case (SConway, SConway)   => eqIn(ea, a, b)
        case (_, _)               => false
    case _ => false
  }

  /** The first difference of two states of one era; states of different eras differ at the root
    * (`ExtLedgerState`'s telescope puts them in the same position only through the same era).
    */
  given Diff[EraLedgerState] = new Diff[EraLedgerState]:
    def diff(a: EraLedgerState, b: EraLedgerState): Option[Difference] = (a, b) match
      case (ByronCurrent(x), ByronCurrent(y)) => Diff[ByronState].diff(x, y)
      case (InShelleyEra(ea, x), InShelleyEra(eb, y)) =>
        import ShelleyBasedEra.*
        (ea, eb) match
          case (SShelley, SShelley) => diffIn(ea, x, y)
          case (SAllegra, SAllegra) => diffIn(ea, x, y)
          case (SMary, SMary)       => diffIn(ea, x, y)
          case (SAlonzo, SAlonzo)   => diffIn(ea, x, y)
          case (SBabbage, SBabbage) => diffIn(ea, x, y)
          case (SConway, SConway)   => diffIn(ea, x, y)
          case (_, _)               => Some(Difference(Path.root, show(a), show(b)))
      case _ => Some(Difference(Path.root, show(a), show(b)))
    def show(a: EraLedgerState): String = Difference.clipped(a match
      case ByronCurrent(s)    => s"ByronCurrent(${s.toString})"
      case InShelleyEra(e, s) => s"InShelleyEra(${e.show}, ${showIn(e, s)})")

  given Show[EraLedgerState] = Show.show {
    case ByronCurrent(s)    => s"ByronCurrent(${s.show})"
    case InShelleyEra(e, s) => s"InShelleyEra(${e.show}, ${showIn(e, s)})"
  }

  private def eqIn[E <: EraTag](
      era: ShelleyBasedEra[E],
      a: ShelleyLedgerState[E],
      b: ShelleyLedgerState[E]
  ): Boolean =
    given ShelleyBasedEra[E] = era
    a === b

  private def diffIn[E <: EraTag](
      era: ShelleyBasedEra[E],
      a: ShelleyLedgerState[E],
      b: ShelleyLedgerState[E]
  ): Option[Difference] =
    given ShelleyBasedEra[E] = era
    Diff[ShelleyLedgerState[E]].diff(a, b)

  private def showIn[E <: EraTag](era: ShelleyBasedEra[E], s: ShelleyLedgerState[E]): String =
    given ShelleyBasedEra[E] = era
    s.show

/** The ledger state of the Cardano chain: a telescope from Byron to the current era. */
type CardanoLedgerState = Telescope[EraLedgerState]

/** `ExtLedgerState` (`Ledger/Extended.hs:70-73`): the ledger state with the header state, less the
  * `LedgerTables` (the UTxO stays in the node's store).
  */
final case class ExtLedgerState(ledgerState: CardanoLedgerState, headerState: HeaderState)

object ExtLedgerState:
  given Eq[ExtLedgerState] =
    Eq.instance((a, b) => a.ledgerState === b.ledgerState && a.headerState === b.headerState)

  given Diff[ExtLedgerState] =
    Diff.record(Diff.at("ledgerState")(_.ledgerState), Diff.at("headerState")(_.headerState))

  given Show[ExtLedgerState] = Show.show(e =>
    s"ExtLedgerState(ledgerState = ${e.ledgerState.show}, headerState = ${e.headerState.show})"
  )

  val ledgerState: Lens[ExtLedgerState, CardanoLedgerState] = Focus[ExtLedgerState](_.ledgerState)
  val headerState: Lens[ExtLedgerState, HeaderState] = Focus[ExtLedgerState](_.headerState)
