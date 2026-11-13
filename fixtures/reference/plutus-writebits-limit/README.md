# `writeBits` at its input length limit

`requests.jsonl`: eight requests, one script each, `[(lam _ ()) [(builtin writeBits) #00…00 [] False]]` with an input
of 4,096 or 4,097 zero bytes, in V3 at protocol 10 (builtin semantics variant C) and at protocol 11 (variant E), and in
V2 and V1 at protocol 11 (variant D). Each request holds the script as the ledger stores it (the CBOR byte string
around the Flat program, version 1.1.0 in V3 and 1.0.0 otherwise) in hex, and the cost-model list of its language and
protocol (`PlutusBoundaryCorpus.costModel`). No request names a budget, so each ran within the harness's default of
2^60 CPU and 2^60 memory.

`verdicts.jsonl`: the reference's answer to each request, in the same order, as the harness wrote it:
`reference.admitted`, the `stage` (`success` or `evaluation`), the budget `spent` on success and on failure, the class
and constructor of a machine failure, its `detail`, and `logs`, which are empty because the harness evaluates in
`Quiet` mode.

In variants D and E the reference fails a 4,097-byte input in `writeBits` and takes a 4,096-byte one; in variant C it
takes both (`PlutusCore/Default/Builtins.hs` lines 2199-2214, `maximumInputLength` of `PlutusCore/Bitwise.hs` lines
81-82). A failing request spends the startup cost and the builtin's cost, 281,245 CPU and 613 memory: the reference
fails in the denotation, after the cost is charged, and the machine steps counted before it are not charged.
`WriteBitsLimitReferenceSuite` rebuilds every script with `FlatProgram`, checks its bytes and cost model against
the request, and requires Scarab's stage and spent budget to equal the reference's on every row.

## Provenance

Executed 2026-11-13 (UTC) with the generated-program harness of the Plutus oracle, which runs plutus-ledger-api
1.70.0.0's `deserialiseScript` and then `evaluateScriptRestricting` in `Quiet` mode, and on failure runs the same
restricting evaluation again through `evaluateTerm` (`PlutusLedgerApi/Common/Eval.hs` lines 228-241) to record the
budget spent. The harness source (`Main.hs`, SHA-256
`8c03e0538ff60a331a2172dcbc9a35329f71551aea22bca8fdce925011a69d77`) is identified by its digest; it is not part of
this fixture.

- Image: `scarab-plutus-generated-oracle:local`,
  `sha256:0ba57aed177d7a9370c31a858ab963581b9e8320b69e2d3cfb8925e88b951002`, built on `scarab-plutus175-oracle:local`,
  `sha256:b50784fa61a079153e78ad8b0216ea5d2c35d98f5255f6b920993011007123cb` (the parent oracle with the complete
  crypto backend; [`scripts/oracle/plutus/README.md`](../../../scripts/oracle/plutus/README.md)).
- Plutus 1.70.0.0 at `507cab8cf6192e5143f12be5b2d3005a2928dc6d`; the image build checks that `plutus-core`,
  `plutus-ledger-api` and `plutus-tx` are unchanged at that commit.
- `requests.jsonl` SHA-256 `572a97766ca6ab64b8e5aea0dafaaa0157e8b75b2dad7c5a123de867a45b1d4a`, `verdicts.jsonl`
  SHA-256 `ae5b14beef48e80bdc731d926f9c0df68a8de51662c69457f6b034eeefdba33d`.

To reproduce, give the requests to the harness on its standard input; the answers are byte for byte
`verdicts.jsonl`:

```sh
docker run --rm -i --label xyz.kd5ujc.scarab.kind=oracle scarab-plutus-generated-oracle:local \
  < fixtures/reference/plutus-writebits-limit/requests.jsonl > answers.jsonl
cmp answers.jsonl fixtures/reference/plutus-writebits-limit/verdicts.jsonl
```

The requests are synthetic and the verdicts are the output of the pinned reference evaluator; both are distributed
under this project's Apache-2.0 licence.
