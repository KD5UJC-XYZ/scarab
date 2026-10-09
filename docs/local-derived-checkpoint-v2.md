# Pure local-derived checkpoint v2

This slice implements an **explicitly trusted local prefix** plus ordinarily
verified retained suffix. It is a research crash-recovery contract, not proof of
the discarded prefix, consensus finality, chain selection or a new supplied
snapshot. Full-ledger and consensus flags remain false.

Trust is in prior execution by the reviewed local writer and a controller-retained
exact publication record in a separate persistence domain. Controller and writer
may be private roles within one trusted process; no process isolation is claimed.
A compromised process, privileged tampering, or restoring both domains to an older
point is excluded. The pure codec changes no credentials or security configuration.
See `combined-local-v2.md` for the local durable backend; ordinary runner defaults
and live cluster behavior remain unchanged. Existing `ValidatedCheckpoint` v1 remains unchanged, including its
rejection of compacted states.

## API and authority

`Runtime.exportLocalCheckpoint(storeId, sessionId, generation)` reads one owned
coherent cell and produces a `LocalDerivedCheckpoint.Publication`. Its constructor
is private; the export cannot mix an anchor and suffix from different cell reads.
It requires a compacted state with `compactedBlocks > 0`. Export is only a pure
image operation: no durable acknowledgement, disk freshness or controller approval
is implied. The combined durable facade uses a private proposed-cell export under its gate.

`decode(bytes)` returns an `UntrustedEnvelope`. A self-checksum is just corruption
detection. The claim binds the whole-image digest, store, context, issuer session,
generation, format, validation profile, authority label, anchor/final IDs, compacted
depth and revision. `Claim` and package-internal serialization data are ordinary
untrusted values, never recovery capabilities.

`ControllerAuthority[F].authorize(claim)` is an explicit external-policy boundary.
`accept(envelope, controller)` issues a privately constructed `AcceptedAuthority`
only after that policy accepts the exact claim. The combined local facade supplies a private exact-registry policy scoped to one
requested verification under the store lock. The pure codec itself supplies no
policy. Tests use a visibly named `TestOnlyController` with an exact retained
record. A policy that merely accepts a checkpoint's own checksum is incorrect.

`recover(bytes, expectedContext, Some(authority), deadline)` requires that same
accepted claim before it can construct an internal authorized image. Missing,
foreign or changed authority fails. Accepted capabilities are reusable and do not
expire/revoke themselves: the pure module neither consults a global latest token
nor claims anti-rollback protection. The combined session facade reauthorizes
each resume against current controller state and does not cache an acceptance
across a store/session/generation change. Scala constructor/package visibility is
an API misuse barrier, not isolation from adversarial code in the process.

## Contents and recovery

The image preserves the original source manifest/files and context, full rebased
anchor, exact retained original bytes, compacted count and history-dependent
provenance. The anchor is the first retained receipt's before-state, or the current
state for an empty suffix. It carries exact certificate and nonce IDs/fields,
original ledger environment/checkpoint identity, output spans, fees, slot, private
head, eligibility result and coherent anchor identity.

Narrow package-internal `trustedRestoreLocal` helpers check shape, bounds and
cross-bindings. Ledger content identity and canonical map framing are recomputed
while original output spans are preserved. The helpers do not reseed certificate
or nonce state, replace the ledger checkpoint identity, or reset its head. Opaque
historical IDs and provenance are accepted only under the external trust contract;
local consistency checks cannot re-prove the discarded transitions.

With compacted depth `d`, suffix count `n` and final revision `R`, require
`d > 0`, `n <= capacity <= 8`, `d+n <= R <= Word64Max`, and even `R-d-n`.
Reconstruct the anchor at replay revision `R-n`, then parse/prepare/publish each
original through the ordinary certificate, nonce, eligibility and ledger path.
This creates a fresh runtime owner and new suffix undo receipts; none are loaded
from disk. The original accepted anchor head is preserved. Suffix heads may change
because transition hashes include revision: their new receipts must agree with
the regenerated heads, not historical suffix heads.

Final ID, point, revision, depth, provenance and tuple bindings must match before
the runtime escapes. Cancellation/deadline/failure discards partial recovery.
Rollback to the derived anchor is supported, while rollback below it fails.
Empty suffixes keep their scoped checked tip.

