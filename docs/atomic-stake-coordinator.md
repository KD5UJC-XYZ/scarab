# Atomic same-epoch stake publication

`CoherentSequence.createWithStake` is an opt-in in-memory coordinator constructor.
It uses the existing bounded coherent runtime and its single `Ref` cell; there is
no second stake store, parallel ledger publication, or independently advancing tip.
The existing `create` and all node/CLI/durable entry points keep their prior profile.

The supplied checked `ConwayStakeSeed.Prepared` must match the coordinator's exact
pinned ledger JSON and decoded whole-UTxO bytes, epoch length, ledger UTxO, fees and
epoch. The stake owner is allocated privately. The stake state is readable as an
immutable observation, but its owner and publication candidates remain hidden.
The coherent state identity includes the complete stake identity; un-staked state
identities retain their previous encoding.

Preparation evaluates the already checked ledger block candidate through the
pure stake projection. Net accepted spent/created outputs update instantaneous
stake, with full whole-UTxO recomputation as a required parity check. Key and script
credentials remain distinct. Pure selection and ledger commit both finish before
one atomic cell update publishes stake, ledger, certificates, nonces, acquisition
bytes and the owned rollback receipt. A failed or cancelled preparation publishes
nothing; cancellation racing publication can leave either the prior whole tuple
or the accepted whole tuple. Owner, semantic identity and increasing revision
fences reject foreign, duplicate and stale candidates.

Rollback first verifies the coordinator-owned whole-tuple receipt and applies
ledger undo. A package-private stake helper checks the restored ledger identity,
slot, epoch and full UTxO against the prior stake state, then rebinds its revision
to the increasing ledger revision while preserving its semantic identity. Exact
whole-tuple restoration is checked before publication. Compaction carries stake
through every retained receipt and removes discarded history using the existing
bounded retention policy. Rollback before that anchor is unavailable, not final.

Both checkpoint encoders explicitly refuse stake-bearing tuples. Their current
formats cannot serialize the complete stake state, and must not silently export
an incomplete projection. Durable stake recovery requires a separately reviewed
codec and authority extension before any runner or CLI enables this constructor.

## Profile limits

Registrations, delegations, pool parameters and checked account/reward balances
are fixed. Accepted UTxO changes affect stake immediately; reward balances are
included once in active stake using the checked foundation's account semantics.
The current ledger admission profile accepts no withdrawals, certificate changes
or reward update transition, so there are no accepted nonzero reward deltas to
apply here. This increment does not implement or accept reward progression.
Mark/set/go remain the checked historical snapshots throughout the same epoch.
Epoch crossing still fails before preparation, and SNAP preview cannot publish an
epoch transition. This is not NEWEPOCH, monetary conformance, consensus validation
or full ledger validation. It does not authenticate supplied snapshots.

## Focused verification

The dedicated coordinator suite covers exact source-pin rejection, concurrent
duplicate publication, full recomputation after each accepted block, unchanged
state during preparation, owner/revision fences, rollback/replay, multistep undo
after compaction, cancellation and refusal by both incomplete checkpoint codecs.
The foundation suites cover key/script credential separation, reward/account
balances, snapshot algebra, unsupported forms and aggregate bounds. The existing
coherent suite exercises unchanged default-runtime behavior.

Only retained private evidence is mounted read-only in offline Docker, using two
CPUs, 2 GiB and a cache copied into this isolated worktree. No cluster, native
conserving fixture capture, signing, key generation, full suite or network fetch
is part of this packet.

Validation: 20 focused tests passed (4 pure stake, 4 seed/source, 6 atomic stake,
and 6 existing coherent tests, including the retained three-block window case).
`scalafmtAll` and `git diff --check` passed. Independent read-only review found
no blocking issue after the exact-source binding check was added. Evidence log:
`.cache/atomic-stake-tests.log` outside Git.
