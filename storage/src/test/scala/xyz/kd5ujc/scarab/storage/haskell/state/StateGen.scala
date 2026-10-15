// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import org.scalacheck.{Arbitrary, Gen}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.given
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.{*, given}
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.models.runtime.Writer
import xyz.kd5ujc.scarab.plutus.ExUnits

import NodeOrders.given

/** Generators for the codec suites: the generators of the ledger tree, with the parts they leave
  * out (pool relays and metadata, future pool parameters, governance proposals, the parameters of
  * each era) filled in, and the node's own ledger types the mirror reuses.
  */
object StateGen:
  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  /** A CBOR item, written. */
  def item(body: Writer => Unit): Bytes =
    val w = new Writer
    body(w)
    w.result

  // ---- the node's ledger types

  val unit: Gen[Rational] = Gen.oneOf(
    Rational.zero,
    Rational.one,
    Rational(1, 2),
    Rational(3, 10),
    Rational(1, 3),
    Rational(577, 10000)
  )

  val nonNegative: Gen[Rational] =
    Gen.oneOf(unit, Gen.const(Rational(721, 10000000)), Gen.const(Rational(7, 2)))

  def nodeCredential: Gen[node.Credential] =
    Gen.oneOf(
      VocabularyGen.bytes(28).map(node.Credential.Key(_)),
      VocabularyGen.bytes(28).map(node.Credential.Script(_))
    )

  def nodeDRep: Gen[node.DRep] =
    Gen.oneOf(
      nodeCredential.map(node.DRep.Registered(_)),
      Gen.const(node.DRep.AlwaysAbstain),
      Gen.const(node.DRep.AlwaysNoConfidence)
    )

  def rewardAccount: Gen[node.RewardAccount] =
    Gen.zip(Gen.choose(0, 15), nodeCredential).map(node.RewardAccount(_, _))

  def govActionId: Gen[node.GovActionId] =
    Gen
      .zip(VocabularyGen.bytes(32), Gen.oneOf(0, 1, 7, 65535))
      .map((t, i) => node.GovActionId(t, BigInt(i)))

  def nodeAnchor: Gen[node.Anchor] =
    Gen.zip(Gen.oneOf("", "https://example.invalid/a", "x" * 64), VocabularyGen.bytes(32)).map(
      node.Anchor(_, _)
    )

  private def smallMap[K, V](key: Gen[K], value: Gen[V]): Gen[Map[K, V]] =
    Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, Gen.zip(key, value)).map(_.toMap))

  private def smallSet[A](elem: Gen[A]): Gen[Set[A]] =
    Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, elem).map(_.toSet))

  private val epochs: Gen[BigInt] = Gen.oneOf(0L, 1L, 400L, Long.MaxValue).map(BigInt(_))

  private val amounts: Gen[BigInt] =
    Gen.oneOf(BigInt(0), BigInt(1), BigInt(2000000), BigInt(Long.MaxValue), (BigInt(1) << 64) - 1)

  def nodeVote: Gen[node.Vote] = Gen.oneOf(node.Vote.values.toSeq)

  /** A parameter update: indices and values that are CBOR items. */
  def paramUpdate: Gen[node.ParamUpdate] =
    smallMap(Gen.oneOf(0, 1, 3, 17, 30), Gen.choose(0L, 100000L).map(n => item(_.uint(n))))
      .map(node.ParamUpdate(_))

  def previousId: Gen[Option[node.GovActionId]] = Gen.option(govActionId)

  def govAction: Gen[node.GovAction] =
    val policy = Gen.option(VocabularyGen.bytes(28))
    Gen.oneOf(
      Gen.zip(previousId, paramUpdate, policy).map(node.GovAction.ParameterChange(_, _, _)),
      Gen
        .zip(previousId, Gen.choose(0, 13), Gen.choose(0, 5))
        .map((p, a, b) => node.GovAction.HardFork(p, a, b)),
      Gen
        .zip(
          smallMap(rewardAccount, amounts).map(
            _.toVector.sortBy(_._1)(using summon[Order[node.RewardAccount]].toOrdering)
          ),
          policy
        )
        .map(node.GovAction.TreasuryWithdrawals(_, _)),
      previousId.map(node.GovAction.NoConfidence(_)),
      Gen
        .zip(previousId, smallSet(nodeCredential), smallMap(nodeCredential, epochs), unit)
        .map(node.GovAction.UpdateCommittee(_, _, _, _)),
      Gen
        .zip(previousId, policy, nodeAnchor)
        .map(node.GovAction.NewConstitution(_, _, _)),
      Gen.const(node.GovAction.Info)
    )

  def proposal: Gen[node.Proposal] =
    Gen.zip(amounts, rewardAccount, govAction, nodeAnchor).map(node.Proposal(_, _, _, _))

  def govActionState: Gen[node.GovActionState] =
    Gen
      .zip(
        govActionId,
        smallMap(nodeCredential, nodeVote),
        smallMap(nodeCredential, nodeVote),
        smallMap(VocabularyGen.bytes(28), nodeVote),
        proposal,
        epochs,
        epochs
      )
      .map(node.GovActionState(_, _, _, _, _, _, _))

  def prevIds: Gen[node.PrevIds] =
    Gen.zip(previousId, previousId, previousId, previousId).map(node.PrevIds(_, _, _, _))

  def proposals: Gen[node.Proposals] =
    Gen
      .zip(Gen.choose(0, 3).flatMap(Gen.listOfN(_, govActionState)).map(_.toVector), prevIds)
      .map(node.Proposals(_, _))

  def committee: Gen[node.Committee] =
    Gen.zip(smallMap(nodeCredential, epochs), unit).map(node.Committee(_, _))

  def constitution: Gen[node.Constitution] =
    Gen.zip(nodeAnchor, Gen.option(VocabularyGen.bytes(28))).map(node.Constitution(_, _))

  def nodeDRepState: Gen[node.DRepState] =
    Gen
      .zip(amounts, epochs, Gen.option(nodeAnchor), smallSet(nodeCredential))
      .map(node.DRepState(_, _, _, _))

  /** Relays as a registration gives them: a sequence of relays, or none. */
  val relays: Gen[Bytes] = Gen.oneOf(
    Gen.const(Bytes.empty),
    Gen.const(item { w =>
      w.arrayHead(1)
      w.arrayHead(2)
      w.uint(2L)
      w.text("pool.example")
    }),
    Gen.const(item { w =>
      w.arrayHead(2)
      w.arrayHead(4)
      w.uint(0L)
      w.uint(3001L)
      w.nul()
      w.nul()
      w.arrayHead(3)
      w.uint(1L)
      w.nul()
      w.text("relay.example")
    })
  )

  val metadata: Gen[Option[Bytes]] = Gen.option(
    VocabularyGen.bytes(32).map(h =>
      item { w =>
        w.arrayHead(2)
        w.text("https://example.invalid/pool.json")
        w.bytes(h)
      }
    )
  )

  def poolParams: Gen[node.PoolParams] =
    Gen
      .zip(
        VocabularyGen.bytes(28),
        VocabularyGen.bytes(32),
        amounts,
        amounts,
        unit,
        rewardAccount,
        smallSet(VocabularyGen.bytes(28)),
        relays,
        metadata
      )
      .map(node.PoolParams(_, _, _, _, _, _, _, _, _))

  def pulsingSnapshot: Gen[node.PulsingSnapshot] =
    Gen
      .zip(
        Gen.choose(0, 2).flatMap(Gen.listOfN(_, govActionState)).map(_.toVector),
        smallMap(nodeDRep, amounts),
        smallMap(nodeCredential, nodeDRepState),
        smallMap(VocabularyGen.bytes(28), amounts)
      )
      .map(node.PulsingSnapshot(_, _, _, _))

  // ---- protocol parameters

  private val costModels: Gen[Map[Int, Vector[BigInt]]] =
    val values = Gen.oneOf(
      Gen.choose(0, 4).flatMap(Gen.listOfN(_, Gen.choose(-5L, 5L))),
      Gen.listOfN(24, Gen.choose(Long.MinValue, Long.MaxValue)),
      Gen.listOfN(166, Gen.choose(-100000L, 100000L))
    )
    smallMap(Gen.choose(0, 2), values.map(_.map(BigInt(_)).toVector))

  /** Parameters with every field set, so each era's codec has something to drop. */
  val fullParams: Gen[node.ProtocolParams] =
    def w(bits: Int): Gen[BigInt] =
      Gen.oneOf(BigInt(0), BigInt(1), (BigInt(1) << bits) - 1, BigInt(2) << (bits - 4))
    val first = Gen.zip(w(64), w(64), w(32), w(32), w(16), w(64), w(64), w(32), w(16), nonNegative)
    val second = Gen.zip(unit, unit, unit, Gen.oneOf(node.Nonce.Neutral, node.Nonce.Hash(Bytes(Vector.fill(32)(7.toByte)))))
    val third = Gen.zip(Gen.choose(0, 13), w(32), w(64), w(64), w(64), costModels, nonNegative, nonNegative)
    val fourth = Gen.zip(w(63), w(63), w(63), w(63), w(32), w(16), w(16))
    val fifth = Gen.zip(
      Gen.listOfN(5, unit),
      Gen.listOfN(10, unit),
      w(16),
      w(32),
      w(32),
      w(64),
      w(64),
      w(32),
      nonNegative
    )
    for
      (a, b, c, d, e, f, g, h, i, a0) <- first
      (rho, tau, dd, entropy) <- second
      (major, minor, minUTxO, minPool, perWord, models, pm, ps) <- third
      (txMem, txSteps, blkMem, blkSteps, maxVal, coll, maxColl) <- fourth
      (pool, drep, cmin, cterm, life, gdep, ddep, dact, refCost) <- fifth
      perByte <- w(64)
    yield node.ProtocolParams(
      minFeeA = a,
      minFeeB = b,
      maxTxSize = d,
      maxBlockBodySize = c,
      maxValueSize = maxVal,
      coinsPerUTxOByte = perByte,
      keyDeposit = f,
      poolDeposit = g,
      eMax = h,
      nOpt = i,
      a0 = a0,
      rho = rho,
      tau = tau,
      minPoolCost = minPool,
      protocolMajor = major,
      drepDeposit = ddep,
      drepActivity = dact,
      govActionDeposit = gdep,
      govActionLifetime = life,
      minFeeRefScriptCostPerByte = refCost,
      protocolMinor = minor,
      maxBlockHeaderSize = e,
      d = dd,
      extraEntropy = entropy,
      minUTxOValue = minUTxO,
      coinsPerUTxOWord = perWord,
      priceMem = pm,
      priceSteps = ps,
      maxTxExUnits = ExUnits(txMem, txSteps),
      maxBlockExUnits = ExUnits(blkMem, blkSteps),
      collateralPercentage = coll,
      maxCollateralInputs = maxColl,
      costModels = models,
      poolVotingThresholds = node.PoolVotingThresholds.fromVector(pool.toVector).toOption.get,
      drepVotingThresholds = node.DRepVotingThresholds.fromVector(drep.toVector).toOption.get,
      committeeMinSize = cmin,
      committeeMaxTermLength = cterm
    )

  /** The parameters an era has, the others at the value the codec reads an absent one as: written
    * out per era here, from the lists in the ledger's `eraPParams`, and not from the codec.
    */
  def paramsOf[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[node.ProtocolParams] =
    val none = Params.empty
    fullParams.map { p =>
      val shelleyOnly = (q: node.ProtocolParams) =>
        q.copy(
          coinsPerUTxOWord = none.coinsPerUTxOWord,
          coinsPerUTxOByte = none.coinsPerUTxOByte,
          costModels = none.costModels,
          priceMem = none.priceMem,
          priceSteps = none.priceSteps,
          maxTxExUnits = none.maxTxExUnits,
          maxBlockExUnits = none.maxBlockExUnits,
          maxValueSize = none.maxValueSize,
          collateralPercentage = none.collateralPercentage,
          maxCollateralInputs = none.maxCollateralInputs
        )
      val noGovernance = (q: node.ProtocolParams) =>
        q.copy(
          poolVotingThresholds = none.poolVotingThresholds,
          drepVotingThresholds = none.drepVotingThresholds,
          committeeMinSize = none.committeeMinSize,
          committeeMaxTermLength = none.committeeMaxTermLength,
          govActionLifetime = none.govActionLifetime,
          govActionDeposit = none.govActionDeposit,
          drepDeposit = none.drepDeposit,
          drepActivity = none.drepActivity,
          minFeeRefScriptCostPerByte = none.minFeeRefScriptCostPerByte
        )
      val noDecentralisation = (q: node.ProtocolParams) =>
        q.copy(d = none.d, extraEntropy = none.extraEntropy)
      era match
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary =>
          noGovernance(shelleyOnly(p)).copy(coinsPerUTxOByte = none.coinsPerUTxOByte)
        case ShelleyBasedEra.SAlonzo =>
          noGovernance(p).copy(minUTxOValue = none.minUTxOValue, coinsPerUTxOByte = none.coinsPerUTxOByte)
        case ShelleyBasedEra.SBabbage =>
          noDecentralisation(noGovernance(p)).copy(
            minUTxOValue = none.minUTxOValue,
            coinsPerUTxOWord = none.coinsPerUTxOWord
          )
        case ShelleyBasedEra.SConway =>
          noDecentralisation(p).copy(
            minUTxOValue = none.minUTxOValue,
            coinsPerUTxOWord = none.coinsPerUTxOWord
          )
    }

  // ---- governance states

  def futurePParams[E <: EraTag](using ShelleyBasedEra[E]): Gen[FuturePParams] =
    Gen.oneOf(
      Gen.const(FuturePParams.NoPParamsUpdate),
      paramsOf[E].map(FuturePParams.DefinitePParamsUpdate(_)),
      Gen.const(FuturePParams.PotentialPParamsUpdate(None)),
      paramsOf[E].map(p => FuturePParams.PotentialPParamsUpdate(Some(p)))
    )

  def enactState: Gen[node.EnactState] =
    Gen
      .zip(
        Gen.option(committee),
        constitution,
        paramsOf[Conway],
        paramsOf[Conway],
        amounts,
        smallMap(nodeCredential, amounts),
        prevIds
      )
      .map(node.EnactState(_, _, _, _, _, _, _))

  def ratifyState: Gen[node.RatifyState] =
    Gen
      .zip(
        enactState,
        Gen.choose(0, 2).flatMap(Gen.listOfN(_, govActionState)).map(_.toVector),
        smallSet(govActionId),
        Gen.oneOf(true, false)
      )
      .map(node.RatifyState(_, _, _, _))

  def conwayGov: Gen[ConwayGovState] =
    Gen
      .zip(
        proposals,
        Gen.option(committee),
        constitution,
        paramsOf[Conway],
        paramsOf[Conway],
        futurePParams[Conway],
        Gen.zip(pulsingSnapshot, ratifyState).map(DRComplete(_, _))
      )
      .map(ConwayGovState(_, _, _, _, _, _, _))

  def shelleyGov[E <: EraTag](using ShelleyBasedEra[E]): Gen[ShelleyGovState] =
    val updates = sortedMap(arb[KeyHash[Genesis]], paramUpdate)
    Gen
      .zip(updates, updates, paramsOf[E], paramsOf[E], futurePParams[E])
      .map(ShelleyGovState(_, _, _, _, _))

  def govState[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[GovState[E]] = era match
    case ShelleyBasedEra.SConway => conwayGov
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      shelleyGov[E]

  // ---- certificate state

  private def sortedMap[K: Order, V](key: Gen[K], value: Gen[V]): Gen[SortedMap[K, V]] =
    LedgerStateGen.sortedMap(key, value)

  def stakePoolState: Gen[StakePoolState] =
    Gen
      .zip(
        Gen.zip(arb[VRFVerKeyHash], arb[Coin], arb[Coin], unit, arb[Credential[Staking]]),
        sortedSet(arb[KeyHash[Staking]]),
        relays,
        metadata,
        arb[CompactCoin],
        sortedSet(arb[Credential[Staking]])
      )
      .map { case ((vrf, pledge, cost, margin, account), owners, relays, meta, deposit, delegators) =>
        StakePoolState(vrf, pledge, cost, margin, account, owners, relays, meta, deposit, delegators)
      }

  def pState: Gen[PState] =
    Gen
      .zip(
        sortedMap(arb[VRFVerKeyHash], arb[NonZero[Word64]]),
        sortedMap(arb[KeyHash[StakePool]], stakePoolState),
        sortedMap(arb[KeyHash[StakePool]], poolParams),
        sortedMap(arb[KeyHash[StakePool]], arb[EpochNo])
      )
      .map(PState(_, _, _, _))

  def certState[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[CertState[E]] = era match
    case ShelleyBasedEra.SConway =>
      Gen
        .zip(arb[VState], pState, dState(arb[ConwayAccounts]))
        .map(ConwayCertState(_, _, _))
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      Gen.zip(pState, dState(arb[ShelleyAccounts])).map(ShelleyCertState(_, _))

  def instantStake[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[InstantStake[E]] = era match
    case ShelleyBasedEra.SConway => arb[ConwayInstantStake]
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      arb[ShelleyInstantStake]

  // ---- the states of an era

  def ledgerState[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[LedgerState[E]] =
    Gen
      .zip(certState[E], utxoState[E](govState[E], instantStake[E]))
      .map(LedgerState[E](_, _))

  /** Snapshots whose delegator counts are what Haskell's `Int` holds: the mirror keeps the count in
    * a word, the generators of the ledger tree use all of it.
    */
  val snapShots: Gen[SnapShots] =
    def cap(s: SnapShot): SnapShot = s.copy(pools =
      Sorted.map(s.pools.iterator.map((k, p) =>
        k -> p.copy(numDelegators = Word64.fromUnsigned(p.numDelegators.bits & Long.MaxValue))
      ))
    )
    arb[SnapShots].map(x => SnapShots(cap(x.mark), cap(x.set), cap(x.go), x.fee))

  def epochState[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[EpochState[E]] =
    Gen
      .zip(arb[ChainAccountState], ledgerState[E], snapShots, arb[NonMyopic])
      .map(EpochState[E](_, _, _, _))

  /** A reward update that is complete: a pulsing one cannot be written. */
  val rewardUpdate: Gen[PulsingRewUpdate] =
    arb[RewardUpdate].map(PulsingRewUpdate.Complete(_))

  def newEpochState[E <: EraTag](using era: ShelleyBasedEra[E]): Gen[NewEpochState[E]] =
    Gen
      .zip(
        arb[EpochNo],
        arb[BlocksMade],
        arb[BlocksMade],
        epochState[E],
        Gen.option(rewardUpdate),
        arb[PoolDistr]
      )
      .map((e, bp, bc, es, ru, pd) => NewEpochState[E](e, bp, bc, es, ru, pd, Empty.stashedAvvm[E]))

  /** Words as a set (for the sets of the tests). */
  def sets[A: Order](elem: Gen[A]): Gen[SortedSet[A]] = LedgerStateGen.sortedSet(elem)
