<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native pool distributions

Captured on 2026-11-10 from the pinned stable and Leios ledger images. The corpus retains every
native result of [the harness](../../../scripts/oracle/pool-distribution/Main.hs); the image IDs,
source revisions and digests are in [provenance.json](provenance.json). The actual core package
units and source-file hashes are in [source-verification.json](source-verification.json): both
files were compared byte for byte with their pinned upstream revisions.

The unchanged `calculatePoolDistr` receives a decoded snapshot. Cases cover empty stake, pools
with and without delegators, an absent or present voting key, an old registration epoch, and a
well-formed proof belonging to another key. The latter demonstrates that distribution derivation
does not verify proof ownership. Stake amounts and ratios are copied from the snapshot; the
registration epoch is not part of `IndividualPoolStake` (ledger `9df9f98c`,
`State/SnapShots.hs:676-695`). These are synthetic API cases, not lawful chain histories.

The individual-record cases exercise definite and indefinite arrays of three or four fields,
including an explicitly empty fourth field. The stable reference refuses the fourth field;
Leios accepts it and writes three fields when the key is absent (`State/PoolDistr.hs:84-119`).

`PoolDistributionReferenceSuite` compares every acceptance result and every accepted output byte.
Native decoder messages are retained for diagnosis; Scala's typed errors are not compared to
Haskell's text. No fields or cases are excluded. The corpus does not exercise SNAP, committee
selection, genesis, epoch transitions or a running Leios node; those remain under #826 and #54.
The reference images use GHC 9.10.3 and the source packages pinned by their recipes, not the
official node executable. Raw build logs are not published.

Regenerate or replay with [the runner](../../../scripts/oracle/pool-distribution/run.py).
