// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes

/** A registered stake credential. `balance` is its reward balance. */
final case class Account(balance: BigInt, deposit: BigInt, pool: Option[Bytes], drep: Option[DRep])

final case class PoolState(params: PoolParams, deposit: BigInt)

final case class DRepState(deposit: BigInt, expiry: BigInt)

/** A stake distribution snapshot: delegated stake per credential and the pool parameters in force.
  */
final case class Snapshot(
    stake: Map[Credential, BigInt],
    delegations: Map[Credential, Bytes],
    pools: Map[Bytes, PoolParams]
):
  /** Total stake delegated to each registered pool. */
  lazy val poolStake: Map[Bytes, BigInt] =
    delegations.iterator
      .collect { case (c, p) if pools.contains(p) => p -> stake.getOrElse(c, BigInt(0)) }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
  lazy val activeStake: BigInt = poolStake.values.sum

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

/** The ledger state the node maintains. Collections are persistent, so the per-block copies kept by
  * the volatile chain share structure.
  */
final case class LedgerState(
    epoch: BigInt,
    params: ProtocolParams,
    utxo: Map[TxIn, TxOut],
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
    rewardUpdate: Option[RewardUpdate],
    /** Pool stake distribution for this epoch's leader schedule (`nesPd`). */
    poolDistribution: Snapshot
):
  /** Total value in existence; equals the maximum supply when the ledger is consistent. */
  def total: BigInt =
    utxo.valuesIterator.map(_.value.coin).sum + fees + deposited + treasury + reserves +
      accounts.valuesIterator.map(_.balance).sum
