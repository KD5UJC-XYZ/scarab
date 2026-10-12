# Running the node and the devnet scenarios

Everything runs in Docker; no host JDK or sbt is needed. See [CONTRIBUTING.md](../CONTRIBUTING.md)
for the build workflow. This node is research software: do not run it with keys that hold real
funds (see [SECURITY.md](../SECURITY.md)).

## Images

| Image | Built from | Used for |
|---|---|---|
| `cardano-scala-jdk:local` | `docker/jdk.Dockerfile` (digest-pinned Temurin 21 plus Python 3) | building, running the node, `devnet.py` |
| `cardano-reference-11.1.3:local` | the checksum-pinned official release archive, see [reference-image.md](reference-image.md) | the Haskell reference cluster for the scenarios |

```sh
docker build -f docker/jdk.Dockerfile -t cardano-scala-jdk:local .
```

Compile once and write the runtime classpath (the scenarios and the commands below use it):

```sh
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  cardano-scala-jdk:local bash scripts/sbtw node/compile node/runtimeClasspathFile
```

This writes `node/target/runtime-classpath.txt`. Regenerate it with `node/runtimeClasspathFile`
after any dependency change: a stale file lacked rocksdb.

## `follow`: the node

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" lab.node.Main follow \
  --genesis-dir <dir> --peer <host>:<port> [options]
