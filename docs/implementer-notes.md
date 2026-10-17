# Implementer notes: reference behaviours and specification gaps

This page lists behaviours of the Haskell reference node that an alternative Cardano node
implementation must replicate exactly, and places where the specification or the CDDL differs from
the reference implementation. It is written for developers of other node implementations. It is not a
list of our own bugs: findings that were purely our mistakes are left out.

Scarab is a Cardano node in Scala. Every entry below was met while checking Scarab block by block
against the Haskell `cardano-node` 11.1.3 as the reference. The page is a curated view of
[findings.md](findings.md), [roadmap.md](roadmap.md) (the B-rows), [compatibility.md](compatibility.md)
and [leios.md](leios.md). Those pages keep the full history and the fixes. This page keeps what
another implementer could plausibly get wrong.

## How the entries were found

- **Block-by-block comparison** with `cardano-node` 11.1.3: every block of a synced chain is checked
  against our rules. The chain is the oracle, so a rule that rejects a valid block shows up as a stop.
- **Sync from genesis** of Preview and preprod to the tip, and of mainnet in stages. The mainnet
  entries come from the points where our sync stopped, so they are tied to a slot and a transaction.
- **Devnet era transitions**: a `cardano-testnet` cluster run from Byron to Conway with the Haskell
  node on the other side, compared at every epoch boundary and through LocalStateQuery.
- **State comparison with `db-analyser`** (roadmap K1): at the first block of each epoch the ledger
  state is compared field by field with the state the reference stores. Preview matches in all 1,451
  epoch-boundary states ([compatibility.md](compatibility.md)).
- **Reading the upstream source** for the cause, once a difference was seen. A few entries come from
  reading the source only, with no chain data; they say so.

## Confidence levels

| Status | Meaning |
|---|---|
| **confirmed in source** | An upstream citation (repository, file, line or function) exists in our docs, and the behaviour was also seen on a chain or a devnet unless the entry says it was read from the source only. |
| **observed** | Inferred from chain or devnet behaviour. Our docs carry no upstream line for it. Treat it as a hypothesis to check against the source. |
| **being landed** | The entry is being written up and its evidence is not complete. |

File and line numbers are as cited in our docs. They refer to the versions named in the entry
(for example `ouroboros-consensus` 4.2.0.0, `cardano-ledger-core` 1.21.0.0, `plutus-ledger-api`
1.70.0.0) and may have moved. A "cited" line is not a claim that the current master has the same
line. Where our docs record no file or line, the entry gives the function name only.

Entry IDs (`IN-n`) are permanent, so they can be cited: a new entry takes the next free number in
whichever part it belongs to, an entry is never renumbered or its number reused, and an entry found to
be wrong stays as a short stub that says why it was withdrawn.

## Agreement and disagreement

