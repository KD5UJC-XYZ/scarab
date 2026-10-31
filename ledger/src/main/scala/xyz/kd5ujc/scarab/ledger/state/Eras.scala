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

  // Which shape each type family takes in this era, with the proof. Matching on one case refines `E`
  // to that era, so the families reduce there; this is the one place that lists the eras of each
  // shape, and era-generic code works on the proofs instead of matching on eras.

  /** The certificate, account, instant-stake and governance families of this era, with their
    * shapes: Shelley's records up to Babbage, Conway's from Conway.
    */
  def shapes: Either[ShelleyShapes[E], ConwayShapes[E]] = this match
    case SShelley => Left(ShelleyShapes(summon, summon, summon, summon))
    case SAllegra => Left(ShelleyShapes(summon, summon, summon, summon))
    case SMary    => Left(ShelleyShapes(summon, summon, summon, summon))
    case SAlonzo  => Left(ShelleyShapes(summon, summon, summon, summon))
    case SBabbage => Left(ShelleyShapes(summon, summon, summon, summon))
    case SConway  => Right(ConwayShapes(summon, summon, summon, summon))

  /** `CertState[E]`: Shelley's record up to Babbage, Conway's from Conway. */
  def cert: EraShape[CertState[E], ShelleyCertState, ConwayCertState] =
    shapes.fold(s => Left(s.cert), c => Right(c.cert))

  /** `Accounts[E]`: with the pointer map up to Babbage, with the DRep from Conway. */
  def accounts: EraShape[Accounts[E], ShelleyAccounts, ConwayAccounts] =
    shapes.fold(s => Left(s.accounts), c => Right(c.accounts))

  /** `InstantStake[E]`: with the pointer stake up to Babbage, without it from Conway. */
  def instantStake: EraShape[InstantStake[E], ShelleyInstantStake, ConwayInstantStake] =
    shapes.fold(s => Left(s.instantStake), c => Right(c.instantStake))

  /** `GovState[E]`: the proposed updates up to Babbage, Conway's governance from Conway. */
  def gov: EraShape[GovState[E], ShelleyGovState, ConwayGovState] =
    shapes.fold(s => Left(s.gov), c => Right(c.gov))

  /** `StashedAVVM[E]`: the UTxO in Shelley, nothing after. */
  def stashedAvvm: EraShape[StashedAVVM[E], UtxoTables, Unit] = this match
    case SShelley => Left(summon)
    case SAllegra => Right(summon)
    case SMary    => Right(summon)
    case SAlonzo  => Right(summon)
    case SBabbage => Right(summon)
    case SConway  => Right(summon)

object ShelleyBasedEra:
  def apply[E <: EraTag](using era: ShelleyBasedEra[E]): ShelleyBasedEra[E] = era

  given ShelleyBasedEra[Shelley] = SShelley
  given ShelleyBasedEra[Allegra] = SAllegra
  given ShelleyBasedEra[Mary] = SMary
  given ShelleyBasedEra[Alonzo] = SAlonzo
  given ShelleyBasedEra[Babbage] = SBabbage
  given ShelleyBasedEra[Conway] = SConway

  given [E <: EraTag]: Show[ShelleyBasedEra[E]] = Show.show(_.toString)

/** The families of an era up to Babbage are Shelley's records: the proofs. */
final case class ShelleyShapes[E <: EraTag](
    cert: CertState[E] =:= ShelleyCertState,
    accounts: Accounts[E] =:= ShelleyAccounts,
    instantStake: InstantStake[E] =:= ShelleyInstantStake,
    gov: GovState[E] =:= ShelleyGovState
)

/** The families of an era from Conway are Conway's records: the proofs. */
final case class ConwayShapes[E <: EraTag](
    cert: CertState[E] =:= ConwayCertState,
    accounts: Accounts[E] =:= ConwayAccounts,
    instantStake: InstantStake[E] =:= ConwayInstantStake,
    gov: GovState[E] =:= ConwayGovState
)

/** Which of two shapes `A` and `B` the type family `F` takes in an era, with the proof that `F` is
  * that shape (an era witness gives it: [[ShelleyBasedEra.cert]], ...). The operations below are
  * the only conversions between a family and its shapes; none is a cast.
  */
type EraShape[F, A, B] = Either[F =:= A, F =:= B]

extension [F, A, B](shape: EraShape[F, A, B])
  /** `value` seen as the shape it has. */
  def on[R](value: F)(a: A => R, b: B => R): R =
    shape.fold(ev => a(ev(value)), ev => b(ev(value)))

  /** `value` changed within the shape it has. */
  def update(value: F)(a: A => A, b: B => B): F =
    shape.fold(ev => ev.flip(a(ev(value))), ev => ev.flip(b(ev(value))))

  /** A value of the family, built in the shape the era has. */
  def make(a: => A, b: => B): F = shape.fold(ev => ev.flip(a), ev => ev.flip(b))

  /** The instance of a type class for the family, from the instance for the shape the era has (only
    * that one is built).
    */
  def instance[T[_]](a: => T[A], b: => T[B]): T[F] =
    shape.fold(ev => ev.flip.substituteCo(a), ev => ev.flip.substituteCo(b))

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
  given certStateEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[CertState[E]] =
    era.cert.instance(Eq[ShelleyCertState], Eq[ConwayCertState])

  given certStateShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[CertState[E]] =
    era.cert.instance(Show[ShelleyCertState], Show[ConwayCertState])

  given govStateEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[GovState[E]] =
    era.gov.instance(Eq[ShelleyGovState], Eq[ConwayGovState])

  given govStateShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[GovState[E]] =
    era.gov.instance(Show[ShelleyGovState], Show[ConwayGovState])

  given instantStakeEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[InstantStake[E]] =
    era.instantStake.instance(Eq[ShelleyInstantStake], Eq[ConwayInstantStake])

  given instantStakeShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[InstantStake[E]] =
    era.instantStake.instance(Show[ShelleyInstantStake], Show[ConwayInstantStake])

  given stashedAvvmEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[StashedAVVM[E]] =
    era.stashedAvvm.instance(Eq[UtxoTables], Eq[Unit])

  given stashedAvvmShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[StashedAVVM[E]] =
    era.stashedAvvm.instance(Show[UtxoTables], Show[Unit])

  given certStateDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[CertState[E]] =
    era.cert.instance(Diff[ShelleyCertState], Diff[ConwayCertState])

  given govStateDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[GovState[E]] =
    era.gov.instance(Diff[ShelleyGovState], Diff[ConwayGovState])

  given instantStakeDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[InstantStake[E]] =
    era.instantStake.instance(Diff[ShelleyInstantStake], Diff[ConwayInstantStake])

  given stashedAvvmDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[StashedAVVM[E]] =
    era.stashedAvvm.instance(Diff[UtxoTables], Diff[Unit])
