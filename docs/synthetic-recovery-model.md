# Internal bounded synthetic recovery model

`SyntheticRecoveryModel` is a package-private authenticated **opaque in-memory handoff**
for the restricted synthetic successor coordinator. It is not a byte format, disk
checkpoint, crash recovery implementation, consensus validator or live epoch-admission
path. Existing durable codecs continue to reject these stake-bearing states. The exact Envelope object, retained context/ledger/certificate/nonce objects, and
reference-equality checks make this handoff process-dependent. It cannot be serialized
and restored in another process. Acquisition
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
with a maximum permitted deadline of thirty seconds. Tests use a recording controller
stub; they establish gate behavior, not a durable authentication mechanism. There is no
serializable recovery or durable authentication claim.

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
projections and historical attribution. Each retained record's original envelope and block
is independently charged, even if already reachable from a branch. Before any commitment
hashing, envelopes are limited to 65,535 bytes and blocks to 1 MiB. Historical revision and
compacted-block counters must be nonnegative and at most 256 bits before decimal string
conversion; historical identities must be 32 bytes. Boundary and certificate-context capsules are
deduplicated by identity; repeated occurrences within supplied roots are charged again.
These limits budget already-existing object graphs. They are not decoder allocation
limits, serialized-byte admission limits or a JVM heap-size estimate. Replay/clone loops yield
between bounded records; underlying pure checks retain their existing collection limits.

Observed fixture measurements (entries / logical bytes):

| Fixture | Entries | Bytes |
| --- | ---: | ---: |
| Absent anchor, early successor | 686 | 162874 |
| Retained freeze after undo and compaction | 1080 | 225012 |
| Post-boundary Complete anchor | 1045 | 211093 |
| Post-boundary anchor, mixed-context successor | 2096 | 381350 |

## Verification, 2026-10-09

66 tests passed: 32 ledger and 34 app, including ten new recovery tests (two ledger,
eight app). The app tests cover exact final identity/revision and continued publication,
whole-boundary undo, Absent and ordinary/post-boundary freezes, mixed epoch contexts,
missing/mutated/substituted provenance and foreign pulser, controller denial/deadline/
cancellation, an aggregate nested-membership budget rejection, oversized substituted record
originals, oversized historical scalars and the exact incremental record-byte charge. Ledger tests cover
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

The [reference-free codec audit](synthetic-recovery-codec-blocker.md) records the
missing historical identity evidence that blocks converting this opaque handoff into
checked-content exact-ID import after arbitrary compaction.

Exact historical implementation-ID reconstruction is a separate unresolved contract,
not a blocker to durable recovery generally. The recommended future direction is
a controller-attested semantic anchor with fresh runtime identities and canonical
content/provenance commitments; old attribution is attested rather than replay
verification of discarded history. No persistence implementation is added here.
