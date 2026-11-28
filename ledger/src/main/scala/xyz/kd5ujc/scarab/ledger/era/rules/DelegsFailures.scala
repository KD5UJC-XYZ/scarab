// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import xyz.kd5ujc.scarab.ledger.era.{MIRPot, Mismatch}
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Coin,
  Credential,
  EpochNo,
  EraTag,
  Genesis,
  GenesisDelegate,
  KeyHash,
  SlotNo,
  StakePool,
  Staking,
  VRFVerKeyHash
}

// The failures and events of Shelley's certificate rules, which the eras up to Babbage run, as the
// reference declares them (cardano-ledger f649f975,
// `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/{Delegs,Delpl,Deleg}.hs`), with each era's
// injections (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/{Delegs,Delpl,Deleg}.hs`) and the
// embeddings of each rule in the rule that runs it. POOL's are in `CertsFailures.scala`, and the
// family of DELEGS's failures (`DelegsPredFailure`) in `LedgerFailures.scala`. At
// 9df9f98c the types derive `Ord` too, and `StakeKeyAlreadyRegisteredDELEG` is
// `DelegAccountAlreadyRegistered`, over Shelley's `AccountAlreadyRegistered`, a newtype of the same
// credential with the same tag and bytes (`Shelley/Rules/Deleg.hs:84-87, 126-127`,
// `Babbage/Rules/Deleg.hs:16-17`, cardano-ledger 9df9f98c); the era types of Alonzo and Babbage
// follow the 11.1 build's ledger, which has neither.

/** `data ShelleyDelegPredFailure era` (`Shelley/Rules/Deleg.hs:89-122`, cardano-ledger f649f975),
  * its constructors in their order (tags 0 to 9, then 11 to 16, `:153-195`: tag 10 is unused).
  */
enum ShelleyDelegPredFailure[E <: EraTag]:
  case StakeKeyAlreadyRegisteredDELEG(credential: Credential[Staking])
  case StakeKeyNotRegisteredDELEG(credential: Credential[Staking])

  /** The balance left in the account. */
  case StakeKeyNonZeroAccountBalanceDELEG(coin: Coin)
  case StakeDelegationImpossibleDELEG(credential: Credential[Staking])

  /** A pool's certificate reached the rule, which it never does through DELPL. */
  case WrongCertificateTypeDELEG()
  case GenesisKeyNotInMappingDELEG(genesis: KeyHash[Genesis])
  case DuplicateGenesisDelegateDELEG(delegate: KeyHash[GenesisDelegate])

  /** The rewards the pot is to pay (supplied) and what it has (expected). */
  case InsufficientForInstantaneousRewardsDELEG(pot: MIRPot, mismatch: Mismatch[RelLTEQ, Coin])

  /** The slot (supplied) and the first slot too late for the epoch (expected). */
  case MIRCertificateTooLateinEpochDELEG(mismatch: Mismatch[RelLT, SlotNo])
  case DuplicateGenesisVRFDELEG(vrf: VRFVerKeyHash)
  case MIRTransferNotCurrentlyAllowed()
  case MIRNegativesNotCurrentlyAllowed()

  /** The amount to move (supplied) and what the pot has left (expected). */
  case InsufficientForTransferDELEG(pot: MIRPot, mismatch: Mismatch[RelLTEQ, Coin])
  case MIRProducesNegativeUpdate()

  /** The amount the certificate moves. */
  case MIRNegativeTransfer(pot: MIRPot, coin: Coin)
  case DelegateeNotRegisteredDELEG(pool: KeyHash[StakePool])

/** `newtype ShelleyDelegEvent era = DelegNewEpoch EpochNo` (`Shelley/Rules/Deleg.hs:128`,
  * cardano-ledger f649f975): the epoch after the environment's, which a MIR certificate's check of
  * its slot tells.
  */
enum ShelleyDelegEvent[E <: EraTag]:
  case DelegNewEpoch(epoch: EpochNo)

/** `data ShelleyDelplPredFailure era` (`Shelley/Rules/Delpl.hs:60-63`, cardano-ledger f649f975),
  * its constructors in the order of their tags (0 and 1, `:143-151`).
  */
enum ShelleyDelplPredFailure[E <: EraTag]:
  case PoolFailure(failure: PoolPredFailure[E])
  case DelegFailure(failure: DelegPredFailure[E])

