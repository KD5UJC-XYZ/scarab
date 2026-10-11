// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes
import lab.node.consensus.GenDelegPair

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

/** What the ledger trusted rather than verified. Phase-2 (Plutus) evaluation is not performed:
  * `plutusValid` transactions flagged `is_valid = true` were applied and `plutusInvalid` ones
  * (flagged false) were charged their collateral, both on the block producer's word.
  * `integrityUnchecked` counts script-data hashes that were not recomputed.
  */
final case class Unverified(plutusValid: Long, plutusInvalid: Long, integrityUnchecked: Long)

object Unverified:
  val zero: Unverified = Unverified(0, 0, 0)

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
    poolDistribution: Snapshot,
    // Pre-Conway state; empty on chains that start in Conway.
    genDelegs: Map[Bytes, GenDelegPair] = Map.empty,
    futureGenDelegs: Vector[FutureGenDeleg] = Vector.empty,
    ppup: Ppup = Ppup.empty,
    mir: InstantRewards = InstantRewards.empty,
    ptrs: Map[Ptr, Credential] = Map.empty,
    /** Parameters of the previous epoch (`esPrevPp`), used by the reward calculation. */
    prevParams: Option[ProtocolParams] = None,
    /** Registered credentials when the reward calculation started (pre-Babbage reward prefilter). */
    rewardRegistered: Option[Set[Credential]] = None,
    unverified: Unverified = Unverified.zero
):
  /** Parameters in force during the previous epoch. */
  def previousParams: ProtocolParams = prevParams.getOrElse(params)

  /** Total value in existence; equals the maximum supply when the ledger is consistent. */
  def total: BigInt =
    utxo.valuesIterator.map(_.value.coin).sum + fees + deposited + treasury + reserves +
      accounts.valuesIterator.map(_.balance).sum
