# Sustained durable v2 ordinary-node acceptance preparation

This is a controller candidate for independent review and offline tests. No live
execution is implied by this document or its synthetic guard fixtures. Main must
coordinate the exclusive live slot before invoking it; do not modify a previous
run or reuse its checkpoint/journal directories.

## Fixed scope and resource budget

Run `scripts/private_cluster_sustained_durable.py --plan` to inspect the bounded
scenario. Actual invocation requires explicit fixture profile, pinned reference
image, private fresh output directory and a reviewed compiled Scala checkout.
Also require `--source-pin` and its independently retained `--source-pin-sha256`.
The pin must cover every current tracked file and every file in the resolved
runtime classpath, with a clean integrated commit, pinned JDK image and exact
launcher copy. It is checked before resources and again after audit; partial old
baselines are rejected. The ordinary node uses loopback and private magic1082026 only. No production
credentials, public-network submission or real funds are involved.

The reference owns 2 CPU/2 GiB and the one current Scala process owns 1 CPU/1 GiB.
Seed, A, B and network-none audit execute sequentially. The inherited reviewed
workload remains420..480 seconds with an absolute600-second cleanup deadline.
Seed+A+B share120 seconds,128 events and32 MiB returned original bytes; each
launch gets only the remaining allowance. The inherited outer pause ceilings are
20 seconds, or 8 seconds for submission. This case enforces tighter caps including
cleanup: prestate/seed setup 10 seconds, seed-to-A setup 5 seconds, A-to-B restart
5 seconds, and paired submission/watcher setup 4 seconds. Frozen poststate work
retains its separate 20-second bound. No target extension or retry widens these bounds. An endpoint race, changed epoch, grouping failure, missing
receipt, timeout or uncertain storage result fails the case and preserves evidence.

## Seed, create and strict journal resume

Retain the seven source-bound prestate exports in the existing stopped-producer
bracket, plus the inherited bounded raw full-ledger CBOR side-export and its tip,
configuration/image/genesis/CLI provenance. The pair is built while producers run,
but not submitted yet. Require early-epoch headroom, Conway PV9/header11.2 and the
same reference instance throughout.

An ordinary bounded-volatile process obtains exactly2 empty original successors.
It exits and releases its resources before A starts. Pin the exact two capture
records and compact through the first original. Capacity2 canonical replay would
make the same first compaction before applying original3, so the final independent
audit can replay the complete canonical sequence from the original supplied anchor.

A is sustained-durable create, cumulative target9, rollback capacity2. Its initial
v2 checkpoint has depth2/compacted1/retained1/revision2/generation0. It applies7 NEW
live blocks with6 additional compactions, ending at revision9/generation13,
depth9/compacted7/retained2. All are empty; transactions are reserved for B.

Pause producers at A's exact endpoint. Require graceful exit0/noOOM and actual
finalization before launching distinct process B. Retain exact checkpoint and
journal bytes and SHA-256, plus checkpoint payload/trailer binding to the returned
full claim and every immutable diagnostic export. Store/journal mounts keep their
same explicit paths and independent binding across the two processes. Do not choose
authority by looking at a checkpoint or diagnostic receipt.

B resumes the exact separately persisted journal binding. Its node-loaded full
projection and EVERY full-claim field must equal A's final acknowledgment, including
the old session ID. This must occur before peer intersection; loaded readiness writes
no acknowledged receipt. The original supplied anchor remains distinct from the
current compacted retained anchor. Initial intersection and any supported alignment
rollback are exact no-ops.

After checked B readiness, submit the prebuilt pair through the nonproducing relay
inside the separate 4-second submission/watcher pause (within the inherited
8-second outer ceiling), retaining both admission records. B must apply3
NEW successor blocks, with the pair together in exactly one and the others empty.
Actual acknowledged successor claims switch to one new B session, with3 further
compactions and3 publications. Final revision12/generation19, depth12/compacted10/
retained2. Compaction changes claim/generation without changing ledger revision.

## Final independent reference comparison

Before releasing B's submission pause, arm a bounded reference-side watcher for the
predeclared absolute height `pre.block + 12`. A 64 KiB bounded relay ChainDB log tail
is only a stop hint. The watcher checks both existing producer PID/start-time/command
identities, stops both in one call, then the controller queries the frozen relay and
requires the exact same-epoch height, hash and slot. Only then does it await B's
terminal result and capture full post oracle plus raw ledger CBOR. The unchanged
exact-point comparison also binds B's complete result. This is not a deterministic
forging ceiling: any overshoot, malformed/missing hint, partial pause, identity
mismatch or deadline failure rejects the run; the target is never extended.

