// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.kernel.laws.discipline.EqTests
import cats.syntax.all.*
import monocle.law.discipline.{IsoTests, LensTests, PrismTests}
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen}
import scala.collection.immutable.SortedMap
import scala.compiletime.testing.typeCheckErrors
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.Utxo
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.{*, given}
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.{*, given}
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.{*, given}

/** The top of the mirror: instances, the lenses and isomorphisms (Monocle laws), and the derived
  * parts of the snapshots.
  */
class EpochStateSuite extends DisciplineSuite:
  given Cogen[SnapShot] = cogenShow
  given Cogen[SnapShots] = cogenShow
  given Cogen[PoolDistr] = cogenShow
  given Cogen[NonMyopic] = cogenShow
  given Cogen[RewardUpdate] = cogenShow
  given Cogen[PulsingRewUpdate] = cogenShow
  given Cogen[Option[PulsingRewUpdate]] = cogenShow
  given Cogen[BlocksMade] = cogenShow
  given Cogen[ChainAccountState] = cogenShow
  given Cogen[EpochNo] = cogenShow
  given Cogen[(Coin, Coin)] = cogenShow
  given blocksCogen: Cogen[SortedMap[KeyHash[StakePool], Word64]] = cogenShow
  given conwayEsCogen: Cogen[EpochState[Conway]] = cogenShow
  given conwayNesCogen: Cogen[NewEpochState[Conway]] = cogenShow
  given babbageNesCogen: Cogen[NewEpochState[Babbage]] = cogenShow
  given conwayLsCogen: Cogen[LedgerState[Conway]] = cogenShow
  given babbageLsCogen: Cogen[LedgerState[Babbage]] = cogenShow
  given babbageEsCogen: Cogen[EpochState[Babbage]] = cogenShow
  given Cogen[ConwayGovState] = cogenShow
  given Cogen[ConwayAccounts] = cogenShow
  given poolsCogen: Cogen[SortedMap[KeyHash[StakePool], StakePoolState]] = cogenShow
  given drepsCogen: Cogen[SortedMap[Credential[DRepRole], DRepState]] = cogenShow
  given Cogen[Coin] = cogenShow
  given nonZeroCoinCogen: Cogen[NonZero[Coin]] = cogenShow
  given Arbitrary[xyz.kd5ujc.scarab.ledger.Proposals] = Arbitrary(
    org.scalacheck.Gen.const(xyz.kd5ujc.scarab.ledger.Proposals.empty)
  )

  // instances, by record and by the witness
  checkAll("Eq[SnapShots]", EqTests[SnapShots].eqv)
  checkAll("Eq[PoolDistr]", EqTests[PoolDistr].eqv)
  checkAll("Eq[NonMyopic]", EqTests[NonMyopic].eqv)
  checkAll("Eq[RewardUpdate]", EqTests[RewardUpdate].eqv)
  checkAll("Eq[PulsingRewUpdate]", EqTests[PulsingRewUpdate].eqv)
  checkAll("Eq[BlocksMade]", EqTests[BlocksMade].eqv)
  checkAll("Eq[EpochState[Conway]]", EqTests[EpochState[Conway]].eqv)
  checkAll("Eq[NewEpochState[Conway]]", EqTests[NewEpochState[Conway]].eqv)
  checkAll("Eq[NewEpochState[Babbage]]", EqTests[NewEpochState[Babbage]].eqv)

  // isomorphisms: the two records that are exactly a pair or a map
  checkAll("BlocksMade.iso", IsoTests(BlocksMade.iso))
  checkAll("ChainAccountState.pair", IsoTests(ChainAccountState.pair))

  // a prism: the completed form of a reward update
  checkAll("PulsingRewUpdate.complete", PrismTests(PulsingRewUpdate.complete))

  // lenses: a field of each record, composed Conway paths through the three levels
  checkAll("SnapShots.mark", LensTests(SnapShots.mark))
  checkAll("SnapShots.fee", LensTests(SnapShots.fee))
  checkAll("PoolDistr.totalActiveStake", LensTests(PoolDistr.totalActiveStake))
  checkAll("NonMyopic.rewardPot", LensTests(NonMyopic.rewardPot))
  checkAll("RewardUpdate.nonMyopic", LensTests(RewardUpdate.nonMyopic))
  checkAll("EpochState.nonMyopic", LensTests(EpochState.nonMyopic[Conway]))
  checkAll("NewEpochState.es", LensTests(NewEpochState.es[Conway]))
  checkAll("NewEpochState.ru", LensTests(NewEpochState.ru[Conway]))
  checkAll("NewEpochState.ledger[Conway]", LensTests(NewEpochState.ledger[Conway]))
  checkAll("NewEpochState.ledger[Babbage]", LensTests(NewEpochState.ledger[Babbage]))
  checkAll("ConwayNewEpochState.gov", LensTests(ConwayNewEpochState.gov))
  checkAll("ConwayNewEpochState.pools", LensTests(ConwayNewEpochState.pools))
  checkAll("ConwayNewEpochState.dreps", LensTests(ConwayNewEpochState.dreps))
  checkAll("ConwayNewEpochState.accounts", LensTests(ConwayNewEpochState.accounts))
  checkAll("ConwayNewEpochState.treasury", LensTests(ConwayNewEpochState.treasury))
  checkAll("ConwayNewEpochState.snapshots", LensTests(ConwayNewEpochState.snapshots))
  checkAll("ConwayEpochState.mark", LensTests(ConwayEpochState.mark))

  private def key[R <: KeyRole](b: Int): KeyHash[R] =
    KeyHash
      .fromBytes[R](xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(28)(b.toByte)))
      .toOption
      .get

  private def coin(n: Long): Coin = Coin(BigInt(n))
  private def compact(n: Long): CompactCoin = CompactCoin.fromUnsigned(n)
  private val vrf: VRFVerKeyHash =
    VRFVerKeyHash
      .fromBytes(xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(32)(7.toByte)))
      .toOption
      .get

  private def poolSnap(stake: Long, delegators: Long): StakePoolSnapShot =
    StakePoolSnapShot(
      compact(stake),
      Rational(1, 2),
      Sorted.emptySet,
      Coin.zero,
      vrf,
      Coin.zero,
      Coin.zero,
      Rational.zero,
      Word64.fromUnsigned(delegators),
      Credential.KeyHashObj[Staking](key[Staking](1))
    )

  test("a likelihood equals its normalisation, as Haskell's Eq does") {
    val a = Likelihood(Vector(LogWeight(1f), LogWeight(2f), LogWeight(3f)))
    val shifted = Likelihood(Vector(LogWeight(11f), LogWeight(12f), LogWeight(13f)))
    val other = Likelihood(Vector(LogWeight(1f), LogWeight(2f), LogWeight(4f)))
    assert(a === shifted)
    assert(a =!= other)
    assertEquals(a.normalised, Likelihood(Vector(LogWeight(0f), LogWeight(1f), LogWeight(2f))))
    assert(Likelihood(Vector.empty) === Likelihood(Vector.empty))
  }

  test("a reward set orders members above leaders, then by pool, and ignores the amount") {
    val p1 = key[StakePool](1)
    val p2 = key[StakePool](2)
    val member1 = Reward(RewardType.MemberReward, p1, coin(5))
    val leader2 = Reward(RewardType.LeaderReward, p2, coin(6))
    val leader1 = Reward(RewardType.LeaderReward, p1, coin(7))
    val member2 = Reward(RewardType.MemberReward, p2, coin(8))
    val set = RewardSet(List(member2, member1, leader2, leader1))
    assertEquals(set.rewards.toList, List(leader1, leader2, member1, member2))
    // the same type and pool is the same element, whatever the amount (the first one stays)
    val twice = RewardSet(List(member1, member1.copy(rewardAmount = coin(99))))
    assertEquals(twice.rewards.size, 1)
    assert(Reward.ordering.compare(member1, member1.copy(rewardAmount = coin(99))) == 0)
    assert(member1 =!= member1.copy(rewardAmount = coin(99)))
  }

  test("the total active stake is the sum, and one lovelace when there is none") {
    val d = StakeWithDelegation(NonZero.from(compact(3)).toOption.get, key[StakePool](1))
    val e = StakeWithDelegation(NonZero.from(compact(4)).toOption.get, key[StakePool](2))
    val active = ActiveStake(
      Sorted.map(
        List(
          Credential.KeyHashObj[Staking](key[Staking](1)) -> d,
          Credential.KeyHashObj[Staking](key[Staking](2)) -> e
        )
      )
    )
    assertEquals(active.total.value.value, coin(7).value)
    assertEquals(SnapShot.empty.totalActiveStake.value.value, BigInt(1))
    assertEquals(PoolDistr.empty.totalActiveStake.value.value, BigInt(1))
  }

  test("the pool distribution of a snapshot leaves out the pools without delegators") {
    val kept = key[StakePool](1)
    val dropped = key[StakePool](2)
    val snap = SnapShot(
      ActiveStake.empty,
      Sorted.map(List(kept -> poolSnap(10, 3), dropped -> poolSnap(20, 0)))
    )
    val distr = PoolDistr.of(snap)
    assertEquals(distr.distribution.keys.toList, List(kept))
    assertEquals(
      distr.distribution(kept),
      IndividualPoolStake(Rational(1, 2), compact(10), vrf)
    )
    assertEquals(SnapShots(snap, SnapShot.empty, SnapShot.empty, Coin.zero).markPoolDistr, distr)
    assertEquals(SnapShots.empty.markPoolDistr, PoolDistr.empty)
  }

  test(
    "the epoch state of each era is the era's, with the stashed AVVM addresses only in Shelley"
  ) {
    val shelleyLedger = LedgerState[Shelley](
      shelleyCert,
      UTxOState[Shelley](
        UtxoTables(Utxo.empty),
        Coin.zero,
        Coin.zero,
        shelleyGov,
        shelleyInstant,
        Coin.zero
      )
    )
    val allegraLedger = LedgerState[Allegra](
      shelleyCert,
      UTxOState[Allegra](
        UtxoTables(Utxo.empty),
        Coin.zero,
        Coin.zero,
        shelleyGov,
        shelleyInstant,
        Coin.zero
      )
    )
    val es = EpochState[Shelley](
      ChainAccountState(Coin.zero, Coin.zero),
      shelleyLedger,
      SnapShots.empty,
      NonMyopic.empty
    )
    val stashed = NewEpochState[Shelley](
      EpochNo.fromUnsigned(0L),
      BlocksMade.empty,
      BlocksMade.empty,
      es,
      None,
      PoolDistr.empty,
      UtxoTables(Utxo.empty)
    )
    assert(stashed === stashed)
    val after = NewEpochState[Allegra](
      EpochNo.fromUnsigned(0L),
      BlocksMade.empty,
      BlocksMade.empty,
      EpochState[Allegra](es.chainAccount, allegraLedger, es.snapshots, es.nonMyopic),
      None,
      PoolDistr.empty,
      ()
    )
    assert(after === after)
    assert(typeCheckErrors("summon[StashedAVVM[Shelley] =:= Unit]").nonEmpty)
    assertEquals(typeCheckErrors("summon[StashedAVVM[Allegra] =:= Unit]"), Nil)
    assert(
      typeCheckErrors(
        "NewEpochState[Allegra](EpochNo.fromUnsigned(0L), BlocksMade.empty, BlocksMade.empty, EpochState[Allegra](es.chainAccount, allegraLedger, es.snapshots, es.nonMyopic), None, PoolDistr.empty, UtxoTables(Utxo.empty))"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "NewEpochState[Shelley](EpochNo.fromUnsigned(0L), BlocksMade.empty, BlocksMade.empty, es, None, PoolDistr.empty, ())"
      ).nonEmpty
    )
  }

  test("a block count map keeps the Haskell order of the pools") {
    val made = BlocksMade(
      Sorted.map(List(key[StakePool](9) -> Word64.zero, key[StakePool](2) -> Word64.max))
    )
    assertEquals(made.blocks.keys.toList, List(key[StakePool](2), key[StakePool](9)))
    assertEquals(BlocksMade.iso.get(BlocksMade.iso.reverseGet(made.blocks)), made.blocks)
  }

  test("the chain account is the pair treasury, reserves") {
    val c = ChainAccountState(coin(3), coin(4))
    assertEquals(ChainAccountState.pair.get(c), (coin(3), coin(4)))
    assertEquals(
      ChainAccountState.pair.reverseGet((coin(1), coin(2))),
      ChainAccountState(coin(1), coin(2))
    )
  }
