// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** Compact stake folds at cardano-ledger f649f975 State/Stake and DRepPulser boundaries. Where the
  * reference's `Word64` wraps (even to a zero that `unsafeNonZero` keeps), the node's sum is a
  * [[VocabError]], which the epoch rules report as no verdict
  * (overflow-improvements.md#compact-coin-arithmetic); below the boundary the results are the
  * reference's.
  */
class CompactStakeSuite extends FunSuite:
  import NativeRewardFixture.{compact, cred, globals, key, ok, pool, stakePool}

  private val modulus = BigInt(1) << 64
  private val maximum = modulus - 1
  private val epoch = EpochNo.fromUnsigned(5)

  test("stake resolution past 2^64 is no verdict, and an account-only zero is omitted") {
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
    val outOfRange = Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    assertEquals(resolved, outOfRange)
    val below = ok(
      StakeDistribution.resolve(
        Sorted.map(List(cred(1) -> compact(1))),
        Vector(
          cred(1) -> EraLedger.AccountView(compact(maximum - 1), compact(0), Some(pool(1))),
          cred(2) -> EraLedger.AccountView(compact(0), compact(0), Some(pool(1)))
        ).iterator,
        Sorted.empty[flat.Ptr, Credential[Staking]],
        Sorted.empty[flat.Ptr, CompactCoin]
      )
    )
    assertEquals(below.entries.keySet.toSet, Set(cred(1)))
    assertEquals(below.entries(cred(1)).stake.value.toBigInt, maximum)
    assertEquals(below.total.value.value, maximum)
    assertEquals(NonZero.from(compact(0)), Left(VocabError.Zero(VocabError.Kind.NonZero)))
    val ptr = flat.Ptr(0, 0, 0)
    val pointer = StakeDistribution.resolve(
      Sorted.empty,
      accounts.iterator,
      Sorted.map(List(ptr -> cred(1))),
      Sorted.map(List(ptr -> compact(1)))
    )
    assertEquals(pointer, outOfRange)
  }

  test("pool and owner compact stake past 2^64 is no verdict; total active Coin is unbounded") {
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
    assertEquals(active.total.value.value, modulus)
    assertEquals(
      StakeDistribution.poolSnapshot(active, active.total, params),
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
    val lower = ActiveStake(
      Sorted.map(
        List(
          cred(1) -> StakeWithDelegation(ok(NonZero.from(compact(maximum - 1))), pool(1)),
          cred(2) -> StakeWithDelegation(ok(NonZero.from(compact(1))), pool(1))
        )
      )
    )
    val snapshot = ok(StakeDistribution.poolSnapshot(lower, lower.total, params))
    assertEquals(snapshot.stake.toBigInt, maximum)
    assertEquals(snapshot.stakeRatio, Rational.one)
    assertEquals(snapshot.selfDelegatedOwnersStake.value, maximum)
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

  test("proposal deposits that do not compact, or add up past 2^64, are no verdict") {
    val account = Convert.back(cred(1))
    // the reference makes a deposit that does not compact zero, and wraps the sum
    assertEquals(
      proposals(maximum, 1).depositsByCredential,
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
    assertEquals(
      proposals(modulus, 7).depositsByCredential,
      Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    )
    assertEquals(
      proposals(-1, 7).depositsByCredential,
      Left(VocabError.Negative(VocabError.Kind.CompactCoin, -1))
    )
    assertEquals(ok(proposals(maximum - 1, 1).depositsByCredential)(account).toBigInt, maximum)
    assertEquals(proposals(modulus, -1, 7).totalDeposits, modulus + 6)
  }

  test("the DRep pulser's sums past 2^64 are no verdict; below, they are the reference's") {
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
      ConwayPulser.fresh(globals(100, Rational(1, 2)), state, epoch).map(_.snapshot.drepDistr)
    val outOfRange = Left(VocabError.OutOfRange(VocabError.Kind.CompactCoin, modulus))
    assertEquals(pulsed(1, Map.empty), outOfRange)
    assertEquals(pulsed(0, Map(cred(1) -> compact(1))), outOfRange)
    assertEquals(ok(pulsed(0, Map.empty))(flat.DRep.AlwaysAbstain), maximum)
  }
