# Independent era-specific Plutus contexts (#288)

**Current: bounded context/preparation/evaluation API evidence, captured 2026-10-20.**

`matrix.json` contains 309 reproducible cases executed by unchanged Haskell ledger APIs at the actual
era type, not by Scalus translation and not by interpreting all eras as Conway. There are 98 original
signed transaction inputs (74 from `v1-context`, 24 from `context-errors`) and 211 explicitly marked
API-only derivations. Original submissions retain separate admission/refusal evidence in those corpora;
this API replay itself makes no signature or whole-ledger admission claim.

The measured matrix has 174 successful collections, 135 collection refusals, 214 complete requests,
189 successful evaluations with exact numeric budget pairs and 25 evaluation failures. A failed
restricting evaluation exposes no consumed-budget value, represented explicitly as `null`.

| Era / protocol | Languages exercised | Purposes measured |
|---|---|---|
| Alonzo / 5, 6 | V1 | spending, minting, certifying, rewarding; combined order |
| Babbage / 7, 8 | V1, V2 | spending, minting, certifying, rewarding; combined order |
| Conway / 9, 10, 11 | V1, V2, V3 | all six: the preceding four plus voting and proposing; combined order |

Voting/proposing under V1/V2 and inline/reference translation constraints are retained as measured
refusals, not removed from the denominator. Conway's V1 reference-script acceptance is retained.
Rich #176 probes add all anchored governance fields, certificate constructors, invalid/missing
redeemer ordering, mixed languages, missing models and normalization controls. New rows add
nonempty structured datums/redeemers, minted/burned multiassets, nonempty signers, nonzero system
start, 250ms slots, finite interval bounds, reference scripts and inline/witness datums. Each
successful row compares *every* request byte and count in order before comparing evaluation verdicts
and successful budgets. The full request includes arguments and complete contexts, so matching
script hashes alone cannot pass. Refusals compare collection stage and ordered closed error
categories; Conway refusals additionally compare the complete typed error CBOR. Historical full
Haskell error bytes remain retained without claiming historical wire payload parity. Diagnostic text
parity is excluded.

The independent observer's [recipe](../../../scripts/oracle/plutus/context-matrix/README.md),
`provenance.json` and `manifest.json` bind package/source pins, immutable image/executable, generator
and corpus hashes. Source/package pins are unchanged from cardano-node11.1.3 / Plutus1.70.0.0;
GHC9.10.3 differs from the official executable's GHC9.6. Public throwaway keys/signatures only;
no signing keys, production data or secrets. Thin observer/generation code: Apache-2.0; upstream
package notices/licenses are retained under `fixtures/licenses` and fetched source distributions.

This is a finite boundary matrix, not exhaustive proof over all transactions, parameter values or
certificate/governance combinations. Derived rows do not establish admission, collateral disposition,
UTXOW correctness, signature checking or BBODY acceptance. Those remain the signed reference corpora,
ledger suites and routine era-load scenario's job. Unsupported language/era combinations are omitted
because the language is unavailable there; they are independently covered by language availability
rules, not misrepresented as context translation failures. No diagnostic-string parity is claimed.
