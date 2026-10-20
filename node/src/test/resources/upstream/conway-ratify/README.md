# Pinned Conway RATIFY judgments

These are original inputs and observations from cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, with the formal and generator pins,
commands, hashes and observer patch stack in [manifest.json](manifest.json).
Apply the listed patches in order to that exact source and build the
`cardano-ledger-conformance:test:tests` target in the pinned reference build environment.
The patch stack is [exporter.patch](../../../../../../scripts/oracle/ledger-govcert/exporter.patch),
[native-hooks.patch](../../../../../../scripts/oracle/ledger-govcert/native-hooks.patch), then
[ratify.patch](../../../../../../scripts/oracle/ledger-govcert/ratify.patch).
Run the recorded `runnerArgs` with `SCARAB_CONFORMANCE_EXPORT_DIR` (STS) or
`SCARAB_RATIFY_ACCEPTED_EXPORT_DIR` (approval predicates) pointing at a private output directory.
The observer does not replace the rule or its native/formal comparison.
The complete reference executable also contains the independently named epoch and
payment observers. `referenceRunner.orderedBuildPatchStack` records that full build
stack and the resulting source hashes; apply it for exact executable-source reproduction.

Four explicitly size-five STS samples retain the complete initial RatifyState,
eight-field environment, original ordered action signal and native post-state.
The original native/Agda comparison passes for all four. Three nonempty samples
start delayed; the remaining signal is empty. They exercise state retention,
expiry and the empty base case, not positive enactment or order discrimination.
Local RatifySuite regressions separately check positive order sensitivity and
retention of prior enacted/expired results.

Six explicitly size-five approval-property samples retain the original independent
native and formal Boolean vectors. The DRep and SPO samples are all false;
committee samples include both outcomes. The Scala consumer compares each action
against the matching production voting predicate, independently of the STS result.
These ten cases do not establish complete property-family or branch coverage.

These files are upstream trace CBOR, not Scarab snapshot payloads; they do not
introduce an older native persisted-state format.
