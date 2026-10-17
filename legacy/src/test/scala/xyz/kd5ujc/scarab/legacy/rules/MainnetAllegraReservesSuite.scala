// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy.*


import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}

/** Mainnet sync from genesis, stop 5: at slot 17,020,941 (epoch 237, Allegra) the node refused
  * transaction 48f567e5 for `WithdrawalsNotInRewardsDELEGS`: it withdraws 351,635,274 lovelace, the
  * whole balance of the reward account `e1f6cb9f...`, and the node's balance was 351,050,635.
  *
  * The account's only reward then was the leader reward of pool `ffdf1b8f...` for epoch 235 (Koios
  * `account_rewards`), calculated in epoch 236, the first Allegra epoch, and paid at the boundary
  * into 237. The calculation draws on the reserves, and the node's reserves at the start of epoch
  * 236 were 318,200,635 ada below the reference's (Koios `totals`: equal treasury and reserves at
  * 235, equal treasury at 236): the unredeemed AVVM balances, which the Shelley to Allegra
  * translation returns to the reserves (`returnRedeemAddrsToReserves`) and the node's boundary did
  * not.
  *
  * The first test reproduces both leader rewards from the real inputs of the calculation, the
  * reserves alone differing; it runs on both rule sets ([[MainnetAllegraReservesSuite]]). The
  * others check the boundary rule that returns the redeem outputs and are the old side's by design
  * ([[MainnetAllegraRedeemOldSuite]]): the new rules get the Byron redeem outputs as the stash the
  * hand-over from Byron leaves in the new-epoch state (`StashedAVVM`), which the flat state does
  * not carry, and the entry into Allegra is a translation of that state (J2.6: the first block of
  * each era stays with the old side).
  */
class MainnetAllegraReservesSuite extends FunSuite:
  private val rs = RuleSet
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e), identity)

  // ---- the reward calculation of epoch 236

  private val owner =
    Credential.Key(bytes("e1f6cb9f65d11425be3aefc5ff5ac2f2d33f885ddfde3c258c4b23f5"))
  private val pool = bytes("ffdf1b8fe56f419eaf95f20df8ffa8e72c799e9571ef9ce1c9a997f3")

  /** The node's reserves at the start of epoch 236 (its `ledgerEpoch` event) and the reference's.
    */
  private val nodeReserves = BigInt("12794406996777589")
  private val avvmReturned = BigInt("318200635000000")

  /** The leader reward of pool ffdf1b8f for epoch 235, as calculated with `reserves`.
    *
    * Inputs, all public: parameters of epoch 235 (d = 0.32, a0 = 0.3, nOpt = 500, rho = 0.003, tau =
    * 0.2), the pool's registration (cost 340 ada, margin 1%, pledge 1,000 ada, one owner who is the
    * reward account), its four delegators in the stake snapshot of epoch 235 and its one block, the
    * 14,392 blocks pools made in epoch 235 (the node's `epochValidated`: the rest of the 21,203
    * were overlay blocks), the fee pot 8,115,520,823 and the active stake of the snapshot (Koios
    * `pool_history`, `pool_delegators_history`, `epoch_params`, `totals`). The other pools are one
    * stand-in pool holding the rest of the active stake and the rest of the blocks.
    */
  private def leaderReward(reserves: BigInt): BigInt =
    val members = Map(
      owner -> BigInt("1008447705"),
      Credential.Key(bytes("437d2a3a1a661e88594e92f62c98d2a1abcad00fe10d9b8720f86d34")) -> BigInt(
        "9281947006"
      ),
      Credential.Key(bytes("6203cb82d7d0eb75721b78bea8a5d27af654ddd1bc1c7ae76910158e")) -> BigInt(
        "142357969763"
      ),
      Credential.Key(bytes("eb526044328462293d1d153d32fcc9fbcc4e9378546a974089a32b9a")) -> BigInt(
        "976728906"
      )
    )
    assertEquals(members.values.sum, BigInt("153625093380"))
    val activeStake = BigInt("20218109006931643")
    val others = bytes("00" * 28)
    val filler = Credential.Key(bytes("11" * 28))
    val params = PoolParams(
      pool,
      bytes("8447cb5ef7b2045afad0527c0978fdc67686c6de5d86e9c7fa1d1a2f4b9a8b22"),
      pledge = BigInt(1000000000),
      cost = BigInt(340000000),
      margin = Rational(1, 100),
      RewardAccount(1, owner),
      Set(bytes("e1f6cb9f65d11425be3aefc5ff5ac2f2d33f885ddfde3c258c4b23f5")),
      Bytes.empty,
      None
    )
    val go = Snapshot(
      stake = members + (filler -> (activeStake - members.values.sum)),
      delegations = members.map((c, _) => c -> pool) + (filler -> others),
      pools = Map(
        pool -> params,
        others -> params
          .copy(
            operator = others,
            pledge = 0,
            rewardAccount = RewardAccount(1, filler),
            owners = Set.empty
          )
      )
    )
    val epoch235 = TestLedger
      .params(2)
      .copy(nOpt = 500, d = Rational(32, 100), minPoolCost = BigInt(340000000))
    val state = FlatTestLedger.state(3, Vector.empty)
      .copy(
        snapshots = Snapshots(Snapshot.empty, Snapshot.empty, go, BigInt("8115520823")),
        blocksPrevious = Map(pool -> BigInt(1), others -> BigInt(14391)),
        // the new rules pay only the credentials that are registered when the update is made
        accounts = (members.keySet + filler).map(_ -> Account(0, 0, None, None)).toMap,
        reserves = reserves,
        prevParams = Some(epoch235)
      )
    val globals = LedgerGlobals(
      maxSupply = TestLedger.Supply,
      epochLength = 432000,
      activeSlotsCoeff = Rational(1, 20),
      network = 1
    )
    rs.rewards(state, globals)(owner)

  test("the leader reward of epoch 235 is the reference's only with the AVVM balances returned") {
    assertEquals(leaderReward(nodeReserves), BigInt(351050635)) // the node's balance at stop 5
    assertEquals(leaderReward(nodeReserves + avvmReturned), BigInt(351635274)) // the withdrawal
  }
