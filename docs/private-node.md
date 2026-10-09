# Running the bounded private validator

`node` is the ordinary launch path for the current restricted validator. It loads
the source-bound bootstrap before opening a peer and does not wait for a reference
post-oracle or generate keys/transactions. Use it inside the approved isolated
local Docker development network with access to the reference peer through
loopback (for example, a shared container network namespace). It does not listen
for inbound connections, forge blocks or submit transactions.

```sh
bash scripts/sbtw 'app/run node --profile conway-pv9-header11-2-derived-nonce-bounded-sequence-v1 --bootstrap /private/bootstrap --port 3001 --blocks 4 --seconds 60'
```

Alternatively, in the prepared `/work` runtime container after compiling:

```sh
java -XX:ActiveProcessorCount=1 -Xmx512m \
  -cp "$(cat app/target/runtime-classpath.txt)" lab.Main node \
  --profile conway-pv9-header11-2-derived-nonce-bounded-sequence-v1 \
  --bootstrap /private/bootstrap --port 3001 --blocks 4 --seconds 60
```

The bootstrap directory must contain the existing `coherent-sequence-context-v1`
manifest and its seven pinned original prestate exports. Use the reviewed private
bootstrap preparation; arbitrary downloaded tip data is not a validated starting
point. Bootstrap state remains supplied and unauthenticated, and snapshots are
separate acquisitions. The profile supports the current restricted ADA/native
path in one epoch; full ledger and consensus validation remain false.

Configuration is explicit and rejects unknown or duplicate flags before opening
files or network resources. `--profile`, `--bootstrap` and `--port` are required.
Optional bounded policy flags are `--blocks` (default 4, maximum 8), `--seconds`
(default 60, maximum 120), `--events` (default 64, maximum 256), `--bytes`
(default 33,554,432, maximum 67,108,864) and `--reconnects` (default 0, maximum 4).
The peer address is fixed to IPv4 loopback and private network magic 1082026.

Structured stdout records report bootstrap, acquisition, applied/rollback progress
and the typed terminal outcome. Acquisition announcements and completed downloads
remain distinct from the committed scoped applied tip. State identifiers are
diagnostic commitments, never resumable authority tokens. Unsupported and rejected
inputs terminate rather than being silently skipped. Target or budget termination
describes why this bounded run stopped; neither means caught up or fully validated.

Owned peer/transport resources are managed by Cats Effect. Cancellation waits for
resource finalization; cleanup failures cannot be reported as a successful run.
Status-output failure is terminal. Output delivery itself is not durable evidence
of publication. Bounded durable storage and sustained volatile windows remain separate modes;
see [the implementation plan](private-node-milestones.md).

## Sustained volatile mode

Select `--mode sustained-volatile` with explicit `--blocks` (9..256) and
`--rollback-capacity` (1..8). The ordinary default remains `bounded-volatile`,
with at most eight retained blocks and no anchor advancement. Sustained mode
advances a checked in-memory anchor before the retained window fills. Status
reports total depth, retained blocks, compacted blocks and derived-anchor identity
separately. It remains same-epoch and bounded by cumulative event/byte/time budgets.

Checkpoint v1 cannot store a derived anchor. Sustained durable execution uses the
separate v2 mode described below; it never migrates or falls back to a v1 or volatile
store. Unknown or duplicate flags still fail before I/O.

Explicit `--audit true` records every acquired original header/block pair and a
complete token-free final state projection. It is off by default. Audit output
contains private reference data and belongs in private evidence storage. Acquisition
records do not claim application; terminal status and independent replay must bind
them to the committed state. Node execution never reads the post-oracle.

See [the 12-block operational acceptance plan](private-node-acceptance.md) for
the exact planned configuration, resource limits and comparison requirements.
The reviewed twelve-block same-epoch case passed; its exact scope and hashes are
recorded in that acceptance document. The sustained durable CLI below is a separate milestone and does not inherit that live acceptance claim.


## Bounded durable create and resume

The reviewed isolated case passed ordinary-node graceful restart and new live
continuation through four blocks. See the acceptance record for its exact source,
receipts and limits. Existing retained-file process-kill evidence remains separate.

