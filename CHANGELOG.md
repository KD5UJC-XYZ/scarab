# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is pre-1.0 research
software; versions mark milestones, not stability. Commit hashes refer to this repository's history.

## [Unreleased]

### Changed (storage: scodec for the Haskell ImmutableDB indices)
- `SecondaryEntry` and `PrimaryIndex` carry their `scodec.Codec` (scodec-core 2.3.3) in the companion,
  citing `Secondary.hs`/`Primary.hs`; `SecondaryIndex.parse`/`PrimaryIndex.parse` read through them and
  return the same `ChunkFault`s with the same entry indices (a typed `Binary.Failure` maps to the fault).
  `encode` now returns `Attempt` and refuses what it used to truncate: an offset beyond Word32, a header
  offset or size beyond Word16, a hash that is not 32 bytes. Identical to the old parsers on random and
  damaged files and on all 29,031 chunks of the K1 Preview ImmutableDB (4.76 M entries).
- Goldens pin the persisted bytes of the file store index entry, the volatile log record and the Haskell
  indices (hex computed outside the code). Those store formats, the UTxO key and the mux SDU header
  stay hand-rolled: scodec measured 2 to 24 times slower on their per-block paths (CONTRIBUTING,
  "Build tooling"). `FormatBenchSuite` is the harness.

### Added (ledger-state mirror, stage S0)
- `ledger/state`: the vocabulary of the Haskell ledger state (opaque `Coin`, `DeltaCoin`, `CompactCoin`,
  `EpochNo`, `SlotNo`, `BlockNo`, `KeyHash[R]`, `ScriptHash`, `VRFVerKeyHash`, `Hash32`, `NonZero`, key-role
  phantoms, `Credential[R]`, `DRep`, `WithOrigin`; smart constructors fail with a closed `VocabError`) with
  hand-written `cats.Order` instances equal to the Haskell `Ord` (script credentials before key credentials;
  DRep key < script < abstain < no confidence), and the era encoding (`ShelleyBasedEra[E]`, a closed witness,
  and the match types `CertState`, `Accounts`, `InstantStake`, `GovState`, `StashedAVVM`; their records are
  stubs for S1). New types beside the existing ones; no persisted type changes. Order-law tests, a
  compile-time test of wrong-era access, and an agreement test with `LedgerEncoders`' sort keys.
- Build: monocle, kittens and monocle-law in `ledger`; `storage` depends on `models`.

### Added (ledger-state mirror, stage S1)
- `ledger/state`: an unsigned `Word64` (opaque over the two's-complement `Long`; unsigned `Order` and
  `Show`; `fromBigInt` over [0, 2^64) with `Negative`/`OutOfRange`; total `fromUnsigned`) that `EpochNo`,
  `SlotNo`, `BlockNo` and `CompactCoin` now wrap, as Haskell's are `Word64`; `EpochNo.next` is `None` at
  2^64 - 1. `NonZero` takes a `HasZero` class (so `NonZero[Word64]` exists). The `WithOrigin` and `NonZero`
  orderings are confirmed against cardano-slotting 0.2.2.0 (`Slot.hs:71-82`) and `NonZero.hs:76-78`.
- `ledger/state`: the ledger tree of the mirror, with the real fields in place of S0's stubs: `LedgerState`,
  `UTxOState`, `ShelleyCertState`/`ConwayCertState`, `DState` (parameterised by its accounts record),
  `PState`, `StakePoolState`, `VState`, `DRepState`, `CommitteeState`, Shelley/Conway accounts and instant
  stake, `ShelleyGovState`/`ConwayGovState`, `FuturePParams`, the completed DRep pulser and the opaque
  `UtxoTables`; every map is a `SortedMap` in the Haskell key order. One Monocle `Focus` lens per field,
  composed into the paths of a conversion (`ConwayLedgerState.pools`, `dreps`, `accounts`, `gov`,
  `proposals`, and `LedgerState.pState` by the era witness). Lens, `Eq` and order tests.
- `QueryAnswers` uses the mirror's `FuturePParams` instead of a private enum; no NtC byte changes.

