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
sealed abstract class Dijkstra extends EraTag

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
  case SDijkstra extends ShelleyBasedEra[Dijkstra]

  /** The era is `other` or a later one (`AtLeastEra` in the reference's constraints): the order of
    * the eras is that of the cases.
    */
  def atLeast[F <: EraTag](other: ShelleyBasedEra[F]): Boolean = ordinal >= other.ordinal

  // Which shape each type family takes in this era, with the proof. Matching on one case refines `E`
  // to that era, so the families reduce there; this is the one place that lists the eras of each
  // shape, and era-generic code works on the proofs instead of matching on eras.

  /** The certificate, account and instant-stake families of this era, with their shapes, and the
    * governance family up to Babbage: Shelley's records up to Babbage, Conway's from Conway.
    * Dijkstra's are Conway's records (cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/State/CertState.hs:23`, `State/Account.hs:13`,
    * `State/Stake.hs:19`); its governance family is the era's own record ([[gov]]).
    */
  def shapes: Either[ShelleyShapes[E], ConwayShapes[E]] = this match
    case SShelley  => Left(ShelleyShapes(summon, summon, summon, summon))
    case SAllegra  => Left(ShelleyShapes(summon, summon, summon, summon))
    case SMary     => Left(ShelleyShapes(summon, summon, summon, summon))
    case SAlonzo   => Left(ShelleyShapes(summon, summon, summon, summon))
    case SBabbage  => Left(ShelleyShapes(summon, summon, summon, summon))
    case SConway   => Right(ConwayShapes(summon, summon, summon))
    case SDijkstra => Right(ConwayShapes(summon, summon, summon))

  /** `CertState[E]`: Shelley's record up to Babbage, Conway's from Conway. */
  def cert: EraShape[CertState[E], ShelleyCertState, ConwayCertState] =
    shapes.fold(s => Left(s.cert), c => Right(c.cert))

  /** `Accounts[E]`: with the pointer map up to Babbage, with the DRep from Conway. */
  def accounts: EraShape[Accounts[E], ShelleyAccounts, ConwayAccounts] =
    shapes.fold(s => Left(s.accounts), c => Right(c.accounts))

  /** `InstantStake[E]`: with the pointer stake up to Babbage, without it from Conway. */
  def instantStake: EraShape[InstantStake[E], ShelleyInstantStake, ConwayInstantStake] =
    shapes.fold(s => Left(s.instantStake), c => Right(c.instantStake))

  /** `GovState[E]`: the proposed updates up to Babbage, Conway's governance record in Conway, and
    * the era's own record in Dijkstra ([[GovShape]]).
    */
  def gov: GovShape[E] = this match
    case SShelley  => GovShape.Proposed(summon)
    case SAllegra  => GovShape.Proposed(summon)
    case SMary     => GovShape.Proposed(summon)
    case SAlonzo   => GovShape.Proposed(summon)
    case SBabbage  => GovShape.Proposed(summon)
    case SConway   => GovShape.Flat(summon)
    case SDijkstra => GovShape.Era(summon)

  /** `StashedAVVM[E]`: the UTxO in Shelley, nothing after. */
  def stashedAvvm: EraShape[StashedAVVM[E], UtxoTables, Unit] = this match
    case SShelley  => Left(summon)
    case SAllegra  => Right(summon)
    case SMary     => Right(summon)
    case SAlonzo   => Right(summon)
    case SBabbage  => Right(summon)
    case SConway   => Right(summon)
    case SDijkstra => Right(summon)

object ShelleyBasedEra:
  def apply[E <: EraTag](using era: ShelleyBasedEra[E]): ShelleyBasedEra[E] = era

  given ShelleyBasedEra[Shelley] = SShelley
  given ShelleyBasedEra[Allegra] = SAllegra
  given ShelleyBasedEra[Mary] = SMary
  given ShelleyBasedEra[Alonzo] = SAlonzo
  given ShelleyBasedEra[Babbage] = SBabbage
  given ShelleyBasedEra[Conway] = SConway
  given ShelleyBasedEra[Dijkstra] = SDijkstra

  given [E <: EraTag]: Show[ShelleyBasedEra[E]] = Show.show(_.toString)