The watcher starts its container-monotonic pause clock
before STOP and begins auto-resume cleanup at 19 seconds, reserving time within the
20-second bound. The controller reserves two seconds for release and verifies the
actual STOP-to-CONT duration. Production waiting stays within the existing online budget.
The controller conservatively derives the remaining pause allowance from an elapsed
container-clock sample, without equating host and container clock origins. A release/
done handshake and independent identity-checked finally path resume both producers,
including failure paths. There is no unbounded tail child or FIFO. Resume before audit
work and the inherited epoch-growth observations. Verify reference instance,
configuration and all genesis hashes again. Preserve original raw process logs.

Concatenate exactly seed2+A7+B3 original capture rows, and only B's finalized
node-state projection, into a NEW separate audit input. Never filter substantive
rollback or state failures into a different history. Run `node-audit ... 2 12`
network-none: it must derive the complete tuple from the prestate and all original
bytes, then compare complete online projection and the pinned reference endpoint
(UTxO/fees, certificates, nonce fields and tip). The transaction pair must occupy
one B continuation block, indices9..11. All11 other blocks are empty.

Diagnostic v2 exports are independently retained evidence only. The controller
journal is the resume authority. Checkpoint/journal domains remain separate; this
case does not establish hostile-writer isolation or recovery from rollback of both.
The success scope is graceful compaction/restart with actual new live continuation,
not power-loss/process-kill recovery, fork choice, epoch transition, capacity1 rolling
operation, full ledger validation or consensus validation. Snapshots remain separate
non-atomic acquisitions under observed quiescence. Every owned resource must be
cleaned within the unchanged absolute deadline before a final acceptance claim.


### Readiness and cumulative time budget

The unchanged fixture has 500 slots per epoch at 0.1 seconds per slot. Readiness
uses the current wall clock bracketed against the container clock and pinned
genesis `systemStart`, rather than requiring a block in slots 1–30. Three converged,
geometrically consistent Conway tips must belong to the current epoch, with at
least 45 seconds remaining and at least 44 seconds of usable production allowance
after charging the entire clock-query bracket. A bounded history retains every considered tip/clock
sample, rejection reason and the accepted genesis hash and budget.

This is an opportunity budget, not a guarantee of twelve stochastic block arrivals.
The 45 seconds consist of processing caps of 10 seconds for prestate/seed setup,
5 for seed-to-A setup, 5 for A-to-B restart and 4 for submission/watcher setup,
at least 20 seconds of aggregate production opportunity, and a one-second stop
margin. Unused processing time remains available under the same immutable fence.
Live1's observed anchor-to-target span was 38.6 seconds, including 15.45 seconds
of those processing pauses; these observations justify trying the bounded window,
not widening it after an unsuccessful attempt. The launcher does not regenerate genesis to select a favorable leader schedule.

The production/frozen-point verification deadline is fixed from the monotonic time
before the fresh UTC query bracket and the epoch end, minus one second. Seed and A
waits reserve 14 and 9 seconds respectively for subsequent processing. Shortened
pause caps include identity-checked CONT cleanup, and both producers receive an
independent resume attempt after errors. Every phase spends the same budget; no
pause, restart or wait renews it. A late or overshot frozen endpoint is rejected.

Only after the exact endpoint is frozen may the poststate bracket extend beyond
the wall-clock epoch boundary, within its separate 20-second limit including B
completion and release. Both producers remain stopped and all authoritative
tip/ledger points must remain at the exact admitted epoch/endpoint. The shared
online deadline is `min(admission monotonic start + 120, production deadline + 35)`.

Admission also reserves 245 seconds beyond the production deadline: 20 for frozen
post work, 15 for finalization/storage retention and pre-audit evidence, 65 for the
offline audit, 10 for final source-pin verification, 110 for the inherited two-epoch
growth/polling opportunity, and a 25-second final guard. The 110 seconds are a
minimum reservation, not a newly imposed maximum on the inherited tail. The minimum complete case
reservation is therefore 289 seconds. Audit and final-pin work have explicit stage
fences; inherited epoch growth still has to pass its own checks. Workload 480 seconds,
absolute cleanup 600 seconds, CPU/memory limits, exact targets and validation scope
are unchanged. Any spent reservation fails without extending a deadline.
