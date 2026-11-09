// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.state.*

/** Compact operations at cardano-ledger f649f975's Coin/account/rule boundaries. Where the
  * reference's `Word64` wraps, the node's checked arithmetic is a [[VocabError]], which the rules
  * report as no verdict (overflow-improvements.md#compact-coin-arithmetic); below the boundary the
  * results are the reference's.
  */
class CompactCoinRulesSuite extends FunSuite:
  import NativeRewardFixture.{accounts, compact, cred, ok, pool, stakePool}

  private val modulus = BigInt(1) << 64
  private val maximum = modulus - 1
  private val epoch = EpochNo.fromUnsigned(5)

  test("compact arithmetic is checked where the reference's Word64 wraps") {
    val outOfRange = (n: BigInt) => Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, n))
    assertEquals(compact(maximum).add(compact(1)), outOfRange(modulus))
    assertEquals(compact(maximum - 1).add(compact(1)), Right(compact(maximum)))
    assertEquals(
      CompactCoin.zero.subtract(compact(1)),
      Left(VocabError.Negative(VocabError.Kind.CompactCoin, -1))
    )
    assertEquals(compact(1).subtract(compact(1)), Right(CompactCoin.zero))
    assertEquals(CompactCoin.sum(List(compact(maximum), compact(2))), outOfRange(modulus + 1))
    assertEquals(CompactCoin.sum(List(compact(maximum - 2), compact(2))), Right(compact(maximum)))
    assertEquals(
      CompactCoin.sumByKey(List(cred(1) -> compact(maximum), cred(1) -> compact(1))),
      outOfRange(modulus)
    )
    assertEquals(
      CompactCoin.sumByKey(List(cred(1) -> compact(maximum), cred(2) -> compact(1))),
      Right(Map(cred(1) -> compact(maximum), cred(2) -> compact(1)))
    )
    assertEquals(
      CompactCoin.fromCoin(Coin(modulus)),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
  }

  test("account credit past 2^64 is no verdict on the dense and sparse paths of both families") {
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
        assertEquals(ordered, Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus)))
        // a credit to one below the boundary is the reference's
        val lower = accounts(
          NewEpochState.empty[E](epoch, TestLedger.params(major)),
          (1 to count).map(n =>
            cred(n) -> EraLedger.AccountView(compact(maximum - 1), compact(0), None)
          )
        ).es.ls
        val credited = ok(EraLedger.creditAscending(lower, additions))
        assertEquals(EraLedger.credit(lower, additions), Right(credited))
        val balances = EraLedger.accounts(credited).map((c, a) => c -> a.balance.toBigInt).toMap
        assertEquals(balances(cred(1)), maximum)
        assertEquals(balances(cred(2)), maximum - 1)
      }
    check[Shelley](2)
    check[Conway](9)
  }

  test("MIR unions Coin amounts before checked compaction, then checks the account addition") {
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
    // the union, 1, compacts; the reference adds it to 2^64 - 1 and wraps to 0
    assertEquals(
      Mir.step(before),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
    val lower = before.copy(ls =
      EraLedger.withIRewards(
        accounts(
          NewEpochState.empty[Shelley](epoch, TestLedger.params(5)),
          List(cred(1) -> EraLedger.AccountView(compact(maximum - 1), compact(0), None))
        ).es.ls,
        ir
      )
    )
    val after = ok(Mir.step(lower))
    assertEquals(after.ls.cert.dState.accounts.states(cred(1)).balance.toBigInt, maximum)
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

  test("POOLREAP's compact refunds past 2^64 are no verdict; below, they are the reference's") {
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
    def reaped(p: PState) =
      val ls = EraLedger.withPState(initial.ls, p)
      PoolReap.step(
        epoch,
        PoolreapState[Shelley](ls.utxo.copy(deposited = Coin(7)), initial.chainAccount, ls.cert)
      )
    // the refund of 2^64 - 1 and the unclaimed 1 add up to 2^64, which the reference wraps to 0
    assertEquals(reaped(pstate), Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus)))
    val lower = pstate.copy(stakePools =
      Sorted.map(
        List(
          pool(1) -> stakePool(cred(1)).copy(deposit = compact(maximum - 1)),
          pool(2) -> stakePool(cred(2)).copy(deposit = compact(1))
        )
      )
    )
    val after = ok(reaped(lower))
    assertEquals(after.utxo.deposited.value, BigInt(7) - maximum)
    assertEquals(after.chainAccount.treasury.value, BigInt(1))
    assertEquals(after.cert.dState.accounts.states(cred(1)).balance.toBigInt, maximum - 1)
    assertEquals(after.cert.pState.stakePools.size, 0)
  }

  test("compact reward aggregation checks each reward and their sum") {
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
    assertEquals(
      Rewards.aggregateCompact(5, rewards(maximum, 1)),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
    assertEquals(
      ok(Rewards.aggregateCompact(5, rewards(maximum - 1, 1)))(cred(1)).toBigInt,
      maximum
    )
    assertEquals(
      Rewards.aggregateCompact(5, rewards(-1, 2)),
      Left(VocabError.Negative(VocabError.Kind.CompactCoin, BigInt(-1)))
    )
    assertEquals(
      Rewards.aggregateCompact(5, rewards(modulus, -1)),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
  }