If you maintain another node and you have met the same behaviour, or you read the source differently,
please open an issue in [KD5UJC-XYZ/scarab](https://github.com/KD5UJC-XYZ/scarab/issues) with the entry
ID. Reports of agreement are as useful as reports of disagreement. Entries with status "observed"
are the ones most in need of a second reading.

## Part A: legacy and era-gated behaviours of the reference

65 entries, grouped by area.

### Addresses, outputs and transactions

#### IN-1 A Byron address has no size limit of its own

- **Behaviour:** The reference decodes a Byron address with no size limit. The derivation-path attribute (`HDAddressPayload`) may be thousands of bytes. Only the block size and `maxTxSize` bound an address. The Byron ledger rule counts only the attributes it does not interpret (`unknownAttributesLength < 128`), and the derivation path is not one of them.
- **Era or protocol-version gate:** Byron; Shelley and later also accept it (the Shelley-era rule `OutputBootAddrAttrsTooBig`, limit 64 bytes, applies to new outputs only).
- **Upstream reference:** cardano-ledger `Cardano.Chain.Common.Address` (`instance DecCBOR Address`, `decodeCrcProtected`); `AddrAttributes.hs` (`instance DecCBOR HDAddressPayload` = `decodeBytesCanonical`); `Cardano.Chain.UTxO.Validation.validateTxOutNM`; `Cardano.Ledger.Address.bootstrapAddressAttrsSize`.
- **Chain evidence:** Mainnet slot 1,769,882 (epoch 81, block hash prefix f277604972be4b11): tx bc61865d pays an output to an address of 7,689 bytes (attribute 1 is 7,641 bytes). Roadmap B20.
- **Pinned by:** [`ByronLargeAddressSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/byron/ByronLargeAddressSuite.scala), [`fixtures/mainnet/byron-large-address-block.hex`](../fixtures/mainnet/byron-large-address-block.hex)
- **Status:** confirmed in source

#### IN-2 Output value size is measured on the ledger's own re-serialisation

- **Behaviour:** The size limit applies to `serialize v` in the ledger's own encoding, not to the bytes the transaction carried. That encoding writes a map of more than 23 entries as indefinite-length (`0xbf ... 0xff`, 2 bytes), so a map of 256 or more entries is one byte shorter than the definite minimal form. A value that is 5001 bytes in definite form is 5000 bytes for the ledger.
- **Era or protocol-version gate:** Hard-coded 4000 bytes in Allegra and Mary; `maxValueSize` from Alonzo; unlimited in Shelley. The indefinite-map rule applies at decoder version 2 and above.
- **Upstream reference:** cardano-ledger `Allegra/Rules/Utxo.hs`; `Alonzo/Rules/Utxo.hs:425-445`; `cardano-ledger-binary` `Encoding/Encoder.hs:397-408` (`encodeMap`), `:433-443` (`variableMapLenEncoding`, `lengthThreshold = 23`); `Mary/Value.hs:354-361`, `:163-164`.
- **Chain evidence:** Preprod tx 06869a76 (block 1,558,703): 500 assets under one policy, 5001 bytes definite, 5000 bytes in the ledger, accepted by the chain. Devnet at 4000/4001 and 5000/5001 bytes. Roadmap B12.
- **Pinned by:** [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala) (CBOR cross-check), [`fixtures/preprod/tx-06869a76.hex`](../fixtures/preprod/tx-06869a76.hex), e2e `value-*`
- **Status:** confirmed in source

#### IN-3 Mary minimum output uses `scaledMinDeposit` with divisor 27 and distinct asset names

- **Behaviour:** For a value with assets the minimum is `max(mv, (mv / 27) * (27 + size v))`; for ada only it is exactly `mv`. `size` is 6 + ceil(bytes / 8) words, with bytes = 12 per (policy, name) pair + 28 per policy + the bytes of each distinct asset name. Mary divides by 27 (`coinSize = 0`). Alonzo counts the ada-only entry as 29 words.
- **Era or protocol-version gate:** Mary only.
- **Upstream reference:** cardano-ledger `Mary/TxOut.hs`, `Mary/Value.hs`.
- **Chain evidence:** Devnet e2e at the boundary. Roadmap B7.
- **Pinned by:** [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala), `scripts/tests/test_devnet_pure.py`, e2e `min-*`
- **Status:** confirmed in source

#### IN-4 Before Babbage, bytes after a valid address in an output are dropped (B28)

- **Behaviour:** Before Babbage, an output whose address field is a valid address followed by extra bytes is accepted. The ledger reads the address from the front of the byte string, keeps only the bytes it read as the address, and ignores the rest. The transaction id is still the hash of the body as received. From Babbage every byte must belong to the address. A short address is refused in every era.
- **Era or protocol-version gate:** By decoder version, the lowest protocol version of the era. Below 7 (Shelley, Allegra, Mary, Alonzo) the decoder is lenient. From 7 (Babbage) it is rigorous.
- **Upstream reference:** cardano-ledger-core 1.21.0.0, `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs`: `fromCborBothAddr` 459-477, `fromCborBackwardsBothAddr` 495-503 (`decodeAddrStateLenientT True True`, which skips `ensureBufIsConsumed`, 643-675), `fromCborRigorousBothAddr` 479-487.
- **Chain evidence:** Mainnet block 6,203,514 (slot 39,249,764, Mary). Output 0 of tx 369826b2f60b443d27966d222fa5bedc6b900326b77a09f15473d77f5ef2871c has a 78-byte address field: a 57-byte base address followed by 21 bytes (`cb57afb0…518c7516`). db-sync and Koios show the 57-byte address.
- **Pinned by:** [`fixtures/mainnet/mary-garbage-address-block.hex`](../fixtures/mainnet/), `MainnetGarbageAddressSuite` (ledger), `MainnetGarbageAddressStoreSuite` (storage).
- **Status:** confirmed in source

#### IN-5 Before Conway, an out-of-range pointer address is normalised, and its address is written again (B29)

- **Behaviour:** Before Conway, each natural of a pointer (slot, transaction index, certificate index) is read as an unbounded variable-length number folded into a Word64, so bits beyond the 64th are lost. The pointer is then normalised: it becomes `Ptr 0 0 0` unless the slot fits a Word32 and both indexes fit a Word16. The address the ledger keeps is the pointer address written again by `putPtr`: the shortest encoding of each field, with any trailing bytes dropped before Babbage, as in IN-4. The stake of such an output is held under `Ptr 0 0 0`. From Conway, an out-of-range pointer, or a field encoded wider than its type, refuses the address. In every version the stored address is the canonical re-serialisation, so an in-range pointer written with padding is canonicalised.
- **Era or protocol-version gate:** By decoder version. Below 9 (Shelley to Babbage): lenient and normalised. From 9 (Conway): strict.
- **Upstream reference:** cardano-ledger-core 1.21.0.0. In `Address.hs`: `decodePtrLenient` 780-788, `decodeVariableLengthWord64` 876-882, `decodePtr` 769-777, `decodeVariableLengthWord32` 852-870, `decodeVariableLengthWord16` 832-850, `cAddrNormalized` 470-476, `putPtr` 347-351. In `Credential.hs`: `mkPtrNormalized` 270-285.
- **Chain evidence:** Mainnet block 5,567,164 (slot 26,366,727, Mary). Output 0 of tx 9a47e091709e4fe1a8ba1291bc8cfd0c0ed0bb9220688b3ccfad5f2d961e8d8e pays 50 ada to a pointer address whose pointer decodes as (16,292,793,057, 1,011,302, 20). An implementation that keeps the exact pointer holds a state the reference's ledger-state encoding cannot represent: a Word32 slot and two Word16 indexes.
- **Pinned by:** [`fixtures/mainnet/mary-pointer-tx-9a47e091.hex`](../fixtures/mainnet/), `MainnetPointerSuite` (ledger), `MainnetPointerStoreSuite` (storage), `MainnetPointerSnapshotSuite` (node).
- **Status:** confirmed in source for the decoding and the stored address. The stake key and the Plutus script context (staking pointer `0 0 0`) are derived from the decoding and observed, not read from the upstream stake and Plutus translation code.

#### IN-6 A Byron input can be witnessed by a plain vkey whose hash is the address root

- **Behaviour:** The needed-witness set holds `bootstrapKeyHash` of each Byron input beside the key-hash credentials. It is checked against one set made of the hashes of the vkey witnesses and the roots of the bootstrap witnesses. A vkey witness for a key that hashes to the address root therefore spends a Byron output. There is no separate missing-bootstrap-witness failure; an unwitnessed Byron input is `MissingVKeyWitnessesUTXOW`.
- **Era or protocol-version gate:** Not gated; seen in Conway.
- **Upstream reference:** cardano-ledger `Shelley/UTxO.hs` lines 210-228 (`getShelleyWitsVKeyNeededNoGov`); `Shelley/Rules/Utxow.hs` lines 412-423 (`validateNeededWitnesses`).
- **Chain evidence:** Preprod tx 0c22edee (block 3,158,876, slot 83,038,792, epoch 196, Conway) spends two Byron outputs of address root 533bcb8d with a vkey witness. Roadmap B14.
- **Pinned by:** [`PreprodByronKeyWitnessSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodByronKeyWitnessSuite.scala), [`fixtures/preprod/tx-0c22edee.hex`](../fixtures/preprod/tx-0c22edee.hex)
- **Status:** confirmed in source

#### IN-7 Shelley's time to live is the last valid slot

- **Behaviour:** In Shelley a transaction is valid while `ttl >= slot`; failure is `ExpiredUTxO`. The exclusive upper bound belongs to Allegra's validity interval (`inInterval`).
- **Era or protocol-version gate:** Shelley only (`ExactEra ShelleyEra`).
- **Upstream reference:** cardano-ledger `Shelley/Rules/Utxo.hs` lines 414-423 (`validateTimeToLive`).
- **Chain evidence:** Mainnet slot 4,498,780 (epoch 208, block hash prefix c3015880): tx c7e8a043 has `ttl` 4,498,780. Roadmap B23.
- **Pinned by:** [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala)
- **Status:** confirmed in source

#### IN-8 The fee size counts array header, body, witnesses and auxiliary data, not the `is_valid` byte

- **Behaviour:** The size used for the linear fee is the size of the transaction without the `is_valid` byte.
- **Era or protocol-version gate:** Alonzo onwards.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview Babbage mint with metadata.
- **Pinned by:** [`fixtures/preview/babbage-mint.hex`](../fixtures/preview/babbage-mint.hex)
- **Status:** observed

#### IN-9 Conway reference-script fee

- **Behaviour:** The fee prices the bytes of the distinct inputs' and reference inputs' scripts at `minFeeRefScriptCostPerByte`, multiplied by 1.2 for every further 25 KiB. The limit is 200 KiB per transaction and 1 MiB per block. Up to protocol version 10 the per-block total is counted against the block's starting UTxO.
- **Era or protocol-version gate:** Conway; the block count differs at protocol 10 and below.
- **Upstream reference:** implemented from the ledger source; no line numbers in our docs.
- **Chain evidence:** Checked on Preview Conway blocks.
- **Pinned by:** [`ConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayRulesSuite.scala)
- **Status:** observed

#### IN-10 Zero-lovelace Byron genesis outputs are dropped at the Byron to Shelley translation

- **Behaviour:** `translateUTxOByronToShelley` drops outputs of zero lovelace (comment upstream: "in some testnets there are a few TxOuts with zero values injected at initialization of Byron"). Preview's Byron genesis has seven such `nonAvvmBalances`. A node that keeps them has a UTxO set that differs from the reference from epoch 1 (11 entries against 4).
- **Era or protocol-version gate:** Byron to Shelley translation.
- **Upstream reference:** cardano-ledger `translateUTxOByronToShelley`; file and line not recorded in our docs.
- **Chain evidence:** Preview epoch 1, `db-analyser` comparison (K1).
- **Pinned by:** `scripts/oracle_compare.py` on Preview (epochs 1 onwards equal)
- **Status:** confirmed in source

### Rewards and stake

#### IN-11 The reward update is created by the first block after 4k/f slots into the epoch

- **Behaviour:** The reward update is created with the first block that arrives after `4k/f` slots into the epoch (`randomnessStabilisationWindow`, strict `>`). An epoch with no such block pays no rewards and keeps its fee snapshot. Before that block `RUPD` answers `SNothing` (`RewardsTooEarly`).
- **Era or protocol-version gate:** All eras (the window is era dependent through `k` and `f`).
- **Upstream reference:** cardano-ledger `Rupd.hs` `determineRewardTiming`; `Rupd.hs:101`.
- **Chain evidence:** Devnet reward-update comparison (commit 1ff2934).
- **Pinned by:** [`ConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayRulesSuite.scala), [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala)
- **Status:** confirmed in source

#### IN-12 Pre-Babbage reward prefilter: only accounts registered at the start slot

- **Behaviour:** The reward calculation (`startStep`) keeps leader rewards (`collectLRs`, `isAccountRegistered`) and member rewards (`rewardOnePoolMember`, `hk` in `addrsRew`) only for accounts registered at the moment of the first block after `4k/f`. What is dropped stays in the reserves (`deltaR2`). At the boundary, rewards of accounts deregistered since go to the treasury (`filterAllRewards'`).
- **Era or protocol-version gate:** `hardforkBabbageForgoRewardPrefilter`: protocol version greater than 6 forgoes the prefilter.
- **Upstream reference:** cardano-ledger `Rupd.hs`; the gate named above.
- **Chain evidence:** Devnet e2e, deregistration before and after the start slot. Roadmap B8.
- **Pinned by:** [`RewardsSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/RewardsSuite.scala) (cut-off and boundary cases), e2e `dereg-early`, `dereg-late`
- **Status:** confirmed in source

#### IN-13 Pre-Allegra reward aggregation keeps one reward per account, a leader reward before a member reward

- **Behaviour:** An account with several rewards keeps one: `filterRewards` takes `Set.deleteFindMin` under `Ord Reward`, where a leader reward sorts before a member reward and then pools compare by id. A zero leader reward still wins over a member reward.
- **Era or protocol-version gate:** `hardforkAllegraAggregatedRewards`: protocol version greater than 2 aggregates.
- **Upstream reference:** cardano-ledger `Rewards.hs`, `Cardano.Ledger.Rewards`.
- **Chain evidence:** Devnet e2e with cross-paying pools, protocol 2. Roadmap B7.
- **Pinned by:** [`RewardsSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/RewardsSuite.scala)
- **Status:** confirmed in source

#### IN-14 The expected number of blocks in the reward calculation is floored

- **Behaviour:** `expectedBlocks = floor ((1 - d) * f * epochSize)` divides the blocks made to get `eta`. The floored and exact values agree whenever the product is an integer, which every public network's parameters make it. They differ for, for example, `d = 0.3` with an epoch of 1,000 slots at `f = 1/30`.
- **Era or protocol-version gate:** All eras.
- **Upstream reference:** cardano-ledger `PulsingReward.hs:127-129`.
- **Chain evidence:** Found by reading the rule, not by chain data.
- **Pinned by:** [`RewardsSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/RewardsSuite.scala) (integral cases only)
- **Status:** confirmed in source

#### IN-15 Only the stake delegated to the pool counts for a pool owner's pledge

- **Behaviour:** The owner's own stake counts toward pledge only if the owner delegates to that pool. An owner delegating elsewhere does not add to the pledge.
- **Era or protocol-version gate:** Not gated.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview epoch 493, found by comparing reserves, treasury, reward pot and single-pool stake with Koios (commit 74a01ae).
- **Pinned by:** [`RewardsSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/RewardsSuite.scala)
- **Status:** observed

#### IN-16 Stake snapshots hold non-zero stake only

- **Behaviour:** `resolveActiveInstantStakeCredentials` keeps a registered, delegated credential only if its UTxO stake plus reward balance is non-zero. `ssStake` and `ssDelegations` never hold a zero entry. Rewards and the leader schedule are the same either way, but a state comparison sees the difference.
- **Era or protocol-version gate:** Not gated.
- **Upstream reference:** cardano-ledger `resolveActiveInstantStakeCredentials` (file not recorded in our docs).
- **Chain evidence:** Preview epoch 18 onwards (a script credential), `db-analyser` comparison (K1).
- **Pinned by:** `scripts/oracle_compare.py`
- **Status:** confirmed in source

### Epoch boundary and era translations

#### IN-17 Entering Allegra returns the outputs at Byron redeem (AVVM) addresses to the reserves

- **Behaviour:** The translation runs `returnRedeemAddrsToReserves` on the state at the end of the last Shelley epoch, before the boundary's tick. Every output to a Byron redeem address leaves the UTxO set and its lovelace goes to the reserves, which the first Allegra reward calculation draws on. On mainnet that is 318,200,635 ada. The reference stashes the redeem outputs at the Byron to Shelley translation and hands that stash to the function as the UTxO, because its UTxO is on disk.
- **Era or protocol-version gate:** Shelley to Allegra.
- **Upstream reference:** cardano-ledger `Allegra/Translation.hs:58-71`; `Shelley/LedgerState/NewEpochState.hs:125-146`; `Address.hs:344` (`isBootstrapRedeemer`); `Shelley/API/ByronTranslation.hs:110-117` (`stashedAVVMAddresses`); ouroboros-consensus `Cardano/CanHardFork.hs:484-543`.
- **Chain evidence:** Mainnet sync from genesis: reserves 318,200,635 ada short at epoch 236. Tx 48f567e5 (mainnet slot 17,020,941) withdraws the leader reward 351,635,274 of pool ffdf1b8f for epoch 235 (the node had 351,050,635). Roadmap B26.
- **Pinned by:** [`MainnetAllegraReservesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/MainnetAllegraReservesSuite.scala), `AllegraRedeemStoreSuite`
- **Status:** confirmed in source

#### IN-18 MIR rules by protocol version, and deregistration leaves MIR entries in place

- **Behaviour:** Transfers between pots and negative entries need protocol 5 (`hardforkAlonzoAllowMIRTransfer`). Before that a later certificate replaces earlier entries of the same credential; from 5 it adds to them and the sum may not go negative. A certificate is refused from slot `firstSlotNextEpoch - 3k/f`. Entries are applied at the next boundary after the reward update, only to accounts registered then, and only if both pots can pay. Deregistration leaves the entries in place.
- **Era or protocol-version gate:** Protocol version 5.
- **Upstream reference:** cardano-ledger `Deleg.hs`, `Mir.hs`, `NewEpoch.hs`; `Rules/Deleg.hs:353,363`.
- **Chain evidence:** Devnet e2e. Roadmap B7.
- **Pinned by:** [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala), e2e `mir-*`
- **Status:** confirmed in source

#### IN-19 Pointer addresses do not count in the stake snapshot at the Babbage to Conway boundary

- **Behaviour:** The SNAP at the boundary runs under the Conway rules, which ignore pointer addresses. A node that counts them in that snapshot differs (a 35,553 ada pointer address on Preview).
- **Era or protocol-version gate:** The boundary that enters Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview epoch 646 (commit f133dcd).
- **Pinned by:** covered by the Preview sync; no dedicated test in the commit
- **Status:** observed

#### IN-20 The previous parameters of the first Conway epoch carry the Conway fields

- **Behaviour:** The hard-fork combinator translates the whole governance state, the previous parameters with the current ones (`upgradePParams` sets `dRepDeposit`, the voting thresholds, `govActionDeposit` and the rest from the Conway genesis). Then the Conway EPOCH makes the translated current parameters the previous ones. So for the whole first epoch of Conway the previous parameters, and those in the pulser's enact state, have the Conway fields.
- **Era or protocol-version gate:** First epoch of Conway.
- **Upstream reference:** cardano-ledger `Conway/Translation.hs:165-178` (`translateGovState`); `Conway/Rules/Epoch.hs:324`.
- **Chain evidence:** Eras capture of the devnet (`es.ls.utxo.gov.prevPParams`), found by a state comparison.
- **Pinned by:** [`TickRulesSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/rules/TickRulesSuite.scala), [`NtcGoldenSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/NtcGoldenSuite.scala)
- **Status:** confirmed in source

#### IN-21 The dormant-epoch counter must be translated into Conway correctly

- **Behaviour:** A wrong dormant-epoch counter at the translation makes DRep expiries differ.
- **Era or protocol-version gate:** Conway entry.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet Conway comparison (commit 3a0da52).
- **Pinned by:** [`EraTransitionSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/EraTransitionSuite.scala)
- **Status:** observed

#### IN-22 Genesis delegations survive the Conway translation

- **Behaviour:** The delegation state keeps `dsGenDelegs` (the Shelley genesis delegates) in every Conway state, unused. A node that clears it differs in a state comparison. No rule reads it.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet e2e epoch 10, `db-analyser` comparison.
- **Pinned by:** `scripts/oracle_compare.py`, `devnet.py e2e --oracle`
- **Status:** observed

#### IN-23 Protocol-9 DRep reverse index; dormant epochs count from version 10

- **Behaviour:** A re-delegation does not remove the old entry of the reverse index at protocol 9. Entering protocol 10 rebuilds the index. DRep expiries account for dormant epochs from version 10.
- **Era or protocol-version gate:** Protocol 9 and 10.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet soak and Preview (commit 1926b1b).
- **Pinned by:** [`ConwayGovernanceSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayGovernanceSuite.scala), [`PreviewConwayGovernanceSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/PreviewConwayGovernanceSuite.scala)
- **Status:** observed

#### IN-24 Governance state in the first epoch and just after a boundary

- **Behaviour:** Before the first boundary the pulser's enact state and the previous parameters are those of the era translation (the PlutusV1 and V3 cost models, not the V2 one from the Alonzo genesis' `extraConfig`). `FuturePParams` is `NoPParamsUpdate` normally, `Potential` in the block that crosses a boundary and until two stability windows before the end of the epoch, and `Definite` once a ratified parameter change is solid.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet LocalStateQuery comparison.
- **Pinned by:** devnet `ntc`
- **Status:** observed