Select `--mode bounded-durable` and supply `--store-action create` or `resume`,
`--store` for the checkpoint directory, `--receipts` for a separate private
receipt directory, and `--expected-context` for the independently retained
64-character lowercase context commitment. The usual profile, bootstrap and peer
flags remain mandatory. Paths must be absolute and normalized; receipt authority
must be external to the checkpoint store. The bounded capacity is eight, and
`--blocks` names cumulative depth from the supplied anchor, including the prefix
restored during resume. A target already reached requires no peer connection.

Resume also requires `--resume-receipt` and `--resume-sha256`, selecting one exact
acknowledged receipt by path and independently retained SHA-256. The implementation
does not discover authority from the store or fall back to a different generation.
Retain the original receipt bytes privately. Pending records are a separate format
and cannot authorize resume; an output line is not itself a durable receipt.

Recovered state starts `loaded-verified`. A no-op intersection or rollback keeps
that classification and generation. Only a successfully returned storage
acknowledgement permits a new acknowledged receipt. If storing that receipt fails,
the node stops with the actual confirmed state and an explicit stale-external-
receipt qualification. It never reports an older artifact as the current receipt.

After a storage failure, status uses only the cached last-confirmed state and
preserves its possibly-older-than-disk qualification. That cache may represent a
loaded checkpoint rather than a new acknowledgement. The runner does not query
the failed backend, retry the publication or continue acquiring blocks. Cleanup
failure is reported separately even when storage failure is the primary reason.

The backend is acquired and checked before the peer, and remains owned until the
peer is released. Resume verifies context, original replay and stored capacity.
This mode has no anchor compaction. The separately reviewed
[local fork acceptance](private-cluster-fork.md) demonstrates peer-selected bounded
undo and graceful handoff, not power-loss recovery, epoch transitions, full-ledger
or full-consensus validity. Keep checkpoint files, receipts,
raw captures and reference cluster data outside Git.


## Intersection and rollback evidence

Every actual peer intersection emits these ordered JSON lines:

- `node-intersection-offered`: `offeredPoints` is the exact ordered vector passed
  to ChainSync (at most nine points, including the retained anchor).
- `node-intersection-selected`: `selectedPoint` is the peer's actual return;
  `offeredMatch` says whether it belongs to that vector. An unoffered selection is
  recorded before the runner rejects it. A failed intersection has no selected row.

Both rows carry `acquisitionOnly: true` and `appliedClaim: false`. A block point is
`{"hash":"<lowercase hex>","slot":<JSON integer>}`; Origin is `{"origin":true}`.
The offered row precedes the call, and the selected row precedes rollback checking.
Neither proves state installation. Each retry emits a new ordered pair; no offers
are recomputed from the later state. These small rows do not require `--audit`.

With `--mode bounded-durable --audit true`, `node-rollback` additionally includes
`projection`, using exactly the canonical full checked-state schema of
`node-loaded`: context and tuple IDs, anchor/tip/applied tip, original header/block
hashes, certificate state and counters, nonce state, eligibility and complete
ledger UTxO CBOR hex/fees/slot/IDs. The existing top-level `revision`, `depth`,
`stateId`, `confirmation`, `confirmedGeneration`, `receiptPath` and `receiptSha256`
describe that same snapshot. The projection omits revision internally; use the
top-level revision. Receipt references are evidence identifiers, not key material.

This row is emitted after rollback returns successfully and the external
acknowledged receipt has been recorded, before the next acquisition event. A
nonempty rollback is `acknowledged` with a new generation; an unchanged resume
intersection remains `loaded-verified` with its original generation. In particular,
`initialIntersection: true` does not mean no-op: the first selection can roll back
the loaded A branch to common point C. Compare the loaded and rollback depth/tip,
revision, generation and full projections to demonstrate removal of A effects.

