# Captured registered-DRep completion

`ConwayRegisteredDRepCompletion` completes a genuine captured
`ConwayEmptyGovernance.Applied` for empty proposals, deposits and withdrawals. It retains
the exact application object, identity and epoch in a privately constructed `Completed`.
`forSource` rejects different or reconstructed captures; it does not detect intervening chain freshness. It does not authenticate original
ledger state, admit a block, or authorize an epoch transition. The old empty-DRep
completion and existing runtime guards are unchanged.

The native `computeDRepDistr` rule folds captured accounts. A voting delegation contributes
instantaneous UTxO stake plus reward balance, excluding both stake-registration and DRep
deposits. Credential targets contribute only if registered; special abstain/no-confidence
targets contribute directly. No delegation contributes nothing. Zero amounts insert a
real zero entry; undelegated registered DReps do not acquire invented zero entries.
Expired registrations remain in the captured registry and distribution: this primitive
does not filter activity. Voting activity matters in RATIFY action decisions, while this
profile has no actions. Unknown credential targets are ignored as in native code.

Arithmetic uses exact BigInt and refuses an individual final distribution value outside
uint64 instead of modeling CompactCoin overflow. Every registry field (expiry, anchor,
deposit, delegators) is preserved. With no proposal deposits, completion retains the
supplied mark pool distribution exactly, including explicit zero pools. As before, the
restricted profile requires that domain equal registered pools and does not manufacture
missing shares or expand the all-zero nonempty mark restriction.

The completed RATIFY result copies all captured enactment fields, changes only enactment
treasury to zero, and has empty enacted/expired actions and no delay. Chain treasury and
the original fresh enactment remain unchanged. Parameters, committee, constitution and
roots are copied from the genuine captured application. Callers must still preserve
post-reward/pre-body capture ordering and recheck full-state lineage at publication.

## Actual native differential

`RegisteredDRepDiagnostic.hs` obtains a genuine newly initialized native pulser, supplies
four explicit finite account/registry captures, and calls `finishDRepPulser`. It checks
native preservation of the full enactment except treasury, whole DRep registry, pool map,
and empty action result. Cases cover three registered DReps with shared delegation,
zero stake and expiries 0/1/8; the same capture at epoch 10; both special targets and an
unregistered target; and a single zero contribution. Account deposits are 2 and DRep
deposits 5, so mistaken deposit inclusion changes the expected distributions.

The exact native result is in the public synthetic `registered-drep/native-result.txt`
test resource. Expected registered amounts are 123, 0 and 18; changing only epoch does
not change them. Special amounts are 100 and 23, and the unknown target is omitted.
Every case retains chain treasury 1234 and yields RATIFY treasury 0. These are finite
synthetic native comparisons, not a full native epoch or live repeated-governance claim.
No private captured accounts, DRep identities or original provider corpus are published.

The pinned GHC9.6.7/-O0 build used the frozen 235-unit closure, network none and a fresh
private 2-CPU/2-GiB cache. Exact commands, binaries and logs remain private; portable
input/output/binary/source hashes are in `native-provenance.json`. Source pins identify
Conway 1.23.0.0 `DRepPulser.hs` compute lines 200–241 and finish lines 385–417, with
the empty-signal RATIFY treasury rule and fresh governance constructor separately pinned.