### Certificates and pools

#### IN-25 A pool registration's reward-account network is checked only from protocol version 5

- **Behaviour:** `hardforkAlonzoValidatePoolAccountAddressNetID pv = pvMajor pv > natVersion @4`. Protocol versions 2 to 4 accept a reward account on any network. A node that applies `WrongNetworkPOOL` in every era stops on mainnet at the first Shelley blocks.
- **Era or protocol-version gate:** Protocol version greater than 4.
- **Upstream reference:** cardano-ledger `eras/shelley/impl/src/Cardano/Ledger/Shelley/Era.hs:246-251`; `Shelley/Rules/Pool.hs:230-243`.
- **Chain evidence:** Mainnet slot 4,495,760 (epoch 208, block hash prefix fb4e87a0): tx 49be5965 registers pool ff5181ba with a reward account on network 0. Roadmap B22.
- **Pinned by:** [`MainnetPoolNetworkSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/MainnetPoolNetworkSuite.scala), [`fixtures/mainnet/shelley-pool-network-block.hex`](../fixtures/mainnet/shelley-pool-network-block.hex)
- **Status:** confirmed in source

#### IN-26 A registered pool's reward account has no network in the ledger state

- **Behaviour:** `StakePoolState` keeps the account's credential only (`spsAccountId`), as does `StakePoolSnapShot`. `stakePoolStateToStakePoolParams networkId` puts the account back on the node's network. `GetStakePoolParams` and `GetPoolState` answer that way. So a pool registered with a network-0 account reads back as a mainnet account, and the ledger state cannot say otherwise. The future parameters of a re-registration, a proposal's return account and a treasury withdrawal key keep the network byte.
- **Era or protocol-version gate:** State shape of cardano-ledger-core 1.21.0.0.
- **Upstream reference:** cardano-ledger-core `State/StakePool.hs:138-159`, `256-269` (`mkStakePoolState`), `275-292`; `Shelley/Rules/Pool.hs:275`; `PoolReap.hs:158`; `Transition.hs:540,555`; `State/SnapShots.hs:156-181`; `cardano-ledger-api` `State/Query.hs:485-525`.
- **Chain evidence:** Mainnet pool ff5181ba (registered at protocol version 2 with a network-0 account); a node that refused the mismatch could not snapshot any state after the handover, through Allegra epoch 237. Roadmap B27.
- **Pinned by:** [`MainnetPoolNetworkSnapshotSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/storage/MainnetPoolNetworkSnapshotSuite.scala), [`PoolNetworkQuerySuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/PoolNetworkQuerySuite.scala), `LedgerMirrorSuite`
- **Status:** confirmed in source

#### IN-27 Pool metadata hash larger than 32 bytes is refused from protocol version 4.1

- **Behaviour:** `SoftForks.restrictPoolMetadataHash` (`pv > (4, 0)`): a pool metadata hash of more than 32 bytes fails with `PoolMedataHashTooBig`. Our node does not raise it yet (roadmap B22, open); only a block the reference rejects could differ.
- **Era or protocol-version gate:** Protocol greater than (4, 0).
- **Upstream reference:** cardano-ledger `Rules/Pool.hs:245`.
- **Chain evidence:** none on chain.
- **Pinned by:** not pinned (open)
- **Status:** confirmed in source

#### IN-28 VRF key hashes are unique at protocol 11

- **Behaviour:** A new pool may not take a key hash held by any pool, active or scheduled by a re-registration. A pool may keep its own current key hash, return to it after scheduling another, and a freed key hash is available again. The HARDFORK rule into 11 fills the set.
- **Era or protocol-version gate:** `hardforkConwayDisallowDuplicatedVRFKeys`: protocol version greater than 10.
- **Upstream reference:** cardano-ledger `Shelley/Rules/Pool.hs:265` (and `265-284`); `Conway/Rules/HardFork.hs:75`.
- **Chain evidence:** Devnet: ten registrations around two pools' key hashes, five refused. Roadmap B5.
- **Pinned by:** [`ConwayGovernanceSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayGovernanceSuite.scala), [`DevnetRefusedTxSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/ledger/DevnetRefusedTxSuite.scala)
- **Status:** confirmed in source

### Governance

#### IN-29 SPO default votes after the bootstrap phase

- **Behaviour:** A pool that does not vote counts as No, except that a reward account delegated to AlwaysAbstain makes it Abstain (on every action) and one delegated to AlwaysNoConfidence makes it Yes on a no-confidence action (No on every other). A hard fork is the exception to both: silence is always No, so an always-abstain pool stays in the denominator. An unregistered reward account or a key-DRep delegation votes No.
- **Era or protocol-version gate:** Conway, after the bootstrap phase.
- **Upstream reference:** cardano-ledger `Rules/Ratify.hs` `spoAcceptedRatio`; `Governance.hs` `defaultStakePoolVote` (master 56ddee1; the 11.1.3 binary behaved the same).
- **Chain evidence:** Devnet `soak --governance-extended`: of two competing hard forks only the one with two voting pools is enacted; a no-confidence action is enacted although no pool voted. Roadmap B2.
- **Pinned by:** [`RatifySuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/RatifySuite.scala)
- **Status:** confirmed in source

#### IN-30 The committee term limit is applied by RATIFY, not GOV

- **Behaviour:** A committee update with a member term beyond `committeeMaxTermLength` is accepted as a proposal (GOV refuses only an expiry that is not in the future) and then never ratified, whatever the votes.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** cardano-ledger `Rules/Ratify.hs:370` (`validCommitteeTerm`).
- **Chain evidence:** Devnet: a term-limit update that DReps, pools and the rest accept stayed pending until it expired. Roadmap B3.
- **Pinned by:** [`RatifySuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/RatifySuite.scala)
- **Status:** confirmed in source

#### IN-31 `committeeMinSize` counts active members and applies inside a ratification run

- **Behaviour:** Active means authorised, not resigned and not expired. A parameter change enacted early in a RATIFY pass changes the committee threshold for the actions after it in the same pass (parameter changes come before withdrawals). A withdrawal proposed with the change is still ratified; one proposed once the member has expired is not.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet: `committeeMinSize` 2 enacted with a withdrawal in one run. Roadmap B3.
- **Pinned by:** [`RatifySuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/RatifySuite.scala)
- **Status:** observed

#### IN-32 The committee state is trimmed to the members at every epoch boundary

- **Behaviour:** The hot-key authorisation of a proposed member is gone at the next boundary. With no committee the state is empty, so every member must authorise again after an election.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** cardano-ledger `Rules/Epoch.hs:423` (`updateCommitteeState`).
- **Chain evidence:** Found building the devnet scenarios.
- **Pinned by:** [`ConwayGovernanceSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayGovernanceSuite.scala)
- **Status:** confirmed in source

#### IN-33 Votes by unelected committee hot keys: GOV refuses from protocol 11, the mempool before

- **Behaviour:** A vote of a hot key that no elected member authorised is refused by GOV from protocol 11 (`UnelectedCommitteeVoters`). Before 11 only the node's mempool refuses it, so blocks may carry such votes.
- **Era or protocol-version gate:** `hardforkConwayDisallowUnelectedCommitteeFromVoting`: protocol 11.
- **Upstream reference:** cardano-ledger `Rules/Gov.hs:478`; `Rules/Mempool.hs:128`.
- **Chain evidence:** Devnet: both a proposed member's key and a former member's key refused at 11. Preprod epochs 187 to 193 carry 648,752 committee votes of authorised hot keys. Roadmap B5.
- **Pinned by:** [`ConwayGovernanceSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayGovernanceSuite.scala), [`MempoolSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/mempool/MempoolSuite.scala), [`DevnetRefusedTxSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/ledger/DevnetRefusedTxSuite.scala), [`ConwayLedgerSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayLedgerSuite.scala)
- **Status:** confirmed in source

#### IN-34 Before protocol 11 a withdrawal that does not drain its account is `WithdrawalsNotInRewardsCERTS`

- **Behaviour:** At protocol 10 the failure comes from CERTS, after the certificates. From 11 the LEDGER rule splits it into `ConwayWithdrawalsMissingAccounts` and `ConwayIncompleteWithdrawals`.
- **Era or protocol-version gate:** `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`: protocol above 10.
- **Upstream reference:** cardano-ledger `Rules/Ledger.hs`, `Rules/Certs.hs`.
- **Chain evidence:** The reference prints the CERTS form at 10.
- **Pinned by:** [`ConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayRulesSuite.scala)
- **Status:** confirmed in source

#### IN-35 Script credentials need a script witness per purpose

- **Behaviour:** Registering a stake or DRep credential and delegating its votes (Cert, redeemer index = certificate index), voting as a script DRep (Voting) and withdrawing from a script reward account (Rewarding, all of the balance) each need a script witness. Treasury withdrawals may target a registered script account.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet: registration, delegation, vote and withdrawal of one Plutus V3 credential. Roadmap B4.
- **Pinned by:** [`ConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayRulesSuite.scala)
- **Status:** observed

#### IN-36 The MEMPOOL rule runs before LEDGER; CERTS and GOV run before UTXOW

- **Behaviour:** A transaction none of whose inputs is unspent is refused with `ConwayMempoolFailure "All inputs are spent. Transaction has probably already been included"` and nothing else. A transaction already held counts as one whose inputs are spent. The ledger runs CERTS and GOV before UTXOW and reports every failure it finds, in that order.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** cardano-ledger `Rules/Mempool.hs`.
- **Chain evidence:** Devnet `ntc`: the reference's rejections of 18 crafted transactions.
- **Pinned by:** [`MempoolSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/mempool/MempoolSuite.scala), `PredicateFailureCborSuite`
- **Status:** observed

#### IN-37 Stake distributions in queries use non-zero totals

- **Behaviour:** The totals are `NonZero`: an empty snapshot counts as 1 lovelace. `GetStakeDistribution2` is the current stake as a fraction of the circulating supply; `GetPoolDistr2` is the distribution used for the leader schedule. `GetDRepDelegations` with an empty set lists the accounts' delegations, not the DReps' reverse index.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet `ntc`.
- **Pinned by:** devnet `ntc`
- **Status:** observed

### Plutus and scripts

#### IN-38 Disjoint reference inputs: the check ends at protocol 11 and moves into the V3 context

- **Behaviour:** `BabbageNonDisjointRefInputs` applies to protocol versions 9 and 10 only. From 11 a transaction may spend and reference the same output, unless a Plutus V3 script runs: building its context fails with `CollectErrors (BadTranslation ReferenceInputsNotDisjointFromInputs)`. Plutus V1 and V2 contexts have no such check.
- **Era or protocol-version gate:** Protocol 9, 10, 11.
- **Upstream reference:** cardano-ledger `Babbage/Rules/Utxo.hs:209-216`; `Conway/TxInfo.hs:512`.
- **Chain evidence:** Devnet: refused at 10, accepted without scripts at 11, refused for a V3 mint at 11. Roadmap B5.
- **Pinned by:** [`ConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/ConwayRulesSuite.scala), [`DevnetRefusedTxSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/ledger/DevnetRefusedTxSuite.scala)
- **Status:** confirmed in source

