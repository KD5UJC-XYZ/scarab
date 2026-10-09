# Native-script ingress fixture and reference comparison preparation

Base: published `3ae17ecff2dbf6e2f169e286912a233ce25b9bd1`.

Integration update: the main-owned signature-script acceptance case has since
passed; see [implementation and final evidence](native-submission-implementation.md).
The preparation and worker-test records below retain their original scope.
Scope: `isolated-conway-pv9-ada-native-v1`, opt-in and fixed for an owner lifetime.
This document and its helpers prepare fixtures and comparisons; they do not enable
the new API profile, prove live acceptance, or implement a second admission validator.

## Owned interfaces

- `scripts/native_script_submission_fixture.py`: pure command plans, complete
  funding-state comparison, a one-use reference funding submit guard, and a strict
  negative-oracle receipt comparator. It imports no subprocess/Docker interface,
  generates no keys, and does not execute anything when imported.
- `app/src/test/scala/lab/NativeScriptFixtureEvidence.scala`: Test-only checks using
  existing `NativeCoinUtxoMemPack`, `ConwayStake`, `NativeEndpointLedger`,
  `NativeScript` and `SignedTransaction`. It binds original body/witness spans and
  proves the supported kind-7 snapshot path with synthetic fixtures.
- Their adjacent Python and Scala suites exercise the bounded preparation and
  evidence contracts. Synthetic fixtures do not claim native execution.

Main retains profile/candidate/owner changes, the API client, pool and relay wiring,
controller lifecycle, all live slots and final publication. No shared controller,
validator, state owner or network file is changed here.

## Exact initial fixture

Reuse two existing disposable fixture payment keys, kept inside the reference
container's private temporary storage. No new credential generation is needed.
The first script is a single signature requirement for key 1:

```text
JSON: {"type":"sig","keyHash":"<28-byte key-1 hash>"}
CBOR: 8200581c<28-byte key-1 hash>
script credential: BLAKE2b-224(00 || original script CBOR)
enterprise testnet address bytes: 70 || script credential
```

The Python script planner and Scala `NativeScript.decode` agree on a fixed
synthetic vector. For an actual fixture, retain the CLI script hash/address-info
original and check its raw address against `enterpriseAddressHex`; do not infer
kind or credential from an `addr_test` prefix alone. `checked_script_address`
requires that original CLI address-info binding. The helpers accept CLI address
text only after the integration owner obtains it from the pinned reference tool.

Reference-only funding consumes one confirmed key-1 UTxO and produces:

1. 20,000,000 lovelace at the enterprise native-script address, output index 0.
2. Exact remaining change at the original key address, output index 1.
3. A 200,000-lovelace fixture fee.

The tested spend consumes the sole confirmed script output and creates only
key-payment scalar-ADA outputs: 10,000,000 to key 2 and 9,800,000 change to key 1,
with a 200,000-lovelace fee. It includes the original signature script and key-1
witness. Its validity lower bound is the held post-funding initial slot and its
upper bound is 2,000. No script output, collateral, reference input, datum,
multiasset, governance or auxiliary data is part of this first acceptance case.
These fixed fixture amounts still need the normal minimum-fee/minimum-output
checks against captured protocol parameters; the planner does not bypass them.

## Reference bootstrap insertion and submission separation

The existing ADA controller forbids **every** reference CLI submission. Do not
weaken that guard globally or invoke the historical `NativeRunner` accepted-spend
path. That older runner submits its spend through reference CLI and therefore
cannot prove this milestone.

Main should introduce an explicit fixture-only bootstrap step before the initial
native capture and before Scala starts:

1. Reach a common early epoch-zero point, stop producer processes, restart both
   nodes keyless and establish a frozen full-point bracket. Require the source
   bootstrap point before slot 100, leaving a strict preparation margin.
2. Query the **complete** UTxO and fee pot, record their exact originals and full
   point. Build the signature script and enterprise address using the pinned CLI.
   `funding_plan` requires exactly one source UTxO, no existing output at this
   script address, scalar ADA and sufficient change.
3. Execute `commands(...).fundingBuild` / `fundingSign`. Keep paths under
   `/work/native-ingress-fixture`; the existing signing key path must remain
   within `/work`. Extract original signed CBOR, independently obtain
   `NativeScriptFixtureEvidence.originals`, and compare its body ID with CLI txid.
4. Restart node 1 as the sole producer before submitting; leave node 2 keyless.
   Restarting after submission would discard the producer's volatile mempool.
   Through `ReferenceSubmissionGate.submit_funding`, reread and hash-check that
   exact funding original and submit **only** `/work/native-ingress-fixture/funding.signed`.
   The gate consumes its attempt before calling the executor. Failure or uncertain
   return aborts the invocation; no silent retry or fallback submission occurs.
