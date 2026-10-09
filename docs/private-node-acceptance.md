# Sustained volatile node acceptance plan

This is the integrated operational milestone plan, not a passed live result.
Use the ordinary `lab.Main node` command and its real argument parser. Keep the
reference launcher and offline audit separate from node execution; a post-oracle
must never seed online state. No durable compaction is supported.

## Fixed case

Use one reviewed private PV9/header11.2 fixture with supplied source-bound
bootstrap and twelve distinct forward blocks in a single epoch. Configure:

```text
node --profile conway-pv9-header11-2-derived-nonce-bounded-sequence-v1
  --bootstrap /evidence --port PORT --mode sustained-volatile
  --blocks 12 --rollback-capacity 4 --seconds 120 --events 128
  --bytes 33554432 --reconnects 0 --audit true
```

The early-epoch supplied anchor and twelve-block endpoint must leave enough time
in the existing epoch. Do not lengthen epochs or raise budgets in response to a
failed run. First observe an applied empty block, then submit the existing pair of
independent supported disposable transactions to the nonproducing relay. Require
an actual transaction-bearing original block, including both exact submitted
body/witness pairs together, rather than treating mempool admission as inclusion.

Require depth 12 and no repeated forward point. Capacity 4 must force checked
anchor advancement, with retained originals bounded to four and compacted depth
accounted separately. Revision and depth are separately reported. In the current implementation,
anchor advancement preserves revision; this straight-line case must finish at
revision 12, depth 12, compacted 8 and retained 4. All event/byte/time budgets remain
cumulative across compaction. One initial no-op rollback to the supplied anchor
is permitted and counted; later rollback/reconnect is not part of this case.

## Evidence and comparison

Readiness follows actual peer intersection and the checked coordinator anchor.
Record bootstrap provenance, acquisition cursors, committed applied progress,
every exact original envelope/block pair and final typed outcome. Acquired bytes
do not themselves imply validation. Require `TargetReached`, successful process
exit and finalized resources before accepting the online result. Budget or timeout
termination is a failed acceptance case and never a caught-up claim.

The node emits its token-free complete final state projection only in explicit
audit mode. After finalized node output and the complete projection are observed, pause
producers promptly within the existing bound and require the post-reference
endpoint to equal the exact node endpoint. Resume producers and verify the
ordinary process exited successfully before publishing the oracle manifest. Pin
poststate sources and captures in the existing oracle manifest. Run `node-audit`
separately: load the original bootstrap, replay every original with the same
capacity/compaction schedule, compare the entire final projection, revision and
derived-anchor provenance with the surviving node output, and compare full UTxO,
fees, counters, known nonce fields, parameters and stake/registration endpoints
with the reference. Equal endpoints do not prove continuity; same-epoch supported
transition checks provide the scoped path evidence.

Preserve raw prestate, originals, stdout, exact command/config, source and compiled
pins, timings and cleanup privately. Public evidence contains only reviewed code,
bounded summaries and hashes. Preserve every failed attempt. Never extend the
target, accept a later endpoint or automatically retry grouping/timing failures.

## Resource and review gates

One reference cluster uses at most 3 CPU/6 GiB; the ordinary node and later offline
audit use at most 1 CPU/1 GiB sequentially. Internal Docker networking only;
disposable keys/local test funds only; one slot totals 4 CPU/7 GiB and no more than
600 seconds including cleanup. Keep producer pauses at the existing pre/post
20-second and submission 8-second bounds. Unrelated containers are untouched.

Before the one live invocation: focused mode/config/status/audit/launcher tests,
combined source and exact command review, fresh private output, compiled-source
pins and a full integrated regression run. Hosted CI gates publication of the
integrated checkpoint. Independent development need not wait on CI. No cluster
is started merely by preparing this plan.

## Durable boundary

Reject `sustained-durable` explicitly. Checkpoint v1 rejects derived anchors even
with an empty suffix; the durable facade exposes no compaction. The separately
reviewed `ValidatorTransitions.Backend` seam owns session-bound candidates/tokens,
distinguishes volatile, loaded-verified and newly acknowledged state, and reports
terminal storage failure using qualified cached confirmation. Bounded durable
mode will be wired in its own milestone; the current case is volatile only.

## Recovery evidence provenance

The coordinating review approved backend seam commit
`929900948ee619f65498a13b3d25013a72a8822a` for a later integration after the sustained
volatile checkpoint. Its eight focused tests and source/class hashes were
reviewed independently. The separately reviewed graceful and post-acknowledgement
SIGKILL retained-file cases used the pinned `e7d1e6f` source: the killed process
exited 137 without OOM and a new process strictly recovered acknowledged state
before rollback/reapplication. These are process-recovery observations, not live
network continuation, compaction persistence or power-loss evidence.

One older raw 310-test adapter log was overwritten by a failed seam run and is
not retained evidence. Do not cite it as such. The separate 311-test follow-up and
the reviewed acceptance receipts remain available; the main integration's own
fresh tests and pins are recorded separately.
