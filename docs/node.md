# Scala node (`node` module)

A Cardano node core written as tagless-final cats-effect programs. It follows one or more peers from genesis,
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
| Follower | `Follower.scala`, `consensus/ChainSelection.scala` | Program over `Stateful[F, VolatileChain[S]]` and `BlockRules[F, S]`: one candidate chain per peer session, Praos chain selection, adoption on a copy of the chain; `supervise` reconnects on `PeerError` |
| Relay | `relay/`, `mempool/`, `network-runtime` servers | Serves the node's chain (ChainSync, BlockFetch), collects and offers transactions (TxSubmission2) and accepts local submissions; see "Relay" below |
| Forging | `forge/`, `Follower.adoptForged`, `core/.../vrf/Draft03Prover`, `core/.../kes/Sum6KesSigning` | Praos block production: VRF proving, KES signing and evolution, block assembly from the mempool; see "Block production" below |
| Consensus | `consensus/Praos.scala`, `consensus/TPraos.scala`, `consensus/OCertRules.scala` | VRF leader check, KES, opcert counters, nonce evolution (evolving / candidate / epoch / lab / last-epoch-block). TPraos adds the overlay schedule, two VRF certificates and the 512-bit leader value |
| Ledger | `ledger/` | Pure functions: transaction decoding, UTXOW/UTXO/CERTS rules, deposits, withdrawals, the EPOCH rule (rewards, SNAP, POOLREAP, obligation, dormant epochs), the reward calculation, and from Conway GOV, RATIFY, ENACT and the DRep pulser |
| Block rule | `LedgerRules.scala` (`NodeRules`) | Tick ledger and Praos to the block's epoch, validate the header, apply the body |
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

## Relay (`follow --listen`, `--local-socket`)

With `--listen <host>:<port>` the node accepts node-to-node connections (versions 14 and 15) and
serves its own chain; with `--local-socket <path>` local clients can submit transactions over a Unix
socket. Everything it serves comes from its own validated chain and mempool, never from what a peer
announced. The pieces, bottom up:

**Multiplexer and duplex** (`network-runtime/Mux.scala`). Every channel has a side per
mini-protocol, `Initiator` (client) or `Responder` (server), and an SDU carries the mode bit of its
sender. `Mux.Config(duplex = true)` registers both sides of every protocol on one bearer, so a
client and a server of the same protocol number never see each other's traffic;
`mux.channel(protocol, role)` picks the side. Without `duplex` only the side opposite to the peer's
is registered and the other mode bit closes the connection. `Mux.flush` waits until everything
queued has been written, used so that a handshake refusal is not lost when the connection closes.

**Handshake** (`HandshakeServer`). Reads the proposal, answers through the pure
`Handshake.responder` (highest common version of 14 and 15 here, 16 to 23 for node-to-client) and
reports the agreed data. `initiatorOnly` in the agreed data is false only if both sides allow a
duplex connection. `NodeToNode.over(..., serve = Some(handlers))` offers InitiatorAndResponder and
runs our servers next to our clients when the peer agrees; `NodeToNodeServer.accept` is the inbound
side (the node's inbound connections are served, and duplex is accepted, but it does not start
clients on them).

**Servers.** `ChainSyncServer.run` answers FindIntersect and RequestNext in order (so pipelining
clients work), sends AwaitReply at the tip and then waits for the chain to change. It reads through
a `Source` that holds one client's cursor. `BlockFetchServer` streams an inclusive range from a
`Blocks` source and answers NoBlocks for anything that is not a contiguous stretch of our chain (or
longer than `maxBlocks`). `KeepAliveServer` echoes cookies. `TxSubmissionClient` and
`TxSubmissionServer` are the two roles of TxSubmission2: the client offers a `Source` of
transactions (each once per connection, by ticket number) and hands out bodies on request; the
server asks for ids with a blocking request, fetches the bodies it does not know (verifying the id
against the body hash) and passes them to a `Sink`; one batch is fully processed and acknowledged
before the next request. `LocalTxSubmissionServer` is the node-to-client submission protocol.

