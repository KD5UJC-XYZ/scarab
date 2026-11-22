// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*

/** `setFreshDRepPulsingState` (`Conway/Governance.hs:458`, cardano-ledger f649f975): the DRep
  * pulser that starts at an epoch boundary, for the ratification that the next boundary applies. It
  * is the same in every era with Conway's certificate state ([[ConwayShapes]]) and the node's flat
  * governance record, Conway; Dijkstra's, the same rule (cardano-ledger 9df9f98c
  * `Dijkstra/Rules/Epoch.hs:233`), is on the era's record (`era.DRepPulser`).
  *
  * Haskell spreads the pulser over the epoch (it works through the accounts a few at a time, so the
  * DRep distribution is ready two stability windows before the end of the epoch) and keeps it
  * `DRPulsing` until it is forced. The node's mirror holds the pulser only completed
  * ([[DRComplete]], `DRepPulser.hs:423-431`): ratification is a pure function of the snapshot taken
  * at the boundary, so the snapshot and the ratification it reaches are computed together here, by
  * [[xyz.kd5ujc.scarab.ledger.Ratify]] over the mirror's records, converted to the plain records
  * that ratification reads.
  */
object ConwayPulser:

  /** The snapshot the pulser takes of epoch state `es` entering epoch `epoch`, and the ratification
    * of the proposals over it: the DRep distribution is the stake (instant stake and balance) of
    * the accounts delegated to each DRep, with their proposal deposits; the pool distribution is
    * the new `mark` snapshot's, with the deposits of the accounts that delegate to those pools.
    */
  def fresh[E <: EraTag](globals: Globals, es: EpochState[E], epoch: EpochNo)(using
      shapes: ConwayShapes[E],
      gs: GovState[E] =:= ConwayGovState
  ): DRComplete =
    val ls = es.ls
    val cert: ConwayCertState = shapes.cert(ls.cert)
    val gov: ConwayGovState = gs(ls.utxo.gov)
    val accounts = cert.dState.accounts.states
    // Ratification reads an account for one thing, the DRep that a pool's reward account
    // delegates to, so only those accounts are put in the node's form, not every account
    val flatAccounts: Map[node.Credential, node.Account] =
      (for
        pool <- cert.pState.stakePools.valuesIterator
        account <- accounts.get(pool.accountId)
      yield FlatView.credBack(pool.accountId) -> FlatView.conwayAccount(account)).toMap
    val flatDReps: Map[node.Credential, node.DRepState] =
      FlatView.stakeMap(cert.vState.dreps)(d =>
        node.DRepState(
          d.deposit.toBigInt,
          d.expiry.toBigInt,
          d.anchor,
          d.delegators.iterator.map(Convert.back).toSet
        )
      )
    val deposits = gov.proposals.depositsByCredential
    val instant = shapes.instantStake(ls.utxo.instantStake).credentialStake
    // the accounts in the order of the credential, and the instant stake walked beside them; the
    // sum for each DRep is built as `groupMapReduce` builds it
    val stakes = Sorted.Cursor(instant)
    val sums = accounts.iterator.foldLeft(Map.empty[node.DRep, CompactCoin]) {
      case (sums, (c, a)) =>
        a.drep.fold(sums) { drep =>
          val d = Convert.drepBack(drep)
          val known = d match
            case node.DRep.Registered(r) => flatDReps.contains(r)
            case _                       => true
          if known then
            val proposals =
              deposits.getOrElse(Convert.back(c), CompactCoin.zero)
            val stake = stakes.at(c).getOrElse(CompactCoin.zero).add(proposals).add(a.balance)
            sums.updated(d, sums.get(d).fold(stake)(_.add(stake)))
          else sums
        }
    }
    val drepDistr: Map[node.DRep, BigInt] =
      sums.iterator.map((d, amount) => d -> amount.toBigInt).toMap
    val mark = es.snapshots.mark
    val base: Map[Bytes, CompactCoin] =
      mark.pools.iterator.collect {
        case (id, p) if !p.numDelegators.isZero => id.bytes -> p.stake
      }.toMap
    val baseTotal = mark.activeStake.total.value.value
    // proposals hold no deposits most of the time, and then no account adds to a pool
    val (poolDistr, totalPoolStake) =
      if deposits.isEmpty then (base, baseTotal)
      else
        accounts.iterator.foldLeft((base, baseTotal)) { case (acc @ (dist, total), (c, a)) =>
          (a.pool.map(_.bytes), deposits.get(Convert.back(c))) match
            case (Some(pool), Some(d)) if dist.contains(pool) =>
              (dist.updated(pool, dist(pool).add(d)), total + d.toBigInt)
            case _ => acc
        }
    val poolAmounts = poolDistr.view.mapValues(_.toBigInt).toMap
    val snapshot =
      node.PulsingSnapshot(gov.proposals.actions, drepDistr, flatDReps, poolAmounts)
    val committeeState: Map[node.Credential, node.CommitteeAuth] =
      FlatView.stakeMap(cert.vState.committeeState.creds) {
        case CommitteeAuthorization.CommitteeHotCredential(h) =>
          node.CommitteeAuth.Hot(Convert.back(h))
        case CommitteeAuthorization.CommitteeMemberResigned(a) => node.CommitteeAuth.Resigned(a)
      }
    val env = node.Ratify.Env(
      drepDistr = drepDistr,
      drepState = flatDReps,
      poolDistr = poolAmounts,
      totalPoolStake = totalPoolStake,
      epoch = epoch.toBigInt,
      committeeState = committeeState,
      accounts = flatAccounts,
      pools = FlatView.pools(globals.networkId.wire, cert.pState.stakePools)
    )
    val enact = node.EnactState(
      committee = gov.committee,
      constitution = gov.constitution,
      params = gov.curPParams,
      prevParams = gov.prevPParams,
      treasury = es.chainAccount.treasury.value,
      withdrawals = Map.empty,
      prevIds = gov.proposals.roots
    )
    DRComplete(snapshot, node.Ratify.ratify(env, enact, gov.proposals.actions))
