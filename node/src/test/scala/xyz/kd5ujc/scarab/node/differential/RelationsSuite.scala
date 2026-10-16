// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.Show
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.state.*

/** The places where the harness takes the new side's state to the old side's terms (`Relations`):
  * each is a relation that holds, and a difference that is not one is still found.
  */
class RelationsSuite extends FunSuite:
  private def ok[A](e: Either[VocabError, A]): A = e.fold(v => fail(s"vocabulary: $v"), identity)

  private def bytes(n: Int, size: Int): Bytes = Bytes.fromArray(Array.fill[Byte](size)(n.toByte))

  private val params = TestLedger.params(10)
  private val empty: NewEpochState[Conway] =
    NewEpochState.empty[Conway](ok(EpochNo.fromBigInt(5)), params)

  private def coin(n: BigInt): Coin = ok(Coin.fromBigInt(n))
  private def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))

  private val pool: KeyHash[StakePool] = ok(KeyHash.fromBytes[StakePool](bytes(1, 28)))
  private val owner: KeyHash[Staking] = ok(KeyHash.fromBytes[Staking](bytes(2, 28)))
  private val staker: Credential[Staking] = Credential.KeyHashObj[Staking](owner)

  private def poolSnapshot(delegators: Int, owners: Set[KeyHash[Staking]]): StakePoolSnapShot =
    StakePoolSnapShot(
      compact(1000),
      Rational(1, 2),
      Sorted.set(owners),
      coin(0),
      ok(VRFVerKeyHash.fromBytes(bytes(3, 32))),
      coin(0),
      coin(0),
      Rational.zero,
      Word64.fromUnsigned(delegators.toLong),
      staker
    )

  private def snapshot(p: StakePoolSnapShot): SnapShot =
    SnapShot(ActiveStake.empty, Sorted.map(List(pool -> p)))

  private def withSet(nes: NewEpochState[Conway], p: StakePoolSnapShot): NewEpochState[Conway] =
    nes.copy(es = nes.es.copy(snapshots = nes.es.snapshots.copy(set = snapshot(p))))

  private def withGo(nes: NewEpochState[Conway], p: StakePoolSnapShot): NewEpochState[Conway] =
    nes.copy(es = nes.es.copy(snapshots = nes.es.snapshots.copy(go = snapshot(p))))

  private def compare(
      old: NewEpochState[Conway],
      neu: NewEpochState[Conway],
      amounts: Map[node.Credential, BigInt] = Map.empty,
      started: Boolean = true
  ) = ok(Relations.compare(old, amounts, neu, started, handover = false))

  test("equal states have no difference, and a difference is found with its path") {
    assertEquals(compare(empty, empty), None)
    val richer =
      empty.copy(es = empty.es.copy(chainAccount = empty.es.chainAccount.copy(treasury = coin(7))))
    assertEquals(compare(empty, richer).map(_.path.render), Some("es.chainAccount.treasury"))
  }

  test(
    "the reward update: the sums per credential are the old amounts, the update is compared without them"
  ) {
    val update =
      RewardUpdate(DeltaCoin(5), DeltaCoin(-5), Sorted.empty, DeltaCoin(0), NonMyopic.empty)
    val old = empty.copy(ru = Some(PulsingRewUpdate.Complete(update)))
    val amounts = Map[node.Credential, BigInt](node.Credential.Key(owner.bytes) -> BigInt(42))
    val seeded = ok(Relations.seed(old, amounts))
    assertEquals(
      seeded.ru.collect { case PulsingRewUpdate.Complete(u) => u.rs.size },
      Some(1),
      "the seed attributes the amounts"
    )
    assertEquals(compare(old, seeded, amounts), None)
    // other amounts are a difference, in the rewards
    val other = Map[node.Credential, BigInt](node.Credential.Key(owner.bytes) -> BigInt(43))
    assertEquals(compare(old, seeded, other).map(_.path.render), Some("ru.rs"))
  }

  test("an old update before the rewards start is not compared") {
    val update =
      RewardUpdate(DeltaCoin(5), DeltaCoin(-5), Sorted.empty, DeltaCoin(0), NonMyopic.empty)
    val old = empty.copy(ru = Some(PulsingRewUpdate.Complete(update)))
    assertEquals(compare(old, empty, started = false), None)
    assertEquals(compare(old, empty, started = true).map(_.path.render), Some("ru"))
  }

  test("the delegators of an older snapshot: the new count is at least the old one") {
    val old = withGo(empty, poolSnapshot(1, Set.empty))
    // a zero-stake delegator, and an owner who delegates with no stake: the old mirror cannot see them
    val exact = withGo(empty, poolSnapshot(3, Set(owner)))
    assertEquals(compare(old, exact), None)
    // a smaller count is a difference
    val lower = withGo(empty, poolSnapshot(0, Set.empty))
    assertEquals(
      compare(old, lower).map(_.path.render),
      Some("es.snapshots.go.pools[" + Show[KeyHash[StakePool]].show(pool) + "].numDelegators")
    )
  }

  test("the set snapshot is the old side's where the pool's stake agrees") {
    val old = withSet(empty, poolSnapshot(1, Set.empty))
    // the seed derived the delegators from the accounts as they are, the snapshot as it was
    assertEquals(compare(old, withSet(empty, poolSnapshot(3, Set(owner)))), None)
    assertEquals(compare(old, withSet(empty, poolSnapshot(0, Set.empty))), None)
    // a pool of another stake is not the same pool
    val other = poolSnapshot(1, Set.empty).copy(stake = compact(999))
    assert(compare(old, withSet(empty, other)).isDefined)
  }

  test("the prediction of the first block of a chain is not compared, any later one is") {
    def withFuture(f: FuturePParams): NewEpochState[Conway] =
      empty.copy(es =
        empty.es.copy(ls =
          empty.es.ls.copy[Conway](utxo =
            empty.es.ls.utxo.copy(gov = empty.es.ls.utxo.gov.copy(futurePParams = f))
          )
        )
      )
    val old = withFuture(FuturePParams.PotentialPParamsUpdate(None))
    val neu = withFuture(FuturePParams.NoPParamsUpdate)
    assertEquals(
      ok(Relations.compare(old, Map.empty, neu, rewardsStarted = true, handover = true)),
      None
    )
    assertEquals(
      ok(Relations.compare(old, Map.empty, neu, rewardsStarted = true, handover = false))
        .map(_.path.render),
      Some("es.ls.utxo.gov.futurePParams")
    )
  }

  test("the cost per UTxO byte is not compared before Babbage") {
    val shelley =
      NewEpochState.empty[Shelley](ok(EpochNo.fromBigInt(5)), params.copy(coinsPerUTxOWord = 0))
    val other = NewEpochState.empty[Shelley](
      ok(EpochNo.fromBigInt(5)),
      params.copy(coinsPerUTxOWord = 0, coinsPerUTxOByte = 1)
    )
    assertEquals(ok(Relations.compare(shelley, Map.empty, other, true, handover = false)), None)
    // but is in Babbage, where the parameters have the field
    val babbage = NewEpochState.empty[Babbage](ok(EpochNo.fromBigInt(5)), params)
    val babbageOther =
      NewEpochState.empty[Babbage](ok(EpochNo.fromBigInt(5)), params.copy(coinsPerUTxOByte = 1))
    assert(
      ok(Relations.compare(babbage, Map.empty, babbageOther, true, handover = false)).isDefined
    )
  }
