# Unlifting bounds of the `ensurable` variants

`verdicts.jsonl`: the reference verdict, and on success the CPU and memory spent, of the 142 scripts of
`UnliftingBoundsCorpus` in the test tree, in PlutusV2 and PlutusV3 at protocol 11 (semantics variants D and E) and in
PlutusV3 at protocol 10 (variant C). Each script applies one of the 35 builtins that the `ensurable` branches of
`PlutusCore/Default/Builtins.hs` type with a `CInteger` or `CByteString` argument, with that argument at its unlifting
bound (`2^262143 - 1`, `-2^262143`, 65,536 bytes) or one past it (`PlutusCore/Default/Universe/Cardano.hs` lines 9-19),
and discards the value. Rows bind script bytes and cost model by SHA-256 as in `plutus-boundaries`.

Executed 2026-10-28 with `scripts/oracle/plutus/serialise-data/constr_index.py` (`--requests` from
`UnliftingBoundsCorpus.main`) in the costing oracle `scarab-plutus-costing-oracle:local`
(`sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b`, plutus 1.70.0.0 at `507cab8cf6192e5143f12be5b2d3005a2928dc6d`;
[`costing/README.md`](../../../scripts/oracle/plutus/costing/README.md)), with a budget of 2^60 CPU and 2^40 memory.

From protocol 11 every value past a bound fails the script and every value at a bound is taken; at protocol 10 none is
bounded. `consByteString` takes its first argument as a `Word8` in variants C and E, so there any of its integer probes
fails. `UnliftingBoundsSuite` compares Scarab's verdict and budget with every row (issue 498).
