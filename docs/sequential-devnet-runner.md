# Sequential private devnet scenarios

`SequentialDevnetRunner` is Test-only Cats Effect orchestration. A caller provides
one `Resource[IO, Adapter]` for one mutable, already-owned private devnet and a
vector of typed scenarios. Actions execute serially; successful actions are
followed by bounded observational checkpoints. Missing prerequisites yield
`Blocked`, never a pass. An action failure, evidence cap or scenario cap stops
the sequence. `allRequestedPassed` is false if any requested scenario is blocked
or the sequence is incomplete.

The concrete `PlutusServiceScenarioAdapter.borrowed` runs inside the existing
helper JVM. It borrows one loopback service, observes `/v1/state`, binds the fixed
profile and first observed owner identity, and invokes the
existing two-transfer HTTP client in process. The client checks first inclusion
before second submission, same owner, API availability and original identities.
Before completing the transfer checkpoint, accepted/included receipt pins and
client state responses must match that borrowed owner, even without a later
observation scenario. Its exchange must contain the independently prepared three signed fixture files.
The two-transfer action is single-use per exchange: evidence is published without
overwrite and repeated execution fails. The adapter cannot create clusters or
submit transactions to a reference node. The external supervisor retains cluster
ownership and cleanup; a borrowed lease does not imply cluster ownership.

```scala
SequentialDevnetRunner.run(
  Vector(Scenario.ObserveService, Scenario.TwoSequentialTransfers,
    Scenario.ObserveService, Scenario.RestartAndRejoin, Scenario.FollowAcrossEpochs),
  Limits(8, 25.seconds, 30.seconds, 65536, 262144),
  PlutusServiceScenarioAdapter.borrowed(port, exchange, 24)
)
```

This example fits inside the current bounded same-epoch service, subject to its
existing early-bootstrap and epoch margin checks. It reports restart and repeated
epochs as blocked. Multiple ingress nodes are also blocked. A later-epoch join
is not enabled. Durable restart needs a complete authenticated coherent state
image and validated fresh-owner restoration. Repeated epochs need checked epoch
transitions, governance completion, exact protocol arithmetic and Plutus
environment rebinding. Reference-only epoch progress proves none of these.

The limits constructor caps scenario count at 128, each action at 60 seconds,
the full resource-use deadline at ten minutes, checkpoint size at 64 KiB and
aggregate checkpoint bytes at 8 MiB. Adapters must bound reads before allocation;
the runner checks returned sizes as a second guard. Cancellation joins the active
effect and finalizes the lease. Deadlines are cooperative: blocking I/O and
resource finalizers may delay return. No thread interruption or forced arbitrary
container cleanup is claimed. Returned checkpoint hashes are observations only,
not durable storage, restore capabilities or mutation authority. Failure and
cancellation can follow a submitted transaction; no rollback is implied.

This runner does not itself enforce Docker disk/log limits or persist its report.
Those remain required supervisor responsibilities before a longer live run.
No live soak or multi-epoch success is established by the offline sequencing tests.


`lab.PlutusScenarioRunnerMain PORT DURATION_SECONDS EXCHANGE` is a Test-only
drop-in client entrypoint for the existing owned controller (3–60 seconds). It
runs observation, the existing two-transfer scenario, then observation in the
same JVM. It preserves `submission/service-client-result.json` and publishes a
separate bounded `submission/scenario-runner-result.json` without overwrite.
The CLI also requests restart/rejoin, repeated epochs and multiple-node ingress,
which are explicitly blocked. Its exit code reports `executedScenariosPassed`:
the three supported scenarios completed, no failure occurred and the plan was
exhausted. `allRequestedPassed` remains false because coverage is incomplete.
An all-blocked run cannot pass executed acceptance. Consumers must inspect both
fields; exit zero does not mean all requested coverage passed. The CLI does not launch a devnet or extend
the service's deadline. Report publication uses a same-filesystem hard link;
there is no file/directory fsync or crash-durability claim.


| Requested scenario | Current result or prerequisite |
| --- | --- |
| Join existing reference network | Supervisor's checked early epoch-zero bootstrap; runner borrows the resulting service |
| Two transactions on one running service | Existing HTTP client, first inclusion before second submission |
| Observe before/after transfers | Fixed profile, volatile scope and stable owner identity |
| Down/restart/rejoin | Blocked: complete authenticated coherent state and fresh-owner restoration |
| Scala operation across epochs | Blocked: checked transition and environment prerequisites |
| Transactions to multiple nodes | Blocked: this adapter has one Scala ingress endpoint |

The bounded live profile retains one isolated instance,
30-second Scala service and client deadline, supervisor's existing 240-second
outer hard alarm, at most eight scenarios, 64 KiB per checkpoint and 64 KiB
aggregate checkpoint evidence. Keep existing service 128-block/128-receipt
limits, epoch margin, source bindings and supervisor disk/log watchdogs. Reference
uses 2 CPU/3 GiB, Scala service 1 CPU/2 GiB and this in-process client 1 CPU/2 GiB;
total at most 4 CPU/7 GiB. The ten-minute constructor ceiling is only a defensive
maximum, not a proposed long run or evidence of supported cross-epoch operation.


The concrete adapter retains exact successful observation bytes at
`submission/scenario-checkpoint-0.json`, `-1.json`, etc. Indices count actual
adapter observations, not blocked scenarios. For the CLI's fixed six-row plan,
indices 0 and 2 are state responses; index 1 equals the original client result.
Each file and the aggregate original byte count are bounded to 64 KiB. Publication
preserves a hard-linked `.part` name without copying its data. An external
controller can independently verify every report hash and the owner identity in
both state responses and the client receipts. These remain observational files;
failed/cancelled attempts can leave partial evidence and never imply restoration.


