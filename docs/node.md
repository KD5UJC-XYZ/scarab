# Scala node (`node` module)

A Cardano node core written as tagless-final cats-effect programs. It follows a peer from genesis,
validates TPraos and Praos headers and applies the ledger rules of every Shelley-based era (see
"Full ledger" below). Every retained state can be queried,
so it can be compared byte for byte against a reference `cardano-node`.

## Architecture

| Layer | Where | Style |
|---|---|---|
| Errors | `NodeError.scala` | Closed hierarchy: `PeerError` (recoverable), `ChainError`, `ValidationError`, `ConfigError`. Raised via cats-mtl `Raise`, handled via lexically scoped `Handle.allow` |
| Peer | `Peer.scala` | `Peer[F]` algebra (intersect / next / fetch) with a node-to-node v14 interpreter over `network-runtime` |
| Decoding | `Header.scala`, `Block.scala`, `network/Era.scala`, `network/NtNHeader.scala` | Era-aware: `Header.Shelley` (TPraos or Praos `HeaderVrf`, Shelley-Conway) and `Header.Byron`; blocks decoded per era with the body hash recomputed from original bytes. Real fixtures under `fixtures/preview/` |
| Chain | `VolatileChain.scala` | Pure last-k chain of `(block, state)`; rollback restores the exact retained state |
| Follower | `Follower.scala` | Program over `Stateful[F, VolatileChain[S]]` and `BlockRules[F, S]`; `supervise` reconnects on `PeerError` |
| Consensus | `consensus/Praos.scala`, `consensus/TPraos.scala`, `consensus/OCertRules.scala` | VRF leader check, KES, opcert counters, nonce evolution (evolving / candidate / epoch / lab / last-epoch-block). TPraos adds the overlay schedule, two VRF certificates and the 512-bit leader value |
| Ledger | `ledger/` | Pure functions: transaction decoding, UTXOW/UTXO/CERTS rules, deposits, withdrawals, the EPOCH rule (rewards, SNAP, POOLREAP, obligation, dormant epochs), the reward calculation, and from Conway GOV, RATIFY, ENACT and the DRep pulser |
| Block rule | `LedgerRules.scala` (`NodeRules`) | Tick ledger and Praos to the block's epoch, validate the header, apply the body |
| UTxO set | `ledger/Utxo.scala`, `storage/UtxoStore.scala`, `storage/RocksUtxoStore.scala` | The UTxO set behind a `UtxoStore[F]` (memory or RocksDB); the ledger state holds a pure `Utxo` view: the last changes plus the entries the block needs. See "Scale" below |
| Query | `Query.scala` | `ChainView[F, S]` plus a loopback http4s server: `GET /tip`, `GET /state?slot=&hash=` |

All configuration (epoch geometry, k, f, KES parameters, protocol parameters, initial UTxO and
staking) is read from the genesis files. Nothing assumes a particular network. Ledger arithmetic
uses an exact `Rational` with Haskell `floor` semantics.

Laws and properties: the `Ref`-backed `Stateful` passes the cats-mtl Stateful laws, `Rational`
passes the cats-kernel Order laws, and the ScalaCheck properties cover rollback, reward bounds and
exactness. Scripted-peer tests cover forks, reconnects and fatal rule violations.

## Era history and Byron (devnet from Byron)

`EpochGeometry` is the node's era history: a Byron prefix (`ByronSpan`: epochs, 10k slots per
epoch, its own slot length) followed by the Shelley-based eras, which share one epoch length.
Epochs and slots are absolute, so `firstSlot(epoch)`, `epochOf(slot)`, the stability windows, the
TPraos overlay, KES periods and PPUP deadlines all work across the boundary. `NodeSetup` derives
it from `shelley-genesis.json`, `byron-genesis.json` and the node configuration
(`Test<Era>HardForkAtEpoch`; an absent `TestShelleyHardForkAtEpoch` means the chain starts in
Shelley, as on Preview). Hard forks decided on chain by update proposals (mainnet) are not
modelled.

