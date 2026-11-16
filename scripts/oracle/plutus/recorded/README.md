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
the requests in the repository's JDK container, then run them on the host, both from the
repository's root:

```sh
./scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.ledger.RecordedRequestsMain .workspace/data/recorded-requests'
python3 -I scripts/oracle/plutus/recorded/run.py --evidence .workspace/data/recorded-requests
python3 -I scripts/oracle/plutus/recorded/run.py --verify-only
```

`RecordedRequestsMain` takes the directory for the requests the oracle reads, script and argument
bytes included (`requests.jsonl` there), and writes `corpus.json` (the release, the recorded case
files and their digests), `models.jsonl` and `requests.jsonl` by digest into
[the fixture](../../../../fixtures/reference/plutus-recorded-requests/README.md). The JDK container
mounts the checkout at `/work` and runs sbt there, so a directory given relative to the checkout is
`/work/<directory>` in the container and `<checkout>/<directory>` on the host: the same relative path
is then `run.py`'s `--evidence`. `.workspace/` is ignored by git; the requests and the raw answers
written there are not published.

`run.py` binds the requests the oracle reads to the fixture's digests, and resolves the image tag
once to its immutable ID, which the check, the run and the record all use. Before a request runs, the
image must run `/usr/local/bin/scarab-plutus-oracle` as its entry point, hold the repository's
harness `Main.hs`, have plutus at the pin with `plutus-core`, `plutus-ledger-api` and `plutus-tx`
equal to their commit (no change, staged or not, and no untracked or ignored file), and cabal must
find the executable up to date with those sources and identical to the entry point. The executable,
the harness's `.cabal` file and the `cabal.project` the image builds with must also be the ones the
fixture's `provenance.json` accepted (`imageFilesSha256`, `oracleBinarySha256`); a new build is
accepted only with `--accept-build`. `run.py` fails on any tool error or a missing or reordered
answer, runs the image without network, and writes `verdicts.jsonl`, `manifest.json` and
`provenance.json`. `--verify-only` checks a retained fixture without Docker: its files, the digests of
both runners and the harness, and the image binding are the ones the manifest and provenance record,
with one verdict per request in order. Ordinary `check` needs no Haskell: `RecordedRequestOracleSuite`
collects the requests again and replays the retained verdicts.

The [generated-program runner](../generated/run.py) runs the tag and resolves it again for its
record, and checks neither the image's sources nor its executable when it runs (its Dockerfile checks
the plutus source when the image is built); its fixture records its hash, so it is left unchanged.
