# Bounded epoch-rotation nonce observation

Status: source, profile and live evidence independently reviewed. This is a separate checkpoint
after `6b0c0abd724d2ac97f5faf63b8a38dd49bbbff97`.

## Scope and epoch context

The isolated `praos-nonce-epoch-rotation-v1` profile observes exactly one epoch
transition using the existing pure certificate and nonce components. It fixes
Conway ledger PV9.0/header11.2, magic 1082026, epochLength 500, k=5, f=1/20 and window=400.
The pre-anchor is in epoch >= 1 at relative slot 400..460; the post anchor is in the
immediately following epoch at relative 40..180. Retain every original ChainSync
header and exact BlockFetch block: 2..16 successors, at least one actual successor
in each epoch. No block at exactly the boundary is assumed. Empty transaction,
witness, auxiliary and invalid-index structures are required.

The mandatory manifest field
`keyMode=pre-anchor-supplied-verification-keys-v1` makes the verification assumption
explicit. Pre-ledger issuer-to-VRF-key mappings are supplied mathematical inputs
over this bounded interval. Profile, key mode and pre-ledger source attribution
are bound into the certificate context identity. This does not validate that the
mapping has registration authority in the new epoch or derive stake continuity.
The post-ledger mapping is parsed only after replay and must match as an endpoint
profile restriction. Full stake distributions need not match. Changing mappings
require a future explicit context-transition API, not silent merging or reseeding.

The seed uses only pre-anchor nonce/counter/genesis/registration bytes. Nine source
digests pin genesis and each side's tips, protocol state, ledger state and protocol
parameters. Post tips delimit acquisition; post-state values are deferred oracles,
never inputs that repair or replace derived state. Snapshots use separate queries
with short producer pauses and stable tip brackets; they are not atomic or
authenticated. No validated branch or acquisition cursor is published.

## Required source rules

The pinned rules and hashes are documented in [nonce evolution](praos-nonce-evolution.md).
The retained Consensus 4.2.0.1 `Protocol/Praos.hs` excerpt at lines 439..465 ticks from old fields:

```
new epoch nonce = old candidate combine old last-epoch-block
previous epoch nonce = old epoch nonce
new last-epoch-block = old LAB
```

Lines 476..491 verify the header with the ticked epoch nonce before re-update.
Lines 503..533 set LAB to the incoming parent hash and evolve with the double
Blake2b256 N-prefixed verified VRF output. Candidate follows evolving only when
`slot + 400 < firstSlotNextEpoch`. The epoch utility detects one change rather
than looping through skipped epochs; this profile rejects skipped epochs anyway.
Conway uses the exact rational randomness window `ceil(4k/f)`.

An absent previousEpochNonce stays unknown until the observed tick derives it from
the known old epoch nonce. If the post exporter omits it, the derived value remains
known but is not reference-compared. Every prefix rollback/reapply must restore
exact paired state IDs, including unknown status before the tick. Wrong old/new
nonce order, omitted/reordered originals, a second epoch crossing, changed supplied
keys and post-oracle tampering must fail. All five exported nonce fields, lastSlot
and full certificate counters are compared after complete replay.

This demonstrates selected nonce/VRF behavior under supplied keys. It does not
establish registration authority/continuity, stake evolution, leader eligibility,
full ledger or consensus validation. Source-to-binary dependency provenance and
broader adversarial cryptographic parity retain their existing limitations.

## Bounded execution

`scripts/private_cluster_nonce_epoch.py` uses the existing explicit coherent empty
Byron-allocation fixture. It submits no transactions. One reference container uses
3CPU/6GiB; one observer uses 1CPU/1GiB. The workload budget is 420..540 seconds (default 480)
under the existing 600-second lifecycle, with owned cleanup. Producers resume before
network acquisition and replay. Existing same-epoch and persisted eight-block
acquisition profiles remain unchanged. CLI: `nonce-epoch PORT EVIDENCE_DIRECTORY`.

`NONCE_EPOCH_EVIDENCE` enables retained private replay tests once a reviewed live
attempt has produced evidence. Raw logs, disposable keys and cluster state remain
outside Git. The completed scoped acceptance is recorded below.


## Completed epoch-rotation acceptance

The first reviewed isolated attempt passed in 229.225 seconds. Anchor block 53/slot 910
in epoch 1 was followed by every original block: 54/963, 55/990, 56/1023, 57/1047.
The first actual new-epoch header was slot 1023; no slot 1000 header was assumed.

Independent retained-byte review recomputed the tick and all subsequent nonce
updates. Old candidate combined with old last-epoch-block produced the new epoch
nonce, old LAB rotated to last-epoch-block, and the original VRF proofs verified
under the appropriate nonce. The observed inputs distinguish this from using the
stale nonce or combining candidate with newly rotated LAB. All five exported final
nonce fields, lastSlot and complete certificate counters matched.

Both protocol exports omit previousEpochNonce. It was unknown at the pre-anchor,
then derived from the known old epoch nonce at slot 1023. It remains derived-known,
not reference-compared. Every prefix rollback/reapply restores exact paired state
identities, including unknown status before the tick. Receipt-attribution fork
guards and wrong-nonce proof tests use explicit synthetic counterexamples; they
are not observed live forks or live negative agreement claims.

The initial nonce state ID was
`3ef111946b99cc95de49afc8e530176eee79feb46934cfe68b376b02332661aa`;
the final ID was
`a8070a0e5be503109c855d6662fe416b44ed15175f3955152f2057da15ffed7f`.
The observer stdout SHA-256 is
`8007c6c33db47e67fccf70186ed47df3271e84c7db1946dfc24df39b1f0e5bab`.

Final convergence reached block 101/slot 2046/epoch 4. Cleanup verified no owned
containers or networks remain. Evidence is retained privately under
`/home/<user>/cardano-nonce-epoch-live1-20261009`; integration logs are under
`/home/<user>/cardano-nonce-epoch-integration-20261009`. This single tick supplies no
registration/stake transition implementation, leadership or full-consensus claim.


Final local validation passed: 977 public Scala tests, 25 public gates and
123 compiler-inclusive Python guards. All five additional retained-evidence tests
passed, including rejection of the unchanged original VRF proof under synthetic
stale-nonce and wrong-LAB-order inputs, missing boundary originals, altered oracle
bytes and changed key mappings. These offline negatives do not claim live
reference rejection agreement.
