# `sliceByteString` with wide indices

`verdicts.jsonl`: the reference verdict, and on success the CPU and memory spent, of the 124 scripts of
`SliceByteStringCorpus` in every ledger profile of the [costing matrix](../plutus-costing/README.md) (V1 at protocols
5 to 11, V2 at 7 to 11, V3 at 9 to 11: builtin semantics variants A to E), 1,860 rows. Rows bind the script bytes and
the cost model by SHA-256, as in `plutus-boundaries`.

The probes call `sliceByteString from len bs` on `#0102030405` and on the empty bytestring, with `from` or `len` at
2^31-1, 2^31, -2^31-1, 2^32-1, 2^32, 2^32+1, 2^32+2, -2^32, -2^32+1, 2^63-1 and -2^63 (inside Int64), and at 2^63,
-2^63-1, 2^64, 2^64+1, -2^64, -2^64+2, 2^100, 2^100+2 and -2^100 (outside it), the other index being small, plus
pairs where both are wide and five small controls.

- 64 **result** probes, both indices within Int64: the script ends in `()` exactly when the slice equals the
  `take len (drop from bs)` bytes written next to it. The reference succeeded on all of them in every profile, so the
  written bytes are the reference's result.
- 18 **32-bit bytes** probes: the same comparison with the bytes a 32-bit truncation of the indices would select,
  wherever those differ from the result. The reference ended every one in the script's `error`.
- 42 **out-of-range** probes, an index outside Int64, the result discarded: the reference failed every one in
  `sliceByteString` itself (the machine's cause is the builtin application, not the script's `error`). It unlifts both
  indices as an `Int64` and fails out of range (`PlutusCore/Default/Universe.hs` `readKnownAsInteger`, `ReadKnownIn
  DefaultUni term Int`; `PlutusCore/Default/Builtins.hs` `SliceByteString`).

Inside Int64 the budget of a successful probe depends only on the size of the bytestrings, so the budget alone cannot
tell a truncated index from the reference's; the verdicts of the result, 32-bit bytes and out-of-range probes do.

Executed 2026-10-25 with `scripts/oracle/plutus/equals-data/equals_data.py --out
fixtures/reference/plutus-slice-bytestring/verdicts.jsonl` in the costing oracle `scarab-plutus-costing-oracle:local`
(`sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b`, built on `scarab-plutus175-oracle:local`,
`sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da`; plutus 1.70.0.0 at
`507cab8cf6192e5143f12be5b2d3005a2928dc6d`; [`costing/README.md`](../../../scripts/oracle/plutus/costing/README.md))
with the budget of `PlutusBoundaryCorpus` and the cost parameters of `BuiltinCostingCorpus`.
`SliceByteStringReferenceSuite` replays every row.
