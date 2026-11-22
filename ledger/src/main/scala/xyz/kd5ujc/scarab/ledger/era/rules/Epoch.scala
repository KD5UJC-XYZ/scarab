// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{Committee as StoredCommittee, GovActionId}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  DijkstraPoolReap,
  DijkstraSnap,
  Embed,
  Globals,
  HardFork,
  PoolreapState,
  Sts,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  ChainAccountState,
  Coin,
  CommitteeState,
  CompactCoin,
  Credential,
  DState,
  Dijkstra,
  EpochNo,
  EpochState,
  EraTag,
  PoolDistr,
  SnapShots,
  Sorted,
  Staking,
  VState,
  VocabError
}

/** The epoch state the era's EPOCH works on: the node's epoch state and the era's governance state
  * ([[ConwayGovState]]), until the ledger state holds the era's record (#85). What the rule reads
  * and writes of the governance state is `gov`. The node's record of it that `es` holds is not
  * written, so it is stale after the rule; it is read only as the record SNAP's parameters are
  * written over, for the fields of other eras, which SNAP does not read.
  */
final case class EpochGovState[E <: EraTag](es: EpochState[E], gov: ConwayGovState[E])

object EpochGovState:
  def es[E <: EraTag]: Lens[EpochGovState[E], EpochState[E]] =
    Lens[EpochGovState[E], EpochState[E]](_.es)(v => _.copy(es = v))

/** The functions of Conway's EPOCH that Dijkstra's calls (cardano-ledger 9df9f98c
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Epoch.hs`), on the era types, generic in the
  * era over the reference's classes.
  */
object ConwayEpoch:
  /** `returnProposalDeposits` (`Conway/Rules/Epoch.hs:183-197`, cardano-ledger 9df9f98c): each
    * removed action's deposit added to its return account's balance where that account is
    * registered (`updateLookupAccountState`), and otherwise kept unclaimed under the action's id,
    * the actions folded from the right (`foldr'`). A deposit added to an account that is not
    * compact is the reference's `compactCoinOrError`, here [[VocabError]]; so is a return account's
    * credential of another length than a stake credential's, which the decoder of an account
    * address refused.
    */
  def returnProposalDeposits[E <: EraTag](
      removedProposals: Vector[GovActionState[E]],
      oldAccounts: Accounts[E]
  )(using a: EraAccounts[E]): Either[VocabError, (Accounts[E], SortedMap[GovActionId, Coin])] =
    removedProposals.foldRight[Either[VocabError, (Accounts[E], SortedMap[GovActionId, Coin])]](
      Right((oldAccounts, SortedMap.empty))
    ) { (gas, acc) =>
      for
        (accounts, unclaimed) <- acc
        cred <- Convert.credential[Staking](gas.gasReturnAddr.credential)
        result <-
          if EraAccounts.isAccountRegistered[E](cred, accounts) then
            CompactCoin
              .fromCoin(gas.gasDeposit)
              .map(refund =>
                (
                  EraAccounts
                    .updateLookupAccountState[E](
                      a.balanceAccountStateL.modify(_.add(refund)),
                      cred,
                      accounts
                    )
                    ._2,
                  unclaimed
                )
              )
          else Right((accounts, unclaimed.updated(gas.gasId, gas.gasDeposit)))
      yield result
    }

  /** `updateNumDormantEpochs` (`Conway/Rules/Epoch.hs:199-205`, cardano-ledger 9df9f98c): one more
    * dormant epoch when no proposal can still be voted on in `currentEpoch`. The reference's `succ`
    * of the largest epoch is an error, here [[VocabError]].
    */
  def updateNumDormantEpochs[E <: EraTag](
      currentEpoch: EpochNo,
      ps: Proposals[E],
      vState: VState
  ): Either[VocabError, VState] =
    if Proposals
        .proposalsActions(ps)
        .exists(gas => currentEpoch.toBigInt <= gas.gasExpiresAfter.toBigInt)
    then Right(vState)
    else
      EpochNo
        .fromBigInt(vState.numDormantEpochs.toBigInt + 1)
        .map(n => vState.copy(numDormantEpochs = n))

  /** `applyEnactedWithdrawals` (`Conway/Rules/Epoch.hs:207-246`, cardano-ledger 9df9f98c): the
    * enacted withdrawals to registered accounts move from the treasury to their balances; those to
    * accounts no longer registered stay in the treasury; the enact state's withdrawals and treasury
    * are reset. A withdrawal that is not compact is the reference's `compactCoinOrError`, here
    * [[VocabError]], which ENACT's treasury check rules out.
    */
  def applyEnactedWithdrawals[E <: EraTag](
      chainAccountState: ChainAccountState,
      dState: DState[Accounts[E]],
      enactedState: EnactState[E]
  )(using
      a: EraAccounts[E]
  ): Either[VocabError, (ChainAccountState, DState[Accounts[E]], EnactState[E])] =
    val accounts = dState.accounts
    enactedState.ensWithdrawals.toVector
      .filter((cred, _) => EraAccounts.isAccountRegistered[E](cred, accounts))
      .traverse((cred, w) => CompactCoin.fromCoin(w).map(cred -> _))
      .map { successful =>
        val successfulWithdrawals = successful.toMap
        val total = CompactCoin.sum(successful.iterator.map(_._2)).toBigInt
        (
          chainAccountState.copy(treasury = Coin(chainAccountState.treasury.value - total)),
          dState
            .copy(accounts = EraAccounts.addToBalanceAccounts[E](successfulWithdrawals, accounts)),
          enactedState.copy(ensWithdrawals = Sorted.empty, ensTreasury = Coin.zero)
        )
      }

  /** `updateCommitteeState` (`Conway/Rules/Epoch.hs:423-427`, cardano-ledger 9df9f98c): the
    * committee state of the committee's members only.
    */
  def updateCommitteeState(
      committee: Option[StoredCommittee],
      committeeState: CommitteeState
  ): CommitteeState =
    val members = committee.fold(Map.empty)(_.members)
    CommitteeState(committeeState.creds.filter((cold, _) => members.contains(Convert.back(cold))))

