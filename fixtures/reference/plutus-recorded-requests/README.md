# Recorded chain script requests judged by the pinned evaluator

Status: current; the reference's verdicts on the script requests of recorded Preview and preprod
transactions, which the own evaluator must reproduce (issue
[#43](https://github.com/KD5UJC-XYZ/scarab/issues/43),
[ADR 0027](../../../docs/adr/0027-own-uplc-evaluator-as-a-correctness-backend.md)).

On 2026-11-16 unchanged plutus 1.70.0.0 (`507cab8cf6192e5143f12be5b2d3005a2928dc6d`), behind the
[generated-program oracle's harness](../../../scripts/oracle/plutus/generated/README.md), ran **253**
script requests through `deserialiseScript` and `evaluateScriptRestricting`: every script the ledger
collects (`Phase2.collect`) for the 49 recorded transactions of
[`fixtures/preview/plutus`](../../preview/plutus/README.md) (45) and `fixtures/preprod/plutus` (4),
with the script bytes, the `Data` arguments, the cost-model list and the protocol version the ledger
passes, within the redeemer's execution units, keeping the logs. One preprod transaction
(`tx-aee75c1c`) runs no script. `RecordedRequestOracleSuite` (module `ledger`) collects the requests
again from the same recorded cases, requires each to be the request the oracle judged, evaluates it
with the node's evaluator (`ScarabEvaluator`), and requires the same admission and stage, the same
class and constructor of the error, the same budget spent on success and on failure, and the same
log lines. No request is excluded; one the own evaluator gives no verdict on fails the suite.

| Language | Protocol (era) | Requests | Verdicts |
|---|---|---:|---|
| V1 | 7, 8 (Babbage), 10 (Conway) | 5 | 5 `success` |
| V2 | 7, 8 (Babbage), 10, 11 (Conway) | 200 | 198 `success`, 2 `budget` (`CekOutOfExError`) |
| V3 | 10, 11 (Conway) | 48 | 48 `success` |

By network, 90 requests are Preview's and 163 preprod's (158 of them from one transaction,
`tx-c70243bb`, with 158 redeemers); by era, 191 are Babbage's and 62 Conway's. The requests name 69
distinct scripts and 11 cost-model lists; 181 have logs (2,229 lines). The two failures are the
recorded `is_valid = false` transactions of Preview slots 1,156,258 and 1,160,386, whose redeemers
declare 10 steps and 10 memory units; the reference spends 100 of each before it stops.

## Files

- `corpus.json`: the release, how the requests were collected, and each recorded case file with its
  SHA-256, its case count and its request count.
- `models.jsonl`: the cost-model lists the requests use (language, protocol, list), each once.
- `requests.jsonl`: per request, its `id` (`<network>/<case>/<redeemer tag>.<index>`), `case` (the
  recorded file), `model` (index into `models.jsonl`), `language`, `protocol`, `scriptSha256` and
  `argumentsSha256` (the SHA-256 of the script bytes and of each argument's CBOR), `cpu` and `memory`
  (the redeemer's execution units). The script and argument bytes are not retained: the recorded
  cases hold them, and the arguments of `tx-c70243bb` alone come to about 18 MB in hex.
- `verdicts.jsonl`: per request, `admitted`, `stage`, `class` and `error` where there is an error,
  `cpu` and `memory` spent, and `logs`, as `scripts/oracle/plutus/generated/run.py` records them.
- `manifest.json`: the plutus pin, the source commit, the oracle image and its base image, the counts
  by network, era, protocol, language, stage and error, the SHA-256 of every file, of the requests
  the oracle read (`oracleRequestsSha256`, script and argument bytes included), of the harness and
  both runners, and of the raw answers.
- `provenance.json`: the record of [schema 1](../../README.md#provenance-records).

The suite checks the files against the manifest, that the ledger writes today the same
`corpus.json`, `models.jsonl` and `requests.jsonl`, and the same requests the oracle read (by
`oracleRequestsSha256`), so a change in a recorded case or in the collection fails it. As a negative
control, with every cost parameter doubled no success spends the recorded budget.

## Limits

- The requests are the ledger's own collection: this fixture judges the evaluation of the scripts,
  arguments, cost models and budgets Scarab builds, not whether cardano-ledger would build the same
  ones. That the pinned Haskell collection builds the same 253 requests is `RecordedScriptContextsSuite`'s
  to show ([recorded-contexts](../recorded-contexts/README.md)); other context corpora have their own
  oracles (`ContextMatrixSuite`, `V1ContextSuite`, `ScriptResultOracleSuite`,
  `BoundaryTransactionsSuite`).
- Nearly every request succeeds; the two failures run out of a budget of 10 steps and 10 units of
  memory, with 100 of each spent. The failure paths of the evaluator are covered by the generated
  scripts of [`uplc-generated`](../uplc-generated/README.md) and the boundary corpora, not here.
- Protocols 7 to 11 only; no PlutusV4 and no protocol 12. Diagnostic text (the reference's `detail`)
  is not compared; log lines are.

## Reproduce

With the [runner](../../../scripts/oracle/plutus/recorded/README.md): the requests are written by
`tools/runMain xyz.kd5ujc.scarab.ledger.RecordedRequestsMain <directory outside the checkout>`, the
verdicts by `run.py` in the image `scarab-plutus-generated-oracle:local`, and
`run.py --verify-only` checks the retained fixture without Docker. Raw oracle output is not
published.

## Provenance and licence

Derived from public Preview and preprod transactions already retained in this repository (see their
READMEs) and from the answers of the unchanged plutus 1.70.0.0 (Apache-2.0). No private data. The
repository's Apache-2.0 applies to the selection and packaging.
