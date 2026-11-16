# Recorded chain script requests judged by the pinned evaluator

The script requests the ledger collects for the recorded Preview and preprod transactions
(`fixtures/preview/plutus`, `fixtures/preprod/plutus`), judged by the
[generated-program oracle](../generated/README.md): unchanged plutus 1.70.0.0
(`507cab8cf6192e5143f12be5b2d3005a2928dc6d`) behind that oracle's harness, in its image
`scarab-plutus-generated-oracle:local`. Nothing in that harness, its image or its runner changes;
`run.py` here loads `../generated/run.py` as it is and adds what a recorded corpus needs.

## Requests and answers

A request is the generated-program oracle's, one per script the ledger would run
(`Phase2.collect`, in the era the case's protocol version gives), with:

- `script`: the script bytes the ledger passes to `deserialiseScript`;
- `arguments`: the `Data` arguments the ledger builds (datum, redeemer and context, or the context
  alone from V3), each the CBOR of the `Data` in hex;
- the cost-model list the ledger passes for the language (`models.jsonl`), and the protocol
  version of the case;
- `cpu` and `memory`: the redeemer's execution units, the budget the ledger evaluates within;
- `verbose`: true, so the logs are kept.

The answer is that oracle's: admission, stage, the class and constructor of an error, the budget
spent (on success and on failure) and the logs.

## Build and run (Docker only, from the repository root)

The image is built as the [generated-program oracle's README](../generated/README.md) says. Write
the requests in the repository's JDK container, then run them on the host:

```sh
./scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.ledger.RecordedRequestsMain'
python3 -I scripts/oracle/plutus/recorded/run.py --evidence <a directory outside the checkout>
python3 -I scripts/oracle/plutus/recorded/run.py --verify-only
```

`RecordedRequestsMain` writes `corpus.json` (the release, the recorded case files and their
digests), `models.jsonl` and `requests.jsonl` into
[the fixture](../../../../fixtures/reference/plutus-recorded-requests/README.md). Before it runs a
request, `run.py` checks that the image holds the current harness source and the pinned plutus
source unchanged; it fails on any tool error or a missing or reordered answer, writes
`verdicts.jsonl`, `manifest.json` and `provenance.json`, and keeps the raw answers outside the
checkout. `--verify-only` checks a retained fixture without Docker: its files and the digests of
both runners and the harness are the ones the manifest binds, with one verdict per request in
order. Ordinary `check` needs no Haskell: `RecordedRequestOracleSuite` collects the requests again
and replays the retained verdicts.
