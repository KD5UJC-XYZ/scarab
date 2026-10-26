// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import xyz.kd5ujc.scarab.codec.DomainError
import cats.{Eq, Functor, Show}
import cats.derived.*
import scala.reflect.ClassTag
import xyz.kd5ujc.scarab.ledger.EpochGeometry

// Vocabulary for completeness metadata, which states converted from the flat state of the retired
// snapshot formats still carry. Native states carry their source gaps through transitions and snapshots;
// freshly computed reward attribution and resumable updates do not reconstruct missing history.

/** What a mirror built from the flat state of the retired snapshot formats does not carry, or
  * carries only in part (the conversion went with the `legacy` module, #488). A mirror is always
  * accompanied by the set of gaps it has; the set was a function of the source state alone.
  */
enum Gap derives Eq, Show, Diff:
  /** `esNonMyopic` and the non-myopic part of the reward update (`PoolRank.hs:231-236`): the pool
    * ranking history is absent from the historical flat source, so its conversion holds
    * `NonMyopic.empty`. Current rules generate new likelihoods, but cannot reconstruct that lost
    * history. The live state retains this gap even after new likelihoods are generated.
    */
  case NonMyopic

  /** The reward update names, per credential, the pools it came from (`Reward.rewardPool`,
    * `RewardUpdate.hs:111-117`); the node keeps one amount per credential. The mirror's `rs` is
    * empty and the amounts are in the extras. Present when a reward update is.
    */
  case RewardAttribution

  /** `numDelegators` of a pool in a snapshot is a count of its delegators, including those with no
    * stake; the node keeps the stake-bearing ones and a set of pools that have another
    * (`Snapshot.idlePools`), so the count is exact up to one. Present when a snapshot has such a
    * pool.
    */
  case NumDelegatorsLowerBound

  /** The reward update is being computed (the node's `rewardDraft`: computed at the boundary, made
    * visible by the first block that follows): Haskell's `ru` is then `Pulsing`, which the mirror
    * of the node's state never is. Present when there is a draft and no update.
    */
  case PendingPulsingUpdate

  /** `shelleyLedgerTransition` of the consensus state (the count of blocks since the era's
    * transition was known): not kept by the node. Added by the consensus side of the mirror.
    */
  case ShelleyTransition

/** A mirror with the gaps it has. A functor, so a conversion of the value keeps them. */
final case class Mirrored[A](value: A, gaps: Set[Gap]) derives Eq, Show

object Mirrored:
  given [A](using Diff[A], ClassTag[A]): Diff[Mirrored[A]] = Diff.derived
  given Functor[Mirrored] with
    def map[A, B](m: Mirrored[A])(f: A => B): Mirrored[B] = Mirrored(f(m.value), m.gaps)

/** The pieces of a mirror that are derived from the others (the retired flat conversion recomputed
  * them and refused a mirror that disagreed).
  */
enum Derived derives Show:
  /** `psVRFKeyHashes`. */
  case VrfKeyHashes

  /** `spsDelegators` of every registered pool. */
  case PoolDelegators

  /** The pointer of every Shelley account (`casPtr`). */
  case AccountPointer

  /** `utxosInstantStake`. */
  case InstantStake

  /** `FuturePParams` of the governance state. */
  case FuturePParams

  /** The DRep pulser the node does not have yet (a placeholder of the mirror, before the first
    * boundary).
    */
  case PulserPlaceholder

  /** `nesPd`. */
  case PoolDistr

  /** A `StakePoolSnapShot`, `ssTotalActiveStake` and the rest of one snapshot. */
  case Snapshot(which: Derived.Which)

  /** What the extras hold about the mirror (pools of a snapshot that is not in it, ...). */
  case Extras

object Derived:
  /** The four stake distributions the mirror holds: the three snapshots and the one `nesPd` is
    * calculated from.
    */
  enum Which derives Show:
    case Mark, Set, Go, Distribution

/** How a source state is not one a mirror can hold. */
enum Shape derives Show:
  /** A credential with stake in a snapshot but no delegation. */
  case StakeWithoutDelegation

  /** A credential delegated in a snapshot but with no stake (the ledger keeps non-zero stake only).
    */
  case DelegationWithoutStake

  /** A snapshot holds a stake of zero. */
  case ZeroStake

  /** A snapshot delegates to a pool that it does not hold. */
  case DelegationToUnknownPool

  /** A snapshot has an idle pool that it does not hold. */
  case IdleUnknownPool

  /** The reward update is still being computed (`Pulsing`): the node's state holds a finished one
    * only, and the pulser is not something a state of the node can be made from.
    */
  case PulsingRewardUpdate

/** Why a conversion refused. Closed: nothing here is a message to match on. */
enum MirrorError extends DomainError derives Show:
  /** A value outside a vocabulary type (a hash of the wrong length, a negative coin, ...). */
  case Vocab(error: VocabError)

  /** The state is not one a mirror can hold. */
  case IllFormed(shape: Shape)

  /** A field the mirror derives is not what the mirror holds (a tampered or stale mirror). */
  case DerivedMismatch(field: Derived)

/** What the ledger side of the conversion reads from the node's configuration.
  *
  * @param network
  *   the node's network id, the one the reward account of a registered pool is read back on
  *   (Haskell keeps the account's credential only, `StakePoolState`)
  * @param geometry
  *   the era history, for the point of no return of the parameter update
  * @param quorum
  *   the number of genesis delegates that must propose the same update (`quorum`,
  *   `Rules/Ppup.hs:190`), before Conway
  * @param snapshots
  *   where the delegators of the pools of the `mark` snapshot come from ([[SnapshotBasis]])
  */
final case class MirrorEnv(
    network: Int,
    geometry: EpochGeometry,
    quorum: Int,
    snapshots: SnapshotBasis = SnapshotBasis.Accounts
)

/** Where the delegator-derived fields of the pools of the `mark` snapshot (`numDelegators`,
  * `selfDelegatedOwners` and their stake) come from. Haskell fixes them when the snapshot is taken
  * (`mkStakePoolSnapShot`, `State/SnapShots.hs:187-217`, over the pool's `spsDelegators` of that
  * moment); the node keeps the stake-bearing delegations of the snapshot only.
  */
enum SnapshotBasis:
  /** The accounts as they are in the state converted, which agree with the snapshot at the boundary
    * that takes it and count a delegator with no stake: what the comparison with the new side's
    * tick needs.
    */
  case Accounts

  /** The delegations of the snapshot itself, as `set` and `go` always are: what a state is seeded
    * with (the new side carries it on, and what the accounts did since the snapshot was taken is
    * not part of it). A delegator with no stake is not known, so the counts are a lower bound.
    */
  case AsTaken