- **Chain model:** `VolatileChain.origin` is the hash the first block names (the Byron genesis
  hash: `ByronGenesisHash` from the configuration, else Blake2b-256 of the canonical JSON of
  `byron-genesis.json`). A Byron epoch-boundary block repeats its predecessor's block number and
  the main block after it may share its slot.
- **Byron rules** (`consensus/ByronRules.scala`): protocol magic, linkage, epoch/slot order, the
  slot leader is a genesis key and the heavy delegation is the genesis one, and the delegate's
  Ed25519 signature over `"01" ++ issuer ++ 0x09 ++ cbor(magic) ++ [prev, proof, slot, difficulty,
  extra]` verifies. A header delegated differently (on-chain certificates are not tracked) is
  accepted structurally and reported `unchecked`; the delegation certificate's own signature and
  the block body proofs are not checked. The JSON log has an `epochValidated` line per Byron epoch
  (`signatureVerified`, `signatureUnchecked`).
- **Byron to Shelley:** the Praos state starts with every nonce equal to the Shelley genesis hash
  and is untouched by Byron blocks; the first Shelley block ticks it to the first Shelley epoch.
  Ledger-lite starts at that epoch.
- **Logging:** every era transition is an `eraTransition` line (`from`, `to`, `epoch`, `slot`,
  `block`), the first one from no era; `extended` lines carry the block's `era`.

Tests: `EraTransitionSuite` replays a committed capture of the `up-eras` cluster
([`fixtures/eras`](../fixtures/eras/README.md)) through the node's block rules and a scripted
follower (no Docker); `EpochGeometrySuite` has the era-history properties.

```sh
python3 scripts/devnet.py e2e          # up-eras + follower from Origin + era load + ledger comparison (about 10 minutes)
python3 scripts/devnet.py --name csl-eras2 e2e   # a second cluster alongside another run
```

`e2e` starts the era-walking cluster and the Scala follower as soon as the nodes answer, so the
Scala ledger tracks the reference through every era. It fails unless: the `eraTransition` lines are
exactly Byron, Shelley, Allegra, Mary, Alonzo, Babbage and Conway at the epochs in `eras.json` and
the configuration, every era has applied blocks, all Byron signatures verified, there are no
validation or fatal errors, and **every ledger comparison matched** (at least `--min-comparisons`
per Shelley-based era). It writes `e2e-summary.json` (blocks and header verdicts per era,
transitions, per-era comparison counts, first mismatches, load counts) and `compare.jsonl` (every
comparison) to the run directory, and removes the containers unless `--keep`.

Era load (`scripts/eraload.py`, transactions built and signed by `scripts/eratx.py` because
cardano-cli 11 builds Conway transactions only): Shelley transfers, stake key registration and
delegation, a new pool, pool re-registration (future parameters), MIR from the reserves, a genesis
delegation certificate, withdrawals; Allegra validity intervals, auxiliary data, a timelock script
address funded and spent, a pool retirement; Mary minting and burning under native-script policies
(one timelocked), multi-asset transfers, MIR from the treasury, stake deregistration; Alonzo
collateral inputs, datum-hash outputs, required signers, network id; Babbage inline datums,
reference-script outputs, reference inputs, collateral return; Conway the cardano-cli `load`
(transfers, registration, delegation, withdrawals, deregistration) and the governance and Plutus V3
scenarios described under "Conway load" below. Plutus V1 and V2 scripts are still a TODO in
`eraload.py`. Rejected submissions are logged.

The e2e run follows seven Conway epochs: the bootstrap-phase governance first (protocol 9), then the
hard fork to 10 and the full governance. The Conway ledger is compared with the reference through the
CLI (all queries of one comparison run in parallel between two tip reads), including `gov-state`,
`committee-state` and `drep-stake-distribution` (see below).

