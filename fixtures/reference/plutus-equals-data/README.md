# `equalsData` on maps

`verdicts.jsonl`: the reference verdict, and on success the CPU and memory spent, of the 27 scripts of
`EqualsDataCorpus` (reordered and repeated map entries, equal maps, maps inside lists and constructors, maps of maps, maps
as keys, and nesting 900 levels deep) in V1, V2 and V3 at protocols 9 to 11. Each script ends in `()` when
`equalsData left right` is `True` and in `error` otherwise; the expected value is never written down, only what the
reference did. Rows bind the script bytes and the cost model by SHA-256, as in `plutus-boundaries`.

Executed 2026-10-24 with `scripts/oracle/plutus/equals-data/equals_data.py` in the costing oracle
`scarab-plutus-costing-oracle:local` (`sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b`, built on
`scarab-plutus175-oracle:local`, `sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da`; plutus
1.70.0.0 at `507cab8cf6192e5143f12be5b2d3005a2928dc6d`; [`costing/README.md`](../../../scripts/oracle/plutus/costing/README.md))
with the budget of `PlutusBoundaryCorpus`. `DataEqualityReferenceSuite` replays every row.
