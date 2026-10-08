# Bounded private reference cluster

The launcher uses the official Cardano 11.1.3 Linux amd64 release binaries to create three disposable local nodes. It requires local Linux Docker and existing Python 3.11+ for host orchestration; it installs nothing. All node/JVM workloads run in containers. No source/image push or transaction submission occurs.

## Prepare once

Download the exact [official archive](https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/cardano-node-11.1.3-linux-amd64.tar.gz) outside the checkout. Expected size is 233694227 bytes, SHA-256 `530fb99986fbdccc46f9ee0dd86cabb1393b015feac9173cb731d89fa748365f`, independently listed in the [official checksum manifest](https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/cardano-node-11.1.3-sha256sums.txt). Release source revision is `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`.

With the documented digest-pinned official node image already cached:

```sh
python3 scripts/prepare_reference_image.py /absolute/task/release.tar.gz
```

This verifies the archive and each selected executable before building a local-only image. It never extracts keys or public-network configuration from the archive. The launcher checks executable hashes again before each run. Actual release component versions are node 11.1.3, CLI 11.2.3.0, and testnet 11.1.1 (testnet's version output calls itself `cardano-node` and has a zero git stamp). Testnet links API 11.6.0.0 and CLI 11.2.3.0. `CARDANO_CLI` and `CARDANO_NODE` must explicitly select these matching binaries; PATH alone is insufficient.

## Reference smoke

```sh
python3 scripts/private_cluster.py \
  --reference-image cardano-reference-11.1.3:local \
  --output /absolute/task/new-evidence-directory --seconds 240
```

The output directory must be new and outside the checkout. The fixed profile uses magic 1082026, three pool nodes, fresh DBs, 500-slot epochs, 0.1-second slots, active-slot coefficient 0.05 and security parameter 5. The launcher changes the actual Shelley genesis protocol version to 9.0, retains epoch-zero transitions through Conway, and rejects Dijkstra transitions. Testnet refreshes timestamps; captured genesis files are the effective files after startup.

Success requires live Conway/PV9 queries, block growth over two epoch boundaries, and recent sampled three-node tip convergence. Sequential queries may straddle a newly forged block; a single differing sample is not automatically divergence. These are bounded smoke criteria, not a long-term consensus proof.

Only loopback topology is accepted. Bootstrap peers, ledger discovery, peer sharing, peer snapshots and external trace/metrics destinations are disabled or rejected. A unique internal Docker network has no published ports. Reference resources are limited to 3 CPUs, 6 GiB and 384 PIDs. Credentials and DBs exist only in a 2 GiB tmpfs; no keys are exported. A host watchdog and finally cleanup remove only the uniquely named task resources, with a maximum 480-second budget including preflight, generation and workload, with 120 seconds reserved for cleanup within a 600-second overall deadline. Abrupt host failure/SIGKILL cannot run Python finalizers; the container supervisor also exits at the remaining workload deadline, and the recorded resource name supports manual task-only cleanup.

## Scala handshake scenario

Compile in the cached official JDK21 container using the repository `scripts/sbtw` wrapper. Mount the checkout at `/work` so its generated runtime classpath remains valid; dependencies must be available before the isolated run:

```sh
scripts/sbtw app/compile app/runtimeClasspathFile
```

Then add `--scala-repo /absolute/checkout` to the launcher. The probe uses the existing Scala TCP/mux/handshake implementation and offers node-to-node wire version 14 to the real Haskell node. Its JDK container shares only the isolated reference container's network namespace, mounts the compiled checkout read-only, and receives no credentials or Docker socket. Its limit is 1 CPU/1 GiB/128 PIDs; combined reference-plus-probe ceilings are 4 CPUs/7 GiB/512 PIDs, with memory-plus-swap set equal to memory (no additional swap allowance). It closes the connection after handshake. This establishes narrow transport interoperability only; it does not validate blocks, ledger transitions, signatures, transactions, or a whole node.

The CLI `reference-handshake PORT PRIVATE_NETWORK_MAGIC` fixes its endpoint to `127.0.0.1`, has bounded transport/session deadlines, and does no DNS lookup. The launcher establishes reference image identity; the CLI alone cannot identify the remote software.

Ledger PV9, node software 11.1.3, and wire version 14 are distinct. The handshake-only scenario does not decode header-advertised protocol version. The optional [live capture scenario](reference-capture.md) measures it from original header bytes.

## Evidence and next scenarios

Evidence includes exact Docker commands, binary/image hashes, actual queried parameters and tips, allowlisted public configuration/genesis, node logs and cleanup checks, all outside Git. The capture list excludes private keys and DBs. Unit tests cover isolation and readiness rejection; they are not substitutes for live reference evidence.

The optional `--capture` increment records a live header and exact block with provenance and compares original bytes, ancestry and body commitments; see [reference-capture.md](reference-capture.md). Fresh-genesis ledger context must be integrated explicitly before comparing transaction acceptance or full replay. Historical restricted replay inputs are not automatically suitable. The untransferred cloud scaffold is unused.

Each cleanup operation and each verification runs independently with a bounded timeout. The receipt records failures and unknown status; an unresponsive Docker daemon cannot be treated as verified removal. The ten-minute budget is a host orchestration deadline, not a guarantee of daemon responsiveness.
