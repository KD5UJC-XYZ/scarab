<!-- SPDX-License-Identifier: Apache-2.0 -->
# Capture and the devnet scenarios

Part of [Running the node and the devnet scenarios](../running.md).

## `capture`: recording blocks

`xyz.kd5ujc.scarab.node.tools.CaptureMain` records raw blocks (one hex-encoded block per line) for offline replay tests:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.tools.CaptureMain capture \
  --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hashhex> --from-block-no N]
```

A non-genesis `--from` requires its exact `--from-block-no` for the native ChainSync policy.
It starts at genesis (or after `--from`) and keeps going until the recorded blocks are 10 deep, so
none is rolled back; `--blocks all` records up to 10 blocks behind the tip. The fixtures under
`fixtures/preview`, `fixtures/mainnet` and `fixtures/eras` were captured with it.

## Devnet scenarios (`scripts/devnet.py`)

`devnet.py` drives Docker containers: a `cardano-testnet` cluster of three Haskell pools from the
reference image, and the Scala node running from a frozen copy of the compiled classes in
`scarab-jdk:local`. The Scala node shares a reference container's network namespace because
the reference nodes bind loopback. Requirements: both images, the classpath file from "Images", Docker,
Python 3 (standard library only), roughly 10 minutes per scenario.

Run data lives in `${SCARAB_DATA_DIR:-/tmp/scarab}/<name>` (never in the checkout or the home
directory). `--name` selects the cluster name (default `scarab-devnet`, `scarab-eras` for `up-eras`), so a
second cluster can run beside another. Directories and sockets that containers of other uids must
write are made world-writable (mode 0777) on a local devnet; the key files are 0400.

```sh
python3 scripts/devnet.py <subcommand> [options]     # python3 scripts/devnet.py --help
python3 scripts/devnet.py down                       # remove containers and the run directory
```

| Subcommand | What it does | What it asserts |
|---|---|---|
| `soak [--seconds 600] [--every 20] [--seed 1] [--governance-extended] [--oracle]` | `up` + Scala follower + ADA, staking and Conway governance/Plutus V3 load + periodic comparison; `--governance-extended` adds the scenarios of [`govextra.py`](../../scripts/devnet/govextra.py) (below; needs `--seconds 900`) | exit status fails on any mismatched comparison or follower error; with `--governance-extended` also on a transaction the reference was expected to refuse but did not refuse for the named predicate failure, and on an extended stage that was not reached. Compares at matching tips: UTxO, fees, deposits, treasury, reserves, accounts, pools, DReps, snapshots, reward update, Praos nonces, governance state. At the end, while the follower is up, scrapes `/metrics` on the query server and on the `--metrics-listen` listener (`127.0.0.1:12798` in the devnet) and fails unless every series of the documented list is there, the text is well formed, `cardano_node_metrics_blockNum_int` and `_epoch_int` lie between the last `extended` event of the log read before and after the scrape, `scarab_blocks_applied_total` equals the number of `extended` events within the same bracket, every applied block was timed, and `scarab_validation_errors_total` is 0 (`metrics` in the soak's `summary.json`) |
| `e2e [--timeout 1500] [--until-epoch N] [--keep] [--no-load] [--no-compare] [--min-comparisons 3] [--oracle]` | `up-eras` (Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway) + follower from Origin + era-specific load + per-era ledger comparison; in Conway the chain is driven by governance to the highest protocol version the reference supports ("Protocol versions in the e2e", below) | the `eraTransition` lines are exactly the seven eras at the configured epochs; every era applied blocks; all Byron signatures verified; no validation or fatal errors; every comparison matched, at least `--min-comparisons` per Shelley-based era; every stage of the light extended set (see below) done, no transaction the reference must refuse accepted or refused for another reason; the chain reached the highest protocol version the reference supports; every version-specific check ran at a version it applies to; no protocol version the reference supports lacks a registered check (the coverage guard). Writes `e2e-summary.json` (with `extended`, `extended.versionChecks` and `protocol` blocks) and `compare.jsonl` |
| `peercut [--backlog-blocks 150] [--cut-after-blocks 30] [--delay 0.1] [--sync-timeout 240] [--follow-seconds 30]` | `up`, the pools grow a backlog, then the Scala node (no database) follows pool 1 through `scripts/devnet/lagproxy.py` (a TCP proxy that holds every chunk `--delay` seconds) and pool 2 directly; after `--cut-after-blocks` applied blocks the proxy goes silent (its connections stay open, nothing is forwarded), which the node sees only as ChainSync timeouts (issue 130) | the node reaches the reference tip through pool 2 and keeps extending, the lost peer is demoted with the closed `Transient` policy (timeout or the chain-window detachment tracked in #152), the governor made both peers hot (`peerSelectionPromote`) and demoted the silent one (`peerSelectionDemote`), no `Submarine` and no `stalled` and no `error` event, no unparsed output |
| `faults [--seed 1] [--duration 600] [--settle 90] [--schedule FILE]` | A seeded network-fault run (#224): the Scala node as pool 1 next to two Haskell pools, every link behind a proxy port of its own, `tc netem` latency and loss, partitions and heals, and crashes and restarts of the Scala node and the Haskell pools, drawn from the seed and written to `fault-schedule.json` before anything runs. Needs the kernel modules `sch_netem`, `sch_prio`, `cls_u32` and the reference image (which has `tc`); about `duration` + 3 minutes. See [the design](../design/adversarial-schedules.md#nightly-randomized-fault-run) | After the final heal all three tips become equal; every quiet window shows each node's chain growing; the Scala node rolls back no deeper than k, logs no error, unparsed output or Submarine, and extends its chain after each restart; no Haskell pool exits unplanned or reports a decoder failure. The failure output ends with the replay command |
| `relay [--seconds 300] [--peers 3] [--relay-mode ...] [--max-ntn-version 15\|16] [--keep]` | the Scala node follows the pools and serves a non-producing Haskell relay (`--listen`, `--db`, `--local-socket`); load is submitted through the relay | the Haskell relay syncs the whole chain from the Scala node and stays at the tip (lag samples every `--every` seconds), transactions travel relay, Scala mempool, pools, block, and one is submitted with `cardano-cli` to the Scala socket. G2: the Haskell relay connects to the Scala node (it is in the relay's topology; the Scala node has no `--peer` for it) and, with the default `--relay-mode InitiatorAndResponder`, the Scala node's own ChainSync client follows the relay over that same inbound duplex connection: `GET /connections` shows an established inbound duplex connection and an inbound session with an intersection that keeps receiving headers and whose peer tip is within 3 blocks of the pools', and the log has the `inbound` events `duplex` and `following`. G4: the relay's handshake trace (`relayHandshake` in the summary) shows what it proposed and that the Scala node accepted `NodeToNodeV_15` by default, or `NodeToNodeV_16` with `--max-ntn-version 16`, which sets `ExperimentalProtocolsEnabled` to true in both configurations (`cardano-testnet` writes true, so the default run turns it off for the two nodes under test; the pools keep theirs) |
| `ntc [--seconds 240] [--warmup 60] [--every 10] [--chain-sync-blocks 60] [--monitor-burst 4]` | a fresh cluster, the Scala node following pool 1 with `--local-socket` and `--db` (and no `--mempool-bytes`, so both nodes use the default of two blocks), and the ordinary and governance load running. Rounds of LocalStateQuery: the Scala node's volatile tip is acquired on the reference node by point, and every query of the battery (`scripts/devnet/ntcqueries.py`, about 55 queries built from the live state) goes to both. Then `cardano-cli conway query ...` (24 commands) against both sockets, each pair accepted only when the tip read before and after is the same block on both nodes. Then 23 refused transactions (`scripts/devnet/ntccases.py`: a witness datum nothing allows (`NotAllowedSupplementalDatums`), fee too small, inputs spent, value not conserved, validity interval, missing witness, small and wrong-network outputs, treasury value, registration, delegation, DRep, pool retirement, proposal, vote, size, size with an input that does not exist, withdrawal network) and one valid transaction through both sockets. In parallel with the query rounds, local ChainSync on both sockets (`scripts/devnet/ntclocal.py`): `--chain-sync-blocks` blocks from Origin (the Scala node reads its immutable store and then its volatile chain), 12 from the tip, 4 as a backlog from a recent block. Last, LocalTxMonitor: snapshots of both mempools walked step for step while bursts of `--monitor-burst` chained transactions go through the Scala socket (which forwards them to the reference node), `cardano-cli conway query tx-mempool info | next-tx | tx-exists` on both sockets at the same time, and `AwaitAcquire` on both | every query that is implemented gives equal bytes in every round (matched / total per query); every `cardano-cli` pair equal; every refusal equal as raw `ApplyTxErr` bytes and as the text `cardano-cli transaction submit` prints; the valid transaction is accepted by the Scala node and reaches a block; every block of local ChainSync equal as the raw item and the same first instruction (a roll-back to the point found); every pair of mempool snapshots with the same slot and transactions has equal raw answers (at least 3 rounds with transactions), the bracketed `tx-mempool` outputs are equal (one with transactions) and `AwaitAcquire` wakes on both. Queries not answered yet are listed separately as missing implementations; they are not conformance passes. The four ledger-state wrappers and bracketed CLI ledger-state output must match without field exclusions. Writes `ntc-summary.json` |
| `pool [--seconds 480] [--pool 1\|2\|3] [--keep]` | the Scala node is one of three pools: pool 1's Haskell node is never started and the Scala node forges with its keys; `slotsPerKESPeriod = 250` | blocks forged, adopted and on the final chain; the Haskell ledger's blocks-made count for the pool equals the Scala-forged blocks on the chain; every slot the reference node's `query leadership-schedule --current` gives the pool (asked once per epoch) is either forged or taken by another pool's block, and a missed one is reported with the forging loop's trace that ended the slot (`slotIsImmutable`, `blockFromFuture`, `noLedgerView`, `nodeNotLeader`, ...), as `not started` when the loop passed over the slot (no `startLeadershipCheck`), or as `forgeFailed` with its kind; load transactions in Scala blocks; at least one KES period crossed; no rollback deeper than `k`; Haskell pools agree on the tip; comparisons match; no error event and no `forgeFailed`, `forgeStateUpdateError`, `nodeCannotForge` or `forgedInvalidBlock`; the forger's own counters (`GET /forging`, per epoch: slots checked, leader slots, forged, adopted, lost slot battles, skips by reason, forge latency) agree with the log and, scraped from `GET /metrics` between two readings of `/forging`, the Haskell-named series `Forge_about_to_lead_counter`, `node_is_leader_counter`, `node_not_leader_counter`, `forged_counter`, `adopted_counter`, `didnt_adopt_counter`, `slot_is_immutable_counter` and `block_from_future_counter` lie between the two readings and the KES gauges exist and no block took more than ten slots from its slot's start to adoption (a sanity bound, not a benchmark). `--byron-start-offset N` writes the Byron genesis' `startTime` N seconds off the Shelley `systemStart` (the reference's clock follows the Byron one; the check that the Scala node does too). Records the host load; a run whose only misses are slots the loop did not start on a host with load above its CPU count exits 2 (inconclusive). Writes `pool-summary.json` (`forgerStats`, `slotClock`, `oracle`, `host`) |
| `kes-rotate [--seconds 300] [--max-kes-evolutions 24] [--rotate-threshold 21] [--rotate-every 10] [--min-rotations 2]` | `pool` with unattended KES rotation (H2a): `slotsPerKESPeriod = 250`, `maxKESEvolutions` 24, the Scala node reads its key and certificate through a `current` link and serves a local socket; `scripts/pool/rotate_kes.py` runs on a loop with the pool's cold key (never mounted into the node) and rotates every three periods | everything `pool` asserts, and: at least `--min-rotations` `kesReloaded` events with strictly increasing counters; one `forgeStarted` (no restart) and no `kesReloadRefused` or `kesPersistFailed`; every driver run exits 0; blocks forged with every certificate counter but perhaps the last, none with an older counter after a newer one and none before its certificate was reloaded; the pool's counter as a Haskell node reports it (`query kes-period-info`) equals the last counter on the final chain, so the Haskell pools took the blocks of every certificate; only the newest generation directory remains and no temporary key file; the key file was rewritten by the node after the last certificate was installed. Writes `pool-summary.json` with a `kesRotation` block |
| `restart [--seconds 20]` | a fresh cluster, the Scala node follows with `--db`, is stopped with SIGTERM and started again on the same database | the stop ends the JVM within 25 s and writes a shutdown snapshot (issue #89); the second start logs `snapshotRestored`; every snapshot file is current (`SCRBSNP7`). The retired `--migrate-from` old-writer round is no longer accepted; historical conversion evidence uses pinned source and an offline recipe |
| `v1-context [--eras] [--no-up]` | a fresh Conway cluster (or, with `--eras`, the cluster that walks Byron to Conway) and a set of transactions that each run one Plutus V1 script (some also V2 and V3 as controls): reference inputs, reference scripts, inline datums and Byron addresses in inputs, reference inputs and outputs, with the validity flag both ways; Alonzo 5 and 6, Babbage 7 and 8, Conway 9 (`--eras`) and the genesis protocol version of the Conway-only cluster | exit status fails on a case the reference decided otherwise than the case expects; writes `v1-context-cases.json` (the shape of `fixtures/reference/v1-context`, see [its README](../../fixtures/reference/README.md)); `follow` against the same cluster validates the blocks of the accepted cases |

`--scala-cpus N` (`follow`, `soak`, `relay`, `pool`, `e2e`; default 2) sets the CPUs of the Scala node's
container, and `--max-starvation-warnings N` makes a scenario fail when the node logged more cats-effect
CPU-starvation warnings than that (otherwise they are only reported: `cpuStarvationWarnings` in
`e2e-summary.json`, and the `scheduling` block of `pool-summary.json` with the worst timer lateness and
the longest CPU-bound step the node measured). `pool --scala-cpus 1` is the check that the forger's slot
clock holds with one CPU (roadmap D8).

`--consensus-mode GenesisMode` on `follow`, `restart`, `relay`, `e2e` or `soak` selects Genesis
for Scarab using a separate run-local configuration and a topology naming only that
run's controlled reference pools as trustable local roots. The reference pools keep
their original configuration. Restart retains the explicit mode on both starts;
soak passes it to its follower. `PraosMode` explicitly selects Praos; omitting the
option retains the harness's existing configuration. See the [Genesis design](../design/genesis.md)
for the reference contracts and acceptance gates.

`--oracle` (on `e2e` and `soak`) makes the follower write a state digest at every epoch boundary and ends the run with the db-analyser comparison described under "Ledger state against the Haskell node"; `devnet.py oracle` repeats that comparison on a cluster that is still up.

Building blocks (also runnable on their own): `up` (start the 3-pool cluster), `up-eras [--epoch-length 500 --active-slots-coeff 0.2 --byron-epochs 1 --byron-slot-ms 1000 --stall-seconds 90 --shelley-epochs 1 --min-utxo-value 0 --pool-margin 0 --cross-rewards]`, `capture-eras [--out fixtures/eras --conway-blocks 3]`, `sample-chain-dep --out FILE [--interval 0.8 --spacing 100 --until-epoch 9]` (run next to `up-eras`: acquires the reference node's volatile tip once per poll and records its DebugChainDepState answer with the era, epoch and tip point of that same acquire; `assemble-chain-dep --points FILE --out DIR` then keeps the points whose block is in a `capture-eras --out DIR` chain, as `fixtures/chain-dep`), `tip [--node N]`, `follow [--seconds N] [--node N]`, `load [--seconds N --wallets 10 --stakers 4 ...]`, `govload [--seconds N]`, `compare`, `down`.

Cluster parameters: the `soak`, `relay` and `pool` clusters come from `cardano-testnet` 11.1.1 defaults
with k = 5, f = 0.05 and 500-slot epochs of 0.1 s (as recorded in the 2026-10-11 runs; the `up-eras`
cluster uses f = 0.2 by default, see [`fixtures/eras/README.md`](../../fixtures/eras/README.md)). The genesis
files are rewritten at cluster start, so a cluster is not reproducible from the repository alone;
the committed `fixtures/eras` capture is the Docker-free replay of one `up-eras` run.

Node-to-client evidence: `scripts/devnet/ntcquery.py` also speaks LocalTxSubmission (`submit`) and can
connect to a socket on the host (`host=True`, used for the Scala node's socket directory); the unit
tests of the query envelope, the predicate-failure encoding and the answers against recorded reference
bytes (`fixtures/devnet/lsq`) need no Docker.

Comparison method: `cardano-cli` 11.2.3 refuses `query ledger-state` and `query protocol-state`
before Conway, so `scripts/devnet/ntcquery.py` speaks LocalStateQuery to the reference node directly and
`scripts/devnet/eraview.py` projects `DebugNewEpochState`, the whole UTxO and `DebugChainDepState` onto the
node's `/state` shape. Byron is not compared directly (the CLI cannot query a Byron ledger); the Byron UTxO
is compared through the first Shelley epoch, which starts from it.

`e2e` shares the `up-eras` options but changes four defaults so the pre-Allegra rules have something to
decide: `--shelley-epochs 3` (the pools hold stake from epoch 2, produce blocks from epoch 3 and the
update for those blocks is made and paid under protocol 2 in epochs 4 and 5), `--min-utxo-value 1000000`,
`--pool-margin 0.1` and `--cross-rewards` (pool 1 pays its leader reward to delegator 2, who is also a
member of pool 2, and pool 2 pays delegator 3, who owns and leads pool 3). The plain `up-eras` defaults
are those of the committed `fixtures/eras` (one Shelley epoch, no minimum, margin 0).

Era load (`scripts/devnet/eraload.py`, transactions built and signed by `scripts/devnet/eratx.py` because
cardano-cli 11 builds Conway transactions only): Shelley transfers, stake registration and delegation,
a new pool, pool re-registration, MIR from the reserves, a genesis delegation certificate,
withdrawals; Allegra validity intervals, auxiliary data, a timelock script, pool retirement; Mary
minting and burning under native-script policies, multi-asset transfers, MIR from the treasury;
Alonzo collateral, datum-hash outputs, required signers; Babbage inline datums, reference scripts,
reference inputs, collateral return; Conway the governance load (`govload`) described below.

Boundary load (roadmap B7, B8, B12): outputs of exactly `minUTxOValue` (Shelley, Allegra, Mary) and one
lovelace less, which the reference must refuse; the Mary scaled minimum for one native asset at the
boundary; serialised values of exactly 4000 (Mary) and 5000 (Alonzo) bytes against 4001 and 5001; MIR
from the reserves early, mid-epoch, just before and just after the `3k/f` cut-off (the later one
replaces the earlier entry before Alonzo) and to an unregistered credential; MIR pot transfers and
negative entries refused in Mary and accepted in Alonzo; stake keys deregistered before and after the
reward calculation starts at `4k/f`. A submission that must be refused counts as `unexpected` (and fails
the run) if the reference accepts it or names another reason; the replies are in `loadEvidence` of
`e2e-summary.json`. The reference reward update is projected with the pre-Allegra choice (one reward
per account) when the previous epoch's parameters are protocol 2.
Plutus V1 and V2 scripts are not driven by the devnet load (`eraload.py` TODO, roadmap I3); they are verified by
the Preview sync and the unit and oracle tests only.

`govload` (run by `soak` next to `load`, and in the Conway epochs of `e2e`) drives, one pass per
epoch: wallets; stake registration with pool and DRep delegation and two DReps; a Plutus V3 script
address (inline datum, no datum, reference script) spent and minted; proposals of the info, parameter
change, treasury withdrawal, committee update, constitution, hard-fork and no-confidence kinds with
votes from DReps, pools and the committee; hot-key authorisation, DRep update and retirement,
committee resignation, stake deregistration with refund, and a treasury donation. The genesis
committee of `cardano-testnet` is empty with threshold 0.

`soak --governance-extended` (`govload --extended`) adds `scripts/devnet/govextra.py`, a thread of
the governance load with wallets and keys of its own that acts on the state of the reference chain
(not on epoch numbers). Every transaction is also compared by the ordinary ledger comparison; the
transactions marked "refused" must be refused by the reference with the named predicate failure
(`EraLoad.expect_rejected`), and their bytes are kept in `negative-txs.jsonl` (and the VRF sequence,
accepted and refused, in `vrf-txs.jsonl`) of the run directory for the Scala replay tests
(`DevnetRefusedTxSuite`, fixtures in `fixtures/devnet`). `summary.json` gets a `governanceExtended`
block (stages done, refusals, what the reference's ratification enacted or let expire per epoch).

| Roadmap item | What it drives | Reference outcome that is compared |
|---|---|---|
| B2 default votes | the three pool reward accounts are registered and delegated to always-abstain (pool 1), always-no-confidence (pool 2) and a DRep key (pool 3); a parameter change lowers the pool thresholds to 0.45; then two competing hard forks (pool 3 alone votes for the first, pools 2 and 3 for the second), a no-confidence action no pool votes on, and three committee updates (none voted by pools, one voted by pool 3, one with a long term) | only the second hard fork, the no-confidence action and one committee update are enacted |
| B3 committee | the elected committee holds a member that expires after four epochs; `committeeMinSize` is raised to 2 together with a treasury withdrawal in the same ratification run; after the member expired a withdrawal that every voter accepts is proposed | the parameter change and the first withdrawal are enacted in one run; the second withdrawal is never ratified |
| B4 scripts | a Plutus V3 script as stake credential, DRep (registered, delegated 30 ada of stake, votes with the Voting purpose) and reward account (treasury withdrawal paid in, then withdrawn with the Rewarding purpose) | accounts, DRep state and treasury |
| B5 protocol 11 | reference inputs that are inputs (before 11: refused; at 11: accepted without scripts, refused with a Plutus V3 script); votes of committee hot keys that are authorised but not elected (refused at 11, accepted once elected); ten pool registrations around a VRF key hash another pool holds, or has scheduled. The first, second and last are registry checks run by `e2e` at the version the chain is at (`protocolversions.VERSION_CHECKS`); the unelected-voter case needs the full set (`soak --governance-extended`) | the refusals above |

`e2e` starts the light subset (script credentials, reward accounts, the reference-input cases and the VRF cases) in its
Conway epochs and judges it (`govextra.extended_outcome`). A stage whose last attempt reported `ok: false`, an applicable
stage that is not done, and any case marked "refused" that the reference did not refuse as expected all fail the run. The
`cardano-cli` answer to a refused case is classified (`eraload.submission_verdict`): `refused`, `wrongReason`, `accepted` (a
divergence: the stage ends failed and is not retried, since the accepted transaction changed the state the case depends on)
or `noVerdict` (the CLI or `docker exec` failed before the ledger answered: submitted again up to three times, each attempt
logged with the exit code and the raw output). `soak --governance-extended` applies the same refusal accounting. A stage of a
version the chain never reached is reported under `extended.notApplicable` of `e2e-summary.json` with its reason (the VRF
rule applies from version 11: `hardforkConwayDisallowDuplicatedVRFKeys`, `Shelley/Rules/Pool.hs:265`); a stage whose version was
reached gets up to ten minutes past the target epoch (`EXTENDED_WAIT`) and fails the run if it is still not done.

#### Protocol versions in the e2e

Protocol versions up to Babbage's 8 come from the update mechanism, one per epoch (`protocolMajorByEpoch` of `eras.json`,
Conway starts at 9). The Conway versions are governance: a HardFork action per version, ratified one per epoch boundary.
`e2e` drives them dynamically (`scripts/devnet/protocolwalk.py`, run inside the extended-governance thread with its wallets):

* **The target.** Once the tip is in Conway the run asks the reference node `GetMaxMajorProtocolVersion` (LocalStateQuery,
  Shelley-based query 38, node-to-client 21 and later) through `scripts/devnet/ntcquery.py`. That is the
  `MaxMajorProtVer` of the reference's consensus layer: the highest version the node accepts in a block header, a property
  of the pinned image (11 for cardano-node 11.1.3; the recorded answer is in `fixtures/ntc-golden`). The configuration key
  `MaxKnownMajorProtocolVersion` is not it (that is the Byron-era setting) and the ledger maximum cannot be read from outside.
  A reference that does not answer, answers with something that is not a version or negotiates an older node-to-client
  version fails the run (`MaxProtocolUnknown`); there is no literal fallback.
* **The walk.** While the version is below the target, one HardFork action to version + 1 is proposed, with the previous
  hop (pending or enacted) as `prev`, and voted yes by every body that may vote: from the first Conway epoch by the pools
  alone (version 9 is the bootstrap phase, where DReps and the committee cannot vote and the DRep thresholds are zero), and
  from version 10 by the DReps, the committee and the pools, as soon as the base governance round has elected and
  authorised the committee (`gcommittee`) and the DRep delegations count in the distribution. `EraLoad.dynamic_forks` removes
  the scripted hops to 10 and 11 from the base rounds, so the walk is the only source of HardForks (`soak
  --governance-extended` keeps its scripted competing forks to 11, which B2 depends on). The devnet's `govActionLifetime` is
  1, so a hop is proposed and voted within one epoch, in its first 60 %, and the next hop one epoch after it (never in the
  same epoch: the enactment of a HardFork delays the rest of its ratification run past the lifetime of the later hop).
  A hop that is no longer pending while the chain has not reached its version fails the walk (`HardForkLost`).
  Every pass that proposes nothing logs why, as a typed `WaitReason` (`walk: true, wait: <reason>` with the hop, the base
  round stages still missing and the epoch the wait began), and `protocol.waiting` of `e2e-summary.json` holds the current
  wait: the reward accounts (`xrewards`), the hop of this epoch, the DRep delegations (`gdeleg`, `gdeleg2`) not made or not
  yet in the distribution, the committee's hot keys (`gcommittee`) not authorised, the slot window or a pending enactment.
* **Where the preconditions come from.** The DRep delegations and the committee are stages of the base governance pass
  (`EraLoad.conway_features`). In `e2e` that pass runs once per epoch in a thread of its own (`EraLoad.start_governance`),
  not in the comparison worker's epoch batch: one comparison retries until the Scala node is at the reference's block
  (about 1.2 epochs on the default devnet when it never gets there), and the batch behind it waits. With the follower
  paused for ten minutes the stages came one blocked comparison apart and the hop to 11 was proposed three epochs late;
  a run under load that fell behind for longer would miss the walk's bound (#451, whose failing run was not
  reproduced). The summary's `blockedThreads` has the stack of every load thread that was still running when the run
  asked it to stop.
* **How long it takes.** The run waits past its target epoch (`--until-epoch`, default Conway start + 7) for the walk and the
  checks of the versions reached. The walk's own limit is derived from the governance parameters:
  `(hops left + 1) * (govActionLifetime + 2) + 2` epochs of the run's epoch length and slot length
  (`protocolwalk.walk_bound_epochs`); exceeding it fails the run (`WalkStalled`). Governance cannot start before Conway, so
  the pre-Conway epochs cannot overlap with it; the earliest hop is proposed in the first Conway epoch, as soon as the
  extended thread has funded its wallets and registered the pools' reward accounts. Each later hop is bound by the rules above
  (committee election, one hop per epoch): with the pinned reference, Conway starting in epoch 10, the hop to 10 is in force from epoch 12
  and the hop to 11 (proposed in epoch 14) from epoch 16; the scripted hops had the chain at protocol 10, not 11, at the default target epoch 17
  (so the VRF stage was not applicable).
* **Checks by version.** `scripts/devnet/protocolversions.py` holds `VERSION_CHECKS`: each check names the extended stage
  it is, the versions it applies to (`since`..`until`, or open-ended) and the highest version it was verified through. A check
  runs when the chain is at an applicable version (`xrefoverlap-pre11` at 9 and 10, `xrefoverlap-11` and `xvrf` from 11;
  `xunelected` belongs to the full set and is not counted for the e2e). `e2e-summary.json` has `extended.versionChecks`
  (per check: `applies`, `status` of `done`, `notApplicable`, `failed` or `missed`, `ranAtProtocol`, `tries`) and `protocol`
  (the plan, the hops with their epochs, the first epoch each version was in force, the failure if any).
* **The coverage guard.** The run fails when the reference supports a Conway protocol version that no registered check covers
  (from `since` to `verified_through`) and that `NO_VERSION_CHECKS` does not list with a reason. A new cardano-node that raises the
  maximum therefore fails `e2e` until someone reads what changed and either extends a check's `verified_through` (the rules
  still hold) or adds a check or a `NO_VERSION_CHECKS` entry. The failure is found when the maximum is read, logged at once, and
  reported at the end; the chain is driven only up to the highest version the registry knows. A new era (Dijkstra) is out of
  scope: it needs its own builders in `eratx.py`, and the guard fails there until the registry and the builders are extended.
  The unit tests of the registry, the guard and the walk are `scripts/tests/test_protocol_versions.py` (no Docker); one of them keeps the
  registry's highest verified version equal to the Scala node's own `HardForkConfig.MaxMajorProtVer`.

Recorded results and the commits they were obtained on are in
[compatibility.md](../compatibility.md) (evidence log).

BlockFetch coordination retains bounded candidate histories. ChainSync uses the pinned reference's
rolling low/high policy, collecting and refilling after handled replies rather than waiting for a
fixed batch. Its default watermarks are 200/300. `--read-ahead` bounds ordered body-publication
chunks independently and retains its default of 128.
