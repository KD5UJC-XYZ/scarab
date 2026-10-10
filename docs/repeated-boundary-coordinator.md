# Repeated checked boundary coordinator

`createWithRepeatedBoundary` is a separate, bounded research constructor. It permits at most eight exact successor epochs, keeps the existing eight-block retained window, and leaves the legacy one-boundary, same-epoch and persistence entry points restricted. The driver must compact through the existing owner before the retained window fills.

Ordinary and successor preparation create an internal unfinished candidate. Such a candidate cannot be published. Likelihood generation executes outside the runtime cell mutation; completion checks the exact captured cell object and normal publication still checks owner, revision, complete source identities and component bindings. Cancellation, failed generation and stale replies publish nothing.

Each freeze retains the exact `ConwayNativeLikelihood.Generated` capability. The state binds one explicit mode. `PureJvm` computes probability and likelihood words entirely on the JVM without native input or a native acceptance gate; native response and comparison counts are absent/zero. `CheckedJvm` separately requires exact diagnostic native parity. Native-authoritative results and cross-mode substitutions are rejected. The frozen object itself, request and mode-specific result originals and comparison capability are retained; the boundary reuses them without executing a second calculation. Only `CheckedJvm` has a diagnostic native execution dependency. Pure generation can be compared with native results after the run; neither mode establishes general floating-point parity or complete consensus validity.

Governance advances through the checked empty-proposal transition and registered-DRep completion. Complete current parameter originals roll into both temporal roles. The source globals remain the original checked genesis/geometry provenance; they are not presented as a new native acquisition. Each boundary binds newly checked Plutus parameters from the current governance role to the exact ledger and preview, then publishes ledger, stake, nonce, eligibility, monetary and governance components together. A late successor keeps the pre-tick frozen environment and non-myopic history.

`RepeatedPlutusBootstrap.start(joined, early, generator, mode = PureJvm)` derives the composition from the original joined native packet. An early restored chain is replayed from those original blocks; its point, UTxO, fees, nonces and stake snapshots must match the separately checked early runtime. It neither imports a mutable snapshot nor restores pending HTTP admission. The source profile is epoch zero, un-compacted, at most eight replayed originals, 1000-slot epochs and active-slot coefficient 1/20.

## Source-bound reward timing correction

The typed boundary profile derives its reward timing window from checked
`GovernanceGlobals.randomnessStabilisationWindow`; callers cannot supply a timing override.
This follows pinned Shelley [Rupd.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Rupd.hs#L96-L117):
`S = epochFirst + randomnessStabilisationWindow`, `F = S + randomnessStabilisationWindow`.
Slots through S remain absent, S < slot <= F start or pulse, and slot > F forces completion.
The separate consensus stability window and existing nonce/consensus checks are unchanged.

The observed pre-fix Scala live2 trace at slot 1319 was premature under the independently
checked effective genesis (`k=5`, `f=1/20`, epoch length 1000): its consensus window is 300,
while its reward window is 400. Epoch-one rewards therefore remain absent through 1400,
can start at 1401, and force only after 1800. The trace used source `9fbbcb0`, bootstrap
source SHA-256 `7339ffec5306782823589b7c269343e6f932c55ed3c31dba34501cd37b2e1a98`,
and effective genesis SHA-256 `49bdde48f28417445de47bc572adf8d333474d7989b4c86c61d4061498ce4bf3`.
No matching native endpoint was acquired for that trace, so this source-level correction
is not an observed native endpoint comparison or live-soak result. Active-pulser comparison
support and launcher guards are unchanged by this timing-only patch.

Generated bootstrap-source tests distinguish the 300/400 windows, reject foreign supplied
globals and timing overrides, and cover exact start/force edges in epochs zero and one.
Signed synthetic runtime tests separately check epoch-zero absence through the start edge,
first-start and late-start phases, forcing immediately after the force edge, and post-rollover
absence through the next start edge. Productive post-rollover likelihood generation is outside
this 40-slot synthetic fixture; its production geometry guard remains intact.

Offline tests cover three consecutive absent-reward boundaries, role and state agreement, retained-window/export refusals, failed/cancelled comparisons, stale replies and the unchanged legacy profile. Retained tests use independently pinned private originals. The valid Plutus successor spend test deliberately supplies a synthetic 500-slot boundary so the original signed validity interval remains unchanged; it is evaluator execution evidence, not a native epoch-transition or live inclusion claim. Empty-domain comparison test doubles are explicitly labeled and provide no native numerical evidence.

This change alone does not establish a multi-epoch live soak, general governance/RATIFY, pool reaping, arbitrary parameter changes, crash durability or multi-epoch checkpoint recovery. Those claims require separate composed runtime and endpoint evidence.