5. Wait at most 15 seconds for the funding output to appear
   in confirmed UTxO, stop both nodes, restart keyless and regain a common point.
   The final post-funding point must still be epoch zero with `0 < slot < 300`.
   A missed window aborts rather than widening the geometry.
6. Query complete post-funding UTxO and fee pot under the frozen point. Run
   `funding_comparison` with the exact original identity receipt. It requires
   removal of precisely the funding input, precisely outputs 0/1, unchanged
   unrelated rows, exact addresses/coins, conservation, a fee-pot increase of
   200,000 and a later full point. This is **reference-only setup**;
   `scalaFundingValidated=false` and `fullLedgerValidated=false` remain explicit.
7. Seal the reference submit gate using that successful comparison receipt.
   Capture the exact native initial packet at this held post-funding point.
   Run `NativeScriptFixtureEvidence.bootstrap` on the packet's MemPack/whole
   UTxO, derived seed and debug original. It verifies credential/value binding,
   exactly one script UTxO and unchanged non-UTxO seed siblings, including fees.
   Enterprise kind 7 contributes no staking credential/instantaneous stake.
8. Construct/sign `/work/native-ingress-fixture/spend.signed` using `spend_command`
   and `commands(...).spendSign`. Reference tooling may construct/sign it, but
   `ReferenceSubmissionGate.execute` rejects any `submit` command. Copy only
   the bounded **public transaction original** into the submission exchange.
9. Start the new fixed-profile Scala owner and use the existing external loopback
   HTTP client. The tested spend enters solely through Scala HTTP → admission →
   pool → Scala TxSubmission2. Reference CLI forwarding is forbidden.

The gate is a direct-command guard around main's owned-container executor, not
an OS sandbox. The executor still owns deadlines, container identity, read-only
mounts, network isolation and cleanup. Its `read_original` callback decodes the
text envelope to unchanged transaction CBOR; no shell or host key path is used.
The gate seals only after a successful submit return and an exact-original,
complete-UTxO comparison receipt. It cannot turn an uncertain submit into success.

## Original evidence and final comparison

Retain the following in private external evidence, bound by role, SHA-256, byte
length and full capture point; user-facing review summaries are Markdown:

- Pinned binary/image/support-manifest identities, effective configuration and
  `TxSubmissionInitDelay=0` override/original receipt. Keep the existing deadlines;
  do not silently adopt the reference default 60-second initialization delay.
- Original native script CBOR and JSON; script credential; CLI address-info original.
- Original signed funding envelope and exact body/witness hashes, CLI txid and
  independent Scala identity; complete before/after UTxO, fee-pot originals and
  point brackets. Never export signing keys or provider-private credentials.
- The actual held post-funding initial native packet, complete MemPack UTxO,
  debug original, derived seed and their existing source/point manifests. A
  synthetic fixture packet is never substituted into a live run.
- Original signed acceptance transaction and exact body/witness receipts; API
  response with the fixed native profile and `fullLedgerValidated=false`;
  relay events; applied follower inclusion point and pool removal.
- Original body and witness spans from the **applied follower block**, checked
  with `inclusionOriginals`. A body-ID match alone cannot claim an identical
  witness envelope. Block inclusion may not retain the transaction's outer-array
  encoding; report span equality separately from full-envelope equality.
- Held-state native endpoint comparison against the applied follower state,
  using the existing complete endpoint ledger/protocol/governance comparison.
  The bootstrap helper is not a substitute for that final endpoint comparison.
- Exact owned cleanup/absence receipts and all failed evidence.

## Negative-reference oracle mechanics

Start with missing script, wrong script and unrelated signer. Each witness-only
negative must share the **exact body** and transaction ID with its positive
control; original witness envelopes must differ. Re-sign every later body or
validity-interval mutation. Keep this separate from the positive acceptance run.

`negative_reference_comparison` requires typed `Rejected` candidate evidence,
`ScopedDerived` control evidence, exact body/envelope identities, identical
pre-state SHA-256 and validation slot, plus a typed reference-oracle receipt
(`native-script-oracle-v1`) binding the exact negative original. The reference
must establish its evaluation slot and unchanged state. Local `Unsupported`,
resource rejection, transport failure or a nonzero CLI exit is insufficient.
No reference predicate-order agreement is inferred.

The old CLI-only negative receipts explicitly set
`referenceEvaluationSlotEstablished=false`; they cannot pass this comparator.
A source-pinned reference evaluator/exporter or a separately reviewed native
execution receipt supplying the actual evaluation slot is still required for
strict negative agreement. No such native exporter was built or run here.
An isolated CLI rejection can be retained as a weaker observation with that
limitation, but must not be labeled exact-slot oracle agreement.

