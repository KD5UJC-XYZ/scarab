// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.state.*

/** The evidence that an era is one of Shelley, Allegra, Mary, Alonzo and Babbage: Haskell's
  * `AtMostEra "Babbage" era` with the class constraints it brings (`ShelleyEraTxBody`,
  * `ShelleyEraAccounts`, `ShelleyEraTxCert`, `GovState era ~ ShelleyGovState era` ...).
  *
  * The state of an era is chosen by the match types of `Eras.scala`, which reduce only at a
  * concrete era; a rule that is one instance for the five eras reaches the records of Shelley
  * through the equalities this evidence holds (given once, at the five concrete eras, where the
  * families reduce), so the rule has no cast and no match on the witness for the state. Conway has
  * no instance: the compiler refuses a Shelley rule on the state of Conway.
  *
  * What differs between the five eras themselves (the checks an era class adds, `Alonzo` 's
  * collateral, `Babbage` 's reference inputs) is the witness' [[atLeast]], as the design has it
  * (§2.1); the checks that Haskell gates by a protocol version predicate (`hardfork*`) stay checks
  * of the version of the parameters in force.
  */
sealed trait PreConway[E <: EraTag]:
  /** The witness of the era. */
  def era: ShelleyBasedEra[E]

  def certIs: CertState[E] =:= ShelleyCertState
  def govIs: GovState[E] =:= ShelleyGovState
  def stakeIs: InstantStake[E] =:= ShelleyInstantStake

  /** The era is `other` or a later one (`AtLeastEra`/`AtMostEra` in the reference's constraints):
    * the order of the eras is that of the witness's cases.
    */
  def atLeast[F <: EraTag](other: ShelleyBasedEra[F]): Boolean = era.ordinal >= other.ordinal

  /** The certificate state of the ledger state. */
  def cert: Lens[LedgerState[E], ShelleyCertState] =
    Lens[LedgerState[E], ShelleyCertState](l => certIs(l.cert))(c =>
      l => l.copy[E](cert = certIs.flip(c))
    )

  /** The governance state of the UTxO state: the proposed updates. */
  def gov: Lens[UTxOState[E], ShelleyGovState] =
    Lens[UTxOState[E], ShelleyGovState](u => govIs(u.gov))(g => u => u.copy[E](gov = govIs.flip(g)))

  /** The instant stake of the UTxO state: the stake by credential and by pointer. */
  def instantStake: Lens[UTxOState[E], ShelleyInstantStake] =
    Lens[UTxOState[E], ShelleyInstantStake](u => stakeIs(u.instantStake))(s =>
      u => u.copy[E](instantStake = stakeIs.flip(s))
    )

  /** The pools and the delegation state of the ledger state. */
  def pState: Lens[LedgerState[E], PState] = cert.andThen(ShelleyCertState.pState)
  def dState: Lens[LedgerState[E], DState[ShelleyAccounts]] = cert.andThen(ShelleyCertState.dState)

  /** The parameters in force. */
  def curPParams: Lens[UTxOState[E], xyz.kd5ujc.scarab.ledger.ProtocolParams] =
    gov.andThen(ShelleyGovState.curPParams)

object PreConway:
  def apply[E <: EraTag](using pre: PreConway[E]): PreConway[E] = pre

  private def make[E <: EraTag](
      witness: ShelleyBasedEra[E]
  )(using
      c: CertState[E] =:= ShelleyCertState,
      g: GovState[E] =:= ShelleyGovState,
      s: InstantStake[E] =:= ShelleyInstantStake
  ): PreConway[E] = new PreConway[E]:
    val era: ShelleyBasedEra[E] = witness
    val certIs = c
    val govIs = g
    val stakeIs = s

  given PreConway[Shelley] = make(ShelleyBasedEra.SShelley)
  given PreConway[Allegra] = make(ShelleyBasedEra.SAllegra)
  given PreConway[Mary] = make(ShelleyBasedEra.SMary)
  given PreConway[Alonzo] = make(ShelleyBasedEra.SAlonzo)
  given PreConway[Babbage] = make(ShelleyBasedEra.SBabbage)

  /** The genesis delegations the way the node's witness checks take them: by the bytes of the key.
    */
  def genDelegs(
      delegs: SortedMap[KeyHash[Genesis], GenDelegPair]
  ): Map[xyz.kd5ujc.scarab.codec.Bytes, GenDelegPair] =
    delegs.iterator.map((k, p) => k.bytes -> p).toMap

  /** A state of a Shelley-based era that is none of Conway, as the evidence of its era: the witness
    * matched once, for the rule that is given a state of whichever era the chain is in.
    */
  def of[E <: EraTag](era: ShelleyBasedEra[E]): Option[PreConway[E]] = era match
    case ShelleyBasedEra.SShelley => Some(summon[PreConway[Shelley]])
    case ShelleyBasedEra.SAllegra => Some(summon[PreConway[Allegra]])
    case ShelleyBasedEra.SMary    => Some(summon[PreConway[Mary]])
    case ShelleyBasedEra.SAlonzo  => Some(summon[PreConway[Alonzo]])
    case ShelleyBasedEra.SBabbage => Some(summon[PreConway[Babbage]])
    case ShelleyBasedEra.SConway  => None
