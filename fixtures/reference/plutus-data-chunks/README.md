# Native Plutus Data byte-string chunk boundaries

Twelve local probes were executed on 2026-10-20 with `cardano-cli` 11.2.3.0 bundled in
cardano-node 11.1.3, image `cardano-reference-11.1.3:local`, identity
`sha256:63bb366ad306366653fd9dfb23e16cceadd1ab68de6556c1e761358a5364bb1e`.
No node or keys were used. `cases.tsv` retains each exact CBOR input, native acceptance,
accepted script-data hash and the native refusal reason.

The reference is Plutus 1.70.0.0 at
[`507cab8c`, `PlutusCore/Data.hs:209-265`](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-core/plutus-core/src/PlutusCore/Data.hs#L209).
`decodeBoundedBytes` limits each original definite chunk to 64 bytes, including the byte payload
of positive and negative bignums. An indefinite byte string can have a larger joined payload.
The cases cover plain bytes and tags 2 and 3, each with definite lengths 64 and 65, one indefinite
chunk of 65, and indefinite chunks of 64 and 1. Each payload byte is `01`.

To reproduce a row, decode its `cborHex` column to a binary file, then run:

```sh
docker run --rm --entrypoint cardano-cli \
  -v /tmp/scarab-data-probes:/data:ro cardano-reference-11.1.3:local \
  latest transaction hash-script-data --script-data-cbor-file /data/input.cbor
```

Accepted inputs exit 0 and print the recorded hash. Refusals exit 1 with
`ByteString exceeds 64 bytes`. These are parser and hash results, not transaction-admission
verdicts. The generated inputs are Apache-2.0 project fixtures; reference notices remain in
[the fixture licenses](../../licenses/).