/** Dijkstra's EPOCH on the era types (cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Epoch.hs:153-244`, `epochTransition`), the
  * node's, which its NEWEPOCH runs ([[DijkstraNewEpoch]]). In the reference's order:
  *
  *   1. POOLREAP, Dijkstra's ([[DijkstraPoolReap]]);
  *   2. the ratification the pulser reached (`extractDRepPulsingState`), its withdrawals applied
  *      ([[ConwayEpoch.applyEnactedWithdrawals]]) and the proposals with its enactment applied
  *      ([[Proposals.proposalsApplyEnactment]]);
  *   3. the governance state with the new proposals, the enact state's committee and constitution,
  *      the next epoch's parameters ([[EraGov.nextEpochPParams]]), the previous ones the current
  *      ones, and no future update;
  *   4. the removed actions' deposits returned ([[ConwayEpoch.returnProposalDeposits]]), the
  *      dormant epochs counted ([[ConwayEpoch.updateNumDormantEpochs]]) on the DReps' state the
  *      rule began with, the committee state kept to the committee's members, the treasury given
  *      the donations and the unclaimed deposits, the deposits counted again (`totalObligation`)
  *      and the donations reset;
  *   5. HARDFORK, Conway's ([[HardFork]]), if the protocol version changed;
  *   6. SNAP, Dijkstra's ([[DijkstraSnap]]), with the parameters now current;
  *   7. the fresh pulser over the new mark's pool distribution
  *      ([[DRepPulser.setFreshDRepPulsingState]]), computed to completion.
  *
  * POOLREAP, HARDFORK and SNAP are the node's rules on the node's records, which read no governance
  * state; SNAP is given the era's parameters now current, as `SnapEnv` holds them. The rule raises
  * no failure of its own (`Void`); what it raises is a [[TickFailure]] of those rules, or a
  * [[VocabError]] where the reference calls `error`. The reference's events are not kept, as the
  * node's rule keeps none.
  */
