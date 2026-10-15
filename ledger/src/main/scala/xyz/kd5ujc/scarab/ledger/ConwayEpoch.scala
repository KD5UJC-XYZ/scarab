// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** The Conway part of the EPOCH rule: applying the ratification result the DRep pulser reached,
  * returning proposal deposits, the dormant-epoch counter, the new obligation, the HARDFORK rule
  * and starting a fresh pulser.
  *
  * The ledger spreads the DRep stake distribution over the epoch (a "pulser") and ratifies when it
  * completes. The result depends only on the snapshot taken at the boundary, so it is computed here
  * in one go and kept next to the snapshot.
  */
object ConwayEpoch:

  /** Enact state of the ledger as it stands: the committee, constitution and parameters. */
  def enactState(s: LedgerState): EnactState =
    EnactState(
      committee = s.gov.committee,
      constitution = s.gov.constitution,
      params = s.params,
      prevParams = s.previousParams,
      treasury = s.treasury,
      withdrawals = Map.empty,
      prevIds = s.gov.proposals.roots
    )

  /** The pulser's snapshot of `s` for the epoch it starts (`epoch`), already ratified. */
  def freshPulser(s: LedgerState, epoch: BigInt): (PulsingSnapshot, RatifyState) =
    val deposits = s.gov.proposals.depositsByCredential
    val stake = Stake.instant(s)
    val drepDistr = s.accounts.iterator
      .flatMap { case (c, a) =>
        a.drep
          .flatMap {
            case d @ DRep.Registered(cred) =>
              Option.when(s.dreps.contains(cred))(d)
            case other => Some(other)
          }
          .map(d => d -> (stake.getOrElse(c, BigInt(0)) + deposits.getOrElse(c, BigInt(0))))
      }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
    val mark = s.snapshots.mark
    val base = mark.poolStake
    val (poolDistr, totalPoolStake) = s.accounts.foldLeft((base, mark.activeStake)) {
      case (acc @ (dist, total), (c, a)) =>
        (a.pool, deposits.get(c)) match
          case (Some(pool), Some(d)) if dist.contains(pool) =>
            (dist.updated(pool, dist(pool) + d), total + d)
          case _ => acc
    }
    val snapshot = PulsingSnapshot(s.gov.proposals.actions, drepDistr, s.dreps, poolDistr)
    val env = Ratify.Env(
      drepDistr = drepDistr,
      drepState = s.dreps,
      poolDistr = poolDistr,
      totalPoolStake = totalPoolStake,
      epoch = epoch,
      committeeState = s.committeeState,
      accounts = s.accounts,
      pools = s.pools
    )
    snapshot -> Ratify.ratify(env, enactState(s), s.gov.proposals.actions)

  /** EPOCH (Conway), after rewards, SNAP and POOLREAP. */
  def apply(state: LedgerState, epoch: BigInt): LedgerState =
    val ratify = state.gov.pulsed
      .map(_._2)
      .getOrElse(
        RatifyState(enactState(state), Vector.empty, Set.empty, delayed = false)
      )
    val enact = ratify.enact
    // Enacted treasury withdrawals reach the registered accounts; the rest stays in the treasury.
    val paid = enact.withdrawals.filter { case (c, _) => state.accounts.contains(c) }
    val afterWithdrawals = state.copy(
      treasury = state.treasury - paid.values.sum,
      accounts = paid.foldLeft(state.accounts) { case (as, (c, amount)) =>
        as.updatedWith(c)(_.map(a => a.copy(balance = a.balance + amount)))
      }
    )
    val (newProposals, competing, expiredActions) =
      state.gov.proposals.applyEnactment(ratify.enacted, ratify.expired)
    val removedActions = ratify.enacted ++ competing ++ expiredActions
    // Deposits go back to the return accounts; those that no longer exist go to the treasury.
    val (refunded, unclaimed) =
      removedActions.foldLeft((afterWithdrawals.accounts, BigInt(0))) { case ((as, lost), gas) =>
        val c = gas.proposal.returnAccount.credential
        if as.contains(c) then
          (as.updatedWith(c)(_.map(a => a.copy(balance = a.balance + gas.proposal.deposit))), lost)
        else (as, lost + gas.proposal.deposit)
      }
    val committee = enact.committee
    val active = newProposals.actions.exists(epoch <= _.expiresAfter)
    val withGov = afterWithdrawals.copy(
      accounts = refunded,
      params = enact.params,
      prevParams = Some(state.params),
      gov = state.gov.copy(
        proposals = newProposals,
        committee = committee,
        constitution = enact.constitution
      ),
      committeeState = state.committeeState.filter { case (cold, _) =>
        committee.exists(_.members.contains(cold))
      },
      dormantEpochs = if active then state.dormantEpochs else state.dormantEpochs + 1,
      treasury = afterWithdrawals.treasury + state.donation + unclaimed,
      donation = 0,
      epoch = epoch,
      blocksPrevious = state.blocksCurrent,
      blocksCurrent = Map.empty,
      rewardUpdate = None,
      poolDistribution = state.snapshots.set
    )
    val obligated = withGov.copy(deposited = Epoch.obligation(withGov))
    val forked =
      if enact.params.protocolMajor != state.params.protocolMajor &&
        enact.params.protocolMajor == 10
      then updateDRepDelegations(obligated)
      else obligated
    forked.copy(gov = forked.gov.copy(pulsed = Some(freshPulser(forked, epoch))))

  /** HARDFORK into protocol version 10: rebuild every DRep's reverse delegation index from the
    * accounts and drop delegations to DReps that are not registered.
    */
  def updateDRepDelegations(s: LedgerState): LedgerState =
    val cleared = s.dreps.view.mapValues(_.copy(delegs = Set.empty)).toMap
    val (dreps, accounts) = s.accounts.foldLeft((cleared, s.accounts)) { case ((ds, as), (c, a)) =>
      a.drep match
        case Some(DRep.Registered(d)) =>
          if ds.contains(d) then (ds.updated(d, ds(d).copy(delegs = ds(d).delegs + c)), as)
          else (ds, as.updated(c, a.copy(drep = None)))
        case _ => (ds, as)
    }
    s.copy(dreps = dreps, accounts = accounts)