Comparison: cardano-cli 11.2.3 refuses `query ledger-state` and `query protocol-state` before
Conway, so `scripts/ntcquery.py` speaks LocalStateQuery to the node directly (through `socat` in the
reference container) and `scripts/eraview.py` projects `DebugNewEpochState`, the whole UTxO and
`DebugChainDepState` of Shelley to Babbage onto the node's `/state` shape, with the protocol
parameters from `cardano-cli query protocol-parameters`. Compared per point: UTxO (multi-assets,
datums, reference scripts), fees, deposits, treasury, reserves, accounts, pools with future
parameters and retirements, genesis delegations (current and future), pending and future update
proposals, MIR, mark/set/go snapshots, blocks made, the reward update when complete, protocol
parameters, pool distribution, nonces and opcert counters. Each epoch is compared right after the
boundary, after the era batch, mid-epoch and shortly before the next boundary. Byron is not
compared: the CLI cannot query a Byron ledger and the node keeps no Byron UTxO.

## Full ledger for Shelley to Babbage

`NodeRules` applies the full ledger to every Shelley-based block; era behaviour follows the protocol
major version of the parameters in force (Allegra 3, Mary 4, Alonzo 5, Babbage 7, Conway 9).

- **Decoding** (`ledger/Tx.scala`, `Scripts.scala`): transaction bodies of every era (keys 0-18),
  witness sets (vkey, bootstrap, native and Plutus scripts, datums, redeemers), outputs in legacy and
  map form (inline datums, reference scripts), certificates 0-6 (genesis delegation, MIR).
- **Rules** (`ledger/PreConwayRules.scala`): UTXO (fee including the Alonzo execution-unit price,
  min-UTxO per era, validity interval, sizes, network ids, collateral), UTXOW (vkey and Byron
  bootstrap witnesses, native scripts, required signers, redeemers and datums, auxiliary data hash),
  DELEGS (registration, delegation, pools, genesis delegation, MIR), PPUP (`PpupRules`). The fee size
  counts the array header, body, witnesses and auxiliary data, not the `is_valid` byte.
- **Epoch boundary** (`ledger/Epoch.scala`): reward application (pre-Babbage prefilter against the
  accounts registered when the calculation started, so unclaimed rewards return to the reserves),
  MIR, SNAP with pointer-address stake, pool re-registration and retirement, parameter update
  adoption, and the reward update with `d`-dependent performance and the previous epoch's
  parameters. The ledger creates the reward update with the first block that arrives after
  `4k/f` slots into the epoch (`LedgerState.rewardDraft` is computed at the boundary and activated by
  `Epoch.tick`): an epoch with no such block pays no rewards and keeps its fee snapshot. The `set` snapshot is the pool distribution for the leader checks, so
  `LeaderCheck.Threshold` replaces `Unchecked`; the node stops if the total supply ever differs
  from the maximum supply at an epoch boundary.
- **Plutus (phase 2)**: every redeemer is evaluated (`ledger/Phase2.scala`) and the block's
  `is_valid` flag must equal the verdict, in both directions (`ValidationTagMismatch`); a valid
  transaction is applied and an invalid one consumes its collateral. See "Plutus evaluation" below.
  The script data hash is recomputed (`ledger/ScriptIntegrity.scala`) and a mismatch is a hard
  error (`PPViewHashesDontMatch`).
- **Conway** continues from the Babbage state through `Translate.toConway` (Conway parameters,
  committee and constitution from the Conway genesis, empty DRep state, pre-Conway update/MIR/pointer
  state dropped). The Conway rules are described in "Conway" below.
- **Bootstrap mode** remains available (`NodeRules(..., bootstrap = true)`): headers only, with the
  leader threshold reported as unchecked.
- **Nonce windows** (verified on Preview): candidate nonce freezes `3k/f` before the epoch end
  through Babbage; Conway and the devnet use `4k/f`. TPraos overlay formulas follow cardano-ledger
  `classifyOverlaySlot` (`position = ceiling(s * d)`, active when `position mod floor(1/f) = 0`).
- **Read-ahead:** while the peer is far ahead, ChainSync requests are pipelined and each batch is
  fetched with one BlockFetch range (`Peer.nodeToNode`, `ChainSyncClient.requestNextBatch`).

