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
   `ConwayMempoolFailure` with the exact all-inputs-spent message. Existing outputs must remain present and state unchanged.
   No assumption is made about duplicate admission while a transaction is pending.
4. Build a different transaction spending the already consumed input, increasing
   its fee by one lovelace and decreasing change by one. Require the same exact all-inputs-spent `ConwayMempoolFailure`,
   a distinct transaction ID, absent outputs and unchanged observed state. Both
   post-inclusion failures are expected mempool prechecks, not claims of observed
   LEDGER rule failures.

Each CLI submission preserves its argument vector, exact return code, separate
stdout/stderr and monotonic timing in `scenario-submission-N.md`. Exceptions are
recorded separately and never interpreted as ledger rejection. Negative transaction
CBOR and bracketed state query originals are retained beside the existing positive
transfer evidence. Snapshot receipts remain explicitly non-atomic. The wrong-key
transaction has the same body ID as the later valid transfer: its non-inclusion
claim applies only to the paused window before valid submission. Repeated included
transactions correctly retain their pre-existing outputs. No bounded observation
establishes permanent future non-inclusion.

Complete submitted CBOR SHA-256 is recorded in both submission and scenario receipts;
the file is read immediately before submission and checked unchanged afterward.
This binds witnesses as well as the body. The post-transfer pause remains held
continuously through both post-inclusion negatives, with one outer `finally` releasing
the hold. The initial pause is still released for valid-transaction inclusion.

The rejection classifier deliberately fails if the expected constructor is absent,
including socket failures and unexpected success. Receipts label the reference rejection layer explicitly. The all-inputs-spent
mempool cases set `recognizedLedgerRejection` false even when the recognized
reference submission rejection passes. A reference local submission rejection is
not invalid-block rejection or a full ledger validity result. The
negative Scala comparison is currently **unsupported by this adapter**; existing
Scala predicates are not thereby claimed unimplemented. Positive Scala evidence is
provided by the inherited transfer path. The Python runner does not invoke the separate negative Scala adapter; no `unsafeRunSync` is used.

A separate follow-up [offline Scala adapter](cluster-negative-observation.md) can
compare the saved negative evidence with selected Scala rejection classes. It does
not change this Python runner's receipt or trigger a live workload; its optional
real-evidence integration result is recorded below.

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

The first authorized local run passed the wrong-key and positive-transfer steps,
then failed closed because the repeated-included transaction returned the
all-inputs-spent mempool precheck instead of the originally expected `BadInputsUTxO`.
The conflict step did not run. Its 59.01-second failure and verified complete cleanup
remain preserved outside Git at `/home/<user>/cardano-scenarios-live1-20261008`.
The adapter now requires that exact mempool constructor/message, independently
supported by the source below. This correction itself is not successful live evidence.
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
keys, public network traffic, or push is needed. The reviewed scenario chain adds seven scenario-specific files. Integration with
the minimum-output context loader adds `utxoCostPerByte` to the synthetic evidence
parameters; source hashes are computed from those complete parameters. The
existing public CI discovery includes the scenario guards without a workflow change.


## Reference mempool distinction

The node-version-matched Conway 1.23.0.0 package at CHaP revision
`6eb65fb4b6efe04ff586ad807ae022a36523e6af` checks whether any spending input exists
before invoking LEDGER. If none exists, it returns `ConwayMempoolFailure` with
the message `All inputs are spent. Transaction has probably already been included`.
See [the pinned mempool implementation](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Mempool.hs).
Package archive SHA-256 is `486831d3d94060fff90e5e85c74030c744008526aa83f4a18215da124720cf1a`;
the package's `Mempool.hs` SHA-256 is
`3369c119d6635dcfffcb6e4a042a8871a6f47cca74ef0cef80e5684e411b4dc8`.
This supports version-level provenance, not a complete reproducible binary build.

The repeated and conflicting transactions have the same already-consumed input;
the reference mempool precheck and Scala's selected `UnresolvedInputs` predicate
are recorded separately. The adapter does not implement general mempool semantics
or establish ledger-rule acceptance-set agreement. Another mempool error,
constructor-only text, a successful process, or split-stream fragments must fail
closed. Raw status and streams remain available in the private receipts.


The second run stopped before submission on an inherited startup-convergence
`KeyError: hash`: the relay could still report an origin tip after node 1 was ready.
Its 26.40-second failure and empty cleanup receipt remain preserved at
`/home/<user>/cardano-scenarios-live2-20261008`. The shared transfer guard now waits
for three well-formed matching hashes, retaining every sample and failing after
at most 40 attempts with 0.25-second spacing (individual queries also remain
deadline-bounded). Missing, malformed or divergent tips never count as convergence.
This is startup handling, not transaction rejection.


## Completed local packet, 2026-10-08

The third isolated run passed in **131.05 seconds**, within its 360-second workload
and 600-second overall budgets, and cleanup verified no owned containers or
networks remained. Evidence is private at `/home/<user>/cardano-scenarios-live3-20261008`.
Wrong-key returned `MissingVKeyWitnessesUTXOW`; repeated-included and the different-body
conflict both returned the exact all-inputs-spent `ConwayMempoolFailure`. All three
receipts passed the paused-state guards. Repeated outputs remained present; conflicting
outputs were absent. The original positive transfer passed inclusion-byte, witness,
minimum-output, whole-UTxO and fee-pot checks. Node observations progressed from
epoch 0 to epoch 2 under Conway ledger protocol 9.0. The inherited positive path
uses direct producer submission; this is not a relay-only propagation result.

The separate offline Scala adapter then checked the complete saved directory and
emitted three matching selected-predicate observations, with explicit reference
layers and `ledgerRuleAgreement: false`. **33 retained-data tests** passed (seven
negative-observation tests including the opt-in packet, plus 26 transfer regressions).
These counts are separate from the public 779-test profile. The initial retained
run passed 32/33 because one historical mutation assumed slot 127; the test now
derives the changed slot/epoch from checked input. A subsequent test-only field-name
compile error was corrected before the final 33/33 pass. All logs are preserved
under `/home/<user>/cardano-scenario-integration-20261008`.

Independent review approved the source corrections, shared startup guard and
test-only mutation fix. The live packet establishes the recorded local submission
observations and related selected Scala rejections, not ledger-rule parity,
invalid-block rejection, signature-rejection coverage, atomic snapshots or
retained-state node restart. Raw evidence and disposable key material are not
distributed in Git.