/** The families of an era up to Babbage are Shelley's records: the proofs. */
final case class ShelleyShapes[E <: EraTag](
    cert: CertState[E] =:= ShelleyCertState,
    accounts: Accounts[E] =:= ShelleyAccounts,
    instantStake: InstantStake[E] =:= ShelleyInstantStake,
    gov: GovState[E] =:= ShelleyGovState
)

/** The certificate, account and instant-stake families of an era from Conway are Conway's records:
  * the proofs. Its governance family is the era's [[GovShape]].
  */
final case class ConwayShapes[E <: EraTag](
    cert: CertState[E] =:= ConwayCertState,
    accounts: Accounts[E] =:= ConwayAccounts,
    instantStake: InstantStake[E] =:= ConwayInstantStake
)

object ConwayShapes:
  given conway: ConwayShapes[Conway] = ConwayShapes(summon, summon, summon)
  given dijkstra: ConwayShapes[Dijkstra] = ConwayShapes(summon, summon, summon)

/** Which record the governance family `GovState[E]` is in an era, with the proof: Shelley's
  * proposed updates up to Babbage, the node's flat record of Conway's governance in Conway, and in
  * Dijkstra the era's own record, `ConwayGovState DijkstraEra` (cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Governance.hs:29`), which the ledger state holds
  * and the Haskell state codec reads and writes as the reference does (#85, FMT-1).
  */
enum GovShape[E <: EraTag]:
  case Proposed(ev: GovState[E] =:= ShelleyGovState)
  case Flat(ev: GovState[E] =:= ConwayGovState)
  case Era(ev: GovState[E] =:= xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra])

  /** `value` seen as the record it is. */
  def on[R](value: GovState[E])(
      proposed: ShelleyGovState => R,
      flat: ConwayGovState => R,
      era: xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra] => R
  ): R = this match
    case Proposed(ev) => proposed(ev(value))
    case Flat(ev)     => flat(ev(value))
    case Era(ev)      => era(ev(value))

  /** `value` changed within the record it is. */
  def update(value: GovState[E])(
      proposed: ShelleyGovState => ShelleyGovState,
      flat: ConwayGovState => ConwayGovState,
      era: xyz.kd5ujc.scarab.ledger.era.ConwayGovState[
        Dijkstra
      ] => xyz.kd5ujc.scarab.ledger.era.ConwayGovState[
        Dijkstra
      ]
  ): GovState[E] = this match
    case Proposed(ev) => ev.flip(proposed(ev(value)))
    case Flat(ev)     => ev.flip(flat(ev(value)))
    case Era(ev)      => ev.flip(era(ev(value)))

  /** The proof that the family is one of the node's flat records, Shelley's proposed updates or
    * Conway's flat record; none in Dijkstra, whose record is the era's.
    */
  def flat: Option[FlatGov[E]] = this match
    case Proposed(ev) => Some(FlatGov(Left(ev)))
    case Flat(ev)     => Some(FlatGov(Right(ev)))
    case Era(_)       => None

  /** The instance of a type class for the family, from the instance for the record the era has. */
  def instance[T[_]](
      proposed: => T[ShelleyGovState],
      flat: => T[ConwayGovState],
      era: => T[xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra]]
  ): T[GovState[E]] = this match
    case Proposed(ev) => ev.flip.substituteCo(proposed)
    case Flat(ev)     => ev.flip.substituteCo(flat)
    case Era(ev)      => ev.flip.substituteCo(era)

/** The proof that the governance family of an era is one of the node's flat records: Shelley's
  * proposed updates (up to Babbage) or Conway's flat record (Conway), what the setters of the flat
  * parameters write ([[GovShape.flat]]).
  */
final case class FlatGov[E <: EraTag](
    record: Either[GovState[E] =:= ShelleyGovState, GovState[E] =:= ConwayGovState]
)

object FlatGov:
  given shelley: FlatGov[Shelley] = FlatGov(Left(summon))
  given allegra: FlatGov[Allegra] = FlatGov(Left(summon))
  given mary: FlatGov[Mary] = FlatGov(Left(summon))
  given alonzo: FlatGov[Alonzo] = FlatGov(Left(summon))
  given babbage: FlatGov[Babbage] = FlatGov(Left(summon))
  given conway: FlatGov[Conway] = FlatGov(Right(summon))

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
  case Shelley  => ShelleyCertState
  case Allegra  => ShelleyCertState
  case Mary     => ShelleyCertState
  case Alonzo   => ShelleyCertState
  case Babbage  => ShelleyCertState
  case Conway   => ConwayCertState
  case Dijkstra => ConwayCertState

