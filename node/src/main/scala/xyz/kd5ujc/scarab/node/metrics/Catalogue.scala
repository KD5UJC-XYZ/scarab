// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import xyz.kd5ujc.scarab.ledger.BlockRule
import xyz.kd5ujc.scarab.node.forge.ForgeEpochStats
import xyz.kd5ujc.scarab.network.selection.{Churn, GovernorState, Origin}
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

/** Where the start has got to: reading the snapshot and opening the stores, applying the stored
  * blocks again, or following the chain. Exactly one is current.
  */
enum PhaseLabel derives LabelValue:
  case Restoring, Replaying, Following

/** What the connection manager did ([[xyz.kd5ujc.scarab.network.ConnectionEvent]]). */
enum ConnectionEventLabel derives LabelValue:
  case Opened, Changed, Refused, Closed

/** What the inbound governor did on a connection ([[xyz.kd5ujc.scarab.node.InboundEvent]]). */
enum InboundEventLabel derives LabelValue:
  case Accepted, Duplex, Unidirectional, Following, SessionEnded, SessionReturned, SessionDropped,
    CachedBlockRejected, Closed, Refused

/** What the peer governor did to a peer, or learnt of it
  * ([[xyz.kd5ujc.scarab.network.selection.Trace]]).
  */
enum PeerTransitionLabel derives LabelValue:
  case PromotedWarm, PromotedHot, DemotedHot, DemotedCold, PromoteFailed, DemoteFailed, Lost,
    Forgotten

/** The targets of the governor that are exported. */
enum TargetLabel derives LabelValue:
  case Root, Known, Established, Active, KnownBigLedger, EstablishedBigLedger, ActiveBigLedger

/** Where a known peer came from ([[xyz.kd5ujc.scarab.network.selection.Origin]]). */
given LabelValue[Origin] = LabelValue.derived

/** The two stake maps of the ledger-peers worker. */
enum LedgerMapLabel derives LabelValue:
  case Ordinary, Big

/** A step of the churn governor's round ([[Churn.Step]]), by its name. */
given LabelValue[Churn.Step] = LabelValue.derived

/** How a churn step ended: its counters met the targets, or its timeout passed first. */
enum ChurnResultLabel derives LabelValue:
  case Reached, TimedOut

/** The two boards of the peer metrics. */
enum MetricsBoardLabel derives LabelValue:
  case Headers, Blocks

/** The state of the Genesis State Machine. */
enum GsmStateLabel derives LabelValue:
  case PreSyncing, Syncing, CaughtUp

/** The fetch mode. */
enum FetchModeLabel derives LabelValue:
  case BulkSync, Deadline

/** Why the mempool refused a transaction ([[xyz.kd5ujc.scarab.node.mempool.Rejection]]). */
enum RejectionLabel derives LabelValue:
  case Malformed, AlreadyHeld, Ledger, Full, WrongEra, NoLedger, InputDefect, RulesDefect

object RejectionLabel:
  def of(reason: Rejection): RejectionLabel = reason match
    case Rejection.Malformed(_)   => Malformed
    case Rejection.AlreadyHeld(_) => AlreadyHeld
    case Rejection.Ledger(_)      => Ledger
    case Rejection.Limits(_, _)   => Ledger
    case Rejection.Full(_, _)     => Full
    case Rejection.WrongEra(_)    => WrongEra
    case Rejection.NoLedger(_)    => NoLedger
    case Rejection.InputDefect(_) => InputDefect
    case Rejection.RulesDefect(_) => RulesDefect

/** Why the forger gave a slot up before the leader check: the reference's `TraceSlotIsImmutable`,
  * `TraceBlockFromFuture` and `TraceNoLedgerView`. `Other` remains as a zero-valued series for
  * compatibility.
  */
enum SkipLabel derives LabelValue:
  case SlotIsImmutable, BlockFromFuture, NoLedgerView, Other

