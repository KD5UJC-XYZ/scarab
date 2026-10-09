# Pure restricted validated checkpoint recovery

This slice adds a bounded recovery image and checked in-memory replay. It adds no
NIO store, durable coordinator, filesystem acknowledgement, CLI or live adapter.
The acquisition checkpoint format remains distinct and cannot bypass validation.
See [the reviewed design](validated-checkpoint-design.md) for the later publication
protocol and source-based revision proof.

`ValidatedCheckpoint.encode` accepts a supplied `SequenceInput.Context` and a
coordinator-owned snapshot, a 32-byte store identifier, nonnegative publication
generation and capacity. It returns bytes and a `Token`. This is serialization of
an already available snapshot, not disk publication or an acknowledgement.

The fixed-order envelope has its own magic and coordinator profile, store/context
IDs, generation, capacity, coordinator revision and final tuple ID. It retains a
canonical source manifest, the seven exact source byte strings, and up to eight
original header/block pairs. Network, epoch and anchor are derived by rebinding
the pinned source bytes rather than duplicated in another trusted projection.
SHA-256 covers all payload bytes; the total bound is 40 MiB. Manifest/source/header/
block bounds remain 8 KiB/4 MiB/65,535 bytes/1 MiB. Revisions require canonical
decimal uint64 encoding; negative/overflow, R below retained count and odd R-count
reject. Extra bytes, oversized/truncated fields, wrong format and mismatched tokens
reject before any runtime is exposed. The code makes no binary compatibility claim
with the acquisition checkpoint format.

`decode` returns only a bounded untrusted `Envelope`, whose constructor is private.
`recover[F: Async]` requires an independently retained context ID AND exact token
(store ID, context ID, generation, payload digest), plus a positive deadline.
There is no optional-token or unchecked-resume mode. Public tokens are data, not
capabilities: supplying a token read from the same file does not establish freshness
or authenticity. A complete older image with its matching older external token is
undetectable as a rollback. No high-water service or uncertain-publication resolver
is implemented by this slice.

Recovery rebinds all context sources, checks the context ID, validates the NORMAL
revision-zero sequence seed, then privately rebases only that supplied anchor to
`R - n`. The narrow ledger bridge rejects a nonzero-revision or nonempty-head
input; it cannot hydrate receipts or arbitrary applied state. Normal `create`
is unchanged. Every original is structurally parsed, prepared through the complete
coordinator certificate/nonce/eligibility/ledger pipeline, and published only into
a private fresh runtime. Final tuple ID and revision must agree before that runtime
escapes. All old candidates/fences remain foreign to its fresh owner.

Coordinator revision is exactly ledger revision. Empty, single-transaction and
multi-transaction block applications each advance it once. Rollback advances once
per removed block. Hence legitimate history has `R - n = 2 * undoCount`, and replay
from `R - n` reaches `R`. Private transition heads and undo receipts are rebuilt for
the new lineage; their old process identities are neither serialized nor asserted
equal. Recovered rollback uses current revision and increases it monotonically.
An empty retained window at R>0 remains a supplied anchor, not an applied tip.

Cancellation checkpoints occur before decode, context binding, checked seed/block
parsing, each coordinator prepare, each publication, and final verification. The
deadline is cooperative: it can interrupt between these bounded synchronous stages,
not preempt a cryptographic operation already executing. A package-private observer
receives stage labels only for deterministic cancellation tests; it receives no
context, candidate, state or partially recovered runtime. The public path uses a
no-op observer. No single monolithic replay computation is wrapped as a purported
hard timeout.

Tests cover public synthetic anchor serialization, bounded rechecksummed malformed
envelopes, exact pins/tokens, source and final-ID mismatch, parity and uint64 edges,
and cancellation. Opt-in retained tests use `COHERENT_SEQUENCE_EVIDENCE` for the
real same-epoch sequence (including empty and two-transaction blocks) and
`COHERENT_POSITIVE_INPUT` for the successful one-transaction context. They compare
whole tuple contents, every retained rollback prefix, reapplication, n=0/R>0,
revision exhaustion and foreign capabilities. Fraction values are compared by
numerator/denominator, not object identity. Structurally accepted KES mutations and
rebound impossible-fee contexts must fail full certificate and ledger checking.
The existing ledger suite tests sixteen dependent transactions consuming one block
revision and late-transaction failure without intermediate publication. No full
recovery claim is made for a real sixteen-transaction Praos block absent such evidence.

The future durable runtime must keep the publication gate and poison boundary
through file installation, directory sync, in-memory installation AND acknowledgement
preparation. Poisoning only the NIO writer is insufficient. No result here establishes
graceful disk restart, post-ack process death, during-publication death or power-loss
safety. Raw private evidence, keys and logs remain outside Git.


## Verification of this slice

Based on committed main `0877ed08dee4eaa0d41a97783cdd4746503ab832` in a separate
worktree. Offline pinned JDK21 containers used network none, 2 CPUs/2 GiB and an
isolated cache. Formatting checks, 180 ledger tests and 288 application tests
passed with the opt-in successful single-transaction and sequence evidence mounted
read-only. All ten new recovery tests executed. Independent source review found
no blocking issue after the cancellation/deadline and boundary additions.

Private logs are retained in
`/home/<user>/cardano-validated-recovery-evidence-20261009/tests-final2.log`.
Earlier attempts remain alongside it: `tests-first.log` caught a fraction object
identity comparison in the new tests; `tests-final.log` used the older unsupported
address fixture for the optional single-transaction lane. The final run uses the
verified successful positive capture and compares fractions semantically. No new
cluster or live workload was run, and no main-checkout files were edited.
