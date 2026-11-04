# Leios testnet blocks, headers and transactions as the testnet's build reads them

Status: Current. Evidence for [#711](https://github.com/KD5UJC-XYZ/scarab/issues/711).

The Leios reference is the build the public Leios testnet runs (decided 2026-11-03 on #711): the
node of release `rc-2026w44`, cardano-node `b004fe0c`, with cardano-ledger
`9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c`, cardano-base `c1e1ed8d`, ouroboros-consensus
`2416493b` ([the oracle README](../../../scripts/oracle/leios-testnet/README.md) says how each was
established; `provenance.json` has them in full). In that build a block of tag 8 is a Dijkstra block
whose header is the Leios header, and a block of tag 7 a Conway block whose header is the Praos one
(`CardanoShelleyEras`, `Ouroboros/Consensus/Cardano/Block.hs:269-278`). At Dijkstra's decoder version
12 no list of a Dijkstra block is a `RecD` record: the block, its body, each transaction, the header,
its body, version info and announcement, the VRF certificate and the Leios certificate are read by
`decodeRecordNamed`, and each record a transaction can hold is read so from version 12. A list is
therefore definite with its count, or indefinite with its items before the break; one written
detached, an empty indefinite list with its fields after it, is refused. The envelope `[tag, block]`
is read by `enforceSize`, a definite list. The KES signature of a Leios header is over its body's
original bytes.

The 2026-11-04 corpus in `cases.jsonl` holds 623 lines, one case each, written by
`scripts/oracle/leios-testnet/leios_cases.py` and, for the transactions, `leios_tx_cases.py`:
- the testnet's blocks as BlockFetch carries them (`kind` "block"), of the network as restarted on
  2026-10-18 (`fixtures/leios/testnet/blocks-rc-2026w44.tsv`): blocks 0, 1 and 8,562 (empty), a
  certifying block and an announcing block with transactions, all accepted;
- block 0 holding two synthetic transactions, with one list written in another form: the envelope
  (indefinite, three items, tag 9, a trailing byte), the block, its header and the header body
  (indefinite, detached, wrong lengths), the block body (indefinite, detached, two and four items),
  the transaction list (indefinite, tagged 258), a transaction (indefinite, detached, three and five
  items, marked invalid, a validity that is not a boolean), the Peras certificate (bytes, chunked
  bytes, an integer);
- the certifying block with the Leios certificate indefinite, detached, its signature chunked, its
  signers with a wide length head, and signers of 8,192 and 8,193 bytes; and with a signature of 47
  and 49 bytes, off the curve and outside the subgroup (the compressed G1 vectors of
  `fixtures/leios/bls/cardano-base-serde-test-vectors`), not compressed, and the identity, which
  the build decodes (cardano-base `BLS12381/Internal.hs:476-491`: decompression and the subgroup
  check, no exclusion of the identity);
- a block of tag 7, a Conway block in this build: with the Praos header of the header oracle
  (accepted, read at Conway's decoder version 9) and with the current network's Leios header
  (refused);
- the empty block 0 of the current network with synthetic transactions whose output value
  `[coin, multiasset]` is definite, indefinite or detached, in an output list and an output map, a
  coin value, and two transactions of which the second holds a detached value;
- the empty block 0 with the transaction `[0, 0, null, true]` of #750, refused;
- transactions alone (`kind` "tx", #750), read by `decodeDijkstraTopTxInBlock` at decoder version 12
  as the block body reads each: the 40 transactions of block 64,853 of the restarted network
  (`announcing-with-txs`), and 496
  synthetic ones with one field changed at each decoder's boundary: each required body field
  missing, unknown, repeated and negative keys, every body key of a top-level transaction and of a
  sub-transaction, empty collections where the build refuses them, duplicates by value (inputs,
  certificates, proposals, owners, guards, map keys) and by hash (scripts, datums, sub-transaction
  ids), definite, indefinite, detached, tagged and chunked forms, outputs in each form with values,
  datums and reference scripts of each language, the multi-asset size bound (1,488 and 1,489 assets),
  every certificate tag with a pool's voting key (valid, null, malformed, off the curve, outside the
  subgroup, the identity), relays, votes, every governance action and every Dijkstra parameter
  update key with values at their bounds, witness sets (vkey, bootstrap, native and Plutus V1 to V4
  scripts, datums, redeemers as a map and as a list, repeated redeemer keys and reversed key order),
  and auxiliary data in each form;
- Leios headers alone (`kind` "header"), as ChainSync carries them inside their era envelope: the
  header of block 0 of the current network, and the forms of the
  [header decoding oracle](../header-decoding/README.md) (definite, indefinite, detached, body,
  version info, announcement and operational certificate in each form, a chunked issuer key, one
  after 244 empty chunks (1100 bytes, the retained testnet's `maxBHSize`, over 256 items) and one after 4000 (over
  4096 bytes), which no decoder counts (#749),
  non-minimal heads, a version info major of 13, 14 and over a `Word32`, a body size or announcement
  size over a `Word32`, a VRF output of no bytes, a body of eleven fields).

The verdict (`accepted` and the complete `failure`) is what the build's decoders did with each:
316 accepted and 307 refused (273 and 263 of the transactions). The record-form variants accept indefinite lists and refuse detached ones; the outer block envelope requires a definite list. For an
accepted case the line records the header hash (`hash`), its size, the signed bytes (`signable`),
the header fields, and for a block its tag, body hash and size as the ledger computes them, each
transaction's id and validity flag (`txs`) and whether it carries a Leios or a Peras certificate.
For an accepted transaction alone the line records its id (`txid`), its validity flag, the keys of
its witness set's script and datum maps (`scripts`, `datums`: hashes of each one's original bytes)
and its redeemers in key order (`redeemers`: purpose and index, original data bytes and execution
units, with the last value kept for repeated keys), and each sub-transaction's corresponding
fields (`subtxs`). `LeiosTestnetBlockReferenceSuite` requires the
same verdict from `LeiosBlock.decode` (a block of another tag from `Block.decode`),
`LeiosHeader.decode` or `DijkstraTx.inBlock`, and the same values.
`provenance.json` records the revisions, the hashes of the sources read, of the harness, the
generator, its input and output, and of the corpus.

## Regenerating

Build the image as [the oracle README](../../../scripts/oracle/leios-testnet/README.md) describes.
The maintained capture includes block 64,853; no extra `--blocks` input is needed. Then:

```sh
python3 scripts/oracle/leios-testnet/leios_cases.py --inputs /tmp/leios-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-leios-testnet:local /fixtures/leios-inputs.json > /tmp/leios-reference.json
python3 scripts/oracle/leios-testnet/leios_cases.py --inputs /tmp/leios-inputs.json --reference /tmp/leios-reference.json --out fixtures/reference/leios-testnet-blocks/cases.jsonl
```

## Not covered here

- No Dijkstra ledger rule is applied to a transaction (the `dijkstra` component, roadmap L1); the
  transaction cases compare decoding only. A Plutus script's program is not deserialised, as the
  build does not deserialise it when it decodes a block.
- No KES signature, VRF proof or Leios certificate is checked; the synthetic headers' keys and
  signatures are filler bytes.

## Additional decoder-bound check (2026-11-03, #750)

`DijkstraTxDepthSuite` constructs a transaction with a reference script containing 2,500,001
zero bytes (language tag 1), wrapped in tag 24. The transaction is 2,500,098 bytes; its SHA-256 is
`bf8465b9f67b969961a0a0ddcabc5ed303d51af44e9210eaac6b65b4d119a83d`. Run the
transaction's hex through this harness as a single input with `kind: "tx"`; the pinned reference
accepts it, with transaction id
`5509833325c071edf4f2c5968671cb3f9654c158330121acc1e6efd6c3af6692`. Scarab's suite
requires the same decode acceptance. This checks that embedded CBOR adds no byte budget; it does
not establish ledger acceptance or block-size validity. This generated case is outside the retained
corpus above, and its raw generated input is not published.
