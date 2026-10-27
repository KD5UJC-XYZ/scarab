<!-- SPDX-License-Identifier: Apache-2.0 -->
# Metrics

Part of [Running the node and the devnet scenarios](../running.md).

## Metrics

The node exports its measurements in the Prometheus text format (version 0.0.4), for a Prometheus and Grafana stack
to watch a long sync or a pool (roadmap J3, [#47](https://github.com/KD5UJC-XYZ/scarab/issues/47); the logging
slice is J3a, #92).

**Where.** `GET /metrics` on the query server (`--query-port`, loopback only, `127.0.0.1:7071` by default), and,
with `--metrics-listen <host>:<port>`, on a listener of its own that serves `/metrics` and nothing else. The
query server also serves the whole ledger state at `/state`, so do not bind it beyond loopback; bind the metrics
listener instead. For a scraper in a sibling container (the monitoring stack) start the node with

```sh
follow ... --metrics-listen 0.0.0.0:12798
```

and scrape `http://<node container>:12798/metrics`. 12798 is the port the Haskell node's Prometheus
backend conventionally uses (`PrometheusSimple ... 12798`); the listener is off unless the flag is given. A
Prometheus job:

```yaml
scrape_configs:
  - job_name: scarab
    scrape_interval: 15s
    static_configs:
      - targets: ['scarab-node:12798']
```

The listener starts at the beginning of `follow`, before the database is opened, so a start that replays many
stored blocks is on the dashboard while it replays (the Haskell node starts its metrics and tracing before it opens
the ChainDB too). What a scrape reads from a part of the node that does not exist yet (the chain tip, the mempool,
the connection table, the forger) is absent from the output, not zero: `cardano_node_metrics_blockNum_int` and the
others appear when the replay is over. During the start read `scarab_node_phase{phase}` (one series is 1:
`Restoring`, `Replaying`, `Following`), the target `scarab_replay_target_block` / `scarab_replay_target_slot` (the
immutable tip, where the replay ends; absent when the store holds no block), the progress `scarab_replay_block` /
`scarab_replay_slot` (the last block reported, every progress interval) and `scarab_replay_blocks` /
`scarab_replay_seconds`. Progress is `scarab_replay_block / scarab_replay_target_block`. The Haskell node reports the
replay as ChainDB traces (`ChainDB.ReplayBlock.LedgerReplay`, with the slot replayed and the target) and has no
Prometheus metric for it, so these names are ours. `--query-port` still starts after the replay.

A scrape is cheap (it reads the chain tip, the mempool, the connection table and the JVM; about 150 series on a
follower) and may be as frequent as once a second. The `devnet.py` scenarios start the follower with
`--metrics-listen 127.0.0.1:12798` (inside the devnet's network namespace).

**Names.** The measurements the Haskell node also makes carry its names, in the form its new tracing writes with
`PrometheusSimple suffix`: `cardano_node_metrics_<name>_int` for an integer gauge, `_real` for a floating point
one, `_counter` for a counter. They were taken from a running cardano-node 11.1.3 (the reference image): started
as a relay and as a forging pool on the devnet with `"TraceOptions": {"": {"backends": ["Stdout MachineFormat",
"PrometheusSimple suffix 127.0.0.1 12798"]}}`, scraped at `:12798/metrics`. The names the table marks "scraped
from 11.1.3" were in that output; the ones marked "not scraped" are in the binary's metric table but the run had
no mempool traffic or lost slot battle to make them appear. The rest is ours: `scarab_*` for what is specific
to this node, `jvm_*` and `process_*` (the names of the Prometheus JVM client) in place of the Haskell node's
`RTS_*`, `rts_gc_*`, `Mem_*` and `Stat_*`, which describe the GHC runtime. Our counters end in `_total`, a
duration is in seconds, a size in bytes. Every metric has `# HELP` and `# TYPE` (the Haskell node writes no
`# HELP`); an info series is written as a gauge of value 1 (the 0.0.4 format has no `info` type).

A counter that restarts from zero is a restart of the process: use `rate()` and `increase()`. Counters with
labels appear on their first event (a label combination that never happened has no series); counters without
labels and histograms exist from the start at zero.

**How it is built** (`node/src/main/scala/xyz/kd5ujc/scarab/node/metrics/`). `Metrics[F]` is an algebra with
`inc`, `advance` (a counter whose total is known elsewhere; it only moves up), `set` and `observe`, given through
`using`; the interpreter keeps the registry in a `Ref` and `Exposition.render` writes a snapshot. A metric is a
`CounterDef[L]`, `GaugeDef[L]` or `HistogramDef[L]` with a label type `L` (`Unit`, an enum, a pair): the
`Catalogue` is the closed list, a metric or label name that the format does not allow does not compile, and a
label is a member of an enum (`Era`, `BlockRule`, ...), so the number of series is bounded by the type.
Every part of the node reports what it does as a value of a closed event type; `EventMetrics` is the one place
where those events become measurements, and `EventSink.alongside` runs it beside the JSON log (the log is
unchanged). What is read when asked for, not when it happens (the chain tip, the mempool, the connection table,
the forger's and scheduling statistics, the JVM), is read by `Collectors` at each scrape. `MeteredRules` wraps
the block rules to time them and to count a refusal by rule family.

Meanings and units worth knowing before building a dashboard:

- `scarab_validation_errors_total{rule}`: `rule` is the family of the rule that refused a block (`byron`,
  `structure`, `decode`, `envelope`, `ledger`, `tpraos`, `praos`: the `rule` field of the `error` event). A block
  from a peer that the rules refuse stops the node (an `error` event, exit status 1), so on a follower the series
  is non-zero only for the instant before it exits; it also counts a block this node forged and then refused
  itself, which does not stop it.
- `scarab_blocks_applied_total{era}` counts blocks applied to the chain since the start (the `extended` events,
  replay of stored blocks excluded); `scarab_block_apply_seconds` is the time of the block rules for each of
  them (buckets from 0.5 ms to 16.4 s, doubling), so the rate of applied blocks is
  `rate(scarab_blocks_applied_total[1m])` and the mean time to apply is
  `rate(scarab_block_apply_seconds_sum[5m]) / rate(scarab_block_apply_seconds_count[5m])`.
- Sync progress: `cardano_node_metrics_slotNum_int / scarab_peer_tip_slot`, or in blocks
  `cardano_node_metrics_blockNum_int / scarab_peer_tip_block` (the tip the peer last announced).
- `scarab_era{era}` is 1 for the era of the tip and 0 for the other six.
- `scarab_immutable_blocks` is what the immutable store held at the start plus the blocks that became
  immutable since; `scarab_volatile_blocks` is at most `k`.
- `scarab_ledger_utxo_entries`, `_accounts` and `_pools` are as of the start of the last epoch (they change once
  per epoch, not per block) and are the `utxo`, `accounts` and `pools` of the `ledgerEpoch` log line.
- `scarab_snapshots_migrated_total` counts files rewritten from an older storage format when the database was
  opened; `scarab_snapshot_last_write_seconds` and `_bytes` describe the last snapshot written.
- `scarab_scheduling_*` and `scarab_cpu_*` are the whole-run measurements of `GET /scheduling` (roadmap D8): a
  timer that fires late means the node's threads were starved.
- `jvm_gc_collection_seconds_total` is the time the JVM reports for each collector (a counter, in seconds, as
  precise as the JVM's own millisecond count); `jvm_memory_bytes_max{area="nonheap"}` is -1 when the JVM sets no
  limit.

**Label values.** Each label is a member of a closed set, so a dashboard can list them: `era` is `Byron`,
`Shelley`, `Allegra`, `Mary`, `Alonzo`, `Babbage` or `Conway`; `rule` is `byron`, `structure`, `decode`, `envelope`,
`ledger`, `tpraos` or `praos`; `state` (of a connection) is `Reserved`, `Unnegotiated`, `Established`, `Terminating`
or `Terminated`; `event` is, for `scarab_connection_events_total`, `Opened`, `Changed`, `Refused` or `Closed`, and for
`scarab_inbound_events_total`, `Accepted`, `Duplex`, `Unidirectional`, `Following`, `SessionEnded`, `SessionReturned`,
`SessionDropped`, `CachedBlockRejected`, `Closed` or `Refused`; `reason` is, for `scarab_mempool_rejected_total`, `Malformed`,
`AlreadyHeld`, `Ledger`, `Full` or `WrongEra`, and for `scarab_forge_skipped_total`, `Busy`, `TipTooOld`,
`BlockFromFuture`, `SlotIsImmutable`, `NotConnected` or `Other`; `kind` (snapshots) is `Skipped`, `Failed` or `Refused`; `over_ms` is
`50` or `100`; `area` is `heap` or `nonheap`; `gc` is the name the JVM gives the collector (`G1 Young
Generation`, `G1 Old Generation`, `G1 Concurrent GC`); `version`, `scala_version` and `git_commit` of
`scarab_build_info` are the build's (`git_commit` is `unknown` when the build could not read `.git`). The series
of an enum label that never happened are absent, except `scarab_era` (written for every era after the first block),
`scarab_connections` and, on a node that forges, `scarab_forge_skipped_total`, which are written for every member.

**Differences in meaning from the Haskell node's metrics of the same name.**

- `cardano_node_metrics_density_real`: both are a number of blocks per slot over the volatile chain. Ours is
  the blocks of the volatile chain over the slots from its anchor (the immutable tip) to the tip; the Haskell
  node computes it over the fragment of its ChainDB (`fragmentChainDensity`, cardano-node
  `Cardano/Node/Tracing/Tracers/ChainDB.hs`; the formula is from the source as remembered, not read line by
  line in this work, and the two agree in magnitude on the devnet).
- `cardano_node_metrics_slotsMissed_int` is ours for the slots the forger skipped because it was still busy
  with an earlier one (the `busy` reason); the Haskell node counts the slots it failed to lead in time.
- `cardano_node_metrics_connectionManager_{inbound,outbound,duplex}Conns_int` count the connections of our
  connection manager (one per peer address, either direction); the Haskell manager has more states and counts
  all of them.
- `cardano_node_metrics_currentKESPeriod_int` and `remainingKESPeriods_int` appear at start, and again after every key evolution and every reload;
  `operationalCertificate{Start,Expiry}KESPeriod_int`, `scarab_opcert_counter` and `forging_enabled_int` at start and
  after every reload.
- The Haskell gauges `blocksForged_int`, `nodeIsLeader_int` and the `Forge_*_counter` counters are two views
  of the same counts; both are exported.

**Not exported** (cardano-node 11.1.3 has them; tracked in roadmap J3e): `cardano_node_metrics_served_block_counter`,
`served_header_counter` and `served_block_latest_counter` (what the node served to peers; only the inbound
sessions of `GET /connections` count headers), `utxoSize_int` and `delegMapSize_int` (see
`scarab_ledger_utxo_entries` for the UTxO set, once per epoch), `blockReplayProgress_real`, `txsSyncDuration*`,
`tipBlock` and `basicInfo`, `inboundGovernor_*`, the `blockfetchclient_*` series (see
`scarab_blockfetch_seconds`), and the GHC runtime's `RTS_*`, `rts_gc_*`, `Mem_*` and `Stat_*`.

The full list follows, in the order of the catalogue
(`node/src/main/scala/xyz/kd5ujc/scarab/node/metrics/Catalogue.scala`); `DocsSuite` fails when this
section and the catalogue differ.

### The chain

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_blockNum_int` | gauge |  | Block number of the tip of the chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_slotNum_int` | gauge |  | Slot number of the tip of the chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_epoch_int` | gauge |  | Epoch of the tip of the chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_slotInEpoch_int` | gauge |  | Slot of the tip of the chain within its epoch. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_density_real` | gauge |  | Chain density of the volatile chain: its blocks per slot, from its anchor (the immutable tip) to the tip. | same name; scraped from 11.1.3 (see "Differences in meaning") |
| `cardano_node_metrics_forks_counter` | counter |  | Switches to another fork (a rollback of at least one block) since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_node_start_time_int` | gauge |  | Start of the node, in seconds since the epoch of 1970. | same name; scraped from 11.1.3 |
| `scarab_build_info` | gauge | `version`, `scala_version`, `git_commit` | The build that is running (always 1). | `cardano_node_metrics_cardano_build_info` (an info series there) |
| `scarab_era` | gauge | `era` | 1 for the era of the tip of the chain, 0 for the others. | none |
| `scarab_blocks_applied_total` | counter | `era` | Blocks applied to the chain since the start, by era (replay of the stored blocks excluded). | none |
| `scarab_block_apply_seconds` | histogram |  | Time to validate a block and apply it to the ledger state. | none |
| `scarab_validation_errors_total` | counter | `rule` | Blocks the rules refused, by rule family. | none |
| `scarab_peer_tip_slot` | gauge |  | Slot of the tip of the peer's chain, as the peer last announced it. | none |
| `scarab_peer_tip_block` | gauge |  | Block number of the tip of the peer's chain, as the peer last announced it. | none |
| `scarab_peer_reconnects_total` | counter |  | Times the connection to the peer was lost and opened again. | none |
| `scarab_blocks_held_total` | counter |  | Times a block was not adopted because the evaluator failed on its scripts: no verdict, no blame; the chain stays behind it. | none |
| `scarab_follower_stalls_total` | counter |  | Times the follower went the stall-minutes without extending its chain while a peer announced a longer one. | none |
| `scarab_follower_stalled` | gauge |  | 1 while the follower is stalled (see scarab_follower_stalls_total), else 0. | none |
| `scarab_rollbacks_total` | counter |  | Rollbacks of the chain. | none |
| `scarab_rollback_depth_blocks` | histogram |  | Blocks rolled back at a time. | none |
| `scarab_volatile_blocks` | gauge |  | Blocks in the volatile chain (at most k). | none |
| `scarab_immutable_blocks` | gauge |  | Blocks in the immutable chain: those found at the start and those that became immutable since. | none |
| `scarab_ledger_utxo_entries` | gauge |  | Entries of the UTxO set, as of the start of the last epoch (updated once per epoch). | none |
| `scarab_ledger_accounts` | gauge |  | Registered stake credentials, as of the start of the last epoch. | none |
| `scarab_ledger_pools` | gauge |  | Registered stake pools, as of the start of the last epoch. | none |

### The mempool

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_txsInMempool_int` | gauge |  | Transactions in the mempool. | same name; in the 11.1.3 binary, not scraped (no traffic in the run) |
| `cardano_node_metrics_mempoolBytes_int` | gauge |  | Size of the transactions in the mempool, in bytes. | same name; in the 11.1.3 binary, not scraped (no traffic in the run) |
| `cardano_node_metrics_txsProcessedNum_counter` | counter |  | Transactions added to the mempool since the start. | same name; in the 11.1.3 binary, not scraped (no traffic in the run) |
| `scarab_mempool_rejected_total` | counter | `reason` | Transactions the mempool refused, by reason. | none (a trace message only) |
| `scarab_mempool_dropped_total` | counter |  | Held transactions dropped because the new tip no longer admits them. | none (a trace message only) |
| `scarab_mempool_adopted_total` | counter |  | Held transactions that appeared in a block of our chain. | none |
| `scarab_mempool_removed_total` | counter |  | Transactions removed from the mempool on request although they still apply (a forged block that our own chain refused). | none |

### Forging (only on a node started with `--forge`)

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_Forge_about_to_lead_counter` | counter |  | Slots checked for leadership. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_node_is_leader_counter` | counter |  | Slots the pool led. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_node_not_leader_counter` | counter |  | Slots checked that the pool did not lead. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_forged_counter` | counter |  | Blocks forged. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_adopted_counter` | counter |  | Forged blocks that became the tip of our chain. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_Forge_didnt_adopt_counter` | counter |  | Forged blocks that did not stay on our chain (lost slot battles, replaced later). | same name; from the binary's trace name `Forge.didnt-adopt`, not scraped (no such event in the run) |
| `cardano_node_metrics_blocksForged_int` | gauge |  | Blocks forged since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_nodeIsLeader_int` | gauge |  | Slots the pool led since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_nodeCannotForge_int` | gauge |  | Slots the pool led but could not forge in, since the start. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_slotsMissed_int` | gauge |  | Slots the forger skipped because it was still busy with an earlier one, since the start. | same name; scraped from 11.1.3 (see "Differences in meaning") |
| `cardano_node_metrics_forgedSlotLast_int` | gauge |  | Slot of the last block forged. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_forging_enabled_int` | gauge |  | 1 when the node has forging credentials. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_currentKESPeriod_int` | gauge |  | The KES period of the last block forged or of the last key evolution. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_remainingKESPeriods_int` | gauge |  | KES periods left before the operational certificate expires. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_operationalCertificateStartKESPeriod_int` | gauge |  | The first KES period of the operational certificate. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_operationalCertificateExpiryKESPeriod_int` | gauge |  | The KES period at which the operational certificate expires. | same name; scraped from 11.1.3 |
| `scarab_opcert_counter` | gauge |  | The issue counter of the operational certificate in use. | none |
| `scarab_kes_reloads_total` | counter | `outcome` | Reloads of the KES key and operational certificate (SIGHUP), by outcome. | none |
| `scarab_kes_persist_failures_total` | counter |  | Evolved KES keys that could not be written back to the key file. | none |
| `scarab_forge_skipped_total` | counter | `reason` | Slots the forger did not check, by reason. | none |
| `scarab_forge_adoption_seconds` | histogram |  | Time from the start of the slot to the adoption of the block forged in it. | none |

### Peers and connections

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `cardano_node_metrics_connectionManager_inboundConns_int` | gauge |  | Connections the peer opened to us. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_connectionManager_outboundConns_int` | gauge |  | Connections we opened to a peer. | same name; scraped from 11.1.3 |
| `cardano_node_metrics_connectionManager_duplexConns_int` | gauge |  | Connections on which both sides run the protocols. | same name; scraped from 11.1.3 |
| `scarab_connections` | gauge | `state` | Connections the connection manager knows, by state. | none |
| `scarab_connection_events_total` | counter | `event` | What the connection manager did. | none |
| `scarab_inbound_events_total` | counter | `event` | What the inbound governor did on accepted connections. | none |
| `scarab_chainsync_rounds_total` | counter |  | Pipelined ChainSync rounds. | none |
| `scarab_chainsync_headers_total` | counter |  | Headers (and rollbacks) received over ChainSync. | none |
| `scarab_chainsync_round_seconds` | histogram |  | Time of a pipelined ChainSync round, from the first request to the last reply. | none |
| `scarab_blockfetch_seconds` | histogram |  | Time to fetch a block on its own, from the request to the block. | none |
| `scarab_headers_validated_total` | counter |  | Headers that passed every header rule before their block was fetched. | none |
| `scarab_headers_unchecked_total` | counter |  | Headers left to the validation of their block. | none |
| `scarab_headers_waited_total` | counter |  | Headers that waited for the chain to advance before they could be checked. | none |

### Peer selection (G1)

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `scarab_peer_selection_transitions_total` | counter | `transition` | What the peer governor did to peers, and what became of it. | none |
| `scarab_peer_selection_targets` | gauge | `target` | The targets the peer governor works towards, for the ordinary peers and for the big ledger peers. | none |
| `scarab_peer_selection_peers` | gauge | `origin` | The known peers by where they came from: a local root, a public root, a bootstrap peer, the ledger, a big ledger pool, or no longer a root. | none |
| `scarab_ledger_peers_pools` | gauge | `map` | The pools of the ledger-peers worker's stake maps: the ordinary map, and the big ledger peers' (the first 90% of the stake). | none |
| `scarab_peer_selection_backoff_seconds` | histogram |  | The wait before a peer whose promotion failed is tried again. | none |
| `scarab_peer_selection_churn_rounds_total` | counter |  | Rounds of the churn governor begun (`TraceChurnMode`). | none |
| `scarab_peer_selection_churn_steps_total` | counter | `step`, `result` | Steps of the churn governor's rounds, by step and by whether the node met the targets or the step timed out. | none |
| `scarab_peer_selection_churn_wait_seconds` | gauge |  | How long the churn governor sleeps before its next round, as last decided. | none |
| `scarab_peer_selection_peer_metrics_board_size` | gauge | `board` | Slots held by the peer metrics boards (at most 180 each): the headers and the blocks whose first arrival is recorded. | none |
| `scarab_peer_selection_sensitive` | gauge |  | 1 while the node uses only trusted peers because its ledger is too old and bootstrap peers are configured, else 0. | none |
| `scarab_outbound_connections_trusted` | gauge |  | 1 while the node is connected only to peers it trusts (the Honest Availability Assumption of the Genesis State Machine), else 0. | none |
| `scarab_gsm_state` | gauge | `state` | 1 for the state of the Genesis State Machine, 0 for the others. | none |
| `scarab_fetch_mode` | gauge | `mode` | 1 for the fetch mode the node is in, 0 for the other. | none |
| `cardano_node_metrics_peerSelection_Cold_int` | gauge |  | Number of cold peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_Warm_int` | gauge |  | Number of warm peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_Hot_int` | gauge |  | Number of hot peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdBigLedgerPeers_int` | gauge |  | Number of cold big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBigLedgerPeers_int` | gauge |  | Number of warm big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_HotBigLedgerPeers_int` | gauge |  | Number of hot big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmLocalRoots_int` | gauge |  | Deprecated; as the reference computes it: the active local roots. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_HotLocalRoots_int` | gauge |  | Deprecated; as the reference computes it: the local roots established beyond the active. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_RootPeers_int` | gauge |  | Number of root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownPeers_int` | gauge |  | Number of known peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdPeersPromotions_int` | gauge |  | Number of cold peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedPeers_int` | gauge |  | Number of established peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmPeersDemotions_int` | gauge |  | Number of warm peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmPeersPromotions_int` | gauge |  | Number of warm peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActivePeers_int` | gauge |  | Number of active peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActivePeersDemotions_int` | gauge |  | Number of active peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownBigLedgerPeers_int` | gauge |  | Number of known big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdBigLedgerPeersPromotions_int` | gauge |  | Number of cold big ledger peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedBigLedgerPeers_int` | gauge |  | Number of established big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBigLedgerPeersDemotions_int` | gauge |  | Number of warm big ledger peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBigLedgerPeersPromotions_int` | gauge |  | Number of warm big ledger peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBigLedgerPeers_int` | gauge |  | Number of active big ledger peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBigLedgerPeersDemotions_int` | gauge |  | Number of active big ledger peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownLocalRootPeers_int` | gauge |  | Number of known local root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedLocalRootPeers_int` | gauge |  | Number of established local root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmLocalRootPeersPromotions_int` | gauge |  | Number of warm local root peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveLocalRootPeers_int` | gauge |  | Number of active local root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveLocalRootPeersDemotions_int` | gauge |  | Number of active local root peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownNonRootPeers_int` | gauge |  | Number of known peers that are not roots. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdNonRootPeersPromotions_int` | gauge |  | Number of cold non-root peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedNonRootPeers_int` | gauge |  | Number of established non-root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmNonRootPeersDemotions_int` | gauge |  | Number of warm non-root peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmNonRootPeersPromotions_int` | gauge |  | Number of warm non-root peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveNonRootPeers_int` | gauge |  | Number of active non-root peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveNonRootPeersDemotions_int` | gauge |  | Number of active non-root peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_KnownBootstrapPeers_int` | gauge |  | Number of known bootstrap peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ColdBootstrapPeersPromotions_int` | gauge |  | Number of cold bootstrap peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_EstablishedBootstrapPeers_int` | gauge |  | Number of established bootstrap peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBootstrapPeersDemotions_int` | gauge |  | Number of warm bootstrap peers being demoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_WarmBootstrapPeersPromotions_int` | gauge |  | Number of warm bootstrap peers being promoted. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBootstrapPeers_int` | gauge |  | Number of active bootstrap peers. | same name; in the source of 11.1.3, not scraped |
| `cardano_node_metrics_peerSelection_ActiveBootstrapPeersDemotions_int` | gauge |  | Number of active bootstrap peers being demoted. | same name; in the source of 11.1.3, not scraped |

### Storage

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `scarab_snapshots_written_total` | counter |  | Ledger snapshots written. | none |
| `scarab_snapshots_restored_total` | counter |  | Ledger snapshots the node started from. | none |
| `scarab_snapshots_migrated_total` | counter |  | Files rewritten from an older storage format when the database was opened. | none |
| `scarab_snapshot_problems_total` | counter | `kind` | Snapshots skipped, not written, refused by the format or restored without a checkpoint binding, by kind. | none |
| `scarab_snapshot_last_write_seconds` | gauge |  | Duration of the last snapshot written. | none |
| `scarab_snapshot_last_bytes` | gauge |  | Size of the last snapshot written, in bytes. | none |
| `scarab_received_blocks` | gauge |  | Received blocks in the index of the received-block log (bodies on disk). | none |
| `scarab_received_cache_blocks` | gauge |  | Received bodies held in memory in front of the received-block log. | none |
| `scarab_received_cache_bytes` | gauge |  | Bytes of the received bodies held in memory, never more than the bound. | none |
| `scarab_received_cache_limit_bytes` | gauge |  | The bound of the received-body cache, in bytes. | none |
| `scarab_received_cache_evictions_total` | counter |  | Received bodies that left the cache for its bound (they stay readable from the log). | none |
| `scarab_received_reads_total` | counter |  | Received bodies read back from the received-block log. | none |
| `scarab_received_unreadable_total` | counter |  | Received-block records that failed their checksum when read back, and were dropped. | none |
| `scarab_replay_blocks` | gauge |  | Stored blocks applied again at the start (so far, then in total). | none |
| `scarab_replay_seconds` | gauge |  | Time spent applying the stored blocks again at the start. | none |
| `scarab_node_phase` | gauge | `phase` | 1 for the phase the start has reached (Restoring, Replaying, Following), 0 for the others. | none |
| `scarab_replay_target_block` | gauge |  | Block number of the immutable tip, where the replay of the stored blocks ends. | none |
| `scarab_replay_target_slot` | gauge |  | Slot of the immutable tip, where the replay of the stored blocks ends. | none |
| `scarab_replay_block` | gauge |  | Block number of the last stored block reported as applied again (every progress interval). | none |
| `scarab_replay_slot` | gauge |  | Slot of the last stored block reported as applied again (every progress interval). | none |

### Scheduling (D8)

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `scarab_scheduling_timer_samples_total` | counter |  | Timer samples taken by the scheduling monitor. | none |
| `scarab_scheduling_timer_lateness_max_seconds` | gauge |  | The latest a timer fired beyond its due time, since the start. | none |
| `scarab_scheduling_timers_late_total` | counter | `over_ms` | Timers that fired more than `over` milliseconds late. | none |
| `scarab_cpu_steps_total` | counter |  | CPU-bound steps run on the follower's pool. | none |
| `scarab_cpu_longest_step_seconds` | gauge |  | The longest CPU-bound step, since the start. | none |

### The JVM and the process

| Metric | Type | Labels | Meaning | cardano-node 11.1.3 |
|---|---|---|---|---|
| `jvm_memory_bytes_used` | gauge | `area` | Memory in use, by area. | `cardano_node_metrics_RTS_gcLiveBytes_int` (GHC) |
| `jvm_memory_bytes_committed` | gauge | `area` | Memory committed by the JVM, by area. | none |
| `jvm_memory_bytes_max` | gauge | `area` | Most memory the JVM may use, by area (-1 when unlimited). | none |
| `jvm_gc_collections_total` | counter | `gc` | Garbage collections, by collector. | `cardano_node_metrics_RTS_gcMajorNum_int` (GHC) |
| `jvm_gc_collection_seconds_total` | counter | `gc` | Time spent in garbage collection (approximate, as the JVM reports it), by collector. | none |
| `jvm_threads_current` | gauge |  | Live threads. | none |
| `jvm_classes_loaded` | gauge |  | Classes currently loaded. | none |
| `process_cpu_seconds_total` | counter |  | CPU time of the process (user and system), in seconds. | `cardano_node_metrics_Stat_cputicks_int` |
| `process_resident_memory_bytes` | gauge |  | Resident memory of the process, in bytes. | `cardano_node_metrics_Mem_resident_int` |
| `process_open_fds` | gauge |  | Open file descriptors. | none |
| `process_start_time_seconds` | gauge |  | Start of the process, in seconds since the epoch of 1970. | `cardano_node_metrics_node_start_time_int`, and `basicInfo` |
