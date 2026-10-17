// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.codec.Bytes

class SnapshotSuite extends munit.FunSuite:
  private def bytes(b: Int) = Bytes.wrap(Array.fill(28)(b.toByte))

  private def pool(b: Int) =
    PoolParams(
      bytes(b),
      bytes(b + 100),
      0,
      0,
      Rational(1, 10),
      RewardAccount(0, Credential.Key(bytes(b))),
      Set(bytes(b)),
      Bytes.empty,
      None
    )

  test(
    "a pool whose delegators have no stake stays in the pool stake with none; others keep theirs"
  ) {
    val staked = Credential.Key(bytes(1))
    val s = Snapshot(
      Map(staked -> BigInt(500)),
      Map(staked -> bytes(10)),
      Map(bytes(10) -> pool(10), bytes(11) -> pool(11), bytes(12) -> pool(12)),
      idlePools = Set(bytes(10), bytes(11), bytes(99))
    )
    // pool 10 has a staked and an idle delegator: its stake is not reset; pool 11 has only an idle one;
    // pool 12 has none; 99 is not a registered pool
    assertEquals(s.poolStake, Map(bytes(10) -> BigInt(500), bytes(11) -> BigInt(0)))
    assertEquals(s.activeStake, BigInt(500))
  }
