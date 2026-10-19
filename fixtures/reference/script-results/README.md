# Multi-script result reference corpus

Generated API inputs and results for [I4 #195](https://github.com/KD5UJC-XYZ/scarab/issues/195).
Scarab's original fixture and thin oracle code are Apache-2.0; upstream package notices/licenses
are retained in [fixtures/licenses](../../licenses). Public transaction data contains public keys
and signatures only. No signing keys or operational node state is present.

`aggregation.json` contains 25 rows. `original-signed-v1-control` is byte-identical to the
`conway-pv10-v1-plain` admitted private-network transaction in
[the context corpus](../context-errors/README.md), with its original submission and inclusion evidence
retained there. This oracle reruns only its context/evaluation APIs; it does not resubmit it.

The other 24 rows are explicitly **API-only derived transactions**, covering all eight ordered
three-script pass/fail patterns in V1, V2 and V3 at protocol 10. Distinct scripts trace their unique
labels and return unit or explicit UPLC error. A deterministic nonce search places pass/fail scripts
in each required collection position. Signatures and script-integrity hashes are not revalidated
after changing the body/witnesses: these rows claim no ledger admission. `prepare.py` recreates the
exact inputs from the retained signed source.

Each `oracle` field is the unchanged released Haskell API output: complete passing/failing script
order, verbose logs, ordinary ledger Quiet logs, successful consumed budgets and exact reconstruction
contexts/validity-tag predicate CBOR. Failed consumed budgets are absent because the reference API
does not expose them. Full diagnostic prose is retained in the golden even though Scarab currently
uses its own typed-cause diagnostic prose; see the [as-built design](../../../docs/design/script-results.md).
The API oracle does not replace full UTXOW/BBODY admission or the CEK conformance corpus.

Reproduce and verify with the [oracle recipe](../../../scripts/oracle/plutus/aggregation/README.md).
`provenance.json` binds released archives, the pinned reference revision, image/compiler and wrapper
sources; `manifest.json` binds the retained files. Normal deterministic Scala tests use only these
files, with no Docker Haskell image or network requirement.