Use a **separate disposable oracle fixture**, never submit negative candidates
through the positive acceptance run's reference gate. Permit at most three
negative attempts in one separately scheduled oracle slot, bounded before/after
state acquisition per attempt; the positive control is evaluated, not forwarded
through reference CLI as the acceptance transaction. Timelock/all/any/threshold
cases belong to the validator worker's deterministic corpus first and get their
own explicit reference case/slot binding before any wider live claim.

## Proposed exclusive devnet execution profile

- Main alone owns live execution. No live process/container was started here.
- One isolated fixture; existing node 11.1.3 / CLI 11.2.3.0 / testnet 11.1.1 pins,
  ledger PV9, 100 ms slots and 1,000-slot epochs. Reference network `none`;
  Scala/client join only that exact container namespace. No host/public ports.
- Reference: 2 CPU / 3 GiB; Scala: 1 CPU / 2 GiB; at most one helper/client:
  1 CPU / 2 GiB. Combined ceiling remains **4 CPU / 7 GiB**. Capture helpers and
  HTTP client are sequential, never additional concurrent allocations.
- Retain the existing overall **240-second operation + 30-second cleanup** budget.
  Funding inclusion has a 15-second sub-budget inside it. Initial point `<300`,
  exact endpoint acquisition and finite one-boundary follower bounds remain.
  If timing cannot fit, stop and return measured evidence; do not widen silently.
- Keep the existing 65,536-byte transaction, 8 ingress, 2 validation, 64 pool /
  4 MiB / 60-second retention and 2 relay leases / 30-second lease policies.
- Separate oracle invocation follows the same combined ceiling and exclusive
  scheduling; three negatives maximum, with a 30-second per-case sub-budget
  and a 240-second total operation ceiling. Actual oracle readiness remains gated
  on source-bound evaluation-slot evidence.
- Private caches and fresh owned roots only. Cleanup targets only invocation-owned
  resources. Preserve failures and existing worktrees. Never export keys, push,
  use a public network or spend real funds.

## Offline verification

Python command:

```text
python3 -m unittest discover -s scripts -p test_native_script_submission_fixture.py -v
```

Scala command:

```text
app/testOnly lab.NativeScriptFixtureEvidenceSuite lab.NativeCoinUtxoMemPackSuite lab.NativeEndpointLedgerSuite
```

Build uses a private worktree cache, Java 21, two active processors and a 1,500 MiB
heap; no native helper or cluster is part of these tests.

Recorded on 2026-10-09 UTC (Scala run completed at 18:13:40 UTC):

| Check | Result |
| --- | --- |
| Pure Python fixture/guard/oracle suite | 12 passed |
| New NativeScriptFixtureEvidenceSuite | 5 passed |
| Existing NativeCoinUtxoMemPackSuite | 8 passed |
| Existing NativeEndpointLedgerSuite | 7 passed |
| Scala total | 20 passed, zero failures/errors/skips |
| app Test scalafmt | completed; only the two new Scala files formatted |
| git diff --check | passed |

No production file changed, and no native/live test was run. Private cache and
ignored test reports remain in the isolated worktree; no raw log is tracked.

### Funding submit-status correction

The submit gate now requires an explicit `returncode` whose exact Python type is
`int` and whose value is zero. `None`, a missing status, a dictionary result,
Boolean/float/string/bytes/container statuses and nonzero integers cannot mark
submission successful or permit bootstrap sealing. Every attempted executor call
still consumes the sole submission attempt, including uncertain and failing results.
The positive fake now returns an explicit integer-zero result.

Focused follow-up: **13 Python tests passed**, including all absent/wrong-type
status cases, retry prevention and seal rejection. No Scala source changed;
the prior 20 Scala XML results remain the relevant unchanged regression evidence.
No native/live jobs were run.

Retained untracked execution log:
`local-evidence/native-fixture-submit-status-python.log`.
SHA-256: `b7d5e5cc2ee7be2956e0cc643a99ada32fdb0e1f9c1261e92de1a46999227f44`.
Command: `python3 -m unittest discover -s scripts -p test_native_script_submission_fixture.py -v`.
The log remains private/ignored; this Markdown records the review evidence.

### Pinned CLI nullable output field

The integrated fixture accepts `inlineDatumRaw` only when absent or JSON null,
as emitted by the pinned CLI's scalar-ADA output query. Non-null values of every
tested type and unrecognized fields still reject. The focused fixture suite now
has 14 passing tests. This representation correction adds no datum support.
