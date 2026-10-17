// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*

/** A stake distribution snapshot: delegated stake per credential and the pool parameters in force.
  */
final case class Snapshot(
    stake: Map[Credential, BigInt],
    delegations: Map[Credential, Bytes],
    pools: Map[Bytes, PoolParams],
    /** Pools with a registered delegator that has no stake. Such a credential is in neither `stake`
      * nor `delegations` (the ledger's active stake holds non-zero amounts only), but the pool
      * still counts it as a delegator and so appears, with no stake, in the pool distribution.
      */
    idlePools: Set[Bytes] = Set.empty
):
  /** Total stake delegated to each registered pool. */
  lazy val poolStake: Map[Bytes, BigInt] =
    val staked = delegations.iterator
      .collect { case (c, p) if pools.contains(p) => p -> stake.getOrElse(c, BigInt(0)) }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
    idlePools.iterator
      .filter(p => pools.contains(p) && !staked.contains(p))
      .foldLeft(staked)(_.updated(_, BigInt(0)))
  lazy val activeStake: BigInt = poolStake.values.sum

  /** The pool distribution as the leader check consumes it; built once per snapshot, not per block.
    */
  lazy val distribution: xyz.kd5ujc.scarab.ledger.PoolDistribution =
    xyz.kd5ujc.scarab.ledger
      .PoolDistribution(poolStake, pools.map { case (id, p) => id -> p.vrfKeyHash })

object Snapshot:
  val empty: Snapshot = Snapshot(Map.empty, Map.empty, Map.empty)

/** The three rotating stake snapshots and the fees collected in the epoch before the newest. */
final case class Snapshots(mark: Snapshot, set: Snapshot, go: Snapshot, fee: BigInt)

/** A computed reward update, applied at the next epoch boundary. */
final case class RewardUpdate(
    deltaTreasury: BigInt,
    deltaReserves: BigInt,
    rewards: Map[Credential, BigInt],
    deltaFees: BigInt
)

/** Pending protocol-parameter update proposals: those for the current epoch and for the next. */
final case class Ppup(current: Map[Bytes, ParamUpdate], future: Map[Bytes, ParamUpdate])

object Ppup:
  val empty: Ppup = Ppup(Map.empty, Map.empty)

/** Instantaneous rewards (MIR) accumulated by certificates, paid at the next epoch boundary. */
final case class InstantRewards(
    reserves: Map[Credential, BigInt],
    treasury: Map[Credential, BigInt],
    deltaReserves: BigInt,
    deltaTreasury: BigInt
)

object InstantRewards:
  val empty: InstantRewards = InstantRewards(Map.empty, Map.empty, 0, 0)

/** The ledger state the node maintains. Collections are persistent, so the per-block copies kept by
  * the volatile chain share structure.
  */
final case class LedgerState(
    epoch: BigInt,
    params: ProtocolParams,
    utxo: Utxo,
    fees: BigInt,
    deposited: BigInt,
    accounts: Map[Credential, Account],
    pools: Map[Bytes, PoolState],
    futurePools: Map[Bytes, PoolParams],
    retiring: Map[Bytes, BigInt],
    dreps: Map[Credential, DRepState],
    dormantEpochs: BigInt,
    treasury: BigInt,
    reserves: BigInt,
    snapshots: Snapshots,
    blocksCurrent: Map[Bytes, BigInt],
    blocksPrevious: Map[Bytes, BigInt],
    /** The reward update to apply at the next boundary. The ledger creates it with the first block
      * after `startRewards` slots into the epoch; an epoch without such a block pays no rewards.
      */
    rewardUpdate: Option[RewardUpdate],
    /** Pool stake distribution for this epoch's leader schedule (`nesPd`). */
    poolDistribution: Snapshot,
    // Pre-Conway state; empty on chains that start in Conway.
    genDelegs: Map[Bytes, GenDelegPair] = Map.empty,
    futureGenDelegs: Vector[FutureGenDeleg] = Vector.empty,
    ppup: Ppup = Ppup.empty,
    mir: InstantRewards = InstantRewards.empty,
    ptrs: PtrMap = PtrMap.empty,
    /** Parameters of the previous epoch (`esPrevPp`), used by the reward calculation. */
    prevParams: Option[ProtocolParams] = None,
    /** Registered credentials when the reward calculation started (pre-Babbage reward prefilter).
      */
    rewardRegistered: Option[Set[Credential]] = None,
    plutus: PlutusStats = PlutusStats.zero,
    // Conway governance state; empty before Conway.
    gov: GovState = GovState.empty,
    /** Committee hot-key authorisations and resignations by cold credential. */
    committeeState: Map[Credential, CommitteeAuth] = Map.empty,
    /** Treasury donations of the epoch so far, moved to the treasury at the boundary. */
    donation: BigInt = 0,
    /** The reward update computed at the epoch boundary, waiting for the block that creates it. */
    rewardDraft: Option[RewardUpdate] = None
):
  /** Parameters in force during the previous epoch. */
  def previousParams: ProtocolParams = prevParams.getOrElse(params)

  /** Total value in existence; equals the maximum supply when the ledger is consistent. */
  def total: BigInt =
    utxo.coin + fees + deposited + treasury + reserves +
      accounts.valuesIterator.map(_.balance).sum + donation
