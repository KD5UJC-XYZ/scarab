// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.state.*

/** Native compact operations at cardano-ledger f649f975's Coin/account/rule boundaries. */
class CompactCoinRulesSuite extends FunSuite:
  import NativeRewardFixture.{accounts, compact, cred, ok, pool, stakePool}

  private val modulus = BigInt(1) << 64
  private val maximum = modulus - 1
  private val epoch = EpochNo.fromUnsigned(5)

  test("compact arithmetic wraps while Coin conversion remains checked") {
    assertEquals(compact(maximum).add(compact(1)).toBigInt, BigInt(0))
    assertEquals(CompactCoin.zero.subtract(compact(1)).toBigInt, maximum)
    assertEquals(CompactCoin.sum(List(compact(maximum), compact(2))).toBigInt, BigInt(1))
    assertEquals(
      CompactCoin.fromCoin(Coin(modulus)),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
  }

  test("native account credit wraps on dense and sparse paths in both account families") {
    def check[E <: EraTag](major: Int)(using ShelleyBasedEra[E]): Unit =
      List(2, 40).foreach { count =>
        val original = accounts(
          NewEpochState.empty[E](epoch, TestLedger.params(major)),
          (1 to count).map(n =>
            cred(n) -> EraLedger.AccountView(compact(maximum), compact(0), None)
          )
        ).es.ls
        val additions = Sorted.map(List(cred(1) -> compact(1)))
        val direct = EraLedger.credit(original, additions)
        val ordered = EraLedger.creditAscending(original, additions)
        assertEquals(ordered, direct)
        val balances = EraLedger.accounts(ordered).map((c, a) => c -> a.balance.toBigInt).toMap
        assertEquals(balances(cred(1)), BigInt(0))
        assertEquals(balances(cred(2)), maximum)
      }
    check[Shelley](2)
    check[Conway](9)
  }

  test("MIR unions Coin amounts before checked compaction, then wraps account addition") {
    val initial = accounts(
      NewEpochState.empty[Shelley](epoch, TestLedger.params(5)),
      List(cred(1) -> EraLedger.AccountView(compact(maximum), compact(0), None))
    ).es
    val ir = InstantaneousRewards(
      Sorted.map(List(cred(1) -> Coin(-1))),
      Sorted.map(List(cred(1) -> Coin(2))),
      DeltaCoin(0),
      DeltaCoin(0)
    )
    val before = initial.copy(
      chainAccount = ChainAccountState(Coin(2), Coin(0)),
      ls = EraLedger.withIRewards(initial.ls, ir)
    )
    val after = ok(Mir.step(before))
    assertEquals(after.ls.cert.dState.accounts.states(cred(1)).balance.toBigInt, BigInt(0))
    assertEquals(after.chainAccount, ChainAccountState(Coin(0), Coin(1)))
    val tooLarge = before.copy(
      chainAccount = ChainAccountState(Coin(1), Coin(maximum)),
      ls = EraLedger.withIRewards(
        before.ls,
        ir.copy(
          reserves = Sorted.map(List(cred(1) -> Coin(maximum))),
          treasury = Sorted.map(List(cred(1) -> Coin(1)))
        )
      )
    )
    assertEquals(
      Mir.step(tooLarge),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
  }

  test("POOLREAP folds compact refunds together before subtracting from deposited Coin") {
    val initial = accounts(
      NewEpochState.empty[Shelley](epoch, TestLedger.params(2)),
      List(cred(1) -> EraLedger.AccountView(compact(0), compact(0), None))
    ).es
    val pools = Sorted.map(
      List(
        pool(1) -> stakePool(cred(1)).copy(deposit = compact(maximum)),
        pool(2) -> stakePool(cred(2)).copy(deposit = compact(1))
      )
    )
    val pstate = initial.ls.cert.pState.copy(
      stakePools = pools,
      retiring = Sorted.map(List(pool(1) -> epoch, pool(2) -> epoch))
    )
    val ls = EraLedger.withPState(initial.ls, pstate)
    val after = ok(
      PoolReap.step(
        epoch,
        PoolreapState[Shelley](
          ls.utxo.copy(deposited = Coin(7)),
          initial.chainAccount,
          ls.cert
        )
      )
    )
    assertEquals(after.utxo.deposited.value, BigInt(7))
    assertEquals(after.chainAccount.treasury.value, BigInt(1))
    assertEquals(after.cert.dState.accounts.states(cred(1)).balance.toBigInt, maximum)
    assertEquals(after.cert.pState.stakePools.size, 0)
  }

  test("compact reward aggregation checks each reward before its wrapped sum") {
    def rewards(first: BigInt, second: BigInt) = Sorted.map(
      List(
        cred(1) -> RewardSet(
          List(
            Reward(RewardType.MemberReward, pool(1), Coin(first)),
            Reward(RewardType.MemberReward, pool(2), Coin(second))
          )
        )
      )
    )
    assertEquals(Rewards.aggregate(5, rewards(maximum, 1)).head._2.value, modulus)
    assertEquals(ok(Rewards.aggregateCompact(5, rewards(maximum, 1)))(cred(1)).toBigInt, BigInt(0))
    assertEquals(
      Rewards.aggregateCompact(5, rewards(-1, 2)),
      Left(VocabError.Negative(VocabError.Kind.CompactCoin, BigInt(-1)))
    )
    assertEquals(
      Rewards.aggregateCompact(5, rewards(modulus, -1)),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
  }
