# Experimental fixed Conway nonce evolution

This adds a pure, rollback-capable nonce component without changing legacy adapters or profiles. It reduces repeated epoch-nonce supply to an explicit initial snapshot plus authenticated header contributions. It does not derive the initial state, stake distribution, ledger parameters, or full consensus. Exact binary-linked dependency provenance and malformed-crypto parity remain unclosed.

## Source contract and order

The pinned consensus package is `ouroboros-consensus-4.2.0.1`, archive SHA256 `a645670ccbb25179c96a10c8082f5bfb84660fbab14a255193c989359cd34501`, obtained from [CHaP](https://chap.intersectmbo.org/package/ouroboros-consensus-4.2.0.1.tar.gz). Node commit `938cba990357ae7c4b7f95c8f75dd9d31174bbeb` declares this dependency range; a declaration is not proof of the actual binary's linked package plan. Source excerpts and checksums are retained in the external Markdown research report.

- `Protocol/Praos.hs`, SHA256 `b770f0c73f2c34dd69146f2b087a786d7d937d119f0efb961ff6757105119e23`: tick, VRF verification, then header re-update.
- `Cardano/Node.hs`, SHA256 `be94c0d8908555d9d0a038ea2b46136b03688ffafa892f8e1c3f267c21a4ae40`: Conway uses the randomness stabilization constructor; Babbage has a different override and is outside this profile.
- `Protocol/Ledger/Util.hs`, SHA256 `6fcf097c8e2cc8dc93f5e6173b2f8559cf3e7677d683929ace57c4b428ae5797`: epoch change is one boolean comparison from the last applied slot, not a loop over empty epochs. Same-era epoch geometry is a precondition.
- Ledger commit `f649f9751074d2ab3de033fc3912f29c9862c1f5`, [StabilityWindow.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/StabilityWindow.hs), SHA256 `9a5b2abdc31a8b951b17b089c0e6364195132f3d58b459175b3f69bade95e9cb`: exact rational `ceil(4k/f)`.
- Same ledger pin, [TPraos/BlockHeader.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-protocol/src/Cardano/Protocol/TPraos/BlockHeader.hs), SHA256 `16f4eb6ebf6b8218fda3f5d7b877d44eae441d8e64fb81adcee75bdf8f83059f`: parent header hash is cast directly to nonce; genesis parent is neutral.
- Existing pinned `fixtures/praos/evidence/Protocol-Praos-VRF.hs`: nonce contribution is Blake2b256(Blake2b256(ASCII `N` || verified VRF output64)). It is not the leader's `L` hash.
- Same ledger [BaseTypes.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs), SHA256 `0b86d1ecdd5abb0cf89194e7324fe1f62e58214cbd7386a1c50aeaf1c605866e`: nonce combination hashes the concatenation of two hashes; neutral is identity on either side.

For old state E (evolving), C (candidate), eta (epoch), P (previous epoch), L (LAB), B (last-epoch-block), a header in a later epoch first ticks:

```
eta' = C combine B     // OLD B, before rotation
P'   = eta
B'   = L              // OLD L
```

E and C remain unchanged by this tick. No extra ledger-supplied entropy is present in this pinned Praos transition. Historical TPraos or other era rules must not be substituted. VRF verification uses eta' (or unchanged eta in the same epoch) and the incoming header slot. Then:

```
E' = E combine hash256(hash256("N" || verifiedOutput))
C' = E' if slot + window < firstSlotNextEpoch, otherwise C
L' = incoming header's parent hash, cast as nonce
lastSlot' = slot
```

The inequality is strict. With retained private genesis k=5, f=1/20, epochLength=500, window=400: candidate updates through slot99, freezes at slot100, and evolving continues. LAB is the parent hash of the last applied header, not that header's own hash; the epoch-boundary B contribution consequently has a deliberate delay. Multiple skipped epochs still cause one tick on the next header.

## State and API

`PraosNonceEvolution.Context.checked` binds the certificate context, fixed epoch length, k and exact rational f. It rejects arithmetic that would enter Word64 wraparound rather than extending acceptance. This is a non-origin, fixed Conway epoch geometry profile; era transitions, general hard-fork epoch history and origin initialization are unsupported.

`seed(context, certificateSeed, fields, sourceSha256)` requires all five exported nonce fields. `previousEpoch` is `Option[Nonce]`: unknown is distinct from explicit neutral and is included in state identity. Actual retained JSON omits previousEpochNonce. That field is unused by ordinary Praos VRF here; its initial value is not fabricated. A later epoch tick establishes it from the known current epoch nonce. This does not supply Peras certificate validation.

`applyHeader(context, state, certificateApplied)` requires the exact checked certificate-state predecessor, including original-byte/hash, signature, registered VRF-key and parent binding. It ticks internally, verifies the original VRF proof against the derived epoch nonce, and only then returns a nonce receipt. No public API accepts an unchecked arbitrary VRF output. Pure package-private arithmetic functions exist for boundary tests. `undo(current, receipt)` requires the receipt's exact after-state ID and restores the entire before-state, including unknown fields. State IDs bind the initial snapshot and complete subsequent certificate/nonce history, preventing receipt substitution across forks.

`PraosNonceSnapshot.bind(certificateContext, certificateSeed, genesisBytes, protocolBytes, protocolSha256)` checks exact source digests, genesis identity, anchor slot, and counter-map agreement, then constructs context and seed. Its `parse` method also reads post snapshots for differential checks. No file or CLI integration is imposed; callers supply bytes from their existing checked capture. The existing transfer loader only supports a same-epoch certificate window. Epoch-spanning callers need an explicitly checked certificate/registration context covering their interval; this nonce component does not assert those registrations remain valid.

Receipts report nonceTransitionChecked=true, vrfProofChecked=true, leaderEligibilityChecked=false and stateDerivedConsensus=false. Before publishing combined acceptance, callers still need the separate leadership/stake checks with the ticked nonce. Existing draft03 and header-serialization acceptance limitations remain.

## Capture fields and acquisition assumptions

Retain exact raw output and SHA256 pins from both before and after queries:

```
cardano-cli conway query protocol-state --testnet-magic 1082026 --socket-path /work/env/socket/node3/sock --output-json
cardano-cli conway query ledger-state --testnet-magic 1082026 --socket-path /work/env/socket/node3/sock --output-json
```

Required protocol fields: `lastSlot`, `evolvingNonce`, `candidateNonce`, `epochNonce`, `labNonce`, `lastEpochBlockNonce`, `oCertCounters`. Retain `previousEpochNonce` if the exporter provides it; absence remains unknown. Every nonce is canonical lowercase 32-byte hex or explicit null (neutral); missing required fields reject. Genesis needs `epochLength`, `securityParam`, numeric exact `activeSlotsCoeff`, and existing KES timing. Keep ledger lastEpoch and pool registration/stake fields for separate certificate/eligibility validation. No additional dynamic ledger entropy field is read by this pinned nonce path.

Both snapshots need named block-hash/slot anchors, stable tip brackets, node/CLI/version/config provenance, and every intervening original header/block in order. Protocol JSON itself lacks a block hash. Separate queries are non-atomic; digest and slot equality cannot authenticate snapshot-chain correspondence. A snapshot queried after a gap of unretained blocks cannot support a differential replay. Preserve both protocol output and stable tips rather than assuming lastSlot alone proves exact acquisition.

## Tests and next bounded differential capture

The offline retained slot127-to-slot199 interval contains one checked header. Applying it matches all five exported nonce fields and lastSlot in the pinned post snapshot; previous epoch remains explicitly unknown. Exact undo/reapply and wrong-nonce, stale-slot, changed-digest, counter mismatch and fork-receipt rejection are covered. Synthetic tests cover neutral identity, N double hash, ceil arithmetic, slot99/100/499 freeze boundary, epoch500 tick ordering, skipped epochs, malformed inputs and overflow refusal.

The retained differential interval does not exercise candidate updates or an actual epoch tick. Next capture should retain two to eight contiguous headers plus before/after protocol states, first around candidate stabilization and then around an epoch boundary; because slots are sparse, use the first actual headers on either side, not an assumed boundary-slot block. Compare all exported fields and lastSlot, verify each proof under the ticked nonce, roll back each receipt to the exact anchor, and reapply. A later snapshot exposing previousEpochNonce or a pinned CBOR decoder would allow checking that presently omitted field. This is a proposed capture only: no new live node was started for this packet.

Passing source-derived synthetic rules and this one-header differential does not close binary/package provenance, adversarial cryptographic acceptance, snapshot authentication, stake evolution or full consensus.


The subsequent [same-epoch candidate-freeze capture](private-cluster-nonce-freeze.md)
now demonstrates a real candidate update at slot 555 and freezing at slot 674 from
pre-anchor 513, all in epoch 1, with complete originals and paired rollback/reapply.
This adds boundary evidence within an epoch; the actual epoch tick and per-epoch
registration/stake context remain untested by that capture.


The separate [epoch-rotation capture](private-cluster-nonce-epoch.md) now exercises
one observed tick at slot 1023 using all four original successors from anchor 910
to 1047. It derives previousEpochNonce from unknown to known at that tick and
matches five exported fields. Cross-epoch verification uses an explicit supplied
key-map assumption; registration/stake evolution and leadership remain unproved.
