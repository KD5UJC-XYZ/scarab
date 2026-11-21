// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.ledger.DRepState as FlatDRepState
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayRatify
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  Credential,
  DRep,
  DRepRole,
  DRepState,
  EpochNo,
  EpochState,
  EraTag,
  InstantStake,
  NonZero,
  PoolDistr,
  Sorted,
  Staking,
  VocabError
}

/** The DRep pulser of the eras from Conway on the era types (cardano-ledger 9df9f98c
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/DRepPulser.hs` and
  * `Conway/Governance.hs:460-520`): the distributions the pulser computes and the ratification it
  * reaches. The reference spreads the computation over the epoch and keeps the pulser running
  * (`DRPulsing`) until it is forced; the node holds it only complete (`DRComplete`,
  * [[DRepPulsingState]]), so it is computed to completion where it starts, which is what forcing it
  * at once gives (`finishDRepPulser` of a fresh pulser, its index zero and its partial distribution
  * empty, `:388-430`).
  */
object DRepPulser:
  /** `toVotingStakePoolDistr` (`Governance/Internal.hs:741-746`, cardano-ledger 9df9f98c): each
    * pool's stake of the distribution and its total.
    */
  def toVotingStakePoolDistr(pd: PoolDistr): VotingStakePoolDistr =
    VotingStakePoolDistr(
      Sorted.map(pd.distribution.iterator.map((pool, ips) => pool -> ips.totalPoolStake)),
      pd.totalActiveStake
    )

  /** `computeDRepDistr` (`DRepPulser.hs:200-242`, cardano-ledger 9df9f98c), over every account in
    * the order of its credential (`Map.foldlWithKey'`): an account's instant stake and the deposits
    * of the proposals it is the return account of, with its balance, go to the DRep it delegates
    * to, if that DRep is registered or is one of the two predefined; the deposits go to the pool it
    * delegates to, if the distribution holds that pool. The sums are `CompactForm Coin` 's `<>`,
    * which wraps; the pools' total is a `Coin` 's.
    */
  def computeDRepDistr[E <: EraTag](
      instantStake: InstantStake[E],
      regDReps: SortedMap[Credential[DRepRole], DRepState],
      proposalDeposits: SortedMap[Credential[Staking], CompactCoin],
      vpDistr: VotingStakePoolDistr,
      dRepDistr: Map[DRep, CompactCoin],
      accounts: SortedMap[Credential[Staking], AccountState[E]]
  )(using
      s: EraStake[E],
      a: ConwayEraAccounts[E]
  ): (Map[DRep, CompactCoin], VotingStakePoolDistr) =
    val instantStakeCredentials = s.instantStakeCredentialsL.get(instantStake)
    def addToPoolDistr(
        accountState: AccountState[E],
        mProposalDeposit: Option[CompactCoin],
        distr: VotingStakePoolDistr
    ): VotingStakePoolDistr =
      (for
        stakePool <- a.stakePoolDelegationAccountStateL.get(accountState)
        proposalDeposit <- mProposalDeposit
        ips <- distr.vspdIndividualStake.get(stakePool)
      yield VotingStakePoolDistr(
        distr.vspdIndividualStake.updated(stakePool, ips.add(proposalDeposit)),
        NonZero.unsafe(
          Coin(distr.vspdTotalVotingStake.value.value + proposalDeposit.toBigInt)
        )
      )).getOrElse(distr)
    def addToDRepDistr(
        accountState: AccountState[E],
        stakeAndDeposits: CompactCoin,
        distr: Map[DRep, CompactCoin]
    ): Map[DRep, CompactCoin] =
      a.dRepDelegationAccountStateL.get(accountState).fold(distr) { dRep =>
        val balance = a.balanceAccountStateL.get(accountState)
        val stake = stakeAndDeposits.add(balance)
        val updatedDistr = distr.updated(dRep, distr.get(dRep).fold(stake)(stake.add))
        ConwayEraCertState.dRepToCred(dRep) match
          case None                                  => updatedDistr
          case Some(cred) if regDReps.contains(cred) => updatedDistr
          case Some(_)                               => distr
      }
    accounts.foldLeft((dRepDistr, vpDistr)) {
      case ((drepAccum, poolAccum), (stakeCred, accountState)) =>
        val mInstantStake = instantStakeCredentials.get(stakeCred)
        val mProposalDeposit = proposalDeposits.get(stakeCred)
        val stakeAndDeposits =
          (mInstantStake.iterator ++ mProposalDeposit.iterator).foldLeft(CompactCoin.zero)(_.add(_))
        (
          addToDRepDistr(accountState, stakeAndDeposits, drepAccum),
          addToPoolDistr(accountState, mProposalDeposit, poolAccum)
        )
    }

  /** The DReps' states as the pulser's snapshot holds them, the node's flat `DRepState`
    * ([[PulsingSnapshot]]).
    */
  private def flatDRepStates(
      dreps: SortedMap[Credential[DRepRole], DRepState]
  ): Map[xyz.kd5ujc.scarab.ledger.Credential, FlatDRepState] =
    dreps.iterator
      .map((c, d) =>
        Convert.back(c) -> FlatDRepState(
          d.deposit.toBigInt,
          d.expiry.toBigInt,
          d.anchor,
          d.delegators.iterator.map(Convert.back).toSet
        )
      )
      .toMap

  /** `setFreshDRepPulsingState` (`Conway/Governance.hs:460-520`, cardano-ledger 9df9f98c), the last
    * step of EPOCH: the pulser of the epoch `epochNo` the chain enters, over the epoch state
    * `epochState` (its accounts, instant stake, DReps, committee state and pools; its governance
    * record is not read) and the pool distribution `stakePoolDistr`, its enact state `mkEnactState`
    * of `govState` with the treasury, and its proposals `govState` 's; then `predictFuturePParams`.
    * Completed at once (`finishDRepPulser`, `DRepPulser.hs:388-430`): the distributions over every
    * account, RATIFY over the actions in priority order (`reorderActions`, `runConwayRatify`,
    * `:365-386`), and the snapshot of the actions, the DRep distribution, the DReps' states and
    * each pool's voting stake.
    */
  def setFreshDRepPulsingState[E <: EraTag](
      epochNo: EpochNo,
      stakePoolDistr: PoolDistr,
      epochState: EpochState[E],
      govState: ConwayGovState[E]
  )(using
      c: ConwayEraCertState[E],
      s: EraStake[E],
      p: ConwayEraPParams[E]
  ): Either[VocabError, ConwayGovState[E]] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val ledgerState = epochState.ls
    val instantStake = ledgerState.utxo.instantStake
    val certState = ledgerState.cert
    val accounts = c.certDStateL.get(certState).accounts
    val vState = c.certVStateL.get(certState)
    val props = govState.cgsProposals
    val dpProposals = Proposals.proposalsActions(props)
    val dpEnactState =
      ConwayGovState.mkEnactState(govState).copy(ensTreasury = epochState.chainAccount.treasury)
    for
      dpProposalDeposits <- Proposals.proposalsDeposits(props)
      (finalDRepDistr, finalVotingStakePoolDistr) = computeDRepDistr[E](
        instantStake,
        vState.dreps,
        dpProposalDeposits,
        toVotingStakePoolDistr(stakePoolDistr),
        Map.empty,
        a.accountsMapL.get(accounts)
      )
      ratifyEnv = RatifyEnv[E](
        instantStake,
        finalVotingStakePoolDistr,
        finalDRepDistr,
        vState.dreps,
        epochNo,
        vState.committeeState,
        accounts,
        c.certPStateL.get(certState).stakePools
      )
      ratifyState = RatifyState[E](dpEnactState, Vector.empty, SortedSet.empty, false)
      ratifyState1 <- ConwayRatify.ratifyTransition[E](
        ratifyEnv,
        ratifyState,
        RatifySignal[E](RatifySignal.reorderActions(dpProposals))
      )
      snapshot = PulsingSnapshot[E](
        dpProposals,
        finalDRepDistr.iterator.map((d, v) => Convert.drepBack(d) -> v.toBigInt).toMap,
        flatDRepStates(vState.dreps),
        finalVotingStakePoolDistr.vspdIndividualStake.iterator
          .map((pool, v) => pool.bytes -> v.toBigInt)
          .toMap
      )
    yield ConwayGovState.predictFuturePParams(
      govState.copy(cgsDRepPulsingState = DRepPulsingState.DRComplete(snapshot, ratifyState1))
    )
