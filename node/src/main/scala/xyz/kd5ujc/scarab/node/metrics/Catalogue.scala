// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import xyz.kd5ujc.scarab.ledger.BlockRule
import xyz.kd5ujc.scarab.node.forge.ForgeEpochStats
import xyz.kd5ujc.scarab.node.mempool.Rejection
import xyz.kd5ujc.scarab.protocol.{ConnectionState, Era}

/** The closed sets that label a series. Each is an enum, so a series is labelled by a member and
  * the number of series per metric is bounded by the type; `derives LabelValue` writes the name of
  * the case.
  */
enum AreaLabel:
  case Heap, NonHeap

object AreaLabel:
  /** `jvm_memory_bytes_used{area="heap"}`, as the JVM client libraries spell it. */
  given LabelValue[AreaLabel] = LabelValue.instance {
    case Heap    => "heap"
    case NonHeap => "nonheap"
  }

/** What state a connection is in, without the data of the state ([[ConnectionState]]). */
enum ConnectionStateLabel derives LabelValue:
  case Reserved, Unnegotiated, Established, Terminating, Terminated

object ConnectionStateLabel:
  def of(state: ConnectionState): ConnectionStateLabel = state match
    case ConnectionState.Reserved          => Reserved
    case ConnectionState.Unnegotiated(_)   => Unnegotiated
    case ConnectionState.Established(_, _) => Established
    case ConnectionState.Terminating       => Terminating
    case ConnectionState.Terminated        => Terminated

/** What the connection manager did ([[xyz.kd5ujc.scarab.network.ConnectionEvent]]). */
enum ConnectionEventLabel derives LabelValue:
  case Opened, Changed, Refused, Closed

/** What the inbound governor did on a connection ([[xyz.kd5ujc.scarab.node.InboundEvent]]). */
enum InboundEventLabel derives LabelValue:
  case Accepted, Duplex, Unidirectional, Following, SessionEnded, SessionDropped, Closed, Refused

/** Why the mempool refused a transaction ([[xyz.kd5ujc.scarab.node.mempool.Rejection]]). */
enum RejectionLabel derives LabelValue:
  case Malformed, AlreadyHeld, Ledger, Full, WrongEra

object RejectionLabel:
  def of(reason: Rejection): RejectionLabel = reason match
    case Rejection.Malformed(_)   => Malformed
    case Rejection.AlreadyHeld(_) => AlreadyHeld
    case Rejection.Ledger(_)      => Ledger
    case Rejection.Full(_, _)     => Full
    case Rejection.WrongEra(_)    => WrongEra

/** Why the forger did not check a slot. The forger counts the reasons as text; anything it does not
  * know is `Other`.
  */
enum SkipLabel derives LabelValue:
  case Busy, TipTooOld, SlotOccupied, NotConnected, Other

object SkipLabel:
  def parse(reason: String): SkipLabel = reason match
    case ForgeEpochStats.Busy         => Busy
    case ForgeEpochStats.TipTooOld    => TipTooOld
    case ForgeEpochStats.SlotOccupied => SlotOccupied
    case ForgeEpochStats.NotConnected => NotConnected
    case _                            => Other

/** Which snapshot trouble: an unreadable file left alone, a write that failed, a state the format
  * refused.
  */
enum SnapshotProblemLabel derives LabelValue:
  case Skipped, Failed, Refused

/** The lateness above which a timer is counted as late. */
enum LatenessLabel:
  case Over50Ms, Over100Ms

object LatenessLabel:
  given LabelValue[LatenessLabel] = LabelValue.instance {
    case Over50Ms  => "50"
    case Over100Ms => "100"
  }

/** Every metric the node exports: the identity of a metric is a value here, never a string at the
  * place that records it. `cardano_node_metrics_*` are the names the Haskell node writes with
  * `PrometheusSimple suffix` (cardano-node 11.1.3: scraped from a running node for the ones the
  * docs mark as verified, see `docs/running.md`, "Metrics"); `scarab_*`, `jvm_*` and `process_*`
  * are ours, after the Prometheus naming conventions (`_total` for counters, base units, `_seconds`
  * and `_bytes`).
  */
