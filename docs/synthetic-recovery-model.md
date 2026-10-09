# Internal bounded synthetic recovery model

`SyntheticRecoveryModel` is a package-private authenticated **opaque in-memory handoff**
for the restricted synthetic successor coordinator. It is not a byte format, disk
checkpoint, crash recovery implementation, consensus validator or live epoch-admission
path. Existing durable codecs continue to reject these stake-bearing states. Acquisition
identity and checked synthetic transitions do not establish full ledger validation.

## Provenance and authority

Each retained transition records its original predecessor identity/revision, certificate
branch attribution, original after identity and exact original envelope/block bytes.
Every successor, including an Absent reward successor, has a typed historical boundary
capsule. Its identity binds the preview, historical tuple/revision, prior registration
context, chained nonce context and ledger environment. Current tuple identity includes
the latest boundary origin. Compaction rebases current receipts without rewriting their
historical positions. No recursive coherent-state history is retained.

Anchor and suffix tuple images retain their opaque frozen reward input and pulser.
`FrozenRecoveryView` distinguishes Ordinary from PostBoundary inputs: calculation uses
the original pre-tick context, while post-boundary application separately binds epoch,
stake context, reserves and previous block counts. Absent stays Absent. Current epoch
certificate/nonce/stake bindings survive anchor movement and mixed-context receipts.

Export computes a domain-separated claim including a 32-byte publication identity,
bounded image commitment, context, anchor/final identity, final revision and measured
payload. A controller must compare that complete claim against independently retained
authority; accepting any supplied digest is not authentication. Acceptance is tied to
the exact Envelope object. Authorization is cancelable and defaults to five seconds,
with a maximum permitted deadline of thirty seconds.

Restore rechecks the budget/commitment, reconstructs historical predecessors and replays
each retained original block through existing checked ordinary/successor preparation.
It checks historical after identity and adjacent semantic content before cloning.
Fresh runtime, stake and boundary owners preserve authenticated historical identities.
Pulser work is rebuilt from its checked prepared inputs and exact traversal prefix,
including exhausted-but-still-Pulsing and Complete states. Whole-tuple undo receipts are
reconstructed. Only after all checks succeed is a new runtime allocated; old fences and
candidates remain foreign. There is no arbitrary public identity hydration setter.

## Bounds

At most eight retained blocks and nine tuple images are admitted. Accounting stops at
200,000 logical entries or 16 MiB of logical payload before commitment/hydration work.
It counts nested owners/delegators, stake/reward maps, snapshots, original bytes, pulser
projections and historical attribution. Boundary and certificate-context capsules are
deduplicated by identity; repeated occurrences within supplied roots are charged again.
This conservative accounting is not a JVM heap-size estimate. Replay/clone loops yield
between bounded records; underlying pure checks retain their existing collection limits.

Observed fixture measurements (entries / logical bytes):

| Fixture | Entries | Bytes |
| --- | ---: | ---: |
| Absent anchor, early successor | 684 | 161144 |
| Retained freeze after undo and compaction | 1078 | 222808 |
| Post-boundary Complete anchor | 1045 | 211093 |
| Post-boundary anchor, mixed-context successor | 2094 | 379146 |

## Verification, 2026-10-09

65 tests passed: 32 ledger and 33 app, including nine new recovery tests (two ledger,
seven app). The app tests cover exact final identity/revision and continued publication,
whole-boundary undo, Absent and ordinary/post-boundary freezes, mixed epoch contexts,
missing/mutated/substituted provenance and foreign pulser, controller denial/deadline/
cancellation, and an aggregate nested-membership budget rejection. Ledger tests cover
fresh-owner pulser prefix reconstruction across initial, partial, exhausted and complete
work, including zero-fee and empty fixtures.

Commands: `scalafmtAll`; `ledger/testOnly lab.ledger.ConwayEpochBoundarySuite
lab.ledger.ConwayRewardPulserSuite`; `app/testOnly lab.SyntheticRecoverySuite
lab.SyntheticSuccessorBlockSuite lab.CoherentSyntheticEpochSuite lab.CoherentStakeSuite
lab.CoherentSequenceSuite`.

Execution used the existing local `cardano-public-v023-check:local` Docker image,
network disabled, 2 CPUs, 2 GiB memory/swap cap, 256 PIDs, JVM 1200 MiB heap, private
worktree cache and read-only existing capture fixtures. No live cluster or fetch ran.
Local log: `.cache/synthetic-recovery-tests.log` (excluded from Git).

A future durable format must explicitly encode and authenticate all historical/frozen
attribution and prove import validation; opaque provenance here does not solve that.
No v3 bytes, filesystem durability, CLI relaxation or native boundary equivalence is
included. Independent read-only review approved this scope after the focused fixes.
