// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import ConwayEpochBoundary.{RewardKind as K, Reward, Pots, Deltas}
import ConwayStake as S
import ConwayRewardApplication as R

class ConwayRewardApplicationSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def b(n: Int, size: Int = 28) = Bytes(Vector.fill(size)(n.toByte))
  private val key = S.Credential(false, b(1))
  private val script = S.Credential(true, b(1))
  private val other = S.Credential(false, b(2))
  private val pool = b(3)
  private val pools = b(4)
  private val max = (BigInt(1) << 64) - 1
  private val accounts = Map(key -> S.Account(10, 2, None), script -> S.Account(20, 2, Some(pool)))
  private val pots = Pots(100, 1000, 50, 5000)

  test(
    "PV9 partitions by current registration, credits undelegated accounts, and sends unregistered to treasury"
  ) {
    val rewards = Map(
      key -> Set(Reward(K.Member, pool, 3), Reward(K.Leader, pool, 4), Reward(K.Member, pools, 5)),
      script -> Set(Reward(K.Member, pool, 6)),
      other -> Set(Reward(K.Leader, pools, 7))
    )
    val p = get(R.applyPv9(accounts, pots, Deltas(2, -19, -8), rewards))
    assertEquals(p.credited, Map(key -> BigInt(12), script -> BigInt(6)))
    assertEquals(p.registered, rewards - other)
    assertEquals(p.unregistered, Map(other -> rewards(other)))
    assertEquals(p.unregisteredCredentials, Set(other));
    assertEquals(p.totalUnregistered, BigInt(7))
    assertEquals(p.balances, Map(key -> BigInt(22), script -> BigInt(26)))
    assertEquals(p.pots, Pots(109, 981, 42, 5000))
    assertEquals(p.accounts(key).deposit, BigInt(2)); assertEquals(p.accounts(key).delegation, None)
    assert(p.shelleyIgnored.isEmpty && !p.nativeEntitlementValidated)
    assertEquals(
      p.balances.values.sum + p.pots.treasury + p.pots.reserves + p.pots.fees,
      BigInt(1180)
    )
  }
  test(
    "registration is independent of freeze-era membership and key/script hashes do not collapse"
  ) {
    val rewards =
      Map(key -> Set(Reward(K.Member, pool, 8)), script -> Set(Reward(K.Leader, pool, 9)))
    val before = get(R.applyPv9(Map(key -> accounts(key)), pots, Deltas(0, -17, 0), rewards))
    val now = get(R.applyPv9(Map(script -> accounts(script)), pots, Deltas(0, -17, 0), rewards))
    assertEquals(before.credited.keySet, Set(key)); assertEquals(now.credited.keySet, Set(script))
    assertEquals(before.totalUnregistered, BigInt(9));
    assertEquals(now.totalUnregistered, BigInt(8))
    assertEquals(now.pots.treasury, BigInt(108)); assert(before.id != now.id)
  }
  test("empty and zero sets preserve native recipient summaries and deterministic set identity") {
    val rewards = Map(other -> Set.empty[Reward], key -> Set(Reward(K.Member, pool, 0)))
    val p = get(R.applyPv9(accounts, pots, Deltas(0, 0, 0), rewards))
    assertEquals(p.unregisteredCredentials, Set(other));
    assertEquals(p.totalUnregistered, BigInt(0))
    assertEquals(p.credited, Map(key -> BigInt(0)))
    assertEquals(p.accounts, accounts); assertEquals(p.pots, pots)
    assertEquals(
      p.id,
      get(
        R.applyPv9(
          accounts.toVector.reverse.toMap,
          pots,
          Deltas(0, 0, 0),
          rewards.toVector.reverse.toMap
        )
      ).id
    )
    assert(p.id != get(R.applyPv9(accounts, pots, Deltas(0, 0, 0), Map.empty)).id)
    val duplicate = Map(key -> Set(Reward(K.Member, pool, 1), Reward(K.Member, pool, 2)))
    assert(R.applyPv9(accounts, pots, Deltas(0, -3, 0), duplicate).isLeft)
  }
  test("conservation and bounded signed application reject negative pots before redistribution") {
    val unregistered = Map(other -> Set(Reward(K.Member, pool, 2)))
    assert(R.applyPv9(Map.empty, Pots(1, 5, 0, 10), Deltas(-2, 0, 0), unregistered).isLeft)
    assert(R.applyPv9(accounts, pots, Deltas(0, -1, 0), Map.empty).isLeft)
    assert(R.applyPv9(accounts, pots, Deltas(1001, -1001, 0), Map.empty).isLeft)
    assert(R.applyPv9(accounts, pots, Deltas(51, 0, -51), Map.empty).isLeft)
    assert(R.applyPv9(accounts, pots, Deltas(max + 1, -max - 1, 0), Map.empty).isLeft)
  }
  test("coin, reward sum, account aggregation and treasury overflow all fail closed") {
    val rs = Map(key -> Set(Reward(K.Member, pool, max), Reward(K.Leader, pool, 1)))
    assert(R.applyPv9(accounts, pots, Deltas(0, 0, 0), rs).isLeft)
    assert(
      R.applyPv9(
        Map(key -> S.Account(max, 0, None), script -> S.Account(1, 0, None)),
        Pots(0, 0, 0, max),
        Deltas(0, 0, 0),
        Map.empty
      ).isLeft
    )
    // A conserving transfer cannot overfill treasury from a valid supply; reject invalid inputs first.
    assert(
      R.applyPv9(
        Map.empty,
        Pots(max, 1, 0, max),
        Deltas(0, -1, 0),
        Map(other -> Set(Reward(K.Member, pool, 1)))
      ).isLeft
    )
    assert(
      R.applyPv9(accounts, pots, Deltas(0, 0, 0), Map(key -> Set(Reward(K.Member, pool, -1))))
        .isLeft
    )
    assert(
      R.applyPv9(
        accounts,
        pots,
        Deltas(0, 0, 0),
        Map(S.Credential(false, Bytes.empty) -> Set.empty)
      ).isLeft
    )
  }