#### IN-39 The V1 cost model is a map of named integers in sorted name order, not file order

- **Behaviour:** The genesis cost model is a map of named integers whose canonical order for the script integrity hash is the sorted name order. Using file order caused about 25% of script integrity hash mismatches.
- **Era or protocol-version gate:** Alonzo and later.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview Alonzo and Babbage sync: after the fix 333,607 hashes matched to epoch 74 (commits 990f620, f8209b4).
- **Pinned by:** [`ScriptIntegritySuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/ScriptIntegritySuite.scala)
- **Status:** observed

#### IN-40 Script data hash encoding

- **Behaviour:** `blake2b-256(redeemers, datums, language views)` over the witness set's own bytes. Datums are omitted when empty. PlutusV1's cost model is a byte string wrapping an indefinite list, under the byte-string key `0x4100`. V2 and V3 are plain. Keys are in canonical order (V2, V3, then V1). No languages is `0xa0`.
- **Era or protocol-version gate:** Alonzo onwards.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview, 333,607 hashes (see the previous entry).
- **Pinned by:** [`ScriptIntegritySuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/ScriptIntegritySuite.scala)
- **Status:** observed

#### IN-41 Empty redeemers in the script data hash

- **Behaviour:** An absent redeemers field is hashed as the encoding of an empty `Redeemers` in the era's lowest protocol version: an empty list `0x80` before Conway, an empty map `0xa0` from Conway. An explicit empty array in the witness set is hashed as written.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** cardano-ledger `Cardano.Ledger.Alonzo.TxWits` (`RedeemersRaw`); `Cardano.Ledger.Alonzo.Tx` (`originalBytes (ScriptIntegrity ...)`).
- **Chain evidence:** Whole Preview chain: about 67,700 Babbage and 6,150 Conway transactions carry datums, no redeemers and no Plutus language, and the recomputed hash equals the chain's in every case. No Alonzo case exists. Roadmap B6.
- **Pinned by:** [`PreviewEmptyRedeemersSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/PreviewEmptyRedeemersSuite.scala), [`fixtures/preview/empty-redeemers.hex`](../fixtures/preview/empty-redeemers.hex)
- **Status:** confirmed in source

#### IN-42 Redeemers are a map keyed by purpose; a duplicate counts once

- **Behaviour:** Redeemers are held in a map keyed by purpose. In an Alonzo-style array witness that lists several redeemers for one purpose, the last entry counts. In the Conway map form a redeemer listed twice is counted once in the fee. See IN-68 in Part B.
- **Era or protocol-version gate:** Alonzo array form and Conway map form.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview slot 12,924,758 (six redeemers for one purpose; commit d8dc65d); slot 62,567,517 (commit 32bc14f).
- **Pinned by:** [`fixtures/preview/duplicate-redeemer-conway.hex`](../fixtures/preview/duplicate-redeemer-conway.hex), [`ScriptContextGoldenSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/ScriptContextGoldenSuite.scala), `fixtures/preview/plutus/slot12924758-e5e7f3de.json`
- **Status:** observed

#### IN-43 Builtin availability depends on ledger language and protocol version

- **Behaviour:** Which builtins a script may use depends on the language and the protocol major version (V1 gets batches 2 to 6 only at protocol 11; V2 batch 3 at 8, batch 4b at 10, the rest at 11; V3 batch 4 at 9, batch 5 at 10, batch 6 at 11). Deserialisation rejects the others. From Babbage the ledger deserialises every Plutus script of the witness set and of the outputs (the collateral return included) in phase 1 (`MalformedScriptWitnesses`, `MalformedReferenceScripts`). In Alonzo an undecodable script a transaction needs fails phase 2. The UPLC program version is checked in evaluation (`1.1.0` with `constr` and `case`, for V1 and V2 from protocol 11, for V3 from 9).
- **Era or protocol-version gate:** Per language and protocol version, as listed.
- **Upstream reference:** plutus-ledger-api 1.70.0.0 `PlutusLedgerApi.Common.Versions.builtinsAvailableIn`; cardano-ledger `validateScriptsWellFormed`; `mkTermToEvaluate`.
- **Chain evidence:** Preview from genesis to epoch 168: 975,398 Plutus transactions evaluated successfully and 86 to failure, every verdict equal to `is_valid`. Roadmap B9.
- **Pinned by:** [`PlutusAvailabilitySuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/PlutusAvailabilitySuite.scala), [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala)
- **Status:** confirmed in source

#### IN-44 V1 and V2 `TxInfo` differ from V3; interval bounds change at Conway

- **Behaviour:** V1 rejects inline datums, reference scripts and, before Conway, reference inputs. From Conway reference inputs are not part of the V1 context. The upper bound of a validity interval without a lower bound is inclusive before Conway and exclusive from it. The V3 context holds `NewConstitution` as `[previous, Constr 0 [Maybe ScriptHash]]`.
- **Era or protocol-version gate:** Conway for the interval and reference-input points.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview slot 65,493,476 (a V1 script with reference inputs; commit d8315bd) and slot 116,688,298 (`NewConstitution`; commit 5bc16a2).
- **Pinned by:** [`ScriptContextGoldenSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/ScriptContextGoldenSuite.scala), `fixtures/preview/plutus/slot65493476-9f538f7a.json`, `fixtures/preview/plutus/slot116688298-89d3a627.json`
- **Status:** observed

#### IN-45 Bytes after a Plutus script's CBOR item are ignored for V1 and V2, an error for V3

- **Behaviour:** `deserialiseScript` decodes the leading CBOR byte string with `deserialiseFromBytes`, which returns the remainder, and raises `RemainderError` only when the language is neither V1 nor V2. The flat decoder, the builtin table and the language-introduction check apply to the first item as before.
- **Era or protocol-version gate:** V1 and V2 ignore the remainder; V3 fails.
- **Upstream reference:** plutus-ledger-api `PlutusLedgerApi/Common/SerialisedScript.hs` lines 251-263.
- **Chain evidence:** Preprod tx aee75c1c (block 4,045,972, slot 105,654,302, epoch 248, protocol 10): an output carries the V2 reference script 5272e713 as a 1078-byte item twice (2156 bytes), created by this transaction. Roadmap B16.
- **Pinned by:** [`PreprodMalformedRefScriptSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodMalformedRefScriptSuite.scala), [`fixtures/preprod/tx-aee75c1c.hex`](../fixtures/preprod/tx-aee75c1c.hex), [`PlutusScriptBytesSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/PlutusScriptBytesSuite.scala)
- **Status:** confirmed in source

#### IN-46 Native script `n_of_k`: `n` is a signed 64-bit integer; slots are unsigned 64-bit

- **Behaviour:** The Allegra `Timelock` (`TimelockMOf !Int`) decodes `n` with cborg `decodeInt`: any CBOR integer from -2^63 to 2^63 - 1, negative allowed. A value outside that range, a bignum or a non-integer fails the decode. The slots are `SlotNo`, a `Word64`. The key hash is exactly 28 bytes. In `evalTimelock` (`isValidMOf`), `n <= 0` holds with any signers; otherwise `n` children must hold, so `n` above the list length fails. See IN-67 in Part B.
- **Era or protocol-version gate:** Allegra onwards.
- **Upstream reference:** cardano-ledger Allegra `Timelock`, `evalTimelock` (`isValidMOf`); file and line not recorded in our docs.
- **Chain evidence:** Preprod tx b1db2a41 (block 5,184,855, slot 133,902,219, epoch 313) carries `[3, -1, [sig, sig]]`. Roadmap B19.
- **Pinned by:** [`PreprodNOfKSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodNOfKSuite.scala), [`fixtures/preprod/tx-b1db2a41.hex`](../fixtures/preprod/tx-b1db2a41.hex)
- **Status:** confirmed in source

#### IN-47 No nesting limit below the size of the block

- **Behaviour:** The `Timelock` decoder, `decodeMetadatum` and `PlutusCore.Data.decodeData` recurse without a depth argument on a stack that grows on the heap. Their only bounds are the 64-byte limits on leaves and the size of the block. A native script nested 5,383 levels (CBOR depth 10,772) is valid.
- **Era or protocol-version gate:** Not gated.
- **Upstream reference:** cardano-ledger `Timelock` decoder (`Summands`, `TimelockRaw`); `Cardano/Ledger/Metadata.hs` (`decodeMetadatum`); `PlutusCore.Data.decodeData`.
- **Chain evidence:** Preprod tx f90dce57 (block 5,183,974, slot 133,883,340, epoch 313, 16,382 bytes): `all [all [ ... all [sig]]]` 5,383 levels deep in the witness set. Roadmap B17, B18.
- **Pinned by:** [`DeepNestingSuite`](../storage/src/test/scala/xyz/kd5ujc/scarab/storage/DeepNestingSuite.scala), [`fixtures/preprod/tx-f90dce57.hex`](../fixtures/preprod/tx-f90dce57.hex)
- **Status:** confirmed in source

#### IN-48 `verifyEcdsaSecp256k1Signature` returns `False` for zero `r` or `s`, and for a high `s`

- **Behaviour:** The builtin fails only when the key is not 33 bytes or not a point, the message is not 32 bytes, or the signature is not 64 bytes or has `r` or `s` at least the group order. A zero `r` or `s` parses and `secp256k1_ecdsa_verify` answers 0, which is `False`. A high `s` is `False` too (libsecp256k1 verifies only normalised signatures and Plutus does not normalise). `verifySchnorrSecp256k1Signature` behaves alike: any 64-byte signature, everything else `False`.
- **Era or protocol-version gate:** Plutus V2 onwards.
- **Upstream reference:** `PlutusCore.Crypto.Secp256k1` over `Cardano.Crypto.DSIGN.EcdsaSecp256k1` and libsecp256k1 (`secp256k1_ec_pubkey_parse`, `secp256k1_ecdsa_signature_parse_compact`).
- **Chain evidence:** Preprod tx 97b31e35 (slot 92,114,397, epoch 217, Plutus V2) calls the builtin with an all-zero signature. Roadmap B15.
- **Pinned by:** [`PreprodSecp256k1RulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodSecp256k1RulesSuite.scala), [`Secp256k1Suite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/Secp256k1Suite.scala), [`fixtures/preprod/tx-97b31e35.hex`](../fixtures/preprod/tx-97b31e35.hex)
- **Status:** confirmed in source

#### IN-49 `serialiseData` writes a constructor index outside 0 to 127 as an unsigned 64-bit integer

- **Behaviour:** `PlutusCore.Data.encodeData` writes the index of a constructor outside 0 to 127 after tag 102 as an unsigned 64-bit integer, and any other integer as a big number. An implementation that stores the index in a signed `Long` turns `2^64 - 1` into `-1` (`d866822080` instead of `d866821bffffffffffffffff80`) and `2^64 + 200` into `200`. Two different `Data` then serialise alike, and a script that hashes serialised data takes another path.
- **Era or protocol-version gate:** Not gated.
- **Upstream reference:** `PlutusCore.Data.encodeData`; file and line not recorded in our docs.
- **Chain evidence:** Preprod tx 2edd684f (slot 80,239,053, epoch 189): a Hydra head fanout whose outputs carry datums with such indices. Filed as B12 in findings.md (the same label as the value-size row of the roadmap).
- **Pinned by:** [`PreprodConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodConwayRulesSuite.scala), `fixtures/preprod/plutus/tx-2edd684f.json`, [`DataSerialiserSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/DataSerialiserSuite.scala)
- **Status:** confirmed in source

#### IN-50 BLS12-381 in the reference is built portable, without `mulx`/`adcx`

- **Behaviour:** The reference node builds blst v0.3.15 with `./build.sh -D__BLST_PORTABLE__`. An implementation that links a blst build with ADX/BMI2 instructions and no run-time switch dies with SIGILL on a CPU without them. The first script that calls `bls12_381_G1_uncompress` triggers it, with no error event.
- **Era or protocol-version gate:** Plutus V3 BLS builtins; a deployment point, not a ledger rule.
- **Upstream reference:** iohk-nix `overlays/crypto/libblst.nix`; `cardano-crypto-class` needs `libblst >= 0.3.14`.
- **Chain evidence:** A preprod follower on an older 2-core x86-64 CPU, exit 134 in `libblst.so` `mulx_mont_384`. Roadmap B25, [ADR 0012](adr/0012-portable-blst.md).
- **Pinned by:** `BlstSuite`, `BlstCheckMain`
- **Status:** confirmed in source

### Byron

#### IN-51 Byron epoch-boundary blocks repeat the block number; Praos nonces stay at the genesis hash across Byron

- **Behaviour:** A Byron epoch-boundary block repeats its predecessor's block number, and the main block after it may share its slot. At the Byron to Shelley boundary every Praos nonce equals the Shelley genesis hash and Byron blocks do not touch it.
- **Era or protocol-version gate:** Byron.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet `up-eras` (commits 7b4b08f, c7054c9).
- **Pinned by:** [`EraTransitionSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/EraTransitionSuite.scala), `fixtures/eras`
- **Status:** observed

