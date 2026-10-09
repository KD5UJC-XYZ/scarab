# Restricted empty-governance boundary model

This packet models automatic governance effects of an otherwise restricted Conway epoch
boundary. Empty proposals and zero monetary rewards do **not** make the boundary a no-op.
It is a pure supplied-state projection, not full Conway state validation, live admission,
or an extension of the coordinator's current `governance = false` assertions. Native
differential verification is required before integration into a live profile.

## Source mapping

Local cached sources are pinned by package/archive/file hashes in
`ledger/src/test/resources/empty-governance/source-pins.json`: Conway 1.23.0.0 and core
1.21.0.0. Their EPOCH source matches the previously retained native-rule excerpt after
removing Markdown framing. Helper source hashes are recorded from the package archives;
this packet does not claim an independent comparison against an upstream Git tree.

| Native source | Modeled behavior |
| --- | --- |
| `Conway/Rules/Epoch.hs`, `updateNumDormantEpochs` | Empty proposals increment the dormant-epoch counter, including with nonempty DRep registrations. Stored DRep expiries are preserved. |
| `Conway/Rules/Epoch.hs:320–375` | Install unchanged enactment content, copy old current parameters to previous, reset future parameters, reconcile committee authorizations, recompute deposits, initialize fresh DRep pulser. |
| `Conway/Rules/Epoch.hs`, `updateCommitteeState` | Restrict committee authorization keys to elected committee membership. This is explicitly modeled cleanup, not an assertion that committee state is absent. |
| `State/Governance.hs:121–140` | Only a Definite update supplies next-epoch parameters. Pending updates are rejected by this restricted profile. |
| `Conway/Governance.hs:299–323` | Empty enacted actions yield Potential Nothing after future-parameter reset/prediction. |
| `Conway/Governance.hs:458–516` | Fresh DRPulsing captures every pulser input; index zero, empty partial distribution, pulse size floor(max(1, account count/(4*k))). |
| `Conway/Rules/Ratify.hs:360` | Empty RATIFY clears enact-state treasury. This is distinct from chain treasury, which supplies the fresh pulser's enactment snapshot. |
| `Conway/Governance/DRepPulser.hs:200–241,385–417` | Nonempty accounts/DReps affect later distribution and must be retained even with no proposals. Distribution computation and RATIFY execution are outside this model. |

The surrounding native order remains monetary reward application, SNAP, POOLREAP,
governance enactment/accounting, hard-fork handling and fresh DRep pulser construction.
This model consumes supplied boundary inputs after the supported prior effects; it does
not replace those rules. Fresh governance pulsing uses the **new SNAP mark pool
distribution**. Header leadership continues to use old mark in the separate coordinator.

## Supported profile and retained evidence

Both current proposals and the previous completed DRep snapshot's proposals must be
explicitly empty. The prior ratification result must have no enacted/expired actions,
withdrawals or pending changes. Unknown or still-pulsing old governance work is rejected;
this slice does not force or independently ratify it. Committee membership, constitution,
parameter and proposal-root enactment changes are unsupported and rejected. Committee
authorization intersection is the one supported committee-state change.

Current and previous complete parameter payloads are retained before rotation. Pending
Definite/Potential parameter updates, pool updates/retirements, donations and proposal
deposit changes are rejected. Parameter, pool, constitution and Globals payloads are
bounded supplied canonical values used for exact preservation/comparison; accepting them
does not validate their full native schema. In particular, Pool.parameters is the complete
supplied StakePoolState payload, including reward account, relays, metadata and delegators,
not a reduced economic-parameter projection. Any extracted scalar supplied alongside such
payloads still requires an authoritative decoder before live integration.

Nonempty DRep records preserve expiry, anchor, deposit and delegators. Accounts preserve
reward balance, registration deposit, pool and voting delegations. Dormancy is a separate
counter, not an eager rewrite of every DRep expiry. Deposit obligations include stake,
pool and DRep components even with zero proposals. Chain treasury is preserved under the
zero-donation/withdrawal restriction; fresh enactment treasury snapshots that value.

The fresh pulser retains accounts, instant stake, new-mark pool distribution, registry,
current epoch, reconciled committee state, enactment state, proposal roots/actions/
deposits, Globals and stake pools. It starts at index zero with empty **partial** DRep
distribution; no claim of an empty final distribution is made. Its floor pulse-size rule
must not be shared with the monetary reward pulser's ceiling rule.

Bounds are 4,096 entries per collection, 65,536 aggregate visited items, 64-bit integers,
64 KiB per canonical payload, and 1 MiB of aggregate payload bytes. Canonical payload
parsing also limits depth to 16 and CBOR items to 8,192. These are bounded synthetic
inputs and logical accounting, not a JVM heap estimate.

The supplied new-mark pool distribution is checked for dimensions, fractions, sum and
pool membership; this model does not recompute SNAP from accounts/instantaneous stake.
Its provenance must come from the enclosing checked transition before live use. Full
max-supply consistency is also outside this opaque-Globals supplied projection.

## Native comparison contract

Native governance serialization completes the DRep pulser. Seed diagnostic projections therefore expose normalized completed semantics,
not the producer live cursor. The separately reviewed finite synthetic comparison
completes its distribution in test-only code; it does not expand this production model. A completed serialized projection cannot establish fresh
DRPulsing/index/cursor equality. Comparing construction requires native in-memory helper
instrumentation; comparing normalized semantics requires separately completing this model
under checked native-equivalent distribution/RATIFY rules, which this slice does not do.
No producer live-cursor equality is claimed.

A native helper must compare the full supplied before-state projection and these after
fields, not just a monetary total or final state hash:

- epoch, dormant count and every unchanged DRep/account field;
- elected committee, reconciled authorization map, constitution and proposal roots;
- complete current/previous parameter values and the explicit future-parameter variant;
- chain treasury, donations and each deposit-obligation component;
- fresh pulser constructor tag, pulse size, index, partial distribution, all snapshot
  fields and fresh enact-state treasury/current/previous parameters/roots.

Minimum positive cases: nonzero dormant counter and DRep deposits; obsolete committee
authorization removal; unequal old previous/current parameters; zero accounts (chunk1);
nine accounts at k=1 (chunk2, specifically not3); nonzero treasury with old empty-RATIFY
enactment treasury zero. Mutation cases must reject nonempty current/old proposals,
pending old work/results, parameter/committee/constitution/root or pool changes, donations,
inconsistent deposits, overflow and aggregate-size violations.

Native output should preserve exact canonical parameter/payload bytes, use decimal
strings for integers, distinguish all sum-type tags, and sort map/set entries by complete
credential encoding (including key/script tag). The Scala case classes are the current
supplied-state schema; there is no decoder or native-result reader in this packet.

## Validation limits

Focused synthetic and mutation tests validate the pure model against pinned rule
interpretation. A separate finite normalized native differential now passes; see the
[recorded comparison](empty-governance-differential.md). This does not establish
full supplied-state provenance or general native parity. No Main, launcher, coordinator,
epoch guard, live cluster, persistence or production configuration change is included.

Validation on 2026-10-09: all eight focused tests passed after `scalafmtAll` and
`ledger/testOnly lab.ledger.ConwayEmptyGovernanceSuite`. Existing local Docker image
`cardano-public-v023-check:local`, network disabled, 2 CPUs, 2 GiB memory/swap cap, 256 PIDs,
1200 MiB JVM heap, private worktree cache. Local excluded log:
`.cache/empty-governance-tests.log`. Independent source/code review approved this restricted
scope after Unicode, historical-registry and allocation-bound fixes.