```

(Inside the JDK image with the checkout mounted, or on a host with JDK 21.) The sbt form is
`sbt "node/runMain lab.node.Main follow ..."`. The node logs one JSON object per line on stdout
(`started`, `extended`, `eraTransition`, `epochValidated`, `ledgerEpoch`, `snapshotWritten`,
`forged`, ...).

Flags, as parsed by `Main.parse` (`node/src/main/scala/lab/node/Main.scala`). Every flag takes a
value except `--forge` and `--dump-state-full`. A repeated flag keeps its last value, except `--peer`, which accumulates.

| Flag | Default | Meaning |
|---|---|---|
| `--genesis-dir <dir>` | required | directory with `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`, `byron-genesis.json` (needed when the chain starts in Byron) and optionally a node configuration (`configuration.yaml`, `configuration.json` or `config.json`) |
| `--peer <host>:<port>` | required, repeatable | node-to-node peer to follow; with several peers each is followed and chain selection picks the best chain. One peer that keeps failing is fatal after 30 attempts, with several it is retried forever |
| `--listen <host>:<port>` | off | accept node-to-node connections (versions 14 and 15) and serve our chain |
| `--local-socket <path>` | off | Unix socket for node-to-client LocalTxSubmission |
| `--seconds N` | run until stopped | stop after N seconds |
| `--query-port P` | `7071` | loopback port of the HTTP query server (`GET /tip`, `GET /state?slot=&hash=`) |
| `--db <dir>` | off (in-memory; replay from genesis on every start) | persistent chain: `immutable/`, `snapshots/`, `utxo/` |
| `--snapshot-every <N\|Nslots\|Nepochs>` | `1epochs` | snapshot interval; a bare number is slots |
| `--keep-snapshots N` | `3` | snapshots (and UTxO checkpoints) to keep |
| `--utxo-store memory\|rocksdb` | `rocksdb` with `--db`, otherwise `memory` | UTxO set interpreter |
| `--utxo-cache-mb N` | `256` | RocksDB block cache |
| `--read-ahead N` | `64` | ChainSync requests pipelined and blocks fetched per range while far behind (256 timed out against the public relay) |
| `--dump-state <dir>` | off | write a ledger-state digest (`<slot>-<hash>.json`) at the points of `--dump-state-at`, for the db-analyser comparison below |
| `--dump-state-at <points>` | `epoch-boundaries` | with `--dump-state`: `epoch-boundaries` (the first block of every epoch), `epoch-boundaries/N` (every Nth epoch) or `slot,slot,...` (the first block at or after each slot, what `db-analyser --store-ledger` stores) |
| `--dump-state-full` | off | with `--dump-state`: also write the whole projection, UTxO included, as `<slot>-<hash>.full` (drill-down on small states only) |
| `--mempool-bytes N` | `1048576` | mempool capacity in bytes |
| `--plutus-cases <dir>` | off | record self-contained JSON cases of Plutus transactions (used to build `fixtures/preview/plutus`) |
| `--forge` | off | produce blocks; requires `--vrf-skey`, `--kes-skey`, `--opcert` |
| `--vrf-skey <file>` | with `--forge` | `cardano-cli` text envelope `VrfSigningKey_PraosVRF` |
| `--kes-skey <file>` | with `--forge` | `KesSigningKey_ed25519_kes_2^6` (the period-0 key) |
| `--opcert <file>` | with `--forge` | `NodeOperationalCertificate` (it also carries the cold verification key; the cold signing key is never needed) |

Caveats:

- The parser accepts any `--name value` pair and silently ignores flags it does not know, so a typo
  such as `--db-dir` has no effect. Rejecting unknown flags is tracked as [M1](roadmap.md).
- A DB does not record which network it belongs to; do not point `--db` at another network's genesis
  ([D5](roadmap.md)).
- Volatile blocks (the last `k`) are not persisted and there is no final snapshot on shutdown; after a
  restart the node resumes from the newest snapshot and re-fetches the volatile tail.

### Preview

The node follows the public Preview testnet from genesis (network magic 2):

```sh
java -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" lab.node.Main follow \
  --genesis-dir <preview config dir> --peer preview-node.play.dev.cardano.org:3001 \
  --db "${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/preview-db"
```

The Preview genesis files (Byron, Shelley, Alonzo, Conway) and `config.json` come from the official
Cardano network configuration and are not all committed here (`fixtures/preview/genesis/` holds only
`shelley-genesis.json` for the tests). Fetch them into the config directory, in a container like
everything else:

```sh
mkdir -p "${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/preview/config"
docker run --rm --user "$(id -u):$(id -g)" -w /out \
  -v "${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/preview/config":/out --entrypoint bash cardano-scala-jdk:local -c \
  'for f in config.json byron-genesis.json shelley-genesis.json alonzo-genesis.json conway-genesis.json; do
     curl -fsSL -o "$f" "https://book.play.dev.cardano.org/environments/preview/$f"; done'
```

Only `ByronGenesisHash` from `config.json` is checked against its file today; the Shelley, Alonzo
and Conway hashes are not yet verified (roadmap J1). Preview starts in Alonzo at epoch 0. The recorded
genesis-to-tip numbers are in [performance.md](performance.md). The run needs about 14 GB of disk
for the immutable blocks, RocksDB and checkpoints, and memory well above the heap limit: an earlier multi-restart run measured 2.2 GB RSS at the tip,
but the uninterrupted run peaked at 5.66 GB RSS with `-Xmx3g` (RocksDB and other off-heap memory are
not yet profiled, roadmap K6). Give the container a 10 GiB limit with `-Xmx3g`; the run started with 6 GiB and RSS
had reached 5.3 GB when the limit was raised.

### Ledger state against the Haskell node (the oracle, roadmap K1)

Following a chain shows that valid blocks are accepted. The oracle shows that the ledger state is the
same: at the first block of every epoch the Scala node writes a digest of its state (`--dump-state`), the
reference image's `db-analyser` stores the Haskell ledger state after the same block, and
`scripts/oracle_compare.py` decodes that snapshot (`scripts/oracle/`) into the same projection and
compares. The projection is the one the devnet comparison uses (`Projection.node`): UTxO, fees,
deposits, treasury, reserves, accounts, pools and their parameters, DReps, the three stake snapshots,
the reward update, the pool distribution, protocol parameters, block counts, the Praos nonces and
opcert counters and, from Conway, the whole governance state (proposals and votes, committee, constitution,
ratification state, DRep distribution, committee hot keys, donations).

A map with more than 64 entries is reduced to its entry count and the sum, modulo 2^256, of the
SHA-256 of each entry's text (key, a NUL byte, compact JSON with sorted keys), plus that sum for each
of sixteen buckets of the key hash; a difference names the buckets, which narrows it to a sixteenth of
the entries. The UTxO set is one such map (`StateDump.scala`, `scripts/oracle/digest.py`; a
shared test vector keeps the two in step). A difference is drilled down with `--dump-state-full` on the Scala
side and `oracle_compare.py project --full` on the Haskell side.

On a public network (Preview shown; the configuration files are those of "Preview" above):

```sh
# 1. A Haskell node that syncs the network. PraosMode and a plain topology (the peer snapshot and the
#    checkpoints file of the official Preview configuration are not needed); 6 CPUs, db outside the checkout.
D="${CSL_DATA_DIR:-/tmp/cardano-scala-lab}"
mkdir -p "$D/haskell-preview-config" "$D/haskell-preview-db" "$D/haskell-preview-ipc"
cp "$D"/preview/config/*.json "$D/haskell-preview-config/"
#    edit config.json: "ConsensusMode": "PraosMode", remove CheckpointsFile and CheckpointsFileHash;
#    topology.json: {"bootstrapPeers": null, "localRoots": [{"accessPoints": [{"address":
#    "preview-node.play.dev.cardano.org", "port": 3001}], "advertise": false, "valency": 1, "trustable": true}],
#    "publicRoots": [{"accessPoints": [], "advertise": false}]}
docker run -d --name csl-k1-haskell-preview --cpus 6 --memory 32g --user "$(id -u):$(id -g)" \
  -v "$D/haskell-preview-config":/config:ro -v "$D/haskell-preview-db":/db -v "$D/haskell-preview-ipc":/ipc \
  --entrypoint cardano-node cardano-reference-11.1.3:local run --config /config/config.json \
  --topology /config/topology.json --database-path /db --socket-path /ipc/node.socket --port 3001 --host-addr 0.0.0.0

# 2. The Scala node from genesis, writing a digest at every epoch boundary (the same configuration as above).
java -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" lab.node.Main follow --genesis-dir "$D/preview/config" \
  --peer preview-node.play.dev.cardano.org:3001 --db "$D/preview-scala-db" --keep-snapshots 2000 \
  --dump-state "$D/preview-dump" --dump-state-at epoch-boundaries

# 3. The Haskell side: db-analyser over the node's ChainDB, one projection per epoch (Docker, parallel
#    segments; 86400 slots per epoch on Preview). It works on hard-linked copies; --live first copies the
#    finished chunks of a node that is still running.
python3 scripts/oracle_compare.py reference --config "$D/haskell-preview-config" --db "$D/haskell-preview-db" --live \
  --work "$D/preview-ref-work" --out "$D/preview-ref" --epoch-slots 86400 --jobs 5 --seed-every 60

# 4. Pair the projections with the Scala digests by block and compare (run it again as either side grows).
python3 scripts/oracle_compare.py diff --reference "$D/preview-ref" --dump "$D/preview-dump" --results "$D/preview-diff.json"
```

`reference` chains `db-analyser --store-ledger <slot> --analyse-from <previous snapshot>` (Docker, `--cpus 2
--memory 16g` each): a seed pass over the chain stores the state at every `--seed-every`-th epoch, and each
segment of that many epochs then continues from its seed in a worker of its own. Each epoch costs one
snapshot load, one epoch of blocks and one snapshot write (5 s with a tiny state, about 30 s at one
million UTxO entries); the snapshot is projected, the previous one deleted, and the projection written to
`<out>/<slot>-<hash>.json` with a line in `<out>/index.jsonl`; a restart skips the epochs in the index.
`db-analyser` needs a ChainDB of its own (it opens the newest chunk for writing), which `--work` holds.

The Scala side does not need the network either: a Scala `--db` directory that holds only `immutable/` is
replayed from genesis (about 200 to 1,100 blocks/s depending on load and era) and writes the same digests.

Without a Haskell node that has synced the network, the Scala node's own immutable store can be exported
as a Haskell ImmutableDB (`oracle_compare.py export --scala-immutable <db>/immutable --out <dir>
--chunk-slots 4320 --magic-file <a protocolMagicId file> --verify-against <a Haskell node's ChainDB>`). The
blocks are the same bytes; only the chunk, primary and secondary index files are written. Against the chunks a
Haskell node synced from the Preview relay, the first 2,943 chunks (slots up to 12.7 million, epoch 147)
of the export are identical byte for byte, files and indexes. `--chunk-slots` is ten times the security
parameter of the Byron genesis (Preview 4320, Preprod and Mainnet 21600).

On a devnet cluster: `python3 scripts/devnet.py e2e --oracle` (or `soak --oracle`, or `follow --dump-state`
then `oracle`) runs `db-analyser` inside the reference container on a copy of node 1's ImmutableDB and
compares at every epoch boundary the follower digested (`scripts/oracle/devnet.py`); the summary is in
`oracle-summary.json` of the run directory and in the `oracle` block of `e2e-summary.json`.
The first epoch of a chain the follower started in Byron has no digest (the follower digests an epoch
when it sees the epoch number change in Shelley-based blocks).

### Forging

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The pool must already be registered with stake in the ledger the node follows. Only Praos (Conway)
blocks are forged. Keep key files outside the repository and readable only by the node's user (the
scenarios mount them read-only with mode 0400). See [architecture.md](architecture.md) for the
forging design and [compatibility.md](compatibility.md) for what is not covered (no KES agent, no
opcert issuing, never run on a public network).

### Query server

`GET /tip` and `GET /state?slot=<n>&hash=<hex>` serve the node's projection of the ledger and
consensus state at a block; the devnet comparator diffs this against the Haskell node. It binds to
loopback and is a development interface, not a stable API.

## `capture`: recording blocks

`lab.node.tools.CaptureMain` records raw blocks (one hex-encoded block per line) for offline replay tests:

```sh
java -cp "$(cat node/target/runtime-classpath.txt)" lab.node.tools.CaptureMain capture \
  --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hashhex>]
```

It starts at genesis (or after `--from`) and keeps going until the recorded blocks are 10 deep, so
none is rolled back; `--blocks all` records up to 10 blocks behind the tip. The fixtures under
`fixtures/preview`, `fixtures/mainnet` and `fixtures/eras` were captured with it.

## Devnet scenarios (`scripts/devnet.py`)

`devnet.py` drives Docker containers: a `cardano-testnet` cluster of three Haskell pools from the
reference image, and the Scala node running from a frozen copy of the compiled classes in
`cardano-scala-jdk:local`. The Scala node shares a reference container's network namespace because
the reference nodes bind loopback. Requirements: both images, the classpath file from "Images", Docker,
Python 3 (standard library only), roughly 10 minutes per scenario.

Run data lives in `${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/<name>` (never in the checkout or the home
directory). `--name` selects the cluster name (default `csl-devnet`, `csl-eras` for `up-eras`), so a
second cluster can run beside another. Directories and sockets that containers of other uids must
write are made world-writable (mode 0777) on a local devnet; the key files are 0400.

```sh
python3 scripts/devnet.py <subcommand> [options]     # python3 scripts/devnet.py --help
python3 scripts/devnet.py down                       # remove containers and the run directory
```

| Subcommand | What it does | What it asserts |
|---|---|---|
| `soak [--seconds 600] [--every 20] [--seed 1] [--governance-extended] [--oracle]` | `up` + Scala follower + ADA, staking and Conway governance/Plutus V3 load + periodic comparison; `--governance-extended` adds the scenarios of [`govextra.py`](../scripts/devnet/govextra.py) (below; needs `--seconds 900`) | exit status fails on any mismatched comparison or follower error; with `--governance-extended` also on a transaction the reference was expected to refuse but did not refuse for the named predicate failure, and on an extended stage that was not reached. Compares at matching tips: UTxO, fees, deposits, treasury, reserves, accounts, pools, DReps, snapshots, reward update, Praos nonces, governance state |
| `e2e [--timeout 1500] [--until-epoch N] [--keep] [--no-load] [--no-compare] [--min-comparisons 3] [--oracle]` | `up-eras` (Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway) + follower from Origin + era-specific load + per-era ledger comparison | the `eraTransition` lines are exactly the seven eras at the configured epochs; every era applied blocks; all Byron signatures verified; no validation or fatal errors; every comparison matched, at least `--min-comparisons` per Shelley-based era. Writes `e2e-summary.json` and `compare.jsonl` |
| `relay [--seconds 300] [--peers 3] [--relay-mode ...] [--keep]` | the Scala node follows the pools and serves a non-producing Haskell relay (`--listen`, `--db`, `--local-socket`); load is submitted through the relay | the Haskell relay syncs the whole chain from the Scala node and stays at the tip (lag samples every `--every` seconds), transactions travel relay, Scala mempool, pools, block, and one is submitted with `cardano-cli` to the Scala socket |
| `pool [--seconds 480] [--pool 1\|2\|3] [--keep]` | the Scala node is one of three pools: pool 1's Haskell node is never started and the Scala node forges with its keys; `slotsPerKESPeriod = 250` | blocks forged, adopted and on the final chain; the Haskell ledger's blocks-made count for the pool equals the Scala-forged blocks on the chain; every slot the reference node's `query leadership-schedule --current` gives the pool (asked once per epoch) is either forged or taken by another pool's block, and a missed one is reported with the `forgeSkipped` reason; load transactions in Scala blocks; at least one KES period crossed; no rollback deeper than `k`; Haskell pools agree on the tip; comparisons match; no error event. Writes `pool-summary.json` |

`--oracle` (on `e2e` and `soak`) makes the follower write a state digest at every epoch boundary and ends the run with the db-analyser comparison described under "Ledger state against the Haskell node"; `devnet.py oracle` repeats that comparison on a cluster that is still up.

Building blocks (also runnable on their own): `up` (start the 3-pool cluster), `up-eras [--epoch-length 500 --active-slots-coeff 0.2 --byron-epochs 1 --byron-slot-ms 1000 --stall-seconds 90 --shelley-epochs 1 --min-utxo-value 0 --pool-margin 0 --cross-rewards]`, `capture-eras [--out fixtures/eras --conway-blocks 3]`, `tip [--node N]`, `follow [--seconds N] [--node N]`, `load [--seconds N --wallets 10 --stakers 4 ...]`, `govload [--seconds N]`, `compare`, `down`.

Cluster parameters: the `soak`, `relay` and `pool` clusters come from `cardano-testnet` 11.1.1 defaults
with k = 5, f = 0.05 and 500-slot epochs of 0.1 s (as recorded in the 2026-10-11 runs; the `up-eras`
cluster uses f = 0.2 by default, see [`fixtures/eras/README.md`](../fixtures/eras/README.md)). The genesis
files are rewritten at cluster start, so a cluster is not reproducible from the repository alone;
the committed `fixtures/eras` capture is the Docker-free replay of one `up-eras` run.

Comparison method: `cardano-cli` 11.2.3 refuses `query ledger-state` and `query protocol-state`
before Conway, so `scripts/devnet/ntcquery.py` speaks LocalStateQuery to the reference node directly and
`scripts/devnet/eraview.py` projects `DebugNewEpochState`, the whole UTxO and `DebugChainDepState` onto the
node's `/state` shape. Byron is not compared (the CLI cannot query a Byron ledger and the node keeps
no Byron UTxO).

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
Plutus V1 and V2 scripts are not driven by the devnet load (`eraload.py` TODO); they are verified by
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
| B5 protocol 11 | reference inputs that are inputs (before 11: refused; at 11: accepted without scripts, refused with a Plutus V3 script); votes of committee hot keys that are authorised but not elected (refused at 11, accepted once elected); ten pool registrations around a VRF key hash another pool holds, or has scheduled | the refusals above |

`e2e` starts the light subset (script credentials, reward accounts, the reference-input refusals and, if protocol 11 is reached, the VRF cases) in its Conway epochs.

Recorded results and the commits they were obtained on are in
[compatibility.md](compatibility.md) (evidence log).
