// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.state.*

/** What the mirror's reading says each credential is paid (`MirrorEraRead.rewardsDue`):
  * `aggregateRewards` (`Rewards.hs:186`), by the parameters of the epoch the update is for. The
  * devnet found it (J2.8): before Allegra a credential that earns from several sources gets only
  * the least of its rewards, and the node's flat state held the amounts so, while the mirror holds
  * every reward.
  */
class RewardsDueSuite extends FunSuite:
  private def bytes(size: Int, n: Int) = Bytes.fromArray(Array.fill(size)(n.toByte))

  private val credential: Credential[Staking] =
    Credential.KeyHashObj(
      KeyHash.fromBytes[Staking](bytes(28, 1)).fold(e => fail(e.toString), identity)
    )
  private val poolA =
    KeyHash.fromBytes[StakePool](bytes(28, 2)).fold(e => fail(e.toString), identity)
  private val poolB =
    KeyHash.fromBytes[StakePool](bytes(28, 3)).fold(e => fail(e.toString), identity)

  private def coin(n: Long) = Coin(n)

  /** A state with the update paying `credential` a leader reward and a member reward, the previous
    * parameters of the major version `previous`.
    */
  private def due(previous: Int): RewardsDue =
    given ShelleyBasedEra[Shelley] = ShelleyBasedEra.SShelley
    val params = TestLedger.params(previous)
    val base = Build.nes[Shelley](3, params)
    val rewards = RewardSet(
      List(
        Reward(RewardType.LeaderReward, poolA, coin(200)),
        Reward(RewardType.MemberReward, poolB, coin(7000))
      )
    )
    val update = RewardUpdate(
      DeltaCoin(0),
      DeltaCoin(0),
      Sorted.map(List(credential -> rewards)),
      DeltaCoin(0),
      NonMyopic.empty
    )
    val nes = base.copy(ru = Some(PulsingRewUpdate.Complete(update)))
    new MirrorEraRead[Shelley](nes).rewardsDue.fold(e => fail(e.toString), identity) match
      case Some(d) => d
      case None    => fail("no update")

  test("before Allegra a credential with several rewards is paid the least") {
    assertEquals(due(2).byCredential.toList, List(credential -> BigInt(200)))
  }

  test("from Allegra it is paid the sum") {
    assertEquals(due(3).byCredential.toList, List(credential -> BigInt(7200)))
  }
