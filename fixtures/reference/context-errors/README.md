# Plutus context collection and diagnostic CBOR (#176)

These generated public fixtures retain original transaction bytes and reference results, with three
separate proof domains. No signing keys are included. All network submissions used an isolated
throwaway private Conway/protocol10 cluster on 2026-10-18; no public-network run was needed.

| Evidence | Cases | What was executed |
| --- | ---: | --- |
| `submissions/conway-pv10.json` | 24 | Complete original signed transactions submitted to official node11.1.3/CLI11.2.3.0: 12 included, 12 refused, including ten context errors and two validity-tag mismatches |
| Original-byte API inputs in `collection.json` | 66 | The same 24 transactions plus 42 retained Conway transactions from [V1 contexts](../README.md), decoded with their original annotations by the released ledger APIs |
| Derived API inputs in `collection.json` | 79 | Controlled changes to those inputs, using unchanged collection and encoding APIs; signatures are not revalidated, and these are not transaction-admission proofs |
| Certificate constructor encodings | 14 | Direct `NoRedeemer` certificate-purpose serialization controls attached to API rows; distinct from whether collection can reach that constructor |

The 145 API rows have 91 collection failures and 54 successful collections. Every failure retains the
reference `NonEmpty CollectError` CBOR and complete `ConwayUtxosPredFailure` CBOR. Successful rows
retain the reference request order by script hash. `provenance` binds source file, source case and
original transaction SHA-256; derived rows explicitly disclaim signature revalidation and ledger admission. Exact raw-body equality
is recorded: three witness-only probes preserve the source body, while 76 probes change it; neither
group asserts a signature-validity verdict.

Actual refusals retain both the first CLI verdict and raw `ApplyTxErr` obtained by submitting the
identical signed bytes over LocalTxSubmission. Admitted controls are broadcast unchanged to all three
private producers. Valid controls require the exact new output; invalid-tag controls require every
ordinary input to remain and every collateral input to disappear. Each effect must persist across
a shared tip and at least `k+1` further blocks. Setup has its own shared-chain confirmation. Partial
attempts are written before confirmation and cannot be mistaken for completed evidence.

The API runner supplies controlled protocol parameters/cost models, the explicitly retained UTxO,
and a fixed SystemStart0/500-slot epoch/0.1-second slot clock. It measures collection and encoding,
not a live clock, phase1 or ledger admission.

The API matrix covers all four governance-field distinctions and precedence, nonempty voting/proposal
anchors, all six script-purpose variants, multiple languages and missing cost models, missing
redeemers before/after translation failures, V2/V3 invalid redeemer pointers, and certificate payloads.
Noncanonical pool relay/metadata and proposal parameter-update bytes measure the reference's typed
diagnostic re-encoding. Original body bytes and transaction IDs remain unchanged by that projection.

Reference collection prepends successful requests and missing-redeemer errors. Once any error is
present, later resolved scripts skip cost-model/context preparation; later missing redeemers still
prepend errors. Cost models are resolved per prepared purpose, without a global model preflight.
The resolver filters absent script witnesses; `NoWitness` remains a reference constructor but is not
emitted through this collector. Phase1 missing-witness admission remains a separate rule. Broader CEK
failure/log aggregation is outside this context-collection boundary.

Reference source: cardano-ledger `f649f975`, byte-identical to the released files recorded in
`provenance.json`:

- `Alonzo/Plutus/Evaluate.hs:141–185`: merge/order and preparation.
- `Alonzo/Plutus/Context.hs`: closed `CollectError` constructors and CBOR.
- `Babbage/TxInfo.hs:249–302`: output source, redeemer pointer and wrapped errors.
- `Conway/TxInfo.hs:180–278`: governance payloads and exact error CBOR.
- `Conway/UTxO.hs`: required-purpose order; `Conway/Rules/Utxos.hs`: failure envelope.

The standalone runner compiles those unchanged released APIs with GHC9.10.3/Cabal3.14.1.1. The official
node executable uses GHC9.6. Package versions were checked against its embedded package IDs; archive
hashes, eight compared source hashes, full resolved package/version/source-hash inventory, compiler,
native base and fresh image identities are retained. This establishes same-source API behavior,
not a bit-identical rebuild of the official executable. Native dependencies follow the existing
[Byron reference recipe](../../../scripts/oracle/byron/full/README.md); no validation source is edited.

`ContextCollectionSuite` checks all API verdicts/order and both flags independently of phase1.
`ContextFailureCborSuite` checks every measured encoding and ten full raw context refusals.
`V1ContextSuite` separately replays complete actual submissions through phase2, LEDGER and BBODY.
The 20,000-element small-stack controls cover error collection/rendering/CBOR, distinct redeemer
resolution and anchored voting-procedure decoding. The snapshot format/fingerprint is unchanged.

See the [reproduction recipe](../../../scripts/oracle/plutus/context/README.md). `manifest.json` pins
the generated data; upstream licenses/notices remain in `fixtures/licenses/cardano-ledger-*`.
