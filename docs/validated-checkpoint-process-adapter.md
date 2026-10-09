# Retained-file validated restart adapter

`lab.ValidatedRestartCapture` and `scripts/validated_checkpoint_restart.py` are a
standalone acceptance adapter/controller around the existing durable coordinator.
They do not change production transitions or dispatch through `Main`. This packet
has only offline compilation, unit tests and scripted process tests. No graceful
or SIGKILL acceptance subprocess has been launched for this packet. Both runs
remain separately gated by the coordinating task's resource grant after main
integration and an exact compiled-artifact pin.

## Checks

Use the existing retained empty-plus-two-transaction capture and all seven
original pre-context files plus their manifest. The controller independently hashes
these nine files, derives the context ID, and derives ordered original header and
block commitments. It records the clean Git commit, pinned JDK image and hashes of
the resolved runtime classpath files. Pins are checked between and after phases.
The controller also decodes the retained original block bodies to derive the
transaction count, summed fees and output-minus-input entry count for each prefix.
Every A application and B reapplication must increase the fee pot by exactly that
sum, decrease total UTxO ADA by the same amount, and change entry count by the
derived delta. Transaction-bearing prefixes must change the whole UTxO bytes;
empty prefixes must preserve them. The Scala adapter performs equivalent checks
from the reconstructed original transaction memos. Unchanged transaction effects
are a failing regression case, including in the scripted successful-process model.

A first proves create/close/strict-resume on a disposable `probe` subdirectory of
the same checkpoint mount. This is a filesystem compatibility probe, not evidence
of power-loss safety. A creates `state`, emits its anchor and every applied prefix,
and holds after the last acknowledged publication. Each projection contains the
tuple/context, anchor/tips, exact original-byte commitments, complete certificate
counters and nonce fields, eligibility values, and whole UTxO/fees/slot plus ledger
IDs. Revision, exact token and capacity are carried outside the content projection.

The controller checks each checkpoint payload/footer digest against the typed
acknowledgement, then atomically writes and forces a receipt outside the child
mount before sending the matching sequence/digest confirmation. This handshake
also applies to every B publication. Receipt failure prevents confirmation and
therefore prevents advancing to the next operation. Pending recorder callbacks
are not reported as successful publication evidence.

For graceful A, the controller releases the hold only after retaining the final
receipt. For kill A, it verifies the same immutable container ID and running host
PID, targets that CID with SIGKILL, and requires actual stopped state, exit 137,
no OOM/error and unchanged checkpoint bytes. A hold expiry fails; it cannot produce
a graceful success. B can start only after verified A termination has returned.
Across phases the controller explicitly rejects reused container IDs or adapter
nonces. Identity receipts retain CID, nonce, namespace PID, host PID and Docker
StartedAt; termination receipts also retain FinishedAt. Holding/termination must
match the complete running identity, and B StartedAt must strictly follow A
FinishedAt, preserving Docker's nanosecond precision. Namespace/host PID reuse by
itself is allowed only with distinct CID/nonce/start identity. The same timestamp
ordering applies between the completed probe and A.

B strictly resumes with A's externally retained token and requires complete exact
state equality, including revision and generation. It rolls back two blocks,
closes/reopens, reapplies both, rolls back to the supplied anchor, and finally
closes/reopens that anchor checkpoint. Rollback/reapply compare content with A's
matching prefix; revision and generation are checked separately. Each application
adds one to each; rollback adds removed-block count to revision and one to generation.
The two reopened B states also require exact equality with their preceding receipts.

## Bounds and execution

The controller requires Linux, an already available pinned Docker image, compiled
classes and resolved classpath; it installs and downloads nothing. Containers use
`--network none`, one CPU, 1 GiB memory, a read-only root/repository/input, a bounded
temporary filesystem, dropped capabilities and only the owned checkpoint mount
writable. Phases run sequentially. The phase budget is 45 seconds, overall case
budget 120 seconds, with a separate bounded cleanup budget. Output is limited to
4 MiB per line and 64 MiB combined stdout/stderr. Checkpoint/input reads reject
symlinks and nonregular files and allocate only up to their explicit bounds.

Unique ownership labels are registered before Docker creation. After uncertain
create results, cleanup discovers only the matching label, validates immutable
identity/ownership, removes by CID, and verifies absence. Cleanup errors make the
case fail. Keys, raw logs, state and receipts stay outside Git. These files must not
be published as public evidence.

After integration, explicit parent approval and the offline build/classpath pin,
the entry point is:

```sh
python3 scripts/validated_checkpoint_restart.py \
  --repo /absolute/clean/owned/worktree \
  --input /absolute/retained-sequence-input \
  --output /absolute/new-private-evidence-directory \
  --mode graceful --execute
```

The output directory must be new and outside the repository/input. The separately
approved kill run uses `--mode kill` and another new output directory. `--execute`
is an explicit execution guard, not a substitute for the parent resource grant.
The controller never opens a reference node or resumes network activity. It does
not establish invalid-block rejection, fresh chain acceptance, rollback freshness
against an external high-water service, crash-during-write recovery or power-loss
durability. It tests recovery of these exact retained originals and supported
certificate/nonce/eligibility/ledger checks from the supplied context.

## Offline verification

```sh
python3 -m unittest discover -s scripts -p test_validated_checkpoint_restart.py -v
# In the task-owned offline 2 CPU / 2 GiB JDK container:
# scalafmtAll scalafmtCheckAll app/test app/runtimeClasspathFile
```

The Python suite uses scripted processes; it launches no Docker containers.
It covers strict schema/sequencing, exact restored state, rollback arithmetic,
receipt force/write failures, immutable kill targets and actual exit checks,
postkill checkpoint mutation, A-before-B ordering, output bounds, uncertain-create
cleanup, wrong ownership labels and nonregular/oversized files. Effect regressions
reject unchanged UTxO/fees, incorrect fee/ADA/entry deltas and mutation in an empty
prefix. Identity regressions reject repeated IDs/nonces, missing A termination,
overlapping timestamps and a reused PID with changed start identity. Scala tests cover
canonical projection encoding, strict expected tokens, exact confirmations and
failing controller timeouts, plus real retained prefix fee/UTxO deltas and failing
unchanged-effects assertions. Existing durable tests cover retained real sequence
replay and rollback; no new live acceptance claim follows from these unit tests.
