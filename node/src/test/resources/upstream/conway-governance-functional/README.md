# Lawful nonempty Conway governance judgments

These are native Haskell inputs and independently observed outcomes from
`cardano-ledger` `f649f9751074d2ab3de033fc3912f29c9862c1f5`, with the formal
and generator pins, exact runner arguments, file hashes, executable hash and
ordered build patch stack in [manifest.json](manifest.json). Apply that stack
in order, including [governance-functional.patch](../../../../../../scripts/oracle/ledger-govcert/governance-functional.patch),
then build `cardano-ledger-conformance:test:tests` in the pinned offline oracle
image. The preceding observer patches are shared with the existing
[RATIFY corpus](../conway-ratify/README.md).

Fifteen `GOV-sized5` invocations retain the original constrained generator's lawful
nonempty votes, proposals and certificates, full six-field environment,
EnactState context, complete input proposal state and native output. Only the
explicit generator size changes. Each ordinary native/formal comparison passes. Scarab executes all fifteen original
inputs through the shared production `GovKernel`, comparing the complete `Proposals`
output: roots, ordered actions, votes and expiration data. The kernel requires only
account, DRep and pool membership and committee authorisations; full ledger execution
supplies these through `GovCertificateView.from` over validated certificate state,
without copying registration maps. The fixture adapter decodes exactly those typed
keys and authorisations from the original environment, without altering inputs or
expected outputs.

Five environments (seeds 510102, 510103, 510109, 510113 and 510114) encode a zero
VRF registration count in `PState.vrfKeyHashes`, whose declared native type is
`NonZero Word64` (`Cardano/Ledger/State/CertState.hs:233`). The native CBOR reader
also refuses zero (`BaseTypes/NonZero.hs:102–107, 129–131`). GOV never reads these
counts, so this unrelated certificate transport constraint no longer excludes
its original judgments. Separate regressions retain the full typed certificate
decoder's `NonZero` refusal and exact path. No invalid native certificate state is
constructed, and the kernel's entire proposal output is compared, not projected.
The ten environments representable as full certificate state additionally compare
the strict production facade against the kernel. Missing DRep/committee registrations
and the native deposit mismatch remain negative controls.

The generated signals test nonempty voter/proposal handling and voter existence,
including empty inner vote maps. The separately named lawful cast-vote integration
below covers actual vote insertion.
Scarab decodes the original voting procedures and certificates and compares the
complete resulting proposal roots, ordered action records, votes and expiry data
through its production GOV rule.

`GOV-invalid-deposit-sized5` uses the same seed as one accepted sample and
increases only the required protocol-parameter deposit by one lovelace. Both
native and formal rules reject it. Scarab must retain the structured
`ProposalDepositIncorrect` cause with the supplied and required amounts. The
native predicate-failure CBOR is retained and decoded as the exact typed mismatch;
Scarab must return the same failure and amounts. The ordinary native/formal
comparison is preserved; the GOV-specific observer separately evaluates the same
input to capture its native failure bytes.

`gov-drep-cast-vote` registers a DRep through the lawful upstream integration
setup, creates a no-confidence proposal and submits an actual yes-vote transaction.
The observer retains that transaction's returned ID, the original pre-proposals,
all six GOV environment fields, EnactState context and its exact voter/action/vote
signal. Native and formal GOV outputs compare equal, and native GOV's full output
also equals the actual integration ledger's post-proposals, separately retained as
`observed-ledger-proposals.cbor`. Scarab must add the same DRep yes vote and match
the complete proposal state. The observer does not mutate serialized fixtures.
Apply [governance-cast-vote.patch](../../../../../../scripts/oracle/ledger-govcert/governance-cast-vote.patch)
after the preceding stack and use `SCARAB_GOV_VOTE_EXPORT_DIR` for this case.
All retained cases were re-executed against the final recorded executable.

Five lawful integration RATIFY cases retain all eight environment fields,
complete RatifyState, original ordered actions, native DRep/SPO approval booleans
and full native post-state. Native and formal complete outcomes compare equal:

- The original upstream `NoConfidence accepted conforms` test, with its original
  DRep threshold of 9/10 and SPO threshold of zero, is unchanged apart from observation.
- A second case registers and matures a stake pool, uses nonzero 1/2 thresholds
  for both DReps and SPOs, and submits both yes votes before ratification.
- Two cases submit competing, individually approved no-confidence actions sharing
  the same parent. Identical seed and setup preserve the same actions and initial
  state; reversing the supplied order changes which action is enacted.
- A matching setup omits both votes. RATIFY accepts the transition but does not
  enact the action, preserving the distinction from a rejected GOV signal.

Use `SCARAB_CONFORMANCE_EXPORT_DIR` for GOV and
`SCARAB_GOVERNANCE_EXPORT_DIR` for RATIFY, pointing at an existing private output
directory, with each case's recorded `runnerArgs`. Observers do not replace the
native rule, formal rule or the ordinary conformance verdict. Native approval
booleans are additional observations; formal approval-predicate vectors are not
separately exported by these integration tests.

This is bounded Conway/PV9 generated GOV and PV11 integration GOV/RATIFY coverage, not all
seeds, action kinds or governance properties. It does not require the production
pulser to adopt a particular internal scheduler. The raw CBOR is an upstream
trace, not a Scarab persisted snapshot, and no native snapshot format changes.