Failed HTTP-client actions retain a closed diagnostic category and operation in
the failed verdict: `reason=ClientFailure`, with `diagnostic.operation=HttpClient`
and `diagnostic.cause` such as `ConnectionFailure`, `Deadline`, or
`InvalidObservation`. Unknown failure types become `UnknownClientFailure` without
copying arbitrary error text; unreadable receipts become `EvidenceUnavailable`.
These categories describe failures only and never alter acceptance. Original
bounded client observations, including stages and HTTP response codes, remain in
the private client receipt; classification does not replace that evidence.
Cancellation remains cancellation and cannot produce a completed checkpoint.

When available, the diagnostic also includes the last retained observation's
closed stage and response-code categories (for example `WaitFirstInclusion` and
`Pending`). This describes the last received response, not a proven cause of
the later connection failure. Acceptance followed by pending responses is never
reported as inclusion. Unknown stage/code values are sanitized to `Unknown`.
Scenario, requirement, stop and failure wire names use explicit closed mappings.


## Bounded sequential acceptance — 2026-10-10

Exact source `0424dd7cc6194ca01581884ab8222632c5e9af28` passed the second isolated attempt. The
[curated receipt](../reference/plutus-admission/sequential-live-receipt.json)
binds the original controller, client, three checkpoints and endpoint observations.
The runner completed observation → two sequential transfers → observation on one
service owner. The other three requested scenarios were **blocked**, so
`executedScenariosPassed=true` and `allRequestedPassed=false` are intentionally
different. This is a smoke test, not a multi-epoch soak.

Bootstrap was slot 182. Transaction acceptance/inclusion
were 395/426 and 426/481. The service continued to
slot 624/block 14, with
11 publications and a `durationLimit` stop. Recorded
active operation was **26.093 seconds** within the configured 30-second
lifetime, which also budgets startup/readiness. All 15
transports closed and owned-container cleanup was verified.

The exact historical endpoint matched the complete supported 10-entry
UTxO, preserved collateral, instantaneous stake and represented protocol fields;
fees increased by 600,000 lovelace. Checkpoint hashes and observation order were
checked against the original client bytes and one owner. These checkpoints grant
no restore authority. Separate queries do not establish an atomic cross-query
snapshot or full ledger/consensus validation.

Final verification passed **1,765 Scala/translator tests, 27 public gates, 486
Python tests run with two skips**, and **20 separate retained-data regressions**.
The receipt records exact log hashes; retained captures remain private.

The first attempt at source `8318eb4` is preserved. Its first transaction was
accepted and remained Pending; no successor block was published. The reference
producer reported NotLeader for slots 356–467, then NoLedgerView for 468–657,
after exceeding frozen tip 167's 300-slot forecast horizon. The service stopped
after 27.062 active seconds and all nine transports closed. The client then saw
a connection failure. No inclusion or endpoint agreement was established.
The retry added typed failure context and bounded private helper-output retention;
it did not extend duration or relax acceptance. One success does not establish
timing reliability.

## Ten-minute target and prerequisites

The proposed next long run is **600 seconds of active operation on one mutable
private devnet**, with a proposed 720-second supervisor budget plus 30-second
owned cleanup. Keep the aggregate 4 CPU/7 GiB profile: reference nodes share
2 CPU/3 GiB, one Scala service uses 1 CPU/2 GiB, and the serial client/helper uses
1 CPU/2 GiB. Use at most eight planned scenarios, at most 64 KiB per checkpoint
and 512 KiB aggregate checkpoint evidence; retain the monitored 1 GiB private
directory ceiling and 60 GiB free-space reserve. The 250ms disk scan can
overshoot; it is not a filesystem quota. Existing bounded container logging
must remain enabled. No such longer run has started.

This needs an explicitly reviewed longer-run mode (proposed 512-block bound),
not silent changes to today's 60-second/128-block service limits. At 100ms slots
and 1,000 slots per epoch, 600 seconds spans about six epochs. The required
scenario sequence is checked join, submission through the configured endpoints,
checkpoint, Scala stop/restore/rejoin, another submission, and checked progress
across subsequent epochs. Reference-only progress cannot satisfy the Scala steps.

| Gate | Current state / next concrete work |
| --- | --- |
| Sequential execution and honest diagnostics | Tested and observed in the bounded smoke test |
| Ledger/stake image components | Reviewed [ledger image](restricted-validator-storage-v1.md) and [stake image](restricted-stake-image.md) preserve bounded data; decoding remains untrusted |
| Complete restart/rejoin | Capture one coherent cell; authenticate ledger/image/full-point correspondence; restore certificates, nonce/eligibility/source authority and replay into fresh owners; reject old capabilities. Runtime refusal remains |
| Repeated epochs | Fresh governance completion and exact nonempty-go likelihood arithmetic, correct reward/freeze ownership, Plutus environment rebinding and atomic successor publication remain prerequisites |
| Empty governance prerequisite | A narrowly source-bound empty DRep completion is the next independent transition component; it must not erase registered pool domains or reuse stale completion |
| Multiple node submissions | Add an owned endpoint adapter and independent original-byte inclusion checks per endpoint; current adapter has one Scala API |
| Longer operational limits | Review duration, publication/evidence caps and cancellation under the proposed profile only after semantic gates pass |

The image components reduce the persistence gap; they do not supply a complete
restore path or enable the ten-minute validator run. No checkpoint or epoch guard
has been removed.