Verified: Preview from genesis through epoch 74 (6.4M slots, protocol 6 to 8) with every non-overlay
header threshold-checked and no rule failure; 315,717 Plutus transactions evaluated successfully and
55 evaluated to failure, each equal to the block's `is_valid` flag, and 333,607 script data hashes
recomputed and equal. The Byron-to-Conway era fixture (`EraTransitionSuite`) also passes.

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
# then run lab.node.Main follow --genesis-dir <preview config dir> --peer preview-node.play.dev.cardano.org:3001
```

## Conway

`ledger/ConwayRules.scala` is the LEDGER rule for protocol versions 9 and later (Preview runs 11).
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

Not modelled: the VRF key hash uniqueness of protocol 11 (it only rejects transactions), the
unelected-committee vote check is implemented but untested against a reference, and block execution-unit
limits.

## Plutus evaluation

`lab.node.plutus` holds the evaluator algebra and `lab.node.ledger` the ledger side.

- **Algebra** (`plutus/ScriptEvaluator.scala`): `ScriptEvaluator[F[_]]` has one method,
  `evaluate(EvalRequest): F[EvalResult]`. A request is the language (`PlutusV1`, `V2`, `V3`), the
  protocol major version (it selects the builtin semantics), the script bytes, the data arguments,
  the language's cost model and the declared budget. The result is `Success(spent, logs)` or
  `Failure(reason, spent, logs)` with a closed `EvalFailure` (`Decode`, `Unsupported`,
  `OutOfBudget`, `Script`).
- **Interpreter** (`plutus/ScalusEvaluator.scala`): the UPLC machine and builtins of Scalus (Apache-2.0,
  `org.scalus:scalus_3:1.2.0`); nothing else of Scalus is used by the node. BLS12-381 runs on blst
  (`foundation.icon:blst-java`) and secp256k1 on `scalus-secp256k1-jni`; both bundle linux x86_64
  libraries that the JVM extracts to its temporary directory, and `ScalusEvaluatorSuite` evaluates a
  BLS pairing and an ECDSA verification (also run inside the Temurin 21 Docker image).
- **Pure or effectful**: evaluation is a deterministic function of the request, so the ledger rules,
  which are pure functions, take a `ScriptEvaluator[Id]` (`TxRules.Env.evaluator`, default
  `ScalusEvaluator.default`). The interpreter only memoises decoded scripts and machines per cost
  model. `ScalusEvaluator.suspended[F]` is the same interpreter in `F` (`Sync.interruptible`) for a
  node that wants evaluation off the main thread. The machine runs on a thread with a large stack.
- **Script context** (`ledger/ScriptContexts.scala`, `ledger/Purpose.scala`): ours, written against
  the ledger's `Plutus/TxInfo.hs` modules. V1 and V2 `TxInfo` (V1 rejects inline datums, reference
  scripts and reference inputs; V2 adds reference inputs, redeemers and a datum map; withdrawals in
  Plutus credential order; Byron outputs are dropped before Babbage and an error from it), V3 `TxInfo`
  and `ScriptContext` (plain transaction ids, ledger-order withdrawals, redeemer and `ScriptInfo`,
  votes, proposals, donation and treasury). Validity intervals are converted to POSIX milliseconds with
  the era history and the system start (`EpochGeometry.slotStart`); the upper bound of an interval
  without a lower bound is inclusive before Conway and exclusive from it. Purposes are spending,
  minting, certifying, rewarding, voting and proposing, in the ledger's orders.
- **Cost models**: per language from the protocol parameters in force. The genesis cost model is a
  map of named integers whose canonical order is the sorted name order, not the file order; the Conway
  genesis adds PlutusV3. A redeemer's declared units are its budget, the transaction total is bounded by
  `maxTxExUnits`.
- **Script data hash** (`ledger/ScriptIntegrity.scala`): `blake2b-256(redeemers ‖ datums ‖ language
  views)` over the witness set's own bytes; datums are omitted when empty; PlutusV1's cost model is a
  byte string wrapping an indefinite list under the byte-string key `0x4100`, V2 and V3 are plain, keys
  in canonical order (V2, V3, then V1); no languages is `0xa0`.
- **Counters**: `ledgerEpoch` log lines carry `phase2.evaluatedSuccess`, `phase2.evaluatedFailure`,
  `phase2.scripts` and `scriptIntegrityHashChecked`.
- **Recording cases**: `follow --plutus-cases <dir>` writes self-contained JSON cases (transaction,
  spent and referenced outputs, cost models, clock samples) for a few transactions of each kind;
  `fixtures/preview/plutus` holds a selection that the tests replay.

## Persistence (`follow --db <dir>`)

Modelled on the ouroboros-consensus ChainDB, as tagless-final algebras in `node/.../storage/`:

| Part | Where | Role |
|---|---|---|
| `ImmutableStore[F]` | `ImmutableStore.scala` (`FileImmutableStore`) | Append-only raw blocks older than `k`: `append`, `sync`, `tip`, `get(point)`, `byBlockNo`, `stream(after)` |
| `LedgerSnapshots[F, S]` | `LedgerSnapshots.scala` (`FileLedgerSnapshots`) | Atomic, checksummed snapshots of the full `NodeState` at an immutable point; newest valid wins |
| `ChainStorage` | `ChainStorage.scala` | Opens both, restores the newest usable snapshot, replays the stored blocks after it, and hands the follower a `ChainPersistence` hook |
| Volatile part | `VolatileChain` (memory) | The last `k` blocks are not stored; after a restart the peer re-sends them from the immutable tip |
| `Codec[A]` | `Codec.scala`, `NodeCodecs.scala` | Binary codecs, one per type, composed. Case classes and enums are derived from their `Mirror`, so a field added to `LedgerState` is persisted with no codec change |
| `StorageError` | `NodeError.scala` | `Corrupt`, `Io`, `VersionMismatch`, `Missing`, `OutOfOrder`; raised through `Raise[F, StorageError]`, IO exceptions are mapped at the file boundary only |

```sh
follow --genesis-dir <dir> --peer <host>:<port> --db <dir> [--snapshot-every <N|Nslots|Nepochs>] [--keep-snapshots N]
```

Without `--db` nothing changes: the node is in-memory and replays from genesis. With `--db` the
JSON log gains `immutableOpened`, `snapshotRestored`, `snapshotSkipped`, `snapshotWritten`,
`snapshotFailed`, `replayStarted`, `replayProgress` and `replayFinished`.

**Immutable store format** (`<db>/immutable/`). `format` names the version and chunk size
(`chunkSlots`, 21600); a different chunk size is a `VersionMismatch`. Blocks of slots
`[c * chunkSlots, (c + 1) * chunkSlots)` go to `<c>.chunk` (concatenated raw blocks) and
`<c>.index`: the magic `CSLIDX01` then 68-byte big-endian entries
`offset u64 | length u32 | crc32 u32 | slot u64 | blockNo u64 | hash[32] | flags u8 | zero[3]`
(`flags` bit 0 marks a Byron epoch-boundary block). Appends write data then index without syncing;
`sync` (before every snapshot, at chunk roll-over and on close) forces both and the directory.
On open the newest chunk is validated entry by entry (contiguous offsets, data in bounds, CRC) and
both files are truncated to the last good entry; a chunk left with no good entry is removed, and
data files without an index are deleted. Older chunks were synced at roll-over and are trusted.

**Snapshot format** (`<db>/snapshots/snapshot-<slot>-<blockNo>.bin`, the newest `--keep-snapshots`,
default 3, are kept): `"CSLSNAP1" | format version u32 | schema fingerprint[8] | payload | SHA-256`.
The payload is the codec of `StateSnapshot(anchor, NodeState)`; integers are LEB128 varints,
`BigInt` is length-prefixed two's complement, containers are a count then the elements, sums a
variant index then the variant. The fingerprint is the SHA-256 prefix of the codec's `shape` (the
names and types of every persisted field), so changing any state type makes older snapshots fail
with `VersionMismatch` rather than decode wrongly. A write goes to `*.tmp`, is fsynced, renamed
(atomic) and the directory is fsynced; leftover `*.tmp` files are removed on open. Snapshots are
written by a background fiber from the immutable (structurally shared) anchor state, so following
does not pause.

**Recovery.** Snapshots are tried newest first. One that fails its checksum, decodes badly, has
another schema or names an anchor missing from the immutable store is reported (`snapshotSkipped`)
and the next older one is tried; with none usable the stored blocks are replayed from genesis. The
stored blocks after the chosen snapshot are re-applied through the same `NodeRules` with no
network, then ChainSync resumes with the replayed anchor as the intersection candidate.

Tests (`node/src/test/.../storage`): round-trip properties for every persisted type from generators
derived by the same `Mirror`s (so new fields are covered automatically), prefix/trailing/random-byte
rejection, golden encodings and a golden digest and schema fingerprint, the store's crash cases
(torn data, torn index entry, garbage, flipped bytes, lost chunk, every cut point of the newest
chunk), snapshot corruption at many points and fallback, and restart equivalence over the Byron to
Conway capture (`fixtures/eras`) through the real follower.

## Scale: the UTxO set on disk and a pipelined sync

Syncing Preview with the whole ledger in memory fell from 350 blocks/s to 20 blocks/s by block 527k
(UTxO of 1.25M entries, 2.7 GB live, 335 MB snapshots). The measurements are in
`.workspace/notes/perf.md`; the changes, in the order they paid off:

**Compact bytes.** `lab.cbor.Bytes` was a `Vector[Byte]` (boxed, about 6 times the size, hashing and
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
- `resident`: the read set. `NodeRules` decodes the block once, collects the inputs, reference inputs
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
functions of the block (the VRF also of the epoch nonce). `Verification.warm` runs them for a whole
fetched batch in parallel and keeps the verdicts; the rules ask for the same verdicts through
`Verification.witness`, `opCert`, `praosVrf` and `tpraosVrf`, which return the remembered one (once)
or compute it. The VRF is warmed with the epoch nonce the rules last saw, keyed by the derived alpha
string, so a stale nonce is only a miss. Replay of the stored blocks warms the next 64 while the
current ones are applied; the peer warms each fetched range.

**A pipelined peer.** `Peer.nodeToNode` runs a producer fiber that keeps up to three batches of 64
in flight: it requests the headers of the next batch while the blocks of earlier ones are being
fetched and verified, and publishes them in order; the consumer applies a batch meanwhile.
`--read-ahead N` changes the batch size (the public relay timed out chain-sync at 256).

```sh
follow ... --db <dir> [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--read-ahead N]
```

`rocksdb` is the default with `--db`; without it the node uses the memory store. The query server
completes the UTxO from the store for `/state`.

## Running a local soak (Docker only)

Prerequisite: the `cardano-reference-11.1.3:local` image (see [reference image](reference-image.md)).

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
python3 scripts/devnet.py soak --seconds 480     # up + follow + load + compare
python3 scripts/devnet.py down                   # remove containers and run data
```

