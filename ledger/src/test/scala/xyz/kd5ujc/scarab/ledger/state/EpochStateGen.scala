// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import org.scalacheck.{Arbitrary, Gen}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.{*, given}
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.{*, given}

/** Generators of the top of the mirror: snapshots, the pool distribution, the reward types and the
  * epoch states. Likelihood weights are finite halves (the `Eq` of a `Float` is the IEEE one, and a
  * normalised sequence is a difference of them, which halves keep exact).
  */
object EpochStateGen:
  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  private val ratio: Gen[Rational] =
    Gen.oneOf(Rational.zero, Rational(1, 3), Rational(1, 2), Rational.one)

  given nonZeroCoin: Arbitrary[NonZero[Coin]] = Arbitrary(
    Gen
      .oneOf(1L, 2L, Long.MaxValue)
      .map(n => NonZero.from(Coin.fromBigInt(BigInt(n)).toOption.get).toOption.get)
  )

  given nonZeroCompact: Arbitrary[NonZero[CompactCoin]] = Arbitrary(
    word64Bits.suchThat(_ != 0L).map(b => NonZero.from(CompactCoin.fromUnsigned(b)).toOption.get)
  )

  given Arbitrary[StakeWithDelegation] = Arbitrary(
    Gen.zip(arb[NonZero[CompactCoin]], arb[KeyHash[StakePool]]).map(StakeWithDelegation(_, _))
  )

  given Arbitrary[ActiveStake] = Arbitrary(
    sortedMap(arb[Credential[Staking]], arb[StakeWithDelegation]).map(ActiveStake(_))
  )

  given Arbitrary[StakePoolSnapShot] = Arbitrary(
    Gen
      .zip(
        arb[CompactCoin],
        ratio,
        sortedSet(arb[KeyHash[Staking]]),
        arb[Coin],
        arb[VRFVerKeyHash],
        arb[Coin],
        arb[Coin],
        ratio,
        arb[Word64],
        arb[Credential[Staking]]
      )
      .map((s, r, o, os, v, p, c, m, n, a) => StakePoolSnapShot(s, r, o, os, v, p, c, m, n, a))
  )

  given Arbitrary[SnapShot] = Arbitrary(
    Gen
      .zip(arb[ActiveStake], sortedMap(arb[KeyHash[StakePool]], arb[StakePoolSnapShot]))
      .map(SnapShot(_, _))
  )

  given Arbitrary[SnapShots] = Arbitrary(
    Gen.zip(arb[SnapShot], arb[SnapShot], arb[SnapShot], arb[Coin]).map(SnapShots(_, _, _, _))
  )

  given Arbitrary[IndividualPoolStake] = Arbitrary(
    Gen.zip(ratio, arb[CompactCoin], arb[VRFVerKeyHash]).map(IndividualPoolStake(_, _, _))
  )

  given Arbitrary[PoolDistr] = Arbitrary(
    Gen
      .zip(sortedMap(arb[KeyHash[StakePool]], arb[IndividualPoolStake]), arb[NonZero[Coin]])
      .map(PoolDistr(_, _))
  )

  given Arbitrary[LogWeight] = Arbitrary(
    Gen.oneOf(-3.5f, -1f, -0.5f, 0f, 0.5f, 1f, 2.5f).map(LogWeight(_))
  )

  given Arbitrary[Likelihood] = Arbitrary(
    Gen.choose(0, 4).flatMap(n => Gen.listOfN(n, arb[LogWeight])).map(ws => Likelihood(ws.toVector))
  )

  given Arbitrary[NonMyopic] = Arbitrary(
    Gen
      .zip(sortedMap(arb[KeyHash[StakePool]], arb[Likelihood]), arb[Coin])
      .map(NonMyopic(_, _))
  )

  given Arbitrary[RewardType] = Arbitrary(Gen.oneOf(RewardType.values.toSeq))

  given Arbitrary[Reward] = Arbitrary(
    Gen.zip(arb[RewardType], arb[KeyHash[StakePool]], arb[Coin]).map(Reward(_, _, _))
  )

  given Arbitrary[RewardSet] = Arbitrary(
    Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, arb[Reward])).map(RewardSet(_))
  )

  given Arbitrary[RewardUpdate] = Arbitrary(
    Gen
      .zip(
        arb[DeltaCoin],
        arb[DeltaCoin],
        sortedMap(arb[Credential[Staking]], arb[RewardSet]),
        arb[DeltaCoin],
        arb[NonMyopic]
      )
      .map(RewardUpdate(_, _, _, _, _))
  )

  given Arbitrary[ProtVer] = Arbitrary(
    Gen
      .zip(Gen.choose(0L, 12L), Gen.choose(0L, 3L))
      .map((a, b) => ProtVer(Word64.fromUnsigned(a), Word64.fromUnsigned(b)))
  )

  given Arbitrary[RewardSnapShot] = Arbitrary(
    Gen
      .zip(
        arb[Coin],
        arb[ProtVer],
        arb[NonMyopic],
        arb[Coin],
        arb[Coin],
        arb[Coin],
        sortedMap(arb[KeyHash[StakePool]], arb[Likelihood]),
        sortedMap(arb[Credential[Staking]], arb[RewardSet])
      )
      .map(RewardSnapShot(_, _, _, _, _, _, _, _))
  )

  given Arbitrary[PulsingRewUpdate] = Arbitrary(
    Gen.oneOf(
      arb[RewardSnapShot].map(PulsingRewUpdate.Pulsing(_)),
      arb[RewardUpdate].map(PulsingRewUpdate.Complete(_))
    )
  )

  given Arbitrary[ChainAccountState] = Arbitrary(
    Gen.zip(arb[Coin], arb[Coin]).map(ChainAccountState(_, _))
  )

  given blocksMapArb: Arbitrary[SortedMap[KeyHash[StakePool], Word64]] = Arbitrary(
    sortedMap(arb[KeyHash[StakePool]], arb[Word64])
  )

  given Arbitrary[BlocksMade] = Arbitrary(
    arb[SortedMap[KeyHash[StakePool], Word64]].map(BlocksMade(_))
  )

  given conwayEpochState: Arbitrary[EpochState[Conway]] = Arbitrary(
    Gen
      .zip(arb[ChainAccountState], arb[LedgerState[Conway]], arb[SnapShots], arb[NonMyopic])
      .map(EpochState[Conway](_, _, _, _))
  )

  given babbageEpochState: Arbitrary[EpochState[Babbage]] = Arbitrary(
    Gen
      .zip(arb[ChainAccountState], arb[LedgerState[Babbage]], arb[SnapShots], arb[NonMyopic])
      .map(EpochState[Babbage](_, _, _, _))
  )

  given babbageNewEpochState: Arbitrary[NewEpochState[Babbage]] = Arbitrary(
    Gen
      .zip(
        arb[EpochNo],
        arb[BlocksMade],
        arb[BlocksMade],
        arb[EpochState[Babbage]],
        Gen.option(arb[PulsingRewUpdate]),
        arb[PoolDistr]
      )
      .map((e, bp, bc, es, ru, pd) => NewEpochState[Babbage](e, bp, bc, es, ru, pd, ()))
  )

  given conwayNewEpochState: Arbitrary[NewEpochState[Conway]] = Arbitrary(
    Gen
      .zip(
        arb[EpochNo],
        arb[BlocksMade],
        arb[BlocksMade],
        arb[EpochState[Conway]],
        Gen.option(arb[PulsingRewUpdate]),
        arb[PoolDistr]
      )
      .map((e, bp, bc, es, ru, pd) => NewEpochState[Conway](e, bp, bc, es, ru, pd, ()))
  )
