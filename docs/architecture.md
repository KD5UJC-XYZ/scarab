# Architecture

**Status: Current.** Module boundaries and runtime responsibilities; historical measurements
and removed implementations are recorded separately in [performance.md](performance.md).

How the node is put together. For flags and scenarios see [running.md](running.md); for what is
verified against what see [compatibility.md](compatibility.md); for facts learned from real data
see [findings.md](findings.md).

## Modules

The module table names directories; `build.sbt` defines their project and artifact names
(organisation `xyz.kd5ujc`). Most share their directory name; `blst-native` uses project
`blstNative` and package `xyz.kd5ujc.scarab.blst`, while `models-gen` uses project `modelsGen`
and package `xyz.kd5ujc.scarab.models.gen`. The dependency direction is enforced by sbt:
a cycle does not load.

| Module | Contents | Effect style |
|---|---|---|
| `blst-native` | The portable libblst (ADR 0012) at the resource path blst-java loads, and `Blst`, the start-up check of the library and the CPU (sbt project `blstNative`) | `F[_]` with `Sync`/`Raise` |
| `codec` | CBOR with original-bytes preservation (`Cbor`, `Node`, `Value`), `Bytes`, `Rational` | Pure |
| `crypto` | Blake2b, Ed25519 (strict profile), VRF draft-03 verify and prove, Sum6 KES verify, sign and evolve, BLS12-381 (Leios), operational certificates | Pure |
| `protocol` | Ouroboros mini-protocol codecs and state machines: handshake, mux framing, ChainSync, BlockFetch, KeepAlive, TxSubmission2, LocalTxSubmission, LocalStateQuery, LocalTxMonitor, Leios, era and header tags (`Era`, `NtNHeader`), pure network error vocabulary | Pure |
| `network` | fs2 bearer, multiplexer (duplex), handshake, client and server roles of every mini-protocol over one connection | `F[_]` with `Async`/`Raise` |
| `plutus` | `ScriptEvaluator[F]` algebra, the Scalus adapter, `PlutusData`, `ExUnits` | `F[_]` at the evaluator, pure otherwise |
| `ledger` | Blocks, headers, genesis files, transactions, scripts, the Shelley to Conway rules, the Byron ledger, `Verdicts`, `OCertRules`, the node error hierarchy, `Nonce`, `LedgerView` | Pure |
| `consensus` | Praos, TPraos and PBFT, chain selection, the hard-fork combinator, header rules, `VolatileChain`, `MirrorState` and the mirror's types, `BlockRules`, `Scheduling` | Pure, `Compute[F]` in `Scheduling` |
| `legacy` | Flat genesis/domain vocabulary and its one-way conversion to the mirror (`Mirroring`, `LegacySeed`), retained for genesis conversion (#134); retired V1–V4 binary snapshot codecs are recovered from pinned history, not shipped runtime readers | Pure |
| `storage` | `ImmutableStore`, `VolatileStore`, `LedgerSnapshots`, `ChainStorage`, `UtxoStore` (memory, RocksDB), derived `Codec`s, format migrations, the Haskell ImmutableDB reader and the Haskell ledger-state codec | Algebras over `F[_]` |
| `node` | Peer, follower, header gate, block rules (`MirrorRules`, with what they share in `NodeRules`), mempool, forging, relay, node-to-client, query server, `Main` | Algebras over `F[_]`; `IO` only in `Main` and the tools |
| `models-gen`, `models` | CDDL parser and Scala generator; the generated models, their runtime and the equivalence tests. A spike: the node does not decode with the generated models, only `storage` uses the runtime (docs/models.md) | Build-time tool; pure |
| `fuzz` | Structured fuzzing of every decoder entry point (test only) | |

```
blst-native -> crypto, plutus (before blst-java on their class paths)

codec <- crypto <- protocol <- network ------------------------.
  ^                  ^                                          \
  |                  '--- ledger <- consensus <- storage <------ node
  |                                           '--- legacy <------'
  '------ plutus <------'
```

`ledger` depends on `protocol` and `plutus`; `consensus` on `ledger`; `legacy` on `consensus`; `storage` on `consensus` and
`models` (which stands on `codec` and `crypto`; the Haskell ledger-state codec reuses the models runtime; `models` itself sees `ledger` only in
its tests); `node` on `storage`, `legacy` and `network`. The ledger cannot import the network runtime, and storage cannot
import the node. Note that `protocol/Mux.scala` (the pure framing) and `network/Mux.scala` (the
effectful multiplexer) are different files. The pure `NetworkError` vocabulary lives in `protocol`;
`network.NetworkError` aliases it. This lets ledger and node adapters retain transport causes
without making the ledger depend on the effectful network runtime.

Cycles that the old layout hid, and how they were cut (types moved down, nothing redesigned):
the header error enums (`HeaderError`, `EnvelopeError`, `TPraosError`, `OCertError`) and `Nonce`,
`PoolDistribution`, `LedgerView` live in `ledger` because `NodeError` (also in `ledger`) and the
rules use them; `ExUnits` lives in `plutus`; `Rational` in `codec`; `Prepared` (a decoded block)
in `ledger`; `MirrorState` and the `BlockRules` interface in `consensus`, while `MirrorRules`, which
needs the `UtxoStore`, stays in `node`.

## Layers

Paths are file names within the module that holds them (see the module table; a layer can span
several modules).

| Layer | Where | Style |
|---|---|---|
| Errors | `NodeError.scala` | Closed hierarchy, see "Error model" |
| Peer | `Peer.scala` | `Peer[F]` algebra (intersect / next / fetch) with a node-to-node interpreter over `network`: `Peer.nodeToNode` for a connection we open, `Peer.over` for any negotiated connection (also an inbound duplex one) |
| Header-first | `HeaderGate.scala`, `NodeHeaderRules.scala`, `HeaderValidation.scala`, `consensus/HeaderRules.scala` | `HeaderCheck`/`HeaderGate` validate each ChainSync batch before its blocks are fetched; the pure `HeaderRules[S, H]` (`NodeHeaderRules`) forecasts the ledger view; `HeaderChain` is the candidate chain's header states (G6) |
| Connections | `InboundGovernor.scala`, `network/ConnectionManager.scala`, `protocol/ConnectionState.scala` | Pure connection life cycle, the manager's table (one connection per address and direction, admission limits and typed refusals), and the governor that runs our clients on inbound duplex connections (G2) |
| Peer selection | `network/selection/*.scala`, `PeerGovernor.scala`, `OutboundPeers.scala`, `PeerNetwork.scala`, `GsmRunner.scala`, `FailurePolicy.scala` | The pure peer governor (state, decision, events, targets, local roots, sensitive state, the Genesis State Machine), its loop and its interpreter over the connection manager, the follower and the block pool (G1.1) |
| Decoding | `Header.scala`, `Block.scala` | Era-aware: `Header.Shelley` (TPraos or Praos `HeaderVrf`) and `Header.Byron`; blocks decoded per era with the body hash recomputed from the original bytes |
| Chain | `VolatileChain.scala` | Pure last-k chain of `(block, state)`; rollback restores the exact retained state |
| Follower | `Follower.scala`, `consensus/ChainSelection.scala` | Program over `Stateful[F, VolatileChain[S]]` and `BlockRules[F, S]`: one candidate chain per peer session, Praos chain selection, adoption on a copy of the chain; session owners classify typed outcomes through `FailurePolicy` |
| Consensus | `consensus/Praos.scala`, `TPraos.scala`, `OCertRules.scala`, `ByronRules.scala` | VRF leader check, KES, opcert counters, nonce evolution; TPraos adds the overlay schedule, two VRF certificates and the 512-bit leader value |
| Byron ledger | `ledger/byron/` | Pure: addresses and roots, transactions and witnesses, the UTxO rules and fee policy, delegation certificates, the update system, the body proofs and the Byron to Shelley transition epoch |
| Ledger | `ledger/` | Pure functions: transaction decoding, UTXOW/UTXO/CERTS rules, deposits, withdrawals, EPOCH (rewards, SNAP, POOLREAP, dormant epochs), and from Conway GOV, RATIFY, ENACT and the DRep pulser |
| Block rule | `MirrorRules.scala` (the rules), `LedgerRules.scala` (`NodeRules`: block decoding, the Byron step, the events between two states) | Tick ledger and Praos to the block's epoch, validate the header, apply the body |
| Plutus | `plutus/`, `ledger/Phase2.scala`, `ScriptContexts.scala`, `ScriptIntegrity.scala` | `ScriptEvaluator[F]` algebra; Scalus interpreter; our own script contexts and cost-model selection |
| UTxO set | `ledger/Utxo.scala`, `storage/UtxoStore.scala`, `RocksUtxoStore.scala` | The UTxO set behind `UtxoStore[F]` (memory or RocksDB); the ledger state holds a pure view |
| Storage | `storage/` | `ImmutableStore`, `VolatileStore`, `LedgerSnapshots`, `ChainStorage`, `NetworkIdentityFile`, derived `Codec`s |
| Scheduling | `Scheduling.scala` | `Compute[F]` (the CPU pool the follower runs on, a `Resource`), timer-lateness monitor, `SchedulingStats` |
| Relay | `relay/`, `mempool/`, `network` servers | Serves the node's chain, collects and offers transactions, accepts local submissions |
| Forging | `forge/`, `Follower.adoptForged`, `crypto/.../vrf/Draft03Prover`, `crypto/.../kes/Sum6KesSigning` | Praos block production |
| Query | `Query.scala` | `ChainView[F, S]` plus a loopback http4s server: `GET /tip`, `GET /state?slot=&hash=` (used by the devnet comparator), `GET /scheduling`, `GET /connections` (connection table and inbound sessions), `GET /metrics` (Prometheus text format; `MetricsServer` serves it alone on `--metrics-listen`) |

All configuration (epoch geometry, k, f, KES parameters, protocol parameters, initial UTxO and
staking) is read from the genesis files; nothing assumes a particular network. Ledger arithmetic
uses an exact `Rational` with Haskell `floor` semantics.

## Algebras

Capabilities are traits over `F[_]`; interpreters are chosen in `Main`:

| Algebra | Role | Interpreters |
|---|---|---|
| `Peer[F]` | intersect, next header, fetch blocks | node-to-node over TCP or over any negotiated connection (pipelined), scripted peers in tests |
| `HeaderRules[S, H]`, `HeaderCheck[F]` | forecast the ledger view and validate a header against it; per session, validate each batch before it is fetched | `NodeHeaderRules` (pure), `HeaderCheck.forNodeChain` (the canonical gate over the node's chain), `HeaderCheck.none` |
| `ConnectionManager[F]` | one connection per peer address and direction, admission policy, limits and accounting | `ConnectionManager.create` (a `Ref` table over the pure `ConnectionState`) |
| `BlockRules[F, S]` | validate and apply one block to a state | `MirrorRules` (the era-indexed rules on the mirror state) |
| `UtxoStore[F]` | read, write, checkpoint, restore the UTxO set | `MemoryUtxoStore`, `RocksUtxoStore` |
| `ImmutableStore[F]` | append-only raw blocks older than k | `FileImmutableStore` |
| `VolatileStore[F]` | crash-safe log of the volatile chain's updates | `VolatileStore.open` (file log) |
| `Compute[F]` | run the follower (CPU-bound) on its own pool, time pure steps | `Compute.pool` (work-stealing pool, a `Resource`), `Compute.measured`, `Compute.inline` (tests) |
| `LedgerSnapshots[F, S]` | atomic checksummed snapshots of the node state | `FileLedgerSnapshots` |
| `ScriptEvaluator[F]` | evaluate one Plutus script request | `ScalusEvaluator` (pure `Id` and suspended `F` forms) |
| `Mempool[F]` | validated transaction pool, TxSubmission2 source and sink | `Mempool` over pure `MempoolState` |
| `ChainView[F, S]` | read access for the query server | `ChainView.fromRef` |
| `Bearer[F]`, `Mux[F]` | byte transport and multiplexer | TCP, in-memory (tests) |
| `FollowerEvents[F]`, `ForgeEvents[F]`, `StorageEvents[F]`, `MempoolEvents[F]` | structured event sinks | the log (`Log.follower`, `Log.sink`, ...) beside the metrics |
| `Log[F]` (`Tell[F, Line]`) | the log: lines built from typed events (`Traced[E]`, a closed `EventKind` with namespace and severity; `NodeEvent`) | `LogRouter`: stdout, stderr and a size-rotated file, JSON or human, per-namespace severity and sampling; the libraries' SLF4J lines through `LibraryLogs` ([ADR 0014](adr/0014-typed-log.md)) |
| `Metrics[F]` | typed measurements (`CounterDef`, `GaugeDef`, `HistogramDef` of the `Catalogue`) and a snapshot of them | `Metrics.create` (a `Ref`), rendered by `Exposition` in the Prometheus text format; fed by `EventMetrics` (the same event sinks as above, side by side with the log through `EventSink.alongside`), `MeteredRules` and `Collectors` |

Purity discipline: rules (header checks, ledger, ratification, mempool state, chain selection,
Plutus context construction) are pure functions or values; `F` appears only where the world is
touched (network, disk, clock, concurrency). The deliberate performance caches are instances, not
globals (roadmap M3): `VerificationCache` (created in a `Resource` in `Main`, read by the rules through
the pure `Verdicts` interface in `PraosConfig.verdicts` and `TxEnv.verdicts`), the `PlutusCases`
recorder (tooling, passed as the phase-2 observer) and the RocksDB handle reference (private to its
store). Rules run without a cache use `Verdicts.Direct`.

## Error model

Pure operations return `Either` with a closed domain/service error ADT. Effectful interpreters
raise their declared errors through cats-mtl `Raise[F, E]` and handle them at lexically scoped
`Handle.allow` sites; `raiseError` is not used for domain errors. Cases retain semantic inputs and
nested causes; messages are presentation, not control flow.

`Encoder[A]` declares `type Error <: DomainError`; both its `encode` and `Serde[A].decode`
return that family. `Encoder.Aux[A, E]`/`Serde.Aux[A, E]` name a particular family, and generic
consumers such as `Hasher.encoded` preserve `encoder.Error`. The open `DomainError` marker admits
closed families to generic cross-module bridges such as `StorageError.Corrupt[E]`; it does not
replace their narrow API types or prove their semantics. See [the error design](design/domain-errors.md).

| ADT | Meaning | Handling |
|---|---|---|
| `PeerError` | the peer misbehaved or the connection failed (`Handshake`, `Protocol`, `Unavailable`, `InvalidHeader`: a header broke a header rule, G6); recoverable | the peer governor classifies it (`FailurePolicy.classify`: forget the peer or back off) and reconnects; on an inbound connection the inbound governor drops the peer for `Protocol` and `InvalidHeader` |
| `ConnectionError` (`network`) | the connection manager refused: `InboundLimit`, `OutboundLimit`, `AlreadyConnected`, `IllegalTransition` (G2) | a refusal of an inbound connection closes it; an outbound one becomes `PeerError.Unavailable` and is retried |
| `ChainError` | chain-level inconsistency (for example a candidate that no longer joins our chain) | per-session or fatal |
| `ValidationError(point, failure: BlockFailure)` | a block broke a consensus or ledger rule | fatal for the sync (the chain stays at the last valid block) |
| `StorageError` | typed `Corrupt` causes, `Io` with its exception, `VersionMismatch`, `Missing`, `OutOfOrder` | IO exceptions are mapped at the file boundary only |
| `ForgeError` | key-file, KES, VRF, ledger-view and assembly failures | the forger logs and skips the slot |
| `ConfigError(problem: ConfigProblem)` | configuration, CLI, topology and genesis failures retain fields, input values and typed JSON, codec or service causes | fatal at start |

Programmer preconditions are distinct from domain rejection. The retained Scalus callback
`BuiltinException` signal is an explicit foreign ABI seam: the adapter records and recovers a
typed evaluator failure because the upstream callback cannot return one. The error design records
that limitation and the separate generator and compiler-guard follow-ups under M11-E; fiber
ownership remains G7 / #180.

## Data flow

```
peer(s) --ChainSync headers--> candidate chain (per session)
        --HeaderGate: header rules against a forecast ledger view (G6; invalid: drop the peer, fetch nothing)-->
        --BlockFetch ranges--> Verification.warm (parallel Ed25519 / opcert / KES / VRF verdicts)
                                      |
                                      v
                    MirrorRules: tick ledger and Praos to the block's epoch
                      -> header checks (VRF, KES, opcert, nonces, leader threshold)
                      -> body: decode once, fetch missing UTxO from the store,
                         UTXOW / UTXO / DELEGS / PPUP or Conway LEDGER, phase 2 (Plutus)
                                      |
                                      v
              VolatileChain (last k states)  --block older than k-->  ImmutableStore
                    |        |                                         LedgerSnapshots + UtxoStore checkpoint
                    |        +--> ChainServer / ServedChain --> ChainSync, BlockFetch servers --> downstream peers
                    +--> Mempool.sync (keep, drop, mark adopted) <--> TxSubmission2, LocalTxSubmission
Forger (slot clock, leader check) --> BlockBuilder (from mempool) --> Follower.adoptForged --> same path as any block
```

Concurrency: one producer fiber per peer keeps up to three batches in flight; one consumer applies
blocks sequentially; snapshots are written by a background fiber from the immutable (structurally
shared) anchor state; the forger, the servers and the query server run as resources next to the
sync.


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

## Era history and Byron

`EpochGeometry` is the node's era history: a Byron prefix (`ByronSpan`: epochs, 10k slots per
epoch, its own slot length) followed by the Shelley-based eras, which share one epoch length.
Epochs and slots are absolute, so `firstSlot(epoch)`, `epochOf(slot)`, the stability windows, the
TPraos overlay, KES periods and PPUP deadlines all work across the boundary. `NodeSetup` derives
it from `shelley-genesis.json`, `byron-genesis.json` and the node configuration
(`Test<Era>HardForkAtEpoch`). Byron slots per epoch are always `10 * k` of `byron-genesis.json`
(`protocolConsts.k`); every decoder takes them as a parameter and nothing defaults them. The first
Shelley epoch has three sources:

- `TestShelleyHardForkAtEpoch: 0` (Preview, `cardano-testnet` clusters): the chain starts in Shelley,
  no Byron prefix;
- `TestShelleyHardForkAtEpoch: n`: a fixed Byron prefix of `n` epochs (the `up-eras` devnet);
- the key absent (preprod, mainnet): the Byron ledger decides. The span is then `derived`
  (open-ended) until the chain enters Shelley; the epoch is stored in `ByronState.firstShelleyEpoch`
  and every consumer resolves the geometry from the state (`EpochGeometry.resolved`).

The era of each Shelley-based block is checked against `consensus/HardFork.scala`
(`HardForkConfig`): an era with a `Test<Era>HardForkAtEpoch` key is entered at that epoch, any
other when the ledger's protocol major version, ticked to the block's slot, reaches the era's first
version (Allegra 3, Mary 4, Alonzo 5, Babbage 7, Conway 9), as the HFC's `TriggerHardForkAtVersion`
does. The same check carries the envelope rules (maximum supported major version 11, header and
body size, Conway mainnet header version).

- **Chain model:** `VolatileChain.origin` is the hash the first block names (the Byron genesis
  hash: `ByronGenesisHash` from the configuration, else Blake2b-256 of the canonical JSON of
  `byron-genesis.json`). A Byron epoch-boundary block repeats its predecessor's block number and
  the main block after it may share its slot.
- **Byron consensus** (`consensus/ByronRules.scala`, ouroboros-consensus `Protocol/PBFT.hs`): the
  protocol magic, linkage and slot/epoch order; the delegate's Ed25519 signature over `"01" ++
  genesis key ++ 0x09 ++ cbor(magic) ++ [prev, proof, slot, difficulty, extra]`; the signer is the
  current delegate of a genesis key in the delegation map (activated up to the block's slot, see
  below); the slot is not below the last signed one; the PBFT window (the last `k` signers) holds at
  most `floor(PBftSignatureThreshold * k)` blocks of that genesis key (0.22 unless the node
  configuration says otherwise). Only heavyweight delegation exists: the signature tags for no
  delegation and light delegation do not decode, as upstream. Upstream does not require the
  round-robin leader of a slot (`slot mod n` over the genesis keys in ascending hash order, which
  only the forging side uses); each block's `onSchedule` is reported and counted per epoch.
- **Byron ledger** (`ledger/byron`, cardano-ledger `Cardano.Chain.Block.Validation`, `UTxO`,
  `Delegation`, `Update`): `ByronLedgerState` holds the UTxO (`Map[TxIn, ByronOut]`), the update
  state and the delegation state. Per block: the tick to the block's slot (epoch change: the newest
  candidate update stable for `4k` slots is adopted; delegations due take effect), header and block
  size against the adopted parameters, the body proofs (transaction count, Merkle root and
  witnesses hash, delegation payload hash, update payload hash; the SSC proof is not compared, as
  upstream), delegation certificates (a genesis key, this or the next epoch, once per epoch and per
  slot, valid signature, effective `2k` slots later, one-to-one map), transactions (inputs exist,
  witness signature under the Byron signing tag and protocol magic, the address root of the
  witnessed key, output network magic, size, value conservation, the linear fee `a + b * size`,
  zero for redeem-only inputs), and the update payload (proposal, votes, the block's endorsement of
  its version, confirmation, candidates). The genesis UTxO is the AVVM balances at the redeem
  addresses of their keys and the non-AVVM balances; each entry spends from the pseudo transaction
  whose id is the Blake2b-256 of the address.
- **Byron to Shelley:** the transition epoch is the configured one or, on a public network, taken
  from the update state exactly as the hard-fork combinator does (`byronTransition`): a candidate for
  protocol major version 2 that is stable (`k` blocks since it became a candidate, or `2k` slots)
  is adopted in the epoch after the one containing `candidate slot + 4k`. The first Shelley block
  hands the final Byron UTxO to `GenesisState.initial` (Shelley `initialFunds` plus the Byron UTxO,
  reserves the rest of the supply), sets the ledger epoch and drops the Byron state. The Praos state
  starts with every nonce equal to the Shelley genesis hash and is untouched by Byron blocks; the
  first Shelley block ticks it to the first Shelley epoch. The JSON log has an `epochValidated`
  line per Byron epoch (`signatureVerified`, `offSchedule`, `transactions`).
- **Logging:** every era transition is an `eraTransition` line (`from`, `to`, `epoch`, `slot`,
  `block`), the first one from no era; `extended` lines carry the block's `era`.

Tests: `EraTransitionSuite` replays a committed capture of the `up-eras` cluster
([`fixtures/eras`](../fixtures/eras/README.md)) through the node's block rules and a scripted
follower (no Docker); `EpochGeometrySuite` has the era-history properties.

## Ledger: Shelley to Babbage

`MirrorRules` applies the full ledger to every Shelley-based block, on the Haskell-shaped, era-indexed state (`ledger/rules/`; the flat rules and state of the first versions were removed in J2.9); era behaviour follows the protocol
major version of the parameters in force (Allegra 3, Mary 4, Alonzo 5, Babbage 7, Conway 9).

- **Decoding** (`ledger/Tx.scala`, `Scripts.scala`): transaction bodies of every era (keys 0-18),
  witness sets (vkey, bootstrap, native and Plutus scripts, datums, redeemers), outputs in legacy and
  map form (inline datums, reference scripts), certificates 0-6 (genesis delegation, MIR).
- **Rules** (`ledger/rules/ShelleyUtxo.scala`, `ShelleyLedger.scala`, `ShelleyCerts.scala`, `PreConway.scala`, with the checks shared with Conway in `ledger/TxChecks.scala`): UTXO (fee including the Alonzo execution-unit price,
  min-UTxO per era, validity interval, sizes, network ids, collateral), UTXOW (vkey and Byron
  bootstrap witnesses, native scripts, required signers, redeemers and datums, auxiliary data hash),
  DELEGS (registration, delegation, pools, genesis delegation, MIR), PPUP (`ledger/UpdateChecks.scala`). The fee size
  counts the array header, body, witnesses and auxiliary data, not the `is_valid` byte.
- **Epoch boundary** (`ledger/rules/Epoch.scala`, `Snap.scala`, `Rewards.scala`, `Rupd.scala`): reward application (pre-Babbage prefilter against the
  accounts registered when the calculation started, so unclaimed rewards return to the reserves),
  MIR, SNAP with pointer-address stake, pool re-registration and retirement, parameter update
  adoption, and the reward update with `d`-dependent performance and the previous epoch's
  parameters. The ledger creates the reward update with the first block that arrives after
  `4k/f` slots into the epoch (the reward update is made by `Rupd` at the first such block and applied at the boundary): an epoch with no such block pays no rewards and keeps its fee snapshot. The `set` snapshot is the pool distribution for the leader checks, so
  `LeaderCheck.Threshold` replaces `Unchecked`. (The check that the supply is the maximum supply at every boundary went with the old rules; `BlockFailure.SupplyInvariant` is no longer raised.)
- **Plutus (phase 2)**: every redeemer is evaluated (`ledger/Phase2.scala`) and the block's
  `is_valid` flag must equal the verdict, in both directions (`ValidationTagMismatch`); a valid
  transaction is applied and an invalid one consumes its collateral. See "Plutus evaluation" below.
  The script data hash is recomputed (`ledger/ScriptIntegrity.scala`) and a mismatch is a hard
  error (`PPViewHashesDontMatch`, and `ScriptIntegrityHashMismatch` from protocol version 11).
- **Conway** continues from the Babbage state through `Translate.toConway` (Conway parameters,
  committee and constitution from the Conway genesis, empty DRep state, pre-Conway update/MIR/pointer
  state dropped). The Conway rules are described in "Conway" below.
- **Nonce windows** (verified on Preview): candidate nonce freezes `3k/f` before the epoch end
  through Babbage; Conway and the devnet use `4k/f`. TPraos overlay formulas follow cardano-ledger
  `classifyOverlaySlot` (`position = ceiling(s * d)`, active when `position mod floor(1/f) = 0`).
- **Read-ahead:** while the peer is far ahead, ChainSync requests are pipelined and each batch is
  fetched with one BlockFetch range (`Peer.nodeToNode`, `ChainSyncClient.requestNextBatch`).


## Conway

`ledger/rules/Ledger.scala` (with `Gov.scala`, `Certs.scala` and `ConwayTx.scala`) is the LEDGER rule for protocol versions 9 and later (Preview runs 11).
For a phase-2 valid transaction it follows the ledger's order: treasury value and reference-script
size checks, the withdrawals (key accounts must be delegated to a DRep from version 10; a withdrawal
takes the whole balance), the DRep expiry updates, CERTS, GOV; UTXOW (scripts provided, needed and not
extraneous, datums, redeemers, vkey and bootstrap witnesses, the script data hash), UTXO (validity
interval, fee, value conservation, collateral) and UTXOS (phase 2). A transaction flagged invalid
consumes its collateral and nothing else.

- **Scripts**: native and Plutus V1, V2, V3, reference scripts, minting and burning, script
  credentials for spending, withdrawals, certificates, votes and proposals (`Purpose`). Redeemers in
  the array or the map form; the integrity hash is over the witness set's own bytes.
- **Fee**: `minFeeA * size + minFeeB + ceil(price * units) + tierRefScriptFee`. The reference-script
  fee prices the bytes of the reference scripts of the distinct inputs and reference inputs at
  `minFeeRefScriptCostPerByte`, multiplied by 1.2 for every further 25 KiB. A transaction may use 200 KiB
  of reference scripts, a block 1 MiB (counted against the block's starting UTxO up to protocol 10).
- **Value**: `inputs + withdrawals + refunds + mint = outputs + fee + deposits + donation`, where the
  deposits are the key, pool (new pools only), DRep and proposal deposits and the refunds come from the
  state before the transaction. A donation joins the treasury at the next epoch boundary.
- **Certificates**: registration (with or without deposit), deregistration, stake, vote and combined
  delegations, pool registration and retirement, DRep registration, update and retirement, committee
  hot-key authorisation and cold-key resignation. The DRep reverse delegation index is kept as the
  ledger keeps it, including the protocol-9 behaviour that a re-delegation does not remove the old
  entry; entering protocol 10 rebuilds it. DRep expiries account for dormant epochs from version 10.
- **GOV** (`ConwayGov`): the seven action types with deposit, return account, network, previous action
  (the proposal store checks ancestry per lineage), hard-fork version succession, well-formed parameter
  updates, the guardrails script hash of the constitution for parameter changes and treasury
  withdrawals, committee updates without conflicts and with future expiries; votes by DReps, pools and
  hot committee keys, who may vote on what, no votes on expired actions, and the bootstrap-phase
  restrictions of protocol 9 (only parameter changes, hard forks and info actions; DReps only vote on
  info actions).
- **Epoch boundary** (`ConwayEpoch`): the ratification result of the pulser that started a boundary
  earlier is applied: enacted treasury withdrawals reach the registered accounts, expired and enacted
  actions leave the proposal store (competitors and descendants with them) and their deposits go back
  to the return accounts (to the treasury if the account is gone), parameters, committee and
  constitution change, donations join the treasury, the dormant-epoch counter and the obligation
  (`deposited`) are updated, and a fresh pulser snapshots the state.
- **Ratification** (`Ratify`): a pure function of the pulser's snapshot. DRep stake distribution
  (UTxO stake, rewards and proposal deposits of delegating accounts; always-abstain excluded,
  always-no-confidence a no vote except on no-confidence), pool distribution of the mark snapshot plus
  proposal deposits, the pool default votes, committee ratios (silent members vote no; expired,
  resigned and unauthorised members are out; fewer active members than `committeeMinSize` means no
  committee), the thresholds by action and parameter group, the bootstrap phase (DRep thresholds zero,
  silent pools abstain), committee term limits, withdrawals bounded by the treasury, delaying
  actions. The ledger computes this incrementally over the epoch; the node computes it when the
  snapshot is taken and keeps both (`GovState.pulsed`), which is what `query ratify-state` shows.

`LedgerState` gained `gov`, `committeeState` and `donation` (plain case classes); `DRepState` has the
anchor and the delegators.

The protocol-11 rules (VRF key hash uniqueness, unelected-committee votes) are implemented and were
checked against the reference's refusals on the devnet (roadmap B5); what remains open is listed there
and in the compatibility matrix. Block execution-unit
limits are validated on received blocks (`LedgerRules.scala`, protocol 5 and later) and enforced by the
forger.

## Plutus evaluation

`xyz.kd5ujc.scarab.plutus` holds the evaluator algebra and `xyz.kd5ujc.scarab.ledger` the ledger side.

- **Algebra** (`plutus/ScriptEvaluator.scala`): `ScriptEvaluator[F[_]]` has one method,
  `evaluate(EvalRequest): F[EvalResult]`. A request is the language (`PlutusV1`, `V2`, `V3`), the
  protocol major version (it selects the builtin semantics), the script bytes, the data arguments,
  the language's cost model and the declared budget. The result is `Success(spent, logs)` or
  `Failure(reason, spent, logs)` with a closed `EvalFailure` (`Decode`, `Unsupported`,
  `OutOfBudget`, `Script`).
- **Interpreter** (`plutus/ScalusEvaluator.scala`): the UPLC machine and builtins of Scalus (Apache-2.0,
  `org.scalus:scalus_3:1.3.0`); nothing else of Scalus is used by the node. BLS12-381 runs on blst
  (`foundation.icon:blst-java`, with the portable `libblst` of `blst-native` loaded in place of the one
  in its jar, so that no CPU needs ADX/BMI2: ADR 0012) and secp256k1 on `scalus-secp256k1-jni`; both bundle linux x86_64
  libraries that the JVM extracts to its temporary directory, and `ScalusEvaluatorSuite` evaluates a
  BLS pairing and an ECDSA verification (also run inside the Temurin 21 Docker image).
- **Pure or effectful**: evaluation is a deterministic function of the request, so the ledger rules,
  which are pure functions, take a `ScriptEvaluator[Id]` (`TxEnv.evaluator`, default
  `ScalusEvaluator.default`). The interpreter only memoises decoded scripts and machines per cost
  model. `ScalusEvaluator.suspended[F]` is the same interpreter in `F` (`Sync.interruptible`) for a
  node that wants evaluation off the main thread. The machine runs on a thread with a large stack.
- **Block scheduling** (`ledger/rules/EraBlock.scala`, `node/ParallelPlutus.scala`): ordered
  preparation freezes each transaction's requests after the existing ordinary checks. A private plan
  retains tentative state while dependency batches run under one shared block-validation worker
  bound; verdicts and observers land in transaction order before any state is returned. Serial fast
  paths also acquire admission when they can run a machine. `--plutus-workers 1` selects the serial
  rules; the default is at most four workers. Mempool single-transaction validation retains its
  serial interpreter. See [the design](design/parallel-plutus.md) and ADR 0018.
- **Script context** (`ledger/ScriptContexts.scala`, `ledger/Purpose.scala`): ours, written against
  the ledger's `Plutus/TxInfo.hs` modules. V1 and V2 `TxInfo` (V1 rejects inline datums, reference
  scripts and reference inputs; V2 adds reference inputs, redeemers and a datum map; withdrawals in
  Plutus credential order; Byron outputs are dropped before Babbage and an error from it), V3 `TxInfo`
  and `ScriptContext` (plain transaction ids, ledger-order withdrawals, redeemer and `ScriptInfo`,
  votes, proposals, donation and treasury). Validity intervals are converted to POSIX milliseconds with
  the era history and the system start (`EpochGeometry.slotStart`); the upper bound of an interval
  without a lower bound is inclusive before Conway and exclusive from it. Purposes are spending,
  minting, certifying, rewarding, voting and proposing, in the ledger's orders.
- **Availability** (`plutus/PlutusAvailability.scala`): the builtins and UPLC program versions a script
  may use in each (language, protocol major), transcribed from plutus-ledger-api
  (`Common/Versions.hs`). `ScalusEvaluator` rejects a script that uses another builtin as a decode
  failure; `ScriptDecoder[F]` (same interpreter, shared cache) is the pure check the phase-1 rule
  `TxChecks.scriptsWellFormed` uses from Babbage on for witness-set and output scripts.
- **Cost models**: per language from the protocol parameters in force. The genesis cost model is a
  map of named integers whose canonical order is the sorted name order, not the file order; the Conway
  genesis adds PlutusV3. A redeemer's declared units are its budget, the transaction total is bounded by
  `maxTxExUnits`.
- **Script data hash** (`ledger/ScriptIntegrity.scala`): `blake2b-256(redeemers ‖ datums ‖ language
  views)` over the witness set's own bytes; datums are omitted when empty; PlutusV1's cost model is a
  byte string wrapping an indefinite list under the byte-string key `0x4100`, V2 and V3 are plain, keys
  in canonical order (V2, V3, then V1); no languages is `0xa0`. Without redeemers the redeemer part is the
  encoding of an empty collection: `0x80` before Conway, `0xa0` from Conway (confirmed on Preview).
- **Counters**: `ledgerEpoch` log lines carry `phase2.evaluatedSuccess`, `phase2.evaluatedFailure`,
  `phase2.scripts` and `scriptIntegrityHashChecked`.
- **Recording cases**: `follow --plutus-cases <dir>` writes self-contained JSON cases (transaction,
  spent and referenced outputs, cost models, clock samples) for a few transactions of each kind;
  `fixtures/preview/plutus` holds a selection that the tests replay.

## Persistence (`follow --db <dir>`)

Modelled on the ouroboros-consensus ChainDB, as tagless-final algebras in the `storage` module (`ChainStorage` is the
composition and `Follower` the consumer, both in `node`):

| Part | Where | Role |
|---|---|---|
| `ImmutableStore[F]` | `ImmutableStore.scala` (`FileImmutableStore`) | Append-only raw blocks older than `k`: `append`, `sync`, `tip`, `get(point)`, `byBlockNo`, `stream(after)` |
| `LedgerSnapshots[F, S]` | `LedgerSnapshots.scala` (`FileLedgerSnapshots`) | Atomic, checksummed snapshots of the full `MirrorState` at an immutable point; newest valid wins. The file's content is a `SnapshotFormat[F, S]` (`SnapshotV5`: Haskell ledger state and a `SnapshotSidecar`); a write refusal is a value (`SnapshotRefusal`), while retired/unknown reads refuse restoration |
| `SnapshotMigration[F]` | `SnapshotMigration.scala` | Registered migration structure and read-only version admission; historical V1–V4 payloads are retired, V5 is current |
| `VolatileStore[F]` | `VolatileStore.scala` | Append-only log of the volatile chain's updates (blocks, rollback markers); lets a restart continue at the previous tip |
| `NetworkIdentityFile` | `NetworkIdentityFile.scala`, `NetworkIdentity.scala` | The network the database was created for (`<db>/network-identity`), checked on every open |
| `SnapshotPolicy` | `SnapshotPolicy.scala` | When the immutable tip is snapshotted: `Aligned` (the Haskell node's default, a multiple of 40 k slots and at most one per 10 minutes) or `EverySlots` (`--snapshot-every`); the pure `due` |
| `ChainStorage` | `ChainStorage.scala` | Checks the identity, opens the stores, restores the newest usable snapshot, replays the stored blocks after it, rebuilds the volatile chain (`resume`), hands the follower a `ChainPersistence` hook, and snapshots the immutable tip when it is released |
| `Codec[A]` | `Codec.scala`, `NodeCodecs.scala` | Binary codecs, one per type, composed. Case classes and enums are derived from their `Mirror`, so a field added to a persisted type is persisted with no codec change |
| `StorageError` | `NodeError.scala` | `Corrupt`, `Io`, `VersionMismatch`, `Missing`, `OutOfOrder`, `NetworkMismatch`, `ForeignChain`; raised through `Raise[F, StorageError]`, IO exceptions are mapped at the file boundary only |
| `HaskellImmutableReader[F]` | `storage/haskell/` | Read-only view of a Haskell node's ImmutableDB (see below); `HaskellImport` appends its blocks to our `ImmutableStore` |

```sh
follow --genesis-dir <dir> --peer <host>:<port> --db <dir> [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N]
```

Without `--db` nothing changes: the node is in-memory and replays from genesis. With `--db` the
JSON log gains `immutableOpened`, `snapshotRestored`, `snapshotSkipped`, `snapshotWritten`,
`snapshotFailed`, `replayStarted`, `replayProgress`, `replayFinished`, `networkRecorded`,
`volatileOpened`, `volatileResumed`, `shutdownSnapshot`, `snapshotRefused` (`file`, `anchor`, `reason`: the
format refused the state, nothing was written) and, the first time an older database is opened,
`formatMigrated` (`kind`, `from`, `to`, `count`; see "Format versions and migration").

**Immutable store format** (`<db>/immutable/`). `format` names the version and chunk size
(`chunkSlots`, 21600); a different chunk size is a `VersionMismatch`. Blocks of slots
`[c * chunkSlots, (c + 1) * chunkSlots)` go to `<c>.chunk` (concatenated raw blocks) and
`<c>.index`: the magic `SCRBIDX2` (`CSLIDX01` in format 1) then 68-byte big-endian entries
`offset u64 | length u32 | crc32 u32 | slot u64 | blockNo u64 | hash[32] | flags u8 | zero[3]`
(`flags` bit 0 marks a Byron epoch-boundary block). Appends write data then index without syncing;
`sync` (before every snapshot, at chunk roll-over and on close) forces both and the directory.
On open the newest chunk is validated entry by entry (contiguous offsets, data in bounds, CRC) and
both files are truncated to the last good entry; a chunk left with no good entry is removed, and
data files without an index are deleted. Older chunks were synced at roll-over and are trusted.

**Haskell ImmutableDB reader** (`storage/.../haskell/`, reader only; our store keeps its own
format, and writing the Haskell layout is not implemented: it belongs to the Haskell-format adoption of M11). `HaskellImmutableDb.scala` holds the pure parts: the opaque `ChunkNo`,
`ChunkSlots` (10 x k of the first era: 4,320 on Preview, 21,600 on mainnet and preprod) and
`ByteOffset`; `PrimaryIndex` (version byte 1, big-endian `u32` offsets, one per relative slot plus
one, a step of 56 for a filled slot; relative slot 0 is the EBB slot) and `SecondaryIndex` (56-byte
entries: block offset u64, header offset u16, header size u16, CRC32 u32, header hash, slot or, for an
EBB, epoch u64); `ChunkValidator` (offsets contiguous, block sizes from the next offset or, for the
last block, from the CBOR item length, CRC32 of the block bytes, the Blake2b-256 of the header slice
equal to the indexed hash for Shelley-based blocks, the slot equal to the one the primary index
position implies); and the closed `ChunkFault` and `HaskellImmutableError` ADTs.
`HaskellImmutableReader[F]` lists the chunks (they must be contiguous from 0), and `contents()` /
`blocks()` stream `HaskellBlock(chunk, position, slot, hash, bytes)` in chain order. Every chunk but
the newest must validate completely; the newest is cut back to its valid prefix, as the Haskell node
does with `ValidateMostRecentChunk`, and what was ignored is reported (`ChunkContents.dropped`)
because the node writes the chunk file before its two indexes. The reader never writes. Our chunk `c`
(21,600 slots) holds the blocks of Haskell chunks `5c` to `5c + 4` byte for byte on Preview: the block
bytes are the same `[era tag, block]` items. `import-haskell-db` (`HaskellImport`) additionally
decodes every block, checks its header hash and slot against the index, the predecessor hash and the
block numbering, the protocol magic (`protocolMagicId`) against the configured network and the first
block's predecessor against the configured genesis, then appends to the `ImmutableStore`; it
continues after the tip of a non-empty target. Epoch-boundary blocks (Byron) are covered by tests
built from the upstream encoding rules (`fixtures/eras`); no Byron Haskell database was available.

**Snapshot format** (`<db>/snapshots/snapshot-<slot>-<blockNo>.bin`, the newest `--keep-snapshots`,
default 2, are kept). A file is the content of a `SnapshotFormat` and the SHA-256 of that content;
the store (`FileLedgerSnapshots`) does the framing, the atomic write and the pruning, and the format
decides what the content is. Format version 5 (`SnapshotV5`, J2.9; versions 3 and 4, `SCRBSNP3` and `SCRBSNP4`, had the same layout and sidecars that held the node's chain-dependent state in its former shape and, in version 4, the old rules' flat state):

```
"SCRBSNP5" | sidecar length u32 | sidecar | Haskell state bytes | SHA-256 of all before
sidecar = schema fingerprint[8] | Codec of SnapshotSidecar
```

The Haskell state bytes are a `state` file of a Haskell node (`[1, [ledger state telescope, header
state]]`, `HaskellLedgerFile`) of the chain's state `MirrorState.ext`, byte-proven against real files;
the UTxO in it is `map(0)`, as in the reference's `state` file, and its entries stay in the RocksDB
store under the checkpoint of the snapshot. The sidecar (`SnapshotSidecar`, derived `Codec`s, a
fingerprint of its own) holds what the Haskell encoding has no place for: the anchor, the eras the node
saw begin where the configuration does not say, the gaps of the mirror, the node's `Utxo` bookkeeping
(stake by credential and pointer, count, total, stamps, diff), the node's full protocol parameters (they
have every field in every era) and the extras of the mirror (the bootstrap and Byron chain-follow state,
the epoch of the last tick, the TPraos previous epoch nonce, the Plutus statistics). A chain still in
Byron has no Haskell section (the mirror does not model the Byron ledger state): the sidecar holds the
chain-dependent state Shelley starts from and the consensus bookkeeping, the Byron ledger among it. The
chain's state is the mirror itself (`MirrorState`), so the read is `HaskellLedgerFile.decode`, the UTxO
put back and the whole parameter sets put back (the future ones laid over with what the update changed,
`ProtocolParams.overlay`) and nothing converted: the telescopes, the tip and the new-epoch state are
those of the file. A write reads its own content back and refuses (`snapshotRefused`, the snapshot is
skipped, nothing is pruned) what would not restore to that state, and the full check
(`--snapshot-check full`) writes what it read again and compares the bytes: a snapshot is an
optimisation, so a refusal costs a replay at worst. An empty relay list of a pool is `Bytes.empty` after
a read (the Haskell encoding writes both the item as given and none as the empty array).

Versions 1–4 are known retired formats. Their payload readers were used by the historical migration
build at `648aed117f826d06117f616b5c0a3716a50485a7`; verified current V5 replacements and conversion
provenance are the maintained inputs. Historical source remains recoverable for offline conversion.
The runtime retains their identifiers to raise `RetiredVersion` rather than treating them as corrupt
snapshots and silently replaying. [Storage migrations](storage-migrations.md) describes the conversion
and retirement conditions, and how to add a future migration without inventing state.

Historically, format version 2 (`legacy.SnapshotV2`, magic `CSLSNAP1`) was
`"CSLSNAP1" | format number u32 | schema fingerprint[8] | payload | SHA-256`, the payload the codec of
`StateSnapshot(anchor, NodeState)`; integers are LEB128 varints, `BigInt` is length-prefixed two's
complement, containers are a count then the elements, sums a variant index then the variant. The
fingerprint is the SHA-256 prefix of the codec's `shape` (the names and types of every persisted
field), so changing any state type makes older snapshots fail with `VersionMismatch` rather than
decode wrongly. Version 1 differed in the number and the fingerprint only (written before the
packages were renamed). A write goes to `*.tmp`, is fsynced, renamed (atomic) and the directory is
fsynced; leftover `*.tmp` files are removed on open. Snapshots are written by a background fiber
from the immutable (structurally shared) anchor state, so following does not pause.

**Recovery.** Snapshots are tried newest first. One that fails its checksum, decodes badly, has
another schema or names an anchor missing from the immutable store is reported (`snapshotSkipped`)
and the next older one is tried; with none usable the stored blocks are replayed from genesis. The
stored blocks after the chosen snapshot are re-applied through the same `MirrorRules` with no
network, then ChainSync resumes with the replayed anchor as the intersection candidate.

**Volatile blocks** (`<db>/volatile/<seq>.log`). The last `k` blocks are kept in memory with their
states, and every update of that chain is also appended to a log, the ouroboros-consensus VolatileDB
as an append-only file instead of a block store with a successor map: a record is
`crc u32 | kind u8 | flags u8 | length u32 | slot u64 | blockNo u64 | hash[32] | payload`, the CRC-32
covering everything after it. `kind` is a block (payload: the raw block) or a rollback to a point
(Origin or a block; no payload), so the chain a log describes is a fold over its records
(`VolatileStore.chainOf`) and forks need no extra structure. A file rolls over after 512 blocks.
The store is a hint, not a source of truth, and is built so that it can only be wrong by missing
blocks:

- Opening validates the files in order and cuts the first damaged one at its last good record
  (`volatileOpened` reports the bytes and the files removed); a record is never returned unless its
  checksum matches. Appends are not forced one by one: a crash loses at most the unforced tail, and
  those blocks are fetched again.
- Collection never loses a block the immutable store lacks. Blocks reach the immutable store when
  they age past `k`; every 512 of those the immutable store is forced (`sync`) and the log files
  whose blocks are all at or below the new immutable tip are deleted (oldest first, the newest file
  stays). A rollback marker whose target was collected leaves an empty chain, which is where the
  volatile chain starts after the immutable tip anyway.
- On start, after the snapshot is restored and the immutable blocks replayed, `ChainStorage.resume`
  takes the logged chain after the immutable tip (at most `k` blocks) and applies each block through
  the node's rules on top of the recovered state (`Verification.warm` first), rebuilding the exact
  `VolatileChain`. A block that does not connect or that the rules refuse ends the chain there; the
  log is cut back with a rollback marker and the rest is fetched from the peer (`volatileResumed`
  reports `resumed` and `dropped`). The relay's served chain and the mempool start from that tip, so
  the first ChainSync intersection offered to the peer is the previous tip, not the immutable
  boundary. The states are not stored (they are a pure function of the blocks), only re-derived.

**Shutdown.** Releasing the storage resource (SIGTERM or SIGINT through the IOApp's cancellation,
`--seconds`, or a fatal error) first waits for a snapshot in flight and then, unless the newest
snapshot already is at the immutable tip, forces the immutable store and writes a snapshot with its
UTxO checkpoint at the tip (`shutdownSnapshot`). The stores are released after that. Persisting one
block (log, pending list, immutable append, backing store, tip bookkeeping) is uncancelable, so
cooperative cancellation waits for that sequence. This is not a transaction across the stores;
storage failure and abrupt process termination still require recovery. After a successful shutdown
snapshot, the next start restores it, replays no immutable block
(`replayFinished` with `replayed` 0) and resumes the volatile chain from the log. After `kill -9`
nothing of this runs: the start restores the newest periodic snapshot, replays the stored blocks after
it, and resumes the volatile chain from whatever the log holds.

**Format versions and migration.** The files whose first bytes name their format are the network
identity, the immutable store's `format` file, every chunk `.index` and every ledger snapshot. Their
identifiers, per version (`FileKind.identifier`, `ledger/.../PersistedFormat.scala`); a kind has a
new identifier only at a version at which its layout changed, and `FileKind.current` is the newest:

| file | format 1 (until the rename) | format 2 | format 3 | format 4 | format 5 | format 6 |
|---|---|---|---|---|---|---|
| `network-identity`, first line | `cardano-scala-lab network identity v1` | `scarab network identity v2` | (as 2) | (as 2) | (as 2) | `scarab network identity v6` |
| `immutable/format`, before ` chunkSlots=<n>` | `cardano-scala-lab immutable store v1` | `scarab immutable store v2` | (as 2) | (as 2) | (as 2) | (as 2) |
| `immutable/<c>.index`, 8-byte magic | `CSLIDX01` | `SCRBIDX2` | (as 2) | (as 2) | (as 2) | (as 2) |
| `snapshots/snapshot-*.bin`, 8-byte magic | `CSLSNAP1`, number 1 | `CSLSNAP1`, number 2 | `SCRBSNP3` | `SCRBSNP4` | `SCRBSNP5` | (as 5) |

The immutable format and index layouts are unchanged after the V2 rename. V6 extends the identity
record with Alonzo/Conway bindings and adoption provenance. Formats 3 to 5 are the snapshot as the
Haskell ledger state plus a sidecar (see
"Snapshot format"; 4 and 5 changed the sidecar). The versions are a closed `enum FormatVersion` (a version this build does not
know cannot be represented) and each kind of file is a `FileKind` with its history of identifiers.
On open, `Migrate` (storage) runs a `Migration[F]` over the files of a kind: it reads the version of
every file first (so an unknown version raises `StorageError.UnknownVersion` and nothing has been
rewritten), then steps each stale file to the next version until it is at the current version of its
kind (or directly to the current version when a registered converter supports that transition).
Snapshot V1–V4 conversion is historical tooling recovered from pinned source, not a migration
offered by the current runtime. A kind unchanged at global V6 is not rewritten, so a V6 identity update
does not touch the 29,031 chunk indexes of a Preview database. A step is `AtomicFile.replace`: a
temporary file in the same directory, fsync, rename
over the original, fsync of the directory; so a crash leaves every file whole at one version, and the
next open finishes. The chunk indexes are migrated first and the `format` file last. Each kind
migrated is reported once (`formatMigrated`); the second open reports nothing. Reading accepts each
kind's supported versions (`readableFrom` through `current`); old identity/index spellings remain
supported, while snapshot V1–V4 identifiers are recognized as retired. The per-entry index read path
never looks at the magic. The migration is one way: an older build does not
know the newer formats (keep a copy of a database to run an older build on it).

`SnapshotMigration` retains the generic `Migration` / `Migrate` registration for future versioned
conversions. Currently only V5 snapshot payloads are supported. A read-only identity verification
precedes snapshot version preflight; `Main` holds database ownership and performs this before opening
RocksDB, and `ChainStorage.open` repeats it before identity migration or immutable/volatile recovery.
A mismatch of network identity takes precedence. Retired and unknown formats refuse admission without
rewriting another file or resetting backing state. Direct migration entry points verify support too;
`step` probes the real file before accepting a caller's claimed current version. Corrupt current files
retain their existing recovery policy. Generic `FileLedgerSnapshots.open` tidies temporary files, so
callers needing the full no-mutation admission guarantee first use `ChainStorage.preflight`; direct
restore propagates typed incompatible-version errors rather than skipping them. The RocksDB
checkpoint-key conversion remains independent. `scripts/oracle/export.py` still reads both index
magics.

**Snapshot reader retirement (D10).** Current data uses V5; historical V1–V4 identifiers stay in
`FileKind` so startup can distinguish a known retired format from an unknown one. Retirement requires
verified current-format replacements for every active local and repository input and transition of
local old writers. Originals and pinned historical conversion evidence remain recoverable outside
the runtime reader path. A separately pinned older deployment keeps its compatible binary until an
explicit upgrade converts and verifies its final database copy; source landing does not perform that
operational transition (#37/#115 for preprod). The procedure and required checks are in
[Storage migrations](storage-migrations.md); genesis conversion (#134) and RocksDB checkpoint-key
migration have separate dependencies.

**Network identity** (`<db>/network-identity`). A plain-text file, written atomically when the
database is created. V6 is complete; V1 and V2 are retained as explicitly incomplete legacy
records:

```
scarab network identity v6
magic 2
shelley-genesis <blake2b-256 of the Shelley genesis file as read>
byron-genesis <hash, or none for a chain that starts in Shelley>
alonzo-genesis file:<blake2b-256 of the exact loaded file bytes>
conway-genesis file:<blake2b-256 of the exact loaded file bytes>
provenance fresh
```

`ChainStorage.open` compares the complete record with the identity built from the files node setup
loaded; a mismatch raises `StorageError.NetworkMismatch` (use the matching `--genesis-dir` or
another `--db`). V1/V2 records do not contain Alonzo or Conway bindings and are never upgraded
automatically. A populated legacy identity or an identity-less database requires the explicit
`--adopt-legacy-network-identity` option. Before it writes a V6 identity, the node checks every
known legacy field, checks the first immutable block's predecessor when available, and preflights
snapshot and immutable-format headers read-only. The original legacy identity bytes are retained
beside the new file; V6 records operator confirmation separately from identity equality and does
not claim that adoption proves the database's historical genesis. A Shelley-start chain's first
block has no Byron predecessor, so that check cannot distinguish historical Shelley-start networks.
Fresh databases receive V6 immediately. Global V6 changes no snapshot or immutable layout, so
unchanged file kinds are not rewritten.

Tests (`storage/src/test/.../storage`, and `ChainStorageSuite` in `node/src/test`): round-trip properties for every persisted type from generators
derived by the same `Mirror`s (so new fields are covered automatically), prefix/trailing/random-byte
rejection, golden encodings and a golden digest and schema fingerprint, the store's crash cases
(torn data, torn index entry, garbage, flipped bytes, lost chunk, every cut point of the newest
chunk), snapshot corruption at many points and fallback, and restart equivalence over the Byron to
Conway capture (`fixtures/eras`) through the real follower. `VolatileStoreSuite` checks the log against
its pure model over random appends, rollbacks, collections and reopens, every crash cut point of the
newest file, and damage in an older file. `ChainStorageSuite` adds restart equivalence with and
without the volatile log, a graceful stop followed by a start that replays 0 blocks, copies of the
database taken at 12 points of a run (standing in for a crash there) that all restart to the same
final state, the volatile log torn at every record boundary, a logged block that does not apply, and
the identity cases (created, reopened, four kinds of mismatch, adoption, refusal).

## Relay (`follow --listen`, `--local-socket`)

With `--listen <host>:<port>` the node accepts node-to-node connections (versions 14 and 15) and
serves its own chain; with `--local-socket <path>` local clients can submit transactions over a Unix
socket. Everything it serves comes from its own validated chain and mempool, never from what a peer
announced. The pieces, bottom up:

**Multiplexer and duplex** (`network/Mux.scala`). Every channel has a side per
mini-protocol, `Initiator` (client) or `Responder` (server), and an SDU carries the mode bit of its
sender. `Mux.Config(duplex = true)` registers both sides of every protocol on one bearer, so a
client and a server of the same protocol number never see each other's traffic;
`mux.channel(protocol, role)` picks the side. Without `duplex` only the side opposite to the peer's
is registered and the other mode bit closes the connection. `Mux.flush` waits until everything
queued has been written, used so that a handshake refusal is not lost when the connection closes.

The multiplexer's fibers and its channels follow [ADR 0017](adr/0017-fibers-end-with-values.md). A channel
holds no error capability: `send`, `recv` and the clients over it (`ChainSyncClient`, `BlockFetchClient`,
`KeepAliveClient`) raise through the `Raise` of the fiber that calls them. The reader routes each SDU into the
ingress queue of its mini-protocol and side, counted in bytes with the limit of the reference
(`IngressLimits`: ChainSync 462,000, BlockFetch 23,068,694, TxSubmission2 721,424, KeepAlive 1,408, from
`Cardano.Network.NodeToNode` of ouroboros-network 1.2.0.0); it never waits for a slow protocol, and a peer past
the limit ends the connection with `NetworkError.IngressLimit`, the reference's `IngressQueueOverRun`. The
writer's queue is bounded in messages and `send` waits for room. `Mux.closed` is the typed reason the
connection ended with (a closed bearer, a framing error, an overrun, a limit of the protocol), and every
pending operation raises it. Each fiber the connection starts (the KeepAlive pinger, the TxSubmission2 client,
the four server fibers) runs in an error scope of its own (`Fibers.scoped`) and, when it fails, aborts the
multiplexer with the error as a value.

**Handshake** (`HandshakeServer`). Reads the proposal, answers through the pure
`Handshake.responder` (highest common version of 14 and 15 here, 16 to 23 for node-to-client) and
reports the agreed data. `initiatorOnly` in the agreed data is false only if both sides allow a
duplex connection. `NodeToNode.over(..., serve = Some(handlers))` offers InitiatorAndResponder and
runs our servers next to our clients when the peer agrees; `NodeToNodeServer.accept` is the inbound
side: the node's inbound connections are served, and on a duplex one the inbound governor also
runs our clients (see "Connections" below).

**Servers.** `ChainSyncServer.run` answers FindIntersect and RequestNext in order (so pipelining
clients work), sends AwaitReply at the tip and then waits for the chain to change. It reads through
a `Source` that holds one client's cursor. `BlockFetchServer` streams an inclusive range from a
`Blocks` source and answers NoBlocks for anything that is not a contiguous stretch of our chain (or
longer than `maxBlocks`). `KeepAliveServer` echoes cookies. `TxSubmissionClient` and
`TxSubmissionServer` are the two roles of TxSubmission2: the client offers a `Source` of
transactions (each once per connection, by ticket number) and hands out bodies on request; the
server asks for ids with a blocking request, fetches the bodies it does not know (verifying the id
against the body hash) and passes them to a `Sink`; one batch is fully processed and acknowledged
before the next request. `LocalTxSubmissionServer` is the node-to-client submission protocol,
`LocalStateQueryServer` the query protocol, `LocalTxMonitorServer` the mempool monitor and the node-to-client
ChainSync the `ChainSyncServer` with whole blocks (see "Node-to-client" below); `NodeToClientServer.accept`
runs the handshake (versions 16 to 23) and then those on one connection.

**Served chain** (`relay/ServedChain.scala`, `relay/ChainServer.scala`). The ouroboros-consensus
`ChainProducerState`: the volatile chain (last `k` blocks with their headers and raw bytes) plus
the read pointer of every connected client. A rollback rewrites exactly the pointers of the clients
that had read past the new tip (they get a RollBackward to it and then follow the new branch). A
client behind the volatile chain is served from the `ImmutableStore`. `ChainServer` is a
`ChainPersistence`, composed after the storage persistence so a block is stored before it leaves the
volatile chain. Without `--db` only the volatile chain can be served.

**Mempool** (`mempool/`). `MempoolState` is pure: the transactions are kept in arrival order, and
the invariant is that they apply in order to the ledger state at the tip through the real
the ledger's `Ledger` rule (`ledger/rules/Ledger.scala`: phase 1, phase 2 and the Conway rules). `add` validates against the state after all held
transactions, so a transaction may spend the output of one before it. Rejections are typed
(`Malformed`, `AlreadyHeld`, `Ledger(failure)`, `Full`) and returned as values. `add` takes the
`Source` of the transaction, as the reference's `WhetherToIntervene`: a transaction from a local client
(`Local`) is validated as received, one from a peer (`Remote`, TxSubmission2) is validated with
`is_valid` forced to true and, when only that flag is wrong because a script fails, held with
`is_valid` false (the submitter loses the collateral); its relayed bytes are then the received ones with
the flag byte changed ([IN-80](implementer-notes.md)). `sync(ledger, slot)`
runs on every new tip, rollback or fork switch, keeps what still applies and reports the rest;
transactions that a block contains leave as adopted. Before the transaction rules, `add` applies the
ledger's MEMPOOL rule: a transaction none of whose inputs is unspent is refused with that alone
(`ConwayMempoolFailure`, "All inputs are spent..."), as is a vote of an unelected committee member
before protocol version 11. Before either, `add` measures the transaction as `Mempool/Update.hs` does
(`Capacity`): its size against `maxTxSize` and its execution units against `maxTxExUnits` (phase 1, both failures
reported when both are exceeded, `Rejection.Limits`), then its reference-script bytes in the state after the held
transactions against 200 KiB (phase 2, `TxRefScriptsSizeTooBig`); a refusal there is the only failure returned and
the ledger does not run. The capacity is a vector of bytes (size plus 4), execution-unit memory, steps and
reference-script bytes, `computeMempoolCapacity` of the parameters of the ticked base: two blocks without
`--mempool-bytes`, the override rounded up to whole blocks with it, recomputed at every `sync` and never trimming a
pool that a parameter change left over it. A transaction that would exceed any component finds no room; the
check runs after phase 1 (without the reference-script component) and again after phase 2, as `doAddTx` and
`pureTryAddTx` do ([IN-81](implementer-notes.md)). `Mempool.add` then waits, as the reference's `implAddTx` does
([IN-82](implementer-notes.md)): it releases the state lock and retries from the first measure whenever the pool
changes (`version`: an add, a `sync`, a removal), until it is held or refused for another reason, and only a
transaction larger than the whole capacity is refused with `Rejection.Full`. Waiters are served through two FIFO
queues, a peer's transaction taking the remote queue and then the common one and a local client's only the common
one, so a local client passes the peers in line and only the head of the line waits for room. The wait is
cancelable (a closed client or a lost peer leaves the line) and the `mempoolWaiting` event marks it. A peer's
wait stalls that peer's TxSubmission2 replies, as in the reference, outside every protocol deadline. A transaction is validated in the slot after the tip within the tip's epoch (it is not ticked over an epoch
boundary). Transactions of rolled-back blocks are not put back. `Mempool[F]` serialises the
state changes (one state lock, held for one validation or one revalidation, never while an add waits), offers `source`/`sink` for TxSubmission2 and remembers recently refused ids so peers
that offer them again are not asked for the body.

**Chain selection** (`consensus/ChainSelection.scala`, `Follower.scala`). A peer session keeps a
candidate (the headers it announced after the point where it joined our chain). The Praos rule: the
longer chain wins; at equal length, tips issued by the same pool compare the operational-certificate
issue number (higher wins) when their slots are also equal; otherwise the lower VRF output of the
tips wins (the VRF certificate of the header, TPraos: the leader one). From Conway the VRF tie-breaker only applies to tips at most 5
slots apart. A candidate that is not strictly preferred changes nothing (so a peer's rollback or
equal fork leaves our chain alone). A switch rolls back at most `k` blocks (the volatile chain is
the limit; a candidate that no longer joins it is dropped as a protocol error of that peer, not of
the node). The blocks of a preferred candidate are fetched from that peer, validated on a copy of
the chain and committed together, so a rule violation leaves the chain at the last valid block
(still fatal, as before). The headers of a candidate have already passed the header gate (see
"Header-first validation (G6)" below), so a bad header never reaches the fetch.
Several sessions can run at once; adoption is serialised. The sessions of all peers share one `BlockPool`
(`BlockPool.scala`): a session claims the blocks of a range when its connection is free to send the request, so a
block is fetched from one peer only (ouroboros-network `Ouroboros.Network.BlockFetch.Decision`: in bulk sync
`filterNotAlreadyInFlightWithOtherPeers` removes blocks in flight with another peer), the ranges of a batch go to
whichever connection is idle, and the session that applies a block takes it from the pool whichever peer delivered
it (waiting if the claim is still in flight, fetching it itself if the claim was released). The reference limits the
peers fetching at once with `maxConcurrencyBulkSync`; ours is limited by the number of peers given.

**Wiring** (`Main`). `--peer` may be given several times (with more than one peer a peer that
keeps failing is retried forever, with one it is fatal after 30 attempts), `--listen` starts the
inbound server, `--local-socket` the local one, `--mempool-bytes` overrides the capacity. The followed
peers get our mempool through TxSubmission2. The JSON log gains `listening`, `inbound`,
`mempoolAdded`, `mempoolRejected`, `mempoolWaiting`, `mempoolAdopted`, `mempoolRemoved`, `mempoolDropped` and `mempoolSynced`.

## Connections: the manager and the inbound governor (G2)

A peer that connects to us is also a source of blocks. Before this, an inbound duplex connection
(InitiatorAndResponder) was accepted and served, but only the connections the node opened ran our
clients. Now:

**`ConnectionState`** (`network`, pure). The life of one connection: `Reserved` (an outbound slot
is taken), `Unnegotiated(provenance)`, `Established(provenance, flow)` with the flow the handshake
agreed (`Unidirectional` or `Duplex`), `Terminating`, `Terminated`; `transition(state, event)`
allows `Connected`, `Negotiated(flow)`, `Close` (from any live state, idempotent) and `Released`
and returns a typed `Illegal` for anything else. `canRunClients` is the rule for who may run our
clients: every established outbound connection and an inbound one that agreed duplex. `Counters.of`
gives inbound, outbound, duplex and established counts from a table. `ConnectionStateSuite`
checks the transitions, the idempotent close, that nothing but `Close`/`Released` is allowed once
terminating, and the counters.

**`ConnectionManager`** (`network`). A `Ref` table from the peer's socket address to its
connection (the key is the address and the direction). `inbound(remote)` and `outbound(remote)` are
`Resource`s: admission is atomic with the checks (one live connection per address and direction, and
none in the other direction while our clients use it: an outbound connection waits for an inbound
duplex one that runs our clients, and with `--inbound-sync off` the two coexist; an inbound
connection is never refused because we have an outbound one, see below; `maxInbound` and
`maxOutbound`), the
refusals are typed (`ConnectionError.InboundLimit`, `OutboundLimit`, `AlreadyConnected`) and raised
through `Raise`, and releasing the resource, however it ends (normally, by failure or by
cancellation), always runs `Close` and `Released` and removes the entry, so the accounting cannot
leak. Every change of state is reported (`ConnectionEvent`, logged as `connection` lines);
`lookup` lets a caller wait for a connection to end; `snapshot` is the table for `GET /connections`.
A peer that dials from its own listening port (the reference node does) is thereby the same
address we would dial; one that dials from an ephemeral port is not recognised as the peer we also
dial (the limits still apply).

**`InboundGovernor`** (`node`). `serve(bearer, remote)` is the inbound handler of `--listen`: it
admits the connection to the manager, runs `NodeToNodeServer.accept` (handshake, our servers) and,
when the handshake agreed duplex and `--inbound-sync` is on, starts in the `negotiated` hook the
same pieces that run on a connection we open: `NodeToNode.clients` (ChainSync, BlockFetch,
KeepAlive and the TxSubmission2 client on the side of the mini-protocols that is ours), then
`Peer.over` (the pipelined producer, header-first validation, ranges of blocks) and the follower
session of `Follower`, so the peer's chain competes in chain selection like any other candidate.
The session is a background fiber of the connection: when the multiplexer closes the clients, the
producer and every supervised fiber are released with it. A session that ends because the peer broke
the protocol, announced an invalid header or sent a block our rules refuse aborts the connection; one that ends
for any other reason of the peer (the peer does not serve ChainSync, say) leaves the serving side running. A
session ends with a `SessionEnd` (a value: `SessionEnd.scoped` runs it in an error scope of its own and catches
caller-scoped rule and store capabilities), and `FailurePolicy.disposition` says what is
done about it: forget the peer, try it again later, or stop the node (storage, an internal failure,
a defect or implementation unavailability; exit codes 5, 6, 7 and 8,
`running.md` "Failures that stop the node"). Each session counts the
headers and rollbacks it saw and the peer's tip (`GET /connections`, `inbound` log events
`accepted`, `duplex`, `following`, `sessionEnded`, `sessionDropped`, `closed`).

**Outbound.** `OutboundPeers` puts the connections we open through the same manager. If the
peer we are asked to dial already has a duplex connection to us on which our client runs, no second
connection is opened: the dialler waits until that connection ends (the `reused` log event) and then
dials; a manager refusal becomes a recoverable connection failure. Outbound peers configured with
`InitiatorAndResponder` receive the node's responder handlers and can read our chain on that same
bearer when duplex is negotiated. `InitiatorOnly` peers do not receive those handlers.
Admission distinguishes address and direction, permits an inbound connection alongside an outbound
one, and permits the reverse when inbound sync is disabled. When two sessions do follow one peer,
chain selection treats their candidates independently.

Tests: `ConnectionManagerSuite` (admission, one connection per address, limits per direction,
release after failure and cancellation, illegal events, waiting for a connection to end, and a
model-based property over random open/negotiate/release sequences that checks the counters against
the table and the limits after every step); `InboundGovernorSuite` (a duplex peer that only connects
to us over in-memory bearers is followed to its tip and its blocks applied; an initiator-only peer is
served and not followed; a second connection from one address is refused; the table is empty again
when the peer lets go). The devnet `relay` scenario asserts it against the reference node (see
[running.md](running.md)).

## Peer selection: the outbound peers (G1.1)

[ADR 0016](adr/0016-peer-states-and-the-governor.md), [design](design/peer-selection.md). The outbound peers are chosen by
a governor that mirrors `Ouroboros.Network.PeerSelection.Governor`.

**Pure part** (`network/.../selection`, no effects). `Status` (`Cold`, `Connecting`, `Warm`, `Activating`, `Hot`,
`Deactivating`, `Closing`, `Cooling`) is the state of one `PeerRecord` in `GovernorState`; the known, established and active
sets of the reference are views of the map (`view`), `invariant` states what must hold. `PeerSelection.decide(state, now)`
returns `Step.Act(decision)` (jobs and the state with them in progress) or `Step.Idle(state, wakeAt)`, in the reference's
order; `Events.apply` applies what happened (a job completed, a connection observed, new targets, roots, a judgement, a
wake-up) and is the only place that does failure accounting. `Rng` is a splittable SplitMix64, so a run is a function of its
seed. `Targets`, `LocalRoots` (`HotValency`, `WarmValency` opaque), `Sensitivity` (the bootstrap regime and
`OutboundConnectionsState`), `Regime`, `Churn` (the twelve steps, their targets, timeouts and the sleep), `PeerMetrics` (the two boards of the first arrival per slot, the hot-demotion scores) and `Gsm` (the Genesis State Machine and `FetchMode`) are pure too.

**Effects** (`node`). `PeerGovernor.run[F]` is the loop on `Stateful[F, GovernorState]`, `Ask` (clock, configuration), `Raise`
(`GovernorError`) and `Tell` (`Trace`); it waits on its event queue only, timers are fibers that offer `Event.Wake`.
`PeerStateActions` is its interface to the world; `OutboundPeers` interprets it over the `ConnectionManager`, with one fiber per
connection and one per hot session, whose `Resource` owns the claims on blocks in the `BlockPool`, so a hot session that ends
releases them. `NodeToNode.warm` opens the connection with KeepAlive and `activate` starts ChainSync, BlockFetch and
TxSubmission2 on it; `finish` ends them with `MsgDone` where the protocol allows it. `PeerNetwork.resource` wires the roots, the
governor, the churn loop (`ChurnLoop`, the one writer of the targets), the GSM runner (`GsmRunner`, the marker file) and the fetch mode, which `BlockPool` reads to decide whether a claimed block may be requested again; hot sessions report headers and blocks to the shared `PeerMetrics` (one update per batch), which the governor reads through `Ask` once per pass; `Main` runs it beside the node, and the governor
ends the node only by a fault.

**Tests.** `PeerSelectionSuite` (the decision by example), `PrimitivesSuite`, `GsmSuite`, `WarmHotSuite` (activate, finish and
reactivate on one connection), the properties of the reference on `TestControl` against a mock environment
(`GovernorPropertiesSuite`), 40 in-memory peers (`FortyBearersSuite`), and a 256 KiB thread (ADR 0011).

## Header-first validation (G6)

A header is checked before its block is wanted, as the ouroboros-consensus ChainSync client does
(`Ouroboros.Consensus.MiniProtocol.ChainSync.Client`). In the pipelined peer each batch of
ChainSync instructions passes the session's `HeaderGate` before any block of the batch is requested;
a header that breaks a rule raises `PeerError.InvalidHeader` (the point and a closed `HeaderError`:
`Envelope`, `Praos`, `TPraos`, `Structure`), which ends the session and disconnects the peer, and no
block of that batch is fetched. The candidate chain of a session is thereby a chain of validated
headers (`HeaderChain`: the join point, the header state there and the header state after every
header announced since, with rollbacks and pruning).

**What is checked** (`NodeHeaderRules`, pure; the same consensus functions used for the
block): Byron PBFT and its delegation forecast; first-Shelley handover; and the Shelley-based
envelope (era, protocol version, header and body limits), VRF proof and leader threshold,
operational certificate, KES signature, certificate counter and nonce evolution. TPraos also
checks its overlay schedule and both VRF proofs. `NodeHeaderState` distinguishes the Byron
state with its pending Shelley state from an active Shelley-based protocol state. Header
outcomes are compared with complete block-derived states in `HeaderRulesSuite` and the
unchanged native all-era captures.

**The ledger view is forecast** from the newest ledger state of ours on the peer's chain.
Shelley-based forecasts use the native exclusive horizon: the stability window from Origin,
otherwise the anchor slot plus one plus that window. Byron uses its native exclusive
`NOW + 1 + 2k` bound; first-Shelley translation follows the hard-fork combinator. In the
anchor's epoch the view uses its pool distribution, parameters and due genesis delegations.
The next-epoch view uses the newest stake snapshot and parameters the boundary would adopt,
including TPraos decentralisation and entropy. Envelope checks use those forecast parameters
at epoch boundaries as well.

Every production session with header-first validation enabled uses `HeaderCheck.forNodeChain`.
A missing forecast waits for ledger progress; it never accepts an unchecked header because
an earlier batch finished or a timer elapsed. A candidate that loses its ledger intersection
restarts, and one that is no longer preferred beyond the forecast horizon is refused as the
native ChainSync client does. Streaming publication lets already accepted headers' bodies
advance the ledger while a later header waits. The shared canonical path applies in Praos
and Genesis; GSM, GDD, LoE and ChainSync jumping remain Genesis policies. The explicit
`--header-first off` setting retains block-only validation in Praos mode; Genesis requires
the canonical header path.

**Cost.** The signatures are verified once. The gate's view of the `VerificationCache`
(`forHeaders`) computes a verdict if it is not there and keeps it; the block's own validation, later,
consumes the same verdict, and `warmBlock` no longer overwrites a verdict that is already there. The
signatures of a batch's headers are verified in parallel before the rules fold over them
(`VerificationCache.warmHeaders`). The measurement is in [performance.md](performance.md).

Tests: `PraosHeaderValidationSuite` (original headers from all seven eras and signature/envelope
refusals before any body request), `HeaderRulesSuite` (valid forged headers validate to the state the block gives, including across
an epoch boundary; a property over eight kinds of tampered header; envelope, counter, horizon and
structure cases), `HeaderGateSuite` (model rules: counting, an invalid header, a header that does
not extend the chain, rollbacks inside the candidate and below the join point, waiting for the chain
and giving up when it stands still, `HeaderChain` as a model), and `InboundGovernorSuite` (a header with a
broken signature drops the peer and the peer's block-fetch server is never asked for a block; when it
is the last header, only the batches before it were fetched).

## Header clock: headers from the future (AUD-01, #153)

Header-first validation checks a header against the ledger forecast, which is a different condition from
the node's clock. The reference also compares the header's slot with the wall clock before it adds the
header to the peer's candidate chain. References are to ouroboros-consensus 4.2.1.0
(`release-ouroboros-consensus-4.2.1.0`, the release cardano-node 11.1.3 pins); the files
`MiniProtocol/ChainSync/Client/InFutureCheck.hs` and the lines cited below are identical on master 5.0.0.0.

**What the reference does.**

- `Node.hs` 589 and 1107-1108: every ChainSync client is built with `realHeaderInFutureCheck
  llrnMaxClockSkew`, and `llrnMaxClockSkew = defaultClockSkew` (`InFutureCheck.hs` 203-207: 2 seconds).
  cardano-node does not expose the skew, so it is not a configuration key here either.
- `Client.hs` 1491: `recordHeaderArrival` reads the wall clock as the first step of `rollForward`, before the
  known-invalid check (1496) and the header rules (`checkValid`, 1513); `setCandidate` (1518) comes after.
- `Client.hs` 1759-1776 (`checkArrivalTime`): the slot is placed on the wall clock with the era history of the
  ledger state at the intersection (`slotToWallclock`, `InFutureCheck.hs` 147-153). If that state cannot place the
  slot (`PastHorizon`) the client waits for the ledger instead; it does not reject.
- `InFutureCheck.hs` 154-175: `ageUponArrival = arrival - onset`; `tooEarly = skew < negate ageUponArrival`,
  that is, the slot begins strictly more than `skew` after the arrival. A header that is not too early is
  delayed: `threadDelay` for `onset - now` (160-164), once, and the ChainSync client of that peer is blocked
  for the delay, so the headers behind it wait too. A header that is too early raises `FarFutureHeaderException`,
  and the client disconnects (`Client.hs` 1775, `InFutureHeaderExceedsClockSkew`). A slot that begins exactly
  `skew` after the arrival waits.
- There is no Genesis or bulk-sync exception. The check runs for every header; a node that is far behind
  needs no special case, because the onset of an old header is long past, `onset - now` is negative and
  `threadDelay` returns at once. The skew compares the header's slot with now, never with the node's tip.
  (What Genesis adds is the historicity check, `HistoricityCheck`, which rejects messages that are too old; it
  is part of the Genesis stage, G3.)

**What Scarab does.** `HeaderClock.clocked` wraps the `HeaderCheck` of a live session, so it applies with
`--header-first on` and `off` alike; a session made without a `HeaderCheck` (offline replay, `--db` replay,
tools, a node restored from a snapshot, which only sees headers of its peers) never meets it. For each
batch of ChainSync updates:

1. The arrival time is read once, as the batch reaches the gate. It lags the reference's per-header arrival by the
   decode time of the batch.
2. `HeaderEligibility.judge` places each header's slot with the era history of the node's chain
   (`SlotClock`: the wall clock, and `EpochGeometry` as the chain knows it now) and returns `Eligible` (the
   slot has begun), `WaitUntil(onset)` (it begins within the skew) or `FarFuture` (strictly beyond it). The
   arithmetic is exact over the slot number the peer chose; it cannot overflow.
3. A `FarFuture` header raises `PeerError.InvalidHeader(point, HeaderError.FarFuture(slot, aheadMillis,
   skewMillis))`; `FailurePolicy.classify` makes it `Misbehaved`, so the governor forgets the peer (owner
   decision 8). Nothing of the batch is validated or fetched, and nothing waits first.
4. Otherwise the batch waits until the latest onset among its headers, reading the wall clock again after each
   sleep (a clock that stepped back is waited out), and then goes on to header-first validation. Block
   selection never sees a header before its slot begins.

**Deviations, with reasons.** (a) The reference adds the headers before a waiting header to the candidate at
once; here the batch is released together at the latest onset, at most the skew later, because the gate hands a
batch on as a unit (`Peer.scala`). (b) The reference decides the far-future header after the ones before it have been
added; here a far-future header anywhere in a batch refuses the batch, which is stricter only for the headers
that precede it in that batch. (c) While the Byron span is still derived (the Byron ledger has not yet seen the
transition to Shelley), a Shelley slot cannot be placed. Reading it as a Byron slot would put it years ahead, so
the slot is placed with the shorter slot length: that is never later than the true onset, so no honest header is
refused, at the price of not judging such a header until the ledger has caught up (the reference waits for the
ledger there). (d) The skew is the reference's 2 seconds, fixed.

Tests: `HeaderClockSuite` (slot onset, the skew boundary in both directions, a property over the verdict,
overflow-safe slots, the derived span, a header that waits and is then offered, a refusal that does not wait,
a waiting header holding its batch, steps of the clock in both directions, reconnection, old headers, 200000
stale clock reads in `tailRecM`) and `InboundGovernorSuite` (a peer whose headers are in the future is dropped
with the typed error while a healthy peer is followed). The devnet has 0.1 second slots, so its skew is 20 slots;
making a producer's clock run ahead needs a test-only flag in the forger, so the typed refusal is exercised in
the suites rather than as a scenario, and the devnet scenarios show that honest peers are never delayed or refused.

## Node-to-client (`--local-socket`)

One Unix socket serves four node-to-client mini-protocols: LocalTxSubmission and LocalStateQuery (what
`cardano-cli` uses), LocalTxMonitor (`cardano-cli query tx-mempool`, wallets) and ChainSync of whole blocks
(db-sync, Ogmios and the followers of `cardano-cli`).

**Rejections** (`ledger/PredicateFailure.scala`, `ntc/PredicateFailureCbor.scala`). The Conway rules
refuse a transaction with a `LedgerFailure.Rejected(txId, PredicateFailure)`: a closed tree that
mirrors `ConwayLedgerPredFailure` (UTXOW, UTXO, UTXOS, CERTS, CERT, DELEG, POOL, GOVCERT and GOV
failures and the LEDGER-level ones) with the payload the ledger reports (the offending inputs,
`Mismatch` pairs, credentials, outputs with their minimum, governance action ids). The rules of the earlier
eras raise cases of the same types (`UtxoFailure.ExpiredUTxO`, `PpupFailure`, the Shelley `DelegFailure`s,
`UtxowFailure.MIRInsufficientGenesisSigs`), which Conway sends as its free-text `ConwayMempoolFailure`
(text derived from the case) and `PreConwayFailureCbor` writes as each earlier era's own instances do; no
failure carries a rule name as its identity (`ruleName` is derived from the case). `PredicateFailureCbor`
writes the shapes of the ledger's `EncCBOR` instances (constructor tag first, a `Mismatch` as two
fields in line, reversed where the ledger swaps them, sets under tag 258, `StrictMaybe` as an empty or
one-element list), and `LocalSubmission.reasonItem` wraps the failures as the hard-fork combinator's
`ApplyTxErr`: `[[6, [failure, ...]]]`; a transaction of another era gets the era mismatch
`[[era, name], [6, "Conway"]]`. The LEDGER-level checks (treasury value, reference-script size,
withdrawals), CERTS and GOV run before UTXOW, as in the ledger. A submitted transaction is judged
by the ledger's MEMPOOL rule under the accumulating judgment (`rules/Judge.scala`,
`docs/design/rule-interpreter-as-built.md`): every rule boundary of the reference is a sub-rule
that always runs, so the failures of different rules are all reported, in the reference's order.
Within each rule every check records its failure and the rule goes on to the state the reference
makes, so a later certificate or sub-rule sees it (#412).

**LocalStateQuery** (`protocol/LocalStateQuery.scala`, `network/LocalStateQueryServer.scala`,
`node/ntc/`). The protocol codec is pure (states Idle, Acquiring, Acquired, Querying, Done; acquire at
a point, the volatile tip or the immutable tip; re-acquire; release). The server asks a `Source` to
acquire a target and gets a `Session`: the ledger state at that block, kept for as long as the client
holds it, whatever the chain does meanwhile (`StateQuerySource` reads the retained states of the
volatile chain: the tip, the anchor, or a block among the last `k`; an older point is `PointTooOld`,
one not on the chain `PointNotOnChain`). `NtcQuery` decodes the envelope (`Query`: system start, chain
block number, chain point; the hard-fork combinator's `QueryIfCurrent`, `QueryAnytime`,
`QueryHardFork`; the Shelley block queries 0 to 39 with sets under tag 258), `QueryAnswers` computes the
answer from the state of the chain as a pure function (the state is read through the `ChainRead` type class, in the vocabulary of the ledger-state mirror: `node/read/`, step 2 of the mirror, J2.7) and the Haskell state codecs (`storage/haskell/state`: `Wire`, the node-type codecs, `UtxoCodecs`) write the ledger's CBOR (the
31-element Conway protocol parameters, outputs in the canonical array or keyed form, the governance
state with its DRep pulser, stake and pool distributions, ...; containers of more than 23 elements are
indefinite as in the ledger's `variableListLenEncoding`). The only effect is reading UTxO entries not
in the retained state from the backing store (`UtxoNeeds`). Only the current era (Conway) is
answered; a query for another era gets the combinator's mismatch reply. The era history is built
from the configured hard-fork epochs, the transitions this run observed and the safe zone of the
current era (`EraHistory`); a version-triggered hard fork that happened before this run cannot be
placed and is assumed to start with the previous era. As on the reference, a result has no byte or item
limit beyond what a byte array holds (`maximumMiniProtocolLimits`, codec run without limits; #353): a
`GetCBOR DebugEpochState` answer of preprod, which `leadership-schedule --next` asks, is tens of megabytes.
A client's query has the same absence of a byte or item bound (#356); only its nesting is bounded (32,767 levels, `LocalStateQuery.MaxQueryDepth`), because the node reads queries on the heap with stack-safe walkers. Queries the node does not answer end the connection with
a protocol error that names the query (`inbound ... closed` in the log), where the reference would
answer.

**LocalTxMonitor** (`protocol/LocalTxMonitor.scala`, `network/LocalTxMonitorServer.scala`,
`node/mempool/LocalMonitor.scala`). The codec is pure (states Idle, Acquiring, Acquired and Busy of four kinds;
`AwaitAcquire` has the wire form of `Acquire`, `[1]`, and the state tells them apart; `GetMeasures`, tag 11,
is answered on every version as the reference's codec does). The server asks a `Source` for a `Snapshot`
(slot, transactions in arrival order with their measures, membership, capacity) and serves `NextTx`, `HasTx`,
`GetSizes` and `GetMeasures` from that frozen value; `AwaitAcquire` waits until the pool's slot or
transactions differ (`Mempool.awaitChange`, woken by `add` and by `sync`). `LocalMonitor` is the reference's
server in numbers: the snapshot's slot is the one after the tip; a transaction counts for its ledger size (the
serialised transaction without the `is_valid` byte, `sizeTxF`) plus 4 bytes, its execution units and the bytes
of the reference scripts of its inputs and reference inputs (`Entry.refScriptBytes`, measured when it was
added); the capacity is the one the pool enforces (`MempoolState.capacity`, `Capacity.of`, which also does the
measures): `computeMempoolCapacity`, a whole number of units, a unit being `maxBlockBodySize -
1024` bytes, `maxBlockExUnits` and 1 MiB of reference scripts, and the number of units `ceil(--mempool-bytes /
unit)` (the reference's override rule, and 2 without one). `GetSizes` describes the whole snapshot,
`GetMeasures` only the transactions `NextTx` has not handed out (the reference's quirk). A transaction is
`[6, #6.24(tx)]`, an id `[6, h'32 bytes']`.

**Local ChainSync** (mini-protocol 5) is the `ChainSyncServer` of node-to-node with the payload
`#6.24(block)` (`CardanoBlockFetch.payloadCodec`, the block as stored, `[era, block]`) and the node-to-client
limits (`ChainSync.Limits.NodeToClient`: 2.5 MB messages, 1024 intersection candidates, where node-to-node
allows 65535 bytes). `ChainServer.blockSource` is the same cursor over the immutable store and the volatile
chain as `source`, projecting blocks instead of headers. As the reference's follower does, the first
instruction after an intersection is a roll-back to the point found (also on node-to-node); devnet `ntc` found
this by comparing the two servers.

Tests: servers against our own clients over in-memory bearers and loopback TCP (both directions,
duplex, pipelining, timeouts, refusals); a property that every follower reconstructs the served chain
across arbitrary extensions, rollbacks and reads; the chain server over a real immutable store;
mempool properties (held transactions always apply in order, byte accounting, tickets);
chain-selection properties (antisymmetric, longer wins, issue number, unsigned VRF compare,
never more than `k` rolled back, the winner among several candidates does not depend on arrival
order); the follower with several scripted peers; the local protocol over a Unix socket.

## Block production (`follow --forge`)

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The node produces Praos (Conway) blocks for one pool. The files are the ones `cardano-cli` writes: the text
envelopes `VrfSigningKey_PraosVRF` (64 bytes, seed then public key), `KesSigningKey_ed25519_kes_2^6` (the
608-byte period-0 key) and `NodeOperationalCertificate` (`[[hotVkey, counter, kesPeriod, sigma], coldVkey]`,
which also gives the cold key, so the cold signing key is never needed). On start the three are
cross-checked: the certificate must name this KES key and be signed by its cold key.

**Cryptography** (`crypto`, pure, with vectors):

- `Draft03Prover` is ECVRF-ED25519-SHA512-Elligator2 draft-03 proving as libsodium does it. It reproduces the
  IETF draft-03 vectors 16 and 17 (public key, 80-byte proof and 64-byte output), and a property test checks
  that every proof verifies with `StrictDraft03` and certifies the same output. Secret scalars only meet the
  constant-time arithmetic of `curve25519-elisabeth`; the hash-to-curve step takes public inputs only.
- `Sum6KesSigning` is the cardano-base `SumKES` layout: a level-`d` key is `subKey || r1 || vk0 || vk1`, the
  signature the leaf signature followed by the `vk0 || vk1` of each level (448 bytes), the verification key
  `Blake2b-256(vk0 || vk1)`. `generate(seed)` expands seeds with `Blake2b-256(0x01 || seed)` and
  `Blake2b-256(0x02 || seed)`; `fixtures/kes-gen` has the seeds `cardano-cli node key-gen-KES` stored in its keys
  together with the verification key it computed, and regenerating the depth-5 tree from the seed gives exactly
  that verification key. `evolve` moves one period and zeroes what cannot be used again (the seed of the right
  half when it is generated, the whole left half when the right one takes over); `fromBytes` recomputes every
  level's stored verification keys and refuses a damaged key. A key from a file is at period 0 and is evolved to
  the slot's period (`slot / slotsPerKESPeriod - certificate start`) before it signs.

**Forging** (`node/forge`). `Forger` runs one attempt per slot from the wall clock over the era history
(`EpochGeometry`); a passed slot is never forged late, and a slot that passes while an earlier attempt is still running is reported as `forgeSkipped` ("the forger was busy with an earlier slot"). For the slot it takes the tip's state to the slot's
epoch exactly as block validation does (cached per tip and epoch), reads the pool's stake and registered VRF
key from that epoch's pool distribution, proves `Blake2b-256(slot || epochNonce)` and compares the output with
the Praos threshold (`Leadership`, the same check as for received headers). When elected it:

1. selects transactions from the mempool and re-applies them one after another to the ledger ticked to the
   slot, in the block's own environment (`BlockBuilder.select`): `maxBlockBodySize`, `maxBlockExUnits` (the
   sum of the redeemers' execution units) and the per-block reference script limit are enforced, and a
   transaction that no longer applies is skipped;
2. encodes the body from the transactions' original bytes (bodies, witness sets, auxiliary data by index,
   invalid indices), hashes it as the header commits, and builds the ten-element Praos header body (block
   number, slot, previous hash, cold key, VRF key, VRF result, body size and hash, operational certificate,
   the ledger's protocol version);
3. evolves the KES key to the slot's period and signs the exact header body bytes;
4. offers the block to `Follower.adoptForged`, which runs it through the node's own `MirrorRules`, then commits it
   through the same path as any block (persistence, the chain server, mempool synchronisation). A block built on
   a tip that has moved is `Lost`; one our rules refuse is `Invalid`, and the forger then retries without
   transactions.

The forger does nothing until a peer session has intersected, and not from a tip older than the stability
window (a node that is still syncing cannot know the slot's ledger view). Only Praos with Conway transactions
is forged; a TPraos era or the bootstrap mode is `ForgeError.UnsupportedProtocol`.

Errors are a closed ADT, `ForgeError`: `KeyFile`, `KeyMismatch`, `KeyPeriodOutOfRange`, `KesFailure`,
`VrfFailure`, `NotLeader` (the ordinary answer), `LedgerViewUnavailable`, `UnsupportedProtocol`, `Assembly`,
`SelfRejected`. Log events: `forgeStarted`, `forged` (slot, block number, hash, transaction ids, KES periods,
stake), `adopted`, `lost` (the block did not join our chain, or was replaced at its height later),
`kesEvolved`, `kesReloaded`, `kesReloadRefused`, `kesPersistFailed` (see "Rotating the KES key" in
[running.md](running.md)), `forgeSkipped`, `forgeFailed`.

The slot clock starts at the Byron genesis' `startTime` (whole seconds) when there is a Byron genesis
file, as the reference's hard-fork combinator does, and at the Shelley `systemStart` otherwise
(`Genesis.withSystemStart`; the `started` line logs the instant as `systemStart`). The two files agree on
the public networks, but a hand-made network can write them apart, and a node that used the Shelley value
then runs its slots a whole number of seconds away from the network's (roadmap H6).

Counters (`ForgeStats`, per epoch; roadmap H5): slots checked for leadership, leader slots, forged, adopted,
lost (slot battles lost at adoption, and blocks replaced later), failed, slots not checked by reason
(`busy`: the loop was still on an earlier slot; `tipTooOld`; `slotOccupied`: a block already held the slot or
a later one; `notConnected`), and the forge latency (slot start to adoption: count, mean, max). The first two
reasons are also logged one by one as `forgeSkipped`. A `forgeEpoch` line carries an epoch's counters when
the next epoch begins, an `adopted` line carries its `latencyMs`, and `GET /forging` on the query port
serves the counters per epoch and in total (the `pool` scenario reads it and asserts it against the log:
forged and adopted counts equal, no block took more than five slots from its slot's start to adoption).

Tests: `ForgeSuite` forges with real keys against the real `MirrorRules` over a synthetic Conway ledger (blocks are
accepted, consecutive, counted for the pool, transactions leave the mempool, the KES key crosses period
boundaries, an unregistered VRF key or a certificate outside its window never forges, lost and invalid
adoption, body size accounting and a decode round trip), `KeyFilesSuite` loads a real `cardano-cli`
certificate, `Draft03ProverSuite` and `Sum6KesSigningSuite` hold the vectors.

## Scale: the UTxO set on disk and a pipelined sync

Syncing Preview with the whole ledger in memory fell from 350 blocks/s to 20 blocks/s by block 527k
(UTxO of 1.25M entries, 2.7 GB live, 335 MB snapshots). The measurements are in
[performance.md](performance.md); the changes, in the order they paid off:

**Compact bytes.** `xyz.kd5ujc.scarab.codec.Bytes` was a `Vector[Byte]` (boxed, about 6 times the size, hashing and
equality walked the elements). It is now a window onto an immutable `Array[Byte]` with a cached hash;
the CBOR decoder gives every node a zero-copy window of the block (`original`) instead of a copy, and
integers are read as `Long` when they fit. The API is unchanged (`value` still gives a `Vector`).

**The UTxO set behind an algebra (UTxO-HD).** `UtxoStore[F]` has `read(keys)`, `write(changes, stamp)`,
`flushed`, `checkpoint(dir)`, `restore(dir)`, `all` and `reset`; `MemoryUtxoStore` (tests, the devnet,
`follow` without `--db`) and `RocksUtxoStore` (RocksDB through its JNI, Maven `org.rocksdb:rocksdbjni`,
native library bundled for linux x86_64) are the interpreters. The ledger state holds a pure `Utxo`:

- `diff`: the changes made since the store's flushed point, newest per key, each stamped with the
  block number. The states of the last `k` blocks each hold one (persistent maps, shared structure),
  which is the DbChangelog: a rollback is dropping states, the store is never touched, and it only ever
  holds immutable state.
- `resident`: the read set. `NodeRules.prepare` decodes the block once, collects the inputs, reference inputs
  and collateral of its transactions that neither the diff nor the block itself decides, and fetches
  them from the store before the pure rules run (`Utxo.missing`, `withResident`); afterwards the read
  set is dropped. The rules keep their shape: `state.utxo.get(in)`, `utxo -- inputs ++ created`.
- `coin`, `count`, `stake` (lovelace by stake credential) and `ptrStake` (by certificate pointer),
  updated as outputs are created and spent. The epoch boundary's SNAP no longer walks the UTxO set
  (`Stake.instant` adds the accounts and resolves the pointers), and the supply invariant reads
  `utxo.coin`.

When a block becomes immutable (the follower's anchor moves) the changes of the anchor state newer than
the store's stamp are written in one batch (`UtxoStore.advance`, through `StateBacking`); the diffs are
trimmed lazily (`Utxo.settled`). A ledger snapshot is written for the anchor state with its diff
trimmed, and a checkpoint of the store (RocksDB `Checkpoint`: memtable flushed, SST files
hard-linked) is taken at the same block under `<db>/utxo/checkpoints/<snapshot name>`, so a snapshot
and its UTxO are always the same block; pruning follows `--keep-snapshots`. A restart restores the
snapshot and rebuilds `<db>/utxo/live` from its checkpoint (SST files hard-linked, the rest copied).
A snapshot without its checkpoint is skipped like a damaged one. There is no write-ahead log: the live
database is disposable.

Properties (`UtxoStoreSuite`, `StakeSuite`): random blocks, rollbacks of up to 4 and flushes with
`k = 3` leave both interpreters equal to a `Map` model at every step, and the incremental stake,
total and count equal a recomputation; checkpoint/restore returns to the checkpointed set; the
Byron-to-Conway capture reaches the same state through the store with verification warmed ahead.

**Verification ahead of the rules.** Most of the time of a block is signature checks: transaction
witnesses (Ed25519), the operational certificate and KES signature, and the VRF proof. They are pure
functions of the block (the VRF also of the epoch nonce). `VerificationCache.warm` runs them for a whole
fetched batch in parallel and keeps the verdicts; the rules ask for the same verdicts through
`Verdicts.witness`, `opCert`, `praosVrf` and `tpraosVrf`, which return the remembered one (once)
or compute it (`Verdicts.Direct` never remembers; a property test checks both agree). The VRF is warmed with the epoch nonce the rules last saw, keyed by the derived alpha
string, so a stale nonce is only a miss. Replay of the stored blocks warms the next 64 while the
current ones are applied; the peer warms each fetched range.

**A pipelined peer.** `Peer.nodeToNode` (and `Peer.over` for any negotiated connection) runs a
producer fiber that keeps up to three batches of `--read-ahead` (default 128) in flight: it requests
the headers of the next batch, validates them (header-first, see above) while the blocks of earlier
ones are being fetched and verified, and publishes them in order; the consumer applies a batch
meanwhile. The depth was measured from 64 to 256 against the public relay (performance.md section 7):
no timeout at any of them, 128 and above are 6 to 10% faster than 64.

```sh
follow ... --db <dir> [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--read-ahead N]
```

`rocksdb` is the default with `--db`; without it the node uses the memory store. The query server
completes the UTxO from the store for `/state`.

## Scheduling: CPU-bound work off the timer path (D8)

cats-effect gives the compute pool one thread per CPU. A pure step that holds one of them for
hundreds of milliseconds (the rules of a block full of Plutus scripts, an epoch boundary over all
accounts) delays every timer on the pool: KeepAlive, protocol timeouts, the forger's slot clock
(0.1 s slots on the devnet). With one CPU there is a single such thread. RocksDB calls and the
file stores already use `blocking`; the CPU-bound part was the follower: header handling, chain
selection, the pure rules, persisting.

- `Compute[F]` (`Scheduling.scala`) has `within(fa)`, which runs an effect on a second cats-effect
  work-stealing pool (`IORuntime.createWorkStealingComputeThreadPool`, workers `scarab-cpu-N`, default one
  per CPU, `--cpu-threads`) allocated and shut down as a `Resource`, and `timed(label)(step)`, which
  runs a pure step where it is and records its duration. `Main` wires the outbound and inbound
  session callbacks through `compute.within(follower.session(...))`; the connection itself
  (multiplexer, KeepAlive, the pipelined ChainSync and BlockFetch producer) is opened outside and stays
  on the compute pool, as do the relay servers, the forger loop and the timer monitor. The replay of
  stored blocks and the resume of the volatile chain run within the pool too, and so does the
  application of a block this node forged. `MirrorRules` times `applyBlock` through `timed`.
- Where the boundary is drawn matters. A first version moved each block's rules to a plain fixed
  thread pool and cost a third of the throughput; the second moved each block to a work-stealing pool
  and cost a fifth: every hand-off is a thread wake-up, and on a busy host each wake-up is a
  scheduler delay in the middle of a sequential chain of 400 blocks a second. A session that stays on
  the pool across blocks pays a hand-off only when it waits for the network (performance.md section 6).
- A step is timed on the thread it ran on and recorded under a label computed from its result:
  `block`, `plutus` (the block evaluated scripts), `epochBoundary` (it crossed an epoch), `rejected`.
  `--cpu-threads 0` runs everything on the compute pool as before, still measured.
- `Scheduling.monitor` is a fiber that sleeps 20 ms over and over and records how late it wakes: the
  lateness of every timer on the compute pool, which is what the cats-effect starvation warning
  reports. `SchedulingStats` keeps a window (read and reset at each epoch line of the log) and the
  totals (a commutative monoid, law-tested).
- The epoch lines `epochValidated` (Byron, bootstrap) and `ledgerEpoch` carry a `scheduling` object:
  `maxTimerLatenessMs`, `timersLateOver50Ms`, `timersLateOver100Ms`, `cpuSteps`, `longestStepMs`,
  `longestStep`. `GET /scheduling` on the query port returns the run totals with every label.
- Long loops yield with `cede`: the replay of stored blocks and the resume of the volatile chain.
- Batch verification (`Verification.warm`) stays on `blocking` threads beside the session. Putting it
  on the CPU pool as well queued the rules behind it and cost a third of the throughput.

A step is still one uninterrupted call of a pure function, so the longest step is reported, not
bounded; what the pool buys is that the compute pool is not the thread that runs it. It does not
bound what the kernel does: a container limited to one CPU is throttled by the cgroup whenever its
threads together use more than the quota, and a JVM that finds one CPU picks the serial collector,
whose pauses stop every thread. Both showed up as late timers in the measurements, and the second is
fixed by JVM flags, not code (`-Xms` equal to `-Xmx` and `-XX:+UseG1GC`, see
[running.md](running.md) and [performance.md](performance.md)).
