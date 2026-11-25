<!-- SPDX-License-Identifier: Apache-2.0 -->
# A tester's runbook

Part of [Running the node and the devnet scenarios](../running.md).

The steps an independent tester runs, in order: build the images, watch Scarab forge beside two Haskell pools on
a local devnet, start mainnet from a certified Mithril snapshot and follow it to the tip, do the same on preprod,
drive the node-to-client socket with `cardano-cli`, and run a pool on preprod. Each step gives the commands and what
success looks like, and links the guide that owns the detail; where this page and that guide differ, the guide wins.

Scarab is research software: never give it keys that hold real funds ([SECURITY.md](../../SECURITY.md)). The devnet
step needs no network and takes minutes; the mainnet and preprod steps need a public network and a large disk, and
the pool days of waiting for its stake snapshot.

## 0. Before you start

Docker, Python 3, Bash, Git, `curl` and `tar` on the host; no JDK or sbt
([CONTRIBUTING.md](../../CONTRIBUTING.md#build-docker-only)). Start in the root of a checkout and keep one Bash shell:
later steps use the variables and functions of earlier ones.

```sh
bash scripts/preflight --doctor     # reports what is missing; its exit status is always 0, so read the report
REPO="$PWD"
D="${SCARAB_DATA_DIR:-/tmp/scarab}"  # run data; point SCARAB_DATA_DIR at a large disk before step 3
mkdir -p "$D"
KEYS=<a directory of your own for key files, outside the checkout and outside $D>
mkdir -p -m 700 "$KEYS"
REF=$(python3 -c 'import sys; sys.path.insert(0, "scripts"); from devnet.util import REFERENCE_IMAGE; print(REFERENCE_IMAGE)')
```

`REF` is the reference image the devnet scenarios run (`REFERENCE_IMAGE` in `scripts/devnet/util.py`); its
`cardano-cli` is the one used below. `KEYS` holds the keys of steps 5 and 6: the nodes see `$D`, never `KEYS`, so
the cold key stays out of their containers, and a directory under `/tmp` would not survive a reboot. The memory and
disk a mainnet follower needs are in [the mainnet follower's measurements](../performance/mainnet-follower.md) and
[performance.md](../performance.md).

## 1. The images

[Release publication](../release-publication.md) says whether a Scarab image is published. Until one is, build the
JDK image, compile the node in it and write its runtime class path, with the Git metadata and dependency cache mounts
of [CONTRIBUTING.md](../../CONTRIBUTING.md#build-docker-only) (the class path names its jars under `/work/.cache`, so
every container that runs the node mounts the same cache there):

```sh
docker build -f docker/jdk.Dockerfile -t scarab-jdk:local .
GITDIR="$(git rev-parse --path-format=absolute --git-common-dir)"
CACHE="$(python3 -I -c 'import sys; sys.path.insert(0, "scripts")
from devnet.util import cache_dir; print(cache_dir())')"
mkdir -p "$CACHE"    # yours: a directory Docker creates for the mount would be root's
docker run --rm -v "$PWD":/work -v "$CACHE":/work/.cache -v "$GITDIR:$GITDIR:ro" -w /work \
  --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  scarab-jdk:local bash scripts/sbtw node/compile node/runtimeClasspathFile
test -s node/target/runtime-classpath.txt && echo classpath written
```

Then build the Haskell reference image from the checksum-pinned official release
([reference-image.md](../reference-image.md)):

```sh
python3 scripts/prepare_reference_image.py --download --pull-base
docker image inspect --format '{{.Id}}' "$REF"
```

**Success:** sbt ends each of its two tasks with a `[success]` line and the class path file exists; the reference
script exits 0 after it prints the new image's ID, and `docker image inspect` prints the same ID.

Every node command below runs the node's `Main` in the JDK image through the function `scarab`: the checkout
(`$REPO`) read-only at `/work` with the dependency cache at `/work/.cache`, `$D` at `/data`, the JVM flags the
devnet passes, and the release image's options and a core file limit of 0, which keep step 6's keys off disk
([forging.md](forging.md#keys-in-memory)). `HEAP` is the JVM's heap option
(`-Xmx...`): preprod's as [configuration.md](configuration.md#preprod) runs it, mainnet's as
[the mainnet follower's measurements](../performance/mainnet-follower.md#flags-for-the-fastest-follow) record it.

```sh
HEAP=-Xmx<size>
scarab() {   # scarab <docker run options> -- <arguments of the node's Main>
  local opts=()
  while [ "$#" -gt 0 ] && [ "$1" != -- ]; do opts+=("$1"); shift; done
  shift
  docker run --user "$(id -u):$(id -g)" --ulimit core=0 -v "$REPO":/work:ro -v "$CACHE":/work/.cache:ro -w /work \
    -v "$D":/data "${opts[@]}" --entrypoint java scarab-jdk:local $HEAP \
    --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
    -XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null -XX:-HeapDumpOnOutOfMemoryError -XX:+DisableAttachMechanism \
    -cp "$(cat "$REPO/node/target/runtime-classpath.txt")" xyz.kd5ujc.scarab.node.Main "$@"
}
```

`java` is the container's first process, so `docker stop` reaches the JVM, which writes a snapshot before it exits
([snapshots.md](snapshots.md)); give it time with `docker stop -t 120`.

## 2. A devnet: Scarab forging beside two Haskell pools

`devnet.py pool` starts a three-pool cluster of the reference node, never starts pool 1's Haskell node, and runs
Scarab with pool 1's keys in its place, beside the two Haskell pools, under a payment load. Use a cluster name of
your own and bring it down whatever the outcome. `down` removes the cluster's run directory with its containers, so
copy the run's summary out first:

```sh
python3 scripts/devnet.py --name my-scarab pool
cp "$D/my-scarab/pool-summary.json" "$D/my-scarab-pool-summary.json"
python3 scripts/devnet.py --name my-scarab down
```

**Success:** exit status 0. While it runs, a line every few seconds shows both Haskell pools on the same tip and
Scarab's `forged` and `adopted` counts rising (a `lost` block is a slot battle another pool won, which the scenario
allows, [forging.md](forging.md#forging)); each periodic comparison prints
`"matched": true` with `"differenceCount": 0`, followed by a `wholeState` line whose `DebugNewEpochState` and
`GetUTxOWhole` are both `true`. The run ends with its summary, also written to `pool-summary.json` in
`$D/my-scarab` (copied above): `"passed": true`, `"verdict": "passed"` and an empty `failures` list. Exit status 1 is a failure
(`failures` says what), 2 an inconclusive run on an overloaded host. What the scenario asserts, and its options
(`--plutus-load`, `--scala-cpus`, ...), are in [devnet.md](devnet.md#devnet-scenarios-scriptsdevnetpy).

## 3. Mainnet from a Mithril snapshot to the tip

**The configuration.** Mainnet runs on its official configuration files, as published: besides the seven that
[configuration.md](configuration.md#preprod) fetches for preprod, `peer-snapshot.json` among them, the mainnet
configuration names `checkpoints.json`, which the node requires. Fetch them into `$D/mainnet/config`:

```sh
mkdir -p "$D/mainnet/config"
docker run --rm --user "$(id -u):$(id -g)" -w /out -v "$D/mainnet/config":/out --entrypoint bash scarab-jdk:local -c \
  'for f in config.json topology.json byron-genesis.json shelley-genesis.json alonzo-genesis.json conway-genesis.json checkpoints.json peer-snapshot.json; do
     curl -fsSL -o "$f" "https://book.play.dev.cardano.org/environments/mainnet/$f"; done'
```

The official configuration selects `GenesisMode`, and the official topology lists `bootstrapPeers`, ledger peers and
the peer snapshot. Use them unchanged: in `GenesisMode` the node logs the reference's warning that bootstrap peers
are not compatible with Genesis syncing mode, goes on without them, and syncs from big ledger peers
([configuration.md](configuration.md#choosing-the-consensus-mode)).

**The Mithril verifier, the trust inputs and the artifact.**
[snapshots.md](snapshots.md#import-mithril-certified-bootstrap-into-a-fresh-database) says how the reviewed client
is built, where the aggregator, the two verification keys and the artifact hash come from, which snapshots the
import takes, and how long it may run. With this page's variables:

```sh
python3 scripts/prepare_mithril_client.py --out "$D/bin/mithril-client"
```

**Success:** one JSON line whose `binarySha256` is the digest pinned in `scripts/prepare_mithril_client.py` and whose
`executable` is the path given; any other digest stops the script. Then the `release-mainnet` inputs:

```sh
mkdir -p "$D/mainnet/mithril"
AGGREGATOR=<the release-mainnet aggregator URL>
curl -fsSL -o "$D/mainnet/mithril/genesis.vkey" <the release-mainnet genesis verification key URL>
curl -fsSL -o "$D/mainnet/mithril/ancillary.vkey" <the release-mainnet ancillary verification key URL>
```

**The artifact**, the exact hash of one certified Cardano database snapshot (`latest` is refused):

```sh
docker run --rm -v "$D/bin":/mithril:ro --entrypoint /mithril/mithril-client scarab-jdk:local \
  --aggregator-endpoint "$AGGREGATOR" cardano-db snapshot list
```

Take the `Hash` of a recent row whose ledger state the import reads
([snapshots.md](snapshots.md#import-mithril-certified-bootstrap-into-a-fresh-database)).

**The import.** The destination must not exist, and its parent (`/data`) must be writable. Give
`--timeout-seconds` the time the whole download needs
([snapshots.md](snapshots.md#import-mithril-certified-bootstrap-into-a-fresh-database)):

```sh
scarab --rm -v "$D/bin":/mithril:ro -- import-mithril \
  --artifact <hash> --aggregator "$AGGREGATOR" \
  --genesis-verification-key /data/mainnet/mithril/genesis.vkey \
  --ancillary-verification-key /data/mainnet/mithril/ancillary.vkey \
  --mithril-client /mithril/mithril-client --timeout-seconds <seconds> \
  --config /data/mainnet/config/config.json --db /data/mainnet-db
```

**Success:** exit status 0 and a last line `{"kind":"importMithril","event":"done","artifact":...,"slot":...,"hash":...,"outputs":...}`
naming the ledger anchor; the database holds `mithril-bootstrap.json`, the record of what was verified. A failed
import is an `"event":"error"` line with a typed message and exit status 1, and nothing is published at `--db`; a bad
argument, or an input it cannot read (a verification key, the configuration), prints a plain message and exits 2
before the import starts.

**Following to the tip**, with the official topology ([configuration.md](configuration.md#peer-selection)):

```sh
mkdir -p "$D/mainnet-ipc"
scarab -d --name scarab-mainnet -- follow --config /data/mainnet/config/config.json \
  --topology /data/mainnet/config/topology.json --db /data/mainnet-db --local-socket /data/mainnet-ipc/node.socket
docker logs -f scarab-mainnet
```

**Success**, in the log ([logging.md](logging.md#log-events)):

- the warning about `bootstrapPeers` above; `snapshotRestored` with the import's slot and hash as its `anchor`; then
  `started` with `"networkMagic":764824073` and a `ledgerPeers` line `snapshotLoaded`; and no `snapshotRefused`,
  `snapshotSkipped` or `"event":"error"` line;
- a `gsmTransition` with `"transition":"preSyncingToSyncing"` once the node's outbound connections are trusted,
  `extended` lines, one a block, whose `block` slots catch up with the wall clock, and once the node judges itself
  caught up a `gsmTransition` with `"transition":"enterCaughtUp"`;
- `cardano-cli latest query tip --mainnet` (step 5) answers `"syncProgress": "100.00"`, and its `block` and `hash`
  are what an independent explorer shows at that height, a minute later too;
- no `stalled` line ([troubleshooting.md](troubleshooting.md#stall-watchdog)).

The [compatibility matrix](../compatibility/fast-start-mithril.md) records what this start has been shown to do and
what is still open.

## 4. A preprod node

As step 3, with preprod's inputs: the same loop from `https://book.play.dev.cardano.org/environments/preprod/`
into `$D/preprod/config`, without `checkpoints.json` (preprod's configuration names none); the `release-preprod`
entry of the Mithril `networks.json`, its keys in `$D/preprod/mithril`; and `--db /data/preprod-db`. Then:

```sh
mkdir -p "$D/preprod-ipc"
scarab -d --name scarab-preprod -- follow --config /data/preprod/config/config.json \
  --topology /data/preprod/config/topology.json --db /data/preprod-db --local-socket /data/preprod-ipc/node.socket
```

**Success:** as in step 3, with `"networkMagic":1` in `started`. To sync from genesis instead, follow
[configuration.md](configuration.md#preprod), on the same files.

## 5. `cardano-cli` against the node-to-client socket

The node serves LocalStateQuery, LocalTxSubmission, LocalTxMonitor and local ChainSync on `--local-socket`
([node-to-client.md](node-to-client.md)). Run the reference image's `cardano-cli` as the node's user (the socket's
mode comes from the node's umask), with the socket's directory at `/ipc` and the current directory at `/w`:

```sh
IPC="$D/mainnet-ipc"
mkdir -p "$D/cli" && cd "$D/cli"     # cli mounts the current directory; keep its files out of the checkout
cli() {
  docker run --rm -i --user "$(id -u):$(id -g)" -v "$IPC":/ipc -v "$PWD":/w -w /w \
    -e CARDANO_NODE_SOCKET_PATH=/ipc/node.socket --entrypoint /opt/reference/bin/cardano-cli "$REF" "$@"
}
cli latest query tip --mainnet
cli latest query protocol-parameters --mainnet --out-file pparams.json
cli latest query era-history --mainnet --out-file era-history.json
cli latest query tx-mempool info --mainnet
```

**Success:** each exits 0. `query tip` prints a JSON object whose `"era"` is the network's current era, as an
explorer shows it, and `"syncProgress": "100.00"` at the tip; `tx-mempool info` prints `capacityInBytes`, `numberOfTxs`, `sizeInBytes` and `slot`. The queries answered,
and those not answered yet, are listed in [node-to-client.md](node-to-client.md#node-to-client); an unanswered one
ends the connection, and the node logs an `inbound ... closed` line naming it.

**Local chain sync.** `cardano-cli ping` in its `tip` mode negotiates the node-to-client handshake on the socket
and reads the tip through local ChainSync; `query` mode lists the versions the node accepts:

```sh
cli ping --mode tip --count 1 --network-magic 764824073 --json /ipc/node.socket
cli ping --mode query --network-magic 764824073 /ipc/node.socket
```

**Success:** both exit 0; the first prints a `negotiated_versions` line and a line with the `blockNo`, `hash` and
`slotNo` of the node's tip, the second one `NodeToClientV_...` line for each version the node accepts
([flags.md](flags.md)). `cardano-cli` has no command that follows
the chain block by block; the devnet's `ntc` scenario does, on both nodes' sockets, and requires every block to be
equal ([devnet.md](devnet.md#devnet-scenarios-scriptsdevnetpy)). A tool such as db-sync or Ogmios on the socket uses
the same protocol.

**Transaction submission**, on preprod (testnet magic 1) against step 4's node, with test funds from the preprod
faucet. The keys go in `KEYS`; `cli` mounts the current directory:

```sh
IPC="$D/preprod-ipc"
mkdir -p -m 700 "$KEYS/preprod" && cd "$KEYS/preprod"
cli latest address key-gen --verification-key-file payment.vkey --signing-key-file payment.skey
cli latest stake-address key-gen --verification-key-file stake.vkey --signing-key-file stake.skey
cli latest address build --payment-verification-key-file payment.vkey --stake-verification-key-file stake.vkey \
  --testnet-magic 1 --out-file payment.addr
# fund payment.addr from the faucet, then
cli latest query utxo --address "$(cat payment.addr)" --testnet-magic 1
cli latest transaction build --tx-in <txid>#<index> --tx-out "$(cat payment.addr)"+5000000 \
  --change-address "$(cat payment.addr)" --testnet-magic 1 --out-file tx.body
cli latest transaction sign --tx-body-file tx.body --signing-key-file payment.skey --testnet-magic 1 --out-file tx.signed
cli latest transaction submit --tx-file tx.signed --testnet-magic 1
TXID=$(cli latest transaction txid --tx-file tx.signed --output-text)
cli latest query tx-mempool tx-exists "$TXID" --testnet-magic 1
cli latest query utxo --tx-in "$TXID#0" --testnet-magic 1
```

**Success:** `transaction build` prints the estimated fee; `transaction submit` prints `Transaction successfully
submitted.` and the transaction's hash; `tx-exists` finds it while it waits in the mempool; once it is in a block
the last query shows its output, and an explorer shows the transaction. A refused transaction prints the ledger's
predicate failure, as against the Haskell node ([node-to-client.md](node-to-client.md#node-to-client)).

## 6. A pool on preprod

The procedure, and what it does not cover, is in
[forging.md](forging.md#a-public-pool-on-a-test-network-h1h1a-37115); the steps below give its commands, in step 5's
key directory `$KEYS/preprod` and with its `cli`.

**Keys**, made where the cold key will stay:

```sh
cli latest node key-gen --cold-verification-key-file cold.vkey --cold-signing-key-file cold.skey \
  --operational-certificate-issue-counter-file opcert.counter
cli latest node key-gen-VRF --verification-key-file vrf.vkey --signing-key-file vrf.skey
cli latest node key-gen-KES --verification-key-file kes.vkey --signing-key-file kes.skey
```

**Registration.** Read `stakeAddressDeposit` and `minPoolCost` from `query protocol-parameters`, make the three
certificates, and submit them in one transaction signed by the payment, stake and cold keys:

```sh
cli latest stake-address registration-certificate --stake-verification-key-file stake.vkey \
  --key-reg-deposit-amt <stakeAddressDeposit> --out-file stake-reg.cert
cli latest stake-pool registration-certificate --cold-verification-key-file cold.vkey --vrf-verification-key-file vrf.vkey \
  --pool-pledge <lovelace> --pool-cost <at least minPoolCost> --pool-margin <0 to 1> \
  --pool-reward-account-verification-key-file stake.vkey --pool-owner-stake-verification-key-file stake.vkey \
  --pool-relay-ipv4 <public address> --pool-relay-port 3001 --testnet-magic 1 --out-file pool-reg.cert
cli latest stake-address stake-delegation-certificate --stake-verification-key-file stake.vkey \
  --cold-verification-key-file cold.vkey --out-file deleg.cert
cli latest transaction build --tx-in <txid>#<index> --change-address "$(cat payment.addr)" \
  --certificate-file stake-reg.cert --certificate-file pool-reg.cert --certificate-file deleg.cert \
  --witness-override 3 --testnet-magic 1 --out-file reg.body
cli latest transaction sign --tx-body-file reg.body --signing-key-file payment.skey --signing-key-file stake.skey \
  --signing-key-file cold.skey --testnet-magic 1 --out-file reg.signed
cli latest transaction submit --tx-file reg.signed --testnet-magic 1
POOL=$(cli latest stake-pool id --cold-verification-key-file cold.vkey --output-format bech32)
cli latest query pool-state --stake-pool-id "$POOL" --testnet-magic 1
```

**Success:** once the transaction is in a block, `query pool-state` shows the pool's registered parameters. Then
have faucet stake delegated to the pool and wait for the stake snapshot: the pool can lead slots from the epoch
after the one the snapshot was taken in ([forging.md](forging.md#forging)). The pool's stake appears in
`stakeMark`, then `stakeSet` and `stakeGo` at the following epoch boundaries, and `leadership-schedule` lists the
pool's leader slots in the current epoch:

```sh
cli latest query stake-snapshot --stake-pool-id "$POOL" --testnet-magic 1
cp "$D/preprod/config/shelley-genesis.json" .
cli latest query leadership-schedule --genesis shelley-genesis.json --stake-pool-id "$POOL" \
  --vrf-signing-key-file vrf.skey --current --testnet-magic 1
```

**The operational certificate.** Its start is the current KES period, the tip's slot divided by the Shelley
genesis' `slotsPerKESPeriod`:

```sh
SLOT=$(cli latest query tip --testnet-magic 1 | python3 -c 'import json, sys; print(json.load(sys.stdin)["slot"])')
PER=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["slotsPerKESPeriod"])' "$D/preprod/config/shelley-genesis.json")
cli latest node issue-op-cert --kes-verification-key-file kes.vkey --cold-signing-key-file cold.skey \
  --operational-certificate-issue-counter-file opcert.counter --kes-period $((SLOT / PER)) --out-file opcert.cert
```

**Forging.** Give the node a directory with only `vrf.skey`, `kes.skey` and `opcert.cert`, never the cold key. The
node writes the evolved KES key back, so `kes.skey` (mode 0600, as `cardano-cli` writes it) and its directory must be
writable by the node's user; a VRF key file readable by others is refused ([forging.md](forging.md#forging)). Scarab
has been shown forging on preprod in Praos mode only, so the pool runs on a copy of the configuration with only
`ConsensusMode` changed. Stop step 4's node, then start the pool on its database; a loopback `--listen` would keep
the node from dialling other hosts ([flags.md](flags.md)):

```sh
python3 -c 'import json, sys; c = json.load(open(sys.argv[1])); c["ConsensusMode"] = "PraosMode"; json.dump(c, open(sys.argv[2], "w"), indent=2)' \
  "$D/preprod/config/config.json" "$D/preprod/config/config-praos.json"
docker stop -t 120 scarab-preprod
mkdir -p -m 700 "$KEYS/preprod-pool"
cp "$KEYS/preprod/vrf.skey" "$KEYS/preprod/kes.skey" "$KEYS/preprod/opcert.cert" "$KEYS/preprod-pool/"
scarab -d --name scarab-preprod-pool -p 3001:3001 -v "$KEYS/preprod-pool":/keys -- \
  follow --config /data/preprod/config/config-praos.json --topology /data/preprod/config/topology.json \
  --db /data/preprod-db --local-socket /data/preprod-ipc/node.socket --listen 0.0.0.0:3001 \
  --forge --vrf-skey /keys/vrf.skey --kes-skey /keys/kes.skey --opcert /keys/opcert.cert
```

**Success:**

- at start, `blockForgingUpdate` with `EnabledBlockForging`, then `forgeStarted` with the pool's ID (`pool`),
  `opCertCounter`, `kesPeriod` and `remainingKesPeriods`; no `kesReloadRefused`, `forgeStateUpdateError` or
  `"event":"error"` line;
- at each epoch boundary a `forgeEpoch` line with the epoch's `leaderSlots`, `forged` and `adopted`, and
  `docker exec scarab-preprod-pool curl -s localhost:7071/forging` gives the same counters
  ([node-to-client.md](node-to-client.md#query-server));
- in a leader slot, `forged` and then `adopted` for the same slot and hash, and that block on an explorer and in a
  Haskell node's chain at its height. A `lost` line for the slot, after its `forged` or later when another block
  replaces it, is a lost block, not a success ([forging.md](forging.md#forging)).

Rotating the KES key before `remainingKesPeriods` runs out is in
[forging.md](forging.md#rotating-the-kes-key-h2a-116).

## 7. Cleaning up

```sh
docker stop -t 120 scarab-mainnet scarab-preprod-pool
docker rm scarab-mainnet scarab-preprod scarab-preprod-pool
python3 "$REPO/scripts/devnet.py" --name my-scarab down
```

Remove `KEYS` when the test ends, unless the pool is to keep running. The mainnet and preprod data stay in `$D`
(`down` removes only the devnet's run directory); nothing is written into the checkout.