### Added (ledger-state mirror, stage S2)
- `ledger/state`: the top of the Haskell ledger state: `NewEpochState[E]`, `EpochState[E]`,
  `ChainAccountState`, `BlocksMade` (opaque over a sorted map of words), `SnapShots`/`SnapShot`/
  `StakePoolSnapShot`/`ActiveStake` (the pinned ledger-1.21 shape; the total active stake and the mark pool
  distribution are derived methods, as Haskell does not encode them), `PoolDistr`/`IndividualPoolStake`,
  `NonMyopic` with `Likelihood`/`LogWeight` (a 32-bit float, with Haskell's normalising `Eq`), and the reward
  types `Reward`, `RewardSet` (ordered as Haskell's `Set Reward`), `RewardUpdate`, `RewardSnapShot` and
  `PulsingRewUpdate` (`Pulsing` keeps the snapshot only; exporting a pulsing state is refused). `StashedAVVM`
  gets its `Eq`/`Show` by the era witness. One Monocle lens per field, Conway paths composed through all
  three levels, an `Iso` for `BlocksMade` and `ChainAccountState`, a `Prism` for the completed update. Lens,
  Iso, Prism and `Eq` law tests, and compile-time era tests. No node type changes.

### Added (ledger-state mirror, stage S3)
- `consensus/state`: the hard-fork combinator and header state of the mirror. `Bound`, `Past`,
  `Current[A]` and `Telescope[A]` (era `n` has `n` past eras, consecutive eras abut; `Telescope.make` is the
  only constructor and fails with a closed `TelescopeError`; the era index is `protocol.Era`, Byron and the
  six Shelley-based eras, tied to a state by an `EraIndexed` class). `ShelleyLedgerState[E]`, `ShelleyTip`,
  `ShelleyTransition`, `EraLedgerState` (`ByronCurrent` | `InShelleyEra[E]`, an existential pack of the
  witness and the state; equality by matching the witnesses, no cast), `CardanoLedgerState`,
  `ExtLedgerState`, `HeaderState`, `AnnTip`, and `ChainDepState` (`PBft` | `TPraos` | `Praos`, the
  Haskell fields, `PraosState` with its eight). Focus lenses per case class, `Eq`/`Show`; telescope
  property tests, Eq laws and Monocle lens laws.
- `Bound`, `EraSummary` and the `EraHistory.summary` interpreter moved from `node/ntc` to
  `consensus/state` (the HFC `Bound` is the same type); `node` keeps the encoding and re-exports the
  names; no NtC byte changes. `Nonce` gained `Eq`/`Show`.
- `scripts/devnet.py` runs from a git worktree: it mounts the shared dependency cache
  (`SCARAB_CACHE_DIR`, else the checkout's own populated `.cache`, else the main checkout's) at
  `/work/.cache`; unit-tested, documented in CONTRIBUTING.

### Fixed (inbound duplex sessions)
- A session on an inbound duplex connection that shared no point with the peer (`NoIntersection`)
  was retried by starting all our clients again on the same multiplexer, so TxSubmission2 sent a
  second `MsgInit` mid-protocol and the Haskell peer dropped the connection (`codecTxSubmission2
  (ClientHasAgency, SingTxIds SingBlocking) unexpected key (6, 1)`). The clients now start once per
  session and only the following is retried. Seen as an intermittent `relay` gate failure (the
  Haskell relay behind by up to 10 blocks while it reconnected); regression test in
  `InboundGovernorSuite`.

### Fixed (B18, #84)
- The paths that still limited nesting below the reference now bound it by size alone, as block decoding
  does since B17: the mempool (was 64), the block builder (32), local state query (`NtcQuery`, 32) and the
  ledger encoders (64) use `Cbor.Limits` without a depth of their own, so the node accepts, relays and
  forges a valid transaction such as preprod f90dce57 (5,383 levels of native script). The mini-protocol
  framing (`ProtocolWire.Reader.skip`, used by LocalTxSubmission, LocalStateQuery, LocalTxMonitor and
  TxSubmission2) keeps its open containers on the heap instead of recursing, and its tx-carrying limits
  allow as many levels as items (65,535) within the message size (1 MiB local, 64 KiB node-to-node,
  where a transaction is at most 16,384 bytes). Byron decoders are unchanged (their structure is fixed;
  nothing in a Byron item nests freely).
- Structural `==`, `hashCode` and `toString` of `Node`, `Value`, `PlutusData` and `NativeScript` walk
  the tree on the heap (`codec.Tree` type class: children, shallow equality, shallow hash, shallow
  name) and no longer overflow at about 5,000 levels. `toString` is truncated on purpose to 1,024
  characters (`Tree.DefaultRenderLimit`, byte strings and text abbreviated, a `Node` shows its value
  and not its original bytes). Regression tests: `DeepNestingSubmissionSuite` (f90dce57 through the
  mempool, LocalTxSubmission, TxSubmission2 and a forged block), `DeepNestingSuite`,
  `LocalTxSubmissionSuite`, `TxSubmission2Suite`.

### Changed (build)
- Scalus 1.3.0 (from 1.2.0). Its `serialiseData` and the flat codec of `Data` constants write a
  constructor index as an unsigned 64-bit integer, but `serialiseData` still refuses more than 1000
  levels of nesting (the reference has no limit), so the evaluator keeps `DataSerialiser`
  (reproducer in `DataSerialiserSuite`). `case` on `Data` is fixed upstream but enabled
  from protocol 12 only, so the Plutus conformance suite (protocol 11) still passes 987 of 1009 with
  the same 22 known failures. The Ed25519 costing, `NewConstitution` and secp256k1 `r = 0` workarounds
  remain (1.3.0 still has those deviations).
- Versions and libraries moved from `build.sbt` to `project/Dependencies.scala` (`V`, `Libraries`).
- Build plugins: sbt-scalafix with OrganizeImports (`.scalafix.conf`), sbt-buildinfo (the `started`
  log line has a `build` object: version, Scala and sbt versions, commit), sbt-scoverage, sbt-updates,
  `dependencyTree`, sbt-tpolecat (disabled: the compiler options of `build.sbt` are unchanged).

### Fixed (B19)
- `n` of a native script `script_n_of_k` may be negative and is a signed 64-bit integer, as the reference
  reads it (`TimelockMOf !Int`, CDDL `int`); the slots of `invalid_before` and `invalid_hereafter` are
  unsigned 64-bit. The preprod sync stopped at block 5,184,855 (slot 133,902,219, epoch 313, transaction
  b1db2a41, script `[3, -1, [sig, sig]]`, which always holds) with `script n: expected unsigned
  integer`. `NativeScript.decode` now returns a typed `NativeScript.DecodeError` (`Malformed`,
  `RequiredOutOfRange`, `SlotOutOfRange`) and rejects what the reference rejects; evaluation
  (`isValidMOf`) already agreed. The snapshot schema fingerprint is unchanged. Fixture
  `fixtures/preprod/tx-b1db2a41.hex`, `PreprodNOfKSuite`.

### Fixed (B17)
- No nesting limit below the size of the block, as in the reference (`Timelock`, `decodeMetadatum` and
  `PlutusCore.Data.decodeData` have none). The preprod sync stopped at block 5,183,974 (slot
  133,883,340, epoch 313, transaction f90dce57: a native script 5,383 levels deep, CBOR depth 10,772)
  with `decode block: depth limit exceeded`, our limits being 2048 for blocks and 1024 for Plutus data.
  `Cbor.decode` and `Cbor.encode` keep an explicit stack, `TreeFold` is a heap post-order fold used by
  `NativeScript.decode`, `PlutusData.fromNode` and `ScalusEvaluator.toData`, and `NativeScript.eval`,
  `Codec[NativeScript]` and `DataSerialiser` are loops; `Cbor.Limits.maxDepth` defaults to no limit of
  its own (input size and `maxItems` bound depth and memory). The verdict on a deep item no longer
  depends on the thread's stack size (the open question of K3). Fixture
  `fixtures/preprod/tx-f90dce57.hex`, `DeepNestingSuite`, `CborSuite`.

### Fixed (B16)
- A Plutus V1 or V2 script may carry bytes after its CBOR byte string, as in `deserialiseScript`
  (V3: `RemainderError`); Scalus `fromCbor` rejected them. The preprod sync stopped on this at block
  4,045,972 (transaction aee75c1c, epoch 248, `MalformedReferenceScripts` for a V2 reference script
  that is its CBOR item twice); `PlutusScriptBytes` cuts the first item before decoding. Fixture
  `fixtures/preprod/plutus/tx-aee75c1c.json`, `PreprodMalformedRefScriptSuite`, `PlutusScriptBytesSuite`.

### Fixed (B15)
- `verifyEcdsaSecp256k1Signature` returns `False` for a signature with `r = 0` or `s = 0` (and for a
  high `s`) and fails only for a malformed key, message or signature (length, key not on the curve,
  `r` or `s` at least the group order), as the reference does; Scalus 1.2.0 and 1.3.0 raised an error
  for a zero `r` or `s`. The evaluator replaces the builtin's meaning (`Secp256k1.verifyEcdsa`, costing
  kept). The preprod sync stopped on this at block 3,498,869 (transaction 97b31e35, epoch 217);
  fixture `fixtures/preprod/plutus/tx-97b31e35.json`, `PreprodSecp256k1RulesSuite`, `Secp256k1Suite`
  (which also pins `verifySchnorrSecp256k1Signature`, already conforming).

### Changed (the reorg, #82)
- Renamed: the Scala package root `lab` is `xyz.kd5ujc.scarab`, the sbt organisation `xyz.kd5ujc`, the
  artifacts `scarab-<module>`. `models-spike` is `models` (`scarab-models`). `CSL_DATA_DIR` is
  `SCARAB_DATA_DIR` (the old name is still read when the new one is unset). Docker image and container
  names (`cardano-scala-jdk`, `csl-`) are unchanged for now. Main classes are
  `xyz.kd5ujc.scarab.node.Main` and `xyz.kd5ujc.scarab.node.tools.CaptureMain`.
- Module split, enforced by sbt (graph in docs/architecture.md): `core` became `codec` and `crypto`,
  `network` became `protocol`, `network-runtime` became `network`, and `node` became `plutus`, `ledger`,
  `consensus`, `storage` and `node`. Every file moved with `git mv`; tests moved with their code. Types
  that closed a cycle moved down: the header error enums, `Nonce`, `PoolDistribution` and `LedgerView`
  into `ledger`, `ExUnits` into `plutus`, `Rational` into `codec`, `NodeState` and `BlockRules` into
  `consensus`; `HaskellImportCommand` moved to `node`. No behaviour change: the ledger snapshot schema
  fingerprint is still `2293c5cf7bb77492` (snapshots written before the reorg restore), and the test
  counts are unchanged.
- Compiler: `-Wunused:imports,privates,locals,explicits,patvars` and `-Ysafe-init` (main sources) are on,
  with `-Werror`. Left off, with their counts: `-Wunused:implicits` 13, `-Wvalue-discard` 243,
  `-Wnonunit-statement` 308 (nearly all fluent `CborOut` calls in `node/ntc`).

### Fixed (B14)
- Byron inputs: the address root is a needed key hash like any other, satisfied by a vkey witness
  whose key hashes to it or by a bootstrap witness with that root, as in cardano-ledger. The separate
  `MissingBootstrapWitnessesUTXOW` rule is gone (an unwitnessed Byron input is
  `MissingVKeyWitnessesUTXOW`). The preprod sync stopped on this at block 3,158,876 (transaction
  0c22edee, epoch 196); fixture and `PreprodByronKeyWitnessSuite`.

### Memory (K6)
- The RSS growth to 5.66 GB on a Preview sync was glibc's per-thread malloc arenas. `docker/jdk.Dockerfile`
  and the devnet node set `MALLOC_ARENA_MAX=2`; running.md says to set it outside a container. With it
  `RssAnon` was the heap limit plus 0.8 GB (3.86 GB with `-Xmx3g` after 45 minutes) against 4.29 GB and
  growing without. New `--memory-report-seconds N` logs the JVM's, the process's, the cgroup's and RocksDB's
  figures; `--utxo-cache-mb` bounds the whole store (the memtables are charged to the block cache).
  Container limit: `-Xmx` plus 2 GiB. performance.md section 7.
- The RocksDB UTxO key is the Haskell node's `txid ++ be16 ix` (34 bytes, was 36); the snapshot format
  version is 2, so older snapshots are rejected and the node replays.

### Leios (roadmap N2, N3 groundwork)
- `lab.bls` in `core` (pure): BLS12-381 MinSig voting keys over blst-java (now a direct dependency):
  key generation from a seed, proofs of possession and votes with the `cardano-base` POP tags,
  signature and key aggregation, strict compressed decoding (length, flag, curve, subgroup,
  identity), `Committee` seating and `Certificate` aggregation and verification, `Vote` and
  `Certificate` CBOR. Opaque `Seed`, `SeatId`, `RbHash`, `Signers`, `Weight`; closed `BlsError`.
  Checked against the Leios proto-devnet pool keys and genesis proofs (byte-exact), cardano-base
  serialisation negatives and the prototype's golden `LeiosVote`; tamper, replay, duplicate and
  rogue-key properties; 900-seat certificate verification about 2 ms (`docs/leios.md` section 9).
- `LeiosNotify` and `LeiosFetch` pure codecs (mini-protocols 18 and 19, Blueprint CDDL), clients in
  `network-runtime`, and a read-only probe of the public Leios testnet relay (`LeiosProbe`, test
  sources): the relay closed the connection on the first protocol-18 request, recorded in
  `docs/leios.md` section 10.

### Verification
- Fuzzing of the decoders (K3): the `fuzz` project, 53 entry points (CBOR, handshakes, mux, ChainSync,
  BlockFetch, TxSubmission2, KeepAlive, LocalStateQuery, LocalTxSubmission, LocalTxMonitor, headers and blocks
  per era, transaction bodies, witnesses and submitted transactions, addresses, scripts, Plutus data,
  node-to-client queries, protocol-parameter updates). ScalaCheck generators mutate the committed fixtures and
  valid items sampled from the pinned CDDL; properties: no exception, time and allocation bounds, decode-encode
  round trips, a 2^60 declared length is refused without allocating. `check` runs 20,000 cases per target (about
  35 s); `fuzz <cases> <seed> <seeds> [target]` and the nightly workflow `fuzz.yml` run more, with the seed and
  the shrunk input printed on failure ([running.md](docs/running.md#fuzzing-the-decoders-roadmap-k3)).
- Fixed by it: `StackOverflowError` from `Cbor.encode`/`decode` and `PlutusData.decode` on deeply nested items
  (now the error `Cbor.TooDeep`), and quadratic time and memory in the decoding of pointer addresses with a long
  run of continuation bytes (now linear, same value; the ledger's Word32/Word16 pointer fields are an open
  difference). See [findings.md](docs/findings.md).
- READMEs with source, licence and how produced for `fixtures/network`, `praos`, `sum6` and `vrf` (M9).

### Network
- Header-first validation (roadmap G6): the pipelined peer validates each ChainSync batch before any
  of its blocks is fetched. `NodeHeaderRules` (pure) checks the envelope, the Praos or TPraos
  header rules and the nonces along the candidate header chain against a ledger view forecast from
  the newest ledger state of ours on the peer's chain, within the stability window (`HeaderRules`,
  `HeaderChain`, `HeaderGate`); an invalid header raises the new `PeerError.InvalidHeader`, the
  session ends, the peer is dropped and none of the batch's blocks is requested. The signatures
  are verified once: the gate's view of `VerificationCache` keeps its verdicts for the block's own
  validation, and `warmBlock` keeps a verdict that is already there. `--header-first on|off`
  (default on); `headersChecked` log line.
- Connection manager and inbound governor (G2): `ConnectionState` (pure), `ConnectionManager` (one
  connection per peer address, inbound and outbound limits, typed `ConnectionError`s, release on
  failure and cancellation), `InboundGovernor` (our ChainSync, BlockFetch and KeepAlive clients run
  on every inbound duplex connection through the same pipelined, header-validating peer, so the
  peer is a block source; a protocol error or invalid header drops the peer), `Outbound.connect`
  (the connections we open go through the manager and wait for a peer's inbound duplex connection
  instead of dialling a second). `Peer.over`, `NodeToNode.clients`. `--max-inbound N`,
  `--inbound-sync on|off`, `GET /connections`, `inbound` and `connection` log events. The devnet
  `relay` scenario asserts that the Scala node follows the Haskell relay over the relay's own
  connection.
- ChainSync depth (D3): `--read-ahead` defaults to 128 (was 64), from 16 runs against the public Preview
  relay at 64 to 256 with no timeout; `chainSyncRounds` and `slowFetch` log lines; `devnet.py pool
  --scala-flags`. `ChainError.NoIntersection` became `PeerError.NoIntersection` (an outbound peer that
  shares no point is retried like any peer failure; it was fatal for the node).
- Receive path (D3): the protocol reader read every byte through `Bytes.value`, which builds a
  `Vector` of the whole buffer, and the multiplexer decoder copied through `Vector`s; a range of 100
  blocks of 90 KB was received at 0.7 MB/s on a loaded machine and now at 48 MB/s. On the shared
  connection the slow receive held the next batch's ChainSync replies behind the block data.

### Node-to-client
- LocalTxMonitor and local ChainSync (roadmap F2, #29). `--local-socket` also serves mini-protocol 9
  (`cardano-cli conway query tx-mempool info | next-tx | tx-exists`, and `GetMeasures`) and mini-protocol 5
  (whole blocks as `#6.24(bytes)`, from the immutable store and the volatile chain, with 2.5 MB messages
  where node-to-node allows 65535). The mempool snapshot is the reference server's in numbers: the slot after
  the tip, each transaction at its ledger size plus 4 bytes, the capacity a whole number of block-sized
  units, `GetMeasures` over the transactions `NextTx` has not handed out. `Mempool.awaitChange` wakes
  `AwaitAcquire` (and `sync` now signals a change). The first instruction after an intersection is a
  roll-back to the point found, as the reference's follower sends it, on node-to-node too (found by
  comparing the two servers). `devnet.py ntc` compares both protocols with the reference node byte for
  byte (`scripts/devnet/ntclocal.py`).
- LocalStateQuery server (roadmap F1): the mini-protocol (acquire at a point, the volatile or immutable
  tip, re-acquire, release), the hard-fork-combinator query envelope and the Conway queries
  `cardano-cli conway query` sends: tip, epoch, protocol parameters, UTxO (whole, by address, by
  input), delegations and rewards, stake pools, pool parameters and state, stake snapshots,
  constitution, governance state, DRep state, DRep stake distribution, committee state, vote
  delegatees, treasury and reserves, SPO stake distribution, proposals, ratify state, future
  parameters, pool and stake distributions, DRep delegations, genesis configuration, protocol
  (chain-dependent) state, era history, system start, chain point and block number. The answers are
  the same CBOR as the Haskell node's at the same block (`devnet.py ntc`). Not answered yet:
  `ledger-state`, the reward-provenance and non-myopic queries, ledger peers, eras before Conway.
- ApplyTxErr (roadmap F3): a refused transaction is sent as the ledger's predicate-failure CBOR, so
  `cardano-cli transaction submit` prints the error the Haskell node gives. The Conway rules raise a
  typed `PredicateFailure` tree with the ledger's payloads; the ledger's MEMPOOL rule (all inputs
  spent) runs first; the CERTS and GOV checks run before UTXOW as in the ledger.
- Protocol parameters read the `extraConfig.costModels` of the Alonzo genesis (PlutusV2); the Scala
  parameters had two cost-model languages where the reference has three.
- `PraosState` keeps the previous epoch nonce (the chain-dependent state a client reads); snapshots
  written by earlier builds fail their schema check and are replayed.

### Configuration
- `follow --config <config.json> --topology <topology.json>` (roadmap J1, #45): the Haskell node's
  configuration is read as `cardano-node run` reads it (genesis file names relative to the file,
  `Test<Era>HardForkAtEpoch`, `RequiresNetworkMagic`, `PBftSignatureThreshold`, `LastKnownBlockVersion-*`,
  `ExperimentalHardForksEnabled`, `ConsensusMode`, `SocketPath`, `DatabasePath`,
  `MempoolCapacityBytesOverride`, ...) and its P2P topology (`localRoots`, `publicRoots`, `bootstrapPeers`,
  `useLedgerAfterSlot`; the local roots and bootstrap peers are followed). Every genesis hash the
  configuration lists is verified: Byron (canonical JSON), Shelley, Alonzo, Conway, and Dijkstra and the
  checkpoints when the file is there; a mismatch is a `ConfigError` of kind `GenesisHashMismatch`, also for
  `--genesis-dir`. `--genesis-dir` and `--peer` work as before. Tested with the public Preview, preprod and
  mainnet files (`fixtures/*/genesis`).

### Block production
- Slot clock (roadmap H6): the node starts its clock at the Byron genesis' `startTime` when there is a
  Byron genesis, as the reference's hard-fork combinator does, and at the Shelley `systemStart`
  otherwise. The devnet pool scenario wrote the two a second apart in some runs, which put the node
  exactly 1.0 s (ten slots) off the network's and silently skipped its leader slots (`started` logs
  `systemStart`; `SystemStartSuite`, geometry properties for fractional starts and slot lengths, a
  forger loop test on a clock off the whole second). The scenario writes both files and takes
  `--byron-start-offset`; it records the host load and reports a run whose only misses are busy slots on an
  oversubscribed host as inconclusive (exit status 2).
- Forging-loop counters (roadmap H5): per epoch, slots checked, leader slots, forged, adopted, lost slot
  battles, failed, slots not checked by reason (`busy`, `tipTooOld`, `slotOccupied`, `notConnected`) and the
  forge latency from the slot's start to adoption. `forgeEpoch` log lines, `latencyMs` on `adopted`, and
  `GET /forging` on the query port; the `pool` scenario checks them against the log and enforces a budget of
  five slots to adoption.
- `FollowerSuite` two-peer test (roadmap M10): the scripted peers replayed their chains from the start
  whatever the intersection, so a peer joining after the other had applied the common blocks failed with
  "does not extend" and the test flaked. Peers now serve chains as a node does and the test runs on
  `TestControl` over 64 join and pause orders and four seeds; the old behaviour is pinned as its own test.

### Byron
- Byron ledger (roadmap C1): UTxO with witness signatures, address roots, network magic, value
  conservation and the linear fee; AVVM and non-AVVM genesis UTxO; delegation certificates; the update
  system; the Byron UTxO carried into the Shelley ledger. Real mainnet transactions (an AVVM redemption
  and verification-key transactions) validate.
- Byron consensus (C2): signer must be the current delegate of a genesis key, last-signed-slot rule,
  PBFT window and `PBftSignatureThreshold`, body proofs; the `unchecked` header outcome and the
  `signatureUnchecked` log field are gone (`offSchedule`, `transactions` added). Light delegation tags
  are decode errors, as upstream.
- The first Shelley epoch is derived from the Byron update state when the configuration has no
  `TestShelleyHardForkAtEpoch` (preprod: epoch 4); `0` still means no Byron prefix.
- Byron epoch length comes from the genesis everywhere (C3): `Block.decode`, `Header.fromEnvelope`
  and friends take it, `Peer.Settings` and `ChainStorage.Config` require it. `CaptureMain` has
  `--byron-epoch-slots`. Snapshot schema changed (old snapshots are rejected and recovery replays).
- Preprod fixtures (`fixtures/preprod`) and mainnet Byron transaction fixtures.

### Storage
- Haskell ImmutableDB reader and `import-haskell-db` (roadmap step A of the Haskell mirror):
  `lab.node.storage.haskell` parses the primary and secondary indexes of ouroboros-consensus
  (opaque `ChunkNo`, `ChunkSlots`, `ByteOffset`; closed `ChunkFault` / `HaskellImmutableError`),
  validates offsets, CRC32, header hashes and slots, cuts the newest chunk back to its valid prefix
  and never writes. `import-haskell-db` appends the blocks to our ImmutableStore after checking the
  protocol magic, genesis, chain links and block numbers, so `follow --db` replays them locally.
  Golden test over three real Preview chunks (`fixtures/haskell-immutable/preview`), properties for
  every index corruption, an EBB database built by the upstream rules. End to end: 40,357 Preview
  blocks imported in 2 s and replayed in 42.7 s. The ImmutableStore format is unchanged.
- D4: the volatile chain (the last `k` blocks) is logged in `<db>/volatile/` (`VolatileStore`: CRC per
  record, rollback markers, files collected once wholly below the forced immutable tip) and applied
  again on start, so a restart continues at the previous tip instead of fetching those blocks. A
  graceful stop (SIGTERM, SIGINT, `--seconds`) writes a snapshot at the immutable tip: the next start
  replays no stored block. Persisting a block is uncancelable, so a stop never lands between two stores.
- D5: the database records its network (`<db>/network-identity`: magic, Shelley and Byron genesis
  hashes) and refuses a mismatch with `StorageError.NetworkMismatch`; a database from before the file
  is adopted if its first block names the configured genesis (`ForeignChain` otherwise).

### Scheduling
- D8: the peer session (header handling, chain selection, the rules, persisting) runs on its own
  work-stealing pool (`Compute[F]`, a `Resource`, `--cpu-threads`), apart from the pool that serves the
  connection, the relay servers, the forger and the timers, so a long CPU-bound step cannot hold up a
  KeepAlive or the slot clock. A monitor fiber records timer lateness; `epochValidated` and `ledgerEpoch`
  lines carry `scheduling` (`maxTimerLatenessMs`, `longestStepMs`, `longestStep`), and `GET /scheduling`
  serves the totals. Moving each block's rules to a pool cost 15 to 48 percent of the throughput; the
  session on the pool costs 1 to 4 percent (Preview, 31 min, started with the base build). The replay of
  stored blocks yields with `cede`.
- With one CPU the JVM's serial collector, not the rules, made timers late: run with
  `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20` (running.md); the devnet node does.

### Verification
- Upstream Plutus conformance (roadmap I1): `PlutusConformanceSuite` runs the 1009 evaluation cases of
  `plutus-conformance` at plutus 1.70.0.0 (commit 507cab8, vendored under
  `node/src/test/resources/plutus-conformance-1.70.0.0`) through `ScalusEvaluator` (Plutus V3,
  protocol 11, semantics variant E): parse errors, evaluation failures, result terms up to renaming
  and budgets, also of failing evaluations. 987 pass; the 22 that do not are listed with their
  class in `plutus-conformance-1.70.0.0-known.txt` and the suite fails on any change in either
  direction. `ScalusConformanceReproducersSuite` reproduces the Scalus deviations found (malformed
  `value` constants accepted, `case` on `Data.Constr`, `hashToGroup` with long or arbitrary DSTs,
  BLS constants in flat, wrapped spent units). Part of `check` (about 5 s). The plutus parameter-name
  lists used as test data were missing four constructors and are regenerated from the source.
- Ledger-state oracle (roadmap K1, `scripts/oracle/`, `scripts/oracle_compare.py`): `follow --dump-state`
  writes a digest of the ledger state at every epoch boundary (or at chosen slots); the reference image's
  `db-analyser` stores the Haskell state after the same block, which is decoded (UTxO-HD `MemPack`
  tables, `NewEpochState`, the Conway governance state) and compared, with differences narrowed by
  bucket digests. `devnet.py e2e --oracle`, `soak --oracle` and `devnet.py oracle` run it against a
  cluster; `oracle_compare.py reference` and `diff` against a Haskell node's ChainDB on a public network.

### Ledger
- Plutus `serialiseData` follows the reference for a `Data` constructor index of 2^63 or more: Scalus
  1.2.0 wrote it through a `Long` (`2^64 - 1` as `-1`), so the Hydra fanout of preprod transaction
  2edd684f (slot 80,239,053, epoch 189), which hashes outputs with such indices, failed phase 2 and
  stopped the preprod sync at block 3,050,799. The evaluator runs the machine with `DataSerialiser`
  for that builtin (costing unchanged); a failing script's trace is now part of the failure reason
  (`Error evaluated; trace: H41 | PT5`). Test: `PreprodConwayRulesSuite` over the real transaction
  (`fixtures/preprod/plutus/tx-2edd684f.json`, `tx-2edd684f.hex`), `DataSerialiserSuite`.
- Byron genesis outputs of zero lovelace (Preview has seven) are not put into the UTxO; the ledger's
  Byron to Shelley translation drops them.
- The stake snapshots leave out a credential without stake (no UTxO and no rewards), as the ledger's
  active-stake resolution does.
- The Conway translation keeps the genesis delegations in the delegation state, unused, as the ledger does.
- Before Allegra an account with a leader and a member reward keeps the leader reward (it kept the member reward).
- Reward prefilter confirmed against the source (registered at the first block after 4k/f); now applied when the update is created, as the reference shows it.
- Mary scaled minimum output: divisor 27 and distinct asset names; Allegra and Mary only call ada-only outputs too small, as the reference does.
- Output value limit: 4000 serialised bytes in Allegra and Mary, `maxValueSize` from Alonzo, measured on the canonical encoding.
- MIR: transfers and negative entries need protocol 5, later certificates replace earlier ones before that, deregistration keeps pending entries.
- Protocol version 11: a pool registration may not take a VRF key hash another pool holds or has
  scheduled by a re-registration (`VRFKeyHashAlreadyRegistered`, `hardforkConwayDisallowDuplicatedVRFKeys`).
- The mempool refuses votes of unelected committee members before protocol version 11, as the
  reference node's mempool does; from 11 the GOV rule does (`ConwayMempoolFailure`).

### Build, CI and structure
- Models cross-check against cuddle, upstream's Haskell CDDL tool (roadmap K10): `docker/cuddle.Dockerfile`
  builds cuddle 1.8.1.0 (pinned GHC, Hackage index and base images), `scripts/cddl_crosscheck.py` runs
  cuddle-generated terms through the generated codecs (byte-exact re-encoding, plus indefinite-length
  variants), our sampler's terms through `cuddle validate-cbor`, and compares the rule sets of all 27
  vendored CDDL files; known divergences are asserted in `models-spike/cuddle-allowlist.tsv`.
  Workflow `cuddle-crosscheck.yml` (on CDDL and generator changes, and nightly). Found and fixed: the
  generator lacked the RFC 8610 prelude (`bigint`, `encoded-cbor`, `tdate`, ...), which Byron's CDDL uses.
- Dependabot alerts: http4s 0.23.30 to 0.23.37 (ember request smuggling and HTTP/2 denial of
  service), circe 0.14.10 to 0.14.16 (brings jawn-parser 1.7.0), fs2 3.11.0 to 3.14.0 (the 3.12.2
  fix; http4s 0.23.37 resolves 3.14.0), cats-effect 3.6.3 to 3.7.1, cats-mtl 1.6.0 to 1.7.0. fs2 3.13
  deprecates `UnixSockets`, `serverResource`, `client` and `remoteAddress`; the bearers now use
  `Network.bind`, `bindAndAccept`, `connect` and `peerAddress`. The jackson, jsoup and jline alerts
  come from the Scala 3.3.8 scaladoc and REPL tool dependencies (not shipped, not on the node
  classpath); no 3.3.x release updates them.
- CI: gitleaks secret scan of the full history (`.gitleaks.toml`, 182 known generic-api-key false
  positives allowlisted by path and commit, 0 findings), dependency review on pull requests and the
  sbt dependency graph submitted on pushes to main (roadmap A8).
- The signature-verdict caches are no longer process-global: `VerificationCache` is created in a
  `Resource` in `Main` and reaches the pure rules as `Verdicts` through `PraosConfig.verdicts` and
  `TxRules.Env.verdicts`; `Verdicts.Direct` is the uncached specification (roadmap M3). A 21 minute
  Preview follow from a fresh db (6 CPUs) ran epochs 0 to 107 at 342 blocks/s (docs/performance.md:
  349 for the first 100 epochs), 0 errors; devnet soak 480 s, 23 comparisons, 0 mismatches.
- `PlutusCases` moved to `lab.node.tools` (roadmap M4).

### Devnet
- `devnet.py ntc`: the Scala node's node-to-client interface against the reference's on a loaded
  cluster: LocalStateQuery byte for byte at the same block (707 of 707 answers, 14 rounds), 23 of 24
  `cardano-cli conway query` commands, 18 refused transactions as raw `ApplyTxErr` bytes and as
  `cardano-cli` text, and a valid submission (`scripts/devnet/ntcqueries.py`, `ntccases.py`,
  `scenarios/ntc.py`; `ntcquery.py` speaks LocalTxSubmission and host sockets).
- Recorded reference answers for the activity-independent queries (`fixtures/devnet/lsq`, replayed by
  `QueryGoldenSuite`) and the reference's rejection bytes (`PredicateFailureCborSuite`); the three
  recorded refusals of `fixtures/devnet` are compared with the text `cardano-cli` printed.

- `--scala-cpus N` for `follow`, `soak`, `relay`, `pool` and `e2e` (the Scala container's CPUs) and
  `--max-starvation-warnings N`; the `pool` summary reports `scheduling` (starvation warnings, worst
  timer lateness, longest CPU-bound step) and `slotClock` (when the node logged its forged blocks and
  received the others' relative to their slots; a run whose clock is a second behind the network's now
  says so instead of only listing missed leader slots).
- `e2e`: three Shelley epochs, 1 ada minimum, pool margin 0.1, cross-paying pools, boundary load and expected-rejection checks.
- `soak --governance-extended` (`govload --extended`, `scripts/devnet/govextra.py`): pool reward accounts
  with default votes, committee election with a term limit, a member that expires and `committeeMinSize`,
  a Plutus V3 script as stake credential, DRep and reward account, and the protocol-11 refusals (disjoint
  reference inputs, unelected committee voters, taken VRF key hashes) checked against the reference
  node's predicate failures (roadmap B2 to B5). `e2e` runs a light subset in its Conway epochs.
- Refused transactions are kept and replayed through the Scala rules (`DevnetRefusedTxSuite`,
  `fixtures/devnet`).

## [0.1.0] - 2026-10-11 - research preview

The uninterrupted Preview genesis-to-tip sync ran on commit d66b216 (4,748,226 blocks in 3h36m42s,
0 error events, final tip identical to Koios; see [docs/performance.md](docs/performance.md)). The tag
is on a later commit (hygiene, docs, CI, forge-loop reporting; no intended behaviour change). On the
tagged code `check` (494 node tests), soak (24/24 and 30/30), e2e (every era matched), relay and
pool all passed; the pool scenario now asserts every scheduled leader slot against
`cardano-cli query leadership-schedule` (92 scheduled, 92 forged, 0 missed).

### Foundation
- Cleanup to a tagless-final code base: pure `core` and `network`, fs2 `network-runtime`, `node`
  with cats-mtl typed errors; research modules, scripts and documents removed (61f9119, history at f4ce3f8).
- Network runtime rebuilt on fs2 as algebras over one multiplexed connection (be15eed).

### Consensus
- Praos header validation (VRF, KES, operational certificates, nonces) and TPraos validation with the
  overlay schedule; Preview followed from genesis (592399e).
- Era-aware headers and blocks for Byron, TPraos and Praos (df0bbb4); Byron to Conway follow with
  the Byron signature check (7b4b08f).
- Praos chain selection over several peers (f2f8446).
- Block forging: ECVRF draft-03 proving, Sum6 KES generation, signing and evolution, leader check,
  block assembly from the mempool, adoption (840bbaf, d5a19b2).

### Ledger
- Full Shelley to Babbage ledger: UTXO/UTXOW/DELEGS/PPUP, MIR, rewards, epoch boundary (7300951, 5de16ae).
- Plutus V1 to V3 phase 2 via Scalus behind a `ScriptEvaluator` algebra, own script contexts and
  script data hash (fa907ac, 990f620, abf6b1e).
- Conway: transaction rules, scripts, certificates, governance, ratification, enactment, DRep
  pulser (946d787, 788f51c, d7e0de0); governance snapshot codecs (ef68faf).
- Block execution-unit limits validated on received blocks and enforced when forging.
- Fixes found by syncing Preview: pledge owners' stake (74a01ae), pointer addresses at the Conway
  boundary (f133dcd), repeated redeemer purposes (d8dc65d, 32bc14f), Plutus V1 with reference inputs
  (d8315bd), `NewConstitution` context (5bc16a2), CBOR nesting depth (429c8e0); reward-update
  activation (1ff2934).

### Network and relay
- Node-to-node servers: duplex mux, handshake, ChainSync, BlockFetch, KeepAlive, TxSubmission2 over
  TCP (0f27403); the node serves its own chain and holds transactions in a mempool (b10f102).
- Node-to-client LocalTxSubmission over a Unix socket (2cc9840).
- BlockFetch ranges cut by body size and intersection candidates thinned (7351502).

### Storage and scale
- `ImmutableStore`, `LedgerSnapshots`, restart recovery (b5d46ae); snapshot reader fix (ae5eb81).
- UTxO set behind `UtxoStore` with RocksDB and memory interpreters, incremental stake (55bf406).
- Compact array-backed bytes (5a8a3f9); parallel verification ahead of the rules (9225835); pipelined
  peer (c139494, 8297223); cached per-block recomputation (eecdc02, 3cdac59).

### Tooling and evidence
- `devnet.py`: `up-eras` and `e2e` (c7054c9, 7b4b08f), per-era load and comparison (3a0da52),
  Conway governance load (7d360ba), `relay` (ac025df), `pool` (b5fbec3); fixtures `fixtures/eras`
  and Preview governance/Plutus cases.
- Documentation overhaul: README, architecture, running, compatibility matrix, performance,
  findings, roadmap; CONTRIBUTING, SECURITY, CODE_OF_CONDUCT; issue and pull request templates;
  complete `NOTICE`.

### Known limits
See [docs/compatibility.md](docs/compatibility.md) and [docs/roadmap.md](docs/roadmap.md): Byron
transactions, preprod and mainnet, full node-to-client queries, Mithril import, P2P governor, KES
agent and opcert issuing, TPraos forging, unaudited.
