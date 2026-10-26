// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** Native compact stake folds at cardano-ledger f649f975 State/Stake and DRepPulser boundaries. */
class CompactStakeSuite extends FunSuite:
  import NativeRewardFixture.{compact, cred, globals, key, ok, pool, stakePool}

  private val modulus = BigInt(1) << 64
  private val maximum = modulus - 1
  private val epoch = EpochNo.fromUnsigned(5)

  test("native stake resolution preserves a wrapped zero but omits an account-only zero") {
    val accounts = Vector(
      cred(1) -> EraLedger.AccountView(compact(maximum), compact(0), Some(pool(1))),
      cred(2) -> EraLedger.AccountView(compact(0), compact(0), Some(pool(1)))
    )
    val resolved = StakeDistribution.resolve(
      Sorted.map(List(cred(1) -> compact(1))),
      accounts.iterator,
      Sorted.empty[flat.Ptr, Credential[Staking]],
      Sorted.empty[flat.Ptr, CompactCoin]
    )
    assertEquals(resolved.entries.keySet.toSet, Set(cred(1)))
    assertEquals(resolved.entries(cred(1)).stake.value.toBigInt, BigInt(0))
    assertEquals(resolved.total.value.value, BigInt(1))
    assertEquals(NonZero.from(compact(0)), Left(VocabError.Zero(VocabError.Kind.NonZero)))
    val ptr = flat.Ptr(0, 0, 0)
    val pointer = StakeDistribution.resolve(
      Sorted.empty,
      accounts.iterator,
      Sorted.map(List(ptr -> cred(1))),
      Sorted.map(List(ptr -> compact(1)))
    )
    assertEquals(pointer.entries(cred(1)).stake.value.toBigInt, BigInt(0))
  }

  test("pool and owner compact stake wrap while total active Coin remains unbounded") {
    val active = ActiveStake(
      Sorted.map(
        List(
          cred(1) -> StakeWithDelegation(ok(NonZero.from(compact(maximum))), pool(1)),
          cred(2) -> StakeWithDelegation(ok(NonZero.from(compact(1))), pool(1))
        )
      )
    )
    val params = stakePool(cred(1)).copy(
      owners = Sorted.set(List(key(1), key(2))),
      delegators = Sorted.set(List(cred(1), cred(2)))
    )
    val snapshot = StakeDistribution.poolSnapshot(active, active.total, params)
    assertEquals(active.total.value.value, modulus)
    assertEquals(snapshot.stake.toBigInt, BigInt(0))
    assertEquals(snapshot.stakeRatio, Rational.zero)
    assertEquals(snapshot.selfDelegatedOwnersStake.value, BigInt(0))
  }

  private def proposals(amounts: BigInt*): flat.Proposals =
    flat.Proposals(
      amounts.zipWithIndex.map { (amount, index) =>
        flat.GovActionState(
          flat.GovActionId(Bytes(Vector.fill(32)(index.toByte)), 0),
          Map.empty,
          Map.empty,
          Map.empty,
          flat.Proposal(amount, flat.RewardAccount(0, Convert.back(cred(1))), flat.GovAction.Info),
          0,
          100
        )
      }.toVector,
      flat.PrevIds()
    )

  test("proposal deposits default failed compaction to zero before wrapped credential grouping") {
    val account = Convert.back(cred(1))
    assertEquals(proposals(maximum, 1).depositsByCredential(account).toBigInt, BigInt(0))
    assertEquals(proposals(modulus, -1, 7).depositsByCredential(account).toBigInt, BigInt(7))
    assertEquals(proposals(modulus, -1, 7).totalDeposits, modulus + 6)
  }

  test("the native DRep pulser wraps account aggregation and instant stake before projection") {
    def pulsed(balance2: BigInt, instant: Map[Credential[Staking], CompactCoin]) =
      val base = NewEpochState.empty[Conway](epoch, flat.TestLedger.params(9)).es
      val cert = base.ls.cert.copy(dState =
        base.ls.cert.dState.copy(accounts =
          ConwayAccounts(
            Sorted.map(
              List(
                cred(1) -> ConwayAccountState(
                  compact(maximum),
                  compact(0),
                  None,
                  Some(DRep.DRepAlwaysAbstain)
                ),
                cred(2) -> ConwayAccountState(
                  compact(balance2),
                  compact(0),
                  None,
                  Some(DRep.DRepAlwaysAbstain)
                )
              )
            )
          )
        )
      )
      val state = base.copy(ls =
        base.ls.copy[Conway](
          cert = cert,
          utxo = base.ls.utxo.copy[Conway](instantStake = ConwayInstantStake(Sorted.map(instant)))
        )
      )
      ConwayPulser.fresh(globals(100, Rational(1, 2)), state, epoch).snapshot.drepDistr
    assertEquals(pulsed(1, Map.empty)(flat.DRep.AlwaysAbstain), BigInt(0))
    assertEquals(pulsed(0, Map(cred(1) -> compact(1)))(flat.DRep.AlwaysAbstain), BigInt(0))
  }