/** `Accounts era`: the stake accounts lost the pointer map and gained the DRep in Conway. Every
  * case is an [[AccountsRecord]], so a delegation state of any era is a `DState[Accounts[E]]`.
  */
type Accounts[E <: EraTag] <: AccountsRecord = E match
  case Shelley  => ShelleyAccounts
  case Allegra  => ShelleyAccounts
  case Mary     => ShelleyAccounts
  case Alonzo   => ShelleyAccounts
  case Babbage  => ShelleyAccounts
  case Conway   => ConwayAccounts
  case Dijkstra => ConwayAccounts

/** `InstantStake era`: the pointer stake is gone in Conway. */
type InstantStake[E <: EraTag] = E match
  case Shelley  => ShelleyInstantStake
  case Allegra  => ShelleyInstantStake
  case Mary     => ShelleyInstantStake
  case Alonzo   => ShelleyInstantStake
  case Babbage  => ShelleyInstantStake
  case Conway   => ConwayInstantStake
  case Dijkstra => ConwayInstantStake

/** `GovState era`: the governance state of Conway replaces the proposed protocol-parameter updates.
  * Dijkstra's is the era's own record (`Dijkstra/Governance.hs:29`, cardano-ledger 9df9f98c), with
  * the era's parameters and updates; Conway's is still the node's flat record.
  */
type GovState[E <: EraTag] = E match
  case Shelley  => ShelleyGovState
  case Allegra  => ShelleyGovState
  case Mary     => ShelleyGovState
  case Alonzo   => ShelleyGovState
  case Babbage  => ShelleyGovState
  case Conway   => ConwayGovState
  case Dijkstra => xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra]

/** `StashedAVVMAddresses era`: the UTxO (the same opaque handle as the main one) in Shelley, `()`
  * after (`LedgerState/Types.hs:357-359`, cardano-ledger f649f975).
  */
type StashedAVVM[E <: EraTag] = E match
  case Shelley  => UtxoTables
  case Allegra  => Unit
  case Mary     => Unit
  case Alonzo   => Unit
  case Babbage  => Unit
  case Conway   => Unit
  case Dijkstra => Unit

/** `Eq` and `Show` of the types the families select, by case analysis on the witness. They cannot
  * be found by implicit search from an abstract `E` (a match type has no implicit scope to look
  * in), so the era-generic composites import this object's givens.
  */
object EraInstances:
  /** Dijkstra's governance record compared by its values (`ProposalProcedure` 's equality ignores
    * the proposal it was read as) and shown as a whole.
    */
  private val eraGovEq: Eq[xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra]] =
    Eq.fromUniversalEquals

  private val eraGovShow: Show[xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra]] =
    Show.fromToString

  given certStateEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[CertState[E]] =
    era.cert.instance(Eq[ShelleyCertState], Eq[ConwayCertState])

  given certStateShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[CertState[E]] =
    era.cert.instance(Show[ShelleyCertState], Show[ConwayCertState])

  given govStateEq[E <: EraTag](using era: ShelleyBasedEra[E]): Eq[GovState[E]] =
    era.gov.instance(Eq[ShelleyGovState], Eq[ConwayGovState], eraGovEq)

  given govStateShow[E <: EraTag](using era: ShelleyBasedEra[E]): Show[GovState[E]] =
    era.gov.instance(Show[ShelleyGovState], Show[ConwayGovState], eraGovShow)

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
    era.gov.instance(
      Diff[ShelleyGovState],
      Diff[ConwayGovState],
      Diff.leaf(eraGovEq, eraGovShow.show)
    )

  given instantStakeDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[InstantStake[E]] =
    era.instantStake.instance(Diff[ShelleyInstantStake], Diff[ConwayInstantStake])

  given stashedAvvmDiff[E <: EraTag](using era: ShelleyBasedEra[E]): Diff[StashedAVVM[E]] =
    era.stashedAvvm.instance(Diff[UtxoTables], Diff[Unit])