/** `data ShelleyDelplEvent era` (`Shelley/Rules/Delpl.hs:75-77`, cardano-ledger f649f975). */
enum ShelleyDelplEvent[E <: EraTag]:
  case PoolEvent(event: PoolRuleEvent[E])
  case DelegEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.DelegEvent[E])

/** `newtype ShelleyDelegsPredFailure era = DelplFailure ...` (`Shelley/Rules/Delegs.hs:82-84`,
  * cardano-ledger f649f975), tag 1 (`:148-152`).
  */
enum ShelleyDelegsPredFailure[E <: EraTag]:
  case DelplFailure(failure: DelplPredFailure[E])

/** `newtype ShelleyDelegsEvent era = DelplEvent ...` (`Shelley/Rules/Delegs.hs:100`, cardano-ledger
  * f649f975).
  */
enum ShelleyDelegsEvent[E <: EraTag]:
  case DelplEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.DelplEvent[E])

/** `EraRuleFailure "DELPL" era`: Babbage's is Shelley's (`Babbage/Rules/Delpl.hs:14`,
  * cardano-ledger f649f975). DELPL is a rule of the eras up to Babbage only.
  */
type DelplPredFailure[E <: EraTag] = E match
  case Babbage => ShelleyDelplPredFailure[Babbage]

/** `Event (EraRule "DELPL" era)`: Babbage's DELPL is Shelley's (`type instance EraRule "DELPL"
  * BabbageEra = Shelley.DELPL BabbageEra`, `Babbage/Era.hs:92`, cardano-ledger f649f975), whose
  * event is `ShelleyDelplEvent` (`Shelley/Rules/Delpl.hs:132`).
  */
type DelplEvent[E <: EraTag] = E match
  case Babbage => ShelleyDelplEvent[Babbage]

/** `Event (EraRule "DELEGS" era)`: Babbage's DELEGS is Shelley's (`Babbage/Era.hs:90`,
  * cardano-ledger f649f975), whose event is `ShelleyDelegsEvent` (`Shelley/Rules/Delegs.hs:138`).
  */
type DelegsEvent[E <: EraTag] = E match
  case Babbage => ShelleyDelegsEvent[Babbage]

/** `InjectRuleFailure "DELPL" f era` (`Babbage/Rules/Delpl.hs:16-22`, cardano-ledger f649f975): how
  * era `E` 's DELPL reports a failure `F` of its own or of a rule it runs. One instance per era and
  * failure type, in the companion.
  */
trait InjectDelplFailure[F, E <: EraTag]:
  def injectFailure(failure: F): DelplPredFailure[E]

object InjectDelplFailure:
  /** `Babbage/Rules/Delpl.hs:16` (cardano-ledger f649f975): the identity. */
  given babbage: InjectDelplFailure[ShelleyDelplPredFailure[Babbage], Babbage] with
    def injectFailure(failure: ShelleyDelplPredFailure[Babbage]): DelplPredFailure[Babbage] =
      failure

  /** `Babbage/Rules/Delpl.hs:18-19` (cardano-ledger f649f975): `PoolFailure`. */
  given babbagePool: InjectDelplFailure[ShelleyPoolPredFailure[Babbage], Babbage] with
    def injectFailure(failure: ShelleyPoolPredFailure[Babbage]): DelplPredFailure[Babbage] =
      ShelleyDelplPredFailure.PoolFailure(failure)

  /** `Babbage/Rules/Delpl.hs:21-22` (cardano-ledger f649f975): `DelegFailure`. */
  given babbageDeleg: InjectDelplFailure[ShelleyDelegPredFailure[Babbage], Babbage] with
    def injectFailure(failure: ShelleyDelegPredFailure[Babbage]): DelplPredFailure[Babbage] =
      ShelleyDelplPredFailure.DelegFailure(failure)

/** `InjectRuleFailure "DELEGS" f era` (`Babbage/Rules/Delegs.hs:15-24`, cardano-ledger f649f975):
  * how era `E` 's DELEGS reports a failure `F` of its own or of a rule below it, DELPL's
  * `DelplFailure . injectFailure`.
  */
trait InjectDelegsFailure[F, E <: EraTag]:
  def injectFailure(failure: F): DelegsPredFailure[E]

