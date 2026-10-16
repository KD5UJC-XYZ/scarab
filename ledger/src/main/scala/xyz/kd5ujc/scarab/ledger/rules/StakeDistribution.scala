// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger.state.*

/** The stake distribution of the SNAP rule: `snapShotFromInstantStake` and what it is made of
  * (`State/SnapShots.hs:418`, `mkStakePoolSnapShot` `187`, `calculatePoolDistr` `446`).
  */
object StakeDistribution:

  /** `resolveActiveInstantStakeCredentials` and, before Conway, the pointer stake of
    * `resolveShelleyInstantStake` (`State/Stake.hs:220`, `Shelley/State/Stake.hs:148`): the
    * accounts that delegate to a pool, each with the instant stake of its credential and its reward
    * balance, and the stake of the pointers that name it. An account with nothing is left out (the
    * active stake holds amounts that are not zero). `accounts` is the whole map in the order of the
    * credential, so the result is built in one pass.
    */
  def resolve(
      instant: SortedMap[Credential[Staking], CompactCoin],
      accounts: Iterator[(Credential[Staking], EraLedger.AccountView)],
      ptrs: SortedMap[Ptr, Credential[Staking]],
      ptrStake: SortedMap[Ptr, CompactCoin]
  ): Either[VocabError, ActiveStake] =
    // the pointer stake by the credential the pointer names (a pointer to no account adds nothing)
    val byPointer: Map[Credential[Staking], BigInt] =
      ptrStake.iterator
        .flatMap((p, stake) => ptrs.get(p).map(_ -> stake.toBigInt))
        .toVector
        .groupMapReduce(_._1)(_._2)(_ + _)
    val entries = accounts.flatMap { (credential, account) =>
      account.pool.flatMap { pool =>
        val total = account.balance.toBigInt +
          instant.get(credential).fold(BigInt(0))(_.toBigInt) +
          byPointer.getOrElse(credential, BigInt(0))
        Option.when(total.signum > 0)((credential, total, pool))
      }
    }
    entries.toList
      .traverse { (credential, total, pool) =>
        for
          stake <- CompactCoin.fromBigInt(total)
          nonZero <- NonZero.from(stake)
        yield credential -> StakeWithDelegation(nonZero, pool)
      }
      .map(es => ActiveStake(Sorted.map(es)))

  /** `mkStakePoolSnapShot` (`State/SnapShots.hs:187`): what the reward calculation needs of one
    * pool, resolved when the snapshot is taken from the delegators the pool state holds.
    */
  def poolSnapshot(
      active: ActiveStake,
      total: NonZero[Coin],
      pool: StakePoolState
  ): Either[VocabError, StakePoolSnapShot] =
    def stakeOf(c: Credential[Staking]): BigInt =
      active.entries.get(c).fold(BigInt(0))(_.stake.value.toBigInt)
    val owners =
      pool.owners.filter(o => pool.delegators.contains(Credential.KeyHashObj[Staking](o)))
    val stake = pool.delegators.iterator.foldLeft(BigInt(0))((sum, d) => sum + stakeOf(d))
    val ownersStake = owners.iterator.foldLeft(BigInt(0))((sum, o) =>
      sum + stakeOf(Credential.KeyHashObj[Staking](o))
    )
    for
      poolStake <- CompactCoin.fromBigInt(stake)
      selfStake <- Coin.fromBigInt(ownersStake)
      count <- Word64.fromBigInt(VocabError.Kind.BlockCount, BigInt(pool.delegators.size))
    yield StakePoolSnapShot(
      poolStake,
      Rational(stake, total.value.value),
      owners,
      selfStake,
      pool.vrf,
      pool.pledge,
      pool.cost,
      pool.margin,
      count,
      pool.accountId
    )

  /** `snapShotFromInstantStake` (`State/SnapShots.hs:418`): the active stake, and a snapshot of
    * every registered pool over it.
    */
  def snapshot(
      active: ActiveStake,
      pools: SortedMap[KeyHash[StakePool], StakePoolState]
  ): Either[VocabError, SnapShot] =
    val total = active.total
    pools.toList
      .traverse((id, p) => poolSnapshot(active, total, p).map(id -> _))
      .map(ps => SnapShot(active, Sorted.map(ps)))
