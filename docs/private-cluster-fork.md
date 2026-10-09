# Competing-branch durable rollback candidate

This opt-in acceptance controller uses the ordinary `lab.Main node --mode bounded-durable`
runtime and the integrated audited intersection/rollback hooks. It is implemented and
offline-tested; it has **not passed a live fork acceptance**. Main owns integration,
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
This is a proposed longer-epoch fixture requiring integration review before launch.
Common C is taken early in epoch one. UTC clocks are bracketed with a one-second
margin, checked against genesis systemStart, and must leave 26 seconds before fork
setup, 18 before A and 9 before B. The forging loop deadline is three seconds before the
calculated boundary; this does not guarantee that the process has exited by then.
Graceful exit and same-epoch captured headers remain mandatory acceptance checks. This is a bounded attempt, not a guarantee of leader slots.
The keyless endpoints and later JVM phases can remain frozen across a wall-clock
epoch boundary; all accepted branch headers must belong to C's epoch.

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
