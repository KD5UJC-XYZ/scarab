# Competing-branch durable rollback acceptance

This opt-in acceptance controller uses the ordinary `lab.Main node --mode bounded-durable`
runtime and the integrated audited intersection/rollback hooks. One bounded local
fork acceptance passed on 2026-10-09, as scoped below. Main owns integration,
the fresh source/classes pin and the runtime resource grant. Do not launch from an
uncommitted checkout or reuse another run's evidence directory.

## Scope and fixture

The controller generates a new disposable `spo,spo,relay` environment but starts only
two producers. Each keeps its own generated pool keys and DB. It never clones a DB
or starts the relay. Producers converge on C, restart without forging arguments,
and are checked against their actual `/proc` command lines and start identities.
Discovery is disabled. After both stop, their topologies become empty before the
two branches are forged sequentially. TA and TB spend the same C input with
different complete outputs and fees. The controller retains exact submission
exit/stdout/stderr, signed transaction originals, inclusion observations and complete
state queries. Key material remains inside the disposable reference container.

Fresh genesis changes are verified against the generated originals before any DB
exists: empty Byron allocations, Shelley PV9 and **epochLength 1000**. Slot length
remains 0.1 seconds, k=5 and f=0.05; the derived nonce stabilization window remains
ceil(4k/f)=400 slots. Effective/genesis hashes are retained and rechecked at the end.
This longer-epoch fixture was explicitly reviewed for the bounded local attempt.
Common C is taken from equal Conway tips at epoch one or later. There is no
arbitrary first-25-slots leader window: it has no protocol or nonce justification.
UTC clocks are bracketed with a one-second margin and checked against genesis
systemStart. Admission requires at least 58 seconds of real epoch headroom:
20 seconds to freeze/query C and prepare transactions, 35 for both branches, and
3 seconds of stop margin. This measures current UTC, not the age of a frozen tip.
Later stage guards must still leave 26 seconds before fork
setup, 18 before A and 9 before B. The forging loop deadline is three seconds
before the calculated boundary; this does not guarantee that the process has
exited by then. Graceful exit and same-epoch captured headers remain mandatory
acceptance checks. This is a bounded attempt, not a guarantee of leader slots.
The keyless endpoints and later JVM phases can remain frozen across a wall-clock
epoch boundary; all accepted branch headers must belong to C's epoch.

Admission also requires sufficient **remaining** case time. It reserves 20 seconds
for preparation, 35 for forging/freezing, 10 for complete endpoint oracles and 5 for
final pin checks. The remaining time is divided equally among Scala A, Scala B
and the auditor, capped at 45 seconds each and requiring at least 15 each. Thus
admission needs at least 115 seconds remaining. Absolute cumulative deadlines
enforce those reservations; a slow early stage cannot consume the later stage's
reserve. Early completion can leave additional time for a later stage, still under
its 45-second cap and the unchanged 240-second overall limit. These are explicit
attempt budgets, not guaranteed production or JVM completion times.

For live2's first common block at slot 1064, the recorded clock pair implies about
117 seconds of remaining case time and over 90 seconds of epoch headroom. That
fits the budget at roughly 15.7 seconds per JVM phase; it failed only the former
arbitrary 25-slot guard. A later observation with less than 115 seconds remaining
rejects before freezing C even if its epoch headroom remains ample. Genesis,
nonce stabilization parameters, suffix counts and same-epoch checks are unchanged.

Ordinary durable capacity is fixed at **8**, with no capacity CLI override. Actual
suffix lengths must satisfy 1 <= nA <= 2 and nA < nB <= 4. Scala A creates the
durable store and reaches A. Its final externally acknowledged receipt is copied
and forced independently. Distinct sequential Scala B resumes that receipt,
offers retained A points in actual order, intersects at C, acknowledges a nonempty
rollback with the full restored projection, then applies B. B's greater target
prevents immediate success at the loaded A depth.

This demonstrates following a peer-selected replacement branch with checked durable
undo, **not independent chain selection**. Reference mempool acceptance is separate
from Scala's selected checked ledger validity. It does not claim invalid-block
rejection or power-loss durability. Whole-state reference queries use separate
acquisitions bracketed by stable keyless tips, not an atomic snapshot.

## Evidence and bounds

`ForkAuditCommand` replays C -> A -> C -> B from original headers/bodies and compares
fresh C -> B plus both complete reference endpoint oracles. It checks exact marker
transaction membership and witness, same-C-input conflict, removed A outputs,
restored C input, complete B effects and fee pots. The Python auditor separately
requires actual `offeredPoints`, `selectedPoint`/`offeredMatch`, and post-acknowledgement
`node-rollback.projection`; any `projectionOmitted` marker rejects. Every bootstrap,
loaded, applied, rollback and final receipt is read from its exact known mount,
hash-verified, and checked for one store and the full generation chain. No receipt
is reconstructed from the checkpoint or selected by scanning it.

