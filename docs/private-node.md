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

`--mode sustained-durable` is rejected explicitly: checkpoint v1 cannot store a
derived anchor, including an empty suffix. No unsupported durable combination
falls back to volatile execution. Unknown or duplicate flags still fail before I/O.

Explicit `--audit true` records every acquired original header/block pair and a
complete token-free final state projection. It is off by default. Audit output
contains private reference data and belongs in private evidence storage. Acquisition
records do not claim application; terminal status and independent replay must bind
them to the committed state. Node execution never reads the post-oracle.

See [the 12-block operational acceptance plan](private-node-acceptance.md) for
the exact planned configuration, resource limits and comparison requirements.
The reviewed twelve-block same-epoch case passed; its exact scope and hashes are
recorded in that acceptance document. Sustained durable mode remains unsupported.


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
This mode has no anchor compaction and makes no power-loss, live-fork, epoch-
transition, full-ledger or full-consensus claim. Keep checkpoint files, receipts,
raw captures and reference cluster data outside Git.
