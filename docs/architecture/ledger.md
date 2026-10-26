<!-- SPDX-License-Identifier: Apache-2.0 -->
# Era history, ledger rules and Plutus evaluation

Part of [Architecture](../architecture.md).

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
([`fixtures/eras`](../../fixtures/eras/README.md)) through the node's block rules and a scripted
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
  `org.scalus:scalus_3:1.4.0`); nothing else of Scalus is used by the node. Ed25519, secp256k1 and
  BLS12-381 run on `libscalus_crypto` of `org.scalus:scalus-crypto-jni:0.1.0` (libsodium,
  libsecp256k1 and portable blst linked into one library per platform), which the JVM extracts to
  its temporary directory. `NativeCrypto` pins its sha-256 and checks known answers at start-up, and
  every builtin it backs is a typed `BuiltinUnavailable` when it did not load
  ([ADR 0020](../adr/0020-plutus-native-crypto-through-scalus-crypto-jni.md)). `ScalusEvaluatorSuite`
  evaluates a BLS pairing and an ECDSA verification (also run inside the Temurin 21 Docker image).
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
  serial interpreter. See [the design](../design/parallel-plutus.md) and ADR 0018.
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
