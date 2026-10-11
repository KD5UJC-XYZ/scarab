# Findings against real data

Facts about the Cardano ledger and consensus that the specifications do not make obvious and that
this project learned from comparing against the Haskell node and the public Preview chain, each with
where it was found and what pins it now. They are observations to confirm against the reference
source, not authoritative statements. Roadmap IDs (B1, ...) point into [roadmap.md](roadmap.md).

## Consensus

| Finding | Where found | Pinned by |
|---|---|---|
| The candidate nonce freezes `3k/f` slots before the epoch end in Alonzo **and** Babbage, and `4k/f` from Conway (and on the devnet, which runs Conway rules). The stability window is era dependent. | Preview, "Verified on real data" in the project status notes (TPraos work, commit 6332426) | `TPraosSuite`; devnet nonce comparison |
| The nonce contribution is `blake2b256` of the **64-byte** VRF output (TPraos). | same | `TPraosSuite` |
| The TPraos overlay schedule follows cardano-ledger `classifyOverlaySlot`: `position = ceiling(s * d)`, a slot is active when `position mod floor(1/f) = 0`. Overlay-slot headers have no leader-threshold check. | Preview epochs 0 and 1 (d = 1) | `TPraosSuite` |
| The leader threshold needs the `set` snapshot pool distribution, so Preview epochs 0 and 1 are overlay-only and the threshold is `unchecked` until a distribution exists (epoch 2 onwards is fully checked). | Preview live follow, status notes | `LeaderCheck.Threshold` vs `Unchecked` |
| A Byron epoch-boundary block repeats its predecessor's block number, and the main block after it may share its slot. At the Byron to Shelley boundary every Praos nonce equals the Shelley genesis hash and Byron blocks do not touch it. | devnet `up-eras` (commits 8c8258b, b5667f8) | `EraTransitionSuite`, `fixtures/eras` |
| Chain selection at equal length: same-pool tips compare opcert issue numbers, otherwise the lower VRF output wins; from Conway the VRF tie-break only applies to tips at most 5 slots apart. **The 5-slot restriction was written from memory and is unconfirmed** (B1). | relay work (commit bb38470) | `ChainSelectionSuite` |

## Ledger