`State.trustedLocalPrefix` makes recovery provenance explicit and propagates
through publication, rollback and compaction without altering content IDs.
Restored anchor eligibility has `historicallyTrusted = true` and
`freshlyVerified = false`: the existing verification flag denotes attested prior
verification, not a new VRF check at recovery. Replayed suffix eligibility is
freshly verified; the prefix remains explicitly trusted.

The codec bounds the full image at 40 MiB, anchor at 2 MiB, seven source files at
4 MiB each, envelope at 65,535 bytes, block at 1 MiB and retained count at eight.
It retains existing 4,096-entry/1 MiB ledger and 10,000-counter bounds. Integers,
counter ordering/uniqueness, nonce tags, hash widths, exact field lengths and
trailing data are checked. Aggregate limits apply even when individual fields fit.

## Later controller and storage requirements

These are required integration contracts, **not implemented by the pure codec**:

1. Retain an explicit active-store selection and writer session/lease epoch in separately persisted controller state. Identify each publication attempt by a unique operation ID, exact
   predecessor token, successor token, store, context and issuing session. Bind
   the full successor claim, not just generation or ledger revision.
2. Record pending publication with controller-side compare-and-set against the
   currently acknowledged predecessor and active session. Preserve the prior
   acknowledgement while recording the permitted pending successor. Reject a
   callback whose predecessor, operation, store or session is obsolete.
3. Apply the existing recorder-before-disk, staged write/fsync, atomic replacement,
   directory fsync, verification, controller acknowledgement, then memory publication protocol. Compaction
   has its own durable generation even though its ledger revision is unchanged.
4. Acknowledge by compare-and-set of that exact pending operation/successor. A
   delayed recorder from a canceled/timed-out attempt must never overwrite a newer
   acknowledgement, session selection or pending attempt. Duplicate callbacks
   are idempotent only for the exact same operation and phase; conflicting callbacks
   fail. A timed-out callback may still finish, so cancellation alone is not a fence.
5. Resume reauthorizes against this current record: a pending transition permits
   only the explicitly recorded old/new outcomes; an acknowledged successor never
   permits silent fallback to an older image. Ambiguous or inconsistent records
   stop. A session-bound backend exposes trusted-local recovery distinctly from
   original-seed replay, and must preserve terminal storage-failure behavior.

Migration is explicit. Quiesce the source writer, fully recover v1, require a
nonempty checked branch, and explicitly compact before exporting into a new v2
store. Keep the source store/token as evidence. Select the destination as the sole
active store by controller CAS only after destination publication is acknowledged.
If acknowledgement/selection is uncertain, stop and reconcile the exact migration
operation; do not auto-select the numerically highest generation across stores or
resume both writers. An empty bootstrap stays on v1. There is no v2-to-v1 conversion
for discarded history without obtaining and replaying that history.

## Focused verification

`LocalDerivedCheckpointSuite` uses the first three/four unmodified linked signed
blocks from `COHERENT_WINDOW_EVIDENCE`, a complete source-bound retained capture.
It covers two-receipt rollback/reapplication, repeated compaction and recovery,
empty suffix/private anchor head, fresh ownership, historical eligibility labels,
authority mutations, structural and suffix failures even under explicit test
acceptance, revision parity/offset/ceiling, canonical malformed payloads with
recomputed checksums, cancellation/deadline and v1 format separation.

Run the affected component suites and retained v1/coherent suites with the fixture
mounted read-only. Test authority is only a deterministic stand-in for the later
controller. These tests do not demonstrate production controller freshness,
crash-safe NIO publication, migration or live restart. Those remain separate gates.

Verification for this packet: **87 passed** (18 core, 33 ledger, 36 app):

```text
core/testOnly lab.header.PraosCertificateStateSuite lab.header.PraosNonceEvolutionSuite lab.header.PraosEligibilitySuite
ledger/testOnly lab.ledger.ClusterTransitionSuite
app/testOnly lab.LocalDerivedCheckpointSuite lab.ValidatedCheckpointSuite lab.CoherentSequenceSuite
```

The Docker process used `--network=none --cpus=2 --memory=2g --memory-swap=2g`,
JVM `-XX:ActiveProcessorCount=2 -Xmx1200m`, a worktree-private build/cache, and
read-only mounts of `cardano-live-runner-live2-20261009` plus
`cardano-nonce-freeze-live1-20261009`. The new suite's nine tests use the named
test-only authority. `scalafmtAll`, compilation, `git diff --check` and independent
read-only review passed. No full-suite repetition or new live run was performed.
The untracked execution log is `.cache/v2-tests.log` in the isolated worktree.
