# Exact historical implementation-ID recovery: missing evidence

Status, 2026-10-09: blocked before codec implementation. The requested combination is
bounded import after compaction, exact historical identities, reconstruction from checked
content, and no arbitrary decoded-ID setter or retained process references. The current
export lacks required identity preimages. Encoding it canonically cannot recover them.
No DTO/import capability, codec, disk integration, or runtime admission is claimed here.

This blocks reconstruction of exact historical **implementation IDs** under the
requested contract, not durable recovery generally. These internal pulser and
coordinator identity chains are not established Cardano protocol commitments;
the counterexample is neither a protocol defect nor a monetary discrepancy.

The recommended future design is a controller-attested semantic anchor with fresh
runtime identities and a canonical content/provenance commitment. Preserve exact
old implementation IDs only when an explicit compatibility or audit requirement
demands them. Retained old attribution would be attested evidence, not independent
replay verification of discarded history. No persistence implementation is needed
for the first bounded ephemeral crossing; that path remains a separate priority.

## Executable counterexample

`ConwayRewardPulserSuite` now demonstrates two valid histories from the same checked
frozen input: pulse at slot 111 versus 112, then force at slot 201. Both end with the same
frozen/allocation identities, security parameter, traversal, pulse size, cursor, member
rewards, phase, completion identity/rewards, slot and revision. Their pulser IDs differ.
An identical next signal at slot 202 preserves that distinction. Replaying the original
slot transcript reproduces the correct ID.

This is intentional identity chaining, not a hash collision or a monetary discrepancy.
`ConwayRewardPulser.scala:92,107` hashes the previous ID and signal slot; its State
(`:15–23`) does not retain previous signals. Opaque reownership (`:148–213`) copies the
historical ID from a checked object. It cannot justify an ID decoded from untrusted bytes.
Compaction can remove the blocks supplying those signals, while Complete state continues
to accumulate signals. A fixed eight-block suffix cannot reconstruct arbitrary history.

## Additional missing provenance

Source references are relative to this checkout (line numbers at audit time):

| Component | Missing derivation evidence |
| --- | --- |
| `ledger/src/main/scala/lab/ledger/ConwayStake.scala:47,154` | Context identity uses sourceDigest, which is not a Context field. |
| `ConwayStake.scala:317,372,452,493–503` | Seed source digest and predecessor/header identity chain are absent; undo preserves old identity at a newer revision. |
| `core/src/main/scala/lab/header/PraosCertificateState.scala:179,298` | Seed source and applied predecessor/header/context chain cannot be derived from current counters. |
| `core/src/main/scala/lab/header/PraosNonceEvolution.scala:91,226,308` | Seed source, predecessor nonce identity and previous successor-context identity are not recoverable from final nonce fields. |
| `core/src/main/scala/lab/header/PraosEligibility.scala:136,174` | Checked results do not retain the complete original context/protocol attribution and VRF inputs; threshold consistency alone is not identity derivation. |
| `ledger/src/main/scala/lab/ledger/ClusterTransition.scala:321,642,689,824` | Semantic state identity is reproducible given checkpoint/environment provenance, but historical head and successor environment depend on old head/revision/state/boundary evidence; undo preserves old head. |

Ordinary frozen inputs are reconstructible if their start contexts can be reconstructed
(`ConwayEpochBoundary.scala:368–402`). Post-boundary frozen inputs additionally require
the original preview identity (`:442–447`); HistoricalBoundary retains that preview.
These must remain explicit separate variants, including boundary records when rewards
are Absent. They do not repair missing stake, pulser or other component preimages.

`CoherentSequence.scala:1182–1194` drops old receipts during compaction. Current recovery
cloning reuses immutable certificate/nonce/eligibility/ledger objects and copies opaque
historical identities; exact Envelope reference authorization is process-dependent.
Serializing just hashes would silently replace that provenance with trusted input.

## Minimum next design choices

1. A bounded **replay-only** DTO can require complete seed provenance and every needed
   signal, rejecting states where compaction discarded any dependency. That is a narrower
   contract and cannot satisfy arbitrary compacted-anchor roundtrips.
2. Exact-ID compacted restoration needs an explicitly **controller-attested anchor**
   contract. Retain bounded, tagged identity preimages when states are created, recompute
   all reconstructible IDs during import, and separately authenticate the complete anchor
   content and claimed historical identities with a distinct import capability. An ID
   preimage containing a predecessor hash does not independently verify the discarded
   prefix. The controller trust assumption must be explicit, including certificate
   counters, eligibility evidence, ledger head and historical pulser chain identity.
3. Giving the restored anchor new identities avoids preserving unprovable historical IDs,
   but changes the requested exact-identity and continuation contract.

No raw `restore(id, fields)` setter or hidden reference registry was introduced. The
decision between these contracts precedes a safe canonical codec and its length/count/
integer/aggregate allocation checks. No independently decoded-byte roundtrip or malformed
byte test is reported, because no valid import contract has yet been implemented.

## Verification

All 11 focused tests passed, including the new counterexample. Focused command: `scalafmtAll`; `ledger/testOnly lab.ledger.ConwayRewardPulserSuite`.
Existing offline Docker image, private copied cache, network disabled, 2 CPUs, 2 GiB
memory/swap cap, 256 PIDs and 1200 MiB JVM heap. Log is excluded from Git at
`.cache/synthetic-recovery-tests.log`. Two independent read-only component audits reached
the same reconstruction limitation. No main checkout or other worktree was modified.
