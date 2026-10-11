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
| Ledger | `ledger/` | Pure functions: transaction decoding, UTXOW/UTXO/CERTS rules, deposits, withdrawals, the EPOCH rule (rewards, SNAP, POOLREAP, obligation, dormant epochs), the reward calculation |
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
python3 scripts/devnet.py e2e          # up-eras + follower from Origin + assertions (about 10 minutes)
```

`e2e` starts the era-walking cluster, follows it from Origin until the node has applied blocks in
the Conway epoch after the first, and fails unless: the `eraTransition` lines are exactly Byron,
Shelley, Allegra, Mary, Alonzo, Babbage and Conway at the epochs in `eras.json` and the
configuration, every era has applied blocks, all Byron signatures verified, and there are no
validation or fatal errors. It writes `e2e-summary.json` (blocks and header verdicts per era,
transitions) to the run directory, and always removes the containers unless `--keep`. Per-era ledger
comparison against the reference is a marked hook (`ERA_LEDGER_COMPARATORS`) for the full ledger.

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
  parameters. The `set` snapshot is the pool distribution for the leader checks, so
  `LeaderCheck.Threshold` replaces `Unchecked`; the node stops if the total supply ever differs
  from the maximum supply at an epoch boundary.
- **Plutus**: phase 2 is not evaluated. A transaction with `is_valid = true` is applied and one with
  `false` consumes its collateral (and pays the collateral return in Babbage), both on the block
  producer's word; `ledgerEpoch` log lines count them (`phase2NotEvaluated`). The script data hash is
  recomputed from the redeemers, datums and cost models; mismatches are counted in
  `scriptIntegrityHashUnchecked`, not rejected.
- **Conway** continues from the Babbage state through `Translate.toConway` (Conway parameters from
  the Conway genesis, empty DRep state, pre-Conway update/MIR/pointer state dropped); this
  translation and the Conway transaction rules for scripts and governance are not verified (see
  below).
- **Bootstrap mode** remains available (`NodeRules(..., bootstrap = true)`): headers only, with the
  leader threshold reported as unchecked.
- **Nonce windows** (verified on Preview): candidate nonce freezes `3k/f` before the epoch end
  through Babbage; Conway and the devnet use `4k/f`. TPraos overlay formulas follow cardano-ledger
  `classifyOverlaySlot` (`position = ceiling(s * d)`, active when `position mod floor(1/f) = 0`).
- **Read-ahead:** while the peer is far ahead, ChainSync requests are pipelined and each batch is
  fetched with one BlockFetch range (`Peer.nodeToNode`, `ChainSyncClient.requestNextBatch`).

Verified: Preview from genesis through epoch 54 (227k blocks, Alonzo to Babbage 8.0) with every
non-overlay header threshold-checked and no rule failure, and the Byron-to-Conway era fixture
(`EraTransitionSuite`).

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
# then run lab.node.Main follow --genesis-dir <preview config dir> --peer preview-node.play.dev.cardano.org:3001
```

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

## Verified result (2026-10-10)

An 8-minute soak from genesis matched the reference exactly at **23/23** comparison points.
Details:

- **Chain:** epochs 0–9, 270 blocks, 2 fork rollbacks and 4 reconnects.
- **Transactions:** 432 accepted: 402 transfers, 6 registrations, 13 delegations,
  8 withdrawals and 3 deregistrations.
- **What each comparison covers:**
  - whole UTxO, fees, deposits, treasury and reserves
  - accounts, pools and DReps
  - mark/set/go snapshots and the fee snapshot
  - block counts, the reward update and the pool distribution
  - all five Praos nonces and the opcert counters

## Not yet implemented

Rejected as `Unsupported` under the Conway rules, so the node stops rather than diverging:

- **Conway scripts and assets:** scripts, minting and bootstrap witnesses (the pre-Conway rules support them).
- **Governance:** proposals and votes, committee certificates and treasury donations.
- **Plutus phase 2:** scripts are never evaluated in any era.
- **Byron:** Byron transactions and body proofs, delegation by on-chain certificates, and the Byron slot-leader schedule.

Also not implemented:

- **Persistence:** none; the node replays from genesis on start.
- **Peers:** a single upstream peer only.
- **Rewards and governance:** non-myopic pool ranking, the DRep pulser and ratification state.
- **Production:** block production and a mempool.