| Finding | Where found | Pinned by |
|---|---|---|
| **Reward update activation.** The ledger creates the reward update with the first block that arrives after `4k/f` slots into the epoch; an epoch with no such block pays no rewards and keeps its fee snapshot. | devnet comparison (commit 8d81d5c) | `ConwayRulesSuite`, `PreConwayRulesSuite`; devnet reward-update comparison |
| **Pre-Babbage reward prefilter.** Before Babbage (protocol of the previous epoch's parameters at most 6) the reward calculation (`startStep`, run by the first block after `4k/f` = `randomnessStabilisationWindow` slots into the epoch, strict `>`; `Rupd.hs determineRewardTiming`) keeps leader rewards (`collectLRs`, `isAccountRegistered`) and member rewards (`rewardOnePoolMember`, `hk ∈ addrsRew`) only for accounts registered at that moment; what is dropped stays in the reserves (`deltaR2`). At the boundary, rewards of accounts deregistered since go to the treasury (`filterAllRewards'`). Confirmed against the source (B8); the code already did this. | cardano-ledger source; devnet e2e deregistration before and after the start slot | `RewardsSuite` (cut-off and boundary cases), e2e `dereg-early`/`dereg-late` |
| **Pre-Allegra reward aggregation.** An account with several rewards keeps one: `filterRewards` takes `Set.deleteFindMin` under `Ord Reward`, where a leader reward sorts **before** a member reward and then pools compare by id. The first version kept the member reward. A zero leader reward still wins over a member reward. | cardano-ledger `Rewards.hs`, `Cardano.Ledger.Rewards` (B7); devnet e2e with cross-paying pools | `RewardsSuite`, e2e reward update comparison under protocol 2 |
| **Mary minimum output.** `scaledMinDeposit`: `max(mv, (mv / 27) * (27 + size v))` for a value with assets and exactly `mv` for ada only, with `size` = 6 + ceil(bytes / 8) words, bytes = 12 per (policy, name) pair + 28 per policy + the bytes of each **distinct** asset name. Mary divides by 27 (`coinSize = 0`); Alonzo counts the ada-only entry as 29 words. The first version divided by 29 and counted a name once per policy. Shelley and Allegra use `minUTxOValue` flat. | cardano-ledger `Mary/TxOut.hs`, `Mary/Value.hs` (B7); devnet e2e at the boundary | `PreConwayRulesSuite`, `test_devnet_pure.py`, e2e `min-*` |
| **Output value size.** `serialize v` (canonical, whatever encoding the transaction used) must be at most 4000 bytes in Allegra and Mary (hard-coded), at most `maxValueSize` from Alonzo, unlimited in Shelley. The first version had no check before Alonzo and used the size of the original encoding. | cardano-ledger `Allegra/Rules/Utxo.hs`, `Alonzo/Rules/Utxo.hs` (B12); devnet e2e at 4000/4001 and 5000/5001 bytes | `PreConwayRulesSuite` (CBOR cross-check), e2e `value-*` |
| **MIR rules by protocol version.** Transfers between pots and negative entries need protocol 5 (`hardforkAlonzoAllowMIRTransfer`); before that a later certificate replaces earlier entries of the same credential, from protocol 5 it adds to them and the sum may not go negative. A certificate is refused from slot `firstSlotNextEpoch - 3k/f`. Entries are applied at the next boundary after the reward update, only to accounts registered then, and only if both pots can pay. **Deregistration leaves the entries in place** (the first version removed them). | cardano-ledger `Deleg.hs`, `Mir.hs`, `NewEpoch.hs` (B7); devnet e2e | `PreConwayRulesSuite`, e2e `mir-*` |
| **Dormant epoch at Conway entry.** The translation to Conway must carry the dormant-epoch counter correctly; otherwise DRep expiries differ. | devnet e2e Conway comparison (commit 85721cb, "fixed Conway dormant-epoch translation") | `EraTransitionSuite`, e2e |
| **Protocol-9 DRep reverse index.** A re-delegation does not remove the old entry at protocol 9; entering protocol 10 rebuilds the index. DRep expiries account for dormant epochs from version 10. | devnet soak / Preview (commit 9ab83c6) | `ConwayGovernanceSuite`, `PreviewConwayGovernanceSuite` |
| **V3 spends need no datum.** Fee and min-UTxO checks were corrected on Preview Conway data. | Preview (commit 9ab83c6) | `PreviewConwayRulesSuite` |
| The fee size counts the array header, body, witnesses and auxiliary data, not the `is_valid` byte. | Preview Babbage mint with metadata | `fixtures/preview/babbage-mint.hex` |
| **Conway reference-script fee** prices the bytes of the distinct inputs' and reference inputs' scripts at `minFeeRefScriptCostPerByte`, times 1.2 for every further 25 KiB; 200 KiB per transaction, 1 MiB per block (counted against the block's starting UTxO up to protocol 10). | implemented from the ledger source, checked on Preview Conway blocks | `ConwayRulesSuite` |
| The node stops if total supply differs from the maximum supply at an epoch boundary (a runtime invariant, `Epoch`). | all runs | runtime check |
| `cardano-cli` 11.2.3 refuses `query ledger-state` and `query protocol-state` before Conway, so the comparator speaks LocalStateQuery itself. | devnet e2e (commit 85721cb) | `scripts/devnet/ntcquery.py` |

## Plutus

| Finding | Where found | Pinned by |
|---|---|---|
| **V1 cost-model ordering.** The genesis cost model is a map of named integers whose canonical order is the **sorted name order**, not the file order. This was the root cause of about 25% script integrity hash mismatches; after the fix 333,607 hashes matched on Preview to epoch 74. | Preview Alonzo/Babbage sync (commits 82ab9b3, 84a42d9) | `ScriptIntegritySuite` |
| **Script data hash encoding.** `blake2b-256(redeemers, datums, language views)` over the witness set's own bytes; datums omitted when empty; PlutusV1's cost model is a byte string wrapping an indefinite list under the byte-string key `0x4100`, V2 and V3 plain, keys in canonical order (V2, V3, then V1); no languages is `0xa0`. | same | `ScriptIntegritySuite` |
| **Scalus Ed25519 costing.** Scalus 1.2.0 costs `verifyEd25519Signature` wrongly under semantics variant A; our wrapper corrects it. To be reported upstream (B11). | Preview Alonzo sync (commit 84a42d9) | the wrapper in `ScalusEvaluator` (no dedicated test confirmed) |
| **Scalus `NewConstitution`.** Scalus 1.2.0 builds the V3 context without the `Constitution` constructor, the same gap as our first version. To be reported upstream (B11). | Preview slot 116688298 (commit 73e5b4f) | `ScalusDeviations`, `ScriptContextOracleSuite` |
| V1 and V2 `TxInfo` differ (V1 rejects inline datums, reference scripts and, before Conway, reference inputs). The upper bound of a validity interval without a lower bound is inclusive before Conway and exclusive from it. | Preview Alonzo/Babbage/Conway sync | `ScriptContextGoldenSuite` |

## The six Preview ledger bugs

Syncing the whole Preview chain was the first check of the ledger against a public network and
found six bugs that the devnet could not show. The first two were located by comparing reserves,
treasury, reward pot and single-pool stake with Koios per epoch; the others from the failing
transaction recorded with `follow --plutus-cases`.

| Where | What the node did | What the ledger does | Commit | Pinned by |
|---|---|---|---|---|
| epoch 493 (rewards) | counted a pool owner's stake for the pledge whatever the owner delegates to | only the stake delegated to the pool counts | ca925cf | `RewardsSuite` |
| epoch 646 (Babbage to Conway) | the SNAP at the boundary counted pointer addresses (a 35,553 ada pointer address) | that boundary runs under the Conway rules, which ignore pointers | ff735db | covered by the Preview sync (no dedicated test in the commit) |
| slot 12924758 (Alonzo-style witness) | evaluated six redeemers for one purpose | redeemers are a map keyed by purpose, the last entry counts | aabb240 | `fixtures/preview/plutus/slot12924758-e5e7f3de.json` |
| slot 62567517 (Conway map form) | counted a redeemer listed twice twice in the fee | one entry per purpose | bd4871f | `TxDecodingSuite`, `fixtures/preview/duplicate-redeemer-conway.hex` |
| slot 65493476 | refused a Plutus V1 script in a transaction with reference inputs | from Conway they are not part of the V1 context | e5beb60 | `fixtures/preview/plutus/slot65493476-9f538f7a.json` |
| slot 116688298 | encoded `NewConstitution` as `[previous, Maybe ScriptHash]` | `Constitution` is a constructor of its own: `[previous, Constr 0 [Maybe ScriptHash]]` | 73e5b4f | `fixtures/preview/plutus/slot116688298-89d3a627.json`, `ScalusDeviations` |

## Network, decoding and storage

| Finding | Where found | Pinned by |
|---|---|---|
| A Preview datum nests deeper than 64 CBOR levels; the decoder limit is now 2048. | Preview sync (commit f3003aa) | `fixtures/preview/deeply-nested-datum.hex`, `TxDecodingSuite` |
| A BlockFetch range of full blocks exceeds the client's 10 MB per-request limit; ranges are cut by announced body size. | pipelined Preview sync (commit d0ed44d) | `PeerSuite` |
| A reconnect offered every retained block as an intersection candidate, more than the protocol's 64; candidates are thinned. | same | `PeerSuite` |
| The public relay timed out ChainSync at 256 pipelined requests; 64 to 128 work. | Preview sync | `--read-ahead` |
| The snapshot reader over-read at the checksum. | live Preview `kill -9` recovery (commit 24cef6b) | `CodecSuite` |

## Method

The recurring pattern: compare per-epoch aggregates (reserves, treasury, rewards, pool stake) with
an independent source (Koios on Preview, LocalStateQuery on the devnet) to find the epoch, then
record the failing transaction (`--plutus-cases`) or capture the blocks (`CaptureMain --from`) into a
fixture and fix against it.