Each projection is limited to 16 MiB of UTF-8 JSON, as for `node-loaded`; exceeding
that bound terminates the audited execution. Rollback projections have a separate
64 MiB cumulative budget. Once a projection would exceed it, the row instead has
`projectionOmitted: "cumulative-size-bound"`; it is not complete rollback evidence.
No projection is added to volatile or audit-disabled rollback rows. Output failure
remains terminal and does not authorize continued acquisition or a receipt retry.

For the bounded fork acceptance, retain `node-loaded` proving restored A,
the actual offered/selected pair proving C was selected, and the acknowledged
`node-rollback` projection proving checked rollback to C before B adoption. This
telemetry alone is not live-fork acceptance, canonical-chain selection, or full
ledger/consensus validation. This bounded v1 acceptance retains capacity eight; the v2 mode below is separate.


## Sustained durable v2 create and resume

`--mode sustained-durable` uses the reviewed combined local v2 journal/checkpoint
owner in one trusted process. Supply explicit `--blocks` (9..256),
`--rollback-capacity` (1..8), `--store-action create|resume`, `--store`, `--journal`,
`--receipts`, `--store-id` and `--expected-context`, in addition to the normal
profile/bootstrap/port flags. The three directories must be absolute, normalized,
pairwise separate and non-nested. Store ID and context are independently supplied
lowercase 32-byte hex values. The binding uses the exact reviewed profile, v2
format/authority and `in-process-resource-v1` launch policy. Cumulative time,
events and bytes retain the existing node bounds.

Create additionally requires `--seed-capture`, `--seed-sha256`, `--compact-slot`
and `--compact-hash`. The capture is an external bounded regular file containing
only 1..8 `transfer-range-block` original records, no more than the requested
capacity. It is read through its exact independent SHA-256; the compaction point
must belong to that supplied nonempty prefix. The combined owner replays the
complete prefix through the existing validation pipeline before publishing a
derived checkpoint. This is not admission of an arbitrary downloaded checkpoint.
Seed files cannot reside inside the journal, checkpoint or receipt directories.

Resume uses only the exact separately persisted controller journal binding and
its full claim. Seed flags and v1 `--resume-receipt`/`--resume-sha256` are forbidden.
There is no bootstrap fallback, checkpoint scanning for authority, older-generation
selection or v1 migration. Keep the journal independently persisted from the
checkpoint data. Loss of either domain is not repaired by an exported receipt.

`node-bootstrap.suppliedAnchor` always names the original source-bound context
anchor. `retainedAnchor` names the current window anchor; v2 compaction makes them
different. Seed file, pin, record-parsing and compact-point membership checks
reject before checkpoint or journal acquisition. Semantic seed replay occurs
inside the combined backend after its owned resources are acquired; those
failures are not classified as pre-acquisition seed rejections.

V2 state rows include `storageVersion: "v2"`, the actual `trustedLocalPrefix`
qualification, `fullClaim` and `storageBinding`. The full claim includes every
token, format, profile, authority, anchor-state ID, final-state ID, compacted count
and revision field. The derived-anchor provenance digest and anchor-state ID are
different commitments. Status does not make either into new resume authority.
`v2-loaded-verified` reports the journal-selected full claim and `node-loaded`
projection before any peer event; it has no newly manufactured acknowledged file.
No-op rollback preserves that classification. A successful publication or anchor
advance becomes `v2-acknowledged`; anchor advancement can change generation and
claim while leaving ledger revision unchanged.

Acknowledged v2 exports use separate immutable `node-v2-acknowledged-v1` files,
with full claim, exact binding, capacity and `diagnosticOnly: true`. They are
forced to disk before reporting their path/SHA and never select a resumed state.
The controller journal independently authorizes that state. Export failure stops
with the actual carried acknowledgment and `externalReceiptStale: true`; storage
failure uses only cached confirmation with its possibly-older-than-disk qualifier.
Audit-enabled v2 rollback uses the same bounded full projection and omission rules
as v1. Cleanup errors remain separate; no poisoned backend is queried for output.

This CLI wiring has no live acceptance, power-loss, malicious-writer, cross-epoch,
full-ledger or full-consensus claim. Its trusted-process model excludes rollback
of both independent storage domains. Keep all journals, checkpoints, seed captures
and diagnostic receipts outside Git.
