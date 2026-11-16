# Pinned evaluator for generated UPLC programs at plutus 1.71.0.0

The [generated-program oracle](../generated/README.md) built on plutus 1.71.0.0
(`261766ad1d582bce8c86bad1af7a3b0e5355b21c`, the plutus of the Leios testnet build and of cardano-node
11.2.1), the oracle of the own evaluator at protocols 5 to 12
([ADR 0027, amendment of 2026-11-15](../../../../docs/adr/0027-own-uplc-evaluator-as-a-correctness-backend.md#amendment-2026-11-15-plutus-17100-and-protocol-12)).
No evaluator, cost model or crypto implementation is patched; the Dockerfile checks that the pinned
source is unchanged under `plutus-core`, `plutus-ledger-api` and `plutus-tx`.

The requests and answers are the 1.70.0.0 harness's, with two changes that 1.71.0.0's API makes:

- `language` may be 4: the request runs through plutus's own PlutusV4 functions (`deserialiseScript`,
  `evaluateScriptRestricting` and `mkEvaluationContext` of `PlutusLedgerApi.V4`). At protocol 12
  these admit and evaluate as the PlutusV3 ones, whose names and variant (G) they share, which is how
  cardano-ledger 9df9f98c runs a PlutusV4 script; below 12 they refuse the language.
- `evaluateTerm` takes the program's version (`PlutusLedgerApi/Common/Eval.hs:228-242`), which picks
  the caser from protocol 12. The harness passes the deserialised program's version, as
  `evaluateScriptRestricting` does (lines 270-276), so its second run is the API's.

## Build and run (Docker only, from the repository root)

The parent image is the [parent oracle's](../README.md) Dockerfile built at 1.71.0.0, then the
harness on top (`-Wall -Werror -O0`, `--offline`):

```sh
docker build --label xyz.kd5ujc.scarab.kind=oracle \
  --build-arg PLUTUS_COMMIT=261766ad1d582bce8c86bad1af7a3b0e5355b21c \
  -f scripts/oracle/plutus/Dockerfile -t scarab-plutus171-oracle:local .
docker build --label xyz.kd5ujc.scarab.kind=oracle \
  -f scripts/oracle/plutus/generated-171/Dockerfile -t scarab-plutus171-generated-oracle:local .
```

The parent build compiles plutus and its dependencies from source and took about ten minutes on a
16-core host; the image ids used are in the fixture's `manifest.json`. Then write the requests in the
repository's JDK container and run them on the host:

```sh
./scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.uplc.GeneratedOracleCorpusMain fixtures/reference/uplc-generated-171 43080001 200 2 1.71.0.0'
python3 -I scripts/oracle/plutus/generated/run.py --release 1.71.0.0 --evidence <a directory outside the checkout>
```

The fifth argument of `GeneratedOracleCorpusMain` selects the corpus of a release
(`GeneratedCorpus.Plutus171`: the 15 profiles of protocols 5 to 11 and V1 to V4 at protocol 12), and
`run.py --release` the image, pin, harness and fixture of that release. Ordinary `check` needs no
Haskell: `GeneratedOracle171Suite` replays the retained verdicts.

Scope, counts and what the fixture does not claim: [fixture README](../../../../fixtures/reference/uplc-generated-171/README.md).