object Catalogue:
  given LabelValue[Era] = LabelValue.derived
  given LabelValue[BlockRule] = LabelValue.instance(_.name)

  private val noLabels = Labels.none
  private val era = Labels.one[Era](LabelName("era"))

  // ---- the chain, under the Haskell node's names

  val blockNum = GaugeDef(
    MetricName("cardano_node_metrics_blockNum_int"),
    "Block number of the tip of the chain."
  )
  val slotNum = GaugeDef(
    MetricName("cardano_node_metrics_slotNum_int"),
    "Slot number of the tip of the chain."
  )
  val epoch = GaugeDef(
    MetricName("cardano_node_metrics_epoch_int"),
    "Epoch of the tip of the chain."
  )
  val slotInEpoch = GaugeDef(
    MetricName("cardano_node_metrics_slotInEpoch_int"),
    "Slot of the tip of the chain within its epoch."
  )
  val density = GaugeDef(
    MetricName("cardano_node_metrics_density_real"),
    "Chain density of the volatile chain: its blocks per slot, from its anchor (the immutable tip) to the tip."
  )
  val forks = CounterDef(
    MetricName("cardano_node_metrics_forks_counter"),
    "Switches to another fork (a rollback of at least one block) since the start."
  )
  val nodeStartTime = GaugeDef(
    MetricName("cardano_node_metrics_node_start_time_int"),
    "Start of the node, in seconds since the epoch of 1970."
  )

  // ---- the mempool

  val txsInMempool = GaugeDef(
    MetricName("cardano_node_metrics_txsInMempool_int"),
    "Transactions in the mempool."
  )
  val mempoolBytes = GaugeDef(
    MetricName("cardano_node_metrics_mempoolBytes_int"),
    "Size of the transactions in the mempool, in bytes."
  )
  val txsProcessed = CounterDef(
    MetricName("cardano_node_metrics_txsProcessedNum_counter"),
    "Transactions added to the mempool since the start."
  )
  val mempoolRejected = CounterDef(
    MetricName("scarab_mempool_rejected_total"),
    "Transactions the mempool refused, by reason.",
    Labels.one[RejectionLabel](LabelName("reason"))
  )
  val mempoolDropped = CounterDef(
    MetricName("scarab_mempool_dropped_total"),
    "Held transactions dropped because the new tip no longer admits them."
  )
  val mempoolAdopted = CounterDef(
    MetricName("scarab_mempool_adopted_total"),
    "Held transactions that appeared in a block of our chain."
  )

  // ---- forging (only on a node that forges)

  val forgeAboutToLead = CounterDef(
    MetricName("cardano_node_metrics_Forge_about_to_lead_counter"),
    "Slots checked for leadership."
  )
  val forgeNodeIsLeader = CounterDef(
    MetricName("cardano_node_metrics_Forge_node_is_leader_counter"),
    "Slots the pool led."
  )
  val forgeNodeNotLeader = CounterDef(
    MetricName("cardano_node_metrics_Forge_node_not_leader_counter"),
    "Slots checked that the pool did not lead."
  )
  val forgeForged = CounterDef(
    MetricName("cardano_node_metrics_Forge_forged_counter"),
    "Blocks forged."
  )
  val forgeAdopted = CounterDef(
    MetricName("cardano_node_metrics_Forge_adopted_counter"),
    "Forged blocks that became the tip of our chain."
  )
  val forgeDidntAdopt = CounterDef(
    MetricName("cardano_node_metrics_Forge_didnt_adopt_counter"),
    "Forged blocks that did not stay on our chain (lost slot battles, replaced later)."
  )
  val blocksForged = GaugeDef(
    MetricName("cardano_node_metrics_blocksForged_int"),
    "Blocks forged since the start."
  )
  val nodeIsLeader = GaugeDef(
    MetricName("cardano_node_metrics_nodeIsLeader_int"),
    "Slots the pool led since the start."
  )
  val nodeCannotForge = GaugeDef(
    MetricName("cardano_node_metrics_nodeCannotForge_int"),
    "Slots the pool led but could not forge in, since the start."
  )
  val slotsMissed = GaugeDef(
    MetricName("cardano_node_metrics_slotsMissed_int"),
    "Slots the forger skipped because it was still busy with an earlier one, since the start."
  )
  val forgedSlotLast = GaugeDef(
    MetricName("cardano_node_metrics_forgedSlotLast_int"),
    "Slot of the last block forged."
  )
  val forgingEnabled = GaugeDef(
    MetricName("cardano_node_metrics_forging_enabled_int"),
    "1 when the node has forging credentials."
  )
  val currentKesPeriod = GaugeDef(
    MetricName("cardano_node_metrics_currentKESPeriod_int"),
    "The KES period of the last block forged or of the last key evolution."
  )
  val remainingKesPeriods = GaugeDef(
    MetricName("cardano_node_metrics_remainingKESPeriods_int"),
    "KES periods left before the operational certificate expires."
  )
  val opCertStartKesPeriod = GaugeDef(
    MetricName("cardano_node_metrics_operationalCertificateStartKESPeriod_int"),
    "The first KES period of the operational certificate."
  )
  val opCertExpiryKesPeriod = GaugeDef(
    MetricName("cardano_node_metrics_operationalCertificateExpiryKESPeriod_int"),
    "The KES period at which the operational certificate expires."
  )
  val forgeSkipped = CounterDef(
    MetricName("scarab_forge_skipped_total"),
    "Slots the forger did not check, by reason.",
    Labels.one[SkipLabel](LabelName("reason"))
  )
  val forgeAdoptionSeconds = HistogramDef(
    MetricName("scarab_forge_adoption_seconds"),
    "Time from the start of the slot to the adoption of the block forged in it.",
    noLabels,
    Buckets.of(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10)
  )

  // ---- connections, under the Haskell node's names where they mean the same

  val inboundConns = GaugeDef(
    MetricName("cardano_node_metrics_connectionManager_inboundConns_int"),
    "Connections the peer opened to us."
  )
  val outboundConns = GaugeDef(
    MetricName("cardano_node_metrics_connectionManager_outboundConns_int"),
    "Connections we opened to a peer."
  )
  val duplexConns = GaugeDef(
    MetricName("cardano_node_metrics_connectionManager_duplexConns_int"),
    "Connections on which both sides run the protocols."
  )

  // ---- scarab_*: the chain

  val buildInfo = GaugeDef(
    MetricName("scarab_build_info"),
    "The build that is running (always 1).",
    Labels.three[String, String, String](
      LabelName("version"),
      LabelName("scala_version"),
      LabelName("git_commit")
    )
  )
  val currentEra = GaugeDef(
    MetricName("scarab_era"),
    "1 for the era of the tip of the chain, 0 for the others.",
    era
  )
  val blocksApplied = CounterDef(
    MetricName("scarab_blocks_applied_total"),
    "Blocks applied to the chain since the start, by era (replay of the stored blocks excluded).",
    era
  )
  val blockApplySeconds = HistogramDef(
    MetricName("scarab_block_apply_seconds"),
    "Time to validate a block and apply it to the ledger state.",
    noLabels,
    Buckets.exponential(0.0005, 2, 16)
  )
  val validationErrors = CounterDef(
    MetricName("scarab_validation_errors_total"),
    "Blocks the rules refused, by rule family.",
    Labels.one[BlockRule](LabelName("rule"))
  )
  val peerTipSlot = GaugeDef(
    MetricName("scarab_peer_tip_slot"),
    "Slot of the tip of the peer's chain, as the peer last announced it."
  )
  val peerTipBlock = GaugeDef(
    MetricName("scarab_peer_tip_block"),
    "Block number of the tip of the peer's chain, as the peer last announced it."
  )
  val peerReconnects = CounterDef(
    MetricName("scarab_peer_reconnects_total"),
    "Times the connection to the peer was lost and opened again."
  )
  val rollbacks = CounterDef(
    MetricName("scarab_rollbacks_total"),
    "Rollbacks of the chain."
  )
  val rollbackDepth = HistogramDef(
    MetricName("scarab_rollback_depth_blocks"),
    "Blocks rolled back at a time.",
    noLabels,
    Buckets.of(1, 2, 3, 5, 10, 20, 50, 100, 500, 2160)
  )
  val volatileBlocks = GaugeDef(
    MetricName("scarab_volatile_blocks"),
    "Blocks in the volatile chain (at most k)."
  )
  val immutableBlocks = GaugeDef(
    MetricName("scarab_immutable_blocks"),
    "Blocks in the immutable chain: those found at the start and those that became immutable since."
  )
  val ledgerUtxo = GaugeDef(
    MetricName("scarab_ledger_utxo_entries"),
    "Entries of the UTxO set, as of the start of the last epoch (updated once per epoch)."
  )
  val ledgerAccounts = GaugeDef(
    MetricName("scarab_ledger_accounts"),
    "Registered stake credentials, as of the start of the last epoch."
  )
  val ledgerPools = GaugeDef(
    MetricName("scarab_ledger_pools"),
    "Registered stake pools, as of the start of the last epoch."
  )

  // ---- scarab_*: storage

  val snapshotsWritten = CounterDef(
    MetricName("scarab_snapshots_written_total"),
    "Ledger snapshots written."
  )
  val snapshotsRestored = CounterDef(
    MetricName("scarab_snapshots_restored_total"),
    "Ledger snapshots the node started from."
  )
  val snapshotsMigrated = CounterDef(
    MetricName("scarab_snapshots_migrated_total"),
    "Files rewritten from an older storage format when the database was opened."
  )
  val snapshotProblems = CounterDef(
    MetricName("scarab_snapshot_problems_total"),
    "Snapshots skipped, not written or refused by the format, by kind.",
    Labels.one[SnapshotProblemLabel](LabelName("kind"))
  )
  val snapshotWriteSeconds = GaugeDef(
    MetricName("scarab_snapshot_last_write_seconds"),
    "Duration of the last snapshot written."
  )
  val snapshotBytes = GaugeDef(
    MetricName("scarab_snapshot_last_bytes"),
    "Size of the last snapshot written, in bytes."
  )
  val replayedBlocks = GaugeDef(
    MetricName("scarab_replay_blocks"),
    "Stored blocks applied again at the start (so far, then in total)."
  )
  val replaySeconds = GaugeDef(
    MetricName("scarab_replay_seconds"),
    "Time spent applying the stored blocks again at the start."
  )

  // ---- scarab_*: network

  val connectionsByState = GaugeDef(
    MetricName("scarab_connections"),
    "Connections the connection manager knows, by state.",
    Labels.one[ConnectionStateLabel](LabelName("state"))
  )
  val connectionEvents = CounterDef(
    MetricName("scarab_connection_events_total"),
    "What the connection manager did.",
    Labels.one[ConnectionEventLabel](LabelName("event"))
  )
  val inboundEvents = CounterDef(
    MetricName("scarab_inbound_events_total"),
    "What the inbound governor did on accepted connections.",
    Labels.one[InboundEventLabel](LabelName("event"))
  )
  val chainSyncRounds = CounterDef(
    MetricName("scarab_chainsync_rounds_total"),
    "Pipelined ChainSync rounds."
  )
  val chainSyncHeaders = CounterDef(
    MetricName("scarab_chainsync_headers_total"),
    "Headers (and rollbacks) received over ChainSync."
  )
  val chainSyncRoundSeconds = HistogramDef(
    MetricName("scarab_chainsync_round_seconds"),
    "Time of a pipelined ChainSync round, from the first request to the last reply.",
    noLabels,
    Buckets.of(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5)
  )
  val blockFetchSeconds = HistogramDef(
    MetricName("scarab_blockfetch_seconds"),
    "Time to fetch a block on its own, from the request to the block.",
    noLabels,
    Buckets.of(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5)
  )
  val headersValidated = CounterDef(
    MetricName("scarab_headers_validated_total"),
    "Headers that passed every header rule before their block was fetched."
  )
  val headersUnchecked = CounterDef(
    MetricName("scarab_headers_unchecked_total"),
    "Headers left to the validation of their block."
  )
  val headersWaited = CounterDef(
    MetricName("scarab_headers_waited_total"),
    "Headers that waited for the chain to advance before they could be checked."
  )

  // ---- scarab_*: scheduling (D8)

  val timerSamples = CounterDef(
    MetricName("scarab_scheduling_timer_samples_total"),
    "Timer samples taken by the scheduling monitor."
  )
  val timerLatenessMax = GaugeDef(
    MetricName("scarab_scheduling_timer_lateness_max_seconds"),
    "The latest a timer fired beyond its due time, since the start."
  )
  val timersLate = CounterDef(
    MetricName("scarab_scheduling_timers_late_total"),
    "Timers that fired more than `over` milliseconds late.",
    Labels.one[LatenessLabel](LabelName("over_ms"))
  )
  val cpuSteps = CounterDef(
    MetricName("scarab_cpu_steps_total"),
    "CPU-bound steps run on the follower's pool."
  )
  val cpuLongestStep = GaugeDef(
    MetricName("scarab_cpu_longest_step_seconds"),
    "The longest CPU-bound step, since the start."
  )

  // ---- jvm_* and process_*, after the Prometheus JVM client

  val jvmMemoryUsed = GaugeDef(
    MetricName("jvm_memory_bytes_used"),
    "Memory in use, by area.",
    Labels.one[AreaLabel](LabelName("area"))
  )
  val jvmMemoryCommitted = GaugeDef(
    MetricName("jvm_memory_bytes_committed"),
    "Memory committed by the JVM, by area.",
    Labels.one[AreaLabel](LabelName("area"))
  )
  val jvmMemoryMax = GaugeDef(
    MetricName("jvm_memory_bytes_max"),
    "Most memory the JVM may use, by area (-1 when unlimited).",
    Labels.one[AreaLabel](LabelName("area"))
  )
  val jvmGcCollections = CounterDef(
    MetricName("jvm_gc_collections_total"),
    "Garbage collections, by collector.",
    Labels.one[String](LabelName("gc"))
  )
  val jvmGcSeconds = CounterDef(
    MetricName("jvm_gc_collection_seconds_total"),
    "Time spent in garbage collection (approximate, as the JVM reports it), by collector.",
    Labels.one[String](LabelName("gc"))
  )
  val jvmThreads = GaugeDef(
    MetricName("jvm_threads_current"),
    "Live threads."
  )
  val jvmClasses = GaugeDef(
    MetricName("jvm_classes_loaded"),
    "Classes currently loaded."
  )
  val processCpuSeconds = CounterDef(
    MetricName("process_cpu_seconds_total"),
    "CPU time of the process (user and system), in seconds."
  )
  val processResident = GaugeDef(
    MetricName("process_resident_memory_bytes"),
    "Resident memory of the process, in bytes."
  )
  val processOpenFds = GaugeDef(
    MetricName("process_open_fds"),
    "Open file descriptors."
  )
  val processStart = GaugeDef(
    MetricName("process_start_time_seconds"),
    "Start of the process, in seconds since the epoch of 1970."
  )

  /** Every definition above; the registry creates their counters and histograms at zero. A test
    * checks that no definition of this object is missing here and that no two share a name.
    */
  val all: List[MetricDef] = List(
    blockNum,
    slotNum,
    epoch,
    slotInEpoch,
    density,
    forks,
    nodeStartTime,
    txsInMempool,
    mempoolBytes,
    txsProcessed,
    mempoolRejected,
    mempoolDropped,
    mempoolAdopted,
    forgeAboutToLead,
    forgeNodeIsLeader,
    forgeNodeNotLeader,
    forgeForged,
    forgeAdopted,
    forgeDidntAdopt,
    blocksForged,
    nodeIsLeader,
    nodeCannotForge,
    slotsMissed,
    forgedSlotLast,
    forgingEnabled,
    currentKesPeriod,
    remainingKesPeriods,
    opCertStartKesPeriod,
    opCertExpiryKesPeriod,
    forgeSkipped,
    forgeAdoptionSeconds,
    inboundConns,
    outboundConns,
    duplexConns,
    buildInfo,
    currentEra,
    blocksApplied,
    blockApplySeconds,
    validationErrors,
    peerTipSlot,
    peerTipBlock,
    peerReconnects,
    rollbacks,
    rollbackDepth,
    volatileBlocks,
    immutableBlocks,
    ledgerUtxo,
    ledgerAccounts,
    ledgerPools,
    snapshotsWritten,
    snapshotsRestored,
    snapshotsMigrated,
    snapshotProblems,
    snapshotWriteSeconds,
    snapshotBytes,
    replayedBlocks,
    replaySeconds,
    connectionsByState,
    connectionEvents,
    inboundEvents,
    chainSyncRounds,
    chainSyncHeaders,
    chainSyncRoundSeconds,
    blockFetchSeconds,
    headersValidated,
    headersUnchecked,
    headersWaited,
    timerSamples,
    timerLatenessMax,
    timersLate,
    cpuSteps,
    cpuLongestStep,
    jvmMemoryUsed,
    jvmMemoryCommitted,
    jvmMemoryMax,
    jvmGcCollections,
    jvmGcSeconds,
    jvmThreads,
    jvmClasses,
    processCpuSeconds,
    processResident,
    processOpenFds,
    processStart
  )
