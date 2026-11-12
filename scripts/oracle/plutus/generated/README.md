# Pinned evaluator for generated UPLC programs

The oracle of the own evaluator's generated and fuzzed terms ([ADR 0027](../../../../docs/adr/0027-own-uplc-evaluator-as-a-correctness-backend.md)):
the unchanged Plutus 1.70.0.0 core and ledger API with the complete crypto backend, as in the
[parent oracle](../README.md), behind a harness derived from the [costing oracle's](../costing/README.md).
No evaluator, cost model or crypto implementation is patched; the Dockerfile checks that the
pinned source (`507cab8cf6192e5143f12be5b2d3005a2928dc6d`) is unchanged under `plutus-core`,
`plutus-ledger-api` and `plutus-tx`.

## Requests and answers

A request is the costing oracle's (`id`, `language`, `protocol`, `script`, `costModel`, and an
optional `cpu` and `memory`, `2^60` each when absent) with two more optional fields:

- `arguments`: the script's arguments, each the CBOR of a `Data` in hex, applied in order as
  `mkTermToEvaluate` applies them (`PlutusLedgerApi/Common/Eval.hs:107-130`). Default: none.
- `verbose`: whether the logs are kept (`Verbose`), `false` by default (`Quiet`).

The answer keeps the costing oracle's `reference.admitted`, `reference.stage` (`decode`, `setup`,
`scope`, `budget`, `evaluation`, `success`) and `reference.detail`, so the requests of the main and
costing oracles are answered as before. It adds:

- `spent`: the budget the machine spent, whenever it ran (a success, `budget`, `evaluation`), also on
  failure. `evaluateScriptRestricting` returns no budget on failure, so the harness runs the same
  restricting evaluation again through the exported `evaluateTerm` (`Eval.hs:228-241`), whose report
  holds the final budget whatever the result, and records `budget - final` as the API does on success
  (line 274). `mkTermToEvaluate` is not exported, so the harness applies the arguments to the
  deserialised term itself, and only after the API's own run has passed its version and scope checks.
  A difference between the two runs (a success's budget, or the class and constructor of a failure) is
  a tool error. The counting mode is not used: it has no limit, so it never returns on a program that
  does not end.
- `class` and `error`: for a failure of the machine, `structural` or `operational` and the constructor
  of its `MachineError` or `CekUserError`; otherwise no class and the constructor of the ledger's
  `EvaluationError` (`CodecError`, `DeBruijnError`, `InvalidReturnValue`).
- `logs`: the `LogOutput` of the evaluation, empty unless `verbose`.

## Build and run (Docker only, from the repository root)

```sh
docker build --label xyz.kd5ujc.scarab.kind=oracle -f scripts/oracle/plutus/generated/Dockerfile -t scarab-plutus-generated-oracle:local .
```

The image derives from the parent oracle's image, `scarab-plutus175-oracle:local`, as the costing
oracle's does, and compiles only the harness (`-Wall -Werror -O0`, `--offline`); the image ids used
are in the fixture's `manifest.json`. Then write the requests in the repository's JDK container and
run them:

```sh
./scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.uplc.GeneratedOracleCorpusMain fixtures/reference/uplc-generated'
python3 -I scripts/oracle/plutus/generated/run.py --evidence <a directory outside the checkout>
```

The JDK container has no Docker, so the requests are written there and run on the host. `run.py`
fails on any tool error or a missing or reordered answer, writes `verdicts.jsonl` and
`manifest.json`, and keeps the raw answers outside the checkout. Ordinary `check` needs no
Haskell: `GeneratedOracleSuite` replays the retained verdicts.

Before the fixture was recorded, the harness answered 1,200 requests of the costing oracle (150 of
its scripts under 8 of its 30 cost-model lists, in that oracle's request shape) with the admission,
stage and successful budgets that oracle retained.

Scope, counts and what the fixture does not claim: [fixture README](../../../../fixtures/reference/uplc-generated/README.md).
