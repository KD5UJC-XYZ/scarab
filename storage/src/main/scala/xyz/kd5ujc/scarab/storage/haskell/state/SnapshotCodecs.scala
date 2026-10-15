// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec

import Wire.*

/** The stake snapshots and the pool distribution (`State/SnapShots.hs`, `State/Stake.hs` and
  * `State/PoolDistr.hs` of `cardano-ledger-core`, the pinned shape of 11.1.3).
  */
trait SnapshotCodecs extends GovStateCodecs:
  /** `StakeWithDelegation` (`Stake.hs:116-117`): `[stake, pool]`, the stake not zero. */
  given stakeWithDelegation: Codec[StakeWithDelegation] = Record.auto[StakeWithDelegation]

  /** `ActiveStake` (`Stake.hs:146-148`, a newtype over the map). */
  given activeStake(using ProtVer): Codec[ActiveStake] =
    mapOf(credential[Staking], stakeWithDelegation).imap(ActiveStake(_))(_.entries)

  /** The `Int` count of delegators (`SnapShots.hs:175`): a non-negative number below 2^63, as the
    * mirror keeps it in a word.
    */
  private val delegatorCount: Codec[Word64] =
    Codec.uintBig(BigInt(Long.MaxValue)).imap(n => Word64.fromUnsigned(n.toLong))(w => w.toBigInt)

  /** `StakePoolSnapShot` (`SnapShots.hs:239-252`): ten fields, the ratio a plain `Rational`. */
  given stakePoolSnapShot(using ProtVer): Codec[StakePoolSnapShot] =
    Record.of[StakePoolSnapShot](
      (
        compactCoin,
        ratio,
        setOf(keyHash[Staking]),
        coin,
        vrfVerKeyHash,
        coin,
        coin,
        unitInterval,
        delegatorCount,
        credential[Staking]
      )
    )

  /** `SnapShot` (`SnapShots.hs:290-296`): `[active stake, pools]`. The older three-field shape
    * (`SnapShots.hs:310-325`) is not read.
    */
  given snapShot(using ProtVer): Codec[SnapShot] =
    Record.of[SnapShot]((activeStake, mapOf(keyHash[StakePool], stakePoolSnapShot)))

  /** `SnapShots` (`SnapShots.hs:355-362`): `[mark, set, go, fee]`; the pool distribution of the
    * mark snapshot is not written, the mirror derives it.
    */
  given snapShots(using ProtVer): Codec[SnapShots] = Record.auto[SnapShots]

  /** `IndividualPoolStake` (`PoolDistr.hs:70-77`): `[ratio of the total, stake, VRF key hash]`. */
  given individualPoolStake(using ProtVer): Codec[IndividualPoolStake] =
    Record.of[IndividualPoolStake]((ratio, compactCoin, vrfVerKeyHash))

  /** `PoolDistr` (`PoolDistr.hs:117-122`): `[distribution, total active stake]`. */
  given poolDistr(using ProtVer): Codec[PoolDistr] =
    Record.of[PoolDistr](
      (mapOf(keyHash[StakePool], individualPoolStake), nonZero[Coin])
    )
