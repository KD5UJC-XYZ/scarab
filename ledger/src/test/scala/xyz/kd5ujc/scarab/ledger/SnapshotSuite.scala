// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, StakeDistribution}
import xyz.kd5ujc.scarab.ledger.rules.NativeRewardFixture.{compact, cred, ok, pool, stakePool}
import xyz.kd5ujc.scarab.ledger.state.{PoolDistr, Sorted}
import xyz.kd5ujc.scarab.ledger.state.given

/** The stake snapshot and the pool distribution it makes (`snapShotFromInstantStake`,
  * `calculatePoolDistr`): a delegator without stake is not in the active stake, but its pool still
  * counts it, so the pool is in the distribution with no stake.
  */
class SnapshotSuite extends munit.FunSuite:
  test(
    "a pool whose delegators have no stake stays in the pool stake with none; others keep theirs"
  ) {
    val (staked, idle, idleOnly, elsewhere) = (cred(1), cred(2), cred(3), cred(4))
    val accounts = List(
      staked -> pool(10),
      idle -> pool(10),
      idleOnly -> pool(11),
      elsewhere -> pool(99)
    ).map((c, p) => c -> EraLedger.AccountView(compact(0), compact(0), Some(p)))
    val active = ok(
      StakeDistribution.resolve(
        Sorted.map(List(staked -> compact(500))),
        accounts.iterator,
        Sorted.empty,
        Sorted.empty
      )
    )
    // pool 10 has a staked and an idle delegator: its stake is not reset; pool 11 has only an idle
    // one; pool 12 has none; 99 is not a registered pool
    val pools = Sorted.map(
      List(
        pool(10) -> stakePool(cred(10)).copy(delegators = Sorted.set(List(staked, idle))),
        pool(11) -> stakePool(cred(11)).copy(delegators = Sorted.set(List(idleOnly))),
        pool(12) -> stakePool(cred(12))
      )
    )
    val distribution = PoolDistr.of(ok(StakeDistribution.snapshot(active, pools)))
    assertEquals(
      distribution.distribution.view.mapValues(_.totalPoolStake.toBigInt).toMap,
      Map(pool(10) -> BigInt(500), pool(11) -> BigInt(0))
    )
    assertEquals(distribution.totalActiveStake.value.value, BigInt(500))
  }
