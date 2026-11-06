# Multi-script Plutus result aggregation

**Status: Current, as built for evaluation and reconstruction.**
[I4 #195](https://github.com/KD5UJC-XYZ/scarab/issues/195) is complete for v0.2; exact diagnostic wording
is required and incomplete under [#796](https://github.com/KD5UJC-XYZ/scarab/issues/796), following the
[diagnostic-parity decision](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity).

`Phase2.run` evaluates every collected request in collection order, including scripts after a proven
script failure. `Report` retains each redeemer, original script/request, typed `EvalResult`, actual
backend units and trace output. `Outcome.Failed` retains a nonempty ordered collection of all failures;
`Outcome.Passed` means every result succeeded. Both serial BBODY and prepared-plan landing use this
same interpreter and `TxChecks.checkedPhase2`. Observations land in transaction order; ordinary
checks, declared execution-unit caps, validity flags, collateral and original signed bytes keep their
existing rules. Script counters count every evaluation in an admitted transaction, including scripts
after a failure in a transaction correctly advertised as invalid.

A backend outage supplies no complete verdict, even after an earlier proven script failure. It returns
typed `Unavailable` with its completed prefix, for either validity flag. An unexpected implementation
defect retains its original exception; prepared workers return it as a value and the owner lands it
in transaction order. Later transactions still cannot overtake earlier transaction refusals. No
error text controls any decision.

The unchanged reference is cardano-ledger revision
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, released ledger-alonzo 1.16.0.0 and ledger-core 1.21.0.0,
with Plutus 1.70.0.0. Its
[`evalPlutusScriptsWithLogs`](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Evaluate.hs#L191)
combines every script using the
[`ScriptResult` semigroup](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs#L174).
It retains successful scripts and all failures in order. The ordinary ledger runner calls the VM in
`Quiet` mode, so aggregate ledger trace logs are empty; the verbose evaluation API exposes ordered
traces. Scarab retains its evaluator's verbose traces as diagnostics. Successful actual units match
the independent API; that API exposes no consumed-unit value on a failed evaluation. Backend-reported
failure units are retained, without claiming independent unit parity for those failures. Declared
budgets, rather than consumed units, remain the transaction/block resource limits.

At the node-to-client wire boundary, a typed failed request reconstructs the reference's exact
`PlutusWithContext` CBOR: protocol version, language and unchanged script bytes, arguments, declared
budget and cost model. The failure-description payload is Base64 of those bytes. All 73 requests in
the retained corpus match exactly, including all V1/V2/V3 arguments and models. Unexpectedly passing
invalid-tag failures match the complete reference UTXOS predicate bytes. A failing valid-tag predicate
retains every failure and matching reconstruction payload. `ScriptResultWireSuite` compares each of
the 25 native aggregation rows as complete validity-tag predicate bytes, including explicit UPLC error
descriptions. The separate context/refusal controls also compare whole outputs.
Other CEK/evaluation-error descriptions still use provider text and are incomplete under
[#796](https://github.com/KD5UJC-XYZ/scarab/issues/796); those fallbacks do not establish conformance.

[The complete diagnostic plan](diagnostic-parity.md) requires lossless typed evaluator diagnostics,
full reference document/term/error printers, independent native observations and complete failing-tag
predicate bytes. Budget/scope failures and historical context variants remain required coverage.
No text classifies a failure or substitutes for its typed cause. API-derived cases keep their narrower
claim; signed native submissions establish wire behavior separately. Presentation changes must retain
scheduling, admission rules, declared budgets, stored formats and ownership of the CEK machine (#43).

The [oracle recipe](../../scripts/oracle/plutus/aggregation/README.md) and
[fixture provenance](../../fixtures/reference/script-results/README.md) distinguish one original signed
reference-admitted control from 24 API-only transaction derivations. The latter cover all eight
pass/fail patterns in three scripts for each language, including a failure at each execution position,
multiple failures and ordered trace labels. They do not claim signature revalidation or whole-ledger
admission. `ScriptResultOracleSuite` compares the actual VM reports to the released APIs.
`ScriptResultBlockSuite` builds independently signed synthetic BBODY controls for both flags, compares
serial/prepared state and observations and checks late unavailability/defects. Those synthetic controls
also make no signed reference-admission claim. `ScriptResultWireSuite` checks original requests and
reconstruction payloads against the reference bytes. The wide-redeemer small-stack regression now
requires the full suffix to execute after a failure.

## Accepted gates

This is historical evidence from 2026-10-19. Its then-applicable wording waiver was superseded by
the [2026-11-04 decision](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity); the original measured scope below is unchanged.

Level 3, 2026-10-19, source `63a629bc` (runtime change `ea5c4283`). Docker
`scripts/sbtw check node/runtimeClasspathFile` passed 4,921 tests with 16 skips, zero failures/errors,
including fuzzing and scalafix. All 220 Python tests passed. The unchanged-reference replay reproduced
25 complete outputs: 73 request/context encodings, 37 required numeric successful-budget pairs,
36 failures with explicit API absence of consumed budgets, ordered failure lists and trace output.
The runner rejects failed builds, stale embedded wrapper/dependency sources and stale executables;
its output manifest binds those hashes and the immutable image to the corpus/output digests.
Accepted oracle image: `7ad48d860b7fe00bfee3227a485b97e379c4236318cf41052c766ec0baf628c9`;
executable SHA-256: `3090e2ab0b8d06d664fe8a3f8351c7312947d763f03a17c69117a111914e051a`.

`python3 scripts/devnet.py --name scarab-195-e2e e2e` passed: 1,650 block applications through all
seven eras to Conway epoch 17, all 66 ledger comparisons equal, three successful Plutus stages and
zero validation/fatal/reconnect/starvation events. The reference CLI cannot query a Byron ledger;
the Byron UTxO is compared through Shelley entry.

`python3 scripts/devnet.py --name scarab-195-ntc ntc` passed: 17 rounds and 932/932 implemented-query
comparisons under the existing ledger-state masks, all 20 refusal controls equal as raw bytes and
CLI text, 76 ChainSync block comparisons, and 205 comparable monitor snapshots (67 nonempty).
Valid submission reached a block; the correctly invalid-tagged submission took collateral while
retaining its spending input. The 23 ordinary CLI command comparisons matched. The then-existing #77
limits were explicit: raw debug-ledger bytes differ under the declared `NonMyopic`,
`SnapshotNumDelegators`, `VrfKeyHashes` and `RewardUpdate` masks; CLI `ledger-state` was 0/1;
`GetLedgerPeerSnapshot`, `GetNonMyopicMemberRewards` and `GetRewardInfoPools` were unimplemented.
These masks and exclusions were unchanged; the count is not a claim of 932 raw-byte-equal answers.
Later query implementations and unmasked runs are recorded separately in the
[compatibility evidence](../compatibility.md) under #285; they do not change this historical receipt.

Both scenarios used reference 11.1.3 image `74e849e1b68e` and JDK 21.0.11 image `c90140def52d`.
Their 10,782-file frozen runtime inventories exactly matched the checked compilation:
`e5bb119fc4eb7db8bd6eab1820ccd85874fbdc0c70bf2a9fe49f0bb024f33eed`.
They ran sequentially under shared-host coordination. All owned cluster/build containers were
removed afterward. No persisted format changed. At that date, full Haskell failing-tag diagnostic prose was
optional under #208; matching reconstruction bytes did not establish full failing-predicate byte parity.
