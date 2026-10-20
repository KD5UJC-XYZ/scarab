# Upstream conformance source review — 2026-10-20

Status: historical source review of the fixed freshness observation at
2026-10-20 19:22:52 UTC. This classifies source changes; it adds no generator runs,
vector passes or observed Scarab discrepancies.

## Reference and scope

The latest stable [cardano-node release](https://github.com/IntersectMBO/cardano-node/releases/tag/11.1.3)
checked during this review is **11.1.3**, published 2026-09-29. Its package table
selects the same ledger `f649f975` and Shelley reward `4c81e909` baselines used by
our adapters. Its Plutus 1.70.0.0 release commit `83fb488e` and our tag commit
`507cab8c` have the identical complete Git tree
`2629616112f325e62ebd1ba3f201e7f31a7a24aa`. Different commit identifiers here do
not represent different evaluator or corpus content.

All **291 watched path events** from the six-source observation are classified
in the [per-file inventory](data/upstream-source-review-20261020.json), retaining
full pins, observed heads, original add/remove events and immutable source links.
The ledger, Plutus and formal/generator tracks were reviewed separately; the
coordinator reviewed consequential source changes and their applicability to
Scarab. Generated formal files were compared using complete endpoint sources,
including a separate normalization of generated identifiers and QName declaration
numbers. That normalization is an aid to source review, not a semantics proof.

The watch lists do not cover every upstream source or every published vector.
Additional implementation files were inspected where necessary to interpret a
watched change; these findings are labelled below. Source-only review cannot
establish that an updated oracle builds, that a generator terminates for all
seeds, or that Scarab agrees with its outputs. Original regression inputs,
source pins, expected results and failure classifications remain unchanged.

| Baseline → observed head | Watched events | Classification and disposition |
|---|---:|---|
| Ledger `f649f975` → `7fa3963c` | 50 | Generator/context repairs, bootstrap translation, broader registrations, state/API refactoring, dependency/compiler fixes and Dijkstra/Leios work. New coherent rule inputs are valuable; upgrading the entire oracle is a deliberate reference change. |
| Rewards `4c81e909` → `7fa3963c` | 3 | Era-indexed types, Go-snapshot wrapper and CIP-50 parameter support. No likelihood/leader-probability arithmetic change; the new parameter is unset through Conway. Keep released reward expectations. |
| Network `2fb69829` → `9793c8ea` | 1 | Three new lazy-encoding/trace properties in `Test/Mux.hs`. No existing wire golden or SDU-ordering assertion changes in this watched diff. Additional DNS/governor changes lie outside the current watch roots. |
| Formal artifacts `b3a4fd47` → `d30664e1` | 154 | Fifty files have only normalized generated metadata differences locally. The other 104 events include Conway rule/API changes, dependency propagation and future-era additions; they require coordinated oracle review/re-export before claiming equivalence. |
| Constrained generators `966f65f3` → `3895396d` | 9 | Actual specification/backpropagation and Boolean normalization fixes, delayed predicate optimization, numeric-instance typing, tests/examples and benchmark/build cleanup. Useful generator improvements, not new ledger rules. |
| Plutus `507cab8c` → `8fd7b6f3` | 74 | Thirty-one budget expectations, fourteen Data-casing version files, ten new free-variable fixture files, ten unchanged relocation events and nine harness/build/generator changes. Newer library defaults and future PV12 behavior must not replace current network parameters or PV11 expectations implicitly. |

## Changes worth acting on

### Coherent ledger generation: first corpus refresh

[f11490a9](https://github.com/IntersectMBO/cardano-ledger/commit/f11490a9d7a947c1a26b0c49700f9bb0ab380501)
repairs a genuine generator loop: initial ledger construction read deposited
obligations from the UTxO state still being constructed. It instead uses the
already-bound empty governance state, moves generation from PV10 to PV11 and
re-enables UTXO, UTXOW, LEDGER and LEDGERS.
[bbc1844c](https://github.com/IntersectMBO/cardano-ledger/commit/bbc1844c0602de558d5eeebbde2c5c3d44cce3e8)
then reuses the transaction's generated execution context instead of drawing an
independent one at the comparison boundary.

The next bounded campaign should export these four families with explicit PV11,
a coherent ledger/formal/generator dependency set and complete typed outcomes.
Preserve the old released/PV10 corpus and original generation failures. This is
an input/oracle repair, not evidence that a Scarab rule or the released node is
wrong. A source upgrade alone is not a passing replay.

### Bootstrap inputs and substantive formal corrections

[d7297028](https://github.com/IntersectMBO/cardano-ledger/commit/d7297028d3ffc910e49183070cb1b33259dc579c)
adds bootstrap-address translation where the adapter formerly failed;
[f8636b4c](https://github.com/IntersectMBO/cardano-ledger/commit/f8636b4c84bb070da67431f51993930b6d162007)
includes bootstrap witnesses alongside ordinary key witnesses. A valid bootstrap
spend, missing/bad witnesses and attribute-bound controls are useful new native
and formal comparisons. Translator support alone establishes no new pass.

The formal source also changes Conway collateral-input existence, overlap-check
placement for Plutus V3, the exclusive upper validity bound, immediate DRep
undelegation, vote indexing, componentwise execution-unit limits and bootstrap
address representation. These are substantive reference changes, not simply
renumbered generated Haskell. Some already agree with Scarab's source: UTXO uses
`slot < ttl`, `ExUnits.fits` checks both components, and GOVCERT removes a DRep's
reverse delegations immediately. These observations are not new native/formal
replays or proof that every changed formal branch agrees.

A matched formal upgrade should therefore include focused boundary cases for
these changes, together with the native released-node result. The native Haskell
reference remains authoritative if formal and native outcomes disagree. Pool-owner
witness additions inspected in the formal source concern Dijkstra, not Conway.

### Plutus: retain the released target; test upgrade boundaries

The two new free-variable cases are useful original-byte regression candidates;
first check overlap with the existing scope corpus, then observe their actual
parse/setup/runtime stages using the pinned native ledger API. Generic suite
error expectations must not be substituted for ledger admission behavior.

A consequential change **outside the watched prefixes** is
[bf691763](https://github.com/IntersectMBO/plutus/commit/bf6917633a7c697b816e19f5b0491966c1f771ce):
type-header and constructor-field bounds become 32/1024 at every protocol version.
Our supported 1.70 reference applies those bounds from PV11. Upstream reports that
historical submitted scripts do not exceed them; this review did not reproduce
that historical scan. Before upgrading the reference, retain old/new native
boundary observations at older protocols and document historical replay behavior.
Do not globally tighten current Scarab decoding merely because HEAD changed.

[82db8973](https://github.com/IntersectMBO/plutus/commit/82db8973764a4deccb9d1b1d2ece33dd6c4c1e54)
requires both PV12 and UPLC 1.2 for Data casing.
[e25e3234](https://github.com/IntersectMBO/plutus/commit/e25e3234130e3a29f4d7c572ac47fa49978b2d14)
retains the older Case/Constr scope behavior below PV12. Semantics F/G, batch-7
builtins and policy-filter limits are future Dijkstra readiness. The fourteen
changed casing files do not establish that current PV11 rejection is wrong.

The thirty-one changed budget goldens concern newer default costing/Value
implementation. A fair budget comparison needs the same explicit cost-model
parameters; replacing network-supplied parameters with library defaults would
change the experiment. The six BLS representation and five dropList reporting
qualifications have no observed repair in these watched case files. The two
Data-casing qualifications need version-aware interpretation rather than deletion.

### Network: one applicability decision and two practical follow-ups

[31938ad4](https://github.com/IntersectMBO/ouroboros-network/commit/31938ad42c71bb0bf5f40c9673dcaa158820beb6)
forces a bounded lazy encoding prefix in the sending mini-protocol and moves
length information to the send-end trace. Three new properties check forcing and
trace length. Scarab accepts strict array-backed `Bytes`; it has no matching lazy
encoder thunk boundary. Existing bounded admission, queue and cleanup checks
remain pertinent, while identical Haskell exception locations or trace formats
are not wire requirements. No new upstream property execution is claimed here.

Inspection beyond the watch list found:

- **Missing watch roots:** `ouroboros-network/tests/` and
  `cardano-diffusion/tests/` contain four changed test files absent from the report;
  the configured `cardano-diffusion/test/` prefix is singular. The new DNS reload
  and advertise-flag properties are among these changes. Expand and validate the
  actual roots; the existing report is accurate only for its declared prefixes.
- **Reload publication order:**
  [6a49b6f6](https://github.com/IntersectMBO/ouroboros-network/commit/6a49b6f63e18886adcdac3ae8393b8f8d316144a)
  publishes configured IP roots/targets and retained DNS answers before resolver
  work. Scarab's `PeerNetwork.reloadRoots` awaits due local DNS and configured
  public resolution before publication. Pending lookups can defer mixed-config
  installation. This order is confirmed from source; elapsed impact has not been
  measured. Add a controlled blocked-DNS regression before changing publication,
  retaining generation/cancellation/stale-result guarantees.
- **Advertise flags:**
  [33c11138](https://github.com/IntersectMBO/ouroboros-network/commit/33c11138f83affc3433f54ade562cfa1e99e3848)
  updates flags on retained known peers. Scarab's `PeerSelection.reorigin` already
  refreshes every retained local peer's flag; no equivalent source defect found.
- **KeepAlive retention:**
  [2cede5d1](https://github.com/IntersectMBO/ouroboros-network/commit/2cede5d1762028503182adbed6232f700fbc4684)
  replaces recursive Haskell server construction with a shared local knot.
  Scarab reuses one effect through `iterateWhile`; there is no mechanical port.
  This observation is not a new memory-soak result.

## Qualifications that remain

Observed HEAD still has the disabled/miswired NEWEPOCH registration,
`newEpochStateSpec = trueSpec` and unsafe unconstrained NonZero construction.
The enact-state generator fix only adds empty RATIFY withdrawals; it does not
repair the EPOCH current/completed-parameter invariant. The formal Conway
LEDGERS foreign wrapper still lacks the explicit input conversions used by
[#270](https://github.com/KD5UJC-XYZ/scarab/issues/270). No new crash was run.
Therefore [PR #273](https://github.com/KD5UJC-XYZ/scarab/pull/273)'s lawful subsets,
parameter controls and local formal repair remain necessary at these heads.

Ledger HEAD also removes a historical pre-PV11 withdrawal/DRep branch. Build
indices, formal interfaces, snapshot wrappers and compiler options change together.
Updating every SHA indiscriminately would replace the comparison target rather
than merely refresh fixture provenance. No source pin, golden expectation,
production behavior, persisted layout or operational database changed in this
review. Historical provenance remains [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129).

## Follow-up order

These are the bounded [K2 source-review follow-ups](roadmap.md), distinct from the
completed agreed v0.2 campaign:

1. Refresh coherent PV11 generation for the four re-enabled ledger families and
   add the supported bootstrap spend/witness matrix, with native outcomes and
   full-state/error comparisons.
2. Correct source watch roots and validate each root independently; add the
   blocked-DNS topology-reload regression and assess publication behavior.
3. Before a reference-version upgrade, run the formal Conway and older-protocol
   Plutus decoding boundaries. Keep new default budgets and future PV12 behavior
   explicitly separate from the currently released target.
4. Broader future-era and backend campaigns remain optional. No current
   production consensus fix is established by this source classification.