object InjectDelegsFailure:
  /** `Babbage/Rules/Delegs.hs:15` (cardano-ledger f649f975): the identity. */
  given babbage: InjectDelegsFailure[ShelleyDelegsPredFailure[Babbage], Babbage] with
    def injectFailure(failure: ShelleyDelegsPredFailure[Babbage]): DelegsPredFailure[Babbage] =
      failure

  /** `Babbage/Rules/Delegs.hs:17-24` (cardano-ledger f649f975): DELPL's failure, and each of the
    * rules below it through DELPL's injection, wrapped in `DelplFailure`.
    */
  given babbageDelpl[F](using
      inject: InjectDelplFailure[F, Babbage]
  ): InjectDelegsFailure[F, Babbage] with
    def injectFailure(failure: F): DelegsPredFailure[Babbage] =
      ShelleyDelegsPredFailure.DelplFailure(inject.injectFailure(failure))

/** `Embed (Shelley.POOL era) (DELPL era)` and `Embed (DELEG era) (DELPL era)`
  * (`Shelley/Rules/Delpl.hs:205-228`, cardano-ledger f649f975): how the failures and events of the
  * rules DELPL runs sit in DELPL's, `PoolFailure` with `PoolEvent` and `DelegFailure` with
  * `DelegEvent`.
  */
trait EmbedInDelpl[E <: EraTag]:
  def wrapPoolFailed(failure: PoolPredFailure[E]): ShelleyDelplPredFailure[E]
  def wrapPoolEvent(event: PoolRuleEvent[E]): ShelleyDelplEvent[E]
  def wrapDelegFailed(failure: ShelleyDelegPredFailure[E]): ShelleyDelplPredFailure[E]
  def wrapDelegEvent(event: ShelleyDelegEvent[E]): ShelleyDelplEvent[E]

object EmbedInDelpl:
  given babbage: EmbedInDelpl[Babbage] with
    def wrapPoolFailed(failure: PoolPredFailure[Babbage]): ShelleyDelplPredFailure[Babbage] =
      ShelleyDelplPredFailure.PoolFailure(failure)
    def wrapPoolEvent(event: PoolRuleEvent[Babbage]): ShelleyDelplEvent[Babbage] =
      ShelleyDelplEvent.PoolEvent(event)
    def wrapDelegFailed(
        failure: ShelleyDelegPredFailure[Babbage]
    ): ShelleyDelplPredFailure[Babbage] = ShelleyDelplPredFailure.DelegFailure(failure)
    def wrapDelegEvent(event: ShelleyDelegEvent[Babbage]): ShelleyDelplEvent[Babbage] =
      ShelleyDelplEvent.DelegEvent(event)

/** `Embed (DELPL era) (DELEGS era)` (`Shelley/Rules/Delegs.hs:199-208`, cardano-ledger f649f975):
  * `DelplFailure` and `DelplEvent`.
  */
trait EmbedDelplInDelegs[E <: EraTag]:
  def wrapFailed(failure: ShelleyDelplPredFailure[E]): ShelleyDelegsPredFailure[E]
  def wrapEvent(event: ShelleyDelplEvent[E]): ShelleyDelegsEvent[E]

object EmbedDelplInDelegs:
  given babbage: EmbedDelplInDelegs[Babbage] with
    def wrapFailed(failure: ShelleyDelplPredFailure[Babbage]): ShelleyDelegsPredFailure[Babbage] =
      ShelleyDelegsPredFailure.DelplFailure(failure)
    def wrapEvent(event: ShelleyDelplEvent[Babbage]): ShelleyDelegsEvent[Babbage] =
      ShelleyDelegsEvent.DelplEvent(event)

object ShelleyDelegPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyDelegPredFailure[E], ShelleyDelegPredFailure[E]] =
    CanEqual.derived

object ShelleyDelegEvent:
  given [E <: EraTag]: CanEqual[ShelleyDelegEvent[E], ShelleyDelegEvent[E]] = CanEqual.derived

object ShelleyDelplPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyDelplPredFailure[E], ShelleyDelplPredFailure[E]] =
    CanEqual.derived

object ShelleyDelplEvent:
  given [E <: EraTag]: CanEqual[ShelleyDelplEvent[E], ShelleyDelplEvent[E]] = CanEqual.derived

object ShelleyDelegsPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyDelegsPredFailure[E], ShelleyDelegsPredFailure[E]] =
    CanEqual.derived

object ShelleyDelegsEvent:
  given [E <: EraTag]: CanEqual[ShelleyDelegsEvent[E], ShelleyDelegsEvent[E]] = CanEqual.derived
