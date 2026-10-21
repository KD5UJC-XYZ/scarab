# Pinned independent builtin-costing oracle

The same unchanged Plutus1.70.0.0 core/ledger API and complete crypto backend as the
[parent oracle](../README.md) measure all A–E ledger profiles independently. `Main.hs` differs only
in request budgets and immutable evaluation-context caching; no evaluator, cost model or crypto
implementation is patched. The harness's `containers` dependency is already in the pinned build.

The base image used was `scarab-plutus175-oracle:local`,
`sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da`.
The measured derived image is pinned in the fixture manifest. GHC9.10.3, O0 and complete crypto
are inherited; the library source commit is `507cab8cf6192e5143f12be5b2d3005a2928dc6d`.

In an isolated worktree, mount the checkout as `/work`, the shared dependency cache as
`/work/.cache`, use a private `SBT_GLOBAL_BASE=/tmp/sbt-global`, and export with Docker JDK:

```sh
bash scripts/sbtw 'plutus/Test/runMain xyz.kd5ujc.scarab.plutus.BuiltinCostingCorpus fixtures/reference/plutus-costing'
```

Build and execute the optional Haskell tool from the repository root:

```sh
docker build -f scripts/oracle/plutus/costing/Dockerfile -t scarab-plutus-costing-oracle:local .
python3 scripts/oracle/plutus/costing/run.py --evidence /tmp/scarab-plutus-costing-evidence
```

The Python driver fails on any tool error, missing/reordered observation, unexpected exact-budget
failure or one-unit-low success. Its reference budgets are `2^60` units per dimension. After measuring
each successful request it executes that request again at the exact budget and at each one-unit-low
boundary. Raw Haskell results stay outside the checkout. Ordinary Docker `check` needs no Haskell
installation: it reads compact retained verdicts, regenerates input hashes and tests the same boundaries.

Scope and full counts: [fixture README](../../../../fixtures/reference/plutus-costing/README.md).