`devnet.py` starts a 3-pool `cardano-testnet` cluster (k = 5, f = 0.05, 500-slot epochs of 0.1 s)
and the Scala node, which shares the reference container's network namespace. It also runs a
load generator that sends ADA transfers, stake registration with pool and DRep delegation,
re-delegation, full-balance reward withdrawals and deregistration. Every `--every` seconds it
reads the reference tip, queries `ledger-state`, `utxo --whole-utxo` and `protocol-state`, then
re-reads the tip. When the tip is unchanged, it fetches the Scala node's state at exactly that
block and diffs everything. Run data lives in `${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/<name>`.
The node runs from a frozen copy of the compiled classes.

### Conway load and comparison

`devnet.py govload` (started by `soak` next to `load`; `e2e` runs the same code in its Conway epochs)
builds and signs Conway transactions with `eratx.py` and drives these stages, one pass per epoch (a
stage that fails is retried, then given up and logged): fund four wallets; register stake credentials
with pool and DRep delegation (certificates 9 to 13) and two DReps; lock funds at a Plutus V3
script address (inline datum, no datum, and a reference-script output), spend both and mint with the
V3 script (redeemer map, script data hash with the V3 cost model) and mint again from the reference
script; re-delegate to a registered DRep; propose an info action, a parameter change, a treasury
withdrawal, a committee update and a constitution (in the bootstrap phase an info action, a hard
fork to 10 and two parameter changes) and vote with the genesis DReps, the new DReps, the three
pools and, later, the committee; authorise the committee's hot keys; a second round of a treasury
withdrawal and the hard fork to 11 that need the committee; a no-confidence action; a committee
elected while there is none; DRep update and retirement, committee resignation, stake
deregistration with refund; a treasury donation naming the current treasury. The genesis committee
of `cardano-testnet` is empty with threshold 0, so with committee `minSize` 0 the committee accepts
everything until the first update.

