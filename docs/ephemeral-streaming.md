# Bounded ephemeral coordinator streaming

`EphemeralStreaming` is an internal sequential driver around the existing
`CoherentSequence.Runtime`. A caller supplies one already parsed `SequenceInput.Block`
or rollback point per pull. Existing coordinator preparation still checks exact original
header/block identity, commitments, certificates, nonce/eligibility and the supported
ledger transition. Parsing an input alone does not establish acceptance.

The driver can accept more than eight blocks over its lifetime. Before an incoming
block would exceed the selected retained depth, it advances the acquisition anchor
and drops the oldest undo receipts. Depth is 1..8 and cannot exceed runtime capacity.
Compaction preserves owned frozen reward inputs and historical boundary provenance.
Only the current event, preparation and final report are held by the driver; it does
not accumulate candidates, per-block snapshots, reports or an original-byte transcript.
The source must itself be bounded and must not retain delivered events unnecessarily.

## Limits, ownership and cancellation

Limits independently bound delivered events, attempted blocks, logical input bytes and
elapsed execution. Rollback and compaction never reset those counters or the clock
within a run. Block bytes count the original envelope, original block and reconstructed
transaction memos; each rollback is charged 64 logical bytes. These are accounting
bounds, not a measurement of aggregate JVM heap. Existing coordinator/component
collection and input-size limits still apply.

The event limit stops before another pull. Block/byte sizes become known after a pull,
so one delivered attempt can exceed either counter limit; it is counted and refused
before compaction or publication. Accepted-block count does not decrease on rollback.
A new invocation starts a new lifetime budget; this is not a durable or cross-invocation
quota. A report contains only final snapshot, counters, elapsed time and stop reason.

The caller exclusively owns the runtime and source during a run and manages source
resources, for example with an outer Cats Effect `Resource`. The driver neither opens
nor closes a network connection. External cancellation and source exceptions propagate.
The deadline cancels cancelable work, with elapsed-time admission checks before applying
a delivered event and publishing a prepared candidate. Synchronous bounded crypto and
short masked mutation/accounting sections can finish beyond the nominal deadline;
an uncancelable source cannot be forcibly interrupted. No hard wall-clock guarantee
is claimed. Publication and its counter update are masked together.

## Rejection and rollback availability

Compaction is explicitly an availability decision, not finality or validation. At a
full window it happens **before** the incoming block is prepared. If that block is
invalid, the final report includes the new anchor and discarded history. Atomic
rejection is therefore assessed against this post-compaction state: the rejected
block publishes no ledger, stake, nonce, certificate or reward transition. Callers
requiring unchanged rollback availability on invalid input need a different policy.

Rollback can restore an anchor or retained point, including a retained epoch boundary;
older targets fail without changing state. Compaction changes the tuple identity and
invalidates fences/candidates tied to its earlier identity even when the ledger revision
is unchanged. Replay compares semantic state where a newly prepared boundary legitimately
has a different revision-derived attribution. A retained historical frozen capsule is
preserved exactly until the corresponding reward transition replaces it.

## Scope

This is an offline synthetic acceptance slice. The internal synthetic successor APIs
retain all seven explicit no-effect assumptions. No network adapter, native seed
admission, public CLI activation, serialization, persistent cursor, epoch-guard change,
native boundary equivalence, full ledger validity or consensus validity is added.
The finite empty-governance and non-myopic comparisons elsewhere do not broaden this
driver's admission contract.

## Verification

The fixture generates **25 linked signed empty blocks**, slots 1..20 and 40..44,
with epoch length 40, ASC 1, security parameter 1 and reward window 4. Reward inputs
freeze at slot 5; the first epoch successor is slot 40. Public deterministic test
Ed25519 seeds and public VRF scalar/nonce one produce the original bytes. Every
fixture block passes the existing body-commitment, certificate, nonce and eligibility
checks. These deliberately public keys are test material, not production key generation.
The blocks contain no transactions; this slice tests streaming composition and does
not extend monetary/body coverage from existing coordinator tests.

**13 focused tests passed**: eight new streaming tests and five existing public
`CoherentSequenceSuite` tests. The optional capture-backed `SyntheticRecoverySuite`
was named in the command but had no cases enabled; none are counted. The eight new
tests cover 25-block progression with at most eight originals/receipts and nine recovery
states, unchanged frozen identity across repeated compaction, retained epoch-boundary
rollback and semantic replay, refusal beyond the window, compaction-only stale fences
and previews, invalid-successor atomicity with disclosed availability changes, independent
event/block/byte/deadline budgets, capacity checks, and cancellation/resource cleanup.
Trace assertions retain only scalar metadata, not old coordinator snapshots.

The final command ran after formatting:

```text
app/Compile/scalafmt
app/Test/scalafmt
app/testOnly lab.EphemeralStreamingSuite lab.CoherentSequenceSuite lab.SyntheticRecoverySuite
```

Execution used an inspected local `cardano-public-v023-check:local` image,
`--pull=never --network=none --cpus=2 --memory=2g --memory-swap=2g --pids-limit=256`,
read-only container root, private copied build/cache and JVM Xmx1200m. The final
format/test process took 22.265 seconds; its owned container was confirmed absent
after cleanup. No captures, live cluster, source downloads or shared cache writes
were used. Command, image, test log, receipt and final formatted source hashes
remain in private execution evidence outside Git.

Independent read-only review approved the driver, fixture, tests and scope subject
to this final run passing. This is focused offline verification, not a full public
suite result or native/cluster acceptance.

## Integration verification

The integration check passed formatting and all three focused suites: streaming,
coherent sequence and synthetic epoch coordination. The retained test log SHA256
is `f65fba8dcb974cde3efb2e16796a4e23a2c33baa3db191da99960fc66e83cc58`. Execution used a private copied cache
with network disabled, 2 CPU and 2 GiB; owned container cleanup was verified.

Cancellation tests cover a waiting source and source-resource release. Cancellation
during publication was not injected; masking of publication and accounting was
verified by source review. The deadline is cooperative, source buffering remains
the caller's responsibility, and logical byte accounting is not a heap bound.
No native, network, CLI or transaction-bearing epoch crossing is established.
