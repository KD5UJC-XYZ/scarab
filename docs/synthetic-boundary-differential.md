# Synthetic boundary comparison candidate

This test-classpath harness computes seven finite synthetic cases from the pinned
`synthetic-boundary/cases.json` input. It follows the proposed native boundary
contract at packet commit `d3ab6dd276ae582733395f3f1db87bad4028649d`.
No native output is included or consumed; native agreement remains pending.

The Scala projection covers nonzero old reward application, snapshot/fee/count
rotation, old-mark leadership and actual-slot pre-tick RUPD, with old-pulsing and
Absent controls. Post-boundary RUPD is a deliberately different diagnostic lane.
All monetary values use decimal strings, fractions are reduced, and the comparator
requires exact fields and array order under a one-MiB output bound. The input is
an exact SHA-256-pinned synthetic matrix, not an external state admission format.

`SyntheticBoundaryDifferentialMain input expected-sha256 scala-output` writes a
Scala-only projection using create-new semantics. The optional final arguments
`native result-file` compare a separately supplied native result. Producer text
does not authenticate provenance: review and pin actual native execution evidence
before interpreting comparison as native evidence. The test suite's relabelled
Scala data tests comparator mechanics only; it is never a native oracle.

Governance/non-myopic fields, full-block validation, issuer increments, cryptography,
nonce evolution, rollback capabilities and persistence are excluded from this
native comparison contract. Synthetic omitted-effect assumptions do not admit
arbitrary native states. Runtime guards and checkpoint refusals are unchanged.

## Candidate verification

Formatting and 39 targeted tests passed: 30 ledger boundary/pulser tests and nine
app differential tests, including four new boundary tests. The Scala-only output
also passed the packet's schema and projection-invariant validator. No retained
fixture tests ran in this check. The container used two CPUs, 2 GiB memory, no
network and private cache/output, with no private fixture mounts; owned cleanup
was verified. Independent source review found no blocker. The initial compile
attempt and its two corrected type errors remain in private evidence.

This harness composes pure APIs: its `tick` projection attaches the calculated
pre-tick reward lane to the boundary projection. It does not independently run
the coherent successor coordinator. No native boundary output has been compared.
