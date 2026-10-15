// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era

/** A Shelley-based era whichever it is: the witness packed so that the era parameter is
  * existential. What turns a value of the runtime [[Era]] into the type-level `E` the mirror's
  * era-indexed types need, with no cast: match on the pack and the witness is in scope.
  *
  * {{{
  * SomeEra.of(era) match
  *   case Some(of: SomeEra.Of[e]) => convert[e](using of.era)
  *   case None                    => byron
  * }}}
  */
enum SomeEra:
  case Of[E <: EraTag](era: ShelleyBasedEra[E])

object SomeEra:
  /** The pack of an era, `None` for Byron (which has no Shelley-based state). Total. */
  def of(era: Era): Option[SomeEra] = era match
    case Era.Byron   => None
    case Era.Shelley => Some(Of(ShelleyBasedEra.SShelley))
    case Era.Allegra => Some(Of(ShelleyBasedEra.SAllegra))
    case Era.Mary    => Some(Of(ShelleyBasedEra.SMary))
    case Era.Alonzo  => Some(Of(ShelleyBasedEra.SAlonzo))
    case Era.Babbage => Some(Of(ShelleyBasedEra.SBabbage))
    case Era.Conway  => Some(Of(ShelleyBasedEra.SConway))
