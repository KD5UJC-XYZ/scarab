// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import cats.derived.*

/** The eras of the ledger-state mirror, as types (phantoms: never instantiated). The era-dependent
  * parts of the Haskell state are type families over the era (`CertState era`, `GovState era`,
  * ...); here they are the match types below, selected by the [[ShelleyBasedEra]] witness.
  */
sealed trait EraTag
sealed abstract class Shelley extends EraTag
sealed abstract class Allegra extends EraTag
sealed abstract class Mary extends EraTag
sealed abstract class Alonzo extends EraTag
sealed abstract class Babbage extends EraTag
sealed abstract class Conway extends EraTag

/** The closed witness of an era: matching on it refines `E`, so the match types below reduce in the
  * branch with no cast, and the compiler checks that every era is handled. Era-generic code takes
  * it as `using ShelleyBasedEra[E]`.
  *
  * Conventions the encoding imposes (spike 2026-10-15): give the era to `apply` /`copy` of an
  * era-generic case class (`LedgerState[Conway](..)`); write `Eq` /`Show` of an era-generic
  * composite by hand over the witness (kittens cannot derive them for an abstract `E`); one Monocle
  * `Focus` per concrete case class, composed with `andThen`; one case per era in a match type.
  */
enum ShelleyBasedEra[E <: EraTag]:
  case SShelley extends ShelleyBasedEra[Shelley]
  case SAllegra extends ShelleyBasedEra[Allegra]
  case SMary extends ShelleyBasedEra[Mary]
  case SAlonzo extends ShelleyBasedEra[Alonzo]
  case SBabbage extends ShelleyBasedEra[Babbage]
  case SConway extends ShelleyBasedEra[Conway]

object ShelleyBasedEra:
  def apply[E <: EraTag](using era: ShelleyBasedEra[E]): ShelleyBasedEra[E] = era

  given ShelleyBasedEra[Shelley] = SShelley
  given ShelleyBasedEra[Allegra] = SAllegra
  given ShelleyBasedEra[Mary] = SMary
  given ShelleyBasedEra[Alonzo] = SAlonzo
  given ShelleyBasedEra[Babbage] = SBabbage
  given ShelleyBasedEra[Conway] = SConway

  given [E <: EraTag]: Show[ShelleyBasedEra[E]] = Show.show(_.toString)

// ---- the era-dependent parts of the state
//
// The records below are STUBS: the targets S1 fills in with the real Haskell fields
// (`Shelley/State/CertState.hs`, `Conway/State/CertState.hs`, `Shelley/State/Stake.hs`,
// `Conway/State/Stake.hs`, the Shelley and Conway `GovState`, `LedgerState/Types.hs:357`). They have
// no fields yet and only fix the era split.

final case class ShelleyCertState() derives Eq, Show // `(PState, DState)`
final case class ConwayCertState() derives Eq, Show // `(VState, PState, DState)`
final case class ShelleyAccounts() derives Eq, Show // per-account balance, deposit, pointer, pool
final case class ConwayAccounts() derives Eq, Show // per-account balance, deposit, pool, DRep
final case class ShelleyInstantStake() derives Eq, Show // credential stake and pointer stake
final case class ConwayInstantStake() derives Eq, Show // credential stake
final case class ShelleyGovState() derives Eq, Show // proposed updates, `PParams`
final case class ConwayGovState()
    derives Eq,
      Show // proposals, committee, constitution, DRep pulser
final case class StashedUtxo() derives Eq, Show // the genesis AVVM UTxO, held in Shelley only

/** `CertState era`: the certificate state grew the voting state in Conway. */
type CertState[E <: EraTag] = E match
  case Shelley => ShelleyCertState
  case Allegra => ShelleyCertState
  case Mary    => ShelleyCertState
  case Alonzo  => ShelleyCertState
  case Babbage => ShelleyCertState
  case Conway  => ConwayCertState

/** `Accounts era`: the stake accounts lost the pointer map and gained the DRep in Conway. */
type Accounts[E <: EraTag] = E match
  case Shelley => ShelleyAccounts
  case Allegra => ShelleyAccounts
  case Mary    => ShelleyAccounts
  case Alonzo  => ShelleyAccounts
  case Babbage => ShelleyAccounts
  case Conway  => ConwayAccounts

/** `InstantStake era`: the pointer stake is gone in Conway. */
type InstantStake[E <: EraTag] = E match
  case Shelley => ShelleyInstantStake
  case Allegra => ShelleyInstantStake
  case Mary    => ShelleyInstantStake
  case Alonzo  => ShelleyInstantStake
  case Babbage => ShelleyInstantStake
  case Conway  => ConwayInstantStake

/** `GovState era`: the governance state of Conway replaces the proposed protocol-parameter updates.
  */
type GovState[E <: EraTag] = E match
  case Shelley => ShelleyGovState
  case Allegra => ShelleyGovState
  case Mary    => ShelleyGovState
  case Alonzo  => ShelleyGovState
  case Babbage => ShelleyGovState
  case Conway  => ConwayGovState

/** `StashedAVVMAddresses era`: the UTxO in Shelley, `()` after (`LedgerState/Types.hs:357-359`). */
type StashedAVVM[E <: EraTag] = E match
  case Shelley => StashedUtxo
  case Allegra => Unit
  case Mary    => Unit
  case Alonzo  => Unit
  case Babbage => Unit
  case Conway  => Unit