object DijkstraEpoch:
  private def poolreap: Lens[EpochGovState[Dijkstra], PoolreapState[Dijkstra]] =
    EpochGovState.es[Dijkstra].andThen(PoolreapState.lens[Dijkstra])

  private def snapshots: Lens[EpochGovState[Dijkstra], SnapShots] =
    EpochGovState.es[Dijkstra].andThen(EpochState.snapshots[Dijkstra])

  private def vocab[F[_]: Monad, A](result: Either[VocabError, A])(using
      Raise[F, TickFailure]
  ): F[A] =
    Sts.lift[F, TickFailure, A](result.leftMap(TickFailure.Vocab(_)))

  val sts: Sts[Unit, EpochGovState[Dijkstra], EpochNo, TickFailure] =
    new Sts[Unit, EpochGovState[Dijkstra], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, eNo: EpochNo)(using
          ask: Ask[F, Globals],
          st: Stateful[F, EpochGovState[Dijkstra]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        val certs = EraCertState.dijkstra
        given EraAccounts[Dijkstra] = certs.accountsOf
        for
          start <- st.get
          epochState0 = start.es
          govState0 = start.gov
          ledgerState0 = epochState0.ls
          utxoState0 = ledgerState0.utxo
          curPParams = govState0.cgsCurPParams
          vState = certs.certVStateL.get(ledgerState0.cert)
          _ <- {
            given Embed[PoolreapState[Dijkstra], EpochGovState[
              Dijkstra
            ], TickFailure, TickFailure] =
              Embed(poolreap, identity[TickFailure])
            Sts.trans[
              F,
              Unit,
              PoolreapState[Dijkstra],
              EpochGovState[Dijkstra],
              EpochNo,
              TickFailure,
              TickFailure
            ](DijkstraPoolReap.sts, (), eNo)
          }
          reaped <- st.get
          utxoState1 = reaped.es.ls.utxo
          chainAccountState1 = reaped.es.chainAccount
          certState1 = reaped.es.ls.cert
          ratifyState = DRepPulsingState.extractDRepPulsingState(govState0.cgsDRepPulsingState)
          withdrawn <- vocab(
            ConwayEpoch.applyEnactedWithdrawals[Dijkstra](
              chainAccountState1,
              certs.certDStateL.get(certState1),
              ratifyState.rsEnactState
            )
          )
          (chainAccountState2, dState2, enactState) = withdrawn
          (newProposals, enactedActions, removedDueToEnactment, expiredActions) =
            Proposals.proposalsApplyEnactment(
              ratifyState.rsEnacted,
              ratifyState.rsExpired,
              govState0.cgsProposals
            )
          govState1 = govState0.copy(
            cgsProposals = newProposals,
            cgsCommittee = enactState.ensCommittee,
            cgsConstitution = enactState.ensConstitution,
            cgsCurPParams = EraGov.nextEpochPParams[Dijkstra](govState0)(using EraGov.dijkstra),
            cgsPrevPParams = curPParams,
            cgsFuturePParams = FuturePParams.PotentialPParamsUpdate(None)
          )
          // `Map.unions [expiredActions, enactedActions, removedDueToEnactment]`: of an id in two,
          // the first's
          allRemovedGovActions = removedDueToEnactment ++ enactedActions ++ expiredActions
          returned <- vocab(
            ConwayEpoch.returnProposalDeposits[Dijkstra](
              allRemovedGovActions.values.toVector,
              dState2.accounts
            )
          )
          (newAccounts, unclaimed) = returned
          dormant <- vocab(ConwayEpoch.updateNumDormantEpochs(eNo, newProposals, vState))
          certState2 = certs.certDStateL.replace(dState2.copy(accounts = newAccounts))(
            certs.certVStateL.replace(
              dormant.copy(committeeState =
                ConwayEpoch.updateCommitteeState(govState1.cgsCommittee, dormant.committeeState)
              )
            )(certState1)
          )
          chainAccountState3 = chainAccountState2.copy(treasury =
            Coin(
              chainAccountState2.treasury.value + utxoState0.donation.value +
                unclaimed.valuesIterator.map(_.value).sum
            )
          )
          deposited = Obligations.sumObligation(
            EraCertState.conwayObligationCertState[Dijkstra](certState2)(using certs) |+|
              EraGov.dijkstra.obligationGovState(govState1)
          )
          utxoState2 = utxoState1.copy[Dijkstra](deposited = deposited, donation = Coin.zero)
          epochState1 = epochState0.copy(
            chainAccount = chainAccountState3,
            ls = ledgerState0.copy[Dijkstra](cert = certState2, utxo = utxoState2)
          )
          globals <- Sts.globals[F]
          curPv = EraPParams.dijkstra.ppProtocolVersionL.get(govState1.cgsCurPParams)
          prevPv = EraPParams.dijkstra.ppProtocolVersionL.get(govState1.cgsPrevPParams)
          epochState2 <-
            if curPv != prevPv then
              vocab(HardFork(globals.ledger.referenceBuild, epochState1, curPv.pvMajor.value))
            else Monad[F].pure(epochState1)
          _ <- st.set(EpochGovState(epochState2, govState1))
          _ <- {
            given Embed[SnapShots, EpochGovState[Dijkstra], TickFailure, TickFailure] =
              Embed(snapshots, identity[TickFailure])
            Sts.trans[
              F,
              (xyz.kd5ujc.scarab.ledger.state.LedgerState[Dijkstra], PParams[Dijkstra]),
              SnapShots,
              EpochGovState[Dijkstra],
              EpochNo,
              TickFailure,
              TickFailure
            ](DijkstraSnap.sts, (epochState2.ls, govState1.cgsCurPParams), eNo)
          }
          snapped <- st.get
          epochState3 = snapped.es
          stakePoolDistr = PoolDistr.of(epochState3.snapshots.mark)
          govState2 <- vocab(
            DRepPulser.setFreshDRepPulsingState[Dijkstra](
              eNo,
              stakePoolDistr,
              epochState3,
              govState1
            )(using certs, EraStake.dijkstra, EraPParams.dijkstra)
          )
          _ <- st.set(EpochGovState(epochState3, govState2))
        yield ()

  /** [[sts]] on the node's epoch state, its governance record read before and written back after
    * ([[StoredGov.onRecord]]).
    */
  val onRecord: Sts[Unit, EpochState[Dijkstra], EpochNo, TickFailure] =
    StoredGov.onRecord[Dijkstra, EpochState[Dijkstra], EpochGovState[Dijkstra], EpochNo](
      sts,
      StoredGov.recordOf[Dijkstra],
      EpochGovState(_, _),
      s => (s.es, s.gov)
    )