#### IN-52 Byron PBFT validation

- **Behaviour:** `updateChainDepState` checks the delegate's signature, `slot >= last signed slot` (equal passes), that the signer is the delegate of a genesis key in the ledger view, and that the key signed at most `floor(threshold * k)` of the last `k` blocks (0.22 by default, 0.6 on the `cardano-testnet` devnet). The round-robin leader (`slot mod n`) is used only for forging, not validation. The genesis key in the signed tag is the header's own. The ledger view is the delegation map with scheduled delegations activated up to the block's slot. Only signature tag 2 (heavy delegation) decodes.
- **Era or protocol-version gate:** Byron.
- **Upstream reference:** ouroboros-consensus `Protocol/PBFT.hs` (`updateChainDepState`).
- **Chain evidence:** Preprod Byron era (45 main blocks) and devnet. Roadmap C2.
- **Pinned by:** [`ByronPreprodSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ByronPreprodSuite.scala), [`EraTransitionSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/EraTransitionSuite.scala)
- **Status:** confirmed in source

#### IN-53 Byron delegation certificate signature and activation

- **Behaviour:** The signed message is the certificate tag and magic followed by the CBOR byte string of `"00" ++ delegate key ++ cbor(epoch)`: the ASCII digits, then a CBOR wrapper added by `serialize'` of a `ByteString`. A certificate in a block takes effect `2k` slots later. A delegate can serve one genesis key.
- **Era or protocol-version gate:** Byron.
- **Upstream reference:** cardano-ledger `Delegation/Certificate.hs`, `Validation/Scheduling.hs`.
- **Chain evidence:** Genesis certificates of the devnet and preprod. Roadmap C2.
- **Pinned by:** [`ByronUpdateSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/byron/ByronUpdateSuite.scala)
- **Status:** confirmed in source

#### IN-54 Byron to Shelley transition epoch comes from the update state

- **Behaviour:** A candidate for protocol major 2 is stable after `k` blocks since it became a candidate (or `2k` slots) and is adopted in `succ(epochOf(candidateSlot + 4k))`. The Byron ledger is not ticked into that epoch.
- **Era or protocol-version gate:** Byron to Shelley, unless `TestShelleyHardForkAtEpoch` is set.
- **Upstream reference:** ouroboros-consensus `ByronHFC.hs`, `byronTransition`.
- **Chain evidence:** Preprod: proposals for 1.0.0 (epoch 0) and 2.0.0 (epoch 2); transition derived as epoch 4, equal to the network's. Roadmap C4.
- **Pinned by:** [`ByronPreprodSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ByronPreprodSuite.scala), [`ByronUpdateSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/byron/ByronUpdateSuite.scala)
- **Status:** confirmed in source

#### IN-55 Byron address root, redeem addresses and fee-free redeem transactions

- **Behaviour:** The root is `blake2b-224(sha3-256(cbor [type, [type, key], attributes]))` with the original attribute map; a verification key is the 64-byte extended key. A redeem address is the same with the 32-byte redeem key. A genesis balance spends from a pseudo transaction whose id is the hash of the address encoding. Witnesses are zipped with the inputs (extras ignored). A transaction whose inputs are all redeem addresses pays no fee.
- **Era or protocol-version gate:** Byron.
- **Upstream reference:** cardano-ledger `Address.hs`, `UTxO/Validation.hs`, `GenesisUTxO.hs`.
- **Chain evidence:** Real mainnet Byron blocks. Roadmap C1.
- **Pinned by:** [`ByronMainnetSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/byron/ByronMainnetSuite.scala)
- **Status:** confirmed in source

### Consensus and headers

#### IN-56 Chain selection at equal length

- **Behaviour:** `comparePraos` compares opcert issue numbers only when issuer and slot are equal (`issueNoArmed`; equal slot and issuer imply equal VRF). Otherwise the strictly lower VRF output wins, compared as unsigned bytes, where the output is the raw certified output of the header. Before Conway the VRF comparison is always armed (`UnrestrictedVRFTiebreaker`); from Conway it is armed when the slot distance is at most 5 (`RestrictedVRFTiebreaker 5`, inclusive). The flavour follows the era of the block.
- **Era or protocol-version gate:** Conway for the restriction.
- **Upstream reference:** ouroboros-consensus 4.2.0.0 `Protocol/Praos/Common.hs` lines 125-168 (line 154: `issueNoArmed`); `Shelley/Protocol/TPraos.hs` line 142; `Protocol/Praos.hs` line 207 (`hbVrfRes`); `Shelley/Ledger/Config.hs` lines 86-93. Unchanged on master 5.0.0.0.
- **Chain evidence:** Read from source. Roadmap B1.
- **Pinned by:** [`ChainSelectionSuite`](../consensus/src/test/scala/xyz/kd5ujc/scarab/consensus/ChainSelectionSuite.scala)
- **Status:** confirmed in source

#### IN-57 Candidate nonce freeze point: 3k/f in Alonzo and Babbage, 4k/f from Conway

- **Behaviour:** The candidate nonce freezes `3k/f` slots before the epoch end in Alonzo and Babbage, and `4k/f` from Conway. The stability window is era dependent.
- **Era or protocol-version gate:** Conway.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview (TPraos work, commit 592399e); devnet nonce comparison.
- **Pinned by:** [`TPraosSuite`](../consensus/src/test/scala/xyz/kd5ujc/scarab/consensus/TPraosSuite.scala)
- **Status:** observed

#### IN-58 The TPraos nonce contribution hashes the 64-byte VRF output

- **Behaviour:** The nonce contribution is `blake2b256` of the 64-byte VRF output.
- **Era or protocol-version gate:** TPraos.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preview.
- **Pinned by:** [`TPraosSuite`](../consensus/src/test/scala/xyz/kd5ujc/scarab/consensus/TPraosSuite.scala)
- **Status:** observed

#### IN-59 TPraos overlay schedule and the absence of a threshold check in overlay slots

- **Behaviour:** `position = ceiling(s * d)`; a slot is active when `position mod floor(1/f) = 0`. Overlay-slot headers have no leader-threshold check. The threshold needs the `set` snapshot pool distribution, so Preview epochs 0 and 1 (d = 1) are overlay-only.
- **Era or protocol-version gate:** While `d > 0`.
- **Upstream reference:** cardano-ledger `classifyOverlaySlot`; file not recorded in our docs.
- **Chain evidence:** Preview epochs 0 and 1.
- **Pinned by:** [`TPraosSuite`](../consensus/src/test/scala/xyz/kd5ujc/scarab/consensus/TPraosSuite.scala), `LeaderCheck.Threshold`
- **Status:** confirmed in source

#### IN-60 Header envelope checks and the timing of era entry

- **Behaviour:** `envelopeCheck` rejects `ObsoleteNode` when the major version exceeds the node's maximum (11 for node 11.1.3), where that version is the header's for TPraos and the ledger's ticked version for Praos. It then checks header size and body size against the ticked parameters. Conway BBODY adds, on mainnet only, `HeaderProtVerTooHigh` when the header's major exceeds the ledger's plus one. The hard-fork combinator enters an era at the epoch where the ledger's protocol major version reaches the era's `eraProtVerLow`, unless `Test<Era>HardForkAtEpoch` is set.
- **Era or protocol-version gate:** Mainnet-only for `HeaderProtVerTooHigh`.
- **Upstream reference:** ouroboros-consensus 4.2.0.0 `Shelley/Protocol/EnvelopeChecks.hs` 77-91; `Protocol/TPraos.hs` 70-80; `Protocol/Praos.hs` 85-110; `ShelleyHFC.hs` 200-250; `Cardano/Node.hs` 355-365; cardano-node `Cardano/Node/Protocol/Cardano.hs` line 192 and 204-260; cardano-ledger `Conway/Rules/Bbody.hs` 312-328.
- **Chain evidence:** Preview from genesis: Babbage entered by version at epoch 3, block 13012; 486k blocks to epoch 123, 0 errors. Roadmap B13.
- **Pinned by:** [`HardForkSuite`](../consensus/src/test/scala/xyz/kd5ujc/scarab/consensus/HardForkSuite.scala), [`EraTransitionSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/EraTransitionSuite.scala)
- **Status:** confirmed in source

#### IN-61 The first header of a chain that starts at Origin names the Byron genesis hash as its parent

- **Behaviour:** A header-first validator must accept any parent for the first header, since the parent is the Byron genesis hash rather than nothing.
- **Era or protocol-version gate:** Chains that start at Origin.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Preprod from genesis with header-first validation.
- **Pinned by:** [`HeaderGateSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/HeaderGateSuite.scala)
- **Status:** observed

### Network, mini-protocols and queries

#### IN-62 ChainSync intersection candidates are limited to 64

- **Behaviour:** A reconnect that offers more than 64 intersection candidates fails; the candidates must be thinned.
- **Era or protocol-version gate:** Not gated.
- **Upstream reference:** none cited in our docs (protocol limit).
- **Chain evidence:** Pipelined Preview sync.
- **Pinned by:** [`PeerSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/PeerSuite.scala)
- **Status:** observed

#### IN-63 A BlockFetch range of full blocks can exceed the 10 MB per-request limit

- **Behaviour:** A range is cut by announced body size, because a range of full blocks can exceed the per-request byte limit.
- **Era or protocol-version gate:** Not gated.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Pipelined Preview sync (commit 7351502).
- **Pinned by:** [`PeerSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/PeerSuite.scala)
- **Status:** observed

#### IN-64 `GetSystemStart` answers with the Byron genesis `startTime`, and that start is the slot clock

- **Behaviour:** `GetSystemStart` returns the Byron genesis `startTime` (whole seconds). The Shelley genesis `systemStart` of a devnet can have a fractional part and a different second. The reference's slot clock uses the Byron start: with the Byron `startTime` 5 s before the Shelley `systemStart`, the reference's tip was slot 82 when the wall clock read 10.0 s after the Byron start. A node that takes the Shelley start has slots a whole second off the network's.
- **Era or protocol-version gate:** Networks with a Byron genesis.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet `pool` scenario (roadmap H6).
- **Pinned by:** [`SystemStartSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/SystemStartSuite.scala), [`QueryGoldenSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/QueryGoldenSuite.scala)
- **Status:** observed

#### IN-65 The Alonzo genesis of a Babbage-era cluster carries the PlutusV2 cost model in `extraConfig`

