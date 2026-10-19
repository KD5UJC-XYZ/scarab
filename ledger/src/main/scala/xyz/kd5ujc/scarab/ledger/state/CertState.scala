// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Order, Show}
import cats.derived.*
import monocle.{Focus, Lens}
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.reflect.ClassTag
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Anchor, GenDelegPair, PoolParams}

// The certificate state: delegation (`DState`), pools (`PState`), voting (`VState`, Conway). The
// Haskell types are indexed by the era; only `DState`'s accounts depend on it, and that dependency
// is the type parameter of `DState` below, so the records here need no witness and derive their
// instances.

/** `FutureGenDeleg` (`State/CertState.hs:100-104`, `deriving (Eq, Ord)`): a genesis delegation that
  * takes effect at a later slot. The fields are declared slot first, so that is the order.
  */
final case class FutureGenDeleg(slot: SlotNo, genKeyHash: KeyHash[Genesis]) derives Eq, Show, Diff

object FutureGenDeleg:
  given Order[FutureGenDeleg] = Order.by(d => (d.slot, d.genKeyHash))

  val slot: Lens[FutureGenDeleg, SlotNo] = Focus[FutureGenDeleg](_.slot)
  val genKeyHash: Lens[FutureGenDeleg, KeyHash[Genesis]] = Focus[FutureGenDeleg](_.genKeyHash)

/** `InstantaneousRewards` (`State/CertState.hs:134-140`): the MIR certificates of the epoch so far,
  * paid at the next boundary.
  */
final case class InstantaneousRewards(
    reserves: SortedMap[Credential[Staking], Coin],
    treasury: SortedMap[Credential[Staking], Coin],
    deltaReserves: DeltaCoin,
    deltaTreasury: DeltaCoin
) derives Eq,
      Show,
      Diff

object InstantaneousRewards:
  val reserves: Lens[InstantaneousRewards, SortedMap[Credential[Staking], Coin]] =
    Focus[InstantaneousRewards](_.reserves)
  val treasury: Lens[InstantaneousRewards, SortedMap[Credential[Staking], Coin]] =
    Focus[InstantaneousRewards](_.treasury)
  val deltaReserves: Lens[InstantaneousRewards, DeltaCoin] =
    Focus[InstantaneousRewards](_.deltaReserves)
  val deltaTreasury: Lens[InstantaneousRewards, DeltaCoin] =
    Focus[InstantaneousRewards](_.deltaTreasury)

/** The accounts a [[DState]] can hold: the Shelley record before Conway, the Conway one after. */
type AccountsRecord = ShelleyAccounts | ConwayAccounts

/** `DState era` (`State/CertState.hs:157-166`): delegation state. `A` is `Accounts[E]` of the era
  * (`DState[ShelleyAccounts]` before Conway, `DState[ConwayAccounts]` in it): parameterised by the
  * record rather than the era, `DState` is a plain record whose `Eq` and `Show` are derived, and
  * the era-generic composites above it need no witness for it.
  */
final case class DState[A <: AccountsRecord](
    accounts: A,
    futureGenDelegs: SortedMap[FutureGenDeleg, GenDelegPair],
    genDelegs: SortedMap[KeyHash[Genesis], GenDelegPair],
    iRewards: InstantaneousRewards
) derives Eq,
      Show

object DState:
  given [A <: AccountsRecord](using Diff[A], ClassTag[A]): Diff[DState[A]] = Diff.derived
  def accounts[A <: AccountsRecord]: Lens[DState[A], A] = Focus[DState[A]](_.accounts)
  def futureGenDelegs[A <: AccountsRecord]
      : Lens[DState[A], SortedMap[FutureGenDeleg, GenDelegPair]] =
    Focus[DState[A]](_.futureGenDelegs)
  def genDelegs[A <: AccountsRecord]: Lens[DState[A], SortedMap[KeyHash[Genesis], GenDelegPair]] =
    Focus[DState[A]](_.genDelegs)
  def iRewards[A <: AccountsRecord]: Lens[DState[A], InstantaneousRewards] =
    Focus[DState[A]](_.iRewards)

/** `StakePoolState` (`State/StakePool.hs:138-159`): a registered pool; `PoolParams` without the
  * pool id (the map key) and with the deposit and the delegators.
  */
final case class StakePoolState(
    vrf: VRFVerKeyHash,
    pledge: Coin,
    cost: Coin,
    margin: Rational,
    accountId: Credential[Staking],
    owners: SortedSet[KeyHash[Staking]],
    relays: Bytes,
    metadata: Option[Bytes],
    deposit: CompactCoin,
    delegators: SortedSet[Credential[Staking]]
) derives Eq,
      Show,
      Diff

