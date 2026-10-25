<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger state against the Haskell node

Part of [Running the node and the devnet scenarios](../running.md).

## Ledger state against the Haskell node (the oracle, roadmap K1)

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
D="${SCARAB_DATA_DIR:-/tmp/scarab}"
mkdir -p "$D/haskell-preview-config" "$D/haskell-preview-db" "$D/haskell-preview-ipc"
cp "$D"/preview/config/*.json "$D/haskell-preview-config/"
#    edit config.json: "ConsensusMode": "PraosMode", remove CheckpointsFile and CheckpointsFileHash;
#    topology.json: {"bootstrapPeers": null, "localRoots": [{"accessPoints": [{"address":
#    "preview-node.play.dev.cardano.org", "port": 3001}], "advertise": false, "valency": 1, "trustable": true}],
#    "publicRoots": [{"accessPoints": [], "advertise": false}]}
docker run -d --name scarab-k1-haskell-preview --cpus 6 --memory 32g --user "$(id -u):$(id -g)" \
  -v "$D/haskell-preview-config":/config:ro -v "$D/haskell-preview-db":/db -v "$D/haskell-preview-ipc":/ipc \
  --entrypoint cardano-node cardano-reference-11.1.3:local run --config /config/config.json \
  --topology /config/topology.json --database-path /db --socket-path /ipc/node.socket --port 3001 --host-addr 0.0.0.0

# 2. The Scala node from genesis, writing a digest at every epoch boundary (the same configuration as above).
java -Xmx3g -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow --genesis-dir "$D/preview/config" \
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

The recorded Preview run (2026-10-14) kept everything under `.workspace/data/2026-10-14-k1-*`: the Haskell node
(`csl-k1-haskell-preview`, `--cpus 6`), the Scala node from a frozen copy of the build (`csl-k1-scala-preview`,
`--cpus 6 --memory 10g`, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`; `run.sh` resumes from its
database) and a loop (`watch.sh`) that repeats steps 3 and 4 every 20 minutes. About 1.8 epochs per minute on
both sides while the other is also syncing.

On a devnet cluster: `python3 scripts/devnet.py e2e --oracle` (or `soak --oracle`, or `follow --dump-state`
then `oracle`) runs `db-analyser` inside the reference container on a copy of node 1's ImmutableDB and
compares at every epoch boundary the follower digested (`scripts/oracle/devnet.py`); the summary is in
`oracle-summary.json` of the run directory and in the `oracle` block of `e2e-summary.json`.
The first epoch of a chain the follower started in Byron has no digest (the follower digests an epoch
when it sees the epoch number change in Shelley-based blocks).

A digest is compared only when its block, by header hash, is on both final chains: in node 1's ImmutableDB
(`db-analyser --show-slot-block-no`) and on the follower's final chain
([`scripts/oracle/finality.py`](../../scripts/oracle/finality.py), #91). The follower's chain is rebuilt from its
whole log and the rebuild fails rather than guess:
- Every `started` line begins a session from Origin. A session restored from a database cannot be modelled from
  the log and fails.
- Every `extended` must follow the tip by block number and slot. Every `rolledBack` must start at the tip and
  remove exactly its `depth`, and every `connected` must intersect on the chain.
- A malformed line, or a log that ends inside a line, fails.
- The rebuilt tip must be the follower's final tip. To record it, the harness stops the follower's only peer,
  reference node 1 (SIGSTOP; nodes 2 and 3 keep the chain growing), reads the follower's `/tip` until two
  readings agree, writes it to `follower-tip.json`, removes the follower and continues node 1 (`stop_follower`;
  `devnet.py oracle` does the same when the follower is still running).

Any other digest is excluded with its reason, and the summary counts both kinds (`compared`, `matched`,
`excluded`, `excludedByReason`, `exclusions`, `epochsWithoutMatch`, `finalChainsAgree`, `scope`):

| Reason | Meaning |
|---|---|
| `rolledBack` | Neither final chain holds the block: the follower's log shows it rolled back a short fork's block. |
| `notFinalOnFollower` | The reference's final chain does not hold it; the follower still held it, less than `k` deep, when it stopped. |
| `notYetImmutable` | The block is after the reference's immutable tip when the comparison ran. |
| `rolledBackOnFollower` | Immutable on the reference; the follower rolled it back within its volatile part (after its immutable tip), so it can still adopt it. |

The comparison fails in four cases:
- on any difference or error in a compared digest;
- when the final chains disagree: the follower's immutable tip, the block with `k` blocks after it as in the
  reference's ChainDB, is not on the reference's chain, and the reference's immutable tip is not on the
  follower's (`diverged`);
- on a digest of a block the follower's log never extended (`unlogged`);
- when no digest was compared and matched.

An excluded digest is not evidence of agreement: `epochsWithoutMatch` lists the boundaries left unchecked. The
follower digests an epoch once, at the first block it applies in that epoch, so an epoch whose boundary block
was rolled back has no digest of the replacement block (#442).
