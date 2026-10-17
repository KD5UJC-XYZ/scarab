// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*

/** `setFreshDRepPulsingState` (`Conway/Governance.hs:458`): the DRep pulser that starts at an epoch
  * boundary, for the ratification that the next boundary applies.
  *
  * Haskell spreads the pulser over the epoch (it works through the accounts a few at a time, so the
  * DRep distribution is ready two stability windows before the end of the epoch) and keeps it
  * `DRPulsing` until it is forced. The node's mirror holds the pulser only completed
  * ([[DRComplete]], `DRepPulser.hs:423-431`): ratification is a pure function of the snapshot taken
  * at the boundary, so the snapshot and the ratification it reaches are computed together here, by
  * the ratification rules the node already has ([[node.Ratify]]) over the mirror's records, which
  * are put in the node's plain form for them.
  */
object ConwayPulser:

  /** The snapshot the pulser takes of epoch state `es` entering epoch `epoch`, and the ratification
    * of the proposals over it: the DRep distribution is the stake (instant stake and balance) of
    * the accounts delegated to each DRep, with their proposal deposits; the pool distribution is
    * the new `mark` snapshot's, with the deposits of the accounts that delegate to those pools.
    */
  def fresh(globals: Globals, es: EpochState[Conway], epoch: EpochNo): DRComplete =
    val ls = es.ls
    val gov = ls.utxo.gov
    val accounts = ls.cert.dState.accounts.states
    val flatAccounts: Map[node.Credential, node.Account] =
      FlatView.stakeMap(accounts)(FlatView.conwayAccount)
    val flatDReps: Map[node.Credential, node.DRepState] =
      FlatView.stakeMap(ls.cert.vState.dreps)(d =>
        node.DRepState(
          d.deposit.toBigInt,
          d.expiry.toBigInt,
          d.anchor,
          d.delegators.iterator.map(Convert.back).toSet
        )
      )
    val deposits = gov.proposals.depositsByCredential
    val instant = ls.utxo.instantStake.credentialStake
    val drepDistr: Map[node.DRep, BigInt] =
      accounts.iterator
        .flatMap { (c, a) =>
          val credential = Convert.back(c)
          a.drep
            .map(Convert.drepBack)
            .filter {
              case node.DRep.Registered(d) => flatDReps.contains(d)
              case _                       => true
            }
            .map(d =>
              d -> (instant.get(c).fold(BigInt(0))(_.toBigInt) + a.balance.toBigInt +
                deposits.getOrElse(credential, BigInt(0)))
            )
        }
        .toVector
        .groupMapReduce(_._1)(_._2)(_ + _)
    val mark = es.snapshots.mark
    val base: Map[Bytes, BigInt] =
      mark.pools.iterator.collect {
        case (id, p) if !p.numDelegators.isZero => id.bytes -> p.stake.toBigInt
      }.toMap
    val baseTotal =
      mark.activeStake.entries.valuesIterator.foldLeft(BigInt(0))(_ + _.stake.value.toBigInt)
    val (poolDistr, totalPoolStake) = accounts.iterator.foldLeft((base, baseTotal)) {
      case (acc @ (dist, total), (c, a)) =>
        (a.pool.map(_.bytes), deposits.get(Convert.back(c))) match
          case (Some(pool), Some(d)) if dist.contains(pool) =>
            (dist.updated(pool, dist(pool) + d), total + d)
          case _ => acc
    }
    val snapshot =
      node.PulsingSnapshot(gov.proposals.actions, drepDistr, flatDReps, poolDistr)
    val committeeState: Map[node.Credential, node.CommitteeAuth] =
      FlatView.stakeMap(ls.cert.vState.committeeState.creds) {
        case CommitteeAuthorization.CommitteeHotCredential(h) =>
          node.CommitteeAuth.Hot(Convert.back(h))
        case CommitteeAuthorization.CommitteeMemberResigned(a) => node.CommitteeAuth.Resigned(a)
      }
    val env = node.Ratify.Env(
      drepDistr = drepDistr,
      drepState = flatDReps,
      poolDistr = poolDistr,
      totalPoolStake = totalPoolStake,
      epoch = epoch.toBigInt,
      committeeState = committeeState,
      accounts = flatAccounts,
      pools = FlatView.pools(globals.networkId, ls.cert.pState.stakePools)
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
