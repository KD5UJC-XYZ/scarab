# Controlled submission scenarios

`scripts/private_cluster_scenarios.py` subclasses the committed transfer harness.
It uses the same disposable private Conway PV9 cluster, selected input, reference
CLI, paused-producer snapshots and original-byte Scala transfer comparison. It does
not change `private_cluster.py`, `private_cluster_transfer.py` or `Main.scala`.

The first packet exercises:

1. Sign the exact transfer body with disposable payment key 2 instead of source
   key 1, submit to the relay, require nonzero CLI status with
   `MissingVKeyWitnessesUTXOW`, and check unchanged whole UTxO and actual fee pot.
   This is missing required-key coverage, not necessarily a bad Ed25519 signature.
2. Run the existing valid transfer, including Scala capture/inclusion, witness,
   selected predicate, whole-UTxO and actual fee-pot comparison. The inherited
   producer fallback remains enabled; this is not the relay-only scenario.
3. Submit the identical transaction after confirmed inclusion and require
   `BadInputsUTxO`. Existing outputs must remain present and state unchanged.
   No assumption is made about duplicate admission while a transaction is pending.
4. Build a different transaction spending the already consumed input, increasing
   its fee by one lovelace and decreasing change by one. Require `BadInputsUTxO`,
   a distinct transaction ID, absent outputs and unchanged observed state.

Each CLI submission preserves its argument vector, exact return code, separate
stdout/stderr and monotonic timing in `scenario-submission-N.md`. Exceptions are
recorded separately and never interpreted as ledger rejection. Negative transaction
CBOR and bracketed state query originals are retained beside the existing positive
transfer evidence. Snapshot receipts remain explicitly non-atomic. The wrong-key
transaction has the same body ID as the later valid transfer: its non-inclusion
claim applies only to the paused window before valid submission. Repeated included
transactions correctly retain their pre-existing outputs. No bounded observation
establishes permanent future non-inclusion.

The rejection classifier deliberately fails if the expected constructor is absent,
including socket failures and unexpected success. A reference local submission
rejection is not invalid-block rejection or a full ledger validity result. The
negative Scala comparison is currently **unsupported by this adapter**; existing
Scala predicates are not thereby claimed unimplemented. Positive Scala evidence is
provided by the inherited transfer path. No new Scala code or `unsafeRunSync` is used.

A separate follow-up [offline Scala adapter](cluster-negative-observation.md) can
compare the saved negative evidence with selected Scala rejection classes. It does
not change this Python runner's receipt or trigger a live workload; its optional
real-evidence integration test remains pending.

## Verification and integration

The public Python discovery command automatically includes the new tests:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_private_cluster*.py'
```

The scripted tests exercise production submission/snapshot guards and the actual
`Runner.run` cleanup path with scripted process responses. Cancellation followed
by restart means a **fresh harness run**, with new UUID resources and separate
evidence, after verified cleanup. It does not mean restart of a reference node or
recovery of a persisted ledger database. Tests also inject cancellation during the
post-transfer scenario and verify producer continuation in `finally`.

No live scenario has been run for this packet. Expected reference rejection
constructors, post-inclusion repeat behavior, runtime budget and quiescence windows
still require a granted live resource slot and direct user approval where required.
The inherited live profile uses 3 CPUs / 6 GiB for the reference container and
1 CPU / 1 GiB for Scala, fitting the coordinator's known 4 CPU / 7 GiB allocation.
The 2 CPU / 2 GiB limit applies to isolated offline tests, not a reduced three-node
live profile. Resource failure must never be classified as transaction rejection.
Existing preflight host-capacity checks remain unchanged.

After the coordinator grants a live slot and prepares the pinned reference image
and separate read-only Scala build, the opt-in entry point is:

```sh
python3 scripts/private_cluster_scenarios.py \
  --reference-image "$REFERENCE_IMAGE" \
  --scala-repo "$ISOLATED_SCALA_BUILD" \
  --output "$NEW_EVIDENCE_DIRECTORY_OUTSIDE_GIT" --seconds 360
```

All inherited local Docker endpoint, internal network, image verification and
cleanup checks remain active. Keys stay in the ephemeral container; logs, original
transactions and state evidence remain outside Git and must not be published as
raw private evidence. No host installations, shared cache mutation, production
keys, public network traffic, or push is needed. Integration consists only of the
three new files; no shared-file patch is necessary.
