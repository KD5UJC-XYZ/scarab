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

  /** `resolveActiveInstantStakeCredentials` (`State/Stake.hs:220–245`) followed by
    * `resolveShelleyInstantStake` (`Shelley/State/Stake.hs:148–172`). An account-only zero is
    * absent. The reference adds the instant stake, the balance and the pointer stake in `Word64`,
    * which wraps (even to zero, which `unsafeNonZero` keeps); here a sum that would leave it is a
    * [[VocabError]], no verdict (overflow-improvements.md#compact-coin-arithmetic), so every entry
    * is the true sum of amounts of which at least one is not zero.
    */
  def resolve(
      instant: SortedMap[Credential[Staking], CompactCoin],
      accounts: Iterator[(Credential[Staking], EraLedger.AccountView)],
      ptrs: SortedMap[Ptr, Credential[Staking]],
      ptrStake: SortedMap[Ptr, CompactCoin]
  ): Either[VocabError, ActiveStake] =
    val stakes = Sorted.Cursor(instant)
    for
      byPointer <- CompactCoin.sumByKey(
        ptrStake.iterator.flatMap((p, stake) => ptrs.get(p).map(_ -> stake))
      )
      entries <- accounts
        .foldLeft[Either[VocabError, Vector[(Credential[Staking], StakeWithDelegation)]]](
          Right(Vector.empty)
        ) { case (acc, (credential, account)) =>
          acc.flatMap(out =>
            account.pool.fold(Right(out)) { pool =>
              val base: Either[VocabError, Option[CompactCoin]] = stakes.at(credential) match
                case Some(stake) => stake.add(account.balance).map(Some(_))
                case None => Right(Option.unless(account.balance.word.isZero)(account.balance))
              base
                .flatMap(b =>
                  byPointer.get(credential) match
                    case Some(pointer) => b.fold(Right(pointer))(_.add(pointer)).map(Some(_))
                    case None          => Right(b)
                )
                .map(
                  _.fold(out)(total =>
                    out :+ (credential -> StakeWithDelegation(NonZero.unsafe(total), pool))
                  )
                )
            }
          )
        }
    yield ActiveStake(Sorted.fromAscending(entries))

  /** `mkStakePoolSnapShot` (`State/SnapShots.hs:187`): what the reward calculation needs of one
    * pool, resolved when the snapshot is taken from the delegators the pool state holds. The
    * reference's sums of the pool's and its owners' stake wrap; here they are checked
    * (overflow-improvements.md#compact-coin-arithmetic).
    */
  def poolSnapshot(
      active: ActiveStake,
      total: NonZero[Coin],
      pool: StakePoolState
  ): Either[VocabError, StakePoolSnapShot] =
    def stakeOf(c: Credential[Staking]): CompactCoin =
      active.entries.get(c).fold(CompactCoin.zero)(_.stake.value)
    val owners =
      pool.owners.filter(o => pool.delegators.contains(Credential.KeyHashObj[Staking](o)))
    for
      stake <- CompactCoin.sum(pool.delegators.iterator.map(stakeOf))
      ownersStake <- CompactCoin.sum(
        owners.iterator.map(o => stakeOf(Credential.KeyHashObj[Staking](o)))
      )
    yield StakePoolSnapShot(
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
  ): Either[VocabError, SnapShot] =
    val total = active.total
    pools.toVector
      .traverse((id, pool) => poolSnapshot(active, total, pool).map(id -> _))
      .map(snapshots => SnapShot(active, Sorted.fromAscending(snapshots)))
