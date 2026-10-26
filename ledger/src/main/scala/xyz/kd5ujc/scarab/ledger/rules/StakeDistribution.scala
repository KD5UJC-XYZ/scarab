// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger.state.*

/** The stake distribution of the SNAP rule: `snapShotFromInstantStake` and what it is made of
  * (`State/SnapShots.hs:418`, `mkStakePoolSnapShot` `187`, `calculatePoolDistr` `446`).
  */
object StakeDistribution:

  /** `resolveActiveInstantStakeCredentials` (`State/Stake.hs:220–245`) followed by
    * `resolveShelleyInstantStake` (`Shelley/State/Stake.hs:148–172`). An account-only zero is
    * absent; an instant/pointer entry is retained even when native compact addition wraps to zero.
    */
  def resolve(
      instant: SortedMap[Credential[Staking], CompactCoin],
      accounts: Iterator[(Credential[Staking], EraLedger.AccountView)],
      ptrs: SortedMap[Ptr, Credential[Staking]],
      ptrStake: SortedMap[Ptr, CompactCoin]
  ): ActiveStake =
    val byPointer = ptrStake.iterator
      .flatMap((p, stake) => ptrs.get(p).map(_ -> stake))
      .toVector
      .groupMapReduce(_._1)(_._2)((a, b) => a.add(b))
    val stakes = Sorted.Cursor(instant)
    val entries = accounts.flatMap { (credential, account) =>
      account.pool.flatMap { pool =>
        val base = stakes.at(credential) match
          case Some(stake) => Some(stake.add(account.balance))
          case None        => Option.unless(account.balance.word.isZero)(account.balance)
        val combined = byPointer.get(credential) match
          case Some(pointer) => Some(base.fold(pointer)(_.add(pointer)))
          case None          => base
        combined.map(total => credential -> StakeWithDelegation(NonZero.unsafe(total), pool))
      }
    }
    ActiveStake(Sorted.fromAscending(entries.toVector))

  /** `mkStakePoolSnapShot` (`State/SnapShots.hs:187`): what the reward calculation needs of one
    * pool, resolved when the snapshot is taken from the delegators the pool state holds.
    */
  def poolSnapshot(
      active: ActiveStake,
      total: NonZero[Coin],
      pool: StakePoolState
  ): StakePoolSnapShot =
    def stakeOf(c: Credential[Staking]): CompactCoin =
      active.entries.get(c).fold(CompactCoin.zero)(_.stake.value)
    val owners =
      pool.owners.filter(o => pool.delegators.contains(Credential.KeyHashObj[Staking](o)))
    val stake = CompactCoin.sum(pool.delegators.iterator.map(stakeOf))
    val ownersStake =
      CompactCoin.sum(owners.iterator.map(o => stakeOf(Credential.KeyHashObj[Staking](o))))
    StakePoolSnapShot(
      stake,
      Rational(stake.toBigInt, total.value.value),
      owners,
      ownersStake.toCoin,
      pool.vrf,
      pool.pledge,
      pool.cost,
      pool.margin,
      Word64.fromUnsigned(pool.delegators.size.toLong),
      pool.accountId
    )

  /** `snapShotFromInstantStake` (`State/SnapShots.hs:418`): the active stake, and a snapshot of
    * every registered pool over it.
    */
  def snapshot(
      active: ActiveStake,
      pools: SortedMap[KeyHash[StakePool], StakePoolState]
  ): SnapShot =
    val total = active.total
    SnapShot(active, pools.transform((_, pool) => poolSnapshot(active, total, pool)))