Besides the existing ledger-state comparison, every comparison diffs the governance projection
(`scripts/govview.py` maps the CLI JSON onto `Projection.gov`): the proposals with their votes,
deposits, return accounts, expiry and action contents, the committee and its threshold, the
constitution, the committee state (hot keys, resignations), the DRep stake distribution (including
always-abstain and always-no-confidence) and the ratify state (enacted and expired actions, the delay
flag, the next committee, constitution and previous action ids, the protocol version).

## Verified result (2026-10-11)

**Soak.** `devnet.py soak --seconds 480` from genesis matched the reference exactly at **24/24**
comparison points (epochs 0 to 9, 245 blocks, no error). The governance load ran every stage within
those ten epochs, including a hard fork to protocol 11 (Plutus V3 spend, mint and reference-script mint
included). A longer run (900 s, epochs 0 to 18, 456 blocks) matched at **41/41**; it went through the
hard fork to 11, a no-confidence action and the election of a new committee while there was none.

**E2E.** `devnet.py e2e` (Byron to Conway, seven Conway epochs after the transition, 751 Conway blocks)
passed with every comparison matched:

| era | compared | mismatched |
|---|---|---|
| Shelley | 4 | 0 |
| Allegra | 4 | 0 |
| Mary | 4 | 0 |
| Alonzo | 8 | 0 |
| Babbage | 8 | 0 |
| Conway | 30 | 0 |