Reference container: 2 CPUs / 2 GiB, at most two Haskell processes, one RTS capability
and 512 MiB heap each. Scala/auditor containers: 1 CPU / 1 GiB, sequential. Network
is internal; Scala shares only the owned reference namespace; audit uses no network.
Case limit 240 seconds, each Scala phase 45 seconds, cleanup 30 seconds. Node
startup retains a 10-second PID/executable allowance. Socket readiness gets 10
seconds after the later of launch time and the recorded genesis `systemStart`,
converted once from UTC to a monotonic deadline and clipped to the unchanged case
deadline. A future genesis at or beyond that case deadline rejects before launch.
The recorded readiness budget explains any startup timeout; individual readiness
queries are capped to the remaining allowance. This does not alter epoch guards.
Image root is read-only; all private state lives in fresh tmpfs/evidence directories.
The reviewed image-installed pidfd helper must match an explicit digest. Signals
bind to the opened process handle and start ticks, never a numeric-PID fallback.
Owned labels resolve uncertain creates; cleanup rechecks labels and removes only
immutable IDs, then verifies their absence. Reference stops currently require exit
zero; manual producer restart behavior and timing remain first-live-run checks.

## Integration and commands

Integrate the seven new files; main's intersection/rollback hooks must already be
present. No edits to `Main.scala`, `NodeCommand.scala` or `private_cluster.py` are
included here. Run the integrated compile and create a new reviewed pin containing
the controller, Python auditor, ForkAuditCommand and NodeCommand source hashes,
compiled hashes, commit, classpath and pinned JDK image. Old baseline pins cannot
authorize new integrated classes.

```sh
python3 -m unittest discover -s scripts -p 'test_private_cluster_fork*.py' -v
# Focused Scala build uses the existing offline Docker workflow:
# app/testOnly lab.ForkAuditSuite ; scalafmtCheckAll ; app/runtimeClasspathFile

# Only after main's explicit live grant; placeholders must be resolved from review:
python3 scripts/private_cluster_fork.py \
  --reference-image <reviewed-local-image-with-pidfd-helper> \
  --pidfd-helper-sha256 <reviewed-helper-sha256> \
  --scala-repo <clean-integrated-build-checkout> \
  --source-pin <fresh-reviewed-source-classpath-pin.json> \
  --source-pin-sha256 <exact-pin-sha256> \
  --fixture-profile conway-pv9-empty-byron-allocations-coherent-v1 \
  --output /home/<user>/cardano-competing-branch-live1-20261009 --execute
```

The proposed evidence destination must still be absent. A failure retains its
exact bounded evidence and cleanup result and does not retry with wider limits.
Offline scripted-process tests exercise output/timeout termination, PID identity,
keyless roles, UTC boundary guards, branch bounds, fixed CLI, immutable receipt
mapping and owned cleanup. They are not substitutes for the real fork acceptance.


## Observed local acceptance (2026-10-09)

The fourth fresh attempt passed on source `5d7e88dbd3784832de00043bebdbd37c842e18d8`
in 153.796 seconds, including the final live genesis and source/classpath checks.
Common C was block 48, slot 1028, epoch 1. A contained one block, ending at slot
1123; B contained two blocks, ending at slot 1207. Their distinct transactions
spent the same C input. Reference endpoint projections and original block bytes
matched the separate checked Scala replay.

Process A acknowledged generation 1 and exited. Distinct process B loaded that
exact state, selected C from the actual offered points, acknowledged the nonempty
undo at revision/generation 2/2, and applied B through revision/generation 4/4.
Every immutable receipt was verified across generations 0 through 4. Each phase
also received one peer-announced no-op rollback to C before forward acquisition;
the auditor checks its complete unchanged row, projection and receipt instead of
discarding it. Both node processes and the separate Scala auditor exited cleanly.
All four owned containers and the internal network were removed and verified
absent, with no cleanup or evidence-collection errors.

The first three failed attempts remain preserved: initial future-genesis readiness,
an overly narrow anchor-slot guard, and the auditor's single-rollback assumption.
The third attempt's corrected offline reanalysis is separate from its failed live
result; it did not retroactively complete skipped final live checks.

This demonstrates following a peer-selected competing branch with bounded durable
undo under graceful process handoff. It does not demonstrate independent chain
selection, power-loss recovery, epoch transitions, full ledger/consensus validity,
or sustained-durable v2 compaction. Reference queries remain separate acquisitions
under observed quiescence. Raw evidence, keys, stores and cluster data stay outside
Git. Further runs require a fresh reviewed pin and an explicit bounded live grant.
