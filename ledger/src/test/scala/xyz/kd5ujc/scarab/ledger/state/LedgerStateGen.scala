// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Order
import org.scalacheck.{Arbitrary, Gen}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  EnactState,
  GenDelegPair,
  PoolParams,
  PrevIds,
  Proposals,
  PulsingSnapshot,
  Ptr,
  RatifyState,
  TestLedger,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.ledger.GovState as NodeGovState

/** Values and generators of the ledger tree. Maps hold a few entries (empty included) so that the
  * order of a `SortedMap` and the sharing of structure between copies are both exercised; the
  * protocol parameters are one fixed value (their lenses are not what is under test).
  */
object LedgerStateGen:
  val params = TestLedger.params(10)

  val enact: EnactState = EnactState(
    Some(Committee(Map.empty, Rational.zero)),
    Constitution(NodeGovState.noAnchor, None),
    params,
    params,
    BigInt(0),
    Map.empty,
    PrevIds()
  )

  val pulsing: DRComplete =
    DRComplete(PulsingSnapshot.empty, RatifyState(enact, Vector.empty, Set.empty, delayed = false))

  // The empty value of each record: the cheapest inhabitant of each side of the era split.
  val emptyPState: PState = PState(Sorted.empty, Sorted.empty, Sorted.empty, Sorted.empty)
  val emptyRewards: InstantaneousRewards =
    InstantaneousRewards(Sorted.empty, Sorted.empty, DeltaCoin(BigInt(0)), DeltaCoin(BigInt(0)))
  val emptyVState: VState =
    VState(Sorted.empty, CommitteeState(Sorted.empty), EpochNo.fromUnsigned(0L))

  val shelleyAccounts: ShelleyAccounts = ShelleyAccounts(Sorted.empty, Sorted.empty)
  val conwayAccounts: ConwayAccounts = ConwayAccounts(Sorted.empty)
  val shelleyInstant: ShelleyInstantStake = ShelleyInstantStake(Sorted.empty, Sorted.empty)
  val conwayInstant: ConwayInstantStake = ConwayInstantStake(Sorted.empty)

  val shelleyCert: ShelleyCertState =
    ShelleyCertState(emptyPState, DState(shelleyAccounts, Sorted.empty, Sorted.empty, emptyRewards))
  val conwayCert: ConwayCertState =
    ConwayCertState(
      emptyVState,
      emptyPState,
      DState(conwayAccounts, Sorted.empty, Sorted.empty, emptyRewards)
    )

  val conwayGov: ConwayGovState = ConwayGovState(
    Proposals.empty,
    None,
    enact.constitution,
    params,
    params,
    FuturePParams.NoPParamsUpdate,
    pulsing
  )
  val shelleyGov: ShelleyGovState =
    ShelleyGovState(Sorted.empty, Sorted.empty, params, params, FuturePParams.NoPParamsUpdate)

  def sortedMap[K: Order, V](key: Gen[K], value: Gen[V]): Gen[SortedMap[K, V]] =
    Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, Gen.zip(key, value)).map(Sorted.map(_)))

  def sortedSet[K: Order](key: Gen[K]): Gen[SortedSet[K]] =
    Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, key).map(Sorted.set(_)))

  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  val ptr: Gen[Ptr] =
    Gen
      .zip(Gen.choose(0L, 3L), Gen.choose(0L, 3L), Gen.choose(0L, 3L))
      .map((a, b, c) => Ptr(a, b, c))

  given Arbitrary[Ptr] = Arbitrary(ptr)
  given Arbitrary[Anchor] = Arbitrary(
    arb[Hash32].map(h => Anchor("https://example.invalid/a", Hash32.bytes(h)))
  )

  given Arbitrary[ShelleyAccountState] = Arbitrary(
    Gen.zip(ptr, arb[CompactCoin], arb[CompactCoin], Gen.option(arb[KeyHash[StakePool]])).map {
      (p, b, d, pool) => ShelleyAccountState(p, b, d, pool)
    }
  )

  given Arbitrary[ConwayAccountState] = Arbitrary(
    Gen
      .zip(
        arb[CompactCoin],
        arb[CompactCoin],
        Gen.option(arb[KeyHash[StakePool]]),
        Gen.option(arb[DRep])
      )
      .map((b, d, pool, drep) => ConwayAccountState(b, d, pool, drep))
  )

  given Arbitrary[ShelleyAccounts] = Arbitrary(
    Gen
      .zip(
        sortedMap(arb[Credential[Staking]], arb[ShelleyAccountState]),
        sortedMap(ptr, arb[Credential[Staking]])
      )
      .map(ShelleyAccounts(_, _))
  )

  given Arbitrary[ConwayAccounts] = Arbitrary(
    sortedMap(arb[Credential[Staking]], arb[ConwayAccountState]).map(ConwayAccounts(_))
  )

  given Arbitrary[ShelleyInstantStake] = Arbitrary(
    Gen
      .zip(sortedMap(arb[Credential[Staking]], arb[CompactCoin]), sortedMap(ptr, arb[CompactCoin]))
      .map(ShelleyInstantStake(_, _))
  )

  given Arbitrary[ConwayInstantStake] = Arbitrary(
    sortedMap(arb[Credential[Staking]], arb[CompactCoin]).map(ConwayInstantStake(_))
  )

  given Arbitrary[FutureGenDeleg] = Arbitrary(
    Gen.zip(arb[SlotNo], arb[KeyHash[Genesis]]).map(FutureGenDeleg(_, _))
  )

  private val genDelegPair: Gen[GenDelegPair] =
    Gen
      .zip(arb[KeyHash[GenesisDelegate]], arb[VRFVerKeyHash])
      .map((d, v) => GenDelegPair(KeyHash.bytes(d), VRFVerKeyHash.bytes(v)))

  given Arbitrary[InstantaneousRewards] = Arbitrary(
    Gen
      .zip(
        sortedMap(arb[Credential[Staking]], arb[Coin]),
        sortedMap(arb[Credential[Staking]], arb[Coin]),
        arb[DeltaCoin],
        arb[DeltaCoin]
      )
      .map(InstantaneousRewards(_, _, _, _))
  )

  def dState[A <: AccountsRecord](accounts: Gen[A]): Gen[DState[A]] =
    Gen
      .zip(
        accounts,
        sortedMap(arb[FutureGenDeleg], genDelegPair),
        sortedMap(arb[KeyHash[Genesis]], genDelegPair),
        arb[InstantaneousRewards]
      )
      .map(DState(_, _, _, _))

  given Arbitrary[StakePoolState] = Arbitrary(
    Gen
      .zip(
        arb[VRFVerKeyHash],
        arb[Coin],
        arb[Coin],
        Gen.oneOf(Rational.zero, Rational(1, 2), Rational(1)),
        arb[Credential[Staking]],
        sortedSet(arb[KeyHash[Staking]]),
        arb[CompactCoin],
        sortedSet(arb[Credential[Staking]])
      )
      .map((vrf, pledge, cost, margin, acct, owners, deposit, delegators) =>
        StakePoolState(
          vrf,
          pledge,
          cost,
          margin,
          acct,
          owners,
          Bytes.empty,
          None,
          deposit,
          delegators
        )
      )
  )

  given Arbitrary[PState] = Arbitrary(
    Gen
      .zip(
        sortedMap(arb[VRFVerKeyHash], arb[NonZero[Word64]]),
        sortedMap(arb[KeyHash[StakePool]], arb[StakePoolState]),
        Gen.const(Sorted.empty[KeyHash[StakePool], PoolParams]),
        sortedMap(arb[KeyHash[StakePool]], arb[EpochNo])
      )
      .map(PState(_, _, _, _))
  )

  given Arbitrary[DRepState] = Arbitrary(
    Gen
      .zip(
        arb[EpochNo],
        Gen.option(arb[Anchor]),
        arb[CompactCoin],
        sortedSet(arb[Credential[Staking]])
      )
      .map(DRepState(_, _, _, _))
  )

  given Arbitrary[CommitteeAuthorization] = Arbitrary(
    Gen.oneOf(
      arb[Credential[HotCommittee]].map(CommitteeAuthorization.CommitteeHotCredential(_)),
      Gen.option(arb[Anchor]).map(CommitteeAuthorization.CommitteeMemberResigned(_))
    )
  )

  given Arbitrary[VState] = Arbitrary(
    Gen
      .zip(
        sortedMap(arb[Credential[DRepRole]], arb[DRepState]),
        sortedMap(arb[Credential[ColdCommittee]], arb[CommitteeAuthorization])
          .map(CommitteeState(_)),
        arb[EpochNo]
      )
      .map(VState(_, _, _))
  )

  given Arbitrary[FuturePParams] = Arbitrary(
    Gen.oneOf(
      Gen.const(FuturePParams.NoPParamsUpdate),
      Gen.const(FuturePParams.DefinitePParamsUpdate(params)),
      Gen.const(FuturePParams.PotentialPParamsUpdate(None)),
      Gen.const(FuturePParams.PotentialPParamsUpdate(Some(params)))
    )
  )

  given Arbitrary[ConwayGovState] = Arbitrary(
    arb[FuturePParams].map(f =>
      ConwayGovState(
        Proposals.empty,
        None,
        enact.constitution,
        params,
        params,
        f,
        pulsing
      )
    )
  )

  given Arbitrary[ShelleyGovState] = Arbitrary(
    arb[FuturePParams].map(f => ShelleyGovState(Sorted.empty, Sorted.empty, params, params, f))
  )

  given Arbitrary[UtxoTables] = Arbitrary(Gen.const(UtxoTables(Utxo.empty)))

  given Arbitrary[ConwayCertState] = Arbitrary(
    Gen
      .zip(arb[VState], arb[PState], dState(arb[ConwayAccounts]))
      .map(ConwayCertState(_, _, _))
  )

  given Arbitrary[ShelleyCertState] = Arbitrary(
    Gen.zip(arb[PState], dState(arb[ShelleyAccounts])).map(ShelleyCertState(_, _))
  )

  def utxoState[E <: EraTag](
      gov: Gen[GovState[E]],
      stake: Gen[InstantStake[E]]
  ): Gen[UTxOState[E]] =
    Gen
      .zip(arb[UtxoTables], arb[Coin], arb[Coin], gov, stake, arb[Coin])
      .map((u, dep, fees, g, s, don) => UTxOState[E](u, dep, fees, g, s, don))

  given poolsMap: Arbitrary[SortedMap[KeyHash[StakePool], StakePoolState]] = Arbitrary(
    sortedMap(arb[KeyHash[StakePool]], arb[StakePoolState])
  )

  given drepsMap: Arbitrary[SortedMap[Credential[DRepRole], DRepState]] = Arbitrary(
    sortedMap(arb[Credential[DRepRole]], arb[DRepState])
  )

  given conwayUtxoState: Arbitrary[UTxOState[Conway]] = Arbitrary(
    utxoState[Conway](arb[ConwayGovState], arb[ConwayInstantStake])
  )

  given conwayLedger: Arbitrary[LedgerState[Conway]] = Arbitrary(
    Gen
      .zip(arb[ConwayCertState], utxoState[Conway](arb[ConwayGovState], arb[ConwayInstantStake]))
      .map(LedgerState[Conway](_, _))
  )

  given babbageLedger: Arbitrary[LedgerState[Babbage]] = Arbitrary(
    Gen
      .zip(
        arb[ShelleyCertState],
        utxoState[Babbage](arb[ShelleyGovState], arb[ShelleyInstantStake])
      )
      .map(LedgerState[Babbage](_, _))
  )
