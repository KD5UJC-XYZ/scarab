<!-- SPDX-License-Identifier: Apache-2.0 -->
# Modules, layers, algebras and the error model

Part of [Architecture](../architecture.md).

## Modules

The module table names directories; `build.sbt` defines their project and artifact names
(organisation `xyz.kd5ujc`). Most share their directory name; `blst-native` uses project
`blstNative` and package `xyz.kd5ujc.scarab.blst`, while `models-gen` uses project `modelsGen`
and package `xyz.kd5ujc.scarab.models.gen`. The dependency direction is enforced by sbt:
a cycle does not load.

| Module | Contents | Effect style |
|---|---|---|
| `blst-native` | The portable libblst (ADR 0012) at the resource path blst-java loads for the Leios BLS keys (`MinSig`), and `Blst`, the start-up check of the library and the CPU (sbt project `blstNative`) | `F[_]` with `Sync`/`Raise` |
| `codec` | CBOR with original-bytes preservation (`Cbor`, `Node`, `Value`), `Bytes`, `Rational` | Pure |
| `crypto` | Blake2b, Ed25519 (strict profile), VRF draft-03 verify and prove, Sum6 KES verify, sign and evolve, BLS12-381 (Leios), operational certificates | Pure |
| `protocol` | Ouroboros mini-protocol codecs and state machines: handshake, mux framing, ChainSync, BlockFetch, KeepAlive, TxSubmission2, LocalTxSubmission, LocalStateQuery, LocalTxMonitor, Leios, era and header tags (`Era`, `NtNHeader`), pure network error vocabulary | Pure |
| `network` | fs2 bearer, multiplexer (duplex), handshake, client and server roles of every mini-protocol over one connection | `F[_]` with `Async`/`Raise` |
| `plutus` | `ScriptEvaluator[F]` algebra, the Scalus adapter, `NativeCrypto` (the start-up check of the Plutus native library), `PlutusData`, `ExUnits` | `F[_]` at the evaluator, pure otherwise |
| `ledger` | Blocks, headers, genesis files, transactions, scripts, the Shelley to Conway rules, the Byron ledger, `Verdicts`, `OCertRules`, the node error hierarchy, `Nonce`, `LedgerView` | Pure |
| `consensus` | Praos, TPraos and PBFT, chain selection, the hard-fork combinator, header rules, `VolatileChain`, `MirrorState` and the mirror's types, `BlockRules`, `Scheduling` | Pure, `Compute[F]` in `Scheduling` |
| `storage` | `ImmutableStore`, `VolatileStore`, `LedgerSnapshots`, `ChainStorage`, `UtxoStore` (memory, RocksDB), derived `Codec`s, format migrations, the Haskell ImmutableDB reader and the Haskell ledger-state codec | Algebras over `F[_]` |
| `node` | Peer, follower, header gate, block rules (`MirrorRules`, with what they share in `NodeRules`), mempool, forging, relay, node-to-client, query server, `Main` | Algebras over `F[_]`; `IO` only in `Main` and the tools |
| `models-gen`, `models` | CDDL parser and Scala generator; the generated models, their runtime and the equivalence tests. A spike: the node does not decode with the generated models, only `storage` uses the runtime (docs/models.md) | Build-time tool; pure |
| `fuzz` | Structured fuzzing of every decoder entry point (test only) | |

```
blst-native -> crypto (before blst-java on its class path)

codec <- crypto <- protocol <- network ------------------------.
  ^                  ^                                          \
  |                  '--- ledger <- consensus <- storage <------ node
  '------ plutus <------'
```

`ledger` depends on `protocol` and `plutus`; `consensus` on `ledger`; `storage` on `consensus` and
`models` (which stands on `codec` and `crypto`; the Haskell ledger-state codec reuses the models runtime; `models` itself sees `ledger` only in
its tests); `node` on `storage` and `network`. The ledger cannot import the network runtime, and storage cannot
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
| Query | `Query.scala` | `ChainView[F, S]` plus a loopback http4s server: `GET /tip`, `GET /state?slot=&hash=[&utxo=summary|full]` (summary by default; the devnet comparator asks for `full`; used by the devnet comparator), `GET /scheduling`, `GET /connections` (connection table and inbound sessions), `GET /metrics` (Prometheus text format; `MetricsServer` serves it alone on `--metrics-listen`) |

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
| `Log[F]` (`Tell[F, Line]`) | the log: lines built from typed events (`Traced[E]`, a closed `EventKind` with namespace and severity; `NodeEvent`) | `LogRouter`: stdout, stderr and a size-rotated file, JSON or human, per-namespace severity and sampling; the libraries' SLF4J lines through `LibraryLogs` ([ADR 0014](../adr/0014-typed-log.md)) |
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
replace their narrow API types or prove their semantics. See [the error design](../design/domain-errors.md).

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