- **Behaviour:** The V2 cost model is in `extraConfig.costModels`, not in `costModels` (V1 only). The ledger merges them, so the reference's protocol parameters have three languages (V1, V2 and the Conway genesis' V3). A reader that takes `costModels` alone has two.
- **Era or protocol-version gate:** Babbage-era clusters.
- **Upstream reference:** none cited in our docs.
- **Chain evidence:** Devnet `ntc`: `GetCurrentPParams` differed in the number of cost models.
- **Pinned by:** [`QueryGoldenSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/QueryGoldenSuite.scala)
- **Status:** observed

## Part B: specification or CDDL versus implementation

14 entries. The first six concern the ledger and Plutus specifications, the next six the Scalus Plutus library (our Plutus evaluator is built on it), and the last two the Leios proposal. Scalus is not the reference; it is listed because an implementation that uses it meets the same differences.

### IN-66 Pointer address fields: unbounded in the encoding, bounded and era-dependent in the decoder

- **Era:** Shelley to Conway (decoder version 9 is the gate)
- **Specification or CDDL says:** A pointer address carries three variable-length natural numbers (slot, transaction index, certificate index) with no stated upper bound. The address format description gives no range.
- **Implementation does:** The ledger decodes the slot as `Word32` and the two indexes as `Word16` (`decodePtr`). From decoder version 9 (Conway) it refuses an address with a larger value. Below version 9 (`decodePtrLenient`) it reads `Word64` with wrap-around and normalises a value beyond the fields to the dangling pointer (0, 0, 0). A decoder that keeps the exact value in every era accepts a Conway transaction that the reference refuses. The long run of continuation bytes that such an address can carry also costs quadratic time in a naive big-integer decoder.
- **Evidence:** Found by the decoder fuzzer (`address-and-output`, allocation bound) and by reading the ledger source. See also the pre-Babbage entries on pointer normalisation in Part A.
- **Pinned by:** cardano-ledger `Cardano.Ledger.Address` at `f649f975`, `decodePtr`, `decodePtrLenient`. [`DecoderRobustnessSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/DecoderRobustnessSuite.scala) pins the linear-time decode only; the era-dependent rule is not implemented in our node (open).
- **Suggested upstream action:** cardano-ledger: state the ranges and the version gate in the address documentation and in the CDDL comments next to the pointer address; formal ledger specification: say what a pointer outside the range means.
- **Status:** confirmed in source

### IN-67 Native script integers: CDDL `int` and `uint` are unbounded, the decoder is 64-bit

- **Era:** Allegra onwards
- **Specification or CDDL says:** The native-script CDDL types the `n` of `script_n_of_k` as `int` and the slots of `invalid_before` and `invalid_hereafter` as `uint`. Neither type states a bound.
- **Implementation does:** The decoder reads `n` as `Int` (cborg `decodeInt`, -2^63 to 2^63 - 1, negative allowed) and slots as `SlotNo` (`Word64`). A bignum or a value outside the range fails the decode. A decoder written from the CDDL alone reads `n` as unsigned and refuses a script the chain accepted, or accepts a value the reference refuses.
- **Evidence:** Preprod tx b1db2a41 (block 5,184,855, slot 133,902,219, epoch 313): `[3, -1, [sig, sig]]`, accepted by the chain. Roadmap B19.
- **Pinned by:** [`PreprodNOfKSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodNOfKSuite.scala) (properties at the 64-bit boundaries against a transcription of the reference).
- **Suggested upstream action:** cardano-ledger CDDL (`eras/allegra` native script section): add the 64-bit bounds as comments or `.le` constraints, and note that `n <= 0` is satisfied by any signer set.
- **Status:** confirmed in source

### IN-68 Redeemers: CDDL map keys are unique, the ledger accepts a duplicate and keeps one

- **Era:** Alonzo array form, Conway map form
- **Specification or CDDL says:** In the Conway CDDL the redeemers are a map from `[tag, index]` to `[data, ex_units]`. A CDDL map has unique keys. The Alonzo form is an array.
- **Implementation does:** Chain blocks contain a redeemer list or map in which the same purpose appears twice, and the ledger holds redeemers in a map keyed by purpose. In the array form the last entry counts. In the Conway map form a redeemer listed twice is counted once in the fee. An implementation that counts entries, or that rejects a duplicate key, differs from the chain.
- **Evidence:** Preview slot 12,924,758 (six redeemers for one purpose) and slot 62,567,517 (a redeemer listed twice in the map form).
- **Pinned by:** [`fixtures/preview/duplicate-redeemer-conway.hex`](../fixtures/preview/duplicate-redeemer-conway.hex), `fixtures/preview/plutus/slot12924758-e5e7f3de.json`, [`ScriptContextGoldenSuite`](../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/ScriptContextGoldenSuite.scala). No upstream citation recorded in our docs, so the status is observed.
- **Suggested upstream action:** cardano-ledger: document in the CDDL comments that decoders keep one entry per purpose and which one; consider whether a duplicate key should be a decode failure from a later protocol version.
- **Status:** observed

### IN-69 Value size limit: defined on serialised size, measured on the ledger's re-encoding

- **Era:** Allegra to Conway
- **Specification or CDDL says:** The rule limits the size of the serialised value (4000 bytes in Allegra and Mary, `maxValueSize` later). A reader takes this to mean the bytes in the transaction.
- **Implementation does:** The size is that of the ledger's own encoding. A map of more than 23 entries is written indefinite-length, so the measured size can be one byte below the minimal definite form (and below the transaction's own bytes).
- **Evidence:** Preprod tx 06869a76 (block 1,558,703): 500 assets under one policy, 5001 bytes definite, 5000 in the ledger, accepted by the chain. Roadmap B12.
- **Pinned by:** [`PreConwayRulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreConwayRulesSuite.scala) (CBOR cross-check), [`fixtures/preprod/tx-06869a76.hex`](../fixtures/preprod/tx-06869a76.hex). The upstream references are in the Part A entry on output value size.
- **Suggested upstream action:** formal ledger specification and cardano-ledger documentation: state that the size is of the re-encoded value and give the indefinite-map threshold.
- **Status:** confirmed in source

### IN-70 Plutus script bytes: nothing in the CDDL says that V1 and V2 ignore a remainder

- **Era:** Babbage onwards
- **Specification or CDDL says:** The CDDL types a Plutus script as `bytes`, and the ledger API describes it as a CBOR-wrapped flat program. No text says what follows the first CBOR item.
- **Implementation does:** `deserialiseScript` ignores the remainder for V1 and V2 and raises `RemainderError` for V3. A reference script that carries its item twice (2156 bytes) is valid on chain and is the same script.
- **Evidence:** Preprod tx aee75c1c (block 4,045,972, protocol 10). Roadmap B16.
- **Pinned by:** [`PreprodMalformedRefScriptSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodMalformedRefScriptSuite.scala), [`PlutusScriptBytesSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/PlutusScriptBytesSuite.scala). Upstream reference in the Part A entry on script bytes.
- **Suggested upstream action:** Plutus (plutus-ledger-api): document the language-dependent remainder rule next to `deserialiseScript`.
- **Status:** confirmed in source

### IN-71 Alonzo genesis: the PlutusV2 cost model sits in `extraConfig`, which the genesis documentation does not mention

