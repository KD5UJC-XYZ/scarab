# Leios testnet blocks and headers as the testnet's build reads them

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

`cases.jsonl` holds 84 lines, one case each, written by
`scripts/oracle/leios-testnet/leios_cases.py`:
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
- Leios headers alone (`kind` "header"), as ChainSync carries them inside their era envelope: the
  header of block 0 of the current network, and the forms of the
  [header decoding oracle](../header-decoding/README.md) (definite, indefinite, detached, body,
  version info, announcement and operational certificate in each form, a chunked issuer key,
  non-minimal heads, a version info major of 13, 14 and over a `Word32`, a body size or announcement
  size over a `Word32`, a VRF output of no bytes, a body of eleven fields).

The verdict (`accepted` and the complete `failure`) is what the build's decoders did with each:
41 accepted and 43 refused. Every detached form is refused, every indefinite one accepted. For an
accepted case the line records the header hash (`hash`), its size, the signed bytes (`signable`),
the header fields, and for a block its tag, body hash and size as the ledger computes them, each
transaction's id and validity flag (`txs`) and whether it carries a Leios or a Peras certificate.
`LeiosTestnetBlockReferenceSuite` requires the same verdict from `LeiosBlock.decode` (a block of
another tag from `Block.decode`) or `LeiosHeader.decode`, and the same values.
`provenance.json` records the revisions, the hashes of the sources read, of the harness, the
generator, its input and output, and of the corpus.

## Regenerating

Build the image as [the oracle README](../../../scripts/oracle/leios-testnet/README.md) describes,
then:

```sh
python3 scripts/oracle/leios-testnet/leios_cases.py --inputs /tmp/leios-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-leios-testnet:local /fixtures/leios-inputs.json > /tmp/leios-reference.json
python3 scripts/oracle/leios-testnet/leios_cases.py --inputs /tmp/leios-inputs.json \
  --reference /tmp/leios-reference.json --out fixtures/reference/leios-testnet-blocks/cases.jsonl
```

## Not covered here

- A transaction's parts are not decoded by Scarab beyond the four items of a transaction: the
  Dijkstra transaction rules are not implemented (#750; the `dijkstra` component, roadmap L1). The
  verdicts on the synthetic transactions turn on their framing only.
- No KES signature, VRF proof or Leios certificate is checked; the synthetic headers' keys and
  signatures are filler bytes.
