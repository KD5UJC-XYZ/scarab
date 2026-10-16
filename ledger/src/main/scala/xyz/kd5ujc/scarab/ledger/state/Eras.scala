// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}

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
// The records themselves are in `CertState.scala` (`ShelleyCertState`, `ConwayCertState`),
// `Stake.scala` (accounts, instant stake) and `GovState.scala` (`ShelleyGovState`,
// `ConwayGovState`); here only the type families that choose between them.

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

/** `StashedAVVMAddresses era`: the UTxO (the same opaque handle as the main one) in Shelley, `()`
  * after (`LedgerState/Types.hs:357-359`).
  */
type StashedAVVM[E <: EraTag] = E match
  case Shelley => UtxoTables
  case Allegra => Unit
  case Mary    => Unit
  case Alonzo  => Unit
  case Babbage => Unit
  case Conway  => Unit

/** `Eq` and `Show` of the types the families select, by case analysis on the witness. They cannot
  * be found by implicit search from an abstract `E` (a match type has no implicit scope to look
  * in), so the era-generic composites import this object's givens.
  */
object EraInstances:
  given certStateEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[CertState[E]] = era match
    case ShelleyBasedEra.SConway => Eq[ConwayCertState]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Eq[ShelleyCertState]

  given certStateShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[CertState[E]] = era match
    case ShelleyBasedEra.SConway => Show[ConwayCertState]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Show[ShelleyCertState]

  given govStateEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[GovState[E]] = era match
    case ShelleyBasedEra.SConway => Eq[ConwayGovState]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Eq[ShelleyGovState]

  given govStateShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[GovState[E]] = era match
    case ShelleyBasedEra.SConway => Show[ConwayGovState]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Show[ShelleyGovState]

  given instantStakeEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[InstantStake[E]] =
    era match
      case ShelleyBasedEra.SConway => Eq[ConwayInstantStake]
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        Eq[ShelleyInstantStake]

  given instantStakeShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[InstantStake[E]] =
    era match
      case ShelleyBasedEra.SConway => Show[ConwayInstantStake]
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        Show[ShelleyInstantStake]

  given stashedAvvmEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[StashedAVVM[E]] = era match
    case ShelleyBasedEra.SShelley => Eq[UtxoTables]
    case ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary | ShelleyBasedEra.SAlonzo |
        ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
      Eq[Unit]

  given stashedAvvmShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[StashedAVVM[E]] =
    era match
      case ShelleyBasedEra.SShelley => Show[UtxoTables]
      case ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary | ShelleyBasedEra.SAlonzo |
          ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
        Show[Unit]

  given certStateDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[CertState[E]] = era match
    case ShelleyBasedEra.SConway => Diff[ConwayCertState]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Diff[ShelleyCertState]

  given govStateDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[GovState[E]] = era match
    case ShelleyBasedEra.SConway => Diff[ConwayGovState]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Diff[ShelleyGovState]

  given instantStakeDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[InstantStake[E]] =
    era match
      case ShelleyBasedEra.SConway => Diff[ConwayInstantStake]
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        Diff[ShelleyInstantStake]

  given stashedAvvmDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[StashedAVVM[E]] =
    era match
      case ShelleyBasedEra.SShelley => Diff[UtxoTables]
      case ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary | ShelleyBasedEra.SAlonzo |
          ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
        Diff[Unit]