- **Era:** Babbage-era clusters
- **Specification or CDDL says:** The Alonzo genesis file lists cost models under `costModels`. For a cluster that starts in Babbage, only V1 is there.
- **Implementation does:** The V2 model is in `extraConfig.costModels`, and the ledger merges both. The resulting protocol parameters carry three languages once the Conway genesis' V3 is added.
- **Evidence:** Devnet `ntc`: `GetCurrentPParams` differed in the number of cost models between the two nodes.
- **Pinned by:** [`QueryGoldenSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/QueryGoldenSuite.scala), [`NtcGoldenSuite`](../node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/NtcGoldenSuite.scala). No upstream citation recorded in our docs, so the status is observed.
- **Suggested upstream action:** cardano-node / cardano-ledger genesis documentation: describe `extraConfig` and the merge.
- **Status:** observed

### IN-72 Scalus: `verifyEd25519Signature` costing under semantics variant A

- **Era:** Plutus, semantics variant A
- **Specification or CDDL says:** The Plutus cost model for `verifyEd25519Signature` under variant A does not depend on the message length.
- **Implementation does:** Scalus 1.2.0 costs it wrongly under variant A; the same code is in Scalus 1.3.0 and on its default branch (2eb9f3d, 2026-10-11). Our wrapper corrects it.
- **Evidence:** Preview Alonzo sync (commit f8209b4). Filed as [scalus3/scalus#387](https://github.com/scalus3/scalus/issues/387). Roadmap B11.
- **Pinned by:** [`BuiltinCostingSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/BuiltinCostingSuite.scala) (cost independent of the message under variant A, linear under B to E), [`ScalusReproducersSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/ScalusReproducersSuite.scala) (fails when Scalus is fixed).
- **Suggested upstream action:** Scalus: fix the costing (issue filed, #387).
- **Status:** observed

### IN-73 Scalus: the V3 `NewConstitution` context

- **Era:** Conway
- **Specification or CDDL says:** The V3 script context holds a new constitution as `[previous, Constr 0 [Maybe ScriptHash]]`; `Constitution` is a constructor of its own.
- **Implementation does:** Scalus 1.2.0 builds it without the `Constitution` constructor, `[previous, Maybe ScriptHash]`. Unchanged in Scalus 1.3.0 and on its default branch.
- **Evidence:** Preview slot 116,688,298 (commit 5bc16a2). Filed as [scalus3/scalus#388](https://github.com/scalus3/scalus/issues/388). Roadmap B11.
- **Pinned by:** `fixtures/preview/plutus/slot116688298-89d3a627.json`, `ScalusDeviations`, [`ScalusReproducersSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/ScalusReproducersSuite.scala).
- **Suggested upstream action:** Scalus: fix the context (issue filed, #388).
- **Status:** observed

### IN-74 Scalus: conformance deviations from the Plutus reference

- **Era:** Plutus, protocol 11 and 12
- **Specification or CDDL says:** The Plutus conformance suite (plutus 1.70.0.0, 1009 evaluation cases) fixes the behaviour of the reference.
- **Implementation does:** Scalus 1.2.0 and 1.3.0 pass the same 987. Differences: (1) the flat decoder accepts `value` constants that `buildValueWith` rejects (unsorted or repeated currency symbols or token names, an empty inner map, a zero quantity); (2) `case` on a `Data.Constr` is enabled from protocol 12 in 1.3.0, so `data-01` and `data-02` fail at protocol 11; (3) `bls12_381_G1/G2_hashToGroup` with a 255-byte DST, and with a 32-byte DST of arbitrary bytes, returns another point; (4) the flat encoder writes a BLS12-381 constant as an application of `uncompress`, and the units spent after an out-of-budget `dropList` with a huge count wrap negative.
- **Evidence:** Roadmap I1. None of these has been filed upstream yet.
- **Pinned by:** [`ScalusConformanceReproducersSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/ScalusConformanceReproducersSuite.scala), `PlutusConformanceSuite`.
- **Suggested upstream action:** Scalus: file the four items (not filed yet).
- **Status:** observed

### IN-75 Scalus: `verifyEcdsaSecp256k1Signature` raises where the reference returns `False`

- **Era:** Plutus V2 onwards
- **Specification or CDDL says:** See the Part A entry on secp256k1: zero `r` or `s` and a high `s` give `False`.
- **Implementation does:** Scalus 1.2.0 and 1.3.0 require `r > 0` and `s > 0` and raise an error.
- **Evidence:** Preprod tx 97b31e35 (slot 92,114,397, epoch 217). Roadmap B15. Not filed upstream yet.
- **Pinned by:** [`PreprodSecp256k1RulesSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodSecp256k1RulesSuite.scala), [`Secp256k1Suite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/Secp256k1Suite.scala).
- **Suggested upstream action:** Scalus: change the builtin to the libsecp256k1 semantics (not filed yet).
- **Status:** confirmed in source

### IN-76 Scalus: trailing bytes after a V1 or V2 script

- **Era:** Babbage onwards
- **Specification or CDDL says:** See the Part A entry on script bytes: V1 and V2 ignore a remainder.
- **Implementation does:** `DeBruijnedProgram.fromCbor` fails on any trailing input.
- **Evidence:** Preprod tx aee75c1c. Roadmap B16. Not filed upstream yet.
- **Pinned by:** [`PreprodMalformedRefScriptSuite`](../legacy/src/test/scala/xyz/kd5ujc/scarab/legacy/rules/PreprodMalformedRefScriptSuite.scala).
- **Suggested upstream action:** Scalus: expose the first-item length or accept a remainder for V1 and V2 (not filed yet).
- **Status:** confirmed in source

### IN-77 Scalus: `serialiseData` constructor index, and a nesting limit in the encoder

- **Era:** Plutus V2 onwards
- **Specification or CDDL says:** See the Part A entry on `serialiseData`: an unsigned 64-bit index, no depth limit.
- **Implementation does:** Scalus 1.2.0 wrote the index through a `Long` (fixed in 1.3.0). The borer encoder of 1.3.0 still stops at 1000 levels of array nesting (`Exceeded 1000 maximum array/map nesting levels`), where the reference has no limit and a datum can be as deep as a block. The flat codec of `Data` constants shared the truncation in 1.2.0.
- **Evidence:** Preprod tx 2edd684f (slot 80,239,053, epoch 189). Not filed upstream yet.
- **Pinned by:** [`DataSerialiserSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/DataSerialiserSuite.scala), [`ScalusConformanceReproducersSuite`](../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/ScalusConformanceReproducersSuite.scala).
- **Suggested upstream action:** Scalus: remove the nesting limit in the `Data` encoder (not filed yet).
- **Status:** confirmed in source

### IN-78 Leios: the CIP, `dijkstra.cddl`, the Blueprint and the Rust specification disagree

- **Era:** Dijkstra (Leios, proposed)
- **Specification or CDDL says:** CIP-164 (revision `a2cac18039`) describes four mini-protocols, a 48-byte proof of possession and `voter_id : uint`. Its Appendix B CDDL is older than `dijkstra.cddl`.
- **Implementation does:** The Cardano Blueprint (`fe4cceb9`) documents two mini-protocols (LeiosNotify, number 18; LeiosFetch, number 19) and `voter_id` as `word16`. The Rust specification in `ouroboros-leios` describes the proof of possession as a 96-byte pair; the CIP and `dijkstra.cddl` use 48 bytes. We follow `dijkstra.cddl` and the `cardano-base` vectors, and the proof of possession regenerates byte for byte from the proto-devnet secret keys at 48 bytes.
- **Evidence:** [leios.md](leios.md) sections 1, 2, 4.2 and 6. The testnet relay closed our connection on protocol 18 before any reply (section 10), so nothing was verified on real traffic.
- **Pinned by:** `fixtures/leios`, `LeiosTestnetHandshakeSuite`.
- **Suggested upstream action:** CIP-164 (cardano-foundation/CIPs): amend the text and Appendix B to the Blueprint and `dijkstra.cddl`; ouroboros-leios: align the Rust specification's proof-of-possession size.
- **Status:** observed

### IN-79 Leios: BLS signer bitfield padding bits and identity points

- **Era:** Dijkstra (Leios, proposed)
- **Specification or CDDL says:** The certificate carries a signer bitfield of `ceil(N/8)` bytes.
- **Implementation does:** `cardano-base` ignores bits at or beyond the committee size, and rejects an identity signature or proof later (at verification and aggregation). We reject set padding bits and an identity point at decode. The accepted set of valid certificates is the same, because no honest producer sets padding bits, but the verdict on a crafted certificate differs in kind.
- **Evidence:** [leios.md](leios.md) section 9, 'Decisions where the sources are silent or differ'. No chain data.
- **Pinned by:** `SignersPaddingSet` in the Leios certificate decoder (see the design note).
- **Suggested upstream action:** ouroboros-leios / Blueprint: state whether padding bits and identity points are a decode failure or only a verification failure.
- **Status:** observed

## Part C: index

| ID | Title | Era | Status | Evidence |
|---|---|---|---|---|
| [IN-1](#in-1-a-byron-address-has-no-size-limit-of-its-own) | A Byron address has no size limit of its own | Byron | confirmed in source | mainnet slot 1,769,882 |
| [IN-2](#in-2-output-value-size-is-measured-on-the-ledgers-own-re-serialisation) | Output value size is measured on the ledger's own re-serialisation | Allegra, Mary (4000 bytes); Alonzo onwards (`maxValueSize`); none in Shelley | confirmed in source | preprod block 1,558,703 |
| [IN-3](#in-3-mary-minimum-output-uses-scaledmindeposit-with-divisor-27-and-distinct-asset-names) | Mary minimum output uses `scaledMinDeposit` with divisor 27 and distinct asset names | Mary (Shelley and Allegra use flat `minUTxOValue`) | confirmed in source | devnet |
| [IN-4](#in-4-before-babbage-bytes-after-a-valid-address-in-an-output-are-dropped-b28) | Before Babbage, bytes after a valid address in an output are dropped (B28) | Shelley to Alonzo | confirmed in source | mainnet slot 39,249,764, tx 369826b2 |
| [IN-5](#in-5-before-conway-an-out-of-range-pointer-address-is-normalised-and-its-address-is-written-again-b29) | Before Conway, an out-of-range pointer address is normalised, and its address is written again (B29) | Shelley to Babbage | confirmed in source | mainnet slot 26,366,727, tx 9a47e091 |
| [IN-6](#in-6-a-byron-input-can-be-witnessed-by-a-plain-vkey-whose-hash-is-the-address-root) | A Byron input can be witnessed by a plain vkey whose hash is the address root | Shelley onwards (seen in Conway) | confirmed in source | preprod block 3,158,876 |
| [IN-7](#in-7-shelleys-time-to-live-is-the-last-valid-slot) | Shelley's time to live is the last valid slot | Shelley (protocol version 2); Allegra onwards use the exclusive bound | confirmed in source | mainnet slot 4,498,780 |
| [IN-8](#in-8-the-fee-size-counts-array-header-body-witnesses-and-auxiliary-data-not-the-is_valid-byte) | The fee size counts array header, body, witnesses and auxiliary data, not the `is_valid` byte | Alonzo onwards | observed | Preview Babbage |
| [IN-9](#in-9-conway-reference-script-fee) | Conway reference-script fee | Conway | observed | Preview Conway blocks |
| [IN-10](#in-10-zero-lovelace-byron-genesis-outputs-are-dropped-at-the-byron-to-shelley-translation) | Zero-lovelace Byron genesis outputs are dropped at the Byron to Shelley translation | Byron to Shelley | confirmed in source | Preview epoch 1 |
| [IN-11](#in-11-the-reward-update-is-created-by-the-first-block-after-4kf-slots-into-the-epoch) | The reward update is created by the first block after 4k/f slots into the epoch | all eras | confirmed in source | devnet |
| [IN-12](#in-12-pre-babbage-reward-prefilter-only-accounts-registered-at-the-start-slot) | Pre-Babbage reward prefilter: only accounts registered at the start slot | before Babbage (previous epoch's protocol version at most 6) | confirmed in source | devnet |
| [IN-13](#in-13-pre-allegra-reward-aggregation-keeps-one-reward-per-account-a-leader-reward-before-a-member-reward) | Pre-Allegra reward aggregation keeps one reward per account, a leader reward before a member reward | Shelley (protocol 2); Allegra aggregates | confirmed in source | devnet |
| [IN-14](#in-14-the-expected-number-of-blocks-in-the-reward-calculation-is-floored) | The expected number of blocks in the reward calculation is floored | all eras | confirmed in source | none (read from source) |
| [IN-15](#in-15-only-the-stake-delegated-to-the-pool-counts-for-a-pool-owners-pledge) | Only the stake delegated to the pool counts for a pool owner's pledge | all eras with pledge | observed | Preview epoch 493 |
| [IN-16](#in-16-stake-snapshots-hold-non-zero-stake-only) | Stake snapshots hold non-zero stake only | Conway state (seen on Preview from epoch 18) | confirmed in source | Preview epoch 18 |
| [IN-17](#in-17-entering-allegra-returns-the-outputs-at-byron-redeem-avvm-addresses-to-the-reserves) | Entering Allegra returns the outputs at Byron redeem (AVVM) addresses to the reserves | Shelley to Allegra translation | confirmed in source | mainnet slot 17,020,941 |
| [IN-18](#in-18-mir-rules-by-protocol-version-and-deregistration-leaves-mir-entries-in-place) | MIR rules by protocol version, and deregistration leaves MIR entries in place | Shelley to Alonzo (gate at protocol 5) | confirmed in source | devnet |
| [IN-19](#in-19-pointer-addresses-do-not-count-in-the-stake-snapshot-at-the-babbage-to-conway-boundary) | Pointer addresses do not count in the stake snapshot at the Babbage to Conway boundary | Babbage to Conway | observed | Preview epoch 646 |
| [IN-20](#in-20-the-previous-parameters-of-the-first-conway-epoch-carry-the-conway-fields) | The previous parameters of the first Conway epoch carry the Conway fields | Babbage to Conway | confirmed in source | devnet eras capture, block 728 |
| [IN-21](#in-21-the-dormant-epoch-counter-must-be-translated-into-conway-correctly) | The dormant-epoch counter must be translated into Conway correctly | Babbage to Conway | observed | devnet |
| [IN-22](#in-22-genesis-delegations-survive-the-conway-translation) | Genesis delegations survive the Conway translation | Conway | observed | devnet epoch 10 |
| [IN-23](#in-23-protocol-9-drep-reverse-index-dormant-epochs-count-from-version-10) | Protocol-9 DRep reverse index; dormant epochs count from version 10 | Conway, protocol 9 and 10 | observed | devnet, Preview |
| [IN-24](#in-24-governance-state-in-the-first-epoch-and-just-after-a-boundary) | Governance state in the first epoch and just after a boundary | Conway | observed | devnet `ntc` |
| [IN-25](#in-25-a-pool-registrations-reward-account-network-is-checked-only-from-protocol-version-5) | A pool registration's reward-account network is checked only from protocol version 5 | Shelley, Allegra, Mary accept any network | confirmed in source | mainnet slot 4,495,760 |
| [IN-26](#in-26-a-registered-pools-reward-account-has-no-network-in-the-ledger-state) | A registered pool's reward account has no network in the ledger state | Allegra onwards (state shape of cardano-ledger-core 1.21.0.0) | confirmed in source | mainnet, pool ff5181ba |
| [IN-27](#in-27-pool-metadata-hash-larger-than-32-bytes-is-refused-from-protocol-version-41) | Pool metadata hash larger than 32 bytes is refused from protocol version 4.1 | protocol greater than (4, 0) | confirmed in source | none (read from source) |
| [IN-28](#in-28-vrf-key-hashes-are-unique-at-protocol-11) | VRF key hashes are unique at protocol 11 | Conway, protocol 11 | confirmed in source | devnet |
| [IN-29](#in-29-spo-default-votes-after-the-bootstrap-phase) | SPO default votes after the bootstrap phase | Conway, after bootstrap | confirmed in source | devnet |
| [IN-30](#in-30-the-committee-term-limit-is-applied-by-ratify-not-gov) | The committee term limit is applied by RATIFY, not GOV | Conway | confirmed in source | devnet |
| [IN-31](#in-31-committeeminsize-counts-active-members-and-applies-inside-a-ratification-run) | `committeeMinSize` counts active members and applies inside a ratification run | Conway | observed | devnet |
| [IN-32](#in-32-the-committee-state-is-trimmed-to-the-members-at-every-epoch-boundary) | The committee state is trimmed to the members at every epoch boundary | Conway | confirmed in source | devnet |
| [IN-33](#in-33-votes-by-unelected-committee-hot-keys-gov-refuses-from-protocol-11-the-mempool-before) | Votes by unelected committee hot keys: GOV refuses from protocol 11, the mempool before | Conway, protocol 10 and 11 | confirmed in source | devnet |
| [IN-34](#in-34-before-protocol-11-a-withdrawal-that-does-not-drain-its-account-is-withdrawalsnotinrewardscerts) | Before protocol 11 a withdrawal that does not drain its account is `WithdrawalsNotInRewardsCERTS` | Conway, protocol 10 and 11 | confirmed in source | devnet |
| [IN-35](#in-35-script-credentials-need-a-script-witness-per-purpose) | Script credentials need a script witness per purpose | Conway | observed | devnet |
| [IN-36](#in-36-the-mempool-rule-runs-before-ledger-certs-and-gov-run-before-utxow) | The MEMPOOL rule runs before LEDGER; CERTS and GOV run before UTXOW | Conway | observed | devnet `ntc` |
| [IN-37](#in-37-stake-distributions-in-queries-use-non-zero-totals) | Stake distributions in queries use non-zero totals | Conway | observed | devnet `ntc` |
| [IN-38](#in-38-disjoint-reference-inputs-the-check-ends-at-protocol-11-and-moves-into-the-v3-context) | Disjoint reference inputs: the check ends at protocol 11 and moves into the V3 context | Babbage and Conway, protocols 9 to 11 | confirmed in source | devnet |
| [IN-39](#in-39-the-v1-cost-model-is-a-map-of-named-integers-in-sorted-name-order-not-file-order) | The V1 cost model is a map of named integers in sorted name order, not file order | Alonzo | observed | Preview to epoch 74 |
| [IN-40](#in-40-script-data-hash-encoding) | Script data hash encoding | Alonzo onwards | observed | Preview |
| [IN-41](#in-41-empty-redeemers-in-the-script-data-hash) | Empty redeemers in the script data hash | before Conway: `0x80`; Conway: `0xa0` | confirmed in source | Preview whole chain |
| [IN-42](#in-42-redeemers-are-a-map-keyed-by-purpose-a-duplicate-counts-once) | Redeemers are a map keyed by purpose; a duplicate counts once | Alonzo array form, Conway map form | observed | Preview slots 12,924,758 and 62,567,517 |
| [IN-43](#in-43-builtin-availability-depends-on-ledger-language-and-protocol-version) | Builtin availability depends on ledger language and protocol version | Alonzo to Conway | confirmed in source | Preview to epoch 168 |
| [IN-44](#in-44-v1-and-v2-txinfo-differ-from-v3-interval-bounds-change-at-conway) | V1 and V2 `TxInfo` differ from V3; interval bounds change at Conway | Alonzo to Conway | observed | Preview |
| [IN-45](#in-45-bytes-after-a-plutus-scripts-cbor-item-are-ignored-for-v1-and-v2-an-error-for-v3) | Bytes after a Plutus script's CBOR item are ignored for V1 and V2, an error for V3 | Babbage onwards (seen at protocol 10) | confirmed in source | preprod block 4,045,972 |
| [IN-46](#in-46-native-script-n_of_k-n-is-a-signed-64-bit-integer-slots-are-unsigned-64-bit) | Native script `n_of_k`: `n` is a signed 64-bit integer; slots are unsigned 64-bit | Allegra onwards | confirmed in source | preprod block 5,184,855 |
| [IN-47](#in-47-no-nesting-limit-below-the-size-of-the-block) | No nesting limit below the size of the block | all eras | confirmed in source | preprod block 5,183,974 |
| [IN-48](#in-48-verifyecdsasecp256k1signature-returns-false-for-zero-r-or-s-and-for-a-high-s) | `verifyEcdsaSecp256k1Signature` returns `False` for zero `r` or `s`, and for a high `s` | Plutus V2 onwards | confirmed in source | preprod block 3,498,869 |
| [IN-49](#in-49-serialisedata-writes-a-constructor-index-outside-0-to-127-as-an-unsigned-64-bit-integer) | `serialiseData` writes a constructor index outside 0 to 127 as an unsigned 64-bit integer | Plutus V2 onwards | confirmed in source | preprod slot 80,239,053 |
| [IN-50](#in-50-bls12-381-in-the-reference-is-built-portable-without-mulxadcx) | BLS12-381 in the reference is built portable, without `mulx`/`adcx` | Plutus V3 (BLS builtins) | confirmed in source | preprod follower |
| [IN-51](#in-51-byron-epoch-boundary-blocks-repeat-the-block-number-praos-nonces-stay-at-the-genesis-hash-across-byron) | Byron epoch-boundary blocks repeat the block number; Praos nonces stay at the genesis hash across Byron | Byron to Shelley | observed | devnet eras capture |
| [IN-52](#in-52-byron-pbft-validation) | Byron PBFT validation | Byron | confirmed in source | preprod, devnet |
| [IN-53](#in-53-byron-delegation-certificate-signature-and-activation) | Byron delegation certificate signature and activation | Byron | confirmed in source | preprod, devnet |
| [IN-54](#in-54-byron-to-shelley-transition-epoch-comes-from-the-update-state) | Byron to Shelley transition epoch comes from the update state | Byron to Shelley | confirmed in source | preprod epoch 4 |
| [IN-55](#in-55-byron-address-root-redeem-addresses-and-fee-free-redeem-transactions) | Byron address root, redeem addresses and fee-free redeem transactions | Byron | confirmed in source | mainnet blocks |
| [IN-56](#in-56-chain-selection-at-equal-length) | Chain selection at equal length | TPraos and Praos; the VRF tie-break restriction from Conway | confirmed in source | none (read from source) |
| [IN-57](#in-57-candidate-nonce-freeze-point-3kf-in-alonzo-and-babbage-4kf-from-conway) | Candidate nonce freeze point: 3k/f in Alonzo and Babbage, 4k/f from Conway | Alonzo, Babbage vs Conway | observed | Preview |
| [IN-58](#in-58-the-tpraos-nonce-contribution-hashes-the-64-byte-vrf-output) | The TPraos nonce contribution hashes the 64-byte VRF output | TPraos | observed | Preview |
| [IN-59](#in-59-tpraos-overlay-schedule-and-the-absence-of-a-threshold-check-in-overlay-slots) | TPraos overlay schedule and the absence of a threshold check in overlay slots | Shelley to Alonzo, d > 0 | confirmed in source | Preview epochs 0 and 1 |
| [IN-60](#in-60-header-envelope-checks-and-the-timing-of-era-entry) | Header envelope checks and the timing of era entry | Shelley onwards | confirmed in source | Preview from genesis |
| [IN-61](#in-61-the-first-header-of-a-chain-that-starts-at-origin-names-the-byron-genesis-hash-as-its-parent) | The first header of a chain that starts at Origin names the Byron genesis hash as its parent | Byron | observed | preprod from genesis |
| [IN-62](#in-62-chainsync-intersection-candidates-are-limited-to-64) | ChainSync intersection candidates are limited to 64 | all | observed | Preview sync |
| [IN-63](#in-63-a-blockfetch-range-of-full-blocks-can-exceed-the-10-mb-per-request-limit) | A BlockFetch range of full blocks can exceed the 10 MB per-request limit | all | observed | Preview sync |
| [IN-64](#in-64-getsystemstart-answers-with-the-byron-genesis-starttime-and-that-start-is-the-slot-clock) | `GetSystemStart` answers with the Byron genesis `startTime`, and that start is the slot clock | all with a Byron genesis | observed | devnet `ntc`, `pool` |
| [IN-65](#in-65-the-alonzo-genesis-of-a-babbage-era-cluster-carries-the-plutusv2-cost-model-in-extraconfig) | The Alonzo genesis of a Babbage-era cluster carries the PlutusV2 cost model in `extraConfig` | Babbage | observed | devnet `ntc` |
| [IN-66](#in-66-pointer-address-fields-unbounded-in-the-encoding-bounded-and-era-dependent-in-the-decoder) | Pointer address fields: unbounded in the encoding, bounded and era-dependent in the decoder | Shelley to Conway (decoder version 9 is the gate) | confirmed in source | fuzz; ledger source |
| [IN-67](#in-67-native-script-integers-cddl-int-and-uint-are-unbounded-the-decoder-is-64-bit) | Native script integers: CDDL `int` and `uint` are unbounded, the decoder is 64-bit | Allegra onwards | confirmed in source | preprod block 5,184,855 |
| [IN-68](#in-68-redeemers-cddl-map-keys-are-unique-the-ledger-accepts-a-duplicate-and-keeps-one) | Redeemers: CDDL map keys are unique, the ledger accepts a duplicate and keeps one | Alonzo array form, Conway map form | observed | Preview slots 12,924,758 and 62,567,517 |
| [IN-69](#in-69-value-size-limit-defined-on-serialised-size-measured-on-the-ledgers-re-encoding) | Value size limit: defined on serialised size, measured on the ledger's re-encoding | Allegra to Conway | confirmed in source | preprod block 1,558,703 |
| [IN-70](#in-70-plutus-script-bytes-nothing-in-the-cddl-says-that-v1-and-v2-ignore-a-remainder) | Plutus script bytes: nothing in the CDDL says that V1 and V2 ignore a remainder | Babbage onwards | confirmed in source | preprod block 4,045,972 |
| [IN-71](#in-71-alonzo-genesis-the-plutusv2-cost-model-sits-in-extraconfig-which-the-genesis-documentation-does-not-mention) | Alonzo genesis: the PlutusV2 cost model sits in `extraConfig`, which the genesis documentation does not mention | Babbage-era clusters | observed | devnet `ntc` |
| [IN-72](#in-72-scalus-verifyed25519signature-costing-under-semantics-variant-a) | Scalus: `verifyEd25519Signature` costing under semantics variant A | Plutus, semantics variant A | observed | Preview Alonzo sync |
| [IN-73](#in-73-scalus-the-v3-newconstitution-context) | Scalus: the V3 `NewConstitution` context | Conway | observed | Preview slot 116,688,298 |
| [IN-74](#in-74-scalus-conformance-deviations-from-the-plutus-reference) | Scalus: conformance deviations from the Plutus reference | Plutus, protocol 11 and 12 | observed | plutus-conformance, 987 of 1009 pass |
| [IN-75](#in-75-scalus-verifyecdsasecp256k1signature-raises-where-the-reference-returns-false) | Scalus: `verifyEcdsaSecp256k1Signature` raises where the reference returns `False` | Plutus V2 onwards | confirmed in source | preprod block 3,498,869 |
| [IN-76](#in-76-scalus-trailing-bytes-after-a-v1-or-v2-script) | Scalus: trailing bytes after a V1 or V2 script | Babbage onwards | confirmed in source | preprod block 4,045,972 |
| [IN-77](#in-77-scalus-serialisedata-constructor-index-and-a-nesting-limit-in-the-encoder) | Scalus: `serialiseData` constructor index, and a nesting limit in the encoder | Plutus V2 onwards | confirmed in source | preprod slot 80,239,053 |
| [IN-78](#in-78-leios-the-cip-dijkstracddl-the-blueprint-and-the-rust-specification-disagree) | Leios: the CIP, `dijkstra.cddl`, the Blueprint and the Rust specification disagree | Dijkstra (Leios, proposed) | observed | design note, section 2 and 4 |
| [IN-79](#in-79-leios-bls-signer-bitfield-padding-bits-and-identity-points) | Leios: BLS signer bitfield padding bits and identity points | Dijkstra (Leios, proposed) | observed | design note, section 9 |

## Related pages

[findings.md](findings.md) holds the full history of each finding, [roadmap.md](roadmap.md) the B-rows,
[compatibility.md](compatibility.md) the evidence log, and [leios.md](leios.md) the Leios design note.