**Served chain** (`relay/ServedChain.scala`, `relay/ChainServer.scala`). The ouroboros-consensus
`ChainProducerState`: the volatile chain (last `k` blocks with their headers and raw bytes) plus
the read pointer of every connected client. A rollback rewrites exactly the pointers of the clients
that had read past the new tip (they get a RollBackward to it and then follow the new branch). A
client behind the volatile chain is served from the `ImmutableStore`. `ChainServer` is a
`ChainPersistence`, composed after the storage persistence so a block is stored before it leaves the
volatile chain. Without `--db` only the volatile chain can be served.

**Mempool** (`mempool/`). `MempoolState` is pure: the transactions are kept in arrival order, and
the invariant is that they apply in order to the ledger state at the tip through the real
`TxRules` (phase 1, phase 2 and the Conway rules). `add` validates against the state after all held
transactions, so a transaction may spend the output of one before it. Rejections are typed
(`Malformed`, `AlreadyHeld`, `Ledger(failure)`, `Full`) and returned as values. `sync(ledger, slot)`
runs on every new tip, rollback or fork switch, keeps what still applies and reports the rest;
transactions that a block contains leave as adopted. Capacity is counted in bytes. A transaction
is validated in the slot after the tip within the tip's epoch (it is not ticked over an epoch
boundary). Transactions of rolled-back blocks are not put back. `Mempool[F]` serialises the
operations, offers `source`/`sink` for TxSubmission2 and remembers recently refused ids so peers
that offer them again are not asked for the body.

**Chain selection** (`consensus/ChainSelection.scala`, `Follower.scala`). A peer session keeps a
candidate (the headers it announced after the point where it joined our chain). The Praos rule: the
longer chain wins; at equal length, tips issued by the same pool compare the operational-certificate
issue number (higher wins); otherwise the lower VRF output of the tips wins (the VRF certificate of
the header, TPraos: the leader one). From Conway the VRF tie-breaker only applies to tips at most 5
slots apart. A candidate that is not strictly preferred changes nothing (so a peer's rollback or
equal fork leaves our chain alone). A switch rolls back at most `k` blocks (the volatile chain is
the limit; a candidate that no longer joins it is dropped as a protocol error of that peer, not of
the node). The blocks of a preferred candidate are fetched from that peer, validated on a copy of
the chain and committed together, so a rule violation leaves the chain at the last valid block
(still fatal, as before). Headers are not validated before the blocks are fetched.
Several sessions can run at once; adoption is serialised.

**Wiring** (`Main`). `--peer` may be given several times (with more than one peer a peer that
keeps failing is retried forever, with one it is fatal after 30 attempts), `--listen` starts the
inbound server, `--local-socket` the local one, `--mempool-bytes` sets the capacity. The followed
peers get our mempool through TxSubmission2. The JSON log gains `listening`, `inbound`,
`mempoolAdded`, `mempoolRejected`, `mempoolAdopted`, `mempoolDropped` and `mempoolSynced`.

Local rejections are sent as a CBOR text with the reason; `cardano-cli` accepts the submission
but cannot render the reason, because it expects the ledger's own predicate-failure encoding.

Tests: servers against our own clients over in-memory bearers and loopback TCP (both directions,
duplex, pipelining, timeouts, refusals); a property that every follower reconstructs the served chain
across arbitrary extensions, rollbacks and reads; the chain server over a real immutable store;
mempool properties (held transactions always apply in order, byte accounting, tickets);
chain-selection properties (antisymmetric, longer wins, issue number, unsigned VRF compare,
never more than `k` rolled back, the winner among several candidates does not depend on arrival
order); the follower with several scripted peers; the local protocol over a Unix socket.