object SkipLabel:
  def of(reason: ForgeEpochStats.Skip): SkipLabel = reason match
    case ForgeEpochStats.Skip.SlotIsImmutable => SlotIsImmutable
    case ForgeEpochStats.Skip.BlockFromFuture => BlockFromFuture
    case ForgeEpochStats.Skip.NoLedgerView    => NoLedgerView

/** What became of a reload of the KES key and certificate: the credentials were replaced (or
  * confirmed), or the reload was refused and the old ones kept.
  */
enum ReloadLabel derives LabelValue:
  case Reloaded, Refused

/** Which snapshot trouble: an unreadable file left alone, a write that failed, a state the format
  * refused.
  */
enum SnapshotProblemLabel derives LabelValue:
  case Skipped, Failed, Refused, Unbound

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
  * docs mark as verified, see `docs/running/metrics.md`); `scarab_*`, `jvm_*` and `process_*` are
  * ours, after the Prometheus naming conventions (`_total` for counters, base units, `_seconds` and
  * `_bytes`).
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
  val mempoolRemoved = CounterDef(
    MetricName("scarab_mempool_removed_total"),
    "Transactions removed from the mempool on request although they still apply (a forged block that our own chain refused)."
  )

  // ---- forging (only on a node that forges): the counters of the loop's traces, cardano-node
  // 11.1.3 `Tracers/Consensus.hs` 1507-1538

  val forgeAboutToLead = CounterDef(
    MetricName("cardano_node_metrics_Forge_about_to_lead_counter"),
    "Slots the forging loop started (TraceStartLeadershipCheck)."
  )
  val forgeSlotIsImmutable = CounterDef(
    MetricName("cardano_node_metrics_Forge_slot_is_immutable_counter"),
    "Slots given up because the immutable tip is in the slot or a later one (TraceSlotIsImmutable)."
  )
  val forgeBlockFromFuture = CounterDef(
    MetricName("cardano_node_metrics_Forge_block_from_future_counter"),
    "Slots given up because the tip of the chain is in a later slot (TraceBlockFromFuture)."
  )
  val forgeCouldNotForge = CounterDef(
    MetricName("cardano_node_metrics_Forge_could_not_forge_counter"),
    "Slots given up for the ledger view or a leader slot that could not be forged (TraceNoLedgerState, TraceNoLedgerView, TraceNodeCannotForge)."
  )
  val forgeNodeIsLeader = CounterDef(
    MetricName("cardano_node_metrics_Forge_node_is_leader_counter"),
    "Slots the pool led and could forge in (TraceNodeIsLeader)."
  )
  val forgeNodeNotLeader = CounterDef(
    MetricName("cardano_node_metrics_Forge_node_not_leader_counter"),
    "Slots the pool did not lead (TraceNodeNotLeader)."
  )
  val forgeForged = CounterDef(
    MetricName("cardano_node_metrics_Forge_forged_counter"),
    "Blocks forged (TraceForgedBlock)."
  )
  val forgeAdopted = CounterDef(
    MetricName("cardano_node_metrics_Forge_adopted_counter"),
    "Forged blocks that became the tip of our chain (TraceAdoptedBlock)."
  )
  val forgeForgedInvalid = CounterDef(
    MetricName("cardano_node_metrics_Forge_forged_invalid_counter"),
    "Forged blocks our own rules refused (TraceForgedInvalidBlock)."
  )
  val forgeDidntAdopt = CounterDef(
    MetricName("cardano_node_metrics_Forge_didnt_adopt_counter"),
    "Forged blocks, not refused by our rules, that did not become the tip of our chain (TraceDidntAdoptBlock)."
  )
  val forgeStateUpdateError = GaugeDef(
    MetricName("cardano_node_metrics_Forge_StateUpdateError_int"),
    "The slot of the last failed update of the forge state (TraceForgeStateUpdateError)."
  )
  val blocksForged = GaugeDef(
    MetricName("cardano_node_metrics_blocksForged_int"),
    "Blocks forged since the start, as of the start of the current slot (ForgingStats)."
  )
  val nodeIsLeader = GaugeDef(
    MetricName("cardano_node_metrics_nodeIsLeader_int"),
    "Slots the pool led and could forge in since the start, as of the start of the current slot (ForgingStats)."
  )
  val nodeCannotForge = GaugeDef(
    MetricName("cardano_node_metrics_nodeCannotForge_int"),
    "Slots the pool led but could not forge in since the start (TraceNodeCannotForge), as of the start of the current slot (ForgingStats)."
  )
  val slotsMissed = GaugeDef(
    MetricName("cardano_node_metrics_slotsMissed_int"),
    "Slots missed since the start, as the reference's ForgingStats counts them at each slot the pool does not lead."
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
    "The KES key's period after the last update of the forge state; 0 after a failed update."
  )
  val remainingKesPeriods = GaugeDef(
    MetricName("cardano_node_metrics_remainingKESPeriods_int"),
    "KES periods left before the operational certificate expires, after the last update of the forge state; 0 after a failed update."
  )
  val opCertStartKesPeriod = GaugeDef(
    MetricName("cardano_node_metrics_operationalCertificateStartKESPeriod_int"),
    "The first KES period of the operational certificate."
  )
  val opCertExpiryKesPeriod = GaugeDef(
    MetricName("cardano_node_metrics_operationalCertificateExpiryKESPeriod_int"),
    "The KES period at which the operational certificate expires."
  )
  val opCertCounter = GaugeDef(
    MetricName("scarab_opcert_counter"),
    "The issue counter of the operational certificate in use."
  )
  val kesReloads = CounterDef(
    MetricName("scarab_kes_reloads_total"),
    "Reloads of the KES key and operational certificate (SIGHUP), by outcome.",
    Labels.one[ReloadLabel](LabelName("outcome"))
  )
  val kesPersistFailures = CounterDef(
    MetricName("scarab_kes_persist_failures_total"),
    "Evolved KES keys that could not be written back to the key file."
  )
  val forgeSkipped = CounterDef(
    MetricName("scarab_forge_skipped_total"),
    "Slots the forger gave up before the leader check, by reason.",
    Labels.one[SkipLabel](LabelName("reason"))
  )
  val forgeReplaced = CounterDef(
    MetricName("scarab_forge_replaced_total"),
    "Forged blocks adopted and later replaced on our chain by another block at their height."
  )
  val forgeFailed = CounterDef(
    MetricName("scarab_forge_failed_total"),
    "Slots given up for a reason of Scarab's own, which the reference does not have (forgeFailed)."
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
  val followerStalls = CounterDef(
    MetricName("scarab_follower_stalls_total"),
    "Times the follower went the stall-minutes without extending its chain while a peer announced a longer one."
  )
  val followerStalled = GaugeDef(
    MetricName("scarab_follower_stalled"),
    "1 while the follower is stalled (see scarab_follower_stalls_total), else 0."
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
    "Snapshots skipped, not written, refused by the format or restored without a checkpoint binding, by kind.",
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
  val receivedBlocks = GaugeDef(
    MetricName("scarab_received_blocks"),
    "Received blocks in the index of the received-block log (bodies on disk)."
  )
  val receivedCacheBlocks = GaugeDef(
    MetricName("scarab_received_cache_blocks"),
    "Received bodies held in memory in front of the received-block log."
  )
  val receivedCacheBytes = GaugeDef(
    MetricName("scarab_received_cache_bytes"),
    "Bytes of the received bodies held in memory, never more than the bound."
  )
  val receivedCacheLimit = GaugeDef(
    MetricName("scarab_received_cache_limit_bytes"),
    "The bound of the received-body cache, in bytes."
  )
  val receivedEvictions = CounterDef(
    MetricName("scarab_received_cache_evictions_total"),
    "Received bodies that left the cache for its bound (they stay readable from the log)."
  )
  val receivedReads = CounterDef(
    MetricName("scarab_received_reads_total"),
    "Received bodies read back from the received-block log."
  )
  val receivedUnreadable = CounterDef(
    MetricName("scarab_received_unreadable_total"),
    "Received-block records that failed their checksum when read back, and were dropped."
  )
  val replayedBlocks = GaugeDef(
    MetricName("scarab_replay_blocks"),
    "Stored blocks applied again at the start (so far, then in total)."
  )
  val replaySeconds = GaugeDef(
    MetricName("scarab_replay_seconds"),
    "Time spent applying the stored blocks again at the start."
  )
  val nodePhase = GaugeDef(
    MetricName("scarab_node_phase"),
    "1 for the phase the start has reached (Restoring, Replaying, Following), 0 for the others.",
    Labels.one[PhaseLabel](LabelName("phase"))
  )
  val replayTargetBlock = GaugeDef(
    MetricName("scarab_replay_target_block"),
    "Block number of the immutable tip, where the replay of the stored blocks ends."
  )
  val replayTargetSlot = GaugeDef(
    MetricName("scarab_replay_target_slot"),
    "Slot of the immutable tip, where the replay of the stored blocks ends."
  )
  val replayBlock = GaugeDef(
    MetricName("scarab_replay_block"),
    "Block number of the last stored block reported as applied again (every progress interval)."
  )
  val replaySlot = GaugeDef(
    MetricName("scarab_replay_slot"),
    "Slot of the last stored block reported as applied again (every progress interval)."
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
  // Governor counters use the Haskell trace dispatcher's `peerSelection.*` names
  // (`PeerSelectionCounters.hs`); each gauge reads the corresponding GovernorState counter.
  // Deprecated WarmLocalRoots reports active local roots; HotLocalRoots reports established
  // local roots beyond the active set, preserving the reference's historical naming.
  /** Each gauge with the counter it reports. */
  val peerSelectionGauges: List[(GaugeDef[Unit], GovernorState.Counters => Int)] = List(
    (
      GaugeDef(MetricName("cardano_node_metrics_peerSelection_Cold_int"), "Number of cold peers."),
      _.cold
    ),
    (
      GaugeDef(MetricName("cardano_node_metrics_peerSelection_Warm_int"), "Number of warm peers."),
      _.warm
    ),
    (
      GaugeDef(MetricName("cardano_node_metrics_peerSelection_Hot_int"), "Number of hot peers."),
      _.hot
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ColdBigLedgerPeers_int"),
        "Number of cold big ledger peers."
      ),
      _.coldBigLedger
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmBigLedgerPeers_int"),
        "Number of warm big ledger peers."
      ),
      _.warmBigLedger
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_HotBigLedgerPeers_int"),
        "Number of hot big ledger peers."
      ),
      _.hotBigLedger
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmLocalRoots_int"),
        "Deprecated; as the reference computes it: the active local roots."
      ),
      _.activeLocalRoots
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_HotLocalRoots_int"),
        "Deprecated; as the reference computes it: the local roots established beyond the active."
      ),
      _.warmLocalRoots
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_RootPeers_int"),
        "Number of root peers."
      ),
      _.root
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_KnownPeers_int"),
        "Number of known peers."
      ),
      _.known
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ColdPeersPromotions_int"),
        "Number of cold peers being promoted."
      ),
      _.coldPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_EstablishedPeers_int"),
        "Number of established peers."
      ),
      _.established
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmPeersDemotions_int"),
        "Number of warm peers being demoted."
      ),
      _.warmDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmPeersPromotions_int"),
        "Number of warm peers being promoted."
      ),
      _.warmPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActivePeers_int"),
        "Number of active peers."
      ),
      _.active
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActivePeersDemotions_int"),
        "Number of active peers being demoted."
      ),
      _.activeDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_KnownBigLedgerPeers_int"),
        "Number of known big ledger peers."
      ),
      _.knownBigLedger
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ColdBigLedgerPeersPromotions_int"),
        "Number of cold big ledger peers being promoted."
      ),
      _.coldBigLedgerPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_EstablishedBigLedgerPeers_int"),
        "Number of established big ledger peers."
      ),
      _.establishedBigLedger
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmBigLedgerPeersDemotions_int"),
        "Number of warm big ledger peers being demoted."
      ),
      _.warmBigLedgerDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmBigLedgerPeersPromotions_int"),
        "Number of warm big ledger peers being promoted."
      ),
      _.warmBigLedgerPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveBigLedgerPeers_int"),
        "Number of active big ledger peers."
      ),
      _.activeBigLedger
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveBigLedgerPeersDemotions_int"),
        "Number of active big ledger peers being demoted."
      ),
      _.activeBigLedgerDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_KnownLocalRootPeers_int"),
        "Number of known local root peers."
      ),
      _.knownLocalRoots
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_EstablishedLocalRootPeers_int"),
        "Number of established local root peers."
      ),
      _.establishedLocalRoots
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmLocalRootPeersPromotions_int"),
        "Number of warm local root peers being promoted."
      ),
      _.warmLocalRootsPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveLocalRootPeers_int"),
        "Number of active local root peers."
      ),
      _.activeLocalRoots
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveLocalRootPeersDemotions_int"),
        "Number of active local root peers being demoted."
      ),
      _.activeLocalRootsDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_KnownNonRootPeers_int"),
        "Number of known peers that are not roots."
      ),
      _.knownNonRoot
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ColdNonRootPeersPromotions_int"),
        "Number of cold non-root peers being promoted."
      ),
      _.coldNonRootPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_EstablishedNonRootPeers_int"),
        "Number of established non-root peers."
      ),
      _.establishedNonRoot
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmNonRootPeersDemotions_int"),
        "Number of warm non-root peers being demoted."
      ),
      _.warmNonRootDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmNonRootPeersPromotions_int"),
        "Number of warm non-root peers being promoted."
      ),
      _.warmNonRootPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveNonRootPeers_int"),
        "Number of active non-root peers."
      ),
      _.activeNonRoot
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveNonRootPeersDemotions_int"),
        "Number of active non-root peers being demoted."
      ),
      _.activeNonRootDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_KnownBootstrapPeers_int"),
        "Number of known bootstrap peers."
      ),
      _.knownBootstrap
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ColdBootstrapPeersPromotions_int"),
        "Number of cold bootstrap peers being promoted."
      ),
      _.coldBootstrapPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_EstablishedBootstrapPeers_int"),
        "Number of established bootstrap peers."
      ),
      _.establishedBootstrap
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmBootstrapPeersDemotions_int"),
        "Number of warm bootstrap peers being demoted."
      ),
      _.warmBootstrapDemotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_WarmBootstrapPeersPromotions_int"),
        "Number of warm bootstrap peers being promoted."
      ),
      _.warmBootstrapPromotions
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveBootstrapPeers_int"),
        "Number of active bootstrap peers."
      ),
      _.activeBootstrap
    ),
    (
      GaugeDef(
        MetricName("cardano_node_metrics_peerSelection_ActiveBootstrapPeersDemotions_int"),
        "Number of active bootstrap peers being demoted."
      ),
      _.activeBootstrapDemotions
    )
  )
  val peerTransitions = CounterDef(
    MetricName("scarab_peer_selection_transitions_total"),
    "What the peer governor did to peers, and what became of it.",
    Labels.one[PeerTransitionLabel](LabelName("transition"))
  )
  val peerTargets = GaugeDef(
    MetricName("scarab_peer_selection_targets"),
    "The targets the peer governor works towards, for the ordinary peers and for the big ledger peers.",
    Labels.one[TargetLabel](LabelName("target"))
  )
  val peerOrigins = GaugeDef(
    MetricName("scarab_peer_selection_peers"),
    "The known peers by where they came from: a local root, a public root, a bootstrap peer, the ledger, a big ledger pool, or no longer a root.",
    Labels.one[Origin](LabelName("origin"))
  )
  val ledgerPeerPools = GaugeDef(
    MetricName("scarab_ledger_peers_pools"),
    "The pools of the ledger-peers worker's stake maps: the ordinary map, and the big ledger peers' (the first 90% of the stake).",
    Labels.one[LedgerMapLabel](LabelName("map"))
  )
  val peerBackoffSeconds = HistogramDef(
    MetricName("scarab_peer_selection_backoff_seconds"),
    "The wait before a peer whose promotion failed is tried again.",
    noLabels,
    Buckets.of(2.5, 5, 10, 20, 40, 80, 160, 320)
  )
  val churnRounds = CounterDef(
    MetricName("scarab_peer_selection_churn_rounds_total"),
    "Rounds of the churn governor begun (`TraceChurnMode`)."
  )
  val churnSteps = CounterDef(
    MetricName("scarab_peer_selection_churn_steps_total"),
    "Steps of the churn governor's rounds, by step and by whether the node met the targets or the step timed out.",
    Labels.two[Churn.Step, ChurnResultLabel](LabelName("step"), LabelName("result"))
  )
  val churnWaitSeconds = GaugeDef(
    MetricName("scarab_peer_selection_churn_wait_seconds"),
    "How long the churn governor sleeps before its next round, as last decided."
  )
  val peerMetricsBoard = GaugeDef(
    MetricName("scarab_peer_selection_peer_metrics_board_size"),
    "Slots held by the peer metrics boards (at most 180 each): the headers and the blocks whose first arrival is recorded.",
    Labels.one[MetricsBoardLabel](LabelName("board"))
  )
  val peerSensitive = GaugeDef(
    MetricName("scarab_peer_selection_sensitive"),
    "1 while the node uses only trusted peers because its ledger is too old and bootstrap peers are configured, else 0."
  )
  val outboundTrusted = GaugeDef(
    MetricName("scarab_outbound_connections_trusted"),
    "1 while the node is connected only to peers it trusts (the Honest Availability Assumption of the Genesis State Machine), else 0."
  )
  val gsmState = GaugeDef(
    MetricName("scarab_gsm_state"),
    "1 for the state of the Genesis State Machine, 0 for the others.",
    Labels.one[GsmStateLabel](LabelName("state"))
  )
  val fetchModeGauge = GaugeDef(
    MetricName("scarab_fetch_mode"),
    "1 for the fetch mode the node is in, 0 for the other.",
    Labels.one[FetchModeLabel](LabelName("mode"))
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
    mempoolRemoved,
    forgeAboutToLead,
    forgeSlotIsImmutable,
    forgeBlockFromFuture,
    forgeCouldNotForge,
    forgeNodeIsLeader,
    forgeNodeNotLeader,
    forgeForged,
    forgeAdopted,
    forgeForgedInvalid,
    forgeDidntAdopt,
    forgeStateUpdateError,
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
    opCertCounter,
    kesReloads,
    kesPersistFailures,
    forgeSkipped,
    forgeReplaced,
    forgeFailed,
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
    followerStalls,
    followerStalled,
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
    receivedBlocks,
    receivedCacheBlocks,
    receivedCacheBytes,
    receivedCacheLimit,
    receivedEvictions,
    receivedReads,
    receivedUnreadable,
    replayedBlocks,
    replaySeconds,
    nodePhase,
    replayTargetBlock,
    replayTargetSlot,
    replayBlock,
    replaySlot,
    connectionsByState,
    connectionEvents,
    inboundEvents,
    peerTransitions,
    peerTargets,
    peerOrigins,
    ledgerPeerPools,
    peerBackoffSeconds,
    churnRounds,
    churnSteps,
    churnWaitSeconds,
    peerMetricsBoard,
    peerSensitive,
    outboundTrusted,
    gsmState,
    fetchModeGauge,
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
  ) ++ peerSelectionGauges.map(_._1)
