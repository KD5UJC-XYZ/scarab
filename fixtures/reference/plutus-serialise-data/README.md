# `serialiseData` of constructor data with a wide index

Executed 2026-10-24 in the pinned oracle image `scarab-plutus175-oracle:local`
(`sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da`, plutus 1.70.0.0 at
`507cab8cf6192e5143f12be5b2d3005a2928dc6d`; recipe in
[`scripts/oracle/plutus/README.md`](../../../scripts/oracle/plutus/README.md)).

- `vectors.tsv`: for `Constr i []` with `i = +-(2^bits - 1)` and bits 65, 72, 512, 513, 600, 1024, the reference
  `serialiseData` bytes and their `sha2_256`, written by
  [`serialise-data/SerialiseData.hs`](../../../scripts/oracle/plutus/serialise-data/SerialiseData.hs) (the denotation of the
  builtin, `BSL.toStrict . serialise`). The last two columns are the bytes and digest of the same data with the index written
  through the chunking integer path of `Data.I`, which differ from the reference from 513 bits.
- `verdicts.jsonl`: the real reference verdict of 18 scripts per language and protocol (V2 and V3, protocols 9 to 11)
  that hash `serialiseData (constrData i [])` and compare with a constant: the reference digest (success expected) and,
  from 513 bits, the digest of the chunked variant (evaluation failure expected). Rows bind script bytes and cost model by
  SHA-256 as in `plutus-boundaries`. `SerialiseDataCorpus` in the test tree generates the scripts.

From protocol 11 the reference restricts the `constrData` index to an unsigned 64-bit integer (`Builtins.hs`,
`ConstrData`, the `ensurable` variants D and E), so every wide-index script fails there.

- `indices.tsv`: the reference `serialiseData` bytes of `Constr i []` and `Constr i [I 1]` for i = -1, -2^64, -2^600,
  2^64-1 and 2^64, written by the same tool with `--indices`.
- `constr-index-verdicts.jsonl`: the reference verdict, and on success the CPU and memory spent, of the 35 scripts of
  `ConstrIndexCorpus` (seven per index: `serialiseData` against both byte strings, the index from `unConstrData`, data rebuilt
  from `unConstrData`, `equalsData` with itself and with the next index, `chooseData`) in V2 and V3 at protocols 9 to 11.
  Executed 2026-10-24 with `scripts/oracle/plutus/serialise-data/constr_index.py` in the costing oracle
  `scarab-plutus-costing-oracle:local` (`sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b`, built on the
  image above; [`costing/README.md`](../../../scripts/oracle/plutus/costing/README.md)) with the budget of
  `PlutusBoundaryCorpus`; two runs gave identical raw output. Negative indices succeed at protocols 9 and 10 and fail at 11;
  2^64-1 succeeds at 11, 2^64 fails there.
