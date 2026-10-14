// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes
import lab.node.consensus.GenDelegPair

/** A registered stake credential. `balance` is its reward balance. */
final case class Account(balance: BigInt, deposit: BigInt, pool: Option[Bytes], drep: Option[DRep])

object Account:
  /** A table of accounts sums the balances (`sumA`) and the deposits (`sumB`). */
  given Weigh[Account] = a => (a.balance, a.deposit)

final case class PoolState(params: PoolParams, deposit: BigInt)

object PoolState:
  given Weigh[PoolState] = p => (BigInt(0), p.deposit)

/** A registered DRep. `delegs` is the reverse index of stake credentials delegating to it (kept as
  * the ledger keeps it, including its bootstrap-phase inconsistencies).
  */
final case class DRepState(
    deposit: BigInt,
    expiry: BigInt,
    anchor: Option[Anchor] = None,
    delegs: Set[Credential] = Set.empty
)

object DRepState:
  given Weigh[DRepState] = d => (BigInt(0), d.deposit)

/** The part of a stake snapshot the leader check and the queries read: stake per pool and the pool
  * parameters in force. Small (one entry per pool), so it stays in the state.
  */
final case class StakeDistribution(
    poolStake: Map[Bytes, BigInt],
    pools: Map[Bytes, PoolParams]
):
  lazy val activeStake: BigInt = poolStake.values.sum

  /** The pool distribution as the leader check consumes it; built once per snapshot, not per block.
    */
  lazy val distribution: lab.node.consensus.PoolDistribution =
    lab.node.consensus.PoolDistribution(poolStake, pools.map { case (id, p) => id -> p.vrfKeyHash })

object StakeDistribution:
  val empty: StakeDistribution = StakeDistribution(Map.empty, Map.empty)

/** A stake distribution snapshot: delegated stake per credential and the pool parameters in force.
  * The per-credential tables (a snapshot of every delegating account, read once, by the reward
  * calculation) are generations of two stored tables, so a rotation of the three snapshots moves
  * values and never rows (roadmap D1); `poolStake` is their per-pool total, fixed when the snapshot
  * is taken.
  */
final case class Snapshot(
    stake: Table[Credential, BigInt],
    delegations: Table[Credential, Bytes],
    pools: Map[Bytes, PoolParams],
    poolStake: Map[Bytes, BigInt]
):
  /** The generation of the stored rows (snapshots taken later have larger ones). */
  def gen: Long = stake.space.gen

  lazy val distr: StakeDistribution = StakeDistribution(poolStake, pools)
  def activeStake: BigInt = distr.activeStake
  def distribution: lab.node.consensus.PoolDistribution = distr.distribution

object Snapshot:
  val StakeTable = "snapStake"
  val DelegTable = "snapDeleg"

  /** A snapshot of generation `gen` holding all its rows in the value (nothing stored yet). */
  def of(
      gen: Long,
      stake: Map[Credential, BigInt],
      delegations: Map[Credential, Bytes],
      pools: Map[Bytes, PoolParams],
      stamp: Long = 0
  ): Snapshot =
    val poolStake = delegations.iterator
      .collect { case (c, p) if pools.contains(p) => p -> stake.getOrElse(c, BigInt(0)) }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
    Snapshot(
      Table.fromMap(Table.Space(StakeTable, gen), stake, stamp),
      Table.fromMap(Table.Space(DelegTable, gen), delegations, stamp),
      pools,
      poolStake
    )

  val empty: Snapshot = of(0, Map.empty, Map.empty, Map.empty)

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

/** Plutus phase-2 evaluation counters. `succeeded` and `failed` count transactions whose scripts
  * were evaluated and whose verdict equalled the block's `is_valid` flag (a disagreement is a
  * validation error, so it is never counted); `scripts` counts the redeemers evaluated and
  * `integrityChecked` the script data hashes recomputed and found equal.
  */
final case class PlutusStats(
    succeeded: Long,
    failed: Long,
    scripts: Long,
    integrityChecked: Long
)

object PlutusStats:
  val zero: PlutusStats = PlutusStats(0, 0, 0, 0)

/** The ledger state the node maintains. Collections are persistent, so the per-block copies kept by
  * the volatile chain share structure.
  */
final case class LedgerState(
    epoch: BigInt,
    params: ProtocolParams,
    utxo: Utxo,
    fees: BigInt,
    deposited: BigInt,
    accounts: Table[Credential, Account],
    pools: Table[Bytes, PoolState],
    futurePools: Map[Bytes, PoolParams],
    retiring: Map[Bytes, BigInt],
    dreps: Table[Credential, DRepState],
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
    poolDistribution: StakeDistribution,
    // Pre-Conway state; empty on chains that start in Conway.
    genDelegs: Map[Bytes, GenDelegPair] = Map.empty,
    futureGenDelegs: Vector[FutureGenDeleg] = Vector.empty,
    ppup: Ppup = Ppup.empty,
    mir: InstantRewards = InstantRewards.empty,
    ptrs: Map[Ptr, Credential] = Map.empty,
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
      accounts.sumA + donation

  /** Changes made from now on (by the block with this number) carry its stamp. */
  def at(blockNo: Long): LedgerState =
    mapTables([K, V] => (t: Table[K, V]) => t.at(blockNo)).copy(utxo = utxo.at(blockNo))

  /** The state with every table's read set dropped and, once enough have piled up (or `force` d),
    * the changes the store holds (stamps up to `upTo`) trimmed.
    */
  def settled(upTo: Long, force: Boolean = false): LedgerState =
    mapTables([K, V] => (t: Table[K, V]) => t.settled(upTo, force))
      .copy(utxo = utxo.settled(upTo, force))

  /** The state with its tables reduced to their rows (see [[Table.logical]]; the UTxO set is left
    * as it is): for comparing states that keep their tables in different places.
    */
  def logical: LedgerState = mapTables([K, V] => (t: Table[K, V]) => t.logical)

  /** Apply `f` to every stored table of the state (not the UTxO set). */
  def mapTables(f: [K, V] => Table[K, V] => Table[K, V]): LedgerState =
    def snap(s: Snapshot): Snapshot = s.copy(stake = f(s.stake), delegations = f(s.delegations))
    copy(
      accounts = f(accounts),
      pools = f(pools),
      dreps = f(dreps),
      snapshots = snapshots.copy(
        mark = snap(snapshots.mark),
        set = snap(snapshots.set),
        go = snap(snapshots.go)
      )
    )

object LedgerState:
  val AccountsTable = "accounts"
  val PoolsTable = "pools"
  val DRepsTable = "dreps"

  def accountsSpace: Table.Space = Table.Space(AccountsTable)
  def poolsSpace: Table.Space = Table.Space(PoolsTable)
  def drepsSpace: Table.Space = Table.Space(DRepsTable)
