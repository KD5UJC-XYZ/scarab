<!-- SPDX-License-Identifier: Apache-2.0 -->
# Configuration and topology files

Part of [Architecture](../architecture.md).

## Configuration and topology files (`--config`, `--topology`)

`NodeConfig` (`NodeConfig.scala`) reads the Haskell node's `config.json` as `Cardano.Node.Configuration.POM`
does: the genesis file names (`ByronGenesisFile` or `GenesisFile`, `ShelleyGenesisFile`, `AlonzoGenesisFile`,
`ConwayGenesisFile`, `DijkstraGenesisFile`, `CheckpointsFile`) with their hashes, `Test<Era>HardForkAtEpoch`,
`RequiresNetworkMagic`, `PBftSignatureThreshold`, `LastKnownBlockVersion-*`, `MaxKnownMajorProtocolVersion`,
`ExperimentalHardForksEnabled`, `ConsensusMode`, `PeerSharing`, `DiffusionMode`, `SocketPath`, `DatabasePath`
and `MempoolCapacityBytesOverride`. Parsing is pure (`NodeConfig.parse(text, strict)`; `strict`, used for
`--config`, insists on what `cardano-node run` insists on), and a failure is a `ConfigError`. The keys that do
not shape the node are kept in `ConfigSettings` and logged; `ConfigSettings.unsupported` names the ones that ask
for something not implemented (the Dijkstra era, Genesis syncing, hard forks triggered by a version, peer
sharing).

`NodeSetup.verifyHashes(config, read)` checks every hash the configuration lists against the file it names: the
Byron hash is the blake2b-256 of the canonical JSON (sorted keys, no whitespace), the Shelley, Alonzo, Conway
(and Dijkstra and checkpoints, when the file is present) hashes the blake2b-256 of the file's bytes. A
mismatch is `ConfigProblem.GenesisHashMismatch(role, file, expected, actual)`. It runs for a genesis
directory and for `--config`, before anything is derived from the files; the Shelley hash is also the initial
Praos nonce, so the bytes hashed are the bytes used. `NodeSetup.fromConfigFile(config, genesisDir)` resolves
the names against the configuration's directory (or `genesisDir`), as the reference does; `Main.configured`
completes the options (the peers of the topology after the `--peer` ones, and, with `--config` only, the socket,
database and mempool capacity of the file as defaults of their flags).

`Topology` (`Topology.scala`) is the P2P `topology.json` (`Cardano.Node.Configuration.TopologyP2P` and
`Ouroboros.Network.OrphanInstances`): `localRoots` (groups with `accessPoints`, `valency` or `hotValency`,
`warmValency`, `advertise`, `behindFirewall`, `diffusionMode`, `trustable`), `publicRoots`, `bootstrapPeers`,
`useLedgerAfterSlot` and `peerSnapshotFile`. `Topology.peers` is what this node follows: local roots, then
bootstrap peers, then (only if neither names one) public roots, without repeats. The reference's refusals are
kept (no port: a DNS SRV name, here `UnsupportedTopology`; the unspecified address; an empty bootstrap list
with no trustable local root; the legacy `Producers` format).
