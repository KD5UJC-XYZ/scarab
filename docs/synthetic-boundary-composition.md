# One supplied synthetic governance/non-myopic boundary

This internal composition builds on the separately reviewable governance projection
bridge (`3db34d6`). It adds an explicitly supported complete-state component to the
existing `CoherentSequence` candidate and owned cell. It does not add native seed
admission, a public CLI mode, durable serialization, general governance execution,
full ledger/consensus validation or another epoch boundary.

## Explicit profile and checked sources

The package-private profile/factory accepts checked current/previous parameter roles,
complete pool payloads keyed by pool ID, typed fixed-epoch Globals, a complete supported
governance input, finite raw-bit non-myopic state and explicit monetary absence evidence.
The old `syntheticRewardProfile` constructor and its seven no-effect assertions remain.
A distinct profile identity marks the composition; the legacy public factory rejects
this profile, including a profile extracted from an already created composite runtime.

Before any runtime is exposed, the helper checks the complete account domain and reward
balances/deposits/delegations, DRep delegation indexes, keyed pool projections and original
payloads, ordered complete parameter hashes against the same Prepared source as Globals,
parameter roles, source-bound header geometry, pre-state instantaneous stake
and mark distribution. Supply is an equality over complete UTxO coins, registered reward
balances, treasury/reserves/fees and all stake/pool/DRep/proposal deposit obligations,
counted once. Unsupported proposals, old pending DRep/RATIFY work, pool changes, donations
and other effects are rejected through the existing governance model.

The full governance graph, context/stake identities, parameter roles, typed Globals,
keyed original pool hashes and non-myopic identity enter the component identity. These
are checked supplied-state relationships, not proof that a native node admitted the
source state. The bridge's finite native-wire-shape subset remains unchanged.

The scoped current/previous reward projections must be identical because the existing
monetary profile is immutable. Full parameter bytes may differ in other fields; governance
still copies current to previous and resets future parameters. Unequal rho/tau/a0/nOpt
roles reject rather than silently applying the wrong epoch's reward parameters.

## One owned transition

At the first monetary freeze, retain the current non-myopic history alongside the exact
frozen and allocation IDs. Reject nonempty `frozen.go.pools` even if rewards or active
stake are zero. At a boundary, use the coordinator-owned completed monetary pulser and
effect: frozen ID, allocation ID, completion ID, reward effect and preview must all agree.
The exact empty-go non-myopic completion replaces history and sets the frozen allocation's
reward pot; an absent monetary update preserves current history unchanged.

Governance consumes post-reward accounts and treasury, the pre-body instantaneous stake,
and the newly rotated mark distribution. Header leadership continues to use old mark.
Dormancy, committee membership-based authorization filtering, current/previous/future
parameter bookkeeping and fresh DRep-pulser fields come from `ConwayEmptyGovernance`.
Its legacy opaque Globals constructor remains unchanged. A new sealed typed fixed-Globals
evidence alternative carries the checked security parameter and complete typed source
identity; this composition refuses the opaque legacy alternative.

TICK ordering remains explicit. A late first successor's new RUPD captures the pre-tick
reward environment and **pre-boundary** non-myopic history, not the just-applied replacement.
An early first successor leaves RUPD absent; a later freeze examines the then-current go
domain and rejects if nonempty. All signals use their actual slots.

Header/body checks, monetary work, governance and non-myopic changes are prepared without
mutating the runtime. The existing candidate publication selects them in one cell update
and one revision. Foreign/stale fences and candidates retain their existing checks.
Invalid blocks publish no component transition. The separate ephemeral driver's explicit
pre-validation anchor-compaction availability policy is unchanged.

## Undo, compaction and unsupported paths

The component and its one-boundary marker are part of state identity and whole-state
undo receipts. Undo restores original governance/NM/frozen objects; replay may have new
revision-derived attribution while preserving semantic state. Compaction retains bounded
components and drops old receipts, without recursively retaining prior coordinator states.
A second boundary is rejected, including after compaction; undo across the first boundary
restores the ability to replay that same boundary.

Existing checkpoint exports and synthetic recovery reject composite-bearing state, including
the raw internal recovery-image preparation route and hydration preflight. No partial export
may silently drop the new component. Ordinary profile behavior remains covered by adjacent
regressions. Fresh DRep completion, nonempty-go likelihood generation, native original-payload
parity and authenticated hard-fork timing are still outside this profile.

## Verification

The offline Docker run `evidence-f1b1ecf6` passed 37 app tests and 40 ledger tests,
including 12 new composition tests. It also ran formatting for the touched app/ledger
production and test sources. The adjacent app suites cover ephemeral bounded streaming,
coordinator ownership, the legacy no-effect profile, the recorded finite governance
comparison and typed Globals. The ledger suites cover empty governance, non-myopic
state and epoch-boundary mechanics. Capture-dependent tests were not enabled.

The synthetic sequence covers absent, pulsing and complete old monetary updates, 25
linked signed blocks with repeated receipt compaction, owned rollback/replay, invalid
successor atomicity, old-mark header leadership versus new-mark governance distribution,
late pre-tick history capture, second-boundary refusal, nonempty-go refusal, foreign
ownership, recovery/checkpoint refusal, and exact ordered complete-source binding.
The nonzero case starts with fees 10 and tau 1/5: treasury increases by 2, reserves by 8,
and the replacement non-myopic reward pot is 8. Empty go produces no member-account
reward credits; this is not a test of nonempty reward delivery. Previous/current full
parameter bytes differ while their supported reward projections agree; parameter
rollover and membership-based committee cleanup are asserted.

Earlier checks caught and corrected fixture pool-domain and gross-versus-net reward-pot
expectations, plus legacy test calls after the typed Globals evidence addition. No
production validation guard was weakened to accommodate those failures. Independent
source review approved this finite scope, with the final combined run passing.

Command, immutable image identity, log, receipt and final formatted source hashes
are retained as private execution evidence outside Git.
The run took 29.874 seconds. Its container used no network, at most two CPUs and 2 GiB
memory, a private build/cache, and a 285-second deadline. The owned container was
confirmed removed. No live cluster or new native harness was run. Native encoded-payload
parity remains untested; recorded finite projected native comparisons do not establish it.

## Main integration verification

The reviewed composition passed 109 focused integration tests (69 app and 40 ledger)
and `scalafmtCheckAll`, with all seven Scala files unchanged from the approved source.
This includes the 12 composition tests, adjacent streaming/coordinator/governance
regressions, and default node/coverage safeguards. Composition tests exercise checkpoint
and recovery refusals. The separately selected capture-dependent `SyntheticRecoverySuite`
registered no tests because retained capture inputs were not enabled; it is not counted.
Log SHA256: `90493d59fb0cd9aae4551d42c3d865ae2192dbce26dc8927650d28bbb7960bd8`. The offline run used
2 CPU, 2 GiB, a private cache, no network and no private fixture mounts; owned container
cleanup was confirmed.

This is one supplied synthetic boundary with empty go. Fees 10 yield treasury 2,
reserves 8 and a non-myopic reward pot of 8; no member-account reward is delivered.
A second boundary, nonempty go, persistence and recovery remain unsupported. Synthetic
timing is not authenticated native timing, and this check establishes no live admission
or native encoded-payload parity.