The Conway epochs start at protocol version 9 (bootstrap phase): info, parameter-change and hard-fork
proposals with pool and DRep votes, the hard fork to 10, then committee election, treasury withdrawals,
no confidence and DRep and committee certificates.

**What each comparison covers:**

- whole UTxO, fees, deposits, treasury and reserves
- accounts, pools and DReps
- mark/set/go snapshots and the fee snapshot
- block counts, the reward update and the pool distribution
- all five Praos nonces and the opcert counters
- Conway: proposals and their votes, committee and committee state, constitution, the DRep stake
  distribution and the ratify state

**Preview.** Conway blocks of Preview (protocol 10 and 11) with every kind of proposal, votes and the
real guardrails script are decoded and replayed state-independently (see `fixtures/preview/README.md`):
18 transactions run through phase 2, all agree with `is_valid`, the recomputed script data hash and
Scalus's script contexts. A full Preview sync into Conway (several million blocks) was not attempted.

## Not yet implemented

- **Byron:** Byron transactions and body proofs, delegation by on-chain certificates, and the Byron slot-leader schedule.
- **Conway details:** the VRF key hash uniqueness of protocol 11 (it only rejects transactions) and the
  block-level execution-unit limit.
- **On-disk ledger:** only the UTxO set is on disk (see "Scale"); the other ledger maps (accounts, pools, DReps, the three stake snapshots) are in memory and in the snapshot file. The volatile blocks are not persisted.
- **Peers:** a single upstream peer only.
- **Rewards:** non-myopic pool ranking.
- **Production:** block production and a mempool.
