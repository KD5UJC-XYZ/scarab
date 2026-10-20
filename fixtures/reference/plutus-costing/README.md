# Independent Plutus builtin costing matrix

Status: current; finite differential coverage for [#287](https://github.com/KD5UJC-XYZ/scarab/issues/287).

On 2026-10-20, unchanged Plutus 1.70.0.0 (`507cab8cf6192e5143f12be5b2d3005a2928dc6d`)
executed **55,740** original-byte API requests: 1,858 scripts, 15 legal language/protocol
profiles, and two cost-parameter sets. All decoder admissions, evaluation stages and **37,184**
successful exact CPU/memory budgets match Scarab. Each successful reference evaluation additionally
ran at its exact budget, CPU minus one, and memory minus one: **111,552** successful/failed restricting
observations, all replayed by `BuiltinCostingOracleSuite`. No case is excluded. Failures compare their
constructor-classified stage, not diagnostic text or consumed units after failure.

Inputs include all **797** pinned upstream `builtin/semantics` programs, without parser drops,
and 1,061 generated probes. Together they mention all **101 released builtins**. The pinned JSON
cost models additionally contain `assetCount`, `multiIndexArray`, and `policies`; these belong to
unreleased batch 7 and are unavailable in every tested profile. Upstream `Versions.hs` also places
`keepPolicies` and `dropPolicies` in that future batch; neither has a current cost-model entry.
They are outside this released-API claim, not passing observations.

`BuiltinCostingCorpus` pins the finite grid:

- V1 protocols 5–11; V2 7–11; V3 9–11. These exercise semantics A, B, C, D and E,
  independently executed with each profile's actual ordered parameters.
- Default pinned cost parameters and every parameter doubled (including signed coefficients),
  both accepted by Haskell's context construction. The negative control proves the suite rejects
  substitution of the wrong parameter set.
- All upstream semantics inputs cover integer, bytes/string, Data/list/pair, arrays, Value,
  signatures, hashes, BLS groups and pairing families, with success and runtime failures.
- Additional integer grid: all pairs of 1, 2, 3, 4, 7, 16, 40, 120 words across ten arithmetic
  and comparison builtins. This crosses below/on/above-diagonal branches.
- Additional bytes: lengths 0, 1, 7, 8, 9, 31, 32, 33, 63, 64, 65, 255, 256, 257, 1024
  across ten unary/hash builtins, four binary builtins (other operand 0, equal, and plus one),
  and Ed25519 message sizing.
- Additional Data/list/array/Value/string families: 0, 1, 7, 8, 9, 31, 32, 33, 64, 128
  elements for serialization/equality, UTF-8, list-to-array/length, Value conversion/union/containment.

Each program's result is ignored through a strict lambda returning unit, then Flat serialized.
This makes the same original bytes meaningful under V1–V3, including V3's unit-result requirement.
BLS constants that lack a Flat encoding become explicit uncompress applications in those bytes;
this is deliberate ledger-API coverage, **not** the original term/counting conformance profile.
The Haskell oracle evaluates precisely these exported bytes; no variant-E expected budget is reused.
Malformed Flat/parser boundaries remain covered by the separate existing boundary corpora.

`models.jsonl` stores each ordered model once. `scripts.jsonl` stores each script once.
`verdicts.jsonl` uses `<script-index>:<model-index>` IDs; successful rows record exact CPU and memory.
`manifest.json` binds sources, images and input/output hashes; `cost-shapes.json` inventories
every CPU/memory model shape by builtin and semantics variant, including explicitly unreleased names. `BuiltinCostingOracleSuite` regenerates
all input bytes, checks hashes, enforces profile and builtin completeness, and replays the observations.

These are synthetic/API evaluations, not signed transaction-admission or performance measurements.
The finite grid does not prove equality for every program, input size or admissible parameter vector.
Real crypto runs on Linux/amd64; platform availability remains a separate concern. No diagnostic
prose equality, network evidence, failure-consumed-budget equality, or future protocol is claimed.

Reproduce with the [retained oracle harness](../../../scripts/oracle/plutus/costing/README.md).
Upstream inputs are Apache-2.0; generated inputs and harness are Apache-2.0. See repository `NOTICE`
for general dependency notices; the original corpus has its own
[provenance](../../../plutus/src/test/resources/plutus-conformance-1.70.0.0/README.md) and
this directory retains the upstream `NOTICE`.
