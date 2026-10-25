# Executed flat decoding boundaries

Reference verdicts for the probes of
[`FlatDecodeCorpus`](../../../plutus/src/test/scala/xyz/kd5ujc/scarab/plutus/FlatDecodeCorpus.scala),
checked by `FlatDecodeOracleSuite`. Source, tool, image and pins are those of
[`scripts/oracle/plutus`](../../../scripts/oracle/plutus/README.md): `deserialiseScript` and then
`evaluateScriptRestricting` of plutus-ledger-api 1.70.0.0 (Plutus
`507cab8cf6192e5143f12be5b2d3005a2928dc6d`), image
`sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da`, executed 2026-10-23.

`verdicts.jsonl` has one row per probe and language/protocol pair the probe runs at. It binds the
original script bytes and the canonical JSON cost-model list by SHA-256 and records decoder
admission, the stage (`success`, `decode`, `setup`, `scope`, `budget`, `evaluation`) and, for a
success, the budget the reference spent (CPU and memory). Budgets are compared exactly.

The probes cover `data` constants of 1,001, 1,003 (the fewest that Scalus 1.3.0's borer-based
`Data` decoder refuses) and 16,000 levels of nesting (a script of the last is just under mainnet's
16,384-byte `maxTxSize`), each ignored, returned and serialised, and two 1,003-level constants
compared; bytes after the CBOR item of a `data` constant (the reference's
`deserialiseOrFail` keeps the first item, `Codec/Serialise.hs` 134-143 of serialise 0.2.6.1); a
truncated `data` item; `string` constants that are not UTF-8; builtin tags 92 and 127; program
version components beyond 32 bits; variable indices of ten padded groups, of eleven padded groups,
of bit 63 and of bit 64 (`dWord64` admits ten groups of which the tenth holds bit 63 alone);
`data` constants with tag 2 and 3 big numbers behind the short head and behind `d8 02`/`d8 03`
(cborg's `peekTokenType` reads a big number only from `0xc2` and `0xc3`), with constructor tags
behind wide heads as controls; and the outer script byte string in definite, 8-byte-length and
indefinite form (`deserialiseScript` refuses the last with `ExpectedBytes`). The rows of these
probes were produced by the pinned image on 2026-10-24. The `filler-*` and `cbor-remainder*` probes run at protocols 5
to 11 (Alonzo, Babbage, Conway): the end of the flat program behind its filler (minimal, longer,
missing its 1 bit, ending inside a byte, followed by a byte) and a byte after the script's CBOR
byte string, alone and with a byte after the filler.

Reproduce from the repository root (Docker only):

```sh
./scripts/sbtw 'plutus/Test/runMain xyz.kd5ujc.scarab.plutus.FlatDecodeCorpus /work/target/flat-decode-requests.jsonl'
docker run --rm -i scarab-plutus-oracle:local < target/flat-decode-requests.jsonl > /tmp/scarab-plutus/flat-decode.jsonl
```

Each output line is joined with its request line: the script and cost-model hashes are computed
over the request's script bytes and its cost model written as compact JSON, and the budget is read
from the `ExBudget` the reference printed.
