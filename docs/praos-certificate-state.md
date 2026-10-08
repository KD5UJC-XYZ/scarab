# Experimental Praos certificate state

This increment advances a pure, branch-bound operational-certificate counter map after checking the existing original-header OpCert/KES predicates and a required pool-distribution/VRF context. It does not advance full consensus state. Existing profiles, source and live acquisition behavior are unchanged.

## Pinned reference contract

Source: [ouroboros-consensus 4.2.0.1](https://chap.intersectmbo.org/package/ouroboros-consensus-4.2.0.1.tar.gz), archive SHA256 `a645670ccbb25179c96a10c8082f5bfb84660fbab14a255193c989359cd34501`. `Ouroboros/Consensus/Protocol/Praos.hs` SHA256 is `b770f0c73f2c34dd69146f2b087a786d7d937d119f0efb961ff6757105119e23`, also matching the project's source pin at consensus commit `82ecba329d7d054340bf707d44fe6e9ac27cec40`.

`doValidateKESSignature` checks KES start/lifetime, OpCert signature, KES signature, then the issue counter:

- Prefer the stored counter `m` for the cold-key hash, even if the key is not in the current pool distribution.
- If no stored entry exists, use `m = 0` only if that key is in the supplied pool distribution. A genesis pool list alone does not establish this membership. Otherwise fail `NoCounterForKeyHashOCERT`.
- Require `m <= n`; a stale certificate fails `CounterTooSmallOCERT`.
- Require `n <= m + 1`; jumping ahead fails `CounterOverIncrementedOCERT`. Equal counters are allowed across successive blocks; strictly increasing is wrong. An absent registered entry can first present 0 or 1.
- `m` is Word64. At maxBound, `m+1` wraps to zero: no proposed value satisfies both comparisons. This boundary is preserved, not replaced with mathematical unbounded addition.

`updateChainDepState` subsequently verifies VRF eligibility before calling `reupdateChainDepState`. The latter inserts exactly `Map.insert issuer n` and preserves other entries. Its tick does not reset the counter map on an epoch change; TPraos translation carries the existing map across. This increment implements only the selected certificate transition and does not pretend the intervening VRF check passed.

KES current period is floor(slot / slotsPerKESPeriod). Start is inclusive and end exclusive. The supported 64-bit target uses Word for KESPeriod; overflowing start+maxEvo cannot satisfy both lifetime comparisons and is rejected here. The earlier core adapter's broader BigInt edge is not silently inherited.

Rollback source: the same archive's `Ouroboros/Consensus/HeaderStateHistory.hs`, SHA256 `a1c404c1f75325fab51f77118ef3afb2de59659fa437ddde394390627a06b07a`. `rewind` selects the exact retained state-history prefix at the requested point. It does not decrement certificate counters. Local immutable receipts follow this restoration model.

## Core API and restrictions

`lab.header.PraosCertificateState` exposes checked `Context`, explicit `seed`, `applyHeader`, and `undo`.

Context includes genesis and registration source identifiers, the full required pool-hash/VRF-hash map, KES timing and a fixed inclusive slot window. All these values participate in its fingerprint. Checked here means shape/bounds and identity consistency, not proof of ledger authority. Maps are bounded to 10000 entries. The app source loader below establishes exact source digest consistency.

The explicit profile is narrower than standalone reference counter validation: every applied issuer must be present in the required pool distribution, even when it already has a stored counter. Both OpCert and KES signatures and the registered VRF-key hash must match. VRF proof/leadership is still unverified. Applying across the supplied registration window or switching context in place is unsupported; epoch/stake/registration evolution needs another reviewed transition.

A non-origin seed requires a supplied counter map and source digest at a concrete anchor (hash, slot, block number). Empty is never inferred from a genesis file or from missing exports. Older counter entries for pools outside the current distribution may be retained in the map; they are not silently dropped.

Apply binds the expected hash to original header bytes, requires a direct parent, advancing slot and next block number, then updates only the issuer's counter after positive signatures and the exact counter rule. The next fingerprint incorporates the prior fingerprint and original header hash. Failed application returns no new state.

An unforgeable `Applied` receipt retains before/after immutable states. `undo(current, receipt)` requires the exact after-state identity/context and returns the original before state, preserving absence versus zero and unrelated issuer entries. It rejects wrong-order or cross-history receipts, including distinct seeds that arrive at equal maps and tips.

The profile remains `experimental-praos-certificate-state-v1`. `consensusValidated` and `vrfEligibilityChecked` are false. The version-11 serializer limitation remains: the existing header adapter admits only bodies whose original bytes equal its supported shortest definite encoding. Complete reference serializer/crypto acceptance parity and exact runtime build identity remain unproven; this packet does not widen any version whitelist.

## Chain pipeline integration

`lab.CertificateBranch` pairs a `BoundedChainFollower.Checkpoint` with the certificate seed, applied steps and current state. `replay` consumes the original envelopes already checked by the acquisition branch, and the core rechecks original header hashes and chain linkage. `append` first checks the resulting acquisition branch and returns a new certificate branch only if every transition passes. `rollback` restores receipts and the corresponding original-byte prefix together. These operations are pure; existing acquired checkpoints remain acquisition-only until the caller chooses to pair them with this additional state.

Illustrative integration (no main-owned source was edited):

```scala
val certified = CertificateBranch.replay(context, explicitSeed, checkedCheckpoint)
val next = CertificateBranch.append(context, branch, original)
val restored = CertificateBranch.rollback(branch, rollbackPoint)
```

Publish a returned branch atomically with the other required chain stages. Success establishes this selected experimental certificate state, not full header or ledger validity. No CLI demo or live follower modification was added.

## Retained source loader and evidence gap

`loadTransfer(directory, protocolStateBytes, expectedProtocolSha256)` reuses the existing strict v2 transfer loader, hash-bound genesis timing and `ReferenceJson`. It rehashes the checked manifest before projecting its existing pre-ledger/tip digest fields, then obtains the actual pool-distribution membership and VRF hashes from `pre-ledger-state.md` at `stakeDistrib.unPoolDistr`. Genesis pool registration is not substituted for that ledger-view distribution. The fixed window is the checked epoch's slots; incompatible epoch translation fails.

A separate explicitly SHA256-pinned protocol export is mandatory. Its `lastSlot` must equal the bound pre point, and its `oCertCounters` becomes the explicit seed. The export has no block-hash field, so this is a supplied, slot-matched snapshot under the existing paused-tip bracket assumptions, not an atomic or independently authenticated consensus snapshot. The caller must supply the correct external pin; this API does not manufacture provenance.

The multi-block follower directory `/home/<user>/cardano-follower-live-20261008-a` starts at block 2, slot 41, hash `8e75c7dc49e9e1604749a72adddfc203797e1f2aa64b396c88376e33b460957c`. It retains genesis and blocks but **no protocol counter map or ledger pool-distribution/VRF snapshot bound to that anchor**. Therefore no stateful replay of those four blocks is claimed. Needed: an explicitly pinned counter export and pool-distribution snapshot with anchor/epoch binding, or an independently checked prior state. Missing entries cannot be assumed zero globally.

Offline integration instead uses the retained transfer-live3 protocol exports with its already-preserved v2 context projection. Pre-export SHA256 is `2bfe6fc2e8024f8ab337c4eb2e857f06b94b1c4da49fe97a81aa66a7f66c5643`; post-export SHA256 is `ec20238d170f26d8183d7fa8d12c0220b46b959044fdeb33f4393d0c19fd2678`. Replay advances slot 127 to 199, matches the full post counter map, undoes exactly, and deterministically reapplies. Both maps happen to contain the same zero counters; this proves equal-counter application, not an observed counter increment. A separate explicitly synthetic missing-entry branch tests absent-to-zero insertion/undo and is not represented as the reference's initial state.

## Tests and residual gaps

Public core tests use a SHA256-pinned already-cleared signed header (certificate counter 7), genuine signature verification, seeds at counters 6/7/8/5, and the exact arithmetic rule. They cover initial membership fallback, equal/new/stale/jumped counters, maxWord64, authenticated apply/undo, repeated block rejection, cross-history receipts, restored lower sibling admissibility, byte/context/anchor/VRF mismatches, unsupported encoding and KES/window expiry. Public app tests cover acquisition seed and rollback bounds.

Opt-in app tests require `CERTIFICATE_TRANSFER_EVIDENCE` (v2 projection) and `CERTIFICATE_PROTOCOL_EVIDENCE` (original transfer-live3 directory). They pin the original pre/post protocol bytes, compare actual state, restore/reapply, exercise the separately marked synthetic absence fork and reject changed digest/slot bindings. No raw private corpus or keys are committed.

Still outside scope: authenticated snapshot acquisition, VRF eligibility, stake/nonce evolution, registration changes, fork selection, full consensus/header validity, transaction/ledger transitions and exact version-11 malformed-input/crypto acceptance parity. No live cluster was started for this packet.