### The relay scenario (`devnet.py relay`)

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
python3 scripts/devnet.py relay --seconds 300     # up + Scala relay + Haskell relay + load
```

Starts the 3-pool cluster, the Scala node following all three pools with `--listen`, `--db` and
`--local-socket`, and, once the Scala node has applied 30 blocks, a non-producing reference
`cardano-node` whose topology names only the Scala node. The relay must sync the whole chain from
the Scala node (the older blocks come from the immutable store) and then stays at the tip while
the load generator builds, signs and submits transactions through the relay's socket. A
transaction travels Haskell relay, TxSubmission2, Scala mempool, TxSubmission2, pools, block, back
through the Scala node to the relay. One transaction is also submitted with `cardano-cli` straight
to the Scala node's Unix socket. The run reports tips and lag every 5 s, the epochs seen, the final
tip of every node, and how many transactions reached the Scala mempool and were included.

## Block production (`follow --forge`)

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The node produces Praos (Conway) blocks for one pool. The files are the ones `cardano-cli` writes: the text
envelopes `VrfSigningKey_PraosVRF` (64 bytes, seed then public key), `KesSigningKey_ed25519_kes_2^6` (the
608-byte period-0 key) and `NodeOperationalCertificate` (`[[hotVkey, counter, kesPeriod, sigma], coldVkey]`,
which also gives the cold key, so the cold signing key is never needed). On start the three are
cross-checked: the certificate must name this KES key and be signed by its cold key.

**Cryptography** (`core`, pure, with vectors):

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
(`EpochGeometry`); a passed slot is never forged late. For the slot it takes the tip's state to the slot's
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
4. offers the block to `Follower.adoptForged`, which runs it through the node's own `NodeRules`, then commits it
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
`kesEvolved`, `forgeSkipped`, `forgeFailed`.

Tests: `ForgeSuite` forges with real keys against the real `NodeRules` over a synthetic Conway ledger (blocks are
accepted, consecutive, counted for the pool, transactions leave the mempool, the KES key crosses period
boundaries, an unregistered VRF key or a certificate outside its window never forges, lost and invalid
adoption, body size accounting and a decode round trip), `KeyFilesSuite` loads a real `cardano-cli`
certificate, `Draft03ProverSuite` and `Sum6KesSigningSuite` hold the vectors.

### The pool scenario (`devnet.py pool`)

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
python3 scripts/devnet.py pool --seconds 480
```

Creates the 3-pool sandbox with `slotsPerKESPeriod = 250` (the default, 129600, would never change period in
a run) and starts the Haskell nodes of pools 2 and 3 only: **the Haskell node of pool 1 is never started**, so
nothing but the Scala node ever holds pool 1's keys. The topologies of pools 2 and 3 name each other and the
Scala node, which follows both, listens on loopback and forges with pool 1's VRF key, KES key and certificate
(copied out of the sandbox into the run directory, mode 0400, and mounted read-only). The load generator submits
through pool 2's socket, so transactions travel Haskell mempool, TxSubmission2, Scala mempool, Scala block.
The run asserts: blocks forged, adopted and on the final chain; the Haskell ledger's blocks-made count for the
pool (`blocksBefore`/`blocksCurrent` of `query ledger-state`, per epoch) equals the number of Scala-forged blocks
on the chain up to the last compared tip and is within four standard deviations of the expected share
(slots times `1 - (1 - f)^stake`); transactions of the load in Scala-forged blocks; at least one KES period
boundary crossed; no rollback deeper than `k` and the Haskell pools agreeing on the tip; the ledger comparison
matching at every point; no error event. It writes `pool-summary.json`.

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
- **Conway details:** the VRF key hash uniqueness of protocol 11 (it only rejects transactions). The block-level
  execution-unit limit is enforced when forging but not yet when validating a received block.
- **On-disk ledger:** the whole UTxO and ledger state live in memory; only snapshots are on disk. The volatile blocks are not persisted.
- **Peers:** no peer selection (the configured peers are all used), no header validation ahead of the block fetch, one block fetched at a time; inbound duplex connections are accepted but not used for our own clients.
- **Rewards:** non-myopic pool ranking.
- **Production:** forging is Praos/Conway only (no TPraos eras), the pool must be registered with stake in the ledger (no Preview pool is run), there is no KES agent (the KES key is loaded from a file at start and evolved in memory; a restart loads period 0 again and evolves forward, no key is rewritten) and no operational certificate issuing; the mempool has no epoch-boundary ticking (the forger re-validates in the block's own slot and epoch).
- **Node-to-client:** only LocalTxSubmission (no LocalStateQuery, LocalChainSync or LocalTxMonitor); rejections are not in the ledger's encoding.