object StakePoolState:
  val vrf: Lens[StakePoolState, VRFVerKeyHash] = Focus[StakePoolState](_.vrf)
  val pledge: Lens[StakePoolState, Coin] = Focus[StakePoolState](_.pledge)
  val cost: Lens[StakePoolState, Coin] = Focus[StakePoolState](_.cost)
  val margin: Lens[StakePoolState, Rational] = Focus[StakePoolState](_.margin)
  val accountId: Lens[StakePoolState, Credential[Staking]] = Focus[StakePoolState](_.accountId)
  val owners: Lens[StakePoolState, SortedSet[KeyHash[Staking]]] = Focus[StakePoolState](_.owners)
  val relays: Lens[StakePoolState, Bytes] = Focus[StakePoolState](_.relays)
  val metadata: Lens[StakePoolState, Option[Bytes]] = Focus[StakePoolState](_.metadata)
  val deposit: Lens[StakePoolState, CompactCoin] = Focus[StakePoolState](_.deposit)
  val delegators: Lens[StakePoolState, SortedSet[Credential[Staking]]] =
    Focus[StakePoolState](_.delegators)

/** `PState era` (`State/CertState.hs:232-246`): the pools. `vrfKeyHashes` counts the registrations
  * of each VRF key hash (a derived field, checked on decode by the mirror).
  */
final case class PState(
    vrfKeyHashes: SortedMap[VRFVerKeyHash, NonZero[Word64]],
    stakePools: SortedMap[KeyHash[StakePool], StakePoolState],
    future: SortedMap[KeyHash[StakePool], PoolParams],
    retiring: SortedMap[KeyHash[StakePool], EpochNo]
) derives Eq,
      Show,
      Diff

object PState:
  val vrfKeyHashes: Lens[PState, SortedMap[VRFVerKeyHash, NonZero[Word64]]] =
    Focus[PState](_.vrfKeyHashes)
  val stakePools: Lens[PState, SortedMap[KeyHash[StakePool], StakePoolState]] =
    Focus[PState](_.stakePools)
  val future: Lens[PState, SortedMap[KeyHash[StakePool], PoolParams]] = Focus[PState](_.future)
  val retiring: Lens[PState, SortedMap[KeyHash[StakePool], EpochNo]] = Focus[PState](_.retiring)

/** `DRepState` (`DRep.hs:166-172`): a registered DRep. Not the node's `DRepState` (another order,
  * `BigInt` s, a `Set`).
  */
final case class DRepState(
    expiry: EpochNo,
    anchor: Option[Anchor],
    deposit: CompactCoin,
    delegators: SortedSet[Credential[Staking]]
) derives Eq,
      Show,
      Diff

object DRepState:
  val expiry: Lens[DRepState, EpochNo] = Focus[DRepState](_.expiry)
  val anchor: Lens[DRepState, Option[Anchor]] = Focus[DRepState](_.anchor)
  val deposit: Lens[DRepState, CompactCoin] = Focus[DRepState](_.deposit)
  val delegators: Lens[DRepState, SortedSet[Credential[Staking]]] =
    Focus[DRepState](_.delegators)

/** `CommitteeAuthorization` (`State/CertState.hs:302-307`): what the committee state records about
  * a cold credential.
  */
enum CommitteeAuthorization derives Eq, Show, Diff:
  case CommitteeHotCredential(credential: Credential[HotCommittee])
  case CommitteeMemberResigned(anchor: Option[Anchor])

/** `CommitteeState` (`State/CertState.hs:328-331`, a newtype over the map). */
final case class CommitteeState(creds: SortedMap[Credential[ColdCommittee], CommitteeAuthorization])
    derives Eq,
      Show,
      Diff

object CommitteeState:
  val creds: Lens[CommitteeState, SortedMap[Credential[ColdCommittee], CommitteeAuthorization]] =
    Focus[CommitteeState](_.creds)

/** `VState era` (`Conway/State/VState.hs:56-68`): the voting state (Conway). */
final case class VState(
    dreps: SortedMap[Credential[DRepRole], DRepState],
    committeeState: CommitteeState,
    numDormantEpochs: EpochNo
) derives Eq,
      Show,
      Diff

object VState:
  val dreps: Lens[VState, SortedMap[Credential[DRepRole], DRepState]] = Focus[VState](_.dreps)
  val committeeState: Lens[VState, CommitteeState] = Focus[VState](_.committeeState)
  val numDormantEpochs: Lens[VState, EpochNo] = Focus[VState](_.numDormantEpochs)

/** `ShelleyCertState` (`Shelley/State/CertState.hs:48-52`): pools and delegation, Shelley to
  * Babbage.
  */
final case class ShelleyCertState(pState: PState, dState: DState[ShelleyAccounts])
    derives Eq,
      Show,
      Diff

object ShelleyCertState:
  val pState: Lens[ShelleyCertState, PState] = Focus[ShelleyCertState](_.pState)
  val dState: Lens[ShelleyCertState, DState[ShelleyAccounts]] = Focus[ShelleyCertState](_.dState)

/** `ConwayCertState` (`Conway/State/CertState.hs:56-61`): voting, pools and delegation. */
final case class ConwayCertState(vState: VState, pState: PState, dState: DState[ConwayAccounts])
    derives Eq,
      Show,
      Diff

object ConwayCertState:
  val vState: Lens[ConwayCertState, VState] = Focus[ConwayCertState](_.vState)
  val pState: Lens[ConwayCertState, PState] = Focus[ConwayCertState](_.pState)
  val dState: Lens[ConwayCertState, DState[ConwayAccounts]] = Focus[ConwayCertState](_.dState)
